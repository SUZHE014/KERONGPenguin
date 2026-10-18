package me.lucko.spark.api;

import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;

import java.util.Collections;
import java.util.Map;

/**
 * 冒烟用假 Spark 接口（1.6.7.5：方法集合对齐真实 API——含 cpuSystem /
 * memoryAllocation / playerPing / gc / placeholders，反射按名查找时多余方法
 * 不干扰，缺失才会暴露查找路径问题）。
 */
public interface Spark {
    DoubleStatistic<StatisticWindow.TicksPerSecond> tps();
    DoubleStatistic<StatisticWindow.CpuUsage> cpuSystem();
    DoubleStatistic<StatisticWindow.CpuUsage> cpuProcess();
    GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> mspt();
    GenericStatistic<DoubleAverageInfo, StatisticWindow.MemoryAllocation> memoryAllocation();
    GenericStatistic<DoubleAverageInfo, StatisticWindow.PlayerPing> playerPing();
    Map<String, me.lucko.spark.api.gc.GarbageCollector> gc();
    me.lucko.spark.api.placeholder.PlaceholderResolver placeholders();
}
