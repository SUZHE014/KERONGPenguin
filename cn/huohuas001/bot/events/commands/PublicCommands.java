/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.github.kloping.qqbot.api.v2.GroupMessageEvent
 *  io.github.kloping.qqbot.entities.qqpd.User
 */
package cn.huohuas001.bot.events.commands;

import cn.huohuas001.bot.HuHoBot;
import cn.huohuas001.bot.events.commands.CommandSupport;
import cn.huohuas001.bot.events.commands.Commands;
import cn.huohuas001.bot.provider.Motd;
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;
import io.github.kloping.qqbot.entities.qqpd.User;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class PublicCommands
extends CommandSupport {
    @Commands(value={"\u67e5\u4fe1\u606f"})
    public void queryInfo(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        if (string == null || string.trim().isEmpty()) {
            this.sendDirect(groupMessageEvent, "\u4f60\u7684OpenId: " + this.userId(groupMessageEvent) + "\n\u7fa4\u7684OpenId: " + this.groupId(groupMessageEvent));
            return;
        }
        this.sendDirect(groupMessageEvent, "\u67e5\u8be2\u73a9\u5bb6\u7ed1\u5b9a\u72b6\u6001\u8bf7\u5728\u670d\u52a1\u5668\u5185\u4f7f\u7528 /qq qxqq <\u73a9\u5bb6\u540d>");
    }

    @Commands(value={"\u53d1\u4fe1\u606f"})
    public void sendGameMessage(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        if (string == null || string.trim().isEmpty()) {
            return;
        }
        String string2 = huHoBot.auditText(string);
        if (huHoBot.getChatFormat().getPostChat()) {
            // 获取发送者QQ名称（而非OpenId），过滤注入字符
            String senderName = this.userId(groupMessageEvent);
            try {
                io.github.kloping.qqbot.entities.qqpd.v2.Contact contact = groupMessageEvent.getSender();
                if (contact != null && contact.getUsername() != null) senderName = contact.getUsername();
            } catch (Throwable ignored) {}
            huHoBot.broadcastMessage(huHoBot.formatGroupMessage(senderName, string2));
        } else {
            groupMessageEvent.sendMessage("\u7fa4\u804a\u8f6c\u53d1\u529f\u80fd\u5df2\u5173\u95ed");
        }
    }

    @Commands(value={"\u67e5\u5728\u7ebf"})
    public void queryOnline(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        List<String> list = huHoBot.getOnlineList();
        Motd motd = huHoBot.getMotd();
        String string2 = huHoBot.getMarkdown("queryOnline");
        if (string2 == null) {
            String string3 = motd.getText();
            if (string3 == null || string3.isEmpty()) {
                string3 = "\u5728\u7ebf\u4eba\u6570: {online}\n{players}";
            }
            StringBuilder stringBuilder = new StringBuilder();
            for (int i = 0; i < list.size(); ++i) {
                if (i > 0) {
                    stringBuilder.append("\n");
                }
                stringBuilder.append(i + 1).append(". ").append(list.get(i));
            }
            this.sendDirect(groupMessageEvent, string3.replace("{online}", String.valueOf(list.size())).replace("{players}", stringBuilder.toString()));
            return;
        }
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < list.size(); ++i) {
            if (i > 0) {
                stringBuilder.append("\n");
            }
            stringBuilder.append(i + 1).append(". **").append(list.get(i)).append("**");
        }
        long l = System.currentTimeMillis() / 1000L;
        String imgUrl = motd.getApi().replace("{ip}", motd.getServerIP()).replace("{port}", String.valueOf(motd.getServerPort()));
        if (imgUrl != null && !imgUrl.isEmpty()) {
            String sep = imgUrl.contains("?") ? "&" : "?";
            imgUrl = imgUrl + sep + "_t=" + l;
        }
        Object object = imgUrl;
        String string4 = string2.replace("{{.server}}", huHoBot.getServerName()).replace("{{.online_num}}", String.valueOf(list.size())).replace("{{.player}}", stringBuilder.toString()).replace("{{.players}}", stringBuilder.toString()).replace("{{.img_url}}", (CharSequence)object).replace("{online}", String.valueOf(list.size())).replace("{players}", stringBuilder.toString()).replace("{server}", huHoBot.getServerName()).replace("{img_url}", (CharSequence)object);
        try {
            huHoBot.replyMarkdown(groupMessageEvent, string4, null);
        }
        catch (Throwable throwable) {
            this.sendDirect(groupMessageEvent, string4);
        }
    }

    @Commands(value={"\u5728\u7ebf\u670d\u52a1\u5668"})
    public void queryServers(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        Motd motd = huHoBot.getMotd();
        Object object = motd.getText();
        if (object == null || ((String)object).isEmpty()) {
            object = "\u670d\u52a1\u5668: " + motd.getServerIP() + ":" + motd.getServerPort();
        }
        this.sendDirect(groupMessageEvent, (String)object);
    }

    @Commands(value={"AI\u5bf9\u8bdd\u4e0a\u4e0b\u6587"})
    public void aiContextToggle(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        QqBindManager qqBindManager;
        if (!this.requireAdmin(huHoBot, groupMessageEvent)) {
            return;
        }
        try {
            qqBindManager = QqBindManager.getInstance();
        }
        catch (Throwable throwable) {
            this.sendDirect(groupMessageEvent, "\u274c \u7ed1\u5b9a\u7ba1\u7406\u5668\u672a\u5c31\u7eea");
            return;
        }
        String string2 = string == null ? "" : string.trim();
        String string3 = this.groupId(groupMessageEvent);
        String string4 = this.userId(groupMessageEvent);
        if (string2.equals("\u5f00") || string2.equalsIgnoreCase("on") || string2.equals("true")) {
            qqBindManager.setAiContextEnabled(string3, string4, true);
            this.sendDirect(groupMessageEvent, "\u2705 AI \u5bf9\u8bdd\u4e0a\u4e0b\u6587\u5df2\u5168\u5c40\u5f00\u542f\uff0c\u5c06\u4fdd\u7559\u6700\u8fd1\u5bf9\u8bdd\u5386\u53f2\u3002");
        } else if (string2.equals("\u5173") || string2.equalsIgnoreCase("off") || string2.equals("false")) {
            qqBindManager.setAiContextEnabled(string3, string4, false);
            this.sendDirect(groupMessageEvent, "\u2705 AI \u5bf9\u8bdd\u4e0a\u4e0b\u6587\u5df2\u5168\u5c40\u5173\u95ed\uff0c\u6bcf\u6b21\u5bf9\u8bdd\u72ec\u7acb\u3002");
        } else {
            boolean bl = qqBindManager.isAiContextEnabled(string3, string4);
            this.sendDirect(groupMessageEvent, "\u7528\u6cd5: /AI\u5bf9\u8bdd\u4e0a\u4e0b\u6587 \u5f00|\u5173\n\u5f53\u524d\u72b6\u6001: " + (bl ? "\u5f00" : "\u5173"));
        }
    }

    @Commands(value={"\u6e05\u9664\u5f53\u524d\u4e0a\u4e0b\u6587"})
    public void clearAiContext(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        QqBindManager qqBindManager;
        if (!this.requireAdmin(huHoBot, groupMessageEvent)) {
            return;
        }
        try {
            qqBindManager = QqBindManager.getInstance();
        }
        catch (Throwable throwable) {
            this.sendDirect(groupMessageEvent, "\u274c \u7ed1\u5b9a\u7ba1\u7406\u5668\u672a\u5c31\u7eea");
            return;
        }
        qqBindManager.clearAiContext(this.groupId(groupMessageEvent), this.userId(groupMessageEvent));
        this.sendDirect(groupMessageEvent, "\u2705 \u5df2\u6e05\u9664\u5168\u5c40 AI \u5bf9\u8bdd\u4e0a\u4e0b\u6587\u3002");
    }

    @Commands(value={"\u9ed1\u540d\u5355"})
    public void blacklist(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        String string2;
        QqBindManager qqBindManager;
        if (!this.requireAdmin(huHoBot, groupMessageEvent)) {
            return;
        }
        try {
            qqBindManager = QqBindManager.getInstance();
        }
        catch (Throwable throwable) {
            this.sendDirect(groupMessageEvent, "\u274c \u7ba1\u7406\u5668\u672a\u5c31\u7eea");
            return;
        }
        String[] stringArray = this.extractMentionedQqInfo(groupMessageEvent);
        String string3 = stringArray != null ? stringArray[0] : null;
        String string4 = string2 = stringArray != null ? stringArray[1] : null;
        if (string3 == null || string3.isEmpty()) {
            List<String> list = qqBindManager.listBlacklist();
            if (list.isEmpty()) {
                this.sendDirect(groupMessageEvent, "\u5f53\u524d\u9ed1\u540d\u5355\u4e3a\u7a7a\u3002\n\u7528\u6cd5: /\u9ed1\u540d\u5355 @QQ");
            } else {
                StringBuilder stringBuilder = new StringBuilder("\u9ed1\u540d\u5355\u5217\u8868\uff08" + list.size() + " \u4e2a\uff09\uff1a\n");
                for (int i = 0; i < list.size(); ++i) {
                    String string5 = list.get(i);
                    String string6 = qqBindManager.getBlacklistName(string5);
                    stringBuilder.append(i + 1).append(". ").append(string6 != null ? string6 : string5).append("\n");
                }
                this.sendDirect(groupMessageEvent, stringBuilder.toString().trim());
            }
            return;
        }
        qqBindManager.addBlacklist(string3, string2);
        String string7 = qqBindManager.unbindAndKickByQq(string3);
        if (string7 != null) {
            this.sendDirect(groupMessageEvent, "\u2705 \u5df2\u5c06 " + (string2 != null ? string2 : string3) + " \u52a0\u5165\u9ed1\u540d\u5355\u3002\n\u5df2\u89e3\u7ed1\u5e76\u8e22\u51fa\u73a9\u5bb6: " + string7);
        } else {
            this.sendDirect(groupMessageEvent, "\u2705 \u5df2\u5c06 " + (string2 != null ? string2 : string3) + " \u52a0\u5165\u9ed1\u540d\u5355\u3002\u8be5 QQ \u5c06\u4e0d\u80fd\u7ed1\u5b9a\u4efb\u4f55\u6e38\u620f\u8d26\u53f7\u3002");
        }
    }

    @Commands(value={"\u89e3\u9664\u9ed1\u540d\u5355"})
    public void unblacklist(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        String string2;
        QqBindManager qqBindManager;
        if (!this.requireAdmin(huHoBot, groupMessageEvent)) {
            return;
        }
        try {
            qqBindManager = QqBindManager.getInstance();
        }
        catch (Throwable throwable) {
            this.sendDirect(groupMessageEvent, "\u274c \u7ba1\u7406\u5668\u672a\u5c31\u7eea");
            return;
        }
        String[] stringArray = this.extractMentionedQqInfo(groupMessageEvent);
        String string3 = stringArray != null ? stringArray[0] : null;
        String string4 = string2 = stringArray != null ? stringArray[1] : null;
        if (string3 != null && !string3.isEmpty()) {
            qqBindManager.removeBlacklist(string3);
            this.sendDirect(groupMessageEvent, "\u2705 \u5df2\u5c06 " + (string2 != null ? string2 : string3) + " \u4ece\u9ed1\u540d\u5355\u79fb\u9664\u3002");
            return;
        }
        List<String> list = qqBindManager.listBlacklist();
        if (list.isEmpty()) {
            this.sendDirect(groupMessageEvent, "\u5f53\u524d\u9ed1\u540d\u5355\u4e3a\u7a7a\u3002");
            return;
        }
        StringBuilder stringBuilder = new StringBuilder("\u9ed1\u540d\u5355\u5217\u8868\uff08" + list.size() + " \u4e2a\uff09\uff1a\n");
        for (int i = 0; i < list.size(); ++i) {
            String string5 = list.get(i);
            String string6 = qqBindManager.getBlacklistName(string5);
            stringBuilder.append(i + 1).append(". ").append(string6 != null ? string6 : string5).append("\n");
        }
        stringBuilder.append("\n\u89e3\u9664\u8bf7\u53d1\u9001: /\u89e3\u9664\u9ed1\u540d\u5355 @QQ");
        this.sendDirect(groupMessageEvent, stringBuilder.toString().trim());
    }

    @Commands(value={"\u91cd\u65b0\u7ed1\u5b9a"})
    public void rebindQq(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        QqBindManager qqBindManager;
        try {
            qqBindManager = QqBindManager.getInstance();
        }
        catch (Throwable throwable) {
            this.sendDirect(groupMessageEvent, "\u274c \u7ba1\u7406\u5668\u672a\u5c31\u7eea");
            return;
        }
        String string2 = this.userId(groupMessageEvent);
        String string3 = qqBindManager.unbindAndKickByQq(string2);
        if (string3 != null) {
            this.sendDirect(groupMessageEvent, "\u2705 \u5df2\u89e3\u9664\u4f60\u7684 QQ \u7ed1\u5b9a\u3002\u73a9\u5bb6 " + string3 + " \u5df2\u88ab\u8e22\u51fa\u670d\u52a1\u5668\u3002\n\u8bf7\u91cd\u65b0\u8fdb\u5165\u670d\u52a1\u5668\u83b7\u53d6\u65b0\u7684\u7ed1\u5b9a\u7801\u3002");
        } else {
            this.sendDirect(groupMessageEvent, "\u2139\ufe0f \u4f60\u7684 QQ \u672a\u7ed1\u5b9a\u4efb\u4f55\u6e38\u620f\u8d26\u53f7\u3002");
        }
    }

    private String extractMentionedQq(GroupMessageEvent groupMessageEvent) {
        String[] stringArray = this.extractMentionedQqInfo(groupMessageEvent);
        return stringArray != null ? stringArray[0] : null;
    }

    private String[] extractMentionedQqInfo(GroupMessageEvent groupMessageEvent) {
        try {
            User[] userArray = groupMessageEvent.getRawMessage().getMentions();
            if (userArray == null || userArray.length == 0) {
                return null;
            }
            for (User user : userArray) {
                Object object;
                try {
                    object = user.getBot();
                    if (object != null && ((Boolean)object).booleanValue()) {
                        continue;
                    }
                }
                catch (Throwable throwable) {
                    // empty catch block
                }
                if ((object = user.getId()) == null || ((String)object).isEmpty()) continue;
                String string = user.getUsername();
                String mid = (String) object; String name = user.getUsername(); return new String[]{ mid, name != null ? name : mid };
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return null;
    }

    @Commands(value={"\u5e2e\u52a9"})
    public void help(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("===== KERONG Penguin \u547d\u4ee4\u5217\u8868 =====\n\n");
        stringBuilder.append("\u3010QQ \u7fa4\u547d\u4ee4\u3011\n");
        stringBuilder.append("  /\u5e2e\u52a9 \u2014\u2014 \u67e5\u770b\u672c\u5e2e\u52a9\n");
        stringBuilder.append("  /\u67e5\u4fe1\u606f \u2014\u2014 \u67e5\u8be2\u4f60\u7684 OpenId\n");
        stringBuilder.append("  /\u67e5\u5728\u7ebf \u2014\u2014 \u67e5\u8be2\u5728\u7ebf\u73a9\u5bb6\n");
        stringBuilder.append("  /\u5728\u7ebf\u670d\u52a1\u5668 \u2014\u2014 \u67e5\u770b\u670d\u52a1\u5668\u72b6\u6001\n");
        stringBuilder.append("  /\u53d1\u4fe1\u606f <\u5185\u5bb9> \u2014\u2014 \u53d1\u9001\u6d88\u606f\u5230\u6e38\u620f\n");
        stringBuilder.append("  /motd \u2014\u2014 \u67e5\u8be2\u670d\u52a1\u5668\u72b6\u6001\n");
        stringBuilder.append("  /\u7ed1\u5b9a <\u7ed1\u5b9a\u7801> \u2014\u2014 \u7ed1\u5b9a QQ\uff08\u73a9\u5bb6\u81ea\u52a9\uff09\n");
        stringBuilder.append("  /\u91cd\u65b0\u7ed1\u5b9a \u2014\u2014 \u89e3\u9664\u5f53\u524d QQ \u7ed1\u5b9a\uff08\u8e22\u51fa\u73a9\u5bb6\uff09\n\n");
        stringBuilder.append("\u3010\u7ba1\u7406\u5458\u547d\u4ee4\u3011\uff08\u9700 QQ \u7fa4\u7ba1\u7406\u5458\uff09\n");
        stringBuilder.append("  /\u6267\u884c\u547d\u4ee4 <\u547d\u4ee4> \u2014\u2014 \u6267\u884c\u670d\u52a1\u5668\u547d\u4ee4\n");
        stringBuilder.append("  /\u6267\u884c <key> \u2014\u2014 \u6267\u884c\u81ea\u5b9a\u4e49\u547d\u4ee4\n");
        stringBuilder.append("  /\u7ba1\u7406\u5458\u6267\u884c <key> \u2014\u2014 \u7ba1\u7406\u5458\u6267\u884c\u81ea\u5b9a\u4e49\u547d\u4ee4\n");
        stringBuilder.append("  /\u5168\u91cf \u2014\u2014 \u5207\u6362\u5168\u91cf\u804a\u5929\u8f6c\u53d1\n\n");
        stringBuilder.append("\u3010AI \u5bf9\u8bdd\u547d\u4ee4\u3011\uff08\u7ba1\u7406\u5458\uff09\n");
        stringBuilder.append("  /AI\u5bf9\u8bdd\u4e0a\u4e0b\u6587 \u5f00|\u5173 \u2014\u2014 \u5f00\u5173\u5168\u5c40\u5bf9\u8bdd\u4e0a\u4e0b\u6587\n");
        stringBuilder.append("  /\u6e05\u9664\u5f53\u524d\u4e0a\u4e0b\u6587 \u2014\u2014 \u6e05\u9664\u5168\u5c40 AI \u5bf9\u8bdd\u5386\u53f2\n");
        stringBuilder.append("  @\u673a\u5668\u4eba + \u6d88\u606f \u2014\u2014 \u4e0e AI \u5bf9\u8bdd\n\n");
        stringBuilder.append("\u3010\u9ed1\u540d\u5355\u547d\u4ee4\u3011\uff08\u7ba1\u7406\u5458\uff09\n");
        stringBuilder.append("  /\u9ed1\u540d\u5355 @QQ \u2014\u2014 \u5c06 QQ \u52a0\u5165\u9ed1\u540d\u5355\n");
        stringBuilder.append("  /\u89e3\u9664\u9ed1\u540d\u5355 @QQ \u2014\u2014 \u4ece\u9ed1\u540d\u5355\u79fb\u9664 QQ\n\n");
        stringBuilder.append("\u3010\u670d\u52a1\u5668\u5185\u547d\u4ee4\u3011\uff08MC \u5185\uff09\n");
        stringBuilder.append("  /qq help \u2014\u2014 \u67e5\u770b QQ \u7ed1\u5b9a\u7ba1\u7406\u547d\u4ee4\n");
        stringBuilder.append("  /hb help \u2014\u2014 \u67e5\u770b\u63d2\u4ef6\u547d\u4ee4");
        this.sendDirect(groupMessageEvent, stringBuilder.toString());
    }

    @Commands(value={"motd"})
    public void motd(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        Motd motd = huHoBot.getMotd();
        Object object = motd.getText();
        if (object == null || ((String)object).isEmpty()) {
            object = "\u670d\u52a1\u5668: " + motd.getServerIP() + ":" + motd.getServerPort();
        }
        this.sendDirect(groupMessageEvent, (String)object);
    }

    @Commands(value={"\u6267\u884c"})
    public void runCustomCommand(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        if (string == null || string.trim().isEmpty()) {
            this.sendDirect(groupMessageEvent, "\u53c2\u6570\u4e0d\u6b63\u786e");
            return;
        }
        String cmd = "huhobot run " + this.groupId(groupMessageEvent) + ' ' + this.userId(groupMessageEvent) + ' ' + string;
        this.executeGameCommandWithMention(huHoBot, groupMessageEvent, cmd, true);
    }

    private void executeGameCommandWithMention(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String command, boolean direct) {
        try {
            CompletableFuture<cn.huohuas001.bot.provider.HExecution> future = huHoBot.sendCommand(command);
            final HuHoBot fHuHoBot = huHoBot;
            final GroupMessageEvent fEvent = groupMessageEvent;
            future.whenComplete(new java.util.function.BiConsumer<cn.huohuas001.bot.provider.HExecution, Throwable>() {
                @Override
                public void accept(cn.huohuas001.bot.provider.HExecution result, Throwable error) {
                    try {
                        if (error != null || result == null) {
                            sendDirect(fEvent, "\u6e38\u620f\u6865\u63a5\u672a\u914d\u7f6e\u6216\u6267\u884c\u5931\u8d25");
                        } else {
                            String raw = result.getRawString();
                            if (raw == null || raw.trim().isEmpty()) {
                                sendDirect(fEvent, "\u5df2\u53d1\u9001\u6267\u884c\u8bf7\u6c42");
                            } else {
                                sendDirect(fEvent, fHuHoBot.auditText(raw));
                            }
                        }
                    } catch (Throwable t) {
                        // empty catch block
                    }
                }
            });
        } catch (Throwable t) {
            this.sendDirect(groupMessageEvent, "\u6267\u884c\u5931\u8d25: " + t.getMessage());
        }
    }

    private void sendDirect(GroupMessageEvent groupMessageEvent, String string) {
        try {
            String userId = null;
            try { userId = this.userId(groupMessageEvent); } catch (Throwable ignored) {}
            String content = string;
            if (userId != null && !userId.isEmpty() && !"<unknown>".equals(userId)) {
                content = "<@" + userId + ">\n" + string;
            }
            try {
                cn.huohuas001.bot.QClient.INSTANCE.replyMarkdown(groupMessageEvent, content, null);
            } catch (Throwable t) {
                groupMessageEvent.sendMessage(content);
            }
        }
        catch (Throwable throwable) {
            try { groupMessageEvent.sendMessage(string); } catch (Throwable ignored) {}
        }
    }
}

