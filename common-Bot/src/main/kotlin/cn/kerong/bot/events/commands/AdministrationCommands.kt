package cn.kerong.bot.events.commands

import cn.kerong.bot.KERONG
import cn.kerong.penguin.spigot.qqbind.MessageTemplates
import cn.kerong.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/**
 * 管理员命令：执行服务器命令、管理员自定义命令、全量转发开关。
 *
 * 1.6.0：全部文本回复改走消息模板（invalid-params / bridge-error /
 * sent-request / execute-failed / full-amount-toggled，配置 messages 段可换文件），
 * 默认内容与旧版一致。
 */
class AdministrationCommands : CommandSupport() {

    /** /执行命令 <命令> —— 向服务器控制台派发命令。 */
    @Commands("执行命令")
    fun runServerCommand(plugin: KERONG, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.trim().isEmpty()) {
            MessageTemplates.sendDirect(event, "invalid-params")
            return
        }
        executeGameCommandWithMention(plugin, event, params, true)
    }

    /** /管理员执行 <key> —— 以管理员身份执行自定义命令。 */
    @Commands("管理员执行")
    fun runAdminCustomCommand(plugin: KERONG, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.trim().isEmpty()) {
            MessageTemplates.sendDirect(event, "invalid-params")
            return
        }
        val cmd = "kerong adminrun ${groupId(event)} ${userId(event)} $params"
        executeGameCommandWithMention(plugin, event, cmd, true)
    }

    /** /全量 —— 切换本群全量聊天转发（模板 full-amount-toggled，{state} = 开启/关闭）。 */
    @Commands("全量")
    fun fullAmount(plugin: KERONG, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val group = groupId(event)
        val enabled = CommandRepositories.groupSettings.fullForwarding(group, plugin.fullAmount)
        CommandRepositories.groupSettings.setFullForwarding(group, !enabled)
        MessageTemplates.sendDirect(event, "full-amount-toggled", mapOf(
            "state" to (if (!enabled) "开启" else "关闭"),
        ))
    }

    /**
     * 派发命令并带 @ 提及回复执行结果。
     * 回复模板：bridge-error（桥接失败）/ sent-request（已发送）/ execute-failed（异常）；
     * 执行结果本身为动态内容（服务器返回原文），原样审核后经 Markdown 回复。
     */
    private fun executeGameCommandWithMention(plugin: KERONG, event: GroupMessageEvent, command: String, direct: Boolean) {
        try {
            val outgoingCommand = if (direct) command
            else "kerong run ${groupId(event)} ${userId(event)} $command"
            plugin.sendCommand(outgoingCommand).whenComplete { result, error ->
                try {
                    when {
                        error != null || result == null -> MessageTemplates.sendDirect(event, "bridge-error")
                        result.rawString.isBlank() -> MessageTemplates.sendDirect(event, "sent-request")
                        else -> sendDirect(event, plugin.auditText(result.rawString))
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            MessageTemplates.sendDirect(event, "execute-failed", mapOf("error" to (t.message ?: t.toString())))
        }
    }

    /** 带 @ 提及的 Markdown 回复（动态内容；失败时退化为纯文本）。 */
    private fun sendDirect(event: GroupMessageEvent, message: String) {
        try {
            val userId = try {
                userId(event)
            } catch (_: Throwable) {
                null
            }
            val content = if (!userId.isNullOrEmpty() && userId != "<unknown>") "<@$userId>\n$message" else message
            try {
                cn.kerong.bot.QClient.replyMarkdown(event, content, null)
            } catch (_: Throwable) {
                event.sendMessage(content)
            }
        } catch (_: Throwable) {
            try {
                event.sendMessage(message)
            } catch (_: Throwable) {
            }
        }
    }
}
