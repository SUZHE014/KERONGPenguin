package cn.kerong.bot.events

import cn.kerong.bot.QClient
import cn.kerong.penguin.spigot.qqbind.MessageTemplates
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import cn.kerong.bot.tools.Keyboards
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * 多玩家选择会话管理（1.7.4 新增，配合 qq-bind.max-players-per-qq 多玩家绑定）。
 *
 * 触发场景：QQ 群内用户使用需要「检索对应玩家」的指令（玩家名片 / 查信息 /
 * 重新绑定 / 签到 等），检测到该 QQ 名下绑定了**多个**玩家时，先发送玩家选择
 * 消息；确认后，原命令以选中的玩家继续执行。
 *
 * ## 1.7.4 覆盖版：交互改为按钮（方框可点击）
 *
 * 选择消息带**内联键盘**——每个绑定玩家一个按钮，点击即选择；旧版 QQ
 * 客户端不渲染按钮时仍可用数字回复兜底。交互模式参考 HuHoBot 的消息按钮
 * （点击回调 / 身份校验 / 消费后过期静默），源码
 * https://github.com/HuHoBot/PenguinAgent
 * （UnbindConfirmation.kt 的确认键盘与互动处理）。
 *
 * 数字确认的两种形态（兜底保留，判定顺序在 AI 对话**之前**——@机器人 发送
 * 数字代码不会被判定为 AI 对话）：
 * - 直接发送数字（如 `1`）；
 * - @机器人 + 数字（全量模式消息行首的 <@机器人> 占位片段在入口已剥离，
 *   到达本处时与直接发送形态一致）。
 *
 * 会话规则：
 * - 以（群 OpenId + 发送者 OpenId）为键，同群同人同时只有一个待选会话
 *   （新命令再次触发时直接覆盖）；
 * - 有效期 2 分钟，过期后按钮点击与数字回复均不再被拦截；
 * - 只有发起者本人能完成选择（按钮点击校验群 + 用户 OpenId，他人点击静默
 *   拒绝；数字路径天然只匹配本人的消息）。
 */
object PlayerSelection {

    /** 按钮回调数据前缀（互动事件据此识别本流程）。 */
    const val ACTION_PREFIX = "krg:psel:"

    /** 选择会话有效期（毫秒）：2 分钟内未确认自动作废。 */
    private const val SESSION_EXPIRE_MILLIS = 120_000L

    /** 待选会话（命令闭包 + 到期时间）。 */
    private class PendingSelection(
        val entries: List<QqBindManager.BoundPlayer>,
        val action: (QqBindManager.BoundPlayer) -> Unit,
        val expireAt: Long,
    )

    /** 会话表：群 OpenId + "\n" + 用户 OpenId → 待选会话。 */
    private val sessions = ConcurrentHashMap<String, PendingSelection>()

    /**
     * 命令侧统一入口：按 OpenId 检索绑定玩家并分派。
     *
     * - 未绑定任何玩家 → 回复 [notBoundTemplate]（为 null 时静默返回）；
     * - 只绑定 1 个玩家 → 直接以该玩家执行 [action]（与旧版行为一致）；
     * - 绑定多个玩家 → 发送选择消息（按钮 + 数字兜底），确认后再执行 [action]。
     *
     * [action] 闭包捕获原命令的全部上下文（原事件 / 目标成员等），
     * 在确认到达时执行；其内部回复引用原命令消息（QQ 被动回复
     * 窗口 5 分钟，远大于 2 分钟选择有效期，安全）。
     */
    fun requireBoundPlayer(
        event: GroupMessageEvent,
        manager: QqBindManager,
        openId: String?,
        notBoundTemplate: String? = null,
        notBoundVars: Map<String, String> = emptyMap(),
        action: (QqBindManager.BoundPlayer) -> Unit,
    ) {
        val players = try {
            manager.listBoundPlayersByQq(openId)
        } catch (_: Throwable) {
            emptyList()
        }
        if (players.isEmpty()) {
            if (notBoundTemplate != null) {
                MessageTemplates.sendDirect(event, notBoundTemplate, notBoundVars)
            }
            return
        }
        selectOrRun(event, players, action)
    }

