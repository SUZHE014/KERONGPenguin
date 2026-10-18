package me.lucko.spark.api.statistic.types;

import me.lucko.spark.api.statistic.StatisticWindow;

/** 冒烟用假 DoubleStatistic（poll 窗口重载签名对齐真实 API）。 */
public interface DoubleStatistic<W extends Enum<W> & StatisticWindow> {
    double poll(W window);
    double[] poll();
}
