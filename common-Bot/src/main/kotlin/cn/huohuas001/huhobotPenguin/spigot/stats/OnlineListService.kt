package cn.huohuas001.huhobotPenguin.spigot.stats

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.huhobotPenguin.spigot.render.CardRenderPool
import cn.huohuas001.huhobotPenguin.spigot.render.InfoCardAssets
import cn.huohuas001.huhobotPenguin.spigot.render.OnlineListRenderer
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import org.bukkit.Bukkit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.awt.image.BufferedImage

/**
 * /查在线 命令图片输出服务（1.5.0 覆盖更新新增，1.5.1 起为 markdown 关闭时的默认输出）。
 *
 * 触发方式（1.5.1 简化）：motd.use-markdown 关闭（或模板缺失）时由 PublicCommands
 * 自动分流到本服务，不再需要 query-online.image-output 配置。
 *
 * 1. 全流程异步（1.5.1）：机器人消息线程只做 5 秒冷却判断，其余全部进入
 *    [CardRenderPool] —— 包括主线程在线名单快照（异步调用时自动切回主线程，
 *    3 秒超时保护），不再阻塞消息线程；
 * 2. 并发预热头像（4 线程小池 + 8 秒总预算，超时未取到的玩家渲染占位块，
 *    下次查询命中缓存后展示真实头像，避免大量玩家时首查等待过久）；
 * 3. [OnlineListRenderer] 渲染毛玻璃卡片（人数多自动切长图），
 *    背景与个人信息卡共用 img/ 目录随机图与缓存；长图背景处理高度封顶
 *    （[InfoCardAssets.ONLINE_BACKGROUND_MAX_HEIGHT]），渲染时拉伸铺满，
 *    控制内存占用；
 * 4. 渲染走 [CardRenderPool]（硬编码 CPU 上限），完成后自动节流 GC；
 * 5. 结果缓存 20 秒（在线名单不变时重复查询秒回），单用户 5 秒冷却防刷屏。
 */
object OnlineListService {

    /** 单用户查询冷却（毫秒）。 */
    private const val USER_COOLDOWN_MILLIS = 5_000L

    /** 头像预热线程数（网络 IO 密集，不占渲染 CPU 预算）。 */
    private const val AVATAR_FETCH_THREADS = 4

    /** 头像预热总预算（毫秒）：超时后未完成的玩家画占位块，不无限等待。 */
    private const val AVATAR_FETCH_BUDGET_MILLIS = 8_000L

    /** 渲染结果缓存秒数（在线名单未变化时复用；名单变化即换 key 自然失效）。 */
    private const val RESULT_TTL_MILLIS = 20_000L

    /** 结果缓存容量上限（防膨胀；超出先清过期再清空）。 */
    private const val RESULT_CACHE_MAX = 4

    /** 用户冷却记录：OpenId → 上次触发时间。 */
    private val lastQueryAt = ConcurrentHashMap<String, Long>()

    /** 在线玩家快照（主线程捕获）：名字 / UUID / 皮肤贴图 URL。 */
    private data class Snapshot(
        val players: List<Triple<String, UUID?, String?>>,
        val playerDataDir: File?,
    )

    /** 渲染结果缓存条目（key = 在线名单哈希，名单变化自然失效）。 */
    private data class CachedList(val bytes: ByteArray, val at: Long)

    private val resultCache = ConcurrentHashMap<Int, CachedList>()

    /** 头像预热线程池（惰性创建，守护线程）。 */
    private val avatarPool by lazy {
        Executors.newFixedThreadPool(AVATAR_FETCH_THREADS) { runnable ->
            Thread(runnable, "PenguinAvatarFetch").apply { isDaemon = true }
        }
    }

