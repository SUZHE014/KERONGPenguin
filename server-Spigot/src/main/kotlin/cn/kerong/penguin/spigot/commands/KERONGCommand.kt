package cn.kerong.penguin.spigot.commands

import cn.kerong.bot.MenuManager
import cn.kerong.bot.QClient
import cn.kerong.penguin.spigot.KERONGSpigot
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import java.util.Locale

/**
 * /hbkrg 主命令：reload / info / chs / mem / bpng / bpngsz / qr / help。
 *
 * 1.7.4 新增 qr：凭据未配置时发起/重显 QQ 官方扫码登录；凭据已配置时仅
 * 提示状态（旧版「官网教程二维码 + 教程链接」输出已按用户指令删除）。
 *
 * 注：QQ 群绑定已改为全自动（bot.groups 未填写时群互动消息自动写入白名单
 * 并重载，见 [cn.kerong.bot.events.AutoGroupBinder]），不再需要控制台命令。
 *
 * 1.7.1 新增 bpng / bpngsz：背景图片包下载与一键切换（共享逻辑在
 * [cn.kerong.penguin.spigot.render.BackgroundPackService]，双端命令层仅做
 * 线程调度与消息发送）。
 *
 * 1.7.0：主命令更名为 /hbkrg（旧名直接移除、不做兼容——
 * 输入 /kerong 由原版「未知指令」路径处理）。
 *
 * 1.6.9.5：panel 子命令移除——reload 在重载配置后一并重新同步 QQ
 * 快捷指令面板（先删后建，按新配置重建按钮），不再需要单独命令。
 *
 * 1.6.2 新增 chs 子命令：机器人重连超时挂起或断线期间，手动重置重连计数
 * 并立即重新尝试连接（等价于 30 分钟退避结束后的自动重试，任何时候可用）。
 *
 * 1.6.6.2 覆盖版新增 mem 子命令：内存诊断（JVM 堆存活 / 已提交 / 上限 +
 * 插件各级缓存条目），用于排查「面板显示内存高」类问题——已提交堆是
 * JVM 预留量而非插件实际存活占用。
 *
 * 1.6.9.4 reload 子命令改异步执行（配置文件读写 + 模板释放 + 缺失键补齐
 * 保存全是磁盘 IO）；反馈里明确写出重载边界——bot.app-id / bot.secret
 * （QQ 凭据）与 update-check（更新检查）仅启动时读一次，改动后必须重启。
 */
