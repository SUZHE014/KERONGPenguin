package cn.kerong.penguin.spigot.scheduler

import cn.kerong.bot.tools.Cancelable
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Folia 调度实现（隔离类，1.5.7.3）。
 *
 * ⚠️ 只允许 [FoliaCompat] 在检测到 Folia 后调用——本类直接引用
 * `io.papermc.paper.threadedregions.scheduler` API（paper-api 1.20+ 才有，
 * 且仅 Folia 系服务端有运行时实现）。旧版服务器（如 1.18.2 Spigot）
 * 上加载本类会抛 NoClassDefFoundError；JVM 惰性类加载保证非 Folia
 * 服务器永远不会加载本类（[FoliaCompat] 对本类的全部引用都在
 * `if (isFolia)` 分支的方法体内，字节码上首次执行才解析类引用）。
 *
 * 三类调度器的分工（Folia 官方线程模型）：
 * - GlobalRegionScheduler（全局区域调度器）：无区域归属的全局工作，
 *   等价旧"主线程"语义的安全子集；
 * - AsyncScheduler（异步调度器）：异步任务；
 * - EntityScheduler（实体调度器）：实体状态访问必须在实体所属
 *   区域线程上执行，通过实体自身的调度器提交。
 *
 * 注意：Folia 调度 API 要求延迟与周期参数至少为 1 tick（0 会抛
 * IllegalArgumentException），本类统一 coerceAtLeast(1) 兜底。
 */
internal object FoliaDelegate {

    /** 全局区域调度器（每次调用现取，避免持有跨停机周期的旧实例）。 */
    private val global get() = Bukkit.getGlobalRegionScheduler()

    /** 异步调度器。 */
    private val async get() = Bukkit.getAsyncScheduler()

    fun runSync(plugin: Plugin, task: Runnable): Cancelable {
        val scheduled: ScheduledTask = global.run(plugin) { task.run() }
        return object : Cancelable {
            override fun cancel() {
                scheduled.cancel()
            }
        }
    }

    fun runSyncLater(plugin: Plugin, delayTicks: Long, task: Runnable): Cancelable {
        val scheduled: ScheduledTask =
            global.runDelayed(plugin, { task.run() }, delayTicks.coerceAtLeast(1L))
        return object : Cancelable {
            override fun cancel() {
                scheduled.cancel()
            }
        }
    }

    fun runTimer(plugin: Plugin, delayTicks: Long, periodTicks: Long, task: Runnable): Cancelable {
        val scheduled: ScheduledTask = global.runAtFixedRate(
            plugin,
            { task.run() },
            delayTicks.coerceAtLeast(1L),
            periodTicks.coerceAtLeast(1L),
        )
        return object : Cancelable {
            override fun cancel() {
                scheduled.cancel()
            }
        }
    }

    fun runAsync(plugin: Plugin, task: Runnable): Cancelable {
        val scheduled: ScheduledTask = async.runNow(plugin) { task.run() }
        return object : Cancelable {
            override fun cancel() {
                scheduled.cancel()
            }
        }
    }

    fun <T> callSync(plugin: Plugin, timeoutSec: Long, task: () -> T): T? {
        val future = CompletableFuture<T>()
        try {
            global.run(plugin) { future.complete(task()) }
        } catch (_: Throwable) {
            return null
        }
        return try {
            future.get(timeoutSec, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // 超时 / 中断：返回 null 由调用方走兜底数据（与旧版超时行为同构）
            null
        }
    }

    fun runOnPlayer(plugin: Plugin, player: Player, task: Runnable) {
        try {
            // retired = null：玩家在任务执行前下线时任务作废，无需回调
            player.scheduler.run(plugin, { task.run() }, null)
        } catch (_: Throwable) {
            // 实体已失效等边界情况：丢弃任务（调用方语义为尽力执行）
        }
    }

    fun <T> callOnPlayer(plugin: Plugin, player: Player, timeoutSec: Long, task: () -> T): T? {
        val future = CompletableFuture<T>()
        try {
            // retired 回调：实体失效时完成 null，让限时等待立即返回
            player.scheduler.run(
                plugin,
                { future.complete(task()) },
                { future.complete(null) },
            )
        } catch (_: Throwable) {
            return null
        }
        return try {
            future.get(timeoutSec, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        }
    }
}
