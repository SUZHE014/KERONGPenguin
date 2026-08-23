/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.command.CommandExecutor
 *  org.bukkit.command.PluginCommand
 *  org.bukkit.command.TabCompleter
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.EventPriority
 *  org.bukkit.event.Listener
 *  org.bukkit.event.player.AsyncPlayerChatEvent
 *  org.bukkit.event.player.PlayerJoinEvent
 *  org.bukkit.event.player.PlayerLoginEvent
 *  org.bukkit.event.player.PlayerLoginEvent$Result
 *  org.bukkit.event.player.PlayerQuitEvent
 *  org.bukkit.plugin.Plugin
 */
package cn.huohuas001.huhobotPenguin.spigot.events;

import cn.huohuas001.bot.QClient;
import cn.huohuas001.bot.events.commands.BindCommands;
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot;
import cn.huohuas001.huhobotPenguin.spigot.commands.QqCommand;
import cn.huohuas001.huhobotPenguin.spigot.qqbind.AiChat;
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

public final class GameChat
implements Listener {
    private static volatile boolean bindRegistered = false;
    private static int retryCount = 0;
    private static final int MAX_RETRIES = 8;

    public GameChat() {
        try {
            QqBindManager.getInstance();
        }
        catch (Throwable throwable) {
            QqBindManager.logQuiet("QqBindManager \u521d\u59cb\u5316\u5931\u8d25: " + GameChat.safeMessage(throwable));
        }
        GameChat.suppressForgeNetworkLogs();
        GameChat.redirectSdkLogs();
        GameChat.tryRegisterBind();
        Plugin plugin = Bukkit.getPluginManager().getPlugin("KERONGPenguin");
        if (plugin == null) {
            plugin = Bukkit.getPluginManager().getPlugin("KERONGPENGUIN");
        }
        if (plugin != null) {
            try {
                HuHoBotSpigot huHoBotSpigot;
                PluginCommand pluginCommand;
                if (plugin instanceof HuHoBotSpigot && (pluginCommand = (huHoBotSpigot = (HuHoBotSpigot)plugin).getCommand("qq")) != null) {
                    QqCommand qqCommand = new QqCommand(huHoBotSpigot);
                    pluginCommand.setExecutor((CommandExecutor)qqCommand);
                    pluginCommand.setTabCompleter((TabCompleter)qqCommand);
                    QqBindManager.logVerbose("[GameChat] /qq \u670d\u52a1\u5668\u547d\u4ee4\u5df2\u6ce8\u518c");
                }
            }
            catch (Throwable throwable) {
                QqBindManager.logQuiet("[GameChat] /qq \u6ce8\u518c\u5931\u8d25: " + GameChat.safeMessage(throwable));
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!bindRegistered) {
                    GameChat.tryRegisterBind();
                }
            }, 40L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                GameChat.checkEconomyForCheckin();
            }, 60L);
        }
    }

    private static synchronized void tryRegisterBind() {
        if (bindRegistered || retryCount >= 8) {
            return;
        }
        ++retryCount;
        try {
            QClient.INSTANCE.registerCommand(new BindCommands());
            bindRegistered = true;
            QqBindManager.logQuiet("/\u7ed1\u5b9a \u547d\u4ee4\u5df2\u6ce8\u518c (\u7b2c " + retryCount + " \u6b21\u5c1d\u8bd5)");
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    @EventHandler(ignoreCancelled=true)
    public final void onChat(AsyncPlayerChatEvent event) {
        String message = event.getMessage();
        String playerName = event.getPlayer().getName();
        QqBindManager mgr;
        try { mgr = QqBindManager.getInstance(); } catch (Throwable t) { mgr = null; }
        if (mgr != null && mgr.isServerAiEnabled() && mgr.isAiEnabled()) {
            String prefix = mgr.getServerAiPrefix();
            if (prefix != null && !prefix.isEmpty() && message.toLowerCase().startsWith(prefix + " ")) {
                String content = message.substring(prefix.length() + 1).trim();
                if (!content.isEmpty()) {
                    event.setCancelled(true);
                    final String fpName = playerName;
                    final String fContent = content;
                    final QqBindManager fMgr = mgr;
                    final String fPlayerName = fpName;
                    final String fContent2 = fContent;
                    final QqBindManager fMgr2 = fMgr;
                    Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("KERONGPenguin"), new Runnable() {
                        @Override
                        public void run() {
                            try {
                                final String aiReply = AiChat.chat(fContent2, fMgr2, "server", fPlayerName);
                                final String aiOutPrefix = fMgr2.getServerAiOutputPrefix();
                                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("KERONGPenguin"), new Runnable() {
                                    @Override
                                    public void run() {
                                        Bukkit.broadcastMessage(aiOutPrefix + " " + aiReply);
                                    }
                                });
                        } catch (final Throwable t2) {
                            QqBindManager.logQuiet("[AI\u5bf9\u8bdd] \u670d\u52a1\u5668\u8c03\u7528\u5931\u8d25: " + t2.getMessage());
                            final String errPrefix = fMgr2.getServerAiOutputPrefix();
                            final String errMsg = t2.getMessage();
                            Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("KERONGPenguin"), new Runnable() {
                                @Override
                                public void run() {
                                    Bukkit.broadcastMessage(errPrefix + " AI \u5bf9\u8bdd\u5931\u8d25: " + errMsg);
                                }
                            });
                        }
                    }
                    });
                    return;
                }
            }
        }
        QClient.INSTANCE.broadcastGameMessage(playerName, message);
    }

    @EventHandler
    public final void onPlayerJoin(PlayerJoinEvent playerJoinEvent) {
        final String playerName = playerJoinEvent.getPlayer().getName();
        QClient.INSTANCE.broadcastPlayerJoin(playerName);
        Plugin plugin = Bukkit.getPluginManager().getPlugin("KERONGPenguin");
        if (plugin == null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    QqBindManager mgr = QqBindManager.getInstance();
                    String quuid = mgr.getQuuid(playerName);
                    if (quuid == null || quuid.isEmpty()) {
                        return;
                    }
                    final double pending = mgr.takePendingCoins(quuid);
                    if (pending > 0) {
                        Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("KERONGPenguin"), new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    org.bukkit.entity.Player player = Bukkit.getPlayerExact(playerName);
                                    if (player != null && player.isOnline()) {
                                        boolean ok = depositToPlayer(playerName, pending);
                                        if (ok) {
                                            player.sendMessage("\u00a7a[\u7b7e\u5230\u5956\u52b1] \u9886\u53d6\u7b7e\u5230\u5956\u52b1 " + formatMoney(pending) + " \u91d1\u5e01");
                                        } else {
                                            QqBindManager.logQuiet("[\u7b7e\u5230\u5956\u52b1] \u7ed9\u4e88\u73a9\u5bb6 " + playerName + " \u91d1\u5e01\u5931\u8d25\uff08Vault\uff09");
                                        }
                                    }
                                } catch (Throwable t) {
                                    QqBindManager.logQuiet("[\u7b7e\u5230\u5956\u52b1] \u73a9\u5bb6\u4e0a\u7ebf\u9886\u53d6\u5f02\u5e38: " + GameChat.safeMessage(t));
                                }
                            }
                        });
                    }
                } catch (Throwable t) {
                    // empty catch block
                }
            }
        });
    }

    public static boolean depositToPlayer(String playerName, double amount) {
        try {
            Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
            if (vault == null) {
                return false;
            }
            org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Class<?> registeredServiceProviderClass = Class.forName("org.bukkit.plugin.RegisteredServiceProvider");
            Method getServicesManager = Bukkit.getServer().getClass().getMethod("getServicesManager", new Class[0]);
            Object servicesManager = getServicesManager.invoke(Bukkit.getServer(), new Object[0]);
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
            Method depositPlayer = economyClass.getMethod("depositPlayer", org.bukkit.OfflinePlayer.class, Double.TYPE);
            Object result = depositPlayer.invoke(economy, offlinePlayer, amount);
            if (result == null) {
                return false;
            }
            try {
                Method transactionSuccess = result.getClass().getMethod("transactionSuccess", new Class[0]);
                Object success = transactionSuccess.invoke(result, new Object[0]);
                return Boolean.TRUE.equals(success);
            } catch (Throwable throwable) {
                return true;
            }
        } catch (Throwable throwable) {
            return false;
        }
    }

    public static String formatMoney(double amount) {
        if (amount == (long)amount) {
            return String.valueOf((long)amount);
        }
        return String.valueOf(amount);
    }

    private static void checkEconomyForCheckin() {
        try {
            QqBindManager mgr = QqBindManager.getInstance();
            if (!mgr.isCheckinEnabled()) {
                return;
            }
            Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
            if (vault != null && vault.isEnabled()) {
                boolean economyReady = false;
                try {
                    Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
                    Method getServicesManager = Bukkit.getServer().getClass().getMethod("getServicesManager", new Class[0]);
                    Object servicesManager = getServicesManager.invoke(Bukkit.getServer(), new Object[0]);
                    Method getRegistration = servicesManager.getClass().getMethod("getRegistration", Class.class);
                    Object rsp = getRegistration.invoke(servicesManager, economyClass);
                    if (rsp != null) {
                        Class<?> registeredServiceProviderClass = Class.forName("org.bukkit.plugin.RegisteredServiceProvider");
                        Method getProvider = registeredServiceProviderClass.getMethod("getProvider", new Class[0]);
                        Object economy = getProvider.invoke(rsp, new Object[0]);
                        if (economy != null) {
                            economyReady = true;
                        }
                    }
                } catch (Throwable t) {
                    // empty catch block
                }
                if (economyReady) {
                    Bukkit.getConsoleSender().sendMessage("\u00a7a[KERONGPenguin] \u7b7e\u5230\u529f\u80fd\u5df2\u5f00\u542f\uff0c\u5df2\u68c0\u6d4b\u5230\u7ecf\u6d4e\u63d2\u4ef6 Vault\uff08Economy \u5c31\u7eea\uff09\u3002");
                } else {
                    Bukkit.getConsoleSender().sendMessage("\u00a7c[KERONGPenguin] \u7b7e\u5230\u529f\u80fd\u5df2\u5f00\u542f\uff0c\u4f46\u672a\u68c0\u6d4b\u5230\u7ecf\u6d4e\u524d\u7f6e\uff08Economy \u672a\u5c31\u7eea\uff09\u3002\u8bf7\u5b89\u88c5 Vault \u53ca\u4efb\u4e00\u7ecf\u6d4e\u63d2\u4ef6\uff08\u5982 EssentialsX\uff09\u3002");
                }
            } else {
                Bukkit.getConsoleSender().sendMessage("\u00a7c[KERONGPenguin] \u7b7e\u5230\u529f\u80fd\u5df2\u5f00\u542f\uff0c\u4f46\u672a\u68c0\u6d4b\u5230 Vault \u7ecf\u6d4e\u63d2\u4ef6\u3002\u8bf7\u5b89\u88c5 Vault \u53ca\u4efb\u4e00\u7ecf\u6d4e\u63d2\u4ef6\uff08\u5982 EssentialsX\uff09\u3002");
            }
        } catch (Throwable t) {
            QqBindManager.logQuiet("[GameChat] \u7b7e\u5230\u7ecf\u6d4e\u68c0\u6d4b\u5931\u8d25: " + GameChat.safeMessage(t));
        }
    }

    @EventHandler
    public final void onPlayerQuit(PlayerQuitEvent playerQuitEvent) {
        QClient.INSTANCE.broadcastPlayerQuit(playerQuitEvent.getPlayer().getName());
    }

    @EventHandler(priority=EventPriority.LOW)
    public final void onPlayerLogin(PlayerLoginEvent playerLoginEvent) {
        QqBindManager qqBindManager;
        if (!bindRegistered) {
            GameChat.tryRegisterBind();
        }
        try {
            qqBindManager = QqBindManager.getInstance();
        }
        catch (Throwable throwable) {
            return;
        }
        if (!qqBindManager.isEnabled()) {
            return;
        }
        String string = playerLoginEvent.getPlayer().getName();
        if (qqBindManager.isSkipped(string)) {
            return;
        }
        qqBindManager.getOrCreateQuuid(string);
        if (qqBindManager.isBound(string)) {
            return;
        }
        String string2 = qqBindManager.generateCode(string);
        playerLoginEvent.disallow(PlayerLoginEvent.Result.KICK_OTHER, qqBindManager.formatKickMessage(string2, string));
    }

    private static String safeMessage(Throwable throwable) {
        String string = throwable.getMessage();
        return string == null || string.isEmpty() ? throwable.toString() : string;
    }

    private static void suppressForgeNetworkLogs() {
        try {
            String[] stringArray = new String[]{"net.minecraftforge.network", "net.minecraftforge.network.NetworkEvent", "net.minecraftforge.network.HandshakeMessages", "net.minecraftforge.fml", "net.minecraftforge.fml.network", "fml", "FMLHandshakeHandler", "net.minecraft.network", "net.minecraftforge.fml.network.FMLNetworkConstants"};
            Class<?> clazz = null;
            Class<?> clazz2 = null;
            Class<?> clazz3 = null;
            try {
                clazz = Class.forName("org.apache.logging.log4j.LogManager");
                clazz2 = Class.forName("org.apache.logging.log4j.Level");
                clazz3 = Class.forName("org.apache.logging.log4j.core.config.Configurator");
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            if (clazz != null && clazz2 != null && clazz3 != null) {
                Method method = clazz3.getMethod("setLevel", String.class, clazz2);
                Field field = clazz2.getField("WARN");
                Object object = field.get(null);
                for (String string : stringArray) {
                    try {
                        method.invoke(null, string, object);
                    }
                    catch (Throwable throwable) {
                        // empty catch block
                    }
                }
                QqBindManager.logQuiet("[GameChat] \u5df2\u6291\u5236 Forge \u7f51\u7edc\u65e5\u5fd7");
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private static void redirectSdkLogs() {
        try {
            Class<?> clazz = Class.forName("io.github.kloping.qqbot.utils.LoggerImpl$LogSink");
            Class<?> clazz2 = Class.forName("io.github.kloping.qqbot.utils.LoggerImpl");
            Method method2 = clazz2.getMethod("setLogSink", clazz);
            Object object2 = Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz}, (object, method, objectArray) -> {
                block8: {
                    if (method.getName().equals("log") && objectArray != null && objectArray.length >= 1) {
                        String string = String.valueOf(objectArray[0]);
                        try {
                            File file = GameChat.getBotLogFile();
                            if (file == null) break block8;
                            try (PrintWriter printWriter = new PrintWriter(new FileWriter(file, true));){
                                String string2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                                printWriter.println("[" + string2 + "] [Bot] " + string);
                            }
                        }
                        catch (Throwable throwable) {
                            // empty catch block
                        }
                    }
                }
                return null;
            });
            method2.invoke(null, object2);
            QqBindManager.logQuiet("[GameChat] \u5df2\u91cd\u5b9a\u5411 Bot SDK \u65e5\u5fd7\u5230\u6587\u4ef6");
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private static File getBotLogFile() {
        try {
            QqBindManager qqBindManager = QqBindManager.getInstance();
            Method method = QqBindManager.class.getDeclaredMethod("getLogFile", new Class[0]);
            method.setAccessible(true);
            return (File)method.invoke(null, new Object[0]);
        }
        catch (Throwable throwable) {
            return null;
        }
    }
}

