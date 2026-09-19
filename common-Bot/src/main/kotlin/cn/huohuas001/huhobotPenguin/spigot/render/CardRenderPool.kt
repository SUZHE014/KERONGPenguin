package cn.huohuas001.huhobotPenguin.spigot.render

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 卡片渲染共享线程池（1.5.0 覆盖更新：CPU 上限硬编码，不走配置文件）。
 *
 * 设计取舍（两轮反馈的折中）：
 * - 单线程限流会导致多张图片排队堆积、出图变慢反而卡服 → 不能只有 1 线程；
 * - 完全不限核在高并发查询时占满 CPU → 需要上限。
 * 故硬编码为：**主机 CPU 核数的一半，至少 1 线程、至多 4 线程**
 * （例：8 核服务器 4 线程、4 核 2 线程、2 核 1 线程），不提供配置项。
 *
 * 线程为守护线程（不阻止 JVM 退出），空闲 60 秒自动回收（无渲染时不占资源）；
 * 队列不设上限（配合各命令的查询冷却与结果缓存，任务量天然有界，
 * 不会触发拒绝），多个命令（/个人信息、/查在线 等）共用本池，
 * 全部图片渲染合计占用不超过上述硬编码上限。
 *
 * 任务完成后做节流 GC 提示（两次至少间隔 [GC_MIN_INTERVAL_MILLIS]），
 * 避免每次渲染都 Full GC 造成停顿卡服；qq-bind.render.gc-after-render 可关。
 */
object CardRenderPool {

    /** 硬编码渲染线程上限：主机核数一半，夹在 1..4（配置文件不暴露）。 */
    internal val MAX_THREADS: Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

    /** 渲染队列（不设上限：任务量受查询冷却与结果缓存约束）。 */
    private val queue = LinkedBlockingQueue<Runnable>()

    /** 渲染线程池（惰性创建）。 */
    @Volatile
    private var pool: ThreadPoolExecutor? = null

    /** 渲染后 GC 节流：两次 GC 提示的最小间隔（防止频繁 Full GC 停顿卡服）。 */
    private const val GC_MIN_INTERVAL_MILLIS = 60_000L

    /** 上次渲染后 GC 提示时间戳。 */
    @Volatile
    private var lastRenderGcAt = 0L

    /**
     * 提交一次图片渲染任务：任务执行完成后自动做节流 GC 提示。
     * 任务的异常由调用方自行捕获（渲染失败已有日志与文本回退提示）。
     */
    fun submit(task: Runnable) {
        pool().execute {
            try {
                task.run()
            } finally {
                gcAfterRenderIfDue()
            }
        }
    }

    /** 渲染线程池（固定 [MAX_THREADS] 线程，守护线程，空闲 60 秒回收）。 */
    private fun pool(): ThreadPoolExecutor {
        pool?.let { return it }
        synchronized(this) {
            pool?.let { return it }
            val created = ThreadPoolExecutor(
                MAX_THREADS, MAX_THREADS,
                60L, TimeUnit.SECONDS,
                queue,
            ) { runnable ->
                Thread(runnable, "PenguinCardRender").apply {
                    isDaemon = true
                }
            }
            created.allowCoreThreadTimeOut(true)
            pool = created
            return created
        }
    }

    /**
     * 渲染完成后释放 JVM 内存：
     * - 单次渲染图像已在渲染器内 flush 释放栅格；
     * - 这里补一次节流的 GC 提示（System.gc，JVM 可能忽略，取决于启动参数），
     *   两次至少间隔 [GC_MIN_INTERVAL_MILLIS]，避免每次渲染都 Full GC 造成停顿卡服；
     * - qq-bind.render.gc-after-render 关闭后完全不触发。
     */
    private fun gcAfterRenderIfDue() {
        if (!gcAfterRenderEnabled()) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastRenderGcAt < GC_MIN_INTERVAL_MILLIS) return
            lastRenderGcAt = now
        }
        try {
            System.gc()
        } catch (_: Throwable) {
        }
    }

    /** qq-bind.render.gc-after-render：渲染后是否做 GC 提示（默认 true）。 */
    private fun gcAfterRenderEnabled(): Boolean = try {
        (Bukkit.getPluginManager().getPlugin("KERONGPenguin") as? JavaPlugin)
            ?.config?.getBoolean("qq-bind.render.gc-after-render", true) ?: true
    } catch (_: Throwable) {
        true
    }
}
