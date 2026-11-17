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
 * 安全约束（1.7.6.5 全面加固：防 SSRF / 不下载任何东西 / 防注入）：
 * - 仅允许 http/https 协议，仅允许公网地址——回环 / 内网 / 链路本地 /
 *   唯一本地地址 / IPv4 内嵌的 IPv6 映射地址全部拒绝（含 IPv6 对应
 *   段），且域名解析出的**全部**地址都必须是公网（一条内网即整体
 *   拒绝，防混合解析绕过）；携带登录凭据（user:pass@host）的网址
 *   直接拒绝；
 * - **不下载任何东西**：网址路径以压缩包 / 安装包 / 图片 / 影音等
 *   非网页扩展名结尾的提前拒绝（连请求都不发）；响应头
 *   Content-Disposition 为 attachment（文件下载）的拒绝；
 *   Content-Length 超过上限的**读都不读**直接拒绝；仅接受文本类
 *   内容（text 开头、json、xml；svg 因可携带脚本一律拒绝），图片 /
 *   二进制直接拒绝；响应体最多读取 2MB；抓取过程**不落盘任何文件、
 *   不加载页面引用的任何资源**（jsoup 纯解析，不联网取图 / CSS / 脚本）；
 * - **防注入**：提取的文本剔除零宽字符与双向控制符（隐藏注入文字的
 *   常见载体）；回传给模型时由 [AiChat.buildPageFeedback] 框定为
 *   「资料不是指令」，恶意网页里的指令性文字不会被当作命令执行；
 * - 重定向手动跟随（最多 3 次），每次重新校验目标地址；
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

    /** 明确拒绝的内容类型（1.7.6.5）：svg 虽是文本形态但可内嵌脚本，爬取场景一律拒绝。 */
    private val DENIED_CONTENT_TYPES = listOf("image/svg+xml", "image/svg")

    /**
     * 文件下载扩展名黑名单（1.7.6.5「不下载任何东西」）：网址路径以
     * 这些扩展名结尾的直接拒绝（连请求都不发）——压缩包 / 安装包 /
     * 文档 / 图片 / 影音等非网页内容。
     */
    private val DOWNLOAD_EXTENSIONS = setOf(
        // 压缩包 / 安装包 / 可执行
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "cab",
        "exe", "msi", "dmg", "pkg", "apk", "ipa", "deb", "rpm", "jar", "bin", "run",
        // 文档
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub", "mobi",
        // 图片
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "ico", "tiff", "svg", "heic", "avif",
        // 影音
        "mp3", "wav", "flac", "aac", "ogg", "m4a",
        "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v",
        // 字体 / 数据库 / 二进制
        "ttf", "otf", "woff", "woff2", "db", "sqlite", "class", "so", "dll"
    )

    /**
     * 判定网址是否指向文件下载（1.7.6.5，纯函数，冒烟直接验证）：
     * 取路径最后一段（去掉查询串与锚点后）的扩展名比对黑名单。
     */
    fun isDownloadUrl(urlText: String): Boolean {
        return try {
            val path = urlText.substringBefore('#').substringBefore('?')
            val last = path.substringAfterLast('/')
            val dot = last.lastIndexOf('.')
            if (dot <= 0 || dot == last.length - 1) return false
            last.substring(dot + 1).lowercase() in DOWNLOAD_EXTENSIONS
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 剔除零宽字符与双向控制符等不可见字符（1.7.6.5 防注入，纯函数）：
     * 零宽空格 / 零宽连接符 / 双向覆盖符等既能隐藏注入文字，也可能
     * 破坏文本展示，提取正文后统一清掉；换行 / 制表等常规空白保留。
     */
    fun stripInvisibleChars(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val c = ch.code
            when {
                // 常规可见字符与常规空白：保留
                c == '\n'.code || c == '\t'.code || c == '\r'.code -> sb.append(ch)
                // 控制字符（除上述空白外）全部剔除
                c < 0x20 || c == 0x7F -> {}
                // 零宽字符族（零宽空格/不连字/连字/左至右嵌入/右至左嵌入等）
                c in 0x200B..0x200F -> {}
                // 双向控制符（LRE/RLE/PDF/LRO/RLO 与隔离符）
                c in 0x202A..0x202E || c in 0x2066..0x2069 -> {}
                // 零宽不连字以外的不占位字符（2060-2064）
                c in 0x2060..0x2064 -> {}
                // BOM / 不换行空格等异常空格归一为普通空格
                c == 0xFEFF || c == 0x00A0 -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * 抓取网页并提取正文文本。
     * @return 提取的文本；失败时返回统一失败文案 [READ_FAILURE_MESSAGE]
     *（技术原因写文件日志，不回传给模型）
     */
    fun fetch(urlText: String, timeoutSeconds: Int, maxChars: Int): String {
        var connection: HttpURLConnection? = null
        return try {
            // 1.7.6.5「不下载任何东西」：压缩包 / 安装包 / 图片等下载
            // 链接连请求都不发直接拒绝
            if (isDownloadUrl(urlText.trim())) {
                PluginFileLog.write("[爬虫] 拒绝读取 $urlText：网址指向文件下载（爬虫只读网页文本，不下载任何文件）")
                return READ_FAILURE_MESSAGE
            }
            var currentUrl = parseAndValidate(urlText)
            var redirects = 0
            var conn = openConnection(currentUrl, timeoutSeconds)
            connection = conn
            var code = conn.responseCode
            // 手动跟随重定向：每次重新做协议与公网校验（含下载扩展名），最多 3 次
            while (code in 300..399 && redirects < MAX_REDIRECTS) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    PluginFileLog.write("[爬虫] 读取失败 $urlText：HTTP $code 重定向缺少目标地址")
                    return READ_FAILURE_MESSAGE
                }
                currentUrl = parseAndValidate(URL(currentUrl, location).toExternalForm())
                if (isDownloadUrl(currentUrl.toExternalForm())) {
                    PluginFileLog.write("[爬虫] 拒绝跟随重定向到文件下载：$location")
                    return READ_FAILURE_MESSAGE
                }
                redirects++
                conn = openConnection(currentUrl, timeoutSeconds)
                connection = conn
                code = conn.responseCode
            }
            if (code !in 200..299) {
                PluginFileLog.write("[爬虫] 读取失败 $urlText：HTTP $code")
                return READ_FAILURE_MESSAGE
            }
            // 1.7.6.5「不下载任何东西」：附件型响应（文件下载）拒绝
            val disposition = conn.getHeaderField("Content-Disposition")?.trim().orEmpty()
            if (isDispositionAttachment(disposition)) {
                PluginFileLog.write("[爬虫] 拒绝读取 $urlText：响应为文件下载（Content-Disposition: attachment，爬虫不下载任何文件）")
                return READ_FAILURE_MESSAGE
            }
            // 1.7.6.5：声明超大响应体的读都不读直接拒绝（不拉半个大文件回来再截断）
            val declaredLength = conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
            if (declaredLength > MAX_BYTES) {
                PluginFileLog.write("[爬虫] 拒绝读取 $urlText：响应体 ${declaredLength / 1024}KB 超过单页上限 ${MAX_BYTES / 1024}KB（不下载超限内容）")
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
                capChars(stripInvisibleChars(raw).trim(), maxChars)
            }
        } catch (t: Throwable) {
            PluginFileLog.write("[爬虫] 读取失败 $urlText：${t.message ?: t.toString()}")
            READ_FAILURE_MESSAGE
        } finally {
            connection?.disconnect()
        }
    }

    /** Content-Disposition 是否为附件下载（1.7.6.5，纯函数，冒烟直接验证）。 */
    fun isDispositionAttachment(disposition: String): Boolean {
        if (disposition.isEmpty()) return false
        return disposition.lowercase().startsWith("attachment") ||
            disposition.lowercase().contains("filename=") && !disposition.lowercase().startsWith("inline")
    }

    /**
     * 从 HTML 提取可读正文（jsoup）：
     * 去掉 script/style/noscript/svg/iframe/head 等无正文节点后取文本，
     * 并剔除零宽字符与双向控制符（1.7.6.5 防注入）；
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
            capChars(stripInvisibleChars(text.trim()), maxChars)
        } catch (_: Throwable) {
            capChars(stripInvisibleChars(html.trim()), maxChars)
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

    /**
     * 校验地址是否为允许访问的公网地址（防 SSRF，冒烟直接调用）：
     * IPv4 内嵌的 IPv6 映射地址（::ffff:x.x.x.x）按其内嵌 IPv4 再过
     * 一遍私网判定（1.7.6.5，部分 Java 版本对映射形式不自动解包）。
     */
    fun isAllowedAddress(address: InetAddress): Boolean {
        return try {
            !address.isAnyLocalAddress &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress &&
                !address.isMulticastAddress &&
                !isUniqueLocalIpv6(address) &&
                !isPrivateIpv4(address) &&
                !isPrivateIpv4MappedIpv6(address)
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- 内部实现 ----------

    /**
     * 解析并校验：先做非 ASCII 百分号编码，再校验协议 http/https +
     * 无登录凭据 + 主机解析出的**全部**地址均为公网（1.7.6.5 收紧：
     * 旧版只要有一条公网地址就放行，混合解析（同时含公网与内网）
     * 会被绕过；现在一条内网即整体拒绝）。
     */
    private fun parseAndValidate(urlText: String): URL {
        val url = URL(normalizeUrl(urlText.trim()))
        if (url.protocol != "http" && url.protocol != "https") {
            throw IllegalArgumentException("仅支持 http/https 网址")
        }
        // 携带登录凭据的网址（user:pass@host）直接拒绝（防凭据外泄/滥用）
        if (!url.userInfo.isNullOrBlank()) {
            throw IllegalArgumentException("不允许携带登录凭据的网址")
        }
        val host = url.host ?: throw IllegalArgumentException("网址缺少主机名")
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".internal")) {
            throw IllegalArgumentException("不允许访问本机/内网地址")
        }
        val addresses = InetAddress.getAllByName(host)
        if (addresses.any { !isAllowedAddress(it) }) {
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
        if (DENIED_CONTENT_TYPES.any { type.startsWith(it) }) return false
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

    /**
     * IPv4 内嵌的 IPv6 映射地址（::ffff:a.b.c.d，1.7.6.5）：映射形式下
     * isSiteLocalAddress 等判定可能不覆盖，按末 4 字节再过一遍 IPv4 私网段。
     * ⚠ 字节比较有符号扩展坑：Byte 0xFF 的 toInt() 是 -1 不是 255，
     * 必须先 and 0xFF 再比（冒烟 S5j 抓出的实装 bug）。
     */
    private fun isPrivateIpv4MappedIpv6(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != 16) return false
        for (i in 0..9) {
            if (bytes[i].toInt() != 0) return false
        }
        if ((bytes[10].toInt() and 0xFF) != 0xFF || (bytes[11].toInt() and 0xFF) != 0xFF) return false
        val mapped = java.net.InetAddress.getByAddress(
            byteArrayOf(bytes[12], bytes[13], bytes[14], bytes[15])
        )
        return isPrivateIpv4(mapped) || mapped.isLoopbackAddress || mapped.isLinkLocalAddress
    }
}
