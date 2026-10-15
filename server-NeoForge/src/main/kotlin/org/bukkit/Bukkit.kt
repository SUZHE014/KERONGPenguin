package org.bukkit

import net.minecraft.server.MinecraftServer
import org.bukkit.entity.NeoPlayer
import org.bukkit.entity.Player
import cn.kerong.penguin.neoforge.NeoServerRef

/**
 * org.bukkit 兼容层（NeoForge 1.21.1 适配）。
 *
 * 作用：common-Bot 模块源码直接引用 org.bukkit API（Spigot 构建编译期依赖
 * paper-api）；NeoForge 构建将同一份源码连同本兼容层一起编译，把其中的
 * Bukkit 调用桥接到 NeoForge / 原版服务端 API。仅实现 common-Bot 实际用到的
 * API 面（调度 / 玩家查询 / 主线程切换 / 统计 / 世界目录）。
 *
 * ⚠️ 兼容层类编译进模组分发 JAR；不要在混合端（Mohist 等自带 Bukkit 的服务端）
 * 上同时安装本模组——那些服务端请使用 KERONGPenguin Spigot 版。
 */
object Bukkit {

    /** 服务端对象（反射 getServicesManager 可见）。 */
    @JvmStatic
    fun getServer(): Server = ServerImpl

    /** 插件管理器（仅识别本模组；Vault/DeluxeTags 等 Bukkit 生态插件一律视为不存在）。 */
    @JvmStatic
    fun getPluginManager(): org.bukkit.plugin.PluginManager = org.bukkit.plugin.PluginManager

    /** 调度器（主线程 = 服务器线程）。 */
    @JvmStatic
    fun getScheduler(): org.bukkit.scheduler.BukkitScheduler = org.bukkit.scheduler.BukkitScheduler

    /** 服务管理器（Bukkit 插件生态不存在，恒返回 null 注册项）。 */
    @JvmStatic
    fun getServicesManager(): org.bukkit.plugin.ServicesManager = org.bukkit.plugin.ServicesManager

    /** 当前线程是否为服务器主线程。 */
    @JvmStatic
    fun isPrimaryThread(): Boolean {
        val server: MinecraftServer? = NeoServerRef.server
        return server == null || server.isSameThread
    }

    // ---------- Folia 调度器入口（1.6.0 兼容层桩） ----------

    // NeoForge 永远不是 Folia（FoliaCompat 按反射查找 getGlobalRegionScheduler
    // 方法判定，本平台不存在），以下入口仅让 common-Bot 的 FoliaDelegate
    // 编译通过；运行时永远不会被调用（调用即抛）。

    /** Folia 全局区域调度器（兼容层桩，不可用）。 */
    @JvmStatic
    fun getGlobalRegionScheduler(): io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler =
        throw UnsupportedOperationException("Folia 调度器仅存在于 Folia 系服务端")

    /** Folia 异步调度器（兼容层桩，不可用）。 */
    @JvmStatic
    fun getAsyncScheduler(): io.papermc.paper.threadedregions.scheduler.AsyncScheduler =
        throw UnsupportedOperationException("Folia 调度器仅存在于 Folia 系服务端")

    /** 按名称前缀匹配在线玩家（Bukkit 语义：大小写不敏感，可部分匹配）。 */
    @JvmStatic
    fun getPlayer(name: String): Player? {
        if (name.isEmpty()) return null
        val server = NeoServerRef.server ?: return null
        val lower = name.lowercase()
        val matched = server.playerList.players
            .firstOrNull { it.gameProfile.name.lowercase().startsWith(lower) } ?: return null
        return NeoPlayer(matched)
    }

    /** 按精确名称查找在线玩家（大小写不敏感）。 */
    @JvmStatic
    fun getPlayerExact(name: String): Player? {
        val server = NeoServerRef.server ?: return null
        val matched = server.playerList.getPlayerByName(name) ?: return null
        return NeoPlayer(matched)
    }

    /** 按 UUID 查找在线玩家。 */
    @JvmStatic
    fun getPlayer(uuid: java.util.UUID): Player? {
        val server = NeoServerRef.server ?: return null
        val matched = server.playerList.getPlayer(uuid) ?: return null
        return NeoPlayer(matched)
    }

    /** 离线玩家（用户缓存档案；缓存未命中时名字为 null）。 */
    @JvmStatic
    fun getOfflinePlayer(uuid: java.util.UUID): org.bukkit.OfflinePlayer =
        org.bukkit.OfflinePlayerImpl.byUuid(uuid)

    /** 离线玩家（按名称，用户缓存解析 UUID）。 */
    @JvmStatic
    fun getOfflinePlayer(name: String): org.bukkit.OfflinePlayer =
        org.bukkit.OfflinePlayerImpl.byName(name)

    /** 当前在线玩家。 */
    @JvmStatic
    fun getOnlinePlayers(): Collection<Player> {
        val server = NeoServerRef.server ?: return emptyList()
        return server.playerList.players.map { NeoPlayer(it) }
    }

    /** 世界列表（仅首个世界用于定位 playerdata 目录）。 */
    @JvmStatic
    fun getWorlds(): List<org.bukkit.World> {
        val server = NeoServerRef.server ?: return emptyList()
        return listOf(WorldImpl(server))
    }

    private object ServerImpl : Server
}
