package me.lucko.spark.api;

/**
 * 冒烟用假 SparkProvider（1.6.7.5：签名与真实 spark 1.10.x 逐项对齐——
 * get() 未装载抛 IllegalStateException；set 为包内可见（真实实现如此，
 * 防外部注入），冒烟经反射调用）。
 */
public final class SparkProvider {
    private static Spark instance;

    public static Spark get() {
        if (instance == null) {
            throw new IllegalStateException("spark has not loaded yet!");
        }
        return instance;
    }

    static void set(Spark impl) {
        instance = impl;
    }

    private SparkProvider() {
        throw new AssertionError();
    }
}
