package cn.kerong.bot.events.commands

import cn.kerong.bot.KERONG
import cn.kerong.penguin.spigot.qqbind.MessageTemplates
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/**
 * 管理员命令：执行服务器命令、管理员自定义命令、全量转发开关。
 *
 * 全部文本回复改走消息模板（invalid-params / bridge-error /
 * sent-request / execute-failed / full-amount-toggled，配置 messages 段可换文件），
 * 默认内容与旧版一致。
 */
class AdministrationCommands : CommandSupport() {

    /** /执行命令 <命令> —— 向服务器控制台派发命令。 */
    @Commands("执行命令", adminOnly = true)
    fun runServerCommand(plugin: KERONG, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.trim().isEmpty()) {
            MessageTemplates.sendDirect(event, "invalid-params")
            return
        }
        executeGameCommandWithMention(plugin, event, params, true, params)
    }

    /** /管理员执行 <key> —— 以管理员身份执行自定义命令。 */
    @Commands("管理员执行", adminOnly = true)
    fun runAdminCustomCommand(plugin: KERONG, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.trim().isEmpty()) {
            MessageTemplates.sendDirect(event, "invalid-params")
            return
        }
        val cmd = "kerong adminrun ${groupId(event)} ${userId(event)} $params"
        executeGameCommandWithMention(plugin, event, cmd, true, params)
    }

    /**
     * /全量 —— 切换全量聊天转发**总开关**（features.full-amount，管理员；
     * 模板 full-amount-toggled，{state} = 开启/关闭）。
     *
     * 1.7.5 覆盖第三轮：群级独立开关（command-state.ini 的 [full-forwarding]
     * 段）已删除——全量转发只按总配置生效（所有配置群统一开 / 关）。本命令
     * 直接切换总配置并写回 config.yml（注释保留），改完立即生效；总配置
     * 关闭时本命令仍可用（管理员手动输入开启），但菜单按钮与快捷面板按钮
     * 会同步隐藏。切换后立即异步重同步快捷面板（全量按钮即时出现 / 消失，
     * 无需再 /hbkrg reload 或重启）。
     */
    @Commands("全量", adminOnly = true)
    fun fullAmount(plugin: KERONG, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val enabled = plugin.fullAmount
        val written = plugin.setFullAmountEnabled(!enabled)
        MessageTemplates.sendDirect(event, "full-amount-toggled", mapOf(
            "state" to (if (!enabled) "开启" else "关闭"),
            "writeResult" to (if (written) "" else "；配置文件写入失败，重启后可能还原，请检查文件权限"),
        ))
 // 切换后立即重同步 QQ 快捷面板（全量按钮随总开关出现 / 消失，
        // 与 /hbkrg reload 同一套先删后建流程）。面板同步含网络 IO 与
        // QPM 节流（最坏 20 秒级），必须异步执行，命令回复不被拖慢。
        refreshPanelsAsync(plugin)
    }

    /**
     * 异步重同步快捷面板（后台线程，结果写日志；机器人未连接时静默跳过，
     * 连接恢复后的启动同步会按最新配置重建面板）。
     */
    private fun refreshPanelsAsync(plugin: KERONG) {
        val starter = try {
            cn.kerong.bot.QClient.starter
        } catch (_: Throwable) {
            null
        } ?: return
        val groups = try {
            plugin.groupOpenIdList()
        } catch (_: Throwable) {
            emptyList()
        }
        if (groups.isEmpty()) return
        Thread({
            try {
                cn.kerong.bot.MenuManager.syncGroupPanels(starter, groups)
            } catch (error: Throwable) {
                cn.kerong.bot.tools.PluginFileLog.write("[全量] 切换后重同步快捷面板失败: ${error.message}")
            }
        }, "qq-panel-refresh").apply { isDaemon = true }.start()
    }

    /**
     * 派发命令并带 @ 提及回复执行结果。
     * 回复模板：bridge-error（桥接失败）/ sent-request（已执行无输出，
     * {command} 变量）/ execute-failed（异常）。
     *
     * ## 控制台命令日志（1.7.6 覆盖轮修复）
     *
     * 旧版命令有输出时只回输出内容，群里看不出执行了什么命令；现在
     * 回复开头统一带「已执行命令：…」行（[displayCommand] = 用户输入的
     * 命令文本，不带内部 kerong run / adminrun 前缀）：
     * - 有输出：已执行命令：<cmd> + 分隔线 + 裁剪后的输出；
     * - 无输出：sent-request 模板（默认文案同样带命令行，可自定义）。
     *
     * 执行结果本身为动态内容（服务器返回原文），先裁剪（1.6.9.5：超长
     * 输出会被 QQ 平台拒绝）再原样审核后经 Markdown 回复。
     *
     * 回退链真可达——replyMarkdown 按平台响应校验真实送达
     * （SDK 对 4xx/5xx 不抛异常，旧版「没抛异常」误判为已送达），拒收时
     * 回退 event.sendMessage 纯文本；回复整体移到异步线程（命令完成
     * 回调在主线程触发，旧版同步 HTTP 会卡服务器主线程）。
     */
    private fun executeGameCommandWithMention(
        plugin: KERONG,
        event: GroupMessageEvent,
        command: String,
        direct: Boolean,
        displayCommand: String,
    ) {
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
                            result.rawString.isBlank() -> MessageTemplates.sendDirect(
                                event, "sent-request", mapOf("command" to displayCommand),
                            )
                            else -> sendDirect(
                                event,
                                "已执行命令：$displayCommand\n——————————\n" +
                                    plugin.auditText(clipCommandOutput(result.rawString)),
                            )
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
 * 回退按 replyMarkdown 的真实送达结果（响应含消息 id 才算成功）。
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
