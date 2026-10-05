package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import cn.huohuas001.huhobotPenguin.spigot.render.CardRenderPool
import cn.huohuas001.huhobotPenguin.spigot.render.InfoCardAssets
import cn.huohuas001.huhobotPenguin.spigot.render.LeaderboardRenderer
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
 * /在线排行榜 —— 总在线时长排行榜图片（1.5.5 新增）。
 *
 * 每名玩家一行：排名徽章（前三名金 / 银 / 铜）+ 真实皮肤头像 + 玩家名 +
 * 总在线时长；背景随机取插件目录 img/ 图片（与其他卡片共用素材与缓存）。
 *
 * 数据来源：QUUID 主文件 stats.play-seconds（全部玩家，含离线；
 * 统计每 60 秒增量落盘，排行榜数值最多滞后一分钟，展示精度足够）。
 *
 * 命令开关：commands.在线排行榜（默认 true，关闭后群命令不再响应，
 * 快捷菜单按钮与 /帮助 中的条目同步消失）；
 * 显示人数：qq-bind.leaderboard.top（默认 20，自动夹逼 1..100）。
 *
 * 分段发送：超过 [LeaderboardRenderer.ROWS_PER_PAGE] 行时自动切页 ——
 * 第一张带标题区，后续各张只绘制行内容；逐张发送间隔 [SEND_INTERVAL_MILLIS]
 * 避免触发平台频控。
 */
class LeaderboardCommands : CommandSupport() {

    companion object {
        /** 单用户查询冷却（毫秒）。 */
        private const val USER_COOLDOWN_MILLIS = 5_000L

        /** 多张图片相邻发送间隔（毫秒），对频控友好。 */
        private const val SEND_INTERVAL_MILLIS = 400L

        /** 头像预热线程数（网络 IO 密集，不占渲染 CPU 预算）。 */
        private const val AVATAR_FETCH_THREADS = 4

        /** 头像预热总预算（毫秒）。 */
        private const val AVATAR_FETCH_BUDGET_MILLIS = 8_000L

        /** 渲染结果缓存秒数（统计每 60 秒落盘，期间重复查询复用整组图片）。 */
        private const val RESULT_TTL_MILLIS = 60_000L

        /** 用户冷却记录：OpenId → 上次触发时间。 */
        private val lastQueryAt = ConcurrentHashMap<String, Long>()

        /** 结果缓存（整组分页图片；单一全局条目，带生成时间）。 */
        private var cachedPages: List<ByteArray> = emptyList()
        private var cachedAt: Long = 0L

        /** 头像预热线程池（惰性创建，守护线程）。 */
        private val avatarPool by lazy {
            Executors.newFixedThreadPool(AVATAR_FETCH_THREADS) { runnable ->
                Thread(runnable, "PenguinAvatarFetch").apply { isDaemon = true }
            }
        }
    }

    /** 原始条目（扫描出的未排序数据）。 */
    private data class RawEntry(val name: String, val uuid: UUID?, val seconds: Long)

