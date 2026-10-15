package cn.kerong.bot.provider

import cn.kerong.bot.KERONG

/**
 * 全局共享的单例入口：持有当前平台插件实例。
 */
object BotShared {
    @Volatile
    private var plugin: KERONG? = null

    /** 注册当前插件实例。 */
    fun setInstance(instance: KERONG) {
        plugin = instance
    }

    /** 获取当前插件实例（未初始化时抛出异常更符合 Kotlin 惯例，但保持与原逻辑一致返回 null 安全调用场景）。 */
    val instance: KERONG?
        get() = plugin
}

/** 快捷访问当前插件实例。 */
val plugin: KERONG
    get() = BotShared.instance
        ?: error("KERONG 插件实例尚未初始化")
