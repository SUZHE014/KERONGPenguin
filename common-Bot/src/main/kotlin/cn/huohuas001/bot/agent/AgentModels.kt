package cn.huohuas001.bot.agent

/**
 * AI Agent 的命令执行模式。
 */
enum class AgentCommandMode(val value: String) {
    /** 由 Agent 自动决定是否执行命令。 */
    AUTO("auto"),

    /** 命令执行前需用户确认。 */
    MANUAL("manual");

    companion object {
        fun from(value: String?): AgentCommandMode? =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
    }
}

/**
 * AI Agent 配置快照。
 */
data class AgentConfig(
    val enabled: Boolean,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val commandMode: AgentCommandMode,
) {
    /** 配置是否可用（开关开启且关键项非空）。 */
    val usable: Boolean
        get() = enabled && baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/** Agent API 调用异常。 */
class AgentApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 一次 Agent 对话的返回结果。
 */
data class AgentChatResult(
    val content: String? = null,
    val reasoning: String? = null,
    val toolCalls: com.alibaba.fastjson.JSONArray? = null,
)
