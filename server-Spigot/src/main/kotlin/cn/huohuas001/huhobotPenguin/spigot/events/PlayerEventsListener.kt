package cn.huohuas001.huhobotPenguin.spigot.events

import cn.huohuas001.huhobotPenguin.spigot.stats.PlayerStatsManager
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
 * 玩家统计事件监听（/查信息 卡片的数据采集端）。
 *
 * 采集策略（低开销）：
 * - 高频事件（方块破坏）只在远古残骸时才写计数，其余直接返回；
 * - 击杀/死亡/钓鱼/繁殖/袭击为低频事件，直接累计；
 * - 游戏时长与原版统计（距离/伤害）由 PlayerStatsManager 周期快照结算，
 *   不依赖事件监听。
 */
class PlayerEventsListener : Listener {

    /** 进入：开启统计会话。 */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
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

    /** 实体死亡（怪物击杀 / 屠龙）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        PlayerStatsManager.onEntityDeath(killer, event.entity.type)
    }

    /** 玩家死亡。 */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity.player ?: return
        PlayerStatsManager.onPlayerDeath(player)
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
}
