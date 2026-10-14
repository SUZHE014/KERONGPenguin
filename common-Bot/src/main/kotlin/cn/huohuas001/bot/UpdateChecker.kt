package cn.huohuas001.bot

import cn.huohuas001.bot.tools.PluginFileLog
import com.alibaba.fastjson.JSON
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 更新检查（1.6.2 新增）：插件启动后异步访问官网的版本信息文件，发现新版本时
 * 在控制台输出「最新版本号 + 下载页链接 + 简要更新日志」。
 *
 * - 官网版本文件：https://kerong.xin/penguin/version.json（每次发版由官网
 *   更新，内容形如 {"version":"1.6.2","url":"...","changelog":["...","..."]}）；
 * - 开关：update-check.enabled（默认开启；关闭后完全不联网检查）；
 * - 新版本：控制台输出 3~4 行（版本号 / 下载页 / 每条更新一行，保持简短
 *   不占满屏幕），同时写入插件日志文件；
 * - 已是最新：一行 INFO；
 * - 检查失败：控制台只输出一句话（网络问题不应刷屏），异常细节
 *   （状态码 / 响应体 / 堆栈摘要）写入插件日志文件供排查；
 * - 执行时机：插件启用后异步延迟 8 秒执行（错开启动高峰，不阻塞 onEnable）。
 *
 * 版本比较：按「点分段数字」逐段比较（1.6.1 < 1.6.2 < 1.6.10 < 1.7），
 * 任一段更大即视为更新；段数不足按 0 补齐；非数字段按字符串比较兜底
 * （如 1.6.2-beta）。
 */
object UpdateChecker {

    /** 官网版本信息文件地址（官网每次发版同步更新）。 */
    const val VERSION_URL = "https://kerong.xin/penguin/version.json"

    /** 下载页地址（输给控制台，用户点击即达）。 */
    const val DOWNLOAD_PAGE_URL = "https://kerong.xin/penguin/"

    /** 检查是否开启（update-check.enabled，默认 true）。 */
    fun enabled(): Boolean {
        return try {
            val instance = cn.huohuas001.bot.provider.BotShared.instance
            (instance as? org.bukkit.plugin.java.JavaPlugin)
                ?.config?.getBoolean("update-check.enabled", true) ?: true
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * 执行一次更新检查（异步调用）。
     *
     * @param currentVersion 当前插件版本（pluginVersion）
     */
    fun check(currentVersion: String) {
        if (!enabled()) {
            PluginFileLog.write("[更新检查] 已关闭（update-check.enabled: false），本次跳过")
            return
        }
        try {
            val body = httpGet(VERSION_URL, 10_000)
                ?: throw java.io.IOException("响应体为空")
            val json = JSON.parseObject(String(body, StandardCharsets.UTF_8))
                ?: throw java.io.IOException("版本文件不是有效 JSON")
            val latest = json.getString("version")?.trim().orEmpty()
            if (latest.isEmpty()) throw java.io.IOException("版本文件缺少 version 字段")
            val pageUrl = json.getString("url")?.trim().takeUnless { it.isNullOrEmpty() }
                ?: DOWNLOAD_PAGE_URL
            val changelog = try {
                json.getJSONArray("changelog")?.mapNotNull { it?.toString() } ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }

            if (!isNewer(latest, currentVersion)) {
                PluginFileLog.infoAndKeep("[更新检查] 当前已是最新版本（$currentVersion）")
                return
            }
            // 1.6.4：更新日志带颜色输出（§ 码）——Spigot 系控制台渲染为彩色；
            // NeoForge 控制台不支持 § 码，平台适配器 log_info 会自动剥离（文件日志同样剥离）。
            val lines = StringBuilder()
            lines.append("§e[更新检查] §f发现新版本：§a§l$latest §7（当前 $currentVersion）")
            lines.append("\n§e[更新检查] §f下载页面：§b$pageUrl")
            if (changelog.isEmpty()) {
                lines.append("\n§e[更新检查] §7更新内容：§f详见官网更新日志")
            } else {
                // 简要日志：最多 6 行，每行截断 60 字（防占满屏幕）
                for (item in changelog.take(6)) {
                    lines.append("\n§e[更新检查] §7- §f${item.take(60)}")
                }
                if (changelog.size > 6) {
                    lines.append("\n§e[更新检查] §7- ……其余 §f${changelog.size - 6} §7条见官网更新日志")
                }
            }
            PluginFileLog.infoAndKeep(lines.toString())
        } catch (error: Throwable) {
            // 失败：控制台一句话，细节进日志（网络问题不该刷屏）
            PluginFileLog.warnAndKeep(
                "[更新检查] 更新检测失败（服务器网络无法访问官网版本文件），不影响插件运行"
            )
            PluginFileLog.write(
                "[更新检查] 失败详情：${error.javaClass.name}: ${error.message}（地址 $VERSION_URL）"
            )
        }
    }

    /** 新版本判定：latest 逐段大于 current 即为更新。 */
    internal fun isNewer(latest: String, current: String): Boolean {
        val left = latest.split('.')
        val right = current.split('.')
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val a = left.getOrNull(i) ?: "0"
            val b = right.getOrNull(i) ?: "0"
            val aNum = a.toIntOrNull()
            val bNum = b.toIntOrNull()
            val cmp = when {
                aNum != null && bNum != null -> aNum.compareTo(bNum)
                else -> a.compareTo(b)
            }
            if (cmp != 0) return cmp > 0
        }
        return false
    }

    /** HTTP GET（带 UA，超时 [timeoutMillis]；异常向上抛由调用方分类处理）。 */
    private fun httpGet(url: String, timeoutMillis: Int): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.setRequestProperty(
                "User-Agent",
                "KERONGPenguin-UpdateChecker/${cn.huohuas001.bot.provider.BotShared.instance?.pluginVersion ?: "1.0"}"
            )
            val code = connection.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code")
            connection.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use {
                    it.readText().toByteArray(StandardCharsets.UTF_8)
                }
            }
        } finally {
            connection?.disconnect()
        }
    }
}
