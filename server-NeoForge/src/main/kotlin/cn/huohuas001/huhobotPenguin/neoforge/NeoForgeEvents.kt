package cn.huohuas001.huhobotPenguin.neoforge

import cn.huohuas001.bot.QClient
import cn.huohuas001.huhobotPenguin.spigot.qqbind.AiChat
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import cn.huohuas001.huhobotPenguin.spigot.stats.PlayerStatsManager
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.block.Blocks
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.ServerChatEvent
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import org.bukkit.entity.EntityType
import org.bukkit.entity.NeoPlayer
import org.bukkit.Material

/**
 * NeoForge 事件监听（等价 Spigot 版 GameChat + PlayerEventsListener）：
 * - 聊天转发到 QQ 群（含服务器内 AI 对话前缀触发）；
 * - 玩家进入 / 退出播报 + 统计会话；
 * - 击杀 / 死亡 / 破坏方块统计（信息卡数据源）；
 * - 服务器生命周期（QQ 机器人运行时启停）。
 */
class NeoForgeEvents(private val plugin: HuHoBotNeoForge) {

    /** /绑定 命令是否已注册。 */
    @Volatile
    private var bindRegistered = false

    /** /绑定 注册重试次数。 */
    private var bindRetries = 0

    companion object {
        private const val MAX_BIND_RETRIES = 8
    }

    // ---------- 生命周期 ----------

