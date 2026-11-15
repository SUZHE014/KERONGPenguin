package cn.kerong.penguin.spigot.qqbind

import cn.kerong.bot.tools.PluginFileLog
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * AI 知识库（1.7.6.2 新增）：把插件目录 knowledge/ 文件夹里的 txt / md
 * 文本文件按空行切段建立轻量索引；群聊 @机器人 提问时做关键词检索，
 * 把最相关的段落注入 system prompt，AI 按资料回答（回答依据以注入的
 * 知识库段落为准，检索不到则按常识回答）。
 *
 * 设计口径（与表情包同风格，均为目录驱动、零部署成本）：
 * - 文件夹默认为插件目录 knowledge（配置 ai.knowledge.folder），
 *   首次自动创建并附说明文件；文件增删即时生效（每次提问都重扫目录）；
 * - 检索算法：查询词 n-gram（中文按 2 字滑窗 + 单字，英文/数字按词）
 *   对段落计命中分（命中次数 / 段长归一），同分取靠前段落；
 * - 注入上限 ai.knowledge.max-chars（默认 2000），超限从尾部截断；
 * - 空目录 / 无命中零注入，对 AI 行为无影响。
 *
 * 纯函数（切段 / 分词 / 打分 / 排序 / 截断）拆出便于冒烟。
 */
object AiKnowledgeLibrary {

    /** 支持的知识库文件扩展名（小写）。 */
    val TEXT_EXTENSIONS = listOf("txt", "md")

    /** 首次创建文件夹时写入的说明文件。 */
    private const val README_NAME = "说明-把知识文件放进这里.txt"

    /** 单个知识文件大小上限（超出跳过并记日志，防误放大文件拖慢扫描）。 */
    const val MAX_FILE_BYTES = 512L * 1024

    /** 单段计入索引的最大长度（超长段截断，防止整篇无空行文件成为一段）。 */
    const val MAX_SEGMENT_CHARS = 400

    /** 打分后取前 N 段注入（命中太多时控制上下文体积）。 */
    const val TOP_SEGMENTS = 6

    /**
     * 确保知识库文件夹存在（首次创建时附带说明文件）。
     * @param folder 插件数据目录下的知识库文件夹（null = 未就绪，跳过）
     */
    fun ensureFolder(folder: File?): File? {
        if (folder == null) return null
        return try {
            if (!folder.exists()) {
                folder.mkdirs()
                val readme = File(folder, README_NAME)
                if (!readme.exists()) {
                    readme.writeText(buildString {
                        append("把知识文件放进这个文件夹（支持 txt / md，UTF-8 编码）。\n\n")
                        append("内容建议：\n")
                        append("  - 一段一个知识点（空行分段），段落别太长；\n")
                        append("  - 服务器规则、FAQ、玩法说明、成员介绍等都可以放；\n")
                        append("  - 群里 @机器人 提问时，最相关的段落会注入给 AI 作答依据；\n")
                        append("  - 新增或修改文件立即生效，无需重启服务器。\n")
                    }, StandardCharsets.UTF_8)
                }
            }
            folder
        } catch (t: Throwable) {
            PluginFileLog.write("[知识库] 创建文件夹失败（${t.message}）")
            null
        }
    }

    /** 列出全部知识文件（按文件名排序，稳定顺序；超大文件跳过记日志）。 */
    fun listFiles(dir: File): List<File> {
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { file ->
            if (!file.isFile) return@filter false
            if (file.extension.lowercase() !in TEXT_EXTENSIONS) return@filter false
            if (file.length() > MAX_FILE_BYTES) {
                PluginFileLog.write("[知识库] 跳过超大文件 ${file.name}（${file.length() / 1024}KB > ${MAX_FILE_BYTES / 1024}KB）")
                return@filter false
            }
            true
        }.sortedBy { it.name.lowercase() }
    }

    /** 知识段落：来源文件名 + 文本。 */
    data class Segment(val source: String, val text: String)

