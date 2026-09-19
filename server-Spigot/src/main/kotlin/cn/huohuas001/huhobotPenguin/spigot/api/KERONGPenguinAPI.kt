package cn.huohuas001.huhobotPenguin.spigot.api

import cn.huohuas001.huhobotPenguin.spigot.events.GameChat
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

/**
 * KERONGPenguin 公开 API。
 * 其他插件可通过此类调用 QQ 绑定查询、金币发放、签到等功能。
 *
 * 用法：
 * ```
 * val api = KERONGPenguinAPI.getInstance()
 * val player = api.getBoundPlayer(qqOpenId)
 * val ok = api.depositCoins(playerName, 100.0)
 * ```
 */
object KERONGPenguinAPI {

    /** 获取 API 实例。 */
    @JvmStatic
    fun getInstance(): KERONGPenguinAPI = this

    private fun manager(): QqBindManager? = try {
        QqBindManager.getInstance()
    } catch (_: Throwable) {
        null
    }

    private fun ready(): Boolean {
        val plugin: Plugin? = Bukkit.getPluginManager().getPlugin("KERONGPenguin")
        return plugin != null && plugin.isEnabled
    }

    /** 通过 QQ OpenId 查询绑定的玩家名。 */
    @JvmStatic
    fun getBoundPlayer(qqOpenId: String?): String? {
        if (!ready() || qqOpenId == null) return null
        return manager()?.findPlayerByQq(qqOpenId)
    }

    /** 通过玩家名查询绑定的 QQ OpenId。 */
    @JvmStatic
    fun getPlayerQq(playerName: String?): String? {
        if (!ready() || playerName == null) return null
        return manager()?.getBoundQq(playerName)
    }

    /** 通过玩家名查询 QUUID。 */
    @JvmStatic
    fun getPlayerQuuid(playerName: String?): String? {
        if (!ready() || playerName == null) return null
        return manager()?.getQuuid(playerName)
    }

    /** 玩家是否已绑定 QQ。 */
    @JvmStatic
    fun isPlayerBound(playerName: String?): Boolean {
        if (!ready() || playerName == null) return false
        return manager()?.isBound(playerName) == true
    }

    /** QQ 是否在黑名单。 */
    @JvmStatic
    fun isBlacklisted(qqOpenId: String?): Boolean {
        if (!ready() || qqOpenId == null) return false
        return try {
            manager()?.listBlacklist()?.contains(qqOpenId) == true
        } catch (_: Throwable) {
            false
        }
    }

    /** 给玩家发放金币（Vault）。 */
    @JvmStatic
    fun depositCoins(playerName: String?, amount: Double): Boolean {
        if (!ready() || playerName == null || amount <= 0) return false
        return GameChat.depositToPlayer(playerName, amount)
    }

    /** 查询玩家待领取的签到金币。 */
    @JvmStatic
    fun getPendingCoins(playerName: String?): Double {
        if (!ready() || playerName == null) return 0.0
        val mgr = manager() ?: return 0.0
        val quuid = mgr.getQuuid(playerName) ?: return 0.0
        return mgr.getPendingCoins(quuid)
    }

    /** 签到功能是否开启。 */
    @JvmStatic
    fun isCheckinEnabled(): Boolean {
        if (!ready()) return false
        return manager()?.isCheckinEnabled == true
    }

    /** 签到奖励金额。 */
    @JvmStatic
    fun getCheckinReward(): Double {
        if (!ready()) return 0.0
        return manager()?.checkinReward ?: 0.0
    }
}
