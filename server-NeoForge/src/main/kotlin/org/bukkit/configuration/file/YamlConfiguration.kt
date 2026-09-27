package org.bukkit.configuration.file

import org.bukkit.configuration.ConfigValues
import org.bukkit.configuration.ConfigurationSection
import java.io.File
import java.io.InputStream
import java.util.LinkedHashMap

/**
 * YamlConfiguration（兼容层）：snakeyaml 实现的 Bukkit 语义配置文件。
 *
 * - 点号路径读写（"stats.play-seconds" → 嵌套映射）；
 * - getConfigurationSection 返回共享同一棵树的子节视图（子节写入会传播）；
 * - 保存输出嵌套 YAML（与真实 Bukkit 写出的文件互读兼容；
 *   QqBindManager 的原子写 / 迁移链路直接复用）；
 * - 配置文件带注释时，经本层重存会丢失注释（snakeyaml 不解析注释行；
 *   首次安装走 saveDefaultConfig 原样复制，无注释损失）。
 */
open class YamlConfiguration : FileConfiguration() {

    companion object {
        /** 装载文件（失败时返回空配置，对齐 Bukkit 容错语义）。 */
        @JvmStatic
        fun loadConfiguration(file: File): YamlConfiguration = try {
            YamlConfiguration().apply { load(file) }
        } catch (_: Throwable) {
            YamlConfiguration()
        }

        /** 装载流（兼容保留）。 */
        @JvmStatic
        fun loadConfiguration(stream: InputStream): YamlConfiguration = try {
            YamlConfiguration().apply { loadStream(stream) }
        } catch (_: Throwable) {
            YamlConfiguration()
        }
    }

    /** 从文件装载。 */
    fun load(file: File) {
        if (!file.isFile) return
        val loaded = org.yaml.snakeyaml.Yaml().load<MutableMap<String, Any?>>(file.inputStream())
        @Suppress("UNCHECKED_CAST")
        (root as MutableMap<String, Any?>).putAll(loaded ?: emptyMap())
    }

    /** 从流装载。 */
    fun loadStream(stream: InputStream) {
        val loaded = org.yaml.snakeyaml.Yaml().load<MutableMap<String, Any?>>(stream)
        @Suppress("UNCHECKED_CAST")
        (root as MutableMap<String, Any?>).putAll(loaded ?: emptyMap())
    }

    /** 保存到文件。 */
    fun save(file: File) {
        file.parentFile?.mkdirs()
        java.io.OutputStreamWriter(file.outputStream(), Charsets.UTF_8).use { writer ->
            val options = org.yaml.snakeyaml.DumperOptions().apply {
                defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
                isAllowUnicode = true
                width = Int.MAX_VALUE
            }
            org.yaml.snakeyaml.Yaml(options).dump(root, writer)
        }
    }

    /** 序列化为字符串。 */
    fun saveToString(): String {
        val options = org.yaml.snakeyaml.DumperOptions().apply {
            defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
            isAllowUnicode = true
            width = Int.MAX_VALUE
        }
        return org.yaml.snakeyaml.Yaml(options).dump(root) ?: ""
    }
}

/** FileConfiguration（兼容层基类：配置树 + 路径寻址）。 */
sealed class FileConfiguration : MemorySection(emptyList())

/**
 * 配置节实现：共享 root 树 + 路径前缀。
 * [YamlConfiguration] 即前缀为空的根节。
 */
open class MemorySection internal constructor(
    private val path: List<String>,
) : ConfigurationSection {

    /** 树根（YamlConfiguration 与全部子节视图共享）。 */
    internal open val root: MutableMap<String, Any?> = LinkedHashMap()

    override fun getKeys(deep: Boolean): Set<String> = sectionMap().keys

    override operator fun get(path: String): Any? = resolve(fullPath(path))

    override operator fun set(path: String, value: Any?) {
        write(fullPath(path), value)
    }

    override fun contains(path: String): Boolean = resolve(fullPath(path)) != null || hasNullKey(fullPath(path))

    override fun getString(path: String): String? = ConfigValues.asString(get(path))

    override fun getString(path: String, def: String?): String? = getString(path) ?: def

    override fun getInt(path: String): Int = getInt(path, 0)

    override fun getInt(path: String, def: Int): Int = ConfigValues.asInt(get(path)) ?: def

    override fun getLong(path: String): Long = getLong(path, 0L)

    override fun getLong(path: String, def: Long): Long = ConfigValues.asLong(get(path)) ?: def

    override fun getDouble(path: String): Double = getDouble(path, 0.0)

    override fun getDouble(path: String, def: Double): Double = ConfigValues.asDouble(get(path)) ?: def

    override fun getBoolean(path: String): Boolean = getBoolean(path, false)

    override fun getBoolean(path: String, def: Boolean): Boolean = ConfigValues.asBoolean(get(path)) ?: def

    override fun getConfigurationSection(path: String): ConfigurationSection? {
        val value = resolve(fullPath(path)) ?: return null
        return if (value is Map<*, *>) SectionView(fullPath(path)) else null
    }

    override fun getStringList(path: String): List<String> = ConfigValues.asStringList(get(path))

    override fun getMapList(path: String): List<Map<*, *>> = ConfigValues.asMapList(get(path))

    // ---------- 内部寻址 ----------

    /** 本节的子树映射（无子树时空映射）。 */
    protected fun sectionMap(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return resolve(path) as? Map<String, Any?> ?: emptyMap()
    }

    private fun fullPath(key: String): List<String> = if (key.isEmpty()) path else path + split(key)

    private fun split(key: String): List<String> = key.split('.')

    /** 读取：逐级下探映射。 */
    @Suppress("UNCHECKED_CAST")
    private fun resolve(path: List<String>): Any? {
        var node: MutableMap<String, Any?> = root
        for ((index, segment) in path.withIndex()) {
            val value = node[segment] ?: return null
            if (index == path.size - 1) return value
            if (value !is MutableMap<*, *>) return null
            node = value as MutableMap<String, Any?>
        }
        return node
    }

    /** null 值键也存在（contains 语义）。 */
    @Suppress("UNCHECKED_CAST")
    private fun hasNullKey(path: List<String>): Boolean {
        if (path.isEmpty()) return true
        var node: MutableMap<String, Any?> = root
        for ((index, segment) in path.withIndex()) {
            if (index == path.size - 1) return node.containsKey(segment)
            val value = node[segment] ?: return false
            if (value !is MutableMap<*, *>) return false
            node = value as MutableMap<String, Any?>
        }
        return false
    }

    /** 写入：逐级建映射；null 删除键。 */
    @Suppress("UNCHECKED_CAST")
    private fun write(path: List<String>, value: Any?) {
        if (path.isEmpty()) return
        var node: MutableMap<String, Any?> = root
        for ((index, segment) in path.withIndex()) {
            if (index == path.size - 1) {
                if (value == null) node.remove(segment) else node[segment] = value
                return
            }
            val existing = node[segment]
            if (existing !is MutableMap<*, *>) {
                if (value == null && existing == null) return
                val created = LinkedHashMap<String, Any?>()
                node[segment] = created
                node = created
            } else {
                node = existing as MutableMap<String, Any?>
            }
        }
    }

    /** 子节视图（共享 root）。 */
    inner class SectionView(path: List<String>) : MemorySection(path) {
        override val root: MutableMap<String, Any?>
            get() = this@MemorySection.root
    }
}
