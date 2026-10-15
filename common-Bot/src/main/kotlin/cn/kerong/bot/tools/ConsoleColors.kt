package cn.kerong.bot.tools

/**
 * 控制台颜色适配（1.6.6 新增）。
 *
 * 背景：共享源码里的彩色控制台文案（如更新检查的更新日志）使用 Minecraft
 * § 颜色码。Bukkit 系的 JUL Logger 直出 § 码时控制台**不会**渲染成颜色，
 * 只会显示符号本身（玩家反馈「输出的是颜色符号而不是颜色」）；NeoForge
 * 侧 log4j 同样不渲染 § 码（1.6.4 的临时方案是输出前剥离，代价是失去颜色）。
 *
 * 现统一转换为 ANSI 转义序列：Minecraft 服务器控制台（Spigot / Paper /
 * Purpur / Folia / NeoForge）全部经 jline3 终端输出，ANSI 转义会被渲染为
 * 真实颜色。转换前探测 TerminalConsoleAppender 的 ANSI 能力：输出被重定向
 * 到文件 / 管道（无彩色终端）时自动退化为剥离纯文本，避免日志文件里出现
 * 乱码转义序列。
 *
 * 文件日志（PluginFileLog.write）始终剥离颜色码，不受本类影响。
 */
object ConsoleColors {

    /** Minecraft § 码 → ANSI 转义序列映射（颜色 16 色 + 常用格式）。 */
    private val MAPPING: Map<Char, String> = mapOf(
        // 颜色
        '0' to "\u001B[30m", // 黑
        '1' to "\u001B[34m", // 深蓝
        '2' to "\u001B[32m", // 深绿
        '3' to "\u001B[36m", // 深青
        '4' to "\u001B[31m", // 深红
        '5' to "\u001B[35m", // 紫
        '6' to "\u001B[33m", // 金/黄
        '7' to "\u001B[37m", // 浅灰
        '8' to "\u001B[90m", // 深灰
        '9' to "\u001B[94m", // 蓝
        'a' to "\u001B[92m", // 绿
        'b' to "\u001B[96m", // 青
        'c' to "\u001B[91m", // 红
        'd' to "\u001B[95m", // 粉
        'e' to "\u001B[93m", // 黄
        'f' to "\u001B[97m", // 白
        // 格式
        'l' to "\u001B[1m",  // 粗体
        'o' to "\u001B[3m",  // 斜体
        'n' to "\u001B[4m",  // 下划线
        'm' to "\u001B[9m",  // 删除线
        'r' to "\u001B[0m",  // 重置
    )

    /** ANSI 重置序列（行尾兜底，防颜色泄漏污染后续日志行）。 */
    private const val RESET = "\u001B[0m"

    /** § + 单字符的颜色码模式。 */
    private val COLOR_CODE = Regex("§.")

    /**
     * 消息是否含 § 颜色码（无码消息直接原样输出，零开销路径）。
     */
    fun hasColorCodes(message: String): Boolean = message.indexOf('§') >= 0

    /**
     * § 码 → ANSI 转义序列。行尾自动补重置码（§r 已在行尾时不重复）。
     * 未知 § 码（如 §k 随机字符）剔除，不产生乱码。
     */
    fun toAnsi(message: String): String {
        if (!hasColorCodes(message)) return message
        val builder = StringBuilder(message.length + 8)
        var index = 0
        while (index < message.length) {
            val ch = message[index]
            if (ch == '§' && index + 1 < message.length) {
                val ansi = MAPPING[message[index + 1]]
                if (ansi != null) {
                    builder.append(ansi)
                    index += 2
                    continue
                }
                // 未知码（§k 等）：整体剔除
                index += 2
                continue
            }
            builder.append(ch)
            index += 1
        }
        val result = builder.toString()
        return if (result.endsWith(RESET) || result.endsWith("\u001B[0m")) result else result + RESET
    }

    /**
     * 控制台输出前的装饰：终端支持 ANSI → 转 ANSI 真彩色；不支持（输出
     * 重定向到文件 / 管道）→ 剥离为纯文本。
     *
     * ANSI 能力探测：TerminalConsoleAppender.isAnsiSupported()（Minecraft
     * 系服务器控制台标配类，jline 终端检测）；类不存在时保守视为支持
     * （Linux 终端环境默认渲染 ANSI，无终端时输出乱码的风险与旧版 § 符号
     * 相当，不额外恶化）。
     */
    fun decorateForConsole(message: String): String {
        if (!hasColorCodes(message)) return message
        return if (ansiSupported()) toAnsi(message) else PluginFileLog.stripColorCodes(message)
    }

    /** ANSI 能力探测（反射调用，结果缓存）。 */
    @Volatile
    private var ansiProbed = false

    @Volatile
    private var ansiAvailable = true

    private fun ansiSupported(): Boolean {
        if (ansiProbed) return ansiAvailable
        return try {
            val cls = Class.forName("net.minecrell.terminalconsole.TerminalConsoleAppender")
            val method = cls.getMethod("isAnsiSupported")
            ansiAvailable = method.invoke(null) as? Boolean ?: true
            ansiProbed = true
            ansiAvailable
        } catch (_: Throwable) {
            // 非 Minecraft 控制台环境（类不存在）：保守视为支持
            ansiAvailable = true
            ansiProbed = true
            true
        }
    }
}
