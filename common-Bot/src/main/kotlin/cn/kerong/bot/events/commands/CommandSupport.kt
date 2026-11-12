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
 * OpenId 反查等「同一命令按身份展示不同内容」的场景使用
     * （无权限时不应拒绝执行，只是隐藏敏感字段，因此不能复用会回复提示的
     * [requireAdmin]）。
     *
 * 按钮门禁已验身优先（1.7.5.4）：平台门禁按钮（krg:acmd:）的回调
     * 经 [cn.kerong.bot.events.MenuButtons] 以点击者身份合成事件执行
     * 命令，合成事件的 author 里带有 [BUTTON_ADMIN_VERIFIED] 标记
     * （QQ 官方消息 payload 无此字段，真实手打消息不可能携带）——
     * 交互链路里的管理员命令判定只在按钮下发处（平台侧）做一次，
     * 到达命令链路后凭此标记直接放行，不再依赖合成事件缺省没有的
     * 群角色字段重判（修复真管理员点按钮被误报「无权限」）。
     */
    protected fun isAdmin(plugin: KERONG, event: GroupMessageEvent): Boolean {
        if (isButtonVerifiedAdmin(event)) return true
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
 // 无权限提示改走消息模板 no-admin-permission.md（messages 段可换文件）
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
     * 按钮平台门禁已验管理员：合成事件 author 里带 [BUTTON_ADMIN_VERIFIED]
     * 标记（仅 krg:acmd: 回调到达时由 [cn.kerong.bot.events.MenuButtons]
     * 写入——QQ 官方真实消息 payload 无此字段，无法伪造）。
     */
    private fun isButtonVerifiedAdmin(event: GroupMessageEvent): Boolean = try {
        "1" == event.sender?.meta?.getString(BUTTON_ADMIN_VERIFIED)
    } catch (_: Throwable) {
        false
    }

    /**
     * 清洗并裁剪命令执行输出。
     *
 * 超长裁剪——服务器回显可能长达数千行，QQ 群消息内容有
     * 长度限制，超长回复会被平台直接拒绝（在敏感词审核之前裁剪，
     * 审核接口同样处理不了超长文本）。
     *
 * 内容清洗——服务器控制台输出常携带 ANSI 转义序列
     * （\u001b[38;2;...m 之类）、Minecraft 旧版 § 颜色码与其他控制
     * 字符，这类内容发到 QQ 平台会被拒收（HTTP 400，表现为「执行完
     * 没有任何回复」）。发送前统一剥离：保留换行与普通可见字符，
     * 去掉 ANSI 转义 / § 码 / 其余控制字符。
     */
    protected fun clipCommandOutput(output: String): String {
        val text = sanitizeConsoleText(output).trim()
        if (text.length <= COMMAND_OUTPUT_MAX_CHARS) return text
        val budget = text.take(COMMAND_OUTPUT_MAX_CHARS)
        val cut = budget.lastIndexOf('\n').takeIf { it > 0 } ?: budget.length
        return text.take(cut).trimEnd() + "\n……（命令输出过长，已截断，完整输出见服务器控制台）"
    }

    /** ANSI 转义序列（CSI / OSC 形态）。 */
    private val ansiEscapeRegex = Regex("\u001B\\[[0-9;:?]*[ -/]*[@-~]|\u001B\\][^\u0007]*(\u0007|\u001B\\\\)")

    /**
     * 控制台文本清洗：剥离 ANSI 转义、§ 颜色码（旧版 Minecraft 文本）
     * 与除换行 / 制表外的控制字符（控制字符会被 QQ 平台拒收）。
     */
    internal fun sanitizeConsoleText(text: String): String {
        if (text.isEmpty()) return text
        val noAnsi = ansiEscapeRegex.replace(text, "")
        val sb = StringBuilder(noAnsi.length)
        for (ch in noAnsi) {
            val code = ch.code
            val keep = when {
                ch == '\n' || ch == '\t' -> true
                code < 32 || code == 127 -> false          // 其余控制字符（含 ESC 残留）
                else -> true
            }
            when {
                keep -> sb.append(ch)
                ch == '\r' -> {}                            // 回车直接丢弃（Windows 换行）
            }
        }
        return sb.toString().replace("§[0-9a-fk-orxA-FK-ORx]".toRegex(), "")
    }

    companion object {
        /**
 * 命令执行结果回复的字符上限：QQ 群消息内容有长度
         * 限制，超长回复被平台拒绝导致「执行完没有回复」，超限截断。
         */
        internal const val COMMAND_OUTPUT_MAX_CHARS = 1200

        /**
         * 按钮平台门禁已验管理员标记（1.7.5.4）：krg:acmd: 回调按钮
         * 点击到达（QQ 平台已实时校验点击者是现任群主 / 群管理员）后，
         * [cn.kerong.bot.events.MenuButtons] 以点击者身份构造合成事件时
         * 写进 author 的验身凭证。QQ 官方消息 payload 无此字段，真实
         * 手打消息不可能携带——只能由本插件在平台门禁通过后写入。
         */
        internal const val BUTTON_ADMIN_VERIFIED = "krg_btn_admin"
    }
}
