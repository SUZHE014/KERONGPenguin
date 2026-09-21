package cn.huohuas001.huhobotPenguin.spigot.qqbind

import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.qqpd.User
import io.github.kloping.qqbot.entities.qqpd.v2.Contact
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * 群成员 OpenId 目录（/查询OpenID 反查数据源）。
 *
 * 背景：QQ 群机器人只能看到成员的 OpenId 与群昵称（看不到真实 QQ 号）。
 * /查询OpenID 反查时需要展示"该 OpenId 对应的 QQ 账号（群昵称）"，
 * 而平台没有提供按 OpenId 查询群资料的接口——昵称只能从机器人见过的
 * 消息里积累：每条群消息的发送者（sender.openid + username）与被 @ 的
 * 成员（mentions[].id + username）都会带昵称。
 *
 * 存储（1.5.3.1 覆盖版）：**不再有任何独立文件或第二套 ID 键**——
 * - 已绑定玩家的昵称 / 最后活跃直接写入其 **QUUID 主文件**
 *   （`QUUID/<QUUID>.yml` 的 nickname / lastSeenAt 字段，与绑定、签到、
 *   统计同一人一文件），经绑定管理器统一锁 + 原子写，与统计等写入方
 *   互不覆盖；脏标记低优先级守护线程每 2 分钟批量落盘，停机 flush 兜底；
 * - 未绑定成员仅在内存中积累（重启后随群消息自动重建，无需落盘），
 *   查询时反查其 QUUID 主文件即可覆盖已绑定场景；
 * - 旧数据自动迁移：首轮 1.5.3.1 的 QUUID/openid/<OpenId>.yml 与更早的
 *   openids.yml 中**已绑定**的记录并入对应玩家主文件后删除，未绑定的
 *   丢弃（随消息重建）；QUUID/ 下不再有任何子目录。
 * - 记录失败静默忽略，绝不影响消息处理主流程。
 */
object OpenIdDirectory {

    /** 目录条目：群昵称 + 最后见到的时间戳（毫秒）。 */
    private data class Entry(val nickname: String, val lastSeenAt: Long)

    /** 内存表：OpenId → 条目。 */
    private val directory = ConcurrentHashMap<String, Entry>()

    /** 待落盘的 OpenId（脏标记）：只写有变动的绑定玩家主文件。 */
    private val dirtyIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 是否已完成旧数据迁移（幂等，仅一次）。 */
    @Volatile
    private var migrated = false

    /** 落盘线程。 */
    @Volatile
    private var saverThread: Thread? = null