    @SubscribeEvent
    fun onServerAboutToStart(event: ServerAboutToStartEvent) {
        plugin.onServerAboutToStart(event.server)
    }

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        plugin.onServerStarted()
        tryRegisterBind()
    }

    // ---------- /绑定 命令注册（QQ 客户端异步启动后注册，重试兜底） ----------

    /** 等价 Spigot 版 GameChat.tryRegisterBind。 */
    @Synchronized
    private fun tryRegisterBind() {
        if (bindRegistered || bindRetries >= MAX_BIND_RETRIES) return
        bindRetries++
        try {
            QClient.registerCommand(cn.huohuas001.bot.events.commands.BindCommands())
            bindRegistered = true
            QqBindManager.logQuiet("/绑定 命令已注册 (第 $bindRetries 次尝试)")
        } catch (_: Throwable) {
            // 客户端尚未启动：稍后重试
            plugin.submitLater(40L) { tryRegisterBind() }
        }
    }

    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        plugin.onServerStopping()
    }

    @SubscribeEvent
    fun onServerStopped(event: ServerStoppedEvent) {
        plugin.onServerStopped()
    }

    // ---------- 聊天转发 + 服务器内 AI ----------

    @SubscribeEvent
    fun onChat(event: ServerChatEvent) {
        val player = event.player
        if (player.level().isClientSide) return
        val message = event.rawText ?: return
        val playerName = player.gameProfile.name

        // 服务器内 AI 对话（前缀触发，与 GameChat.onChat 一致）
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            null
        }
        if (manager != null && manager.isServerAiEnabled && manager.isAiEnabled) {
            val prefix = manager.serverAiPrefix()
            if (prefix.isNotEmpty() && message.lowercase().startsWith("$prefix ")) {
                val content = message.substring(prefix.length + 1).trim()
                if (content.isNotEmpty()) {
                    event.isCanceled = true
                    plugin.submitAsync {
                        try {
                            val reply = AiChat.chat(content, manager, "server", playerName)
                            val outputPrefix = manager.serverAiOutputPrefix()
                            plugin.broadcastMessage("$outputPrefix $reply")
                        } catch (t: Throwable) {
                            QqBindManager.logVerbose("[AI对话] 服务器调用失败: ${t.message}")
                            val outputPrefix = manager.serverAiOutputPrefix()
                            plugin.broadcastMessage("$outputPrefix AI 对话失败: ${t.message}")
                        }
                    }
                    return
                }
            }
        }
        QClient.broadcastGameMessage(playerName, message)
    }

    // ---------- 玩家进出 ----------

    @SubscribeEvent
    fun onPlayerJoin(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (player.level().isClientSide) return
        QClient.broadcastPlayerJoin(player.gameProfile.name)
        PlayerStatsManager.onJoin(NeoPlayer(player))
        enforceLoginBinding(player)
    }

    /**
     * 登录绑定校验（等价 Spigot 版 GameChat.onPlayerLogin 的未绑定拒入）：
     * NeoForge 事件在进入完成后触发，改为加入即断开并在断开界面展示绑定码。
     */
    private fun enforceLoginBinding(player: ServerPlayer) {
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            return
        }
        if (!manager.isEnabled) return
        val playerName = player.gameProfile.name
        val playerUuid = player.uuid.toString()
        if (manager.isSkipped(playerName)) return

        // 确保绑定记录存在（UUID 机制，含旧数据迁移）
        manager.getOrCreateQuuidByUuid(playerName, playerUuid)
        if (manager.isBoundByUuid(playerName, playerUuid)) return

        // 未绑定：生成绑定码并在断开界面提示（延后 1 tick，确保踢出界面文案就绪）
        val code = manager.generateCode(playerName, playerUuid)
        val kickMessage = manager.formatKickMessage(code, playerName)
        plugin.submitLater(1L) {
            try {
                if (!player.hasDisconnected()) {
                    player.connection.disconnect(LegacyText.toComponent(kickMessage))
                }
            } catch (_: Throwable) {
            }
        }
    }

    @SubscribeEvent
    fun onPlayerQuit(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (player.level().isClientSide) return
        QClient.broadcastPlayerQuit(player.gameProfile.name)
        PlayerStatsManager.onQuit(NeoPlayer(player))
    }

    // ---------- 统计（信息卡数据源） ----------

    /** 死亡：玩家死亡计数；击杀归属（怪物击杀 / 屠龙 / 击杀玩家）。 */
    @SubscribeEvent
    fun onLivingDeath(event: LivingDeathEvent) {
        val dying = event.entity
        if (dying.level().isClientSide) return
        val killer = event.source.entity as? ServerPlayer

        if (dying is ServerPlayer) {
            PlayerStatsManager.onPlayerDeath(NeoPlayer(dying))
        }
        if (killer != null && killer !== dying) {
            PlayerStatsManager.onEntityDeath(NeoPlayer(killer), mapEntityType(dying))
        }
    }

    /** 破坏方块（仅统计远古残骸）。 */
    @SubscribeEvent
    fun onBlockBreak(event: BlockEvent.BreakEvent) {
        val player = event.player as? ServerPlayer ?: return
        if (player.level().isClientSide) return
        val material = if (event.state.block == Blocks.ANCIENT_DEBRIS) Material.ANCIENT_DEBRIS else Material.OTHER
        PlayerStatsManager.onBlockBreak(NeoPlayer(player), material)
    }

    /**
     * 钓鱼收竿（钓上渔获时计数）。
     * NeoForge ItemFishedEvent 在收竿带掉落物时触发（含少量杂物误差，
     * 与 Bukkit CAUGHT_FISH 的纯鱼类计数略有差异，可接受）。
     */
    @SubscribeEvent
    fun onItemFished(event: ItemFishedEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (player.level().isClientSide) return
        if (event.drops.isEmpty()) return
        PlayerStatsManager.onFishCaught(NeoPlayer(player))
    }

    /** 繁殖动物（繁殖者计数）。 */
    @SubscribeEvent
    fun onBabySpawn(event: BabyEntitySpawnEvent) {
        val player = event.causedByPlayer as? ServerPlayer ?: return
        if (player.level().isClientSide) return
        if (event.child == null) return
        PlayerStatsManager.onAnimalBred(NeoPlayer(player))
    }

    /** 死亡实体 → Bukkit 兼容层 EntityType 映射。 */
    private fun mapEntityType(entity: LivingEntity): EntityType = when {
        entity is ServerPlayer -> EntityType.PLAYER
        entity is net.minecraft.world.entity.boss.enderdragon.EnderDragon -> EntityType.ENDER_DRAGON
        else -> EntityType.GENERIC_MOB
    }
}
