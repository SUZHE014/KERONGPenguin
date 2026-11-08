package cn.kerong.bot

import cn.kerong.bot.provider.plugin
import cn.kerong.bot.tools.PluginFileLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 多服务器集群状态（1.7.4 第三轮覆盖版新增）。
 *
 * ## 背景
 *
 * KERONGPenguin 每台 MC 服务器一个实例、各自用同一 QQ 机器人凭据连接
 * QQ 开放平台——群里一条消息会被**每台服务器同时收到**。单服部署没有
 * 影响；多台服务器共用一个机器人时，同一条命令会被每台服务器各自
 * 回复一遍（群里刷屏）。
 *
 * ## 方案（配置式对等集群）
 *
 * 在每台服务器的 config.yml `bot.cluster` 段声明集群成员后：
 * - **群级选择**：群里执行命令时先弹出「选择服务器」按钮（见
 *   [ServerSelectButtons]），按下后该群的选择被**所有实例**记录
 *   （按钮互动事件与群消息一样广播到每条连接），之后的命令、聊天
 *   转发、进出服 / 死亡播报都只由所选服务器处理；
 * - **未选择时的兜底**：普通聊天与游戏侧播报按「群 OpenId 哈希」
 *   确定性地路由到一台默认服务器（保证初始行为确定、不刷屏），
 *   命令则持续弹选择按钮等待用户选择；
 * - **弹按钮仲裁**：同一条命令消息只有按 msg_id 哈希选中的那台
 *   实例弹按钮（多台实例独立计算、结果一致，不会重复弹窗）。
 *
 * ## 与 HuHoBot 的架构差异（如实说明）
 *
 * HuHoBot 是「主控 + 适配器」架构：一个主控连接 QQ，各服务器适配器
 * 连接主控，主控天然知道所有服务器、由主控统一转发。KERONGPenguin 是每服独立的对等
 * 实例，无主控——本功能以「各服配置一致 + 消息广播 + 互动事件同步」
 * 达成同样的多服务器选择体验（参考 HuHoBot 的多服务器概念与按钮交互）。
 *
 * ## 约束
 *
 * - 各服务器的 `bot.cluster.members` **必须完全一致**，否则路由错乱；
 * - 未配置（或成员少于 2 个）时**全部集群逻辑旁路**，行为与单服
 *   版本完全一致（零开销）；
 * - 选择状态持久化在 `cluster-selection.yml`（群 OpenId → 服务器名），
 *   重启不丢；`/hbkrg reload` 重新加载。
 */
object ClusterState {

    /** 选择状态文件名（位于插件数据目录）。 */
    private const val SELECTION_FILE = "cluster-selection.yml"

    /** 群级选择记录：群 OpenId → 服务器名。 */
    private val selection = ConcurrentHashMap<String, String>()

    /** 当前生效的本服显示名（启动 / reload 时刷新）。 */
    @Volatile
    private var selfName: String = ""

    /** 成员列表（排序后，各实例必须一致；少于 2 个 = 单服模式）。 */
    @Volatile
    private var members: List<String> = emptyList()

    /** 是否已初始化（避免每条消息重复读配置）。 */
    @Volatile
    private var initialized = false

    /** 从配置加载集群信息（启动 / reload / 首次访问时调用）。 */
    fun reload() {
        synchronized(this) {
            val currentPlugin = plugin
            val clusterName = currentPlugin.clusterName().trim()
            val configured = currentPlugin.clusterMembers()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
            // 本服显示名：cluster.name 优先，未配置时用 bot.name
            val effectiveSelf = clusterName.ifEmpty { currentPlugin.botName.trim() }
            if (configured.size >= 2 && configured.contains(effectiveSelf)) {
                selfName = effectiveSelf
                members = configured
                PluginFileLog.infoAndKeep(
                    "[多服务器] 集群模式已启用：本服=\"$selfName\"，成员=${configured.joinToString(" / ")}（共 ${configured.size} 台）"
                )
            } else {
                selfName = effectiveSelf
                members = emptyList()
                if (configured.size >= 2 && !configured.contains(effectiveSelf)) {
                    // 配了多成员但本服名不在列表里：如实告警并按单服运行
                    PluginFileLog.warnAndKeep(
                        "[多服务器] bot.cluster.members 配置了 ${configured.size} 个成员，但不含本服名 \"$effectiveSelf\"（cluster.name）——按单服模式运行；请检查各服配置是否一致"
                    )
                }
            }
            initialized = true
            loadSelectionFile()
        }
    }

    /** 确保已初始化（懒加载兜底）。 */
    private fun ensureLoaded() {
        if (!initialized) reload()
    }

    /** 是否处于多服务器模式（成员 ≥ 2 且包含本服）。 */
    val isMultiServer: Boolean
        get() {
            ensureLoaded()
            return members.size >= 2
        }

    /** 本服显示名（按钮上展示的服务器名）。 */
    val selfServerName: String
        get() {
            ensureLoaded()
            return selfName
        }

