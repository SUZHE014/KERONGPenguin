package cn.huohuas001.bot.agent

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.provider.HExecution
import cn.huohuas001.bot.state.CommandRepositories
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.api.event.InterActionEvent
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import io.github.kloping.qqbot.entities.qqpd.v2.Contact
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Agent 任务管理器：会话调度、工具执行循环、审批流程。
 */
object AgentManager {
    private const val ACTION_PREFIX = "huhobot:agent:"
    private const val MAX_STEPS = 15
    private const val EXECUTE_TIMEOUT_SECONDS = 15L
    private const val MAX_CONTEXT_MESSAGES = 40
    private const val MAX_MESSAGE_CHARS = 3000

    /** 会话表（sessionId → 会话）。 */
    private val sessions = ConcurrentHashMap<String, AgentSession>()

    /** 启动（或续接）一个 Agent 任务。 */
    fun startAgent(plugin: HuHoBot, event: GroupMessageEvent, task: String) {
        val config = plugin.agentConfig
        if (config == null || !config.usable) {
            event.sendMessage("AI Agent 未启用或未配置，请检查配置中的 agent 部分（enabled、base-url、api-key、model）")
            return
        }
        if (task.isBlank()) {
            event.sendMessage("用法：/agent <任务描述>")
            return
        }
        event.sendMessage("已收到任务，AI 助手开始处理…")

        val groupOpenId = event.metadata?.getString("group_openid") ?: event.groupOpenId ?: event.groupId ?: return
        val requestUserId = event.sender?.openid ?: event.sender?.id ?: ""

        // 构建 @提及 用户映射，供 AI 直接使用 member_openid
        val mentionMap = buildMentionMap(event)
        val fullTask = (
            if (mentionMap.isNotEmpty()) {
                "[提及用户映射 - 直接使用下面的 member_openid 调用工具，不要调用 get_group_members]\n$mentionMap\n任务：$task"
            } else task
            ).trim()

        // 同一用户存在未决会话时续接，否则新建
        val existing = sessions.values.firstOrNull { it.requestUserId == requestUserId && it.awaitingApproval == null }
        val session: AgentSession
        if (existing != null) {
            session = existing
            session.finished = false
            session.memberNames.putAll(mentionMapValues(event))
            session.messages.add(userMessage("请帮我：$fullTask"))
        } else {
            session = AgentSession(UUID.randomUUID().toString(), groupOpenId, requestUserId, fullTask)
            session.memberNames.putAll(mentionMapValues(event))
            sessions[session.sessionId] = session
            val guide = loadCommandGuide()
            session.messages.add(systemMessage(buildSystemPrompt(plugin, guide)))
            session.messages.add(userMessage("请帮我：$fullTask"))
        }

        plugin.submitAsync { runLoop(plugin, session, config) }
    }

    /** 构建系统提示词。 */
    private fun buildSystemPrompt(plugin: HuHoBot, guide: String): String {
        val guideSection = if (guide.isNotEmpty()) "\n\n以下是 Minecraft 命令语法速查（完整内容通过 load_skill 获取）：\n$guide" else ""
        return """你是部署在 Minecraft 服务器管理机器人中的 AI 助手。你通过调用工具帮助管理员完成服务器管理任务。
服务器信息：
- 平台：${plugin.platform}
- 服务器版本：${plugin.serverVersion}
- 机器人插件版本：${plugin.pluginVersion}
规则：
1. 只调用与当前任务相关的工具，不要滥用工具。做任务时不要调用无关工具。
2. read_server_logs 仅在排查错误、异常或需要确认服务器状态时才调用，不要无故读取服务器日志。
3. 需要执行服务器命令时必须使用 run_command 工具执行，禁止只把命令展示给用户而不调用 run_command。
4. 生成涉及物品数据的命令前（如 /give、/item、/summon、/data、/loot、/clear），必须先调用 load_skill 加载对应的 SKILL 文档获取正确语法，再根据 SKILL 内容生成命令并调用 run_command 执行。
   - 生成物品相关命令时，先加载 components（数据组件参考）+ 对应命令的 SKILL（give/item/summon/data/loot/clear）
   - 例如：用户要给一把附魔钻石剑 → 先调用 load_skill(components) → 再调用 load_skill(give) → 再生成命令
5. 命令帮助中的权限名（如 minecraft.command.give）仅用于说明，命令中无需使用。
6. 所有回复使用中文，简洁友好。
7. QQ 群管理操作（禁言、审批、查询等）：所有工具已自动绑定当前群的 group_openid，无需用户提供。
   - 任务开头如果有 [提及用户映射]，你必须直接使用其中给出的 member_openid 调用对应工具，绝对不要调用 get_group_members。
   - 例如任务说禁言某人，映射里有某人->member_openid: XXX，直接用XXX调用set_member_mute。
   - 禁言前先调用 get_bot_state 确认机器人是否为群管理员（member_role 必须是 admin 或 owner），否则禁言会失败。
   - 禁止询问用户 group_openid 或 member_openid。$guideSection"""
    }

