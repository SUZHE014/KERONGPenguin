package cn.huohuas001.huhobotPenguin.spigot.qqbind

import cn.huohuas001.bot.QClient
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/**
 * QQ 群消息发送工具。
 *
 * 艾特原理（与 QClient.broadcastGameMessage 一致）：
 * - 艾特格式：`<@openid>`（QQ 不解析 `<qqbot-at-user>`）
 * - 必须用 msg_type=2 + Markdown 模式发送，QQ 才解析 `<@openid>` 艾特；
 * - 纯文本模式（msg_type=0）不解析艾特标签，会原样显示。
 *
 * 引用原理：
 * - QClient.replyMarkdown 内部 setMsg_id(msgId) 实现引用；
 * - Markdown 模式（msg_type=2）渲染引用框。
 */
object GroupMsgSender {

    /** 发送带艾特的命令回复（Markdown 模式，失败回退纯文本）。 */
    fun sendWithMention(event: GroupMessageEvent, content: String) {
        try {
            QClient.replyMarkdown(event, content, null)
        } catch (t: Throwable) {
            QqBindManager.logQuiet("[消息发送] replyMarkdown 失败: ${t.message}，回退 sendMessage")
            try {
                event.sendMessage(content)
            } catch (_: Throwable) {
            }
        }
    }

    /** 发送 AI 引用回复（Markdown 模式 + msg_id 引用，失败回退纯文本）。 */
    fun sendReply(event: GroupMessageEvent, content: String) {
        try {
            QClient.replyMarkdown(event, content, null)
            QqBindManager.logQuiet("[AI引用] replyMarkdown 发送成功，内容长度=${content.length}")
        } catch (t: Throwable) {
            QqBindManager.logQuiet("[AI引用] replyMarkdown 失败: ${t.message}，回退 sendMessage")
            try {
                event.sendMessage(content)
                QqBindManager.logQuiet("[AI引用] sendMessage 回退发送成功")
            } catch (t2: Throwable) {
                QqBindManager.logQuiet("[AI引用] sendMessage 也失败: ${t2.message}")
            }
        }
    }
}
