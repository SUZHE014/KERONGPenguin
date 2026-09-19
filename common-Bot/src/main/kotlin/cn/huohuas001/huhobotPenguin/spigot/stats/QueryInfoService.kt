package cn.huohuas001.huhobotPenguin.spigot.stats

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.provider.plugin
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * /个人信息 命令服务：QQ → 玩家 UUID → 数据组装 → 卡片渲染 → 图片发送。
 *
 * 流程：
 * 1. 通过 QQ OpenId 查找绑定的玩家（未绑定则直接提示）；
 * 2. 汇总数据：金币（Vault）、称号（0.1.5.5 默认 DeluxeTags，含颜色码）、
 *    在线时长与今日在线（本插件自记录）；
 * 3. 异步渲染毛玻璃风格统计卡片（0.1.5.3 起背景从插件目录 img/ 随机挑选，无图用黑色；
 *    0.1.5.5 起仅展示 4 项：金币 / 称号 / 在线时长 / 今日在线时长，高度收紧为 660）；
 * 4. 以图片消息发送到群（不 @ 提及）；发送失败时以文本回退提示，
 *    与“生成失败”区分开，便于定位是渲染问题还是机器人连接问题。
 *
 * 0.1.5.2 性能修复：
 * - 单用户查询冷却，防止连续触发导致 CPU / 内存狂飙；
 * - 皮肤信息（贴图 URL / playerdata 目录）一次性主线程捕获，避免渲染线程触碰非线程安全 API；
 * - 背景与头像均走 [InfoCardAssets] 缓存，重复查询不再重复解码 / 请求。
 *
 * 0.1.5.5：
 * - 称号接入 DeluxeTags（反射调用，优先于 Vault 前缀；返回带颜色码的原文，
 *   由卡片按 MC 色板真实渲染彩色，离线玩家回退到持久化保存的标签选择）；
 * - 渲染/发送失败日志带上堆栈前几帧，且 log_error 已双写插件日志文件，事后可从
 *   logs/qq/qq-bind-日期.log 完整追溯（修复“部分报错没有记录在日志里面”）。
 * 1.5.0：
 * - 渲染性能限制（用户可配）：卡片渲染从 Bukkit 通用异步线程池迁到专用低优先级线程池，
 *   线程数由 qq-bind.render.threads 控制（默认 1，即渲染最多占用 1 核 CPU），
 *   队列积压超过 8 直接回忙不渲染；同一玩家的渲染结果在 TTL 内复用（默认 60 秒，
 *   qq-bind.render.result-cache-seconds 可调），重复查询秒回零渲染；
 * - 修复图片有概率加载失败（coverImage 浮点截断越界，见 InfoCardRenderer）；
 * - 修复称号无法检测：DeluxeTags 反射改为方法名宽容匹配（兼容不同版本重载），
 *   在线玩家直接取内存中的实际显示标签（含默认/强制标签），
 *   离线玩家回退持久化保存的标签选择，占位符文本自动剔除。
 */
object QueryInfoService {

    /** 单用户查询冷却（毫秒）。 */
    private const val USER_COOLDOWN_MILLIS = 5_000L

    /** 用户冷却记录：OpenId → 上次触发时间。 */
    private val lastQueryAt = ConcurrentHashMap<String, Long>()

    // ---------- 1.5.0：渲染性能限制（线程池 + 结果缓存） ----------

    /** 渲染队列长度上限：积压超过此值视为过载，直接回忙（防刷屏堆积）。 */
    private const val RENDER_QUEUE_LIMIT = 8

    /** 渲染结果缓存容量上限（超出先清过期再整体清空，防膨胀）。 */
    private const val CARD_CACHE_MAX = 32

    /** 渲染结果条目：PNG 字节 + 生成时间。 */
    private data class CachedCard(val bytes: ByteArray, val at: Long)

    /** 渲染结果缓存：玩家标识 → 最近一次卡片。 */
    private val cardCache = ConcurrentHashMap<String, CachedCard>()

    /** 专用渲染线程池（惰性创建；大小取自 qq-bind.render.threads，默认 1）。 */
    @Volatile
    private var renderPool: ThreadPoolExecutor? = null

    /** 渲染与发送的全部流程在异步线程执行，避免阻塞消息线程。 */
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