    /**
     * 调用方已自行取得绑定玩家列表时的低层入口：
     * 单个玩家直接执行 [action]，多个玩家发送选择消息等待确认
     * （空列表不由本方法处理——调用方按自己的文案回复）。
     */
    fun selectOrRun(
        event: GroupMessageEvent,
        players: List<QqBindManager.BoundPlayer>,
        action: (QqBindManager.BoundPlayer) -> Unit,
    ) {
        if (players.isEmpty()) return
        if (players.size == 1) {
            action(players[0])
            return
        }
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            null
        } ?: run {
            // 管理器异常时退化为直接取第一个（不阻塞命令）
            action(players[0])
            return
        }
        begin(event, manager, players, action)
    }

    /** 发送选择消息（按钮键盘 + 数字兜底文案）并登记会话（覆盖同键旧会话）。 */
    private fun begin(
        event: GroupMessageEvent,
        manager: QqBindManager,
        players: List<QqBindManager.BoundPlayer>,
        action: (QqBindManager.BoundPlayer) -> Unit,
    ) {
        val group = try {
            event.groupOpenId ?: event.groupId ?: ""
        } catch (_: Throwable) {
            ""
        }
        val user = try {
            val contact = event.sender
            contact?.openid ?: contact?.id ?: ""
        } catch (_: Throwable) {
            ""
        }
        if (group.isEmpty() || user.isEmpty()) {
            // 拿不到稳定键：退化为直接取第一个绑定玩家（不阻塞命令）
            action(players[0])
            return
        }
        val key = "$group\n$user"
        sessions[key] = PendingSelection(players, action, System.currentTimeMillis() + SESSION_EXPIRE_MILLIS)
        val list = players.mapIndexed { index, player ->
            "${index + 1}. ${player.playerName}"
        }.joinToString("\n")
        val vars = mapOf(
            "count" to players.size.toString(),
            "list" to list,
        )
        val message = MessageTemplates.render("select-player", vars)
        // 按钮键盘：每个绑定玩家一个按钮（1.7.4 覆盖版主交互）
        val keyboard = Keyboards.build(
            players.map { player ->
                Keyboards.Button(
                    label = player.playerName,
                    visitedLabel = "已选择 ${player.playerName}",
                    actionData = "$ACTION_PREFIX${player.playerName}",
                    style = 1,
                )
            }
        )
        val sent = try {
            message.isNotEmpty() && QClient.replyMarkdown(event, "<@$user>\n$message", keyboard)
        } catch (_: Throwable) {
            false
        }
        if (!sent) {
            // 键盘消息发送失败（平台拒收 / 客户端未启动）：回落旧版纯文本
            MessageTemplates.sendDirect(event, "select-player", vars)
        }
        QqBindManager.logFileOnly(
            "[玩家选择] 群=$group 用户=$user 名下 ${players.size} 个绑定玩家，已发送选择消息（按钮 + 数字兜底）"
        )
    }

    /**
     * 消息入口拦截：当前消息是否为某个待选会话的数字确认（兜底路径）。
     *
     * 由 [GroupMessageHandler] 在**自动绑定与命令分发之前**调用——
     * 优先级：玩家选择按钮/数字确认 > 纯数字自动绑定 > 命令分发 > @机器人
     * （命令 / AI 对话）> 全量转发；因此 @机器人 + 数字不会被判定为 AI 对话。
     *
     * @return true = 消息已消费（已执行选择或已回复序号提示），不再继续后续流程
     */
    fun tryConsume(event: GroupMessageEvent, content: String): Boolean {
        val text = content.trim()
        // 仅 1-2 位纯数字视为选择尝试（3 位以上不拦截，留给自动绑定等正常流程）
        if (text.length !in 1..2) return false
        if (text.any { it !in '0'..'9' }) return false
        if (text == "0") return false // 序号从 1 起；0 不是有效选择也不提示
        val group = try {
            event.groupOpenId ?: event.groupId ?: return false
        } catch (_: Throwable) {
            return false
        }
        val user = try {
            val contact = event.sender
            contact?.openid ?: contact?.id ?: return false
        } catch (_: Throwable) {
            return false
        }
        val key = "$group\n$user"
        val session = sessions[key] ?: return false
        val index = text.toInt()
        return consumeByIndex(group, user, key, session, index, event)
    }

    /** 按序号消费会话（数字路径）：越界提示并保留会话供重试。 */
    private fun consumeByIndex(
        group: String,
        user: String,
        key: String,
        session: PendingSelection,
        index: Int,
        event: GroupMessageEvent,
    ): Boolean {
        if (System.currentTimeMillis() > session.expireAt) {
            sessions.remove(key)
            return false // 已过期：数字放行走正常流程
        }
        if (index < 1 || index > session.entries.size) {
            // 序号超出范围：回复提示，保留会话供重试
            MessageTemplates.sendDirect(event, "select-invalid", mapOf(
                "count" to session.entries.size.toString(),
            ))
            return true
        }
        val selected = session.entries[index - 1]
        sessions.remove(key)
        executeSelection(group, user, selected, session)
        return true
    }

    /**
     * 按钮点击处理（1.7.4 覆盖版主交互）。
     *
     * @param groupOpenId 互动事件所属群
     * @param userOpenId 点击者 OpenId（SDK 偶尔不带，拿不到一律拒绝）
     * @param buttonData 按钮 actionData（形如 krg:psel:<玩家名>）
     * @param respond 互动回应（0 = 客户端提示操作成功；非 0 = 静默）
     * @return 是否接管了这次互动（不属于本流程时返回 null，交后续流程）
     */
    fun onInteraction(
        groupOpenId: String,
        userOpenId: String,
        buttonData: String,
        respond: (Int) -> Unit,
    ): Boolean? {
        if (!buttonData.startsWith(ACTION_PREFIX)) return null
        val playerName = buttonData.removePrefix(ACTION_PREFIX)
        if (playerName.isEmpty()) {
            respond(1)
            return true
        }
        if (userOpenId.isBlank()) {
            // 拿不到点击者身份：一律拒绝（不能替别人完成选择）
            respond(1)
            return true
        }
        val key = "$groupOpenId\n$userOpenId"
        val session = sessions[key]
        if (session == null || System.currentTimeMillis() > session.expireAt) {
            sessions.remove(key)
            // 已处理过 / 已过期：正常情况，静默
            respond(1)
            return true
        }
        val selected = session.entries.firstOrNull { it.playerName == playerName }
        if (selected == null) {
            // 会话已被新命令覆盖，按钮上的玩家不在当前候选里：静默
            respond(1)
            return true
        }
        if (!sessions.remove(key, session)) {
            // 同一张卡片被连点两次：只消费第一次
            respond(1)
            return true
        }
        respond(0)
        executeSelection(groupOpenId, userOpenId, selected, session)
        return true
    }

    /** 执行选中玩家的后续命令闭包。 */
    private fun executeSelection(
        group: String,
        user: String,
        selected: QqBindManager.BoundPlayer,
        session: PendingSelection,
    ) {
        QqBindManager.logFileOnly(
            "[玩家选择] 群=$group 用户=$user 选择：${selected.playerName}"
        )
        try {
            session.action(selected)
        } catch (error: Throwable) {
            QqBindManager.logQuiet("[玩家选择] 执行选中玩家后续操作异常: ${error.message}")
        }
    }

    /** 清空全部待选会话（重载配置时调用，防止旧闭包引用过期配置）。 */
    fun clearAll() = sessions.clear()
}