    @Commands("在线排行榜")
    fun leaderboard(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        // 查询冷却：同一用户 5 秒内只允许一次
        val qq = try {
            userId(event)
        } catch (_: Throwable) {
            "<unknown>"
        }
        val cooldownKey = if (qq.isEmpty() || qq == "<unknown>") "group:${groupId(event)}" else qq
        val now = System.currentTimeMillis()
        val lastAt = lastQueryAt[cooldownKey] ?: 0L
        if (now - lastAt < USER_COOLDOWN_MILLIS) {
            sendDirect(event, "查询太频繁了，请稍后再试")
            return
        }
        lastQueryAt[cooldownKey] = now
        if (lastQueryAt.size > 1024) lastQueryAt.clear()

        try {
            // 全流程进入共享渲染池：磁盘扫描 / 头像预热 / 渲染 / 发送，
            // 机器人消息线程不等待任何磁盘或网络操作
            CardRenderPool.submit {
                try {
                    val manager = try {
                        QqBindManager.getInstance()
                    } catch (_: Throwable) {
                        null
                    }
                    if (manager == null) {
                        sendDirect(event, "❌ 管理器未就绪，请稍后再试")
                        return@submit
                    }

                    // 1. 全量扫描 QUUID 主文件统计（含离线玩家）
                    val entries = collectEntries(manager)
                    if (entries.isEmpty()) {
                        sendDirect(event, "📋 暂无在线时长统计数据")
                        return@submit
                    }
                    val totalPlayers = entries.size.toLong()
                    val top = manager.leaderboardTop
                    val topEntries = entries.take(top)

                    // 2. 缓存未过期且人数未变：直接复用整组图片
                    if (cachedPages.isNotEmpty() &&
                        System.currentTimeMillis() - cachedAt < RESULT_TTL_MILLIS
                    ) {
                        sendPages(event, cachedPages)
                        return@submit
                    }

                    // 3. 主线程快照在线玩家（头像实时皮肤增强）+ 并发预热头像
                    val onlineSkins = snapshotOnlineSkins()
                    val playerDataDir = try {
                        Bukkit.getWorlds().firstOrNull()?.worldFolder?.resolve("playerdata")
                    } catch (_: Throwable) {
                        null
                    }
                    val avatars = fetchAvatars(topEntries, onlineSkins, playerDataDir)

                    // 4. 分页渲染（第一页带标题，续页只画行内容）
                    val updateTime = SimpleDateFormat("HH:mm").format(Date())
                    val dataDirectory = plugin.configFile?.parentFile
                    val pages = ArrayList<ByteArray>(4)
                    var pageStart = 0
                    while (pageStart < topEntries.size) {
                        val pageEnd = minOf(pageStart + LeaderboardRenderer.ROWS_PER_PAGE, topEntries.size)
                        val pageEntries = topEntries.subList(pageStart, pageEnd).mapIndexed { offset, raw ->
                            LeaderboardRenderer.LeaderEntry(
                                rank = pageStart + offset + 1,
                                name = raw.name,
                                playSeconds = raw.seconds,
                                avatar = avatars[raw.name],
                            )
                        }
                        val height = LeaderboardRenderer.measurePageHeight(pageEntries.size, pages.isEmpty())
                        val backgroundHeight = height.coerceAtMost(InfoCardAssets.ONLINE_BACKGROUND_MAX_HEIGHT)
                        val background = dataDirectory?.let {
                            InfoCardAssets.processedBackground(it, LeaderboardRenderer.WIDTH, backgroundHeight)
                        }
                        val bytes = LeaderboardRenderer.renderPage(
                            LeaderboardRenderer.LeaderboardData(
                                serverName = plugin.serverName,
                                updateTime = updateTime,
                                entries = pageEntries,
                                totalPlayers = totalPlayers,
                                topLimit = top,
                                background = background,
                            ),
                            firstPage = pages.isEmpty(),
                        )
                        if (bytes.isNotEmpty()) pages.add(bytes)
                        pageStart = pageEnd
                    }
                    if (pages.isEmpty()) {
                        sendDirect(event, "❌ 排行榜图片生成失败，请稍后重试或联系管理员")
                        return@submit
                    }

                    // 5. 写入结果缓存（60 秒内重复查询整组复用，含多页场景）
                    cachedPages = pages
                    cachedAt = System.currentTimeMillis()

                    // 6. 逐张发送（相邻间隔 400ms）
                    sendPages(event, pages)
                } catch (error: Throwable) {
                    // 失败日志带堆栈前几帧，与 /查在线 / /查信息 一致可事后追溯
                    plugin.log_error("[在线排行榜] 渲染失败: ${error.message}")
                    plugin.log_error(
                        "[在线排行榜] 失败堆栈: " +
                            error.stackTraceToString().lineSequence().take(8).joinToString(" | ")
                    )
                    sendDirect(event, "❌ 排行榜图片生成失败，请稍后重试或联系管理员")
                }
            }
        } catch (_: Throwable) {
            // 理论上不会触发（队列无上限），兜底防止线程池异常时静默丢消息
            sendDirect(event, "当前查询人数较多，请稍后再试")
        }
    }

