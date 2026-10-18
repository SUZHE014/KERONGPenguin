package cn.kerong.bot.tools

import cn.kerong.bot.provider.plugin
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 插件文件日志（0.1.5.4）。
 *
 * 背景：插件自身的连接生命周期日志与数据保存日志此前只走 Bukkit Logger
 * （仅控制台），混合端服务器的 latest.log 路由不稳定，事后无法追溯；
 * 数据保存日志则相反——被要求只在文件留档、不刷控制台。
 *
 * 现统一写入插件日志文件（与 SDK 详细日志同一份按天分文件）：
 *   plugins/KERONGPenguin/logs/qq/qq-bind-yyyy-MM-dd.log
 *
 * 写入为逐行追加、按需打开即关，无后台线程、无内存缓存、无锁竞争
 * （低频日志，单行写入原子性由追加模式保证），开销可忽略。
 *
 * 0.1.5.5：警告/错误统一双写——平台 log_warning/log_error
 * 本身已改为“控制台 + 本文件”双写（见平台适配器），
 * 此处 warnAndKeep/errorAndKeep 直接委托，避免双写重复。
 */
object PluginFileLog {

    /** 颜色代码（§ + 一个字符，Minecraft / Bukkit 控制台颜色）。 */
    private val COLOR_CODE = Regex("§.")

    /** 剥离 § 颜色码（1.6.4 公开：NeoForge 控制台不渲染颜色码，输出前需剥离）。 */
    fun stripColorCodes(message: String): String = COLOR_CODE.replace(message, "")

    /**
     * 单个日志文件大小上限（1.6.7.4：由 1GB 收紧为 512KB——按天 + 超限
     * 轮转双维度控制单文件体积，用户需求；超限后写入 <基名>-N.log）。
     */
    const val ROTATE_THRESHOLD_BYTES: Long = 512L * 1024

    /**
     * 解析应写入的日志文件：优先 <基名>.log；已存在且超 [ROTATE_THRESHOLD_BYTES]
     * 时顺次找第一个不满的 <基名>-N.log（N 从 1 起）。
     *
     * 本文件与 QqBindManager 的详细日志写的是同一批文件
     * （logs/qq/qq-bind-<日期>[-N].log），两处写入入口共用同一轮转实现，
     * 确保无论从哪个入口写都遵守同一大小上限与命名规则。
     */
    fun resolveRotated(folder: File, baseName: String): File {
        var file = File(folder, "$baseName.log")
        if (file.exists() && file.length() >= ROTATE_THRESHOLD_BYTES) {
            var index = 1
            while (true) {
                val candidate = File(folder, "$baseName-$index.log")
                if (!candidate.exists() || candidate.length() < ROTATE_THRESHOLD_BYTES) {
                    file = candidate
                    break
                }
                index++
            }
        }
        return file
    }

    /**
     * 当前日志文件（按天分文件；单文件超 512KB 轮转到 -N 后缀；目录与
     * QqBindManager 详细日志保持一致，轮转共用 [resolveRotated]）。
     */
    private fun currentFile(): File? {
        return try {
            val folder = plugin.configFile?.parentFile?.resolve("logs/qq") ?: return null
            if (!folder.exists() && !folder.mkdirs()) return null
            resolveRotated(folder, "qq-bind-${SimpleDateFormat("yyyy-MM-dd").format(Date())}")
        } catch (_: Throwable) {
            null
        }
    }

    /** 仅写入日志文件（不进控制台）。用于数据保存等只需留档的日志。 */
    fun write(message: String) {
        try {
            val file = currentFile() ?: return
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
            // 1.6.1：写入前剥离 § 颜色代码（控制台彩色文本进文件后是乱码字符）
            val plain = COLOR_CODE.replace(message, "")
            PrintWriter(FileWriter(file, true)).use { it.println("[$timestamp] $plain") }
        } catch (_: Throwable) {
        }
    }

    /**
     * 信息：控制台 + 日志文件双写。
     * 1.6.1：log_info 本身已双写（平台适配器），直接委托，不再重复写文件。
     */
    fun infoAndKeep(message: String) {
        plugin.log_info(message)
    }

    /** 警告：控制台 + 日志文件双写（log_warning 已双写，直接委托）。 */
    fun warnAndKeep(message: String) {
        plugin.log_warning(message)
    }

    /** 错误：控制台 + 日志文件双写（log_error 已双写，直接委托）。 */
    fun errorAndKeep(message: String) {
        plugin.log_error(message)
    }
}
