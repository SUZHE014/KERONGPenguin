package cn.huohuas001.bot.tools

import java.io.PrintStream

/**
 * 按调用方包名过滤输出的 PrintStream。
 * 用于屏蔽 QQ SDK 内部库向控制台刷屏的调试输出。
 *
 * @param delegate          实际的目标输出流
 * @param blockedPackagePrefix 需要屏蔽的调用方类名前缀（如 "io.github.kloping.qqbot."）
 */
internal class PackageFilteringPrintStream(
    private val delegate: PrintStream,
    private val blockedPackagePrefix: String,
) : PrintStream(delegate, true) {

    override fun write(value: Int) {
        if (!isBlockedCaller()) delegate.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (!isBlockedCaller()) delegate.write(buffer, offset, length)
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        flush()
    }

    override fun checkError(): Boolean = delegate.checkError()

    /** 当调用栈中出现被屏蔽包名前缀的帧时，丢弃本次输出。 */
    private fun isBlockedCaller(): Boolean {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace.any { it.className.startsWith(blockedPackagePrefix) }
    }
}

/**
 * 控制台输出过滤器：将 System.out 替换为过滤流，
 * 屏蔽 kloping QQ SDK 内部产生的冗余日志。
 */
object QqBotConsoleOutputFilter {
    private const val BLOCKED_PACKAGE_PREFIX = "io.github.kloping.qqbot."

    @Volatile
    private var originalOutput: PrintStream? = null

    @Volatile
    private var filteredOutput: PackageFilteringPrintStream? = null

    @Synchronized
    fun install() {
        if (filteredOutput != null) return
        val currentOutput = System.out ?: return
        val replacement = PackageFilteringPrintStream(currentOutput, BLOCKED_PACKAGE_PREFIX)
        System.setOut(replacement)
        originalOutput = currentOutput
        filteredOutput = replacement
    }

    @Synchronized
    fun uninstall() {
        val replacement = filteredOutput ?: return
        val original = originalOutput
        replacement.flush()
        if (System.out === replacement && original != null) {
            System.setOut(original)
        }
        filteredOutput = null
        originalOutput = null
    }
}
