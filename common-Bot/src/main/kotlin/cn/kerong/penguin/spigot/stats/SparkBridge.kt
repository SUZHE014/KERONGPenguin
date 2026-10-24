package cn.kerong.penguin.spigot.stats

/**
 * spark 性能数据桥（1.6.7.2 新增，1.6.7.5 修复反射链）。
 *
 * /查性能 检测到服务器装了 spark（Spigot 端为插件、NeoForge 端为模组）时，
 * 优先直接调用 spark 的公开 API 读取 TPS / MSPT / CPU——spark 对各核心的
 * 数据采集比本插件的自研反射链更稳（尤其 NeoForge 无 TPS 字段、纯 Spigot
 * 无 getTPS() 的场合），装了就借它的数；spark 读不到的项回退自研链路。
 *
 * ## 1.6.7.5 根因修复：方法一律从「公共接口」解析
 *
 * 症状：1.6.7.2 ~ 1.6.7.4 装了 spark 也读不到任何指标（启动自检报
 * 「检测到 spark 但接入失败（各指标均暂不可读）」，/查性能 数据也从未
 * 走过 spark）。
 *
 * 根因：spark 的 `Spark` 实现与各统计实现类（`me.lucko.spark.common.*`）
 * 全部是**包私有类**。旧实现对实例的运行时类取方法表找 `tps()` /
 * `poll(...)` 等方法——运行时类是包私有的实现类，`Method.invoke` 对
 * 非公共 declaring class 抛 `IllegalAccessException`，被逐项吞掉后全部
 * 指标为 null → read() 返回 null → 永远按「无 spark / 接入失败」处理。
 * （对着手写假 spark 冒烟时假实现类恰好是 public，所以旧冒烟没暴露。）
 *
 * 修复：所有方法都从 spark 的**公共 API 接口**上解析再 invoke——接口是
 * public 的，`Method.invoke` 对「公共接口方法 + 实现实例」永远放行，与
 * 实现类可见性无关。已对真实 spark 1.10.187（Modrinth 分发的 NeoForge
 * 包，API 类与 Spigot 端同源）提取字节码逐项核实：
 * - `me.lucko.spark.api.Spark`：`tps()` → `DoubleStatistic<TicksPerSecond>`、
 *   `mspt()` → `GenericStatistic<DoubleAverageInfo, MillisPerTick>`、
 *   `cpuProcess()` → `DoubleStatistic<CpuUsage>`（另有 cpuSystem /
 *   memoryAllocation / playerPing / gc / placeholders）；
 * - `SparkProvider.get()`：静态拿单例，spark 未加载时抛
 *   `IllegalStateException("spark has not loaded yet!")`；
 * - `DoubleStatistic#poll(W)` 擦除后参数为 `java.lang.Enum`；
 *   `GenericStatistic#poll(W)` 同；`DoubleAverageInfo#mean()` 返回 double；
 * - 窗口枚举：`TicksPerSecond{SECONDS_5, SECONDS_10, MINUTES_1, MINUTES_5,
 *   MINUTES_15}`、`MillisPerTick{SECONDS_10, MINUTES_1, MINUTES_5}`、
 *   `CpuUsage{SECONDS_10, MINUTES_1, MINUTES_15}`。
 *
 * 实现（全程反射，无编译期依赖，双端共用）：
 * - TPS：`Spark#tps()` → `DoubleStatistic#poll(TicksPerSecond.MINUTES_1)`
 *   （最近 1 分钟平均，与卡片口径一致）；
 * - MSPT：`Spark#mspt()` → `GenericStatistic#poll(MillisPerTick.SECONDS_10)`
 *   → `DoubleAverageInfo#mean()`（近 10 秒每刻平均耗时毫秒）；
 * - CPU：`Spark#cpuProcess()`（必有）→
 *   `DoubleStatistic#poll(CpuUsage.MINUTES_1)`（0.0~1.0，与卡片口径同为
 *   进程级占用，spark 为 1 分钟窗口均值更平稳）；
 * - 延迟：`Spark#playerPing()`（1.6.7.6 新增）→
 *   `GenericStatistic#poll(PlayerPing.MINUTES_1)` →
 *   `DoubleAverageInfo#mean()`（在线玩家平均延迟毫秒，旧版 spark 无此
 *   方法时安静回退 null）；
 *
 * 类可见性：Spigot 端插件类加载器互查可见（传入 spark 插件的 loader 最稳）；
 * NeoForge 端模组均为自动模块（互相可读），用本类 loader 的
 * Class.forName 即可。任何一步失败只作废该项，不拖累其余指标。
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
        /** 在线玩家平均延迟（毫秒；spark playerPing 统计，无人在线为 null，
         * 1.6.7.6 新增）。 */
        val ping: Double?,
    )

    /** spark 公共 API 接口名（方法从这些接口解析，绝不经实现类）。 */
    private const val SPARK_INTERFACE = "me.lucko.spark.api.Spark"
    private const val SPARK_PROVIDER = "me.lucko.spark.api.SparkProvider"
    private const val DOUBLE_STATISTIC = "me.lucko.spark.api.statistic.types.DoubleStatistic"
    private const val GENERIC_STATISTIC = "me.lucko.spark.api.statistic.types.GenericStatistic"
    private const val DOUBLE_AVERAGE_INFO = "me.lucko.spark.api.statistic.misc.DoubleAverageInfo"

    /**
     * 「内置 spark」通道类加载器解析（1.6.7.7 重做，对齐 1.6.7.6 覆盖版设计）。
     *
     * Paper 1.21+ 系核心（Paper / Purpur / Folia 1.21 与 26.x）**内置 spark**：
     * 其类直接挂在服务器系统类路径上，不注册 Bukkit 插件表，自装插件版
     * JAR 还会被这些核心默认忽略——这正是「装了 spark 仍检测不到」的根因
     * （用户实测 Purpur 26.2）。插件表查不到时，依次在候选加载器里探测
     * `me.lucko.spark.api.SparkProvider`（能加载且单例非空且是 Spark 实例
     * 即视为内置 spark 可用）：
     * 1. [extraCandidate]——Spigot 端传 Bukkit API 类加载器（服务器主加载器）；
     * 2. 本类自己的加载器（混合端 / 极端环境兜底）；
     * 3. 线程上下文加载器（部分核心把 spark 挂在 TCCL）。
     *
     * @return 第一个能读到 spark 的加载器；全部探测失败返回 null（无内置）。
     */
    fun bundledSparkClassLoader(extraCandidate: ClassLoader?): ClassLoader? {
        val candidates = ArrayList<ClassLoader>(3)
        extraCandidate?.let { candidates.add(it) }
        candidates.add(SparkBridge::class.java.classLoader)
        try {
            Thread.currentThread().contextClassLoader?.let { candidates.add(it) }
        } catch (_: Throwable) {
        }
        return candidates
            .distinctBy { System.identityHashCode(it) }
            .firstOrNull { loader ->
                try {
                    val spark = Class.forName(SPARK_PROVIDER, true, loader)
                        .getMethod("get").invoke(null) ?: return@firstOrNull false
                    Class.forName(SPARK_INTERFACE, true, loader).isInstance(spark)
                } catch (_: Throwable) {
                    false
                }
            }
    }

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
            val providerClass = Class.forName(SPARK_PROVIDER, true, loader)
            val spark = providerClass.getMethod("get").invoke(null) ?: return null
            // 防御：拿到的东西不是 Spark 实现（异常环境）按无 spark 处理
            val sparkClass = Class.forName(SPARK_INTERFACE, true, loader)
            if (!sparkClass.isInstance(spark)) return null
            val tps = pollTps(spark, loader)
            val mspt = pollMsptMean(spark, loader)
            val cpu = pollCpu(spark, loader)
            val ping = pollPlayerPingMean(spark, loader)
            if (tps == null && mspt == null && cpu == null && ping == null) null
            else SparkMetrics(tps, mspt, cpu, ping)
        } catch (_: Throwable) {
            // spark 不存在（NoClassDefFound / ClassNotFound）或未完成加载
            //（IllegalStateException）——一律按「无 spark」回退自研链路
            null
        }
    }

    /**
     * TPS：Spark#tps() → DoubleStatistic#poll(TicksPerSecond.MINUTES_1)。
     * 1.6.7.7：读数封顶 20——spark 的 TPS 数据源在部分核心（尤其新版
     * Paper 系与自采样的 Spigot 系）下是对瞬时速率的无上限平均，启动
     * 追赶 / 空闲加速场景会给出 300+ 的异常值，封顶到满速 20（与
     * [cn.kerong.bot.PerformanceSnapshot] 各层一致的三重保险）。
     */
    private fun pollTps(spark: Any, loader: ClassLoader): Double? {
        return pollWindowDouble(
            spark, loader, "tps",
            "me.lucko.spark.api.statistic.StatisticWindow\$TicksPerSecond",
            listOf("MINUTES_1", "SECONDS_10"),
        )?.coerceAtMost(20.0)
    }

    /** CPU：Spark#cpuProcess() → DoubleStatistic#poll(CpuUsage.MINUTES_1)。 */
    private fun pollCpu(spark: Any, loader: ClassLoader): Double? {
        return pollWindowDouble(
            spark, loader, "cpuProcess",
            "me.lucko.spark.api.statistic.StatisticWindow\$CpuUsage",
            listOf("MINUTES_1", "SECONDS_10"),
        )
    }

    /**
     * 通用：公共接口上的 statisticGetter() 拿统计对象 → 公共统计接口上的
     * poll(窗口枚举) → double。窗口常量按候选顺序取第一个存在的
     * （不同 spark 版本窗口集合略有差异）。
     */
    private fun pollWindowDouble(
        spark: Any,
        loader: ClassLoader,
        statisticGetter: String,
        windowEnumClass: String,
        windowCandidates: List<String>,
    ): Double? {
        return try {
            val stat = invokeOnInterface(spark, SPARK_INTERFACE, statisticGetter, loader) ?: return null
            val window = resolveEnumConstant(windowEnumClass, windowCandidates, loader) ?: return null
            val statInterface = Class.forName(DOUBLE_STATISTIC, true, loader)
            // poll(W) 擦除后参数类型是 java.lang.Enum，用 isAssignableFrom 匹配
            val poll = statInterface.methods.firstOrNull {
                it.name == "poll" && it.parameterCount == 1 &&
                    it.parameterTypes[0].isAssignableFrom(window.javaClass)
            } ?: return null
            val value = poll.invoke(stat, window) ?: return null
            (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * MSPT：Spark#mspt() → GenericStatistic#poll(MillisPerTick.SECONDS_10) →
     * DoubleAverageInfo#mean()（平均每刻毫秒）。
     */
    private fun pollMsptMean(spark: Any, loader: ClassLoader): Double? {
        return try {
            val stat = invokeOnInterface(spark, "me.lucko.spark.api.Spark", "mspt", loader) ?: return null
            val window = resolveEnumConstant(
                "me.lucko.spark.api.statistic.StatisticWindow\$MillisPerTick",
                listOf("SECONDS_10", "MINUTES_1"),
                loader,
            ) ?: return null
            val statInterface = Class.forName(GENERIC_STATISTIC, true, loader)
            val poll = statInterface.methods.firstOrNull {
                it.name == "poll" && it.parameterCount == 1 &&
                    it.parameterTypes[0].isAssignableFrom(window.javaClass)
            } ?: return null
            val info = poll.invoke(stat, window) ?: return null
            val infoInterface = Class.forName(DOUBLE_AVERAGE_INFO, true, loader)
            val mean = infoInterface.getMethod("mean").invoke(info) ?: return null
            (mean as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 玩家平均延迟：Spark#playerPing() → GenericStatistic#poll(PlayerPing
     * .MINUTES_1) → DoubleAverageInfo#mean()（毫秒；旧版 spark 无该方法/
     * 无人在线时返回 null）。1.6.7.6 新增。
     */
    private fun pollPlayerPingMean(spark: Any, loader: ClassLoader): Double? {
        return try {
            val stat = invokeOnInterface(spark, SPARK_INTERFACE, "playerPing", loader) ?: return null
            val window = resolveEnumConstant(
                "me.lucko.spark.api.statistic.StatisticWindow\$PlayerPing",
                listOf("MINUTES_1", "SECONDS_10"),
                loader,
            ) ?: return null
            val statInterface = Class.forName(GENERIC_STATISTIC, true, loader)
            val poll = statInterface.methods.firstOrNull {
                it.name == "poll" && it.parameterCount == 1 &&
                    it.parameterTypes[0].isAssignableFrom(window.javaClass)
            } ?: return null
            val info = poll.invoke(stat, window) ?: return null
            val infoInterface = Class.forName(DOUBLE_AVERAGE_INFO, true, loader)
            val mean = infoInterface.getMethod("mean").invoke(info) ?: return null
            (mean as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
        } catch (_: Throwable) {
            // 旧版 spark 无 playerPing()：安静回退（平台实现自行读 Player.getPing）
            null
        }
    }

    /**
     * 在公共接口 [interfaceName] 上解析无参方法并作用到 [target] 实例。
     * （绝不对 target 的运行时类取方法表——实现类是包私有时方法不可访问。）
     */
    private fun invokeOnInterface(target: Any, interfaceName: String, method: String, loader: ClassLoader): Any? {
        return try {
            val iface = Class.forName(interfaceName, true, loader)
            iface.getMethod(method).invoke(target)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 按候选名解析窗口枚举常量。
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
