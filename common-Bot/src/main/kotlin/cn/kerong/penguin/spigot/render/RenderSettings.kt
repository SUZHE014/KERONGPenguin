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

    /** 渲染空闲内存释放默认阈值（分钟，1.6.6.2 新增配置化）。 */
    const val DEFAULT_IDLE_RELEASE_MINUTES = 5.0

    /** 空闲释放后是否向 JVM 发 GC 提示归还内存（1.6.6.2 覆盖版新增，默认开）。 */
    const val DEFAULT_IDLE_GC_HINT = true

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

    /**
     * 渲染空闲内存释放阈值（毫秒，1.6.6.2 新增）。
     *
     * - render.idle-release-minutes（默认 5）：一次渲染完成后指定分钟内
     *   没有下一次渲染时，自动释放头像 / 背景等图像素材缓存占用的内存
     *   （flush + 清空；磁盘头像缓存与轻量元数据缓存保留，下次渲染
     *   从磁盘 / 外部接口秒级恢复）；
     * - 默认 5；支持小数（如 2.5 = 2 分半）；0 = 关闭空闲释放；
     * - 负数按默认处理；实时读取（/kerong reload 后立即生效）。
     */
    fun idleReleaseMillis(): Long {
        val minutes = try {
            (BotShared.instance as? JavaPlugin)?.config?.getDouble(
                "render.idle-release-minutes",
                DEFAULT_IDLE_RELEASE_MINUTES,
            ) ?: DEFAULT_IDLE_RELEASE_MINUTES
        } catch (_: Throwable) {
            DEFAULT_IDLE_RELEASE_MINUTES
        }
        if (minutes < 0) return (DEFAULT_IDLE_RELEASE_MINUTES * 60_000L).toLong()
        return (minutes * 60_000.0).toLong()
    }

    /**
     * 空闲释放后是否向 JVM 发一次 GC 提示（1.6.6.2 覆盖版新增）。
     *
     * - render.idle-gc-hint（默认 true）：渲染空闲释放清空图像缓存后，
     *   向 JVM 发一次 full GC 提示——G1 借此把渲染高峰期扩张的堆归还
     *   操作系统（面板 / 进程管理器显示的内存随之回落）。触发时机是
     *   「已空闲整个空闲周期 + 缓存已清空」的存活对象最低水位，停顿
     *   为毫秒级，与 1.6.5 移除的「渲染后每次 System.gc」高频全停顿
     *   行为完全不同；
     * - false = 关闭（完全不触发 GC 提示，堆保持自然回落节奏）；
     * - 实时读取（/kerong reload 后立即生效）。
     */
    fun idleGcHint(): Boolean {
        return try {
            (BotShared.instance as? JavaPlugin)?.config?.getBoolean(
                "render.idle-gc-hint",
                DEFAULT_IDLE_GC_HINT,
            ) ?: DEFAULT_IDLE_GC_HINT
        } catch (_: Throwable) {
            DEFAULT_IDLE_GC_HINT
        }
    }
}
