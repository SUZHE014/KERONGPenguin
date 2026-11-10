package org.bukkit

/**
 * 颜色码工具（兼容层最小面：translateAlternateColorCodes / stripColor）。
 * QqBindManager 渲染踢人消息与文本清洗使用。
 */
object ChatColor {

    private val CODE_CHARS = "0123456789AaBbCcDdEeFfKkLlMmNnOoRr"
    private val COLOR_PATTERN = Regex("§[0-9A-FK-ORa-fk-or]")

    /** 替代颜色码 → 段落符（Bukkit 语义：成对替换）。 */
    @JvmStatic
    fun translateAlternateColorCodes(alt: Char, text: String): String {
        if (text.indexOf(alt) < 0) return text
        val chars = text.toCharArray()
        var i = 0
        while (i < chars.size - 1) {
            if (chars[i] == alt && CODE_CHARS.indexOf(chars[i + 1]) > -1) {
                chars[i] = '§'
                chars[i + 1] = Character.toLowerCase(chars[i + 1])
            }
            i++
        }
        return String(chars)
    }

    /** 剔除全部颜色码。 */
    @JvmStatic
    fun stripColor(text: String?): String? =
        if (text == null) null else COLOR_PATTERN.replace(text, "")
}
