package cn.kerong.penguin.fabric

import cn.kerong.bot.MenuManager
import cn.kerong.bot.QClient
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

/**
 * 游戏内命令注册（等价 NeoForge 版 NeoForgeCommands / Spigot 版 plugin.yml 命令）：
 * - /hbkrg qbd：获取 QQ 绑定验证码（**所有玩家**，1.7.5.1 起 fa 版绑定不再
 *   拦截进服——玩家自愿绑定：游戏内取码 → QQ 群发 /绑定 <码>）；
 * - /hbkrg 管理子命令：reload / info / chs / mem / bpng / bpngsz / qr / help，
 *   各自要求权限等级 2（OP）——根节点不再整体锁 OP（qbd 要对玩家开放）；
 * - /qq：rebind（玩家自助解绑）与 qxqq（OP 强制解绑）。
 *
 * Fabric 与 NeoForge 同用 brigadier + CommandSourceStack（mojmap 名）。
 * 1.7.5.1 起 fa 版无绑定拦截，/qq skip / unskip（跳过绑定名单）随之移除。
 */
class FabricCommands(private val plugin: KERONGFabric) {

    /** 全量注册（CommandRegistrationCallback 回调里调用）。 */
    fun registerAll(dispatcher: CommandDispatcher<CommandSourceStack>) {
        registerKerong(dispatcher)
        registerQq(dispatcher)
    }

    // ---------- /hbkrg 主命令 ----------

    private fun registerKerong(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val label = "hbkrg"
        // 根节点不整体锁 OP（qbd 对所有玩家开放）；管理子命令各自 requires(2)
        val builder: LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(label)

        // /hbkrg qbd：获取 QQ 绑定验证码（所有玩家，无需 OP）
        builder.then(
            Commands.literal("qbd").executes { context ->
                handleQbd(context.source)
                1
            },
        )
        builder.then(
            Commands.literal("help").requires { it.hasPermission(2) }.executes { context ->
                sendHelp(context.source, label)
                1
            },
        )
        builder.then(
            Commands.literal("reload").requires { it.hasPermission(2) }.executes { context ->
                context.source.sendSuccess({ Component.literal("§e正在重载配置文件并重新同步 QQ 快捷面板（异步执行）…") }, false)
                val source = context.source
                plugin.submitAsync {
                    try {
                        plugin.reloadPluginConfig()
                        val panelReport = syncPanelReport()
                        plugin.submit {
                            source.sendSuccess({ Component.literal("§a已重载配置文件。") }, true)
                            source.sendSuccess(
                                {
                                    Component.literal(
                                        "§e注意: §f以下配置不能用 reload 生效，只能重启服务器：\n" +
                                            "  - bot.app-id / bot.secret（QQ 机器人凭据，仅启动时连接一次）\n" +
                                            "  - update-check 段（更新检查，仅启动后第 8 秒检查一次）"
                                    )
                                },
                                false,
                            )
                            for (line in panelReport) {
                                source.sendSuccess({ Component.literal(line) }, false)
                            }
                        }
                    } catch (t: Throwable) {
                        val message = t.message ?: t.toString()
                        plugin.submit { source.sendFailure(Component.literal("§c重载配置失败: $message")) }
                    }
                }
                1
            },
        )
        builder.then(
            Commands.literal("info").requires { it.hasPermission(2) }.executes { context ->
                context.source.sendSuccess(
                    { Component.literal("平台: ${plugin.platform}（测试版）  版本: ${plugin.pluginVersion}") },
                    false,
                )
                1
            },
        )
        builder.then(
            Commands.literal("chs").requires { it.hasPermission(2) }.executes { context ->
                val result = cn.kerong.bot.ReconnectManager.manualReconnect()
                context.source.sendSuccess({ Component.literal("§a$result") }, true)
                1
            },
        )
        builder.then(
            Commands.literal("mem").requires { it.hasPermission(2) }.executes { context ->
                context.source.sendSuccess({ Component.literal("§6===== KERONGPenguin 内存诊断 =====") }, false)
                for (lineText in cn.kerong.penguin.spigot.render.InfoCardAssets.cacheStats().split('\n')) {
                    context.source.sendSuccess({ Component.literal("§7$lineText") }, false)
                }
                context.source.sendSuccess(
                    { Component.literal("§e提示: §f已提交堆是 JVM 预留量，渲染高峰后扩张、不代表插件实际占用；若此处存活堆很小而面板内存很高，多查其他模组 / JVM 参数（-Xmx 与 GC 行为）") },
                    false,
                )
                1
            },
        )
        builder.then(
            Commands.literal("qr").requires { it.hasPermission(2) }.executes { context ->
                val configured = plugin.botAppId.isNotBlank() && plugin.botSecret.isNotBlank()
                val result = cn.kerong.bot.QrLoginManager.start(manual = true)
                context.source.sendSuccess({ Component.literal("§a$result") }, false)
                if (configured) {
                    context.source.sendSuccess(
                        { Component.literal("§c注意：当前已配置 QQ 机器人凭据，本次扫码成功后将替换 bot.app-id / bot.secret 并以新凭据重新连接。") },
                        false,
                    )
                }
                context.source.sendSuccess(
                    { Component.literal("§e请在服务器控制台查看二维码与扫码链接（也支持手动复制链接到手机 QQ 打开）。") },
                    false,
                )
                1
            },
        )
        builder.then(
            Commands.literal("bpng")
                .requires { it.hasPermission(2) }
                .executes { context ->
                    handleBpng(context.source, "")
                    1
                }
                .then(
                    Commands.literal("stop").executes { context ->
                        handleBpng(context.source, "stop")
                        1
                    },
                )
                .then(
                    Commands.argument("包ID", StringArgumentType.word()).executes { context ->
                        handleBpng(context.source, StringArgumentType.getString(context, "包ID"))
                        1
                    },
                ),
        )
        builder.then(
            Commands.literal("bpngsz")
                .requires { it.hasPermission(2) }
                .executes { context ->
                    handleBpngsz(context.source, "")
                    1
                }
                .then(
                    Commands.literal("yes").executes { context ->
                        handleBpngsz(context.source, "yes")
                        1
                    },
                )
                .then(
                    Commands.literal("no").executes { context ->
                        handleBpngsz(context.source, "no")
                        1
                    },
                ),
        )
        builder.executes { context ->
            sendHelp(context.source, label)
            1
        }
        dispatcher.register(builder)
    }

