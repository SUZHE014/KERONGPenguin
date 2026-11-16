package cn.kerong.penguin.spigot.qqbind

import cn.kerong.bot.tools.PluginFileLog
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 网页爬取能力（正式功能）：AI 需要了解网页内容时（回复中输出
 * 「[GET] 网址」标记），由本类抓取网页并提取正文文本回传给 AI。
 *
 * 多次多层读取（1.7.6.3）：AI 可在一轮回答里多次输出 [GET] 连续
 * 读多个页面，也可以先读入口页 / 搜索结果页、再继续读页面里的
 * 链接逐层深入——正文提取时超链接以「文字（网址）」形式保留在
 * 文本里，模型能看到下一层链接继续请求（总次数受 ai.crawler.max-pages
 * 约束）；需要搜索时模型用必应（bing.com）搜索页作为入口。
 *
 * 安全约束（防 SSRF / 防滥用）：
 * - 仅允许 http/https 协议，仅允许公网地址——回环 / 内网 / 链路本地 /
 *   唯一本地地址全部拒绝（含 IPv6 对应段）；
 * - 重定向手动跟随（最多 3 次），每次重新校验目标地址；
 * - 响应体最多读取 2MB；仅接受文本类内容（text 开头、json、xml），
 *   图片 / 二进制直接拒绝；
 * - 提取文本按固定上限截断（[DEFAULT_MAX_CHARS]，1.7.6.3 起
 *   不再走配置调优键）；
 * - 网址中的非 ASCII 字符（如必应搜索的中文关键词）与空格自动
 *   百分号编码后再请求（见 [normalizeUrl]）。
 *
 * 失败口径：所有失败（HTTP 非 2xx / 内容类型不支持 / 响应为空 /
 * 网络 / 解析异常）统一返回 [READ_FAILURE_MESSAGE]——不携带 HTTP
 * 状态码、异常信息等技术细节，避免模型把「访问代码」原样复述给
 * 用户；技术原因只写文件日志（PluginFileLog）供服主排查。
 */
object WebCrawler {

    /** 单页响应体最大读取字节数。 */
    private const val MAX_BYTES = 2 * 1024 * 1024

    /** 最大重定向跟随次数。 */
    private const val MAX_REDIRECTS = 3

    /** 抓取超时秒数（1.7.6.3 起固定值，原配置调优键已删）。 */
    const val DEFAULT_TIMEOUT_SECONDS = 10

    /** 单页正文提取字符上限（1.7.6.3 起固定值，原配置调优键已删）。 */
    const val DEFAULT_MAX_CHARS = 6000

    /**
     * 统一失败文案（不携带状态码 / 异常细节，防止模型向用户输出访问代码）。
     * 回传给模型后由 [AiChat] 附带自然语言引导。
     */
    const val READ_FAILURE_MESSAGE = "[读取失败：网页暂时无法访问或没有返回内容]"

