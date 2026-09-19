package cn.huohuas001.huhobotPenguin.spigot.commands

import cn.huohuas001.bot.provider.HExecution
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import org.bukkit.Bukkit
import java.util.concurrent.CompletableFuture

/**
 * 混合端命令执行器（适用于 Mohist / CatServer / Arclight 等混合服务端）：
 * 由服务器自身的控制台通道执行命令，同时捕获 Sender 消息与 Log4j 日志双路输出。
 */
class HybridCommandExecutor(private val plugin: HuHoBotSpigot) : HExecution {
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
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
                completeAfterCommandOutput(result)
            } catch (error: Exception) {
                outputAppender.stopCapture()
                result.completeExceptionally(error)
            }
        }
        return result
    }

    /** 命令派发后延迟收集双路输出。 */
    private fun completeAfterCommandOutput(result: CompletableFuture<HExecution>) {
        Bukkit.getScheduler().runTaskLater(plugin as org.bukkit.plugin.Plugin, Runnable {
            val senderMessages = sender.getAndClearMessages()
            val loggedMessages = outputAppender.stopCapture()
            for (message in (senderMessages + loggedMessages).distinct()) {
                sender.sendMessage(message)
            }
            result.complete(sender)
        }, COMMAND_OUTPUT_DELAY_TICKS)
    }

    companion object {
        /** 等待命令输出的延迟（tick）。 */
        @Deprecated("保留以兼容旧配置读取")
        const val COMMAND_OUTPUT_DELAY_TICKS = 40L
    }
}
