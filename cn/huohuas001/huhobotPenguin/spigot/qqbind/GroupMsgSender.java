package cn.huohuas001.huhobotPenguin.spigot.qqbind;

import io.github.kloping.qqbot.api.v2.GroupMessageEvent;

/**
 * QQ 群消息发送工具
 *
 * 原理（参考 1.2.2 BaseGroupMessageEvent.sendMessage）：
 * event.sendMessage(String) 内部自动设置：
 *   V2MsgData.setMsg_id(this.getMsgId()).setContent(text).setMsg_seq(seq)
 * 即纯文本模式（msg_type=0）自带 msg_id 引用，QQ 客户端显示蓝色引用框。
 *
 * 1.2.2 的所有命令回复都用 reply() → event.sendMessage()，无 <@openid> 标签。
 */
public final class GroupMsgSender {
    private GroupMsgSender() {}

    /**
     * 发送命令回复（纯文本，自带 msg_id 引用，QQ 显示蓝色引用框）。
     * 参考 1.2.2 BaseGroupMessageEvent.sendMessage。
     */
    public static void sendWithMention(GroupMessageEvent event, String content) {
        try {
            event.sendMessage(content);
        } catch (Throwable t) {
            QqBindManager.logQuiet("[消息发送] 失败: " + t.getMessage());
        }
    }

    /**
     * 发送引用回复（同 sendWithMention，sendMessage 自带 msg_id 引用）。
     */
    public static void sendReply(GroupMessageEvent event, String content) {
        try {
            event.sendMessage(content);
            QqBindManager.logQuiet("[AI引用] 发送成功，内容长度=" + (content == null ? 0 : content.length()));
        } catch (Throwable t) {
            QqBindManager.logQuiet("[AI引用] 发送失败: " + t.getMessage());
            try { event.sendMessage(content); } catch (Throwable ignored) {}
        }
    }
}
