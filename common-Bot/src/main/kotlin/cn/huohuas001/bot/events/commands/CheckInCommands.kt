package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.huhobotPenguin.spigot.qqbind.BeijingTimeUtil
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method

/**
 * /签到 —— 每日签到领取金币（依赖 Vault 经济插件）。
 * 连续签到按北京时间计算；离线玩家的奖励先记账，上线自动补发。
 */
class CheckInCommands : CommandSupport() {

    @Commands("签到", "checkin", "sign")
    fun checkin(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            sendDirect(event, "❌ 管理器未就绪")
            return
        }
        if (!manager.isCheckinEnabled) {
            sendDirect(event, "⚠️ 签到功能未开启")
            return
        }
        val qq = try {
            userId(event)
        } catch (_: Throwable) {
            null
        }
        if (qq.isNullOrEmpty() || qq == "<unknown>") {
            sendDirect(event, "❌ 无法识别你的 QQ 账号")
            return
        }
        val playerName = manager.findPlayerByQq(qq)
        if (playerName.isNullOrEmpty()) {
            sendDirect(event, manager.checkinPrefix + "  ❌签到失败！\n您的QQ号暂未绑定任何玩家，请进入服务器获取验证码后绑定QQ号")
            return
        }
        val quuid = manager.findQuuidByQq(qq)
        if (quuid.isNullOrEmpty()) {
            sendDirect(event, manager.checkinPrefix + "  ❌签到失败！\n数据异常，请联系管理员")
            return
        }

        // 联网获取北京时间（可能阻塞数秒，此方法已在消息线程）
        val today = try {
            BeijingTimeUtil.getBeijingDate()
        } catch (_: Throwable) {
            ""
        }
        if (today.isEmpty()) {
            sendDirect(event, manager.checkinPrefix + "  ❌签到失败！\n获取服务器时间失败，请稍后重试")
            return
        }

        // 今日是否已签到
        val lastDate = manager.getCheckinDate(quuid)
        val prefix = manager.checkinPrefix
        if (today == lastDate) {
            sendDirect(event, prefix + "  ❌签到失败，💰你太贪了\n今日已经签到过了，请明天再试...")
            return
        }

        val reward = manager.checkinReward
        if (reward <= 0) {
            sendDirect(event, prefix + "  ❌签到失败！\n奖励金币数量配置错误")
            return
        }

        // 计算连续签到天数
        var streak = manager.getCheckinStreak(quuid)
        val yesterday = BeijingTimeUtil.nextDay(lastDate)
        streak = if (lastDate.isEmpty() || yesterday != today) 1 else streak + 1

        // 发放金币：在线直接入账，离线记账待上线补发
        val online = try {
            Bukkit.getPlayerExact(playerName) != null
        } catch (_: Throwable) {
            false
        }
        val paid = if (online) {
            depositPlayer(playerName, reward)
        } else {
            manager.addPendingCoins(quuid, reward)
            true
        }

        if (paid) {
            manager.setCheckinDate(quuid, today)
            manager.setCheckinStreak(quuid, streak)
            manager.addCheckinTotal(quuid, 1)
            sendDirect(event, "$prefix  ✔️签到成功！\n玩家 $playerName 获得了${formatMoney(reward)}金币，已连续签到${streak}天")
        } else {
            sendDirect(event, prefix + "  ❌签到失败！\n金币系统未就绪或数据异常，请联系管理员")
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

    /** 带 @ 提及的 Markdown 回复（失败时退化为纯文本）。 */
    private fun sendDirect(event: GroupMessageEvent, message: String) {
        try {
            val userId = try {
                userId(event)
            } catch (_: Throwable) {
                null
            }
            val content = if (!userId.isNullOrEmpty() && userId != "<unknown>") "<@$userId>\n$message" else message
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
