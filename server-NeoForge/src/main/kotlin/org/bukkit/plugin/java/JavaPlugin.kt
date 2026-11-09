package org.bukkit.plugin.java

import org.bukkit.plugin.Plugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger as JulLogger

/**
 * JavaPlugin（兼容层）：为 ConfigManager / QqBindManager 提供
 * dataFolder / logger / config（YamlConfiguration）与默认配置释放能力，
 * 行为对齐 Bukkit 的同名语义。
 */
abstract class JavaPlugin : Plugin {

    /** 插件数据目录（模组构造时由子类传入： FMLPaths.CONFIGDIR 或 GAMEDIR/kerongpenguin）。 */
    abstract override val name: String

    /** 数据目录。 */
    abstract val dataFolder: File

    override val isEnabled: Boolean
        get() = enabled

    @Volatile
    private var enabled: Boolean = false

    /** 平台内初始化（模组生命周期 onServerStarting 调用，置位启用）。 */
    fun markEnabled() {
        enabled = true
    }

    /** 模组卸载 / 服务器停止。 */
    fun markDisabled() {
        enabled = false
    }

    /**
 * 日志器（java.util.logging 接口 → SLF4J 转发，修复）。
     *
     * 兼容层的 logger 供共享源码（ConfigManager / QqBindManager 等）调用
     * （它们按 Bukkit 习惯调用 JUL 的 info / warning）。旧版直接返回裸
     * JUL Logger，而 NeoForge 环境没有任何 JUL 桥接，控制台会打出
     * 「Oct 13, 2026 11:41:13 PM cn.kerong…ConfigManager reload / INFO: …」
     * 这种原始格式——无 [KERONGPenguin/] 前缀、与服务器时间戳重复、
     * 也绕过了日志级别体系。
     *
     * 现在给该 JUL 日志器挂一个转发 Handler：JUL → SLF4J("KERONGPenguin")
     * → 服务器控制台标准 [KERONGPenguin/]: 格式，与 Spigot 的 PluginLogger
     * 视觉一致；JUL 自身的父级控制台输出被关闭（useParentHandlers=false），
     * 不会出现两份。转发 Handler 幂等安装（logger 为 JVM 全局按名单例，
     * 防止多实例重复挂载）。懒取避免构造顺序问题。
     */
    val logger: JulLogger by lazy { bridgeJulToSlf4j(name) }

    /** 把本名称的 JUL 日志器桥接到 SLF4J（幂等）。 */
    private fun bridgeJulToSlf4j(loggerName: String): JulLogger {
        val jul = JulLogger.getLogger(loggerName)
        jul.useParentHandlers = false
        if (jul.handlers.none { it is Slf4jForwardHandler }) {
            jul.addHandler(Slf4jForwardHandler(LoggerFactory.getLogger(loggerName)))
        }
        return jul
    }

    /**
     * JUL → SLF4J 单向转发 Handler（仅本兼容层使用）。
     *
     * 级别映射：SEVERE → error、WARNING → warn、其余（INFO 及以下）→ info；
     * 附带异常时把 throwable 传给 SLF4J（保留堆栈）。SLF4J 侧的日志级别
     * 配置（服务器 log4j2）对最终输出生效，JUL 侧不再自行过滤。
     */
    private class Slf4jForwardHandler(private val slf4j: Logger) : Handler() {
        init {
            level = Level.ALL
        }

        override fun publish(record: LogRecord) {
            if (!isLoggable(record)) return
            val message = record.message ?: return
            val thrown = record.thrown
            try {
                when {
                    record.level.intValue() >= Level.SEVERE.intValue() ->
                        if (thrown != null) slf4j.error(message, thrown) else slf4j.error(message)
                    record.level.intValue() >= Level.WARNING.intValue() ->
                        if (thrown != null) slf4j.warn(message, thrown) else slf4j.warn(message)
                    else ->
                        if (thrown != null) slf4j.info(message, thrown) else slf4j.info(message)
                }
            } catch (_: Throwable) {
                // 转发失败不影响调用方
            }
        }

