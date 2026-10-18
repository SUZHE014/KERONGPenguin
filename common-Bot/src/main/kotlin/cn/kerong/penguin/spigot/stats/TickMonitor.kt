package cn.kerong.penguin.spigot.stats

import cn.kerong.bot.tools.Cancelable
import cn.kerong.penguin.spigot.scheduler.FoliaCompat
import org.bukkit.plugin.Plugin

/**
 * 自采样服务器刻耗时监视器（1.6.7.6 新增，Folia 多线程核心修复）。
 *
 * ## 为什么需要它
 *
 * Folia 把单一主线程 tick 拆成多个区域线程并行 tick，**不存在全局统一的
 * tick 计时**：
 * - Paper 系 API 的 `getAverageTickTime()` / `tickTimesNanos` 在 Folia 上
 *   量的是「全局区域线程」的耗时——全局区域负责随机刻 / 天气 / 时间等，
 *   波动极大，读出来的 MSPT 忽高忽低（用户反馈「多线程核心一些项
 *   一下子特别高一下子特别低」正是它）；部分 Folia 版本这些字段甚至
 *   不再维护，MSPT 直接读不到（用户反馈「Folia MSPT 获取失败」）；
 * - TPS 由瞬时 MSPT 换算同样跟着跳。
 *
 * ## 方案：像 spark 一样自己数 tick
 *
 * 在「主线程 / Folia 全局区域调度器」上挂一个**每 tick 一次**的轻量任务
 * （一次 nanoTime 读取 + 环形缓冲写入，开销可忽略），度量相邻两次执行
 * 的墙钟间隔 = 实际 tick 耗时：
 * - MSPT = 最近 200 tick（约 10 秒）的窗口均值——窗口平均天然平滑，
 *   不会忽高忽低；
 * - TPS = 最近 1200 tick（约 60 秒）的窗口均值换算（min(20, 1000/mspt)），
 *   与 MinecraftServer 自身 1 分钟 TPS 口径一致；
 * - 非 Folia 核心上 Paper 反射链读数权威，本监视器仅作兜底；
 *   Folia 上反过来：本监视器优先（反射读数在 Folia 上不可信）。
 *
 * ## 线程与生命周期
 *
 * - 写入方：主线程（非 Folia）或全局区域线程（Folia），经 [FoliaCompat.runTimer]
 *   周期 1 tick，双核心统一；
 * - 读取方：CardRenderPool 渲染线程（/查性能 快照采集）——synchronized
 *   小临界区，无锁竞争压力；
 * - [start] 幂等；插件停服时任务随调度器取消（[stop] 供显式清理）；
 * - 样本不足（刚启动 < 40 tick）返回 null，卡片显示「--」直到预热完成；
 * - 单次间隔超过 10 秒的样本视为调度异常（GC 长停顿 / 线程池堆积 /
 *   tick 卡死恢复），丢弃不进窗口，防止一次卡顿把窗口均值拉爆。
 */
object TickMonitor {

    /** 环形缓冲长度（1200 tick ≈ 60 秒；MSPT 取其中最近 200 tick）。 */
    private const val WINDOW_TICKS = 1200

    /** MSPT 窗口（最近 N tick）。 */
    private const val MSPT_WINDOW_TICKS = 200

    /** 返回有效读数所需的最小样本数（约 2 秒预热）。 */
    private const val MIN_SAMPLES = 40

    /** 异常间隔阈值：单次 tick 间隔超过 10 秒不进窗口。 */
    private const val GAP_DISCARD_NANOS = 10_000_000_000L

    /** 读写锁（写 = tick 任务 1 次赋值；读 = 快照采集）。 */
    private val lock = Any()

    /** tick 耗时环形缓冲（纳秒）。 */
    private val durationsNanos = LongArray(WINDOW_TICKS)

    /** 已写入样本总数（含已淘汰，用于判断预热与求均值）。 */
    private var samples = 0

    /** 下一个写入位置。 */
    private var writeIndex = 0

    /** 上次执行时间戳（0 = 尚未跑第一次）。 */
    private var lastNanos = 0L

    /** 周期任务句柄（幂等启动 / 停止）。 */
    private var task: Cancelable? = null

    /**
     * 启动监视（幂等）。由 Spigot 平台主类 onEnable 调用；
     * 非 Folia 核心上同样安全运行（作为反射链的兜底数据源）。
     */
    fun start(plugin: Plugin) {
        synchronized(lock) {
            if (task != null) return
        }
        try {
            val handle = FoliaCompat.runTimer(plugin, 1L, 1L, Runnable { onTick() })
            synchronized(lock) {
                if (task != null) {
                    // 并发启动兜底：后到者直接取消（runTimer 已注册）
                    handle.cancel()
                    return
                }
                task = handle
                lastNanos = 0L
            }
        } catch (_: Throwable) {
            // 调度器异常（极端核心）不影响其余功能，/查性能 走反射链
        }
    }

    /** 停止并清零（插件停服调用；数据清空防止下次启动残留旧窗口）。 */
    fun stop() {
        val handle: Cancelable?
        synchronized(lock) {
            handle = task
            task = null
            resetLocked()
        }
        try {
            handle?.cancel()
        } catch (_: Throwable) {
        }
    }

    /** 采样计数（诊断用）。 */
    fun sampleCount(): Int = synchronized(lock) { samples }

    /**
     * 自采样 MSPT（最近 200 tick 窗口均值，毫秒）。
     * 未启动 / 样本不足返回 null。
     */
    fun sampledMspt(): Double? = averageMillis(MSPT_WINDOW_TICKS)

    /**
     * 自采样 TPS（最近 1200 tick 窗口均值换算，满值 20）。
     * 未启动 / 样本不足返回 null。
     */
    fun sampledTps(): Double? {
        val mspt = averageMillis(WINDOW_TICKS) ?: return null
        return minOf(20.0, 1000.0 / mspt)
    }

    // ---------- 内部 ----------

    private fun onTick() {
        val now = System.nanoTime()
        synchronized(lock) {
            val last = lastNanos
            lastNanos = now
            if (last == 0L) return // 第一次执行只记时间戳
            val delta = now - last
            if (delta <= 0L || delta > GAP_DISCARD_NANOS) return // 异常间隔丢弃
            durationsNanos[writeIndex] = delta
            writeIndex = (writeIndex + 1) % WINDOW_TICKS
            samples++
        }
    }

    /** 最近 [ticks] 个样本的窗口均值（毫秒）。 */
    private fun averageMillis(ticks: Int): Double? = synchronized(lock) {
        val count = samples
        if (count < MIN_SAMPLES) return null
        val window = minOf(ticks, count, WINDOW_TICKS)
        var sum = 0.0
        // 从最新样本往回累加（写位置的前一个是最新）
        var index = (writeIndex - 1 + WINDOW_TICKS) % WINDOW_TICKS
        repeat(window) {
            sum += durationsNanos[index].toDouble()
            index = (index - 1 + WINDOW_TICKS) % WINDOW_TICKS
        }
        (sum / window) / 1_000_000.0
    }

    private fun resetLocked() {
        durationsNanos.fill(0L)
        samples = 0
        writeIndex = 0
        lastNanos = 0L
    }
}
