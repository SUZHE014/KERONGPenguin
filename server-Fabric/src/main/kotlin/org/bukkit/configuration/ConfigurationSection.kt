package org.bukkit.configuration

/**
 * 配置节接口（兼容层）：点号路径 + 读写视图。
 * 语义对齐 Bukkit ConfigurationSection（common-Bot 实际用到的面）。
 */
interface ConfigurationSection {

    /** 相对本节的顶层键（deep 恒仅支持 false：common-Bot 全部为浅层遍历）。 */
    fun getKeys(deep: Boolean): Set<String>

    /** 读取原始值。 */
    operator fun get(path: String): Any?

    /** 写入（null 删除键）。 */
    operator fun set(path: String, value: Any?)

    /** 键是否存在（含 null 值键）。 */
    fun contains(path: String): Boolean

    /** 键是否真实写入（非默认值回退；本兼容层与 contains 等价）。 */
    fun isSet(path: String): Boolean = contains(path)

    fun getString(path: String): String?

    fun getString(path: String, def: String?): String? = getString(path) ?: def

    fun getInt(path: String): Int

    fun getInt(path: String, def: Int): Int

    fun getLong(path: String): Long

    fun getLong(path: String, def: Long): Long

    fun getDouble(path: String): Double

    fun getDouble(path: String, def: Double): Double

    fun getBoolean(path: String): Boolean

    fun getBoolean(path: String, def: Boolean): Boolean

    /** 子节视图（不存在且目标非映射时返回 null）。 */
    fun getConfigurationSection(path: String): ConfigurationSection?

    /** 字符串列表。 */
    fun getStringList(path: String): List<String>

    /** 映射列表（custom-commands）。 */
    fun getMapList(path: String): List<Map<*, *>>
}

/** 类型强转工具。 */
internal object ConfigValues {
    fun asString(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> null
    }

    fun asInt(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: value.toDoubleOrNull()?.toInt()
        else -> null
    }

    fun asLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong()
        else -> null
    }

    fun asDouble(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    fun asBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        is Number -> value.toDouble() != 0.0
        else -> null
    }

    fun asStringList(value: Any?): List<String> = when (value) {
        is List<*> -> value.mapNotNull { asString(it) }
        else -> emptyList()
    }

    fun asMapList(value: Any?): List<Map<*, *>> = when (value) {
        is List<*> -> value.filterIsInstance<Map<*, *>>()
        else -> emptyList()
    }
}
