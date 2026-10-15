package cn.huohuas001.huhobotPenguin.spigot

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.provider.AdminMode
import cn.huohuas001.bot.provider.ChatFormat
import cn.huohuas001.bot.provider.CustomCommandDetail
import cn.huohuas001.bot.provider.HExecution
import cn.huohuas001.bot.provider.Motd
import cn.huohuas001.bot.provider.PlayerEventFormat
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.bot.tools.PluginFileLog
import cn.huohuas001.huhobotPenguin.spigot.commands.BukkitConsoleSender
import cn.huohuas001.huhobotPenguin.spigot.commands.CommandOutputAppender
import cn.huohuas001.huhobotPenguin.spigot.commands.HuHoBotCommand
import cn.huohuas001.huhobotPenguin.spigot.commands.HybridCommandExecutor
import cn.huohuas001.huhobotPenguin.spigot.events.GameChat
import cn.huohuas001.huhobotPenguin.spigot.events.PlayerEventsListener
import cn.huohuas001.huhobotPenguin.spigot.manager.ConfigManager
import cn.huohuas001.huhobotPenguin.spigot.render.InfoCardAssets
import cn.huohuas001.huhobotPenguin.spigot.scheduler.FoliaCompat
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

        // 初始化命令（1.6.5：主命令 /huhobot 更名 /kerong，旧名与 /hb 保留为别名）
        val command = HuHoBotCommand(this)
        getCommand("kerong")?.let { pluginCommand ->
            pluginCommand.setExecutor(command)
            pluginCommand.setTabCompleter(command)
        } ?: log_error("无法注册 /kerong 命令，请检查 plugin.yml")

        // 注册游戏事件监听（GameChat 构造时会初始化 QqBindManager，需在共享实例注册后）
        server.pluginManager.registerEvents(GameChat(), this)
        server.pluginManager.registerEvents(PlayerEventsListener(), this)

        // 注册统计记录器（/查信息 数据源）
        PlayerStatsManager.initialize()

        // 1.6.4：类预热（停机 zip closed 修复）
        // Paper/Purpur 停机阶段会提前关闭插件 jar 的 ZipFile 句柄，此时若
        // onDisable 里首次触发某个类的加载（旧版 CommandOutputAppender 的
        // 伴生对象在从未执行过命令输出捕获时不会被加载），
        // PluginClassLoader.findClass 将抛「zip file closed → Error occurred
        // while disabling」。启用阶段强制加载一次并挂载命令输出 Appender，
        // onDisable 的 removeInstance 不再触发任何类加载。
        try {
            CommandOutputAppender.getInstance()
        } catch (_: Throwable) {
        }

        logCommandExecutor()
        // 1.6.5：品牌统一（旧版误印 HuHoBot）
        log_info("KERONG Penguin 已加载")
    }

    override fun onDisable() {
        // 1.6.4：每一步都独立捕获——停机阶段任何一步失败（含历史遗留的
        // 「zip file closed」类加载错误）都不应中断后续清理与服务器关服。
        // 保存统计数据
        try {
            PlayerStatsManager.shutdown()
        } catch (_: Throwable) {
        }
        // 释放卡片图片缓存（头像 / 背景占用的堆内存）
        try {
            InfoCardAssets.clearCaches()
        } catch (_: Throwable) {
        }
        try {
            shutdownRuntime()
        } catch (_: Throwable) {
        }
        try {
            CommandOutputAppender.Companion.removeInstance()
        } catch (_: Throwable) {
        }
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

    // 1.5.7.3：全部调度经 FoliaCompat 桥接（Folia 多线程区域化核心兼容）：
    // 非 Folia 服务器行为与旧版完全一致（主线程 / 异步线程池）；
    // Folia 上分别路由到全局区域调度器 / 异步调度器，
    // 实体状态操作（踢人、统计读取）由调用方走实体调度器变体。

    override fun broadcastMessage(msg: String) {
        FoliaCompat.runSync(this, Runnable { Bukkit.broadcastMessage(msg) })
    }

    override fun submit(task: Runnable): Cancelable {
        return FoliaCompat.runSync(this, task)
    }

    override fun submitAsync(task: Runnable): Cancelable {
        return FoliaCompat.runAsync(this, task)
    }

    override fun submitLater(delay: Long, task: Runnable): Cancelable {
        return FoliaCompat.runSyncLater(this, delay, task)
    }

    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable {
        return FoliaCompat.runTimer(this, delay, period, task)
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

    override fun filterRegexList(): List<String> = configManager.filterRegexList()

    override fun adminMode(): AdminMode = configManager.adminMode()

    override fun adminList(): List<String> = configManager.adminOpenIds()

    override fun groupOpenIdList(): List<String> = configManager.groupOpenIds()

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

    // 0.1.5.5：警告与错误改为“控制台 + 插件日志文件”双写。
    // 此前仅走 Bukkit Logger（只进控制台 / latest.log 路由不稳定的混合端），
    // 渲染失败、发送失败等报错事后无法从文件追溯——即用户反馈的
    // “部分报错没有记录在日志里面”的问题。
    // 1.6.1：info 级同样双写（用户要求插件全部控制台输出都留档）；
    // 数据保存等“只留档不刷屏”的日志仍走 PluginFileLog.write 仅写文件，
    // 互不影响。文件行自动剥离 § 颜色代码（见 PluginFileLog）。
    override fun log_info(msg: String) {
        logger.info(msg)
        PluginFileLog.write(msg)
    }

    override fun log_warning(msg: String) {
        logger.warning(msg)
        PluginFileLog.write("[警告] $msg")
    }

    override fun log_error(msg: String) {
        logger.severe(msg)
        PluginFileLog.write("[错误] $msg")
    }
}
