package org.bukkit.scheduler

import cn.huohuas001.huhobotPenguin.neoforge.NeoServerRef
import org.bukkit.plugin.Plugin
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 调度器（兼容层）：主线程任务经 MinecraftServer#execute（服务器线程），
 * 延迟/周期任务按 1 tick = 50ms 换算后投递回服务器线程执行。
 *
 * common-Bot 用到的面：runTask / runTaskLater / runTaskAsynchronous /
 * runTaskTimer / callSyncMethod（1.6.0 起返回 BukkitTask 句柄，
 * 与 Spigot 签名对齐供 FoliaCompat 非 Folia 路径包装取消）。
 */
object BukkitScheduler {

    private val asyncPool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "KERONGPenguin-Async").apply { isDaemon = true }
    }

    private val timerPool: ScheduledExecutorService = Executors.newScheduledThreadPool(1) { runnable ->
        Thread(runnable, "KERONGPenguin-Timer").apply { isDaemon = true }
    }

    /** 服务器线程执行（服务器未就绪时退化为线程池执行，不丢任务）。 */
    private fun onServerThread(task: Runnable) {
        val server = NeoServerRef.server
        if (server != null) {
            server.execute(task)
        } else {
            asyncPool.execute(task)
        }
    }

    fun runTask(plugin: Plugin, task: Runnable): BukkitTask =
        NeoBukkitTask(null, taskId()).also { onServerThread(task) }

    fun runTaskLater(plugin: Plugin, task: Runnable, delay: Long): BukkitTask =
        NeoBukkitTask(
            timerPool.schedule({ onServerThread(task) }, delay * 50L, TimeUnit.MILLISECONDS),
            taskId(),
        )

    fun runTaskTimer(plugin: Plugin, task: Runnable, delay: Long, period: Long): BukkitTask =
        NeoBukkitTask(
            timerPool.scheduleAtFixedRate({ onServerThread(task) }, delay * 50L, period * 50L, TimeUnit.MILLISECONDS),
            taskId(),
        )

    fun runTaskAsynchronously(plugin: Plugin, task: Runnable): BukkitTask =
        NeoBukkitTask(asyncPool.submit(task), taskId())

    /** 主线程同步执行并返回结果（服务器未就绪时直接在调用线程执行）。 */
    fun <T> callSyncMethod(plugin: Plugin, task: Callable<T>): Future<T> {
        val server = NeoServerRef.server
            ?: return CompletableFuture.completedFuture(task.call())
        val future = FutureTask(task)
        server.execute(future)
        return future
    }

    /** 取消全部任务（服务器停止时调用）。 */
    fun cancelTasks(plugin: Plugin) {
        // 任务由插件自身的 Cancelable / 生命周期管理，这里无需全局取消
    }

    private fun taskId(): Int = nextId.incrementAndGet()

    private val nextId = java.util.concurrent.atomic.AtomicInteger(0)
}


/** 调度任务句柄（对齐 Spigot 的 BukkitTask；立即执行的任务无 future，取消为空操作）。 */
interface BukkitTask {
    fun cancel()
    fun getTaskId(): Int
}

/** BukkitTask 兼容层实现：可取消的 future 包装。 */
private class NeoBukkitTask(
    private val future: java.util.concurrent.Future<*>?,
    private val id: Int,
) : BukkitTask {
    override fun cancel() {
        future?.cancel(false)
    }

    override fun getTaskId(): Int = id
}
