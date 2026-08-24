package cn.huohuas001.bot.events.commands;

import cn.huohuas001.bot.HuHoBot;
import cn.huohuas001.bot.events.commands.CommandSupport;
import cn.huohuas001.bot.events.commands.Commands;
import cn.huohuas001.bot.state.CommandRepositories;
import cn.huohuas001.bot.provider.HExecution;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

public final class AdministrationCommands
extends CommandSupport {
    @Commands(value={"\u6267\u884c\u547d\u4ee4"})
    public void runServerCommand(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        if (!this.requireAdmin(huHoBot, groupMessageEvent)) {
            return;
        }
        if (string == null || string.trim().isEmpty()) {
            this.sendDirect(groupMessageEvent, "\u53c2\u6570\u4e0d\u6b63\u786e");
            return;
        }
        this.executeGameCommandWithMention(huHoBot, groupMessageEvent, string, true);
    }

    @Commands(value={"\u7ba1\u7406\u5458\u6267\u884c"})
    public void runAdminCustomCommand(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        if (!this.requireAdmin(huHoBot, groupMessageEvent)) {
            return;
        }
        if (string == null || string.trim().isEmpty()) {
            this.sendDirect(groupMessageEvent, "\u53c2\u6570\u4e0d\u6b63\u786e");
            return;
        }
        String type = "adminrun";
        String cmd = "huhobot " + type + ' ' + this.groupId(groupMessageEvent) + ' ' + this.userId(groupMessageEvent) + ' ' + string;
        this.executeGameCommandWithMention(huHoBot, groupMessageEvent, cmd, true);
    }

    @Commands(value={"\u5168\u91cf"})
    public void fullAmount(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        if (!this.requireAdmin(huHoBot, groupMessageEvent)) {
            return;
        }
        String string2 = this.groupId(groupMessageEvent);
        boolean bl = CommandRepositories.INSTANCE.getGroupSettings().fullForwarding(string2, huHoBot.getFullAmount());
        boolean bl2 = !bl;
        CommandRepositories.INSTANCE.getGroupSettings().setFullForwarding(string2, bl2);
        this.sendDirect(groupMessageEvent, "\u672c\u7fa4\u5168\u91cf\u8f6c\u53d1\u5df2" + (bl2 ? "\u5f00\u542f" : "\u5173\u95ed"));
    }

    /**
     * 自定义命令执行 + 带艾特回复（覆盖 CommandSupport.executeGameCommand 的纯文本回复）。
     */
    private void executeGameCommandWithMention(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String command, boolean direct) {
        try {
            String outgoingCommand = direct ? command : "huhobot run " + this.groupId(groupMessageEvent) + ' ' + this.userId(groupMessageEvent) + ' ' + command;
            CompletableFuture<HExecution> future = huHoBot.sendCommand(outgoingCommand);
            final HuHoBot fHuHoBot = huHoBot;
            final GroupMessageEvent fEvent = groupMessageEvent;
            future.whenComplete(new BiConsumer<HExecution, Throwable>() {
                @Override
                public void accept(HExecution result, Throwable error) {
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
            String senderName = null;
            try {
                io.github.kloping.qqbot.entities.qqpd.v2.Contact contact = groupMessageEvent.getSender();
                if (contact != null && contact.getUsername() != null) senderName = contact.getUsername();
            } catch (Throwable ignored) {}
            String content = string;
            if (senderName != null && !senderName.isEmpty()) {
                content = "@" + senderName + "\n" + string;
            }
            groupMessageEvent.sendMessage(content);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }
}
