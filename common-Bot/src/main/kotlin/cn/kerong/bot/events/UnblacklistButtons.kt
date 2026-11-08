package cn.kerong.bot.events

import cn.kerong.penguin.spigot.qqbind.MessageTemplates
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import cn.kerong.bot.QClient
import cn.kerong.bot.tools.Keyboards
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * /解除黑名单 的按钮交互（1.7.4 覆盖版新增）。
 *
 * 旧版流程：/解除黑名单 列出黑名单编号清单 → 管理员按清单
 * @ 对应成员或手动输入 OpenId 解除——群里没有可点的入口。
 *
 * 现改为：列出清单的同一消息携带**内联键盘**，每个黑名单条目一个按钮，
 * 点击即解除该条目（无需再 @）。交互模式参考 HuHoBot 的消息按钮
 * （点击回调 / 发起者身份校验 / 消费后过期静默）：
 * 文档 http://docs.huhobot.dpdns.org/ 、
 * 源码 https://github.com/HuHoBot/PenguinAgent （UnbindConfirmation.kt）。
 *
 * 安全边界：黑名单管理是管理员操作，本流程沿用「发起者本人才能点」的
 * 校验（[UnbindConfirmation] 同款）——发起 /解除黑名单 的管理员点击按钮
 * 才生效，其他成员（含其他管理员）点击被静默拒绝并提示让发起者操作；
 * @成员 的旧解除路径保持不变，两种方式并存。
 */
object UnblacklistButtons {

    /** 按钮回调数据前缀（互动事件据此识别本流程）。 */
    const val ACTION_PREFIX = "krg:unban:"

    /** 待解除条目：OpenId → 展示名。 */
    private data class PendingUnban(
        val groupOpenId: String,
        val initiatorOpenId: String,
        val entries: List<Pair<String, String>>,
        val expireAt: Long,
    )

    /** 会话有效期（毫秒）：2 分钟后按钮过期（黑名单可能已变化）。 */
    private const val SESSION_EXPIRE_MILLIS = 120_000L

    /** 待解除会话表：群 OpenId + "\n" + 发起者 OpenId → 会话。 */
    private val pending = ConcurrentHashMap<String, PendingUnban>()

    /**
     * 发送带按钮的黑名单清单（覆盖同键旧会话）。
     *
     * @param entries 黑名单条目（OpenId + 展示名）
     * @return 是否成功发出按钮消息（失败时调用方回落旧版纯文本清单）
     */
    fun sendList(event: GroupMessageEvent, entries: List<Pair<String, String>>): Boolean {
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
        if (group.isEmpty() || user.isEmpty() || entries.isEmpty()) return false
        val key = "$group\n$user"
        pending[key] = PendingUnban(group, user, entries, System.currentTimeMillis() + SESSION_EXPIRE_MILLIS)
        val list = entries.mapIndexed { index, (_, name) ->
            "${index + 1}. $name"
        }.joinToString("\n")
        val message = MessageTemplates.render("unblacklist-list", mapOf(
            "count" to entries.size.toString(),
            "list" to list,
        ))
        if (message.isEmpty()) return false
        val keyboard = Keyboards.build(
            entries.map { (openId, name) ->
                Keyboards.Button(
                    label = "解除 $name",
                    visitedLabel = "已解除 $name",
                    actionData = "$ACTION_PREFIX$openId",
                    style = 0,
                )
            }
        )
        val sent = try {
            QClient.replyMarkdown(event, "<@$user>\n$message", keyboard)
        } catch (_: Throwable) {
            false
        }
        if (sent) {
            QqBindManager.logFileOnly(
                "[黑名单] 群=$group 管理员=$user 获取黑名单清单（${entries.size} 条，已发送按钮）"
            )
        }
        return sent
    }

    /**
     * 按钮点击处理。
     *
     * @return 是否接管了这次互动（不属于本流程时返回 null）
     */
    fun onInteraction(
        groupOpenId: String,
        userOpenId: String,
        buttonData: String,
        respond: (Int) -> Unit,
    ): Boolean? {
        if (!buttonData.startsWith(ACTION_PREFIX)) return null
        val targetOpenId = buttonData.removePrefix(ACTION_PREFIX)
        if (targetOpenId.isEmpty()) {
            respond(1)
            return true
        }
        if (userOpenId.isBlank()) {
            respond(1)
            return true
        }
        val key = "$groupOpenId\n$userOpenId"
        val request = pending[key]
        if (request == null) {
            // 无会话（未发起过 / 已过期 / 已被覆盖）：静默
            respond(1)
            return true
        }
        if (System.currentTimeMillis() > request.expireAt) {
            pending.remove(key)
            respond(1)
            return true
        }
        val entry = request.entries.firstOrNull { it.first == targetOpenId }
        if (entry == null) {
            // 点击的条目不在本次清单里（旧消息残留按钮）：静默
            respond(1)
            return true
        }
        // 身份校验：只有发起 /解除黑名单 的管理员本人能点（黑名单是管理员操作）
        if (request.initiatorOpenId != userOpenId) {
            respond(1)
            QClient.sendMarkdownToGroup(
                request.groupOpenId,
                "<@$userOpenId> 这不是你发起的黑名单操作，请让发起的管理员操作（或自行发送 /解除黑名单）"
            )
            return true
        }
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            respond(1)
            return true
        }
        respond(0)
        manager.removeBlacklist(targetOpenId)
        // 清单里去掉该条目，按钮消息里其余按钮仍可继续点
        val remaining = request.entries.filter { it.first != targetOpenId }
        if (remaining.isEmpty()) {
            pending.remove(key)
        } else {
            pending[key] = request.copy(entries = remaining)
        }
        QClient.sendMarkdownToGroup(
            request.groupOpenId,
            "已从黑名单移除：${entry.second}"
        )
        QqBindManager.logFileOnly(
            "[黑名单] 群=$groupOpenId 管理员=$userOpenId 按钮解除黑名单条目 ${entry.second}"
        )
        return true
    }

    /** 清空全部待解除会话（重载配置时调用）。 */
    fun clearAll() = pending.clear()
}
