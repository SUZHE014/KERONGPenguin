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
     * 日志器（java.util.logging 接口 → SLF4J 转发，1.6.3 修复）。
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

    /** 从磁盘重载配置。 */
    fun reloadConfig() {
        (config as org.bukkit.configuration.file.YamlConfiguration).load(File(dataFolder, "config.yml"))
    }

    /** 保存配置到磁盘。 */
    fun saveConfig() {
        (config as org.bukkit.configuration.file.YamlConfiguration).save(File(dataFolder, "config.yml"))
    }
}
