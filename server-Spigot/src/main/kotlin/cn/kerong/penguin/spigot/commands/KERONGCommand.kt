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
 * /kerong 主命令（短别名 /hb）：reload / info / panel / chs / help。
 *
 * 1.6.2 新增 chs 子命令：机器人重连超时挂起或断线期间，手动重置重连计数
 * 并立即重新尝试连接（等价于 30 分钟退避结束后的自动重试，任何时候可用）。
 */
class KERONGCommand(private val plugin: KERONGSpigot) : TabExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = if (args.isNotEmpty()) args[0].lowercase(Locale.ROOT) else "help"
        when (sub) {
            "reload" -> {
                plugin.reloadPluginConfig()
                sender.sendMessage("${ChatColor.GOLD}已重载配置文件。")
                return true
            }
            "info" -> {
                sender.sendMessage("平台: ${plugin.platform}\n版本: ${plugin.pluginVersion}")
                return true
            }
            "panel" -> {
                resyncPanel(sender)
                return true
            }
            "chs" -> {
                // 1.6.2：手动重连（重连超时挂起 / 断线期间随时可用）
                val result = cn.kerong.bot.ReconnectManager.manualReconnect()
                sender.sendMessage("${ChatColor.GREEN}$result")
                return true
            }
        }
        sendHelp(sender, label)
        return true
    }

    /** 重新同步 QQ 快捷指令面板。 */
    private fun resyncPanel(sender: CommandSender) {
        try {
            val starter = QClient.starter
            if (starter == null) {
                sender.sendMessage("${ChatColor.RED}QQ 客户端未启动。")
                return
            }
            val groups = plugin.groupOpenIdList()
            if (groups.isEmpty()) {
                sender.sendMessage("${ChatColor.RED}未配置 QQ 群。")
                return
            }
            MenuManager.syncGroupPanels(starter, groups)
            sender.sendMessage("${ChatColor.GREEN}已重新同步 QQ 快捷指令面板。")
        } catch (t: Throwable) {
            sender.sendMessage("${ChatColor.RED}面板同步失败: ${t.message}")
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
        sender.sendMessage("${ChatColor.YELLOW}/$label reload${ChatColor.WHITE} - 重载配置文件")
        sender.sendMessage("${ChatColor.YELLOW}/$label info${ChatColor.WHITE} - 查看适配器信息")
        sender.sendMessage("${ChatColor.YELLOW}/$label panel${ChatColor.WHITE} - 重新同步 QQ 快捷指令面板")
        sender.sendMessage("${ChatColor.YELLOW}/$label chs${ChatColor.WHITE} - 立即重试连接 QQ 机器人（断线 / 重连超时后用）")
    }

    companion object {
        private val SUBCOMMANDS = listOf("reload", "info", "panel", "chs", "help")
    }
}
