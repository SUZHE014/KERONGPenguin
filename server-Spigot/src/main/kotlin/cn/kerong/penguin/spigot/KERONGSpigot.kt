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
import cn.kerong.penguin.spigot.stats.SparkStartupCheck
import cn.kerong.penguin.spigot.stats.TickMonitor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginCommand
import org.bukkit.command.TabCompleter
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerLoadEvent
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

        // 初始化命令（主命令 /kerong）
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

        // 1.6.7.6：自采样刻监视器（每 tick 一次轻量采样）——Folia 多线程核心
        // 上反射链读不到 / 读到的全局区域值忽高忽低，自采样窗口均值是
        // MSPT / TPS 的稳定数据源（非 Folia 核心作反射链的兑底）
        try {
            TickMonitor.start(this)
        } catch (_: Throwable) {
        }

        logCommandExecutor()
        // 1.6.6：PlaceholderAPI 依赖检测（异步延迟 12 秒；1.6.7.3 起仅当
        // 检测到 PlayerTitle 等相关依赖插件时才检测并提示）
        PapiDependencyCheck.scheduleStartupCheck(platform)
        // 1.6.7.4：spark 接入状态提示改到「服务器启动成功之后」
        // （ServerLoadEvent——全部插件 enable 完毕，spark 插件已就位）；
        // 成功提示受 commands.查性能 开关门控，接入失败无论开关必提示，
        // 未安装静默（规则见 SparkStartupCheck KDoc）
        try {
            Bukkit.getPluginManager().registerEvents(object : Listener {
                @EventHandler(priority = EventPriority.MONITOR)
                fun onServerLoad(@Suppress("UNUSED_PARAMETER") event: ServerLoadEvent) {
                    SparkStartupCheck.schedule { probeSpark() }
                }
            }, this)
        } catch (_: Throwable) {
            // ServerLoadEvent 不可用的极端核心：退回 onEnable 后 12 秒旧节奏
            SparkStartupCheck.schedule(12_000L) { probeSpark() }
        }
        log_info("KERONG Penguin 已加载")
    }

    /**
     * spark 可用性探测（1.6.7.4 三态 + 1.6.7.6 第四态 + 1.6.7.7 五态融合）。
     * 本插件名 KERONGPenguin 按字母序先于 spark 加载，onEnable 时 spark 多半
     * 尚未 enable，故仅在 ServerLoadEvent 之后调用。
     *
     * 探测顺序（1.6.7.7 重做，对齐 1.6.7.6 覆盖版设计并强化）：
     * 1. 插件表查 spark（[findSparkPlugin]，大小写不敏感——Bukkit getPlugin
     *    是精确 equals，变体包名会漏检）；
     * 2. 表中查无 → **内置通道**（[SparkBridge.bundledSparkClassLoader] 在
     *    Bukkit API 加载器 / 本类加载器 / 线程上下文加载器里探测）——
     *    Paper 1.21+ 系核心（Paper/Purpur/Folia 1.21 与 26.x）内置 spark、
     *    不注册插件表且默认忽略自装插件版 JAR，这正是用户实测 Purpur 26.2
     *    「装了 spark 仍检测不到」的根因；内置可读时按已接入处理并标记
     *    来源为「服务器核心内置」；
     * 3. 内置也没有但 plugins 目录里有 spark 相关 JAR（[findStraySparkFiles]）
     *    → [ProbeOutcome.NotLoaded]：多为下载到模组版 spark（Modrinth 全是
     *    NeoForge/Fabric/Forge 变体）或核心已内置而忽略插件版，给出排查
     *    方向而不是笼统的「接入失败」；
     * 4. 全部查无 → 未安装（静默）。
     *
     * 1.6.7.6：Folia 下 spark 统计整体不可读属平台现状而非故障，第四态按
     * 信息级提示（内置采样器已兜底），不再误报「接入失败」。
     */
    private fun probeSpark(): SparkStartupCheck.ProbeOutcome = try {
        val sparkPlugin = findSparkPlugin()
        when {
            sparkPlugin != null && !sparkPlugin.isEnabled ->
                SparkStartupCheck.ProbeOutcome.Failed("spark 插件未启用")
            sparkPlugin != null -> {
                val metrics = SparkBridge.read(sparkPlugin.javaClass.classLoader)
                when {
                    metrics != null -> SparkStartupCheck.ProbeOutcome.Installed(metrics, bundled = false)
                    FoliaCompat.isFolia -> SparkStartupCheck.ProbeOutcome.NoStatsOnThisCore(
                        "Folia 多线程核心区域化 ticking，spark 统计暂不可读"
                    )
                    else -> SparkStartupCheck.ProbeOutcome.Failed("各指标均暂不可读")
                }
            }
            else -> {
                // 插件表查无 → 内置 spark 通道（Paper 1.21+ 系核心）
                val bundledLoader = SparkBridge.bundledSparkClassLoader(bukkitApiLoader())
                when {
                    bundledLoader != null -> {
                        val metrics = SparkBridge.read(bundledLoader)
                        when {
                            metrics != null -> SparkStartupCheck.ProbeOutcome.Installed(metrics, bundled = true)
                            FoliaCompat.isFolia -> SparkStartupCheck.ProbeOutcome.NoStatsOnThisCore(
                                "Folia 多线程核心区域化 ticking，spark 统计暂不可读"
                            )
                            else -> SparkStartupCheck.ProbeOutcome.Failed("spark 已随核心内置但各指标均暂不可读")
                        }
                    }
                    else -> {
                        val strayFiles = findStraySparkFiles()
                        if (strayFiles != null) {
                            SparkStartupCheck.ProbeOutcome.NotLoaded(strayFiles)
                        } else {
                            SparkStartupCheck.ProbeOutcome.NotInstalled
                        }
                    }
                }
            }
        }
    } catch (t: Throwable) {
        SparkStartupCheck.ProbeOutcome.Failed(t.message?.takeIf { it.isNotEmpty() } ?: t.toString())
    }

    /**
     * 查 spark 插件（1.6.7.7：大小写不敏感）。
     * Bukkit 的 getPlugin(name) 是区分大小写的精确 equals，第三方分发或
     * 改名变体的插件名大小写有差异时会漏检；先精确匹配（快路径），
     * 再全表忽略大小写扫一遍。
     */
    private fun findSparkPlugin(): org.bukkit.plugin.Plugin? {
        return try {
            Bukkit.getPluginManager().getPlugin("spark")
                ?: Bukkit.getPluginManager().plugins.firstOrNull {
                    it.name.equals("spark", ignoreCase = true)
                }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Bukkit API 类加载器（服务器系统类加载器，1.6.7.7 内置通道首选候选）。
     * Paper 1.21+ 系核心把内置 spark 挂在系统类路径上，Bukkit 接口本身由
     * 该加载器定义——从它的加载器起探测最直接。
     */
    private fun bukkitApiLoader(): ClassLoader? {
        return try {
            Bukkit::class.java.classLoader
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * plugins 目录里的 spark 相关 JAR 文件名列表（1.6.7.7，对齐 1.6.7.6
     * 覆盖版形态）。
     *
     * 过滤：普通文件 + .jar 结尾 + 文件名含 "spark"（均忽略大小写）；
     * 命中文件名 join 成串用于 [SparkStartupCheck.ProbeOutcome.NotLoaded]
     * 提示（展示具体文件让用户一眼认出自己下载的是模组版还是插件版）；
     * 无命中返回 null。
     */
    private fun findStraySparkFiles(): String? {
        return try {
            val pluginsDir = dataFolder.parentFile ?: return null
            val files = pluginsDir.listFiles { file ->
                file.isFile &&
                    file.name.endsWith(".jar", ignoreCase = true) &&
                    file.name.contains("spark", ignoreCase = true)
            } ?: return null
            if (files.isEmpty()) null else files.joinToString(", ") { it.name }
        } catch (_: Throwable) {
            null
        }
    }



    override fun onDisable() {
        // 1.6.4：每一步都独立捕获——停机阶段任何一步失败（含历史遗留的
        // 「zip file closed」类加载错误）都不应中断后续清理与服务器关服。
        // 保存统计数据
        try {
            PlayerStatsManager.shutdown()
        } catch (_: Throwable) {
        }
        // 1.6.7.6：停止自采样刻监视器（清空窗口防下次启动残留）
        try {
            TickMonitor.stop()
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
     * 服务器性能快照（1.6.7，/查性能 数据源；1.6.7.1 修复读不到；1.6.7.6 重排队列）。
     *
     * 采集优先级（逐项独立，单项读不到回退下一层）：
     * 1. spark（装了且可读时）：TPS / MSPT / CPU / 延迟——spark 的窗口均值
     *    天然稳定；
     * 2. Folia 多线程核心：优先自采样器 [TickMonitor]（10 秒窗口均值）——
     *    Paper 系 API 在 Folia 上量的是「全局区域线程」耗时，波动极大
     *    （「数值忽高忽低」根因）且部分版本不再维护（「MSPT 读不到」根因），
     *    反射链降为兜底；
     * 3. 其他核心：反射链优先（Paper / Purpur 的 Server API、Spigot 的
     *    NMS 跳板，见 [cn.kerong.bot.PerformanceSnapshot] 分层读取器），
     *    自采样器兜底；
     * 4. 延迟：spark → 平台玩家 getPing 平均；在线人数：平台玩家表。
     *
     * 读取全部只读数据，无实体状态写操作；Folia 上玩家统计经全局区域线程
     * 采样（[FoliaCompat.callSync]）。
     */
    override fun performanceSnapshot(): cn.kerong.bot.PerformanceSnapshot? {
        val api: org.bukkit.Server = Bukkit.getServer()
        val folia = try {
            FoliaCompat.isFolia
        } catch (_: Throwable) {
            false
        }

        // 1. spark 优先（可用项直接采用；读不到的项回退后续链路）
        var sparkTps: Double? = null
        var sparkMspt: Double? = null
        var sparkCpu: Double? = null
        var sparkPing: Double? = null
        try {
            // 1.6.7.7：插件表（大小写不敏感）查无或未启用时，尝试内置 spark
            // 通道（Paper 1.21+ 系核心内置 spark 不注册插件表）——与启动自检
            // 同口径，装不装插件版都能接入
            val sparkPlugin = findSparkPlugin()
            val loader = if (sparkPlugin != null && sparkPlugin.isEnabled) {
                sparkPlugin.javaClass.classLoader
            } else {
                SparkBridge.bundledSparkClassLoader(bukkitApiLoader())
            }
            if (loader != null) {
                val metrics = SparkBridge.read(loader)
                if (metrics != null) {
                    sparkTps = metrics.tps
                    sparkMspt = metrics.mspt
                    sparkCpu = metrics.cpu
                    sparkPing = metrics.ping
                }
            }
        } catch (_: Throwable) {
            // spark 检测失败不影响后续回退链路
        }

        // 2. MSPT：Folia 优先自采样（稳定），其余核心反射优先、自采样兜底
        val mspt = sparkMspt
            ?: if (folia) {
                TickMonitor.sampledMspt()
                    ?: cn.kerong.bot.PerformanceSnapshot.readMspt(api)
            } else {
                cn.kerong.bot.PerformanceSnapshot.readMspt(api)
                    ?: TickMonitor.sampledMspt()
            }

        // 3. TPS：spark → (Folia: 自采样) → 反射 → MSPT 换算兜底
        val tps = sparkTps
            ?: (if (folia) TickMonitor.sampledTps() else null)
            ?: cn.kerong.bot.PerformanceSnapshot.readTps(api)
            ?: cn.kerong.bot.PerformanceSnapshot.tpsFromMspt(mspt)

        // 4. 延迟与在线人数（Folia 上玩家表读数经全局区域线程采样）
        val playerStats = readPlayerStats(folia)
        val ping = sparkPing ?: playerStats?.second

        return try {
            cn.kerong.bot.PerformanceSnapshot.capture(tps, mspt, sparkCpu, ping, playerStats?.first)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 在线人数与平均延迟（毫秒）采样（1.6.7.6 新增；1.6.7.7 修复延迟暂不可读）。
     *
     * - Folia：玩家集合访问经全局区域线程（[FoliaCompat.callSync]，超时 3 秒
     *   返回 null）；
     * - 其他核心：直接读（与 /查在线 同级安全性）；
     * - getPing 为 Paper 系 API（纯 Spigot 无此方法 → NoSuchMethodError 被
     *   捕获，人数照常返回、延迟为 null）；
     * - 1.6.7.7：延迟为 0 是合法值（本机/同机连接、首次 keep-alive 未完成
     *   都会长时间保持 0），旧版 `ping > 0` 会把 0 延误玩家当作无效跳过
     *   ——单人本地进服时平均延迟永远算不出来，卡片误显示「暂无玩家」；
     *   现改为 `ping >= 0` 计数，-1 仍是读取失败哨兵不入平均；
     * - 无人在线延迟为 null。
     */
    private fun readPlayerStats(folia: Boolean): Pair<Int, Double?>? {
        val direct: () -> Pair<Int, Double?>? = {
            try {
                val players = Bukkit.getOnlinePlayers()
                val count = players.size
                var pingSum = 0.0
                var pingCount = 0
                for (player in players) {
                    val ping = try {
                        player.ping
                    } catch (_: Throwable) {
                        -1
                    }
                    if (ping >= 0) {
                        pingSum += ping
                        pingCount++
                    }
                }
                val avg = if (pingCount > 0) pingSum / pingCount else null
                count to avg
            } catch (_: Throwable) {
                null
            }
        }
        return if (folia) {
            try {
                FoliaCompat.callSync(this, 3L, direct)
            } catch (_: Throwable) {
                null
            }
        } else {
            direct()
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
