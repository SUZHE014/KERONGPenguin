package org.bukkit

import java.io.File

/** Server 接口（兼容层最小面：CheckInCommands 反射 getServicesManager）。 */
interface Server {
    /** 服务管理器（Bukkit 生态不存在，恒空）。 */
    fun getServicesManager(): org.bukkit.plugin.ServicesManager = org.bukkit.plugin.ServicesManager
}

/** World（兼容层最小面：playerdata 目录定位）。 */
interface World {
    /** 世界存档目录。 */
    val worldFolder: File
}

/**
 * 世界实现：主世界存档目录（server.getWorldPath(ROOT)）。
 * QueryInfoService / OnlineListService 用它 resolve("playerdata") 定位离线玩家 NBT。
 */
class WorldImpl(private val handle: net.minecraft.server.MinecraftServer) : World {
    override val worldFolder: File
        get() = net.minecraft.world.level.storage.LevelResource.ROOT
            .let { handle.getWorldPath(it).toFile() }
}
