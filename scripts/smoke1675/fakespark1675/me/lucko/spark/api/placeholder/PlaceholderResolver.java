package me.lucko.spark.api.placeholder;

/** 冒烟用假 PlaceholderResolver（真实 API 占位）。 */
public interface PlaceholderResolver {
    Object lookup(String placeholder);
}
