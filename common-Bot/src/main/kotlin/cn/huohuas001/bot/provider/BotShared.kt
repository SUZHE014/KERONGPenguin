package cn.huohuas001.bot.provider

import cn.huohuas001.bot.HuHoBot
import java.lang.reflect.Field

/**
 * 反射工具：按类型名后缀查找实例字段。
 * 用于兼容不同版本第三方库的字段访问。
 */
object HReflection {

    /**
     * 在 [instance] 的已声明字段中查找类型名以 [type] 结尾的字段并返回其值。
     * @return 找到的字段值，未找到返回 null
     */
    fun findFieldByType(instance: Any, type: String): Any? {
        for (field: Field in instance.javaClass.declaredFields) {
            if (field.type.name.endsWith(type)) {
                field.isAccessible = true
                return field.get(instance)
            }
        }
        return null
    }
}

/**
 * 全局共享的单例入口：持有当前平台插件实例。
 */
object BotShared {
    @Volatile
    private var plugin: HuHoBot? = null

    /** 注册当前插件实例。 */
    fun setInstance(instance: HuHoBot) {
        plugin = instance
    }

    /** 获取当前插件实例（未初始化时抛出异常更符合 Kotlin 惯例，但保持与原逻辑一致返回 null 安全调用场景）。 */
    val instance: HuHoBot?
        get() = plugin
}

/** 快捷访问当前插件实例。 */
val plugin: HuHoBot
    get() = BotShared.instance
        ?: error("HuHoBot 插件实例尚未初始化")
