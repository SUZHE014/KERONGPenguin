package cn.huohuas001.huhobotPenguin.spigot.manager

import cn.huohuas001.bot.provider.AdminMode
import cn.huohuas001.bot.provider.ChatFormat
import cn.huohuas001.bot.provider.ConfigUpgrader
import cn.huohuas001.bot.provider.CustomCommandDetail
import cn.huohuas001.bot.provider.Motd
import cn.huohuas001.bot.provider.PlayerEventFormat
import cn.huohuas001.bot.provider.WhiteList
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import java.io.File
import java.util.LinkedHashMap

/**
 * 配置管理器：加载 config.yml、补齐缺失键、提供类型安全的配置访问。
 */
class ConfigManager(private val plugin: HuHoBotSpigot) {

    /** 配置文件路径。 */
    fun getConfigFile(): File = File(plugin.dataFolder, "config.yml")

    /** 初始化：释放默认配置并装载。 */
    fun initialize() {
        plugin.saveDefaultConfig()
        reload()
    }

    /** 重新加载配置（含格式错误保护与缺失键补齐）。 */
    fun reload() {
        try {
            plugin.reloadConfig()
        } catch (t: Throwable) {
            plugin.logger.warning("配置文件格式错误，跳过重载以避免数据丢失: ${t.message}")
            return
        }
        val config: FileConfiguration = plugin.config
        if (config == null || config.getKeys(false).isEmpty()) {
            plugin.logger.warning("配置文件为空或格式错误，跳过保存以避免数据丢失")
            return
        }

        var changed = false
        // 补齐缺失键（isSet 不穿透 JAR 内置默认值，确保新键真正写入用户配置文件，
        // 否则 contains 会因 defaults 回退恒为 true，导致新键对用户不可见）
        changed = ConfigUpgrader.fillMissing(DEFAULT_VALUES, { config.isSet(it) }) { path, value ->
            config.set(path, value)
        } || changed

        var oldVersion = config.getInt(CONFIG_VERSION_PATH, 0)
        if (oldVersion != CURRENT_CONFIG_VERSION) {
            config.set(CONFIG_VERSION_PATH, CURRENT_CONFIG_VERSION)
            changed = true
        }
        // 清理已废弃的配置段
        for (removed in listOf("agent", "audit", "whitelist")) {
            if (config.contains(removed)) {
                config.set(removed, null)
                changed = true
            }
        }
        if (changed) {
            plugin.saveConfig()
            plugin.logger.info("配置文件已升级到版本 $CURRENT_CONFIG_VERSION（旧版本：$oldVersion）")
        }
    }

    fun botAppId(): String = plugin.config.getString("bot.app-id") ?: ""

    fun botSecret(): String = plugin.config.getString("bot.secret") ?: ""

    fun botName(): String = plugin.config.getString("bot.name", "KERONGPenguin") ?: "KERONGPenguin"

    fun serverName(): String = plugin.config.getString("serverName", botName()) ?: botName()

    fun groupOpenIds(): List<String> = plugin.config.getStringList("bot.groups")

    /**
     * 命令执行器配置。
     * 可取值：Auto（自动检测，推荐）/ Hybrid（混合控制台）/ 其他（模拟控制台）。
     */
    fun commandSender(): String = plugin.config.getString("command-sender", "Auto") ?: "Auto"

    fun chatFormat(): ChatFormat = ChatFormat(
        plugin.config.getString("chat-format.from-game", "[游戏] {message}") ?: "[游戏] {message}",
        plugin.config.getString("chat-format.from-group", "[QQ] {name}: {message}") ?: "[QQ] {name}: {message}",
        plugin.config.getBoolean("chat-format.post-chat", true),
        plugin.config.getString("chat-format.start-with", "") ?: "",
    )

    fun playerEventFormat(): PlayerEventFormat = PlayerEventFormat(
        plugin.config.getBoolean("player-events.join.enabled", true),
        plugin.config.getString("player-events.join.format", "[游戏] {name} 加入了服务器") ?: "[游戏] {name} 加入了服务器",
        plugin.config.getBoolean("player-events.quit.enabled", true),
        plugin.config.getString("player-events.quit.format", "[游戏] {name} 离开了服务器") ?: "[游戏] {name} 离开了服务器",
    )

    fun markdownFiles(): Map<String, String> {
        val files = LinkedHashMap<String, String>()
        val section: ConfigurationSection = plugin.config.getConfigurationSection("markdown") ?: return files
        for (key in section.getKeys(false)) {
            val value = section.getString(key) ?: continue
            files[key] = value
        }
        if (!files.containsKey("queryOnline")) {
            files["queryOnline"] = "online.md"
        }
        return files
    }

    fun whiteList(): WhiteList = WhiteList("whitelist add {name}", "whitelist remove {name}")

