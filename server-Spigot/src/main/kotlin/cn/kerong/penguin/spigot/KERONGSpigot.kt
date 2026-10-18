package cn.kerong.penguin.spigot

import cn.kerong.bot.KERONG
import cn.kerong.bot.provider.AdminMode
import cn.kerong.bot.provider.ChatFormat
import cn.kerong.bot.provider.CustomCommandDetail
import cn.kerong.bot.provider.HExecution
import cn.kerong.bot.provider.Motd
import cn.kerong.bot.provider.PlayerEventFormat
import cn.kerong.bot.tools.Cancelable
import cn.kerong.bot.tools.ConsoleColors
import cn.kerong.bot.tools.PluginFileLog
import cn.kerong.penguin.spigot.commands.BukkitConsoleSender
import cn.kerong.penguin.spigot.commands.CommandOutputAppender
import cn.kerong.penguin.spigot.commands.KERONGCommand
import cn.kerong.penguin.spigot.commands.HybridCommandExecutor
import cn.kerong.penguin.spigot.events.GameChat
import cn.kerong.penguin.spigot.events.PlayerEventsListener
import cn.kerong.penguin.spigot.manager.ConfigManager
import cn.kerong.penguin.spigot.render.InfoCardAssets
import cn.kerong.penguin.spigot.scheduler.FoliaCompat
import cn.kerong.penguin.spigot.stats.PapiDependencyCheck
import cn.kerong.penguin.spigot.stats.PlayerStatsManager
import cn.kerong.penguin.spigot.stats.SparkBridge
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
class KERONGSpigot : JavaPlugin(), KERONG {
    private lateinit var configManager: ConfigManager

    // ---------- 生命周期 ----------

    override fun onEnable() {
        configManager = ConfigManager(this)
        configManager.initialize()

        // 自动创建卡片背景图目录
        File(dataFolder, "img").mkdirs()

        // 启动 QQ 机器人运行时（注册共享实例 → 装载状态 → 异步启动 QQ 客户端）
        initializeRuntime()

        // 初始化命令（主命令 /kerong，短别名 /hb）
        val command = KERONGCommand(this)
        getCommand("kerong")?.let { pluginCommand ->
            pluginCommand.setExecutor(command)
            pluginCommand.setTabCompleter(command)
        } ?: log_error("无法注册 /kerong 命令，请检查 plugin.yml")

        // 注册游戏事件监听（GameChat 构造时会初始化 QqBindManager，需在共享实例注册后）
        server.pluginManager.registerEvents(GameChat(), this)
        server.pluginManager.registerEvents(PlayerEventsListener(), this)

        // 注册统计记录器（/查信息 数据源）
        PlayerStatsManager.initialize()

        // 1.6.6.2：启动即开启磁盘缓存清扫线程（首轮 10 秒后执行）——
        // 不再等到首次取头像才惰性启动，强制关机 / 崩溃后残留的过期
        // 头像缓存文件在下次启动 10 秒内就会被清掉，磁盘清理不依赖
        // 运行期状态（关机前已过期但未清的文件同样覆盖）
        try {
            InfoCardAssets.ensureAvatarSweeper()
        } catch (_: Throwable) {
        }

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
        // 1.6.6：PlaceholderAPI 依赖检测（异步延迟 12 秒，无 PAPI 时提示
        // 哪些功能受影响、哪些不受影响）
        PapiDependencyCheck.scheduleStartupCheck(platform)
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

    /**
     * 服务器性能快照（1.6.7，/查性能 数据源；1.6.7.1 修复读不到）。
     *
     * - TPS / MSPT 经 [cn.kerong.bot.PerformanceSnapshot] 的分层反射
     *   读取：① Paper / Purpur / Folia 的 Server 接口 getTPS() /
     *   getAverageTickTime()；② 纯 Spigot 经 CraftServer#getServer()
     *   跳到内部 MinecraftServer 读 recentTps 与 tickTimes 数组
     *   （1.20.4 Spigot 映射名）/ tickTimesNanos（1.20.5+ Mojang 名）；
     *   ③ 仍无 TPS 时由 MSPT 换算（min(20, 1000/mspt)，Spark 同款）；
     *   全部失败才为 null，卡片显示「--」；
     * - JVM 堆内存与进程 CPU 由 [cn.kerong.bot.PerformanceSnapshot.capture]
     *   统一采集（双端一致）；
     * - 读取全部只读数据（数组 / 计数），无实体状态操作，不需要切回主线程。
     */
    override fun performanceSnapshot(): cn.kerong.bot.PerformanceSnapshot? {
        // 1.6.7.2：装了 spark 插件时优先直接调用 spark 取数（对核心无感，
        // 数据最稳）；spark 读不到的项逐项回退自研反射链
        try {
            val sparkPlugin = Bukkit.getPluginManager().getPlugin("spark")
            if (sparkPlugin != null && sparkPlugin.isEnabled) {
                val metrics = SparkBridge.read(sparkPlugin.javaClass.classLoader)
                if (metrics != null) {
                    val api: org.bukkit.Server = Bukkit.getServer()
                    val mspt = metrics.mspt ?: cn.kerong.bot.PerformanceSnapshot.readMspt(api)
                    val tps = metrics.tps
                        ?: cn.kerong.bot.PerformanceSnapshot.readTps(api)
                        ?: cn.kerong.bot.PerformanceSnapshot.tpsFromMspt(mspt)
                    return cn.kerong.bot.PerformanceSnapshot.capture(tps, mspt, metrics.cpu)
                }
            }
        } catch (_: Throwable) {
            // spark 检测失败不影响后续回退链路
        }
        return try {
            val api: org.bukkit.Server = Bukkit.getServer()
            val mspt = cn.kerong.bot.PerformanceSnapshot.readMspt(api)
            val tps = cn.kerong.bot.PerformanceSnapshot.readTps(api)
                ?: cn.kerong.bot.PerformanceSnapshot.tpsFromMspt(mspt)
            cn.kerong.bot.PerformanceSnapshot.capture(tps, mspt)
        } catch (_: Throwable) {
            null
        }
    }

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

    // ---------- 日志 ----------

    // 0.1.5.5：警告与错误改为“控制台 + 插件日志文件”双写。
    // 此前仅走 Bukkit Logger（只进控制台 / latest.log 路由不稳定的混合端），
    // 渲染失败、发送失败等报错事后无法从文件追溯——即用户反馈的
    // “部分报错没有记录在日志里面”的问题。
    // 1.6.1：info 级同样双写（用户要求插件全部控制台输出都留档）；
    // 数据保存等“只留档不刷屏”的日志仍走 PluginFileLog.write 仅写文件，
    // 互不影响。
    // 1.6.6：控制台输出前把 § 颜色码转 ANSI 转义（Bukkit JUL Logger
    // 直出 § 码不会被渲染，控制台只会显示符号本身）；终端不支持 ANSI
    // （输出重定向到文件）时自动退化为纯文本；文件行自动剥离颜色码
    // （见 PluginFileLog / ConsoleColors）。
    override fun log_info(msg: String) {
        logger.info(ConsoleColors.decorateForConsole(msg))
        PluginFileLog.write(msg)
    }

    override fun log_warning(msg: String) {
        logger.warning(ConsoleColors.decorateForConsole(msg))
        PluginFileLog.write("[警告] $msg")
    }

    override fun log_error(msg: String) {
        logger.severe(ConsoleColors.decorateForConsole(msg))
        PluginFileLog.write("[错误] $msg")
    }
}
