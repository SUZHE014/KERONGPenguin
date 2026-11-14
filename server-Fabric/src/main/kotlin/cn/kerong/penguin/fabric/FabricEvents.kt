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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
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
 * - 玩家进入 / 退出播报 + 统计会话 + 建档（QQ 绑定不拦截进服，见下）；
 * - 击杀 / 死亡统计与死亡播报（ALLOW_DEATH 阶段，返回 true 放行）；
 * - 破坏方块统计（远古残骸）；
 * - /hbkrg 与 /qq 命令注册（CommandRegistrationCallback）。
 *
 * 钓鱼 / 繁殖动物统计暂缺（Fabric API 无对应事件，与 NeoForge 版的已知差异）。
 *
 * 1.7.5.9 起 Fabric 端 QQ 绑定策略覆盖（用户指令）：未绑定玩家不再自愿
 * 绑定——进入服务器后限制全部互动（锁移动 / 禁交互 / 禁聊天 / 禁命令，
 * 详见 [BindRestrict]），聊天栏持续输出绑定码，超时踢出，绑定成功自动
 * 解限；/hbkrg qbd 取码指令已删除（绑定码自动输出，无需自助查询）。
 * 原 1.7.5.1「不拦截 + 自愿取码」方案不再存在。
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

        // 聊天：ALLOW 阶段先做服务器内 AI 前缀判定（命中拦截并异步回复）；
        // 1.7.5.9：未绑定限制玩家在此拦截（不广播 / 不转发 QQ 群）
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { message, sender, _ ->
            if (!BindRestrictInteractions.onChat(sender)) return@register false
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
            // QQ 绑定建档（统计 / 后续绑定用）+ 1.7.5.9 未绑定限制启动
            // （[BindRestrict]：未绑定→限制互动+持续输出绑定码+超时踢出）
            registerPlayerProfile(player)
            BindRestrict.onJoin(player)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val player = handler.player
            QClient.broadcastPlayerQuit(player.gameProfile.name)
            PlayerStatsManager.onQuit(FabricPlayer(player))
            BindRestrict.onQuit(player)
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

        // 命令注册（1.7.5.9：注册后对整棵命令树做 requirement 包装，
        // 受限玩家的命令 parse 一律拒绝；详见 [BindRestrictCommandGate]）
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            FabricCommands(plugin).registerAll(dispatcher)
            BindRestrictCommandGate.ensureWrapped(dispatcher.root)
        }

        // 1.7.5.9 未绑定限制：周期循环（锚点锁定 / 绑定码输出 / 超时踢出 /
        // 绑定成功解限）与广播型命令（/me /say）拦截
        ServerTickEvents.END_SERVER_TICK.register { _ -> BindRestrict.tick() }
        ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register { _, source, _ ->
            BindRestrictInteractions.onCommand(try { source.getPlayer() } catch (_: Throwable) { null })
        }
        UseBlockCallback.EVENT.register { player, _, _, _ ->
            BindRestrictInteractions.onUseBlock(player as? ServerPlayer)
        }
        AttackBlockCallback.EVENT.register { player, _, _, _, _ ->
            BindRestrictInteractions.onAttackBlock(player as? ServerPlayer)
        }
        UseEntityCallback.EVENT.register { player, _, _, _, _ ->
            BindRestrictInteractions.onUseEntity(player as? ServerPlayer)
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
     * 进服建档（QQ 绑定的统计与后续绑定流程都基于 QUUID 档案）；
     * 1.7.5.9 起未绑定玩家的限制由 [BindRestrict.onJoin] 启动。
     */
    private fun registerPlayerProfile(player: ServerPlayer) {
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            return
        }
        if (!manager.isEnabled) return
        manager.getOrCreateQuuidByUuid(player.gameProfile.name, player.uuid.toString())
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
