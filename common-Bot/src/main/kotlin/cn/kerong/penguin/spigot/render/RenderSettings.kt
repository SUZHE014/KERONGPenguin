package cn.kerong.penguin.spigot.render

import cn.kerong.bot.provider.BotShared
import org.bukkit.plugin.java.JavaPlugin

/**
 * 图片渲染命令的运行时设置（1.6.0 新增配置化）。
 *
 * - render.command-cooldown-seconds：同一 QQ 用户两次触发图片渲染命令
 *   （/查信息、/查在线、/在线排行榜、/财富榜）的最小间隔秒数，
 *   防止刷屏导致渲染风暴。默认 5（与旧版硬编码一致）；0 = 关闭冷却；
 *   负数按 0 处理。实时读取（/hbkrg reload 后立即生效）。
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

    /** 毛玻璃模糊度默认值（1.7.0 新增 render.blur-strength，50 = 轻模糊）。 */
    const val DEFAULT_BLUR_STRENGTH = 50

    /**
     * 标题卡片底色黑度默认值（1.7.2 新增 render.title-card-gray，覆盖版 96 → 80）。
     *
     * 作用于四张卡片的顶部标题区：查信息顶部欢迎面板（你好, 玩家名）、
     * 查在线在线人数卡、排行榜统计玩家卡、查性能更新时间卡。旧版这些
     * 标题区硬编码半透明白（视觉上「透明」），改标题框黑度调不动——
     * 覆盖版统一改接本键；默认 80 比项目卡片（106）略浅（用户口径：
     * 标题框黑度略低于项目卡片，默认再调低一点）。
     */
    const val DEFAULT_TITLE_CARD_GRAY = 80

    /**
     * 项目卡片底色黑度默认值（1.7.2 新增 render.item-card-gray）。
     *
     * 列表容器底色：查信息统计面板、查在线三状态卡 / 网格容器 / 空态、
     * 排行榜行容器 / 空态、查性能网格容器；1.7.0 调浅到 64 后太浅，
     * 取 (旧值 120 + 当前 64) / 2 = 92 再偏旧值一侧 → 106（不越过旧值 120）。
     */
    const val DEFAULT_ITEM_CARD_GRAY = 106

    /**
     * 副标题卡底色黑度默认值（1.7.3 新增 render.subtitle-card-gray）。
     *
     * 作用于副标题类小卡：查信息玩家名称项与五张卡片的副标题条
     * （服务器 / 更新时间 / 渲染耗时）。1.7.3.1 覆盖版：用户口径
     * 「副标题框的默认灰度换成和项目框一样的灰度」→ 106（与
     * [DEFAULT_ITEM_CARD_GRAY] 相同）；历史默认 40（初版太浅）与
     * 56（覆盖版调深但仍浅）升级时自动收敛（自改值不动）。
     */
    const val DEFAULT_SUBTITLE_CARD_GRAY = 106

    /** 排行榜单页最大行数默认值（1.7.3 新增 leaderboard.page-size，全局：在线排行榜 / 财富榜共用）。 */
    const val DEFAULT_LEADERBOARD_PAGE_SIZE = 20

    /** 图片渲染线程数上限（1.7.3 覆盖版新增自定义线程配置的安全上限）。 */
    const val MAX_RENDER_THREADS = 16

    /** 头像批量下载线程数默认值（网络 IO 型，1.7.2 起 8 线程共享池）。 */
    const val DEFAULT_AVATAR_THREADS = 8

    /** 右上角小方框（头部徽标卡）比标题框加深的固定增量（1.7.3 覆盖版）。 */
    private const val HEADER_BADGE_GRAY_DELTA = 36

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
     * - 负数按默认处理；实时读取（/hbkrg reload 后立即生效）。
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
     * 渲染空闲内存释放阈值（毫秒，1.6.6.2 新增）。
     *
     * - render.idle-release-minutes（1.6.7.2 默认由 5 调整为 15）：一次
     *   渲染完成后指定分钟内没有下一次渲染时，自动释放头像 / 背景等
     *   图像素材缓存占用的内存（flush + 清空；磁盘头像缓存与轻量元
     *   数据缓存保留，下次渲染从磁盘 / 外部接口秒级恢复）；
     * - 支持小数（如 2.5 = 2 分半）；0 = 关闭空闲释放；
     * - 负数按默认处理；实时读取（/hbkrg reload 后立即生效）。
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
     * img/ 图片（无图自动回退内置）。实时读取（/hbkrg reload 立即生效）。
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
     * 毛玻璃模糊度（render.blur-strength，1.7.0 新增，默认 50）。
     *
     * - 作用于全部图片卡片（查信息 / 查在线 / 查性能 / 在线排行榜 /
     *   财富榜）：只有卡片背后的背景被模糊（backdrop-filter），
     *   卡片外的背景保持原样清晰；
     * - 取值范围 0 ~ 100：越大越模糊；0 = 不模糊（卡片仅叠半透明底色）；
     * - 默认 50 为轻模糊观感，100 等效旧版的最大模糊；
     * - 背景图按「等比缩放 + 中心裁剪」铺满画布，不拉伸变形；
     * - 实时读取（/hbkrg reload 后立即生效——模糊副本按背景缓存
     *   条目惰性生成，模糊度变化自动重新生成）。
     */
    fun blurStrength(): Int {
        val value = try {
            (BotShared.instance as? JavaPlugin)?.config?.getInt(
                "render.blur-strength",
                DEFAULT_BLUR_STRENGTH,
            ) ?: DEFAULT_BLUR_STRENGTH
        } catch (_: Throwable) {
            DEFAULT_BLUR_STRENGTH
        }
        return value.coerceIn(0, 100)
    }

    /**
     * 标题卡片底色黑度（render.title-card-gray，1.7.2 新增，覆盖版默认 80）。
     *
     * - 作用于四张卡片的顶部标题区：查信息顶部欢迎面板、查在线在线
     *   人数卡、排行榜统计玩家卡、查性能更新时间卡（覆盖版前这些区域
     *   硬编码半透明白，改本键不生效——标题框视觉上恒为透明）；
     * - 取值 0 ~ 255（半透明黑 alpha；越大越深）；
     * - 默认 80 比项目卡片（[DEFAULT_ITEM_CARD_GRAY] = 106）略浅；
     * - 实时读取（/hbkrg reload 后立即生效）。
     */
    fun titleCardGray(): Int {
        val value = try {
            (BotShared.instance as? JavaPlugin)?.config?.getInt(
                "render.title-card-gray",
                DEFAULT_TITLE_CARD_GRAY,
            ) ?: DEFAULT_TITLE_CARD_GRAY
        } catch (_: Throwable) {
            DEFAULT_TITLE_CARD_GRAY
        }
        return value.coerceIn(0, 255)
    }

    /**
     * 项目卡片底色黑度（render.item-card-gray，1.7.2 新增，默认 106）。
     *
     * - 作用于列表容器底色：查信息统计面板（覆盖版起并入）、查在线
     *   三状态卡 / 玩家网格容器 / 空态容器、排行榜行容器 / 空态容器、
     *   查性能指标网格容器；
     * - 取值 0 ~ 255（半透明黑 alpha；越大越深）；
     * - 默认 106 = 旧值 120 与 1.7.0 浅值 64 的中间偏旧值一侧
     *   （(120+64)/2=92，再向 120 方向取半 → 106）；
     * - 实时读取（/hbkrg reload 后立即生效）。
     */
    fun itemCardGray(): Int {
        val value = try {
            (BotShared.instance as? JavaPlugin)?.config?.getInt(
                "render.item-card-gray",
                DEFAULT_ITEM_CARD_GRAY,
            ) ?: DEFAULT_ITEM_CARD_GRAY
        } catch (_: Throwable) {
            DEFAULT_ITEM_CARD_GRAY
        }
        return value.coerceIn(0, 255)
    }

    /**
     * 副标题卡底色黑度（render.subtitle-card-gray，1.7.3 新增；
     * 1.7.3.1 覆盖版默认 106 = 与项目卡相同）。
     *
     * - 作用于副标题类小卡：查信息玩家名称项（旧版硬编码半透明白 22，
     *   灰度配置调不动——1.7.3 修复）与五张卡片的副标题条：
     *   查在线 / 在线排行榜 / 财富榜（含经济别名自定义标题）/ 查性能 /
     *   查信息的「服务器 / 更新时间 / 状态」三张小卡；
     * - 取值 0 ~ 255（半透明黑 alpha；越大越深）；
     * - 1.7.3.1 覆盖版起默认与项目卡片（106）相同；
     * - 实时读取（/hbkrg reload 后立即生效）。
     */
    fun subtitleCardGray(): Int {
        val value = try {
            (BotShared.instance as? JavaPlugin)?.config?.getInt(
                "render.subtitle-card-gray",
                DEFAULT_SUBTITLE_CARD_GRAY,
            ) ?: DEFAULT_SUBTITLE_CARD_GRAY
        } catch (_: Throwable) {
            DEFAULT_SUBTITLE_CARD_GRAY
        }
        return value.coerceIn(0, 255)
    }

    /**
     * 头部右上角小方框（徽标卡）底色黑度（1.7.3 覆盖版新增派生键）。
     *
     * - 查在线「在线 N」、排行榜「玩家 N」、查性能「综合状态」三张卡的
     *   右上角小方框；1.7.3 初版与标题框同灰度，叠在同色标题卡上视觉
     *   融合看不出边界——覆盖版改为**永远比标题框深一点**（固定 +36，
     *   随 render.title-card-gray 联动，上限 255）；
     * - 无独立配置键：改 render.title-card-gray 即同步增减。
     */
    fun headerBadgeCardGray(): Int {
        return (titleCardGray() + HEADER_BADGE_GRAY_DELTA).coerceAtMost(255)
    }

    /**
     * 自定义图片渲染线程开关（render.custom-render-threads.enabled，默认 false）。
     *
     * - false = 自动模式：渲染池取 CPU 核数一半（1~4 线程）、头像批量下载池
     *   8 线程——对绝大多数服务器是最优解；
     * - true = 自定义模式：线程数改用下方 render-threads / avatar-threads；
     * - 线程池在首次渲染时创建，**修改本段配置需重启服务器生效**（非实时）。
     */
    fun customRenderThreadsEnabled(): Boolean {
        return try {
            (BotShared.instance as? JavaPlugin)?.config?.getBoolean(
                "render.custom-render-threads.enabled",
                false,
            ) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 图片渲染线程数（render.custom-render-threads.render-threads，开关开启时生效）。
     *
     * - 渲染池负责五张卡片的 CPU 绘制（查信息 / 查在线 / 排行榜 / 财富榜 /
     *   查性能）；自动值为 CPU 核数一半夹在 1~4；
     * - ⚠ 线程数越大同时渲染的图片越多、CPU 争抢越强，核数少的服务器
     *   上可能影响游戏 tick——不建议超过物理核数的一半；
     * - 取值 1~16（越界收敛）；重启生效。
     */
    fun customRenderThreads(): Int {
        if (!customRenderThreadsEnabled()) return 0
        val value = try {
            (BotShared.instance as? JavaPlugin)?.config?.getInt(
                "render.custom-render-threads.render-threads",
                (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4),
            ) ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        } catch (_: Throwable) {
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        }
        return value.coerceIn(1, MAX_RENDER_THREADS)
    }

    /**
     * 头像批量下载线程数（render.custom-render-threads.avatar-threads，开关开启时生效）。
     *
     * - 下载池是网络 IO 型（等外部头像接口响应，不占 CPU）：在线列表 /
     *   排行榜 / 财富榜 / 查信息批量拉取头像时并行下载；默认 8；
     * - ⚠ 调大会增加对外部头像接口的并发压力（可能触发限流），调小则
     *   大名单收齐变慢（12 秒预算内收不齐的玩家本轮显示占位块）；
     * - 与 MC 主线程无关，不影响游戏 tick；取值 1~16（越界收敛）；重启生效。
     */
    fun customAvatarThreads(): Int {
        if (!customRenderThreadsEnabled()) return 0
        val value = try {
            (BotShared.instance as? JavaPlugin)?.config?.getInt(
                "render.custom-render-threads.avatar-threads",
                DEFAULT_AVATAR_THREADS,
            ) ?: DEFAULT_AVATAR_THREADS
        } catch (_: Throwable) {
            DEFAULT_AVATAR_THREADS
        }
        return value.coerceIn(1, MAX_RENDER_THREADS)
    }

    /**
     * 排行榜单页最大行数（leaderboard.page-size，1.7.3 新增，默认 20）。
     *
     * - 全局键：在线排行榜 / 财富榜（含经济别名自定义标题）共用；
     * - 超出单页上限自动分多张图片发送（首页带标题，续页只画行）；
     * - 取值 1 ~ 100；实时读取（/hbkrg reload 后立即生效）；
     * - 旧版硬编码 15 行（ROWS_PER_PAGE 常量已删除）。
     */
    fun leaderboardPageSize(): Int {
        val value = try {
            (BotShared.instance as? JavaPlugin)?.config?.getInt(
                "leaderboard.page-size",
                DEFAULT_LEADERBOARD_PAGE_SIZE,
            ) ?: DEFAULT_LEADERBOARD_PAGE_SIZE
        } catch (_: Throwable) {
            DEFAULT_LEADERBOARD_PAGE_SIZE
        }
        return value.coerceIn(1, 100)
    }

    /**
     * 性能数据缓存时长（毫秒，1.6.7.2 新增；覆盖版默认 5 → 60）。
     *
     * - render.performance.cache-seconds（默认 60）：缓存时间内的重复
     *   /查性能 查询直接复用上一张卡片（多人同时查 / 冷却结束立刻再查
     *   时秒回，不重复取数与渲染——对应 EasyBot 模板「渲染过慢」一节
     *   推荐开启的性能数据缓存）；
     * - 0 = 关闭缓存（每次都实时取数渲染）；负数按默认；
     * - 实时读取（/hbkrg reload 后立即生效）。
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
