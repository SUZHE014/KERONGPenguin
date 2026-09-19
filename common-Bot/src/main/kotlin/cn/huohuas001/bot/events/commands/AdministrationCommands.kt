package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/**
 * 管理员命令：执行服务器命令、管理员自定义命令、全量转发开关。
 */
class AdministrationCommands : CommandSupport() {

    /** /执行命令 <命令> —— 向服务器控制台派发命令。 */
    @Commands("执行命令")
    fun runServerCommand(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.trim().isEmpty()) {
            sendDirect(event, "参数不正确")
            return
        }
        executeGameCommandWithMention(plugin, event, params, true)
    }

    /** /管理员执行 <key> —— 以管理员身份执行自定义命令。 */
    @Commands("管理员执行")
    fun runAdminCustomCommand(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.trim().isEmpty()) {
            sendDirect(event, "参数不正确")
            return
        }
        val cmd = "huhobot adminrun ${groupId(event)} ${userId(event)} $params"
        executeGameCommandWithMention(plugin, event, cmd, true)
    }

    /** /全量 —— 切换本群全量聊天转发。 */
    @Commands("全量")
    fun fullAmount(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val group = groupId(event)
        val enabled = CommandRepositories.groupSettings.fullForwarding(group, plugin.fullAmount)
        CommandRepositories.groupSettings.setFullForwarding(group, !enabled)
        sendDirect(event, "本群全量转发已${if (!enabled) "开启" else "关闭"}")
    }

    /**
     * 派发命令并带 @ 提及回复执行结果。
     * （覆盖 CommandSupport.executeGameCommand 的纯文本回复，与绑定类命令的回复方式保持一致。）
     */
    private fun executeGameCommandWithMention(plugin: HuHoBot, event: GroupMessageEvent, command: String, direct: Boolean) {
        try {
            val outgoingCommand = if (direct) command
            else "huhobot run ${groupId(event)} ${userId(event)} $command"
            plugin.sendCommand(outgoingCommand).whenComplete { result, error ->
                try {
                    when {
                        error != null || result == null -> sendDirect(event, "游戏桥接未配置或执行失败")
                        result.rawString.isBlank() -> sendDirect(event, "已发送执行请求")
                        else -> sendDirect(event, plugin.auditText(result.rawString))
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            sendDirect(event, "执行失败: ${t.message}")
        }
    }

    /** 带 @ 提及的 Markdown 回复（失败时退化为纯文本）。 */
    protected fun sendDirect(event: GroupMessageEvent, message: String) {
        try {
            val userId = try {
                userId(event)
            } catch (_: Throwable) {
                null
            }
            val content = if (!userId.isNullOrEmpty() && userId != "<unknown>") "<@$userId>\n$message" else message
            try {
                QClient.replyMarkdown(event, content, null)
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
