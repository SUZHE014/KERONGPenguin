package cn.huohuas001.huhobotPenguin.neoforge

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.provider.AdminMode
import cn.huohuas001.bot.provider.ChatFormat
import cn.huohuas001.bot.provider.CustomCommandDetail
import cn.huohuas001.bot.provider.HExecution
import cn.huohuas001.bot.provider.Motd
import cn.huohuas001.bot.provider.PlayerEventFormat
import cn.huohuas001.bot.provider.WhiteList
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.bot.tools.PluginFileLog
import cn.huohuas001.huhobotPenguin.spigot.manager.ConfigManager
import cn.huohuas001.huhobotPenguin.spigot.render.InfoCardAssets
import cn.huohuas001.huhobotPenguin.spigot.stats.PlayerStatsManager
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
 * 与 Spigot 版（HuHoBotSpigot）同源：共用 common-Bot 全部业务
 * （QQ 机器人 / 命令系统 / 绑定 / 签到 / 统计 / 信息卡渲染 / Web 面板），
 * 平台差异经 org.bukkit 兼容层桥接：
 * - 主线程 = 服务器线程（MinecraftServer#execute）；
 * - 玩家查询 / 统计 / 皮肤 / playerdata 定位 → PlayerList / StatsCounter /
 *   GameProfile 档案 / 世界存档目录；
 * - Vault / DeluxeTags / PlayerPoints / PlaceholderAPI 不存在 → 相关功能
 *   自动降级（卡片隐藏金币称号点券项，签到走记账模式）。
 */
@Mod("kerongpenguin")
class HuHoBotNeoForge : JavaPlugin(), HuHoBot {

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
        modLogger.info("KERONGPenguin NeoForge 已加载（${pluginVersion} / MC ${serverVersion}）")
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

    /** 服务器完全停止（ServerStoppedEvent）：解除引用。 */
    internal fun onServerStopped() {
        NeoServerRef.unbind()
        PluginManager.unregister()
        timerPool.shutdownNow()
        asyncPool.shutdownNow()
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

    override fun whiteList(): WhiteList = configManager.whiteList()

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

    // ---------- Web 面板配置 ----------

    override fun webUiConfigValues(): Map<String, Any> = mapOf(
        "platform" to platform,
        "version" to pluginVersion,
    )

    override fun applyWebUiConfigChanges(changes: com.alibaba.fastjson.JSONObject): Boolean = false

    // ---------- 日志 ----------

    override fun log_info(msg: String) {
        modLogger.info(msg)
    }

    override fun log_warning(msg: String) {
        modLogger.warn(msg)
        PluginFileLog.write("[警告] $msg")
    }

    override fun log_error(msg: String) {
        modLogger.error(msg)
        PluginFileLog.write("[错误] $msg")
    }

    companion object {
        /** 数据目录：游戏目录下 kerongpenguin/（与 mods 同级，避开世界目录）。 */
        private val MOD_DATA_DIR: File = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
            .resolve("kerongpenguin").toFile()

        /** 模组版本（与 neoforge.mods.toml 一致）。 */
        private const val MOD_VERSION = "1.5.4"

        /** 当前模组实例（命令与事件层访问）。 */
        @Volatile
        internal var instance: HuHoBotNeoForge? = null
            private set
    }
}