    private fun syncPanelReport(): List<String> {
        return try {
            val starter = QClient.starter
                ?: return listOf("§eQQ 快捷面板：QQ 客户端未启动，本次未同步。")
            val groups = plugin.groupOpenIdList()
            if (groups.isEmpty()) {
                return listOf("§eQQ 快捷面板：未配置 QQ 群（bot.groups 为空），本次未同步。")
            }
            val result = MenuManager.syncGroupPanelsWithResult(starter, groups)
            if (result.ok) {
                listOf(
                    "§aQQ 快捷面板已按新配置重新同步（面板 ${result.panelId}，${result.buttonCount} 个按钮，${result.groupCount} 个群）。",
                )
            } else {
                listOf(
                    "§c快捷面板同步失败：${result.error}§e（重启服务器会再自动同步一次；持续失败见控制台与 logs/qq 日志）",
                )
            }
        } catch (t: Throwable) {
            listOf("§cQQ 快捷面板同步异常: ${t.message}")
        }
    }

    // ---------- 背景图片包（与 Spigot / NeoForge 版同逻辑） ----------

    private fun handleBpng(source: CommandSourceStack, argRaw: String) {
        val service = cn.kerong.penguin.spigot.render.BackgroundPackService
        val arg = argRaw.trim().lowercase(java.util.Locale.ROOT)
        when {
            arg.isEmpty() -> {
                if (!service.isImageDownloadEnabled()) {
                    service.imageDownloadDisabledLines().forEach { source.sendSuccess({ Component.literal(it) }, false) }
                    return
                }
                source.sendSuccess({ Component.literal("§e正在获取图片包清单（异步）…") }, false)
                plugin.submitAsync {
                    val lines = service.listPacksLines()
                    plugin.submit { lines.forEach { source.sendSuccess({ Component.literal(it) }, false) } }
                }
            }
            arg == "stop" -> {
                if (service.requestCancel()) {
                    source.sendSuccess(
                        { Component.literal("§e已发送中断信号，当前下载（${service.activeDownloadBrief()}）将在本张图片结束后停止。") },
                        false,
                    )
                } else {
                    source.sendSuccess({ Component.literal("§e当前没有进行中的图片包下载。") }, false)
                }
            }
            else -> {
                if (!service.isImageDownloadEnabled()) {
                    service.imageDownloadDisabledLines().forEach { source.sendSuccess({ Component.literal(it) }, false) }
                    return
                }
                if (service.hasActiveDownload()) {
                    service.requestCancel()
                    source.sendSuccess(
                        { Component.literal("§e已发送中断信号，当前下载（${service.activeDownloadBrief()}）将在本张图片结束后停止。") },
                        false,
                    )
                    return
                }
                source.sendSuccess({ Component.literal("§e开始下载图片包 $arg（异步执行）…") }, false)
                plugin.submitAsync {
                    service.downloadPackById(plugin.dataFolder, arg) { line ->
                        plugin.submit { source.sendSuccess({ Component.literal(line) }, false) }
                    }
                }
            }
        }
    }

    private fun handleBpngsz(source: CommandSourceStack, argRaw: String) {
        val arg = argRaw.trim().lowercase(java.util.Locale.ROOT)
        if (arg != "yes" && arg != "no") {
            source.sendFailure(
                Component.literal("§c用法: /hbkrg bpngsz yes 开启 img/ 自定义背景；/hbkrg bpngsz no 恢复内置背景"),
            )
            return
        }
        val useCustom = arg == "yes"
        source.sendSuccess(
            {
                Component.literal(
                    "§e正在" +
                        (if (useCustom) "把全部卡片切换为 img/ 自定义背景" else "把全部卡片恢复为内置背景") +
                        "（写入配置并自动重载，异步执行）…",
                )
            },
            false,
        )
        plugin.submitAsync {
            val lines = cn.kerong.penguin.spigot.render.BackgroundPackService.switchBackgrounds(useCustom)
            plugin.submit { lines.forEach { source.sendSuccess({ Component.literal(it) }, false) } }
        }
    }

