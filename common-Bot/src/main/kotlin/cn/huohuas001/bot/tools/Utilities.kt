package cn.huohuas001.bot.tools

import java.util.UUID

/**
 * 通用工具函数集合（对应原项目的 Utilities.kt 顶层函数）。
 */
private val ANSI_ESCAPE_REGEX = Regex("\u001B\\[[;\\d]*[ -/]*[@-~]")

/** 生成一个不含连字符的随机 UUID，用作通信包 ID。 */
val packID: String
    get() = UUID.randomUUID().toString().replace("-", "")

/** 去除文本中的 ANSI 转义序列（控制台颜色码等）。 */
fun stripAnsiEscape(text: String): String = ANSI_ESCAPE_REGEX.replace(text, "")

/**
 * 按正则列表过滤文本。
 * @param removeAnsiEscape 过滤前是否先移除 ANSI 转义序列
 */
fun filterTextByRegex(text: String, patterns: List<String>, removeAnsiEscape: Boolean = false): String {
    val source = if (removeAnsiEscape) stripAnsiEscape(text) else text
    if (patterns.isEmpty()) return source
    var result = source
    for (pattern in patterns) {
        if (pattern.isBlank()) continue
        result = Regex(pattern).replace(result, "")
    }
    return result
}

/**
 * 判断文本是否命中任意一条正则。
 * @param removeAnsiEscape 匹配前是否先移除 ANSI 转义序列
 */
fun containsRegexMatch(text: String, patterns: List<String>, removeAnsiEscape: Boolean = false): Boolean {
    val source = if (removeAnsiEscape) stripAnsiEscape(text) else text
    return patterns.any { it.isNotBlank() && Regex(it).containsMatchIn(source) }
}

