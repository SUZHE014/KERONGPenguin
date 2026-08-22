/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.configuration.ConfigurationSection
 *  org.bukkit.configuration.file.FileConfiguration
 */
package cn.huohuas001.huhobotPenguin.spigot.manager;

import cn.huohuas001.bot.agent.AgentCommandMode;
import cn.huohuas001.bot.provider.AdminMode;
import cn.huohuas001.bot.provider.ChatFormat;
import cn.huohuas001.bot.provider.CustomCommandDetail;
import cn.huohuas001.bot.provider.Motd;
import cn.huohuas001.bot.provider.PlayerEventFormat;
import cn.huohuas001.bot.provider.WhiteList;
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class ConfigManager {
    private static final String CONFIG_VERSION_PATH = "config-version";
    private static final int CURRENT_CONFIG_VERSION = 9;
    private static final List<String> COMMAND_NAMES = Collections.unmodifiableList(Arrays.asList("\u67e5\u4fe1\u606f", "\u7ed1\u5b9a", "\u91cd\u65b0\u7ed1\u5b9a", "\u67e5\u5728\u7ebf", "\u5728\u7ebf\u670d\u52a1\u5668", "\u53d1\u4fe1\u606f", "\u6267\u884c\u547d\u4ee4", "\u6267\u884c", "\u7ba1\u7406\u5458\u6267\u884c", "\u5168\u91cf", "motd", "\u5e2e\u52a9", "AI\u5bf9\u8bdd\u4e0a\u4e0b\u6587", "\u6e05\u9664\u5f53\u524d\u4e0a\u4e0b\u6587", "\u9ed1\u540d\u5355", "\u89e3\u9664\u9ed1\u540d\u5355"));
    private static final Map<String, Object> DEFAULT_VALUES;
    private final HuHoBotSpigot plugin;

    public ConfigManager(HuHoBotSpigot huHoBotSpigot) {
        this.plugin = huHoBotSpigot;
    }

    public File getConfigFile() {
        return new File(this.plugin.getDataFolder(), "config.yml");
    }

    public void initialize() {
        this.plugin.saveDefaultConfig();
        this.reload();
    }

    public void reload() {
        this.plugin.reloadConfig();
        FileConfiguration fileConfiguration = this.plugin.getConfig();
        boolean bl = false;
        for (Map.Entry<String, Object> stringArray : DEFAULT_VALUES.entrySet()) {
            String string = stringArray.getKey();
            if (fileConfiguration.contains(string)) continue;
            fileConfiguration.set(string, stringArray.getValue());
            bl = true;
        }
        int n = fileConfiguration.getInt(CONFIG_VERSION_PATH, 0);
        if (n != 9) {
            fileConfiguration.set(CONFIG_VERSION_PATH, (Object)9);
            bl = true;
        }
        for (String string : new String[]{"agent", "audit", "whitelist"}) {
            if (!fileConfiguration.contains(string)) continue;
            fileConfiguration.set(string, null);
            bl = true;
        }
        if (bl) {
            this.plugin.saveConfig();
            this.plugin.getLogger().info("\u914d\u7f6e\u6587\u4ef6\u5df2\u5347\u7ea7\u5230\u7248\u672c 9\uff08\u65e7\u7248\u672c\uff1a" + n + "\uff09");
        }
    }

    public String botAppId() {
        String string = this.plugin.getConfig().getString("bot.app-id");
        return string == null ? "" : string;
    }

    public String botSecret() {
        String string = this.plugin.getConfig().getString("bot.secret");
        return string == null ? "" : string;
    }

    public String botName() {
        return this.plugin.getConfig().getString("bot.name", "KERONGPenguin");
    }

    public String serverName() {
        return this.plugin.getConfig().getString("serverName", this.botName());
    }

    public List<String> groupOpenIds() {
        return this.plugin.getConfig().getStringList("bot.groups");
    }

    public boolean suppressQqBotConsoleOutput() {
        return this.plugin.getConfig().getBoolean("bot.suppress-console-output", true);
    }

    public String commandSender() {
        return this.plugin.getConfig().getString("command-sender", "Hybrid");
    }

    public ChatFormat chatFormat() {
        return new ChatFormat(this.plugin.getConfig().getString("chat-format.from-game", "[\u6e38\u620f] {message}"), this.plugin.getConfig().getString("chat-format.from-group", "[QQ] {name}: {message}"), this.plugin.getConfig().getBoolean("chat-format.post-chat", true), this.plugin.getConfig().getString("chat-format.start-with", ""));
    }

    public PlayerEventFormat playerEventFormat() {
        return new PlayerEventFormat(this.plugin.getConfig().getBoolean("player-events.join.enabled", true), this.plugin.getConfig().getString("player-events.join.format", "[\u6e38\u620f] {name} \u52a0\u5165\u4e86\u670d\u52a1\u5668"), this.plugin.getConfig().getBoolean("player-events.quit.enabled", true), this.plugin.getConfig().getString("player-events.quit.format", "[\u6e38\u620f] {name} \u79bb\u5f00\u4e86\u670d\u52a1\u5668"));
    }

    public Map<String, String> markdownFiles() {
        LinkedHashMap<String, String> linkedHashMap = new LinkedHashMap<String, String>();
        ConfigurationSection configurationSection = this.plugin.getConfig().getConfigurationSection("markdown");
        if (configurationSection != null) {
            for (String string : configurationSection.getKeys(false)) {
                String string2 = configurationSection.getString(string);
                if (string2 == null) continue;
                linkedHashMap.put(string, string2);
            }
        }
        if (!linkedHashMap.containsKey("queryOnline")) {
            linkedHashMap.put("queryOnline", "online.md");
        }
        return linkedHashMap;
    }

    public WhiteList whiteList() {
        return new WhiteList("whitelist add {name}", "whitelist remove {name}");
    }

    public Motd motd() {
        return new Motd(this.plugin.getConfig().getString("motd.server-ip", "127.0.0.1"), this.plugin.getConfig().getInt("motd.server-port", this.plugin.getServer().getPort()), this.plugin.getConfig().getString("motd.api", "http://motd.txssb.cn/api/app_img?ip={ip}&port={port}&dark=true&lang=zh-CN"), this.plugin.getConfig().getString("motd.text", ""), this.plugin.getConfig().getBoolean("motd.post-img", false), this.plugin.getConfig().getBoolean("motd.use-markdown", false));
    }

    public List<String> filterRegexList() {
        return this.plugin.getConfig().getStringList("filter-regex");
    }

    public AdminMode adminMode() {
        AdminMode adminMode = AdminMode.Companion.from(this.plugin.getConfig().getString("admin.mode", "qq"));
        return adminMode == null ? AdminMode.QQ : adminMode;
    }

    public List<String> adminOpenIds() {
        return this.plugin.getConfig().getStringList("admin.openids");
    }

    public boolean fullForwardingByDefault() {
        return this.plugin.getConfig().getBoolean("features.full-amount", false);
    }

    public Map<String, Boolean> commandSwitches() {
        LinkedHashMap<String, Boolean> linkedHashMap = new LinkedHashMap<String, Boolean>();
        ConfigurationSection configurationSection = this.plugin.getConfig().getConfigurationSection("commands");
        if (configurationSection == null) {
            return linkedHashMap;
        }
        for (String string : configurationSection.getKeys(false)) {
            Object object = configurationSection.get(string);
            Boolean bl = object instanceof Boolean ? (Boolean)object : true;
            linkedHashMap.put(string, bl);
        }
        return linkedHashMap;
    }

    public String auditBaseUrl() {
        return null;
    }

    public String auditApiKey() {
        return null;
    }

    public String auditModel() {
        return "gpt-4o-mini";
    }

    public List<CustomCommandDetail> customCommands() {
        ArrayList<CustomCommandDetail> arrayList = new ArrayList<CustomCommandDetail>();
        @SuppressWarnings("unchecked") List<Map<?, ?>> list = (List<Map<?, ?>>) (List) this.plugin.getConfig().getMapList("custom-commands");
        if (list == null) {
            return arrayList;
        }
        for (Map<?, ?> map : list) {
            if (map == null) continue;
            String string = ConfigManager.stringOf(map.get("key"));
            String string2 = ConfigManager.stringOf(map.get("command"));
            Object v = map.get("permission");
            int n = 0;
            if (v != null) {
                try {
                    n = Integer.parseInt(v.toString());
                }
                catch (NumberFormatException numberFormatException) {
                    // empty catch block
                }
            }
            if (string == null || string.isEmpty() || string2 == null || string2.isEmpty()) continue;
            arrayList.add(new CustomCommandDetail(string, string2, n));
        }
        return arrayList;
    }

    private static String stringOf(Object object) {
        return object == null ? null : object.toString();
    }

    public boolean agentEnabled() {
        return false;
    }

    public String agentBaseUrl() {
        return null;
    }

    public String agentApiKey() {
        return null;
    }

    public String agentModel() {
        return "gpt-4o-mini";
    }

    public AgentCommandMode agentCommandMode() {
        return AgentCommandMode.MANUAL;
    }

    static {
        LinkedHashMap<Object, Object> linkedHashMap = new LinkedHashMap<Object, Object>();
        linkedHashMap.put(CONFIG_VERSION_PATH, 8);
        linkedHashMap.put("bot.app-id", "");
        linkedHashMap.put("bot.secret", "");
        linkedHashMap.put("bot.name", "KERONGPenguin");
        linkedHashMap.put("bot.groups", Collections.emptyList());
        linkedHashMap.put("bot.suppress-console-output", true);
        linkedHashMap.put("serverName", "KERONGPenguin");
        linkedHashMap.put("chat-format.from-game", "[\u6e38\u620f] {message}");
        linkedHashMap.put("chat-format.from-group", "[QQ] {name}: {message}");
        linkedHashMap.put("chat-format.post-chat", true);
        linkedHashMap.put("chat-format.start-with", "#");
        linkedHashMap.put("player-events.join.enabled", true);
        linkedHashMap.put("player-events.join.format", "[\u6e38\u620f] {name} \u52a0\u5165\u4e86\u670d\u52a1\u5668");
        linkedHashMap.put("player-events.quit.enabled", true);
        linkedHashMap.put("player-events.quit.format", "[\u6e38\u620f] {name} \u79bb\u5f00\u4e86\u670d\u52a1\u5668");
        linkedHashMap.put("markdown.queryOnline", "online.md");
        linkedHashMap.put("command-sender", "Hybrid");
        linkedHashMap.put("motd.server-ip", "127.0.0.1");
        linkedHashMap.put("motd.server-port", 25565);
        linkedHashMap.put("motd.api", "http://motd.txssb.cn/api/app_img?ip={ip}&port={port}&dark=true&lang=zh-CN");
        linkedHashMap.put("motd.text", "");
        linkedHashMap.put("motd.post-img", false);
        linkedHashMap.put("motd.use-markdown", false);
        linkedHashMap.put("death-notify.enabled", false);
        linkedHashMap.put("death-notify.prefix", "[\u6b7b\u4ea1\u901a\u62a5]");
        linkedHashMap.put("filter-regex", Collections.emptyList());
        linkedHashMap.put("admin.mode", "qq");
        linkedHashMap.put("admin.openids", Collections.emptyList());
        linkedHashMap.put("features.full-amount", false);
        linkedHashMap.put("custom-commands", Collections.emptyList());
        linkedHashMap.put("qq-bind.enabled", false);
        linkedHashMap.put("qq-bind.code-expire-minutes", 10);
        linkedHashMap.put("qq-bind.code-length", 5);
        linkedHashMap.put("ai.enabled", false);
        linkedHashMap.put("ai.server-enabled", false);
        linkedHashMap.put("ai.server-prefix", "ai");
        linkedHashMap.put("ai.server-output-prefix", "[AI]");
        linkedHashMap.put("ai.qq-output-prefix", "[AI]");
        linkedHashMap.put("ai.base-url", "https://api.deepseek.com");
        linkedHashMap.put("ai.api-key", "");
        linkedHashMap.put("ai.model", "deepseek-chat");
        linkedHashMap.put("ai.system-prompt", "\u4f60\u662f\u4e00\u4e2a\u53cb\u597d\u7684\u6e38\u620f\u7fa4\u52a9\u624b\uff0c\u8bf7\u7b80\u6d01\u56de\u7b54\u3002");
        linkedHashMap.put("ai.url-auto-append", true);
        linkedHashMap.put("ai.context-limit", 6);
        linkedHashMap.put("ai.context-global", false);
        for (String string : COMMAND_NAMES) {
            linkedHashMap.put("commands." + string, true);
        }
        DEFAULT_VALUES = (Map<String, Object>) (Map) Collections.unmodifiableMap((Map) linkedHashMap);
    }
}

