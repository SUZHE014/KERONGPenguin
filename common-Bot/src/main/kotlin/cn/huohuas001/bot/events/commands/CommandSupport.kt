package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.datapack.AdministratorAccessMode
import cn.huohuas001.bot.state.CommandRepositories
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.qqpd.v2.Contact
import java.util.Locale

/**
 * 命令支持基类：提供群/用户标识提取、管理员校验与命令执行等通用能力。
 */
abstract class CommandSupport : BaseCommand() {

    /** 提取群 OpenId（回退到群号）。 */
    protected fun groupId(event: GroupMessageEvent): String =
        event.groupOpenId ?: event.groupId ?: ""

    /** 提取发送者用户 OpenId（回退到用户 Id）。 */
    protected fun userId(event: GroupMessageEvent): String {
        val sender: Contact? = event.sender
        return sender?.openid ?: sender?.id ?: ""
    }

    /** 审核后回复文本消息。 */
    protected fun reply(plugin: HuHoBot, event: GroupMessageEvent, message: String) {
        event.sendMessage(plugin.auditText(message))
    }

    /** 回复图片消息。 */
    protected fun replyWithImg(plugin: HuHoBot, event: GroupMessageEvent, message: String, imgUrl: String) {
        plugin.replyWithImg(event, message, imgUrl)
    }

    /** 校验发送者是否具备管理员权限，无权限时自动回复提示。 */
    protected fun requireAdmin(plugin: HuHoBot, event: GroupMessageEvent): Boolean {
        val groupId = groupId(event)
        val userId = userId(event)
        val qqAdmin = memberRole(event) in setOf("owner", "admin")
        val manualAdmin = userId in plugin.adminList() ||
                CommandRepositories.administrators.contains(groupId, userId)

        val defaultMode = AdministratorAccessMode.fromConfig(plugin.adminMode())
        val configuredMode = CommandRepositories.groupSettings.administratorMode(groupId, defaultMode)
        val allowed = when (configuredMode) {
            AdministratorAccessMode.QQ -> qqAdmin
            AdministratorAccessMode.MANUAL -> manualAdmin
            AdministratorAccessMode.BOTH -> qqAdmin || manualAdmin
        }
        if (!allowed) {
            event.sendMessage("你没有执行此命令的管理员权限")
        }
        return allowed
    }

    /** 向服务器派发命令并将执行结果回复到群。 */
    protected fun executeGameCommand(plugin: HuHoBot, event: GroupMessageEvent, command: String, direct: Boolean) {
        val outgoingCommand = if (direct) command
        else "huhobot run ${groupId(event)} ${userId(event)} $command"
        plugin.sendCommand(outgoingCommand).whenComplete { result, error ->
            if (error != null || result == null) {
                event.sendMessage("游戏桥接未配置或执行失败")
            } else {
                val raw = result.rawString
                if (raw.isBlank()) {
                    event.sendMessage("已发送执行请求")
                } else {
                    event.sendMessage(plugin.auditText(raw))
                }
            }
        }
    }

    /** 以自定义命令形式（run / adminrun）派发参数。 */
    protected fun executeCustomCommand(plugin: HuHoBot, event: GroupMessageEvent, params: String, admin: Boolean) {
        val type = if (admin) "adminrun" else "run"
        executeGameCommand(plugin, event, "huhobot $type ${groupId(event)} ${userId(event)} $params", true)
    }

    /** 从消息元数据中提取发送者的群身份（owner / admin / member）。 */
    private fun memberRole(event: GroupMessageEvent): String = try {
        val meta: JSONObject? = event.sender?.meta
        meta?.getString("member_role")?.lowercase(Locale.ROOT) ?: ""
    } catch (_: Exception) {
        ""
    }
}
