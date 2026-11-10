package cn.kerong.bot.events

import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * 每个群最近若干条**真实消息**的 id 登记表（供按钮点击后的合成事件做被动回复）。
 *
 * ## 背景：按钮点击后的回复没有可用的 msg_id
 *
 * QQ 群被动回复（msg_id 引用）只能引用机器人**收到过的真实消息**，且时限
 * 约 5 分钟。菜单按钮点击产生的「合成群消息事件」本身没有消息 id——旧版
 * 直接以无 msg_id 的主动消息回复，受主动消息配额限制且时灵时不灵。
 *
 * 每次收到真实群消息时登记（群 OpenId → 消息 id + 时间戳），按钮点击
 * 时取出该群**未过期的真实消息 id** 塞进合成事件，命令回复即可走正常
 * 被动通道（与手打命令完全一致，不受主动消息配额影响）。
 *
 * ## 轮换取用（修复平台 40034128「被动回复时间或者次数超过限制」）
 *
 * QQ 平台对**同一条消息**的被动回复有次数上限（约 5 条）与时间窗口（约
 * 5 分钟）。旧版只登记「最近一条」，用户在群里连续点击多个按钮（翻页 /
 * 查询 / 重新绑定…）时，每次按钮的回复都引用同一条消息 id，几次点击就
 * 吃满该条消息的被动回复配额，之后全部被平台以 40034128 拒收（客户端
 * 表现为点了按钮没有任何回复）。
 *
 * 现登记最近 [CAPACITY] 条真实消息（环形缓冲），按钮点击时**轮转取用**
 * （round-robin）：连续 N 次点击分散引用 N 条不同的消息 id，被动回复
 * 配额按「条数 × 每条上限」成倍放大（16 条 × 5 次 = 80 次点击额度），
 * 任何正常操作频率都不会触顶。取用时跳过已过期条目（惰性清理）。
 *
 * ## 时效与清理
 *
 * - 有效期 4 分钟（QQ 被动回复窗口约 5 分钟，留 1 分钟安全余量）；
 * - 过期条目在写入与读取时惰性清理（按钮点击是人手频率，无需后台线程）；
 * - 群内没有可用消息时返回 null，调用方回退主动消息通道。
 */
object RecentGroupMessages {

    /** 登记有效期（毫秒）：QQ 被动回复窗口约 5 分钟，取 4 分钟留余量。 */
    private const val TTL_MILLIS = 4L * 60L * 1000L

    /**
     * 每个群最多登记的消息条数（环形缓冲容量）。
     * 容量越大可分摊的被动回复次数越多；群消息本身也消耗配额（手打命令
     * 的回复也走被动），16 条已远超任何人手点击频率的需要。
     */
    private const val CAPACITY = 16

    private class Entry(val msgId: String, val at: Long)

    /** 每个群的登记状态：环形缓冲 + 轮转指针。 */
    private class GroupState {
        val buffer = ArrayList<Entry>(CAPACITY)
        /** 下一次取用的位置（0 直到 buffer.size-1 循环）。 */
        var cursor = 0
    }

    /** 群 OpenId → 登记状态（全部访问 synchronized(groupState)，群数量级小无竞争）。 */
    private val groups = ConcurrentHashMap<String, GroupState>()

    /** 登记一条真实群消息（群 id / 消息 id 为空时忽略，零开销）。 */
    fun record(groupOpenId: String?, msgId: String?) {
        if (groupOpenId.isNullOrEmpty() || msgId.isNullOrEmpty()) return
        val state = groups.computeIfAbsent(groupOpenId) { GroupState() }
        synchronized(state) {
            // 同一条消息重复投递（网关 at-least-once）不重复登记
            if (state.buffer.isNotEmpty() && state.buffer.last().msgId == msgId) return
            if (state.buffer.size >= CAPACITY) {
                state.buffer.removeAt(0)
                if (state.cursor > 0) state.cursor--
            }
            state.buffer.add(Entry(msgId, System.currentTimeMillis()))
        }
    }

    /**
     * 轮转取出该群一条未过期的真实消息 id。
     *
     * 连续调用依次返回缓冲里不同的消息（round-robin），把被动回复次数
     * 分摊到多条消息上；全部过期时清空返回 null。
     *
     * @return 可用于被动回复的消息 id；无可用消息返回 null
     */
    fun nextId(groupOpenId: String): String? {
        val state = groups[groupOpenId] ?: return null
        val now = System.currentTimeMillis()
        synchronized(state) {
            // 惰性清理过期条目
            state.buffer.removeAll { now - it.at > TTL_MILLIS }
            if (state.cursor >= state.buffer.size) state.cursor = 0
            if (state.buffer.isEmpty()) return null
            val entry = state.buffer[state.cursor]
            state.cursor = (state.cursor + 1) % state.buffer.size
            return entry.msgId
        }
    }

    /** 清空全部登记（测试 / 重置用）。 */
    fun clear() = groups.clear()
}
