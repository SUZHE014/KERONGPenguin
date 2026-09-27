package org.bukkit.entity

import com.mojang.authlib.GameProfile
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import org.bukkit.OfflinePlayer
import org.bukkit.Statistic
import java.net.URL
import java.util.Base64
import java.util.UUID

/** 在线玩家接口（兼容层最小面；与 Bukkit 一致：Player 是 OfflinePlayer 的子类型）。 */
interface Player : OfflinePlayer {
    override val name: String
    val uniqueId: UUID
    override val isOnline: Boolean
    fun sendMessage(message: String)
    fun kickPlayer(reason: String)
    fun getStatistic(statistic: Statistic): Int
    val playerProfile: PlayerProfile
}

/** 玩家档案（兼容层最小面：皮肤贴图 URL）。 */
class PlayerProfile(private val profile: GameProfile) {
    val textures: ProfileTextures = ProfileTextures(profile)
}

/** 贴图集合（皮肤 URL 解析）。 */
class ProfileTextures(profile: GameProfile) {
    /** 皮肤贴图 URL（档案无贴图属性或解析失败时为 null）。 */
    val skin: URL? = try {
        profile.properties.get("textures").firstOrNull()?.let { property ->
            val decoded = String(Base64.getDecoder().decode(property.value), Charsets.UTF_8)
            val url = com.alibaba.fastjson.JSON.parseObject(decoded)
                ?.getJSONObject("textures")
                ?.getJSONObject("SKIN")
                ?.getString("url")
            if (url.isNullOrEmpty()) null else URL(url)
        }
    } catch (_: Throwable) {
        null
    }
}

/** 在线玩家实现（包装 ServerPlayer）。 */
class NeoPlayer(internal val handle: ServerPlayer) : Player {

    override val name: String
        get() = handle.gameProfile.name

    override val uniqueId: UUID
        get() = handle.gameProfile.id

    override val isOnline: Boolean
        get() = !handle.hasDisconnected()

    override fun sendMessage(message: String) {
        handle.sendSystemMessage(
            cn.huohuas001.huhobotPenguin.neoforge.LegacyText.toComponent(message)
        )
    }

    override fun kickPlayer(reason: String) {
        handle.connection.disconnect(cn.huohuas001.huhobotPenguin.neoforge.LegacyText.toComponent(reason))
    }

    override fun getStatistic(statistic: Statistic): Int = try {
        val counter = handle.getStats()
        val formatter = net.minecraft.stats.StatFormatter.DEFAULT
        when (statistic) {
            Statistic.WALK_ONE_CM -> counter.getValue(Stats.CUSTOM.get(Stats.WALK_ONE_CM, formatter))
            Statistic.FLY_ONE_CM -> counter.getValue(Stats.CUSTOM.get(Stats.FLY_ONE_CM, formatter))
            Statistic.DAMAGE_DEALT -> counter.getValue(Stats.CUSTOM.get(Stats.DAMAGE_DEALT, formatter))
        }
    } catch (_: Throwable) {
        0
    }

    override val playerProfile: PlayerProfile
        get() = PlayerProfile(handle.gameProfile)
}
