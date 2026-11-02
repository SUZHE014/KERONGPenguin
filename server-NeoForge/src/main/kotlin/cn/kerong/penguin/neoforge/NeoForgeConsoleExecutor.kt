package cn.kerong.penguin.neoforge

import cn.kerong.bot.provider.HExecution
import cn.kerong.penguin.spigot.commands.CommandOutputAppender
import java.util.concurrent.CompletableFuture

/**
 * 服务器控制台命令执行器（等价 Spigot 版 BukkitConsoleSender）：
 * 在服务器线程经 Commands#performPrefixedCommand 派发命令，
 * 输出经 log4j 捕获器（CommandOutputAppender，与 Spigot 同源）聚合。
 *
 * 1.7.0 修复「/执行命令 执行完没有输出」：旧版在完成回调里先
 * stopCapture()（内部会清空调用方再无法读取）再 complete(result)，
 * 随后读取 rawString 拿到的永远是空列表——命令明明执行了、QQ 群却
 * 收不到任何输出。现在停止捕获时先把已捕获的行落进 [capturedText]，
 * rawString 从这里读取。
 */
class NeoForgeConsoleExecutor(private val plugin: KERONGNeoForge) : HExecution {

    private val outputAppender = CommandOutputAppender.Companion.getInstance()

    /** 最近一次执行捕获到的输出（stopCapture 时定格，rawString 读取）。 */
    @Volatile
    private var capturedText: String = ""

    override val rawString: String
        get() = capturedText

    override fun execute(command: String): CompletableFuture<HExecution> {
        val result = CompletableFuture<HExecution>()
        val server = cn.kerong.penguin.neoforge.NeoServerRef.server
        if (server == null) {
            result.completeExceptionally(IllegalStateException("服务器尚未启动"))
            return result
        }
        capturedText = ""
        outputAppender.startCapture()
        server.execute {
            try {
                val source = server.createCommandSourceStack()
                server.commands.performPrefixedCommand(source, command)
                // 延迟 2 秒收集异步输出（等价 Spigot 版 COMMAND_OUTPUT_DELAY_TICKS）
                plugin.submitLater(40L) {
                    // 先定格输出再完成 future：rawString 读取的是 capturedText，
                    // 不依赖已被 stopCapture 清空的捕获缓冲
                    capturedText = outputAppender.stopCapture().joinToString("\n")
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