    /** 从消息元数据构建 “昵称 → member_openid” 映射文本。 */
    private fun buildMentionMap(event: GroupMessageEvent): String {
        val mentions = event.metadata?.getJSONArray("mentions") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until mentions.size) {
            val m = mentions.getJSONObject(i) ?: continue
            if (m.getBoolean("is_you") == true) continue
            val name = m.getString("username") ?: continue
            val openId = m.getString("member_openid") ?: continue
            sb.append("$name → member_openid: $openId").append('\n')
        }
        return sb.toString().trim()
    }

    /** 从消息元数据构建 OpenId → 昵称映射。 */
    private fun mentionMapValues(event: GroupMessageEvent): Map<String, String> {
        val mentions = event.metadata?.getJSONArray("mentions") ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (i in 0 until mentions.size) {
            val m = mentions.getJSONObject(i) ?: continue
            if (m.getBoolean("is_you") == true) continue
            val name = m.getString("username") ?: continue
            val openId = m.getString("member_openid") ?: continue
            result[openId] = name
        }
        return result
    }

    /** Agent 主循环：调用模型 → 执行工具 → 直到给出最终回复。 */
    private fun runLoop(plugin: HuHoBot, session: AgentSession, config: AgentConfig) {
        if (session.finished) return
        val client = AgentApiClient(config.baseUrl, config.apiKey, config.model)
        val tools: JSONArray = AgentTools.buildTools()
        var steps = 0

        loop@ while (true) {
            if (session.stopped) {
                sendToGroup(plugin, session, "⏹️ 任务已紧急停止")
                finish(plugin, session)
                return
            }
            if (++steps > MAX_STEPS) {
                sendToGroup(plugin, session, AgentMessageFormatter.error("处理步骤过多，任务已中止"))
                finish(plugin, session)
                return
            }

            val result = try {
                client.chat(trimContext(session.messages), tools)
            } catch (error: Throwable) {
                plugin.log_error("Agent AI 调用失败: ${error.message}")
                sendToGroup(plugin, session, AgentMessageFormatter.error(error.message ?: "未知错误"))
                finish(plugin, session)
                return
            }

            // 展示思考过程
            val reasoning = result.reasoning?.trim().orEmpty()
            if (reasoning.isNotEmpty()) {
                sendToGroup(plugin, session, AgentMessageFormatter.thinking(reasoning))
            }

            // 无工具调用：输出最终回复并结束
            val toolCalls = result.toolCalls
            if (toolCalls == null || toolCalls.isEmpty()) {
                val content = result.content?.trim().orEmpty()
                if (content.isNotEmpty()) {
                    sendToGroup(plugin, session, AgentMessageFormatter.reply(content))
                }
                finish(plugin, session)
                return
            }

            session.messages.add(assistantMessage(result.content, toolCalls))

            for (index in 0 until toolCalls.size) {
                val toolCall = toolCalls.getJSONObject(index) ?: continue
                val toolCallId = toolCall.getString("id") ?: "call_$index"
                val function = toolCall.getJSONObject("function") ?: continue
                val functionName = function.getString("name") ?: continue
                val query = AgentTools.parseArguments(function.getString("arguments"))

                when (functionName) {
                    "get_server_plugin_list" -> {
                        val toolResult = AgentTools.getPluginList(plugin)
                        session.messages.add(toolMessage(toolCallId, toolResult.aiText))
                        sendToGroup(plugin, session, AgentMessageFormatter.fetchCard(toolResult.displayTitle, toolResult.displayContent))
                    }
                    "get_command_help" -> {
                        val toolResult = AgentTools.getCommandHelp(plugin, query)
                        session.messages.add(toolMessage(toolCallId, toolResult.aiText))
                        sendToGroup(plugin, session, AgentMessageFormatter.fetchCard(toolResult.displayTitle, toolResult.displayContent))
                    }
                    "read_server_logs" -> {
                        val toolResult = AgentTools.getServerLogs(plugin, query)
                        session.messages.add(toolMessage(toolCallId, toolResult.aiText))
                        sendToGroup(plugin, session, AgentMessageFormatter.fetchCard(toolResult.displayTitle, toolResult.displayContent))
                    }
                    "run_command" -> {
                        val command = query.getString("command")?.trim().orEmpty()
                        if (command.isBlank()) {
                            session.messages.add(toolMessage(toolCallId, "命令为空，无法执行。"))
                            continue
                        }
                        if (config.commandMode == AgentCommandMode.AUTO) {
                            val output = executeCommand(plugin, command)
                            session.messages.add(toolMessage(toolCallId, "命令已执行，输出：\n$output"))
                            sendToGroup(plugin, session, AgentMessageFormatter.executedCard(command, output))
                        } else {
                            requestApproval(plugin, session, toolCallId, command)
                            return
                        }
                    }
                    "load_skill" -> {
                        val skillName = query.getString("skill")?.trim().orEmpty()
                        sendToGroup(plugin, session, AgentMessageFormatter.skillLoading(skillName))
                        val toolResult = AgentTools.loadSkill(query)
                        session.messages.add(toolMessage(toolCallId, toolResult.aiText))
                    }
                    "get_group_info", "get_bot_state", "get_join_requests", "approve_join_request",
                    "get_mute_status", "set_member_mute", "list_auto_approve_policies",
                    "create_auto_approve_policy", "update_auto_approve_policy", "delete_auto_approve_policy",
                    "execute_auto_approve_policy", "update_whitelist_users",
                    -> {
                        val needsApproval = functionName in setOf(
                            "set_member_mute", "approve_join_request", "create_auto_approve_policy",
                            "update_auto_approve_policy", "delete_auto_approve_policy",
                            "execute_auto_approve_policy", "update_whitelist_users",
                        )
                        if (needsApproval && config.commandMode == AgentCommandMode.MANUAL) {
                            requestToolApproval(plugin, session, toolCallId, functionName, query)
                            return
                        }
                        executeGroupTool(plugin, session, toolCallId, functionName, query)
                    }
                    else -> {
                        session.messages.add(toolMessage(toolCallId, "未知工具：$functionName"))
                    }
                }
            }
        }
    }

    /** 发起普通命令审批（MANUAL 模式）。 */
    private fun requestApproval(plugin: HuHoBot, session: AgentSession, toolCallId: String, command: String) {
        val approvalId = UUID.randomUUID().toString()
        session.awaitingApproval = AgentSession.PendingApproval(approvalId, toolCallId, command)
        plugin.sendMarkdownToGroup(session.groupOpenId, AgentMessageFormatter.approvalCard(command), buildApprovalKeyboard(approvalId))
    }

    /** 发起群管理工具审批（MANUAL 模式）。 */
    private fun requestToolApproval(plugin: HuHoBot, session: AgentSession, toolCallId: String, toolName: String, query: JSONObject) {
        val approvalId = UUID.randomUUID().toString()
        val description = describeToolAction(session, toolName, query)
        session.awaitingApproval = AgentSession.PendingApproval(approvalId, toolCallId, description, toolName, query)
        plugin.sendMarkdownToGroup(session.groupOpenId, AgentMessageFormatter.approvalCard(description), buildApprovalKeyboard(approvalId))
    }

    /** 生成待审批操作的描述文本。 */
    private fun describeToolAction(session: AgentSession, toolName: String, query: JSONObject): String = when (toolName) {
        "set_member_mute" -> {
            val op = query.getString("op") ?: ""
            val minutes = query.getInteger("minutes") ?: 1
            val member = session.displayName(query.getString("member_openid") ?: "?")
            if (op == "add") "禁言成员 $member（$minutes 分钟）" else "解除禁言 $member"
        }
        "approve_join_request" -> "审批入群申请：${session.displayName(query.getString("member_openid") ?: "?")}"
        "create_auto_approve_policy" -> "创建入群自动审批策略"
        "update_auto_approve_policy" -> "修改入群自动审批策略 ${query.getString("strategy_id") ?: "?"}"
        "delete_auto_approve_policy" -> "删除入群自动审批策略 ${query.getString("strategy_id") ?: "?"}"
        "execute_auto_approve_policy" -> "执行入群自动审批策略 ${query.getString("strategy_id") ?: "?"}"
        "update_whitelist_users" -> "修改自动审批白名单"
        else -> "执行群管理操作 $toolName"
    }

    /** 执行群管理工具并记录结果。 */
    private fun executeGroupTool(plugin: HuHoBot, session: AgentSession, toolCallId: String, functionName: String, query: JSONObject) {
        val starter = QClient.starter
        if (starter == null) {
            session.messages.add(toolMessage(toolCallId, "QQ Bot 未启动，无法调用群管理 API。"))
            return
        }
        val defaultGroupOpenId = session.groupOpenId
        query["group_openid"] = defaultGroupOpenId
        val result = when (functionName) {
            "get_group_info" -> AgentTools.getGroupInfo(starter, query, defaultGroupOpenId)
            "get_bot_state" -> AgentTools.getBotState(starter, query, defaultGroupOpenId)
            "get_join_requests" -> AgentTools.getJoinRequests(starter, query, defaultGroupOpenId)
            "approve_join_request" -> AgentTools.approveJoinRequest(starter, query, defaultGroupOpenId)
            "get_mute_status" -> AgentTools.getMuteStatus(starter, query, defaultGroupOpenId)
            "set_member_mute" -> AgentTools.setMemberMute(starter, query, defaultGroupOpenId)
            "list_auto_approve_policies" -> AgentTools.listAutoApprovePolicies(starter, query)
            "create_auto_approve_policy" -> AgentTools.createAutoApprovePolicy(starter, query)
            "update_auto_approve_policy" -> AgentTools.updateAutoApprovePolicy(starter, query)
            "delete_auto_approve_policy" -> AgentTools.deleteAutoApprovePolicy(starter, query)
            "execute_auto_approve_policy" -> AgentTools.executeAutoApprovePolicy(starter, query)
            "update_whitelist_users" -> AgentTools.updateWhitelistUsers(starter, query)
            else -> AgentTools.ToolResult(aiText = "未知工具: $functionName")
        }
        session.messages.add(toolMessage(toolCallId, result.aiText))
        if (result.displayTitle.isNotEmpty()) {
            val displayContent = replaceOpenIdsWithNames(session, result.displayContent)
            sendToGroup(plugin, session, AgentMessageFormatter.fetchCard(result.displayTitle, displayContent))
        }
    }

    /** 将文本中的 OpenId 替换为群昵称。 */
    private fun replaceOpenIdsWithNames(session: AgentSession, text: String): String {
        if (session.memberNames.isEmpty()) return text
        var result = text
        for ((openId, name) in session.memberNames) {
            result = result.replace(openId, name)
        }
        return result
    }

    /** 派发并等待命令执行，统一处理超时与异常。 */
    private fun executeCommand(plugin: HuHoBot, command: String): String = try {
        val execution: HExecution = plugin.dispatchCommand(command)[EXECUTE_TIMEOUT_SECONDS, TimeUnit.SECONDS]
        val output = stripFormattingCodes(execution.rawString)
        if (output.isBlank()) "命令执行完成（无输出）" else output
    } catch (_: TimeoutException) {
        "命令执行超时"
    } catch (error: Throwable) {
        var root: Throwable = error
        while (root.cause != null) root = root.cause!!
        var rootMsg = root.toString()
        val idx = rootMsg.indexOf("com.mojang.brigadier")
        if (idx >= 0) rootMsg = rootMsg.substring(idx)
        "命令执行失败：${stripFormattingCodes(rootMsg)}"
    }

    /** 去除 § / & / ANSI 颜色代码。 */
    private fun stripFormattingCodes(text: String): String = text
        .replace(Regex("§."), "")
        .replace(Regex("&[0-9a-fk-orA-FK-OR]"), "")
        .replace(Regex("\u001B\\[[0-9;]*m"), "")

    /** 读取内置命令速查资源。 */
    private fun loadCommandGuide(): String = try {
        AgentManager::class.java.classLoader.getResourceAsStream("agent-command-guide.md")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: ""
    } catch (_: Exception) {
        ""
    }

    /** 处理 QQ 按钮回调（审批同意 / 拒绝）。 */
    fun onInteraction(plugin: HuHoBot, event: InterActionEvent) {
        val interaction = event.interAction ?: return
        val data = interaction.data?.resolved?.button_data ?: return
        if (!data.startsWith(ACTION_PREFIX)) return
        val groupOpenId = interaction.group_openid ?: return
        val memberOpenId = interaction.group_member_openid ?: ""
        plugin.log_info("Agent 审批回调: group=$groupOpenId, member=$memberOpenId, data=$data")

        // huhobot:agent:<approvalId>:<decision>
        val parts = data.split(":")
        if (parts.size < 4) return
        val approvalId = parts[2]
        val decision = parts[3]

        val session = sessions.values.firstOrNull {
            it.awaitingApproval?.approvalId == approvalId
        } ?: return
        val pending = session.awaitingApproval ?: return

        if (session.stopped) {
            session.awaitingApproval = null
            return
        }
        if (session.groupOpenId != groupOpenId) {
            plugin.log_warning("Agent 审批群不匹配，已忽略 approvalId=$approvalId")
            sendToGroup(plugin, session, AgentMessageFormatter.noPermissionNotice())
            return
        }
        if (!isApprovalAuthorized(plugin, session, groupOpenId, memberOpenId)) {
            sendToGroup(plugin, session, AgentMessageFormatter.noPermissionNotice())
            return
        }
        session.awaitingApproval = null

        if (decision == "yes") {
            sendToGroup(plugin, session, AgentMessageFormatter.approvedNotice(session.displayName(memberOpenId)))
            if (pending.isTool) {
                val query = pending.query ?: JSONObject()
                executeGroupTool(plugin, session, pending.toolCallId, pending.toolName, query)
                session.messages.add(toolMessage(pending.toolCallId, "操作已由管理员批准执行。"))
            } else {
                val output = executeCommand(plugin, pending.command)
                session.messages.add(toolMessage(pending.toolCallId, "命令已由管理员批准执行，输出：\n$output"))
                sendToGroup(plugin, session, AgentMessageFormatter.executedCard(pending.command, output))
            }
        } else {
            sendToGroup(plugin, session, AgentMessageFormatter.rejectedNotice(session.displayName(memberOpenId)))
            session.messages.add(toolMessage(pending.toolCallId, "操作被管理员拒绝，请向用户说明无需执行。"))
        }

        val config = plugin.agentConfig
        if (config != null && config.usable) {
            plugin.submitAsync { runLoop(plugin, session, config) }
        }
    }

    /** 校验审批人权限：任务发起人 / 配置管理员 / 群管理员名单。 */
    private fun isApprovalAuthorized(plugin: HuHoBot, session: AgentSession, groupId: String, memberOpenId: String): Boolean {
        plugin.log_info("Agent 审批权限检查: memberOpenId=$memberOpenId, requestUserId=${session.requestUserId}, group=$groupId")
        if (memberOpenId.isBlank()) {
            plugin.log_warning("Agent 审批: group_member_openid 为空，无法验证权限")
            return false
        }
        if (memberOpenId == session.requestUserId) return true
        if (memberOpenId in plugin.adminList()) return true
        if (CommandRepositories.administrators.contains(groupId, memberOpenId)) return true
        plugin.log_warning("Agent 审批: $memberOpenId 不在管理员列表中")
        return false
    }

    /** 构建审批按钮键盘（同意 / 拒绝，仅管理员可见）。 */
    private fun buildApprovalKeyboard(approvalId: String): Keyboard {
        val builder = Keyboard.KeyboardBuilder.create()
        val row = builder.addRow()
        row.addButton()
            .setLabel("同意").setVisitedLabel("已同意").setStyle(1)
            .setActionType(1).setActionData("$ACTION_PREFIX$approvalId:yes")
            .setPermissionType(1).setUnSupportTips("请升级QQ客户端后再操作")
            .build()
        row.addButton()
            .setLabel("拒绝").setVisitedLabel("已拒绝").setStyle(0)
            .setActionType(1).setActionData("$ACTION_PREFIX$approvalId:no")
            .setPermissionType(1).setUnSupportTips("请升级QQ客户端后再操作")
            .build()
        row.build()
        return builder.build()
    }

    /** 向会话所在群发送 Markdown（自动分片）。 */
    private fun sendToGroup(plugin: HuHoBot, session: AgentSession, content: String) {
        if (session.stopped || content.isBlank()) return
        for (chunk in splitMessages(content)) {
            if (session.stopped) return
            plugin.sendMarkdownToGroup(session.groupOpenId, chunk)
        }
    }

    /** 超长内容按行分片。 */
    private fun splitMessages(content: String): List<String> {
        if (content.length <= MAX_MESSAGE_CHARS) return listOf(content)
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        for (line in content.lineSequence()) {
            if (current.isNotEmpty() && current.length + line.length + 1 > MAX_MESSAGE_CHARS) {
                chunks.add(current.toString())
                current.clear()
            }
            current.append(line).append('\n')
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    /** 保留首条系统消息，裁剪历史到上限。 */
    private fun trimContext(messages: List<JSONObject>): List<JSONObject> {
        if (messages.size <= MAX_CONTEXT_MESSAGES) return messages
        return listOf(messages.first()) + messages.takeLast(MAX_CONTEXT_MESSAGES - 1)
    }

    /** 标记会话完成。 */
    private fun finish(plugin: HuHoBot, session: AgentSession) {
        session.finished = true
    }

    /** 清除指定用户在指定群的会话。 */
    fun clearSession(plugin: HuHoBot, groupOpenId: String, requestUserId: String) {
        val removed = sessions.entries.removeIf { (_, session) ->
            session.requestUserId == requestUserId && session.groupOpenId == groupOpenId
        }
        if (removed) {
            plugin.log_info("Agent 会话已手动清除：group=$groupOpenId user=$requestUserId")
        }
    }

    /** 紧急停止指定用户在指定群的所有未完成会话。 */
    fun stopAgent(plugin: HuHoBot, groupOpenId: String, requestUserId: String) {
        var count = 0
        for (session in sessions.values) {
            if (session.requestUserId != requestUserId || session.groupOpenId != groupOpenId || session.finished) continue
            session.stopped = true
            session.awaitingApproval = null
            count++
        }
        plugin.log_info("Agent 紧急停止：group=$groupOpenId user=$requestUserId 停止了 $count 个会话")
    }

    private fun systemMessage(content: String): JSONObject = JSONObject().apply {
        put("role", "system")
        put("content", content)
    }

    private fun userMessage(content: String): JSONObject = JSONObject().apply {
        put("role", "user")
        put("content", content)
    }

    private fun assistantMessage(content: String?, toolCalls: JSONArray): JSONObject = JSONObject().apply {
        put("role", "assistant")
        put("content", content)
        put("tool_calls", toolCalls)
    }

    private fun toolMessage(toolCallId: String, content: String): JSONObject = JSONObject().apply {
        put("role", "tool")
        put("tool_call_id", toolCallId)
        put("content", content)
    }
}
