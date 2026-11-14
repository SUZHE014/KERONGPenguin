package cn.kerong.bot.tools

import cn.kerong.bot.KERONG
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/**
 * 群消息发送者显示名解析器（1.7.5.8，chat-format.group-sender-name）。
 *
 * 背景：QQ 官方机器人接口在群消息里只提供**一个**昵称字段（author.username），
 * 它就是该成员在群内显示的名字（设置过群名片为群名片，未设置为其 QQ 昵称），
 * 官方没有单独提供「QQ 资料昵称」的通道（群成员资料接口在内测/需平台邀请，
 * SDK 未封装）——因此「QQ 用户名」与「群昵称」在官方接口下是同一个值。
 *
 * 两种模式：
 * - group（默认）：显示事件返回的群昵称（即群内显示名，历史行为）；
 * - player：优先显示该成员绑定的游戏玩家名（按 OpenId 反查，只读不建档案），
 *   未绑定 / 查询失败回退群昵称。
 *
 * 任何异常一律回退群昵称，绝不阻断转发链路。
 */
object GroupSenderName {

    /**
     * 解析群消息转发到游戏时的显示名。
     *
     * @param plugin 平台入口（读取 chatFormat.groupSenderName）
     * @param event 群消息事件（player 模式下取 sender.openid 反查绑定）
     * @param groupNickname 事件返回的群昵称（调用方已解析，作为回退值）
     */
    fun resolve(plugin: KERONG, event: GroupMessageEvent, groupNickname: String): String {
        if (plugin.chatFormat.groupSenderName != "player") return groupNickname
        return try {
            val contact = event.sender
            val openid = contact?.openid ?: contact?.id
            val bound = QqBindManager.getInstance().getBoundPlayerNameByOpenid(openid)
            if (bound.isNullOrEmpty()) groupNickname else bound
        } catch (_: Throwable) {
            groupNickname
        }
    }
}
