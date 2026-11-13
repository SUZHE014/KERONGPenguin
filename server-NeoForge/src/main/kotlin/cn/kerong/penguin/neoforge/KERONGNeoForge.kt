package cn.kerong.penguin.neoforge

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
import net.minecraft.network.chat.Component
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * KERONGPenguin 模组主类（NeoForge 适配器，MC 1.20.4+：1.20.4 / 1.20.6 / 1.21.x）。
 *
 * 与 Spigot 版（KERONGSpigot）同源：共用 common-Bot 全部业务
 * （QQ 机器人 / 命令系统 / 绑定 / 签到 / 统计 / 信息卡渲染），
 * 平台差异经 org.bukkit 兼容层桥接：
 * - 主线程 = 服务器线程（MinecraftServer#execute）；
 * - 玩家查询 / 统计 / 皮肤 / playerdata 定位 → PlayerList / StatsCounter /
 *   GameProfile 档案 / 世界存档目录；
 * - Vault / DeluxeTags / PlayerPoints / PlaceholderAPI 不存在 → 相关功能
 *   自动降级（卡片隐藏金币称号点券项，签到走记账模式）。
 */
@Mod("kerongpenguin")
class KERONGNeoForge : JavaPlugin(), KERONG {

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

    init {
        instance = this
        PluginManager.register(this)
        // 事件总线（game bus）：聊天 / 进出服 / 死亡 / 破坏 / 命令注册 / 生命周期
        NeoForge.EVENT_BUS.register(NeoForgeEvents(this))
        NeoForge.EVENT_BUS.register(NeoForgeCommands(this))
        modLogger.info("KERONGPenguin NeoForge 适配器已构造（QQ 机器人在服务器启动完成后启动）")
    }

    // ---------- 生命周期（由 NeoForgeEvents 驱动） ----------

    /** 服务器即将启动（ServerAboutToStartEvent）：绑定服务器引用。 */
    internal fun onServerAboutToStart(server: net.minecraft.server.MinecraftServer) {
        NeoServerRef.bind(server)
        markEnabled()
    }

