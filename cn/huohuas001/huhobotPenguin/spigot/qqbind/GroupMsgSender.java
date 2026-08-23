package cn.huohuas001.huhobotPenguin.spigot.qqbind;

import cn.huohuas001.bot.HuHoBot;
import cn.huohuas001.bot.QClient;
import cn.huohuas001.bot.provider.BotShared;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;

/**
 * QQ 群消息发送工具（支持艾特和引用）
 *
 * 原理：
 * - 纯文本（msg_type=0）：不解析 <@openid> 艾特标签
 * - Markdown（msg_type=2）：解析 <@openid> 艾特标签，支持 msg_id 引用
 *
 * 实现方式：复用 QClient.sendMarkdownToGroup / QClient.replyMarkdown
 * （这两个方法已在查在线功能中验证可用）
 */
public final class GroupMsgSender {
    private GroupMsgSender() {}

    /**
     * 发送带艾特的消息（使用 Markdown 模式，QQ 才能解析 <@openid> 标签）。
     * @param event 群消息事件
     * @param content 已含 <@openid> 标签的内容
     */
    public static void sendWithMention(GroupMessageEvent event, String content) {
        try {
            String groupOpenId = null;
            try { groupOpenId = event.getGroupOpenId(); } catch (Throwable ignored) {}
            if (groupOpenId == null || groupOpenId.isEmpty()) {
                try { groupOpenId = event.getGroupId(); } catch (Throwable ignored) {}
            }
            if (groupOpenId == null || groupOpenId.isEmpty()) {
                event.sendMessage(content);
                return;
            }
            QClient.INSTANCE.sendMarkdownToGroup(groupOpenId, content, null);
        } catch (Throwable t) {
            QqBindManager.logQuiet("[消息发送] 艾特消息失败: " + t.getMessage());
            try { event.sendMessage(content); } catch (Throwable ignored) {}
        }
    }

    /**
     * 发送引用回复消息（引用 event 对应的消息，使用 Markdown + msg_id）。
     * @param event 群消息事件
     * @param content 回复内容
     */
    public static void sendReply(GroupMessageEvent event, String content) {
        try {
            QClient.INSTANCE.replyMarkdown(event, content, null);
        } catch (Throwable t) {
            QqBindManager.logQuiet("[消息发送] 引用回复失败: " + t.getMessage());
            try { event.sendMessage(content); } catch (Throwable ignored) {}
        }
    }
}
