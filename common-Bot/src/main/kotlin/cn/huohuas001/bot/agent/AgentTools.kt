package cn.huohuas001.bot.agent

import cn.huohuas001.bot.HuHoBot
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.Starter
import java.util.Locale

/**
 * Agent 可调用的工具集合：服务器信息查询、命令执行、SKILL 加载与 QQ 群管理。
 */
object AgentTools {
    private const val TOOL_GET_PLUGIN_LIST = "get_server_plugin_list"
    private const val TOOL_GET_COMMAND_HELP = "get_command_help"
    private const val TOOL_RUN_COMMAND = "run_command"
    private const val TOOL_READ_SERVER_LOGS = "read_server_logs"
    private const val TOOL_LOAD_SKILL = "load_skill"
    private const val TOOL_GET_GROUP_INFO = "get_group_info"
    private const val TOOL_GET_BOT_STATE = "get_bot_state"
    private const val TOOL_GET_JOIN_REQUESTS = "get_join_requests"
    private const val TOOL_APPROVE_JOIN_REQUEST = "approve_join_request"
    private const val TOOL_GET_MUTE_STATUS = "get_mute_status"
    private const val TOOL_SET_MEMBER_MUTE = "set_member_mute"
    private const val TOOL_LIST_AUTO_APPROVE_POLICIES = "list_auto_approve_policies"
    private const val TOOL_CREATE_AUTO_APPROVE_POLICY = "create_auto_approve_policy"
    private const val TOOL_UPDATE_AUTO_APPROVE_POLICY = "update_auto_approve_policy"
    private const val TOOL_DELETE_AUTO_APPROVE_POLICY = "delete_auto_approve_policy"
    private const val TOOL_EXECUTE_AUTO_APPROVE_POLICY = "execute_auto_approve_policy"
    private const val TOOL_UPDATE_WHITELIST_USERS = "update_whitelist_users"

    /** 可加载的 SKILL 名称。 */
    private val VALID_SKILLS = setOf("components", "give", "item", "summon", "data", "loot", "clear")

    /**
     * 工具执行结果。
     * @param aiText         返回给模型的内容
     * @param displayTitle   群内展示卡片标题（空则不展示）
     * @param displayContent 群内展示卡片内容
     */
    data class ToolResult(
        val aiText: String,
        val displayTitle: String = "",
        val displayContent: String = "",
    )

