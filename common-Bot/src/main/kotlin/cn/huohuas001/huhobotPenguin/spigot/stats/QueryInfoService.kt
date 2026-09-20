package cn.huohuas001.huhobotPenguin.spigot.stats

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.tools.PluginFileLog
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import cn.huohuas001.huhobotPenguin.spigot.render.CardRenderPool
import cn.huohuas001.huhobotPenguin.spigot.render.InfoCardAssets
import cn.huohuas001.huhobotPenguin.spigot.render.InfoCardRenderer
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * /查信息（1.5.2 前名为 /个人信息）命令服务：QQ → 玩家 UUID → 数据组装 → 卡片渲染 → 图片发送。
 *
 * 流程：
 * 1. 通过 QQ OpenId 查找绑定的玩家（未绑定则直接提示；1.5.3 支持 `/查信息 @成员`
 *    查询他人，未绑定时指明是谁未绑定）；
 * 2. 汇总数据：金币（Vault）、称号（0.1.5.5 默认 DeluxeTags，含颜色码）、
 *    在线时长与今日在线（本插件自记录）、累计签到（本插件签到系统）、点券（PlayerPoints）、
 *    屠龙次数 / 击杀玩家次数 / 死亡次数（本插件自记录，1.5.2 起展示）；
 * 3. 异步渲染毛玻璃风格统计卡片（背景从插件目录 img/ 随机挑选，无图用黑色；
 *    最多 9 项：金币 / 称号 / 在线时长 / 今日在线时长 / 累计签到 / 点券 /
 *    屠龙次数 / 击杀玩家次数 / 死亡次数，3 列 × 3 行）；
 * 4. 以图片消息发送到群（不 @ 提及）；发送失败时以文本回退提示，
 *    与“生成失败”区分开，便于定位是渲染问题还是机器人连接问题。
 *
 * 1.5.3：
 * - 依赖插件缺失隐藏统计项：Vault（金币）/ DeluxeTags+Vault（称号）/ PlayerPoints（点券）
 *   任一未安装或未启用（含经济服务未注册）时对应项不渲染，签到未开启同理；
 *   卡片高度随实际项数自适应（满项 760，少一行减 96px），背景按实际高度处理；
 * - 查询他人：`/查信息 @成员` 渲染该成员绑定玩家的卡片，冷却按查询发起者计。
 *
 * 1.5.1：
 * - 全链路异步：机器人消息线程只做冷却判断，绑定查找（磁盘 IO）、统计 / 称号 /
 *   金币 / 点券 / 皮肤读取（主线程单次跳转，3 秒超时）、头像 / 背景拉取、渲染与
 *   发送全部在 [CardRenderPool] 内完成，主线程只执行一次轻量采集；
 * - 修复 1.8.x 服务器称号无法检测：老版 DeluxeTags（2015-2021，MC 1.8 全系）
 *   的标签查询是 me.clip.deluxetags.DeluxeTag 类的静态方法（新版才是
 *   getTagsHandler() 实例 API），现按“新版实例 API → 老版静态 API →
 *   PlaceholderAPI 占位符”三层依次尝试，任一版本均能取到称号；
 * - 称号 / 金币 / 点券检测结果写入插件日志文件（logs/qq/qq-bind-日期.log），
 *   含检测来源与失败原因，排障时可直接翻日志定位（仅文件不刷控制台）。
 *
 * 1.5.0（覆盖更新）：
 * - 渲染 CPU 上限硬编码：全部图片渲染共用 [CardRenderPool]，线程数固定为
 *   主机核数一半（1..4，详见 CardRenderPool，不走配置文件）；
 * - 渲染完成后释放内存：单次渲染图像立即 flush 释放栅格，并做节流 GC 提示
 *   （两次至少间隔 60 秒，qq-bind.render.gc-after-render 可关）；
 * - 同一玩家的渲染结果在 TTL 内复用（默认 60 秒，qq-bind.render.result-cache-seconds 可调）。
 */
object QueryInfoService {

    /** 单用户查询冷却（毫秒）。 */
    private const val USER_COOLDOWN_MILLIS = 5_000L

    /** 用户冷却记录：OpenId → 上次触发时间。 */
    private val lastQueryAt = ConcurrentHashMap<String, Long>()

    /** 渲染结果缓存容量上限（超出先清过期再整体清空，防膨胀）。 */
    private const val CARD_CACHE_MAX = 32

    /** 渲染结果条目：PNG 字节 + 生成时间。 */
    private data class CachedCard(val bytes: ByteArray, val at: Long)

    /** 渲染结果缓存：玩家标识 → 最近一次卡片。 */
    private val cardCache = ConcurrentHashMap<String, CachedCard>()

    /**
     * 主线程一次性采集的数据快照（1.5.1：合并为单次主线程跳转）。
     * 附带各字段的检测说明（写入日志文件，用于称号/金币/点券检测排障）。
     * 1.5.3：新增依赖可用性标记——对应插件未安装/未启用时，该统计项不展示。
     */
    private data class MainThreadSnapshot(
        val stats: PlayerStatsManager.PlayerStats,
        val title: String,
        val titleSource: String,
        val balance: Double?,
        val balanceNote: String,
        val pointsText: String,
        val pointsNote: String,
        val skinUrl: String?,
        val playerDataDir: File?,
        /** Vault（含经济服务）是否可用——不可用时卡片不展示“金币”项。 */
        val vaultAvailable: Boolean,
        /** 称号来源链（DeluxeTags 或 Vault）是否有任一插件可用——均无时不展示“称号”项。 */
        val titleAvailable: Boolean,
        /** PlayerPoints 是否可用——不可用时卡片不展示“点券”项。 */
        val pointsAvailable: Boolean,
    )