        val bindManager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            null
        }
        if (bindManager == null) {
            replyText(event, "❌ 绑定管理器未就绪，请稍后再试")
            return
        }

        // 1. 查找绑定的玩家
        val playerName = bindManager.findPlayerByQq(qqOpenId)
        val quuid = bindManager.findQuuidByQq(qqOpenId)
        if (playerName == null || playerName.isEmpty() || quuid == null || quuid.isEmpty()) {
            replyText(event, "该账号未绑定QQ，请先进入服务器完成绑定后再查询")
            return
        }

        // 2. 组装数据（在异步线程渲染前先取好主线程数据）
        val playerUuid: UUID? = parseUuid(bindManager.getPlayerUuidByQuuid(quuid))
        val stats = if (playerUuid != null) PlayerStatsManager.query(playerUuid) else PlayerStatsManager.PlayerStats()
        val vaultData = readVaultDataOnMainThread(playerName, playerUuid)
        val title = vaultData.first
        val balance = vaultData.second
        val skinInfo = readSkinInfoOnMainThread(playerUuid)

        // 3. 拉取头像与背景并渲染（1.5.0：专用渲染池 + 结果缓存）
        //    TTL 内同一玩家直接复用上次渲染的 PNG，零渲染开销秒回
        val cacheKey = playerUuid?.toString() ?: playerName
        val ttlMillis = resultCacheMillis()
        val cached = if (ttlMillis > 0) cardCache[cacheKey] else null
        if (cached != null && System.currentTimeMillis() - cached.at < ttlMillis) {
            val sent = QClient.replyWithImgBytes(event, cached.bytes)
            if (!sent) {
                replyText(event, "❌ 图片发送失败（机器人连接可能断开），请稍后重试")
            }
            return
        }

        val pool = try {
            renderPool().also { current ->
                if (current.queue.size >= RENDER_QUEUE_LIMIT) {
                    replyText(event, "当前查询人数较多，请稍后再试")
                    return
                }
            }
        } catch (_: RejectedExecutionException) {
            replyText(event, "当前查询人数较多，请稍后再试")
            return
        }

        try {
            pool.execute {
                try {
                    val avatar = InfoCardAssets.fetchAvatar(
                        InfoCardAssets.AvatarRequest(
                            uuid = playerUuid,
                            playerName = playerName,
                            skinUrl = skinInfo?.first,
                            playerDataDir = skinInfo?.second,
                        )
                    )
                    val dataDirectory = plugin.configFile?.parentFile
                    val background = dataDirectory?.let {
                        InfoCardAssets.processedBackground(it, InfoCardRenderer.WIDTH, InfoCardRenderer.HEIGHT)
                    }

                    val items = buildItems(stats, balance, title)
                    val bytes = InfoCardRenderer.render(
                        InfoCardRenderer.CardData(
                            playerName = playerName,
                            items = items,
                            avatar = avatar,
                            background = background,
                        )
                    )

                    // 1.5.0：渲染成功写入结果缓存（TTL 内重复查询直接复用）
                    if (ttlMillis > 0 && bytes.isNotEmpty()) {
                        cardCache[cacheKey] = CachedCard(bytes, System.currentTimeMillis())
                        if (cardCache.size > CARD_CACHE_MAX) {
                            cardCache.entries.removeIf { System.currentTimeMillis() - it.value.at >= ttlMillis }
                            if (cardCache.size > CARD_CACHE_MAX) cardCache.clear()
                        }
                    }

                    // 4. 发送图片（不 @）；发送失败与生成失败分开提示，便于定位问题
                    val sent = QClient.replyWithImgBytes(event, bytes)
                    if (!sent) {
                        replyText(event, "❌ 图片发送失败（机器人连接可能断开），请稍后重试")
                    }
                } catch (error: Throwable) {
                    // 失败日志带堆栈前几帧且双写日志文件，事后可从
                    // logs/qq/qq-bind-日期.log 追溯具体失败原因
                    plugin.log_error("[个人信息] 渲染卡片失败: ${error.message}")
                    plugin.log_error(
                        "[个人信息] 失败堆栈: " +
                            error.stackTraceToString().lineSequence().take(8).joinToString(" | ")
                    )
                    replyText(event, "❌ 信息卡片生成失败，请稍后重试或联系管理员")
                }
            }
        } catch (_: RejectedExecutionException) {
            replyText(event, "当前查询人数较多，请稍后再试")
        }
    }

    // ---------- 1.5.0：渲染池 / 结果缓存实现 ----------

    /**
     * 专用渲染线程池：固定 [qqBindRenderThreads]（默认 1，即渲染最多占用 1 核 CPU），
     * 低优先级守护线程（不与服务器主线程 / 机器人消息线程争抢 CPU），空闲自动回收。
     * 线程数在首次渲染时读取配置并固定，修改配置后重启服务器生效。
     */
    private fun renderPool(): ThreadPoolExecutor {
        renderPool?.let { return it }
        synchronized(this) {
            renderPool?.let { return it }
            val threads = qqBindRenderThreads()
            val pool = ThreadPoolExecutor(
                threads, threads,
                60L, TimeUnit.SECONDS,
                LinkedBlockingQueue(RENDER_QUEUE_LIMIT),
            ) { runnable ->
                Thread(runnable, "PenguinCardRender").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
            pool.allowCoreThreadTimeOut(true)
            renderPool = pool
            return pool
        }
    }

    /** qq-bind.render.threads：渲染线程数（默认 1 = 1 核，范围 1-4）。 */
    private fun qqBindRenderThreads(): Int = try {
        (Bukkit.getPluginManager().getPlugin("KERONGPenguin") as? JavaPlugin)
            ?.config?.getInt("qq-bind.render.threads", 1) ?: 1
    } catch (_: Throwable) {
        1
    }.coerceIn(1, 4)

    /** qq-bind.render.result-cache-seconds：渲染结果复用秒数（默认 60，0 = 关闭，上限 600）。 */
    private fun resultCacheMillis(): Long = try {
        (Bukkit.getPluginManager().getPlugin("KERONGPenguin") as? JavaPlugin)
            ?.config?.getLong("qq-bind.render.result-cache-seconds", 60L) ?: 60L
    } catch (_: Throwable) {
        60L
    }.coerceIn(0L, 600L) * 1000L

    /**
     * 组装统计项（0.1.5.5：按用户需求精简为 4 项，布局 2 列 × 2 行）。
     * 顺序：金币 / 称号 / 在线时长 / 今日在线时长。
     */
    private fun buildItems(
        stats: PlayerStatsManager.PlayerStats,
        balance: Double?,
        title: String,
    ): List<InfoCardRenderer.CardItem> = listOf(
        InfoCardRenderer.CardItem("金币", if (balance != null) formatAmount(balance) else "暂无"),
        InfoCardRenderer.CardItem("称号", title),
        InfoCardRenderer.CardItem("在线时长", formatPlayTime(stats.playSeconds)),
        InfoCardRenderer.CardItem("今日在线时长", formatTodayTime(stats.todaySeconds)),
    )

    /** 在主线程读取 Vault 称号与金币（异步调用时自动切换；超时返回默认值）。 */
    private fun readVaultDataOnMainThread(playerName: String, playerUuid: UUID?): Pair<String, Double?> {
        if (Bukkit.isPrimaryThread()) {
            return resolveTitle(playerName, playerUuid) to queryBalance(playerName, playerUuid)
        }
        val bukkitPlugin = Bukkit.getPluginManager().getPlugin("KERONGPenguin")
            ?: return "暂无" to null
        return try {
            val future = Bukkit.getScheduler().callSyncMethod(bukkitPlugin) {
                resolveTitle(playerName, playerUuid) to queryBalance(playerName, playerUuid)
            }
            // 等待不超过 3 秒，避免经济插件异常时长时间阻塞
            future.get(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            "暂无" to null
        }
    }

    /**
     * 主线程读取在线玩家皮肤贴图 URL 与 playerdata 目录（异步调用时自动切换；超时返回 null）。
     * 返回 (皮肤 URL, playerdata 目录)。
     */
    private fun readSkinInfoOnMainThread(playerUuid: UUID?): Pair<String?, File?>? {
        if (playerUuid == null) return null
        if (Bukkit.isPrimaryThread()) {
            return querySkinInfo(playerUuid)
        }
        val bukkitPlugin = Bukkit.getPluginManager().getPlugin("KERONGPenguin") ?: return null
        return try {
            Bukkit.getScheduler().callSyncMethod(bukkitPlugin) { querySkinInfo(playerUuid) }
                .get(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        }
    }

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
     * 解析玩家称号（0.1.5.5）：
     * 1. DeluxeTags（用户服务器默认称号插件，反射调用，返回带颜色码的原文，
     *    卡片端按 MC 色板彩色渲染；在线取激活标签，离线回退到持久化保存的选择）；
     * 2. Vault Chat 前缀（无 DeluxeTags 或无标签时，清理颜色码的纯文本）；
     * 3. 主权限组；
     * 4. 均无 → “暂无”。
     */
    private fun resolveTitle(playerName: String, playerUuid: UUID?): String {
        return try {
            resolveDeluxeTagsTitle(playerUuid)
                ?: resolveVaultTitle(playerName, playerUuid)
                ?: "暂无"
        } catch (_: Throwable) {
            "暂无"
        }
    }

    // ---------- 1.5.0：DeluxeTags 宽容反射 ----------

    /** DeluxeTags 玩家明确选择"无标签"时持久化的哨兵值（官方源码常量）。 */
    private const val DELUXETAGS_NO_TAG = "__deluxetags_no_tag__"

    /** 未解析的 PlaceholderAPI 占位符（如 %deluxetags_tag%），渲染前剔除避免出现百分号原文。 */
    private val UNRESOLVED_PLACEHOLDER = Regex("%[^%\\s]{1,64}%")

    /**
     * DeluxeTags 称号（反射调用，插件不存在 / 无标签返回 null）。
     * 返回值为称号原文（含 & / § 颜色码，如 "&7[&6Vip&7]&f"），
     * 由 [InfoCardRenderer] 解析后按原色渲染。
     *
     * 1.5.0 修复"称号无法检测"：
     * - 反射改为按方法名遍历公共方法并自适应参数类型（UUID / Player / OfflinePlayer / String），
     *   不再依赖精确签名，兼容不同版本 DeluxeTags 的重载差异；
     * - 在线玩家直接取内存中的实际显示标签（DeluxeTags 对默认 / 强制 / 玩家自选标签
     *   统一写入内存表，即游戏内聊天显示的称号）；
     * - displayTag 优先取带 OfflinePlayer 的重载（内部应用 PAPI 占位符，
     *   还原游戏内实际显示文本），未解析的占位符兜底剔除；
     * - 玩家明确选择"无标签"（哨兵值）或从未选择时返回 null，交由 Vault 链兜底。
     */
    private fun resolveDeluxeTagsTitle(playerUuid: UUID?): String? {
        if (playerUuid == null) return null
        return try {
            val deluxeTags = Bukkit.getPluginManager().getPlugin("DeluxeTags") ?: return null
            if (!deluxeTags.isEnabled) return null
            val handler = findMethod(deluxeTags, "getTagsHandler", 0)?.invoke(deluxeTags) ?: return null

            // 在线玩家对象（本函数在主线程调用，Bukkit.getPlayer 线程安全）
            val onlinePlayer = try {
                Bukkit.getPlayer(playerUuid)
            } catch (_: Throwable) {
                null
            }

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
                if (!cleaned.isNullOrEmpty()) return cleaned
            }
            null
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

    /** Vault 前缀 / 权限组称号（无 DeluxeTags 时的回退链，返回纯文本）。 */
    private fun resolveVaultTitle(playerName: String, playerUuid: UUID?): String? {
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
                    if (!cleaned.isNullOrEmpty()) return cleaned
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
                    if (!group.isNullOrEmpty()) return group
                }
            } catch (_: Throwable) {
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** 查询金币余额（Vault Economy 反射调用）。 */
    private fun queryBalance(playerName: String, playerUuid: UUID?): Double? {
        return try {
        val vaultPlugin = Bukkit.getPluginManager().getPlugin("Vault") ?: return null
        if (!vaultPlugin.isEnabled) return null
        val offlinePlayer: OfflinePlayer = playerUuid?.let { Bukkit.getOfflinePlayer(it) }
            ?: Bukkit.getOfflinePlayer(playerName)
        val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
        val rsp = Bukkit.getServicesManager().getRegistration(economyClass) ?: return null
        val economy = rsp.provider
        economy.javaClass
            .getMethod("getBalance", OfflinePlayer::class.java)
            .invoke(economy, offlinePlayer) as? Double
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
