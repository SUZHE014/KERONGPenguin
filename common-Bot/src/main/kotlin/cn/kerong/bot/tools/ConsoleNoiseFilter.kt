package cn.kerong.bot.tools

/**
 * 控制台噪音行过滤器（1.7.2 新增）。
 *
 * /执行命令 的输出捕获窗口内，与命令执行无关的周期性日志会被一并捕到
 * （旧版整段混进 QQ 回复刷屏）。本过滤器按「消息前缀 + logger 名关键字」
 * 双通道剔除已知噪音：
 * - NeoForge 模组（如「布谷鸟时钟」）的 [MediaSource] 媒体日志高频刷屏
 *   （媒体=(空) mode=FIT …，周期输出，与命令无关）；
 * - 未签名聊天在控制台的 [Not Secure] 行（如 [Not Secure] [Server] 1）。
 *
 * 纯函数无依赖：Spigot / NeoForge 两个 CommandOutputAppender 副本共用，
 * 独立 JVM 冒烟直接调用断言。
 */
object ConsoleNoiseFilter {

    /** 噪音黑名单：消息前缀（命中即判噪音）。 */
    private val NOISE_MESSAGE_PREFIXES = listOf(
        "[MediaSource]",
        "[MediaSource ",
        "[Not Secure]",
    )

    /** 噪音黑名单：logger 名关键字（命中即判噪音）。 */
    private val NOISE_LOGGER_KEYWORDS = listOf(
        "MediaSource",
    )

    /**
     * 判定一行控制台日志是否为已知噪音。
     *
     * @param loggerName 日志事件的 logger 名（null 按空串处理）
     * @param message 格式化后的消息文本（null 直接判非噪音）
     */
    fun isNoise(loggerName: String?, message: String?): Boolean {
        val name = loggerName ?: ""
        for (keyword in NOISE_LOGGER_KEYWORDS) {
            if (name.contains(keyword)) return true
        }
        if (message == null) return false
        for (prefix in NOISE_MESSAGE_PREFIXES) {
            if (message.startsWith(prefix)) return true
        }
        return false
    }
}
