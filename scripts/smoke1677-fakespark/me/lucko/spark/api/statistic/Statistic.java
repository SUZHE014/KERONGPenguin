package me.lucko.spark.api.statistic;

/** 冒烟用假 Statistic（1.6.7.5：含真实 API 的 name()/getWindows() 抽象）。 */
public interface Statistic<W extends Enum<W> & StatisticWindow> {
    String name();
    W[] getWindows();
}
