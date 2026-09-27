package cn.huohuas001.bot.tools

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import java.util.Base64

/**
 * QQ 群消息文本净化与引用消息解析。
 *
 * 1.5.4：修复“引用消息转发到游戏服务器乱码”。乱码根源（QQ 官方机器人平台
 * 群消息事件协议，GROUP_MESSAGE_CREATE）：
 *
 * 1. **QQ 原生表情标签内嵌在 content 里**（任意消息都可能携带，引用消息正文
 *    尤其常见）：
 *    - 新格式 `<faceType=4,faceId="",ext="base64(JSON)">`，ext 解码后为
 *      `{"text":"[微笑]"}` 之类的表情名——直接转发到游戏聊天会显示为一段
 *      base64 乱码串；
 *    - 旧格式 `[<face,id=123/>]`——转发会显示原始标签。
 *    处理：解码 ext 得到表情名输出 `[表情:微笑]`，无法解码时剔除标签。
 *
 * 2. **引用消息（message_type=103）的正文与被引内容分离**：
 *    - `content` 只含回复者输入的正文（纯引用不输入时为单个空格）；
 *    - 被引用的原文在 `msg_elements[0]`（含 content / attachments / author），
 *      引用关系在 `message_scene.ext` 的 `ref_msg_idx=REFIDX_xxx`（key=value）；
 *    - 旧实现只转发 content，引用上下文丢失（群里看是“回复某人”，游戏里
 *      只有一句没头没尾的话）。
 *    处理：解析 msg_elements[0] 构造 `[回复 昵称「被引摘要」]` 前缀一并转发，
 *      被引正文同样做表情净化，附件转成 `[图片]` / `[语音]` 摘要。
 *
 * 3. 兼容旧版平台事件把引用标记 `<reply id="..."/>` 直接嵌在 content 的形态：
 *    统一剔除残留标记。
 */
object QqText {

    /** 新格式表情标签（ext 为 base64 编码的表情描述 JSON）。 */
    private val FACE_TAG_NEW = Regex("""<faceType=\d+[^>]*>""")

    /** 旧格式表情标签。 */
    private val FACE_TAG_OLD = Regex("""\[<face,id=\d+/?>]""")

    /** 引用残留标记（兼容旧平台事件）。 */
    private val REPLY_TAG = Regex("""<reply[^>]*/?>""")

    /** 被引正文转发时的最大长度（超长截断加省略号，保持游戏聊天一行内可读）。 */
    private const val QUOTE_MAX_LENGTH = 50

    /**
     * 净化消息文本：表情标签解码 / 剔除，引用残留标记剔除。
     * 不含标签时原样返回（零开销快速路径）。
     */
    fun sanitize(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        if (result.contains("<faceType")) {
            result = FACE_TAG_NEW.replace(result) { decodeFaceTag(it.value) }
        }
        if (result.contains("[<face")) {
            result = FACE_TAG_OLD.replace(result, "")
        }
        if (result.contains("<reply")) {
            result = REPLY_TAG.replace(result, "")
        }
        return result
    }

    /** 解码单条新格式表情标签为 [表情:名称]；无法解析时剔除（返回空串）。 */
    private fun decodeFaceTag(tag: String): String = try {
        val extMatch = Regex("""ext="([^"]*)"""").find(tag)
        if (extMatch == null) "" else {
            val decoded = String(Base64.getDecoder().decode(extMatch.groupValues[1]), Charsets.UTF_8)
            val text = JSONObject.parseObject(decoded)?.getString("text")?.trim() ?: ""
            if (text.isNotEmpty()) "[表情:$text]" else ""
        }
    } catch (_: Throwable) {
        ""
    }

    /** 引用消息摘要（被引者昵称 + 净化后的被引正文/附件摘要）。 */
    data class Quote(
        /** 被引用消息的发送者昵称（平台未下发时为 null）。 */
        val senderName: String?,
        /** 被引正文（含附件摘要，已净化与截断）。 */
        val text: String,
    )

    /**
     * 从群消息事件原始 JSON（metadata）解析引用消息。
     *
     * 判定条件（满足其一即视为引用消息）：
     * - `message_type == 103`（官方文档：引用消息）；
     * - `message_scene.ext` 含 `ref_msg_idx=`（旧事件无 message_type 字段时的兼容判定）。
     *
     * 被引内容取 `msg_elements[0]`（官方文档：引用消息时 [0] 为被引用的原始消息）；
     * 解析失败或字段缺失时返回 null（调用方按普通消息处理，不影响转发）。
     */
    fun extractQuote(metadata: JSONObject?): Quote? {
        if (metadata == null) return null
        return try {
            val isReplyType = metadata.getIntValue("message_type") == 103
            val hasRefInScene = hasRefInScene(metadata)
            if (!isReplyType && !hasRefInScene) return null
            val elements = metadata.getJSONArray("msg_elements")
            if (elements.isNullOrEmpty()) return null
            val quoted = elements.getJSONObject(0) ?: return null

            val senderName = quoted.getJSONObject("author")?.getString("username")?.takeIf { it.isNotBlank() }
            val content = sanitize(quoted.getString("content") ?: "").trim()
            val attachmentSummary = summarizeAttachments(quoted.getJSONArray("attachments"))
            val text = when {
                content.isNotEmpty() && attachmentSummary != null -> "$content $attachmentSummary"
                content.isNotEmpty() -> content
                attachmentSummary != null -> attachmentSummary
                else -> return null // 被引内容不可得：不构造引用前缀
            }
            Quote(senderName, ellipsize(text))
        } catch (_: Throwable) {
            null
        }
    }

    /** message_scene.ext 是否含 ref_msg_idx（key=value 形式的引用索引）。 */
    private fun hasRefInScene(metadata: JSONObject): Boolean = try {
        val ext: JSONArray? = metadata.getJSONObject("message_scene")?.getJSONArray("ext")
        if (ext == null) false
        else ext.any { it is String && it.startsWith("ref_msg_idx=") }
    } catch (_: Throwable) {
        false
    }

    /** 附件摘要（引用消息的被引附件：图片 / 语音 / 文件）。 */
    private fun summarizeAttachments(attachments: JSONArray?): String? = try {
        if (attachments.isNullOrEmpty()) null
        else attachments.joinToString(" ") { item ->
            val attachment = item as? JSONObject
            val contentType = attachment?.getString("content_type") ?: ""
            when {
                contentType.startsWith("image/") -> "[图片]"
                "voice" == contentType -> "[语音]"
                contentType.startsWith("video/") -> "[视频]"
                else -> "[文件: ${attachment?.getString("filename") ?: "文件"}]"
            }
        }.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /** 超长截断（引用摘要展示上限）。 */
    private fun ellipsize(text: String): String =
        if (text.length <= QUOTE_MAX_LENGTH) text else text.take(QUOTE_MAX_LENGTH) + "…"

    /**
     * 构造转发到游戏的引用前缀：`[回复 昵称「被引摘要」] `。
     * 昵称未知时省略昵称段（`[回复「被引摘要」] `）。
     */
    fun quotePrefix(quote: Quote): String {
        val target = if (quote.senderName != null) "${quote.senderName}「${quote.text}」" else "「${quote.text}」"
        return "[回复 $target] "
    }
}
