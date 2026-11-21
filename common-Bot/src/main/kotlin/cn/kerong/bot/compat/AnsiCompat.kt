package cn.kerong.bot.compat

/**
 * kloping SDK 日志着色的 jansi 替身（无第三方依赖，1.7.6.7 Arclight 覆盖轮）。
 *
 * ## 背景：Arclight-forge 1.20.1「鉴权成功但机器人离线」根因
 *
 * Spigot 1.18+ 的 `PluginClassLoader.loadClass0` 第一优先级是
 * `super.loadClass`（URLClassLoader 标准**父优先**委托）——插件 fat JAR 内置的
 * 第三方库**一律被服务端自带的同包名类覆盖**：
 *
 * - Spigot / Paper 1.20.1 自带 jansi **2.x**（含 `Ansi.fgRgb(int)`）→ 恰好兼容；
 * - Arclight-forge 1.20.1 自带 Forge 引导库 jansi **1.18**（无 `fgRgb`）→
 *   SDK 的 `LoggerImpl` / `HttpClientConfig` / `HttpClientManagerImpl(+$Invoker)`
 *   四个类里所有 `Ansi.ansi().fgRgb(...)` 调用抛
 *   `NoSuchMethodError: Ansi.fgRgb(int)`。
 *
 * `LoggerImpl.Log` 对**每一条**日志（全部级别）都无条件走 jansi 着色，且 SDK 的
 * `catch (Exception)` 拦不住 Error：真凭据时崩溃发生在 WSS 工作线程的
 * `logger.log("ws url:...")`（被 Future 静默吞掉），表象即"凭据自检通过、
 * 机器人始终离线"；凭据异常时崩溃发生在启动线程，直接终止客户端启动。
 *
 * ## 修复：字节码层把 4 个 SDK 类的 jansi 引用重定位到本类
 *
 * `scripts/patch_base_jansi.py` + `tools/JansiRelocate.java` 在打包期把
 * `org/fusesource/jansi/Ansi` 全部引用改写为 `cn/kerong/bot/compat/AnsiCompat`
 * （ASM ClassRemapper，含栈映射帧），本类按 jansi 2.x **字节码语义**复刻构建器：
 *
 * - `fgRgb(int rgb)`：`ESC[38;2;R;G;Bm`（R/G/B = rgb>>16 / >>8 / 原值，各 & 0xFF，
 *   与 jansi 2.x `fgRgb(int)` → `fgRgb(int,int,int)` 的掩码口径一致）；
 * - `a(String)` / `a(Object)`：flush 属性后追加文本（null 追加 "null"）；
 * - `a(int)`：追加数字字面量（jansi 2.x `a(int)` 即 `StringBuilder.append(int)`，
 *   HttpClientManagerImpl$Invoker 的 HTTP 状态码走此重载）；
 * - `reset()`：`ESC[0m`；`toString()`：拼装结果。
 *
 * `LoggerImpl` 落盘时用正则 `ESC[38;2;R;G;Bm` 剥色（见其 Log 实现），本类输出
 * 的转义序列与其完全匹配，日志文件内容与旧版逐字节一致。插件 LogSink 接管后
 * 控制台输出走原始消息（不着色），着色串仅作无 sink 时的兜底。
 */
class AnsiCompat private constructor() {

    /** 构建缓冲（对应 jansi Ansi.builder）。 */
    private val sb = StringBuilder()

    /** 前景色 24 位真彩（与 jansi 2.x fgRgb(int) 掩码口径一致，alpha 位丢弃）。 */
    fun fgRgb(rgb: Int): AnsiCompat {
        sb.append(ESC).append("[38;2;")
            .append((rgb shr 16) and 0xFF).append(';')
            .append((rgb shr 8) and 0xFF).append(';')
            .append(rgb and 0xFF).append('m')
        return this
    }

    /** 追加文本（null 追加 "null"，同 StringBuilder.append(String)）。 */
    fun a(text: String?): AnsiCompat {
        sb.append(text)
        return this
    }

    /** 追加对象（String.valueOf 语义，null 追加 "null"）。 */
    fun a(value: Any?): AnsiCompat {
        sb.append(value)
        return this
    }

    /** 追加数字字面量（jansi 2.x a(int) 即追加数字本身，非转义码）。 */
    fun a(code: Int): AnsiCompat {
        sb.append(code)
        return this
    }

    /** 复位属性（ESC[0m）。 */
    fun reset(): AnsiCompat {
        sb.append(ESC).append("[0m")
        return this
    }

    override fun toString(): String = sb.toString()

    companion object {
        /** ESC 控制符（0x1B，jansi 全部转义序列的前缀）。 */
        private const val ESC = "\u001B"

        /** 起一次构建（对应 Ansi.ansi()；不做终端探测，恒产出转义序列——
         *  着色串只进日志文件（落盘前剥色）与无 sink 兜底，不进真终端）。 */
        @JvmStatic
        fun ansi(): AnsiCompat = AnsiCompat()
    }
}
