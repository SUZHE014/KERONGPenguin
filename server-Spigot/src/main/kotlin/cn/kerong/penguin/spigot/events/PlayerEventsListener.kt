package cn.kerong.penguin.spigot.events

import cn.kerong.penguin.spigot.stats.FakePlayerRegistry
import cn.kerong.penguin.spigot.stats.PlayerStatsManager
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityBreedEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.raid.RaidTriggerEvent

/**
 * 玩家统计事件监听（/玩家名片 卡片的数据采集端，1.5.2 前命令名为 /个人信息）。
 * 采集策略（低开销）：
 * - 高频事件（方块破坏）只在远古残骸时才写计数，其余直接返回；
 * - 击杀/死亡/钓鱼/繁殖/袭击为低频事件，直接累计；
 * - 游戏时长与原版统计（距离/伤害）由 PlayerStatsManager 周期快照结算，
 *   不依赖事件监听。
 *
 * 移除原 ServerListPingEvent 监听（旧 motd 捕获
 * 机制只记最大人数、模组端无事件恒真实值、改包型假人数读不到）——
 * motd 展示值改由 [cn.kerong.penguin.spigot.stats.MotdDisplayTracker]
 * 本机 SLP 自查询捕获，不再依赖事件。
 */
class PlayerEventsListener : Listener {

    /** 进入：开启统计会话。 */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
 // 假人登记（1.7.6.1）：本地注入型连接（地址为空 / 回环）或
 // fake-player-whitelist 名单命中 → 记入假人登记表（在线排行榜不再
 // 计入其时长、/查在线 开启假人列表后划入假人小节）；真实外网连接 →
 // 自愈移除历史误登记（与 GameChat 绑定拦截豁免同一判定口径）
        try {
            FakePlayerRegistry.evaluateJoin(
                event.player.uniqueId,
                event.player.name,
                isLocalFakeJoin(event.player),
            )
        } catch (_: Throwable) {
        }
        PlayerStatsManager.onJoin(event.player)
    }

    /** 退出：结算会话并保存。 */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        PlayerStatsManager.onQuit(event.player)
    }

    /** 破坏方块（仅统计远古残骸）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        PlayerStatsManager.onBlockBreak(event.player, event.block.type)
    }

    /** 实体死亡（怪物击杀 / 屠龙 / 击杀玩家；1.5.2 自伤自杀不计入击杀）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        if (event.entity === killer) return  // 自己造成的死亡（自己的箭/TNT 等）不算击杀玩家
        PlayerStatsManager.onEntityDeath(killer, event.entity.type)
    }

 /** 玩家死亡：统计 + 死亡播报（播报，默认关闭）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity.player ?: return
        PlayerStatsManager.onPlayerDeath(player)
        // 死亡播报（player-events.death.enabled，默认 false；文案与占位符口径
        // 参考 HuHoBot，描述生成见 [DeathMessage]）
        cn.kerong.bot.QClient.broadcastPlayerDeath(
            event.entity.name,
            DeathMessage.describe(event),
            DeathMessage.killerOf(event),
        )
    }

    /** 钓鱼（仅统计钓上鱼）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state == PlayerFishEvent.State.CAUGHT_FISH) {
            PlayerStatsManager.onFishCaught(event.player)
        }
    }

    /** 繁殖动物。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreed(event: EntityBreedEvent) {
        val breeder = event.breeder ?: return
        if (breeder is org.bukkit.entity.Player) {
            PlayerStatsManager.onAnimalBred(breeder)
        }
    }

    /** 触发袭击。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRaidTrigger(event: RaidTriggerEvent) {
        PlayerStatsManager.onRaidTrigger(event.player)
    }

    /**
     * 加入阶段假人连接判定（1.7.6.1，与 GameChat.isLocalFakeJoin 同源）：
     * 地址为空（进程内注入，无网络通道）或回环（127.0.0.0/8，
     * minecraft-fakeplayer 的 FakeChannel 生成）→ 假人插件假人。
     * 未解析地址（hostname 型，代理场景）按真实玩家处理（与绑定豁免口径
     * 一致——真实玩家来自外网地址不会被误判，BungeeCord/Velocity 正确
     * 开启 IP 透传时同样是真实外网 IP）。
     */
    private fun isLocalFakeJoin(player: Player): Boolean {
        val socketAddress = try {
            player.address
        } catch (_: Throwable) {
            null
        } ?: return true
        val address = try {
            socketAddress.address
        } catch (_: Throwable) {
            null
        } ?: return false
        return address.isLoopbackAddress
    }
}
