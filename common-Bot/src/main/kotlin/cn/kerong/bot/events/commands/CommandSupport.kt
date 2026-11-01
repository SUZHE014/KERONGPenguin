package cn.kerong.bot.events.commands

import cn.kerong.bot.KERONG
import cn.kerong.bot.datapack.AdministratorAccessMode
import cn.kerong.bot.state.CommandRepositories
import cn.kerong.penguin.spigot.qqbind.MessageTemplates
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
    protected fun reply(plugin: KERONG, event: GroupMessageEvent, message: String) {
        event.sendMessage(plugin.auditText(message))
    }

    /**
     * 静默判定发送者是否具备管理员权限（不输出任何提示）。
     *
     * 1.6.5 新增：OpenId 反查等「同一命令按身份展示不同内容」的场景使用
     * （无权限时不应拒绝执行，只是隐藏敏感字段，因此不能复用会回复提示的
     * [requireAdmin]）。
     */
    protected fun isAdmin(plugin: KERONG, event: GroupMessageEvent): Boolean {
        val groupId = groupId(event)
        val userId = userId(event)
        val qqAdmin = memberRole(event) in setOf("owner", "admin")
        val manualAdmin = userId in plugin.adminList() ||
                CommandRepositories.administrators.contains(groupId, userId)

        val defaultMode = AdministratorAccessMode.fromConfig(plugin.adminMode())
        val configuredMode = CommandRepositories.groupSettings.administratorMode(groupId, defaultMode)
        return when (configuredMode) {
            AdministratorAccessMode.QQ -> qqAdmin
            AdministratorAccessMode.MANUAL -> manualAdmin
            AdministratorAccessMode.BOTH -> qqAdmin || manualAdmin
        }
    }

    /** 校验发送者是否具备管理员权限，无权限时自动回复提示。 */
    protected fun requireAdmin(plugin: KERONG, event: GroupMessageEvent): Boolean {
        val allowed = isAdmin(plugin, event)
        if (!allowed) {
            // 1.6.0：无权限提示改走消息模板 no-admin-permission.md（messages 段可换文件）
            MessageTemplates.sendPlain(event, "no-admin-permission")
        }
        return allowed
    }

    /** 从消息元数据中提取发送者的群身份（owner / admin / member）。 */
    private fun memberRole(event: GroupMessageEvent): String = try {
        val meta: JSONObject? = event.sender?.meta
        meta?.getString("member_role")?.lowercase(Locale.ROOT) ?: ""
    } catch (_: Exception) {
        ""
    }

    /**
     * 裁剪命令执行输出（1.6.9.5 新增）：超出上限按整行截断并加标记。
     *
     * 背景：/执行命令 / /执行 的服务器回显可能长达数千行（旧版捕获上限
     * 512 行 / 128K 字符全部拼进一条回复），QQ 群消息内容有长度限制，
     * 超长回复会被平台直接拒绝——且旧版 replyMarkdown 失败后不再回退
     * 纯文本，最终表现为「执行完命令没有任何回复」。现在发送前先裁剪
     * 到安全长度（在敏感词审核之前裁剪，审核接口同样处理不了超长文本）。
     */
    protected fun clipCommandOutput(output: String): String {
        val text = output.trim()
        if (text.length <= COMMAND_OUTPUT_MAX_CHARS) return text
        val budget = text.take(COMMAND_OUTPUT_MAX_CHARS)
        val cut = budget.lastIndexOf('\n').takeIf { it > 0 } ?: budget.length
        return text.take(cut).trimEnd() + "\n……（命令输出过长，已截断，完整输出见服务器控制台）"
    }

    companion object {
        /**
         * 命令执行结果回复的字符上限（1.6.9.5）：QQ 群消息内容有长度
         * 限制，超长回复被平台拒绝导致「执行完没有回复」，超限截断。
         */
        internal const val COMMAND_OUTPUT_MAX_CHARS = 1200
    }
}
