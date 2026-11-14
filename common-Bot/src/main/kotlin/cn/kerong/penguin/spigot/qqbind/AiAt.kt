package cn.kerong.penguin.spigot.qqbind

import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.qqpd.User

/**
 * AI 艾特接口（1.7.5.5 覆盖：ai.at-enabled，默认开）。
 *
 * 两个方向：
 * 1. **入向（解析用户艾特）**：用户消息里艾特了某人（如「你好，@张三，这人是谁？」），
 *    content 里是 `<@openid>` 占位片段 + mentions 数组（openid + 群昵称）。旧版把占位
 *    整体剥掉送给 AI——AI 根本看不到「艾特了谁」。现在（开关开启时）把占位还原为
 *    `@昵称`，AI 能看到完整语境；
 * 2. **出向（AI 自己调用艾特）**：通过 system prompt 附注告知 AI「回复中可输出
 *    `[at:昵称]` 标记艾特某成员」。发送前 [renderOutgoing] 把标记解析为
 *    QQ 官方 Markdown 艾特语法 `<@openid>`（msg_type=2 才解析，AI 回复链路本就是
 *    Markdown 模式）。昵称 → OpenId 反查两级：本条消息 mentions 数组（艾特当前
 *    在场的成员最准）→ [OpenIdDirectory] 反向目录（机器人历史上见过的成员）；
 *    两级都未命中时降级为纯文本 `@昵称`（艾特不生效但文本不丢，绝不因昵称
 *    陌生而丢字）。
 *
 * 开关关闭（ai.at-enabled: false）时入向恢复旧行为（剥掉占位），system prompt
 * 不附接口说明——AI 不会输出标记，出向渲染零匹配零开销。
 */
object AiAt {

    /** AI 输出里的艾特标记：[at:昵称]。昵称不含 [ ]，限长防灾难匹配；允许空
     * 昵称（[at:] 形态直接剥掉，不把坏标记原样漏进群里）。 */
    private val AT_MARKER = Regex("""\[at:([^\[\]]{0,64})]""")

    /** content 里的 @ 占位片段（群格式 `<@openid>` / 频道格式 `<@!id>` / 裸 id）。 */
    private val MENTION_PLACEHOLDER = Regex("""<@!?([A-Za-z0-9_-]+)>""")

    /**
     * system prompt 附注（开关开启时拼在 ai.system-prompt 之后）：
     * 告知 AI 艾特接口的存在与用法。措辞克制——只说「确有需要时」使用，
     * 避免 AI 每句都带艾特刷屏。
     */
    fun systemPromptSuffix(): String =
        "\n你可以使用艾特接口：确有需要提醒某位群成员时，在回复正文中输出 [at:该成员的群昵称] " +
            "标记即可艾特对方（昵称须与用户消息中出现的完全一致，不确定昵称时不要使用该标记）。"

    /**
     * 入向：把用户消息 content 里的 @ 占位片段还原为 `@昵称`（送 AI 前的正文净化）。
     *
     * 昵称来源：mentions 数组（openid + username）。机器人自身的占位剥掉
     * （群昵称对 AI 无意义，行首占位在入口处已剥，这里兜底）；未携带昵称的
     * 成员占位同样剥掉（乱码 token 对模型是噪声）；普通成员占位替换为 `@昵称`。
     *
     * 调用方开关判断：ai.at-enabled 关闭时走旧逻辑（QqText.removeMentionPlaceholders）。
     */
    fun parseIncomingMentions(body: String, event: GroupMessageEvent): String {
        if (!body.contains("<@")) return body
        // openid → 昵称（本条消息的 mentions 数组，机器人自身标记为 null）
        val nicknames = HashMap<String, String?>()
        val mentions: Array<User>? = try {
            event.rawMessage?.mentions
        } catch (_: Throwable) {
            null
        }
        if (mentions != null) {
            for (member in mentions) {
                val id = member.id ?: continue
                val isBot = try {
                    member.bot == true
                } catch (_: Throwable) {
                    false
                }
                val name = try {
                    member.username
                } catch (_: Throwable) {
                    null
                }
                nicknames[id] = if (isBot || name.isNullOrEmpty()) null else name
            }
        }
        return MENTION_PLACEHOLDER.replace(body) { match ->
            val id = match.groupValues[1]
            when (val name = nicknames[id] ?: OpenIdDirectory.lookupNickname(id)) {
                null -> "" // 机器人自身 / 未知昵称：剥掉占位
                else -> "@$name"
            }
        }
    }

    /**
     * 出向：把 AI 回复中的 `[at:昵称]` 标记渲染为官方艾特 `<@openid>`。
     *
     * 反查两级（见类注释）；未命中降级纯文本 `@昵称`。
     * 无标记时原样返回同一实例（零开销快速路径）。
     */
    fun renderOutgoing(text: String, event: GroupMessageEvent): String {
        if (!text.contains("[at:")) return text
        // 昵称 → openid：本条消息 mentions（一级，当前在场最准）
        val byNickname = HashMap<String, String>()
        val mentions: Array<User>? = try {
            event.rawMessage?.mentions
        } catch (_: Throwable) {
            null
        }
        if (mentions != null) {
            for (member in mentions) {
                val id = member.id ?: continue
                // 1.7.5.7：机器人成员不进反查表——把 AI 的艾特渲染成
                // <@其他机器人openid> 会触发对方机器人回复（机器人互踩
                // 死循环）；对机器人的 [at:] 降级纯文本 @昵称
                val isBot = try {
                    member.bot == true
                } catch (_: Throwable) {
                    false
                }
                if (isBot) continue
                val name = try {
                    member.username
                } catch (_: Throwable) {
                    null
                }
                if (!id.isNullOrEmpty() && !name.isNullOrEmpty()) byNickname[name] = id
            }
        }
        return AT_MARKER.replace(text) { match ->
            val nickname = match.groupValues[1].trim()
            if (nickname.isEmpty()) return@replace ""
            val openId = byNickname[nickname]
                ?: OpenIdDirectory.lookupOpenIdByNickname(nickname)
            if (openId != null) "<@$openId>" else "@$nickname"
        }
    }
}
