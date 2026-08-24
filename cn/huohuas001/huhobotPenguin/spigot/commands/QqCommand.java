/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.ChatColor
 *  org.bukkit.command.Command
 *  org.bukkit.command.CommandSender
 *  org.bukkit.command.TabExecutor
 *  org.bukkit.entity.Player
 */
package cn.huohuas001.huhobotPenguin.spigot.commands;

import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot;
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public final class QqCommand
implements TabExecutor {
    private static final List<String> SUBCOMMANDS = Arrays.asList("help", "rebind", "qxqq", "skip", "unskip");
    private final HuHoBotSpigot plugin;

    public QqCommand(HuHoBotSpigot huHoBotSpigot) {
        this.plugin = huHoBotSpigot;
    }

    public boolean onCommand(CommandSender commandSender, Command command, String string, String[] stringArray) {
        String string2 = stringArray.length > 0 ? stringArray[0].toLowerCase(Locale.ROOT) : "help";
        switch (string2) {
            case "help": {
                this.sendHelp(commandSender, string);
                return true;
            }
            case "rebind": {
                this.handleSelfRebind(commandSender, string);
                return true;
            }
            case "qxqq": {
                if (!this.requireOp(commandSender, string, string2)) {
                    return true;
                }
                this.handleForceRebind(commandSender, stringArray, string, string2);
                return true;
            }
            case "skip": {
                if (!this.requireOp(commandSender, string, string2)) {
                    return true;
                }
                this.handleSkip(commandSender, stringArray, string, true);
                return true;
            }
            case "unskip": {
                if (!this.requireOp(commandSender, string, string2)) {
                    return true;
                }
                this.handleSkip(commandSender, stringArray, string, false);
                return true;
            }
        }
        this.sendHelp(commandSender, string);
        return true;
    }

    private boolean requireOp(CommandSender commandSender, String string, String string2) {
        if (!commandSender.isOp()) {
            commandSender.sendMessage(String.valueOf(ChatColor.RED) + "\u4f60\u6ca1\u6709\u6743\u9650\u6267\u884c /" + string + " " + string2 + "\uff08\u9700\u8981\u7ba1\u7406\u5458\u6743\u9650\uff09");
            return false;
        }
        return true;
    }

    private void handleSelfRebind(CommandSender commandSender, String string) {
        QqBindManager qqBindManager;
        if (!(commandSender instanceof Player)) {
            commandSender.sendMessage(String.valueOf(ChatColor.RED) + "\u6b64\u547d\u4ee4\u53ea\u80fd\u7531\u73a9\u5bb6\u6267\u884c\u3002\u7ba1\u7406\u5458\u8bf7\u4f7f\u7528 /" + string + " qxqq <\u73a9\u5bb6\u540d>");
            return;
        }
        String string2 = ((Player)commandSender).getName();
        String playerUuid = ((Player)commandSender).getUniqueId().toString();
        try {
            qqBindManager = QqBindManager.getInstance();
        }
        catch (Throwable throwable) {
            commandSender.sendMessage(String.valueOf(ChatColor.RED) + "\u7ba1\u7406\u5668\u672a\u5c31\u7eea");
            return;
        }
        boolean bl = qqBindManager.unbindByUuid(string2, playerUuid);
        if (bl) {
            QqBindManager.logQuiet("[MC\u547d\u4ee4] /" + string + " rebind \u73a9\u5bb6=" + string2 + " \u81ea\u884c\u89e3\u9664\u7ed1\u5b9a");
            commandSender.sendMessage(String.valueOf(ChatColor.GREEN) + "\u2705 \u5df2\u89e3\u9664\u4f60\u7684 QQ \u7ed1\u5b9a\u3002\u4f60\u4e0b\u6b21\u767b\u5f55\u5c06\u91cd\u65b0\u83b7\u53d6\u7ed1\u5b9a\u7801\u3002");
        } else {
            commandSender.sendMessage(String.valueOf(ChatColor.RED) + "\u274c \u4f60\u6ca1\u6709\u7ed1\u5b9a\u8bb0\u5f55\u3002");
        }
    }

    private void handleForceRebind(CommandSender commandSender, String[] stringArray, String string, String string2) {
        QqBindManager qqBindManager;
        if (stringArray.length < 2) {
            commandSender.sendMessage(String.valueOf(ChatColor.RED) + "\u7528\u6cd5: /" + string + " " + string2 + " <\u73a9\u5bb6\u540d>");
            return;
        }
        String string3 = stringArray[1];
        try {
            qqBindManager = QqBindManager.getInstance();
        }
        catch (Throwable throwable) {
            commandSender.sendMessage(String.valueOf(ChatColor.RED) + "\u7ba1\u7406\u5668\u672a\u5c31\u7eea");
            return;
        }
        // 用玩家名查找 QUUID（支持 UUID 绑定机制）
        String quuid = qqBindManager.findQuuidByPlayerName(string3);
        boolean bl;
        if (quuid != null && !quuid.isEmpty()) {
            bl = qqBindManager.unbindByQuuid(quuid, string3);
        } else {
            bl = qqBindManager.unbind(string3);
        }
        if (bl) {
            QqBindManager.logQuiet("[MC\u547d\u4ee4] /" + string + " " + string2 + " " + string3 + " \u6210\u529f by " + commandSender.getName());
            commandSender.sendMessage(String.valueOf(ChatColor.GREEN) + "\u2705 \u5df2\u89e3\u9664\u73a9\u5bb6 " + string3 + " \u7684 QQ \u7ed1\u5b9a\u3002\u8be5\u73a9\u5bb6\u4e0b\u6b21\u767b\u5f55\u5c06\u91cd\u65b0\u83b7\u53d6\u7ed1\u5b9a\u7801\u3002");
        } else {
            commandSender.sendMessage(String.valueOf(ChatColor.RED) + "\u274c \u73a9\u5bb6 " + string3 + " \u672a\u627e\u5230\u7ed1\u5b9a\u8bb0\u5f55\u3002");
        }
    }

    private void handleSkip(CommandSender commandSender, String[] stringArray, String string, boolean bl) {
        QqBindManager qqBindManager;
        if (stringArray.length < 2) {
            commandSender.sendMessage(String.valueOf(ChatColor.RED) + "\u7528\u6cd5: /" + string + " " + (bl ? "skip" : "unskip") + " <\u73a9\u5bb6\u540d>");
            return;
        }
        String string2 = stringArray[1];
        try {
            qqBindManager = QqBindManager.getInstance();
        }
        catch (Throwable throwable) {
            commandSender.sendMessage(String.valueOf(ChatColor.RED) + "\u7ba1\u7406\u5668\u672a\u5c31\u7eea");
            return;
        }
        qqBindManager.setSkipped(string2, bl);
        QqBindManager.logQuiet("[MC\u547d\u4ee4] /" + string + " " + (bl ? "skip" : "unskip") + " " + string2 + " by " + commandSender.getName());
        if (bl) {
            commandSender.sendMessage(String.valueOf(ChatColor.GREEN) + "\u2705 \u73a9\u5bb6 " + string2 + " \u5df2\u52a0\u5165\u8df3\u8fc7\u7ed1\u5b9a\u5217\u8868\u3002");
        } else {
            commandSender.sendMessage(String.valueOf(ChatColor.GREEN) + "\u2705 \u73a9\u5bb6 " + string2 + " \u5df2\u79fb\u51fa\u8df3\u8fc7\u7ed1\u5b9a\u5217\u8868\u3002");
        }
    }

    public List<String> onTabComplete(CommandSender commandSender, Command command, String string, String[] stringArray) {
        String string2;
        if (stringArray.length == 1) {
            String string3 = stringArray[0].toLowerCase(Locale.ROOT);
            ArrayList<String> arrayList = new ArrayList<String>();
            for (String string4 : SUBCOMMANDS) {
                if (!string4.startsWith(string3)) continue;
                arrayList.add(string4);
            }
            return arrayList;
        }
        if (stringArray.length == 2 && ((string2 = stringArray[0].toLowerCase(Locale.ROOT)).equals("qxqq") || string2.equals("skip") || string2.equals("unskip"))) {
            return this.getPlayerNameSuggestions(stringArray[1]);
        }
        return new ArrayList<String>();
    }

    private List<String> getPlayerNameSuggestions(String string) {
        ArrayList<String> arrayList = new ArrayList<String>();
        String string2 = string.toLowerCase(Locale.ROOT);
        try {
            QqBindManager qqBindManager = QqBindManager.getInstance();
            for (String string3 : qqBindManager.listBoundPlayerNames()) {
                if (!string3.toLowerCase(Locale.ROOT).startsWith(string2)) continue;
                arrayList.add(string3);
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            for (Player player : this.plugin.getServer().getOnlinePlayers()) {
                String string3;
                string3 = player.getName();
                if (string3 == null || !string3.toLowerCase(Locale.ROOT).startsWith(string2) || arrayList.contains(string3)) continue;
                arrayList.add(string3);
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return arrayList;
    }

    private void sendHelp(CommandSender commandSender, String string) {
        commandSender.sendMessage(String.valueOf(ChatColor.GOLD) + "===== /" + string + " QQ \u7ed1\u5b9a\u7ba1\u7406\u547d\u4ee4 =====");
        commandSender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/" + string + " help" + String.valueOf(ChatColor.WHITE) + " - \u67e5\u770b\u6b64\u5e2e\u52a9\uff08\u6240\u6709\u4eba\u53ef\u7528\uff09");
        commandSender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/" + string + " rebind" + String.valueOf(ChatColor.WHITE) + " - \u91cd\u65b0\u7ed1\u5b9a\u81ea\u5df1\u7684 QQ\uff08\u73a9\u5bb6\u81ea\u52a9\uff0c\u65e0\u9700\u7ba1\u7406\u5458\uff09");
        commandSender.sendMessage(String.valueOf(ChatColor.GRAY) + "\u4ee5\u4e0b\u547d\u4ee4\u9700\u8981\u7ba1\u7406\u5458\uff08op\uff09\u6743\u9650\uff1a");
        commandSender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/" + string + " qxqq <\u73a9\u5bb6\u540d>" + String.valueOf(ChatColor.WHITE) + " - \u5f3a\u5236\u8ba9\u73a9\u5bb6\u91cd\u65b0\u7ed1\u5b9a QQ\uff08tab \u8865\u5168\u5df2\u7ed1\u5b9a\u73a9\u5bb6\uff09");
        commandSender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/" + string + " skip <\u73a9\u5bb6\u540d>" + String.valueOf(ChatColor.WHITE) + " - \u5f3a\u5236\u8ba9\u73a9\u5bb6\u8df3\u8fc7 QQ \u7ed1\u5b9a\u9a8c\u8bc1");
        commandSender.sendMessage(String.valueOf(ChatColor.YELLOW) + "/" + string + " unskip <\u73a9\u5bb6\u540d>" + String.valueOf(ChatColor.WHITE) + " - \u53d6\u6d88\u8df3\u8fc7\u7ed1\u5b9a");
    }
}

