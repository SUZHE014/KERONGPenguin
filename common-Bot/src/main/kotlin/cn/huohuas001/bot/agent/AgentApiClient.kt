package cn.huohuas001.bot.agent

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * OpenAI 兼容接口客户端（chat/completions）。
 */
class AgentApiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) {

    /**
     * 发起一次对话补全请求。
     * @param messages 对话历史（role/content 结构）
     * @param tools 可供模型调用的工具定义
     */
    fun chat(messages: List<JSONObject>, tools: JSONArray): AgentChatResult {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("tools", tools)
            put("temperature", 0.3)
        }
        val responseText = post(body.toJSONString())
        val response = JSON.parseObject(responseText)
        val choices: JSONArray = response?.getJSONArray("choices")
            ?: throw AgentApiException("AI 接口响应缺少 choices 字段: ${responseText.take(500)}")
        val message: JSONObject = choices.getJSONObject(0)?.getJSONObject("message")
            ?: throw AgentApiException("AI 接口响应缺少 message 字段: ${responseText.take(500)}")
        return AgentChatResult(
            content = message.getString("content"),
            reasoning = extractReasoning(message),
            toolCalls = message.getJSONArray("tool_calls"),
        )
    }

    /** 兼容不同厂商的思考链字段（reasoning_content / reasoning 的多种形态）。 */
    private fun extractReasoning(message: JSONObject): String? {
        val reasoningContent = message.get("reasoning_content")
        if (reasoningContent is String && reasoningContent.isNotBlank()) return reasoningContent
        val reasoning = message.get("reasoning") ?: return null
        return when (reasoning) {
            is String -> reasoning.takeIf { it.isNotBlank() }
            is JSONArray -> {
                val joined = reasoning.joinToString("\n") { element ->
                    if (element is JSONObject) (element.getString("content") ?: element.toJSONString()) else element.toString()
                }
                joined.takeIf { it.isNotBlank() }
            }
            is JSONObject -> {
                val text = reasoning.getString("content")
                (text?.takeIf { it.isNotBlank() } ?: reasoning.toJSONString())
            }
            else -> reasoning.toString()
        }
    }

    /** 执行 POST 请求并返回响应文本。 */
    private fun post(json: String): String {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val connection: HttpURLConnection
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 120000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
        } catch (error: Exception) {
            throw AgentApiException("无法连接 AI 接口 $url: ${error.message}", error)
        }
        try {
            connection.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = connection.errorStream?.let { stream ->
                    BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
                } ?: ""
                throw AgentApiException("AI 接口返回 HTTP $code: ${errorBody.take(500)}")
            }
            return BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
        } catch (error: AgentApiException) {
            throw error
        } catch (error: Exception) {
            throw AgentApiException("请求 AI 接口失败: ${error.message}", error)
        } finally {
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
