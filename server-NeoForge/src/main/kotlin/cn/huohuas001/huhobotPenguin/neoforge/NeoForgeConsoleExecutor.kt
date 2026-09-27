package cn.huohuas001.huhobotPenguin.neoforge

import cn.huohuas001.bot.provider.HExecution
import cn.huohuas001.huhobotPenguin.spigot.commands.CommandOutputAppender
import java.util.concurrent.CompletableFuture

/**
 * 服务器控制台命令执行器（等价 Spigot 版 BukkitConsoleSender）：
 * 在服务器线程经 Commands#performPrefixedCommand 派发命令，
 * 输出经 log4j 捕获器（CommandOutputAppender，与 Spigot 同源）聚合。
 */
class NeoForgeConsoleExecutor(private val plugin: HuHoBotNeoForge) : HExecution {

    private val outputAppender = CommandOutputAppender.Companion.getInstance()

    override val rawString: String
        get() = outputAppender.getCaptured().joinToString("\n")

    override fun execute(command: String): CompletableFuture<HExecution> {
        val result = CompletableFuture<HExecution>()
        val server = cn.huohuas001.huhobotPenguin.neoforge.NeoServerRef.server
        if (server == null) {
            result.completeExceptionally(IllegalStateException("服务器尚未启动"))
            return result
        }
        outputAppender.startCapture()
        server.execute {
            try {
                val source = server.createCommandSourceStack()
                server.commands.performPrefixedCommand(source, command)
                // 延迟 2 秒收集异步输出（等价 Spigot 版 COMMAND_OUTPUT_DELAY_TICKS）
                plugin.submitLater(40L) {
                    outputAppender.stopCapture()
                    result.complete(this)
                }
            } catch (error: Throwable) {
                outputAppender.stopCapture()
                result.completeExceptionally(error)
            }
        }
        return result
    }
}
