package cn.huohuas001.huhobotPenguin.spigot.qqbind;

import cn.huohuas001.bot.QClient;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.github.kloping.qqbot.Starter;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;
import java.lang.reflect.Method;
import java.util.List;

/**
 * QQ 群消息发送工具（支持艾特和引用）
 *
 * QQ 官方机器人 API v2：
 * - 纯文本（msg_type=0）：不解析 <@openid> 艾特标签
 * - Markdown（msg_type=2）：解析 <@openid> 艾特标签
 * - msg_id 字段：引用指定消息
 *
 * 参考 HuHoBot 1.2.2 QClient.broadcastGameMessage 实现。
 */
public final class GroupMsgSender {
    private GroupMsgSender() {}

    /**
     * 发送带艾特的消息（使用 Markdown 模式，QQ 才能解析 <@openid> 标签）。
     * @param event 群消息事件
     * @param content 已含 <qqbot-at-user id="..." /> 或 <@openid> 标签的内容
     */
    public static void sendWithMention(GroupMessageEvent event, String content) {
        try {
            sendDirect(event, content, null, true);
        } catch (Throwable t) {
            QqBindManager.logQuiet("[消息发送] 艾特消息失败: " + t.getMessage());
            try { event.sendMessage(content); } catch (Throwable ignored) {}
        }
    }

    /**
     * 发送引用回复消息（引用 event 对应的消息）。
     * @param event 群消息事件
     * @param content 回复内容
     */
    public static void sendReply(GroupMessageEvent event, String content) {
        try {
            String msgId = null;
            try { msgId = event.getRawMessage().getId(); } catch (Throwable ignored) {}
            sendDirect(event, content, msgId, true);
        } catch (Throwable t) {
            QqBindManager.logQuiet("[消息发送] 引用回复失败: " + t.getMessage());
            try { event.sendMessage(content); } catch (Throwable ignored) {}
        }
    }

    /**
     * 发送普通文本消息（不带艾特，不带引用）。
     */
    public static void sendPlain(GroupMessageEvent event, String content) {
        try {
            sendDirect(event, content, null, false);
        } catch (Throwable t) {
            try { event.sendMessage(content); } catch (Throwable ignored) {}
        }
    }

    /**
     * 直接发送（通过反射调用 groupBaseV2.send）。
     *
     * @param event 群消息事件
     * @param content 消息内容
     * @param msgId 引用消息 ID（null=不引用）
     * @param useMarkdown 是否用 Markdown 模式（true=解析艾特标签）
     */
    private static void sendDirect(GroupMessageEvent event, String content, String msgId, boolean useMarkdown) throws Exception {
        Starter starter = QClient.INSTANCE.getStarter();
        if (starter == null) {
            event.sendMessage(content);
            return;
        }
        Object bot = starter.getBot();
        if (bot == null) {
            event.sendMessage(content);
            return;
        }
        String groupOpenId = null;
        try { groupOpenId = event.getGroupOpenId(); } catch (Throwable ignored) {}
        if (groupOpenId == null || groupOpenId.isEmpty()) {
            try { groupOpenId = event.getGroupId(); } catch (Throwable ignored) {}
        }
        if (groupOpenId == null || groupOpenId.isEmpty()) {
            event.sendMessage(content);
            return;
        }
        JSONObject payload = new JSONObject();
        if (useMarkdown) {
            JSONObject markdown = new JSONObject();
            markdown.put("content", content);
            payload.put("msg_type", 2);
            payload.put("markdown", markdown);
        } else {
            payload.put("msg_type", 0);
            payload.put("content", content);
        }
        if (msgId != null && !msgId.isEmpty()) {
            payload.put("msg_id", msgId);
        }
        String json = payload.toJSONString();
        Object groupBaseV2;
        try {
            Method g = bot.getClass().getMethod("getGroupBaseV2");
            groupBaseV2 = g.invoke(bot);
        } catch (Throwable t) {
            event.sendMessage(content);
            return;
        }
        if (groupBaseV2 == null) {
            event.sendMessage(content);
            return;
        }
        Class<?> headersClass = null;
        try {
            headersClass = Class.forName("io.github.kloping.qqbot.http.Channel");
            for (java.lang.reflect.Field f : headersClass.getDeclaredFields()) {
                if ("SEND_MESSAGE_HEADERS".equals(f.getName())) {
                    headersClass = null;
                    Object headers = f.get(null);
                    Method send = groupBaseV2.getClass().getMethod("send", String.class, String.class, headers == null ? Object.class : headers.getClass());
                    send.invoke(groupBaseV2, groupOpenId, json, headers);
                    return;
                }
            }
            headersClass = null;
        } catch (Throwable ignored) {}
        if (headersClass == null) {
            try {
                Method send = groupBaseV2.getClass().getMethod("send", String.class, String.class, Object.class);
                send.invoke(groupBaseV2, groupOpenId, json, null);
            } catch (Throwable t) {
                event.sendMessage(content);
            }
        }
    }
}
