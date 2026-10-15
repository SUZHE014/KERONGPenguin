package cn.huohuas001.huhobotPenguin.spigot.stats

import cn.huohuas001.bot.provider.plugin
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.bot.tools.PluginFileLog
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * PlayTimeManager 在线时长同步（1.6.4 新增）。
 *
 * ## 需求
 *
 * 检测到服务器安装了 PlayTimeManager（modrinth.com/plugin/playtimemanager，
 * 以下简称 PTM）时，把本插件统计的玩家在线时长（QUUID stats.play-seconds，
 * /查信息 卡片与 /在线排行榜 的数据源）同步给 PTM，让两边展示的时长一致。
 *
 * ## PTM 的数据模型（决定同步方式）
 *
 * PTM 的玩家总时长 = 实际时长（它自己按在线会话累计）+ 人工时长
 * （`/playtime <名> add|remove <时长>` 修正的部分）。因此同步采用
 * **差值对齐**而不是每次全量搬运：
 * - 读取 PTM 当前总时长：PlaceholderAPI 变量 `%PTM_playtime_s_<玩家名>%`
 *   （官方注册的秒数形式变量，见 PTM Wiki「Playtime-related-placeholders」）；
 * - 差值 = 本插件时长 - PTM 时长；仅当 |差值| ≥ [SYNC_THRESHOLD_SECONDS]
 *   时经控制台执行 `/playtime <名> add|remove <差值>s` 修正；
 * - 对齐之后两边各自继续累计（两者口径一致，速率相同），偏差只会来自
 *   历史数据或单侧重置，无需逐分钟追赶。
 *
 * ## 触发时机
 *
 * - 玩家退出（[PlayerStatsManager.onQuit] 结算完总时长后）；
 * - 周期兜底（每 [PERIOD_TICKS] 对在线玩家对齐一轮，每轮最多
 *   [PERIOD_MAX_PLAYERS] 人，服务器空闲开销可忽略）。
 *
 * ## 防抖与安全
 *
 * - 单玩家 [PLAYER_COOLDOWN_MS] 冷却（10 分钟内不重复修正，防 AFK 排除
 *   配置等口径差异导致的反复小修）；
 * - 阈值 [SYNC_THRESHOLD_SECONDS] 以下不动（两侧秒级偏差属正常抖动）；
 * - PAPI 变量解析与命令派发全部在主线程一次性完成（与查信息卡片的
 *   数据采集同模式；Folia 经 [FoliaCompat] 桥接）；
 * - PTM / PlaceholderAPI 未安装或任一步骤失败时静默跳过（文件日志留档），
 *   绝不影响玩家退出与统计落账；NeoForge 端 Bukkit 生态不存在，本对象
 *   自动不启用（[available] 恒 false）。
 */
object PlayTimeSync {

    /** 差值达到该秒数才修正（更小的偏差视为正常抖动）。 */
    private const val SYNC_THRESHOLD_SECONDS = 300L

    /** 单玩家修正冷却（毫秒）。 */
    private const val PLAYER_COOLDOWN_MS = 10L * 60_000L

    /** 周期对齐任务间隔（tick，30 分钟）。 */
    private const val PERIOD_TICKS = 20L * 60 * 30

    /** 每轮周期对齐最多处理的玩家数（分摊主线程开销）。 */
    private const val PERIOD_MAX_PLAYERS = 10

    /** 玩家名 → 最近一次修正时间（防抖）。 */
    private val lastSyncAt = ConcurrentHashMap<String, Long>()

    /** 周期对齐任务句柄。 */
    @Volatile
    private var periodTask: Cancelable? = null

    /** 是否已输出过「检测到 PTM，同步已开启」提示（每次启用只提示一次）。 */
    @Volatile
    private var announced = false

    /** PTM 与 PlaceholderAPI 是否均已安装并启用（缺任一返回 false，同步整体关闭）。 */
    fun available(): Boolean {
        val ptm = try {
            Bukkit.getPluginManager().getPlugin("PlayTimeManager")
        } catch (_: Throwable) {
            null
        } ?: return false
        val papi = try {
            Bukkit.getPluginManager().getPlugin("PlaceholderAPI")
        } catch (_: Throwable) {
            null
        } ?: return false
        return try {
            ptm.isEnabled && papi.isEnabled
        } catch (_: Throwable) {
            false
        }
    }

    /** 插件启用时调用：输出一次性提示并启动周期对齐任务。 */
    fun initialize() {
        if (!available()) {
            PluginFileLog.write("[时长同步] 未检测到 PlayTimeManager（或未装 PlaceholderAPI），在线时长同步未开启")
            return
        }
        if (!announced) {
            announced = true
            PluginFileLog.infoAndKeep("[时长同步] 检测到 PlayTimeManager：本插件统计的在线时长将与其自动对齐（玩家退出时 + 每 30 分钟兜底）")
        }
        if (periodTask != null) return
        periodTask = plugin.submitTimer(PERIOD_TICKS, PERIOD_TICKS) {
            try {
                syncOnlinePlayers()
            } catch (_: Throwable) {
            }
        }
    }

