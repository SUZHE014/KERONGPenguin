package cn.huohuas001.bot.events

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.events.commands.AdministrationCommands
import cn.huohuas001.bot.events.commands.BaseCommand
import cn.huohuas001.bot.events.commands.BindCommands
import cn.huohuas001.bot.events.commands.CheckInCommands
import cn.huohuas001.bot.events.commands.MotdCommands
import cn.huohuas001.bot.events.commands.PublicCommands
import cn.huohuas001.bot.state.CommandRepositories
import cn.huohuas001.huhobotPenguin.spigot.qqbind.AiChat
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.qqpd.User
import io.github.kloping.qqbot.entities.qqpd.v2.Contact
import io.github.kloping.qqbot.impl.ListenerHost
import io.github.kloping.qqbot.impl.message.v2.BaseMessageEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * QQ 群消息入口：命令分发 → @机器人 AI 对话 → 全量转发。
 */
class GroupMessageHandler(private val plugin: HuHoBot) : ListenerHost() {

    private val commands: CopyOnWriteArrayList<BaseCommand> = CopyOnWriteArrayList()

    init {
        commands.add(PublicCommands())
        commands.add(AdministrationCommands())
        commands.add(MotdCommands())
        commands.add(BindCommands())
        commands.add(CheckInCommands())
    }

    /** 注册额外的群命令处理器。 */
    fun registerCommand(command: BaseCommand?) {
        if (command != null) commands.add(command)
    }

    @ListenerHost.EventReceiver
    fun onGroupMessage(event: GroupMessageEvent) {
        if (event == null) return
        val groupId: String? = event.groupOpenId ?: event.groupId
        val content = event.rawMessage?.content ?: return

        // 查询open ID（OpenId 查询）与查信息（统计卡片）命令始终放行（任何群可用）；
        // 其余命令仅允许配置的群。1.5.2：两命令更名（原 查信息/个人信息）。
        // “查询open” 用前缀匹配兼容大小写/空格变体（查询OpenID、查询openid 等）。
        if (!content.contains("查信息") && !content.contains("查询open")) {
            if (groupId == null || !isAllowedGroup(groupId)) return
        }

        // 1. 依次尝试命令分发
        if (dispatchCommand(event)) return

        // 2. @机器人触发 AI 对话（非 / 开头）
        if (content.contains("<@") && !content.trim().startsWith("/")) {
            if (isMentionedBot(event)) {
                handleAiChat(event, content, groupId)
                return
            }
        }

        // 3. 全量消息转发到游戏
        if (groupId != null) forwardFullGroupMessage(groupId, event)
    }

    /** 是否为允许交互的群（空配置表示不限制）。 */
    private fun isAllowedGroup(groupId: String): Boolean {
        val allowed = plugin.groupOpenIdList()
        return allowed.isEmpty() || groupId in allowed
    }

    /** 遍历命令处理器尝试处理消息。 */
    private fun dispatchCommand(event: GroupMessageEvent): Boolean {
        for (command in commands) {
            try {
                if (command.handleMessage(plugin, event)) return true
            } catch (e: Exception) {
                plugin.log_error("指令处理异常: ${e.message}")
            }
        }
        return false
    }

    /** 判断消息是否 @ 了机器人。 */
    private fun isMentionedBot(event: GroupMessageEvent): Boolean {
        var botSelfId: String? = null
        try {
            val starter = QClient.starter
            if (starter?.bot != null) botSelfId = starter.bot.id
        } catch (_: Throwable) {
        }
        val botAppId = plugin.botAppId
        // 1) mentions 数组中含机器人
        try {
            val mentions: Array<User>? = event.rawMessage?.mentions
            if (mentions != null) {
                for (member in mentions) {
                    val memberId = member.id ?: continue
                    if (botSelfId != null && botSelfId.isNotEmpty() && memberId == botSelfId) return true
                    try {
                        if (member.bot == true) return true
                    } catch (_: Throwable) {
                    }
                    if (botAppId != null && botAppId.isNotEmpty() && memberId == botAppId) return true
                }
            }
        } catch (_: Throwable) {
        }
        // 2) 文本中的 @ 片段
        try {
            val text = event.rawMessage?.content
            if (text != null) {
                if (botSelfId != null && botSelfId.isNotEmpty() &&
                    (text.contains("<@!$botSelfId>") || text.contains("<@$botSelfId>"))
                ) return true
                if (botAppId != null && botAppId.isNotEmpty() &&
                    (text.contains("<@!$botAppId>") || text.contains("<@$botAppId>"))
                ) return true
            }
        } catch (_: Throwable) {
        }
        return false
    }