        override fun flush() {}

        override fun close() {}
    }

    /** 当前配置（懒加载自 dataFolder/config.yml）。 */
    val config: org.bukkit.configuration.file.FileConfiguration = org.bukkit.configuration.file.YamlConfiguration()

    /**
 * 配置已从磁盘装载过：saveConfig 的注释保留路径
     * 只在装载后可用；装载抛异常（文件损坏）时保持 false，避免把
     * 内存里的不完整树整盘覆盖用户文件。
     */
    @Volatile
    private var configLoaded = false

    // ---------- 配置生命周期（对齐 Bukkit JavaPlugin） ----------

    /** 释放内置默认配置（不覆盖已有文件）。 */
    fun saveDefaultConfig() {
        val target = File(dataFolder, "config.yml")
        if (target.isFile) return
        dataFolder.mkdirs()
        val resource: InputStream? = javaClass.classLoader.getResourceAsStream("config.yml")
        if (resource == null) {
            logger.warning("内置默认配置 config.yml 未找到")
            return
        }
        resource.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    }

    /**
 * 读取模组 JAR 内资源（对齐 Bukkit JavaPlugin#getResource；
     * 配置版本重建器用它取 JAR 内置 config.yml 全文）。
     */
    fun getResource(name: String): InputStream? {
        val normalized = if (name.startsWith("/")) name.substring(1) else name
        return try {
            javaClass.classLoader.getResourceAsStream(normalized)
        } catch (_: Throwable) {
            null
        }
    }

    /** 从磁盘重载配置。 */
    fun reloadConfig() {
        // 先复位装载标记：装载抛异常（文件损坏）时保持 false，
        // saveConfig 会跳过写盘，不会把内存里的旧树覆盖用户文件
        configLoaded = false
        (config as org.bukkit.configuration.file.YamlConfiguration).load(File(dataFolder, "config.yml"))
        configLoaded = true
    }

    /**
 * 保存配置到磁盘（注释保留保存）。
     *
     * 旧版直接整树 dump，config.yml 里的注释全部丢失（bpngsz 一键切换 /
     * 配置升级补齐后文件被剥成光杆键值，教程里按注释找配置无从下手）。
     * 现改为：以当前磁盘文本为底做文本级差分合并（只重写有差异的行），
     * 注释与段落结构原样保留；差分校验不通过时退回整树 dump（旧行为，
     * 注释会丢但数据不丢）。从未装载过且磁盘已有文件时跳过写盘，
     * 避免空树覆盖用户配置。
     */
    fun saveConfig() {
        val file = File(dataFolder, "config.yml")
        val yaml = config as org.bukkit.configuration.file.YamlConfiguration
        if (!configLoaded) {
            if (file.isFile) {
                logger.warning("配置尚未从磁盘装载，跳过本次保存以避免覆盖 config.yml（请先 reloadConfig）")
                return
            }
            yaml.save(file)
            return
        }
        if (!ConfigCommentSaver.savePreservingComments(file, yaml)) {
            try {
                logger.warning("config.yml 注释保留保存未成功，本次退回整树写出（注释会丢失，配置值不受影响）")
            } catch (_: Throwable) {
            }
            yaml.save(file)
        }
    }
}

/**
 * config.yml 注释保留保存（仅本兼容层使用）。
 *
 * 差分思路与 ConfigFileRebuilder 同源：磁盘文本为底 → 快照树 vs 内存
 * 树逐叶子比对 → 差异键走 [YamlTextMerge] 文本合并（替换 / 插入 / 删除）
 * → 合并结果整树校验（可解析且每个键值与内存一致，数字按 double 宽松
 * 比较）→ 原子写盘。任一环节不达标返回 false，调用方退回整树 dump。
 */
