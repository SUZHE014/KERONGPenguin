package cn.huohuas001.huhobotPenguin.spigot.qqbind

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * QQ 群 AI 对话（@机器人触发，OpenAI 兼容接口）。
 * 支持可选的全局上下文（见 QqBindManager AI 上下文开关）。
 */
object AiChat {

    /**
     * 执行一次 AI 对话。
     * @param userMessage 用户输入（已去除 @ 片段）
     * @param manager     绑定管理器（提供 AI 配置与上下文）
     * @param groupId     群 OpenId
     * @param userId      用户 OpenId
     * @return AI 回复文本（失败时返回错误说明）
     */
    fun chat(userMessage: String, manager: QqBindManager?, groupId: String?, userId: String?): String {
        if (manager == null) return "AI 对话未就绪。"
        if (!manager.isAiEnabled) return "AI 对话未开启。"
        val baseUrl = manager.aiBaseUrl()
        val apiKey = manager.aiApiKey()
        val model = manager.aiModel()
        val systemPrompt = manager.aiSystemPrompt()
        if (baseUrl.isEmpty() || apiKey.isEmpty()) return "AI 对话未配置。"

        val contextEnabled = manager.isAiContextEnabled(groupId, userId)
        QqBindManager.logVerbose("[AI对话] 输入: $userMessage 上下文=$contextEnabled")

        var url = baseUrl.trimEnd('/')
        if (manager.isAiUrlAutoAppend && !url.endsWith("/chat/completions")) {
            url += "/chat/completions"
        }

        return try {
            val body = JSONObject().apply {
                put("model", model)
                val messages = JSONArray()
                messages.add(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                if (contextEnabled) {
                    val history = manager.getAiContext(groupId, userId)
                    if (history != null) {
                        for (message in history) {
                            messages.add(JSONObject().apply {
                                put("role", message.role)
                                put("content", message.content)
                            })
                        }
                    }
                }
                messages.add(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
                put("messages", messages)
                put("temperature", 0.7)
            }

            QqBindManager.logVerbose("[AI对话] 请求 URL=$url 消息数=${body.getJSONArray("messages").size}")
            val response = doPost(url, apiKey, body.toJSONString())
            QqBindManager.logVerbose(
                "[AI对话] 响应: " + (if (response.length > 300) response.substring(0, 300) + "..." else response)
            )
            val json = JSON.parseObject(response) ?: return "AI 返回为空"
            val choices: JSONArray? = json.getJSONArray("choices")
            if (choices == null || choices.isEmpty()) {
                val error = json.getString("error")
                return "AI 返回无 choices" + (if (error != null) ": $error" else "")
            }
            var result = choices.getJSONObject(0).getJSONObject("message").getString("content")
            result = result?.trim() ?: "（AI 未返回内容）"
            if (contextEnabled) {
                manager.appendAiContext(groupId, userId, userMessage, result)
            }
            result
        } catch (t: Throwable) {
            "AI 调用失败：${t.message}"
        }
    }

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
