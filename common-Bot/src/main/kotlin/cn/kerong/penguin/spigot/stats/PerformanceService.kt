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
 * /查性能 命令图片输出服务（1.6.7 新增，1.6.7.1 调整，1.6.7.2 增缓存）。
 *
 * 全流程异步（与 /查在线 一致）：机器人消息线程只做冷却判断
 * （render.command-cooldown-seconds，同一配置共用冷却秒数但独立计数），
 * 其余进入 [CardRenderPool]：
 * 1. 取平台性能快照（[KERONG.performanceSnapshot]：TPS / MSPT /
 *    JVM 堆 / 进程 CPU；1.6.7.2 起检测到 spark（插件 / 模组）时
 *    优先直接取 spark 数据；核心不支持读取的项为 null，卡片显示「--」）；
 * 2. 指标固定四项（TPS / MSPT / 内存 / CPU，1.6.7.1 起不再展示
 *    在线人数，单项显隐配置已移除）；
 * 3. [PerformanceRenderer] 渲染卡片（背景与其他渲染共用
 *    [InfoCardAssets.resolveBackground]，render.performance.builtin-background
 *    默认内置底图；1.6.7.2 覆盖版起样式固定原版，透明度由
 *    render.global-opacity 统一控制）；
 * 4. 单图发送；性能数据缓存（1.6.7.2 新增 render.performance.cache-seconds，
 *    覆盖版默认 60 秒）：缓存窗口内的重复查询直接复用上一张卡片秒回
 *    （对应 EasyBot 模板「渲染过慢」一节推荐开启的性能数据缓存），
 *    0 = 关闭。
 *
 * 1.6.9.3：
 * - 在线人数卡展示「N / M」：M 默认服务器真实最大人数，
 *   render.performance.max-players-source = motd 时改用其他插件修改过的
 *   展示人数（即 MOTD 里显示的人数，[MotdDisplayTracker] 从最近一次
 *   ping 捕获；无人 ping 过回退真实值）；
 * - TPS 变量（variable-fetch.tps）：开关开启且填写时优先输出变量内容
 *   （主线程解析，失败 / 留空回退插件自采集值；可解析为数值的同步
 *   用于状态分级与进度条，非数值文本仅展示）。
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

    /** 缓存写入时间。 */
    @Volatile
    private var cachedAtMillis: Long = 0L

    /** 取未过期缓存（无缓存 / 过期返回 null）。 */
    private fun peekCache(): ByteArray? {
        val ttl = RenderSettings.performanceCacheMillis()
        if (ttl <= 0) return null
        synchronized(cacheLock) {
            val cached = cachedPng ?: return null
            if (System.currentTimeMillis() - cachedAtMillis >= ttl) return null
            return cached
        }
    }

    /** 写入缓存（关闭缓存时清空旧缓存）。 */
    private fun storeCache(png: ByteArray) {
        synchronized(cacheLock) {
            if (RenderSettings.performanceCacheMillis() <= 0) {
                cachedPng = null
                return
            }
            cachedPng = png
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
                    // 0. 缓存命中直接复用（性能数据缓存，1.6.7.2 新增）
                    peekCache()?.let { cached ->
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

                    // 1b. 1.6.9.3：在线人数卡的最大人数（real = 真实值默认；
                    //     motd = 服务器列表展示值）；1.7.3.1 第三次覆盖版重做：
                    //     人数 N 与最大 M 成对切到展示值，捕获器改本机 SLP 自查询
                    //     （60 秒节流，查不到回退真实值，双端一致）
                    val (onlinePlayers, maxPlayers, fromMotd) =
                        resolveDisplayCounts(snapshot.onlinePlayers, snapshot.maxPlayers)

                    // 1c. 1.6.9.3：TPS 变量（variable-fetch.tps，开启且填写时
                    //     优先输出；主线程解析，失败 / 未配置回退自采集值）
                    val tpsVariableText = if (cn.kerong.penguin.spigot.stats.PlaceholderVariableService.isConfigured("tps")) {
                        cn.kerong.penguin.spigot.stats.PlaceholderVariableService.resolveOnMainThread {
                            cn.kerong.penguin.spigot.stats.PlaceholderVariableService.resolveServerTps()
                        }
                    } else {
                        null
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
                            onlinePlayersDisplay = onlinePlayers,
                            maxPlayers = maxPlayers,
                            maxPlayersFromMotd = fromMotd,
                            tpsVariableText = tpsVariableText,
                        )
                    )

                    // 3. 写入缓存（render.performance.cache-seconds 窗口内复用）
                    storeCache(png)

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

    /**
     * 展示值解析（1.6.9.3 新增 max-players-source；1.7.3.1 第三次覆盖版重做）：
     * - real（默认）：人数与最大人数用服务器真实值（快照携带）；
     * - motd：服务器列表展示值（[MotdDisplayTracker] 本机 SLP 自查询最近
     *   捕获，过期现场刷新；人数 N 与最大 M 成对切换，查不到回退真实值）；
     * 返回（展示在线人数, 展示最大人数, 是否为 MOTD 展示值）。
     */
    private fun resolveDisplayCounts(realOnline: Int?, realMax: Int?): Triple<Int?, Int?, Boolean> {
        val source = try {
            (org.bukkit.Bukkit.getPluginManager().getPlugin("KERONGPenguin") as? org.bukkit.plugin.java.JavaPlugin)
                ?.config?.getString("render.performance.max-players-source", "real")?.trim()?.lowercase()
        } catch (_: Throwable) {
            null
        }
        if (source == "motd") {
            cn.kerong.penguin.spigot.stats.MotdDisplayTracker.refreshIfStale()
            val online = cn.kerong.penguin.spigot.stats.MotdDisplayTracker.lastDisplayOnline
            val max = cn.kerong.penguin.spigot.stats.MotdDisplayTracker.lastDisplayMax
            if (online != null && max != null) {
                return Triple(online, max, true)
            }
        }
        return Triple(realOnline, realMax, false)
    }
}
