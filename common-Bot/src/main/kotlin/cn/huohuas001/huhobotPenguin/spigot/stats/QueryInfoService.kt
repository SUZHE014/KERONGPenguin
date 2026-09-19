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
import java.util.UUID

/**
 * /个人信息 命令服务：QQ → 玩家 UUID → 数据组装 → 卡片渲染 → 图片发送。
 *
 * 流程：
 * 1. 通过 QQ OpenId 查找绑定的玩家（未绑定则直接提示）；
 * 2. 汇总数据：金币（Vault）、签到次数（绑定系统）、其余生涯统计（本插件自记录）；
 * 3. 异步渲染毛玻璃风格统计卡片（背景取插件目录 img/，无图用黑色）；
 * 4. 以图片消息发送到群（不 @ 提及）。
 */
object QueryInfoService {

    /** 渲染与发送的全部流程在异步线程执行，避免阻塞消息线程。 */
    fun handle(plugin: HuHoBot, event: GroupMessageEvent, qqOpenId: String) {
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
        val checkinTotal = bindManager.getCheckinTotal(quuid)
        val vaultData = readVaultDataOnMainThread(playerName, playerUuid)
        val title = vaultData.first
        val balance = vaultData.second

        // 3. 拉取头像与背景并渲染
        plugin.submitAsync {
            try {
                val avatar = if (playerUuid != null) {
                    InfoCardAssets.fetchAvatar(playerUuid.toString(), playerName)
                } else {
                    InfoCardAssets.fetchAvatar(playerName, playerName)
                }
                val dataDirectory = plugin.configFile?.parentFile
                val background = dataDirectory?.let { InfoCardAssets.loadBackground(it) }

                val items = buildItems(stats, balance, checkinTotal, title)
                val bytes = InfoCardRenderer.render(
                    InfoCardRenderer.CardData(
                        playerName = playerName,
                        items = items,
                        avatar = avatar,
                        background = background,
                    )
                )

                // 4. 发送图片（不 @）
                QClient.replyWithImgBytes(event, bytes)
            } catch (error: Throwable) {
                plugin.log_error("[个人信息] 渲染卡片失败: ${error.message}")
                replyText(event, "❌ 信息卡片生成失败，请稍后重试或联系管理员")
            }
        }
    }

    /** 组装 15 项生涯统计数据（顺序与卡片网格一致）。 */
    private fun buildItems(
        stats: PlayerStatsManager.PlayerStats,
        balance: Double?,
        checkinTotal: Long,
        title: String,
    ): List<InfoCardRenderer.CardItem> = listOf(
        InfoCardRenderer.CardItem("游戏时间", formatPlayTime(stats.playSeconds)),
        InfoCardRenderer.CardItem("今日在线", formatTodayTime(stats.todaySeconds)),
        InfoCardRenderer.CardItem("称号", title),
        InfoCardRenderer.CardItem("金币数量", if (balance != null) formatAmount(balance) else "暂无"),
        InfoCardRenderer.CardItem("击杀怪物", formatNumber(stats.mobKills)),
        InfoCardRenderer.CardItem("签到次数", formatNumber(checkinTotal) + " 次"),
        InfoCardRenderer.CardItem("挖掘残骸", formatNumber(stats.ancientDebris)),
        InfoCardRenderer.CardItem("死亡次数", formatNumber(stats.deaths)),
        InfoCardRenderer.CardItem("触发袭击", formatNumber(stats.raidTriggers)),
        InfoCardRenderer.CardItem("飞行距离", formatDistance(stats.flyCm)),
        InfoCardRenderer.CardItem("屠龙数量", formatNumber(stats.dragonKills)),
        InfoCardRenderer.CardItem("钓鱼数量", formatNumber(stats.fishCaught)),
        InfoCardRenderer.CardItem("走过的路", formatDistance(stats.walkCm)),
        InfoCardRenderer.CardItem("繁殖动物", formatNumber(stats.animalsBred)),
        InfoCardRenderer.CardItem("造成伤害", formatNumber(stats.damageDealt)),
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

    /** 解析玩家称号。 */
    private fun resolveTitle(playerName: String, playerUuid: UUID?): String {
        return try {
        val vault = Bukkit.getPluginManager().getPlugin("Vault") ?: return "暂无"
        if (!vault.isEnabled) return "暂无"
        val offlinePlayer: OfflinePlayer = playerUuid?.let { Bukkit.getOfflinePlayer(it) }
            ?: Bukkit.getOfflinePlayer(playerName)
        // 1) Vault Chat 前缀
        try {
            val chatClass = Class.forName("net.milkbowl.vault.chat.Chat")
            val rsp = Bukkit.getServicesManager().getRegistration(chatClass)
            if (rsp != null) {
                val chat = rsp.provider
                val prefix = chat.javaClass.getMethod("getPlayerPrefix", String::class.java, OfflinePlayer::class.java)
                    .invoke(chat, "world", offlinePlayer) as? String
                val cleaned = prefix?.replace("§.".toRegex(), "")?.trim()
                if (!cleaned.isNullOrEmpty()) return cleaned.take(12)
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
        "暂无"
        } catch (_: Throwable) {
            "暂无"
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
