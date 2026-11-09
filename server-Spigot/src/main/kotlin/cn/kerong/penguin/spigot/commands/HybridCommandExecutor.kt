package cn.kerong.penguin.spigot.commands

import cn.kerong.bot.provider.HExecution
import cn.kerong.penguin.spigot.KERONGSpigot
import cn.kerong.penguin.spigot.scheduler.FoliaCompat
import org.bukkit.Bukkit
import java.util.concurrent.CompletableFuture

/**
 * 混合端命令执行器（适用于 Mohist / CatServer / Arclight 等混合服务端）。
 *
 * 噪音修复：命令改派发给自定义 Sender（[BukkitConsoleSender]），
 * 只有该 Sender 收到的行才进返回；Sender 无输出时才回退到窗口内
 * 日志（Appender 已带 [MediaSource] / [Not Secure] 噪音黑名单）。
 * 旧版把控制台全部窗口日志与 Sender 消息合并去重，混合端模组的周期
 * 性日志整段混进 QQ 回复。
 *
 * 控制台记录修复：派发给自定义 Sender 后命令输出只进 QQ 回复、
 * 控制台无记录——现由 [BukkitConsoleSender] 在捕获时同步转发控制台，
 * 本类回放路径改用 replayMessage（仅记录不转发，避免重复）。
 */
class HybridCommandExecutor(private val plugin: KERONGSpigot) : HExecution {
    private val sender = BukkitConsoleSender(plugin)
    private val outputAppender = CommandOutputAppender.Companion.getInstance()

    override val rawString: String
        get() = sender.rawString

    override fun execute(command: String): CompletableFuture<HExecution> {
        val result = CompletableFuture<HExecution>()
        sender.clearMessages()
        outputAppender.startCapture()
        plugin.submit {
            try {
 // 派发给自定义 Sender（收集 sendMessage 回调），
                // 不再派发给真实控制台（那会只能靠日志全量捕获带回噪音）
                Bukkit.dispatchCommand(sender, command)
                completeAfterCommandOutput(result)
            } catch (error: Throwable) {
 // 捕获 Throwable 而非 Exception——命令抛 Error（如其他
                // 插件类的 NoClassDefFoundError）时旧版不完成 future，
                // QQ 侧表现为「执行完没有任何回复」
                outputAppender.stopCapture()
                result.completeExceptionally(error)
            }
        }
        return result
    }

    /** 命令派发后延迟收集输出（优先 Sender 行，无输出才用日志回退）。 */
    private fun completeAfterCommandOutput(result: CompletableFuture<HExecution>) {
 // 经 FoliaCompat 桥接（Folia 全局区域线程延迟）
        FoliaCompat.runSyncLater(plugin as org.bukkit.plugin.Plugin, COMMAND_OUTPUT_DELAY_TICKS) {
            val senderMessages = sender.getAndClearMessages()
            if (senderMessages.isNotEmpty()) {
                // 回放仅记录不转发（控制台转发在捕获时刻已由 Sender 完成）
                for (message in senderMessages) {
                    sender.replayMessage(message)
                }
            } else {
                for (line in outputAppender.stopCapture()) {
                    sender.replayMessage(line)
                }
            }
            result.complete(sender)
        }
    }

    companion object {
        /** 等待命令输出的延迟（tick）。 */
        @Deprecated("保留以兼容旧配置读取")
        const val COMMAND_OUTPUT_DELAY_TICKS = 40L
    }
}
