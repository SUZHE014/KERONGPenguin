package cn.huohuas001.huhobotPenguin.spigot.qqbind

import cn.huohuas001.bot.provider.plugin
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.qqpd.User
import io.github.kloping.qqbot.entities.qqpd.v2.Contact
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * 群成员 OpenId 目录（1.5.3）：OpenId → 群昵称。
 *
 * 背景：QQ 群机器人只能看到成员的 OpenId 与群昵称（看不到真实 QQ 号）。
 * /查询OpenID 反查时需要展示"该 OpenId 对应的 QQ 账号（群昵称）"，
 * 而平台没有提供按 OpenId 查询群资料的接口——昵称只能从机器人见过的
 * 消息里积累：每条群消息的发送者（sender.openid + username）与被 @ 的
 * 成员（mentions[].id + username）都会带昵称。
 *
 * 实现：
 * - 内存表：OpenId → 条目（昵称 + 最后见到时间）；
 * - 持久化（1.5.3.1）：**一人一文件**，位于 QUUID/openid/<OpenId>.yml，
 *   彻底消除旧版"全部成员合一个 openids.yml"的单文件膨胀风险；
 *   查询时懒加载该 OpenId 的文件（不整目录扫描），
 *   落盘只写**有变动**的 OpenId（脏标记，低优先级守护线程每 2 分钟一次，
 *   退出前由 flush 兜底）；旧版 openids.yml 首次访问自动拆分迁移并归档
 *   （openids.yml.migrated，一次性备份不再增长）；
 * - 记录失败静默忽略，绝不影响消息处理主流程。
 */
object OpenIdDirectory {

    /** 目录条目：群昵称 + 最后见到的时间戳（毫秒）。 */
    private data class Entry(val nickname: String, val lastSeenAt: Long)

    /** 内存表：OpenId → 条目。 */
    private val directory = ConcurrentHashMap<String, Entry>()

    /** 待落盘的 OpenId（脏标记，1.5.3.1）：只写有变动的文件。 */
    private val dirtyIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 是否已完成旧版 openids.yml 迁移（幂等，仅一次）。 */
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

    /** 记录 / 更新一个 OpenId 的昵称。 */
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

    /** 查条目：内存优先，没有则懒加载该 OpenId 的数据文件。 */
    private fun lookupEntry(openId: String): Entry? {
        directory[openId]?.let { return it }
        val file = fileFor(openId) ?: return null
        if (!file.isFile) return null
        return try {
            val yaml = YamlConfiguration.loadConfiguration(file)
            val name = yaml.getString("nickname") ?: return null
            val at = yaml.getLong("last-seen", 0L)
            val entry = Entry(name, at)
            directory.putIfAbsent(openId, entry) ?: entry
        } catch (_: Throwable) {
            null
        }
    }

    // ---------- 持久化（1.5.3.1：QUUID/openid/ 一人一文件） ----------

    /** 数据目录：<插件目录>/QUUID/openid。 */
    private fun dataFolder(): File? = try {
        plugin.configFile?.parentFile
    } catch (_: Throwable) {
        null
    }

    /**
     * 数据文件名：OpenId 通常是平台分配的安全字符（字母数字）；
     * 遇到异常字符时替换并追加哈希后缀，保证文件名安全且不与他人冲突。
     */
    private fun fileNameFor(openId: String): String {
        val safe = openId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (safe == openId) "$safe.yml" else "${safe}_${Integer.toHexString(openId.hashCode())}.yml"
    }

    /** 单个 OpenId 的数据文件路径。 */
    private fun fileFor(openId: String): File? = try {
        val folder = dataFolder() ?: return null
        File(File(folder, "QUUID/openid"), fileNameFor(openId))
    } catch (_: Throwable) {
        null
    }

    /**
     * 一次性迁移（1.5.3.1）：旧版全部成员合一的 openids.yml →
     * QUUID/openid/<OpenId>.yml 一人一文件；完成后旧文件改名
     * openids.yml.migrated 归档（一次性备份，不再增长）。
     * 已有同 OpenId 文件时跳过（新数据优先，重试幂等）；
     * 失败时旧文件保留，下次访问自动重试。
     */
    private fun ensureMigrated() {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            migrated = true
            try {
                val folder = dataFolder() ?: return
                val legacy = File(folder, "openids.yml")
                if (!legacy.isFile) return
                val yaml = YamlConfiguration.loadConfiguration(legacy)
                val section = yaml.getConfigurationSection("openids") ?: return
                val target = File(folder, "QUUID/openid")
                if (!target.isDirectory) target.mkdirs()
                var moved = 0
                for (id in section.getKeys(false)) {
                    val raw = section.getString(id) ?: continue
                    // 旧存储格式：昵称||最后见到时间戳
                    val separator = raw.lastIndexOf("||")
                    if (separator <= 0) continue
                    val name = raw.substring(0, separator)
                    val at = raw.substring(separator + 2).toLongOrNull() ?: continue
                    val file = File(target, fileNameFor(id))
                    if (file.isFile) {
                        moved++ // 已有新文件（本次会话落盘过），新数据优先
                        continue
                    }
                    val out = YamlConfiguration()
                    out.set("nickname", name)
                    out.set("last-seen", at)
                    try {
                        out.save(file)
                        moved++
                    } catch (_: Throwable) {
                    }
                }
                if (!legacy.renameTo(File(folder, "openids.yml.migrated"))) {
                    plugin.log_error("[OpenId目录] openids.yml 归档失败（已迁移 $moved 条），下次启动将重试")
                    return
                }
                plugin.log_info("[OpenId目录] 已迁移 $moved 条记录到 QUUID/openid/（旧文件归档为 openids.yml.migrated）")
            } catch (_: Throwable) {
            }
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

    /** 立即落盘（脏数据时；插件停机或定时触发；只写有变动的 OpenId 文件）。 */
    fun flush() {
        try {
            if (dirtyIds.isEmpty()) return
            val folder = dataFolder() ?: return
            val target = File(folder, "QUUID/openid")
            if (!target.isDirectory) target.mkdirs()
            val iterator = dirtyIds.iterator()
            while (iterator.hasNext()) {
                val id = iterator.next()
                // 先摘除再写：写入期间的新变动会重新标脏，下轮补写
                iterator.remove()
                val entry = directory[id] ?: continue
                val out = YamlConfiguration()
                out.set("nickname", entry.nickname)
                out.set("last-seen", entry.lastSeenAt)
                try {
                    out.save(File(target, fileNameFor(id)))
                } catch (_: Throwable) {
                    dirtyIds.add(id) // 写失败保住脏标记，下轮重试
                }
            }
        } catch (_: Throwable) {
        }
    }
}
