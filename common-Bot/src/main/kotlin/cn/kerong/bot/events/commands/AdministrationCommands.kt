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
     * 执行结果本身为动态内容（服务器返回原文），先裁剪（1.6.9.5：超长
     * 输出会被 QQ 平台拒绝）再原样审核后经 Markdown 回复。
     *
     * 1.7.0：回退链真可达——replyMarkdown 按平台响应校验真实送达
     * （SDK 对 4xx/5xx 不抛异常，旧版「没抛异常」误判为已送达），拒收时
     * 回退 event.sendMessage 纯文本；回复整体移到异步线程（命令完成
     * 回调在主线程触发，旧版同步 HTTP 会卡服务器主线程）。
     */
    private fun executeGameCommandWithMention(plugin: KERONG, event: GroupMessageEvent, command: String, direct: Boolean) {
        try {
            val outgoingCommand = if (direct) command
            else "kerong run ${groupId(event)} ${userId(event)} $command"
            plugin.sendCommand(outgoingCommand).whenComplete { result, error ->
                // 回复（含 QQ HTTP 请求）移到异步线程：完成回调可能运行在
                // 服务器主线程，同步 HTTP 会造成主线程卡顿
                plugin.submitAsync {
                    try {
                        when {
                            error != null || result == null -> MessageTemplates.sendDirect(event, "bridge-error")
                            result.rawString.isBlank() -> MessageTemplates.sendDirect(event, "sent-request")
                            else -> sendDirect(event, plugin.auditText(clipCommandOutput(result.rawString)))
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (t: Throwable) {
            MessageTemplates.sendDirect(event, "execute-failed", mapOf("error" to (t.message ?: t.toString())))
        }
    }

    /**
     * 带 @ 提及的 Markdown 回复（动态内容；失败时退化为纯文本）。
     * 1.7.0：回退按 replyMarkdown 的真实送达结果（响应含消息 id 才算成功）。
     */
    private fun sendDirect(event: GroupMessageEvent, message: String) {
        try {
            val userId = try {
                userId(event)
            } catch (_: Throwable) {
                null
            }
            val content = if (!userId.isNullOrEmpty() && userId != "<unknown>") "<@$userId>\n$message" else message
            val sent = try {
                cn.kerong.bot.QClient.replyMarkdown(event, content, null)
            } catch (_: Throwable) {
                false
            }
            if (!sent) {
                try {
                    event.sendMessage(content)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
            try {
                event.sendMessage(message)
            } catch (_: Throwable) {
            }
        }
    }
}
