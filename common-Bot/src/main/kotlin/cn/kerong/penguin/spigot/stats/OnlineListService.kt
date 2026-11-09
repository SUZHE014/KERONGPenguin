package cn.kerong.penguin.spigot.stats

import cn.kerong.bot.KERONG
import cn.kerong.bot.QClient
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import cn.kerong.penguin.spigot.render.CardRenderPool
import cn.kerong.penguin.spigot.render.InfoCardAssets
import cn.kerong.penguin.spigot.render.OnlineListRenderer
import cn.kerong.penguin.spigot.scheduler.FoliaCompat
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import org.bukkit.Bukkit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.awt.image.BufferedImage

/**
 * /查在线 命令图片输出服务（为 markdown 关闭时的默认输出）。
 *
 * 触发方式（简化）：motd.use-markdown 关闭（或模板缺失）时由 PublicCommands
 * 自动分流到本服务，不再需要 query-online.image-output 配置。
 *
 * 1. 全流程异步：机器人消息线程只做冷却判断（起秒数可配置
 *    render.command-cooldown-seconds，默认 5，0 = 关闭），其余全部进入
 *    [CardRenderPool] —— 包括主线程在线名单快照（异步调用时自动切回主线程，
 *    3 秒超时保护），不再阻塞消息线程；
 * 2. 并发预热头像（4 线程小池 + 8 秒总预算，超时未取到的玩家渲染占位块，
 *    下次查询命中缓存后展示真实头像，避免大量玩家时首查等待过久）；
 * 3. [OnlineListRenderer] 渲染毛玻璃卡片，背景 1.5.7 起默认内置深灰底图
 *    （render.online-list-builtin-background，1.6.0 起位于顶层 render 段，
 *    随 JAR 分发，与玩家名片卡片共用横版底图与缓存）；开关关闭后随机用
 *    img/ 目录图片，img 无可用图片时自动回退内置图；长图背景处理高度封顶
 *    （[InfoCardAssets.ONLINE_BACKGROUND_MAX_HEIGHT]），渲染时拉伸铺满，
 *    控制内存占用；
 * 4. 分段发送：超过 [OnlineListRenderer.MAX_PLAYERS_PER_PAGE] 人自动
 *    切页多张发送 —— 第一张带标题，后续只画内容，相邻间隔 400ms 对频控友好；
 * 5. 渲染走 [CardRenderPool]（硬编码 CPU 上限），完成后自动节流 GC；
 * 6. 结果缓存 20 秒（在线名单不变时重复查询秒回，整组图片复用），
 *    单用户冷却防刷屏（秒数可配置）。1.6.0：全部文本提示改走消息模板
 *    （online-data-unavailable / online-render-failed / image-send-failed /
 *    server-busy / query-too-frequent）。
 */
object OnlineListService {

    /** 渲染结果缓存秒数（在线名单未变化时复用；名单变化即换 key 自然失效）。 */
    private const val RESULT_TTL_MILLIS = 20_000L

    /** 结果缓存容量上限（防膨胀；超出先清过期再清空）。 */
    private const val RESULT_CACHE_MAX = 4

 /** 多张图片相邻发送间隔（毫秒），对频控友好（分段发送）。 */
    private const val SEND_INTERVAL_MILLIS = 400L

    /** 用户冷却记录：OpenId → 上次触发时间。 */
    private val lastQueryAt = ConcurrentHashMap<String, Long>()

    /** 在线玩家快照（主线程捕获）：名字 / UUID。 */
    private data class Snapshot(
        val players: List<Pair<String, UUID?>>,
    )

    /** 渲染结果缓存条目（key = 在线名单哈希，名单变化自然失效；1.5.5 起存整组分页）。 */
    private data class CachedList(val pages: List<ByteArray>, val at: Long)

    private val resultCache = ConcurrentHashMap<Int, CachedList>()

