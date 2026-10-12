package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.huhobotPenguin.spigot.qqbind.MessageTemplates
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
 *
 * 1.6.0：固定文案改走消息模板（motd-usage / motd-query-failed / motd-offline /
 * motd-error / motd-status，配置 messages 段可换文件），默认内容与旧版一致。
 * motd-status 模板占位符：{type} {motd} {version} {protocolLine}（协议行，
 * 无协议时为空行内容）{online} {max} {delay} {playersSection}（在线玩家小节，
 * 无人时为空）。
 */
class MotdCommands : CommandSupport() {

    @Commands("motd")
    fun motd(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val host = params.trim()
        if (host.isBlank()) {
            MessageTemplates.sendDirect(event, "motd-usage")
            return
        }
        val apiUrl = "https://motd.minebbs.com/api/status?ip=$host&stype=auto"
        val imgUrl = "https://motd.minebbs.com/api/status_img?ip=$host&stype=auto&theme=simple"
        try {
            val response = fetchJson(apiUrl) ?: run {
                MessageTemplates.sendDirect(event, "motd-query-failed")
                return
            }
            val status = response.getString("status") ?: "unknown"
            if (status != "online") {
                val error = response.getString("error") ?: "服务器离线或无法连接"
                MessageTemplates.sendDirect(event, "motd-offline", mapOf(
                    "host" to host,
                    "error" to error,
                ))
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

            // {protocolLine}：协议 > 0 时为「协议: N\n」，否则为空（行不显示）
            val protocolLine = if (protocol > 0) "协议: $protocol\n" else ""
            // {playersSection}：有玩家时为「\n**在线玩家:**\n- a\n- b\n」，无人时为空
            val playersSection = if (playerList.isNotEmpty()) {
                "\n**在线玩家:**\n" + playerList.joinToString("\n") { "- $it" } + "\n"
            } else ""
            val markdown = MessageTemplates.render("motd-status", mapOf(
                "type" to serverType,
                "motd" to motdText,
                "version" to version,
                "protocolLine" to protocolLine,
                "online" to playersOnline.toString(),
                "max" to playersMax.toString(),
                "delay" to delay.toString(),
                "playersSection" to playersSection,
            ))
            plugin.replyWithImg(event, "", imgUrl)
            plugin.sendMarkdownToGroup(groupId(event), markdown)
        } catch (e: Exception) {
            plugin.log_error("MOTD 查询异常: ${e.message}")
            MessageTemplates.sendDirect(event, "motd-error", mapOf("error" to (e.message ?: e.toString())))
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
