package cn.huohuas001.bot.events

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.events.commands.AdministrationCommands
import cn.huohuas001.bot.events.commands.BaseCommand
import cn.huohuas001.bot.events.commands.BindCommands
import cn.huohuas001.bot.events.commands.CheckInCommands
import cn.huohuas001.bot.events.commands.LeaderboardCommands
import cn.huohuas001.bot.events.commands.MotdCommands
import cn.huohuas001.bot.events.commands.PublicCommands
import cn.huohuas001.bot.state.CommandRepositories
import cn.huohuas001.bot.tools.QqText
import cn.huohuas001.huhobotPenguin.spigot.qqbind.AiChat
import cn.huohuas001.huhobotPenguin.spigot.qqbind.GroupMsgSender
import cn.huohuas001.huhobotPenguin.spigot.qqbind.OpenIdDirectory
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
        commands.add(LeaderboardCommands())
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

        // 1.5.3：记录群成员昵称（OpenId → 群昵称目录，供 /查询OpenID 反查展示），
        // 失败不影响消息处理（仅内存写入，持久化异步）
        OpenIdDirectory.recordEvent(event)

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

            // 去除 @ 片段后取正文；1.5.4：净化 QQ 表情标签（旧实现把
            // <faceType=...,ext="base64"> 原样送给 AI，AI 收到一段乱码）
            var text = QqText.sanitize(content)
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
            // 1.5.3：改为引用回复（引用发送者的原消息，QQ 官方 MessageReference 协议），
            // 失败时在 QClient.replyMarkdownWithReference 内部逐级回退，回复不会丢失
            try {
                GroupMsgSender.sendReply(event, aiContent)
            } catch (replyError: Throwable) {
                QqBindManager.logVerbose("[AI对话] 引用回复发送失败: ${replyError.message}")
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

    /** 全量转发群消息到游戏（含图片/语音/文件占位；1.5.4：引用上下文 + 表情净化）。 */
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

            // 1.5.4：解析引用消息（message_type=103，被引原文在 msg_elements[0]）
            val quote = QqText.extractQuote(metadata)

            val parts = ArrayList<String>()
            val rawContent = event.rawMessage?.content
            // 1.5.4：净化 QQ 表情标签——旧实现把 <faceType=...,ext="base64…"> 原样
            // 转发到游戏聊天，玩家看到一段 base64 乱码串（即“引用消息转发乱码”）
            val trimmed = QqText.sanitize(rawContent ?: "").trim()

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
            // 纯引用消息（回复者未输入正文，content 为空格）：仍转发引用摘要，
            // 否则游戏端会看到转发凭空消失
            if (parts.isEmpty() && quote != null) parts.add(QqText.quotePrefix(quote).trim())
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
            // 1.5.4：引用消息带被引上下文（[回复 昵称「被引摘要」] 前缀）
            if (quote != null) message = QqText.quotePrefix(quote) + message
            // 1.5.4.5：敏感词审核（配置审核接口时为同步 HTTP，最坏 15s）移出
            // WSS 事件分发线程，避免阻塞后续 QQ 消息与命令响应；
            // broadcastMessage 内部自行调度主线程广播进服。
            val capturedMessage = message
            val capturedSenderName = senderName
            plugin.submitAsync {
                try {
                    plugin.broadcastMessage(plugin.formatGroupMessage(capturedSenderName, plugin.auditText(capturedMessage)))
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }
}
