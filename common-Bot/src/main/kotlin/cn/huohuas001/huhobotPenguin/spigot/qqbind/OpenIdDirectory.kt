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
 * - 持久化：插件数据目录 openids.yml（每次会话装载，脏数据由低优先级
 *   守护线程每 2 分钟落盘一次，退出前由 flush 兜底）；
 * - 记录失败静默忽略，绝不影响消息处理主流程。
 */
object OpenIdDirectory {

    /** 目录条目：群昵称 + 最后见到的时间戳（毫秒）。 */
    private data class Entry(val nickname: String, val lastSeenAt: Long)

    /** 内存表：OpenId → 条目。 */
    private val directory = ConcurrentHashMap<String, Entry>()

    /** 脏标记：有更新待落盘。 */
    @Volatile
    private var dirty = false

    /** 是否已装载磁盘数据。 */
    @Volatile
    private var loaded = false

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
        dirty = true
        ensureSaverStarted()
    }

    /** 查询 OpenId 对应的群昵称（未知返回 null）。 */
    fun lookupNickname(openId: String?): String? {
        if (openId.isNullOrEmpty()) return null
        ensureLoaded()
        return directory[openId]?.nickname
    }

    /** 查询 OpenId 最后在线（被机器人见到）的时间描述。 */
    fun lookupLastSeenText(openId: String?): String? {
        if (openId.isNullOrEmpty()) return null
        ensureLoaded()
        val at = directory[openId]?.lastSeenAt ?: return null
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(at))
        } catch (_: Throwable) {
            null
        }
    }

    /** 目录条目数（诊断用）。 */
    fun size(): Int {
        ensureLoaded()
        return directory.size
    }

    // ---------- 持久化 ----------

    /** 数据文件：<插件目录>/openids.yml。 */
    private fun dataFile(): File? = try {
        val folder = plugin.configFile?.parentFile ?: return null
        val file = File(folder, "openids.yml")
        if (!file.exists()) file.createNewFile()
        file
    } catch (_: Throwable) {
        null
    }

    /** 首次访问时装载磁盘数据（幂等）。 */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val file = dataFile() ?: return
                val yaml = YamlConfiguration.loadConfiguration(file)
                val section = yaml.getConfigurationSection("openids") ?: return
                val now = System.currentTimeMillis()
                for (id in section.getKeys(false)) {
                    val raw = section.getString(id) ?: continue
                    // 存储格式：昵称||最后见到时间戳
                    val separator = raw.lastIndexOf("||")
                    if (separator <= 0) continue
                    val name = raw.substring(0, separator)
                    val at = raw.substring(separator + 2).toLongOrNull() ?: now
                    directory[id] = Entry(name, at)
                }
                plugin.log_info("[OpenId目录] 已装载 ${directory.size} 个群成员昵称记录")
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
                    if (dirty) flush()
                }
            }, "qq-openid-directory-saver")
            thread.isDaemon = true
            thread.priority = Thread.MIN_PRIORITY
            saverThread = thread
            thread.start()
        }
    }

    /** 立即落盘（脏数据时；插件停机或定时触发）。 */
    fun flush() {
        try {
            val file = dataFile() ?: return
            val yaml = YamlConfiguration()
            val section = yaml.createSection("openids")
            for ((id, entry) in directory) {
                section.set(id, "${entry.nickname}||${entry.lastSeenAt}")
            }
            yaml.save(file)
            dirty = false
        } catch (_: Throwable) {
        }
    }
}
