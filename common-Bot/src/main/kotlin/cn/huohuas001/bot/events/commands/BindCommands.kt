package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/**
 * QQ 绑定命令：玩家进服获取绑定码后在群内发送 /绑定 <绑定码> 完成绑定。
 */
class BindCommands : CommandSupport() {

    /** /绑定 [绑定码] —— 提交绑定码完成 QQ 绑定。 */
    @Commands("绑定", "bind")
    fun bind(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val manager = QqBindManager.getInstance()
        val code = params?.trim() ?: ""
        QqBindManager.logVerbose("[QQ命令] /绑定 code=$code qq=${safeUserId(event)}")

        if (code.isEmpty()) {
            val usage = try {
                if (manager.isEnabled) {
                    "📋 QQ 绑定\n用法: /绑定 <绑定码>\n请先进入服务器获取专属绑定码。"
                } else {
                    "⚠️ QQ 绑定功能未开启"
                }
            } catch (_: Throwable) {
                "用法: /绑定 <绑定码>"
            }
            sendDirect(event, usage)
            return
        }

        val qq = safeUserId(event)
        val success = try {
            manager.confirmBinding(code, qq)
        } catch (error: Throwable) {
            QqBindManager.logQuiet("[QQ命令] /绑定 异常: ${safeMessage(error)}")
            sendDirect(event, "❌ 绑定处理异常：${safeMessage(error)}")
            return
        }

        if (success) {
            QqBindManager.logQuiet("[QQ命令] /绑定 成功 code=$code qq=$qq")
            sendDirect(event, "✅ QQ 绑定成功！现在可以重新进入服务器了。")
        } else {
            QqBindManager.logQuiet("[QQ命令] /绑定 失败 code=$code 无效或过期")
            sendDirect(event, "❌ 绑定码无效或已过期。\n请重新进入服务器获取新的绑定码后再试。")
        }
    }

    /** 带 @ 提及的 Markdown 回复（失败时退化为纯文本）。 */
    private fun sendDirect(event: GroupMessageEvent, message: String) {
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

    /** 安全提取发送者 OpenId（失败返回占位值）。 */
    private fun safeUserId(event: GroupMessageEvent): String = try {
        userId(event)
    } catch (_: Throwable) {
        "<unknown>"
    }

    /** 异常消息文本（空则用 toString）。 */
    private fun safeMessage(throwable: Throwable): String =
        throwable.message?.takeIf { it.isNotEmpty() } ?: throwable.toString()
}
