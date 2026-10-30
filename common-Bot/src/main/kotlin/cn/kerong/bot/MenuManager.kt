package cn.kerong.bot

import cn.kerong.penguin.spigot.qqbind.EconomyName
import cn.kerong.penguin.spigot.qqbind.QqBindManager
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
 *
 * 1.6.0 面板同步加固（修复「面板显示没有指定 / 按钮丢失」问题）：
 * - 全部面板接口调用校验 HTTP 状态码并解析 QQ 错误码（如 40030009 并发冲突、
 *   40030013 超出数量限制、40030020 内容安全拦截），失败明细写入日志；
 * - 面板元素长度预校验：name ≤ 14 单位 / desc ≤ 30 单位（中文按 2 单位），
 *   超限按钮跳过并告警；
 * - 40030009（操作进行中）自动重试（最多 3 次，间隔 1 秒）；
 * - 同步结束后输出面板 ID 与可用按钮数，/kerong panel 与启动日志均可核对。
 *
 * 1.6.7.9 先删后建（修复「创建面板失败 HTTP 400 code 30013 超出数量限制」）：
 * - 同步逻辑改为**先删后建**——不再走 PUT 原地更新：平台侧 PUT 更新在部分
 *   场景下仍会累积面板记录，且旧版「列表查询失败 → 误判无面板 → 直接创建」
 *   的路径会在平台上残留旧面板，累计触发 30013 数量超限后创建永远被拒；
 *   现每次同步前先删除列表里的全部面板（含非本插件 remark 的残留面板），
 *   再按当前配置创建，面板数量始终为 1，不再逼近平台上限；
 * - 创建面板遇 30013（超出数量限制，含 40030013 形态）时**自动清空重建**：
 *   重新拉取列表 → 删除全部面板 → 再创建一次（中间态最多几秒，QQ 客户端
 *   面板入口会即时反映）；
 * - 报错如实提示：删除 / 创建失败时输出 HTTP 状态码 + QQ 错误码 + 平台
 *   原始消息 + 人话解释（见 [describePanelError]），不再只有一句笼统的失败。
 */
object MenuManager {
    private const val API_BASE = "https://api.sgroup.qq.com"

    /** 面板备注（用于识别本插件创建的面板；不对用户展示）。 */
    private const val PANEL_REMARK = "KERONG Penguin"

    /** 面板操作冲突（30009 / 40030009）重试次数与间隔。 */
    private const val RETRY_TIMES = 3
    private const val RETRY_INTERVAL_MILLIS = 1_000L

    /** QQ 面板错误码（归一化后）：操作进行中（并发冲突）。 */
    private const val PANEL_ERR_CONFLICT = 30009

    /** QQ 面板错误码（归一化后）：面板数量超出平台限制。 */
    private const val PANEL_ERR_LIMIT = 30013

    /** QQ 面板错误码（归一化后）：内容安全拦截。 */
    private const val PANEL_ERR_CONTENT_SAFETY = 30020

    /**
     * 面板按钮定义：name 菜单按钮文案 / desc 描述 / command 点击后发送的命令。
     * 注意：QQ 面板点击后发送的是 name 本身（API 无独立命令字段），
     * 故 name 必须与群命令名一致；参数型命令（如 绑定）点击后仅发送命令名，
     * 玩家自行补参数。
     * 1.6.0 新增金币榜按钮；1.6.2 更名「财富榜」（命令 /金币榜 同步更名，
     * 旧名保留为隐藏别名不出现在菜单）；「在线」为 /查在线 的输入别名，
     * 按用户要求不出现在快捷菜单；1.6.7 新增「查性能」按钮
     * （commands.查性能 关闭时同步消失）。
     */
    private data class PanelItem(val name: String, val desc: String)