private object ConfigCommentSaver {

    /** 差分合并保存；成功返回 true，失败返回 false（调用方走 dump 兑底）。 */
    fun savePreservingComments(file: File, yaml: org.bukkit.configuration.file.YamlConfiguration): Boolean {
        val raw = try {
            if (!file.isFile) return false
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return false
            // BOM 剥除（snakeyaml 解析与合并均以无 BOM 文本为准）
            if (text.startsWith("﻿")) text.substring(1) else text
        } catch (_: Throwable) {
            return false
        }

        // 快照树：磁盘文本重新解析一份（内存树从同一文本装载 + 本次改动，
        // 两者差 = 本轮要落盘的改动）
        val snapshot: Map<String, Any?> = try {
            @Suppress("UNCHECKED_CAST")
            org.yaml.snakeyaml.Yaml().load<Any?>(raw) as? Map<String, Any?> ?: return false
        } catch (_: Throwable) {
            return false
        }

        val liveLeaves = LinkedHashMap<String, Any?>()
        collectLeaves(yaml.root, "", liveLeaves)
        val snapLeaves = LinkedHashMap<String, Any?>()
        collectLeaves(snapshot, "", snapLeaves)

        val overrides = LinkedHashMap<String, Any?>()
        for ((path, value) in liveLeaves) {
            if (!snapLeaves.containsKey(path) || !valuesEqual(snapLeaves[path], value)) {
                overrides[path] = value
            }
        }
        val removals = HashSet<String>()
        for (path in snapLeaves.keys) {
            if (!liveLeaves.containsKey(path)) removals.add(path)
        }

        val merged = try {
            cn.kerong.penguin.spigot.manager.YamlTextMerge.merge(raw, overrides, removals)
        } catch (_: Throwable) {
            return false
        }

        // 整树校验：合并结果可解析，且与内存树逐叶子一致、被删键确已消失
        val verifyLeaves = LinkedHashMap<String, Any?>()
        try {
            @Suppress("UNCHECKED_CAST")
            val parsed = org.yaml.snakeyaml.Yaml().load<Any?>(merged) as? Map<String, Any?> ?: return false
            collectLeaves(parsed, "", verifyLeaves)
        } catch (_: Throwable) {
            return false
        }
        for ((path, value) in liveLeaves) {
            if (!verifyLeaves.containsKey(path) || !valuesEqual(verifyLeaves[path], value)) return false
        }
        for (path in removals) {
            if (verifyLeaves.containsKey(path)) return false
        }

        return try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            java.nio.file.Files.write(tmp.toPath(), merged.toByteArray(Charsets.UTF_8))
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 递归收集叶子键值（嵌套映射下探；列表与标量均按叶子处理）。 */
    private fun collectLeaves(node: Map<*, *>, prefix: String, out: LinkedHashMap<String, Any?>) {
        for ((key, value) in node) {
            val name = key.toString()
            val path = if (prefix.isEmpty()) name else "$prefix.$name"
            if (value is Map<*, *>) {
                collectLeaves(value, path, out)
            } else {
                out[path] = value
            }
        }
    }

    /**
     * 宽松等值（校验用）：数字按 double 比较（1 与 1.0 同值）；字符串 /
     * 布尔精确；列表逐项；映射按键逐值。类型不可比一律判不等（保守落
     * 到 dump 兑底，数据不丢）。
     */
    private fun valuesEqual(a: Any?, b: Any?): Boolean = when {
        a == null && b == null -> true
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is Boolean && b is Boolean -> a == b
        a is String && b is String -> a == b
        a is List<*> && b is List<*> ->
            a.size == b.size && a.indices.all { valuesEqual(a[it], b[it]) }
        a is Map<*, *> && b is Map<*, *> ->
            a.size == b.size && a.keys.all { k -> b.containsKey(k) && valuesEqual(a[k], b[k]) }
        else -> false
    }
}
