package cn.huohuas001.bot.agent

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
import java.text.SimpleDateFormat
import java.util.Calendar

/**
 * QQ 群管理 REST API 封装（api.sgroup.qq.com）。
 * 请求头（含鉴权）复用 SDK 已登录会话的公共请求头。
 */
object GroupManagementApi {
    private const val API_BASE = "https://api.sgroup.qq.com"

    /** 从 SDK 上下文获取 access_token 鉴权头。 */
    private fun authHeader(starter: Starter): String {
        val start0 = starter.APPLICATION.INSTANCE.contextManager.getContextEntity(Start0::class.java)
        val token = start0.accessToken ?: throw IllegalStateException("无法获取 access_token")
        return "QQBot $token"
    }

    /** 从 SDK 上下文获取 Union AppId。 */
    @Suppress("unused")
    private fun appId(starter: Starter): String {
        val start0 = starter.APPLICATION.INSTANCE.contextManager.getContextEntity(Start0::class.java)
        return start0.headers["X-Union-Appid"] ?: ""
    }

    /** 执行 HTTP 请求并解析 JSON（业务错误码时抛异常）。 */
    private fun request(starter: Starter, method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = URL(API_BASE + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            val start0 = starter.APPLICATION.INSTANCE.contextManager.getContextEntity(Start0::class.java)
            for ((k, v) in start0.headers) {
                connection.setRequestProperty(k, v)
            }
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = body != null
            if (body != null) {
                val bodyStr = body.toJSONString()
                println("[GroupApi] $method $path body=$bodyStr")
                connection.outputStream.writer(StandardCharsets.UTF_8).use { it.write(bodyStr) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: "{}"
            println("[GroupApi] <- $code $text")
            val resp = JSON.parseObject(text)
            if (resp == null || resp.containsKey("code")) {
                throw RuntimeException("API 错误 [$code]: ${resp?.getString("message") ?: "未知错误"}")
            }
            return resp
        } finally {
            connection.disconnect()
        }
    }

    /** 获取群信息。 */
    fun getGroupInfo(starter: Starter, groupOpenId: String): JSONObject =
        request(starter, "GET", "/v2/groups/$groupOpenId/info")

    /** 获取机器人在群中的状态。 */
    fun getBotState(starter: Starter, groupOpenId: String): JSONObject =
        request(starter, "GET", "/v2/groups/$groupOpenId/bot_state")

    /** 获取入群申请列表。 */
    fun getJoinRequests(starter: Starter, groupOpenId: String, cursor: String = "", limit: Int = 20): JSONObject {
        val query = buildString {
            if (cursor.isNotEmpty()) append("cursor=$cursor")
            if (limit != 20) append(if (isEmpty()) "" else "&").append("limit=$limit")
        }.let { if (it.isEmpty()) "" else "?$it" }
        return request(starter, "GET", "/v2/groups/$groupOpenId/join_request_list$query")
    }

    /** 审批入群申请（approve=true 通过，false 拒绝）。 */
    fun approveJoinRequest(
        starter: Starter,
        groupOpenId: String,
        memberOpenId: String,
        joinRequestId: String,
        approve: Boolean,
        rejectReason: String = "",
        blacklist: Boolean = false,
    ): JSONObject {
        val body = JSONObject().apply {
            put("op", if (approve) "approve" else "decline")
            put("join_request_id", joinRequestId)
            if (!approve && rejectReason.isNotEmpty()) put("reject_reason", rejectReason)
            if (!approve) put("add_to_member_blacklist", blacklist)
        }
        return request(starter, "POST", "/v2/groups/$groupOpenId/approval_join_request/$memberOpenId", body)
    }

    /** 获取群禁言设置。 */
    fun getMuteStatus(starter: Starter, groupOpenId: String): JSONObject =
        request(starter, "GET", "/v2/groups/$groupOpenId/restrict_chat_setting")

    /** 设置成员禁言（op=add 禁言 / del 解除，minutes 上限 30 天）。 */
    fun setMemberMute(starter: Starter, groupOpenId: String, memberOpenId: String, op: String, minutes: Int = 1): JSONObject {
        val member = JSONObject().apply {
            put("op", op)
            put("member_openid", memberOpenId)
            if (op == "add") {
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.MINUTE, minutes.coerceIn(1, 43200))
                put("mute_expire_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(calendar.time))
            }
        }
        val body = JSONObject().apply {
            put("members", JSONArray().apply { add(member) })
        }
        return request(starter, "POST", "/v2/groups/$groupOpenId/restrict_chat_setting", body)
    }

    /** 获取群成员列表。 */
    fun getGroupMembers(starter: Starter, groupOpenId: String, cursor: String = "", limit: Int = 50): JSONObject {
        val query = buildString {
            if (cursor.isNotEmpty()) append("cursor=$cursor")
            if (limit != 50) append(if (isEmpty()) "" else "&").append("limit=$limit")
        }.let { if (it.isEmpty()) "" else "?$it" }
        return request(starter, "GET", "/v2/groups/$groupOpenId/members$query")
    }

    /** 查询入群自动审批策略列表。 */
    fun listAutoApprovePolicies(starter: Starter, cursor: String = "", limit: Int = 20): JSONObject {
        val query = buildString {
            if (cursor.isNotEmpty()) append("cursor=$cursor")
            if (limit != 20) append(if (isEmpty()) "" else "&").append("limit=$limit")
        }.let { if (it.isEmpty()) "" else "?$it" }
        return request(starter, "GET", "/v2/groups/join_approval_strategy$query")
    }

    /** 创建入群自动审批策略。 */
    fun createAutoApprovePolicy(
        starter: Starter,
        groupOpenIds: List<String>,
        groupIds: List<String>,
        enable: Boolean,
        remark: String,
    ): JSONObject {
        val body = JSONObject()
        if (groupOpenIds.isNotEmpty()) body["group_openids"] = JSONArray(groupOpenIds)
        if (groupIds.isNotEmpty()) body["group_ids"] = JSONArray(groupIds)
        body["is_enable"] = if (enable) "on" else "off"
        if (remark.isNotEmpty()) body["remark"] = remark
        return request(starter, "POST", "/v2/groups/join_approval_strategy", body)
    }

    /** 修改入群自动审批策略（仅提交提供的字段）。 */
    fun updateAutoApprovePolicy(
        starter: Starter,
        strategyId: String,
        enable: Boolean?,
        remark: String?,
        groupAction: JSONObject?,
    ): JSONObject {
        val body = JSONObject()
        if (enable != null) body["is_enable"] = if (enable) "on" else "off"
        if (remark != null) body["remark"] = remark
        if (groupAction != null) body["group_action"] = groupAction
        return request(starter, "PATCH", "/v2/groups/join_approval_strategy/$strategyId", body)
    }

    /** 删除入群自动审批策略。 */
    fun deleteAutoApprovePolicy(starter: Starter, strategyId: String): JSONObject =
        request(starter, "DELETE", "/v2/groups/join_approval_strategy/$strategyId")

    /** 执行入群自动审批策略。 */
    fun executeAutoApprovePolicy(starter: Starter, strategyId: String): JSONObject =
        request(starter, "POST", "/v2/groups/join_approval_strategy/$strategyId/execute")

    /** 修改自动审批白名单（op=add 新增 / del 删除）。 */
    fun updateWhitelistUsers(starter: Starter, strategyId: String, op: String, users: List<String>): JSONObject {
        val body = JSONObject().apply {
            put("op", op)
            put("whitelist_users", JSONArray(users))
        }
        return request(starter, "POST", "/v2/groups/join_approval_strategy/$strategyId/whitelist_users", body)
    }
}
