package cn.kerong.penguin.neoforge

import cn.kerong.bot.MenuManager
import cn.kerong.bot.QClient
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * 游戏内命令注册（等价 Spigot 版 plugin.yml 命令 + KERONGCommand / QqCommand）：
 * - /kerong：reload / info / panel / chs / help，需要权限等级 2（OP）；
 *   1.6.2 新增 chs：机器人重连超时挂起或断线期间手动重试连接；
 * - /qq：rebind（玩家自助解绑）与 qxqq / skip / unskip（OP 管理）。
 *
 * 1.6.7.7：移除短别名 /hb（用户需求），主命令唯一 /kerong；旧 /hb 输入
 * 由原版“未知指令”路径处理。
 */
class NeoForgeCommands(private val plugin: KERONGNeoForge) {

    /** 主命令注册名（1.6.7.7 起仅 /kerong，/hb 别名已移除）。 */
    private val MAIN_COMMAND_LABELS = listOf("kerong")

    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        registerKerong(event)
        registerQq(event)
    }

    // ---------- /kerong ----------

    private fun registerKerong(event: RegisterCommandsEvent) {
        // 注册主命令 /kerong（同一套子命令）
        for (label in MAIN_COMMAND_LABELS) {
            event.dispatcher.register(buildMainCommand(label))
        }
    }

    /** 构造主命令节点（label = kerong，子命令完全一致）。 */
    private fun buildMainCommand(label: String): LiteralArgumentBuilder<CommandSourceStack> {
        val builder = Commands.literal(label).requires { it.hasPermission(2) }

        builder.then(
            Commands.literal("help").executes { context ->
                sendHelp(context.source, label)
                1
            },
        )
        builder.then(
            Commands.literal("reload").executes { context ->
                plugin.reloadPluginConfig()
                context.source.sendSuccess({ Component.literal("§6已重载配置文件。") }, true)
                1
            },
        )
        builder.then(
            Commands.literal("info").executes { context ->
                context.source.sendSuccess(
                    { Component.literal("平台: ${plugin.platform}  版本: ${plugin.pluginVersion}") },
                    false,
                )
                1
            },
        )
        builder.then(
            Commands.literal("panel").executes { context -> resyncPanel(context.source) },
        )
        builder.then(
            Commands.literal("chs").executes { context ->
                // 1.6.2：手动重连（重连超时挂起 / 断线期间随时可用）
                val result = cn.kerong.bot.ReconnectManager.manualReconnect()
                context.source.sendSuccess({ Component.literal("§a$result") }, true)
                1
            },
        )
        builder.then(
            Commands.literal("mem").executes { context ->
                // 1.6.6.2 覆盖版：内存诊断（JVM 堆存活/已提交/上限 + 插件各级缓存条目）
                context.source.sendSuccess({ Component.literal("§6===== KERONGPenguin 内存诊断 =====") }, false)
                for (lineText in cn.kerong.penguin.spigot.render.InfoCardAssets.cacheStats().split('\n')) {
                    context.source.sendSuccess({ Component.literal("§7$lineText") }, false)
                }
                context.source.sendSuccess(
                    { Component.literal("§e提示: §f已提交堆是 JVM 预留量，渲染高峰后扩张、不代表插件实际占用；若此处存活堆很小而面板内存很高，多查其他插件 / JVM 参数（-Xmx 与 GC 行为）") },
                    false,
                )
                1
            },
        )
        builder.executes { context ->
            sendHelp(context.source, label)
            1
        }
        return builder
    }

    private fun resyncPanel(source: net.minecraft.commands.CommandSourceStack): Int {
        try {
            val starter = QClient.starter
            if (starter == null) {
                source.sendFailure(Component.literal("§cQQ 客户端未启动。"))
                return 0
            }
            val groups = plugin.groupOpenIdList()
            if (groups.isEmpty()) {
                source.sendFailure(Component.literal("§c未配置 QQ 群。"))
                return 0
            }
            MenuManager.syncGroupPanels(starter, groups)
            source.sendSuccess({ Component.literal("§a已重新同步 QQ 快捷指令面板。") }, true)
        } catch (t: Throwable) {
            source.sendFailure(Component.literal("§c面板同步失败: ${t.message}"))
            return 0
        }
        return 1
    }

    private fun sendHelp(source: net.minecraft.commands.CommandSourceStack, label: String) {
        fun line(text: String) = source.sendSuccess({ Component.literal(text) }, false)
        line("§6===== /$label 命令帮助 =====")
        line("§e/$label help§f - 查看此帮助")
        line("§e/$label reload§f - 重载配置文件")
        line("§e/$label info§f - 查看适配器信息")
        line("§e/$label panel§f - 重新同步 QQ 快捷指令面板")
        line("§e/$label chs§f - 立即重试连接 QQ 机器人（断线 / 重连超时后用）")
        line("§e/$label mem§f - 查看插件内存与缓存诊断（JVM 堆 + 各级缓存）")
    }

    // ---------- /qq ----------

    private fun registerQq(event: RegisterCommandsEvent) {
        val playerNode = Commands.literal("qq")
            // help / rebind 所有玩家可用；管理子命令各自 requires
            .then(Commands.literal("help").executes { context ->
                sendQqHelp(context.source, "qq")
                1
            })
            .then(Commands.literal("rebind").executes { context ->
                handleSelfRebind(context.source)
                1
            })

        playerNode.then(
            Commands.literal("qxqq")
                .requires { it.hasPermission(2) }
                .then(
                    Commands.argument("玩家名", StringArgumentType.word()).executes { context ->
                        handleForceRebind(context.source, StringArgumentType.getString(context, "玩家名"))
                        1
                    },
                ),
        )
        playerNode.then(
            Commands.literal("skip")
                .requires { it.hasPermission(2) }
                .then(
                    Commands.argument("玩家名", StringArgumentType.word()).executes { context ->
                        handleSkip(context.source, StringArgumentType.getString(context, "玩家名"), true)
                        1
                    },
                ),
        )
        playerNode.then(
            Commands.literal("unskip")
                .requires { it.hasPermission(2) }
                .then(
                    Commands.argument("玩家名", StringArgumentType.word()).executes { context ->
                        handleSkip(context.source, StringArgumentType.getString(context, "玩家名"), false)
                        1
                    },
                ),
        )
        playerNode.executes { context ->
            sendQqHelp(context.source, "qq")
            1
        }
        event.dispatcher.register(playerNode)
    }

    /** 玩家自助解除绑定（等价 QqCommand.handleSelfRebind）。 */
    private fun handleSelfRebind(source: net.minecraft.commands.CommandSourceStack): Int {
        val player = try {
            source.getPlayerOrException()
        } catch (_: Throwable) {
            source.sendFailure(Component.literal("§c此命令只能由玩家执行。管理员请使用 /qq qxqq <玩家名>"))
            return 0
        }
        val playerName = player.gameProfile.name
        val playerUuid = player.uuid.toString()
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            source.sendFailure(Component.literal("§c管理器未就绪"))
            return 0
        }
        val success = manager.unbindByUuid(playerName, playerUuid)
        return if (success) {
            QqBindManager.logQuiet("[MC命令] /qq rebind 玩家=$playerName 自行解除绑定")
            source.sendSuccess({ Component.literal("§a✅ 已解除你的 QQ 绑定。你下次登录将重新获取绑定码。") }, false)
            1
        } else {
            source.sendFailure(Component.literal("§c❌ 你没有绑定记录。"))
            0
        }
    }

    /** 管理员强制玩家重新绑定。 */
    private fun handleForceRebind(source: net.minecraft.commands.CommandSourceStack, playerName: String): Int {
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            source.sendFailure(Component.literal("§c管理器未就绪"))
            return 0
        }
        val quuid = manager.findQuuidByPlayerName(playerName)
        val success = if (!quuid.isNullOrEmpty()) {
            manager.unbindByQuuid(quuid, playerName)
        } else {
            manager.unbind(playerName)
        }
        return if (success) {
            QqBindManager.logQuiet("[MC命令] /qq qxqq $playerName 成功")
            source.sendSuccess(
                { Component.literal("§a✅ 已解除玩家 $playerName 的 QQ 绑定。该玩家下次登录将重新获取绑定码。") },
                true,
            )
            1
        } else {
            source.sendFailure(Component.literal("§c❌ 玩家 $playerName 未找到绑定记录。"))
            0
        }
    }

    /** 设置 / 取消跳过绑定验证。 */
    private fun handleSkip(source: net.minecraft.commands.CommandSourceStack, playerName: String, skip: Boolean): Int {
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            source.sendFailure(Component.literal("§c管理器未就绪"))
            return 0
        }
        manager.setSkipped(playerName, skip)
        QqBindManager.logQuiet("[MC命令] /qq ${if (skip) "skip" else "unskip"} $playerName")
        source.sendSuccess(
            {
                Component.literal(
                    if (skip) "§a✅ 玩家 $playerName 已加入跳过绑定列表。"
                    else "§a✅ 玩家 $playerName 已移出跳过绑定列表。",
                )
            },
            true,
        )
        return 1
    }

    private fun sendQqHelp(source: net.minecraft.commands.CommandSourceStack, label: String) {
        fun line(text: String) = source.sendSuccess({ Component.literal(text) }, false)
        line("§6===== /$label QQ 绑定管理命令 =====")
        line("§e/$label help§f - 查看此帮助（所有人可用）")
        line("§e/$label rebind§f - 重新绑定自己的 QQ（玩家自助，无需管理员）")
        line("§7以下命令需要管理员（OP）权限：")
        line("§e/$label qxqq <玩家名>§f - 强制让玩家重新绑定 QQ")
        line("§e/$label skip <玩家名>§f - 强制让玩家跳过 QQ 绑定验证")
        line("§e/$label unskip <玩家名>§f - 取消跳过绑定")
    }
}
