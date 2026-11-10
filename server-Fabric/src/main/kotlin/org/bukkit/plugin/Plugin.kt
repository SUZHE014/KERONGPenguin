package org.bukkit.plugin

/**
 * 插件管理器（兼容层）：按名查找已注册插件。
 *
 * 1.6.9.3 起支持多插件并存注册（列表 + 按名匹配）：生产环境仍然只有本模组
 * 自身会注册（[KERONGFabric] 构造时），行为与旧单槽完全一致；多槽只为
 * 独立 JVM 冒烟测试同时注册「假 KERONGPenguin + 假 PlaceholderAPI」铺路
 * （旧单槽后注册者顶掉先注册者，两个插件无法共存）。
 * Vault / DeluxeTags / PlayerPoints 等 Bukkit 生态插件在 Fabric 上
 * 不存在，getPlugin 一律返回 null——QueryInfoService / CheckInCommands 的
 * 反射链路会走"未安装"分支优雅降级（卡片隐藏对应统计项、签到走记账模式）。
 */
object PluginManager {

    /** 已注册插件（生产仅模组自身；并发读写用 COW 列表）。 */
    private val plugins: MutableList<Plugin> = java.util.concurrent.CopyOnWriteArrayList()

    /** 注册插件（重复注册不去重，按名查找取首个）。 */
    fun register(plugin: Plugin) {
        plugins.add(plugin)
    }

    /** 清空全部注册（模组卸载 / 服务器停止）。 */
    fun unregister() {
        plugins.clear()
    }

    /** 插件查找：按注册名大小写不敏感匹配（无匹配返回 null）。 */
    fun getPlugin(name: String): Plugin? =
        plugins.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** 全部已装载插件。 */
    fun getPlugins(): Array<Plugin> = plugins.toTypedArray()

    /** 是否有插件注册（与旧单槽语义一致：注册即视为启用）。 */
    fun isPluginEnabled(name: String): Boolean = getPlugin(name) != null
}

/** 插件接口（兼容层最小面：isEnabled / name / server）。 */
interface Plugin {
    /** 是否启用（恒为 true）。 */
    val isEnabled: Boolean

    /** 插件名（KERONGPenguin）。 */
    val name: String

    /** 服务器对象（调度入口；FoliaCompat 非 Folia 路径经 plugin.server.scheduler）。 */
    val server: org.bukkit.Server
        get() = org.bukkit.Bukkit.getServer()
}

/** 服务注册项（兼容层桩：provider 永不返回）。 */
class RegisteredServiceProvider<out T : Any>(val provider: T)

/** 服务管理器（兼容层桩：Bukkit 插件生态不存在，恒 null）。 */
object ServicesManager {
    /** 服务注册查询（恒 null → Vault 生态判定为未安装）。 */
    fun getRegistration(service: Class<*>): RegisteredServiceProvider<*>? = null
}
