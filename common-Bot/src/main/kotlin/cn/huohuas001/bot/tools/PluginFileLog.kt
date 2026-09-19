package cn.huohuas001.bot.tools

import cn.huohuas001.bot.provider.plugin
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
 */
object PluginFileLog {

    /** 当前日志文件（按天分文件；目录与 QqBindManager 详细日志保持一致）。 */
    private fun currentFile(): File? {
        return try {
            val folder = plugin.configFile?.parentFile?.resolve("logs/qq") ?: return null
            if (!folder.exists() && !folder.mkdirs()) return null
            File(folder, "qq-bind-${SimpleDateFormat("yyyy-MM-dd").format(Date())}.log")
        } catch (_: Throwable) {
            null
        }
    }

    /** 仅写入日志文件（不进控制台）。用于数据保存等只需留档的日志。 */
    fun write(message: String) {
        try {
            val file = currentFile() ?: return
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
            PrintWriter(FileWriter(file, true)).use { it.println("[$timestamp] $message") }
        } catch (_: Throwable) {
        }
    }

    /** 信息：控制台 + 日志文件双写。用于连接等需要实时观察且需留档的日志。 */
    fun infoAndKeep(message: String) {
        plugin.log_info(message)
        write(message)
    }

    /** 警告：控制台 + 日志文件双写。 */
    fun warnAndKeep(message: String) {
        plugin.log_warning(message)
        write("[警告] $message")
    }

    /** 错误：控制台 + 日志文件双写。 */
    fun errorAndKeep(message: String) {
        plugin.log_error(message)
        write("[错误] $message")
    }
}
