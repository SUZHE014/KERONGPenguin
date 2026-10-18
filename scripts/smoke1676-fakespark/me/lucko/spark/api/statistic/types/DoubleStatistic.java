package me.lucko.spark.api.statistic.types;

import me.lucko.spark.api.statistic.Statistic;
import me.lucko.spark.api.statistic.StatisticWindow;

/** 冒烟用假 DoubleStatistic（继承 Statistic 的 name/getWindows，对齐真实 API）。 */
public interface DoubleStatistic<W extends Enum<W> & StatisticWindow> extends Statistic<W> {
    double poll(W window);
    double[] poll();
}
