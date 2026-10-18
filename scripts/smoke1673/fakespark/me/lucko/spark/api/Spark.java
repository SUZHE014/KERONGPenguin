package me.lucko.spark.api;

import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;

/** 冒烟用假 Spark 接口（签名对齐真实 spark API，供 SparkBridge 反射）。 */
public interface Spark {
    DoubleStatistic<StatisticWindow.CpuUsage> cpuProcess();
    DoubleStatistic<StatisticWindow.TicksPerSecond> tps();
    GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> mspt();
}
