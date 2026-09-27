package cn.huohuas001.huhobotPenguin.neoforge

import cn.huohuas001.bot.MenuManager
import cn.huohuas001.bot.QClient
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent

/**
 * 游戏内命令注册（等价 Spigot 版 plugin.yml 命令 + HuHoBotCommand / QqCommand）：
 * - /huhobot（别名 /hb）：reload / info / panel / help，需要权限等级 2（OP）；
 * - /qq：rebind（玩家自助解绑）与 qxqq / skip / unskip（OP 管理）。
 */
class NeoForgeCommands(private val plugin: HuHoBotNeoForge) {

    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        registerHuHoBot(event)
        registerQq(event)
    }

    // ---------- /huhobot ----------

    private fun registerHuHoBot(event: RegisterCommandsEvent) {
        val builder = Commands.literal("huhobot").requires { it.hasPermission(2) }

        builder.then(
            Commands.literal("help").executes { context ->
                sendHelp(context.source, "huhobot")
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
        builder.executes { context ->
            sendHelp(context.source, "huhobot")
            1
        }
        event.dispatcher.register(builder)

        // 别名 /hb
        val alias = Commands.literal("hb").requires { it.hasPermission(2) }
            .then(Commands.literal("help").executes { context ->
                sendHelp(context.source, "hb"); 1
            })
            .then(Commands.literal("reload").executes { context ->
                plugin.reloadPluginConfig()
                context.source.sendSuccess({ Component.literal("§6已重载配置文件。") }, true)
                1
            })
            .then(Commands.literal("info").executes { context ->
                context.source.sendSuccess(
                    { Component.literal("平台: ${plugin.platform}  版本: ${plugin.pluginVersion}") },
                    false,
                )
                1
            })
            .then(Commands.literal("panel").executes { context -> resyncPanel(context.source) })
            .executes { context -> sendHelp(context.source, "hb"); 1 }
        event.dispatcher.register(alias)
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
