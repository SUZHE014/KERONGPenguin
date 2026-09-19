package cn.huohuas001.huhobotPenguin.spigot.commands

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.layout.PatternLayout

/**
 * Log4j2 根日志 Appender：执行命令期间捕获服务器日志行。
 * Spigot 服务端日志走 Log4j，控制台 dispatchCommand 的输出不会进入
 * ConsoleCommandSender.sendMessage，因此通过本 Appender 捕获。
 *
 * 0.1.5.2 内存 / CPU 修复：
 * 旧实现存在两个问题：
 * 1. 使用 CopyOnWriteArrayList，捕获期间每追加一行都要复制整个数组（O(n) 分配），
 *    高频日志下产生大量 CPU 开销与垃圾对象；
 * 2. 若停止捕获的收尾任务因故未执行（插件重载 / 关服竞态），capturing 永久为 true，
 *    之后全服日志都会无上限地积累在内存中，导致内存持续增长。
 * 现在改为：
 * - 普通 ArrayList + 同步块追加（O(1) 均摊）；
 * - 行数与字符数双重上限，超出即丢弃并标记截断；
 * - 捕获带硬超时兜底（超时自动停止），彻底杜绝无限积累。
 */
class CommandOutputAppender private constructor() : AbstractAppender(
    "CommandOutputAppender",
    null as Filter?,
    PatternLayout.createDefaultLayout(),
    true,
    Property.EMPTY_ARRAY,
) {
    private val lock = Any()
    private val messages = ArrayList<String>()

    @Volatile
    private var capturing = false

    @Volatile
    private var captureDeadline = 0L

    private var capturedChars = 0
    private var droppedLines = 0

    init {
        start()
    }

    override fun append(event: LogEvent) {
        if (!capturing) return
        // 硬超时兜底：即使停止逻辑未执行，也不会永远累积日志
        if (System.currentTimeMillis() > captureDeadline) {
            capturing = false
            return
        }
        val line = event.message?.formattedMessage ?: return
        synchronized(lock) {
            if (!capturing) return
            if (messages.size >= MAX_LINES || capturedChars + line.length > MAX_CHARS) {
                droppedLines++
                return
            }
            messages.add(line)
            capturedChars += line.length
        }
    }

    /** 开始捕获（清空旧数据）。 */
    fun startCapture() {
        synchronized(lock) {
            messages.clear()
            capturedChars = 0
            droppedLines = 0
        }
        captureDeadline = System.currentTimeMillis() + CAPTURE_HARD_TIMEOUT_MILLIS
        capturing = true
    }

    /** 停止捕获并返回已捕获内容。 */
    fun stopCapture(): List<String> {
        capturing = false
        synchronized(lock) {
            val result = ArrayList<String>(messages.size + 1)
            result.addAll(messages)
            if (droppedLines > 0) {
                result.add("……（命令输出超出上限，已截断 $droppedLines 行）")
            }
            messages.clear()
            capturedChars = 0
            droppedLines = 0
            return result
        }
    }

    /** 获取当前已捕获内容（不影响捕获状态）。 */
    fun getCaptured(): List<String> = synchronized(lock) { ArrayList(messages) }

    companion object {
        /** 捕获行数上限。 */
        private const val MAX_LINES = 512

        /** 捕获字符总数上限。 */
        private const val MAX_CHARS = 128_000

        /** 捕获硬超时：无论命令是否收尾，超过即自动停止。 */
        private const val CAPTURE_HARD_TIMEOUT_MILLIS = 20_000L

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
            appender.capturing = false
            appender.stop()
            synchronized(appender.lock) {
                appender.messages.clear()
                appender.capturedChars = 0
                appender.droppedLines = 0
            }
            instance = null
        }
    }
}
