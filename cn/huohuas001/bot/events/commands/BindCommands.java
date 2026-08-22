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
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;

public final class BindCommands
extends CommandSupport {
    @Commands(value={"\u7ed1\u5b9a", "bind"})
    public void bind(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        boolean bl;
        QqBindManager qqBindManager = QqBindManager.getInstance();
        String string2 = string == null ? "" : string.trim();
        QqBindManager.logVerbose("[QQ\u547d\u4ee4] /\u7ed1\u5b9a code=" + string2 + " qq=" + this.safeUserId(groupMessageEvent));
        if (string2.isEmpty()) {
            String string3;
            try {
                string3 = qqBindManager.isEnabled() ? "\ud83d\udccb QQ \u7ed1\u5b9a\n\u7528\u6cd5: /\u7ed1\u5b9a <\u7ed1\u5b9a\u7801>\n\u8bf7\u5148\u8fdb\u5165\u670d\u52a1\u5668\u83b7\u53d6\u4e13\u5c5e\u7ed1\u5b9a\u7801\u3002" : "\u26a0\ufe0f QQ \u7ed1\u5b9a\u529f\u80fd\u672a\u5f00\u542f";
            }
            catch (Throwable throwable) {
                string3 = "\u7528\u6cd5: /\u7ed1\u5b9a <\u7ed1\u5b9a\u7801>";
            }
            this.sendDirect(groupMessageEvent, string3);
            return;
        }
        String string4 = this.safeUserId(groupMessageEvent);
        try {
            bl = qqBindManager.confirmBinding(string2, string4);
        }
        catch (Throwable throwable) {
            QqBindManager.logQuiet("[QQ\u547d\u4ee4] /\u7ed1\u5b9a \u5f02\u5e38: " + BindCommands.safeMessage(throwable));
            this.sendDirect(groupMessageEvent, "\u274c \u7ed1\u5b9a\u5904\u7406\u5f02\u5e38\uff1a" + BindCommands.safeMessage(throwable));
            return;
        }
        if (bl) {
            QqBindManager.logQuiet("[QQ\u547d\u4ee4] /\u7ed1\u5b9a \u6210\u529f code=" + string2 + " qq=" + string4);
            this.sendDirect(groupMessageEvent, "\u2705 QQ \u7ed1\u5b9a\u6210\u529f\uff01\u73b0\u5728\u53ef\u4ee5\u91cd\u65b0\u8fdb\u5165\u670d\u52a1\u5668\u4e86\u3002");
        } else {
            QqBindManager.logQuiet("[QQ\u547d\u4ee4] /\u7ed1\u5b9a \u5931\u8d25 code=" + string2 + " \u65e0\u6548\u6216\u8fc7\u671f");
            this.sendDirect(groupMessageEvent, "\u274c \u7ed1\u5b9a\u7801\u65e0\u6548\u6216\u5df2\u8fc7\u671f\u3002\n\u8bf7\u91cd\u65b0\u8fdb\u5165\u670d\u52a1\u5668\u83b7\u53d6\u65b0\u7684\u7ed1\u5b9a\u7801\u540e\u518d\u8bd5\u3002");
        }
    }

    private void sendDirect(GroupMessageEvent groupMessageEvent, String string) {
        try {
            groupMessageEvent.sendMessage(string);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private String safeUserId(GroupMessageEvent groupMessageEvent) {
        try {
            return this.userId(groupMessageEvent);
        }
        catch (Throwable throwable) {
            return "<unknown>";
        }
    }

    private static String safeMessage(Throwable throwable) {
        String string = throwable.getMessage();
        return string == null || string.isEmpty() ? throwable.toString() : string;
    }
}