    /** 构建 OpenAI 函数调用格式的工具定义列表。 */
    fun buildTools(): JSONArray {
        val tools = JSONArray()
        tools.add(toolDef(TOOL_GET_PLUGIN_LIST, "获取服务器上当前已安装的插件列表。", emptyMap()))
        tools.add(toolDef(TOOL_GET_COMMAND_HELP, "获取服务器命令的帮助信息（包括插件命令与原版命令，如 give、gamemode、time）。不传任何参数时返回所有命令概览；指定 plugin 时返回该插件注册的命令及帮助；指定 command 时返回该具体命令的详细帮助。", mapOf(
            "plugin" to "可选。插件名称，例如 Essentials。",
            "command" to "可选。具体命令名称，例如 kick。",
        )))
        tools.add(toolDef(TOOL_RUN_COMMAND, "在服务器控制台执行一条命令，例如 kick Player 使用外挂、whitelist add Player。执行属于敏感操作，手动审批模式下会先请求管理员确认。", mapOf(
            "command" to "要执行的完整命令，例如 kick Player 使用外挂。",
        ), required = listOf("command")))
        tools.add(toolDef(TOOL_READ_SERVER_LOGS, "读取服务端最新日志（logs/latest.log），用于分析插件报错、警告等信息。可指定读取末尾行数，或用关键词过滤只返回相关日志行及其上下文。", mapOf(
            "lines" to "可选。读取日志末尾的行数，默认 50，范围 1-500。",
            "keyword" to "可选。只返回包含该关键词（如 Exception、ERROR、插件名）的日志行及其上下文。",
        )))
        tools.add(toolDef(TOOL_LOAD_SKILL, "加载 Minecraft 命令语法 SKILL 文档。在生成任何涉及物品数据组件的命令前，必须先加载对应的 SKILL 获取正确的语法格式。可用的 skill：\n- components：所有数据组件的格式和用法参考（最常用，给任何命令生成物品前都应先读）\n- give：/give 命令语法和示例\n- item：/item 命令（替换/修改容器物品）\n- summon：/summon 命令（实体 NBT，物品字段格式）\n- data：/data 命令（读写 NBT 数据）\n- loot：/loot 命令（战利品表）\n- clear：/clear 和 /execute if items 命令（物品检测）", mapOf(
            "skill" to "SKILL 名称。可选值：components、give、item、summon、data、loot、clear",
        ), required = listOf("skill")))
        tools.add(toolDef(TOOL_GET_GROUP_INFO, "获取当前 QQ 群基本信息（群名、人数、标签等）。group_openid 已自动绑定当前群，无需提供。", mapOf(
            "group_openid" to "群 OpenID（可选，已自动绑定当前群）",
        )))
        tools.add(toolDef(TOOL_GET_BOT_STATE, "获取机器人在当前群中的状态（角色、入群时间等）。group_openid 已自动绑定当前群，无需提供。", mapOf(
            "group_openid" to "群 OpenID（可选，已自动绑定当前群）",
        )))
        tools.add(toolDef(TOOL_GET_JOIN_REQUESTS, "拉取入群申请列表。机器人需为群管理员。group_openid 已自动绑定当前群，无需提供。", mapOf(
            "group_openid" to "群 OpenID（可选，已自动绑定当前群）",
            "cursor" to "分页游标（可选）",
            "limit" to "每页数量（可选，默认20）",
        )))
        tools.add(toolDef(TOOL_APPROVE_JOIN_REQUEST, "审批入群申请。approve=通过，decline=拒绝。机器人需为群管理员。group_openid 已自动绑定当前群，无需提供。", mapOf(
            "group_openid" to "群 OpenID（可选，已自动绑定当前群）",
            "member_openid" to "申请人 OpenID",
            "join_request_id" to "申请 ID",
            "approve" to "true=通过，false=拒绝",
            "reject_reason" to "拒绝理由（仅 decline 时，可选）",
            "blacklist" to "是否同时拉黑（仅 decline 时，可选）",
        )))
        tools.add(toolDef(TOOL_GET_MUTE_STATUS, "查询当前群禁言状态（全员禁言配置、被禁言成员列表）。group_openid 已自动绑定当前群，无需提供。", mapOf(
            "group_openid" to "群 OpenID（可选，已自动绑定当前群）",
        )))
        tools.add(toolDef(TOOL_SET_MEMBER_MUTE, "设置群成员禁言。add=禁言，del=解除禁言。最大禁言30天。到期时间由服务端计算，禁言时长用 minutes 参数指定。group_openid 已自动绑定当前群，无需提供。", mapOf(
            "group_openid" to "群 OpenID（可选，已自动绑定当前群）",
            "member_openid" to "成员 OpenID",
            "op" to "操作：add=禁言，del=解除",
            "minutes" to "禁言时长（分钟，1-43200，仅 add，默认1）",
        )))
        tools.add(toolDef(TOOL_LIST_AUTO_APPROVE_POLICIES, "查询入群自动审批策略列表。", mapOf(
            "cursor" to "分页游标（可选）",
            "limit" to "每页数量（可选，默认20）",
        )))
        tools.add(toolDef(TOOL_CREATE_AUTO_APPROVE_POLICY, "创建入群自动审批策略。最多20个策略。", mapOf(
            "group_openids" to "关联群 OpenID 列表（与 group_ids 二选一）",
            "group_ids" to "关联 QQ 群号列表（与 group_openids 二选一）",
            "enable" to "是否启用（默认 true）",
            "remark" to "策略备注（可选）",
        )))
        tools.add(toolDef(TOOL_UPDATE_AUTO_APPROVE_POLICY, "修改入群自动审批策略（启用/停用/增删关联群）。", mapOf(
            "strategy_id" to "策略 ID",
            "enable" to "是否启用（可选）",
            "remark" to "备注（可选）",
            "group_action" to "关联群操作 JSON（可选）：{op:'add'/'del', group_openids:[...]} 或 {op:'add'/'del', group_ids:[...]}",
        )))
        tools.add(toolDef(TOOL_DELETE_AUTO_APPROVE_POLICY, "删除入群自动审批策略。", mapOf(
            "strategy_id" to "策略 ID",
        )))
        tools.add(toolDef(TOOL_EXECUTE_AUTO_APPROVE_POLICY, "执行入群自动审批策略，对关联群命中白名单的申请自动通过（异步约10分钟）。", mapOf(
            "strategy_id" to "策略 ID",
        )))
        tools.add(toolDef(TOOL_UPDATE_WHITELIST_USERS, "修改入群自动审批策略的白名单 QQ 号码。单次最多10000个，上限10万。", mapOf(
            "strategy_id" to "策略 ID",
            "op" to "操作：add=新增，del=删除",
            "users" to "QQ 号码列表（字符串数组）",
        )))
        return tools
    }

