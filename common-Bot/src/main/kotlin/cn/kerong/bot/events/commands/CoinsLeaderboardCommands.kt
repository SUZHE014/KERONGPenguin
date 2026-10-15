package cn.kerong.bot.events.commands

import cn.kerong.bot.KERONG
import cn.kerong.bot.QClient
import cn.kerong.penguin.spigot.qqbind.EconomyName
import cn.kerong.penguin.spigot.qqbind.MessageTemplates
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import cn.kerong.penguin.spigot.render.CardRenderPool
import cn.kerong.penguin.spigot.render.InfoCardAssets
import cn.kerong.penguin.spigot.render.LeaderboardRenderer
import cn.kerong.penguin.spigot.render.RenderSettings
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
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
 * /财富榜 —— 金币持有量排行榜图片（1.6.0 新增，1.6.2 由 /金币榜 更名）。
 *
 * 与 /在线排行榜 同一套渲染器（[LeaderboardRenderer]）：每名玩家一行，
 * 排名徽章（前三名金 / 银 / 铜）+ 真实皮肤头像 + 玩家名 + 右侧金币数量；
 * 主标题与列表头使用经济名称（economy-alias 配置别名后自动替换「金币」二字，
 * 默认为「金币排行榜」/「金币 TOP N」）。
 *
 * 1.6.2 更名：命令由 /金币榜 改为 /财富榜，快捷菜单按钮同步更名；
 * 旧命令 /金币榜 保留为隐藏别名（不进入快捷菜单，与主命令共用开关，
 * 配置里的 commands.金币榜 升级时自动迁移为 commands.财富榜），
 * 旧用户使用习惯不受影响。消息模板文件名仍为 coins-leaderboard-*.md
 * （模板 ID 与命令名解耦，已自定义模板的用户升级后不丢失）。
 *
 * 配置与在线排行榜一致（coins-leaderboard 段）：
 * - coins-leaderboard.top：显示人数（默认 15，夹逼 1..100，超出自动分页）；
 * - coins-leaderboard.builtin-background：背景开关（默认 true = 内置专属底图，
 *   与在线排行榜共用 leaderboard-default.png；false 时随机用 img/ 目录图片）。
 *
 * 数据来源：QUUID 主文件全部玩家（含离线）+ Vault 经济余额（反射调用，
 * 兼容各经济插件）。NeoForge 模组版无 Vault 生态，命令回复
 * 「未安装经济插件」提示（模板 coins-leaderboard-no-economy）。
 *
 * 命令开关：commands.财富榜（默认 true）。
 * 冷却（1.6.0 起可配置）：render.command-cooldown-seconds（默认 5 秒，0 = 关闭）。
 * 提示文本模板：coins-leaderboard-no-economy / coins-leaderboard-no-data /
 * coins-leaderboard-render-failed / manager-not-ready-retry /
 * query-too-frequent / image-send-failed / server-busy（messages 段）。
 */
class CoinsLeaderboardCommands : CommandSupport() {

    companion object {
        /** 多张图片相邻发送间隔（毫秒），对频控友好（与在线排行榜一致）。 */
        private const val SEND_INTERVAL_MILLIS = 400L

        /** 头像预热线程数（网络 IO 密集，不占渲染 CPU 预算）。 */
        private const val AVATAR_FETCH_THREADS = 4

        /** 头像预热总预算（毫秒）。 */
        private const val AVATAR_FETCH_BUDGET_MILLIS = 8_000L

        /** 渲染结果缓存秒数（余额变动不频繁，期间重复查询复用整组图片）。 */
        private const val RESULT_TTL_MILLIS = 60_000L

        /** 用户冷却记录：OpenId → 上次触发时间。 */
        private val lastQueryAt = ConcurrentHashMap<String, Long>()

        /** 结果缓存（整组分页图片；单一全局条目，带生成时间）。 */
        private var cachedPages: List<ByteArray> = emptyList()
        private var cachedAt: Long = 0L

        /** 头像预热线程池（惰性创建，守护线程）。 */
        private val avatarPool by lazy {
            Executors.newFixedThreadPool(AVATAR_FETCH_THREADS) { runnable ->
                Thread(runnable, "PenguinCoinsAvatarFetch").apply { isDaemon = true }
            }
        }
    }

