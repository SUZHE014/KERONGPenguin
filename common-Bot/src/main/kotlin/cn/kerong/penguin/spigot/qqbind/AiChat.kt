package cn.kerong.penguin.spigot.qqbind

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import cn.kerong.bot.tools.PluginFileLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.io.File

/**
 * QQ 群 / 游戏内 AI 对话（@机器人触发，OpenAI 兼容接口）。
 *
 * 在原有 API 对话基础上并入三大能力：
 * - **表情包**：回复中「[表情] 文件名或关键词」标记解析为插件目录表情包
 *   文件夹里的图片（含动态 GIF，字节直传不重编码），由群聊发送层
 *   [GroupMessageHandler] 以图片消息逐张送达（[chatWithOutcome]）；
 * - **网页读取（正式功能）**：模型回复中出现「[GET] 网址」标记时自动
 *   抓取网页、把内容回传给模型继续生成——支持多次多层读取（先读
 *   入口页/搜索页，再继续读页面里的链接逐层深入；需要搜索时用必应
 *   bing.com），防 SSRF 与体积约束见 [WebCrawler]；默认开启
 *   （ai.crawler.enabled，读取次数上限 ai.crawler.max-pages）；
 *   读取失败时回传统一失败文案与自然语言引导，
 *   模型不会把错误码 / 技术原因复述给用户；
 * - **提示词文件**：角色设定与专属知识写在插件目录的单个 .md 文件里
 *   （默认 提示词.md，见 [AiPromptFile]），整份内容作为 system prompt；
 * - DeepSeek-R1 类推理模型输出中的思考段（think 标签）会被剥离。
 *
 * 对外双入口：
 * - [chat]：返回纯文本（游戏内 / 兼容旧调用点，标记已剥离）；
 * - [chatWithOutcome]：返回 [Outcome]（群聊用，含表情文件与抓取计数）。
 */
object AiChat {

    /** 模型请求读取网页的标记（见系统提示词的协议约定）。 */
    private const val GET_MARKER = "[GET]"

    /** 每条 AI 回复最多发送几张表情包（1.7.6.3 起固定值，原配置调优键已删）。 */
    const val MEME_MAX_PER_REPLY = 1

    /** 解析回复中的 [GET] 网址（http(s) 开头，遇空白或常见收尾标点截止）。 */
    private val GET_URL_REGEX = Regex("""\[GET]\s*(https?://[^\s【】"'<>]+)""")

    /** 表情包标记（[表情] 开心 / [表情:鼓掌] / [表情：鼓掌]），内容为文件名或关键词；结尾可选闭括号一并剥离。 */
    val MEME_MARKER_REGEX = Regex("""\[表情(\]\s*|\s*[:：]\s*)([^\[\]\n\s]{1,40})\]?""")

    /** 发给 QQ 的单条回复软上限（超长截断，避免刷屏）。 */
    private const val REPLY_MAX_CHARS = 2000

    // DeepSeek-R1 思考段标签（字面量尖括号，避免转义序列被工具链损坏）
    private const val THINK_OPEN = "<" + "think" + ">"
    private const val THINK_CLOSE = "</" + "think" + ">"

    /**
     * 一次对话的完整产出（1.7.6.2）：
     * @param text         发给用户的文字（标记已剥离、已截断）
     * @param memeFiles    AI 指定发送的表情包文件（已匹配，可能为空）
     * @param fetchedPages 本次实际抓取的网页数
     */
    class Outcome(
        val text: String,
        val memeFiles: List<File>,
        val fetchedPages: Int,
    ) {
        companion object {
            fun plain(text: String) = Outcome(text, emptyList(), 0)
        }
    }

    // ---------- 对外入口 ----------

    /**
     * 执行一次 AI 对话（兼容入口，游戏内 / 旧调用点）。
     * 返回纯文本：标记已剥离、思考段已剥离。
     */
    fun chat(userMessage: String, manager: QqBindManager?, groupId: String?, userId: String?): String {
        return chatWithOutcome(userMessage, manager, groupId, userId).text
    }

