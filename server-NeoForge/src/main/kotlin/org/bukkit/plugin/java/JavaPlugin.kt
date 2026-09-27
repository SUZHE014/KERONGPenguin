package org.bukkit.plugin.java

import org.bukkit.plugin.Plugin
import java.io.File
import java.io.InputStream
import java.util.logging.Logger

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

    /** 日志器（java.util.logging，路由到 SLF4J 由适配器完成；懒取避免构造顺序问题）。 */
    val logger: Logger by lazy { Logger.getLogger(name) }

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
