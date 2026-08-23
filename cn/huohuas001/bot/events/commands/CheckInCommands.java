package cn.huohuas001.bot.events.commands;

import cn.huohuas001.bot.HuHoBot;
import cn.huohuas001.bot.events.commands.CommandSupport;
import cn.huohuas001.bot.events.commands.Commands;
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

public final class CheckInCommands
extends CommandSupport {
    @Commands(value={"\u7b7e\u5230", "checkin", "sign"})
    public void checkin(HuHoBot huHoBot, GroupMessageEvent groupMessageEvent, String string) {
        QqBindManager mgr;
        try {
            mgr = QqBindManager.getInstance();
        }
        catch (Throwable throwable) {
            this.sendDirect(groupMessageEvent, "\u274c \u7ba1\u7406\u5668\u672a\u5c31\u7eea");
            return;
        }
        if (!mgr.isCheckinEnabled()) {
            this.sendDirect(groupMessageEvent, "\u26a0\ufe0f \u7b7e\u5230\u529f\u80fd\u672a\u5f00\u542f");
            return;
        }
        String qq = null;
        try {
            qq = this.userId(groupMessageEvent);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        if (qq == null || qq.isEmpty() || "<unknown>".equals(qq)) {
            this.sendDirect(groupMessageEvent, "\u274c \u65e0\u6cd5\u8bc6\u522b\u4f60\u7684 QQ \u8d26\u53f7");
            return;
        }
        String playerName = mgr.findPlayerByQq(qq);
        if (playerName == null || playerName.isEmpty()) {
            this.sendDirect(groupMessageEvent, "\u274c \u4f60\u8fd8\u672a\u7ed1\u5b9a\u6e38\u620f\u8d26\u53f7\uff0c\u8bf7\u5148\u8fdb\u5165\u670d\u52a1\u5668\u7ed1\u5b9a QQ \u540e\u518d\u7b7e\u5230\u3002");
            return;
        }
        double reward = mgr.getCheckinReward();
        if (reward <= 0) {
            this.sendDirect(groupMessageEvent, "\u274c \u7b7e\u5230\u5956\u52b1\u91d1\u5e01\u6570\u91cf\u914d\u7f6e\u9519\u8bef");
            return;
        }
        String quuid = mgr.getQuuid(playerName);
        boolean online = false;
        try {
            online = Bukkit.getPlayerExact(playerName) != null;
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        String prefix = mgr.getCheckinPrefix();
        boolean paid;
        if (online) {
            paid = CheckInCommands.depositPlayer(playerName, reward);
        } else {
            if (quuid != null && !quuid.isEmpty()) {
                mgr.addPendingCoins(quuid, reward);
                paid = true;
            } else {
                paid = false;
            }
        }
        if (paid) {
            if (online) {
                this.sendDirect(groupMessageEvent, prefix + " \u7b7e\u5230\u6210\u529f\uff01\u5956\u52b1 " + CheckInCommands.formatMoney(reward) + " \u91d1\u5e01\u5df2\u53d1\u653e\u7ed9\u5728\u7ebf\u73a9\u5bb6 " + playerName + "\u3002");
            } else {
                this.sendDirect(groupMessageEvent, prefix + " \u7b7e\u5230\u6210\u529f\uff01\u5956\u52b1 " + CheckInCommands.formatMoney(reward) + " \u91d1\u5e01\u5df2\u6682\u5b58\uff0c\u73a9\u5bb6 " + playerName + " \u4e0a\u7ebf\u540e\u81ea\u52a8\u9886\u53d6\u3002");
            }
        } else {
            this.sendDirect(groupMessageEvent, "\u274c \u7b7e\u5230\u5931\u8d25\uff1a\u91d1\u5e01\u7cfb\u7edf\u672a\u5c31\u7eea\u6216\u6570\u636e\u5f02\u5e38\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u3002");
        }
    }

    public static boolean depositPlayer(String playerName, double amount) {
        try {
            Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
            if (vault == null) {
                return false;
            }
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Class<?> registeredServiceProviderClass = Class.forName("org.bukkit.plugin.RegisteredServiceProvider");
            Method getRegistrationMethod = Bukkit.getServer().getClass().getMethod("getServicesManager", new Class[0]);
            Object servicesManager = getRegistrationMethod.invoke(Bukkit.getServer(), new Object[0]);
            Method getRegistration = servicesManager.getClass().getMethod("getRegistration", Class.class);
            Object rsp = getRegistration.invoke(servicesManager, economyClass);
            if (rsp == null) {
                return false;
            }
            Method getProvider = registeredServiceProviderClass.getMethod("getProvider", new Class[0]);
            Object economy = getProvider.invoke(rsp, new Object[0]);
            if (economy == null) {
                return false;
            }
            Method depositPlayer = economyClass.getMethod("depositPlayer", OfflinePlayer.class, Double.TYPE);
            Object result = depositPlayer.invoke(economy, offlinePlayer, amount);
            if (result == null) {
                return false;
            }
            try {
                Method transactionSuccess = result.getClass().getMethod("transactionSuccess", new Class[0]);
                Object success = transactionSuccess.invoke(result, new Object[0]);
                return Boolean.TRUE.equals(success);
            }
            catch (Throwable throwable) {
                return true;
            }
        }
        catch (Throwable throwable) {
            return false;
        }
    }

    public static String formatMoney(double amount) {
        if (amount == (long)amount) {
            return String.valueOf((long)amount);
        }
        return String.valueOf(amount);
    }

    private void sendDirect(GroupMessageEvent groupMessageEvent, String string) {
        try {
            groupMessageEvent.sendMessage(string);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }
}
