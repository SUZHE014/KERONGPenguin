package cn.kerong.penguin.spigot.stats

import cn.kerong.bot.tools.PluginFileLog

/**
 * spark 接入状态启动提示（1.6.7.3 新增）。
 *
 * /查性能 自 1.6.7.2 起检测到 spark（Spigot 端为插件、NeoForge 端为模组）
 * 时优先经其公开 API 取 TPS / MSPT / CPU——但此前接入与否只在真正查询时
 * 才能感知，用户无法在启动日志里确认 spark 桥是否生效（尤其 NeoForge 端
 * spark 是模组，装没装、桥通没通全靠猜）。
 *
 * 现在（用户需求）：服务器启动过程中，检测到 spark 并成功读取到指标时
 * 输出一条正向确认；未安装 / 未就绪 / 读取失败一律静默（回退自研反射链
 * 属正常降级，不制造噪音）。
 *
 * 实现：异步延迟线程（守护线程，一次即出）：
 * - Spigot 端 12 秒（与 PapiDependencyCheck 同款错峰节奏——本插件名
 *   KERONGPenguin 按字母序先于 spark 加载，onEnable 时 spark 多半尚未
 *   enable，必须等全部插件加载完再探测）；
 * - NeoForge 端 5 秒（挂载点已是 ServerStartedEvent，全部模组就绪，
 *   只需留几秒让 spark 的统计窗口积累出首批样本）。
 *
 * spark 可用性探测由平台适配层以 lambda 注入（Spigot 查插件表并传
 * spark 插件的类加载器；NeoForge 查 ModList 并用本类加载器），本类
 * 只负责延迟调度、读取与提示，双端共用零平台依赖。
 */
object SparkStartupCheck {

    /** 延迟毫秒（错开启动高峰；两端各自的默认值见 KDoc）。 */
    private const val SPIGOT_DELAY_MILLIS = 12_000L
    private const val NEOFORGE_DELAY_MILLIS = 5_000L

    /**
     * 调度启动提示（一次；spark 探测成功才输出）。
     *
     * @param delayMillis 延迟毫秒（默认 Spigot 节奏 12 秒）
     * @param sparkProbe 延迟到期后执行的探测：返回非 null = spark 已接入
     *   且至少一项指标可读；null = 未安装 / 未就绪 / 读取失败（静默）
     */
    fun schedule(delayMillis: Long = SPIGOT_DELAY_MILLIS, sparkProbe: () -> SparkBridge.SparkMetrics?) {
        Thread({
            try {
                Thread.sleep(delayMillis)
            } catch (_: InterruptedException) {
                return@Thread
            }
            try {
                val metrics = sparkProbe() ?: return@Thread
                val readable = buildString {
                    if (metrics.tps != null) append("TPS")
                    if (metrics.mspt != null) append(if (isEmpty()) "MSPT" else " / MSPT")
                    if (metrics.cpu != null) append(if (isEmpty()) "CPU" else " / CPU")
                }.ifEmpty { "指标" }
                PluginFileLog.infoAndKeep(
                    "[性能数据] 已接入 spark（$readable 读取优先走 spark，对服务端核心无感，数据更稳）"
                )
                PluginFileLog.write("[性能数据] spark 接入自检通过（启动档，可读项：$readable）")
            } catch (_: Throwable) {
                // 自检失败不影响任何功能（查询时照常走探测 + 回退链）
            }
        }, "qq-spark-check").apply { isDaemon = true }.start()
    }

    /** NeoForge 端延迟节奏（ServerStarted 后 5 秒）。 */
    fun neoforgeDelay(): Long = NEOFORGE_DELAY_MILLIS
}
