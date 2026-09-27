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
 * common-Bot 用到的面：runTask / runTaskLater / runTaskAsynchronously /
 * runTaskTimer / callSyncMethod。
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

    fun runTask(plugin: Plugin, task: Runnable): Int = taskId().also { onServerThread(task) }

    fun runTaskLater(plugin: Plugin, task: Runnable, delay: Long): Int =
        taskId().also {
            timerPool.schedule({ onServerThread(task) }, delay * 50L, TimeUnit.MILLISECONDS)
        }

    fun runTaskTimer(plugin: Plugin, task: Runnable, delay: Long, period: Long): Int =
        taskId().also {
            timerPool.scheduleAtFixedRate({ onServerThread(task) }, delay * 50L, period * 50L, TimeUnit.MILLISECONDS)
        }

    fun runTaskAsynchronously(plugin: Plugin, task: Runnable): Int =
        taskId().also { asyncPool.execute(task) }

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

/** 周期任务句柄（submitTimer 用）。 */
class NeoScheduledHandle(private val future: ScheduledFuture<*>) : cn.huohuas001.bot.tools.Cancelable {
    override fun cancel() {
        future.cancel(false)
    }
}
