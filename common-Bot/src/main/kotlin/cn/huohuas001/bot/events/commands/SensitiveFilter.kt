package cn.huohuas001.bot.events.commands

import com.alibaba.fastjson.JSON
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 敏感词过滤：本地词表替换 + 可选的远程大模型二审。
 */
object SensitiveFilter {

    /** 内置的基础敏感词（可由 sensitive-words 目录内的 txt 文件扩展）。 */
    private val defaults = listOf("傻逼", "操你", "色情", "反动", "赌博")

    /**
     * 过滤文本。
     * 1. 本地词表命中即替换为等长星号；
     * 2. 配置了审核接口时调用 OpenAI 兼容接口二审，失败时返回本地结果。
     */
    fun filter(value: String, baseUrl: String? = null, apiKey: String? = null, model: String? = null, words: List<String> = emptyList()): String {
        var local = value
        for (word in (defaults + words).distinct()) {
            if (word.isEmpty()) continue
            local = local.replace(word, "*".repeat(word.length), ignoreCase = true)
        }
        if (baseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return local

        return try {
            val connection = URL("${baseUrl.trimEnd('/')}/chat/completions").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")

            val body = "{\"model\":\"${escape(model ?: "gpt-4o-mini")}\"," +
                "\"messages\":[{\"role\":\"system\",\"content\":\"你是敏感词二审工具，只输出替换敏感内容后的完整原文。\"}," +
                "{\"role\":\"user\",\"content\":\"${escape(value)}\"}],\"temperature\":0.1}"
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

            val response = BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
            val content = JSON.parseObject(response)
                ?.getJSONArray("choices")
                ?.getJSONObject(0)
                ?.getJSONObject("message")
                ?.getString("content")
                ?.trim()
            content ?: local
        } catch (_: Exception) {
            local
        }
    }

    /** JSON 字符串转义。 */
    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
}
