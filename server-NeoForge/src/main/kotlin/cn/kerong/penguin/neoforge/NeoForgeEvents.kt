package cn.kerong.penguin.neoforge

import cn.kerong.bot.QClient
import cn.kerong.penguin.spigot.qqbind.AiChat
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import cn.kerong.penguin.spigot.stats.PlayerStatsManager
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.block.Blocks
import net.neoforged.bus.api.EventPriority
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
class NeoForgeEvents(private val plugin: KERONGNeoForge) {

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
            QClient.registerCommand(cn.kerong.bot.events.commands.BindCommands())
            bindRegistered = true
 // 注册例行信息仅写文件留档，不再刷控制台（用户需求）
            QqBindManager.logFileOnly("/绑定 命令已注册 (第 $bindRetries 次尝试)")
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

    /**
     * 判定消息是否命中游戏内 AI 前缀触发（唤醒）：命中返回去前缀后的
     * 对话内容，未命中（AI 关闭 / 前缀不匹配 / 内容为空）返回 null。
     *
     * 纯判定无副作用——[onChatAiWake] 与 [onChat] 两个监听共用同一条件
     *（与 Spigot 版 GameChat.serverAiTriggerContent / Fabric 版
     * serverAiTriggerContent 同源同口径）。
     */
    internal fun serverAiTriggerContent(message: String, manager: QqBindManager): String? {
        if (!manager.isServerAiEnabled || !manager.isAiEnabled) return null
        val prefix = manager.serverAiPrefix()
        if (prefix.isEmpty() || !message.lowercase().startsWith("$prefix ")) return null
        val content = message.substring(prefix.length + 1).trim()
        return content.ifEmpty { null }
    }

    /**
     * 游戏内 AI 唤醒监听（1.7.6.5 起独立拆出，与 Spigot 版 GameChat
     * .onChatAiWake 同口径）：
     *
     * - **HIGHEST 优先级（NeoForge 侧最先执行）+ 接收已取消事件**——
     *   服务器装了格式化聊天模组（取消原生聊天事件、自行广播格式化
     *   消息的那类）时，旧版不接收取消事件的监听根本收不到事件，AI
     *   唤醒消息识别不到；现在本监听在格式化模组动手之前拿到**原始
     *   消息**（rawText）做前缀判定，事件被取消也能触发；
     * - 1.7.6.4 覆盖轮起不再取消聊天事件（玩家消息照常显示，异步取回
     *   AI 回复后追加广播），本监听只读不写不取消；
     * - AI 触发消息不转发 QQ 群（由 [onChat] 按同一判定跳过）。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    fun onChatAiWake(event: ServerChatEvent) {
        val player = event.player
        if (player.level().isClientSide) return
 // 多版本适配备注：getRawText: String 在 1.20.4 / 1.20.6 /
        // 1.21.x 三代 NeoForge 字节码签名完全一致（已逐一 javap 核对），
        // 直接调用无需跨版本反射。
        val message = event.rawText ?: return
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            null
        } ?: return
        val content = serverAiTriggerContent(message, manager) ?: return
        val playerName = player.gameProfile.name
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
    }

    /**
     * 聊天转发到 QQ 群（默认优先级、不接收已取消事件）：事件未被取消
     * 才转发（格式化模组取消后自行广播的消息维持既有口径——不重复
     * 转发）；AI 触发消息不转发（与 [onChatAiWake] 共用同一判定）。
     */
    @SubscribeEvent
    fun onChat(event: ServerChatEvent) {
        val player = event.player
        if (player.level().isClientSide) return
        val message = event.rawText ?: return
        val playerName = player.gameProfile.name
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            null
        }
        if (manager != null && serverAiTriggerContent(message, manager) != null) return
        QClient.broadcastGameMessage(playerName, message)
    }

    // ---------- 玩家进出 ----------

    @SubscribeEvent
    fun onPlayerJoin(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (player.level().isClientSide) return
        QClient.broadcastPlayerJoin(player.gameProfile.name)
 // 假人登记（1.7.6.1）：本地注入型连接（BindLoginGate 同源判定）或
 // fake-player-whitelist 名单命中 → 记入假人登记表（在线排行榜不再计入
 // 其时长、/查在线 开启假人列表后划入假人小节）；真实外网连接 → 自愈
        try {
            cn.kerong.penguin.spigot.stats.FakePlayerRegistry.evaluateJoin(
                player.uuid,
                player.gameProfile.name,
                BindLoginGate.isLocalFakeConnection(player.connection),
            )
        } catch (_: Throwable) {
        }
        PlayerStatsManager.onJoin(NeoPlayer(player))
        enforceLoginBinding(player)
    }

    /**
     * 登录绑定校验（未绑定玩家的加入后踢出，与 Spigot 版 GameChat.enforceJoinBinding 同源）。
 * 分工：未绑定的老玩家已在配置阶段被 BindLoginGate 拦截（提示直接显示
     * 在“无法连接到服务器”界面）；此处负责新玩家（服务器先建档 UUID 再踢出）
     * 与闸门失效（总线挂载失败 / 反射异常）时的兜底——所有走到加入事件的
     * 未绑定玩家一律踢出并展示绑定码。
     */
    private fun enforceLoginBinding(player: ServerPlayer) {
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            return
        }
        if (!manager.isEnabled) return

 // 假人豁免（1.7.5.8）：本地注入型连接（空/回环地址、内存连接）不是
 // 真实外网玩家，加入后踢出路径同样放行（与 BindLoginGate 配置阶段判定同源；
 // ServerPlayer.connection 为公共字段，传入 isLocalFakeConnection 反射读取）
        if (BindLoginGate.isLocalFakeConnection(player.connection)) return

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

 /** 死亡：玩家死亡计数；击杀归属（怪物击杀 / 屠龙 / 击杀玩家）；死亡播报。 */
    @SubscribeEvent
    fun onLivingDeath(event: LivingDeathEvent) {
        val dying = event.entity
        if (dying.level().isClientSide) return
        val killer = event.source.entity as? ServerPlayer

        if (dying is ServerPlayer) {
            PlayerStatsManager.onPlayerDeath(NeoPlayer(dying))
            // 死亡播报（player-events.death.enabled，默认 false；占位符口径与
            // HuHoBot 一致，中文描述生成见 [DeathMessageNeo]）；1.7.6.4 覆盖轮：
            // custom-causes 开关（默认开）关闭时只展示内置原版死因表，其余统一「死了」
            QClient.broadcastPlayerDeath(
                dying.gameProfile.name,
                DeathMessageNeo.describe(event, plugin.playerEventFormat().deathCustomCauses),
                DeathMessageNeo.killerOf(event),
            )
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