    // ---------- /hbkrg qbd：获取 QQ 绑定验证码（所有玩家） ----------

    /**
     * /hbkrg qbd —— 游戏内获取 QQ 绑定验证码（1.7.5.1 起 fa 版绑定不拦截
     * 进服：玩家自愿绑定，游戏内取码 → QQ 群发 /绑定 <码>）。
     */
    private fun handleQbd(source: CommandSourceStack) {
        val player = try {
            source.getPlayerOrException()
        } catch (_: Throwable) {
            source.sendFailure(Component.literal("§c此命令只能由玩家执行（控制台没有 QQ 绑定）。"))
            return
        }
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            source.sendFailure(Component.literal("§c管理器未就绪，请稍后再试。"))
            return
        }
        if (!manager.isEnabled) {
            source.sendFailure(Component.literal("§cQQ 绑定功能未开启。"))
            return
        }
        val playerName = player.gameProfile.name
        val playerUuid = player.uuid.toString()
        if (manager.isBoundByUuid(playerName, playerUuid)) {
            source.sendSuccess({ Component.literal("§a✅ 你已绑定 QQ，无需获取验证码。") }, false)
            return
        }
        val code = manager.generateCode(playerName, playerUuid)
        QqBindManager.logQuiet("[MC命令] /hbkrg qbd 玩家=$playerName 获取绑定码")
        source.sendSuccess({ Component.literal("§e你的 QQ 绑定验证码：§a§l$code") }, false)
        source.sendSuccess(
            { Component.literal("§f到 QQ 群发送 §b/绑定 $code §f完成绑定（§7${manager.codeExpireMinutes} 分钟内有效§f）。") },
            false,
        )
    }

    private fun sendHelp(source: CommandSourceStack, label: String) {
        fun line(text: String) = source.sendSuccess({ Component.literal(text) }, false)
        line("§6===== /$label 命令帮助 =====")
        line("§e/$label qbd§f - 获取 QQ 绑定验证码（所有玩家可用）")
        if (!source.hasPermission(2)) {
            line("§7其余子命令需要管理员（OP）权限。")
            return
        }
        line("§e/$label help§f - 查看此帮助")
        line("§e/$label reload§f - 重载配置并重新同步 QQ 快捷面板（QQ 凭据与更新检查除外，需重启）")
        line("§e/$label info§f - 查看适配器信息")
        line("§e/$label chs§f - 立即重试连接 QQ 机器人（断线 / 重连超时后用）")
        line("§e/$label mem§f - 查看模组内存与缓存诊断（JVM 堆 + 各级缓存）")
        line("§e/$label bpng§f - 查看可下载的背景图片包；/$label bpng <包ID> 下载到 img/（下载中可再执行一次中断）")
        line("§e/$label bpngsz yes|no§f - 一键切换全部卡片是否使用 img/ 自定义背景图")
        line("§e/$label qr§f - 未配置凭据时发起 QQ 机器人扫码登录（控制台扫码，授权后自动连接）")
    }

    // ---------- /qq ----------

    private fun registerQq(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val playerNode = Commands.literal("qq")
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
        // 1.7.5.1 起 fa 版无绑定拦截：skip / unskip（跳过绑定名单）随之移除，
        // 绑定管理保留 rebind（自助解绑）与 qxqq（OP 强制解绑）
        playerNode.executes { context ->
            sendQqHelp(context.source, "qq")
            1
        }
        dispatcher.register(playerNode)
    }

    private fun handleSelfRebind(source: CommandSourceStack): Int {
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
            source.sendSuccess({ Component.literal("§a✅ 已解除你的 QQ 绑定。重新绑定请发送 /hbkrg qbd 获取新验证码。") }, false)
            1
        } else {
            source.sendFailure(Component.literal("§c❌ 你没有绑定记录。"))
            0
        }
    }

    private fun handleForceRebind(source: CommandSourceStack, playerName: String): Int {
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
                { Component.literal("§a✅ 已解除玩家 $playerName 的 QQ 绑定。该玩家可用 /hbkrg qbd 获取新验证码。") },
                true,
            )
            1
        } else {
            source.sendFailure(Component.literal("§c❌ 玩家 $playerName 未找到绑定记录。"))
            0
        }
    }

    private fun sendQqHelp(source: CommandSourceStack, label: String) {
        fun line(text: String) = source.sendSuccess({ Component.literal(text) }, false)
        line("§6===== /$label QQ 绑定管理命令 =====")
        line("§e/$label help§f - 查看此帮助（所有人可用）")
        line("§e/$label rebind§f - 解除自己的 QQ 绑定（玩家自助，无需管理员）")
        line("§7获取绑定验证码请用 /hbkrg qbd（游戏内发送即可）")
        line("§7以下命令需要管理员（OP）权限：")
        line("§e/$label qxqq <玩家名>§f - 强制解除玩家的 QQ 绑定")
    }
}
