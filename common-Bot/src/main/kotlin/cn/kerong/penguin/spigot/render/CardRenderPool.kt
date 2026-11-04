package cn.kerong.penguin.spigot.render

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 卡片渲染共享线程池。
 *
 * 设计取舍（两轮反馈的折中）：
 * - 单线程限流会导致多张图片排队堆积、出图变慢反而卡服 → 不能只有 1 线程；
 * - 完全不限核在高并发查询时占满 CPU → 需要上限。
 * 故默认为：**主机 CPU 核数的一半，至少 1 线程、至多 4 线程**
 * （例：8 核服务器 4 线程、4 核 2 线程、2 核 1 线程）。
 *
 * 1.7.3 覆盖版：新增「自定义图片渲染线程」配置（render.custom-render-threads
 * 段）：开关开启后线程数改用 render.custom-render-threads.render-threads
 * （1~16）。⚠ 线程数越大同时渲染的图片越多、CPU 争抢越强，核数少的
 * 服务器上可能影响游戏 tick——不建议超过物理核数的一半。线程池在首次
 * 渲染时创建，修改配置需重启服务器生效。
 *
 * 线程为守护线程（不阻止 JVM 退出），空闲 60 秒自动回收（无渲染时不占资源）；
 * 队列不设上限（配合各命令的查询冷却与结果缓存，任务量天然有界，
 * 不会触发拒绝），多个命令（/查信息、/查在线 等）共用本池，
 * 全部图片渲染合计占用不超过上述上限。
 *
 * 1.6.5：移除「渲染后 System.gc() 提示」（原 render.gc-after-render 开关一并废除）。
 * - System.gc 在默认 JVM 参数下触发一次 Full GC（全停顿标记-压缩，几十到几百毫秒），
 *   在 20 TPS 的游戏服务器上等于凭空丢若干 tick——是渲染链路里最大的自伤性能点；
 * - 内存释放改为**确定性 flush**：单次画布（各渲染器 render() 末尾的 image.flush()）、
 *   中间图（皮肤贴图 / 头像缩放 / cover 裁剪）、缓存淘汰条目
 *   （见 InfoCardAssets 的 evict / clear 路径）都在失去引用前 flush，
 *   栅格与绘制缓存即时归还，剩余少量堆内存由 JVM 常规新生代 GC 秒级回收，
 *   不再需要全停顿的显式 GC 兜底。
 *
 * 1.6.6.2：任务收尾时刷新渲染完成时间戳（InfoCardAssets.markRenderCompleted，
 * 空闲释放的判断基准）并维护活跃任务计数（activeRenders，空闲释放与
 * 过期清扫仅在无渲染进行中时执行，避免 flush 正在画布上的图像）。
 */
object CardRenderPool {

    /** 自动模式渲染线程上限：主机核数一半，夹在 1..4。 */
    internal val MAX_THREADS: Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

    /** 实际生效的渲染线程数（自定义开关开启时用配置值，否则自动值；池创建时定格）。 */
    internal val THREADS: Int =
        RenderSettings.customRenderThreads().takeIf { it > 0 } ?: MAX_THREADS

    /** 渲染队列（不设上限：任务量受查询冷却与结果缓存约束）。 */
    private val queue = LinkedBlockingQueue<Runnable>()

    /** 渲染线程池（惰性创建）。 */
    @Volatile
    private var pool: ThreadPoolExecutor? = null

    /** 进行中的渲染任务数（1.6.6.2：空闲释放 / 过期清扫的安全护栏）。 */
    private val activeRenders = AtomicInteger()

    /** 当前进行中的渲染任务数（0 = 空闲）。 */
    fun activeRenders(): Int = activeRenders.get()

    /**
     * 提交一次图片渲染任务。
     * 任务的异常由调用方自行捕获（渲染失败已有日志与文本回退提示）；
     * 1.6.6.2：任务真正开始执行时计入活跃数，收尾（含异常收尾）时刷新
     * 渲染完成时间戳——「渲染完图片 N 分钟内无下次渲染即释放内存」的基准。
     */
    fun submit(task: Runnable) {
        pool().execute {
            activeRenders.incrementAndGet()
            try {
                task.run()
            } finally {
                activeRenders.decrementAndGet()
                try {
                    InfoCardAssets.markRenderCompleted()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** 渲染线程池（固定 [THREADS] 线程，守护线程，空闲 60 秒回收）。 */
    private fun pool(): ThreadPoolExecutor {
        pool?.let { return it }
        synchronized(this) {
            pool?.let { return it }
            val created = ThreadPoolExecutor(
                THREADS, THREADS,
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
}
