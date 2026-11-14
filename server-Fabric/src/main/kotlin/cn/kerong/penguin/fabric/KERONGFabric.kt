package cn.kerong.penguin.fabric

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
import cn.kerong.penguin.spigot.manager.ConfigManager
import cn.kerong.penguin.spigot.render.InfoCardAssets
import cn.kerong.penguin.spigot.stats.PlayerStatsManager
import cn.kerong.penguin.spigot.stats.SparkBridge
import cn.kerong.penguin.spigot.stats.SparkStartupCheck
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.loader.api.FabricLoader
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * KERONGPenguin 模组主类（Fabric 适配器，MC 1.20.4+，1.7.5.1 起入主线发版链）。
 *
 * 与 Spigot / NeoForge 版同源：共用 common-Bot 全部业务
 * （QQ 机器人 / 命令系统 / 绑定 / 签到 / 统计 / 信息卡渲染），
 * 平台差异经 org.bukkit 兼容层桥接（同 NeoForge 版思路）：
 * - 主线程 = 服务器线程（MinecraftServer#execute）；
 * - 玩家查询 / 统计 / 皮肤 / playerdata 定位 → PlayerList / StatsCounter /
 *   GameProfile 档案 / 世界存档目录（mojmap 名编译，运行时经
 *   tiny-remapper 重映射为 intermediary 名）；
 * - Vault / DeluxeTags / PlayerPoints / PlaceholderAPI 不存在 → 相关功能
 *   自动降级（卡片隐藏金币称号点券项，签到走记账模式）；
 * - spark 为 Fabric 版 mod 时经同一 SparkBridge 反射取数。
 *
 * 1.7.5.1 起随主线同号发版；QQ 绑定不再拦截进服（未绑定玩家正常游玩，
 * 绑定验证码改由游戏内 /hbkrg qbd 获取，见 FabricCommands）。
 * 钓鱼 / 繁殖动物统计暂缺（Fabric API 无对应事件，与 NeoForge 版的已知差异）。
 */
class KERONGFabric : JavaPlugin(), KERONG, DedicatedServerModInitializer {

    private lateinit var configManager: ConfigManager

    /** 模组日志（SLF4J）。 */
    private val modLogger: Logger = LoggerFactory.getLogger("KERONGPenguin")

