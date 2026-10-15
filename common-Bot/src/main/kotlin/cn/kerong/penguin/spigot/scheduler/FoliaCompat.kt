package cn.kerong.penguin.spigot.scheduler

import cn.kerong.bot.tools.Cancelable
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Folia 多线程区域化核心兼容桥（1.5.7.3）。
 *
 * 背景：Folia 是 Paper 的多线程区域化分支——整个世界被划分为多个区域，
 * 每个区域由独立线程 tick，不再存在单一主线程。传统 BukkitScheduler
 * （runTask / runTaskTimer / callSyncMethod 等）在 Folia 上调用会直接
 * 抛 UnsupportedOperationException；插件还必须在 plugin.yml 中声明
 * `folia-supported: true`，否则 Folia 拒绝加载。
 *
 * 兼容策略（单一 JAR 同时支持 Spigot / Paper / Folia）：
 * 1. 运行时检测：服务端存在 `getGlobalRegionScheduler` 方法即视为 Folia 系
 *    （该方法只在 Folia 及其衍生分支上存在）；
 * 2. 非 Folia 服务器全部走原 BukkitScheduler，行为与旧版完全一致；
 * 3. Folia 专属代码隔离在 [FoliaDelegate] 中——它直接引用
 *    `io.papermc.paper.threadedregions.scheduler` API（paper-api 1.20+ 才有），
 *    非 Folia 服务器（如 1.18.2 运行时）永远不会加载该类，JVM 惰性类加载
 *    保证不会出现 NoClassDefFoundError。
 *
 * 调度语义对照表：
 * | 桥接口              | 非 Folia（原行为）  | Folia 路由              |
 * |---------------------|---------------------|-------------------------|
 * | runSync             | 主线程（可取消）    | 全局区域调度器（可取消）|
 * | runSyncLater        | 主线程延迟（可取消）| 全局区域调度器延迟      |
 * | runTimer            | 主线程周期（可取消）| 全局区域调度器周期      |
 * | runAsync            | 异步线程池（可取消）| 异步调度器              |
 * | callSync            | callSyncMethod      | 全局区域调度器 + Future |
 * | runOnPlayer         | 主线程              | 玩家实体调度器          |
 * | callOnPlayer        | 主线程 + Future     | 玩家实体调度器 + Future |
 * （取消句柄统一返回 [Cancelable]，不需要取消的调用方直接忽略返回值）
 *
 * 线程模型说明（Folia）：
 * - 全局区域线程：与具体区域无关的工作（控制台命令派发、全局广播、
 *   在线列表快照、Vault/称号等第三方经济数据读取）；
 * - 实体调度器（玩家所在区域线程）：实体状态访问（原版统计读取、
 *   踢出玩家等），Folia 要求实体状态只能在其所属区域线程上访问；
 * - 网络消息（玩家 sendMessage / QQ 转发）与磁盘 IO 线程无关。
 */
object FoliaCompat {

    @Volatile
    private var foliaDetected: Boolean? = null

    /** 当前服务端是否为 Folia 系（Folia 及其衍生分支）。检测结果进程内缓存。 */
    val isFolia: Boolean
        get() = foliaDetected ?: detectFolia().also { foliaDetected = it }

    /** Folia 系服务端独有的区域化调度入口方法名。 */
    private const val FOLIA_MARKER_METHOD = "getGlobalRegionScheduler"

    private fun detectFolia(): Boolean = try {
        Bukkit.getServer().javaClass.getMethod(FOLIA_MARKER_METHOD)
        true
    } catch (_: Throwable) {
        // 方法不存在（非 Folia）或服务器异常（按非 Folia 处理走原路径）
        false
    }

    // ---------- 同步调度（原"主线程"语义 → Folia 全局区域线程） ----------

    /** 立即在主线程 / 全局区域线程执行（控制台命令、全局广播等），返回可取消句柄。 */
    fun runSync(plugin: Plugin, task: Runnable): Cancelable {
        return if (isFolia) FoliaDelegate.runSync(plugin, task)
        else BukkitCancelable(plugin.server.scheduler.runTask(plugin, task))
    }