    private val PANEL_ITEMS = listOf(
        PanelItem("帮助", "查看所有命令"),
        PanelItem("查询open ID", "查询 OpenId"),
        PanelItem("查信息", "查询玩家统计卡片"),
        PanelItem("绑定", "绑定 QQ"),
        PanelItem("重新绑定", "解除 QQ 绑定"),
        PanelItem("查在线", "查询在线玩家"),
        PanelItem("查性能", "查看服务器性能"),
        PanelItem("在线排行榜", "在线时长排行"),
        PanelItem("财富榜", "金币数量排行"),
        PanelItem("在线服务器", "查看服务器"),
        PanelItem("发信息", "发送消息"),
        PanelItem("执行命令", "执行服务器命令"),
        PanelItem("执行", "执行自定义命令"),
        PanelItem("管理员执行", "管理员执行"),
        PanelItem("全量", "切换全量转发"),
        PanelItem("motd", "查询服务器"),
        PanelItem("AI对话上下文", "AI对话上下文"),
        PanelItem("清除当前上下文", "AI清除"),
        PanelItem("黑名单", "黑名单"),
        PanelItem("解除黑名单", "解除黑名单"),
        PanelItem("签到", "每日签到领金币"),
    )

    /** 依赖 QQ 绑定功能开启的按钮（绑定关闭时一并隐藏；1.5.2：个人信息按钮更名为查信息）。 */
    private val QQ_BIND_PANEL_NAMES = setOf("绑定", "重新绑定", "黑名单", "解除黑名单", "查信息")

    /** 依赖签到功能开启的按钮。 */
    private val CHECKIN_PANEL_NAMES = setOf("签到")

    /** 依赖经济插件（Vault）开启的按钮（1.6.0 金币榜；1.6.2 更名财富榜）。 */
    private val ECONOMY_PANEL_NAMES = setOf("财富榜")

    /**
     * 同步所有配置群的快捷面板（1.6.7.9 起先删后建）：
     * ① 列出已有面板 → ② 删除全部（含非本插件残留）→ ③ 校验长度后创建
     * 新面板（遇 30013 数量超限自动清空重建）→ ④ 记录面板 ID 与结果日志。
     */
    fun syncGroupPanels(starter: Starter, groupOpenIds: List<String>) {
        if (groupOpenIds.isEmpty()) return
        try {
            val start0 = starter.APPLICATION.INSTANCE.contextManager.getContextEntity(Start0::class.java)
            val token = start0.accessToken ?: run {
                log("面板同步跳过：访问令牌不可用（机器人尚未完成鉴权）")
                return
            }
            val authHeader = "QQBot $token"

            // 收集功能开关
            var commandList: Map<String, Boolean> = emptyMap()
            var qqBindEnabled = false
            var personalInfoEnabled = true
            var checkinEnabled = false
            var economyAvailable = false
            try {
                val plugin = cn.kerong.bot.provider.BotShared.instance ?: return
                commandList = plugin.commandList()
                val manager = QqBindManager.getInstance()
                qqBindEnabled = manager.isEnabled
                personalInfoEnabled = manager.isPersonalInfoEnabled
                checkinEnabled = manager.isCheckinEnabled
                economyAvailable = cn.kerong.bot.events.commands.CheckInCommands().isEconomyAvailable()
            } catch (_: Throwable) {
            }

            val visible = PANEL_ITEMS.filter { item ->
                if (!qqBindEnabled && item.name in QQ_BIND_PANEL_NAMES) return@filter false
                if (qqBindEnabled && !personalInfoEnabled && item.name == "查信息") return@filter false
                if (!checkinEnabled && item.name in CHECKIN_PANEL_NAMES) return@filter false
                if (!economyAvailable && item.name in ECONOMY_PANEL_NAMES) return@filter false
                val enabled = commandList[item.name]
                enabled == null || enabled
            }
            // 1.6.0：长度预校验（中文按 2 单位）——超限按钮跳过并告警，
            // 防止单个按钮把整次面板提交拖垮（面板整体被拒 → 全部按钮消失）
            // 1.6.1：描述文本应用经济别名（如「金币数量排行」/「每日签到领金币」
            // 里的“金币”跟随 economy-alias 配置替换；按钮名即命令名不替换）
            val checked = visible.mapNotNull { item ->
                val aliasedDesc = EconomyName.apply(item.desc)
                when {
                    displayUnits(item.name) > 14 -> {
                        log("面板按钮「${item.name}」名称超过 14 单位（中文按 2 计），已跳过")
                        null
                    }
                    displayUnits(aliasedDesc) > 30 -> {
                        log("面板按钮「${item.name}」描述超过 30 单位（中文按 2 计），已跳过")
                        null
                    }
                    else -> PanelItem(item.name, aliasedDesc)
                }
            }
            if (checked.isEmpty()) {
                log("面板同步中止：全部按钮被功能开关或长度校验过滤，本次不创建面板")
                return
            }

            val panelJson = JSONObject().apply {
                put("remark", PANEL_REMARK)
                put("items", JSONArray(checked.map { item ->
                    JSONObject().apply {
                        put("type", "command")
                        put("name", item.name)
                        put("desc", item.desc)
                    }
                }))
            }

            val createBody = JSONObject().apply {
                put("scope", "group")
                put("target_type", "specific")
                put("group_openids", JSONArray(groupOpenIds))
                put("panel", panelJson)
            }

            // 1.6.7.9：先删后建——不再 PUT 原地更新（旧面板在平台上累积触发
            // 30013 数量超限的根因），每次同步前删除列表里的全部面板（含非本
            // 插件 remark 的残留面板），再按当前配置创建一个新面板；创建遇
            // 30013 时由 createPanelWithLimitRecovery 自动清空重建兜底
            deleteAllPanels(authHeader)

            val panelId = createPanelWithLimitRecovery(authHeader, createBody)

            if (panelId != null) {
                log("已同步 ${groupOpenIds.size} 个群的快捷菜单（面板 $panelId，${checked.size} 个按钮可用）")
            } else {
                log("快捷菜单同步失败：面板未创建成功，详情见上方错误日志（可稍后用 /kerong panel 重试）")
            }
        } catch (e: Exception) {
            cn.kerong.bot.provider.BotShared.instance?.log_error("面板同步失败: ${e.message}")
        }
    }


