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
 * KERONGPenguin 模组主类（NeoForge 1.21.1 适配器）。
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
        // 共享实例注册 → 装载状态 → 异步启动 QQ 客户端（与 Spigot onEnable 同序）
        initializeRuntime()
        PlayerStatsManager.initialize()
        // 1.6.6.2：启动即开启磁盘缓存清扫线程（首轮 10 秒后执行）——
        // 强制关机 / 崩溃后残留的过期头像缓存文件在下次启动 10 秒内
        // 就会被清掉，磁盘清理不依赖运行期状态（与 Spigot 同序）
        try {
            InfoCardAssets.ensureAvatarSweeper()
        } catch (_: Throwable) {
        }
        registerBindLoginGate()
        // 1.6.7.3：spark 接入状态提示；1.6.7.4 三态探测（ServerStarted 即
        // 服务器启动成功，延迟 5 秒等 spark 统计窗口积累首批样本；成功提示
        // 受 commands.查性能 开关门控、接入失败无论开关必提示、未安装静默）
        SparkStartupCheck.schedule(SparkStartupCheck.neoforgeDelay()) {
            try {
                if (net.neoforged.fml.ModList.get().isLoaded("spark")) {
                    val metrics = SparkBridge.read()
                    if (metrics == null) {
                        SparkStartupCheck.ProbeOutcome.Failed("各指标均暂不可读")
                    } else {
                        SparkStartupCheck.ProbeOutcome.Installed(metrics)
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
     * 挂载绑定登录闸门（1.6.4）：监听 mod 总线的 RegisterConfigurationTasksEvent，
     * 未绑定老玩家在配置阶段（进入世界之前）直接断开，绑定提示显示在连接失败
     * 界面。挂载失败不影响功能（退化为加入后踢出的旧版兜底路径）。
     */
    private fun registerBindLoginGate() {
        try {
            val container = net.neoforged.fml.ModList.get()
                .getModContainerById("kerongpenguin").orElse(null)
            val modBus = (container as? net.neoforged.fml.javafmlmod.FMLModContainer)?.getEventBus()
            if (modBus != null) {
                BindLoginGate.registerTo(modBus)
                modLogger.info("[QQ绑定] 登录闸门已挂载（未绑定老玩家在配置阶段拦截）")
            } else {
                modLogger.warn("[QQ绑定] 登录闸门挂载失败（未取得 mod 事件总线），老玩家拦截退化为加入后踢出")
            }
        } catch (t: Throwable) {
            modLogger.warn("[QQ绑定] 登录闸门挂载异常（老玩家拦截退化为加入后踢出）: ${t.message}")
        }
    }

    /** 服务器停止中（ServerStoppingEvent）：落账并保存。 */
    internal fun onServerStopping() {
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
     * 关服兑底退出（1.5.4.2，仅专用服）：
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
    }

    // ---------- 平台标识 ----------

    override val name: String = "KERONGPenguin"

    override val dataFolder: File = MOD_DATA_DIR

    override val platform: String = "neoforge"

    override val pluginVersion: String
        get() = MOD_VERSION

    override val serverVersion: String
        get() = NeoServerRef.server?.serverModName ?: "NeoForge 1.21.1"

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

    override val fullAmount: Boolean
        get() = configManager.fullForwardingByDefault()

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

    /**
     * 服务器性能快照（1.6.7，/查性能 数据源；1.6.7.1 修复读不到）。
     *
     * - MSPT 反射读取 MinecraftServer 的 getAverageTickTimeNanos()
     *   （1.20.5+ Mojang 映射公有方法，纳秒 → 毫秒；1.6.7 错误反射
     *   不存在的 getAverageTickTime()/tickTimes 导致永远读不到），
     *   失败时回退 tickTimesNanos / tickTimes 数组平均；
     * - TPS 由 MSPT 换算（1000/mspt 封顶 20，与 Spark 等性能插件
     *   算法一致）；
     * - JVM 堆内存与进程 CPU 由 [cn.kerong.bot.PerformanceSnapshot.capture]
     *   统一采集（双端一致）；
     * - 服务器未完成启动（NeoServerRef 为空）返回 null。
     */
    override fun performanceSnapshot(): cn.kerong.bot.PerformanceSnapshot? {
        val server = NeoServerRef.server ?: return null
        // 1.6.7.2：装了 spark 模组时优先直接调用 spark 取数（NeoForge 无
        // TPS 追踪字段，spark 是最稳的来源）；spark 读不到的项逐项回退
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("spark")) {
                val metrics = SparkBridge.read()
                if (metrics != null) {
                    val mspt = metrics.mspt ?: cn.kerong.bot.PerformanceSnapshot.readMspt(server)
                    val tps = metrics.tps
                        ?: cn.kerong.bot.PerformanceSnapshot.readTps(server)
                        ?: cn.kerong.bot.PerformanceSnapshot.tpsFromMspt(mspt)
                    return cn.kerong.bot.PerformanceSnapshot.capture(tps, mspt, metrics.cpu)
                }
            }
        } catch (_: Throwable) {
            // spark 检测失败不影响后续回退链路
        }
        return try {
            val mspt = cn.kerong.bot.PerformanceSnapshot.readMspt(server)
            val tps = cn.kerong.bot.PerformanceSnapshot.readTps(server)
                ?: cn.kerong.bot.PerformanceSnapshot.tpsFromMspt(mspt)
            cn.kerong.bot.PerformanceSnapshot.capture(tps, mspt)
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

    // 1.6.1：info 级与警告/错误一致双写（控制台 + 插件日志文件），
    // 保证插件全部控制台输出都能从 logs/qq/qq-bind-日期.log 追溯。
    // 1.6.6：控制台输出前把 § 颜色码转 ANSI 转义——log4j 不渲染 § 码，
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
        private const val MOD_VERSION = "1.6.7.4"

        /** 当前模组实例（命令与事件层访问）。 */
        @Volatile
        internal var instance: KERONGNeoForge? = null
            private set
    }
}
