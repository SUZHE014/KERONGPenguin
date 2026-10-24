package cn.kerong.penguin.spigot.render

import cn.kerong.bot.provider.BotShared
import org.bukkit.plugin.java.JavaPlugin

/**
 * 图片渲染命令的运行时设置（1.6.0 新增配置化）。
 *
 * - render.command-cooldown-seconds：同一 QQ 用户两次触发图片渲染命令
 *   （/查信息、/查在线、/在线排行榜、/财富榜）的最小间隔秒数，
 *   防止刷屏导致渲染风暴。默认 5（与旧版硬编码一致）；0 = 关闭冷却；
 *   负数按 0 处理。实时读取（/kerong reload 后立即生效）。
 */
object RenderSettings {

    /** 旧版硬编码默认值（秒）。 */
    private const val DEFAULT_COOLDOWN_SECONDS = 5

    /** 头像磁盘缓存默认有效期（小时，1.6.6.1 新增配置化）。 */
    const val DEFAULT_AVATAR_CACHE_HOURS = 3.0

    /** 渲染空闲内存释放默认阈值（分钟，1.6.6.2 新增；1.6.7.2 默认 5 → 15）。 */
    const val DEFAULT_IDLE_RELEASE_MINUTES = 15.0

    /** 性能数据缓存默认秒数（1.6.7.2 新增；覆盖版默认 5 → 60）。 */
    const val DEFAULT_PERFORMANCE_CACHE_SECONDS = 60

    /** 全局透明度默认值（1.6.7.2 覆盖版新增，1.0 = 不透明）。 */
    const val DEFAULT_GLOBAL_OPACITY = 1.0

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
     * - render.idle-release-minutes（1.6.7.2 默认由 5 调整为 15）：一次
     *   渲染完成后指定分钟内没有下一次渲染时，自动释放头像 / 背景等
     *   图像素材缓存占用的内存（flush + 清空；磁盘头像缓存与轻量元
     *   数据缓存保留，下次渲染从磁盘 / 外部接口秒级恢复）；
     * - 支持小数（如 2.5 = 2 分半）；0 = 关闭空闲释放；
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

    /**
     * 全局透明度（1.6.7.2 覆盖版新增 render.global-opacity，默认 1.0）。
     *
     * - 作用于全部图片卡片输出（查信息 / 查在线 / 查性能 / 在线排行榜 /
     *   财富榜）：编码 PNG 前把整张画布像素 alpha 统一乘以该系数；
     * - 1.0 = 完全不透明（默认，行为与旧版一致）；0.5 = 半透明；
     *   越小越透明（QQ 客户端按 PNG 透明度叠加在聊天背景上显示）；
     * - 取值范围 0.0 ~ 1.0，超出自动收敛到边界；
     * - 实时读取（/kerong reload 后立即生效）。
     */
    fun globalOpacity(): Float {
        val value = try {
            (BotShared.instance as? JavaPlugin)?.config?.getDouble(
                "render.global-opacity",
                DEFAULT_GLOBAL_OPACITY,
            ) ?: DEFAULT_GLOBAL_OPACITY
        } catch (_: Throwable) {
            DEFAULT_GLOBAL_OPACITY
        }
        return value.coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * 性能数据缓存时长（毫秒，1.6.7.2 新增；覆盖版默认 5 → 60）。
     *
     * - render.performance.cache-seconds（默认 60）：缓存时间内的重复
     *   /查性能 查询直接复用上一张卡片（多人同时查 / 冷却结束立刻再查
     *   时秒回，不重复取数与渲染——对应 EasyBot 模板「渲染过慢」一节
     *   推荐开启的性能数据缓存）；
     * - 0 = 关闭缓存（每次都实时取数渲染）；负数按默认；
     * - 实时读取（/kerong reload 后立即生效）。
     */
    fun performanceCacheMillis(): Long {
        val seconds = try {
            (BotShared.instance as? JavaPlugin)?.config?.getInt(
                "render.performance.cache-seconds",
                DEFAULT_PERFORMANCE_CACHE_SECONDS,
            ) ?: DEFAULT_PERFORMANCE_CACHE_SECONDS
        } catch (_: Throwable) {
            DEFAULT_PERFORMANCE_CACHE_SECONDS
        }
        if (seconds < 0) return DEFAULT_PERFORMANCE_CACHE_SECONDS * 1000L
        return seconds * 1000L
    }
}