class KERONGCommand(private val plugin: KERONGSpigot) : TabExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = if (args.isNotEmpty()) args[0].lowercase(Locale.ROOT) else "help"
        when (sub) {
            "reload" -> {
                // 1.6.9.4：重载移入异步线程（配置读写 / 模板释放 / 缺失键
                // 补齐保存全部是磁盘 IO，不再占用主线程），完成后反馈结果；
                // 1.6.9.5：重载完成后顺带重新同步 QQ 快捷面板（先删后建）
                sender.sendMessage("${ChatColor.YELLOW}正在重载配置文件并重新同步 QQ 快捷面板（异步执行）…")
                plugin.submitAsync {
                    try {
                        plugin.reloadPluginConfig()
                        val panelReport = syncPanelReport()
                        plugin.submit {
                            sender.sendMessage("${ChatColor.GREEN}已重载配置文件。")
                            sender.sendMessage(
                                "${ChatColor.YELLOW}注意: ${ChatColor.WHITE}以下配置不能用 reload 生效，只能重启服务器：\n" +
                                    "  - bot.app-id / bot.secret（QQ 机器人凭据，仅启动时连接一次）\n" +
                                    "  - update-check 段（更新检查，仅启动后第 8 秒检查一次）"
                            )
                            for (line in panelReport) {
                                sender.sendMessage(line)
                            }
                        }
                    } catch (t: Throwable) {
                        plugin.submit { sender.sendMessage("${ChatColor.RED}重载配置失败: ${t.message}") }
                    }
                }
                return true
            }
            "info" -> {
                sender.sendMessage("平台: ${plugin.platform}\n版本: ${plugin.pluginVersion}")
                return true
            }
            "chs" -> {
                // 1.6.2：手动重连（重连超时挂起 / 断线期间随时可用）
                val result = cn.kerong.bot.ReconnectManager.manualReconnect()
                sender.sendMessage("${ChatColor.GREEN}$result")
                return true
            }
            "mem" -> {
                // 1.6.6.2 覆盖版：内存诊断（JVM 堆存活/已提交/上限 + 插件各级缓存条目）
                sender.sendMessage("${ChatColor.GOLD}===== KERONGPenguin 内存诊断 =====")
                for (line in cn.kerong.penguin.spigot.render.InfoCardAssets.cacheStats().split('\n')) {
                    sender.sendMessage("${ChatColor.GRAY}$line")
                }
                sender.sendMessage("${ChatColor.YELLOW}提示: ${ChatColor.WHITE}已提交堆是 JVM 预留量，渲染高峰后扩张、不代表插件实际占用；若此处存活堆很小而面板内存很高，多查其他插件 / JVM 参数（-Xmx 与 GC 行为）")
                return true
            }
            "bpng" -> {
                handleBpng(sender, args)
                return true
            }
            "bpngsz" -> {
                handleBpngsz(sender, args)
                return true
            }
            "qr" -> {
                // 1.7.4 覆盖版：凭据未配置 → 发起/重显 QQ 官方扫码登录（授权后
                // 凭据自动写入并连接；二维码过期自动刷新重发）；已配置 → 纯
                // 文本状态提示（1.7.4 第三轮覆盖版：旧版「教程二维码 + 教程
                // 链接」输出已按用户指令删除）
                if (plugin.botAppId.isBlank() || plugin.botSecret.isBlank()) {
                    val result = cn.kerong.bot.QrLoginManager.start()
                    sender.sendMessage("${ChatColor.GREEN}$result")
                    sender.sendMessage("${ChatColor.YELLOW}请在服务器控制台查看二维码与扫码链接（也支持手动复制链接到手机 QQ 打开）。")
                } else {
                    sender.sendMessage("${ChatColor.YELLOW}QQ 机器人凭据已配置（正常连接中）。重新扫码绑定请先清空 bot.app-id / bot.secret 并重启服务器。")
                }
                return true
            }
        }
        sendHelp(sender, label)
        return true
    }

    /**
     * 重新同步 QQ 快捷指令面板（1.6.9.5：reload 的一部分，原 panel 子命令已移除）。
     *
     * 步骤与启动同步完全一致（[MenuManager.syncGroupPanelsWithResult]）：
     * ① 列出平台上已有面板 → ② 删除全部（含非本插件残留）→ ③ 按当前
     * 配置（含刚刚 reload 的新配置）校验长度、裁剪到平台上限后创建新面板
     * （遇 30013 数量超限自动退避后清空重建）。
     *
     * 必须在异步线程调用（内部有网络 IO 与退避等待，最坏 20 秒级）；
     * 返回的反馈行由调用方回到主线程发送。
     *
     * @return 反馈行列表（每行已带颜色码；空列表 = 无需提示）
     */
    private fun syncPanelReport(): List<String> {
        return try {
            val starter = QClient.starter
                ?: return listOf("${ChatColor.YELLOW}QQ 快捷面板：QQ 客户端未启动，本次未同步。")
            val groups = plugin.groupOpenIdList()
            if (groups.isEmpty()) {
                return listOf("${ChatColor.YELLOW}QQ 快捷面板：未配置 QQ 群（bot.groups 为空），本次未同步。")
            }
            val result = MenuManager.syncGroupPanelsWithResult(starter, groups)
            if (result.ok) {
                listOf(
                    "${ChatColor.GREEN}QQ 快捷面板已按新配置重新同步" +
                        "（面板 ${result.panelId}，${result.buttonCount} 个按钮，${result.groupCount} 个群）。",
                )
            } else {
                listOf(
                    "${ChatColor.RED}QQ 快捷面板同步失败：${result.error}" +
                        "${ChatColor.YELLOW}（重启服务器会再自动同步一次；持续失败见控制台与 logs/qq 日志）",
                )
            }
        } catch (t: Throwable) {
            listOf("${ChatColor.RED}QQ 快捷面板同步异常: ${t.message}")
        }
    }

    // ---------- 背景图片包（1.7.1） ----------

    /**
     * /hbkrg bpng [包ID|stop]：无参列出图片包；带包 ID 下载（再次执行同一
     * 命令或 stop 可中断）；下载中的进度行经主线程发回命令发送者。
     *
     * 1.7.2：image-download.enabled 关闭时，列表与下载入口直接回复
     * 「管理员已关闭图片下载功能」并中止（bpng stop 不拦截——下载开关
     * 关闭前已开始的下载仍可中断）。
     */
    private fun handleBpng(sender: CommandSender, args: Array<out String>) {
        val service = cn.kerong.penguin.spigot.render.BackgroundPackService
        val arg = args.drop(1).firstOrNull()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        when {
            arg.isEmpty() -> {
                if (!service.isImageDownloadEnabled()) {
                    service.imageDownloadDisabledLines().forEach { sender.sendMessage(it) }
                    return
                }
                sender.sendMessage("${ChatColor.YELLOW}正在获取图片包清单（异步）…")
                plugin.submitAsync {
                    val lines = service.listPacksLines()
                    plugin.submit { lines.forEach { sender.sendMessage(it) } }
                }
            }
            arg == "stop" -> {
                if (service.requestCancel()) {
                    sender.sendMessage("${ChatColor.YELLOW}已发送中断信号，当前下载（${service.activeDownloadBrief()}）将在本张图片结束后停止。")
                } else {
                    sender.sendMessage("${ChatColor.YELLOW}当前没有进行中的图片包下载。")
                }
            }
            else -> {
                if (!service.isImageDownloadEnabled()) {
                    service.imageDownloadDisabledLines().forEach { sender.sendMessage(it) }
                    return
                }
                // 下载进行中：任何再次执行的下载命令都先中断当前任务（用户语义：下载过程可执行指令中断）
                if (service.hasActiveDownload()) {
                    service.requestCancel()
                    sender.sendMessage("${ChatColor.YELLOW}已发送中断信号，当前下载（${service.activeDownloadBrief()}）将在本张图片结束后停止。")
                    return
                }
                sender.sendMessage("${ChatColor.YELLOW}开始下载图片包 $arg（异步执行）…")
                plugin.submitAsync {
                    service.downloadPackById(plugin.dataFolder, arg) { line ->
                        plugin.submit { sender.sendMessage(line) }
                    }
                }
            }
        }
    }

    /**
     * /hbkrg bpngsz yes|no：一键切换全部卡片背景（写入配置后自动重载）。
     */
    private fun handleBpngsz(sender: CommandSender, args: Array<out String>) {
        val arg = args.drop(1).firstOrNull()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        when (arg) {
            "yes", "no" -> {
                val useCustom = arg == "yes"
                sender.sendMessage(
                    "${ChatColor.YELLOW}正在" +
                        (if (useCustom) "把全部卡片切换为 img/ 自定义背景" else "把全部卡片恢复为内置背景") +
                        "（写入配置并自动重载，异步执行）…",
                )
                plugin.submitAsync {
                    val lines = cn.kerong.penguin.spigot.render.BackgroundPackService.switchBackgrounds(useCustom)
                    plugin.submit { lines.forEach { sender.sendMessage(it) } }
                }
            }
            else -> {
                sender.sendMessage("${ChatColor.RED}用法: /hbkrg bpngsz yes 开启 img/ 自定义背景；/hbkrg bpngsz no 恢复内置背景")
            }
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase(Locale.ROOT)
            return SUBCOMMANDS.filter { it.startsWith(prefix) }
        }
        if (args.size == 2) {
            val prefix = args[1].lowercase(Locale.ROOT)
            val suggestions = when (args[0].lowercase(Locale.ROOT)) {
                "bpng" -> listOf("stop")
                "bpngsz" -> listOf("yes", "no")
                else -> emptyList()
            }
            return suggestions.filter { it.startsWith(prefix) }
        }
        return emptyList()
    }

    /** 发送命令帮助。 */
    private fun sendHelp(sender: CommandSender, label: String) {
        sender.sendMessage("${ChatColor.GOLD}===== /$label 命令帮助 =====")
        sender.sendMessage("${ChatColor.YELLOW}/$label help${ChatColor.WHITE} - 查看此帮助")
        sender.sendMessage("${ChatColor.YELLOW}/$label reload${ChatColor.WHITE} - 重载配置并重新同步 QQ 快捷面板（QQ 凭据与更新检查除外，需重启）")
        sender.sendMessage("${ChatColor.YELLOW}/$label info${ChatColor.WHITE} - 查看适配器信息")
        sender.sendMessage("${ChatColor.YELLOW}/$label chs${ChatColor.WHITE} - 立即重试连接 QQ 机器人（断线 / 重连超时后用）")
        sender.sendMessage("${ChatColor.YELLOW}/$label mem${ChatColor.WHITE} - 查看插件内存与缓存诊断（JVM 堆 + 各级缓存）")
        sender.sendMessage("${ChatColor.YELLOW}/$label bpng${ChatColor.WHITE} - 查看可下载的背景图片包；/$label bpng <包ID> 下载到 img/（下载中可再执行一次中断）")
        sender.sendMessage("${ChatColor.YELLOW}/$label bpngsz yes|no${ChatColor.WHITE} - 一键切换全部卡片是否使用 img/ 自定义背景图")
        sender.sendMessage("${ChatColor.YELLOW}/$label qr${ChatColor.WHITE} - 未配置凭据时发起 QQ 机器人扫码登录（控制台扫码，授权后自动连接）")
    }

    companion object {
        private val SUBCOMMANDS = listOf("reload", "info", "chs", "mem", "bpng", "bpngsz", "qr", "help")
    }
}
