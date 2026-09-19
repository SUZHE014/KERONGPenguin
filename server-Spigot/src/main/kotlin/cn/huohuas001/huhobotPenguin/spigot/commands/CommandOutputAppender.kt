package cn.huohuas001.huhobotPenguin.spigot.commands

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.layout.PatternLayout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Log4j2 根日志 Appender：执行命令期间捕获服务器日志行。
 * Spigot 服务端日志走 Log4j，控制台 dispatchCommand 的输出不会进入
 * ConsoleCommandSender.sendMessage，因此通过本 Appender 捕获。
 */
class CommandOutputAppender private constructor() : AbstractAppender(
    "CommandOutputAppender",
    null as Filter?,
    PatternLayout.createDefaultLayout(),
    true,
    Property.EMPTY_ARRAY,
) {
    private val messages = CopyOnWriteArrayList<String>()

    @Volatile
    private var capturing = false

    init {
        start()
    }

    override fun append(event: LogEvent) {
        if (capturing) {
            messages.add(event.message.formattedMessage)
        }
    }

    /** 开始捕获（清空旧数据）。 */
    fun startCapture() {
        messages.clear()
        capturing = true
    }

    /** 停止捕获并返回已捕获内容。 */
    fun stopCapture(): List<String> {
        capturing = false
        return messages.toList()
    }

    /** 获取当前已捕获内容（不影响捕获状态）。 */
    fun getCaptured(): List<String> = messages.toList()

    companion object {
        @Volatile
        private var instance: CommandOutputAppender? = null

        /** 获取（或创建并挂载到根日志）单例。 */
        @JvmStatic
        fun getInstance(): CommandOutputAppender {
            return instance ?: synchronized(this) {
                instance ?: CommandOutputAppender().also { appender ->
                    val rootLogger = LogManager.getRootLogger() as org.apache.logging.log4j.core.Logger
                    rootLogger.addAppender(appender)
                    instance = appender
                }
            }
        }

        /** 卸载单例（插件停用时调用）。 */
        @JvmStatic
        fun removeInstance() {
            val appender = instance ?: return
            val rootLogger = LogManager.getRootLogger() as org.apache.logging.log4j.core.Logger
            rootLogger.removeAppender(appender)
            appender.stop()
            instance = null
        }
    }
}