    /** 索引全部文件的段落（空行切段；首段若为标题行风格也按正文处理）。 */
    fun indexSegments(dir: File): List<Segment> {
        val segments = ArrayList<Segment>()
        for (file in listFiles(dir)) {
            val text = try {
                file.readText(StandardCharsets.UTF_8)
            } catch (t: Throwable) {
                PluginFileLog.write("[知识库] 读取失败 ${file.name}：${t.message}")
                continue
            }
            val name = file.nameWithoutExtension
            for (raw in text.split(Regex("\\n\\s*\\n"))) {
                val trimmed = raw.replace('\r', ' ').trim()
                if (trimmed.isEmpty()) continue
                val capped = if (trimmed.length > MAX_SEGMENT_CHARS) {
                    trimmed.substring(0, MAX_SEGMENT_CHARS)
                } else trimmed
                segments.add(Segment(name, capped))
            }
        }
        return segments
    }

    /**
     * 查询分词（纯函数，冒烟直接验证）：
     * 中文按 2 字滑窗 + 单字；连续的字母 / 数字串按整词。
     * @return 归一化词项（小写去空白）
     */
    fun tokenize(query: String): List<String> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return emptyList()
        val terms = LinkedHashSet<String>()
        val chars = trimmed.toCharArray()
        var i = 0
        val latin = StringBuilder()
        fun flushLatin() {
            if (latin.isNotEmpty()) {
                terms.add(latin.toString())
                latin.setLength(0)
            }
        }
        while (i < chars.size) {
            val c = chars[i]
            val isAsciiWord = c in 'a'..'z' || c in '0'..'9'
            if (isAsciiWord) {
                latin.append(c)
                i++
                continue
            }
            flushLatin()
            if (c.code in 0x4E00..0x9FFF) {
                // 中文：单字 + 与下一字的 2 字滑窗
                terms.add(c.toString())
                if (i + 1 < chars.size && chars[i + 1].code in 0x4E00..0x9FFF) {
                    terms.add(String(chars, i, 2))
                }
            }
            i++
        }
        flushLatin()
        return terms.filter { it.isNotBlank() }.toList()
    }

    /** 段落文本归一（小写；冒烟用）。 */
    fun normalizeText(text: String): String = text.lowercase()

    /**
     * 段落打分（纯函数，冒烟直接验证）：
     * 命中一个词项加 1 分；单字命中权重 0.5（避免常见单字刷分）；
     * 排序按分数降序、同分按索引升序（索引顺序即文件名序，稳定可复现）。
     */
    fun scoreSegments(terms: List<String>, segments: List<Segment>): List<Pair<Segment, Double>> {
        if (terms.isEmpty() || segments.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Segment, Double>>(segments.size)
        for (( index, segment) in segments.withIndex()) {
            val haystack = normalizeText(segment.text)
            var score = 0.0
            for (term in terms) {
                if (term.isEmpty()) continue
                if (haystack.contains(term)) {
                    score += if (term.length == 1) 0.5 else 1.0
                }
            }
            if (score > 0) scored.add(Pair(segment, score))
        }
        return scored
            .mapIndexed { order, pair -> Triple(order, pair.first, pair.second) }
            .sortedWith(compareByDescending<Triple<Int, Segment, Double>> { it.third }.thenBy { it.first })
            .map { Pair(it.second, it.third) }
    }

    /**
     * 检索与注入渲染（插件侧入口）：
     * @return 「知识库资料」system 段落；无命中或空目录返回空串
     */
    fun renderPromptSection(dir: File?, query: String, maxChars: Int): String {
        if (dir == null || !dir.isDirectory) return ""
        val segments = indexSegments(dir)
        if (segments.isEmpty()) return ""
        val terms = tokenize(query)
        if (terms.isEmpty()) return ""
        val scored = scoreSegments(terms, segments)
        if (scored.isEmpty()) return ""
        // 相关性门槛（1.7.6.2）：最高分不足 1（仅单个单字弱命中）不注入——
        // 避免无关闲聊（碰巧含某个常见单字）把知识库段落拖进上下文
        val best = scored.first().second
        if (best < 1.0) return ""
        val picked = scored.take(TOP_SEGMENTS)
        val sb = StringBuilder()
        sb.append("\n\n## 知识库资料（回答优先依据这些段落，没有相关内容就按常识回答，不要编造）")
        var used = 0
        for ((segment, score) in picked) {
            if (used >= maxChars) break
            val remain = maxChars - used
            val body = if (segment.text.length > remain) segment.text.substring(0, remain) else segment.text
            sb.append("\n【").append(segment.source).append("】").append(body)
            used += body.length
        }
        sb.append("\n（知识库检索命中 ").append(scored.size).append(" 段，注入前 ")
            .append(minOf(picked.size, scored.size)).append(" 段）")
        return sb.toString()
    }
}
