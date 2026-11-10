package cn.kerong.penguin.fabric

import cn.kerong.bot.provider.HExecution
import cn.kerong.bot.tools.ConsoleNoiseFilter
import cn.kerong.bot.tools.PluginFileLog
import cn.kerong.penguin.spigot.commands.CommandOutputAppender
import net.minecraft.commands.CommandSource
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 服务器控制台命令执行器（等价 NeoForge 版 NeoForgeConsoleExecutor /
 * Spigot 版 BukkitConsoleSender）：在服务器线程经 Commands#performPrefixedCommand
 * 派发命令；命令反馈引到收集源（与 1.7.2 噪音修复同机制）。
 */
class FabricConsoleExecutor(private val plugin: KERONGFabric) : HExecution {

    private val outputAppender = CommandOutputAppender.Companion.getInstance()

    /** 控制台转发 logger（与主类同名，控制台 + latest.log 双写）。 */
    private val consoleLogger: Logger = LoggerFactory.getLogger("KERONGPenguin")

    /** 最近一次执行捕获到的输出（收集完成时定格，rawString 读取）。 */
    @Volatile
    private var capturedText: String = ""

    override val rawString: String
        get() = capturedText

    override fun execute(command: String): CompletableFuture<HExecution> {
        val result = CompletableFuture<HExecution>()
        val server = FabricServerRef.server
        if (server == null) {
            result.completeExceptionally(IllegalStateException("服务器尚未启动"))
            return result
        }
        capturedText = ""
        val collector = CollectingCommandSource()
        outputAppender.startCapture()
        server.execute {
            try {
                val base = server.createCommandSourceStack()
                val target: CommandSourceStack = try {
                    base.withSource(collector)
                } catch (_: Throwable) {
                    base
                }
                try {
                    consoleLogger.info("[QQ 远程命令] 执行: $command")
                } catch (_: Throwable) {
                }
                server.commands.performPrefixedCommand(target, command)
                plugin.submitLater(40L) {
                    val senderLines = collector.lines()
                    capturedText = if (senderLines.isNotEmpty()) {
                        senderLines.joinToString("\n")
                    } else {
                        outputAppender.stopCapture().joinToString("\n")
                    }
                    result.complete(this)
                }
            } catch (error: Throwable) {
                outputAppender.stopCapture()
                result.completeExceptionally(error)
            }
        }
        return result
    }

    /**
     * 收集命令反馈的 CommandSource（与 NeoForge 版同机制）。
     */
    private class CollectingCommandSource : CommandSource {
        private val messages = CopyOnWriteArrayList<String>()

        fun lines(): List<String> = messages.toList()

        override fun sendSystemMessage(component: Component) {
            try {
                val line = component.getString()
                messages.add(line)
                try {
                    if (line.isNotBlank()) {
                        LOGGER.info("[QQ 远程命令] " + PluginFileLog.stripColorCodes(line))
                    }
                } catch (_: Throwable) {
                }
            } catch (_: Throwable) {
            }
        }

        override fun acceptsSuccess(): Boolean = true

        override fun acceptsFailure(): Boolean = true

        override fun shouldInformAdmins(): Boolean = false

        override fun alwaysAccepts(): Boolean = false

        companion object {
            private val LOGGER: Logger = LoggerFactory.getLogger("KERONGPenguin")
        }
    }
}
