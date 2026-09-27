package org.bukkit.plugin

/**
 * 插件管理器（兼容层）：仅识别本模组自身；Vault / DeluxeTags / PlayerPoints /
 * PlaceholderAPI 等 Bukkit 生态插件在 NeoForge 上不存在，getPlugin 一律返回
 * null——QueryInfoService / CheckInCommands 的反射链路会走"未安装"分支优雅降级
 * （卡片隐藏对应统计项、签到走记账模式）。
 */
object PluginManager {

    /** 本模组的 JavaPlugin 实例（模组构造时注册）。 */
    @Volatile
    private var self: Plugin? = null

    /** 注册本模组实例。 */
    fun register(plugin: Plugin) {
        self = plugin
    }

    fun unregister() {
        self = null
    }

    /** 插件查找：仅 "KERONGPenguin"（大小写不敏感）命中自身。 */
    fun getPlugin(name: String): Plugin? {
        val current = self ?: return null
        val registeredName = current.name
        return if (name.equals(registeredName, ignoreCase = true)) current else null
    }

    /** 全部已装载插件（仅自身）。 */
    fun getPlugins(): Array<Plugin> = self?.let { arrayOf(it) } ?: emptyArray()

    /** 是否有插件启用（自身恒启用）。 */
    fun isPluginEnabled(name: String): Boolean = getPlugin(name) != null
}

/** 插件接口（兼容层最小面：isEnabled / name）。 */
interface Plugin {
    /** 是否启用（恒为 true）。 */
    val isEnabled: Boolean

    /** 插件名（KERONGPenguin）。 */
    val name: String
}

/** 服务注册项（兼容层桩：provider 永不返回）。 */
class RegisteredServiceProvider<out T : Any>(val provider: T)

/** 服务管理器（兼容层桩：Bukkit 插件生态不存在，恒 null）。 */
object ServicesManager {
    /** 服务注册查询（恒 null → Vault 生态判定为未安装）。 */
    fun getRegistration(service: Class<*>): RegisteredServiceProvider<*>? = null
}
