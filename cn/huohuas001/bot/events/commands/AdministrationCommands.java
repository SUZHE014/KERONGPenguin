/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.github.kloping.qqbot.api.v2.GroupMessageEvent
 */
package cn.huohuas001.bot.events.commands;

import cn.huohuas001.bot.HuHoBot;
import cn.huohuas001.bot.events.commands.CommandSupport;
import cn.huohuas001.bot.events.commands.Commands;
import cn.huohuas001.bot.state.CommandRepositories;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;

public final class AdministrationCommands
extends CommandSupport {
    @Commands(value={"\u6267\u884c\u547d\u4ee4"})
    public void runServerCommand(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        if (!this.requireAdmin(huHoBot, groupMessageEvent)) {
            return;
        }
        if (string == null || string.trim().isEmpty()) {
            groupMessageEvent.sendMessage("\u53c2\u6570\u4e0d\u6b63\u786e");
            return;
        }
        this.executeGameCommand(huHoBot, groupMessageEvent, string, true);
    }

    @Commands(value={"\u7ba1\u7406\u5458\u6267\u884c"})
    public void runAdminCustomCommand(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        if (!this.requireAdmin(huHoBot, groupMessageEvent)) {
            return;
        }
        if (string == null || string.trim().isEmpty()) {
            groupMessageEvent.sendMessage("\u53c2\u6570\u4e0d\u6b63\u786e");
            return;
        }
        this.executeCustomCommand(huHoBot, groupMessageEvent, string, true);
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
        this.reply(huHoBot, groupMessageEvent, "\u672c\u7fa4\u5168\u91cf\u8f6c\u53d1\u5df2" + (bl2 ? "\u5f00\u542f" : "\u5173\u95ed"));
    }
}

