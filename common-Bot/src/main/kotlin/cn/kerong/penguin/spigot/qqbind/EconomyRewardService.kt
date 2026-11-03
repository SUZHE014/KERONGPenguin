package cn.kerong.penguin.spigot.qqbind

import cn.kerong.bot.provider.BotShared
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * 签到奖品经济发放服务（1.7.2 新增）。
 *
 * 背景：签到奖励此前只检测 Vault（金币）一条链路且不校验到账；用户要求
 * 增加点券等经济插件检测——①发放奖品时通过对应插件 API 校验发放到账
 * （发放前后余额差值），未到账返回失败；②软依赖 + Class 探测，插件未装
 * 时优雅跳过不报错。
 *
 * 三个奖励通道（全部反射调用，无编译期依赖）：
 * - **Vault 金币**（net.milkbowl.vault.economy.Economy，经 ServicesManager
 *   解析）：[depositVaultVerified]——getBalance 前后差值校验；
 * - **PlayerPoints 点券**（org.black_ixx.playerpoints，getAPI() →
 *   PlayerPointsAPI）：[givePointsVerified]——look(UUID) 前后差值校验，
 *   支持离线玩家（点券按 UUID 持久）；
 * - **CoinsEngine 金币**（com.magmaguy.coinsengine，CoinsEngineAPI 静态
 *   方法）：[giveCoinsEngineVerified]——getCoins 前后差值校验；仅当方法
 *   签名匹配（UUID/OfflinePlayer 形参）时可用，API 形态不符自动跳过。
 *
 * 检测语义（每次实时探测，中途安装 / 卸载即时生效）：
 * - [isEconomyAvailable]：Vault 经济链路是否就绪（兼容旧调用方语义）；
 * - [isPointsAvailable] / [isCoinsEngineAvailable]：对应插件是否可用；
 * - NeoForge 端无 Bukkit 插件生态，三者恒不可用——签到自动走
 * 无经济分支（记录天数不发奖）或离线记账，优雅跳过零报错。
 */
object EconomyRewardService {

    /** 校验通过要求的最低到账比例（余额差值 / 发放数额；容忍浮点误差）。 */
    private const val VERIFY_RATIO = 0.999

    // ---------- Vault 金币 ----------

    /** Vault 经济服务实例（Vault 插件已启用且经济服务已注册；否则 null）。 */
    private fun vaultEconomy(): Any? {
        return try {
            val vault: Plugin? = Bukkit.getPluginManager().getPlugin("Vault")
            if (vault == null || !vault.isEnabled) return null
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
            val servicesManager = Bukkit.getServer().javaClass.getMethod("getServicesManager").invoke(Bukkit.getServer())
            val rsp = servicesManager.javaClass.getMethod("getRegistration", Class::class.java).invoke(servicesManager, economyClass) ?: return null
            Class.forName("org.bukkit.plugin.RegisteredServiceProvider").getMethod("getProvider").invoke(rsp)
        } catch (_: Throwable) {
            null
        }
    }

    /** Vault 经济是否就绪（与旧 CheckInCommands.isEconomyAvailable 同一检测链）。 */
    fun isEconomyAvailable(): Boolean = vaultEconomy() != null

