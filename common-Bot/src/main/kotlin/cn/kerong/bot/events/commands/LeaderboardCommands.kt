package cn.kerong.bot.events.commands

import cn.kerong.bot.KERONG
import cn.kerong.bot.QClient
import cn.kerong.penguin.spigot.qqbind.MessageTemplates
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import cn.kerong.penguin.spigot.render.CardRenderPool
import cn.kerong.penguin.spigot.render.InfoCardAssets
import cn.kerong.penguin.spigot.render.LeaderboardRenderer
import cn.kerong.penguin.spigot.render.RenderSettings
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import org.bukkit.Bukkit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.awt.image.BufferedImage

/**
 * /在线排行榜 —— 总在线时长排行榜图片。
 *
 * 每名玩家一行：排名徽章（前三名金 / 银 / 铜）+ 真实皮肤头像 + 玩家名 +
 * 总在线时长。
 *
 * 背景：默认使用内置专属背景图（深灰色主体 + 深绿色装饰，随 JAR
 * 分发，零配置零素材）；leaderboard.builtin-background 关闭后改用
 * 插件目录 img/ 随机图（img 无可用图片时自动回退内置图）。
 *
 * 数据来源：QUUID 主文件 stats.play-seconds（全部玩家，含离线；
 * 统计每 60 秒增量落盘，排行榜数值最多滞后一分钟，展示精度足够）。
 *
 * 命令开关：commands.在线排行榜（默认 true，关闭后群命令不再响应，
 * 快捷菜单按钮与 /菜单 里的按钮同步消失）；
 * 显示人数：leaderboard.top（起默认 15，自动夹逼 1..100）。
 *
 * 冷却（可配置）：同一用户两次查询的最小间隔秒数，配置
 * render.command-cooldown-seconds（默认 5 秒，0 = 关闭）。
 * 提示文本模板：manager-not-ready-retry / leaderboard-no-data /
 * leaderboard-render-failed / image-send-failed / server-busy（messages 段）。
 *
 * 变量获取信息同步生效——variable-fetch 开启且填写了
 * 玩家名（player-name）/ 总在线时长（total-playtime）变量时，榜单行
 * 展示名与时长文本改为变量返回内容（主线程快照内批量解析，
 * 失败 / 留空回退插件自带数据；头像 / 排名仍按真实玩家）。
 *
 * 分段发送：超过单页上限时自动切页 —— 1.7.3 起单页上限走全局配置
 * leaderboard.page-size（RenderSettings.leaderboardPageSize，默认 20；
 * 旧版硬编码 15）——
 * 第一张带标题区，后续各张只绘制行内容；逐张发送间隔 [SEND_INTERVAL_MILLIS]
 * 避免触发平台频控。
 */
class LeaderboardCommands : CommandSupport() {

    companion object {
        /** 多张图片相邻发送间隔（毫秒），对频控友好。 */
        private const val SEND_INTERVAL_MILLIS = 400L

        /** 渲染结果缓存秒数（统计每 60 秒落盘，期间重复查询复用整组图片）。 */
        private const val RESULT_TTL_MILLIS = 60_000L

        /** 用户冷却记录：OpenId → 上次触发时间。 */
        private val lastQueryAt = ConcurrentHashMap<String, Long>()

        /** 结果缓存（整组分页图片；单一全局条目，带生成时间）。 */
        private var cachedPages: List<ByteArray> = emptyList()
        private var cachedAt: Long = 0L
    }

    /** 原始条目（扫描出的未排序数据）。 */
    private data class RawEntry(val name: String, val uuid: UUID?, val seconds: Long)

    @Commands("在线排行榜")
    fun leaderboard(plugin: KERONG, event: GroupMessageEvent, params: String) {
        // 查询冷却（1.6.0：秒数可配置 render.command-cooldown-seconds，默认 5）
        val qq = try {
            userId(event)
        } catch (_: Throwable) {
            "<unknown>"
        }
        val cooldownKey = if (qq.isEmpty() || qq == "<unknown>") "group:${groupId(event)}" else qq
        val cooldownMillis = RenderSettings.commandCooldownMillis()
        val now = System.currentTimeMillis()
        if (cooldownMillis > 0) {
            val lastAt = lastQueryAt[cooldownKey] ?: 0L
            if (now - lastAt < cooldownMillis) {
                // 与旧版一致：命令层的频控提示带 @ 提及（服务层 replyText 为纯文本）
                MessageTemplates.sendDirect(event, "query-too-frequent")
                return
            }
            lastQueryAt[cooldownKey] = now
            if (lastQueryAt.size > 1024) lastQueryAt.clear()
        }

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
                        MessageTemplates.sendDirect(event, "manager-not-ready-retry")
                        return@submit
                    }