    /** 称号检测结果（值 + 来源说明）。 */
    private data class TitleResult(val value: String, val source: String)

    /** 点券检测结果（展示文本 + 检测说明）。 */
    private data class PointsResult(val text: String, val note: String)

    /**
     * 全流程异步执行（1.5.1）：机器人消息线程仅做冷却判断，
     * 绑定查找、数据采集、渲染与发送全部在共享渲染池完成。
     *
     * 1.5.3：支持查询他人——`/查信息 @成员` 时 [qqOpenId] 为发起查询者（冷却接其计），
     * [targetOpenId] 为被查询成员（未绑定则提示该成员先完成绑定）；
     * 不传 [targetOpenId] 时行为与旧版一致（查询自己）。
     */
    fun handle(
        plugin: HuHoBot,
        event: GroupMessageEvent,
        qqOpenId: String,
        targetOpenId: String? = null,
        targetName: String? = null,
    ) {
        // 查询冷却：同一用户 5 秒内只允许一次，防止刷屏导致渲染风暴（1.5.3：按发起者计）
        val now = System.currentTimeMillis()
        val lastAt = lastQueryAt[qqOpenId] ?: 0L
        if (now - lastAt < USER_COOLDOWN_MILLIS) {
            replyText(event, "查询太频繁了，请稍后再试")
            return
        }
        lastQueryAt[qqOpenId] = now
        if (lastQueryAt.size > 1024) lastQueryAt.clear()

        // 查询目标：未指定则查询自己（旧版行为）
        val lookupOpenId = targetOpenId?.takeIf { it.isNotEmpty() } ?: qqOpenId

        try {
            // 共享渲染池（CardRenderPool：硬编码核数一半 1..4 线程；完成后自动节流 GC）
            CardRenderPool.submit {
                try {
                    // 1. 查找绑定的玩家（磁盘 IO，1.5.1 起在渲染池线程执行，不阻塞消息线程）
                    val bindManager = try {
                        QqBindManager.getInstance()
                    } catch (_: Throwable) {
                        null
                    }
                    if (bindManager == null) {
                        replyText(event, "❌ 绑定管理器未就绪，请稍后再试")
                        return@submit
                    }
                    if (targetOpenId != null) {
                        PluginFileLog.write(
                            "[查信息] ${qqOpenId} 查询 ${targetName ?: targetOpenId}（${targetOpenId}）的玩家信息"
                        )
                    }
                    val playerName = bindManager.findPlayerByQq(lookupOpenId)
                    val quuid = bindManager.findQuuidByQq(lookupOpenId)
                    if (playerName == null || playerName.isEmpty() || quuid == null || quuid.isEmpty()) {
                        // 1.5.3：查询他人未绑定时指明是谁未绑定，避免误以为查询者自己未绑定
                        replyText(
                            event,
                            if (targetName != null) "@$targetName 未绑定游戏账号（该 QQ 还未完成绑定，无法查询其玩家信息）"
                            else "该账号未绑定QQ，请先进入服务器完成绑定后再查询"
                        )
                        return@submit
                    }
                    val playerUuid: UUID? = parseUuid(bindManager.getPlayerUuidByQuuid(quuid))

                    // 2. TTL 内同一玩家直接复用上次渲染的 PNG，零渲染开销秒回
                    val cacheKey = playerUuid?.toString() ?: playerName
                    val ttlMillis = resultCacheMillis()
                    val cached = if (ttlMillis > 0) cardCache[cacheKey] else null
                    if (cached != null && System.currentTimeMillis() - cached.at < ttlMillis) {
                        val sent = QClient.replyWithImgBytes(event, cached.bytes)
                        if (!sent) {
                            replyText(event, "❌ 图片发送失败（机器人连接可能断开），请稍后重试")
                        }
                        return@submit
                    }

                    // 3. 单次主线程跳转采集全部数据（统计 / 称号 / 金币 / 点券 / 皮肤；
                    //    旧版为 4 次独立跳转各等 3 秒，现合并为 1 次）
                    val snapshot = gatherOnMainThread(playerName, playerUuid)

                    // 4. 检测日志（1.5.1）：称号 / 金币 / 点券的检测来源与结果写入
                    //    插件日志文件（logs/qq/qq-bind-日期.log），仅文件不刷控制台
                    PluginFileLog.write("[检测] 称号: $playerName → ${snapshot.titleSource} → '${snapshot.title}'")
                    PluginFileLog.write("[检测] 金币: $playerName → ${snapshot.balanceNote} → ${snapshot.balance ?: "无"}")
                    PluginFileLog.write("[检测] 点券: $playerName → ${snapshot.pointsNote} → ${snapshot.pointsText}")

                    // 5. 累计签到（本插件签到系统；磁盘 IO 同样在池线程执行）
                    // 1.5.3：签到功能未开启时返回 null → 卡片不展示“累计签到”项
                    val checkinTotalText = resolveCheckinTotal(bindManager, quuid)

                    // 6. 组装统计项（1.5.3：依赖插件缺失的项不展示）并计算卡片实际高度，
                    //    背景图按实际高度处理与缓存（不同高度缓存隔离，互不污染）
                    val items = buildItems(snapshot, checkinTotalText)
                    val cardHeight = InfoCardRenderer.cardHeight(items.size)
                    PluginFileLog.write(
                        "[查信息] 统计项 ${items.size} 项（金币=${if (snapshot.vaultAvailable) "展示" else "隐藏-Vault未装"}" +
                            " 称号=${if (snapshot.titleAvailable) "展示" else "隐藏-无来源插件"}" +
                            " 点券=${if (snapshot.pointsAvailable) "展示" else "隐藏-PlayerPoints未装"}" +
                            " 签到=${if (checkinTotalText != null) "展示" else "隐藏-未开启"}）卡片 ${InfoCardRenderer.WIDTH}×$cardHeight"
                    )
                    val dataDirectory = plugin.configFile?.parentFile
                    val background = dataDirectory?.let {
                        InfoCardAssets.processedBackground(it, InfoCardRenderer.WIDTH, cardHeight)
                    }

                    // 7. 拉取头像并渲染
                    val avatar = InfoCardAssets.fetchAvatar(
                        InfoCardAssets.AvatarRequest(
                            uuid = playerUuid,
                            playerName = playerName,
                            skinUrl = snapshot.skinUrl,
                            playerDataDir = snapshot.playerDataDir,
                        )
                    )
                    val bytes = InfoCardRenderer.render(
                        InfoCardRenderer.CardData(
                            playerName = playerName,
                            items = items,
                            avatar = avatar,
                            background = background,
                        )
                    )

                    // 8. 渲染成功写入结果缓存（TTL 内重复查询直接复用）
                    if (ttlMillis > 0 && bytes.isNotEmpty()) {
                        cardCache[cacheKey] = CachedCard(bytes, System.currentTimeMillis())
                        if (cardCache.size > CARD_CACHE_MAX) {
                            cardCache.entries.removeIf { System.currentTimeMillis() - it.value.at >= ttlMillis }
                            if (cardCache.size > CARD_CACHE_MAX) cardCache.clear()
                        }
                    }

                    // 9. 发送图片（不 @）；发送失败与生成失败分开提示，便于定位问题
                    val sent = QClient.replyWithImgBytes(event, bytes)
                    if (!sent) {
                        replyText(event, "❌ 图片发送失败（机器人连接可能断开），请稍后重试")
                    }
                } catch (error: Throwable) {
                    // 失败日志带堆栈前几帧且双写日志文件，事后可从
                    // logs/qq/qq-bind-日期.log 追溯具体失败原因
                    plugin.log_error("[查信息] 渲染卡片失败: ${error.message}")
                    plugin.log_error(
                        "[查信息] 失败堆栈: " +
                            error.stackTraceToString().lineSequence().take(8).joinToString(" | ")
                    )
                    replyText(event, "❌ 信息卡片生成失败，请稍后重试或联系管理员")
                }
            }
        } catch (_: Throwable) {
            // 理论上不会触发（队列无上限），兑底防止线程池异常时静默丢消息
            replyText(event, "当前查询人数较多，请稍后再试")
        }
    }

