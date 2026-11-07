package cn.kerong.bot.events

import cn.kerong.penguin.spigot.qqbind.MessageTemplates
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * 多玩家选择会话管理（1.7.4 新增，配合 qq-bind.max-players-per-qq 多玩家绑定）。
 *
 * 触发场景：QQ 群内用户使用需要「检索对应玩家」的指令（查信息 / 查询open ID /
 * 重新绑定 / 签到 等），检测到该 QQ 名下绑定了**多个**玩家时，先发送玩家选择
 * 消息；用户回复数字序号确认后，原命令以选中的玩家继续执行。
 *
 * 数字确认的两种形态（都支持，判定顺序在 AI 对话**之前**——@机器人 发送
 * 数字代码不会被判定为 AI 对话）：
 * - 直接发送数字（如 `1`）；
 * - @机器人 + 数字（全量模式消息行首的 <@机器人> 占位片段在入口已剥离，
 *   到达本处时与直接发送形态一致）。
 *
 * 会话规则：
 * - 以（群 OpenId + 发送者 OpenId）为键，同群同人同时只有一个待选会话
 *   （新命令再次触发时直接覆盖）；
 * - 有效期 2 分钟，过期后数字回复不再被拦截（走正常消息流）；
 * - 只有 1-2 位纯数字会被当作选择尝试（绑定码为 4-12 位，不会误伤自动绑定）；
 *   序号在有效范围内 → 执行并消费；不在范围 → 回复提示并保留会话（可重试）。
 */
object PlayerSelection {

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
     * - 绑定多个玩家 → 发送选择消息，等待数字确认后再执行 [action]。
     *
     * [action] 闭包捕获原命令的全部上下文（原事件 / 目标成员等），
     * 在确认消息到达时执行；其内部回复引用原命令消息（QQ 被动回复
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

    /** 发送选择消息并登记会话（覆盖同键旧会话）。 */
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
        MessageTemplates.sendDirect(event, "select-player", mapOf(
            "count" to players.size.toString(),
            "list" to list,
        ))
        QqBindManager.logFileOnly(
            "[玩家选择] 群=$group 用户=$user 名下 ${players.size} 个绑定玩家，已发送选择消息"
        )
    }

    /**
     * 消息入口拦截：当前消息是否为某个待选会话的数字确认。
     *
     * 由 [GroupMessageHandler] 在**自动绑定与命令分发之前**调用——
     * 优先级：玩家选择数字确认 > 纯数字自动绑定 > 命令分发 > @机器人
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
        if (System.currentTimeMillis() > session.expireAt) {
            sessions.remove(key)
            return false // 已过期：数字放行走正常流程
        }
        val index = text.toInt()
        if (index < 1 || index > session.entries.size) {
            // 序号超出范围：回复提示，保留会话供重试
            MessageTemplates.sendDirect(event, "select-invalid", mapOf(
                "count" to session.entries.size.toString(),
            ))
            return true
        }
        val selected = session.entries[index - 1]
        sessions.remove(key)
        QqBindManager.logFileOnly(
            "[玩家选择] 群=$group 用户=$user 选择 ${index}/${session.entries.size}：${selected.playerName}"
        )
        try {
            session.action(selected)
        } catch (error: Throwable) {
            QqBindManager.logQuiet("[玩家选择] 执行选中玩家后续操作异常: ${error.message}")
        }
        return true
    }

    /** 清空全部待选会话（重载配置时调用，防止旧闭包引用过期配置）。 */
    fun clearAll() = sessions.clear()
}
