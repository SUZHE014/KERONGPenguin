package cn.kerong.bot.events.commands

import cn.kerong.bot.KERONG
import cn.kerong.penguin.spigot.qqbind.MessageTemplates
import cn.kerong.penguin.spigot.qqbind.QqBindManager
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
    fun bind(plugin: KERONG, event: GroupMessageEvent, params: String) {
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
        val result = try {
            manager.confirmBinding(code, qq)
        } catch (error: Throwable) {
            QqBindManager.logQuiet("[QQ命令] /绑定 异常: ${safeMessage(error)}")
            MessageTemplates.sendDirect(event, "bind-error", mapOf("error" to safeMessage(error)))
            return
        }

        when (result) {
            QqBindManager.BindResult.SUCCESS -> {
                QqBindManager.logQuiet("[QQ命令] /绑定 成功 code=$code qq=$qq")
                MessageTemplates.sendDirect(event, "bind-success")
            }
            // 1.7.4：已达每个 QQ 可绑定玩家数上限（绑定码保留，解绑后重发同一码仍可绑定）
            QqBindManager.BindResult.LIMIT -> {
                QqBindManager.logQuiet("[QQ命令] /绑定 拒绝：已达绑定上限 code=$code qq=$qq")
                MessageTemplates.sendDirect(event, "bind-limit", mapOf(
                    "count" to manager.listBoundPlayersByQq(qq).size.toString(),
                    "max" to manager.maxPlayersPerQq.toString(),
                ))
            }
            // 黑名单与无效码统一回复 bind-invalid（旧版行为：不暴露黑名单状态）
            else -> {
                QqBindManager.logQuiet("[QQ命令] /绑定 失败 code=$code 无效或过期")
                MessageTemplates.sendDirect(event, "bind-invalid")
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
