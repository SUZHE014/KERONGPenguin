package cn.huohuas001.huhobotPenguin.spigot.commands

import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import java.util.Locale

/**
 * /qq 命令：QQ 绑定管理（rebind 自助解绑 / qxqq 强制解绑 / skip 免验证）。
 */
class QqCommand(private val plugin: HuHoBotSpigot) : TabExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = if (args.isNotEmpty()) args[0].lowercase(Locale.ROOT) else "help"
        when (sub) {
            "help" -> {
                sendHelp(sender, label)
                return true
            }
            "rebind" -> {
                handleSelfRebind(sender, label)
                return true
            }
            "qxqq" -> {
                if (!requireOp(sender, label, sub)) return true
                handleForceRebind(sender, args, label, sub)
                return true
            }
            "skip" -> {
                if (!requireOp(sender, label, sub)) return true
                handleSkip(sender, args, label, true)
                return true
            }
            "unskip" -> {
                if (!requireOp(sender, label, sub)) return true
                handleSkip(sender, args, label, false)
                return true
            }
        }
        sendHelp(sender, label)
        return true
    }

    /** 校验 OP 权限。 */
    private fun requireOp(sender: CommandSender, label: String, sub: String): Boolean {
        if (!sender.isOp) {
            sender.sendMessage("${ChatColor.RED}你没有权限执行 /$label $sub（需要管理员权限）")
            return false
        }
        return true
    }

    /** 玩家自助解除绑定。 */
    private fun handleSelfRebind(sender: CommandSender, label: String) {
        val player = sender as? Player ?: run {
            sender.sendMessage("${ChatColor.RED}此命令只能由玩家执行。管理员请使用 /$label qxqq <玩家名>")
            return
        }
        val playerName = player.name
        val playerUuid = player.uniqueId.toString()
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            sender.sendMessage("${ChatColor.RED}管理器未就绪")
            return
        }
        val success = manager.unbindByUuid(playerName, playerUuid)
        if (success) {
            QqBindManager.logQuiet("[MC命令] /$label rebind 玩家=$playerName 自行解除绑定")
            sender.sendMessage("${ChatColor.GREEN}✅ 已解除你的 QQ 绑定。你下次登录将重新获取绑定码。")
        } else {
            sender.sendMessage("${ChatColor.RED}❌ 你没有绑定记录。")
        }
    }

    /** 管理员强制玩家重新绑定。 */
    private fun handleForceRebind(sender: CommandSender, args: Array<out String>, label: String, sub: String) {
        if (args.size < 2) {
            sender.sendMessage("${ChatColor.RED}用法: /$label $sub <玩家名>")
            return
        }
        val playerName = args[1]
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            sender.sendMessage("${ChatColor.RED}管理器未就绪")
            return
        }
        // 优先用玩家名反查 QUUID（支持 UUID 绑定机制）
        val quuid = manager.findQuuidByPlayerName(playerName)
        val success = if (!quuid.isNullOrEmpty()) {
            manager.unbindByQuuid(quuid, playerName)
        } else {
            manager.unbind(playerName)
        }
        if (success) {
            QqBindManager.logQuiet("[MC命令] /$label $sub $playerName 成功 by ${sender.name}")
            sender.sendMessage("${ChatColor.GREEN}✅ 已解除玩家 $playerName 的 QQ 绑定。该玩家下次登录将重新获取绑定码。")
        } else {
            sender.sendMessage("${ChatColor.RED}❌ 玩家 $playerName 未找到绑定记录。")
        }
    }

    /** 设置/取消跳过绑定验证。 */
    private fun handleSkip(sender: CommandSender, args: Array<out String>, label: String, skip: Boolean) {
        if (args.size < 2) {
            sender.sendMessage("${ChatColor.RED}用法: /$label ${if (skip) "skip" else "unskip"} <玩家名>")
            return
        }
        val playerName = args[1]
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            sender.sendMessage("${ChatColor.RED}管理器未就绪")
            return
        }
        manager.setSkipped(playerName, skip)
        QqBindManager.logQuiet("[MC命令] /$label ${if (skip) "skip" else "unskip"} $playerName by ${sender.name}")
        if (skip) {
            sender.sendMessage("${ChatColor.GREEN}✅ 玩家 $playerName 已加入跳过绑定列表。")
        } else {
            sender.sendMessage("${ChatColor.GREEN}✅ 玩家 $playerName 已移出跳过绑定列表。")
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase(Locale.ROOT)
            return SUBCOMMANDS.filter { it.startsWith(prefix) }
        }
        if (args.size == 2 && args[0].lowercase(Locale.ROOT) in setOf("qxqq", "skip", "unskip")) {
            return getPlayerNameSuggestions(args[1])
        }
        return emptyList()
    }

    /** 玩家名补全（已绑定玩家 + 在线玩家）。 */
    private fun getPlayerNameSuggestions(prefix: String): List<String> {
        val result = ArrayList<String>()
        val lower = prefix.lowercase(Locale.ROOT)
        try {
            val manager = QqBindManager.getInstance()
            for (name in manager.listBoundPlayerNames()) {
                if (name.lowercase(Locale.ROOT).startsWith(lower)) result.add(name)
            }
        } catch (_: Throwable) {
        }
        try {
            for (player in plugin.server.onlinePlayers) {
                val name = player.name ?: continue
                if (name.lowercase(Locale.ROOT).startsWith(lower) && !result.contains(name)) {
                    result.add(name)
                }
            }
        } catch (_: Throwable) {
        }
        return result
    }

    /** 发送帮助。 */
    private fun sendHelp(sender: CommandSender, label: String) {
        sender.sendMessage("${ChatColor.GOLD}===== /$label QQ 绑定管理命令 =====")
        sender.sendMessage("${ChatColor.YELLOW}/$label help${ChatColor.WHITE} - 查看此帮助（所有人可用）")
        sender.sendMessage("${ChatColor.YELLOW}/$label rebind${ChatColor.WHITE} - 重新绑定自己的 QQ（玩家自助，无需管理员）")
        sender.sendMessage("${ChatColor.GRAY}以下命令需要管理员（op）权限：")
        sender.sendMessage("${ChatColor.YELLOW}/$label qxqq <玩家名>${ChatColor.WHITE} - 强制让玩家重新绑定 QQ（tab 补全已绑定玩家）")
        sender.sendMessage("${ChatColor.YELLOW}/$label skip <玩家名>${ChatColor.WHITE} - 强制让玩家跳过 QQ 绑定验证")
        sender.sendMessage("${ChatColor.YELLOW}/$label unskip <玩家名>${ChatColor.WHITE} - 取消跳过绑定")
    }

    companion object {
        private val SUBCOMMANDS = listOf("help", "rebind", "qxqq", "skip", "unskip")
    }
}