    /**
     * 执行一次 AI 对话（群聊入口）：
     * 在原有链路（配置校验 → system prompt 组装 → 上下文注入 → 请求）
     * 上并入 [GET] 网页阅读循环与 [表情] 标记解析。
     */
    fun chatWithOutcome(userMessage: String, manager: QqBindManager?, groupId: String?, userId: String?): Outcome {
        if (manager == null) return Outcome.plain("AI 对话未就绪。")
        if (!manager.isAiEnabled) return Outcome.plain("AI 对话未开启。")
        val baseUrl = manager.aiBaseUrl()
        val apiKey = manager.aiApiKey()
        val model = manager.aiModel()
        // system prompt = 提示词文件（角色设定 + 专属知识）+ 艾特接口附注 + 动态能力段
        var systemPrompt = manager.aiSystemPrompt()
        if (manager.isAiAtEnabled) {
            systemPrompt += AiAt.systemPromptSuffix()
        }
        systemPrompt += capabilitySection(manager)
        if (baseUrl.isEmpty() || apiKey.isEmpty()) return Outcome.plain("AI 对话未配置。")

        val contextEnabled = manager.isAiContextEnabled(groupId, userId)
        QqBindManager.logVerbose("[AI对话] 输入: $userMessage 上下文=$contextEnabled")

        var url = baseUrl.trimEnd('/')
        if (manager.isAiUrlAutoAppend && !url.endsWith("/chat/completions")) {
            url += "/chat/completions"
        }

        return try {
            val working = ArrayList<JSONObject>()
            working.add(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            if (contextEnabled) {
                val history = manager.getAiContext(groupId, userId)
                if (history != null) {
                    for (message in history) {
                        working.add(JSONObject().apply {
                            put("role", message.role)
                            put("content", message.content)
                        })
                    }
                }
            }
            working.add(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })

            // [GET] 网页阅读循环（1.7.6.2）：模型请求读网页时抓取回传继续生成
            val conversation = runConversation(url, apiKey, model, manager, working)
            val reply = conversation.first
            val fetchedPages = conversation.second

            val result = (reply?.trim() ?: "（AI 未返回内容）")
            val stored = result
            if (contextEnabled) {
                manager.appendAiContext(groupId, userId, userMessage, stored)
            }
            // [表情] 标记解析（群聊发送层发图；游戏内调用点只拿剥离后的文本）
            val memeFiles = resolveMemeFiles(manager, result)
            Outcome(
                text = capReply(stripReplyMarkers(stripThink(result))),
                memeFiles = memeFiles,
                fetchedPages = fetchedPages,
            )
        } catch (t: Throwable) {
            Outcome.plain("AI 调用失败：${t.message}")
        }
    }

    // ---------- 对话循环（[GET] 网页阅读） ----------

