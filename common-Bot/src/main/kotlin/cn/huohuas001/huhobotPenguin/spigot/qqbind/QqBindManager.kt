package cn.huohuas001.huhobotPenguin.spigot.qqbind

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ 绑定管理器：QUUID 索引、绑定码、黑名单、签到数据、AI 对话上下文。
 *
 * 数据布局（插件数据目录）：
 * ```
 * KERONGPenguin/
 * ├── QUUID/
 * │   ├── index.yml           # 索引：“玩家名[UUID]” → QUUID
 * │   ├── <QUUID>.yml         # 单个玩家的主文件（一人一文件，全部数据都在这里：
 * │   │                        # qq(OpenId) / playerName / playerUuid / 群昵称 / 签到 /
 * │   │                        # 待补金币 / stats 统计节 —— 1.5.3.1 覆盖版合并存储）
 * │   ├── skip.yml            # 免验证名单
 * │   └── blacklist.yml       # QQ 黑名单
 * ├── ai-context.yml          # AI 对话上下文开关
 * └── logs/qq/qq-bind-*.log   # 绑定日志（超 1GB 轮转）
 * ```
 *
 * 1.5.3.1 覆盖版：不再有任何子目录 / 第二套 ID 键 —— 统计（stats 节）与
 * OpenId 昵称（nickname / lastSeenAt）全部写入玩家自己的 QUUID 主文件；
 * 同名玩家 UUID 不同 = 不同 key = 不同 QUUID = 两个人，互不串数据。
 */
class QqBindManager private constructor(private val plugin: JavaPlugin) {

    /** 待确认的绑定码。 */
    class PendingBind(val playerName: String, val playerKey: String, val quuid: String, val expireAt: Long)

    /** AI 对话消息。 */
    class ChatMessage(val role: String, val content: String)

    private val quuidFolder: File
    private val indexFile: File
    private val skipFile: File
    private val logFile: File
    private val contextFile: File
    private val blacklistFile: File

    /** 玩家名[UUID] → QUUID 索引（内存镜像）。 */
    private val nameToQuuid = ConcurrentHashMap<String, String>()

    /**
     * 玩家 UUID → QUUID 反查索引（内存镜像，1.5.3.1 覆盖版）：
     * 由 index.yml 的 “玩家名[UUID]” 键与主文件的 playerUuid 字段共同构建，
     * 统计存储按玩家 UUID 定位主文件时 O(1) 命中，无需扫目录。
     */
    private val uuidToQuuid = ConcurrentHashMap<String, String>()

    /**
     * QQ OpenId → QUUID 反查索引（内存镜像，1.5.3.1 覆盖版）：
     * 启动时扫描全部主文件的 qq 字段一次性构建；绑定 / 解绑时同步维护。
     * findQuuidByQq / findPlayerByQq 从逐文件磁盘扫描降为 O(1)。
     */
    private val openidToQuuid = ConcurrentHashMap<String, String>()

    /** 绑定码 → 待确认绑定。 */
    private val pendingCodes = ConcurrentHashMap<String, PendingBind>()

    /** 免验证名单。 */
    private val skipSet = ConcurrentHashMap<String, Boolean>()

    /** AI 对话上下文（全局键）。 */
    private val aiContext = ConcurrentHashMap<String, MutableList<ChatMessage>>()
    private val aiContextEnabled = ConcurrentHashMap<String, Boolean>()

    /** QQ OpenId → 展示名 黑名单。 */
    private val blacklist = ConcurrentHashMap<String, String>()

    init {
        val dataFolder = plugin.dataFolder
        if (!dataFolder.exists()) dataFolder.mkdirs()
        quuidFolder = File(dataFolder, "QUUID")
        if (!quuidFolder.exists()) quuidFolder.mkdirs()
        indexFile = File(quuidFolder, "index.yml")
        skipFile = File(quuidFolder, "skip.yml")
        logFile = File(dataFolder, "logs/qq/qq-bind.log")
        contextFile = File(dataFolder, "ai-context.yml")
        blacklistFile = File(quuidFolder, "blacklist.yml")
        loadIndex()
        loadSkip()
        loadBlacklist()
        loadAiContext()
        val boundCount = buildReverseIndexes()
        logQuiet("===== QqBindManager 已就绪 (enabled=$isEnabled, 绑定玩家数=$boundCount) =====")
    }

    // ---------- 单例 ----------

