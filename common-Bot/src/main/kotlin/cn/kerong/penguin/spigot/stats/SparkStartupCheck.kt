package cn.kerong.penguin.spigot.stats

import cn.kerong.bot.provider.plugin
import cn.kerong.bot.tools.PluginFileLog

/**
 * spark 接入状态提示（1.6.7.3 新增，1.6.7.4 调整提示规则）。
 *
 * /查性能 自 1.6.7.2 起检测到 spark（Spigot 端为插件、NeoForge 端为模组）
 * 时优先经其公开 API 取 TPS / MSPT / CPU——但接入与否此前只在真正查询时
 * 才能感知。
 *
 * 1.6.7.4 提示规则（用户需求）：
 * - **时机**：服务器**启动成功之后**再检测提示（Spigot 端挂 ServerLoadEvent
 *   ——全部插件 enable 完毕；NeoForge 端本就挂 ServerStartedEvent），
 *   不再在启动过程中抢跑；
 * - **成功提示**：仅当「/查性能 命令开关（commands.查性能）开启」且 spark
 *   接入成功时输出正向确认——查性能都关了，spark 通不通用户不关心，
 *   不提示（文件仍留一条自检记录备查）；
 * - **失败提示**：检测到 spark 已安装但接入失败（插件未启用 / 指标读不到 /
 *   探测异常）时**无论查性能开关与否都要提示**（warning 级）——用户装了
 *   spark 却接不上是问题信号，静默会让用户误以为已生效；
 * - **未安装**：spark 都没装属正常降级（回退自研反射链），完全静默。
 *
 * 实现：平台在「服务器启动成功」事件后调用 [schedule]，异步延迟线程
 * （守护线程，一次即出）执行探测：
 * - Spigot 端 ServerLoadEvent 后 5 秒（事件即「全部插件加载完成」，
 *   只需留几秒让 spark 统计窗口积累首批样本）；
 * - NeoForge 端 ServerStartedEvent 后 5 秒（全部模组就绪，同上）。
 *
 * spark 可用性探测由平台适配层以 lambda 注入（返回 [ProbeOutcome]
 * 三态：未安装 / 已接入（含指标）/ 已安装但接入失败（含原因）），本类
 * 只负责延迟调度、判定与提示，双端共用零平台依赖。
 */
object SparkStartupCheck {

    /** 服务器启动成功事件后的延迟毫秒（留几秒让 spark 统计窗口积累首批样本）。 */
    private const val AFTER_SERVER_STARTED_MILLIS = 5_000L

    /** 接入失败后的复测毫秒（1.6.7.5：spark 统计窗口尚未积累样本时首轮可能暂不可读）。 */
    private const val RETRY_AFTER_FAILED_MILLIS = 15_000L

    /**
     * spark 探测三态结果（1.6.7.4：区分「未安装」与「已安装但接入失败」，
     * 前者静默、后者必须提示）。
     */
    sealed class ProbeOutcome {
        /** spark 未安装（Spigot 插件表 / NeoForge ModList 均查无）→ 完全静默。 */
        object NotInstalled : ProbeOutcome()

        /** spark 已接入且至少一项指标可读（携带 [SparkBridge.SparkMetrics]）。 */
        class Installed(val metrics: SparkBridge.SparkMetrics) : ProbeOutcome()

        /** spark 已安装但接入失败（未启用 / 指标读不到 / 探测异常，携带原因）。 */
        class Failed(val reason: String) : ProbeOutcome()
    }

    /**
     * 调度启动自检（一次）。
     *
     * @param delayMillis 延迟毫秒（默认「服务器启动成功后 5 秒」节奏）
     * @param sparkProbe 延迟到期后执行的探测：返回 [ProbeOutcome] 三态
     */
    fun schedule(delayMillis: Long = AFTER_SERVER_STARTED_MILLIS, sparkProbe: () -> ProbeOutcome) {
        Thread({
            try {
                Thread.sleep(delayMillis)
            } catch (_: InterruptedException) {
                return@Thread
            }
            try {
                var outcome = sparkProbe()
                // 1.6.7.5：接入失败先静默复测一轮再定性——spark 统计窗口在
                // 启动初期可能尚未积累首批样本（反射链已修复的前提下，
                // 偶发首轮读不到通常只是窗口未热），避免误报接入失败
                if (outcome is ProbeOutcome.Failed) {
                    try {
                        Thread.sleep(RETRY_AFTER_FAILED_MILLIS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    outcome = sparkProbe()
                }
                when (outcome) {
                    is ProbeOutcome.NotInstalled -> {
                        // 未安装属正常降级：文件留一条备查，控制台静默
                        PluginFileLog.write("[性能数据] 未检测到 spark，/查性能 使用内置反射链读取")
                    }
                    is ProbeOutcome.Installed -> {
                        val readable = readableList(outcome.metrics)
                        if (isPerformanceCommandEnabled()) {
                            PluginFileLog.infoAndKeep(
                                "[性能数据] 已接入 spark（$readable 读取优先走 spark，对服务端核心无感，数据更稳）"
                            )
                            PluginFileLog.write("[性能数据] spark 接入自检通过（可读项：$readable）")
                        } else {
                            // 查性能关闭：成功与否不影响任何功能，不刷控制台
                            PluginFileLog.write(
                                "[性能数据] spark 接入自检通过（可读项：$readable），但 commands.查性能 已关闭，成功不另作提示"
                            )
                        }
                    }
                    is ProbeOutcome.Failed -> {
                        // 用户需求：接入失败必须提示（与查性能开关无关——装了 spark 却
                        // 接不上是问题信号），warning 级醒目但说明影响面消除恐慌
                        PluginFileLog.warnAndKeep(
                            "[性能数据] 检测到 spark 但接入失败（${outcome.reason}），/查性能 将回退内置反射链读取，功能不受影响"
                        )
                    }
                }
            } catch (_: Throwable) {
                // 自检失败不影响任何功能（查询时照常走探测 + 回退链）
            }
        }, "qq-spark-check").apply { isDaemon = true }.start()
    }

    /** NeoForge 端延迟节奏（ServerStarted 后 5 秒；1.6.7.4 与 Spigot 统一）。 */
    fun neoforgeDelay(): Long = AFTER_SERVER_STARTED_MILLIS

    /** /查性能 命令总开关（commands.查性能，默认 true；读不到按开启处理）。 */
    private fun isPerformanceCommandEnabled(): Boolean = try {
        plugin.commandList()["查性能"] != false
    } catch (_: Throwable) {
        true
    }

    /** 指标可读项清单（TPS / MSPT / CPU）。 */
    private fun readableList(metrics: SparkBridge.SparkMetrics): String = buildString {
        if (metrics.tps != null) append("TPS")
        if (metrics.mspt != null) append(if (isEmpty()) "MSPT" else " / MSPT")
        if (metrics.cpu != null) append(if (isEmpty()) "CPU" else " / CPU")
    }.ifEmpty { "指标" }
}