    fun motd(): Motd = Motd(
        plugin.config.getString("motd.server-ip", "") ?: "",
        plugin.config.getInt("motd.server-port", 0),
        plugin.config.getString(
            "motd.api",
            "http://motd.txssb.cn/api/app_img?ip={ip}&port={port}&dark=true&lang=zh-CN",
        ) ?: "",
        plugin.config.getString("motd.text", "") ?: "",
        plugin.config.getBoolean("motd.post-img", true),
        plugin.config.getBoolean("motd.use-markdown", true),
    )

    fun filterRegexList(): List<String> = plugin.config.getStringList("filter-regex")

    fun adminMode(): AdminMode = AdminMode.from(plugin.config.getString("admin.mode", "qq")) ?: AdminMode.QQ

    fun adminOpenIds(): List<String> = plugin.config.getStringList("admin.openids")

    fun fullForwardingByDefault(): Boolean = plugin.config.getBoolean("features.full-amount", false)

    /** 命令开关表。 */
    fun commandSwitches(): Map<String, Boolean> {
        val switches = LinkedHashMap<String, Boolean>()
        val section: ConfigurationSection = plugin.config.getConfigurationSection("commands") ?: return switches
        for (key in section.getKeys(false)) {
            val value = section.get(key)
            switches[key] = if (value is Boolean) value else true
        }
        return switches
    }

    fun auditBaseUrl(): String? = null

    fun auditApiKey(): String? = null

    fun auditModel(): String = "gpt-4o-mini"

    /** 自定义命令列表。 */
    fun customCommands(): List<CustomCommandDetail> {
        val commands = ArrayList<CustomCommandDetail>()
        val rawList: List<Map<*, *>> = plugin.config.getMapList("custom-commands")
        for (map in rawList) {
            if (map == null) continue
            val key = map["key"]?.toString()
            val command = map["command"]?.toString()
            val permission = map["permission"]?.toString()?.toIntOrNull() ?: 0
            if (key.isNullOrEmpty() || command.isNullOrEmpty()) continue
            commands.add(CustomCommandDetail(key, command, permission))
        }
        return commands
    }

    companion object {
        private const val CONFIG_VERSION_PATH = "config-version"
        private const val CURRENT_CONFIG_VERSION = 11

        /** 支持开关的群命令名单。 */
        private val COMMAND_NAMES = listOf(
            "查信息", "绑定", "重新绑定", "查在线", "在线服务器", "发信息",
            "执行命令", "执行", "管理员执行", "全量", "motd", "帮助",
            "AI对话上下文", "清除当前上下文", "黑名单", "解除黑名单", "签到",
        )

        /** 全部默认配置值（用于补齐缺失键）。 */
        private val DEFAULT_VALUES: Map<String, Any> = linkedMapOf(
            CONFIG_VERSION_PATH to 8,
            "bot.app-id" to "",
            "bot.secret" to "",
            "bot.name" to "KERONGPenguin",
            "bot.groups" to emptyList<Any>(),
            "serverName" to "KERONGPenguin",
            "chat-format.from-game" to "[游戏] {message}",
            "chat-format.from-group" to "[QQ] {name}: {message}",
            "chat-format.post-chat" to true,
            "chat-format.start-with" to "#",
            "player-events.join.enabled" to true,
            "player-events.join.format" to "[游戏] {name} 加入了服务器",
            "player-events.quit.enabled" to true,
            "player-events.quit.format" to "[游戏] {name} 离开了服务器",
            "markdown.queryOnline" to "online.md",
            // 默认 Auto：自动检测混合端，纯 Spigot/Paper 不再误提示“已启用混合控制台”
            "command-sender" to "Auto",
            "motd.server-ip" to "",
            "motd.server-port" to 0,
            "motd.api" to "http://motd.txssb.cn/api/app_img?ip={ip}&port={port}&dark=true&lang=zh-CN",
            "motd.text" to "",
            "motd.post-img" to true,
            "motd.use-markdown" to true,
            "filter-regex" to emptyList<Any>(),
            "admin.mode" to "qq",
            "admin.openids" to emptyList<Any>(),
            "features.full-amount" to false,
            "custom-commands" to emptyList<Any>(),
            "qq-bind.enabled" to false,
            "qq-bind.personal-info" to true,
            "qq-bind.code-expire-minutes" to 10,
            "qq-bind.code-length" to 5,
            "qq-bind.checkin.enabled" to false,
            "qq-bind.checkin.prefix" to "[签到]",
            "qq-bind.checkin.reward" to 100.0,
            "ai.enabled" to false,
            "ai.server-enabled" to false,
            "ai.server-prefix" to "ai",
            "ai.server-output-prefix" to "[AI]",
            "ai.qq-output-prefix" to "[AI]",
            "ai.base-url" to "https://api.deepseek.com",
            "ai.api-key" to "",
            "ai.model" to "deepseek-chat",
            "ai.system-prompt" to "你是一个友好的游戏群助手，请简洁回答。",
            "ai.url-auto-append" to true,
            "ai.context-limit" to 6,
            "ai.context-global" to false,
        ).apply {
            for (name in COMMAND_NAMES) {
                put("commands.$name", true)
            }
        }
    }
}
