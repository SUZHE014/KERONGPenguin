package cn.kerong.bot.events

import java.util.concurrent.ConcurrentHashMap

/**
 * 每个群最近一条**真实消息**的 id 登记表（供按钮点击后的合成事件做被动回复）。
 *
 * ## 背景：按钮点击后的回复没有可用的 msg_id
 *
 * QQ 群被动回复（msg_id 引用）只能引用机器人**收到过的真实消息**，且时限
 * 约 5 分钟。菜单按钮点击产生的「合成群消息事件」本身没有消息 id——旧版
 * 直接以无 msg_id 的主动消息回复，受主动消息配额限制且时灵时不灵。
 *
 * 现在每次收到真实群消息时登记（群 OpenId → 消息 id + 时间戳），按钮点击
 * 时取出该群**最近一条未过期的真实消息 id** 塞进合成事件，命令回复即可
 * 走正常被动通道（与手打命令完全一致，不受主动消息配额影响）。
 *
 * ## 时效与清理
 *
 * - 有效期 4 分钟（QQ 被动回复窗口约 5 分钟，留 1 分钟安全余量）；
 * - 过期条目在读取时惰性清理（按钮点击是人手频率，无需后台线程）；
 * - 群未登记 / 已过期时返回 null，调用方回退主动消息通道。
 */
object RecentGroupMessages {

    /** 登记有效期（毫秒）：QQ 被动回复窗口约 5 分钟，取 4 分钟留余量。 */
    private const val TTL_MILLIS = 4L * 60L * 1000L

    private class Entry(val msgId: String, val at: Long)

    /** 群 OpenId → 最近一条真实消息。 */
    private val latest = ConcurrentHashMap<String, Entry>()

    /** 登记一条真实群消息（群 id / 消息 id 为空时忽略，零开销）。 */
    fun record(groupOpenId: String?, msgId: String?) {
        if (groupOpenId.isNullOrEmpty() || msgId.isNullOrEmpty()) return
        latest[groupOpenId] = Entry(msgId, System.currentTimeMillis())
    }

    /**
     * 取该群最近一条未过期的真实消息 id。
     *
     * @return 可用于被动回复的消息 id；未登记 / 已过期返回 null
     */
    fun latestId(groupOpenId: String): String? {
        val entry = latest[groupOpenId] ?: return null
        if (System.currentTimeMillis() - entry.at > TTL_MILLIS) {
            latest.remove(groupOpenId)
            return null
        }
        return entry.msgId
    }

    /** 清空全部登记（测试 / 重置用）。 */
    fun clear() = latest.clear()
}
