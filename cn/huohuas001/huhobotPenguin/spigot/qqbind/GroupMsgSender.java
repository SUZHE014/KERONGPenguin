package cn.huohuas001.huhobotPenguin.spigot.qqbind;

import cn.huohuas001.bot.QClient;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;

/**
 * QQ 群消息发送工具
 *
 * 命令回复（sendWithMention）：
 *   用 event.sendMessage（msg_type=0 纯文本）+ <qqbot-at-user id="openid" /> 标签
 *   QQ 官方机器人 API 在纯文本模式解析此标签为蓝色艾特
 *   参考 At.toString() 新格式：<qqbot-at-user id="..." />
 *   sendMessage 自带 msg_id，实现引用回复效果
 *
 * AI 对话回复（sendReply）：
 *   用 QClient.replyMarkdown（msg_type=2 + markdown + msg_id）
 *   Markdown 模式更好地渲染引用框
 */
public final class GroupMsgSender {
    private GroupMsgSender() {}

    /**
     * 发送带艾特的命令回复（纯文本 + <qqbot-at-user> 标签 + msg_id 引用）。
     */
    public static void sendWithMention(GroupMessageEvent event, String content) {
        try {
            event.sendMessage(content);
        } catch (Throwable t) {
            QqBindManager.logQuiet("[消息发送] 失败: " + t.getMessage());
            try { event.sendMessage(content); } catch (Throwable ignored) {}
        }
    }

    /**
     * 发送 AI 引用回复（Markdown 模式 + msg_id 引用）。
     * replyMarkdown 内部：setMsg_id + setMsg_type(2) + setMarkdown + setContent
     */
    public static void sendReply(GroupMessageEvent event, String content) {
        try {
            QClient.INSTANCE.replyMarkdown(event, content, null);
            QqBindManager.logQuiet("[AI引用] replyMarkdown 发送成功，内容长度=" + (content == null ? 0 : content.length()));
        } catch (Throwable t) {
            QqBindManager.logQuiet("[AI引用] replyMarkdown 失败: " + t.getMessage() + "，回退到 sendMessage");
            try {
                event.sendMessage(content);
                QqBindManager.logQuiet("[AI引用] sendMessage 回退发送成功");
            } catch (Throwable t2) {
                QqBindManager.logQuiet("[AI引用] sendMessage 也失败: " + t2.getMessage());
            }
        }
    }
}

