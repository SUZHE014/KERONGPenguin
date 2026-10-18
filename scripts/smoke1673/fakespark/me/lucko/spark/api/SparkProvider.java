package me.lucko.spark.api;

/** 冒烟用假 SparkProvider（get() 语义对齐真实实现：未装载抛 IllegalStateException）。 */
public final class SparkProvider {
    private static Spark instance;

    public static Spark get() {
        if (instance == null) {
            throw new IllegalStateException("spark has not loaded yet!");
        }
        return instance;
    }

    /** 冒烟专用注入口（真实实现的 set 为包内可见）。 */
    public static void setForTest(Spark impl) {
        instance = impl;
    }

    private SparkProvider() {
        throw new AssertionError();
    }
}
