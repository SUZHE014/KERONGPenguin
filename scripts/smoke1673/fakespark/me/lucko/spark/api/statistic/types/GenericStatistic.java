package me.lucko.spark.api.statistic.types;

import me.lucko.spark.api.statistic.StatisticWindow;

/** 冒烟用假 GenericStatistic（poll 窗口重载签名对齐真实 API）。 */
public interface GenericStatistic<T, W extends Enum<W> & StatisticWindow> {
    T poll(W window);
    T[] poll();
}