    /** Vault 余额读取（读取失败返回 null）。 */
    private fun vaultBalance(economy: Any, offlinePlayer: OfflinePlayer): Double? {
        return try {
            economy.javaClass.getMethod("getBalance", OfflinePlayer::class.java)
                .invoke(economy, offlinePlayer) as? Double
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Vault 金币入账 + 到账校验（1.7.2：发放前后余额差值）。
     *
     * 校验规则：depositPlayer 返回 transactionSuccess 且（余额可读时）
     * 差值 ≥ 数额 × 0.999；余额读不到时退回只看 transactionSuccess
     * （与旧行为一致，不因个别经济插件 getBalance 异常误判失败）。
     */
    fun depositVaultVerified(playerName: String, amount: Double, offlinePlayer: OfflinePlayer? = null): Boolean {
        val economy = vaultEconomy() ?: return false
        return try {
            val player: OfflinePlayer = offlinePlayer ?: Bukkit.getOfflinePlayer(playerName)
            val before = vaultBalance(economy, player)
            val result = economy.javaClass
                .getMethod("depositPlayer", OfflinePlayer::class.java, Double::class.javaPrimitiveType)
                .invoke(economy, player, amount) ?: return false
            val transactionOk = try {
                val success = result.javaClass.getMethod("transactionSuccess").invoke(result)
                java.lang.Boolean.TRUE == success
            } catch (_: Throwable) {
                true
            }
            if (!transactionOk) return false
            val after = vaultBalance(economy, player)
            if (before != null && after != null) {
                (after - before) >= amount * VERIFY_RATIO
            } else {
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- PlayerPoints 点券 ----------

    /** PlayerPoints 插件实例（已启用；未装 / 未启用返回 null）。 */
    private fun pointsPlugin(): Plugin? {
        return try {
            val plugin = Bukkit.getPluginManager().getPlugin("PlayerPoints") ?: return null
            if (plugin.isEnabled) plugin else null
        } catch (_: Throwable) {
            null
        }
    }

    /** PlayerPointsAPI 实例（插件存在且 getAPI 可调；否则 null）。 */
    private fun pointsApi(): Any? {
        val plugin = pointsPlugin() ?: return null
        return try {
            plugin.javaClass.methods.firstOrNull { it.name == "getAPI" && it.parameterCount == 0 }
                ?.invoke(plugin)
        } catch (_: Throwable) {
            null
        }
    }

    /** PlayerPoints 点券插件是否可用。 */
    fun isPointsAvailable(): Boolean = pointsApi() != null

    /** 点券余额（look(UUID)；读取失败返回 null）。 */
    private fun pointsBalance(api: Any, playerUuid: UUID): Int? {
        return try {
            val method = api.javaClass.methods.firstOrNull {
                it.parameterCount == 1 && java.util.UUID::class.java == it.parameterTypes[0] &&
                    (it.name == "look" || Number::class.java.isAssignableFrom(it.returnType) || it.returnType == Int::class.javaPrimitiveType)
            } ?: return null
            when (val value = method.invoke(api, playerUuid)) {
                is Number -> value.toInt()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * PlayerPoints 点券入账 + 到账校验（give(UUID, int) 前后 look 差值）。
     *
     * - 支持离线玩家（点券按 UUID 持久，与在线状态无关）；
     * - give 方法签名不匹配（版本差异）或调用异常返回 false；
     * - 余额读不到时只看 give 返回值（boolean / 非 0 int 视为成功）。
     */
    fun givePointsVerified(playerUuid: UUID, amount: Int): Boolean {
        if (amount <= 0) return true
        val api = pointsApi() ?: return false
        return try {
            val give = api.javaClass.methods.firstOrNull {
                it.name == "give" && it.parameterCount == 2 &&
                    java.util.UUID::class.java == it.parameterTypes[0] &&
                    (it.parameterTypes[1] == Int::class.javaPrimitiveType || it.parameterTypes[1] == Integer::class.java)
            } ?: return false
            val before = pointsBalance(api, playerUuid)
            val result = give.invoke(api, playerUuid, amount)
            val reportedOk = when (result) {
                is Boolean -> result
                is Number -> result.toInt() != 0
                else -> true
            }
            if (!reportedOk) return false
            val after = pointsBalance(api, playerUuid)
            if (before != null && after != null) {
                (after - before) >= amount
            } else {
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- CoinsEngine 金币 ----------

    /** CoinsEngine 静态 API 类（未安装 / 类不存在返回 null）。 */
    private fun coinsEngineApiClass(): Class<*>? {
        return try {
            val plugin = Bukkit.getPluginManager().getPlugin("CoinsEngine")
            if (plugin == null || !plugin.isEnabled) return null
            Class.forName("com.magmaguy.coinsengine.CoinsEngineAPI")
        } catch (_: Throwable) {
            null
        }
    }

    /** CoinsEngine 金币插件是否可用（插件已启用且静态 API 形态匹配）。 */
    fun isCoinsEngineAvailable(): Boolean = coinsEngineClassPair() != null

    /** CoinsEngine 可用方法对（addCoins / getCoins，形参形态兼容离线玩家）。 */
    private data class CoinsEngineMethods(val addCoins: java.lang.reflect.Method, val getCoins: java.lang.reflect.Method, val byUuid: Boolean)

    private fun coinsEngineClassPair(): CoinsEngineMethods? {
        val apiClass = coinsEngineApiClass() ?: return null
        return try {
            val methods = apiClass.methods
            val add = methods.firstOrNull { it.name == "addCoins" && it.parameterCount == 2 } ?: return null
            val paramType = add.parameterTypes[0]
            val byUuid = paramType == java.util.UUID::class.java || paramType == OfflinePlayer::class.java
            if (!byUuid && paramType != org.bukkit.entity.Player::class.java) return null
            val get = methods.firstOrNull {
                it.name == "getCoins" && it.parameterCount == 1 && it.parameterTypes[0] == paramType
            } ?: return null
            CoinsEngineMethods(add, get, paramType == java.util.UUID::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * CoinsEngine 金币入账 + 到账校验（getCoins 前后差值）。
     *
     * - 仅当 addCoins 形参是 UUID / OfflinePlayer（离线可用）时参与签到发放；
     *   形参是 Player（仅在线）的版本按不可用跳过——签到发生时玩家常离线；
     * - 余额读不到时退回只看 addCoins 是否正常返回（无异常即视为成功）。
     */
    fun giveCoinsEngineVerified(playerName: String, playerUuid: UUID?, amount: Double): Boolean {
        val pair = coinsEngineClassPair() ?: return false
        return try {
            val target: Any = when {
                pair.byUuid && playerUuid != null -> playerUuid
                else -> Bukkit.getOfflinePlayer(playerName)
            }
            val before = try {
                when (val value = pair.getCoins.invoke(null, target)) {
                    is Number -> value.toDouble()
                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
            pair.addCoins.invoke(null, target, amount)
            val after = try {
                when (val value = pair.getCoins.invoke(null, target)) {
                    is Number -> value.toDouble()
                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
            if (before != null && after != null) {
                (after - before) >= amount * VERIFY_RATIO
            } else {
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- 配置 ----------

    /**
     * 签到点券奖励数额（qq-bind.checkin.points-reward，1.7.2 新增，默认 0 = 关闭）。
     * 实时读取（reload 即生效）。
     */
    fun checkinPointsReward(): Int {
        return try {
            (BotShared.instance as? JavaPlugin)?.config?.getInt("qq-bind.checkin.points-reward", 0) ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    /** 检测结论写日志文件（排查「签到没发点券」类问题；仅文件不刷控制台）。 */
    fun logDetectionSummary() {
        try {
            cn.kerong.bot.tools.PluginFileLog.write(
                "[签到] 经济通道检测：Vault=${isEconomyAvailable()} PlayerPoints=${isPointsAvailable()} " +
                    "CoinsEngine=${isCoinsEngineAvailable()}（金币经 Vault，缺 Vault 时回退 CoinsEngine；点券经 PlayerPoints）"
            )
        } catch (_: Throwable) {
        }
    }
}
