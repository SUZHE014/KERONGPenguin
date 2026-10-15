package cn.huohuas001.huhobotPenguin.spigot.render

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
 * 不会触发拒绝），多个命令（/查信息、/查在线 等，查信息 1.5.2 前为 /个人信息）共用本池，
 * 全部图片渲染合计占用不超过上述硬编码上限。
 *
 * 1.6.5：移除「渲染后 System.gc() 提示」（原 render.gc-after-render 开关一并废除）。
 * - System.gc 在默认 JVM 参数下触发一次 Full GC（全停顿标记-压缩，几十到几百毫秒），
 *   在 20 TPS 的游戏服务器上等于凭空丢若干 tick——是渲染链路里最大的自伤性能点；
 * - 内存释放改为**确定性 flush**：单次画布（各渲染器 render() 末尾的 image.flush()）、
 *   中间图（皮肤贴图 / 头像缩放 / cover 裁剪）、缓存淘汰条目
 *   （见 InfoCardAssets 的 evict / clear 路径）都在失去引用前 flush，
 *   栅格与绘制缓存即时归还，剩余少量堆内存由 JVM 常规新生代 GC 秒级回收，
 *   不再需要全停顿的显式 GC 兜底。
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

    /**
     * 提交一次图片渲染任务。
     * 任务的异常由调用方自行捕获（渲染失败已有日志与文本回退提示）。
     */
    fun submit(task: Runnable) {
        pool().execute(task)
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
}
