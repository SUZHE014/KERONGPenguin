package org.bukkit

/**
 * 材质枚举（兼容层最小面）。
 * PlayerStatsManager.onBlockBreak 只关心远古残骸；NeoForge 事件监听把
 * 被破坏方块映射为本枚举（ANCIENT_DEBRIS / OTHER）。
 */
enum class Material {
    ANCIENT_DEBRIS,
    OTHER,
}

/**
 * 原版统计枚举（兼容层最小面：PlayerStatsManager 周期快照读取的三项）。
 * 映射见 NeoPlayer.getStatistic。
 */
enum class Statistic {
    WALK_ONE_CM,
    FLY_ONE_CM,
    DAMAGE_DEALT,
}
