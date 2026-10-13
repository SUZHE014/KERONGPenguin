package cn.huohuas001.huhobotPenguin.spigot.manager

import cn.huohuas001.bot.provider.AdminMode
import cn.huohuas001.bot.provider.ChatFormat
import cn.huohuas001.bot.provider.ConfigUpgrader
import cn.huohuas001.bot.provider.CustomCommandDetail
import cn.huohuas001.bot.provider.Motd
import cn.huohuas001.bot.provider.PlayerEventFormat
import cn.huohuas001.bot.provider.WhiteList
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.LinkedHashMap

/**
 * 配置管理器：加载 config.yml、补齐缺失键、提供类型安全的配置访问。
 *
 * 1.6.0 配置结构修复：查在线与在线排行榜不依赖 QQ 绑定，相关配置从
 * qq-bind 段移出——渲染设置迁至顶层 render 段（含新增性能键
 * command-cooldown-seconds）、排行榜迁至顶层 leaderboard 段、新增金币排行榜
 * coins-leaderboard 段与经济别名 economy-alias、新增消息模板 messages 段
 * （每条机器人回复对应一个 .md 模板文件）。旧值经版本迁移 16→17 自动搬家。
 */
class ConfigManager(private val plugin: JavaPlugin) {

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
        // 1.6.2：命令更名 /金币榜 → /财富榜（commands 段开关同步迁移保留用户取值；
        // 旧命令名作为隐藏别名继续可用，与主命令共用开关）
        if (oldVersion in 1 until 18 && config.isSet("commands.金币榜")) {
            config.set("commands.财富榜", config.get("commands.金币榜"))
            config.set("commands.金币榜", null)
            plugin.logger.info("命令更名：/金币榜 → /财富榜（原 commands.金币榜 开关取值已迁移）")
            changed = true
        }
        // 1.5.6：排行榜每张图片展示玩家数 20 → 15（旧默认值 20 属 1.5.5 自动补齐，
        // 非用户手动意图，升级时同步改为新默认；用户自定义的其他值（如 30）不动）
        if (oldVersion in 1 until 15 && config.getInt("qq-bind.leaderboard.top", 15) == 20) {
            config.set("qq-bind.leaderboard.top", 15)
            plugin.logger.info("在线排行榜每张图片展示玩家数：20 → 15（新默认，可在 leaderboard.top 改回）")
            changed = true
        }
        // 1.5.7：random-background（默认 false = 内置图）反向更名 builtin-background
        //（默认 true = 内置图）—— 旧配置手动开启过随机的用户升级后保持随机效果
        if (oldVersion in 1 until 16 && config.isSet("qq-bind.leaderboard.random-background") &&
            config.getBoolean("qq-bind.leaderboard.random-background", false)
        ) {
            config.set("qq-bind.leaderboard.builtin-background", false)
            plugin.logger.info("在线排行榜背景：已按旧配置随机使用 img/ 目录图片（leaderboard.builtin-background=false）")
            changed = true
        }
        // 1.6.0：渲染与排行榜配置移出 qq-bind 段（查在线 / 排行榜不依赖 QQ 绑定）：
        // 路径平移 + 保留用户已改值 + 清掉迁空的旧子段
        if (oldVersion in 1 until 17) {
            val moves = listOf(
                "qq-bind.render.gc-after-render" to "render.gc-after-render",
                "qq-bind.render.result-cache-seconds" to "render.result-cache-seconds",
                "qq-bind.render.info-card-builtin-background" to "render.info-card-builtin-background",
                "qq-bind.render.online-list-builtin-background" to "render.online-list-builtin-background",
                "qq-bind.leaderboard.top" to "leaderboard.top",
                "qq-bind.leaderboard.builtin-background" to "leaderboard.builtin-background",
            )
            var movedAny = false
            for ((old, new) in moves) {
                if (config.isSet(old)) {
                    config.set(new, config.get(old))
                    config.set(old, null)
                    movedAny = true
                }
            }
            if (movedAny) {
                // 移空后的旧子段一并清掉（两个子段的全部子键都已迁走）
                config.set("qq-bind.render", null)
                config.set("qq-bind.leaderboard", null)
                plugin.logger.info("配置结构升级：渲染与排行榜设置已从 qq-bind 段移至顶层 render / leaderboard 段（原有取值全部保留）")
                changed = true
            }
        }
        // 清理已废弃的配置段
        // 1.5.1：移除 query-online 段（图片输出改为 markdown 关闭时自动启用）与 motd.text（文本模板已删除）
        // 1.5.2：移除 commands.个人信息（该命令已更名为 查信息）
        // 1.5.7：移除 qq-bind.leaderboard.random-background（反向更名为 builtin-background，
        // 手动开启过随机的用户已在上方迁移逻辑中保留意图）
        // 1.5.7.1：移除 ai.context-global（旧默认值误写进用户配置，实际无任何功能——
        // AI 上下文自 1.5.3 起即为全局一份记忆，不存在按群/按人切换）
        for (removed in listOf(
            "agent", "audit", "whitelist", "query-online", "motd.text", "commands.个人信息",
            "qq-bind.leaderboard.random-background", "ai.context-global",
        )) {
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
        // 1.5.7.4：命令别名共享主命令开关（别名自身不注册进 commands 段，
        // 关闭主命令时别名一并失效，快捷菜单也不展示别名按钮）
        for ((alias, main) in COMMAND_ALIASES) {
            if (!switches.containsKey(alias)) {
                switches[alias] = switches[main] ?: true
            }
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
        private const val CURRENT_CONFIG_VERSION = 18

        /**
         * 支持开关的群命令名单（1.5.2：查信息更名对应个人信息卡片命令，新增 查询open ID；
         * 1.6.0 新增金币榜；1.6.2 更名财富榜）。
         */
        private val COMMAND_NAMES = listOf(
            "查询open ID", "查信息", "绑定", "重新绑定", "查在线", "在线排行榜", "财富榜", "在线服务器", "发信息",
            "执行命令", "执行", "管理员执行", "全量", "motd", "帮助",
            "AI对话上下文", "清除当前上下文", "黑名单", "解除黑名单", "签到",
        )

        /**
         * 命令别名表（别名 → 主命令）。
         * 别名与主命令共用 commands 段里的开关；别名不出现在快捷指令菜单与
         * commands 配置段（dispatch 层按别名命中后仍查主命令开关值）。
         * 1.5.7.4：「在线」为 /查在线 的输入别名；
         * 1.6.2：「金币榜」为 /财富榜 的旧名别名（命令更名后旧用户无感升级）。
         */
        private val COMMAND_ALIASES = linkedMapOf(
            "在线" to "查在线",
            "金币榜" to "财富榜",
        )

        /**
         * 全部消息模板 ID（1.6.0）。默认文件名 = <ID>.md（位于数据目录 Markdown/ 下，
         * 首次启动自动释放默认模板；messages.<ID> 可改为自定义文件名）。
         * 与 MessageTemplates.TEMPLATE_IDS 保持一致。
         */
        private val MESSAGE_TEMPLATE_IDS = listOf(
            // 通用
            "command-disabled", "no-admin-permission", "query-too-frequent", "server-busy",
            "image-send-failed", "manager-not-ready", "manager-not-ready-bind",
            "manager-not-ready-retry", "manager-not-ready-bind-retry", "qq-unknown", "server-status",
            // QQ 绑定
            "bind-usage", "bind-usage-fallback", "bind-disabled", "bind-error", "bind-success", "bind-invalid",
            // 签到
            "checkin-disabled", "checkin-not-bound", "checkin-data-error", "checkin-time-error",
            "checkin-already", "checkin-success-no-economy", "checkin-reward-invalid",
            "checkin-success", "checkin-pay-error",
            // 查信息
            "info-bind-disabled", "info-closed", "info-not-bound", "info-target-not-bound", "info-render-failed",
            // 查在线
            "online-data-unavailable", "online-render-failed",
            // 在线排行榜
            "leaderboard-no-data", "leaderboard-render-failed",
            // 金币排行榜
            "coins-leaderboard-no-economy", "coins-leaderboard-no-data", "coins-leaderboard-render-failed",
            // 查询 OpenID
            "openid-self", "openid-lookup",
            // 发信息
            "forward-disabled",
            // AI 对话
            "ai-context-usage", "ai-context-on", "ai-context-off", "ai-context-cleared",
            "ai-chat-empty", "ai-chat-error",
            // 黑名单
            "blacklist-empty", "blacklist-list", "blacklist-added-kicked", "blacklist-added",
            "blacklist-removed", "unblacklist-empty", "unblacklist-list",
            // 重新绑定
            "rebind-success", "rebind-not-bound",
            // 帮助
            "help",
            // 命令执行
            "invalid-params", "bridge-error", "sent-request", "execute-failed", "full-amount-toggled",
            // MOTD 查询
            "motd-usage", "motd-query-failed", "motd-offline", "motd-error", "motd-status",
        )

        /** 全部默认配置值（用于补齐缺失键）。 */
        private val DEFAULT_VALUES: Map<String, Any> = linkedMapOf(
            CONFIG_VERSION_PATH to 8,
            "bot.app-id" to "",
            "bot.secret" to "",
            "bot.name" to "KERONGPenguin",
            "bot.groups" to emptyList<Any>(),
            // 1.6.2：断线重连过程提示开关（false = 关闭「第 N 次（1/5）重试连接…」防刷屏；
            // 重连超时后的 /hb chs 恢复指引不受此开关影响，始终输出）
            "bot.reconnect-notify" to true,
            // 1.6.2：更新检查开关（启动后异步访问官网版本文件，发现新版本时控制台提示
            // 版本号 / 下载页 / 简要日志；检测失败仅一句话提示，细节进日志）
            "update-check.enabled" to true,
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
            // 1.5.1：query-online.image-output 已移除（markdown 关闭时自动启用图片输出）
            // 默认 Auto：自动检测混合端，纯 Spigot/Paper 不再误提示“已启用混合控制台”
            "command-sender" to "Auto",
            "motd.server-ip" to "",
            "motd.server-port" to 0,
            "motd.api" to "http://motd.txssb.cn/api/app_img?ip={ip}&port={port}&dark=true&lang=zh-CN",
            // 1.5.1：motd.text（文本模板）已移除
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
            // 1.6.0：渲染设置迁至顶层 render 段（查在线 / 卡片渲染不依赖 QQ 绑定）
            "render.gc-after-render" to true,
            "render.result-cache-seconds" to 60,
            // 1.6.0 新增：图片渲染命令的用户冷却秒数（旧版硬编码 5 秒；0 = 关闭）
            "render.command-cooldown-seconds" to 5,
            // 1.5.7：全部图片渲染的内置背景开关（默认 true = 随 JAR 分发的深灰底图，
            // 关闭后随机用 img/ 目录图片，img 无可用图片时自动回退内置图）
            "render.info-card-builtin-background" to true,
            "render.online-list-builtin-background" to true,
            // 1.5.5：在线排行榜（1.6.0 从 qq-bind.leaderboard 迁至顶层）
            "leaderboard.top" to 15,
            "leaderboard.builtin-background" to true,
            // 1.6.0：金币排行榜（配置与在线排行榜一致）
            "coins-leaderboard.top" to 15,
            "coins-leaderboard.builtin-background" to true,
            // 1.6.0：经济别名（留空 = 默认「金币」；填写后所有输出的金币字样替换为该名称）
            "economy-alias" to "",
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
        ).apply {
            // 1.6.0：消息模板映射（每条回复 → Markdown 模板文件名，详见 config.yml 注释）
            for (id in MESSAGE_TEMPLATE_IDS) {
                put("messages.$id", "$id.md")
            }
            for (name in COMMAND_NAMES) {
                put("commands.$name", true)
            }
        }
    }
}
