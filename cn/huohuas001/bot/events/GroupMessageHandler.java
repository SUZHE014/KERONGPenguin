package cn.huohuas001.bot.events;
import cn.huohuas001.bot.HuHoBot;
import cn.huohuas001.bot.events.commands.*;
import cn.huohuas001.bot.state.CommandRepositories;
import cn.huohuas001.huhobotPenguin.spigot.qqbind.AiChat;
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;
import io.github.kloping.qqbot.entities.qqpd.User;
import io.github.kloping.qqbot.entities.qqpd.v2.Contact;
import io.github.kloping.qqbot.impl.ListenerHost;
import io.github.kloping.qqbot.impl.message.v2.BaseMessageEvent;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class GroupMessageHandler extends ListenerHost {
    private final HuHoBot plugin;
    private final CopyOnWriteArrayList<BaseCommand> commands = new CopyOnWriteArrayList<>();
    public GroupMessageHandler(HuHoBot plugin) { this.plugin = plugin; this.commands.add(new PublicCommands()); this.commands.add(new AdministrationCommands()); this.commands.add(new MotdCommands()); this.commands.add(new BindCommands()); }
    public void registerCommand(BaseCommand command) { if (command != null) this.commands.add(command); }
    @ListenerHost.EventReceiver
    public void onGroupMessage(GroupMessageEvent event) {
        if (event == null) return;
        String groupId = event.getGroupOpenId(); if (groupId == null) groupId = event.getGroupId();
        String content = event.getRawMessage().getContent(); if (content == null) return;
        if (!content.contains("查信息")) { if (groupId == null || !isAllowedGroup(groupId)) return; }
        if (dispatchCommand(event)) return;
        if (content.contains("<@") && !content.trim().startsWith("/")) {
            boolean mentioned = isMentionedBot(event);
            if (mentioned) { handleAiChat(event, content, groupId); return; }
        }
        if (groupId != null) forwardFullGroupMessage(groupId, event);
    }
    private boolean isAllowedGroup(String groupId) { List<String> allowed = plugin.getGroupOpenIdList(); return allowed.isEmpty() || allowed.contains(groupId); }
    private boolean dispatchCommand(GroupMessageEvent event) { for (BaseCommand c : commands) { try { if (c.handleMessage(plugin, event)) return true; } catch (Exception e) { plugin.log_error("指令处理异常: " + e.getMessage()); } } return false; }
    private boolean isMentionedBot(GroupMessageEvent event) {
        String botSelfId = null;
        try { io.github.kloping.qqbot.Starter starter = cn.huohuas001.bot.QClient.INSTANCE.getStarter(); if (starter != null && starter.getBot() != null) botSelfId = starter.getBot().getId(); } catch (Throwable ignored) {}
        String botAppId = this.plugin.getBotAppId();
        try { User[] mentions = event.getRawMessage().getMentions(); if (mentions != null) for (int i = 0; i < mentions.length; i++) { User m = mentions[i]; String mid = m.getId(); if (mid != null && botSelfId != null && !botSelfId.isEmpty() && mid.equals(botSelfId)) return true; try { Boolean isBot = m.getBot(); if (isBot != null && isBot) return true; } catch (Throwable ignored) {} if (mid != null && botAppId != null && !botAppId.isEmpty() && mid.equals(botAppId)) return true; } } catch (Throwable ignored) {}
        try { String c = event.getRawMessage().getContent(); if (c != null) { if (botSelfId != null && !botSelfId.isEmpty() && (c.contains("<@!" + botSelfId + ">") || c.contains("<@" + botSelfId + ">"))) return true; if (botAppId != null && !botAppId.isEmpty() && (c.contains("<@!" + botAppId + ">") || c.contains("<@" + botAppId + ">"))) return true; } } catch (Throwable ignored) {}
        return false;
    }
    private void handleAiChat(GroupMessageEvent event, String content, String groupId) {
        try {
            QqBindManager mgr; try { mgr = QqBindManager.getInstance(); } catch (Throwable t) { return; }
            if (!mgr.isAiEnabled()) return;
            String text = content; User[] mentions = event.getRawMessage().getMentions();
            if (mentions != null) for (int i = 0; i < mentions.length; i++) { User m = mentions[i]; String mid = m.getId(); if (mid == null) continue; text = text.replace("<@!" + mid + ">", "").replace("<@" + mid + ">", "").replace("<" + mid + ">", "").replace(mid, ""); }
            text = text.trim(); if (text.isEmpty()) { event.sendMessage("请在 @我 之后输入你想说的话～"); return; }
            String userId = safeUserId(event);
            String reply = AiChat.chat(text, mgr, groupId, userId);
            event.sendMessage(mgr.getQqAiOutputPrefix() + " " + reply);
        } catch (Throwable t) { event.sendMessage("AI 对话失败：" + t.getMessage()); }
    }
    private String safeUserId(GroupMessageEvent event) { try { Contact c = event.getSender(); if (c == null) return "<unknown>"; String oid = c.getOpenid(); if (oid == null) oid = c.getId(); return oid == null ? "<unknown>" : oid; } catch (Throwable t) { return "<unknown>"; } }
    @SuppressWarnings("unchecked")
    private void forwardFullGroupMessage(String groupId, GroupMessageEvent event) {
        try {
            boolean enabled = CommandRepositories.INSTANCE.getGroupSettings().fullForwarding(groupId, plugin.getFullAmount());
            if (!enabled || !plugin.getChatFormat().getPostChat()) return;
            String senderName = "unknown"; Contact contact = event.getSender(); if (contact != null) { String un = contact.getUsername(); if (un != null) senderName = un; }
            BaseMessageEvent baseMsg = event instanceof BaseMessageEvent ? (BaseMessageEvent) event : null;
            JSONObject metadata = baseMsg != null ? baseMsg.getMetadata() : null;
            JSONArray atts = metadata != null ? metadata.getJSONArray("attachments") : null;
            List<String> parts = new ArrayList<>(); String raw = event.getRawMessage().getContent(); String tc = raw != null ? raw.trim() : ""; if (tc.length() > 0) parts.add(tc);
            if (atts != null) { int n = ((Collection<Object>) atts).size(); for (int i = 0; i < n; i++) { JSONObject a = atts.getJSONObject(i); if (a == null) continue; String ct = a.getString("content_type"); if (ct == null) ct = ""; if ("voice".equals(ct)) { String asr = a.getString("asr_refer_text"); parts.add(asr != null && !asr.trim().isEmpty() ? "[语音] [" + asr.trim() + "]" : "[语音]"); } else if (ct.startsWith("image/")) parts.add("[图片]"); else if ("image/gif".equals(ct)) parts.add("[表情包]"); else if (ct.startsWith("video/")) parts.add("[视频]"); else { String fn = a.getString("filename"); parts.add("[文件: " + (fn == null ? "文件" : fn) + "]"); } } }
            if (parts.isEmpty()) return; StringBuilder sb = new StringBuilder(); for (int i = 0; i < parts.size(); i++) { if (i > 0) sb.append(' '); sb.append(parts.get(i)); } String msg = sb.toString();
            User[] mentions = event.getRawMessage().getMentions(); if (mentions != null) for (int i = 0; i < mentions.length; i++) { User m = mentions[i]; String mid = m.getId(), mname = m.getUsername(); if (mid == null || mname == null) continue; msg = msg.replace("<@!" + mid + ">", "@" + mname).replace("<@" + mid + ">", "@" + mname).replace("<" + mid + ">", "@" + mname).replace(mid, "@" + mname); }
            plugin.broadcastMessage(plugin.formatGroupMessage(senderName, plugin.auditText(msg)));
        } catch (Throwable ignored) {}
    }
}
