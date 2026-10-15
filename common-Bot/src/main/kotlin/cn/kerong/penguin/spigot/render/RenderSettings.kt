package cn.kerong.penguin.spigot.render

import cn.kerong.bot.provider.BotShared
import org.bukkit.plugin.java.JavaPlugin

/**
 * 图片渲染命令的运行时设置（1.6.0 新增配置化）。
 *
 * - render.command-cooldown-seconds：同一 QQ 用户两次触发图片渲染命令
 *   （/查信息、/查在线、/在线排行榜、/财富榜）的最小间隔秒数，
 *   防止刷屏导致渲染风暴。默认 5（与旧版硬编码一致）；0 = 关闭冷却；
 *   负数按 0 处理。实时读取（/hb reload 后立即生效）。
 */
object RenderSettings {

    /** 旧版硬编码默认值（秒）。 */
    private const val DEFAULT_COOLDOWN_SECONDS = 5

    /** 头像磁盘缓存默认有效期（小时，1.6.6.1 新增配置化）。 */
    const val DEFAULT_AVATAR_CACHE_HOURS = 3.0

    /** 头像获取优先方式（1.6.6.1 覆盖版新增配置）。 */
    enum class AvatarFetchPriority { LOCAL, EXTERNAL }

    /** 图片渲染命令的用户冷却（毫秒；0 = 无冷却）。 */
    fun commandCooldownMillis(): Long {
        val seconds = try {
            (BotShared.instance as? JavaPlugin)?.config?.getInt("render.command-cooldown-seconds", DEFAULT_COOLDOWN_SECONDS)
                ?: DEFAULT_COOLDOWN_SECONDS
        } catch (_: Throwable) {
            DEFAULT_COOLDOWN_SECONDS
        }
        return seconds.coerceAtLeast(0).toLong() * 1000L
    }

    /**
     * 头像磁盘缓存有效期（小时，1.6.6.1 新增）。
     *
     * - 皮肤头像保存到 cache/avatars/ 目录，超过有效期自动删除
     *   （后台每 30 分钟清扫 + 查询命中时顺手删）；
     * - 默认 3；支持小数（如 0.5 = 半小时）；0 = 关闭磁盘缓存；
     * - 负数按默认处理；实时读取（/kerong reload 后立即生效）。
     */
    fun avatarCacheHours(): Double {
        val hours = try {
            (BotShared.instance as? JavaPlugin)?.config?.getDouble(
                "render.avatar-cache-hours",
                DEFAULT_AVATAR_CACHE_HOURS,
            ) ?: DEFAULT_AVATAR_CACHE_HOURS
        } catch (_: Throwable) {
            DEFAULT_AVATAR_CACHE_HOURS
        }
        return if (hours < 0) DEFAULT_AVATAR_CACHE_HOURS else hours
    }

    /**
     * 头像获取优先方式（1.6.6.1 覆盖版新增）。
     *
     * - render.avatar-fetch-priority（默认 local）：
     *   - local：优先本地（在线玩家 Profile 皮肤 + 本服存档数据），
     *     本地取不到自动切换外置接口（Mojang 官方 API → 头像多线路）；
     *   - external：优先外置接口，全部失败后再试本地；
     * - 值不区分大小写，未识别的值一律按 local 处理；
     * - 实时读取（/kerong reload 后立即生效）。
     */
    fun avatarFetchPriority(): AvatarFetchPriority {
        val value = try {
            (BotShared.instance as? JavaPlugin)?.config?.getString("render.avatar-fetch-priority", "local")
                ?: "local"
        } catch (_: Throwable) {
            "local"
        }
        return if (value.trim().equals("external", ignoreCase = true)) {
            AvatarFetchPriority.EXTERNAL
        } else {
            AvatarFetchPriority.LOCAL
        }
    }
}