    // ---------- 结果缓存 / 统计项组装 ----------

    /** qq-bind.render.result-cache-seconds：渲染结果复用秒数（默认 60，0 = 关闭，上限 600）。 */
    private fun resultCacheMillis(): Long = try {
        (Bukkit.getPluginManager().getPlugin("KERONGPenguin") as? JavaPlugin)
            ?.config?.getLong("qq-bind.render.result-cache-seconds", 60L) ?: 60L
    } catch (_: Throwable) {
        60L
    }.coerceIn(0L, 600L) * 1000L

    /**
     * 组装统计项（1.5.3：依赖插件缺失的项不展示，卡片高度随实际项数自适应）。
     *
     * 隐藏规则（用户需求：检测不到相关依赖插件则不显示对应项目）：
     * - 金币：Vault 未安装 / 未启用 / 经济服务未注册（如只装了权限插件）→ 不展示；
     * - 称号：DeluxeTags 与 Vault 均不可用（称号来源链全断）→ 不展示；
     * - 累计签到：本插件签到功能未开启 → 不展示；
     * - 点券：PlayerPoints 未安装 / 未启用 → 不展示；
     * - 在线时长 / 今日在线时长 / 屠龙次数 / 击杀玩家次数 / 死亡次数：本插件自记录，恒展示；
     * - 插件已安装但读值失败（主线程繁忙/读取异常）→ 保留该项并显示“暂无”（与隐藏区分）。
     *
     * 展示顺序与 1.5.2 一致：金币 / 称号 / 在线时长 / 今日在线时长 /
     * 累计签到 / 点券 / 屠龙次数 / 击杀玩家次数 / 死亡次数。
     */
    private fun buildItems(
        snapshot: MainThreadSnapshot,
        checkinTotalText: String?,
    ): List<InfoCardRenderer.CardItem> = buildList {
        if (snapshot.vaultAvailable) {
            add(InfoCardRenderer.CardItem("金币", if (snapshot.balance != null) formatAmount(snapshot.balance) else "暂无"))
        }
        if (snapshot.titleAvailable) {
            add(InfoCardRenderer.CardItem("称号", snapshot.title))
        }
        add(InfoCardRenderer.CardItem("在线时长", formatPlayTime(snapshot.stats.playSeconds)))
        add(InfoCardRenderer.CardItem("今日在线时长", formatTodayTime(snapshot.stats.todaySeconds)))
        if (checkinTotalText != null) {
            add(InfoCardRenderer.CardItem("累计签到", checkinTotalText))
        }
        if (snapshot.pointsAvailable) {
            add(InfoCardRenderer.CardItem("点券", snapshot.pointsText))
        }
        add(InfoCardRenderer.CardItem("屠龙次数", "${snapshot.stats.dragonKills} 次"))
        add(InfoCardRenderer.CardItem("击杀玩家次数", "${snapshot.stats.playerKills} 次"))
        add(InfoCardRenderer.CardItem("死亡次数", "${snapshot.stats.deaths} 次"))
    }

