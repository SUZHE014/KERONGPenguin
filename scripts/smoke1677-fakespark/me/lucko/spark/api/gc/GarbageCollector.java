package me.lucko.spark.api.gc;

/** 冒烟用假 GarbageCollector（真实 API 占位，Spark.gc() 返回空 Map 用）。 */
public interface GarbageCollector {
    String name();
}