    companion object {
        private val LOCK = Any()
        private const val GLOBAL_CTX_KEY = "__global__"

        @Volatile
        private var instance: QqBindManager? = null

        /** 获取单例（首次调用时懒加载）。 */
        fun getInstance(): QqBindManager {
            return instance ?: synchronized(LOCK) {
                instance ?: QqBindManager(currentPlugin()).also { instance = it }
            }
        }

        private fun currentPlugin(): JavaPlugin {
            // 由 BotShared 持有的插件实例即主插件
            val shared = cn.huohuas001.bot.provider.BotShared.instance
            return shared as? JavaPlugin ?: error("QqBindManager 需要 Spigot 插件实例")
        }

        /** 静默日志（控制台 + 文件）。 */
        @JvmStatic
        fun logQuiet(message: String) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
            val prefix = if (message.startsWith("[")) "" else "[QQ绑定] "
            val line = "[$timestamp] $prefix$message"
            try {
                val manager = instance
                if (manager?.plugin != null) {
                    manager.plugin.logger.info(line)
                } else {
                    println(line)
                }
            } catch (_: Throwable) {
                println(line)
            }
            try {
                val file = getLogFile() ?: return
                PrintWriter(FileWriter(file, true)).use { it.println(line) }
            } catch (_: Throwable) {
            }
        }

        /** 详细日志（仅文件）。 */
        @JvmStatic
        fun logVerbose(message: String) {
            try {
                val file = getLogFile() ?: return
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
                val prefix = if (message.startsWith("[")) "" else "[QQ绑定][详细] "
                PrintWriter(FileWriter(file, true)).use { it.println("[$timestamp] $prefix$message") }
            } catch (_: Throwable) {
            }
        }

        /** 对外暴露的当前日志文件路径（供日志重定向使用）。 */
        @JvmStatic
        fun currentLogFile(): File? = getLogFile()

