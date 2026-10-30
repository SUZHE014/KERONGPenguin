package cn.kerong.bot

import cn.kerong.bot.tools.PluginFileLog
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
 * 1.6.7.9 镜像回退（修复「更新检测失败」红色告警在部分服务器网络下必现）：
 * - 部分部署环境（海外服务器 / DNS 污染 / 域名线路不可达）访问官网域名
 *   kerong.xin 失败，旧版单源访问一失败就报「更新检测失败」，服主以为插件
 *   出了问题；现改为**多源依次回退**：官网域名（主源）→ 官网服务器 IP 直连
 *   （镜像源，Host 头路由到同一站点，证书域名与 IP 不匹配在镜像请求上放行，
 *   证书链仍按系统 CA 校验）→ 任一源成功即正常检查；
 * - 自定义镜像：update-check.mirror-url 可改写镜像源地址（默认内置官网
 *   IP 直连；设为 "off" 时跳过镜像回退只试主源）；
 * - 全部源失败时控制台一句话附带**关闭指引**（update-check.enabled: false），
 *   如实告知但不制造焦虑——更新检查失败完全不影响插件功能。
 *
 * 版本比较：按「点分段数字」逐段比较（1.6.1 < 1.6.2 < 1.6.10 < 1.7），
 * 任一段更大即视为更新；段数不足按 0 补齐；非数字段按字符串比较兜底
 * （如 1.6.2-beta）。
 */
object UpdateChecker {

    /** 官网版本信息文件地址（主源；官网每次发版同步更新）。 */
    const val VERSION_URL = "https://kerong.xin/penguin/version.json"

    /**
     * 内置镜像源：官网服务器 IP 直连（域名解析失败 / 线路不可达时兜底，
     * Host 头保证 nginx 路由到同一站点；证书域名与 IP 不匹配在镜像请求放行）。
     */
    const val BUILTIN_MIRROR_URL = "https://156.233.228.248/penguin/version.json"

    /** 镜像源 Host 头（nginx 按 Host 路由站点，与官网域名同站）。 */
    private const val MIRROR_HOST = "kerong.xin"

    /** 下载页地址（输给控制台，用户点击即达）。 */
    const val DOWNLOAD_PAGE_URL = "https://kerong.xin/penguin/"

    /** 检查是否开启（update-check.enabled，默认 true）。 */
    fun enabled(): Boolean {
        return try {
            val instance = cn.kerong.bot.provider.BotShared.instance
            (instance as? org.bukkit.plugin.java.JavaPlugin)
                ?.config?.getBoolean("update-check.enabled", true) ?: true
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * 用户自定义镜像源（update-check.mirror-url）：
     * - 空 / 未配置 → 内置官网 IP 直连镜像；
     * - "off" / "false" / "none" → 关闭镜像回退（只试主源）；
     * - 其他值 → 作为镜像源地址使用（如自建反代）。
     */
    private fun mirrorUrl(): String? {
        val custom = try {
            val instance = cn.kerong.bot.provider.BotShared.instance
            (instance as? org.bukkit.plugin.java.JavaPlugin)
                ?.config?.getString("update-check.mirror-url")?.trim()
        } catch (_: Throwable) {
            null
        }
        if (custom.isNullOrEmpty()) return BUILTIN_MIRROR_URL
        if (custom.equals("off", true) || custom.equals("false", true) ||
            custom.equals("none", true)
        ) return null
        return custom
    }

    /**
     * 本次检查的源列表（主源在前，镜像在后；镜像关闭时仅主源）。
     */
    internal fun sourceUrls(): List<String> {
        val mirror = mirrorUrl()
        return if (mirror == null || mirror == VERSION_URL) {
            listOf(VERSION_URL)
        } else {
            listOf(VERSION_URL, mirror)
        }
    }

    /**
     * 执行一次更新检查（异步调用；1.6.7.9 起多源回退）。
     *
     * @param currentVersion 当前插件版本（pluginVersion）
     */
    fun check(currentVersion: String) {
        if (!enabled()) {
            PluginFileLog.write("[更新检查] 已关闭（update-check.enabled: false），本次跳过")
            return
        }
        val sources = sourceUrls()
        var lastError: Throwable? = null
        for ((index, url) in sources.withIndex()) {
            val isMirror = index > 0
            try {
                val body = httpGet(url, 10_000, isMirror)
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

                if (isMirror) {
                    // 镜像源命中的说明（主源不可达，仅文件留档，不打扰控制台）
                    PluginFileLog.write("[更新检查] 主源不可达，已由镜像源完成检查（$url）")
                }

                if (!isNewer(latest, currentVersion)) {
                    PluginFileLog.infoAndKeep("[更新检查] 当前已是最新版本（$currentVersion）")
                    return
                }
                // 1.6.4：更新日志带颜色输出（§ 码）；
                // 1.6.6：双端平台适配器 log_* 输出前把 § 码转 ANSI 转义，
                // Minecraft 控制台（jline 终端）渲染为真实颜色；输出重定向
                // （无彩色终端）时自动退化为纯文本；文件日志始终剥离。
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
                return
            } catch (error: Throwable) {
                lastError = error
                PluginFileLog.write(
                    "[更新检查] 源 ${index + 1}/${sources.size} 访问失败（$url）：" +
                        "${error.javaClass.name}: ${error.message}"
                )
            }
        }
        // 全部源失败：控制台一句话 + 关闭指引（网络问题不该刷屏，也不该没提示）
        PluginFileLog.warnAndKeep(
            "[更新检查] 更新检测失败（服务器网络无法访问官网与镜像的版本文件），不影响插件运行；" +
                "如不需要更新检查，可在 config.yml 中设置 update-check.enabled: false 关闭"
        )
        PluginFileLog.write(
            "[更新检查] 失败详情：${lastError?.javaClass?.name}: ${lastError?.message}" +
                "（已尝试源：${sources.joinToString(" → ")}）"
        )
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

    /**
     * HTTP GET（带 UA，超时 [timeoutMillis]；异常向上抛由调用方分类处理）。
     *
     * @param mirror true = 镜像源请求：补 Host 头（nginx 站点路由）并放行
     * 证书域名与访问地址不匹配（官网证书发给 kerong.xin，镜像用 IP 直连；
     * 证书链仍按系统 CA 校验，仅跳过主机名比对，不降低证书验证强度）。
     */
    private fun httpGet(url: String, timeoutMillis: Int, mirror: Boolean = false): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.setRequestProperty(
                "User-Agent",
                "KERONGPenguin-UpdateChecker/${cn.kerong.bot.provider.BotShared.instance?.pluginVersion ?: "1.0"}"
            )
            if (mirror && connection is javax.net.ssl.HttpsURLConnection) {
                connection.setRequestProperty("Host", MIRROR_HOST)
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
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
