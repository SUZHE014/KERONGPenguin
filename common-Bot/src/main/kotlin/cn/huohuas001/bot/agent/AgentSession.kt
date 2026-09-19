package cn.huohuas001.bot.agent

import com.alibaba.fastjson.JSONObject

/**
 * 一次 Agent 任务的会话状态：对话历史、执行进度与待确认操作。
 */
class AgentSession(
    val sessionId: String,
    val groupOpenId: String,
    val requestUserId: String,
    val task: String,
) {
    /** 对话历史（含系统提示与工具调用结果）。 */
    val messages: MutableList<JSONObject> = ArrayList()

    /** 会话创建时间戳（毫秒）。 */
    val createdAt: Long = System.currentTimeMillis()

    /** 成员 OpenId → 群昵称缓存。 */
    val memberNames: MutableMap<String, String> = LinkedHashMap()

    /** 任务是否已完成。 */
    @Volatile
    var finished: Boolean = false

    /** 任务是否被手动停止。 */
    @Volatile
    var stopped: Boolean = false

    /** 待用户确认的操作（MANUAL 模式）。 */
    @Volatile
    var awaitingApproval: PendingApproval? = null

    /** 获取成员展示名（无缓存时回退 OpenId）。 */
    fun displayName(openId: String): String = memberNames[openId] ?: openId

    /**
     * 等待用户确认的操作。
     * @param approvalId 确认编号（群内回复“确认/取消 编号”）
     * @param toolCallId 对应的 tool_calls 条目 Id
     * @param command 待执行的服务器命令
     * @param toolName 工具名（普通命令执行为空）
     * @param query 工具原始参数
     */
    data class PendingApproval(
        val approvalId: String,
        val toolCallId: String,
        val command: String,
        val toolName: String = "",
        val query: JSONObject? = null,
    ) {
        /** 是否为工具调用（区别于普通命令执行）。 */
        val isTool: Boolean
            get() = toolName.isNotEmpty()
    }
}