    /** 处理 @机器人 的 AI 对话。 */
    private fun handleAiChat(event: GroupMessageEvent, content: String, groupId: String?) {
        try {
            val manager = try {
                QqBindManager.getInstance()
            } catch (_: Throwable) {
                return
            }
            if (!manager.isAiEnabled) return

            // 去除 @ 片段后取正文
            var text = content
            val mentions: Array<User>? = event.rawMessage?.mentions
            if (mentions != null) {
                for (member in mentions) {
                    val memberId = member.id ?: continue
                    text = text.replace("<@!$memberId>", "")
                        .replace("<@$memberId>", "")
                        .replace("<$memberId>", "")
                        .replace(memberId, "")
                }
            }
            text = text.trim()
            if (text.isEmpty()) {
                event.sendMessage("请在 @我 之后输入你想说的话～")
                return
            }
            val userId = safeUserId(event)
            val reply = AiChat.chat(text, manager, groupId, userId)
            val aiContent = "${manager.qqAiOutputPrefix} $reply"
            // 0.1.5.2：AI 对话日志仅写入文件，不再上控制台（隐私泄露修复）
            QqBindManager.logVerbose("[AI对话] 回复内容长度=${aiContent.length} 准备发送")
            try {
                event.sendMessage(aiContent)
            } catch (replyError: Throwable) {
                QqBindManager.logVerbose("[AI对话] sendMessage 失败: ${replyError.message}")
            }
        } catch (t: Throwable) {
            event.sendMessage("AI 对话失败：${t.message}")
        }
    }

    /** 安全提取发送者 OpenId。 */
    private fun safeUserId(event: GroupMessageEvent): String = try {
        val contact: Contact? = event.sender
        val openid = contact?.openid ?: contact?.id
        openid ?: "<unknown>"
    } catch (_: Throwable) {
        "<unknown>"
    }

    /** 全量转发群消息到游戏（含图片/语音/文件占位）。 */
    @Suppress("UNCHECKED_CAST")
    private fun forwardFullGroupMessage(groupId: String, event: GroupMessageEvent) {
        try {
            val enabled = CommandRepositories.groupSettings.fullForwarding(groupId, plugin.fullAmount)
            if (!enabled || !plugin.chatFormat.postChat) return

            var senderName = "unknown"
            val contact: Contact? = event.sender
            if (contact?.username != null) senderName = contact.username!!

            val baseMessage = event as? BaseMessageEvent<*>
            val metadata: JSONObject? = baseMessage?.metadata
            val attachments: JSONArray? = metadata?.getJSONArray("attachments")

            val parts = ArrayList<String>()
            val rawContent = event.rawMessage?.content
            val trimmed = rawContent?.trim() ?: ""

            var hasImage = false
            if (attachments != null) {
                for (i in 0 until attachments.size) {
                    val attachment = attachments.getJSONObject(i) ?: continue
                    val contentType = attachment.getString("content_type") ?: ""
                    if (contentType.startsWith("image/")) {
                        hasImage = true
                        break
                    }
                }
            }
            if (!hasImage && trimmed.isNotEmpty()) parts.add(trimmed)
            if (attachments != null) {
                for (i in 0 until attachments.size) {
                    val attachment = attachments.getJSONObject(i) ?: continue
                    val contentType = attachment.getString("content_type") ?: ""
                    when {
                        "voice" == contentType -> {
                            val asr = attachment.getString("asr_refer_text")
                            parts.add(if (!asr.isNullOrBlank()) "[语音] [${asr.trim()}]" else "[语音]")
                        }
                        contentType.startsWith("image/") -> parts.add("[图片]")
                        "image/gif" == contentType -> parts.add("[表情包]")
                        contentType.startsWith("video/") -> parts.add("[视频]")
                        else -> parts.add("[文件: ${attachment.getString("filename") ?: "文件"}]")
                    }
                }
            }
            if (parts.isEmpty()) return

            var message = parts.joinToString(" ")
            // @ 片段还原为昵称
            val mentions: Array<User>? = event.rawMessage?.mentions
            if (mentions != null) {
                for (member in mentions) {
                    val memberId = member.id ?: continue
                    val memberName = member.username ?: continue
                    message = message.replace("<@!$memberId>", "@$memberName")
                        .replace("<@$memberId>", "@$memberName")
                        .replace("<$memberId>", "@$memberName")
                        .replace(memberId, "@$memberName")
                }
            }
            plugin.broadcastMessage(plugin.formatGroupMessage(senderName, plugin.auditText(message)))
        } catch (_: Throwable) {
        }
    }
}