    // ---------- HTTP 调用（全部带响应校验与错误码日志） ----------

    /** 拉取已存在的面板列表（失败返回空表并记录错误）。 */
    private fun listPanels(authHeader: String, scope: String): List<JSONObject> {
        val (code, body) = httpCall("GET", "$API_BASE/v2/panels?scope=$scope&limit=50", authHeader)
        if (code !in 200..299) {
            log("查询面板列表失败（HTTP $code${describePanelError(body)}）: $body")
            return emptyList()
        }
        return try {
            val records = JSON.parseObject(body ?: "{}")?.getJSONArray("records") ?: return emptyList()
            (0 until records.size).mapNotNull { records.get(it) as? JSONObject }
        } catch (e: Exception) {
            log("解析面板列表响应失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 删除列表里的全部面板（1.6.7.9 先删后建主体）。
     * 删除失败逐个如实记录（HTTP 状态码 + 错误码 + 人话解释），不阻断后续创建。
     */
    private fun deleteAllPanels(authHeader: String) {
        val existing = listPanels(authHeader, "group")
        if (existing.isEmpty()) {
            log("平台上无残留面板，直接创建")
            return
        }
        var deleted = 0
        var failed = 0
        for (panel in existing) {
            val id = panel.getString("panel_id") ?: continue
            if (deletePanel(authHeader, id)) {
                deleted++
            } else {
                failed++
            }
        }
        if (failed == 0) {
            log("已删除全部旧面板（$deleted 个），开始创建新面板")
        } else {
            log("旧面板清理完成：成功 $deleted 个、失败 $failed 个（失败明细见上方日志），继续尝试创建")
        }
    }

    /** 删除面板（失败如实记录错误码与原因，不阻断后续创建）。返回是否成功。 */
    private fun deletePanel(authHeader: String, panelId: String): Boolean {
        val (code, text) = httpCall("DELETE", "$API_BASE/v2/panels/$panelId", authHeader)
        if (code !in 200..299) {
            log("删除旧面板 $panelId 失败（HTTP $code${describePanelError(text)}）: $text")
            return false
        }
        return true
    }

    /**
     * 创建面板（POST）：
     * - 30009（操作进行中，含 40030009 形态）自动重试；
     * - **30013（超出数量限制，含 40030013 形态）自动清空重建**——重新拉取
     *   列表删除全部面板后再试，规避平台面板数量上限导致的永久创建失败；
     * - 其余失败如实输出 HTTP 状态码 + QQ 错误码 + 平台原始响应。
     * 返回 panel_id（失败返回 null）。
     */
    private fun createPanelWithLimitRecovery(authHeader: String, body: JSONObject): String? {
        repeat(RETRY_TIMES) { attempt ->
            val (code, text) = httpCall("POST", "$API_BASE/v2/panels", authHeader, body.toJSONString())
            if (code in 200..299) {
                val panelId = parsePanelId(text)
                if (panelId == null) {
                    log("创建面板成功但响应中无 panel_id（响应: $text）")
                    return null
                }
                return panelId
            }
            val err = normalizedErrorCode(text)
            when {
                err == PANEL_ERR_CONFLICT && attempt < RETRY_TIMES - 1 -> {
                    log("面板创建冲突（操作进行中），${RETRY_INTERVAL_MILLIS}ms 后重试（第 ${attempt + 1} 次）")
                    Thread.sleep(RETRY_INTERVAL_MILLIS)
                    return@repeat
                }
                err == PANEL_ERR_LIMIT && attempt < RETRY_TIMES - 1 -> {
                    // 30013：平台上面板数量已超上限——清空全部面板后重试创建
                    log("创建面板被拒（错误码 30013：超出面板数量限制），自动清空全部面板后重建")
                    deleteAllPanels(authHeader)
                    return@repeat
                }
                else -> {
                    log("创建面板失败（HTTP $code${describePanelError(text)}）: $text")
                    return null
                }
            }
        }
        return null
    }

    /** 从创建成功响应解析 panel_id。 */
    private fun parsePanelId(text: String?): String? = try {
        JSON.parseObject(text ?: "{}")?.getString("panel_id")
    } catch (_: Throwable) {
        null
    }

    /**
     * 错误码归一化：QQ 面板接口的错误码存在 30013 与 40030013 两种形态
     * （v2 API 返回前者、部分网关返回后者），统一折算为 30013 形态比较。
     */
    private fun normalizedErrorCode(body: String?): Int {
        val code = errorCodeOf(body)
        return if (code > 40_000_000) code - 40_000_000 else code
    }

    /** 面板错误码 → 人话解释（拼进失败日志，帮助服主自助排查）。 */
    private fun describePanelError(body: String?): String {
        return when (normalizedErrorCode(body)) {
            PANEL_ERR_CONFLICT -> "，错误码 30009：操作进行中（并发冲突），稍后自动重试"
            PANEL_ERR_LIMIT -> "，错误码 30013：超出面板数量限制（本次同步已自动清空重建，仍失败可稍后用 /kerong panel 重试）"
            PANEL_ERR_CONTENT_SAFETY -> "，错误码 30020：内容安全拦截（检查按钮文案后重试）"
            else -> ""
        }
    }

    /**
     * 统一 HTTP 调用：返回（状态码，响应体）。
     * 读取错误流而不是异常吞掉——QQ 面板接口的错误响应体携带
     * 错误码与中文说明，是排查「面板没显示」的关键信息。
     */
    private fun httpCall(
        method: String,
        url: String,
        authHeader: String,
        jsonBody: String? = null,
    ): Pair<Int, String?> {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", authHeader)
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (jsonBody != null) {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.writer(StandardCharsets.UTF_8).use { it.write(jsonBody) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = readAll(stream)
            code to body
        } catch (e: Exception) {
            -1 to (e.message ?: e.toString())
        } finally {
            connection?.disconnect()
        }
    }

    /** 从错误响应体解析 QQ 错误码（无则返回 -1）。 */
    private fun errorCodeOf(body: String?): Int = try {
        JSON.parseObject(body ?: "{}")?.getIntValue("code") ?: -1
    } catch (_: Throwable) {
        -1
    }

    /** 面板文案长度单位（中文等全角字符按 2 单位，其余 1 单位）。 */
    private fun displayUnits(text: String): Int =
        text.sumOf { ch -> if (ch.code > 0x2E80) 2 else 1 }

    /** 读取流内容为字符串。 */
    private fun readAll(stream: java.io.InputStream?): String? {
        if (stream == null) return null
        return try {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    /** 面板同步过程日志（控制台 + 文件，便于事后排查面板问题）。 */
    private fun log(message: String) {
        cn.kerong.bot.provider.BotShared.instance?.log_info("[快捷面板] $message")
            ?: println("[快捷面板] $message")
    }
}