    /** 全量扫描 QUUID 主文件：玩家名 + UUID + 总在线秒数，按秒数降序（并列按名字）。 */
    private fun collectEntries(manager: QqBindManager): List<RawEntry> {
        val entries = ArrayList<RawEntry>()
        for (quuid in manager.listQuuids()) {
            val yaml = manager.readQuuidRecord(quuid) ?: continue
            val name = yaml.getString("playerName", "") ?: ""
            if (name.isEmpty()) continue
            val seconds = try {
                yaml.getLong("stats.play-seconds", 0L)
            } catch (_: Throwable) {
                0L
            }
            if (seconds <= 0L) continue
            val uuidStr = yaml.getString("playerUuid", "")
            val uuid = try {
                if (uuidStr.isNullOrEmpty()) null else UUID.fromString(uuidStr)
            } catch (_: Throwable) {
                null
            }
            entries.add(RawEntry(name, uuid, seconds))
        }
        entries.sortWith(compareByDescending<RawEntry> { it.seconds }.thenBy { it.name })
        return entries
    }

    /** 主线程快照在线玩家（异步调用时自动切换；超时 / 失败返回空表，不影响流程）。 */
    private fun snapshotOnlineSkins(): Map<String, Pair<UUID?, String?>> {
        val snapshot: () -> Map<String, Pair<UUID?, String?>> = {
            try {
                Bukkit.getOnlinePlayers().associate { player ->
                    val skinUrl = try {
                        player.playerProfile.textures.skin?.toString()
                    } catch (_: Throwable) {
                        null
                    }
                    player.name to (player.uniqueId to skinUrl)
                }
            } catch (_: Throwable) {
                emptyMap()
            }
        }
        if (Bukkit.isPrimaryThread()) return snapshot()
        val bukkitPlugin = try {
            Bukkit.getPluginManager().getPlugin("KERONGPenguin")
        } catch (_: Throwable) {
            null
        } ?: return emptyMap()
        return try {
            Bukkit.getScheduler().callSyncMethod(bukkitPlugin) { snapshot() }
                .get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * 并发预热头像：在线玩家优先用实时皮肤 URL（含 SkinsRestorer 等修改结果），
     * 其余走离线四级链路（playerdata NBT → Mojang 会话 → 按名查）。
     * 超时未取到的玩家渲染占位块；头像缓存 10 分钟，重复查询秒回。
     */
    private fun fetchAvatars(
        entries: List<RawEntry>,
        onlineSkins: Map<String, Pair<UUID?, String?>>,
        playerDataDir: File?,
    ): Map<String, BufferedImage> {
        if (entries.isEmpty()) return emptyMap()
        val tasks = entries.map { entry ->
            Callable {
                val online = onlineSkins[entry.name]
                entry.name to InfoCardAssets.fetchAvatar(
                    InfoCardAssets.AvatarRequest(
                        uuid = entry.uuid ?: online?.first,
                        playerName = entry.name,
                        skinUrl = online?.second,
                        playerDataDir = playerDataDir,
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

    /** 逐张发送分页图片（相邻间隔 [SEND_INTERVAL_MILLIS]）；任一张失败提示重试。 */
    private fun sendPages(event: GroupMessageEvent, pages: List<ByteArray>) {
        var allSent = true
        for ((index, page) in pages.withIndex()) {
            if (index > 0) {
                try {
                    Thread.sleep(SEND_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
            }
            if (!QClient.replyWithImgBytes(event, page)) {
                allSent = false
                break
            }
        }
        if (!allSent) {
            replyText(event, "❌ 图片发送失败（机器人连接可能断开），请稍后重试")
        }
    }

    /** 纯文本回复（不 @）。 */
    private fun replyText(event: GroupMessageEvent, message: String) {
        try {
            event.sendMessage(message)
        } catch (_: Throwable) {
        }
    }

    /** 带 @ 提及的 Markdown 回复（失败时退化为纯文本）。 */
    private fun sendDirect(event: GroupMessageEvent, message: String) {
        try {
            val senderId = try {
                userId(event)
            } catch (_: Throwable) {
                null
            }
            val content = if (!senderId.isNullOrEmpty() && senderId != "<unknown>") "<@$senderId>\n$message" else message
            try {
                QClient.replyMarkdown(event, content, null)
            } catch (_: Throwable) {
                event.sendMessage(content)
            }
        } catch (_: Throwable) {
            try {
                event.sendMessage(message)
            } catch (_: Throwable) {
            }
        }
    }
}