    /**
     * 累计签到（1.5.0 覆盖更新）：读取本插件签到系统的累计签到次数。
     * 1.5.3：签到功能未开启时返回 null（卡片不展示该项）；
     * 已开启时显示“N 次”；读取异常时返回“暂无”（保留展示便于发现异常）。
     */
    private fun resolveCheckinTotal(bindManager: QqBindManager, quuid: String): String? {
        return try {
            if (!bindManager.isCheckinEnabled) null
            else "${bindManager.getCheckinTotal(quuid)} 次"
        } catch (_: Throwable) {
            "暂无"
        }
    }

    // ---------- 主线程单次采集（1.5.1） ----------

    /**
     * 单次主线程跳转采集全部数据：统计 / 称号 / 金币 / 点券 / 皮肤。
     * 旧版为 4 次独立的主线程跳转（各带 3 秒超时），主线程繁忙时最坏阻塞
     * 消息线程 12 秒；现在合并为 1 次跳转（在渲染池线程等待，不再阻塞消息线程）。
     */
    private fun gatherOnMainThread(playerName: String, playerUuid: UUID?): MainThreadSnapshot {
        val bukkitPlugin = Bukkit.getPluginManager().getPlugin("KERONGPenguin")
        val task: () -> MainThreadSnapshot = {
            val onlinePlayer = playerUuid?.let { uuid ->
                try {
                    Bukkit.getPlayer(uuid)
                } catch (_: Throwable) {
                    null
                }
            }
            val stats = if (playerUuid != null) {
                PlayerStatsManager.query(playerUuid)
            } else {
                PlayerStatsManager.PlayerStats()
            }
            val title = resolveTitle(playerName, playerUuid, onlinePlayer)
            val balance = queryBalance(playerName, playerUuid)
            val points = if (playerUuid != null) resolvePlayerPoints(playerUuid) else null
            val skinInfo = if (playerUuid != null) querySkinInfo(playerUuid) else null
            // 1.5.3：依赖插件可用性检测（与数据同线程采集，避免状态不一致）
            val dependency = detectDependencies()
            MainThreadSnapshot(
                stats = stats,
                title = title.value,
                titleSource = title.source,
                balance = balance.first,
                balanceNote = balance.second,
                pointsText = points?.text ?: "暂无",
                pointsNote = points?.note ?: "无玩家 UUID",
                skinUrl = skinInfo?.first,
                playerDataDir = skinInfo?.second,
                vaultAvailable = dependency.vault,
                titleAvailable = dependency.title,
                pointsAvailable = dependency.points,
            )
        }
        if (Bukkit.isPrimaryThread()) return task()
        if (bukkitPlugin == null) return timeoutSnapshot()
        return try {
            Bukkit.getScheduler().callSyncMethod(bukkitPlugin) { task() }
                .get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            timeoutSnapshot()
        }
    }

    /**
     * 依赖插件可用性检测（1.5.3）：
     * - 金币：Vault 已启用且经济服务已注册（只装权限类 Vault 时无经济服务，同样隐藏）；
     * - 称号：DeluxeTags 或 Vault 任一可用（resolveTitle 的回退链两端）；
     * - 点券：PlayerPoints 已启用。
     */
    private data class DependencyStatus(val vault: Boolean, val title: Boolean, val points: Boolean)

    private fun detectDependencies(): DependencyStatus = try {
        val vaultPlugin = Bukkit.getPluginManager().getPlugin("Vault")
        val vaultOn = vaultPlugin != null && vaultPlugin.isEnabled
        val economyRegistered = vaultOn && try {
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
            Bukkit.getServicesManager().getRegistration(economyClass) != null
        } catch (_: Throwable) {
            false
        }
        val deluxeTagsOn = Bukkit.getPluginManager().getPlugin("DeluxeTags")?.isEnabled == true
        val pointsOn = Bukkit.getPluginManager().getPlugin("PlayerPoints")?.isEnabled == true
        DependencyStatus(vault = economyRegistered, title = deluxeTagsOn || vaultOn, points = pointsOn)
    } catch (_: Throwable) {
        DependencyStatus(vault = false, title = false, points = false)
    }

