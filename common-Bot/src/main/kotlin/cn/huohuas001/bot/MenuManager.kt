package cn.huohuas001.bot

import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.Start0
import io.github.kloping.qqbot.Starter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * QQ 快捷菜单（输入面板）管理：启动时按配置同步群快捷命令按钮。
 */
object MenuManager {
    private const val API_BASE = "https://api.sgroup.qq.com"

    /**
     * 面板按钮定义：name 菜单按钮文案 / desc 描述 / command 点击后发送的命令。
     */
    private data class PanelItem(val name: String, val desc: String, val command: String)

    private val PANEL_ITEMS = listOf(
        PanelItem("帮助", "查看所有命令", "帮助"),
        PanelItem("查询open ID", "查询 OpenId", "查询open ID"),
        PanelItem("查信息", "查询玩家统计卡片", "查信息"),
        PanelItem("绑定", "绑定 QQ", "绑定 "),
        PanelItem("重新绑定", "解除 QQ 绑定", "重新绑定"),
        PanelItem("查在线", "查询在线玩家", "查在线"),
        PanelItem("在线服务器", "查看服务器", "在线服务器"),
        PanelItem("发信息", "发送消息", "发信息 "),
        PanelItem("执行命令", "执行服务器命令", "执行命令 "),
        PanelItem("执行", "执行自定义命令", "执行 "),
        PanelItem("管理员执行", "管理员执行", "管理员执行 "),
        PanelItem("全量", "切换全量转发", "全量"),
        PanelItem("motd", "查询服务器", "motd "),
        PanelItem("AI对话上下文", "AI对话上下文", "AI对话上下文 "),
        PanelItem("清除当前上下文", "AI清除", "清除当前上下文"),
        PanelItem("黑名单", "黑名单", "黑名单 "),
        PanelItem("解除黑名单", "解除黑名单", "解除黑名单"),
        PanelItem("签到", "每日签到领金币", "签到"),
    )

    /** 依赖 QQ 绑定功能开启的按钮（绑定关闭时一并隐藏；1.5.2：个人信息按钮更名为查信息）。 */
    private val QQ_BIND_PANEL_NAMES = setOf("绑定", "重新绑定", "黑名单", "解除黑名单", "查信息")

    /** 依赖签到功能开启的按钮。 */
    private val CHECKIN_PANEL_NAMES = setOf("签到")

    /**
     * 同步所有配置群的快捷面板：先清空旧面板，再按当前功能开关重建。
     */
    fun syncGroupPanels(starter: Starter, groupOpenIds: List<String>) {
        if (groupOpenIds.isEmpty()) return
        try {
            val start0 = starter.APPLICATION.INSTANCE.contextManager.getContextEntity(Start0::class.java)
            val token = start0.accessToken ?: return
            val authHeader = "QQBot $token"

            // 清空已有面板
            listPanels(authHeader, "group").forEach { panel ->
                panel.getString("panel_id")?.let { deletePanel(authHeader, it) }
            }

            // 收集功能开关
            var commandList: Map<String, Boolean> = emptyMap()
            var qqBindEnabled = false
            var personalInfoEnabled = true
            var checkinEnabled = false
            try {
                val plugin = cn.huohuas001.bot.provider.BotShared.instance ?: return
                commandList = plugin.commandList()
                val manager = QqBindManager.getInstance()
                qqBindEnabled = manager.isEnabled
                personalInfoEnabled = manager.isPersonalInfoEnabled
                checkinEnabled = manager.isCheckinEnabled
            } catch (_: Throwable) {
            }

            val visible = PANEL_ITEMS.filter { item ->
                if (!qqBindEnabled && item.name in QQ_BIND_PANEL_NAMES) return@filter false
                if (qqBindEnabled && !personalInfoEnabled && item.name == "查信息") return@filter false
                if (!checkinEnabled && item.name in CHECKIN_PANEL_NAMES) return@filter false
                val enabled = commandList[item.name]
                enabled == null || enabled
            }

            val body = JSONObject().apply {
                put("scope", "group")
                put("target_type", "specific")
                put("group_openids", JSONArray(groupOpenIds))
                put("panel", JSONObject().apply {
                    put("remark", "KERONG Penguin")
                    put("items", JSONArray(visible.map { item ->
                        JSONObject().apply {
                            put("type", "command")
                            put("name", item.name)
                            put("desc", item.desc)
                        }
                    }))
                })
            }
            createPanel(authHeader, body)
            cn.huohuas001.bot.provider.BotShared.instance?.log_info(
                "已同步 ${groupOpenIds.size} 个群的快捷菜单（${visible.size} 个按钮可用）"
            )
        } catch (e: Exception) {
            cn.huohuas001.bot.provider.BotShared.instance?.log_error("面板同步失败: ${e.message}")
        }
    }

    /** 获取按钮对应的命令触发文本。 */
    fun getCommandTrigger(name: String?): String? {
        if (name == null) return null
        return PANEL_ITEMS.firstOrNull { it.name == name }?.command
    }

    /** 拉取已存在的面板列表。 */
    private fun listPanels(authHeader: String, scope: String): List<JSONObject> {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("$API_BASE/v2/panels?scope=$scope&limit=50")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", authHeader)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = JSON.parseObject(readAll(stream) ?: "{}")
            val records = body?.getJSONArray("records") ?: return emptyList()
            (0 until records.size).mapNotNull { records.get(it) as? JSONObject }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    /** 删除指定面板。 */
    private fun deletePanel(authHeader: String, panelId: String) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("$API_BASE/v2/panels/$panelId").openConnection() as HttpURLConnection
            connection.requestMethod = "DELETE"
            connection.setRequestProperty("Authorization", authHeader)
            connection.inputStream.close()
        } catch (_: Exception) {
        } finally {
            connection?.disconnect()
        }
    }

    /** 创建面板。 */
    private fun createPanel(authHeader: String, body: JSONObject) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("$API_BASE/v2/panels").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", authHeader)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.writer(StandardCharsets.UTF_8).use { it.write(body.toJSONString()) }
            connection.responseCode
            connection.inputStream.close()
        } catch (_: Exception) {
        } finally {
            connection?.disconnect()
        }
    }

    /** 读取流内容为字符串。 */
    private fun readAll(stream: java.io.InputStream?): String? {
        if (stream == null) return ""
        return try {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }
}
