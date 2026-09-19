package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/**
 * 身份认证命令（管理员执行敏感操作前的二次认证）。
 */
class AuthenticationCommands : CommandSupport() {

    /** /认证 —— 查询自己的认证状态；管理员可用 "/认证 @某人" 标记他人。 */
    @Commands("认证")
    fun authenticate(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (params.isBlank()) {
            val authenticated = CommandRepositories.authentication.contains(groupId(event), userId(event))
            reply(plugin, event, if (authenticated) "你已认证" else "你未认证")
            return
        }
        if (!requireAdmin(plugin, event)) return
        // 支持 “认证 @某人” 或直接传 OpenId
        val targetOpenId = params.trim().substringAfterLast(' ')
        CommandRepositories.authentication.authenticate(groupId(event), targetOpenId)
        reply(plugin, event, "认证状态已更新")
    }

    /** /解除认证 <OpenId> —— 管理员撤销用户认证。 */
    @Commands("解除认证")
    fun removeAuthentication(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        if (params.isBlank()) {
            event.sendMessage("请指定要解除认证的OpenId")
            return
        }
        CommandRepositories.authentication.revoke(groupId(event), params.trim())
        reply(plugin, event, "认证已解除")
    }
}
