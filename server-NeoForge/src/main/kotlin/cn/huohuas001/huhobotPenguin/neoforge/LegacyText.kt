package cn.huohuas001.huhobotPenguin.neoforge

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * 传统颜色码（§ / &）→ Adventure/Component 转换。
 *
 * 兼容层沿用插件内部的 § 颜色码约定（踢人消息、命令输出等），
 * NeoForge 端聊天与断开界面只接受 Component，此处做逐段解析。
 */
object LegacyText {

    private val CODE_TO_FORMAT = mapOf(
        '0' to ChatFormatting.BLACK,
        '1' to ChatFormatting.DARK_BLUE,
        '2' to ChatFormatting.DARK_GREEN,
        '3' to ChatFormatting.DARK_AQUA,
        '4' to ChatFormatting.DARK_RED,
        '5' to ChatFormatting.DARK_PURPLE,
        '6' to ChatFormatting.GOLD,
        '7' to ChatFormatting.GRAY,
        '8' to ChatFormatting.DARK_GRAY,
        '9' to ChatFormatting.BLUE,
        'a' to ChatFormatting.GREEN,
        'b' to ChatFormatting.AQUA,
        'c' to ChatFormatting.RED,
        'd' to ChatFormatting.LIGHT_PURPLE,
        'e' to ChatFormatting.YELLOW,
        'f' to ChatFormatting.WHITE,
        'k' to ChatFormatting.OBFUSCATED,
        'l' to ChatFormatting.BOLD,
        'm' to ChatFormatting.STRIKETHROUGH,
        'n' to ChatFormatting.UNDERLINE,
        'o' to ChatFormatting.ITALIC,
        'r' to ChatFormatting.RESET,
    )

    /** § 颜色码文本 → Component（无颜色码时为纯文本字面量）。 */
    fun toComponent(text: String): Component {
        if (text.isEmpty()) return Component.empty()
        if (!text.contains('§')) return Component.literal(text)
        val result: MutableComponent = Component.empty()
        val builder = StringBuilder()
        var color: ChatFormatting? = null
        var bold = false
        var italic = false
        var underline = false
        var strikethrough = false
        var obfuscated = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '§' && i + 1 < text.length) {
                val code = Character.toLowerCase(text[i + 1])
                val format = CODE_TO_FORMAT[code]
                if (format != null) {
                    if (builder.isNotEmpty()) {
                        result.append(styleOf(builder.toString(), color, bold, italic, underline, strikethrough, obfuscated))
                        builder.setLength(0)
                    }
                    when (format) {
                        ChatFormatting.RESET -> {
                            color = null; bold = false; italic = false
                            underline = false; strikethrough = false; obfuscated = false
                        }
                        ChatFormatting.BOLD -> bold = true
                        ChatFormatting.ITALIC -> italic = true
                        ChatFormatting.UNDERLINE -> underline = true
                        ChatFormatting.STRIKETHROUGH -> strikethrough = true
                        ChatFormatting.OBFUSCATED -> obfuscated = true
                        else -> color = format
                    }
                    i += 2
                    continue
                }
            }
            builder.append(c)
            i++
        }
        if (builder.isNotEmpty()) {
            result.append(styleOf(builder.toString(), color, bold, italic, underline, strikethrough, obfuscated))
        }
        return result
    }

    private fun styleOf(
        content: String,
        color: ChatFormatting?,
        bold: Boolean,
        italic: Boolean,
        underline: Boolean,
        strikethrough: Boolean,
        obfuscated: Boolean,
    ): Component {
        var style = net.minecraft.network.chat.Style.EMPTY
        if (color != null) style = style.withColor(color)
        if (bold) style = style.withBold(true)
        if (italic) style = style.withItalic(true)
        if (underline) style = style.withUnderlined(true)
        if (strikethrough) style = style.withStrikethrough(true)
        if (obfuscated) style = style.withObfuscated(true)
        return Component.literal(content).withStyle(style)
    }
}
