package org.bukkit

import com.mojang.authlib.GameProfile
import java.util.UUID

/** 离线玩家接口（兼容层最小面：name / isOnline）。 */
interface OfflinePlayer {
    val name: String?
    val isOnline: Boolean
}

/** 离线玩家实现（用户缓存档案）。 */
class OfflinePlayerImpl private constructor(
    private val profile: GameProfile?,
) : OfflinePlayer {

    override val name: String?
        get() = profile?.name

    override val isOnline: Boolean
        get() = profile != null && Bukkit.getPlayer(profile.id) != null

    companion object {
        fun byUuid(uuid: UUID): OfflinePlayerImpl = OfflinePlayerImpl(
            try {
                cn.huohuas001.huhobotPenguin.neoforge.NeoServerRef.server
                    ?.profileCache?.get(uuid)?.orElse(null)
            } catch (_: Throwable) {
                null
            },
        )

        fun byName(name: String): OfflinePlayerImpl = OfflinePlayerImpl(
            try {
                cn.huohuas001.huhobotPenguin.neoforge.NeoServerRef.server
                    ?.profileCache?.get(name)?.orElse(null)
            } catch (_: Throwable) {
                null
            },
        )
    }
}
