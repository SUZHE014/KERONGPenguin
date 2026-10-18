package cn.kerong.penguin.spigot.stats

/**
 * spark 性能数据桥（1.6.7.2 新增）。
 *
 * /查性能 检测到服务器装了 spark（Spigot 端为插件、NeoForge 端为模组）时，
 * 优先直接调用 spark 的公开 API 读取 TPS / MSPT / CPU——spark 对各核心的
 * 数据采集比本插件的自研反射链更稳（尤其 NeoForge 无 TPS 字段、纯 Spigot
 * 无 getTPS() 的场合），装了就借它的数；spark 读不到的项回退自研链路。
 *
 * 实现（全程反射，无编译期依赖，双端共用）：
 * - [me.lucko.spark.api.SparkProvider.get] 静态拿 spark 单例（未加载时
 *   抛 IllegalStateException，捕获后按「无 spark」处理）；
 * - TPS：Spark#tps()（无该统计的平台返回 null）→
 *   DoubleStatistic#poll(TicksPerSecond.MINUTES_1)（最近 1 分钟平均，
 *   与卡片口径一致）；
 * - MSPT：Spark#mspt() → GenericStatistic#poll(MillisPerTick.SECONDS_10)
 *   → DoubleAverageInfo#mean()（近 10 秒每刻平均耗时毫秒）；
 * - CPU：Spark#cpuProcess()（必有）→
 *   DoubleStatistic#poll(CpuUsage.MINUTES_1)（0.0~1.0，与卡片口径同为
 *   进程级占用，spark 为 1 分钟窗口均值更平稳）；
 *
 * 类可见性：Spigot 端插件类加载器互查可见（传入 spark 插件的 loader 最稳）；
 * NeoForge 端模组均为自动模块（互相可读），用本类 loader 的
 * Class.forName 即可。统计实例上取枚举常量时用「实例自身的类加载器」，
 * 保证跨模块解析。任何一步失败只作废该项，不拖累其余指标。
 */
object SparkBridge {

    /** spark 读取结果（逐项可空：单项读不到不拖累其余）。 */
    data class SparkMetrics(
        /** 最近 1 分钟平均 TPS（满值 20）。 */
        val tps: Double?,
        /** 近 10 秒平均每刻耗时（毫秒）。 */
        val mspt: Double?,
        /** 进程 CPU 占用（0.0~1.0，1 分钟均值）。 */
        val cpu: Double?,
    )

    /**
     * 尝试从 spark 读取性能指标。
     *
     * @param preferredLoader 优先使用的类加载器（Spigot 端传 spark 插件的
     *   loader；null = 用本类自己的 loader，NeoForge 端走这条）。
     * @return spark 未安装 / 未加载 / 全部指标读取失败时返回 null。
     */
    fun read(preferredLoader: ClassLoader? = null): SparkMetrics? {
        return try {
            val loader = preferredLoader ?: SparkBridge::class.java.classLoader
            val providerClass = Class.forName("me.lucko.spark.api.SparkProvider", true, loader)
            val spark = providerClass.getMethod("get").invoke(null) ?: return null
            val tps = pollTps(spark)
            val mspt = pollMsptMean(spark)
            val cpu = pollCpu(spark)
            if (tps == null && mspt == null && cpu == null) null else SparkMetrics(tps, mspt, cpu)
        } catch (_: Throwable) {
            // spark 不存在（NoClassDefFound / ClassNotFound）或未完成加载
            //（IllegalStateException）——一律按「无 spark」回退自研链路
            null
        }
    }

    /** TPS：tps() 统计 → poll(TicksPerSecond.MINUTES_1)。 */
    private fun pollTps(spark: Any): Double? {
        return pollWindowDouble(
            spark, "tps",
            "me.lucko.spark.api.statistic.StatisticWindow\$TicksPerSecond",
            listOf("MINUTES_1", "SECONDS_10"),
        )
    }

    /** CPU：cpuProcess() 统计 → poll(CpuUsage.MINUTES_1)。 */
    private fun pollCpu(spark: Any): Double? {
        return pollWindowDouble(
            spark, "cpuProcess",
            "me.lucko.spark.api.statistic.StatisticWindow\$CpuUsage",
            listOf("MINUTES_1", "SECONDS_10"),
        )
    }

    /**
     * 通用：statisticGetter() 拿统计对象 → poll(窗口枚举常量) → double。
     * 窗口常量按候选顺序取第一个存在的（不同 spark 版本窗口集合略有差异）。
     */
    private fun pollWindowDouble(
        spark: Any,
        statisticGetter: String,
        windowEnumClass: String,
        windowCandidates: List<String>,
    ): Double? {
        return try {
            val stat = invokeNoArg(spark, statisticGetter) ?: return null
            val statisticLoader = stat.javaClass.classLoader
            val window = resolveEnumConstant(windowEnumClass, windowCandidates, statisticLoader) ?: return null
            val value = stat.javaClass.methods.firstOrNull {
                it.name == "poll" && it.parameterCount == 1 && it.parameterTypes[0] == window.javaClass
            }?.invoke(stat, window) ?: return null
            (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * MSPT：mspt() 统计 → poll(MillisPerTick.SECONDS_10) →
     * DoubleAverageInfo#mean()（平均每刻毫秒）。
     */
    private fun pollMsptMean(spark: Any): Double? {
        return try {
            val stat = invokeNoArg(spark, "mspt") ?: return null
            val statisticLoader = stat.javaClass.classLoader
            val window = resolveEnumConstant(
                "me.lucko.spark.api.statistic.StatisticWindow\$MillisPerTick",
                listOf("SECONDS_10", "MINUTES_1"),
                statisticLoader,
            ) ?: return null
            val info = stat.javaClass.methods.firstOrNull {
                it.name == "poll" && it.parameterCount == 1 && it.parameterTypes[0] == window.javaClass
            }?.invoke(stat, window) ?: return null
            val mean = invokeNoArg(info, "mean") ?: return null
            (mean as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
        } catch (_: Throwable) {
            null
        }
    }

    /** 调无参公有方法（找不到 / 调用失败返回 null，不抛出）。 */
    private fun invokeNoArg(obj: Any, name: String): Any? {
        return try {
            obj.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(obj)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 按候选名解析窗口枚举常量（用统计实例的类加载器跨模块解析）。
     * 候选顺序即优先级：先找第一个候选，找不到再退下一个。
     */
    private fun resolveEnumConstant(
        enumClassName: String,
        candidates: List<String>,
        loader: ClassLoader,
    ): Any? {
        return try {
            val enumClass = Class.forName(enumClassName, true, loader)
            val constants = enumClass.enumConstants ?: return null
            for (candidate in candidates) {
                constants.firstOrNull { constant ->
                    (constant as? Enum<*>)?.name == candidate
                }?.let { return it }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}
