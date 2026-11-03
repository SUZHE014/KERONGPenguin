package cn.kerong.penguin.neoforge

import cn.kerong.bot.provider.HExecution
import cn.kerong.penguin.spigot.commands.CommandOutputAppender
import net.minecraft.commands.CommandSource
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 服务器控制台命令执行器（等价 Spigot 版 BukkitConsoleSender）：
 * 在服务器线程经 Commands#performPrefixedCommand 派发命令。
 *
 * 1.7.0 修复「/执行命令 执行完没有输出」：旧版在完成回调里先
 * stopCapture()（内部会清空调用方再无法读取）再 complete(result)，
 * 随后读取 rawString 拿到的永远是空列表——命令明明执行了、QQ 群却
 * 收不到任何输出。现在停止捕获时先把已捕获的行落进 [capturedText]，
 * rawString 从这里读取。
 *
 * 1.7.2 噪音修复（用户实测：QQ 回复里混入与命令无关的周期性日志——
 * NeoForge 模组的 [MediaSource] 媒体刷屏、[Not Secure] 聊天行等）：
 * - 命令源改用**包装的 CommandSourceStack**——withSource(收集器) 把
 *   命令反馈（sendSuccess / sendFailure → CommandSource.sendSystemMessage）
 *   全部引到 [CollectingCommandSource]，只有该源收到的行才进返回；
 * - 旧版用真实控制台源 + log4j 根 Appender 全量捕获窗口内日志，窗口里
 *   所有异步日志（与命令无关）都进了 QQ 回复；
 * - 命令没有源输出（只往 logger 打）时才回退到 Appender 日志行
 *   （已带噪音黑名单过滤，与 Spigot 端同源）；
 * - withSource 在个别运行版本缺失时（NoSuchMethodError）自动回退到
 *   未包装源（行为退回旧版 + 黑名单兜底，不会让命令执行失败）。
 */
class NeoForgeConsoleExecutor(private val plugin: KERONGNeoForge) : HExecution {

    private val outputAppender = CommandOutputAppender.Companion.getInstance()

    /** 最近一次执行捕获到的输出（收集完成时定格，rawString 读取）。 */
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
        val collector = CollectingCommandSource()
        outputAppender.startCapture()
        server.execute {
            try {
                val base = server.createCommandSourceStack()
                // 1.7.2：把命令反馈引到收集源（只有该源收到的行才进返回）；
                // withSource 个别版本缺失时回退未包装源（日志黑名单兜底）
                val target: CommandSourceStack = try {
                    base.withSource(collector)
                } catch (_: Throwable) {
                    base
                }
                server.commands.performPrefixedCommand(target, command)
                // 延迟 2 秒收集异步输出（等价 Spigot 版 COMMAND_OUTPUT_DELAY_TICKS）
                plugin.submitLater(40L) {
                    // 先定格输出再完成 future：rawString 读取的是 capturedText，
                    // 不依赖已被 stopCapture 清空的捕获缓冲
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
     * 收集命令反馈的 CommandSource（1.7.2）。
     *
     * - sendSystemMessage 收到的行进 [lines]（命令的全部 sendMessage 反馈）；
     * - acceptsSuccess / acceptsFailure = true（与控制台一致：成功与失败
     *   反馈都要发过来）；
     * - shouldInformAdmins = false（不触发面向管理员的广播副本，避免
     *   同一输出以广播形式再混进日志回退通道）。
     */
    private class CollectingCommandSource : CommandSource {
        private val messages = CopyOnWriteArrayList<String>()

        fun lines(): List<String> = messages.toList()

        override fun sendSystemMessage(component: Component) {
            try {
                messages.add(component.getString())
            } catch (_: Throwable) {
                // 文本提取异常不至于让命令执行失败
            }
        }

        override fun acceptsSuccess(): Boolean = true

        override fun acceptsFailure(): Boolean = true

        override fun shouldInformAdmins(): Boolean = false

        override fun alwaysAccepts(): Boolean = false
    }
}
