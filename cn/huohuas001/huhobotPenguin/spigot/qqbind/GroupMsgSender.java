package cn.huohuas001.huhobotPenguin.spigot.qqbind;

import cn.huohuas001.bot.QClient;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;

/**
 * QQ 群消息发送工具（支持艾特和引用）
 *
 * 原理：
 * - QClient.replyMarkdown 内部构造 V2MsgData.setMsg_id().setMsg_type(2).setMarkdown().setContent()
 *   同时设置 content + markdown + msg_id + msg_type=2
 * - QQ 官方机器人 API v2 在 msg_type=2 时解析 <@openid> 艾特标签
 * - msg_id 设置后实现引用回复效果
 *
 * 参考 1.2.2 QClient.broadcastGameMessage 的实现。
 */
public final class GroupMsgSender {
    private GroupMsgSender() {}

    /**
     * 发送带艾特的命令回复（Markdown 模式，QQ 解析 <@openid> 标签 + msg_id 引用）。
     * @param event 群消息事件
     * @param content 已含 <@openid> 标签的内容
     */
    public static void sendWithMention(GroupMessageEvent event, String content) {
        try {
            QClient.INSTANCE.replyMarkdown(event, content, null);
        } catch (Throwable t) {
            QqBindManager.logQuiet("[消息发送] 艾特消息失败: " + t.getMessage());
            try { event.sendMessage(content); } catch (Throwable ignored) {}
        }
    }

    /**
     * 发送引用回复（同 sendWithMention，replyMarkdown 自带 msg_id 引用）。
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
