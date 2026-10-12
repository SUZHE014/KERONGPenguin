package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.huhobotPenguin.spigot.qqbind.MessageTemplates
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/**
 * QQ 绑定命令：玩家进服获取绑定码后在群内发送 /绑定 <绑定码> 完成绑定。
 *
 * 1.6.0：全部文本回复改走消息模板（bind-usage / bind-disabled / bind-error /
 * bind-success / bind-invalid，配置 messages 段可换文件），默认内容与旧版一致。
 */
class BindCommands : CommandSupport() {

    /** /绑定 [绑定码] —— 提交绑定码完成 QQ 绑定。 */
    @Commands("绑定", "bind")
    fun bind(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val manager = QqBindManager.getInstance()
        val code = params?.trim() ?: ""
        QqBindManager.logVerbose("[QQ命令] /绑定 code=$code qq=${safeUserId(event)}")

        if (code.isEmpty()) {
            try {
                if (manager.isEnabled) {
                    MessageTemplates.sendDirect(event, "bind-usage")
                } else {
                    MessageTemplates.sendDirect(event, "bind-disabled")
                }
            } catch (_: Throwable) {
                MessageTemplates.sendDirect(event, "bind-usage-fallback")
            }
            return
        }

        val qq = safeUserId(event)
        val success = try {
            manager.confirmBinding(code, qq)
        } catch (error: Throwable) {
            QqBindManager.logQuiet("[QQ命令] /绑定 异常: ${safeMessage(error)}")
            MessageTemplates.sendDirect(event, "bind-error", mapOf("error" to safeMessage(error)))
            return
        }

        if (success) {
            QqBindManager.logQuiet("[QQ命令] /绑定 成功 code=$code qq=$qq")
            MessageTemplates.sendDirect(event, "bind-success")
        } else {
            QqBindManager.logQuiet("[QQ命令] /绑定 失败 code=$code 无效或过期")
            MessageTemplates.sendDirect(event, "bind-invalid")
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
