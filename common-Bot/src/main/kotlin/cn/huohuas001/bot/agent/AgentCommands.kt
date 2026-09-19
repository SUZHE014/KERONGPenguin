package cn.huohuas001.bot.agent

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.events.commands.CommandSupport
import cn.huohuas001.bot.events.commands.Commands
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.api.event.InterActionEvent
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.qqpd.v2.Contact
import io.github.kloping.qqbot.impl.ListenerHost

/**
 * Agent 相关 QQ 群命令。
 */
class AgentCommands : CommandSupport() {

    /** /agent <任务描述> —— 启动 AI 任务。 */
    @Commands("agent", "Agent")
    fun agent(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        AgentManager.startAgent(plugin, event, params.trim())
    }

    /** /newsession —— 清除会话上下文。 */
    @Commands("newsession", "Newsession")
    fun newsession(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val groupOpenId = event.metadata?.getString("group_openid") ?: event.groupOpenId ?: event.groupId ?: return
        val userId = event.sender?.openid ?: event.sender?.id ?: ""
        AgentManager.clearSession(plugin, groupOpenId, userId)
        event.sendMessage("✅ 会话上下文已清除，下次 /agent 将开始全新对话。")
    }

    /** /stop —— 紧急停止所有 AI 任务。 */
    @Commands("stop", "Stop")
    fun stop(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val groupOpenId = event.metadata?.getString("group_openid") ?: event.groupOpenId ?: event.groupId ?: return
        val userId = event.sender?.openid ?: event.sender?.id ?: ""
        AgentManager.stopAgent(plugin, groupOpenId, userId)
        event.sendMessage("⏹️ 已紧急停止所有 AI 任务")
    }
}

/**
 * QQ 按钮交互事件监听：转发给 AgentManager 处理审批。
 */
class AgentInteractionListener : ListenerHost() {

    @ListenerHost.EventReceiver
    fun onInterAction(event: InterActionEvent) {
        AgentManager.onInteraction(cn.huohuas001.bot.provider.plugin, event)
    }
}
