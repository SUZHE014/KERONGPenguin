package cn.huohuas001.huhobotPenguin.spigot

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.provider.AdminMode
import cn.huohuas001.bot.provider.ChatFormat
import cn.huohuas001.bot.provider.CustomCommandDetail
import cn.huohuas001.bot.provider.HExecution
import cn.huohuas001.bot.provider.Motd
import cn.huohuas001.bot.provider.PlayerEventFormat
import cn.huohuas001.bot.provider.WhiteList
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobotPenguin.spigot.commands.BukkitConsoleSender
import cn.huohuas001.huhobotPenguin.spigot.commands.CommandOutputAppender
import cn.huohuas001.huhobotPenguin.spigot.commands.HuHoBotCommand
import cn.huohuas001.huhobotPenguin.spigot.commands.HuHoBotTask
import cn.huohuas001.huhobotPenguin.spigot.commands.HybridCommandExecutor
import cn.huohuas001.huhobotPenguin.spigot.events.GameChat
import cn.huohuas001.huhobotPenguin.spigot.events.PlayerEventsListener
import cn.huohuas001.huhobotPenguin.spigot.manager.ConfigManager
import cn.huohuas001.huhobotPenguin.spigot.stats.PlayerStatsManager
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginCommand
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.Locale

/**
 * KERONG Penguin 插件主类（Spigot 平台）。
 *
 * 生命周期：
 * 1. onEnable：初始化配置 → 注册命令与事件 → 启动 QQ 机器人运行时；
 * 2. onDisable：停止 QQ 机器人、卸载日志捕获器、保存统计数据。
 */
class HuHoBotSpigot : JavaPlugin(), HuHoBot {
    private lateinit var configManager: ConfigManager

    // ---------- 生命周期 ----------

    override fun onEnable() {
        configManager = ConfigManager(this)
        configManager.initialize()

        // 自动创建卡片背景图目录
        File(dataFolder, "img").mkdirs()

        // 启动 QQ 机器人运行时（注册共享实例 → 装载状态 → 异步启动 QQ 客户端）
        initializeRuntime()

        // 初始化命令
        val command = HuHoBotCommand(this)
        getCommand("huhobot")?.let { pluginCommand ->
            pluginCommand.setExecutor(command)
            pluginCommand.setTabCompleter(command)
        } ?: log_error("无法注册 /huhobot 命令，请检查 plugin.yml")

        // 注册游戏事件监听（GameChat 构造时会初始化 QqBindManager，需在共享实例注册后）
        server.pluginManager.registerEvents(GameChat(), this)
        server.pluginManager.registerEvents(PlayerEventsListener(), this)

        // 注册统计记录器（/查信息 数据源）
        PlayerStatsManager.initialize()

        logCommandExecutor()
        log_info("HuHoBot Penguin 已加载")
    }

    override fun onDisable() {
        // 保存统计数据
        try {
            PlayerStatsManager.shutdown()
        } catch (_: Throwable) {
        }
        shutdownRuntime()
        CommandOutputAppender.Companion.removeInstance()
    }

    override fun reloadPluginConfig() {
        configManager.reload()
        reloadRuntimeConfig()
        logCommandExecutor()
    }

    // ---------- 命令执行器选择（含混合端自动检测） ----------

    /**
     * 日志提示当前使用的命令执行器。
     *
     * 修复记录（0.1.5.0）：
     * 旧版本默认 command-sender=Hybrid，导致纯 Spigot/Paper 服务器
     * 也提示“已启用混合控制台命令执行器”。现在：
     * - Auto（默认）：检测到混合端才使用混合执行器；
     * - Hybrid：明确指定混合端；若检测到当前并非混合端，自动回退模拟控制台并给出提示；
     * - 其他值：模拟控制台。
     */
    private fun logCommandExecutor() {
        when (commandSenderType) {
            CommandSenderType.HYBRID -> log_info("已启用混合控制台命令执行器（检测到混合端服务端）")
            CommandSenderType.HYBRID_FALLBACK -> log_warning("配置指定了 Hybrid，但当前服务器不是混合端，已自动使用模拟控制台命令执行器")
            CommandSenderType.BUKKIT -> log_info("已启用模拟控制台命令执行器")
        }
    }

    /** 解析后的命令执行器类型。 */
    private val commandSenderType: CommandSenderType
        get() {
            val configured = configManager.commandSender()
            return when (configured.lowercase(Locale.ROOT)) {
                "auto" -> if (isHybridServer) CommandSenderType.HYBRID else CommandSenderType.BUKKIT
                "hybrid" -> if (isHybridServer) CommandSenderType.HYBRID else CommandSenderType.HYBRID_FALLBACK
                else -> CommandSenderType.BUKKIT
            }
        }

    /** 创建当前配置对应的命令执行器。 */
    override fun createCommandExecutor(): HExecution = when (commandSenderType) {
        CommandSenderType.HYBRID -> HybridCommandExecutor(this)
        CommandSenderType.HYBRID_FALLBACK, CommandSenderType.BUKKIT -> BukkitConsoleSender(this)
    }