    /**
     * 渲染与发送全流程在共享渲染池执行（1.5.1：快照也移入池内），
     * 机器人消息线程仅做冷却判断，立即返回不阻塞。
     */
    fun handle(plugin: KERONG, event: GroupMessageEvent, qqOpenId: String) {
        // 查询冷却（1.6.0：秒数可配置 render.command-cooldown-seconds，默认 5，0 = 关闭）
        val cooldownMillis = cn.kerong.penguin.spigot.render.RenderSettings.commandCooldownMillis()
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
 // 全部工作（主线程快照 / 缓存判断 / 头像预热 / 渲染 / 发送）
            // 都在共享渲染池执行，消息线程不等待任何主线程或磁盘操作
            CardRenderPool.submit {
                try {
                    // 1. 主线程快照在线玩家（名字 / UUID / 皮肤 URL）
                    val snapshot = snapshotOnlinePlayers()
                    if (snapshot == null) {
                        cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "online-data-unavailable")
                        return@submit
                    }

                    // 2. 名单未变化且缓存新鲜：直接复用上次整组渲染结果秒回
                    val cacheKey = snapshot.players.joinToString(",") { it.first }.hashCode()
                    val cached = resultCache[cacheKey]
                    if (cached != null && System.currentTimeMillis() - cached.at < RESULT_TTL_MILLIS) {
                        if (!sendPages(event, cached.pages)) {
                            cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "image-send-failed")
                        }
                        return@submit
                    }

                    // 3. 并发预热头像（8 线程共享池 + 12 秒渲染等待预算：超预算的
                    //    玩家本轮画占位块，但下载不中断，后台完成后进缓存）
                    val avatars = fetchAvatars(snapshot)
                    val entries = snapshot.players.map {
                        OnlineListRenderer.OnlineEntry(it.first, avatars[it.first])
                    }

 // 4. 背景与渲染（分段发送：人数过多自动切页多张，
                    //    首页带标题、续页只画内容）；1.5.7 统一背景入口：
                    //    默认内置深灰底图（随 JAR 分发），开关关闭后随机用
                    //    img/ 目录图片，img 无可用图片时回退内置图；
 // 背景处理高度封顶（内存限制），渲染时 cover 裁剪铺满。
 // 多页改为每页独立重新抽背景
                    //    （backgroundProvider 按各页画布高度各调一次，不再
                    //    整组共用同一张——自定义 img/ 随机图时第 2 页起换图，
                    //    与在线排行榜 / 财富榜分页行为一致；内置背景与单页
                    //    场景观感不变）
                    val dataDirectory = plugin.configFile?.parentFile
                    val bindManager = try {
                        QqBindManager.getInstance()
                    } catch (_: Throwable) {
                        null
                    }
                    val useBuiltinBackground = bindManager?.onlineListBuiltinBackground ?: true
                    val updateTime = SimpleDateFormat("HH:mm").format(Date())
                    val pages = OnlineListRenderer.renderPages(
                        OnlineListRenderer.OnlineListData(
                            serverName = plugin.serverName,
                            updateTime = updateTime,
                            entries = entries,
                            backgroundProvider = { pageHeight ->
                                InfoCardAssets.resolveBackground(
                                    dataDirectory,
                                    OnlineListRenderer.WIDTH,
                                    pageHeight,
                                    useBuiltinBackground,
                                )
                            },
                        )
                    )

                    // 5. 写入结果缓存（名单不变时 20 秒内重复查询复用整组）
                    if (pages.isNotEmpty()) {
                        resultCache[cacheKey] = CachedList(pages, System.currentTimeMillis())
                        if (resultCache.size > RESULT_CACHE_MAX) {
                            resultCache.entries.removeIf { System.currentTimeMillis() - it.value.at >= RESULT_TTL_MILLIS }
                            if (resultCache.size > RESULT_CACHE_MAX) resultCache.clear()
                        }
                    }

                    // 6. 逐张发送（相邻间隔 400ms）
                    if (pages.isEmpty() || !sendPages(event, pages)) {
                        cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "image-send-failed")
                    }
                } catch (error: Throwable) {
                    // 失败日志带堆栈前几帧且双写日志文件，与 /玩家名片 一致可事后追溯
                    plugin.log_error("[查在线] 渲染在线列表失败: ${error.message}")
                    plugin.log_error(
                        "[查在线] 失败堆栈: " +
                            error.stackTraceToString().lineSequence().take(8).joinToString(" | ")
                    )
                    cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "online-render-failed")
                }
            }
        } catch (_: Throwable) {
            // 理论上不会触发（队列无上限），兜底防止线程池异常时静默丢消息
            cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "server-busy")
        }
    }

    /**
     * 主线程快照在线玩家（异步调用时自动切换；超时返回 null）。
     */
    private fun snapshotOnlinePlayers(): Snapshot? {
        val snapshot: () -> Snapshot = {
            val players = try {
                Bukkit.getOnlinePlayers().map { player ->
                    player.name to player.uniqueId
                }
            } catch (_: Throwable) {
                emptyList()
            }
            Snapshot(players)
        }
        if (Bukkit.isPrimaryThread()) return snapshot()
        val bukkitPlugin = Bukkit.getPluginManager().getPlugin("KERONGPenguin") ?: return null
 // 经 FoliaCompat 桥接（Folia 全局区域线程限时取值，超时回退 null）
        return FoliaCompat.callSync(bukkitPlugin, 3L) { snapshot() }
    }

    /**
 * 并发预热头像：统一走 [InfoCardAssets.fetchAvatarBatch]（重做并发
     * 策略：8 线程共享池 + 12 秒渲染等待预算，超预算不中断在途下载——后台
     * 完成后进缓存，下一轮查询即显示真实头像）；超预算/失败的玩家本轮画
     * 占位块；头像获取本身带 TTL 缓存与失败负缓存（见 InfoCardAssets）。
     */
    private fun fetchAvatars(snapshot: Snapshot): Map<String, BufferedImage> {
        if (snapshot.players.isEmpty()) return emptyMap()
        return InfoCardAssets.fetchAvatarBatch(
            snapshot.players.map {
                InfoCardAssets.AvatarRequest(uuid = it.second, playerName = it.first)
            }
        )
    }

    /** 逐张发送分页图片（相邻间隔 [SEND_INTERVAL_MILLIS]）；返回是否全部发送成功。 */
    private fun sendPages(event: GroupMessageEvent, pages: List<ByteArray>): Boolean {
        for ((index, page) in pages.withIndex()) {
            if (index > 0) {
                try {
                    Thread.sleep(SEND_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return false
                }
            }
            if (!QClient.replyWithImgBytes(event, page)) return false
        }
        return true
    }
}