    /** 服务器就绪（ServerStartedEvent）：初始化配置与 QQ 运行时。 */
    internal fun onServerStarted() {
        configManager = ConfigManager(this)
        configManager.initialize()
        File(dataFolder, "img").mkdirs()

 // 注入本服端口（MotdDisplayTracker 本机 SLP
        // 自查询——render.performance.max-players-source = motd 时查性能展示
        // 服务器列表的实际展示值，与 Spigot 端同一机制；端口三重策略见
        // NeoPortReader：连接 channel 反射 → 专用服属性反射 → server.properties）
        cn.kerong.penguin.spigot.stats.MotdDisplayTracker.portProvider = {
            try {
                NeoPortReader.portOf(NeoServerRef.server)
                    ?: NeoPortReader.portFromPropertiesFile(dataFolder)
            } catch (_: Throwable) {
                null
            }
        }
        // 共享实例注册 → 装载状态 → 异步启动 QQ 客户端（与 Spigot onEnable 同序）
        initializeRuntime()
 // QQ 绑定开启时自动关闭服务器自带白名单并提醒（1.7.5.2；
        // ServerStarted 时 playerList 已就绪，关闭发生在任何玩家登录之前）
        try {
            enforceNoNativeWhitelist()
        } catch (_: Throwable) {
        }
        PlayerStatsManager.initialize()
 // 启动即开启磁盘缓存清扫线程（首轮 10 秒后执行）——
        // 强制关机 / 崩溃后残留的过期头像缓存文件在下次启动 10 秒内
        // 就会被清掉，磁盘清理不依赖运行期状态（与 Spigot 同序）
        try {
            InfoCardAssets.ensureAvatarSweeper()
        } catch (_: Throwable) {
        }
        registerBindLoginGate()
 // 服务器开关消息（口径与 HuHoBot 的 server-notify
 // 一致，默认开启；180 秒重启判定窗口）——启动完成时
        // 检查上次运行如何结束（崩溃补发 / 窗口内发重启 / 超窗发开机）；
        // QQ 异步连接，通知线程就绪后自发
        try {
            cn.kerong.bot.ServerNotifyManager.onServerStarted()
        } catch (_: Throwable) {
        }
 // spark 接入状态提示；1.6.7.4 三态探测（ServerStarted 即
        // 服务器启动成功，延迟 5 秒等 spark 统计窗口积累首批样本；成功提示
        // 受 commands.查性能 开关门控、接入失败无论开关必提示、未安装静默）
        SparkStartupCheck.schedule(SparkStartupCheck.neoforgeDelay()) {
            try {
                if (net.neoforged.fml.ModList.get().isLoaded("spark")) {
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
        modLogger.info("KERONGPenguin NeoForge 已加载（${pluginVersion} / MC ${serverVersion}）")
    }

    /**
 * 挂载绑定登录闸门：监听 mod 总线的配置阶段事件（20.5+
     * RegisterConfigurationTasksEvent / 20.4 OnGameConfigurationEvent，经
     * BindLoginGate 运行时探测），未绑定老玩家在配置阶段（进入世界之前）
     * 直接断开，绑定提示显示在连接失败界面。挂载失败不影响功能（退化为
     * 加入后踢出的旧版兜底路径）。1.6.8：按 registerTo 真实结果反馈，
     * 不再无论成败一律报「已挂载」（失败细节由 registerTo 内部 WARN）。
     */
    private fun registerBindLoginGate() {
        try {
            val container = net.neoforged.fml.ModList.get()
                .getModContainerById("kerongpenguin").orElse(null)
            val modBus = (container as? net.neoforged.fml.javafmlmod.FMLModContainer)?.getEventBus()
            if (modBus == null) {
                modLogger.warn("[QQ绑定] 登录闸门挂载失败（未取得 mod 事件总线），老玩家拦截退化为加入后踢出")
                return
            }
            if (BindLoginGate.registerTo(modBus)) {
                modLogger.info("[QQ绑定] 登录闸门已挂载（未绑定老玩家在配置阶段拦截）")
            }
        } catch (t: Throwable) {
            modLogger.warn("[QQ绑定] 登录闸门挂载异常（老玩家拦截退化为加入后踢出）: ${t.message}")
        }
    }

    /** 服务器停止中（ServerStoppingEvent）：落账并保存。 */
    internal fun onServerStopping() {
 // 服务器开关消息——真实关服发「服务器已正常
        // 关闭」（同步发送，此时 QQ 客户端仍在连接）。必须最先执行（后续
        // shutdownRuntime 会断开 QQ 连接）；ServerStoppingEvent 在 NeoForge
        // 里只会由真实关服触发（模组无插件式热重载），按真实停止处理
        try {
            cn.kerong.bot.ServerNotifyManager.onServerStopping(true)
        } catch (_: Throwable) {
        }
 // 清空 motd 展示值捕获器（端口注入与捕获值）
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

    /** 服务器完全停止（ServerStoppedEvent）：解除引用并兑底退出进程。 */
    internal fun onServerStopped() {
        val dedicated = try {
            NeoServerRef.server?.isDedicatedServer
        } catch (_: Throwable) {
            null
        }
        NeoServerRef.unbind()
        PluginManager.unregister()
        timerPool.shutdownNow()
        asyncPool.shutdownNow()
        ensureProcessExit(dedicated == true)
    }

    /**
 * 关服兑底退出（仅专用服）：
     *
     * NeoForge 主线程结束后，JVM 须等所有非 daemon 线程结束才退出；
     * 尽管 SptCompat 已回收已知非 daemon 池，仍不排除残留线程
     * （Pterodactyl 等面板表现为“服务器已停止但进程挂着”，只能强制结束）。
     * 此处起一个守护线程：若 5 秒后进程仍未自然退出则 halt(0)，
     * 兑底保证 stop 始终能关掉；若 JVM 已自然退出则该线程随之消失，无副作用。
     * 单人游戏（集成服务器的 ServerStoppedEvent）不受影响。
     */
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
 // QQ 绑定刚开启时补检白名单（1.7.5.2：绑定开于 reload 而非启动的
        // 场景，关闭与提醒不能等到下次重启）
        try {
            enforceNoNativeWhitelist()
        } catch (_: Throwable) {
        }
 // 多玩家选择的待选会话随重载作废（旧闭包可能引用过期配置）
        cn.kerong.bot.events.PlayerSelection.clearAll()
        cn.kerong.bot.events.UnblacklistButtons.clearAll()
 // 群自动绑定标记重置（清空 groups 后可再次
        // 自动绑定）；多服务器集群重新加载（配置 + 选择状态文件）并清弹窗节流
        cn.kerong.bot.events.AutoGroupBinder.reset()
        cn.kerong.bot.ClusterState.reload()
        cn.kerong.bot.events.ServerSelectButtons.clearAll()
    }

    // ---------- 平台标识 ----------

    override val name: String = "KERONGPenguin"

    override val dataFolder: File = MOD_DATA_DIR

    override val platform: String = "neoforge"

    override val pluginVersion: String
        get() = MOD_VERSION

    override val serverVersion: String
        get() = NeoServerRef.server?.serverModName ?: "NeoForge"

    // ---------- 配置（ConfigManager，与 Spigot 同一实现） ----------

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

 // 多服务器集群配置（与 Spigot 共用 ConfigManager）
    override fun clusterName(): String = configManager.clusterName()

    override fun clusterMembers(): List<String> = configManager.clusterMembers()

 // 服务器开关消息配置接线（此前两端主类都未覆写这些
    // getter，运行时一直走 ConfigProvider 接口硬编码默认值——用户在
    // config.yml 里改 server-notify 段不生效；本轮补上两端委托，配置
    // 文件真正生效；与 Spigot 共用 ConfigManager）
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

    override fun setFullAmountEnabled(enabled: Boolean): Boolean =
        configManager.setFullAmountEnabled(enabled)

    override fun commandList(): Map<String, Boolean> = configManager.commandSwitches()

    override fun customCommands(): List<CustomCommandDetail> = configManager.customCommands()

    override val botName: String
        get() = configManager.botName()

    override val serverName: String
        get() = configManager.serverName()

    // ---------- 命令执行（控制台） ----------

    override fun createCommandExecutor(): HExecution = NeoForgeConsoleExecutor(this)

    // ---------- 玩家 ----------

    override val onlineList: List<String>
        get() = NeoServerRef.server?.playerList?.players?.map { it.gameProfile.name } ?: emptyList()

    // ---------- 服务器自带白名单（QQ 绑定互斥检测，1.7.5.2） ----------

    /**
     * 白名单开关方法名跨版本不一致（编译目标 1.20.4 mojmap 为
     * isUsingWhiteList / setUsingWhiteList，1.20.6+ 改为 isUsingWhitelist /
     * setUsingWhitelist）——直接调用会在其一版本运行时抛 NoSuchMethodError，
     * 按本模组跨版本惯例走反射双名探测。
     */
    private fun whitelistMethod(name20_4: String, name20_6plus: String): java.lang.reflect.Method? {
        val playerList = NeoServerRef.server?.playerList ?: return null
        for (name in listOf(name20_4, name20_6plus)) {
            try {
                return playerList.javaClass.getMethod(name)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    override fun isNativeWhitelistEnabled(): Boolean = try {
        val playerList = NeoServerRef.server?.playerList ?: return false
        whitelistMethod("isUsingWhiteList", "isUsingWhitelist")
            ?.invoke(playerList) as? Boolean ?: false
    } catch (_: Throwable) {
        false
    }

    override fun disableNativeWhitelist() {
        try {
            val playerList = NeoServerRef.server?.playerList ?: return
            val getter = whitelistMethod("isUsingWhiteList", "isUsingWhitelist") ?: return
            val setterName = if (getter.name == "isUsingWhiteList") "setUsingWhiteList" else "setUsingWhitelist"
            playerList.javaClass.getMethod(setterName, java.lang.Boolean.TYPE)
                .invoke(playerList, false)
        } catch (_: Throwable) {
        }
    }

    /**
 * 服务器性能快照（/查性能 数据源；修复读不到；增
 * 在线人数；移除延迟项）。
     *
     * - MSPT 反射读取 MinecraftServer 的 getAverageTickTimeNanos()
     *   （1.20.5+ Mojang 映射公有方法，纳秒 → 毫秒），失败时回退
     *   tickTimesNanos / tickTimes 数组平均；NeoForge 单一服务器线程，
     *   该读数权威稳定，不需要自采样兜底；
     * - TPS 由 MSPT 换算（1000/mspt 封顶 20）；
 * - 在线人数：playerList 当前人数（仅人数展示）；
     * - 1.6.7.8：延迟项移除——玩家 Ping 反映的是「客户端到服务器」连接
     *   的往返，本地 / 同机 / 内网穿透部署下恒为 0ms，数据永远只代表
     *   服务器本地网络，对性能监控无实际意义（用户反馈），
     *   spark playerPing 与连接 latency 平均链路一并下线；
     * - JVM 堆内存与进程 CPU 由 [cn.kerong.bot.PerformanceSnapshot.capture]
     *   统一采集（双端一致）；
     * - 服务器未完成启动（NeoServerRef 为空）返回 null。
     */
    override fun performanceSnapshot(): cn.kerong.bot.PerformanceSnapshot? {
        val server = NeoServerRef.server ?: return null
 // 装了 spark 模组时优先直接调用 spark 取数（NeoForge 无
        // TPS 追踪字段，spark 是最稳的来源）；spark 读不到的项逐项回退
        var sparkTps: Double? = null
        var sparkMspt: Double? = null
        var sparkCpu: Double? = null
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("spark")) {
                val metrics = SparkBridge.read()
                if (metrics != null) {
                    sparkTps = metrics.tps
                    sparkMspt = metrics.mspt
                    sparkCpu = metrics.cpu
                }
            }
        } catch (_: Throwable) {
            // spark 检测失败不影响后续回退链路
        }

        val mspt = sparkMspt ?: cn.kerong.bot.PerformanceSnapshot.readMspt(server)
        val tps = sparkTps
            ?: cn.kerong.bot.PerformanceSnapshot.readTps(server)
            ?: cn.kerong.bot.PerformanceSnapshot.tpsFromMspt(mspt)

 // 在线人数（仅人数展示）；1.6.7.8 起不再采集延迟——玩家
        // Ping 反映的是「客户端到服务器」连接的往返，本地 / 同机 / 内网
        // 穿透部署下恒为 0ms，数据永远只代表服务器本地网络，对性能监控
        // 无实际意义（用户反馈），延迟指标与采集链路整体下线
        var online: Int? = null
        try {
            online = server.playerList?.players?.size
        } catch (_: Throwable) {
            // 玩家表读取失败：人数保持 null（卡片显示「--」）
        }

 // 真实最大人数（查性能卡片在线人数卡展示「N / M」；
 // motd 展示值由 MotdDisplayTracker 本机
        // SLP 自查询捕获（双端一致），这里始终提供真实值兜底）
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
        val server = NeoServerRef.server
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
        val server = NeoServerRef.server
        if (server != null) {
            server.execute(task)
        } else {
            task.run()
        }
    }

    // ---------- 广播 ----------

    override fun broadcastMessage(msg: String) {
        val server = NeoServerRef.server ?: return
        server.execute {
            server.playerList.broadcastSystemMessage(
                LegacyText.toComponent(msg),
                false,
            )
        }
    }

    // ---------- 日志 ----------

 // info 级与警告/错误一致双写（控制台 + 插件日志文件），
    // 保证插件全部控制台输出都能从 logs/qq/qq-bind-日期.log 追溯。
 // 控制台输出前把 § 颜色码转 ANSI 转义——log4j 不渲染 § 码，
    // 但 Minecraft 控制台（jline 终端）会渲染 ANSI 转义为真实颜色；
    // 终端不支持 ANSI（输出重定向）时自动退化为纯文本；文件日志
    // PluginFileLog.write 本身已剥离，不受影响。
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
        private val MOD_DATA_DIR: File = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
            .resolve("kerongpenguin").toFile()

        /** 模组版本（与 neoforge.mods.toml 一致）。 */
        private const val MOD_VERSION = "1.7.5.6"

        /** 当前模组实例（命令与事件层访问）。 */
        @Volatile
        internal var instance: KERONGNeoForge? = null
            private set
    }
}
