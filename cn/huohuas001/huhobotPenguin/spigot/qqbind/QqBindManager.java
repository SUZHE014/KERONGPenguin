/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.ChatColor
 *  org.bukkit.configuration.ConfigurationSection
 *  org.bukkit.configuration.file.YamlConfiguration
 *  org.bukkit.entity.Player
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.plugin.java.JavaPlugin
 */
package cn.huohuas001.huhobotPenguin.spigot.qqbind;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class QqBindManager {
    private static volatile QqBindManager instance;
    private static final Object LOCK;
    private final JavaPlugin plugin;
    private final File quuidFolder;
    private final File indexFile;
    private final File skipFile;
    private final File logFile;
    private final File contextFile;
    private final ConcurrentHashMap<String, String> nameToQuuid = new ConcurrentHashMap();
    private final ConcurrentHashMap<String, PendingBind> pendingCodes = new ConcurrentHashMap();
    private final ConcurrentHashMap<String, Boolean> skipSet = new ConcurrentHashMap();
    private final ConcurrentHashMap<String, List<ChatMessage>> aiContext = new ConcurrentHashMap();
    private final ConcurrentHashMap<String, Boolean> aiContextEnabled = new ConcurrentHashMap();
    private final File blacklistFile;
    private final ConcurrentHashMap<String, String> blacklist = new ConcurrentHashMap();
    private static final String GLOBAL_CTX_KEY = "__global__";

    private QqBindManager(JavaPlugin javaPlugin) {
        this.plugin = javaPlugin;
        File file = javaPlugin.getDataFolder();
        if (file != null && !file.exists()) {
            file.mkdirs();
        }
        this.quuidFolder = new File(file, "QUUID");
        if (!this.quuidFolder.exists()) {
            this.quuidFolder.mkdirs();
        }
        this.indexFile = new File(this.quuidFolder, "index.yml");
        this.skipFile = new File(this.quuidFolder, "skip.yml");
        this.logFile = new File(file, "logs/qq/qq-bind.log");
        this.contextFile = new File(file, "ai-context.yml");
        this.blacklistFile = new File(this.quuidFolder, "blacklist.yml");
        this.loadIndex();
        this.loadSkip();
        this.loadBlacklist();
        this.loadAiContext();
        QqBindManager.logQuiet("===== QqBindManager \u5df2\u5c31\u7eea (enabled=" + this.isEnabled() + ", \u7ed1\u5b9a\u73a9\u5bb6\u6570=" + this.countBound() + ") =====");
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static QqBindManager getInstance() {
        Object object;
        QqBindManager qqBindManager = instance;
        if (qqBindManager != null) {
            return qqBindManager;
        }
        Object object2 = object = LOCK;
        synchronized (object2) {
            if (instance == null) {
                Plugin plugin = Bukkit.getPluginManager().getPlugin("KERONGPenguin");
                if (plugin == null) {
                    plugin = Bukkit.getPluginManager().getPlugin("KERONGPENGUIN");
                }
                if (!(plugin instanceof JavaPlugin)) {
                    throw new IllegalStateException("KERONGPenguin \u63d2\u4ef6\u672a\u52a0\u8f7d");
                }
                instance = new QqBindManager((JavaPlugin)plugin);
            }
            return instance;
        }
    }

    private static String key(String string) {
        return string == null ? "" : string.toLowerCase();
    }

    public boolean isEnabled() {
        try {
            return this.plugin.getConfig().getBoolean("qq-bind.enabled", false);
        }
        catch (Throwable throwable) {
            return false;
        }
    }

    public int getCodeExpireMinutes() {
        int n = this.plugin.getConfig().getInt("qq-bind.code-expire-minutes", 10);
        return n < 1 ? 10 : n;
    }

    public int getCodeLength() {
        int n = this.plugin.getConfig().getInt("qq-bind.code-length", 5);
        if (n < 4) {
            n = 4;
        }
        if (n > 8) {
            n = 8;
        }
        return n;
    }

    public boolean isAiEnabled() {
        try {
            return this.plugin.getConfig().getBoolean("ai.enabled", false);
        }
        catch (Throwable throwable) {
            return false;
        }
    }

    public boolean isAiUrlAutoAppend() {
        try {
            return this.plugin.getConfig().getBoolean("ai.url-auto-append", true);
        }
        catch (Throwable throwable) {
            return true;
        }
    }

    public String getAiBaseUrl() {
        try {
            return this.plugin.getConfig().getString("ai.base-url", "");
        }
        catch (Throwable throwable) {
            return "";
        }
    }

    public String getAiApiKey() {
        try {
            return this.plugin.getConfig().getString("ai.api-key", "");
        }
        catch (Throwable throwable) {
            return "";
        }
    }

    public String getAiModel() {
        try {
            return this.plugin.getConfig().getString("ai.model", "deepseek-chat");
        }
        catch (Throwable throwable) {
            return "deepseek-chat";
        }
    }

    public String getAiSystemPrompt() {
        try {
            return this.plugin.getConfig().getString("ai.system-prompt", "\u4f60\u662f\u4e00\u4e2a\u53cb\u597d\u7684\u6e38\u620f\u7fa4\u52a9\u624b\uff0c\u8bf7\u7b80\u6d01\u56de\u7b54\u3002");
        }
        catch (Throwable throwable) {
            return "\u4f60\u662f\u4e00\u4e2a\u53cb\u597d\u7684\u6e38\u620f\u7fa4\u52a9\u624b\uff0c\u8bf7\u7b80\u6d01\u56de\u7b54\u3002";
        }
    }

    public int getAiContextLimit() {
        try {
            return this.plugin.getConfig().getInt("ai.context-limit", 6);
        }
        catch (Throwable throwable) {
            return 6;
        }
    }

    public boolean isServerAiEnabled() {
        try {
            return this.plugin.getConfig().getBoolean("ai.server-enabled", false);
        }
        catch (Throwable throwable) {
            return false;
        }
    }

    public String getServerAiPrefix() {
        try {
            return this.plugin.getConfig().getString("ai.server-prefix", "ai");
        }
        catch (Throwable throwable) {
            return "ai";
        }
    }

    public String getServerAiOutputPrefix() {
        try {
            return this.plugin.getConfig().getString("ai.server-output-prefix", "[AI]");
        }
        catch (Throwable throwable) {
            return "[AI]";
        }
    }

    public String getQqAiOutputPrefix() {
        try {
            return this.plugin.getConfig().getString("ai.qq-output-prefix", "[AI]");
        }
        catch (Throwable throwable) {
            return "[AI]";
        }
    }

    public List<String> getKickMessageLines() {
        ArrayList<String> arrayList = new ArrayList<String>();
        List<String> list = this.plugin.getConfig().getStringList("qq-bind.kick-message");
        if (list != null && !list.isEmpty()) {
            for (int i = 0; i < list.size(); i++) { String string = list.get(i);
                if (string == null) continue;
                arrayList.add(string);
            }
        }
        if (arrayList.isEmpty()) {
            arrayList.add("&c===== QQ \u7ed1\u5b9a\u9a8c\u8bc1 =====");
            arrayList.add("&f\u4f60\u8fd8\u672a\u7ed1\u5b9a QQ\uff0c\u65e0\u6cd5\u8fdb\u5165\u670d\u52a1\u5668\u3002");
            arrayList.add("");
            arrayList.add("&e\u4f60\u7684\u4e13\u5c5e\u7ed1\u5b9a\u7801: &a&l{code}");
            arrayList.add("&7\u8bf7\u5728 QQ \u7fa4\u53d1\u9001: &f/\u7ed1\u5b9a {code}");
            arrayList.add("&7\uff08\u7ed1\u5b9a\u7801 {expire} \u5206\u949f\u5185\u6709\u6548\uff09");
        }
        return arrayList;
    }

    public String formatKickMessage(String string, String string2) {
        int n = this.getCodeExpireMinutes();
        StringBuilder stringBuilder = new StringBuilder();
        for (String string3 : this.getKickMessageLines()) {
            String string4 = string3 == null ? "" : string3;
            string4 = string4.replace("{code}", string == null ? "" : string).replace("{name}", string2 == null ? "" : string2).replace("{expire}", Integer.toString(n));
            string4 = ChatColor.translateAlternateColorCodes((char)'&', (String)string4);
            if (stringBuilder.length() > 0) {
                stringBuilder.append('\n');
            }
            stringBuilder.append(string4);
        }
        return stringBuilder.toString();
    }

    public synchronized String getOrCreateQuuid(String string) {
        String string2 = QqBindManager.key(string);
        String string3 = this.nameToQuuid.get(string2);
        if (string3 != null) {
            return string3;
        }
        String string4 = UUID.randomUUID().toString();
        this.nameToQuuid.put(string2, string4);
        this.saveIndex();
        File file = new File(this.quuidFolder, string4 + ".yml");
        if (!file.exists()) {
            try {
                file.createNewFile();
            }
            catch (IOException iOException) {
                // empty catch block
            }
        }
        this.saveQuuidRecord(string4, string, null, 0L, false);
        QqBindManager.logQuiet("[QUUID \u65b0\u5efa] \u73a9\u5bb6=" + string + " QUUID=" + string4);
        return string4;
    }

    public String getQuuid(String string) {
        return this.nameToQuuid.get(QqBindManager.key(string));
    }

    public boolean isBound(String string) {
        String string2 = this.getQuuid(string);
        if (string2 == null) {
            return false;
        }
        File file = new File(this.quuidFolder, string2 + ".yml");
        if (!file.exists()) {
            return false;
        }
        String string3 = YamlConfiguration.loadConfiguration((File)file).getString("qq", "");
        return string3 != null && !string3.isEmpty();
    }

    public String getBoundQq(String string) {
        String string2 = this.getQuuid(string);
        if (string2 == null) {
            return null;
        }
        File file = new File(this.quuidFolder, string2 + ".yml");
        if (!file.exists()) {
            return null;
        }
        return YamlConfiguration.loadConfiguration((File)file).getString("qq", null);
    }

    public List<String> listBoundPlayerNames() {
        ArrayList<String> arrayList = new ArrayList<String>();
        for (Map.Entry<String, String> entry : this.nameToQuuid.entrySet()) {
            String string;
            YamlConfiguration yamlConfiguration;
            String string2;
            File file = new File(this.quuidFolder, entry.getValue() + ".yml");
            if (!file.exists() || (string2 = (yamlConfiguration = YamlConfiguration.loadConfiguration((File)file)).getString("qq", "")) == null || string2.isEmpty() || (string = yamlConfiguration.getString("playerName", entry.getKey())) == null || string.isEmpty()) continue;
            arrayList.add(string);
        }
        return arrayList;
    }

    public boolean isSkipped(String string) {
        return this.skipSet.containsKey(QqBindManager.key(string));
    }

    public void setSkipped(String string, boolean bl) {
        String string2 = QqBindManager.key(string);
        if (bl) {
            this.skipSet.put(string2, true);
        } else {
            this.skipSet.remove(string2);
        }
        this.saveSkip();
    }

    public boolean isBlacklisted(String string) {
        return string != null && !string.isEmpty() && this.blacklist.containsKey(string);
    }

    public synchronized void addBlacklist(String string, String string2) {
        if (string == null || string.isEmpty()) {
            return;
        }
        String string3 = string2 != null && !string2.isEmpty() ? string2 : string;
        this.blacklist.put(string, string3);
        this.saveBlacklist();
        QqBindManager.logQuiet("[\u9ed1\u540d\u5355] \u6dfb\u52a0 QQ=" + string + " \u540d\u79f0=" + string3);
    }

    public synchronized void removeBlacklist(String string) {
        if (string == null) {
            return;
        }
        this.blacklist.remove(string);
        this.saveBlacklist();
        QqBindManager.logQuiet("[\u9ed1\u540d\u5355] \u79fb\u9664 QQ=" + string);
    }

    public List<String> listBlacklist() {
        return new ArrayList<String>(this.blacklist.keySet());
    }

    public List<String> listBlacklistNames() {
        return new ArrayList<String>(this.blacklist.values());
    }

    public String getBlacklistName(String string) {
        if (string == null) {
            return null;
        }
        return this.blacklist.get(string);
    }

    public String findPlayerByQq(String string) {
        if (string == null || string.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : this.nameToQuuid.entrySet()) {
            String string2;
            File file = new File(this.quuidFolder, entry.getValue() + ".yml");
            if (!file.exists() || !string.equals(string2 = YamlConfiguration.loadConfiguration((File)file).getString("qq", ""))) continue;
            return YamlConfiguration.loadConfiguration((File)file).getString("playerName", entry.getKey());
        }
        return null;
    }

    public String unbindAndKickByQq(String string) {
        String string2 = this.findPlayerByQq(string);
        if (string2 == null) {
            return null;
        }
        this.unbind(string2);
        QqBindManager.logQuiet("[\u89e3\u7ed1\u8e22\u51fa] QQ=" + string + " \u73a9\u5bb6=" + string2 + " \u5df2\u89e3\u7ed1");
        try {
            Player player = Bukkit.getPlayerExact((String)string2);
            if (player != null && player.isOnline()) {
                Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> player.kickPlayer("\u00a7c\u4f60\u7684 QQ \u5df2\u88ab\u89e3\u9664\u7ed1\u5b9a\uff0c\u8bf7\u91cd\u65b0\u7ed1\u5b9a\u540e\u8fdb\u5165\u670d\u52a1\u5668\u3002"));
                QqBindManager.logQuiet("[\u89e3\u7ed1\u8e22\u51fa] \u73a9\u5bb6=" + string2 + " \u5df2\u8e22\u51fa\u670d\u52a1\u5668");
            }
        }
        catch (Throwable throwable) {
            QqBindManager.logQuiet("[\u89e3\u7ed1\u8e22\u51fa] \u8e22\u51fa\u5931\u8d25: " + throwable.getMessage());
        }
        return string2;
    }

    public synchronized String generateCode(String string) {
        String string2;
        this.cleanExpiredCodes();
        String string3 = this.getOrCreateQuuid(string);
        String string4 = QqBindManager.key(string);
        for (Map.Entry<String, PendingBind> object2 : new ArrayList<Map.Entry<String, PendingBind>>(this.pendingCodes.entrySet())) {
            if (!string4.equals(object2.getValue().playerKey)) continue;
            this.pendingCodes.remove(object2.getKey());
        }
        int n = this.getCodeLength();
        Random random = new Random();
        int n2 = 0;
        while (this.pendingCodes.containsKey(string2 = QqBindManager.randomDigits(n, random)) && ++n2 < 50) {
        }
        long l = System.currentTimeMillis() + (long)this.getCodeExpireMinutes() * 60000L;
        this.pendingCodes.put(string2, new PendingBind(string, string4, string3, l));
        return string2;
    }

    public synchronized boolean confirmBinding(String string, String string2) {
        if (string == null || string2 == null || string2.isEmpty()) {
            return false;
        }
        if ((string = string.trim()).isEmpty()) {
            return false;
        }
        this.cleanExpiredCodes();
        PendingBind pendingBind = this.pendingCodes.get(string);
        if (pendingBind == null) {
            return false;
        }
        if (this.isBlacklisted(string2)) {
            QqBindManager.logQuiet("[\u7ed1\u5b9a\u62d2\u7edd] QQ=" + string2 + " \u5728\u9ed1\u540d\u5355\u4e2d\uff0c\u62d2\u7edd\u7ed1\u5b9a \u73a9\u5bb6=" + pendingBind.playerName);
            this.pendingCodes.remove(string);
            return false;
        }
        String string3 = this.findPlayerByQq(string2);
        if (string3 != null && !string3.equalsIgnoreCase(pendingBind.playerName)) {
            QqBindManager.logQuiet("[\u7ed1\u5b9a\u62d2\u7edd] QQ=" + string2 + " \u5df2\u7ed1\u5b9a\u73a9\u5bb6=" + string3 + "\uff0c\u62d2\u7edd\u91cd\u590d\u7ed1\u5b9a \u73a9\u5bb6=" + pendingBind.playerName);
            this.pendingCodes.remove(string);
            return false;
        }
        this.saveQuuidRecord(pendingBind.quuid, pendingBind.playerName, string2, System.currentTimeMillis(), false);
        this.pendingCodes.remove(string);
        QqBindManager.logQuiet("[\u7ed1\u5b9a\u6210\u529f] \u73a9\u5bb6=" + pendingBind.playerName + " QUUID=" + pendingBind.quuid + " QQ=" + string2);
        return true;
    }

    public synchronized boolean unbind(String string) {
        String string2 = this.getQuuid(string);
        if (string2 == null) {
            return false;
        }
        File file = new File(this.quuidFolder, string2 + ".yml");
        if (!file.exists()) {
            return false;
        }
        YamlConfiguration yamlConfiguration = YamlConfiguration.loadConfiguration((File)file);
        yamlConfiguration.set("qq", (Object)"");
        yamlConfiguration.set("boundAt", (Object)0L);
        try {
            yamlConfiguration.save(file);
        }
        catch (IOException iOException) {
            // empty catch block
        }
        QqBindManager.logQuiet("[\u89e3\u9664\u7ed1\u5b9a] \u73a9\u5bb6=" + string + " QUUID=" + string2);
        return true;
    }

    private String ctxKey(String string, String string2) {
        return string + "|" + string2;
    }

    public boolean isAiContextEnabled(String string, String string2) {
        return this.aiContextEnabled.getOrDefault(GLOBAL_CTX_KEY, false);
    }

    public void setAiContextEnabled(String string, String string2, boolean bl) {
        this.aiContextEnabled.put(GLOBAL_CTX_KEY, bl);
        this.saveAiContext();
        if (!bl) {
            this.aiContext.remove(GLOBAL_CTX_KEY);
        }
    }

    public List<ChatMessage> getAiContext(String string, String string2) {
        if (!this.isAiContextEnabled(string, string2)) {
            return null;
        }
        List<ChatMessage> list = this.aiContext.get(GLOBAL_CTX_KEY);
        return list != null ? new ArrayList<ChatMessage>(list) : new ArrayList();
    }

    public void appendAiContext(String string2, String string3, String string4, String string5) {
        if (!this.isAiContextEnabled(string2, string3)) {
            return;
        }
        List list = this.aiContext.computeIfAbsent(GLOBAL_CTX_KEY, string -> new ArrayList());
        list.add(new ChatMessage("user", string4));
        list.add(new ChatMessage("assistant", string5));
        int n = this.getAiContextLimit() * 2;
        while (list.size() > n) {
            list.remove(0);
        }
        this.saveAiContext();
    }

    public void clearAiContext(String string, String string2) {
        this.aiContext.remove(GLOBAL_CTX_KEY);
    }

    private int countBound() {
        int n = 0;
        for (String string : this.nameToQuuid.values()) {
            String string2;
            File file = new File(this.quuidFolder, string + ".yml");
            if (!file.exists() || (string2 = YamlConfiguration.loadConfiguration((File)file).getString("qq", "")) == null || string2.isEmpty()) continue;
            ++n;
        }
        return n;
    }

    private void cleanExpiredCodes() {
        long l = System.currentTimeMillis();
        for (Map.Entry<String, PendingBind> entry : new ArrayList<Map.Entry<String, PendingBind>>(this.pendingCodes.entrySet())) {
            if (entry.getValue().expireAt >= l) continue;
            this.pendingCodes.remove(entry.getKey());
        }
    }

    private static String randomDigits(int n, Random random) {
        StringBuilder stringBuilder = new StringBuilder(n);
        for (int i = 0; i < n; ++i) {
            stringBuilder.append(random.nextInt(10));
        }
        return stringBuilder.toString();
    }

    private void loadIndex() {
        if (!this.indexFile.exists()) {
            return;
        }
        YamlConfiguration yamlConfiguration = YamlConfiguration.loadConfiguration((File)this.indexFile);
        ConfigurationSection configurationSection = yamlConfiguration.getConfigurationSection("players");
        if (configurationSection == null) {
            return;
        }
        for (String string : configurationSection.getKeys(false)) {
            String string2 = configurationSection.getString(string, "");
            if (string2 == null || string2.isEmpty()) continue;
            this.nameToQuuid.put(string, string2);
        }
    }

    private void saveIndex() {
        YamlConfiguration yamlConfiguration = new YamlConfiguration();
        for (Map.Entry<String, String> entry : this.nameToQuuid.entrySet()) {
            yamlConfiguration.set("players." + entry.getKey(), (Object)entry.getValue());
        }
        try {
            yamlConfiguration.save(this.indexFile);
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    private void loadSkip() {
        if (!this.skipFile.exists()) {
            return;
        }
        YamlConfiguration yamlConfiguration = YamlConfiguration.loadConfiguration((File)this.skipFile);
        ConfigurationSection configurationSection = yamlConfiguration.getConfigurationSection("players");
        if (configurationSection == null) {
            return;
        }
        for (String string : configurationSection.getKeys(false)) {
            if (!configurationSection.getBoolean(string, false)) continue;
            this.skipSet.put(string, true);
        }
    }

    private void saveSkip() {
        YamlConfiguration yamlConfiguration = new YamlConfiguration();
        for (String string : this.skipSet.keySet()) {
            yamlConfiguration.set("players." + string, (Object)true);
        }
        try {
            yamlConfiguration.save(this.skipFile);
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    private void loadBlacklist() {
        if (!this.blacklistFile.exists()) {
            return;
        }
        YamlConfiguration yamlConfiguration = YamlConfiguration.loadConfiguration((File)this.blacklistFile);
        ConfigurationSection configurationSection = yamlConfiguration.getConfigurationSection("qq");
        if (configurationSection == null) {
            return;
        }
        for (String string : configurationSection.getKeys(false)) {
            String string2 = configurationSection.getString(string, string);
            this.blacklist.put(string, string2);
        }
    }

    private void saveBlacklist() {
        YamlConfiguration yamlConfiguration = new YamlConfiguration();
        for (Map.Entry<String, String> entry : this.blacklist.entrySet()) {
            yamlConfiguration.set("qq." + entry.getKey(), (Object)entry.getValue());
        }
        try {
            yamlConfiguration.save(this.blacklistFile);
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    private void saveQuuidRecord(String string, String string2, String string3, long l, boolean bl) {
        File file = new File(this.quuidFolder, string + ".yml");
        YamlConfiguration yamlConfiguration = file.exists() ? YamlConfiguration.loadConfiguration((File)file) : new YamlConfiguration();
        YamlConfiguration yamlConfiguration2 = yamlConfiguration;
        if (string2 != null) {
            yamlConfiguration.set("playerName", (Object)string2);
        }
        if (string3 != null) {
            yamlConfiguration.set("qq", (Object)string3);
        }
        if (l > 0L) {
            yamlConfiguration.set("boundAt", (Object)l);
        }
        if (bl) {
            yamlConfiguration.set("skip", (Object)true);
        }
        try {
            yamlConfiguration.save(file);
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    private void loadAiContext() {
        if (!this.contextFile.exists()) {
            return;
        }
        YamlConfiguration yamlConfiguration = YamlConfiguration.loadConfiguration((File)this.contextFile);
        ConfigurationSection configurationSection = yamlConfiguration.getConfigurationSection("enabled");
        if (configurationSection != null) {
            for (String string : configurationSection.getKeys(false)) {
                if (!configurationSection.getBoolean(string, false)) continue;
                this.aiContextEnabled.put(string, true);
            }
        }
    }

    private void saveAiContext() {
        YamlConfiguration yamlConfiguration = new YamlConfiguration();
        for (Map.Entry<String, Boolean> entry : this.aiContextEnabled.entrySet()) {
            yamlConfiguration.set("enabled." + entry.getKey(), (Object)entry.getValue());
        }
        try {
            yamlConfiguration.save(this.contextFile);
        }
        catch (IOException iOException) {
            // empty catch block
        }
    }

    public static void logQuiet(String string) {
        block11: {
            Object object;
            String string2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String string3 = string.startsWith("[") ? "" : "[QQ\u7ed1\u5b9a] ";
            String string4 = "[" + string2 + "] " + string3 + string;
            try {
                object = instance;
                if (object != null && ((QqBindManager)object).plugin != null) {
                    ((QqBindManager)object).plugin.getLogger().info(string4);
                } else {
                    System.out.println(string4);
                }
            }
            catch (Throwable throwable) {
                System.out.println(string4);
            }
            try {
                object = QqBindManager.getLogFile();
                if (object == null) break block11;
                try (PrintWriter printWriter = new PrintWriter(new FileWriter((File)object, true));){
                    printWriter.println(string4);
                }
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
    }

    public static void logVerbose(String string) {
        try {
            File file = QqBindManager.getLogFile();
            if (file == null) {
                return;
            }
            String string2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String string3 = string.startsWith("[") ? "" : "[QQ\u7ed1\u5b9a][\u8be6\u7ec6] ";
            try (PrintWriter printWriter = new PrintWriter(new FileWriter(file, true));){
                printWriter.println("[" + string2 + "] " + string3 + string);
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private static File getLogFile() {
        try {
            QqBindManager qqBindManager = instance;
            if (qqBindManager == null || qqBindManager.logFile == null) {
                return null;
            }
            File file = qqBindManager.logFile.getParentFile();
            if (file == null) {
                return null;
            }
            if (!file.exists()) {
                file.mkdirs();
            }
            String string = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            String string2 = "qq-bind-" + string;
            File file2 = new File(file, string2 + ".log");
            long l = 0x40000000L;
            if (file2.exists() && file2.length() >= l) {
                int n = 1;
                while (true) {
                    File file3;
                    if (!(file3 = new File(file, string2 + "-" + n + ".log")).exists() || file3.length() < l) {
                        file2 = file3;
                        break;
                    }
                    ++n;
                }
            }
            return file2;
        }
        catch (Throwable throwable) {
            return null;
        }
    }

    static {
        LOCK = new Object();
    }

    public static final class PendingBind {
        public final String playerName;
        public final String playerKey;
        public final String quuid;
        public final long expireAt;

        PendingBind(String string, String string2, String string3, long l) {
            this.playerName = string;
            this.playerKey = string2;
            this.quuid = string3;
            this.expireAt = l;
        }
    }

    public static final class ChatMessage {
        public final String role;
        public final String content;

        public ChatMessage(String string, String string2) {
            this.role = string;
            this.content = string2;
        }
    }
}

