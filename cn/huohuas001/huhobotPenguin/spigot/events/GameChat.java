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
import org.bukkit.event.entity.PlayerDeathEvent;
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
        QClient.INSTANCE.broadcastPlayerJoin(playerJoinEvent.getPlayer().getName());
    }

    @EventHandler
    public final void onPlayerQuit(PlayerQuitEvent playerQuitEvent) {
        QClient.INSTANCE.broadcastPlayerQuit(playerQuitEvent.getPlayer().getName());
    }

    @EventHandler
    public final void onPlayerDeath(PlayerDeathEvent playerDeathEvent) {
        try {
            QqBindManager mgr;
            try { mgr = QqBindManager.getInstance(); } catch (Throwable t) { return; }
            if (!mgr.isDeathNotifyEnabled()) {
                return;
            }
            String deathMessage = playerDeathEvent.getDeathMessage();
            if (deathMessage == null || deathMessage.trim().isEmpty()) {
                return;
            }
            String prefix = mgr.getDeathNotifyPrefix();
            if (prefix == null || prefix.isEmpty()) {
                prefix = "[\u6b7b\u4ea1\u901a\u62a5]";
            }
            final String content = prefix + " " + deathMessage;
            final Plugin plugin = Bukkit.getPluginManager().getPlugin("KERONGPenguin");
            if (plugin == null) {
                return;
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
                @Override
                public void run() {
                    try {
                        Method method = QClient.class.getDeclaredMethod("sendTextToGroups", String.class, String.class);
                        method.setAccessible(true);
                        method.invoke(QClient.INSTANCE, content, "\u53d1\u9001\u6b7b\u4ea1\u901a\u62a5");
                    } catch (Throwable t) {
                        QqBindManager.logQuiet("[\u6b7b\u4ea1\u901a\u62a5] \u53d1\u9001\u5931\u8d25: " + GameChat.safeMessage(t));
                    }
                }
            });
        } catch (Throwable throwable) {
            // empty catch block
        }
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

