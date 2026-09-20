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
 *
 * 1.5.3：AI 对话回复改为引用发送者的原消息（QQ 官方 MessageReference 协议，
 * 见 QClient.replyMarkdownWithReference），失败自动回退普通被动回复。
 */
object GroupMsgSender {

    /** 发送带艾特的命令回复（Markdown 模式，失败回退纯文本）。 */
    fun sendWithMention(event: GroupMessageEvent, content: String) {
        try {
            QClient.replyMarkdown(event, content, null)
        } catch (t: Throwable) {
            QqBindManager.logVerbose("[消息发送] replyMarkdown 失败: ${t.message}，回退 sendMessage")
            try {
                event.sendMessage(content)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 发送 AI 引用回复（1.5.3：Markdown 模式 + message_reference 引用发送者原消息）。
     *
     * 引用 ID 提取顺序（与官方文档一致）：
     * 新版事件 MessageScene.ext 数组的 msg_idx（REFIDX 形式）→ 事件 msg_id → rawMessage.id；
     * 平台不支持引用协议时自动回退普通被动回复（既有行为），AI 回复不会丢失。
     */
    fun sendReply(event: GroupMessageEvent, content: String) {
        QClient.replyMarkdownWithReference(event, content)
    }
}
