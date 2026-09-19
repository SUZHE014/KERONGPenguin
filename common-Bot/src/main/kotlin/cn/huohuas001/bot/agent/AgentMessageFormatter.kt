package cn.huohuas001.bot.agent

/**
 * Agent 消息卡片格式化（Markdown）。
 */
object AgentMessageFormatter {
    private const val HEADER = "## 🤖 AI 助手"

    /** 思考过程卡片。 */
    fun thinking(content: String): String = block("思考", content)

    /** 常规回复卡片。 */
    fun reply(content: String): String = block("回复", content)

    /** 待审批命令卡片。 */
    fun approvalCard(command: String): String = buildString {
        append(HEADER).append('\n').append('\n')
        append("**[执行]** 执行命令：`").append(escapeCommand(command)).append('`').append('\n')
        append('\n')
        append("> 该操作需要管理员审批，请点击下方按钮确认。").append('\n')
    }.trimEnd()

    /** 已执行命令卡片（含输出）。 */
    fun executedCard(command: String, output: String): String = buildString {
        append(HEADER).append('\n').append('\n')
        append("**[执行]** 已执行命令：`").append(escapeCommand(command)).append('`').append('\n')
        if (output.isNotBlank()) {
            append('\n').append("**输出：**").append('\n').append('\n')
            append(codeBlock(output)).append('\n')
        }
    }.trimEnd()

    /** 信息获取卡片。 */
    fun fetchCard(title: String, content: String): String = buildString {
        append(HEADER).append('\n').append('\n')
        append("**[获取]** ").append(title).append('\n')
        if (content.isNotBlank()) {
            append('\n').append(codeBlock(content)).append('\n')
        }
    }.trimEnd()

    /** 审批通过通知。 */
    fun approvedNotice(byWho: String): String = block("审批", "命令已获管理员 **$byWho** 批准，正在执行。")

    /** 审批拒绝通知。 */
    fun rejectedNotice(byWho: String): String = block("审批", "命令被管理员 **$byWho** 拒绝，已停止执行。")

    /** 无权限提示。 */
    fun noPermissionNotice(): String = block("审批", "只有本群管理员或群主才能进行审批操作。")

    /** 任务完成提示。 */
    fun taskFinished(): String = block("完成", "本次任务已处理完毕。")

    /** 错误提示。 */
    fun error(message: String): String = block("错误", "AI 调用失败：$message")

    /** SKILL 加载提示。 */
    fun skillLoading(skillName: String): String = block("SKILL", "正在加载 **$skillName** SKILL…")

    /** 统一的分块格式。 */
    private fun block(tag: String, content: String): String = buildString {
        append(HEADER).append('\n').append('\n')
        append("**[$tag]** ").append(content.trim()).append('\n')
    }.trimEnd()

    /** 命令转义（反引号替换为单引号，限制长度）。 */
    private fun escapeCommand(command: String): String =
        command.trim().replace("`", "'").take(200)

    /** 代码块内容转义（防止嵌套）。 */
    private fun codeBlock(content: String): String {
        val escaped = content.trim().replace("```", "` ` `")
        return "```\n$escaped\n```"
    }
}
