package cn.huohuas001.huhobotPenguin.spigot.qqbind;

import cn.huohuas001.bot.QClient;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;

/**
 * QQ 群消息发送工具
 *
 * 艾特原理（参考 1.2.2 QClient.broadcastGameMessage）：
 * - 艾特格式：<@openid>（不是 <qqbot-at-user>，后者 QQ 不解析）
 * - 必须用 msg_type=2 + Markdown 模式发送，QQ 才解析 <@openid> 艾特
 * - 纯文本模式（msg_type=0）不解析艾特标签，会原样显示
 *
 * 引用原理：
 * - QClient.replyMarkdown 内部 setMsg_id(getMsgId) 实现引用
 * - Markdown 模式（msg_type=2）渲染引用框
 */
public final class GroupMsgSender {
    private GroupMsgSender() {}

    /**
     * 发送带艾特的命令回复。
     * 用 <@openid> 格式 + replyMarkdown（msg_type=2 Markdown），QQ 解析为艾特 + 引用。
     */
    public static void sendWithMention(GroupMessageEvent event, String content) {
        try {
            QClient.INSTANCE.replyMarkdown(event, content, null);
        } catch (Throwable t) {
            QqBindManager.logQuiet("[消息发送] replyMarkdown 失败: " + t.getMessage() + "，回退 sendMessage");
            try { event.sendMessage(content); } catch (Throwable ignored) {}
        }
    }

    /**
     * 发送 AI 引用回复（Markdown 模式 + msg_id 引用）。
     */
    public static void sendReply(GroupMessageEvent event, String content) {
        try {
            QClient.INSTANCE.replyMarkdown(event, content, null);
            QqBindManager.logQuiet("[AI引用] replyMarkdown 发送成功，内容长度=" + (content == null ? 0 : content.length()));
        } catch (Throwable t) {
            QqBindManager.logQuiet("[AI引用] replyMarkdown 失败: " + t.getMessage() + "，回退 sendMessage");
            try {
                event.sendMessage(content);
                QqBindManager.logQuiet("[AI引用] sendMessage 回退发送成功");
            } catch (Throwable t2) {
                QqBindManager.logQuiet("[AI引用] sendMessage 也失败: " + t2.getMessage());
            }
        }
    }
}
