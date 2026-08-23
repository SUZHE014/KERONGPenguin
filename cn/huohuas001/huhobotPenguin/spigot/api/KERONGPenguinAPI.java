package cn.huohuas001.huhobotPenguin.spigot.api;

import cn.huohuas001.huhobotPenguin.spigot.events.GameChat;
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * KERONGPenguin 公开 API
 * 其他插件可通过此类调用魔改版功能（QQ 绑定查询、金币发放、签到等）。
 *
 * 用法：
 *   KERONGPenguinAPI api = KERONGPenguinAPI.getInstance();
 *   if (api != null) {
 *       String player = api.getBoundPlayer(qqOpenId);
 *       boolean ok = api.depositCoins(playerName, 100.0);
 *   }
 */
public final class KERONGPenguinAPI {
    private static volatile KERONGPenguinAPI instance;

    static {
        instance = new KERONGPenguinAPI();
    }

    public static KERONGPenguinAPI getInstance() {
        if (instance == null) {
            instance = new KERONGPenguinAPI();
        }
        return instance;
    }

    private KERONGPenguinAPI() {
    }

    private QqBindManager mgr() {
        try {
            return QqBindManager.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean ready() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("KERONGPenguin");
        return plugin != null && plugin.isEnabled();
    }

    public String getBoundPlayer(String qqOpenId) {
        if (!ready() || qqOpenId == null) return null;
        QqBindManager mgr = mgr();
        return mgr == null ? null : mgr.findPlayerByQq(qqOpenId);
    }

    public String getPlayerQq(String playerName) {
        if (!ready() || playerName == null) return null;
        QqBindManager mgr = mgr();
        return mgr == null ? null : mgr.getBoundQq(playerName);
    }

    public String getPlayerQuuid(String playerName) {
        if (!ready() || playerName == null) return null;
        QqBindManager mgr = mgr();
        return mgr == null ? null : mgr.getQuuid(playerName);
    }

    public boolean isPlayerBound(String playerName) {
        if (!ready() || playerName == null) return false;
        QqBindManager mgr = mgr();
        return mgr != null && mgr.isBound(playerName);
    }

    public boolean isBlacklisted(String qqOpenId) {
        if (!ready() || qqOpenId == null) return false;
        QqBindManager mgr = mgr();
        if (mgr == null) return false;
        try {
            return mgr.listBlacklist().contains(qqOpenId);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean depositCoins(String playerName, double amount) {
        if (!ready() || playerName == null || amount <= 0) return false;
        return GameChat.depositToPlayer(playerName, amount);
    }

    public double getPendingCoins(String playerName) {
        if (!ready() || playerName == null) return 0.0;
        QqBindManager mgr = mgr();
        if (mgr == null) return 0.0;
        String quuid = mgr.getQuuid(playerName);
        return quuid == null ? 0.0 : mgr.getPendingCoins(quuid);
    }

    public boolean isCheckinEnabled() {
        if (!ready()) return false;
        QqBindManager mgr = mgr();
        return mgr != null && mgr.isCheckinEnabled();
    }

    public double getCheckinReward() {
        if (!ready()) return 0.0;
        QqBindManager mgr = mgr();
        return mgr == null ? 0.0 : mgr.getCheckinReward();
    }
}
