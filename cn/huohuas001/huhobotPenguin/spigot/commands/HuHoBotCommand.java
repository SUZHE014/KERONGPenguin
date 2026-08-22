/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.github.kloping.qqbot.Starter
 *  org.bukkit.ChatColor
 *  org.bukkit.command.Command
 *  org.bukkit.command.CommandSender
 *  org.bukkit.command.TabExecutor
 */
package cn.huohuas001.huhobotPenguin.spigot.commands;

import cn.huohuas001.bot.MenuManager;
import cn.huohuas001.bot.QClient;
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot;
import io.github.kloping.qqbot.Starter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

public final class HuHoBotCommand
implements TabExecutor {
    private static final List<String> SUBCOMMANDS = Arrays.asList("reload", "info", "panel", "help");
    private final HuHoBotSpigot plugin;

    public HuHoBotCommand(HuHoBotSpigot huHoBotSpigot) {
        this.plugin = huHoBotSpigot;
    }

    public boolean onCommand(CommandSender commandSender, Command command, String string, String[] stringArray) {
        String string2 = stringArray.length > 0 ? stringArray[0].toLowerCase(Locale.ROOT) : "help";
        switch (string2) {
            case "reload": {
                this.plugin.reloadPluginConfig();
                commandSender.sendMessage(String.valueOf(ChatColor.GOLD) + "\u5df2\u91cd\u8f7d\u914d\u7f6e\u6587\u4ef6\u3002");
                return true;
            }
            case "info": {
                commandSender.sendMessage("\u5e73\u53f0: " + this.plugin.getPlatform() + "\n\u7248\u672c: " + this.plugin.getPluginVersion());
                return true;
            }
            case "panel": {
                this.resyncPanel(commandSender);
                return true;
            }
        }
        this.sendHelp(commandSender, string);
        return true;
    }

    private void resyncPanel(CommandSender commandSender) {
        try {
            Starter starter = QClient.INSTANCE.getStarter();
            if (starter == null) {
                commandSender.sendMessage(String.valueOf(ChatColor.RED) + "QQ \u5ba2\u6237\u7aef\u672a\u542f\u52a8\u3002");
                return;
            }
            List<String> list = this.plugin.getGroupOpenIdList();
            if (list == null || list.isEmpty()) {
                commandSender.sendMessage(String.valueOf(ChatColor.RED) + "\u672a\u914d\u7f6e QQ \u7fa4\u3002");
                return;
            }
            MenuManager.INSTANCE.syncGroupPanels(starter, list);
            commandSender.sendMessage(String.valueOf(ChatColor.GREEN) + "\u5df2\u91cd\u65b0\u540c\u6b65 QQ \u5feb\u6377\u6307\u4ee4\u9762\u677f\u3002");
        }
        catch (Throwable throwable) {
            commandSender.sendMessage(String.valueOf(ChatColor.RED) + "\u9762\u677f\u540c\u6b65\u5931\u8d25: " + throwable.getMessage());
        }
    }

    public List<String> onTabComplete(CommandSender commandSender, Command command, String string, String[] stringArray) {
        if (stringArray.length != 1) {
            return new ArrayList<String>();
        }
        String string2 = stringArray[0].toLowerCase(Locale.ROOT);
        ArrayList<String> arrayList = new ArrayList<String>();
        for (String string3 : SUBCOMMANDS) {
            if (!string3.startsWith(string2)) continue;
            arrayList.add(string3);
        }
        return arrayList;
    }

    private void sendHelp(CommandSender commandSender, String string) {
        commandSender.sendMessage(String.valueOf(ChatColor.GOLD) + "===== /" + string + " \u547d\u4ee4\u5e2e\u52a9 =====");
        commandSender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/" + string + " help" + String.valueOf(ChatColor.WHITE) + " - \u67e5\u770b\u6b64\u5e2e\u52a9");
        commandSender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/" + string + " reload" + String.valueOf(ChatColor.WHITE) + " - \u91cd\u8f7d\u914d\u7f6e\u6587\u4ef6");
        commandSender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/" + string + " info" + String.valueOf(ChatColor.WHITE) + " - \u67e5\u770b\u9002\u914d\u5668\u4fe1\u606f");
        commandSender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/" + string + " panel" + String.valueOf(ChatColor.WHITE) + " - \u91cd\u65b0\u540c\u6b65 QQ \u5feb\u6377\u6307\u4ee4\u9762\u677f");
    }
}