    private val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) KERONGPenguin-Bot/1.7.6.3"

    private val ACCEPT = "text/html,text/plain,application/json,application/xml;q=0.9,*/*;q=0.5"

    /** 允许读取的内容类型前缀（其余按二进制拒绝）。 */
    private val ALLOWED_CONTENT_TYPES = listOf(
        "text/", "application/json", "application/xml", "application/xhtml", "+xml", "text"
    )

    /**
     * 抓取网页并提取正文文本。
     * @return 提取的文本；失败时返回统一失败文案 [READ_FAILURE_MESSAGE]
     *（技术原因写文件日志，不回传给模型）
     */
    fun fetch(urlText: String, timeoutSeconds: Int, maxChars: Int): String {
        var connection: HttpURLConnection? = null
        return try {
            var currentUrl = parseAndValidate(urlText)
            var redirects = 0
            var conn = openConnection(currentUrl, timeoutSeconds)
            connection = conn
            var code = conn.responseCode
            // 手动跟随重定向：每次重新做协议与公网校验，最多 3 次
            while (code in 300..399 && redirects < MAX_REDIRECTS) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    PluginFileLog.write("[爬虫] 读取失败 $urlText：HTTP $code 重定向缺少目标地址")
                    return READ_FAILURE_MESSAGE
                }
                currentUrl = parseAndValidate(URL(currentUrl, location).toExternalForm())
                redirects++
                conn = openConnection(currentUrl, timeoutSeconds)
                connection = conn
                code = conn.responseCode
            }
            if (code !in 200..299) {
                PluginFileLog.write("[爬虫] 读取失败 $urlText：HTTP $code")
                return READ_FAILURE_MESSAGE
            }
            val contentType = conn.contentType ?: ""
            if (!isAllowedContentType(contentType)) {
                PluginFileLog.write("[爬虫] 读取失败 $urlText：内容类型 ${contentType.substringBefore(';')} 不支持读取（仅支持网页/文本类内容）")
                return READ_FAILURE_MESSAGE
            }
            val charset = parseCharset(contentType) ?: "UTF-8"
            val raw = readBounded(conn, charset)
            if (raw == null) {
                PluginFileLog.write("[爬虫] 读取失败 $urlText：响应为空")
                return READ_FAILURE_MESSAGE
            }
            if (raw.isBlank()) {
                PluginFileLog.write("[爬虫] 读取失败 $urlText：响应无正文内容")
                return READ_FAILURE_MESSAGE
            }
            if (contentType.contains("html", ignoreCase = true) || raw.trimStart().startsWith("<")) {
                // 以最终落点 URL 为基准解析相对链接（多层爬取：模型靠链接继续深入）
                extractText(raw, maxChars, currentUrl.toExternalForm())
            } else {
                capChars(raw.trim(), maxChars)
            }
        } catch (t: Throwable) {
            PluginFileLog.write("[爬虫] 读取失败 $urlText：${t.message ?: t.toString()}")
            READ_FAILURE_MESSAGE
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 从 HTML 提取可读正文（jsoup）：
     * 去掉 script/style/noscript/svg/iframe/head 等无正文节点后取文本；
     * 带 baseUrl 时超链接以「文字（网址）」形式保留在文本里——模型
     * 看得到下一层链接，才能多层深入继续 [GET]（搜索结果页 → 具体页面）。
     */
    fun extractText(html: String, maxChars: Int, baseUrl: String = ""): String {
        return try {
            val doc = Jsoup.parse(html)
            if (baseUrl.isNotBlank()) {
                doc.setBaseUri(baseUrl)
                // 只保留 http/https 链接（相对链接经 abs:href 解析为绝对地址）
                for (anchor in doc.select("a[href]")) {
                    val href = anchor.attr("abs:href")
                    if (href.startsWith("http://") || href.startsWith("https://")) {
                        val label = anchor.text().trim()
                        if (label.isNotEmpty()) {
                            anchor.text("$label（$href）")
                        }
                    }
                }
            }
            doc.select("script,style,noscript,svg,iframe,head,nav,footer,template").remove()
            val text = doc.body()?.text() ?: doc.text()
            capChars(text.trim(), maxChars)
        } catch (_: Throwable) {
            capChars(html.trim(), maxChars)
        }
    }

    /**
     * 网址归一（纯函数，冒烟直接验证）：把网址里的非 ASCII 字符与空格
     * 百分号编码后再请求——模型常输出带中文关键词的必应搜索网址
     * （https://www.bing.com/search?q=我的世界），Java HttpURLConnection
     * 不做自动编码，原始字符直接写请求行会被服务器拒绝。
     *
     * 规则：ASCII 可打印字符（含已编码的 %XX、保留字、中文之外的
     * 全角符号以外的部分）原样保留；空格与一切非 ASCII 字符按
     * UTF-8 百分号编码。
     */
    fun normalizeUrl(urlText: String): String {
        if (urlText.none { it.code > 0x7E || it == ' ' }) return urlText
        val sb = StringBuilder(urlText.length + 16)
        for (ch in urlText) {
            when {
                ch == ' ' -> sb.append("%20")
                ch.code in 0x21..0x7E -> sb.append(ch)
                else -> sb.append(java.net.URLEncoder.encode(ch.toString(), "UTF-8"))
            }
        }
        return sb.toString()
    }

    /** 校验地址是否为允许访问的公网地址（防 SSRF，冒烟直接调用）。 */
    fun isAllowedAddress(address: InetAddress): Boolean {
        return try {
            !address.isAnyLocalAddress &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress &&
                !address.isMulticastAddress &&
                !isUniqueLocalIpv6(address) &&
                !isPrivateIpv4(address)
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- 内部实现 ----------

    /** 解析并校验：先做非 ASCII 百分号编码，再校验协议 http/https + 主机解析出的全部地址均为公网。 */
    private fun parseAndValidate(urlText: String): URL {
        val url = URL(normalizeUrl(urlText.trim()))
        if (url.protocol != "http" && url.protocol != "https") {
            throw IllegalArgumentException("仅支持 http/https 网址")
        }
        val host = url.host ?: throw IllegalArgumentException("网址缺少主机名")
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".internal")) {
            throw IllegalArgumentException("不允许访问本机/内网地址")
        }
        val addresses = InetAddress.getAllByName(host)
        if (addresses.none { isAllowedAddress(it) }) {
            throw IllegalArgumentException("不允许访问内网地址（$host）")
        }
        return url
    }

    private fun openConnection(url: URL, timeoutSeconds: Int): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutSeconds * 1000
        connection.readTimeout = timeoutSeconds * 1000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", ACCEPT)
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        return connection
    }

    /** 限长读取响应体（最多 [MAX_BYTES] 字节）。 */
    private fun readBounded(connection: HttpURLConnection, charset: String): String? {
        val stream = connection.inputStream ?: return null
        stream.use { s ->
            BufferedReader(InputStreamReader(s, charset), 64 * 1024).use { reader ->
                val sb = StringBuilder()
                val buffer = CharArray(16 * 1024)
                var total = 0
                while (total < MAX_BYTES) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    total += n
                    sb.append(buffer, 0, n)
                }
                return sb.toString()
            }
        }
    }

    private fun isAllowedContentType(contentType: String): Boolean {
        val type = contentType.substringBefore(';').trim().lowercase()
        if (type.isEmpty()) return true // 无类型头按文本尝试
        return ALLOWED_CONTENT_TYPES.any { type.startsWith(it) || type.contains(it) }
    }

    private fun parseCharset(contentType: String): String? = try {
        Regex("charset=([A-Za-z0-9_\\-]+)").find(contentType)?.groupValues?.get(1)
    } catch (_: Throwable) {
        null
    }

    /** 截断到指定字符数（超出加省略说明）。 */
    private fun capChars(text: String, maxChars: Int): String {
        return if (text.length <= maxChars) text
        else text.substring(0, maxChars) + "……（内容过长，已截断）"
    }

    /** IPv4 私有段（10/8、172.16/12、192.168/16、100.64/10 CGN）。 */
    private fun isPrivateIpv4(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return when {
            b0 == 10 -> true
            b0 == 172 && b1 in 16..31 -> true
            b0 == 192 && b1 == 168 -> true
            b0 == 100 && b1 in 64..127 -> true
            b0 == 0 -> true
            else -> false
        }
    }

    /** IPv6 唯一本地地址（fc00::/7）。 */
    private fun isUniqueLocalIpv6(address: InetAddress): Boolean {
        val bytes = address.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    }
}
