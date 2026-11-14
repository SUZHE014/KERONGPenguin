package cn.kerong.penguin.fabric

import cn.kerong.penguin.spigot.qqbind.QqBindManager
import com.mojang.brigadier.tree.CommandNode
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Predicate

/**
 * Fabric 端 QQ 绑定「未绑定限制」策略（1.7.5.9，覆盖 1.7.5.1 起「不拦截」
 * 的自愿绑定方案——用户指令：改为限制互动，不再直接踢出）：
 *
 * 玩家进入服务器后检测（uuid）未绑定 → 不踢出，改为限制该玩家全部互动：
 * - 限制行走 / 跑步：每 tick 锚点锁定（位置偏移即传送回进服点）；
 * - 限制方块交互（右键使用 / 左键开始破坏）与生物交互（右键实体）；
 * - 限制发送消息（公共聊天不广播、不转发 QQ 群）；
 * - 限制指令输入（所有命令的发送： brigadier 命令树包装——受限玩家
 *   parse 任何命令都按「未知命令」拒绝；/me /say 广播型命令另由
 *   ALLOW_COMMAND_MESSAGE 拦截兜底）。
 *
 * 聊天栏持续输出绑定码（每 5 秒一次；绑定码过期自动换新码）；
 * 到达 [restrictTimeoutSeconds]（qq-bind.restrict-timeout-seconds，默认
 * 120 秒）仍未绑定 → 踢出服务器；再次进入则重新刷新绑定码；
 * QQ 群绑定成功后（绑定判定与之前一样用 uuid，tick 轮询 1 秒内感知）
 * 自动解除该玩家的全部限制。
 *
 * 旧验证码获取指令 /hbkrg qbd 已删除（绑定码持续自动输出，无需玩家
 * 自助查询）。
 *
 * 假人插件（minecraft-fakeplayer 等）：新策略下假人可正常加入，但未
 * 绑定同样受限、超时同样被踢（本轮未做假人白名单，如实实现）。
 *
 * 仅 server-Fabric 生效；Spigot / NeoForge 端仍为登录期拦截方案
 * （1.7.5.8 起本地注入型假人连接豁免），两套策略差异待用户定夺。
 */
object BindRestrict {

    /** 单个受限玩家的会话状态。 */
    private class Entry(
        val playerName: String,
        val playerUuid: String,
        var code: String,
        var anchorX: Double,
        var anchorY: Double,
        var anchorZ: Double,
        val joinAt: Long,
        val deadline: Long,
        var lastAnnounceAt: Long = 0L,
        var lastBindCheckAt: Long = 0L,
    )

    /** uuid → 受限会话（主线程与事件线程都可能读，服务器线程写）。 */
    private val entries = ConcurrentHashMap<String, Entry>()

    /** 输出绑定码的间隔（毫秒，每 5 秒一条）。 */
    private const val ANNOUNCE_INTERVAL_MILLIS = 5_000L

    /** 绑定状态轮询间隔（毫秒，1 秒内感知绑定成功）。 */
    private const val BIND_CHECK_INTERVAL_MILLIS = 1_000L

    /** 位置锁定判定半径（方块；偏移超过即传送回锚点）。 */
    private const val ANCHOR_RADIUS = 0.75

    // ---------- 查询（事件回调高频调用，只读） ----------

    /** 该玩家当前是否处于未绑定限制中（null / 离线 = 否）。 */
    fun isRestricted(player: ServerPlayer?): Boolean {
        if (player == null) return false
        return entries.containsKey(player.uuid.toString())
    }

    // ---------- 进出服（FabricEvents 驱动） ----------

