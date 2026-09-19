package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * /motd 命令：查询任意 MC 服务器的状态（minebbs MOTD API）。
 */
class MotdCommands : CommandSupport() {

    @Commands("motd")
    fun motd(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val host = params.trim()
        if (host.isBlank()) {
            reply(plugin, event, "用法: /motd <服务器地址>\n示例: /motd mc.hypixel.net")
            return
        }
        val apiUrl = "https://motd.minebbs.com/api/status?ip=$host&stype=auto"
        val imgUrl = "https://motd.minebbs.com/api/status_img?ip=$host&stype=auto&theme=simple"
        try {
            val response = fetchJson(apiUrl) ?: run {
                reply(plugin, event, "查询失败，请检查服务器地址是否正确")
                return
            }
            val status = response.getString("status") ?: "unknown"
            if (status != "online") {
                val error = response.getString("error") ?: "服务器离线或无法连接"
                reply(plugin, event, "服务器 $host 当前离线\n$error")
                return
            }
            val serverType = response.getString("type") ?: "Java"
            val version = response.getString("version") ?: "未知"
            val protocol = response.getIntValue("protocol")
            val delay = response.getIntValue("delay")
            val motdText = response.getString("pureMotd")
                ?: response.getJSONObject("motd")?.getString("pureMotd")
                ?: "未知"
            val playersObj = response.getJSONObject("players")
            val playersOnline = playersObj?.getIntValue("online") ?: 0
            val playersMax = playersObj?.getIntValue("max") ?: 0
            val playerList = playersObj?.getString("sample")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

            val markdown = buildString {
                append("**MC 服务器状态查询**").append('\n').append('\n')
                append("类型: $serverType").append('\n')
                append("状态: 在线").append('\n')
                append("MOTD: $motdText").append('\n')
                append("版本: $version").append('\n')
                if (protocol > 0) append("协议: $protocol").append('\n')
                append("在线人数: $playersOnline/$playersMax").append('\n')
                append("延迟: ${delay}ms").append('\n')
                if (playerList.isNotEmpty()) {
                    append('\n').append("**在线玩家:**").append('\n')
                    playerList.forEach { append("- $it").append('\n') }
                }
            }
            plugin.replyWithImg(event, "", imgUrl)
            plugin.sendMarkdownToGroup(groupId(event), markdown)
        } catch (e: Exception) {
            plugin.log_error("MOTD 查询异常: ${e.message}")
            reply(plugin, event, "查询异常: ${e.message}")
        }
    }

    /** 请求 JSON 接口。 */
    private fun fetchJson(url: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { reader -> reader.readText() }
            } ?: return null
            JSON.parseObject(text)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
