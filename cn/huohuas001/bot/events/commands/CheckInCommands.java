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
            String prefix = mgr.getCheckinPrefix();
            String msg = prefix + "  \u274c\u7b7e\u5230\u5931\u8d25\uff01\n\u60a8\u7684QQ\u53f7\u6682\u672a\u7ed1\u5b9a\u4efb\u4f55\u73a9\u5bb6\uff0c\u8bf7\u8fdb\u5165\u670d\u52a1\u5668\u83b7\u53d6\u9a8c\u8bc1\u7801\u540e\u7ed1\u5b9aQQ\u53f7";
            this.sendDirect(groupMessageEvent, msg);
            return;
        }
        String quuid = mgr.findQuuidByQq(qq);
        if (quuid == null || quuid.isEmpty()) {
            this.sendDirect(groupMessageEvent, mgr.getCheckinPrefix() + "  \u274c\u7b7e\u5230\u5931\u8d25\uff01\n\u6570\u636e\u5f02\u5e38\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458");
            return;
        }
        // 联网获取北京时间（异步）
        String today;
        try {
            today = cn.huohuas001.huhobotPenguin.spigot.qqbind.BeijingTimeUtil.getBeijingDate();
        } catch (Throwable t) {
            today = "";
        }
        if (today == null || today.isEmpty()) {
            this.sendDirect(groupMessageEvent, mgr.getCheckinPrefix() + "  \u274c\u7b7e\u5230\u5931\u8d25\uff01\n\u83b7\u53d6\u670d\u52a1\u5668\u65f6\u95f4\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
            return;
        }
        // 检查今日是否已签到
        String lastDate = mgr.getCheckinDate(quuid);
        String prefix = mgr.getCheckinPrefix();
        if (today.equals(lastDate)) {
            this.sendDirect(groupMessageEvent, prefix + "  \u274c\u7b7e\u5230\u5931\u8d25\uff0c\ud83d\udcb0\u4f60\u592a\u8d2a\u4e86\n\u4eca\u65e5\u5df2\u7ecf\u7b7e\u5230\u8fc7\u4e86\uff0c\u8bf7\u660e\u5929\u518d\u8bd5...");
            return;
        }
        double reward = mgr.getCheckinReward();
        if (reward <= 0) {
            this.sendDirect(groupMessageEvent, prefix + "  \u274c\u7b7e\u5230\u5931\u8d25\uff01\n\u5956\u52b1\u91d1\u5e01\u6570\u91cf\u914d\u7f6e\u9519\u8bef");
            return;
        }
        // 计算连续签到天数
        int streak = mgr.getCheckinStreak(quuid);
        String yesterday = cn.huohuas001.huhobotPenguin.spigot.qqbind.BeijingTimeUtil.nextDay(lastDate);
        if (lastDate.isEmpty() || !yesterday.equals(today)) {
            streak = 1;
        } else {
            streak = streak + 1;
        }
        // 发放金币
        boolean online = false;
        try {
            online = Bukkit.getPlayerExact(playerName) != null;
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        boolean paid;
        if (online) {
            paid = CheckInCommands.depositPlayer(playerName, reward);
        } else {
            mgr.addPendingCoins(quuid, reward);
            paid = true;
        }
        if (paid) {
            mgr.setCheckinDate(quuid, today);
            mgr.setCheckinStreak(quuid, streak);
            String msg = prefix + "  \u2714\ufe0f\u7b7e\u5230\u6210\u529f\uff01\n\u73a9\u5bb6 " + playerName + " \u83b7\u5f97\u4e86" + CheckInCommands.formatMoney(reward) + "\u91d1\u5e01\uff0c\u5df2\u8fde\u7eed\u7b7e\u5230" + streak + "\u5929";
            this.sendDirect(groupMessageEvent, msg);
        } else {
            this.sendDirect(groupMessageEvent, prefix + "  \u274c\u7b7e\u5230\u5931\u8d25\uff01\n\u91d1\u5e01\u7cfb\u7edf\u672a\u5c31\u7eea\u6216\u6570\u636e\u5f02\u5e38\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458");
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
            try { groupMessageEvent.sendMessage(string); } catch (Throwable ignored) {}
        }
    }
}
