package io.papermc.paper.threadedregions.scheduler

import org.bukkit.plugin.Plugin

/**
 * Folia 区域化调度 API 兼容层桩（NeoForge 编译期专用，1.6.0）。
 *
 * 背景：common-Bot 的 FoliaDelegate 直接引用本包 API（paper-api 1.20+ 才有，
 * 仅 Folia 系服务端提供运行时实现）。Spigot 线编译期由 paper-api 依赖提供；
 * NeoForge 线需要本桩让同一份源码编译通过。
 *
 * ⚠️ 运行时约定：NeoForge 永远不是 Folia（FoliaCompat.detectFolia 通过
 * 反射查找 getGlobalRegionScheduler 方法判定，NeoForge 服务器上该方法不存在
 * → 恒走非 Folia 路径），FoliaDelegate 及本包类型在 NeoForge 运行时
 * 永远不会被加载/调用。桩方法签名以 Kotlin 函数类型声明（与 paper-api 的
 * Java SAM 接口在调用点语法上等价，FoliaDelegate 源码两边通用）。
 */

/** Folia 调度任务句柄（真实 Folia 上可取消 / 查询执行状态）。 */
interface ScheduledTask {
    fun cancel()
    fun cancel(boolean: Boolean)
    fun getExecutionState(): Int
}

/** 全局区域调度器（等价旧"主线程"语义的安全子集）。 */
interface GlobalRegionScheduler {
    fun run(plugin: Plugin, task: () -> Unit): ScheduledTask
    fun runDelayed(plugin: Plugin, task: () -> Unit, delayTicks: Long): ScheduledTask
    fun runAtFixedRate(plugin: Plugin, task: () -> Unit, initialDelayTicks: Long, periodTicks: Long): ScheduledTask
    fun execute(plugin: Plugin, task: () -> Unit)
}

/** 异步调度器（网络 / 文件 IO 等不触碰实体状态的工作）。 */
interface AsyncScheduler {
    fun runNow(plugin: Plugin, task: () -> Unit): ScheduledTask
    fun runDelayed(plugin: Plugin, task: () -> Unit, delayTicks: Long): ScheduledTask
    fun runAtFixedRate(plugin: Plugin, task: () -> Unit, initialDelayTicks: Long, periodTicks: Long): ScheduledTask
}

/** 实体调度器（实体状态访问必须在实体所属区域线程上执行）。 */
interface EntityScheduler {
    fun run(plugin: Plugin, task: () -> Unit, retired: (() -> Unit)?): ScheduledTask
    fun runDelayed(plugin: Plugin, task: () -> Unit, retired: (() -> Unit)?, delayTicks: Long): ScheduledTask
}