    /** 集群成员列表（排序后，与各实例一致）。 */
    val serverMembers: List<String>
        get() {
            ensureLoaded()
            return members
        }

    /** 群当前选择的服务器名（null = 未选择）。 */
    fun selectedServer(groupOpenId: String): String? {
        ensureLoaded()
        if (!isMultiServer || groupOpenId.isEmpty()) return null
        val selected = selection[groupOpenId] ?: return null
        // 防配置变更后残留失效选择（服务器已从成员中移除）
        return if (selected in members) selected else null
    }

    /**
     * 记录群的选择（按钮互动时每台实例都会调用——互动事件广播到达，
     * 各实例同步记录，保证后续路由一致）。
     */
    fun setSelectedServer(groupOpenId: String, serverName: String) {
        ensureLoaded()
        if (!isMultiServer || groupOpenId.isEmpty() || serverName !in members) return
        selection[groupOpenId] = serverName
        saveSelectionFile()
    }

    /**
     * 群未选择时的默认服务器（群 OpenId 哈希取模，确定性：各实例计算
     * 结果一致，普通聊天与游戏侧播报在用户选择前有稳定的路由归属）。
     */
    fun defaultServerFor(groupOpenId: String): String? {
        ensureLoaded()
        if (!isMultiServer || groupOpenId.isEmpty()) return null
        val index = ((groupOpenId.hashCode().toLong() and 0x7FFFFFFFL) % members.size).toInt()
        return members[index]
    }

    /**
     * 游戏 → 群方向的路由判定（进出服 / 死亡播报、游戏聊天转发、
     * 命令结果广播等主动推送）：本服是否应处理该群。
     *
     * - 单服模式：始终 true（零变化）；
     * - 群已选择：仅所选服务器 true；
     * - 群未选择：仅哈希默认服务器 true。
     */
    fun shouldServeGroup(groupOpenId: String): Boolean {
        ensureLoaded()
        if (!isMultiServer) return true
        if (groupOpenId.isEmpty()) return true
        val owner = selectedServer(groupOpenId) ?: defaultServerFor(groupOpenId)
        return owner == selfName
    }

    /**
     * 群 → 命令方向的仲裁：这条消息是否轮到本服弹「选择服务器」按钮
     * （msg_id 哈希取模，各实例独立计算结果一致，避免多台实例重复弹窗）。
     */
    fun isArbiterFor(msgId: String): Boolean {
        ensureLoaded()
        if (!isMultiServer) return true
        val index = ((msgId.hashCode().toLong() and 0x7FFFFFFFL) % members.size).toInt()
        return members[index] == selfName
    }

    /** 清空内存状态（/hbkrg reload 时随 reload 一并调用）。 */
    fun clearRuntime() {
        synchronized(this) {
            initialized = false
            selection.clear()
        }
    }

    // ---------- 持久化 ----------

    /** 选择状态文件（数据目录下；无数据目录的平台返回 null，仅内存态）。 */
    private fun selectionFile(): File? {
        val configDir = plugin.configFile?.absoluteFile?.parentFile ?: return null
        return configDir.resolve(SELECTION_FILE)
    }

    private fun loadSelectionFile() {
        val file = selectionFile() ?: return
        try {
            if (!file.isFile) return
            val loaded = mutableMapOf<String, String>()
            for (line in file.readLines(Charsets.UTF_8)) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val separator = trimmed.indexOf(':')
                if (separator <= 0) continue
                val group = trimmed.substring(0, separator).trim()
                val server = trimmed.substring(separator + 1).trim()
                if (group.isNotEmpty() && server.isNotEmpty()) loaded[group] = server
            }
            selection.clear()
            selection.putAll(loaded)
            if (loaded.isNotEmpty()) {
                PluginFileLog.infoAndKeep("[多服务器] 已加载群选择状态：${loaded.size} 个群")
            }
        } catch (error: Throwable) {
            PluginFileLog.warnAndKeep("[多服务器] 读取选择状态文件失败: ${error.message}（忽略，按空状态运行）")
        }
    }

    private fun saveSelectionFile() {
        // selectionFile 本身可能在插件实例未就绪时抛异常（configFile 访问
        // BotShared），一并包进 try——持久化失败只损失重启后的选择记忆，
        // 不能让按钮回调崩掉
        val file = try {
            selectionFile() ?: return
        } catch (_: Throwable) {
            return
        }
        try {
            val lines = StringBuilder()
            lines.append("# KERONGPenguin 多服务器群选择状态（自动维护，勿手改）\n")
            lines.append("# 格式：群OpenId: 服务器名\n")
            for ((group, server) in selection.toSortedMap()) {
                lines.append("$group: $server\n")
            }
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory) parent.mkdirs()
            file.writeText(lines.toString(), Charsets.UTF_8)
        } catch (error: Throwable) {
            PluginFileLog.warnAndKeep("[多服务器] 写入选择状态文件失败: ${error.message}")
        }
    }
}