        /** 当前日志文件（超 1GB 自动轮转）。 */
        private fun getLogFile(): File? = try {
            val manager = instance ?: return null
            val folder = manager.logFile.parentFile ?: return null
            if (!folder.exists()) folder.mkdirs()
            val date = SimpleDateFormat("yyyy-MM-dd").format(Date())
            val base = "qq-bind-$date"
            var file = File(folder, "$base.log")
            val rotateThreshold = 0x40000000L // 1GB
            if (file.exists() && file.length() >= rotateThreshold) {
                var index = 1
                while (true) {
                    val candidate = File(folder, "$base-$index.log")
                    if (!candidate.exists() || candidate.length() < rotateThreshold) {
                        file = candidate
                        break
                    }
                    index++
                }
            }
            file
        } catch (_: Throwable) {
            null
        }
    }

    // ---------- Key 工具 ----------

    private fun key(playerName: String?): String = playerName?.lowercase() ?: ""

    /** 生成 “玩家名[服务器UUID]” 绑定 key。 */
    fun keyWithUuid(playerName: String?, playerUuid: String?): String =
        (playerName ?: "").lowercase() + "[" + (playerUuid ?: "") + "]"

    // ---------- QUUID 机制 ----------

    /**
     * 通过玩家名 + 服务器 UUID 查找/创建 QUUID（新版绑定机制）。
     * 兼容旧版：纯玩家名 key 存在时自动迁移。
     */
    @Synchronized
    fun getOrCreateQuuidByUuid(playerName: String?, playerUuid: String?): String {
        val newKey = keyWithUuid(playerName, playerUuid)
        nameToQuuid[newKey]?.let {
            if (!playerUuid.isNullOrEmpty()) uuidToQuuid[playerUuid] = it
            return it
        }

        // 兼容旧版：纯玩家名 key
        val oldKey = key(playerName)
        val oldQuuid = nameToQuuid[oldKey]
        if (oldQuuid != null) {
            nameToQuuid.remove(oldKey)
            nameToQuuid[newKey] = oldQuuid
            if (!playerUuid.isNullOrEmpty()) uuidToQuuid[playerUuid] = oldQuuid
            saveIndex()
            setQuuidPlayerUuid(oldQuuid, playerUuid)
            logQuiet("[QUUID 迁移] 玩家=$playerName 旧key=$oldKey 新key=$newKey QUUID=$oldQuuid")
            return oldQuuid
        }

        // 全新创建
        val newQuuid = UUID.randomUUID().toString()
        nameToQuuid[newKey] = newQuuid
        if (!playerUuid.isNullOrEmpty()) uuidToQuuid[playerUuid] = newQuuid
        saveIndex()
        val file = File(quuidFolder, "$newQuuid.yml")
        if (!file.exists()) try {
            file.createNewFile()
        } catch (_: IOException) {
        }
        saveQuuidRecord(newQuuid, playerName, null, 0L, false)
        setQuuidPlayerUuid(newQuuid, playerUuid)
        logQuiet("[QUUID 新建] 玩家=$playerName UUID=$playerUuid QUUID=$newQuuid")
        return newQuuid
    }

    /** 检查玩家（通过 UUID）是否已绑定 QQ。 */
    fun isBoundByUuid(playerName: String?, playerUuid: String?): Boolean {
        var quuid = nameToQuuid[keyWithUuid(playerName, playerUuid)]
        if (quuid == null) {
            quuid = nameToQuuid[key(playerName)] ?: return false
        }
        val file = File(quuidFolder, "$quuid.yml")
        if (!file.exists()) return false
        val qq = YamlConfiguration.loadConfiguration(file).getString("qq", "")
        return !qq.isNullOrEmpty()
    }

    /** 通过玩家名 + UUID 获取 QUUID。 */
    fun getQuuidByUuid(playerName: String?, playerUuid: String?): String? =
        nameToQuuid[keyWithUuid(playerName, playerUuid)] ?: nameToQuuid[key(playerName)]

    /** 写入 QUUID 记录的 playerUuid 字段。 */
    fun setQuuidPlayerUuid(quuid: String?, playerUuid: String?) {
        if (quuid.isNullOrEmpty() || playerUuid == null) return
        updateQuuidRecord(quuid) { yaml ->
            yaml.set("playerUuid", playerUuid)
        }
        if (playerUuid.isNotEmpty()) uuidToQuuid.putIfAbsent(playerUuid, quuid)
    }

    /** 读取 QUUID 记录的 playerUuid 字段（/查信息 使用）。 */
    fun getPlayerUuidByQuuid(quuid: String?): String? {
        if (quuid.isNullOrEmpty()) return null
        val file = File(quuidFolder, "$quuid.yml")
        if (!file.exists()) return null
        return YamlConfiguration.loadConfiguration(file).getString("playerUuid", null)
    }

    // ---------- 主文件统一读写（1.5.3.1 覆盖版） ----------

    /**
     * 通过玩家 UUID 获取 QUUID（不创建）：同名玩家 UUID 不同即不同人。
     * O(1) 内存索引命中；未命中时兑底扫描索引键 “玩家名[UUID]” 并回填。
     */
    fun getQuuidByPlayerUuid(playerUuid: String?): String? {
        if (playerUuid.isNullOrEmpty()) return null
        uuidToQuuid[playerUuid]?.let { return it }
        for ((key, quuid) in nameToQuuid) {
            if (key.endsWith("[$playerUuid]")) {
                uuidToQuuid[playerUuid] = quuid
                return quuid
            }
        }
        return null
    }

    /**
     * 通过 QQ OpenId 获取 QUUID（不创建）：O(1) 内存索引命中；
     * 未命中时兑底逐文件扫描一次并回填（启动扫描前的极端时序保险）。
     */
    fun getQuuidByOpenid(openId: String?): String? {
        if (openId.isNullOrEmpty()) return null
        openidToQuuid[openId]?.let { return it }
        val files = quuidFolder.listFiles { f -> f.isFile && f.name.endsWith(".yml") } ?: return null
        for (file in files) {
            val name = file.name
            if (name == "index.yml" || name == "skip.yml" || name == "blacklist.yml") continue
            try {
                val yaml = YamlConfiguration.loadConfiguration(file)
                val qq = yaml.getString("qq", "") ?: continue
                if (qq.isEmpty()) continue
                val quuid = name.removeSuffix(".yml")
                openidToQuuid[qq] = quuid
                if (qq == openId) return quuid
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * 加锁更新单条 QUUID 主文件（读 → 改 → 原子写）。
     *
     * 1.5.3.1 覆盖版起，统计 / OpenId 昵称 / 签到 / 金币等多个写入方共用同一份
     * 主文件，全部经本方法串行化，防止并发“各自读旧值再整文件回写”互相覆盖
     * 丢字段；保存先写临时文件再原子改名，读方不会读到写一半的文件。
     *
     * @return 是否写入成功（quuid 为空 / 磁盘异常返回 false）
     */
    @Synchronized
    fun updateQuuidRecord(quuid: String?, mutator: (YamlConfiguration) -> Unit): Boolean {
        if (quuid.isNullOrEmpty()) return false
        return try {
            val file = File(quuidFolder, "$quuid.yml")
            val yaml = if (file.exists()) {
                try {
                    YamlConfiguration.loadConfiguration(file)
                } catch (_: Throwable) {
                    YamlConfiguration()
                }
            } else {
                YamlConfiguration()
            }
            mutator(yaml)
            saveAtomic(yaml, file)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 只读快照：读取整条 QUUID 主文件（不存在返回 null，不创建）。 */
    fun readQuuidRecord(quuid: String?): YamlConfiguration? {
        if (quuid.isNullOrEmpty()) return null
        return try {
            val file = File(quuidFolder, "$quuid.yml")
            if (!file.exists()) null else YamlConfiguration.loadConfiguration(file)
        } catch (_: Throwable) {
            null
        }
    }

    /** 原子保存：先写临时文件再改名替换；文件系统不支持时退回直接写。 */
    private fun saveAtomic(yaml: YamlConfiguration, file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        yaml.save(tmp)
        if (!tmp.renameTo(file)) {
            yaml.save(file)
            tmp.delete()
        }
    }

    // ---------- 配置读取 ----------

    /** QQ 绑定功能总开关。 */
    val isEnabled: Boolean
        get() = try {
            plugin.config.getBoolean("qq-bind.enabled", false)
        } catch (_: Throwable) {
            false
        }

    /**
     * /查信息 统计卡片命令开关（默认开启，1.5.2 前名为 /个人信息）。
     * 前提：QQ 绑定功能（qq-bind.enabled）处于开启状态，否则本开关无效。
     */
    val isPersonalInfoEnabled: Boolean
        get() = try {
            plugin.config.getBoolean("qq-bind.personal-info", true)
        } catch (_: Throwable) {
            true
        }

    /** 绑定码有效期（分钟，至少 1）。 */
    val codeExpireMinutes: Int
        get() {
            val minutes = plugin.config.getInt("qq-bind.code-expire-minutes", 10)
            return if (minutes < 1) 10 else minutes
        }

    /** 绑定码长度（4-8 位）。 */
    val codeLength: Int
        get() {
            var length = plugin.config.getInt("qq-bind.code-length", 5)
            if (length < 4) length = 4
            if (length > 8) length = 8
            return length
        }

    /** QQ 群 AI 对话开关。 */
    val isAiEnabled: Boolean
        get() = try {
            plugin.config.getBoolean("ai.enabled", false)
        } catch (_: Throwable) {
            false
        }

    /** AI 接口地址自动补全开关。 */
    val isAiUrlAutoAppend: Boolean
        get() = try {
            plugin.config.getBoolean("ai.url-auto-append", true)
        } catch (_: Throwable) {
            true
        }

    fun aiBaseUrl(): String = try {
        plugin.config.getString("ai.base-url", "") ?: ""
    } catch (_: Throwable) {
        ""
    }

    fun aiApiKey(): String = try {
        plugin.config.getString("ai.api-key", "") ?: ""
    } catch (_: Throwable) {
        ""
    }

    fun aiModel(): String = try {
        plugin.config.getString("ai.model", "deepseek-chat") ?: "deepseek-chat"
    } catch (_: Throwable) {
        "deepseek-chat"
    }

    fun aiSystemPrompt(): String = try {
        plugin.config.getString("ai.system-prompt", "你是一个友好的游戏群助手，请简洁回答。") ?: "你是一个友好的游戏群助手，请简洁回答。"
    } catch (_: Throwable) {
        "你是一个友好的游戏群助手，请简洁回答。"
    }

    fun aiContextLimit(): Int = try {
        plugin.config.getInt("ai.context-limit", 6)
    } catch (_: Throwable) {
        6
    }

    /** 服务器内 AI 对话开关。 */
    val isServerAiEnabled: Boolean
        get() = try {
            plugin.config.getBoolean("ai.server-enabled", false)
        } catch (_: Throwable) {
            false
        }

    fun serverAiPrefix(): String = try {
        plugin.config.getString("ai.server-prefix", "ai") ?: "ai"
    } catch (_: Throwable) {
        "ai"
    }

    fun serverAiOutputPrefix(): String = try {
        plugin.config.getString("ai.server-output-prefix", "[AI]") ?: "[AI]"
    } catch (_: Throwable) {
        "[AI]"
    }

    val qqAiOutputPrefix: String
        get() = try {
            plugin.config.getString("ai.qq-output-prefix", "[AI]") ?: "[AI]"
        } catch (_: Throwable) {
            "[AI]"
        }

    /** 踢出提示语（占位符 {code} {name} {expire}）。 */
    private val kickMessageLines: List<String>
        get() {
            val lines = ArrayList<String>()
            try {
                val configured = plugin.config.getStringList("qq-bind.kick-message")
                if (configured != null) {
                    for (line in configured) {
                        if (line != null) lines.add(line)
                    }
                }
            } catch (_: Throwable) {
            }
            if (lines.isEmpty()) {
                lines.add("&c===== QQ 绑定验证 =====")
                lines.add("&f你还未绑定 QQ，无法进入服务器。")
                lines.add("")
                lines.add("&e你的专属绑定码: &a&l{code}")
                lines.add("&7请在 QQ 群发送: &f/绑定 {code}")
                lines.add("&7（绑定码 {expire} 分钟内有效）")
            }
            return lines
        }

    /** 签到功能开关。 */
    val isCheckinEnabled: Boolean
        get() = try {
            plugin.config.getBoolean("qq-bind.checkin.enabled", false)
        } catch (_: Throwable) {
            false
        }

    val checkinPrefix: String
        get() = try {
            plugin.config.getString("qq-bind.checkin.prefix", "[签到]") ?: "[签到]"
        } catch (_: Throwable) {
            "[签到]"
        }

    val checkinReward: Double
        get() = try {
            plugin.config.getDouble("qq-bind.checkin.reward", 100.0)
        } catch (_: Throwable) {
            100.0
        }

    // ---------- QUUID 记录字段 ----------

    /** 待补发的签到金币。 */
    fun getPendingCoins(quuid: String?): Double {
        if (quuid.isNullOrEmpty()) return 0.0
        return readQuuidRecord(quuid)?.getDouble("pendingCoins", 0.0) ?: 0.0
    }

    /** 累加待补发金币。 */
    fun addPendingCoins(quuid: String?, amount: Double) {
        if (quuid.isNullOrEmpty() || amount <= 0) return
        updateQuuidRecord(quuid) { yaml ->
            yaml.set("pendingCoins", yaml.getDouble("pendingCoins", 0.0) + amount)
        }
    }

    /** 取走全部待补发金币。 */
    fun takePendingCoins(quuid: String?): Double {
        if (quuid.isNullOrEmpty()) return 0.0
        val yaml = readQuuidRecord(quuid) ?: return 0.0
        val current = yaml.getDouble("pendingCoins", 0.0)
        if (current > 0) {
            updateQuuidRecord(quuid) { it.set("pendingCoins", 0.0) }
        }
        return current
    }

    /** 最近签到日期（yyyy-MM-dd）。 */
    fun getCheckinDate(quuid: String?): String {
        if (quuid.isNullOrEmpty()) return ""
        return readQuuidRecord(quuid)?.getString("lastCheckinDate", "") ?: ""
    }

    fun setCheckinDate(quuid: String?, date: String?) {
        if (quuid.isNullOrEmpty() || date == null) return
        updateQuuidRecord(quuid) { yaml -> yaml.set("lastCheckinDate", date) }
    }

    /** 连续签到天数。 */
    fun getCheckinStreak(quuid: String?): Int {
        if (quuid.isNullOrEmpty()) return 0
        return readQuuidRecord(quuid)?.getInt("checkinStreak", 0) ?: 0
    }

    fun setCheckinStreak(quuid: String?, streak: Int) {
        if (quuid.isNullOrEmpty()) return
        updateQuuidRecord(quuid) { yaml -> yaml.set("checkinStreak", streak) }
    }

    /** 累计签到次数（/查信息 卡片数据）。 */
    fun getCheckinTotal(quuid: String?): Long {
        if (quuid.isNullOrEmpty()) return 0L
        return readQuuidRecord(quuid)?.getLong("checkinTotal", 0L) ?: 0L
    }

    /** 累加签到次数。 */
    fun addCheckinTotal(quuid: String?, amount: Long) {
        if (quuid.isNullOrEmpty() || amount <= 0) return
        updateQuuidRecord(quuid) { yaml ->
            yaml.set("checkinTotal", yaml.getLong("checkinTotal", 0L) + amount)
        }
    }

    /** 渲染踢出提示语。 */
    fun formatKickMessage(code: String?, name: String?): String {
        val expire = codeExpireMinutes
        val builder = StringBuilder()
        for (line in kickMessageLines) {
            var rendered = (line ?: "")
                .replace("{code}", code ?: "")
                .replace("{name}", name ?: "")
                .replace("{expire}", expire.toString())
            rendered = ChatColor.translateAlternateColorCodes('&', rendered)
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(rendered)
        }
        return builder.toString()
    }

    // ---------- 旧版按名绑定 API ----------

    /** 通过玩家名查找/创建 QUUID（旧版机制）。 */
    @Synchronized
    fun getOrCreateQuuid(playerName: String?): String {
        val playerKey = key(playerName)
        nameToQuuid[playerKey]?.let { return it }
        val newQuuid = UUID.randomUUID().toString()
        nameToQuuid[playerKey] = newQuuid
        saveIndex()
        val file = File(quuidFolder, "$newQuuid.yml")
        if (!file.exists()) try {
            file.createNewFile()
        } catch (_: IOException) {
        }
        saveQuuidRecord(newQuuid, playerName, null, 0L, false)
        logQuiet("[QUUID 新建] 玩家=$playerName QUUID=$newQuuid")
        return newQuuid
    }

    fun getQuuid(playerName: String?): String? = nameToQuuid[key(playerName)]

    fun isBound(playerName: String?): Boolean {
        val quuid = getQuuid(playerName) ?: return false
        val file = File(quuidFolder, "$quuid.yml")
        if (!file.exists()) return false
        val qq = YamlConfiguration.loadConfiguration(file).getString("qq", "")
        return !qq.isNullOrEmpty()
    }

    fun getBoundQq(playerName: String?): String? {
        val quuid = getQuuid(playerName) ?: return null
        val file = File(quuidFolder, "$quuid.yml")
        if (!file.exists()) return null
        return YamlConfiguration.loadConfiguration(file).getString("qq", null)
    }

    /** 已绑定 QQ 的玩家名列表。 */
    fun listBoundPlayerNames(): List<String> {
        val names = ArrayList<String>()
        for ((key, quuid) in nameToQuuid) {
            val file = File(quuidFolder, "$quuid.yml")
            if (!file.exists()) continue
            val yaml = YamlConfiguration.loadConfiguration(file)
            val qq = yaml.getString("qq", "") ?: continue
            if (qq.isEmpty()) continue
            val name = yaml.getString("playerName", key) ?: continue
            if (name.isEmpty()) continue
            names.add(name)
        }
        return names
    }

    // ---------- 免验证 ----------

    fun isSkipped(playerName: String?): Boolean = skipSet.containsKey(key(playerName))

    fun setSkipped(playerName: String?, skipped: Boolean) {
        val playerKey = key(playerName)
        if (skipped) {
            skipSet[playerKey] = true
        } else {
            skipSet.remove(playerKey)
        }
        saveSkip()
    }

    // ---------- 黑名单 ----------

    fun isBlacklisted(qq: String?): Boolean = !qq.isNullOrEmpty() && blacklist.containsKey(qq)

    @Synchronized
    fun addBlacklist(qq: String?, displayName: String?) {
        if (qq.isNullOrEmpty()) return
        val name = if (!displayName.isNullOrEmpty()) displayName else qq
        blacklist[qq] = name
        saveBlacklist()
        logQuiet("[黑名单] 添加 QQ=$qq 名称=$name")
    }

    @Synchronized
    fun removeBlacklist(qq: String?) {
        if (qq == null) return
        blacklist.remove(qq)
        saveBlacklist()
        logQuiet("[黑名单] 移除 QQ=$qq")
    }

    fun listBlacklist(): List<String> = ArrayList(blacklist.keys)

    fun listBlacklistNames(): List<String> = ArrayList(blacklist.values)

    fun getBlacklistName(qq: String?): String? = if (qq == null) null else blacklist[qq]

    // ---------- 反查（1.5.3.1 覆盖版：内存索引 O(1)，不再逐文件扫盘） ----------

    /** 通过 QQ OpenId 查找绑定的玩家名。 */
    fun findPlayerByQq(qq: String?): String? {
        val quuid = findQuuidByQq(qq) ?: return null
        return readQuuidRecord(quuid)?.getString("playerName", null)
    }

    /** 通过 QQ OpenId 查找 QUUID。 */
    fun findQuuidByQq(qq: String?): String? = getQuuidByOpenid(qq)

    /** 通过玩家名查找 QUUID（按 playerName 字段忽略大小写匹配）。 */
    fun findQuuidByPlayerName(playerName: String?): String? {
        if (playerName.isNullOrEmpty()) return null
        for ((_, quuid) in nameToQuuid) {
            val file = File(quuidFolder, "$quuid.yml")
            if (!file.exists()) continue
            val savedName = YamlConfiguration.loadConfiguration(file).getString("playerName", "")
            if (playerName.equals(savedName, ignoreCase = true)) {
                return quuid
            }
        }
        return null
    }

    /** 解绑该 QQ 绑定的玩家并踢出在线玩家，返回玩家名。 */
    fun unbindAndKickByQq(qq: String?): String? {
        val playerName = findPlayerByQq(qq) ?: return null
        val quuid = findQuuidByQq(qq)
        if (!quuid.isNullOrEmpty()) {
            unbindByQuuid(quuid, playerName)
        } else {
            unbind(playerName)
        }
        logQuiet("[解绑踢出] QQ=$qq 玩家=$playerName 已解绑")
        return playerName
    }

    // ---------- 绑定码 ----------

    /** 生成绑定码（旧版按名）。 */
    @Synchronized
    fun generateCode(playerName: String?): String = generateCode(playerName, null)

    /**
     * 生成绑定码：清除同玩家的旧码后发放新码。
     */
    @Synchronized
    fun generateCode(playerName: String?, playerUuid: String?): String {
        cleanExpiredCodes()
        val quuid = if (!playerUuid.isNullOrEmpty()) {
            getOrCreateQuuidByUuid(playerName, playerUuid)
        } else {
            getOrCreateQuuid(playerName)
        }
        val playerKey = key(playerName)
        // 移除该玩家的旧绑定码
        for ((code, pending) in ArrayList(pendingCodes.entries)) {
            if (playerKey == pending.playerKey) {
                pendingCodes.remove(code)
            }
        }
        // 生成不重复的数字码
        val length = codeLength
        val random = Random()
        var code: String
        var attempts = 0
        do {
            code = randomDigits(length, random)
            attempts++
        } while (pendingCodes.containsKey(code) && attempts < 50)
        val expireAt = System.currentTimeMillis() + codeExpireMinutes * 60000L
        pendingCodes[code] = PendingBind(playerName ?: "", playerKey, quuid, expireAt)
        return code
    }

    /** 提交绑定码完成绑定。 */
    @Synchronized
    fun confirmBinding(code: String?, qq: String?): Boolean {
        if (code == null || qq.isNullOrEmpty()) return false
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return false
        cleanExpiredCodes()
        val pending = pendingCodes[trimmed] ?: return false

        if (isBlacklisted(qq)) {
            logQuiet("[绑定拒绝] QQ=$qq 在黑名单中，拒绝绑定 玩家=${pending.playerName}")
            pendingCodes.remove(trimmed)
            return false
        }
        val existing = findPlayerByQq(qq)
        if (existing != null && !existing.equals(pending.playerName, ignoreCase = true)) {
            logQuiet("[绑定拒绝] QQ=$qq 已绑定玩家=$existing，拒绝重复绑定 玩家=${pending.playerName}")
            pendingCodes.remove(trimmed)
            return false
        }
        saveQuuidRecord(pending.quuid, pending.playerName, qq, System.currentTimeMillis(), false)
        openidToQuuid[qq] = pending.quuid
        pendingCodes.remove(trimmed)
        logQuiet("[绑定成功] 玩家=${pending.playerName} QUUID=${pending.quuid} QQ=$qq")
        return true
    }

    @Synchronized
    fun unbind(playerName: String?): Boolean {
        val quuid = getQuuid(playerName) ?: return false
        return unbindByQuuid(quuid, playerName)
    }

    /** 通过 QUUID 解绑（并踢出在线玩家）。 */
    @Synchronized
    fun unbindByQuuid(quuid: String?, playerName: String?): Boolean {
        if (quuid.isNullOrEmpty()) return false
        val file = File(quuidFolder, "$quuid.yml")
        if (!file.exists()) return false
        val yaml = try {
            YamlConfiguration.loadConfiguration(file)
        } catch (_: Throwable) {
            return false
        }
        val currentQq = yaml.getString("qq", "")
        if (currentQq.isNullOrEmpty()) return false
        yaml.set("qq", "")
        yaml.set("boundAt", 0L)
        openidToQuuid.remove(currentQq)
        try {
            saveAtomic(yaml, file)
        } catch (_: IOException) {
        }
        logQuiet("[解除绑定] 玩家=$playerName QUUID=$quuid")

        // 踢出在线玩家
        if (!playerName.isNullOrEmpty()) {
            try {
                val player: Player? = Bukkit.getPlayerExact(playerName)
                if (player != null && player.isOnline) {
                    val pluginRef: Plugin = plugin
                    Bukkit.getScheduler().runTask(pluginRef, Runnable {
                        player.kickPlayer("§c你的 QQ 已被解除绑定，请重新绑定后进入服务器。")
                    })
                    logQuiet("[解绑踢出] 玩家=$playerName 已踢出服务器")
                }
            } catch (error: Throwable) {
                logQuiet("[解绑踢出] 踢出失败: ${error.message}")
            }
        }
        return true
    }

    /** 通过玩家名 + 服务器 UUID 解绑。 */
    fun unbindByUuid(playerName: String?, playerUuid: String?): Boolean {
        var quuid = getQuuidByUuid(playerName, playerUuid)
        if (quuid.isNullOrEmpty()) {
            quuid = getQuuid(playerName)
        }
        if (quuid.isNullOrEmpty()) return false
        return unbindByQuuid(quuid, playerName)
    }

    // ---------- AI 对话上下文（全局） ----------

    fun isAiContextEnabled(groupId: String?, userId: String?): Boolean =
        aiContextEnabled.getOrDefault(GLOBAL_CTX_KEY, false)

    fun setAiContextEnabled(groupId: String?, userId: String?, enabled: Boolean) {
        aiContextEnabled[GLOBAL_CTX_KEY] = enabled
        saveAiContext()
        if (!enabled) {
            aiContext.remove(GLOBAL_CTX_KEY)
        }
    }

    fun getAiContext(groupId: String?, userId: String?): MutableList<ChatMessage>? {
        if (!isAiContextEnabled(groupId, userId)) return null
        return aiContext[GLOBAL_CTX_KEY]?.let { ArrayList(it) } ?: ArrayList()
    }

    fun appendAiContext(groupId: String?, userId: String?, userMessage: String, assistantMessage: String) {
        if (!isAiContextEnabled(groupId, userId)) return
        val history = aiContext.computeIfAbsent(GLOBAL_CTX_KEY) { ArrayList() }
        history.add(ChatMessage("user", userMessage))
        history.add(ChatMessage("assistant", assistantMessage))
        val limit = aiContextLimit() * 2
        while (history.size > limit) {
            history.removeAt(0)
        }
        saveAiContext()
    }

    fun clearAiContext(groupId: String?, userId: String?) {
        aiContext.remove(GLOBAL_CTX_KEY)
    }

    /** 生成指定长度的随机数字串。 */
    private fun randomDigits(length: Int, random: Random): String {
        val builder = StringBuilder(length)
        repeat(length) {
            builder.append(random.nextInt(10))
        }
        return builder.toString()
    }

    // ---------- 内部持久化 ----------

    /**
     * 启动时扫描全部 QUUID 主文件，构建 OpenId → QUUID 与
     * playerUuid 字段 → QUUID 两条反查索引（每个小文件只读一次，
     * 扫描量 = 玩家数，小服务器秒级完成；取代旧版 countBound 的
     * 同规模扫描，不新增任何启动开销），返回已绑定玩家数。
     */
    private fun buildReverseIndexes(): Int {
        var bound = 0
        val files = try {
            quuidFolder.listFiles { f -> f.isFile && f.name.endsWith(".yml") }
        } catch (_: Throwable) {
            null
        } ?: return 0
        for (file in files) {
            val name = file.name
            if (name == "index.yml" || name == "skip.yml" || name == "blacklist.yml") continue
            try {
                val yaml = YamlConfiguration.loadConfiguration(file)
                val quuid = name.removeSuffix(".yml")
                val qq = yaml.getString("qq", "")
                if (!qq.isNullOrEmpty()) {
                    openidToQuuid[qq] = quuid
                    bound++
                }
                val playerUuidField = yaml.getString("playerUuid", "")
                if (!playerUuidField.isNullOrEmpty()) {
                    // 索引键派生的映射优先（putIfAbsent），主文件字段仅补缺
                    uuidToQuuid.putIfAbsent(playerUuidField, quuid)
                }
            } catch (_: Throwable) {
            }
        }
        return bound
    }

    private fun cleanExpiredCodes() {
        val now = System.currentTimeMillis()
        for ((code, pending) in ArrayList(pendingCodes.entries)) {
            if (pending.expireAt < now) {
                pendingCodes.remove(code)
            }
        }
    }

    private fun loadIndex() {
        if (!indexFile.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(indexFile)
        val section: ConfigurationSection = yaml.getConfigurationSection("players") ?: return
        for (key in section.getKeys(false)) {
            val quuid = section.getString(key, "")
            if (quuid.isNullOrEmpty()) continue
            nameToQuuid[key] = quuid
            // 1.5.3.1 覆盖版：解析 “玩家名[UUID]” 键中的 UUID 部分，建 UUID → QUUID 反查
            val start = key.lastIndexOf('[')
            val end = key.lastIndexOf(']')
            if (start >= 0 && end > start + 1) {
                val uuidPart = key.substring(start + 1, end)
                if (uuidPart.length == 36 && uuidPart.count { it == '-' } == 4) {
                    uuidToQuuid.putIfAbsent(uuidPart, quuid)
                }
            }
        }
    }

    private fun saveIndex() {
        val yaml = YamlConfiguration()
        for ((key, quuid) in nameToQuuid) {
            yaml.set("players.$key", quuid)
        }
        try {
            yaml.save(indexFile)
        } catch (_: IOException) {
        }
    }

    private fun loadSkip() {
        if (!skipFile.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(skipFile)
        val section: ConfigurationSection = yaml.getConfigurationSection("players") ?: return
        for (key in section.getKeys(false)) {
            if (section.getBoolean(key, false)) {
                skipSet[key] = true
            }
        }
    }

    private fun saveSkip() {
        val yaml = YamlConfiguration()
        for (key in skipSet.keys) {
            yaml.set("players.$key", true)
        }
        try {
            yaml.save(skipFile)
        } catch (_: IOException) {
        }
    }

    private fun loadBlacklist() {
        if (!blacklistFile.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(blacklistFile)
        val section: ConfigurationSection = yaml.getConfigurationSection("qq") ?: return
        for (key in section.getKeys(false)) {
            blacklist[key] = section.getString(key, key) ?: key
        }
    }

    private fun saveBlacklist() {
        val yaml = YamlConfiguration()
        for ((qq, name) in blacklist) {
            yaml.set("qq.$qq", name)
        }
        try {
            yaml.save(blacklistFile)
        } catch (_: IOException) {
        }
    }

    /** 保存单条 QUUID 主文件（绑定字段部分，走统一加锁原子写）。 */
    private fun saveQuuidRecord(quuid: String, playerName: String?, qq: String?, boundAt: Long, skip: Boolean) {
        updateQuuidRecord(quuid) { yaml ->
            if (playerName != null) {
                yaml.set("playerName", playerName)
            }
            if (qq != null) {
                yaml.set("qq", qq)
            }
            if (boundAt > 0L) {
                yaml.set("boundAt", boundAt)
            }
            if (skip) {
                yaml.set("skip", true)
            }
        }
    }

    private fun loadAiContext() {
        if (!contextFile.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(contextFile)
        val section: ConfigurationSection? = yaml.getConfigurationSection("enabled")
        if (section != null) {
            val snapshot = section
            for (key in snapshot.getKeys(false)) {
                if (snapshot.getBoolean(key, false)) {
                    aiContextEnabled[key] = true
                }
            }
        }
    }

    private fun saveAiContext() {
        val yaml = YamlConfiguration()
        for ((key, enabled) in aiContextEnabled) {
            yaml.set("enabled.$key", enabled)
        }
        try {
            yaml.save(contextFile)
        } catch (_: IOException) {
        }
    }
}