    /**
     * 主线程繁忙（超时/不可调度）时的兑底快照：字段保留“暂无”并注明原因。
     * 1.5.3：依赖标记置 true（沿用旧版展示“暂无”的行为，避免超时时误隐藏项）。
     */
    private fun timeoutSnapshot(): MainThreadSnapshot = MainThreadSnapshot(
        stats = PlayerStatsManager.PlayerStats(),
        title = "暂无",
        titleSource = "主线程繁忙读取超时",
        balance = null,
        balanceNote = "主线程繁忙读取超时",
        pointsText = "暂无",
        pointsNote = "主线程繁忙读取超时",
        skinUrl = null,
        playerDataDir = null,
        vaultAvailable = true,
        titleAvailable = true,
        pointsAvailable = true,
    )

    /** 主线程查询：在线玩家取 Profile 皮肤 URL；同时定位 playerdata 目录（离线玩家 NBT 解析用）。 */
    private fun querySkinInfo(playerUuid: UUID): Pair<String?, File?> {
        val playerDataDir = try {
            Bukkit.getWorlds().firstOrNull()?.worldFolder?.resolve("playerdata")
        } catch (_: Throwable) {
            null
        }
        val skinUrl = try {
            val player = Bukkit.getPlayer(playerUuid) ?: return null to playerDataDir
            // 在线玩家：Profile 贴图 URL（含 SkinsRestorer 等皮肤插件修改后的结果）
            player.playerProfile.textures.skin?.toString()
        } catch (_: Throwable) {
            null
        }
        return skinUrl to playerDataDir
    }

    /**
     * 解析玩家称号（返回值 + 检测来源说明）：
     * 1. DeluxeTags（三层兼容：新版实例 API → 老版静态 API → PlaceholderAPI 占位符）；
     * 2. Vault Chat 前缀（无 DeluxeTags 或无标签时，清理颜色码的纯文本）；
     * 3. 主权限组；
     * 4. 均无 → “暂无”。
     */
    private fun resolveTitle(playerName: String, playerUuid: UUID?, onlinePlayer: Player?): TitleResult {
        return try {
            resolveDeluxeTagsTitle(playerUuid, onlinePlayer)
                ?: resolveVaultTitle(playerName, playerUuid)
                ?: TitleResult("暂无", "未检测到（DeluxeTags/Vault 均无结果）")
        } catch (_: Throwable) {
            TitleResult("暂无", "称号检测异常")
        }
    }

    // ---------- DeluxeTags 三层兼容（1.5.1 修复 1.8.x 检测失败） ----------

    /** DeluxeTags 玩家明确选择"无标签"时持久化的哨兵值（官方源码常量）。 */
    private const val DELUXETAGS_NO_TAG = "__deluxetags_no_tag__"

    /** 未解析的 PlaceholderAPI 占位符（如 %deluxetags_tag%），渲染前剔除避免出现百分号原文。 */
    private val UNRESOLVED_PLACEHOLDER = Regex("%[^%\\s]{1,64}%")

    /**
     * DeluxeTags 称号（反射调用，插件不存在 / 无标签返回 null）。
     * 返回值为称号原文（含 & / § 颜色码，如 "&7[&6Vip&7]&f"），
     * 由 [InfoCardRenderer] 解析后按原色渲染。
     *
     * 1.5.1 修复 1.8.x 服务器“称号还是检测不到”：
     * 老版 DeluxeTags（2015-2021 全系，兼容 MC 1.8）与新版（2021+ / 1.8.3-Release）
     * 的 API 完全不同 —— 新版主类有 getTagsHandler() 实例方法，老版主类没有，
     * 标签查询走 me.clip.deluxetags.DeluxeTag 类的静态方法。现按三层依次尝试：
     * 1. 新版实例 API：getTagsHandler() → getPlayerActiveTag(UUID/Player) → getDisplayTag；
     * 2. 老版静态 API：DeluxeTag.getPlayerDisplayTag(Player/uuid)（在线），
     *    离线回退 getSavedTagIdentifier → getLoadedTag(identifier)；
     * 3. PlaceholderAPI 占位符：%deluxetags_tag%（装了 PAPI 时任何版本均可解析）。
     */
    private fun resolveDeluxeTagsTitle(playerUuid: UUID?, onlinePlayer: Player?): TitleResult? {
        if (playerUuid == null) return null
        val deluxeTags = Bukkit.getPluginManager().getPlugin("DeluxeTags") ?: return null
        if (!deluxeTags.isEnabled) return null

        // 1) 新版实例 API（2021+，含官方 1.8.3-Release）
        resolveModernDeluxeTagsTitle(deluxeTags, playerUuid, onlinePlayer)?.let { return it }

        // 2) 老版静态 API（2015-2021，MC 1.8 全系，1.8.2 服务器即此形态）
        resolveLegacyDeluxeTagsTitle(deluxeTags, playerUuid, onlinePlayer)?.let { return it }

        // 3) PlaceholderAPI 占位符兜底（任何 DeluxeTags 版本注册过 %deluxetags_tag% 扩展即可）
        resolveDeluxeTagsViaPlaceholder(playerUuid)?.let { return TitleResult(it, "DeluxeTags(PlaceholderAPI)") }
        return null
    }