    /** 原始条目（扫描 + 余额查询后的数据）。 */
    private data class RawEntry(val name: String, val uuid: UUID?, val balance: Double)

    @Commands("财富榜", "金币榜")
    fun coinsLeaderboard(plugin: KERONG, event: GroupMessageEvent, params: String) {
        // 查询冷却（render.command-cooldown-seconds，默认 5 秒，0 = 关闭）
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
                MessageTemplates.sendDirect(event, "query-too-frequent")
                return
            }
            lastQueryAt[cooldownKey] = now
            if (lastQueryAt.size > 1024) lastQueryAt.clear()
        }

        try {
            // 全流程进入共享渲染池（磁盘扫描 / 余额查询 / 头像预热 / 渲染 / 发送）
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

                    // 1. Vault 经济是否可用（NeoForge 恒不可用）
                    if (!economyAvailable()) {
                        MessageTemplates.sendDirect(event, "coins-leaderboard-no-economy")
                        return@submit
                    }

                    // 2. 全量扫描 QUUID 玩家 + 逐个查询 Vault 余额
                    val entries = collectEntries(manager)
                    if (entries.isEmpty()) {
                        MessageTemplates.sendDirect(event, "coins-leaderboard-no-data")
                        return@submit
                    }
                    val totalPlayers = entries.size.toLong()
                    val top = manager.coinsLeaderboardTop
                    val topEntries = entries.take(top)

                    // 3. 缓存未过期：直接复用整组图片
                    if (cachedPages.isNotEmpty() &&
                        System.currentTimeMillis() - cachedAt < RESULT_TTL_MILLIS
                    ) {
                        sendPages(event, cachedPages)
                        return@submit
                    }

                    // 4. 主线程快照在线玩家（实时皮肤）+ 并发预热头像
                    val onlineSkins = snapshotOnlineSkins()
                    val playerDataDir = try {
                        Bukkit.getWorlds().firstOrNull()?.worldFolder?.resolve("playerdata")
                    } catch (_: Throwable) {
                        null
                    }
                    val avatars = fetchAvatars(topEntries, onlineSkins, playerDataDir)

                    // 5. 分页渲染（第一页带标题，续页只画行内容）
                    val updateTime = SimpleDateFormat("HH:mm").format(Date())
                    val dataDirectory = plugin.configFile?.parentFile
                    val useBuiltinBackground = manager.coinsLeaderboardBuiltinBackground
                    val economyName = EconomyName.current()
                    val pages = ArrayList<ByteArray>(4)
                    var pageStart = 0
                    while (pageStart < topEntries.size) {
                        val pageEnd = minOf(pageStart + LeaderboardRenderer.ROWS_PER_PAGE, topEntries.size)
                        val pageEntries = topEntries.subList(pageStart, pageEnd).mapIndexed { offset, raw ->
                            LeaderboardRenderer.LeaderEntry(
                                rank = pageStart + offset + 1,
                                name = raw.name,
                                playSeconds = 0L,
                                avatar = avatars[raw.name],
                                valueText = formatMoney(raw.balance),
                            )
                        }
                        val height = LeaderboardRenderer.measurePageHeight(pageEntries.size, pages.isEmpty())
                        // 背景：与在线排行榜共用内置专属底图（leaderboard-default.png）
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
                                title = "${economyName}排行榜",
                                listHeader = economyName,
                            ),
                            firstPage = pages.isEmpty(),
                        )
                        if (bytes.isNotEmpty()) pages.add(bytes)
                        pageStart = pageEnd
                    }
                    if (pages.isEmpty()) {
                        MessageTemplates.sendDirect(event, "coins-leaderboard-render-failed")
                        return@submit
                    }

                    // 6. 写入结果缓存 + 逐张发送（相邻间隔 400ms）
                    cachedPages = pages
                    cachedAt = System.currentTimeMillis()
                    sendPages(event, pages)
                } catch (error: Throwable) {
                    plugin.log_error("[财富榜] 渲染失败: ${error.message}")
                    plugin.log_error(
                        "[财富榜] 失败堆栈: " +
                            error.stackTraceToString().lineSequence().take(8).joinToString(" | ")
                    )
                    MessageTemplates.sendDirect(event, "coins-leaderboard-render-failed")
                }
            }
        } catch (_: Throwable) {
            MessageTemplates.sendPlain(event, "server-busy")
        }
    }

    /** Vault + 经济服务是否就绪（与签到检测同一链；NeoForge 恒 false）。 */
    private fun economyAvailable(): Boolean {
        return try {
            val vault = Bukkit.getPluginManager().getPlugin("Vault") ?: return false
            if (!vault.isEnabled) return false
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
            val servicesManager = Bukkit.getServer().javaClass.getMethod("getServicesManager").invoke(Bukkit.getServer())
            val rsp = servicesManager.javaClass.getMethod("getRegistration", Class::class.java).invoke(servicesManager, economyClass) ?: return false
            val provider = Class.forName("org.bukkit.plugin.RegisteredServiceProvider").getMethod("getProvider").invoke(rsp) ?: return false
            provider != null
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 全量扫描 QUUID 主文件并查询每位玩家的 Vault 余额。
     *
     * OfflinePlayer 解析与 1.5.7.2 金币入账同规则（Geyser-Spigot / Floodgate
     * 基岩版兼容）：①在线精确匹配即本人；②离线已绑定用记录里的真实
     * playerUuid（基岩即 Floodgate UUID，余额查询不再掉入名字派生的幽灵账户）；
     * ③未绑定按名字解析（Java 行为不变）。
     * 余额查不到的玩家跳过（不出现在榜上）。
     */
    private fun collectEntries(manager: QqBindManager): List<RawEntry> {
        val entries = ArrayList<RawEntry>()
        for (quuid in manager.listQuuids()) {
            val yaml = manager.readQuuidRecord(quuid) ?: continue
            val name = yaml.getString("playerName", "") ?: ""
            if (name.isEmpty()) continue
            val uuidStr = yaml.getString("playerUuid", "")
            val uuid = try {
                if (uuidStr.isNullOrEmpty()) null else UUID.fromString(uuidStr)
            } catch (_: Throwable) {
                null
            }
            val offline = resolveOfflinePlayer(manager, name, uuid)
            val balance = offline?.let { queryBalance(it) } ?: continue
            if (balance <= 0.0) continue
            entries.add(RawEntry(name, uuid, balance))
        }
        entries.sortWith(compareByDescending<RawEntry> { it.balance }.thenBy { it.name })
        return entries
    }

    /** 三级解析金币账户目标（在线 → 绑定 UUID → 按名字）。 */
    private fun resolveOfflinePlayer(manager: QqBindManager, name: String, uuid: UUID?): OfflinePlayer? {
        return try {
            Bukkit.getPlayerExact(name)?.let { return it }
            if (uuid != null) return Bukkit.getOfflinePlayer(uuid)
            val uuidText = try {
                manager.findPlayerUuidByName(name)
            } catch (_: Throwable) {
                null
            }
            if (!uuidText.isNullOrEmpty()) {
                Bukkit.getOfflinePlayer(UUID.fromString(uuidText))
            } else {
                Bukkit.getOfflinePlayer(name)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 反射调用 Vault Economy 查询玩家余额（避免编译期依赖）。 */
    private fun queryBalance(offline: OfflinePlayer): Double? {
        return try {
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
            val servicesManager = Bukkit.getServer().javaClass.getMethod("getServicesManager").invoke(Bukkit.getServer())
            val rsp = servicesManager.javaClass.getMethod("getRegistration", Class::class.java).invoke(servicesManager, economyClass) ?: return null
            val economy = Class.forName("org.bukkit.plugin.RegisteredServiceProvider").getMethod("getProvider").invoke(rsp) ?: return null
            val balance = economyClass.getMethod("getBalance", OfflinePlayer::class.java)
                .invoke(economy, offline) as? Number
            balance?.toDouble()
        } catch (_: Throwable) {
            null
        }
    }

    /** 金额显示（整数去掉小数点，与签到 / 查信息卡片同风格）。 */
    private fun formatMoney(amount: Double): String =
        if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()

    /** 主线程快照在线玩家（与在线排行榜同实现；超时 / 失败返回空表）。 */
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
            cn.kerong.penguin.spigot.scheduler.FoliaCompat.callSync(bukkitPlugin, 3L) { snapshot() } ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** 并发预热头像（与在线排行榜同实现：在线皮肤优先，离线四级链路兜底）。 */
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
                }
            }
        } catch (_: Throwable) {
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
            MessageTemplates.sendPlain(event, "image-send-failed")
        }
    }
}
