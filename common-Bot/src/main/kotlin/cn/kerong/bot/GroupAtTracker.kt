package cn.kerong.bot

/**
 * 群「@机器人」事件标记与消息级去重（1.6.7.5 新增）。
 *
 * ## 背景：为什么需要帧级捕获
 *
 * QQ 官方群消息协议（bot.q.qq.com 文档 + 平台实测）：
 * - **GROUP_AT_MESSAGE_CREATE**（用户在群里 @机器人 触发，最常见群事件）：
 *   **content 字段已被平台自动去除 @机器人的前缀**，
 *   **mentions 数组也明确「不含@机器人自身」**——
 *   也就是说收到的事件体里没有任何「这条消息 @ 过机器人」的直接痕迹；
 * - **GROUP_MESSAGE_CREATE**（群消息全量模式，机器人开启「接收所有消息」
 *   后每条消息都推）：content **原样保留** `<@openid>` 占位前缀，@机器人
 *   时 mentions 里会有 `bot=true` 条目（可检测，旧逻辑只覆盖了这条）。
 *
 * 旧版 AI 对话判定（1.6.7.4 及之前）只看「content 含 `<@` 片段」+ mentions
 * 匹配——对 GROUP_AT_MESSAGE_CREATE 事件**永远不成立**（前缀被剥、mentions
 * 无机器人），所以群里 @机器人 发消息从没被判定为 AI 对话，全部落到
 * 全量转发：这就是「AI 聊天艾特发送消息后没有判定为 AI 聊天」的根因。
 *
 * ## 方案
 *
 * [WssDiagnostics] 挂在 WebSocket 帧层（SDK 处理前先过插件钩子），每收到
 * op=0 的 GROUP_AT_MESSAGE_CREATE 帧就解析出 `d.id` 记入 [GroupAtTracker]。
 * [GroupMessageHandler] 拿到群消息事件后按 `rawMessage.id` 查询——在册即
 * 「这条消息是 @机器人 的」。帧钩子在 SDK 事件分发之前执行，同一条消息
 * 的标记必然先于处理写入；重连 / 断线重投递也不受影响（每帧都过钩子）。
 *
 * 附带 [RecentMessageGate]：msg_id 级去重。全量模式下同一条 @ 消息可能
 * 以两种事件先后到达（先 GROUP_MESSAGE_CREATE 带 `<@>` 前缀、再
 * GROUP_AT_MESSAGE_CREATE 已剥前缀），QQ 官方也提示「相同 msg_id 可能
 * 重复推送」——不去重会导致同一条消息被回复两次 / 转发两次。
 *
 * 两者均为纯内存结构（有界 + TTL），插件重启自然清空，无持久化。
 */
object GroupAtTracker {

    /** 容量上限（超限按最旧淘汰；群消息 id 为 64 字符串，1024 条内存占用可忽略）。 */
    private const val CAP = 1024

    /** 标记有效期（毫秒）：QQ 弱网重投递 / 双事件间隔都在秒级，10 分钟余量充足。 */
    private const val TTL_MILLIS = 10 * 60 * 1000L

    private val lock = Any()

    /** 消息 id → 记录时间戳（@机器人 事件）。 */
    private val atBotIds = LinkedHashMap<String, Long>()

    /** 记录一条「@机器人」群消息（来自 WSS 帧的 GROUP_AT_MESSAGE_CREATE）。 */
    fun recordAtBot(messageId: String?) {
        if (messageId.isNullOrEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            atBotIds.remove(messageId)
            atBotIds[messageId] = now
            evictLocked(now)
        }
    }

    /** 该消息 id 是否为 @机器人 消息（10 分钟内在册）。 */
    fun isAtBot(messageId: String?): Boolean {
        if (messageId.isNullOrEmpty()) return false
        val at = synchronized(lock) { atBotIds[messageId] } ?: return false
        return System.currentTimeMillis() - at <= TTL_MILLIS
    }

    /** 容量 + 过期淘汰（调用方持锁）。 */
    private fun evictLocked(now: Long) {
        val iterator = atBotIds.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > TTL_MILLIS || atBotIds.size > CAP) iterator.remove() else break
        }
    }
}

/**
 * 群消息 msg_id 级处理闸门（1.6.7.5 新增）。
 *
 * 同一 msg_id 只放行一次（[tryAcquire] 返回 true 一次），其余到达一律
 * 拦截。覆盖两类重复：
 * 1. 全量模式下 @ 消息双事件（GROUP_MESSAGE_CREATE + GROUP_AT_MESSAGE_CREATE）；
 * 2. QQ 网关弱网 / 会话恢复时对同一条消息的重投递（SDK 为重投递创建全新
 *    事件对象，旧版会完整重处理一遍：转发两次 / 回复两次）。
 */
object RecentMessageGate {

    private const val CAP = 2048

    private const val TTL_MILLIS = 10 * 60 * 1000L

    private val lock = Any()

    private val seen = LinkedHashMap<String, Long>()

    /**
     * 尝试占用消息处理权：首次见到该 id 返回 true，重复到达返回 false。
     * id 为空（异常事件）一律放行（不做去重）。
     */
    fun tryAcquire(messageId: String?): Boolean {
        if (messageId.isNullOrEmpty()) return true
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            if (seen.containsKey(messageId)) {
                false
            } else {
                seen[messageId] = now
                evictLocked(now)
                true
            }
        }
    }

    private fun evictLocked(now: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > TTL_MILLIS || seen.size > CAP) iterator.remove() else break
        }
    }
}
