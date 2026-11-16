package cn.kerong.penguin.spigot.qqbind

import cn.kerong.bot.tools.PluginFileLog
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 表情包库（1.7.6.2，主线三端版）：AI 可按「文件名代表的含义」从表情包
 * 文件夹挑选图片发送（含动态 GIF）。
 *
 * 与 0.3AI 版的差异：本类纯库化——文件夹由调用方（[QqBindManager.memesFolder]）
 * 构造后传入，库内不依赖插件实例，检索与匹配函数可直接冒烟。
 *
 * - 文件夹固定为插件目录 guaipng（1.7.6.3 起不再走配置改名），支持
 *   png / jpg / jpeg / gif / webp / bmp；
 * - 文件名即含义（如「开心.png」「鼓掌.gif」）：可用列表注入 AI 提示词，
 *   AI 回复中输出 [表情] 文件名或关键词，由本库匹配后把图片发到群里；
 * - 发送通道：[cn.kerong.bot.QClient.replyWithImgBytes]——图片字节
 *   base64 直传 QQ 富媒体，不重编码，动态 GIF 原样投递（0.3AI v0.5
 *   已在真机验证）；发送失败仅记日志，不影响文字回复；
 * - 匹配策略：精确（忽略大小写与分隔符）→ 包含 → 字符重合度（中文友好）；
 * - 文件增删即时生效（每次解析都读目录，无需重启）。
 */
object AiMemeLibrary {

    /** 表情包文件夹名（插件目录下，1.7.6.3 起固定值，原配置键已删）。 */
    const val FOLDER_NAME = "guaipng"

    /** 支持的图片扩展名（小写）。GIF 动图按字节直传，动画保留。 */
    val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /** 单张图片大小上限（QQ 富媒体 base64 通道的稳妥边界）。 */
    const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

    /** 注入提示词的文件名数量上限（防止几百张图刷爆上下文）。 */
    const val PROMPT_NAME_LIMIT = 60

    /** 首次创建文件夹时写入的说明文件。 */
    private const val README_NAME = "说明-把表情包图片放进这里.txt"

    /**
     * 确保表情包文件夹存在（首次创建时附带说明文件）。
     * @param folder 插件数据目录下的表情包文件夹（null = 未就绪，跳过）
     */
    fun ensureFolder(folder: File?): File? {
        if (folder == null) return null
        return try {
            if (!folder.exists()) {
                folder.mkdirs()
                val readme = File(folder, README_NAME)
                if (!readme.exists()) {
                    readme.writeText(buildString {
                        append("把表情包图片放进这个文件夹（支持 png / jpg / jpeg / gif / webp / bmp，动图 GIF 正常发送）。\n\n")
                        append("文件名 = 图片的意思，AI 会按文件名理解并挑选：\n")
                        append("  开心.png、鼓掌.jpg、问号.png、笑哭.gif ……\n\n")
                        append("建议：\n")
                        append("  - 文件名用简短的中文或英文描述图片含义；\n")
                        append("  - 一个含义一张图，含义不同文件名就不同；\n")
                        append("  - 新增或删除图片立即生效，无需重启服务器；\n")
                        append("  - 图片不宜过大（建议 2MB 以内，最大 10MB）。\n")
                    }, StandardCharsets.UTF_8)
                }
            }
            folder
        } catch (t: Throwable) {
            PluginFileLog.write("[表情包] 创建文件夹失败（${t.message}）")
            null
        }
    }

    /** 列出全部图片文件（按文件名排序，稳定顺序）。 */
    fun listFiles(dir: File): List<File> {
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { file ->
            file.isFile && file.extension.lowercase() in IMAGE_EXTENSIONS
        }.sortedBy { it.name.lowercase() }
    }

    /** 列出全部文件名（去扩展名，去重，保持排序）。 */
    fun listNames(dir: File): List<String> {
        return listFiles(dir).map { stripExtension(it.name) }.distinct()
    }

    /**
     * 按关键词解析要发送的图片文件。
     * @return 匹配的图片文件；无可用图片或不匹配返回 null
     */
    fun resolve(dir: File?, keyword: String): File? {
        if (dir == null || !dir.isDirectory) return null
        val files = listFiles(dir)
        if (files.isEmpty()) return null
        val names = files.map { stripExtension(it.name) }
        val best = findBest(keyword, names) ?: return null
        // findBest 返回的是原始名（可能与其他扩展名重名），优先取第一个匹配文件
        return files.firstOrNull { stripExtension(it.name) == best }
    }

    /**
     * 纯匹配函数（冒烟直接验证）：
     * 1. 归一化精确相等；2. 互为包含（长度 ≥ 2）；3. 字符重合度 ≥ 0.5。
     * @return 命中的原始文件名；不匹配返回 null
     */
    fun findBest(query: String, names: List<String>): String? {
        if (query.isBlank() || names.isEmpty()) return null
        val q = normalize(query)
        if (q.isEmpty()) return null
        // 1. 精确
        for (name in names) {
            if (normalize(name) == q) return name
        }
        // 2. 包含（双向）
        if (q.length >= 2) {
            for (name in names) {
                val n = normalize(name)
                if (n.length >= 2 && (n.contains(q) || q.contains(n))) return name
            }
        }
        // 3. 字符重合度（distinct 字符集合的交集占比，对中文名友好）
        val qChars = q.toCharArray().toSet()
        var bestName: String? = null
        var bestScore = 0.0
        for (name in names) {
            val n = normalize(name)
            if (n.isEmpty()) continue
            val nChars = n.toCharArray().toSet()
            val shared = qChars.intersect(nChars).size
            val score = shared.toDouble() / minOf(qChars.size, nChars.size).toDouble()
            if (score > bestScore) {
                bestScore = score
                bestName = name
            }
        }
        return if (bestScore >= 0.5) bestName else null
    }

    /** 归一化：小写、去空白、去常见分隔符。 */
    fun normalize(text: String): String {
        return text.lowercase()
            .replace(" ", "")
            .replace("\t", "")
            .replace("_", "")
            .replace("-", "")
            .replace(".", "")
            .trim()
    }

    /** 去掉文件扩展名（最后一个点之后；无点返回原名）。 */
    fun stripExtension(name: String): String {
        val idx = name.lastIndexOf('.')
        return if (idx > 0) name.substring(0, idx) else name
    }

    /**
     * 渲染注入提示词的「可用表情包」段落。
     * @return 段落文本；无可用图片时返回空串
     */
    fun renderPromptSection(dir: File?): String {
        if (dir == null || !dir.isDirectory) return ""
        val names = listNames(dir)
        if (names.isEmpty()) return ""
        val shown = if (names.size > PROMPT_NAME_LIMIT) {
            names.take(PROMPT_NAME_LIMIT).joinToString("、") + "（共 ${names.size} 张，仅列出前 $PROMPT_NAME_LIMIT 个）"
        } else {
            names.joinToString("、")
        }
        return "### 可用表情包（回复末尾用 [表情] 文件名 发送）\n" +
            "可用：$shown\n" +
            "只有当气氛合适才发，没有贴合的就干脆不发；文件名代表图片含义（含动图），从中选最贴切的。"
    }
}
