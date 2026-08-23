package cn.huohuas001.huhobotPenguin.spigot.qqbind;

import io.github.kloping.qqbot.api.v2.GroupMessageEvent;

/**
 * QQ 群消息发送工具
 *
 * 原理：
 * event.sendMessage(String) 内部自动设置 msg_id（引用原消息），
 * 纯文本模式（msg_type=0）即可实现引用回复效果。
 *
 * 参考 1.0.3.3 版本的回复方式：直接 event.sendMessage(content)。
 */
public final class GroupMsgSender {
    private GroupMsgSender() {}

    /**
     * 发送命令回复（纯文本，自带 msg_id 引用）。
     * 参考 1.0.3.3 版本方式：直接 event.sendMessage(content)。
     */
    public static void sendWithMention(GroupMessageEvent event, String content) {
        try {
            event.sendMessage(content);
        } catch (Throwable t) {
            QqBindManager.logQuiet("[消息发送] 失败: " + t.getMessage());
        }
    }

    /**
     * 发送引用回复（纯文本，自带 msg_id 引用）。
     * event.sendMessage 内部自动设置 msg_id 实现引用效果。
     */
    public static void sendReply(GroupMessageEvent event, String content) {
        try {
            event.sendMessage(content);
        } catch (Throwable t) {
            QqBindManager.logQuiet("[消息发送] 引用回复失败: " + t.getMessage());
        }
    }
}