                    // 1. 全量扫描 QUUID 主文件统计（含离线玩家）
                    val entries = collectEntries(manager)
                    if (entries.isEmpty()) {
                        MessageTemplates.sendDirect(event, "leaderboard-no-data")
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

                    // 3. 主线程快照在线玩家（头像实时皮肤增强）+ 并发预热头像；
 // 变量获取（玩家名 / 总在线时长，同一次主线程
                    //    语义内解析，仅对展示的 top 条目）
                    val onlineUuids = snapshotOnlineUuids()
                    val variableOverrides = snapshotVariableOverrides(topEntries)
                    val avatars = fetchAvatars(topEntries, onlineUuids)

                    // 4. 分页渲染（第一页带标题，续页只画行内容）
                    val updateTime = SimpleDateFormat("HH:mm").format(Date())
                    val dataDirectory = plugin.configFile?.parentFile
                    val useBuiltinBackground = manager.leaderboardBuiltinBackground
                    val pages = ArrayList<ByteArray>(4)
                    var pageStart = 0
                    while (pageStart < topEntries.size) {
 // 单页上限改走全局配置 leaderboard.page-size（默认 20）
                        val pageEnd = minOf(pageStart + RenderSettings.leaderboardPageSize(), topEntries.size)
                        val pageEntries = topEntries.subList(pageStart, pageEnd).mapIndexed { offset, raw ->
                            LeaderboardRenderer.LeaderEntry(
                                rank = pageStart + offset + 1,
                                name = variableOverrides[raw.name]?.first ?: raw.name,
                                playSeconds = raw.seconds,
                                avatar = avatars[raw.name],
                                valueText = variableOverrides[raw.name]?.second,
                            )
                        }
                        val height = LeaderboardRenderer.measurePageHeight(pageEntries.size, pages.isEmpty())
 // 背景：统一走 resolveBackground —— 默认内置专属图
                        // （深灰主体 + 深绿色装饰，随 JAR 分发）；开关关闭后随机用 img/
                        // 目录图片，img 无可用图片时同样回退内置图（长图封顶 1600px 防栅格膨胀）
                        val background = InfoCardAssets.resolveBackground(
                            dataDirectory,
                            LeaderboardRenderer.WIDTH,
                            height,
                            useBuiltinBackground,
                            InfoCardAssets.BUILTIN_BACKGROUND_LEADERBOARD,
                        )
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
                        MessageTemplates.sendDirect(event, "leaderboard-render-failed")
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
                    MessageTemplates.sendDirect(event, "leaderboard-render-failed")
                }
            }
        } catch (_: Throwable) {
            // 理论上不会触发（队列无上限），兜底防止线程池异常时静默丢消息
            MessageTemplates.sendPlain(event, "server-busy")
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
    private fun snapshotOnlineUuids(): Map<String, UUID?> {
        val snapshot: () -> Map<String, UUID?> = {
            try {
                Bukkit.getOnlinePlayers().associate { player ->
                    player.name to player.uniqueId
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
 // 经 FoliaCompat 桥接（Folia 全局区域线程限时取值，超时回退空表）
        return try {
            cn.kerong.penguin.spigot.scheduler.FoliaCompat.callSync(bukkitPlugin, 3L) { snapshot() } ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
 * 主线程快照解析 top 玩家的变量覆盖（玩家名 / 总在线时长）。
     * 开关关闭或两键均未配置时零开销返回空表；超时 / 异常同样空表
     * （回退插件自带数据，不影响榜单流程）。
     */
    private fun snapshotVariableOverrides(entries: List<RawEntry>): Map<String, Pair<String?, String?>> {
        val enabled = try {
            cn.kerong.penguin.spigot.stats.PlaceholderVariableService.isEnabled()
        } catch (_: Throwable) {
            false
        }
        if (!enabled) return emptyMap()
        val snapshot: () -> Map<String, Pair<String?, String?>> = {
            cn.kerong.penguin.spigot.stats.PlaceholderVariableService.resolveBatch(
                entries.map { it.name to it.uuid },
                nameKey = "player-name",
                valueKey = "total-playtime",
            )
        }
        if (Bukkit.isPrimaryThread()) return snapshot()
        val bukkitPlugin = try {
            Bukkit.getPluginManager().getPlugin("KERONGPenguin")
        } catch (_: Throwable) {
            null
        } ?: return emptyMap()
        return try {
            cn.kerong.penguin.spigot.scheduler.FoliaCompat.callSync(bukkitPlugin, 3L) { snapshot() } ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
 * 并发预热头像：统一走 InfoCardAssets.fetchAvatarBatch（重做并发
     * 策略：8 线程共享池 + 12 秒渲染等待预算，超预算不中断在途下载——后台
     * 完成后进缓存，下一轮查询即显示真实头像）；超预算未取到的玩家本轮
     * 渲染占位块；头像缓存 10 分钟，重复查询秒回。
     */
    private fun fetchAvatars(
        entries: List<RawEntry>,
        onlineUuids: Map<String, UUID?>,
    ): Map<String, BufferedImage> {
        if (entries.isEmpty()) return emptyMap()
        return InfoCardAssets.fetchAvatarBatch(
            entries.map {
                InfoCardAssets.AvatarRequest(
                    uuid = it.uuid ?: onlineUuids[it.name],
                    playerName = it.name,
                )
            }
        )
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
            MessageTemplates.sendPlain(event, "image-send-failed")
        }
    }
}
