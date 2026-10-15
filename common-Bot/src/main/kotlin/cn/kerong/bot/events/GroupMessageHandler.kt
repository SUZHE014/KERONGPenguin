package cn.kerong.bot.events

import cn.kerong.bot.KERONG
import cn.kerong.bot.QClient
import cn.kerong.bot.ReplySeqGuard
import cn.kerong.bot.events.commands.AdministrationCommands
import cn.kerong.bot.events.commands.BaseCommand
import cn.kerong.bot.events.commands.BindCommands
import cn.kerong.bot.events.commands.CheckInCommands
import cn.kerong.bot.events.commands.CoinsLeaderboardCommands
import cn.kerong.bot.events.commands.LeaderboardCommands
import cn.kerong.bot.events.commands.MotdCommands
import cn.kerong.bot.events.commands.PublicCommands
import cn.kerong.bot.state.CommandRepositories
import cn.kerong.bot.tools.QqText
import cn.kerong.penguin.spigot.qqbind.AiChat
import cn.kerong.penguin.spigot.qqbind.GroupMsgSender
import cn.kerong.penguin.spigot.qqbind.MessageTemplates
import cn.kerong.penguin.spigot.qqbind.OpenIdDirectory
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.qqpd.User
import io.github.kloping.qqbot.entities.qqpd.v2.Contact
import io.github.kloping.qqbot.impl.ListenerHost
import io.github.kloping.qqbot.impl.message.v2.BaseGroupMessageEvent
import io.github.kloping.qqbot.impl.message.v2.BaseMessageEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * QQ 群消息入口：命令分发 → @机器人 AI 对话 → 全量转发。
 */
class GroupMessageHandler(private val plugin: KERONG) : ListenerHost() {

    private val commands: CopyOnWriteArrayList<BaseCommand> = CopyOnWriteArrayList()

    init {
        commands.add(PublicCommands())
        commands.add(AdministrationCommands())
        commands.add(MotdCommands())
        commands.add(BindCommands())
        commands.add(CheckInCommands())
        commands.add(LeaderboardCommands())
        commands.add(CoinsLeaderboardCommands())
    }

    /** 注册额外的群命令处理器。 */
    fun registerCommand(command: BaseCommand?) {
        if (command != null) commands.add(command)
    }

    @ListenerHost.EventReceiver
    fun onGroupMessage(event: GroupMessageEvent) {
        if (event == null) return
        // 1.6.2：为本次消息投递分配全局唯一回复序号基数（防 QQ 平台 40054005 消息去重）。
        // QQ 网关弱网 / 会话恢复时会重投递同一条群消息，SDK 为重投递创建全新事件对象
        // （回复序号又从 1 起算），与首次投递的回复序号重复后被平台按重复消息拒收；
        // 此处按 msg_id 计数分配互不重叠的基数（首次 =1，第 N 次重投递 =1+(N-1)×100000），
        // 之后本事件内任意路径的多条回复（文本 / Markdown / 多页图片）按基数递增，
        // 任何两条回复的 msg_seq 不会重复（详见 ReconnectManager.ReplySeqGuard 注释）
        try {
            (event as? BaseGroupMessageEvent)?.setMsgSeq(
                ReplySeqGuard.allocateBase(event.rawMessage?.id)
            )
        } catch (_: Throwable) {
        }
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

        // 1.6.0：纯数字消息自动绑定 —— 群里发出一串纯数字（不带任何其他文字）
        // 时，若该数字恰好是某位玩家当前的绑定码，发送者即完成绑定
        // （与 /绑定 <验证码> 同一回复模板 bind-success）。
        // 不匹配任何绑定码时静默放行（不影响全量转发与 AI 对话）。
        if (tryAutoBindByDigits(event, content)) return

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

    /**
     * 1.6.0：纯数字消息自动绑定尝试。
     *
     * 仅当消息为 4-12 位纯数字（前后空白除外，不含任何其他文字/符号）时，
     * 按绑定码提交（confirmBinding 内部校验有效期 / 黑名单 / 重复绑定）。
     * 绑定成功 → 回复 bind-success 模板并拦截后续处理；
     * 其余情况（非纯数字 / 无匹配码 / 绑定失败）一律返回 false，
     * 消息继续走正常流程（命令 / AI / 全量转发），不产生任何额外回复
     * （避免群里正常的数字闲聊被机器人骚扰）。
     *
     * 前置条件：QQ 绑定功能开启（qq-bind.enabled，未开启时无绑定码存在）
     * 且 /绑定 命令未被管理员关闭（commands.绑定）。
     */
    private fun tryAutoBindByDigits(event: GroupMessageEvent, content: String): Boolean {
        val text = content.trim()
        if (text.length !in 4..12) return false
        if (text.any { it !in '0'..'9' }) return false
        return try {
            val manager = try {
                QqBindManager.getInstance()
            } catch (_: Throwable) {
                return false
            }
            if (!manager.isEnabled) return false
            if (plugin.commandList()["绑定"] == false) return false
            val qq = safeUserId(event)
            if (qq == "<unknown>") return false
            val success = try {
                manager.confirmBinding(text, qq)
            } catch (_: Throwable) {
                false
            }
            if (success) {
                QqBindManager.logQuiet("[自动绑定] 纯数字消息匹配绑定码 玩家绑定成功 QQ=$qq")
                MessageTemplates.sendDirect(event, "bind-success")
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
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
                MessageTemplates.sendPlain(event, "ai-chat-empty")
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
            MessageTemplates.sendPlain(event, "ai-chat-error", mapOf("error" to (t.message ?: t.toString())))
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