    /** 延迟执行（tick，20 tick = 1 秒；Folia 要求延迟至少 1 tick）。 */
    fun runSyncLater(plugin: Plugin, delayTicks: Long, task: Runnable): Cancelable {
        return if (isFolia) FoliaDelegate.runSyncLater(plugin, delayTicks, task)
        else BukkitCancelable(plugin.server.scheduler.runTaskLater(plugin, task, delayTicks))
    }

    /** 周期任务（tick；Folia 要求周期至少 1 tick）。 */
    fun runTimer(plugin: Plugin, delayTicks: Long, periodTicks: Long, task: Runnable): Cancelable {
        return if (isFolia) FoliaDelegate.runTimer(plugin, delayTicks, periodTicks, task)
        else BukkitCancelable(
            plugin.server.scheduler.runTaskTimer(plugin, task, delayTicks, periodTicks)
        )
    }

    // ---------- 异步调度 ----------

    /** 异步执行（网络 IO、文件 IO 等不触碰实体状态的工作）。 */
    fun runAsync(plugin: Plugin, task: Runnable): Cancelable {
        return if (isFolia) FoliaDelegate.runAsync(plugin, task)
        else BukkitCancelable(plugin.server.scheduler.runTaskAsynchronously(plugin, task))
    }

    // ---------- 主线程取值（原 callSyncMethod 语义 → Folia 全局区域线程） ----------

    /**
     * 切到主线程 / 全局区域线程取值并限时等待。
     * 已在主线程（或 Folia 任一 tick 线程）时直接执行；超时 / 异常返回 null，
     * 与旧版 callSyncMethod().get(timeout) 用法同构。
     *
     * 适用：在线列表快照、经济 / 称号 / 点券等与区域无关的读取。
     * 涉及原版统计（实体状态）的读取请改用 [callOnPlayerByUuid]。
     */
    fun <T> callSync(plugin: Plugin, timeoutSec: Long, task: () -> T): T? {
        if (Bukkit.isPrimaryThread()) return task()
        if (isFolia) return FoliaDelegate.callSync(plugin, timeoutSec, task)
        return try {
            plugin.server.scheduler.callSyncMethod(plugin, task).get(timeoutSec, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 实体调度（玩家实体状态访问：统计读取、踢出等） ----------

    /**
     * 在玩家实体所属线程执行（Folia 的区域线程 / 非 Folia 的主线程）。
     * 典型用途：踢出玩家（kickPlayer）等实体状态写操作。
     * Folia 上玩家在任务执行前已下线时任务自动作废（retired），无需处理。
     */
    fun runOnPlayer(plugin: Plugin, player: Player, task: Runnable) {
        if (isFolia) FoliaDelegate.runOnPlayer(plugin, player, task)
        else runSync(plugin, task)
    }


    /**
     * 按玩家 UUID 在其实体线程取值（FoliaDelegate.callOnPlayer 的 UUID 变体）：
     * 在线时走实体线程，离线时（无实体可调度）退全局区域线程执行——
     * 离线场景任务内部自行兜底（如查不到实体返回已存档数据）。
     */
    fun <T> callOnPlayerByUuid(plugin: Plugin, uuid: UUID, timeoutSec: Long, task: () -> T): T? {
        if (!isFolia) return callSync(plugin, timeoutSec, task)
        val player = try {
            Bukkit.getPlayer(uuid)
        } catch (_: Throwable) {
            null
        } ?: return FoliaDelegate.callSync(plugin, timeoutSec, task)
        return FoliaDelegate.callOnPlayer(plugin, player, timeoutSec, task)
    }

    /** 传统 BukkitTask 的 Cancelable 包装（非 Folia 路径）。 */
    private class BukkitCancelable(val task: BukkitTask) : Cancelable {
        override fun cancel() {
            task.cancel()
        }
    }
}