    /** 命令执行器类型。 */
    private enum class CommandSenderType {
        HYBRID,
        HYBRID_FALLBACK,
        BUKKIT,
    }

    /**
     * 混合端服务端检测（Mohist / CatServer / Arclight / Uranium 等 Forge+Bukkit 服务端）。
     * 依次检查：服务端名称标识 → Forge/Fabric 内部类是否存在。
     */
    private val isHybridServer: Boolean by lazy {
        val server = Bukkit.getServer()
        val identity = (server.name + " " + server.version).lowercase(Locale.ROOT)
        val nameMarkers = listOf(
            "mohist", "catserver", "arclight", "uranium", "lumina",
            "banner", "hybrid", "crucible", "flexremap", "sponge",
        )
        if (nameMarkers.any { identity.contains(it) }) {
            true
        } else {
            // 混合端必然携带 Forge/Fabric 引导类
            val loaderClasses = listOf(
                "net.minecraftforge.fml.loading.FMLLoader",
                "net.minecraftforge.fml.common.Loader",
                "net.fabricmc.loader.impl.FabricLoaderImpl",
            )
            loaderClasses.any { className ->
                try {
                    Class.forName(className, false, javaClass.classLoader) != null
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }

    // ---------- 调度 ----------

    override fun broadcastMessage(msg: String) {
        server.scheduler.runTask(this, Runnable { Bukkit.broadcastMessage(msg) })
    }

    override fun submit(task: Runnable): Cancelable {
        val bukkitTask: BukkitTask = server.scheduler.runTask(this, task as Runnable)
        return HuHoBotTask(bukkitTask)
    }

    override fun submitAsync(task: Runnable): Cancelable {
        val bukkitTask: BukkitTask = server.scheduler.runTaskAsynchronously(this, task)
        return HuHoBotTask(bukkitTask)
    }

    override fun submitLater(delay: Long, task: Runnable): Cancelable {
        val bukkitTask: BukkitTask = server.scheduler.runTaskLater(this, task, delay)
        return HuHoBotTask(bukkitTask)
    }

    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable {
        val bukkitTask: BukkitTask = server.scheduler.runTaskTimer(this, task, delay, period)
        return HuHoBotTask(bukkitTask)
    }

    // ---------- 平台信息 ----------

    override val onlineList: List<String>
        get() = server.onlinePlayers.map { it.name }

    override val configFile: File
        get() = configManager.getConfigFile()

    override val botAppId: String
        get() = configManager.botAppId()

    override val botSecret: String
        get() = configManager.botSecret()

    override val chatFormat: ChatFormat
        get() = configManager.chatFormat()

    override fun playerEventFormat(): PlayerEventFormat = configManager.playerEventFormat()

    override fun markdownFiles(): Map<String, String> = configManager.markdownFiles()

    override val motd: Motd
        get() = configManager.motd()

    override fun whiteList(): WhiteList = configManager.whiteList()

    override fun filterRegexList(): List<String> = configManager.filterRegexList()

    override fun adminMode(): AdminMode = configManager.adminMode()

    override fun adminList(): List<String> = configManager.adminOpenIds()

    override fun groupOpenIdList(): List<String> = configManager.groupOpenIds()

    override fun shouldSuppressQqBotConsoleOutput(): Boolean = configManager.suppressQqBotConsoleOutput()

    override val fullAmount: Boolean
        get() = configManager.fullForwardingByDefault()

    override fun commandList(): Map<String, Boolean> = configManager.commandSwitches()

    override fun auditBaseUrl(): String? = configManager.auditBaseUrl()

    override fun auditApiKey(): String? = configManager.auditApiKey()

    override fun auditModel(): String = configManager.auditModel()

    override fun sensitiveWords(): List<String> = super.sensitiveWords()

    override fun customCommands(): List<CustomCommandDetail> = configManager.customCommands()

    override val botName: String
        get() = configManager.botName()

    override val serverName: String
        get() = configManager.serverName()

    override val platform: String
        get() = "spigot"

    override val pluginVersion: String
        get() = description.version

    override val serverVersion: String
        get() = server.version

    // ---------- Web 面板配置 ----------

    override fun webUiConfigValues(): Map<String, Any> = config.getValues(true)

    override fun applyWebUiConfigChanges(changes: JSONObject): Boolean = try {
        for ((path, value) in changes) {
            config.set(path, convertJsonValue(value))
        }
        saveConfig()
        reloadPluginConfig()
        true
    } catch (error: Exception) {
        log_error("WebUI 保存配置失败: ${error.message}")
        false
    }

    /** fastjson 值转 Bukkit 配置值（递归转换 Map/List）。 */
    private fun convertJsonValue(value: Any?): Any? = when (value) {
        is JSONObject -> value.entries.associate { (k, v) -> k to convertJsonValue(v) }
        is JSONArray -> value.map { convertJsonValue(it) }
        else -> value
    }

    // ---------- 日志 ----------

    override fun log_info(msg: String) {
        logger.info(msg)
    }

    override fun log_warning(msg: String) {
        logger.warning(msg)
    }

    override fun log_error(msg: String) {
        logger.severe(msg)
    }
}
