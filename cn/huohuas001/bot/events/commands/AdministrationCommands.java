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
            groupMessageEvent.sendMessage("参数不正确");
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
            groupMessageEvent.sendMessage("参数不正确");
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
        this.reply(huHoBot, groupMessageEvent, "本群全量转发已" + (bl2 ? "开启" : "关闭"));
    }
}