    /** 记录一次群消息事件中出现的所有成员（发送者 + 被 @ 者）。 */
    fun recordEvent(event: GroupMessageEvent) {
        try {
            // 1) 消息发送者
            val sender: Contact? = event.sender
            if (sender != null) {
                val id = sender.openid ?: sender.id
                val name = sender.username
                if (!id.isNullOrEmpty() && !name.isNullOrEmpty()) {
                    record(id, name)
                }
            }
            // 2) 被 @ 的成员（含机器人自身，一并记录便于排查）
            val mentions: Array<User>? = event.rawMessage?.mentions
            if (mentions != null) {
                for (member in mentions) {
                    val id = member.id ?: continue
                    val name = member.username ?: continue
                    if (id.isNotEmpty() && name.isNotEmpty()) record(id, name)
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** 记录 / 更新一个 OpenId 的昵称（内存 + 脏标记）。 */
    private fun record(openId: String, nickname: String) {
        val now = System.currentTimeMillis()
        val previous = directory[openId]
        // 昵称未变化且 10 分钟内记录过则跳过（减少无谓脏标记）
        if (previous != null && previous.nickname == nickname && now - previous.lastSeenAt < 600_000) return
        directory[openId] = Entry(nickname, now)
        dirtyIds.add(openId)
        ensureSaverStarted()
    }

    /** 查询 OpenId 对应的群昵称（未知返回 null）。 */
    fun lookupNickname(openId: String?): String? {
        if (openId.isNullOrEmpty()) return null
        ensureMigrated()
        return lookupEntry(openId)?.nickname
    }

    /** 查询 OpenId 最后在线（被机器人见到）的时间描述。 */
    fun lookupLastSeenText(openId: String?): String? {
        if (openId.isNullOrEmpty()) return null
        ensureMigrated()
        val at = lookupEntry(openId)?.lastSeenAt ?: return null
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(at))
        } catch (_: Throwable) {
            null
        }
    }

    /** 目录条目数（诊断用；懒加载下为本次启动已触达的条目数）。 */
    fun size(): Int = directory.size

    /** 查条目：内存优先，没有则反查该 OpenId 绑定玩家的 QUUID 主文件。 */
    private fun lookupEntry(openId: String): Entry? {
        directory[openId]?.let { return it }
        val manager = bindManagerOrNull() ?: return null
        val quuid = manager.getQuuidByOpenid(openId) ?: return null
        val yaml = manager.readQuuidRecord(quuid) ?: return null
        val name = yaml.getString("nickname") ?: return null
        val entry = Entry(name, yaml.getLong("lastSeenAt", 0L))
        return directory.putIfAbsent(openId, entry) ?: entry
    }

    /** 绑定管理器（不可用时返回 null，读写方自行降级）。 */
    private fun bindManagerOrNull(): QqBindManager? = try {
        QqBindManager.getInstance()
    } catch (_: Throwable) {
        null
    }

    // ---------- 持久化（1.5.3.1 覆盖版：写入各自 QUUID 主文件） ----------

    /** 数据目录（插件根目录）。 */
    private fun dataFolder(): File? = try {
        cn.huohuas001.bot.provider.plugin.configFile?.parentFile
    } catch (_: Throwable) {
        null
    }

    /**
     * 立即落盘（脏数据时；插件停机或定时触发）。
     * 只持久化**已绑定**的 OpenId——昵称 / 最后活跃写入其绑定玩家的
     * QUUID 主文件（nickname / lastSeenAt 字段，统一锁 + 原子写）；
     * 未绑定成员仅内存积累（重启后随群消息自动重建），不产生任何文件。
     */
    fun flush() {
        try {
            if (dirtyIds.isEmpty()) return
            val manager = bindManagerOrNull() ?: return
            val iterator = dirtyIds.iterator()
            while (iterator.hasNext()) {
                val id = iterator.next()
                // 先摘除再写：写入期间的新变动会重新标脏，下轮补写
                iterator.remove()
                val entry = directory[id] ?: continue
                val quuid = manager.getQuuidByOpenid(id) ?: continue // 未绑定：仅内存
                val written = manager.updateQuuidRecord(quuid) { yaml ->
                    yaml.set("nickname", entry.nickname)
                    yaml.set("lastSeenAt", entry.lastSeenAt)
                }
                if (!written) {
                    dirtyIds.add(id) // 写失败保住脏标记，下轮重试
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 一次性迁移（1.5.3.1 覆盖版）：把两个历史布局的昵称数据并入各自
     * 玩家的 QUUID 主文件——
     * ① 更早的 openids.yml（全部成员合一）；
     * ② 首轮 1.5.3.1 的 QUUID/openid/<OpenId>.yml 子目录（不再使用）。
     * 已绑定的记录写入其玩家主文件；未绑定的丢弃（随群消息自动重建）。
     * 幂等：主文件已有 nickname 时跳过（运行数据优先）；旧文件处理后
     * 删除（openids.yml 归档为 openids.yml.migrated），失败下次重试。
     */
    private fun ensureMigrated() {
        if (migrated) return
        val manager = bindManagerOrNull() ?: return // 管理器未就绪：下次查询再试
        synchronized(this) {
            if (migrated) return
            try {
                val folder = dataFolder() ?: run {
                    migrated = true
                    return
                }
                var fromYml = 0
                var fromFolder = 0
                // ① openids.yml（含旧格式 昵称||时间戳）
                val legacy = File(folder, "openids.yml")
                if (legacy.isFile) {
                    val yaml = YamlConfiguration.loadConfiguration(legacy)
                    val section = yaml.getConfigurationSection("openids")
                    if (section != null) {
                        for (id in section.getKeys(false)) {
                            val raw = section.getString(id) ?: continue
                            val separator = raw.lastIndexOf("||")
                            if (separator <= 0) continue
                            val name = raw.substring(0, separator)
                            val at = raw.substring(separator + 2).toLongOrNull() ?: continue
                            if (mergeIntoQuuidRecord(manager, id, name, at)) fromYml++
                        }
                    }
                    if (!legacy.renameTo(File(folder, "openids.yml.migrated"))) {
                        cn.huohuas001.bot.tools.PluginFileLog.errorAndKeep(
                            "[OpenId目录] openids.yml 归档失败（已迁移 $fromYml 条），下次启动将重试"
                        )
                    }
                }
                // ② 首轮 1.5.3.1 的 QUUID/openid/ 子目录
                val interimFolder = File(folder, "QUUID/openid")
                if (interimFolder.isDirectory) {
                    val files = interimFolder.listFiles { f -> f.isFile && f.name.endsWith(".yml") }
                    if (files != null) {
                        for (file in files) {
                            try {
                                val yaml = YamlConfiguration.loadConfiguration(file)
                                val name = yaml.getString("nickname")
                                if (name != null) {
                                    // 文件名即 OpenId（平台分配的安全字符）
                                    val openId = file.name.removeSuffix(".yml")
                                    if (mergeIntoQuuidRecord(manager, openId, name, yaml.getLong("last-seen", 0L))) {
                                        fromFolder++
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                            file.delete() // 已并入或未绑定：均不再保留（未绑定随消息重建）
                        }
                    }
                    // 目录清空后移除，QUUID/ 下不再有任何子目录
                    val remaining = interimFolder.listFiles()
                    if (remaining != null && remaining.isEmpty()) interimFolder.delete()
                }
                if (fromYml + fromFolder > 0) {
                    cn.huohuas001.bot.tools.PluginFileLog.write(
                        "[OpenId目录] 已将 $fromYml 条（openids.yml）与 $fromFolder 条（QUUID/openid/）" +
                            "昵称记录并入对应玩家的 QUUID 主文件"
                    )
                }
            } catch (_: Throwable) {
            } finally {
                migrated = true
            }
        }
    }

    /**
     * 把一条昵称记录并入该 OpenId 绑定玩家的 QUUID 主文件（幂等：
     * 已有 nickname 时跳过，运行数据优先）。返回是否写入。
     */
    private fun mergeIntoQuuidRecord(manager: QqBindManager, openId: String, nickname: String, lastSeenAt: Long): Boolean {
        return try {
            val quuid = manager.getQuuidByOpenid(openId) ?: return false // 未绑定：不落盘
            manager.updateQuuidRecord(quuid) { yaml ->
                if (!yaml.contains("nickname")) {
                    yaml.set("nickname", nickname)
                    yaml.set("lastSeenAt", lastSeenAt)
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 启动落盘守护线程（幂等）。 */
    private fun ensureSaverStarted() {
        if (saverThread?.isAlive == true) return
        synchronized(this) {
            if (saverThread?.isAlive == true) return
            val thread = Thread({
                while (true) {
                    try {
                        Thread.sleep(120_000)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (dirtyIds.isNotEmpty()) flush()
                }
            }, "qq-openid-directory-saver")
            thread.isDaemon = true
            thread.priority = Thread.MIN_PRIORITY
            saverThread = thread
            thread.start()
        }
    }
}
