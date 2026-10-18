package cn.kerong.penguin.spigot.stats

import cn.kerong.bot.KERONG
import cn.kerong.bot.QClient
import cn.kerong.penguin.spigot.render.CardRenderPool
import cn.kerong.penguin.spigot.render.InfoCardAssets
import cn.kerong.penguin.spigot.render.PerformanceRenderer
import cn.kerong.penguin.spigot.render.RenderSettings
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * /查性能 命令图片输出服务（1.6.7 新增，1.6.7.1 调整，1.6.7.2 增样式与缓存）。
 *
 * 全流程异步（与 /查在线 一致）：机器人消息线程只做冷却判断
 * （render.command-cooldown-seconds，同一配置共用冷却秒数但独立计数），
 * 其余进入 [CardRenderPool]：
 * 1. 取平台性能快照（[KERONG.performanceSnapshot]：TPS / MSPT /
 *    JVM 堆 / 进程 CPU；1.6.7.2 起检测到 spark（插件 / 模组）时
 *    优先直接取 spark 数据；核心不支持读取的项为 null，卡片显示「--」）；
 * 2. 指标固定四项（TPS / MSPT / 内存 / CPU，1.6.7.1 起不再展示
 *    在线人数，单项显隐配置已移除）；
 * 3. [PerformanceRenderer] 渲染卡片（1.6.7.2 起按 render.performance.style
 *    切换原版 / 液态玻璃样式；背景与其他渲染共用
 *    [InfoCardAssets.resolveBackground]，render.performance.builtin-background
 *    默认内置底图）；
 * 4. 单图发送；性能数据缓存（1.6.7.2 新增 render.performance.cache-seconds，
 *    默认 5 秒）：缓存窗口内的重复查询直接复用上一张卡片秒回（对应
 *    EasyBot 模板「渲染过慢」一节推荐开启的性能数据缓存），0 = 关闭。
 *
 * 命令总开关走 commands.查性能（与其他群命令一致：关闭后命令失效、
 * 快捷菜单按钮同步消失、回复 command-disabled 模板）。
 */
object PerformanceService {

    /** 用户冷却记录：OpenId → 上次触发时间。 */
    private val lastQueryAt = ConcurrentHashMap<String, Long>()

    // ---------- 性能数据缓存（1.6.7.2 新增） ----------

    /** 缓存互斥锁（[CardRenderPool] 可能多线程并发提交）。 */
    private val cacheLock = Any()

    /** 缓存的卡片 PNG（null = 无缓存）。 */
    @Volatile
    private var cachedPng: ByteArray? = null

    /** 缓存对应的样式（样式切换后旧缓存不复用）。 */
    @Volatile
    private var cachedStyle: RenderSettings.PerformanceStyle? = null

    /** 缓存写入时间。 */
    @Volatile
    private var cachedAtMillis: Long = 0L

    /** 取未过期缓存（无缓存 / 过期 / 样式不一致返回 null）。 */
    private fun peekCache(style: RenderSettings.PerformanceStyle): ByteArray? {
        val ttl = RenderSettings.performanceCacheMillis()
        if (ttl <= 0) return null
        synchronized(cacheLock) {
            val cached = cachedPng ?: return null
            if (cachedStyle != style) return null
            if (System.currentTimeMillis() - cachedAtMillis >= ttl) return null
            return cached
        }
    }

    /** 写入缓存（关闭缓存时清空旧缓存）。 */
    private fun storeCache(style: RenderSettings.PerformanceStyle, png: ByteArray) {
        synchronized(cacheLock) {
            if (RenderSettings.performanceCacheMillis() <= 0) {
                cachedPng = null
                cachedStyle = null
                return
            }
            cachedPng = png
            cachedStyle = style
            cachedAtMillis = System.currentTimeMillis()
        }
    }

    /**
     * 渲染与发送全流程在共享渲染池执行，消息线程仅做冷却判断。
     */
    fun handle(plugin: KERONG, event: GroupMessageEvent, qqOpenId: String) {
        // 查询冷却（与在线列表共用 render.command-cooldown-seconds，独立计数）
        val cooldownMillis = RenderSettings.commandCooldownMillis()
        val now = System.currentTimeMillis()
        if (cooldownMillis > 0) {
            val lastAt = lastQueryAt[qqOpenId] ?: 0L
            if (now - lastAt < cooldownMillis) {
                cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "query-too-frequent")
                return
            }
            lastQueryAt[qqOpenId] = now
            if (lastQueryAt.size > 1024) lastQueryAt.clear()
        }

        try {
            CardRenderPool.submit {
                try {
                    val style = RenderSettings.performanceStyle()

                    // 0. 缓存命中直接复用（性能数据缓存，1.6.7.2 新增）
                    peekCache(style)?.let { cached ->
                        if (!QClient.replyWithImgBytes(event, cached)) {
                            cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "image-send-failed")
                        }
                        return@submit
                    }

                    // 1. 平台性能快照（null = 服务端不可用，如 NeoForge 未完成启动）
                    val snapshot = plugin.performanceSnapshot()
                    if (snapshot == null) {
                        cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "online-data-unavailable")
                        return@submit
                    }

                    // 2. 固定四项指标（1.6.7.1 起无单项显隐配置）
                    val height = PerformanceRenderer.measureHeight()
                    val dataDirectory = plugin.configFile?.parentFile
                    val background = InfoCardAssets.resolveBackground(
                        dataDirectory,
                        PerformanceRenderer.WIDTH,
                        height,
                        RenderSettings.performanceBuiltinBackground(),
                    )
                    val updateTime = SimpleDateFormat("HH:mm").format(Date())
                    val png = PerformanceRenderer.render(
                        PerformanceRenderer.PerformanceData(
                            serverName = plugin.serverName,
                            updateTime = updateTime,
                            snapshot = snapshot,
                            background = background,
                            style = style,
                        )
                    )

                    // 3. 写入缓存（render.performance.cache-seconds 窗口内复用）
                    storeCache(style, png)

                    // 4. 单图发送
                    if (png.isEmpty() || !QClient.replyWithImgBytes(event, png)) {
                        cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "image-send-failed")
                    }
                } catch (error: Throwable) {
                    // 失败日志带堆栈前几帧，与 /查在线 一致可事后追溯
                    plugin.log_error("[查性能] 性能卡片渲染失败: ${error.message}")
                    plugin.log_error(
                        "[查性能] 失败堆栈: " +
                            error.stackTraceToString().lineSequence().take(8).joinToString(" | ")
                    )
                    cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "performance-render-failed")
                }
            }
        } catch (_: Throwable) {
            // 理论上不会触发（队列无上限），兜底防止线程池异常时静默丢消息
            cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "server-busy")
        }
    }
}
