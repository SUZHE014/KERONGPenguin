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
     * 1.6.6.4：空闲 GC 提示（render.idle-gc-hint）与线上 1.6.6.3 短暂引入的
     * 自动收缩（auto-shrink）均已移除——System.gc 的 Full GC 全停顿
     *（0.05~0.6 秒）可能落在玩家游玩时刻造成卡顿；内存归还交给 JVM
     * 自然节奏，插件侧只做确定性图像缓存释放（见 [idleReleaseMillis]）。
     */

    /**
     * 查性能卡片是否使用内置背景图（1.6.7 新增，默认 true）。
     *
     * 语义与其他渲染的 builtin-background 一致：true = 内置深灰底图
     * （随 JAR 分发，与其他卡片共用横版底图），false = 随机取数据目录
     * img/ 图片（无图自动回退内置）。实时读取（/kerong reload 立即生效）。
     *
     * 1.6.7.1：指标固定四项（TPS / MSPT / 内存 / CPU），原
     * render.performance.show-* 单项显隐配置已移除（在线人数卡同步
     * 移除——查在线命令已有专门卡片）；命令总开关走 commands.查性能。
     */
    fun performanceBuiltinBackground(): Boolean {
        return try {
            (BotShared.instance as? JavaPlugin)?.config?.getBoolean(
                "render.performance.builtin-background",
                true,
            ) ?: true
        } catch (_: Throwable) {
            true
        }
    }
}