    /**
     * 渲染与发送全流程在共享渲染池执行（1.5.1：快照也移入池内），
     * 机器人消息线程仅做冷却判断，立即返回不阻塞。
     */
    fun handle(plugin: HuHoBot, event: GroupMessageEvent, qqOpenId: String) {
        // 查询冷却：同一用户 5 秒内只允许一次，防止刷屏导致渲染风暴
        val now = System.currentTimeMillis()
        val lastAt = lastQueryAt[qqOpenId] ?: 0L
        if (now - lastAt < USER_COOLDOWN_MILLIS) {
            replyText(event, "查询太频繁了，请稍后再试")
            return
        }
        lastQueryAt[qqOpenId] = now
        if (lastQueryAt.size > 1024) lastQueryAt.clear()

        try {
            // 1.5.1：全部工作（主线程快照 / 缓存判断 / 头像预热 / 渲染 / 发送）
            // 都在共享渲染池执行，消息线程不等待任何主线程或磁盘操作
            CardRenderPool.submit {
                try {
                    // 1. 主线程快照在线玩家（名字 / UUID / 皮肤 URL）
                    val snapshot = snapshotOnlinePlayers()
                    if (snapshot == null) {
                        replyText(event, "❌ 服务器数据暂不可用，请稍后再试")
                        return@submit
                    }

                    // 2. 名单未变化且缓存新鲜：直接复用上次渲染结果秒回
                    val cacheKey = snapshot.players.joinToString(",") { it.first }.hashCode()
                    val cached = resultCache[cacheKey]
                    if (cached != null && System.currentTimeMillis() - cached.at < RESULT_TTL_MILLIS) {
                        if (!QClient.replyWithImgBytes(event, cached.bytes)) {
                            replyText(event, "❌ 图片发送失败（机器人连接可能断开），请稍后重试")
                        }
                        return@submit
                    }

                    // 3. 并发预热头像（8 秒预算，超时的玩家画占位块）
                    val avatars = fetchAvatars(snapshot)
                    val entries = snapshot.players.map {
                        OnlineListRenderer.OnlineEntry(it.first, avatars[it.first])
                    }

                    // 4. 背景与渲染（人数多时高度自动加长为长图）；
                    //    长图背景处理高度封顶（1.5.1 内存限制），渲染器拉伸铺满
                    val height = OnlineListRenderer.measureHeight(entries.size)
                    val dataDirectory = plugin.configFile?.parentFile
                    val backgroundHeight = height.coerceAtMost(InfoCardAssets.ONLINE_BACKGROUND_MAX_HEIGHT)
                    val background = dataDirectory?.let {
                        InfoCardAssets.processedBackground(it, OnlineListRenderer.WIDTH, backgroundHeight)
                    }
                    val updateTime = SimpleDateFormat("HH:mm").format(Date())
                    val bytes = OnlineListRenderer.render(
                        OnlineListRenderer.OnlineListData(
                            serverName = plugin.serverName,
                            updateTime = updateTime,
                            entries = entries,
                            background = background,
                        )
                    )

                    // 5. 写入结果缓存（名单不变时 20 秒内重复查询复用）
                    if (bytes.isNotEmpty()) {
                        resultCache[cacheKey] = CachedList(bytes, System.currentTimeMillis())
                        if (resultCache.size > RESULT_CACHE_MAX) {
                            resultCache.entries.removeIf { System.currentTimeMillis() - it.value.at >= RESULT_TTL_MILLIS }
                            if (resultCache.size > RESULT_CACHE_MAX) resultCache.clear()
                        }
                    }

                    // 6. 发送图片
                    if (!QClient.replyWithImgBytes(event, bytes)) {
                        replyText(event, "❌ 图片发送失败（机器人连接可能断开），请稍后重试")
                    }
                } catch (error: Throwable) {
                    // 失败日志带堆栈前几帧且双写日志文件，与 /个人信息 一致可事后追溯
                    plugin.log_error("[查在线] 渲染在线列表失败: ${error.message}")
                    plugin.log_error(
                        "[查在线] 失败堆栈: " +
                            error.stackTraceToString().lineSequence().take(8).joinToString(" | ")
                    )
                    replyText(event, "❌ 在线列表图片生成失败，请稍后重试或联系管理员")
                }
            }
        } catch (_: Throwable) {
            // 理论上不会触发（队列无上限），兜底防止线程池异常时静默丢消息
            replyText(event, "当前查询人数较多，请稍后再试")
        }
    }

    /**
     * 主线程快照在线玩家（异步调用时自动切换；超时返回 null）。
     * 皮肤 URL 与个人信息卡片同源：在线玩家 Profile（含 SkinsRestorer 等修改结果）。
     */
    private fun snapshotOnlinePlayers(): Snapshot? {
        val playerDataDir = try {
            Bukkit.getWorlds().firstOrNull()?.worldFolder?.resolve("playerdata")
        } catch (_: Throwable) {
            null
        }
        val snapshot: () -> Snapshot = {
            val players = try {
                Bukkit.getOnlinePlayers().map { player ->
                    val skinUrl = try {
                        player.playerProfile.textures.skin?.toString()
                    } catch (_: Throwable) {
                        null
                    }
                    Triple(player.name, player.uniqueId, skinUrl)
                }
            } catch (_: Throwable) {
                emptyList()
            }
            Snapshot(players, playerDataDir)
        }
        if (Bukkit.isPrimaryThread()) return snapshot()
        val bukkitPlugin = Bukkit.getPluginManager().getPlugin("KERONGPenguin") ?: return null
        return try {
            Bukkit.getScheduler().callSyncMethod(bukkitPlugin) { snapshot() }
                .get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 并发预热头像：4 线程小池 + 总预算 8 秒。
     * 超时未完成的玩家返回 null（渲染占位块）；后续查询命中头像缓存后展示真实头像。
     * 头像获取本身带 TTL 缓存与失败负缓存（见 InfoCardAssets），网络失败不重试轰炸。
     */
    private fun fetchAvatars(snapshot: Snapshot): Map<String, BufferedImage> {
        if (snapshot.players.isEmpty()) return emptyMap()
        val tasks = snapshot.players.map { player ->
            Callable {
                player.first to InfoCardAssets.fetchAvatar(
                    InfoCardAssets.AvatarRequest(
                        uuid = player.second,
                        playerName = player.first,
                        skinUrl = player.third,
                        playerDataDir = snapshot.playerDataDir,
                    )
                )
            }
        }
        val result = mutableMapOf<String, BufferedImage>()
        try {
            val futures = avatarPool.invokeAll(tasks, AVATAR_FETCH_BUDGET_MILLIS, TimeUnit.MILLISECONDS)
            for (future in futures) {
                try {
                    val pair = future.get() ?: continue
                    pair.second?.let { face -> result[pair.first] = face }
                } catch (_: Throwable) {
                    // 单个玩家取头像失败/超时：跳过，渲染占位块
                }
            }
        } catch (_: Throwable) {
            // invokeAll 整体异常（如池已关闭）：全部占位块兜底
        }
        return result
    }

    /** 纯文本回复（不 @）。 */
    private fun replyText(event: GroupMessageEvent, message: String) {
        try {
            event.sendMessage(message)
        } catch (_: Throwable) {
        }
    }
}
