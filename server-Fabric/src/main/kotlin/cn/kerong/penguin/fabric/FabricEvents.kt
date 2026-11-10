package cn.kerong.penguin.fabric

import cn.kerong.bot.QClient
import cn.kerong.penguin.spigot.qqbind.AiChat
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import cn.kerong.penguin.spigot.stats.PlayerStatsManager
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.block.Blocks
import org.bukkit.entity.EntityType
import org.bukkit.entity.FabricPlayer
import org.bukkit.Material

/**
 * Fabric 事件监听（等价 NeoForge 版 NeoForgeEvents / Spigot 版 GameChat +
 * PlayerEventsListener）：
 * - 生命周期（SERVER_STARTING / STARTED / STOPPING / STOPPED → QQ 运行时启停）；
 * - 聊天转发到 QQ 群 + 服务器内 AI 对话前缀触发（ALLOW_CHAT 阶段拦截）；
 * - 玩家进入 / 退出播报 + 统计会话 + 登录绑定校验（加入后踢出兜底）；
 * - 击杀 / 死亡统计与死亡播报（ALLOW_DEATH 阶段，返回 true 放行）；
 * - 破坏方块统计（远古残骸）；
 * - /hbkrg 与 /qq 命令注册（CommandRegistrationCallback）。
 *
 * 测试版差异（相对 NeoForge 版）：钓鱼 / 繁殖动物统计暂缺（Fabric API
 * 无对应事件）；登录绑定不做配置阶段拦截（无 RegisterConfigurationTasks
 * 等价物，未绑定玩家走加入后踢出兜底路径）。
 */
class FabricEvents(private val plugin: KERONGFabric) {

    /** /绑定 命令是否已注册。 */
    @Volatile
    private var bindRegistered = false

    /** /绑定 注册重试次数。 */
    private var bindRetries = 0

    companion object {
        private const val MAX_BIND_RETRIES = 8
    }

    /** 事件注册入口（KERONGFabric.onInitializeServer 后由主类调用一次）。 */
    fun registerAll() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            plugin.onServerAboutToStart(server)
        }
        ServerLifecycleEvents.SERVER_STARTED.register { _ ->
            plugin.onServerStarted()
            tryRegisterBind()
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            plugin.onServerStopping()
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { _ ->
            plugin.onServerStopped()
        }

        // 聊天：ALLOW 阶段先做服务器内 AI 前缀判定（命中拦截并异步回复）
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { message, sender, _ ->
            handleAllowChat(message.signedContent(), sender)
        }
        // 聊天：CHARGE 阶段转发到 QQ 群（AI 命中已在 ALLOW 阶段返回 false 不会到达这里）
        ServerMessageEvents.CHAT_MESSAGE.register { message, sender, _ ->
            QClient.broadcastGameMessage(sender.gameProfile.name, message.signedContent())
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            QClient.broadcastPlayerJoin(player.gameProfile.name)
            PlayerStatsManager.onJoin(FabricPlayer(player))
            enforceLoginBinding(player)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val player = handler.player
            QClient.broadcastPlayerQuit(player.gameProfile.name)
            PlayerStatsManager.onQuit(FabricPlayer(player))
        }

        // 死亡 / 击杀统计 + 死亡播报（返回 true 放行真实死亡）
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, source, _ ->
            onLivingDeath(entity, source)
        }

        // 破坏方块统计（仅远古残骸）
        PlayerBlockBreakEvents.AFTER.register { _, player, _, state, _ ->
            if (player is ServerPlayer) {
                val material = if (state.block == Blocks.ANCIENT_DEBRIS) Material.ANCIENT_DEBRIS else Material.OTHER
                PlayerStatsManager.onBlockBreak(FabricPlayer(player), material)
            }
        }

        // 命令注册
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            FabricCommands(plugin).registerAll(dispatcher)
        }
    }

    // ---------- /绑定 命令注册（QQ 客户端异步启动后注册，重试兜底） ----------

    @Synchronized
    private fun tryRegisterBind() {
        if (bindRegistered || bindRetries >= MAX_BIND_RETRIES) return
        bindRetries++
        try {
            QClient.registerCommand(cn.kerong.bot.events.commands.BindCommands())
            bindRegistered = true
            QqBindManager.logFileOnly("/绑定 命令已注册 (第 $bindRetries 次尝试)")
        } catch (_: Throwable) {
            plugin.submitLater(40L) { tryRegisterBind() }
        }
    }

    // ---------- 聊天：服务器内 AI 前缀触发 ----------

    /**
     * ALLOW_CHAT 阶段：前缀命中返回 false（消息不广播）并异步 AI 回复；
     * 否则返回 true 放行（后续 CHAT_MESSAGE 阶段转发到 QQ 群）。
     */
    private fun handleAllowChat(message: String, sender: ServerPlayer): Boolean {
        if (message.isEmpty()) return true
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            null
        } ?: return true
        if (!manager.isServerAiEnabled || !manager.isAiEnabled) return true
        val prefix = manager.serverAiPrefix()
        if (prefix.isEmpty() || !message.lowercase().startsWith("$prefix ")) return true
        val content = message.substring(prefix.length + 1).trim()
        if (content.isEmpty()) return true
        val playerName = sender.gameProfile.name
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
        return false
    }

    // ---------- 玩家进出 ----------

    /**
     * 登录绑定校验（未绑定玩家的加入后踢出兜底；与 NeoForge 版
     * enforceLoginBinding 同源——Fabric 测试版无配置阶段闸门，全部走此路径）。
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

        manager.getOrCreateQuuidByUuid(playerName, playerUuid)
        if (manager.isBoundByUuid(playerName, playerUuid)) return

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

    // ---------- 死亡 / 击杀统计 + 死亡播报 ----------

    private fun onLivingDeath(entity: LivingEntity, source: net.minecraft.world.damagesource.DamageSource): Boolean {
        if (entity.level().isClientSide) return true
        val killer = source.entity as? ServerPlayer
        if (entity is ServerPlayer) {
            PlayerStatsManager.onPlayerDeath(FabricPlayer(entity))
            QClient.broadcastPlayerDeath(
                entity.gameProfile.name,
                DeathMessageFabric.describe(entity, source),
                DeathMessageFabric.killerOf(entity, source),
            )
        }
        if (killer != null && killer !== entity) {
            PlayerStatsManager.onEntityDeath(FabricPlayer(killer), mapEntityType(entity))
        }
        return true
    }

    /** 死亡实体 → Bukkit 兼容层 EntityType 映射。 */
    private fun mapEntityType(entity: LivingEntity): EntityType = when {
        entity is ServerPlayer -> EntityType.PLAYER
        entity is net.minecraft.world.entity.boss.enderdragon.EnderDragon -> EntityType.ENDER_DRAGON
        else -> EntityType.GENERIC_MOB
    }
}
