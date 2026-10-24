package me.lucko.spark.api.statistic;

/** 冒烟用假窗口枚举（常量集合对齐真实 spark 1.10.x）。 */
public interface StatisticWindow {
    enum CpuUsage implements StatisticWindow { SECONDS_10, MINUTES_1, MINUTES_15 }
    enum TicksPerSecond implements StatisticWindow { SECONDS_5, SECONDS_10, MINUTES_1, MINUTES_5, MINUTES_15 }
    enum MillisPerTick implements StatisticWindow { SECONDS_10, MINUTES_1, MINUTES_5 }
    enum MemoryAllocation implements StatisticWindow { SECONDS_10, MINUTES_1, MINUTES_5, MINUTES_15 }
    enum PlayerPing implements StatisticWindow { SECONDS_10, MINUTES_1, MINUTES_5, MINUTES_15 }
}
