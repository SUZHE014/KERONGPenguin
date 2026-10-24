package me.lucko.spark.api.statistic.misc;

/** 冒烟用假 DoubleAverageInfo（方法集合对齐真实 API）。 */
public interface DoubleAverageInfo {
    double mean();
    double max();
    double min();
    double median();
    double percentile95th();
    double percentile(double percentile);
}
