package org.bukkit.entity

/**
 * 实体类型枚举（兼容层最小面）。
 * PlayerStatsManager.onEntityDeath 区分：击杀玩家 / 屠龙 / 普通怪物。
 * NeoForge 事件监听把死亡实体映射为本枚举。
 */
enum class EntityType(private val alive: Boolean) {
    PLAYER(true),
    ENDER_DRAGON(true),
    GENERIC_MOB(true),
    GENERIC_OTHER(false),
    ;

    /** 是否生物（Bukkit 语义：活物才计入怪物击杀）。 */
    val isAlive: Boolean
        get() = alive
}
