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
 * /hbkrg 主命令：reload / info / chs / mem / help。
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

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size != 1) return emptyList()
        val prefix = args[0].lowercase(Locale.ROOT)
        return SUBCOMMANDS.filter { it.startsWith(prefix) }
    }

    /** 发送命令帮助。 */
    private fun sendHelp(sender: CommandSender, label: String) {
        sender.sendMessage("${ChatColor.GOLD}===== /$label 命令帮助 =====")
        sender.sendMessage("${ChatColor.YELLOW}/$label help${ChatColor.WHITE} - 查看此帮助")
        sender.sendMessage("${ChatColor.YELLOW}/$label reload${ChatColor.WHITE} - 重载配置并重新同步 QQ 快捷面板（QQ 凭据与更新检查除外，需重启）")
        sender.sendMessage("${ChatColor.YELLOW}/$label info${ChatColor.WHITE} - 查看适配器信息")
        sender.sendMessage("${ChatColor.YELLOW}/$label chs${ChatColor.WHITE} - 立即重试连接 QQ 机器人（断线 / 重连超时后用）")
        sender.sendMessage("${ChatColor.YELLOW}/$label mem${ChatColor.WHITE} - 查看插件内存与缓存诊断（JVM 堆 + 各级缓存）")
    }

    companion object {
        private val SUBCOMMANDS = listOf("reload", "info", "chs", "mem", "help")
    }
}