    /** 新版 DeluxeTags（getTagsHandler 实例 API）：在线取内存表激活标签，离线回退持久化选择。 */
    private fun resolveModernDeluxeTagsTitle(deluxeTags: Any, playerUuid: UUID, onlinePlayer: Player?): TitleResult? {
        return try {
            val handler = findMethod(deluxeTags, "getTagsHandler", 0)?.invoke(deluxeTags) ?: return null

            // 1) 当前激活的标签（在线玩家必有——DeluxeTags 把默认/强制/自选标签统一写入内存表；
            //    离线玩家可能已从内存卸载；getPlayerActiveTag 兼容老版本命名 getActiveTag）
            val activeTag = invokeActiveTagLookup(handler, "getPlayerActiveTag", playerUuid, onlinePlayer)
                ?: invokeActiveTagLookup(handler, "getActiveTag", playerUuid, onlinePlayer)

            // 2) 离线回退：读取持久化的标签标识（玩家手动选过才有记录），再按标识查标签对象
            val tagObject = activeTag ?: run {
                val identifier = try {
                    val method = findMethod(deluxeTags, "getSavedTagIdentifier", 1)
                    if (method != null && method.parameterTypes[0] == String::class.java) {
                        method.invoke(deluxeTags, playerUuid.toString()) as? String
                    } else {
                        null
                    }
                } catch (_: Throwable) {
                    null
                }
                if (identifier.isNullOrEmpty() || identifier == DELUXETAGS_NO_TAG) {
                    null
                } else {
                    try {
                        val method = findMethod(handler, "getTagByIdentifier", 1)
                        if (method != null && method.parameterTypes[0] == String::class.java) {
                            method.invoke(handler, identifier)
                        } else {
                            null
                        }
                    } catch (_: Throwable) {
                        null
                    }
                }
            }

            if (tagObject != null) {
                val offlineForDisplay: OfflinePlayer = onlinePlayer ?: Bukkit.getOfflinePlayer(playerUuid)
                val display = readDisplayTag(tagObject, offlineForDisplay)
                // 剔除未解析占位符；保留颜色码原文（卡片按颜色码彩色渲染）；空白视为无称号
                val cleaned = display?.replace(UNRESOLVED_PLACEHOLDER, "")?.trim()
                if (!cleaned.isNullOrEmpty()) return TitleResult(cleaned, "DeluxeTags(新版API)")
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 老版 DeluxeTags 静态 API（1.5.1 新增，修复 MC 1.8.x 检测失败）：
     * - 类名 me.clip.deluxetags.DeluxeTag（新版移到了 me.clip.deluxetags.tags 包，
     *   Class.forName 用 DeluxeTags 插件类加载器加载，加载失败自动跳过）；
     * - 在线：getPlayerDisplayTag(Player)（内部应用 PAPI）；
     *   离线/无标签：getPlayerDisplayTag(String uuid)；再回退
     *   主类 getSavedTagIdentifier(uuid) → getLoadedTag(identifier) → getDisplayTag()。
     */
    private fun resolveLegacyDeluxeTagsTitle(deluxeTags: Any, playerUuid: UUID, onlinePlayer: Player?): TitleResult? {
        val tagClass = try {
            Class.forName("me.clip.deluxetags.DeluxeTag", false, deluxeTags.javaClass.classLoader)
        } catch (_: Throwable) {
            null
        } ?: return null

        // 1) 在线玩家：Player 重载（内部会应用 PAPI 占位符）；无则走 String(uuid) 重载
        val display: String? = onlinePlayer?.let { player ->
            try {
                tagClass.getMethod("getPlayerDisplayTag", Player::class.java).invoke(null, player) as? String
            } catch (_: Throwable) {
                null
            }
        } ?: try {
            tagClass.getMethod("getPlayerDisplayTag", String::class.java)
                .invoke(null, playerUuid.toString()) as? String
        } catch (_: Throwable) {
            null
        }

        // 2) 离线回退：持久化保存的标签标识 → getLoadedTag(identifier) → getDisplayTag()
        var text = display
        if (text.isNullOrEmpty()) {
            val identifier = try {
                val method = findMethod(deluxeTags, "getSavedTagIdentifier", 1)
                if (method != null && method.parameterTypes[0] == String::class.java) {
                    method.invoke(deluxeTags, playerUuid.toString()) as? String
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
            if (!identifier.isNullOrEmpty() && identifier != DELUXETAGS_NO_TAG) {
                text = try {
                    val tagObject = tagClass.getMethod("getLoadedTag", String::class.java)
                        .invoke(null, identifier)
                    tagObject?.let { findMethod(it, "getDisplayTag", 0)?.invoke(it) as? String }
                } catch (_: Throwable) {
                    null
                }
            }
        }

        val cleaned = text?.replace(UNRESOLVED_PLACEHOLDER, "")?.trim()
        if (cleaned.isNullOrEmpty()) return null
        return TitleResult(cleaned, "DeluxeTags(老版静态API)")
    }

    /**
     * PlaceholderAPI 兜底：直接解析 %deluxetags_tag% 占位符。
     * 任何版本的 DeluxeTags 都注册了该占位符（即游戏内聊天显示的称号），
     * 前两层反射失败时这里是最后的通用通路。
     */
    private fun resolveDeluxeTagsViaPlaceholder(playerUuid: UUID): String? {
        val papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") ?: return null
        if (!papi.isEnabled) return null
        return try {
            // 用 PAPI 插件自己的类加载器加载（跨插件类不可见）
            val papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI", false, papi.javaClass.classLoader)
            val method = papiClass.methods.firstOrNull {
                it.name == "setPlaceholders" && it.parameterCount == 2 &&
                    OfflinePlayer::class.java == it.parameterTypes[0] && String::class.java == it.parameterTypes[1]
            } ?: return null
            val offlinePlayer = Bukkit.getOfflinePlayer(playerUuid)
            val resolved = method.invoke(null, offlinePlayer, "%deluxetags_tag%") as? String ?: return null
            // PAPI 未注册对应扩展时原样返回占位符文本 —— 剔除后为空即视为无称号
            resolved.replace(UNRESOLVED_PLACEHOLDER, "").trim().takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    /** 按名称（与可选参数个数）查找对象的公共方法，找不到返回 null。 */
    private fun findMethod(target: Any, name: String, parameterCount: Int?): Method? = try {
        target.javaClass.methods.firstOrNull { method ->
            method.name == name && (parameterCount == null || method.parameterCount == parameterCount)
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 反射调用"查玩家激活标签"类方法（单参数）。
     * 按目标方法形参类型自适应传参：UUID / Player / OfflinePlayer / String(离线 UUID 字符串)，
     * 兼容不同版本 DeluxeTags 的重载差异；玩家不在线时跳过需要 Player 实参的重载。
     */
    private fun invokeActiveTagLookup(
        handler: Any,
        name: String,
        playerUuid: UUID,
        onlinePlayer: Player?,
    ): Any? {
        val methods = try {
            handler.javaClass.methods.filter { it.name == name && it.parameterCount == 1 }
        } catch (_: Throwable) {
            return null
        }
        for (method in methods) {
            val argument: Any? = when (method.parameterTypes[0]) {
                java.util.UUID::class.java -> playerUuid
                Player::class.java -> onlinePlayer ?: continue
                OfflinePlayer::class.java -> onlinePlayer ?: Bukkit.getOfflinePlayer(playerUuid)
                String::class.java -> playerUuid.toString()
                else -> continue
            }
            try {
                return method.invoke(handler, argument)
            } catch (_: Throwable) {
                // 该重载调用失败（含内部 NPE 等），换下一个重载继续尝试
            }
        }
        return null
    }

    /** 读取标签显示文本：优先带 OfflinePlayer 的重载（内部应用 PAPI 占位符），否则无参版。 */
    private fun readDisplayTag(tagObject: Any, offlinePlayer: OfflinePlayer): String? {
        val withPlayer = try {
            tagObject.javaClass.methods.firstOrNull {
                it.name == "getDisplayTag" && it.parameterCount == 1 &&
                    OfflinePlayer::class.java.isAssignableFrom(it.parameterTypes[0])
            }?.invoke(tagObject, offlinePlayer) as? String
        } catch (_: Throwable) {
            null
        }
        if (withPlayer != null) return withPlayer
        return try {
            findMethod(tagObject, "getDisplayTag", 0)?.invoke(tagObject) as? String
        } catch (_: Throwable) {
            null
        }
    }

    // ---------- 金币 / 点券检测（1.5.1：附检测说明用于日志） ----------

    /** Vault 前缀 / 权限组称号（无 DeluxeTags 时的回退链，返回纯文本 + 来源）。 */
    private fun resolveVaultTitle(playerName: String, playerUuid: UUID?): TitleResult? {
        return try {
            val vault = Bukkit.getPluginManager().getPlugin("Vault") ?: return null
            if (!vault.isEnabled) return null
            val offlinePlayer: OfflinePlayer = playerUuid?.let { Bukkit.getOfflinePlayer(it) }
                ?: Bukkit.getOfflinePlayer(playerName)
            // 1) Vault Chat 前缀（去除颜色码）
            try {
                val chatClass = Class.forName("net.milkbowl.vault.chat.Chat")
                val rsp = Bukkit.getServicesManager().getRegistration(chatClass)
                if (rsp != null) {
                    val chat = rsp.provider
                    val prefix = chat.javaClass.getMethod("getPlayerPrefix", String::class.java, OfflinePlayer::class.java)
                        .invoke(chat, "world", offlinePlayer) as? String
                    val cleaned = prefix?.replace("§.".toRegex(), "")?.replace("&[0-9a-fk-or]".toRegex(), "")?.trim()
                    if (!cleaned.isNullOrEmpty()) return TitleResult(cleaned, "Vault(Chat 前缀)")
                }
            } catch (_: Throwable) {
            }
            // 2) 主权限组
            try {
                val permClass = Class.forName("net.milkbowl.vault.permission.Permission")
                val rsp = Bukkit.getServicesManager().getRegistration(permClass)
                if (rsp != null) {
                    val permission = rsp.provider
                    val groups = permission.javaClass
                        .getMethod("getPlayerGroups", String::class.java, OfflinePlayer::class.java)
                        .invoke(permission, "world", offlinePlayer) as? Array<*>
                    val group = groups?.firstOrNull()?.toString()
                    if (!group.isNullOrEmpty()) return TitleResult(group, "Vault(主权限组)")
                }
            } catch (_: Throwable) {
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 查询金币余额（Vault Economy 反射调用；返回值 + 检测说明）。
     */
    private fun queryBalance(playerName: String, playerUuid: UUID?): Pair<Double?, String> {
        return try {
            val vaultPlugin = Bukkit.getPluginManager().getPlugin("Vault")
                ?: return null to "Vault 未安装"
            if (!vaultPlugin.isEnabled) return null to "Vault 已安装但未启用"
            val offlinePlayer: OfflinePlayer = playerUuid?.let { Bukkit.getOfflinePlayer(it) }
                ?: Bukkit.getOfflinePlayer(playerName)
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
            val rsp = Bukkit.getServicesManager().getRegistration(economyClass)
                ?: return null to "经济服务未注册（Vault 已装但无经济插件）"
            val economy = rsp.provider
            val balance = economy.javaClass
                .getMethod("getBalance", OfflinePlayer::class.java)
                .invoke(economy, offlinePlayer) as? Double
            balance to "Vault(${economy.javaClass.simpleName})"
        } catch (_: Throwable) {
            null to "Vault 读取异常"
        }
    }

    /**
     * 点券：读取 PlayerPoints 插件的点券余额（反射调用，宽容匹配 look(UUID)）。
     * 返回展示文本 + 检测说明（未安装 / 读取失败 / 正常）。
     */
    private fun resolvePlayerPoints(playerUuid: UUID): PointsResult {
        return try {
            val pointsPlugin = Bukkit.getPluginManager().getPlugin("PlayerPoints")
                ?: return PointsResult("未启用", "PlayerPoints 未安装")
            if (!pointsPlugin.isEnabled) return PointsResult("未启用", "PlayerPoints 已安装但未启用")
            val api = findMethod(pointsPlugin, "getAPI", 0)?.invoke(pointsPlugin)
                ?: return PointsResult("暂无", "PlayerPoints getAPI 未找到")
            val amount = when (val value = invokePointsLookup(api, playerUuid)) {
                is Number -> value.toLong()
                else -> return PointsResult("暂无", "PlayerPoints 余额方法未匹配")
            }
            PointsResult(formatNumber(amount), "PlayerPoints")
        } catch (_: Throwable) {
            PointsResult("暂无", "PlayerPoints 读取异常")
        }
    }

    /** 调用 PlayerPointsAPI 的余额查询方法（look(UUID)，兼容同名重载）。 */
    private fun invokePointsLookup(api: Any, playerUuid: UUID): Any? {
        val methods = try {
            api.javaClass.methods.filter { it.parameterCount == 1 && java.util.UUID::class.java == it.parameterTypes[0] }
        } catch (_: Throwable) {
            return null
        }
        // 优先官方方法名 look；同名不存在时退化为“返回数值类型的单 UUID 参数方法”
        val look = methods.firstOrNull { it.name == "look" } ?: methods.firstOrNull {
            Number::class.java.isAssignableFrom(it.returnType) || it.returnType == Int::class.javaPrimitiveType
        } ?: return null
        return try {
            look.invoke(api, playerUuid)
        } catch (_: Throwable) {
            null
        }
    }

    /** 纯文本回复（不 @）。 */
    private fun replyText(event: GroupMessageEvent, message: String) {
        try {
            event.sendMessage(message)
        } catch (_: Throwable) {
        }
    }

    private fun parseUuid(value: String?): UUID? = try {
        UUID.fromString(value)
    } catch (_: Throwable) {
        null
    }

    // ---------- 格式化工具 ----------

    /** 大数字缩写（1234 → 1.20k，1234567 → 1.23M）。 */
    fun formatNumber(value: Long): String = when {
        value >= 1_000_000_000 -> trimZero(value / 1_000_000_000.0) + "B"
        value >= 1_000_000 -> trimZero(value / 1_000_000.0) + "M"
        value >= 10_000 -> trimZero(value / 1000.0) + "k"
        else -> value.toString()
    }

    /** 金额缩写（保留两位小数：1600 → 1.60k）。 */
    fun formatAmount(value: Double): String = when {
        value >= 1_000_000_000 -> trimZero(value / 1_000_000_000.0, 2) + "B"
        value >= 1_000_000 -> trimZero(value / 1_000_000.0, 2) + "M"
        value >= 1_000 -> trimZero(value / 1000.0, 2) + "k"
        value >= 10 -> trimZero(value, 1)
        else -> trimZero(value, 2)
    }

    /** 保留 [decimals] 位小数并去掉多余的 0。 */
    private fun trimZero(value: Double, decimals: Int = 2): String {
        val text = String.format("%.${decimals}f", value)
        return text.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    /** 游戏时长：0天9时42分33秒。 */
    fun formatPlayTime(seconds: Long): String {
        if (seconds <= 0) return "0分钟"
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return buildString {
            if (days > 0) append("${days}天")
            if (hours > 0) append("${hours}时")
            if (minutes > 0) append("${minutes}分")
            if (secs > 0 && days == 0L && hours == 0L) append("${secs}秒")
            if (isEmpty()) append("0分钟")
        }
    }

    /** 今日在线：1时37分。 */
    fun formatTodayTime(seconds: Long): String {
        if (seconds <= 0) return "0分钟"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return buildString {
            if (hours > 0) append("${hours}时")
            if (minutes > 0) append("${minutes}分")
            if (isEmpty()) append("不足1分钟")
        }
    }

    /** 距离：8.44 km。 */
    fun formatDistance(cm: Long): String {
        if (cm <= 0) return "0 m"
        val meters = cm / 100.0
        return if (meters >= 1000) {
            trimZero(meters / 1000.0, 2) + " km"
        } else {
            trimZero(meters, 0) + " m"
        }
    }
}