    /** 解析模型返回的工具参数 JSON。 */
    fun parseArguments(arguments: String?): JSONObject = try {
        JSON.parseObject(arguments ?: "{}") ?: JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    /** 获取服务器插件列表。 */
    fun getPluginList(plugin: HuHoBot): ToolResult {
        val plugins = try {
            plugin.serverPluginList()
        } catch (error: Throwable) {
            plugin.log_error("Agent 获取插件列表失败: ${error.message}")
            emptyList()
        }
        if (plugins.isEmpty()) {
            return ToolResult("服务器插件列表为空或当前平台不支持查询。", "服务器插件列表", "当前平台无法获取插件列表。")
        }
        val display = plugins.joinToString("\n") { "- $it" }
        return ToolResult(
            "服务器插件列表（共 ${plugins.size} 个）：\n${plugins.joinToString("\n")}",
            "服务器插件列表",
            display,
        )
    }

    /** 获取命令帮助。 */
    fun getCommandHelp(plugin: HuHoBot, query: JSONObject): ToolResult {
        val pluginName = query.getString("plugin")?.trim()?.takeIf { it.isNotEmpty() }
        val commandName = query.getString("command")?.trim()?.takeIf { it.isNotEmpty() }
        val help = try {
            plugin.serverCommandHelp(pluginName, commandName)
        } catch (error: Throwable) {
            plugin.log_error("Agent 获取命令帮助失败: ${error.message}")
            "获取命令帮助失败：${error.message}"
        }
        val title = when {
            commandName != null -> "命令 /$commandName 的帮助"
            pluginName != null -> "插件 $pluginName 的命令帮助"
            else -> "服务器命令帮助"
        }
        return ToolResult(help, title, help)
    }

    /** 读取服务端日志。 */
    fun getServerLogs(plugin: HuHoBot, query: JSONObject): ToolResult {
        val lines = query.getInteger("lines")
        val keyword = query.getString("keyword")?.trim()?.takeIf { it.isNotEmpty() }
        val content = try {
            plugin.serverLogs(lines, keyword)
        } catch (error: Throwable) {
            plugin.log_error("Agent 读取服务端日志失败: ${error.message}")
            "读取服务端日志失败：${error.message}"
        }
        return ToolResult(content, "服务端日志", content)
    }

    /** 加载 SKILL 文档。 */
    fun loadSkill(query: JSONObject): ToolResult {
        val skillName = query.getString("skill")?.trim()?.lowercase(Locale.ROOT)
        if (skillName.isNullOrBlank()) {
            return ToolResult("请指定 skill 名称。可用：components、give、item、summon、data、loot、clear")
        }
        if (skillName !in VALID_SKILLS) {
            return ToolResult("未知的 skill: $skillName。可用：${VALID_SKILLS.joinToString("、")}")
        }
        val resourcePath = "skill-$skillName.md"
        return try {
            val stream = AgentTools::class.java.classLoader.getResourceAsStream(resourcePath)
                ?: return ToolResult("SKILL 文件不存在: $resourcePath")
            val content = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ToolResult(content)
        } catch (error: Throwable) {
            ToolResult("加载 SKILL 失败: ${error.message}")
        }
    }

    /** 获取群信息。 */
    fun getGroupInfo(starter: Starter, query: JSONObject, defaultGroupOpenId: String = ""): ToolResult {
        val groupOpenId = resolveGroupOpenId(query, defaultGroupOpenId) ?: return ToolResult("请指定 group_openid。")
        return try {
            val info = GroupManagementApi.getGroupInfo(starter, groupOpenId)
            val tags = info.getJSONArray("group_tags")?.joinToString(", ") { it.toString() }
            val text = "群名: ${info.getString("group_name")}\n成员数: ${info.getIntValue("group_member_num")}\n" +
                "分类: ${info.getString("group_class_text")}\n简介: ${info.getString("group_finger_memo")}\n标签: $tags"
            ToolResult(text, "群信息", text)
        } catch (e: Exception) {
            ToolResult("获取群信息失败: ${e.message}")
        }
    }

    /** 获取机器人在群中的状态。 */
    fun getBotState(starter: Starter, query: JSONObject, defaultGroupOpenId: String = ""): ToolResult {
        val groupOpenId = resolveGroupOpenId(query, defaultGroupOpenId) ?: return ToolResult("请指定 group_openid。")
        return try {
            val info = GroupManagementApi.getBotState(starter, groupOpenId)
            val text = "角色: ${info.getString("member_role")}\n入群时间: ${info.getString("joined_at")}\n" +
                "接收消息设置: ${info.getString("recv_msg_setting")}\n主动推送: ${info.getBooleanValue("allow_proactive_msg")}"
            ToolResult(text, "机器人状态", text)
        } catch (e: Exception) {
            ToolResult("获取机器人状态失败: ${e.message}")
        }
    }

    /** 获取入群申请列表。 */
    fun getJoinRequests(starter: Starter, query: JSONObject, defaultGroupOpenId: String = ""): ToolResult {
        val groupOpenId = resolveGroupOpenId(query, defaultGroupOpenId) ?: return ToolResult("请指定 group_openid。")
        val cursor = query.getString("cursor")?.trim() ?: ""
        val limit = query.getInteger("limit") ?: 20
        return try {
            val resp = GroupManagementApi.getJoinRequests(starter, groupOpenId, cursor, limit)
            val list = resp.getJSONArray("list")
            if (list == null || list.isEmpty()) {
                return ToolResult("当前没有入群申请。")
            }
            val sb = StringBuilder("入群申请列表（${list.size} 条）：\n")
            for (i in 0 until list.size) {
                val r = list.getJSONObject(i)
                sb.append("${i + 1}. ${r.getString("username")} (${r.getString("member_openid")}) 来源: ${r.getString("apply_source")} 时间: ${r.getString("apply_at")}").append('\n')
            }
            resp.getString("next_cursor")?.takeIf { it.isNotEmpty() }?.let { sb.append("下一页游标: $it").append('\n') }
            val text = sb.toString()
            ToolResult(text, "入群申请", text)
        } catch (e: Exception) {
            ToolResult("获取入群申请列表失败: ${e.message}")
        }
    }

    /** 审批入群申请。 */
    fun approveJoinRequest(starter: Starter, query: JSONObject, defaultGroupOpenId: String = ""): ToolResult {
        val groupOpenId = resolveGroupOpenId(query, defaultGroupOpenId) ?: ""
        val memberOpenId = query.getString("member_openid")?.trim() ?: ""
        val joinRequestId = query.getString("join_request_id")?.trim() ?: ""
        val approve = query.getBoolean("approve") ?: true
        val rejectReason = query.getString("reject_reason")?.trim() ?: ""
        val blacklist = query.getBoolean("blacklist") ?: false
        if (groupOpenId.isEmpty() || memberOpenId.isEmpty()) {
            return ToolResult("请指定 group_openid 和 member_openid。")
        }
        return try {
            GroupManagementApi.approveJoinRequest(starter, groupOpenId, memberOpenId, joinRequestId, approve, rejectReason, blacklist)
            val action = if (approve) "已通过" else "已拒绝"
            ToolResult("入群申请 $action: $memberOpenId", "入群审批", "$action $memberOpenId")
        } catch (e: Exception) {
            ToolResult("审批入群申请失败: ${e.message}")
        }
    }

    /** 查询群禁言状态。 */
    fun getMuteStatus(starter: Starter, query: JSONObject, defaultGroupOpenId: String = ""): ToolResult {
        val groupOpenId = resolveGroupOpenId(query, defaultGroupOpenId) ?: return ToolResult("请指定 group_openid。")
        return try {
            val info = GroupManagementApi.getMuteStatus(starter, groupOpenId)
            val mode = info.getJSONObject("global_rule")?.getString("mode") ?: "none"
            val members = info.getJSONArray("members")
            val sb = StringBuilder("全员禁言模式: $mode\n")
            if (members != null && !members.isEmpty()) {
                sb.append("被禁言成员（${members.size} 人）：").append('\n')
                for (i in 0 until members.size) {
                    val m = members.getJSONObject(i)
                    sb.append("- ${m.getString("username")} (${m.getString("member_openid")}) 到期: ${m.getString("mute_expire_at")}").append('\n')
                }
            } else {
                sb.append("当前无成员被禁言。").append('\n')
            }
            val text = sb.toString()
            ToolResult(text, "禁言状态", text)
        } catch (e: Exception) {
            ToolResult("获取禁言状态失败: ${e.message}")
        }
    }

    /** 设置成员禁言。 */
    fun setMemberMute(starter: Starter, query: JSONObject, defaultGroupOpenId: String = ""): ToolResult {
        val groupOpenId = resolveGroupOpenId(query, defaultGroupOpenId) ?: ""
        val memberOpenId = query.getString("member_openid")?.trim() ?: ""
        val op = query.getString("op")?.trim() ?: ""
        val minutes = query.getInteger("minutes") ?: 1
        if (groupOpenId.isEmpty() || memberOpenId.isEmpty()) {
            return ToolResult("请指定 group_openid 和 member_openid。")
        }
        if (op !in listOf("add", "del")) {
            return ToolResult("op 必须为 add 或 del。")
        }
        return try {
            GroupManagementApi.setMemberMute(starter, groupOpenId, memberOpenId, op, minutes)
            val action = if (op == "add") "已禁言 $minutes 分钟" else "已解除禁言"
            ToolResult("$action: $memberOpenId", "禁言操作", "$action $memberOpenId")
        } catch (e: Exception) {
            ToolResult("设置禁言失败: ${e.message}")
        }
    }

    /** 查询自动审批策略列表。 */
    fun listAutoApprovePolicies(starter: Starter, query: JSONObject): ToolResult {
        val resp = try {
            GroupManagementApi.listAutoApprovePolicies(starter)
        } catch (e: Exception) {
            return ToolResult("获取策略列表失败: ${e.message}")
        }
        val strategies = resp.getJSONArray("strategies")
        if (strategies == null || strategies.isEmpty()) {
            return ToolResult("当前没有自动审批策略。")
        }
        val sb = StringBuilder("自动审批策略列表（${strategies.size} 条）：\n")
        for (i in 0 until strategies.size) {
            val s = strategies.getJSONObject(i)
            sb.append("${i + 1}. ID: ${s.getString("strategy_id")} 状态: ${s.getString("is_enable")} 白名单数: ${s.getIntValue("whitelist_user_count")} 备注: ${s.getString("remark")}").append('\n')
        }
        val text = sb.toString()
        return ToolResult(text, "自动审批策略", text)
    }

    /** 创建自动审批策略。 */
    fun createAutoApprovePolicy(starter: Starter, query: JSONObject): ToolResult {
        val groupOpenIds = query.getJSONArray("group_openids")?.map { it.toString() } ?: emptyList()
        val groupIds = query.getJSONArray("group_ids")?.map { it.toString() } ?: emptyList()
        val enable = query.getBoolean("enable") ?: true
        val remark = query.getString("remark")?.trim() ?: ""
        if (groupOpenIds.isEmpty() && groupIds.isEmpty()) {
            return ToolResult("请指定 group_openids 或 group_ids。")
        }
        return try {
            val resp = GroupManagementApi.createAutoApprovePolicy(starter, groupOpenIds, groupIds, enable, remark)
            val id = resp.getString("strategy_id")
            ToolResult("策略已创建: $id", "创建策略", "策略 ID: $id")
        } catch (e: Exception) {
            ToolResult("创建策略失败: ${e.message}")
        }
    }

    /** 修改自动审批策略。 */
    fun updateAutoApprovePolicy(starter: Starter, query: JSONObject): ToolResult {
        val strategyId = query.getString("strategy_id")?.trim() ?: ""
        if (strategyId.isEmpty()) {
            return ToolResult("请指定 strategy_id。")
        }
        val enable = if (query.containsKey("enable")) query.getBooleanValue("enable") else null
        val remark = if (query.containsKey("remark")) query.getString("remark")?.trim() else null
        val groupAction = if (query.containsKey("group_action")) query.getJSONObject("group_action") else null
        return try {
            GroupManagementApi.updateAutoApprovePolicy(starter, strategyId, enable, remark, groupAction)
            ToolResult("策略 $strategyId 已更新", "更新策略", "策略 $strategyId 已更新")
        } catch (e: Exception) {
            ToolResult("更新策略失败: ${e.message}")
        }
    }

    /** 删除自动审批策略。 */
    fun deleteAutoApprovePolicy(starter: Starter, query: JSONObject): ToolResult {
        val strategyId = query.getString("strategy_id")?.trim() ?: ""
        if (strategyId.isEmpty()) {
            return ToolResult("请指定 strategy_id。")
        }
        return try {
            GroupManagementApi.deleteAutoApprovePolicy(starter, strategyId)
            ToolResult("策略 $strategyId 已删除", "删除策略", "策略 $strategyId 已删除")
        } catch (e: Exception) {
            ToolResult("删除策略失败: ${e.message}")
        }
    }

    /** 执行自动审批策略。 */
    fun executeAutoApprovePolicy(starter: Starter, query: JSONObject): ToolResult {
        val strategyId = query.getString("strategy_id")?.trim() ?: ""
        if (strategyId.isEmpty()) {
            return ToolResult("请指定 strategy_id。")
        }
        return try {
            GroupManagementApi.executeAutoApprovePolicy(starter, strategyId)
            ToolResult("策略 $strategyId 已开始执行（约10分钟完成）", "执行策略", "策略 $strategyId 已开始执行，约10分钟完成。")
        } catch (e: Exception) {
            ToolResult("执行策略失败: ${e.message}")
        }
    }

    /** 更新自动审批白名单。 */
    fun updateWhitelistUsers(starter: Starter, query: JSONObject): ToolResult {
        val strategyId = query.getString("strategy_id")?.trim() ?: ""
        val op = query.getString("op")?.trim() ?: ""
        val users = query.getJSONArray("users")?.map { it.toString() } ?: emptyList()
        if (strategyId.isEmpty()) {
            return ToolResult("请指定 strategy_id。")
        }
        if (op !in listOf("add", "del")) {
            return ToolResult("op 必须为 add 或 del。")
        }
        if (users.isEmpty()) {
            return ToolResult("请指定 users 列表。")
        }
        return try {
            val resp = GroupManagementApi.updateWhitelistUsers(starter, strategyId, op, users)
            val count = resp.getIntValue("whitelist_user_count")
            ToolResult("白名单已更新，当前 $count 个号码", "白名单更新", "当前白名单号码数: $count")
        } catch (e: Exception) {
            ToolResult("更新白名单失败: ${e.message}")
        }
    }

    /** 获取群成员列表。 */
    fun getGroupMembers(starter: Starter, query: JSONObject, defaultGroupOpenId: String = ""): ToolResult {
        val groupOpenId = resolveGroupOpenId(query, defaultGroupOpenId) ?: return ToolResult("请指定 group_openid。")
        val cursor = query.getString("cursor")?.trim() ?: ""
        val limit = query.getInteger("limit") ?: 50
        return try {
            val resp = GroupManagementApi.getGroupMembers(starter, groupOpenId, cursor, limit)
            val members = resp.getJSONArray("members")
            if (members == null || members.isEmpty()) {
                return ToolResult("群内无成员或无法获取成员列表。")
            }
            val sb = StringBuilder("群成员列表（${members.size} 人）：\n")
            for (i in 0 until members.size) {
                val m = members.getJSONObject(i)
                val role = m.getString("member_role") ?: "member"
                val roleLabel = when (role) {
                    "owner" -> "[群主]"
                    "admin" -> "[管理员]"
                    else -> ""
                }
                sb.append("${m.getString("username")} (${m.getString("member_openid")}) $roleLabel").append('\n')
            }
            resp.getString("next_cursor")?.takeIf { it.isNotEmpty() }?.let { sb.append("下一页游标: $it").append('\n') }
            val text = sb.toString()
            ToolResult(text, "群成员列表", text)
        } catch (e: Exception) {
            ToolResult("获取群成员列表失败: ${e.message}")
        }
    }

    /** 从查询参数解析群 OpenId（空则回退默认值，仍空返回 null）。 */
    private fun resolveGroupOpenId(query: JSONObject, defaultGroupOpenId: String): String? {
        val value = query.getString("group_openid")?.trim()?.takeIf { it.isNotEmpty() } ?: defaultGroupOpenId
        return value.takeIf { it.isNotEmpty() }
    }

    /** 构建单个工具定义。 */
    private fun toolDef(name: String, desc: String, params: Map<String, String>, required: List<String> = emptyList()): JSONObject {
        val properties = JSONObject()
        for ((k, v) in params) {
            properties[k] = JSONObject().apply {
                put("type", "string")
                put("description", v)
            }
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", desc)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", properties)
                    if (required.isNotEmpty()) put("required", JSONArray(required))
                })
            })
        }
    }
}