    /** 异步池（submitAsync；与 BukkitScheduler 内部池独立均可）。 */
    private val asyncPool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "KERONGPenguin-Pool").apply { isDaemon = true }
    }

    /** 延迟 / 周期任务池（投递回服务器线程执行）。 */
    private val timerPool = Executors.newScheduledThreadPool(1) { runnable ->
        Thread(runnable, "KERONGPenguin-Timing").apply { isDaemon = true }
    }

    override fun onInitializeServer() {
        instance = this
        PluginManager.register(this)
        FabricEvents(this).registerAll()
        modLogger.info("KERONGPenguin Fabric 适配器已构造（QQ 机器人在服务器启动完成后启动）")
    }

    // ---------- 生命周期（由 FabricEvents 驱动） ----------

    /** 服务器即将启动（SERVER_STARTING）：绑定服务器引用。 */
    internal fun onServerAboutToStart(server: net.minecraft.server.MinecraftServer) {
        FabricServerRef.bind(server)
        markEnabled()
    }

    /** 服务器就绪（SERVER_STARTED）：初始化配置与 QQ 运行时。 */
    internal fun onServerStarted() {
        configManager = ConfigManager(this)
        configManager.initialize()
        File(dataFolder, "img").mkdirs()

        // 注入本服端口（MotdDisplayTracker 本机 SLP 自查询；与 NeoForge 版同机制）
        cn.kerong.penguin.spigot.stats.MotdDisplayTracker.portProvider = {
            try {
                FabricPortReader.portOf(FabricServerRef.server)
                    ?: FabricPortReader.portFromPropertiesFile(dataFolder)
            } catch (_: Throwable) {
                null
            }
        }
        // 共享实例注册 → 装载状态 → 异步启动 QQ 客户端（与 Spigot onEnable 同序）
        initializeRuntime()
        PlayerStatsManager.initialize()
        // 磁盘缓存清扫线程（与 Spigot / NeoForge 同序）
        try {
            InfoCardAssets.ensureAvatarSweeper()
        } catch (_: Throwable) {
        }
        // 服务器开关消息（1.7.4 第三轮覆盖版引入；1.7.4.1：180 秒重启判定窗口）
        try {
            cn.kerong.bot.ServerNotifyManager.onServerStarted()
        } catch (_: Throwable) {
        }
        // spark 接入状态提示（延迟 5 秒等统计窗口积累首批样本）
        SparkStartupCheck.schedule(SparkStartupCheck.neoforgeDelay()) {
            try {
                if (FabricLoader.getInstance().isModLoaded("spark")) {
                    val metrics = SparkBridge.read()
                    if (metrics == null) {
                        SparkStartupCheck.ProbeOutcome.Failed("各指标均暂不可读")
                    } else {
                        SparkStartupCheck.ProbeOutcome.Installed(metrics, bundled = false)
                    }
                } else {
                    SparkStartupCheck.ProbeOutcome.NotInstalled
                }
            } catch (t: Throwable) {
                SparkStartupCheck.ProbeOutcome.Failed(
                    t.message?.takeIf { it.isNotEmpty() } ?: t.toString()
                )
            }
        }
        modLogger.info("KERONGPenguin Fabric 已加载（${pluginVersion} / MC ${serverVersion}）")
    }

    /** 服务器停止中（SERVER_STOPPING）：落账并保存。 */
    internal fun onServerStopping() {
        // 服务器开关消息——真实关服发「服务器已正常关闭」（同步发送）。
        // 必须最先执行（后续 shutdownRuntime 会断开 QQ 连接）；Fabric 的
        // SERVER_STOPPING 只会由真实关服触发（无插件式热重载），按真实停止处理
        try {
            cn.kerong.bot.ServerNotifyManager.onServerStopping(true)
        } catch (_: Throwable) {
        }
        try {
            cn.kerong.penguin.spigot.stats.MotdDisplayTracker.reset()
        } catch (_: Throwable) {
        }
        try {
            PlayerStatsManager.shutdown()
        } catch (_: Throwable) {
        }
        try {
            InfoCardAssets.clearCaches()
        } catch (_: Throwable) {
        }
        shutdownRuntime()
        markDisabled()
    }

    /** 服务器完全停止（SERVER_STOPPED）：解除引用并兑底退出进程。 */
    internal fun onServerStopped() {
        val dedicated = try {
            FabricServerRef.server?.isDedicatedServer
        } catch (_: Throwable) {
            null
        }
        FabricServerRef.unbind()
        PluginManager.unregister()
        timerPool.shutdownNow()
        asyncPool.shutdownNow()
        ensureProcessExit(dedicated == true)
    }

    /** 关服兑底退出（与 NeoForge 版同机制：残留非 daemon 线程防进程悬挂）。 */
    private fun ensureProcessExit(dedicated: Boolean) {
        if (!dedicated) return
        val guard = Thread({
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            modLogger.info("[关服兑底] 服务器已完全停止 5 秒，进程仍未退出，强制退出")
            Runtime.getRuntime().halt(0)
        }, "KERONGPenguin-Exit")
        guard.isDaemon = true
        guard.start()
    }

    override fun reloadPluginConfig() {
        configManager.reload()
        reloadRuntimeConfig()
        cn.kerong.bot.events.PlayerSelection.clearAll()
        cn.kerong.bot.events.UnblacklistButtons.clearAll()
        cn.kerong.bot.events.AutoGroupBinder.reset()
        cn.kerong.bot.ClusterState.reload()
        cn.kerong.bot.events.ServerSelectButtons.clearAll()
        // 1.7.5.6 覆盖轮：按钮互动冷却记录随重载作废（间隔改小后立即生效）
        cn.kerong.bot.events.ButtonInteractionCooldown.clearAll()
    }

    // ---------- 平台标识 ----------

    override val name: String = "KERONGPenguin"

    override val dataFolder: File = MOD_DATA_DIR

    override val platform: String = "fabric"

    override val pluginVersion: String
        get() = MOD_VERSION

    override val serverVersion: String
        get() = try {
            "Fabric MC " + (FabricLoader.getInstance().getModContainer("minecraft")
                .map { it.metadata.version.friendlyString }.orElse("unknown"))
        } catch (_: Throwable) {
            "Fabric"
        }

    // ---------- 配置（ConfigManager，与 Spigot / NeoForge 同一实现） ----------

    override val configFile: File
        get() = configManager.getConfigFile()

    override val botAppId: String
        get() = configManager.botAppId()

    override val botSecret: String
        get() = configManager.botSecret()

    override val chatFormat: ChatFormat
        get() = configManager.chatFormat()

    /** Fabric 端未绑定限制的超时秒数（qq-bind.restrict-timeout-seconds，1.7.5.9）。 */
    fun restrictTimeoutSeconds(): Int = configManager.restrictTimeoutSeconds()

    override fun playerEventFormat(): PlayerEventFormat = configManager.playerEventFormat()

    override fun markdownFiles(): Map<String, String> = configManager.markdownFiles()

    override val motd: Motd
        get() = configManager.motd()

    override fun filterRegexList(): List<String> = configManager.filterRegexList()

    override fun adminMode(): AdminMode = configManager.adminMode()

    override fun adminList(): List<String> = configManager.adminOpenIds()

    override fun groupOpenIdList(): List<String> = configManager.groupOpenIds()

    override fun clusterName(): String = configManager.clusterName()

    override fun clusterMembers(): List<String> = configManager.clusterMembers()

    // 1.7.4.1 覆盖版：服务器开关消息配置接线（双端主类委托，配置文件真正生效）
    override fun isServerNotifyEnabled(): Boolean = configManager.isServerNotifyEnabled()

    override fun serverNotifyRestartWindow(): Int = configManager.serverNotifyRestartWindow()

    override fun isServerNotifyShutdownEnabled(): Boolean = configManager.isServerNotifyShutdownEnabled()

    override fun serverNotifyShutdownMessage(): String = configManager.serverNotifyShutdownMessage()

    override fun isServerNotifyRestartEnabled(): Boolean = configManager.isServerNotifyRestartEnabled()

    override fun serverNotifyRestartMessage(): String = configManager.serverNotifyRestartMessage()

    override fun isServerNotifyStartEnabled(): Boolean = configManager.isServerNotifyStartEnabled()

    override fun serverNotifyStartMessage(): String = configManager.serverNotifyStartMessage()

    override fun isServerNotifyCrashEnabled(): Boolean = configManager.isServerNotifyCrashEnabled()

    override fun serverNotifyCrashMessage(): String = configManager.serverNotifyCrashMessage()

    override val fullAmount: Boolean
        get() = configManager.fullForwardingByDefault()

    /** /全量 总开关写回（1.7.5 覆盖第三轮起：切换后写回 features.full-amount）。 */
    override fun setFullAmountEnabled(enabled: Boolean): Boolean =
        configManager.setFullAmountEnabled(enabled)

    override fun commandList(): Map<String, Boolean> = configManager.commandSwitches()

    override fun customCommands(): List<CustomCommandDetail> = configManager.customCommands()

    override val botName: String
        get() = configManager.botName()

    override val serverName: String
        get() = configManager.serverName()

    // ---------- 命令执行（控制台） ----------

    override fun createCommandExecutor(): HExecution = FabricConsoleExecutor(this)

    // ---------- 玩家 ----------

    override val onlineList: List<String>
        get() = FabricServerRef.server?.playerList?.players?.map { it.gameProfile.name } ?: emptyList()

    /**
     * 服务器性能快照（与 NeoForge 版同源：spark 优先，反射 MSPT 兜底，
     * 在线人数真实值 + motd 展示值由 MotdDisplayTracker 捕获）。
     */
    override fun performanceSnapshot(): cn.kerong.bot.PerformanceSnapshot? {
        val server = FabricServerRef.server ?: return null
        var sparkTps: Double? = null
        var sparkMspt: Double? = null
        var sparkCpu: Double? = null
        try {
            if (FabricLoader.getInstance().isModLoaded("spark")) {
                val metrics = SparkBridge.read()
                if (metrics != null) {
                    sparkTps = metrics.tps
                    sparkMspt = metrics.mspt
                    sparkCpu = metrics.cpu
                }
            }
        } catch (_: Throwable) {
        }

        val mspt = sparkMspt ?: cn.kerong.bot.PerformanceSnapshot.readMspt(server)
        val tps = sparkTps
            ?: cn.kerong.bot.PerformanceSnapshot.readTps(server)
            ?: cn.kerong.bot.PerformanceSnapshot.tpsFromMspt(mspt)

        var online: Int? = null
        try {
            online = server.playerList?.players?.size
        } catch (_: Throwable) {
        }

        val realMaxPlayers = try {
            server.playerList?.maxPlayers
        } catch (_: Throwable) {
            null
        }

        return try {
            cn.kerong.bot.PerformanceSnapshot.capture(tps, mspt, sparkCpu, online, realMaxPlayers)
        } catch (_: Throwable) {
            null
        }
    }

    // ---------- 调度（主线程 = 服务器线程） ----------

    override fun submit(task: Runnable): Cancelable {
        val server = FabricServerRef.server
        if (server != null) {
            server.execute(task)
        } else {
            asyncPool.execute(task)
        }
        return object : Cancelable {
            override fun cancel() {}
        }
    }

    override fun submitLater(delay: Long, task: Runnable): Cancelable = schedule(delay, 0L, task)

    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable = schedule(delay, period, task)

    private fun schedule(delay: Long, period: Long, task: Runnable): Cancelable {
        val future: ScheduledFuture<*> = if (period > 0) {
            timerPool.scheduleAtFixedRate({ runOnServerThread(task) }, delay * 50L, period * 50L, TimeUnit.MILLISECONDS)
        } else {
            timerPool.schedule({ runOnServerThread(task) }, delay * 50L, TimeUnit.MILLISECONDS)
        }
        return object : Cancelable {
            override fun cancel() {
                future.cancel(false)
            }
        }
    }

    private fun runOnServerThread(task: Runnable) {
        val server = FabricServerRef.server
        if (server != null) {
            server.execute(task)
        } else {
            task.run()
        }
    }

    // ---------- 广播 ----------

    override fun broadcastMessage(msg: String) {
        val server = FabricServerRef.server ?: return
        server.execute {
            server.playerList.broadcastSystemMessage(
                LegacyText.toComponent(msg),
                false,
            )
        }
    }

    // ---------- 日志 ----------

    override fun log_info(msg: String) {
        modLogger.info(ConsoleColors.decorateForConsole(msg))
        PluginFileLog.write(msg)
    }

    override fun log_warning(msg: String) {
        modLogger.warn(ConsoleColors.decorateForConsole(msg))
        PluginFileLog.write("[警告] $msg")
    }

    override fun log_error(msg: String) {
        modLogger.error(ConsoleColors.decorateForConsole(msg))
        PluginFileLog.write("[错误] $msg")
    }

    companion object {
        /** 数据目录：游戏目录下 kerongpenguin/（与 mods 同级，避开世界目录）。 */
        private val MOD_DATA_DIR: File = FabricLoader.getInstance().getGameDir()
            .resolve("kerongpenguin").toFile()

        /** 模组版本（读 fabric.mod.json，不再硬编码）。 */
        private val MOD_VERSION: String = try {
            FabricLoader.getInstance().getModContainer("kerongpenguin")
                .map { it.metadata.version.friendlyString }.orElse("unknown")
        } catch (_: Throwable) {
            "unknown"
        }

        /** 当前模组实例（命令与事件层访问）。 */
        @Volatile
        internal var instance: KERONGFabric? = null
            private set
    }
}