    /** 插件停用时调用：取消周期任务。 */
    fun shutdown() {
        periodTask?.cancel()
        periodTask = null
        lastSyncAt.clear()
    }

    /**
     * 玩家退出时调度一次同步（[ourSeconds] 为本插件结算后的总在线秒数）。
     * 在主线程执行一次轻量对齐（PAPI 读取 + 控制台命令派发）。
     */
    fun scheduleSync(playerName: String, ourSeconds: Long) {
        if (!available()) return
        if (playerName.isBlank()) return
        val now = System.currentTimeMillis()
        val last = lastSyncAt[playerName] ?: 0L
        if (now - last < PLAYER_COOLDOWN_MS) return
        try {
            syncOnMainThread(playerName, ourSeconds)
        } catch (t: Throwable) {
            PluginFileLog.write("[时长同步] ${t.javaClass.simpleName}: ${t.message ?: "无消息"}")
        }
    }

    /** 周期兜底：对在线玩家逐个对齐（最多 [PERIOD_MAX_PLAYERS] 人）。 */
    private fun syncOnlinePlayers() {
        if (!available()) return
        val online = try {
            Bukkit.getOnlinePlayers().map { it.name }
        } catch (_: Throwable) {
            emptyList()
        }
        if (online.isEmpty()) return
        var processed = 0
        for (name in online) {
            if (processed >= PERIOD_MAX_PLAYERS) break
            val uuid = try {
                Bukkit.getPlayerExact(name)?.uniqueId
            } catch (_: Throwable) {
                null
            } ?: continue
            processed++
            val stats = PlayerStatsManager.query(uuid)
            try {
                syncOnMainThread(name, stats.playSeconds)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 单玩家对齐（主线程）：读 PTM 秒数 → 差值 → 控制台命令修正。
     * 非主线程调用时经 FoliaCompat 跳到主线程（与查信息采集同模式）。
     */
    private fun syncOnMainThread(playerName: String, ourSeconds: Long) {
        val bukkitPlugin = try {
            Bukkit.getPluginManager().getPlugin("KERONGPenguin")
        } catch (_: Throwable) {
            null
        }
        val task: () -> Unit = { align(playerName, ourSeconds) }
        if (Bukkit.isPrimaryThread()) {
            task()
            return
        }
        if (bukkitPlugin == null) return
        try {
            cn.huohuas001.huhobotPenguin.spigot.scheduler.FoliaCompat.callSync(bukkitPlugin, 3L) { task() }
        } catch (_: Throwable) {
        }
    }

    /** 实际对齐逻辑（必须在主线程执行）。 */
    private fun align(playerName: String, ourSeconds: Long) {
        val ptmSeconds = readPtmSeconds(playerName) ?: return
        val delta = ourSeconds - ptmSeconds
        if (kotlin.math.abs(delta) < SYNC_THRESHOLD_SECONDS) return
        val now = System.currentTimeMillis()
        if (now - (lastSyncAt[playerName] ?: 0L) < PLAYER_COOLDOWN_MS) return
        lastSyncAt[playerName] = now
        val action = if (delta > 0) "add" else "remove"
        val amount = kotlin.math.abs(delta)
        val command = "playtime $playerName $action ${amount}s"
        PluginFileLog.write(
            "[时长同步] $playerName：本插件 ${ourSeconds}s / PlayTimeManager ${ptmSeconds}s，" +
                "差值 $delta 秒 → 执行 /$command"
        )
        try {
            plugin.dispatchCommand(command)
        } catch (t: Throwable) {
            PluginFileLog.write("[时长同步] 命令派发失败: ${t.message ?: "无消息"}")
        }
    }

    /**
     * 读取 PTM 记录的玩家总时长（秒）。
     * 经 PlaceholderAPI 解析 `%PTM_playtime_s_<名>%`（秒数形式，官方注册变量），
     * 返回值剥除非数字字符后解析；未解析 / 非数字 / 异常一律返回 null。
     */
    private fun readPtmSeconds(playerName: String): Long? {
        return try {
            val papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") ?: return null
            val papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI", false, papi.javaClass.classLoader)
            val method = papiClass.methods.firstOrNull {
                it.name == "setPlaceholders" && it.parameterCount == 2 &&
                    OfflinePlayer::class.java == it.parameterTypes[0] && String::class.java == it.parameterTypes[1]
            } ?: return null
            val offlinePlayer = Bukkit.getOfflinePlayer(playerName)
            val resolved = method.invoke(null, offlinePlayer, "%PTM_playtime_s_$playerName%") as? String
            val digits = resolved?.filter { it.isDigit() } ?: return null
            if (digits.isEmpty()) null else digits.toLongOrNull()
        } catch (_: Throwable) {
            null
        }
    }
}
