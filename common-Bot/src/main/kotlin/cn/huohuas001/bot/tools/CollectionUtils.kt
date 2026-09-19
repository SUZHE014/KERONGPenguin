package cn.huohuas001.bot.tools

/**
 * 集合工具函数（对应原项目的 CollectionUtils.kt）。
 */
object CollectionUtils {

    /** 将字符串集合切分为若干固定大小的分片（用于分页展示）。 */
    fun chunkSet(set: Set<String>, size: Int): List<List<String>> = set.toList().chunked(size)

    /** 在集合中查找包含关键字的所有元素。 */
    fun searchInSet(set: Set<String>, keyword: String): List<String> =
        set.filter { it.contains(keyword) }
}
