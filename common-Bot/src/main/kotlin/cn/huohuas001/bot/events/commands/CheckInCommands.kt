package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.huhobotPenguin.spigot.qqbind.BeijingTimeUtil
import cn.huohuas001.huhobotPenguin.spigot.qqbind.MessageTemplates
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method

/**
 * /签到 —— 每日签到领取金币（依赖 Vault 经济插件）。
 * 连续签到按北京时间计算；离线玩家的奖励先记账，上线自动补发。
 *
 * 1.6.0：全部文本回复改走消息模板（checkin-*，配置 messages 段可换文件），
 * 模板渲染出口统一应用经济别名（economy-alias，签到成功文案中的「金币」
 * 二字自动替换为别名，默认不配置时与旧版输出一字不差）。
 *
 * 1.5.5：签到开启但金币插件（Vault 经济）不可用时自动关闭金币奖励——
 * 不入账也不记账，签到照常累计（连续天数 / 总次数），
 * 成功提示不再显示金币数量（检测每次实时进行，中途装 / 卸经济插件即时生效）。
 */
class CheckInCommands : CommandSupport() {

    @Commands("签到", "checkin", "sign")
    fun checkin(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            MessageTemplates.sendDirect(event, "manager-not-ready")
            return
        }
        if (!manager.isCheckinEnabled) {
            MessageTemplates.sendDirect(event, "checkin-disabled")
            return
        }
        val qq = try {
            userId(event)
        } catch (_: Throwable) {
            null
        }
        if (qq.isNullOrEmpty() || qq == "<unknown>") {
            MessageTemplates.sendDirect(event, "qq-unknown")
            return
        }
        val playerName = manager.findPlayerByQq(qq)
        if (playerName.isNullOrEmpty()) {
            MessageTemplates.sendDirect(event, "checkin-not-bound", mapOf("prefix" to manager.checkinPrefix))
            return
        }
        val quuid = manager.findQuuidByQq(qq)
        if (quuid.isNullOrEmpty()) {
            MessageTemplates.sendDirect(event, "checkin-data-error", mapOf("prefix" to manager.checkinPrefix))
            return
        }

        // 联网获取北京时间（可能阻塞数秒，此方法已在消息线程）
        val today = try {
            BeijingTimeUtil.getBeijingDate()
        } catch (_: Throwable) {
            ""
        }
        if (today.isEmpty()) {
            MessageTemplates.sendDirect(event, "checkin-time-error", mapOf("prefix" to manager.checkinPrefix))
            return
        }

        // 今日是否已签到
        val lastDate = manager.getCheckinDate(quuid)
        val prefix = manager.checkinPrefix
        if (today == lastDate) {
            MessageTemplates.sendDirect(event, "checkin-already", mapOf("prefix" to prefix))
            return
        }

        // 计算连续签到天数
        var streak = manager.getCheckinStreak(quuid)
        val yesterday = BeijingTimeUtil.nextDay(lastDate)
        streak = if (lastDate.isEmpty() || yesterday != today) 1 else streak + 1

        // 1.5.5：金币插件（Vault 经济）不可用时自动关闭金币奖励：
        // 签到照常累计（连续天数 / 总次数），不发放也不记账，提示不显示金币。
        // NeoForge 平台无 Vault 生态同样走本分支（旧版记入待领字段但永远无法补发，
        // 新版不再积累无效数据）。
        if (!isEconomyAvailable()) {
            manager.setCheckinDate(quuid, today)
            manager.setCheckinStreak(quuid, streak)
            manager.addCheckinTotal(quuid, 1)
            MessageTemplates.sendDirect(event, "checkin-success-no-economy", mapOf(
                "prefix" to prefix,
                "player" to playerName,
                "streak" to streak.toString(),
            ))
            return
        }

        val reward = manager.checkinReward
        if (reward <= 0) {
            MessageTemplates.sendDirect(event, "checkin-reward-invalid", mapOf("prefix" to prefix))
            return
        }

        // 发放金币：在线直接入账，离线记账待上线补发
        // 1.5.4：非 Spigot 平台（NeoForge）无 Vault 经济生态，在线也走记账，
        // 签到正常累计（连续/总次数），金币记入 QUUID 待领字段
        val online = try {
            Bukkit.getPlayerExact(playerName) != null
        } catch (_: Throwable) {
            false
        }
        val paid = if (online && plugin.platform == "spigot") {
            depositPlayer(playerName, reward)
        } else {
            manager.addPendingCoins(quuid, reward)
            true
        }

        if (paid) {
            manager.setCheckinDate(quuid, today)
            manager.setCheckinStreak(quuid, streak)
            manager.addCheckinTotal(quuid, 1)
            MessageTemplates.sendDirect(event, "checkin-success", mapOf(
                "prefix" to prefix,
                "player" to playerName,
                "reward" to formatMoney(reward),
                "streak" to streak.toString(),
            ))
        } else {
            MessageTemplates.sendDirect(event, "checkin-pay-error", mapOf("prefix" to prefix))
        }
    }

    /**
     * 金币插件（Vault + 经济服务）是否就绪（1.5.5）。
     *
     * 与 [depositPlayer] 同一检测链：Vault 插件已启用且经济服务已注册。
     * 每次签到实时检测（纯内存查找零 IO 开销），中途安装 / 卸载经济插件即时生效。
     */
    fun isEconomyAvailable(): Boolean {
        return try {
            val vault: Plugin? = Bukkit.getPluginManager().getPlugin("Vault")
            if (vault == null || !vault.isEnabled) return false
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
            val servicesManager = Bukkit.getServer().javaClass.getMethod("getServicesManager").invoke(Bukkit.getServer())
            val rsp = servicesManager.javaClass.getMethod("getRegistration", Class::class.java).invoke(servicesManager, economyClass) ?: return false
            val provider = Class.forName("org.bukkit.plugin.RegisteredServiceProvider").getMethod("getProvider").invoke(rsp) ?: return false
            provider != null
        } catch (_: Throwable) {
            false
        }
    }

    /** 通过反射调用 Vault Economy 给玩家入账（避免编译期依赖）。 */
    fun depositPlayer(playerName: String, amount: Double): Boolean {
        val vault: Plugin? = Bukkit.getPluginManager().getPlugin("Vault")
        if (vault == null || !vault.isEnabled) return false
        return try {
            val offlinePlayer: OfflinePlayer = Bukkit.getOfflinePlayer(playerName)
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
            val registeredServiceProviderClass = Class.forName("org.bukkit.plugin.RegisteredServiceProvider")
            val servicesManager = Bukkit.getServer().javaClass.getMethod("getServicesManager").invoke(Bukkit.getServer())
            val getRegistration: Method = servicesManager.javaClass.getMethod("getRegistration", Class::class.java)
            val rsp = getRegistration.invoke(servicesManager, economyClass) ?: return false
            val economy = registeredServiceProviderClass.getMethod("getProvider").invoke(rsp) ?: return false
            val result = economyClass.getMethod("depositPlayer", OfflinePlayer::class.java, Double::class.javaPrimitiveType)
                .invoke(economy, offlinePlayer, amount) ?: return false
            try {
                val success = result.javaClass.getMethod("transactionSuccess").invoke(result)
                success == true
            } catch (_: Throwable) {
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 金额显示（整数去掉小数点）。 */
    fun formatMoney(amount: Double): String =
        if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()
}
