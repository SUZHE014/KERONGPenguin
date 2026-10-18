package me.lucko.spark.api.statistic.misc;

/** 冒烟用假 DoubleAverageInfo（mean 等取值签名对齐真实 API）。 */
public interface DoubleAverageInfo {
    double mean();
    double max();
    double min();
    double percentile(double percentile);
}