    /**
     * 完整对话循环：
     * 1. 组装消息调用接口；
     * 2. 回复含 [GET] 网址且爬取开启未达上限 → 抓取网页、以 user 角色回传，
     *    继续下一轮生成；
     * 3. 无标记 / 爬取关闭 / 达上限 → 返回最终回复（残留标记剥离）。
     * 中间轮不写入持久上下文。
     * @return Pair(最终回复, 实际抓取页数)
     */
    private fun runConversation(
        url: String,
        apiKey: String,
        model: String,
        manager: QqBindManager?,
        working: MutableList<JSONObject>,
    ): Pair<String?, Int> {
        val crawlerEnabled = manager?.isAiCrawlerEnabled == true
        val maxFetch = if (crawlerEnabled) (manager.aiCrawlerMaxPages()).coerceAtLeast(0) else 0
        var fetched = 0
        // 轮次护栏：每抓一页至少多一轮生成，加余量防异常循环
        val maxRounds = maxFetch + 3
        var reply: String? = null

        var round = 0
        while (round < maxRounds) {
            round++
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray(working.toList()))
                put("temperature", 0.7)
            }
            QqBindManager.logVerbose("[AI对话] 请求 URL=$url 消息数=${body.getJSONArray("messages").size}")
            val response = doPost(url, apiKey, body.toJSONString())
            QqBindManager.logVerbose(
                "[AI对话] 响应: " + (if (response.length > 300) response.substring(0, 300) + "..." else response)
            )
            val json = JSON.parseObject(response)
                ?: return Pair(stripGetMarkers(reply ?: "").ifBlank { null }, fetched)
            val choices: JSONArray? = json.getJSONArray("choices")
            if (choices == null || choices.isEmpty()) {
                val error = json.getString("error")
                    ?: json.getJSONObject("error")?.getString("message")
                throw IllegalStateException("AI 返回无 choices" + (if (error != null) ": $error" else ""))
            }
            reply = choices.getJSONObject(0).getJSONObject("message")?.getString("content")?.trim()
            if (reply.isNullOrEmpty()) {
                throw IllegalStateException("AI 返回的回复内容为空")
            }
            val urls = parseGetUrls(reply)
            // 无标记：最终回复
            if (urls.isEmpty()) {
                return Pair(reply, fetched)
            }
            // 爬取关闭 / 已达上限：剥离残留标记，避免把协议标记发给用户
            if (maxFetch <= 0 || fetched >= maxFetch) {
                val cleaned = stripGetMarkers(reply)
                return Pair(
                    cleaned.ifBlank { "（网页读取功能未开启或已达上限，本次未能读取网页）" },
                    fetched
                )
            }
            // 模型请求读网页：记录其回复，抓取后把内容回传
            working.add(JSONObject().apply { put("role", "assistant"); put("content", reply) })
            val take = urls.take(maxFetch - fetched)
            val page = StringBuilder()
            for (targetUrl in take) {
                fetched++
                PluginFileLog.write("[AI对话] 网页读取（${fetched}/$maxFetch）: $targetUrl")
                page.append(
                    buildPageFeedback(
                        targetUrl,
                        WebCrawler.fetch(
                            targetUrl,
                            WebCrawler.DEFAULT_TIMEOUT_SECONDS,
                            WebCrawler.DEFAULT_MAX_CHARS,
                        )
                    )
                ).append("\n\n")
            }
            working.add(JSONObject().apply { put("role", "user"); put("content", page.toString().trim()) })
        }
        // 超出轮次护栏：按最后一轮回复返回（剥离残留标记）
        return Pair(stripGetMarkers(reply ?: "").ifBlank { null }, fetched)
    }

    // ---------- 动态能力段 ----------

    /**
     * 动态能力段（拼在提示词文件之后）：协议标记说明。
     * - 表情包：ai.memes.enabled 且文件夹有图时注入（含可用列表）；
     * - 读网页（正式功能，默认开）：ai.crawler.enabled 开启时注入，
     *   含多次多层读取与必应搜索指引；关闭时不注入，模型不会输出 [GET]。
     */
    fun capabilitySection(manager: QqBindManager?): String {
        if (manager == null) return ""
        val sb = StringBuilder()
        sb.append("\n\n## 系统能力（以下标记由平台自动处理，不会显示给用户；按需使用，不用刻意使用）")
        // 表情包
        if (manager.isAiMemesEnabled) {
            val dir = manager.memesFolder()
            AiMemeLibrary.ensureFolder(dir)
            val memeSection = AiMemeLibrary.renderPromptSection(dir)
            if (memeSection.isNotEmpty()) {
                sb.append("\n- 发表情包：气氛合适想发表情时，在回复末尾加 [表情] 文件名")
                sb.append("（每次回复最多 ").append(MEME_MAX_PER_REPLY).append(" 张）\n")
                sb.append(memeSection)
            }
        }
        // 网页读取（正式功能，默认开；多次多层 + 必应搜索）
        if (manager.isAiCrawlerEnabled) {
            sb.append("\n- 读网页：需要先看某个网页或搜索资料才能回答时，输出一行 [GET] 网址")
            sb.append("（一次回答也可以多行读多个网页，插件会把网页内容回传给你继续回答）")
            sb.append("（每次回答最多读 ").append(manager.aiCrawlerMaxPages()).append(" 页，能直接回答就不读；")
            sb.append("需要搜索时用必应搜索引擎，如 [GET] https://www.bing.com/search?q=关键词；")
            sb.append("回传的网页内容里带有链接（文字（网址）形式），需要深入时可以继续 [GET] 这些链接逐层阅读；")
            sb.append("仅 http/https 公网页，内网地址会被拒绝；")
            sb.append("网页读取失败时用自然语言告诉用户网页暂时打不开即可，不要输出错误码或技术原因）")
        }
        return sb.toString()
    }

    // ---------- 标记解析（纯函数，冒烟直接验证） ----------

    /**
     * 网页读取结果回传给模型的反馈文本（纯函数，冒烟直接验证）：
     * 读取成功 → 网址 + 正文；读取失败 → 统一失败文案 + 自然语言引导
     *（不携带错误码 / 技术细节，避免模型把访问代码复述给用户）。
     */
    fun buildPageFeedback(targetUrl: String, fetched: String): String {
        return if (fetched == WebCrawler.READ_FAILURE_MESSAGE) {
            "[网页内容] $targetUrl\n$fetched\n" +
                "（这个网页没读取成功：请直接用自然语言告诉用户网页暂时打不开，" +
                "不要输出错误代码或技术原因，也可以基于已有知识回答）"
        } else {
            "[网页内容] $targetUrl\n$fetched"
        }
    }

    /** 解析回复中全部 [GET] 网址（去重、保序）。 */
    fun parseGetUrls(text: String): List<String> {
        return GET_URL_REGEX.findAll(text)
            .map { it.groupValues[1].trimEnd(',', '.', '，', '。', ')', '）', ';', '；') }
            .distinct()
            .toList()
    }

    /** 剥离回复中残留的 [GET] 标记行（达抓取上限时兜底展示）。 */
    fun stripGetMarkers(text: String): String {
        if (!text.contains(GET_MARKER)) return text
        return text.lineSequence()
            .filter { line -> !line.trim().startsWith(GET_MARKER) }
            .joinToString("\n")
            .trim()
    }

    /** 解析回复中的表情包关键词（去重、保序、限量）。 */
    fun parseMemeKeywords(text: String, max: Int): List<String> {
        if (!text.contains("[表情")) return emptyList()
        return MEME_MARKER_REGEX.findAll(text)
            .map { it.groupValues[2].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(max.coerceAtLeast(1))
            .toList()
    }

    /**
     * 解析并落图：把 [表情] 关键词解析为表情包文件夹里的图片文件。
     * @return 待发送的图片文件列表（去重、限量；无匹配返回空）
     */
    fun resolveMemeFiles(manager: QqBindManager?, reply: String): List<File> {
        if (manager == null || !manager.isAiMemesEnabled) return emptyList()
        val dir = manager.memesFolder() ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        val keywords = parseMemeKeywords(reply, MEME_MAX_PER_REPLY)
        if (keywords.isEmpty()) return emptyList()
        val files = ArrayList<File>()
        for (keyword in keywords) {
            val file = AiMemeLibrary.resolve(dir, keyword)
            if (file != null) {
                files.add(file)
                QqBindManager.logVerbose("[表情包] 命中 ${file.name}（关键词「$keyword」）")
            } else {
                PluginFileLog.write("[表情包] 未找到匹配「$keyword」的图片（群文件夹 ${dir.name}）")
            }
        }
        return files
    }

    /**
     * 剥离表情包标记：无标记时仅做首尾裁剪（保留原有段落结构）；
     * 有标记时连同标记占用的空行一并清理（聊天场景空行无实义）。
     */
    fun stripReplyMarkers(text: String): String {
        if (!MEME_MARKER_REGEX.containsMatchIn(text)) return text.trim()
        val cleaned = MEME_MARKER_REGEX.replace(text, "")
        return cleaned.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /**
     * 剥离推理模型思考段：成对标签取末段；未闭合的开放标签
     * 视为仍在思考，只保留标签之前的文本；全为思考时给出可重试提示。
     */
    fun stripThink(text: String): String {
        var result = text
        while (true) {
            val start = result.indexOf(THINK_OPEN)
            if (start < 0) break
            val end = result.indexOf(THINK_CLOSE, start)
            if (end < 0) {
                result = result.substring(0, start)
                break
            }
            result = result.removeRange(start, end + THINK_CLOSE.length)
        }
        val trimmed = result.trim()
        if (trimmed.isEmpty() && text.contains(THINK_OPEN)) {
            return "（模型这次只输出了思考过程，没有给出正式回答，请重新提问试试）"
        }
        return trimmed
    }

    /** 发送前的软截断（超长回复截断并注明，防止 QQ 刷屏）。 */
    fun capReply(reply: String): String {
        return if (reply.length <= REPLY_MAX_CHARS) reply
        else reply.substring(0, REPLY_MAX_CHARS) + "……（回复过长已截断）"
    }

    // ---------- HTTP ----------

    /** 执行 POST 请求。 */
    private fun doPost(url: String, apiKey: String, body: String): String {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.doOutput = true
            connection.outputStream.use { stream ->
                stream.writer(StandardCharsets.UTF_8).use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        } finally {
            connection?.disconnect()
        }
    }
}
