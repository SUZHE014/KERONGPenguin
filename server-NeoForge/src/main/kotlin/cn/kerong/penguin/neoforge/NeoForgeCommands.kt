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
import java.util.Locale

/**
 * 游戏内命令注册（等价 Spigot 版 plugin.yml 命令 + KERONGCommand / QqCommand）：
 * - /hbkrg：reload / info / chs / bpng / bpngsz / qr / help，需要权限等级 2（OP）；
 *   1.6.2 新增 chs：机器人重连超时挂起或断线期间手动重试连接；
 * - /qq：rebind（玩家自助解绑）与 qxqq / skip / unskip（OP 管理）。
 *
 * 1.6.7.7：移除短别名 /hb（用户需求），主命令唯一；旧输入由原版
 * “未知指令”路径处理。
 *
 * 1.7.0：主命令更名为 /hbkrg（旧名直接移除不兼容）；
 * panel 子命令移除——reload 在重载配置后一并重新同步 QQ 快捷面板
 * （先删后建，按新配置重建按钮）。
 *
 * 1.6.9.4：reload 子命令异步执行（配置读写 / 模板释放全是磁盘
 * IO）+ 反馈里写明重载边界（QQ 凭据与更新检查需重启）。
 */
class NeoForgeCommands(private val plugin: KERONGNeoForge) {

    /** 主命令注册名（/hbkrg；旧名已移除不兼容）。 */
    private val MAIN_COMMAND_LABELS = listOf("hbkrg")

    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        registerKerong(event)
        registerQq(event)
    }

    // ---------- /hbkrg 主命令 ----------

    private fun registerKerong(event: RegisterCommandsEvent) {
        // 注册主命令 /hbkrg（同一套子命令）
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
                // 1.6.9.4：重载移入异步线程（配置读写 / 模板释放 / 缺失键
                // 补齐保存全部是磁盘 IO，不再占用服务器线程），完成后回
                // 服务器线程反馈结果与重载边界说明；
                // 1.6.9.5：重载完成后顺带重新同步 QQ 快捷面板（先删后建，
                // 原 panel 子命令已移除）
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
            Commands.literal("info").executes { context ->
                context.source.sendSuccess(
                    { Component.literal("平台: ${plugin.platform}  版本: ${plugin.pluginVersion}") },
                    false,
                )
                1
            },
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
        // 1.7.4：控制台重显 QQ 机器人配置引导二维码（官网教程页；
        // 未配置凭据时启动也会自动打印一次）
        builder.then(
            Commands.literal("qr").executes { context ->
                plugin.printQqSetupQr()
                1
            },
        )
        // 1.7.1：背景图片包（无参列表 / stop 中断 / 包 ID 下载）
        builder.then(
            Commands.literal("bpng")
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
        // 1.7.1：一键切换全部卡片背景（yes = img/ 自定义；no = 内置）
        builder.then(
            Commands.literal("bpngsz")
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
        return builder
    }

    /**
     * 重新同步 QQ 快捷指令面板（1.6.9.5：reload 的一部分，原 panel 子命令
     * 已移除）。步骤与启动同步完全一致（MenuManager.syncGroupPanelsWithResult
     * 先删后建）：① 列出已有面板 → ② 删除全部（含非本插件残留）→
     * ③ 按当前配置重建按钮（遇 30013 自动退避后清空重建）。
     *
     * 必须在异步线程调用（内部有网络 IO 与退避等待，最坏 20 秒级）；
     * 返回的反馈行由调用方回服务器线程发送。
     */
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

    // ---------- 背景图片包（1.7.1，与 Spigot 版同逻辑） ----------

    /**
     * /hbkrg bpng [包ID|stop]：无参列出图片包；带包 ID 下载（再次执行同一
     * 命令或 stop 可中断）；进度行转回服务器主线程发送。
     *
     * 1.7.2：image-download.enabled 关闭时，列表与下载入口直接回复
     * 「管理员已关闭图片下载功能」并中止（bpng stop 不拦截）。
     */
    private fun handleBpng(source: net.minecraft.commands.CommandSourceStack, argRaw: String) {
        val service = cn.kerong.penguin.spigot.render.BackgroundPackService
        val arg = argRaw.trim().lowercase(Locale.ROOT)
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
                // 下载进行中：任何再次执行的下载命令都先中断当前任务（下载过程可执行指令中断）
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

    /** /hbkrg bpngsz yes|no：一键切换全部卡片背景（写入配置后自动重载）。 */
    private fun handleBpngsz(source: net.minecraft.commands.CommandSourceStack, argRaw: String) {
        val arg = argRaw.trim().lowercase(Locale.ROOT)
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

    private fun sendHelp(source: net.minecraft.commands.CommandSourceStack, label: String) {
        fun line(text: String) = source.sendSuccess({ Component.literal(text) }, false)
        line("§6===== /$label 命令帮助 =====")
        line("§e/$label help§f - 查看此帮助")
        line("§e/$label reload§f - 重载配置并重新同步 QQ 快捷面板（QQ 凭据与更新检查除外，需重启）")
        line("§e/$label info§f - 查看适配器信息")
        line("§e/$label chs§f - 立即重试连接 QQ 机器人（断线 / 重连超时后用）")
        line("§e/$label mem§f - 查看插件内存与缓存诊断（JVM 堆 + 各级缓存）")
        line("§e/$label bpng§f - 查看可下载的背景图片包；/$label bpng <包ID> 下载到 img/（下载中可再执行一次中断）")
        line("§e/$label bpngsz yes|no§f - 一键切换全部卡片是否使用 img/ 自定义背景图")
        line("§e/$label qr§f - 控制台重新打印 QQ 机器人配置引导二维码（扫码打开官网教程）")
    }

    // ---------- 更名说明（/hbkrg） ----------

    // 内部自定义命令协议串「kerong run|adminrun …」不受更名影响
    // （CustomCommandRegistry 在派发前拦截，不会进入命令注册表）。

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