    /** 玩家进入：未绑定 → 生成/刷新绑定码并启动限制；已绑定 / 功能关闭 → 不限。 */
    fun onJoin(player: ServerPlayer) {
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            null
        } ?: return
        if (!manager.isEnabled) return
        val playerName = player.gameProfile.name
        val playerUuid = player.uuid.toString()
        if (manager.isBoundByUuid(playerName, playerUuid)) return
        // 未绑定：刷新绑定码（generateCode 清同玩家旧码后发新码——
        // 「再次进入则重新刷新绑定码」由这里的每次进服调用天然满足）
        val code = try {
            manager.generateCode(playerName, playerUuid)
        } catch (_: Throwable) {
            return
        }
        val timeoutSeconds = restrictTimeoutSeconds()
        val now = System.currentTimeMillis()
        val pos = player.position()
        entries[playerUuid] = Entry(
            playerName, playerUuid, code,
            pos.x, pos.y, pos.z,
            now, now + timeoutSeconds * 1000L,
        )
        QqBindManager.logQuiet("[Fabric绑定] 玩家=$playerName 未绑定，进入限制状态（绑定码 $code，${timeoutSeconds}s 后超时踢出）")
        // 立即输出第一条（不等到第一个 5 秒）
        announceCode(player, code, manager.codeExpireMinutes)
    }

    /** 玩家退出：清理限制会话。 */
    fun onQuit(player: ServerPlayer) {
        val uuid = player.uuid.toString()
        if (entries.remove(uuid) != null) {
            QqBindManager.logFileOnly("[Fabric绑定] 玩家=${player.gameProfile.name} 退出，清理限制会话")
        }
    }

    // ---------- 周期循环（END_SERVER_TICK，服务器主线程） ----------

    fun tick() {
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (uuid, entry) = iterator.next()
            val player = currentOnlinePlayer(entry) ?: run {
                iterator.remove()
                continue
            }
            // ① 超时踢出
            if (now >= entry.deadline) {
                kickTimeout(player)
                iterator.remove()
                continue
            }
            // ② 绑定成功检测（1 秒轮询；绑定判定与之前一样用 uuid）
            if (now - entry.lastBindCheckAt >= BIND_CHECK_INTERVAL_MILLIS) {
                entry.lastBindCheckAt = now
                val manager = try {
                    QqBindManager.getInstance()
                } catch (_: Throwable) {
                    null
                }
                if (manager != null && manager.isBoundByUuid(entry.playerName, uuid)) {
                    unbindRestrict(player)
                    iterator.remove()
                    continue
                }
            }
            // ③ 锚点锁定（限制行走 / 跑步；传送类命令也被拉回）
            lockAnchor(player, entry)
            // ④ 聊天栏持续输出绑定码（5 秒一条；码过期自动换新）
            if (now - entry.lastAnnounceAt >= ANNOUNCE_INTERVAL_MILLIS) {
                entry.lastAnnounceAt = now
                val manager = try {
                    QqBindManager.getInstance()
                } catch (_: Throwable) {
                    null
                }
                if (manager != null) {
                    var code = entry.code
                    if (!manager.hasPendingCode(code)) {
                        code = try {
                            manager.generateCode(entry.playerName, uuid)
                        } catch (_: Throwable) {
                            code
                        }
                        entry.code = code
                    }
                    announceCode(player, entry.code, manager.codeExpireMinutes)
                }
            }
        }
    }

    // ---------- 子动作 ----------

    /** 位置偏移超阈值 → 传送回进服锚点（服务端主动传送，客户端立即同步）。 */
    private fun lockAnchor(player: ServerPlayer, entry: Entry) {
        try {
            val p = player.position()
            val dx = p.x - entry.anchorX
            val dz = p.z - entry.anchorZ
            if (dx * dx + dz * dz > ANCHOR_RADIUS * ANCHOR_RADIUS ||
                kotlin.math.abs(p.y - entry.anchorY) > 1.5
            ) {
                player.connection.teleport(entry.anchorX, entry.anchorY, entry.anchorZ, player.yRot, player.xRot)
            }
        } catch (_: Throwable) {
        }
    }

    /** 输出一条绑定码提示。 */
    private fun announceCode(player: ServerPlayer, code: String, expireMinutes: Int) {
        try {
            player.sendSystemMessage(
                Component.literal(
                    "§e你还未绑定 QQ：§a§l$code§e（${expireMinutes} 分钟内有效）\n" +
                        "§7到 QQ 群发送 §f/绑定 $code §7或直接发送这串数字即可完成绑定，绑定后立即解除限制",
                ),
            )
        } catch (_: Throwable) {
        }
    }

    /** 超时踢出。 */
    private fun kickTimeout(player: ServerPlayer) {
        try {
            player.connection.disconnect(
                Component.literal("§cQQ 绑定超时：未在限制时间内完成绑定。\n§7到 QQ 群发送绑定码完成绑定后再进入服务器。"),
            )
            QqBindManager.logQuiet("[Fabric绑定] 玩家=${player.gameProfile.name} 限制超时，已踢出")
        } catch (_: Throwable) {
        }
    }

    /** 绑定成功，解除全部限制。 */
    private fun unbindRestrict(player: ServerPlayer) {
        try {
            player.sendSystemMessage(Component.literal("§a✅ QQ 绑定成功，已解除全部限制，祝你游戏愉快！"))
            QqBindManager.logQuiet("[Fabric绑定] 玩家=${player.gameProfile.name} 绑定成功，已解除限制")
        } catch (_: Throwable) {
        }
    }

    /** 在线查找（session 引用避免持有玩家对象）。 */
    private fun currentOnlinePlayer(entry: Entry): ServerPlayer? {
        return try {
            FabricServerRef.server?.playerList?.getPlayerByName(entry.playerName)?.let { online ->
                if (online.uuid.toString() == entry.playerUuid) online else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 限制超时秒数（qq-bind.restrict-timeout-seconds；<10 按 120 处理）。 */
    private fun restrictTimeoutSeconds(): Int {
        val configured = try {
            KERONGFabric.instance?.restrictTimeoutSeconds()
        } catch (_: Throwable) {
            120
        } ?: 120
        return if (configured < 10) 120 else configured
    }
}

/**
 * brigadier 命令树 requirement 包装（限制未绑定玩家的「所有命令发送」）：
 * 把命令树每个节点的 requirement 替换为「原判定 AND 该来源玩家不受限」——
 * 受限玩家 parse 任何命令都会得到「未知或不完整的命令」（与 vanilla 对无
 * 权限命令的表现一致），tab 补全同样不再提示，等效于命令入口整体关闭。
 *
 * 实现为 Unsafe 反射替换 private final 字段（brigadier CommandNode 的
 * requirement 无公开写入口）；任何一步失败即整体停用（[failed] = true），
 * 事件层拦截（ALLOW_COMMAND_MESSAGE / 聊天 / 交互 / 锚点锁定）不受影响。
 * 命令树每次重建（启动 / 重载）都会重新经过 [ensureWrapped] 补包装，
 * 已包装节点（requirement 为 [GatePredicate] 实例）整棵跳过，天然幂等。
 */
object BindRestrictCommandGate {

    /** 包装能力初始化结果（null = 未初始化 / false = 反射失败已停用）。 */
    @Volatile
    private var usable: Boolean? = null

    /** 我们生成的包装判定（instanceof 检测已包装，防重复嵌套）。 */
    private class GatePredicate(
        private val original: Predicate<CommandSourceStack>?,
    ) : Predicate<CommandSourceStack> {
        override fun test(source: CommandSourceStack): Boolean {
            val base = original?.test(source) ?: true
            if (!base) return false
            val player = try {
                source.getPlayer()
            } catch (_: Throwable) {
                null
            } ?: return true // 控制台 / 命令方块等非玩家来源不受限
            return !BindRestrict.isRestricted(player)
        }
    }

    /** 确保整棵命令树已包装（幂等；失败停用后不再尝试）。 */
    fun ensureWrapped(root: CommandNode<CommandSourceStack>) {
        if (usable == false) return
        try {
            if (usable == null) {
                init()
            }
            wrapNode(root)
        } catch (_: Throwable) {
            usable = false
            QqBindManager.logFileOnly("[Fabric绑定] 命令树包装不可用（反射失败），命令拦截退化为事件层拦截（/me /say 与聊天），其余限制不受影响")
        }
    }

    /** 反射初始化：requirement 字段 offset（一次）。 */
    private fun init() {
        val field = CommandNode::class.java.getDeclaredField("requirement")
        REQUIREMENT_OFFSET = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null).let { unsafe ->
                (unsafe as sun.misc.Unsafe).objectFieldOffset(field)
            }
        UNSAFE = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        usable = true
    }

    private var UNSAFE: sun.misc.Unsafe? = null
    private var REQUIREMENT_OFFSET = 0L

    /** 递归包装单个节点（已包装的整棵跳过）。 */
    private fun wrapNode(node: CommandNode<CommandSourceStack>) {
        val current = node.requirement
        if (current is GatePredicate) return
        val replacement = GatePredicate(current)
        UNSAFE?.putObject(node, REQUIREMENT_OFFSET, replacement)
        for (child in node.children) {
            wrapNode(child)
        }
    }
}

/**
 * 事件层拦截入口（FabricEvents 注册的回调转发到这里，保持 FabricEvents 可读）。
 */
object BindRestrictInteractions {

    /** 聊天：受限玩家拦截（不广播 / 不转发 QQ 群）。返回 false = 取消本条消息。 */
    fun onChat(player: ServerPlayer?): Boolean {
        if (!BindRestrict.isRestricted(player)) return true
        try {
            player?.sendSystemMessage(
                Component.literal("§c你还未绑定 QQ，暂不能发送消息；绑定码已在聊天栏持续提示。"),
            )
        } catch (_: Throwable) {
        }
        return false
    }

    /** 广播型命令（/me /say）：受限玩家拦截。返回 false = 取消命令。 */
    fun onCommand(player: ServerPlayer?): Boolean {
        if (!BindRestrict.isRestricted(player)) return true
        try {
            player?.sendSystemMessage(
                Component.literal("§c你还未绑定 QQ，暂不能使用命令；绑定码已在聊天栏持续提示。"),
            )
        } catch (_: Throwable) {
        }
        return false
    }

    /** 方块交互（右键使用）：受限玩家 FAIL。 */
    fun onUseBlock(player: ServerPlayer?): InteractionResult =
        if (BindRestrict.isRestricted(player)) InteractionResult.FAIL else InteractionResult.PASS

    /** 方块交互（左键开始破坏）：受限玩家 FAIL。 */
    fun onAttackBlock(player: ServerPlayer?): InteractionResult =
        if (BindRestrict.isRestricted(player)) InteractionResult.FAIL else InteractionResult.PASS

    /** 生物交互（右键实体）：受限玩家 FAIL。 */
    fun onUseEntity(player: ServerPlayer?): InteractionResult =
        if (BindRestrict.isRestricted(player)) InteractionResult.FAIL else InteractionResult.PASS
}
