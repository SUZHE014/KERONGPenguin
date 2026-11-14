package cn.kerong.bot.events

import cn.kerong.bot.GroupAtTracker
import cn.kerong.bot.ClusterState
import cn.kerong.bot.KERONG
import cn.kerong.bot.QClient
import cn.kerong.bot.RecentMessageGate
import cn.kerong.bot.ReplySeqGuard
import cn.kerong.bot.events.commands.AdministrationCommands
import cn.kerong.bot.events.commands.BaseCommand
import cn.kerong.bot.events.commands.BindCommands
import cn.kerong.bot.events.commands.CheckInCommands
import cn.kerong.bot.events.commands.CoinsLeaderboardCommands
import cn.kerong.bot.events.commands.LeaderboardCommands
import cn.kerong.bot.events.commands.PublicCommands
import cn.kerong.bot.tools.QqText
import cn.kerong.penguin.spigot.qqbind.AiAt
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
 // /motd 与 /在线服务器 命令已移除（MotdCommands 下线）
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
 // 为本次消息投递分配全局唯一回复序号基数（防 QQ 平台 40054005 消息去重）。
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
        val rawContent = event.rawMessage?.content ?: return
 // msg_id 级去重——全量模式下同一条 @ 消息可能先后以
        // GROUP_MESSAGE_CREATE（带 <@> 前缀）与 GROUP_AT_MESSAGE_CREATE
        //（前缀已剥）两种事件到达；QQ 官方也提示弱网/会话恢复时会重投递
        // 同一 msg_id。不去重会导致同一条消息被回复/转发两次。
        // id 为空的异常事件不去重直接放行。
        if (!RecentMessageGate.tryAcquire(event.rawMessage?.id)) return
 // 登记本群最近一条真实消息 id——菜单按钮点击后的合成事件靠它拿到
 // 可引用的 msg_id 走被动回复（QQ 被动回复只能引用收到的真实消息，
 // 时限约 5 分钟，详见 [RecentGroupMessages]）；失败零影响
        RecentGroupMessages.record(groupId, event.rawMessage?.id)
 // 剥离行首 @ 占位片段（全量模式 content 原样保留 <@openid>
        // 前缀，导致 /命令 前缀匹配失败——先剥再进命令分发；无片段零开销）。
        //
 // 修复：@其他用户的消息不触发命令——群里发「@张三 /查在线」
        // 是成员间的对话（@ 的是人不是机器人），旧版把行首 @ 片段与命令内
        // @ 片段无差别剥掉，照样命中命令，机器人误插嘴。现在按 @ 对象分流：
        // - @机器人（三通道判定 [isMentionedBot]）：行为不变，照旧剥行首
        //   片段、命令正常命中（1.7.4「@机器人 + 指令」特性保持）
        // - 只 @ 其他人：保留消息里的 <@…> 片段不剥——后续命令匹配因片段
        //   残留自然失败，数字自动绑定 / 多玩家选择确认同样不命中；
        //   转发到游戏不受影响（转发链路用 rawContent 与 mentions 数组）
        // - 无任何 @ 片段：零开销快速路径，与旧版完全一致
        val mentionsBot = isMentionedBot(event)
        val mentionBarred = !mentionsBot && rawContent.contains("<@")
        val content =
            if (mentionBarred) rawContent
            else QqText.stripLeadingMentionPlaceholders(rawContent)

 // 记录群成员昵称（OpenId → 群昵称目录，供 /查信息（原 /查询OpenID）反查展示），
        // 失败不影响消息处理（仅内存写入，持久化异步）
        OpenIdDirectory.recordEvent(event)

 // 群自动绑定——bot.groups 未填写时，任何群互动
        // 消息（命令 / 聊天均可）自动把该群 OpenId 写入白名单并重载
        // （[AutoGroupBinder]；不拦截消息，本条继续正常处理）
        AutoGroupBinder.tryBind(groupId)

 // 多玩家选择的数字确认拦截——优先级最高（在群白名单检查与
        // 自动绑定之前：玩家名片 / 查信息在任意群可用，它们触发的选择
        // 会话也应能在任意群确认；只有 1-2 位纯数字且存在同群同人的活跃
        // 会话时才消费，其余消息零影响）。在此拦截意味着 @机器人 + 数字
        // 不会被判定为 AI 对话（用户口径：艾特机器人发数字代码是选择确认）
        if (PlayerSelection.tryConsume(event, content)) return

        // 查信息（OpenId 查询）与玩家名片（统计卡片）命令始终放行（任何群
        // 可用）；切换服务器（多服务器集群指令）同样任意群可用；其余命令仅
        // 允许配置的群。
 // 旧名「查询open」前缀兼容匹配已按用户指令移除
        // （旧版指令兜底全部下线，指令只认现名）。
        if (!content.contains("查信息") && !content.contains("玩家名片") && !content.contains("切换服务器")) {
            if (groupId == null || !isAllowedGroup(groupId)) return
        }

 // 纯数字消息自动绑定 —— 群里发出一串纯数字（不带任何其他文字）
        // 时，若该数字恰好是某位玩家当前的绑定码，发送者即完成绑定
        // （与 /绑定 <验证码> 同一回复模板 bind-success）。
        // 不匹配任何绑定码时静默放行（不影响全量转发与 AI 对话）。
        // （多服务器集群下置于路由之前：绑定码由持有该码的服务器实例消费，
        // 与群当前选择无关）
        if (tryAutoBindByDigits(event, content)) return

 // 多服务器集群路由（未配置集群时全部旁路，
        // 行为与单服版本完全一致）。
        // - 群未选择：命令 → 按 msg_id 哈希选出的仲裁实例弹「选择服务器」
        //   按钮后全部拦截（等待用户选择）；非命令消息（聊天 / @机器人）→
        //   按群哈希确定性路由到默认服务器；
        // - 群已选择：仅所选服务器继续处理，其余实例直接返回。
        // 参考 HuHoBot 的多服务器选择体验（其为主控-适配器架构，本插件为
        // 对等实例广播协调，详见 [ClusterState] KDoc）。
        if (!filterByCluster(event, content, groupId)) return

        // 1. 依次尝试命令分发（1.7.4：@机器人 + 指令同样在此命中——消息内
        // 的 @ 片段与前导斜杠在命令匹配前被剥离，与直接发送指令走完全
        // 同一条路径，因此「@机器人 查信息」「@机器人 /菜单」均可执行；
 // 只 @ 其他人的消息跳过命令分发（见入口 mentionBarred
        // 注释），“@张三 /查在线”不再触发机器人回复）
        if (!mentionBarred) {
            if (dispatchCommand(event)) return
        }

        // 2. @机器人触发 AI 对话（非 / 开头）。
 // 根因修复：QQ 群消息的 content 已被平台去除 @机器人前缀、
        //    mentions 数组也不含机器人自身（官方文档明确「不含@机器人自身」），
        //    旧的「content 含 <@」门槛对群消息永远不成立（频道消息才有效）——
        //    群里 @机器人 发消息从未被判定为 AI 对话。现改为三通道判定
        //    （GROUP_AT 事件标记 / mentions 的 bot=true / <@文本），见
        //    [isMentionedBot]；AI 功能未开启时不再吞消息，落到全量转发
        if (!content.trim().startsWith("/") && isMentionedBot(event)) {
            if (handleAiChat(event, content, groupId)) {
                return
            }
            // AI 功能未开启 → 落到下方全量转发（旧版 @ 消息凭空消失）
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
 * 多服务器集群路由（详见 [ClusterState] KDoc）。
     *
     * @return true = 本实例继续处理这条消息；false = 本实例让位（所选 / 默认
     *   归属其他服务器，或命令已被选择按钮接管）
     */
    private fun filterByCluster(event: GroupMessageEvent, content: String, groupId: String?): Boolean {
        if (!ClusterState.isMultiServer) return true
        if (groupId.isNullOrEmpty()) return true
        val selected = ClusterState.selectedServer(groupId)
        if (selected != null) {
            return selected == ClusterState.selfServerName
        }
        // 群未选择：命令 → 仲裁实例弹选择按钮后统一拦截（等待选择）
        if (content.trim().startsWith("/")) {
            val msgId = try {
                event.rawMessage?.id
            } catch (_: Throwable) {
                null
            }
            if (msgId != null && ClusterState.isArbiterFor(msgId)) {
                ServerSelectButtons.sendSelector(
                    event,
                    "检测到多台服务器连接本机器人，执行命令前请先选择服务器：",
                )
            }
            return false
        }
        // 非命令消息（聊天 / @机器人）：未选择时按群哈希确定性路由
        return ClusterState.defaultServerFor(groupId) == ClusterState.selfServerName
    }

    /**
 * 纯数字消息自动绑定尝试。
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
            val result = try {
                manager.confirmBinding(text, qq)
            } catch (_: Throwable) {
                QqBindManager.BindResult.INVALID
            }
            when (result) {
                QqBindManager.BindResult.SUCCESS -> {
                    QqBindManager.logQuiet("[自动绑定] 纯数字消息匹配绑定码 玩家绑定成功 QQ=$qq")
                    MessageTemplates.sendDirect(event, "bind-success")
                    true
                }
 // 码匹配但已达绑定上限——用户意图明确，如实提示
                // （码保留，解绑后重发同一码仍可绑定）；黑名单与无效码保持
                // 静默放行（与旧版一致：不向群里无关数字闲聊回复任何内容）
                QqBindManager.BindResult.LIMIT -> {
                    QqBindManager.logQuiet("[自动绑定] 绑定码有效但已达绑定上限 QQ=$qq")
                    MessageTemplates.sendDirect(event, "bind-limit", mapOf(
                        "count" to manager.listBoundPlayersByQq(qq).size.toString(),
                        "max" to manager.maxPlayersPerQq.toString(),
                    ))
                    true
                }
                else -> false
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

    /**
     * 按钮互动合成事件入口：菜单按钮点击后以点击者身份执行对应命令。
     *
     * 由 [MenuButtons] 构造「合成群消息事件」（群 OpenId + 点击者 OpenId +
     * 命令文本，无真实消息 id——回复走主动消息）后调用本方法。这里补齐与
     * 真实消息入口相同的门禁，再进命令分发：
     * - 多服务器集群：本群不归本服处理时静默让位（互动事件每台实例都会
     *   收到广播，与消息路由同口径）；
     * - 群白名单：与真实消息一致（查信息 / 玩家名片 / 切换服务器任意群
     *   可用，其余命令仅配置的群）。
     *
     * @return 命令是否被执行
     */
    fun dispatchSynthetic(event: GroupMessageEvent): Boolean {
        val groupId = try {
            event.groupOpenId ?: event.groupId
        } catch (_: Throwable) {
            null
        }
        if (groupId != null && !ClusterState.shouldServeGroup(groupId)) return false
        val content = try {
            event.rawMessage?.content.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (!content.contains("查信息") && !content.contains("玩家名片") && !content.contains("切换服务器")) {
            if (groupId == null || !isAllowedGroup(groupId)) return false
        }
        return dispatchCommand(event)
    }

    /**
     * 判断消息是否 @ 了机器人。
     *
 * 三通道（按可靠性排序）：
     * 1. **GROUP_AT_MESSAGE_CREATE 事件标记**：[GroupAtTracker] 在 WSS 帧层
     *    捕获（事件类型是唯一可靠信号——该事件只在用户艾特本机器人时
     *    推送，艾特其他机器人不会产生；且该事件的 content 已被平台剥掉
     *    @前缀、mentions 不含机器人）；
     * 2. mentions 数组含**本机器人 id**（精确匹配）：全量模式
     *    （GROUP_MESSAGE_CREATE）下 @机器人时 mentions 会带本机器人条目。
     *    1.7.5.7 修复：旧版「member.bot == true 即判定被艾特」是泛匹配——
     *    bot 标志只说明该成员是机器人，不说明被艾特的是本机器人；群里
     *    艾特其他机器人（如另一个 AI 机器人）时 mentions 同样带 bot=true
     *    条目，旧判定误把「艾特其他机器人」当成「艾特本机器人」→ AI
     *    误触发插嘴 + 回复艾特发送人。现只认 id 精确命中（botSelfId /
     *    botAppId 双源），本通道退化为全量模式的补充判定；
     * 3. 文本里的 <@ 片段匹配机器人 id（频道消息形态，保留兼容）。
     */
    private fun isMentionedBot(event: GroupMessageEvent): Boolean {
 // 通道一：GROUP_AT 事件标记（群消息主判定）
        if (GroupAtTracker.isAtBot(event.rawMessage?.id)) return true
        var botSelfId: String? = null
        try {
            val starter = QClient.starter
            if (starter?.bot != null) botSelfId = starter.bot.id
        } catch (_: Throwable) {
        }
        val botAppId = plugin.botAppId
        // 1) mentions 数组中含本机器人（精确 id 匹配；1.7.5.7 删除 bot
        //    泛匹配，见上方 KDoc 通道 2——艾特其他机器人不再误判）
        try {
            val mentions: Array<User>? = event.rawMessage?.mentions
            if (mentions != null) {
                for (member in mentions) {
                    val memberId = member.id ?: continue
                    if (botSelfId != null && botSelfId.isNotEmpty() && memberId == botSelfId) return true
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

    /**
     * 处理 @机器人 的 AI 对话。
     *
     * @return true = 消息已消费（AI 已回复或已给出提示）；false = AI 功能
     *   未开启，消息继续走全量转发（1.6.7.5：旧版直接吞掉，@ 消息凭空消失）
     *
 * AI 调用移出 WSS 消息分发线程——AiChat.chat 是同步 HTTP
     * （connect 10s + read 30s，失败重试更久），旧版在 WSS 读取线程里
     * 直接执行，AI 回复期间同连接上后续所有群消息 / 命令全部排队等它；
     * 现整体移入异步线程（submitAsync），WSS 线程即刻返回继续收消息，
     * 回复照旧引用发送者原消息（QQ 被动回复窗口 5 分钟，远大于 AI 耗时）。
     */
    private fun handleAiChat(event: GroupMessageEvent, content: String, groupId: String?): Boolean {
        try {
            val manager = try {
                QqBindManager.getInstance()
            } catch (_: Throwable) {
                return false
            }
            if (!manager.isAiEnabled) return false

 // 净化 QQ 表情标签（旧实现把 <faceType=...,ext="base64">
            // 原样送给 AI，AI 收到一段乱码）；正文里 @ 其他人的占位片段
            // 处理分两路（1.7.5.5 覆盖 ai.at-enabled，默认开）：
            // - 开：不再剥掉，而是还原为 @昵称（如「你好，@张三，这人是谁？」
            //   AI 能看到被艾特的是谁），见 [AiAt.parseIncomingMentions]；
            // - 关：恢复旧行为（全部剥离，乱码 token 对模型是噪声）
            var body = QqText.sanitize(content.trim())
            if (manager.isAiAtEnabled) {
                body = AiAt.parseIncomingMentions(body, event).trim()
            } else {
                val mentions: Array<User>? = event.rawMessage?.mentions
                if (mentions != null) {
                    for (member in mentions) {
                        val memberId = member.id ?: continue
                        body = body.replace("<@!$memberId>", "")
                            .replace("<@$memberId>", "")
                            .replace("<$memberId>", "")
                            .replace(memberId, "")
                    }
                }
                // 兜底剔除正文里剩余的任何 <@…> 占位片段（不在 mentions 列表中的形态）
                body = QqText.removeMentionPlaceholders(body).trim()
            }
            if (body.isEmpty()) {
                MessageTemplates.sendPlain(event, "ai-chat-empty")
                return true
            }
            val userId = safeUserId(event)
            val preparedBody = body
            plugin.submitAsync {
                try {
                    var reply = AiChat.chat(preparedBody, manager, groupId, userId)
                    // 1.7.5.5 覆盖：AI 艾特接口出向——AI 回复中的 [at:昵称] 标记
                    // 在发送前渲染为 QQ 官方艾特 <@openid>（Markdown msg_type=2
                    // 解析；反查两级：本条 mentions → OpenIdDirectory 反向目录；
                    // 未命中降级纯文本不丢字）。开关关闭时 AI 不知晓接口不会
                    // 输出标记，此处跳过零开销
                    if (manager.isAiAtEnabled) {
                        reply = AiAt.renderOutgoing(reply, event)
                    }
                    val aiContent = "${manager.qqAiOutputPrefix} $reply"
 // AI 对话日志仅写入文件，不再上控制台（隐私泄露修复）
                    QqBindManager.logVerbose("[AI对话] 回复内容长度=${aiContent.length} 准备发送")
 // 发送方式（1.7.5.6 覆盖轮：AI 提醒方式三选一 ai.reminder-mode）：
 // - at：回复开头艾特发送人（<@openid>，用户收到 QQ 提醒），不引用
 // - quote：引用发送者的原消息（QQ 官方 MessageReference 协议，1.7.5.5
 //   前的默认行为），失败时在 QClient.replyMarkdownWithReference 内部
 //   逐级回退，回复不会丢失
 // - none：既不艾特也不引用（走普通被动回复：仍带 msg_id 被动通道，
 //   不带 message_reference，群里不渲染引用框）
                    val reminder = manager.aiReminderMode()
                    val senderId = try {
                        val contact = event.sender
                        contact?.openid ?: contact?.id
                    } catch (_: Throwable) {
                        null
                    }
                    val mentionPrefix = if (reminder == "at" && !senderId.isNullOrEmpty()) {
                        "<@$senderId>\n"
                    } else null
                    val outbound = if (mentionPrefix != null) "$mentionPrefix$aiContent" else aiContent
                    try {
                        GroupMsgSender.sendReply(event, outbound, quote = reminder == "quote")
                    } catch (replyError: Throwable) {
                        QqBindManager.logVerbose("[AI对话] 回复发送失败: ${replyError.message}")
                    }
                } catch (t: Throwable) {
                    MessageTemplates.sendPlain(event, "ai-chat-error", mapOf("error" to (t.message ?: t.toString())))
                }
            }
            return true
        } catch (t: Throwable) {
            MessageTemplates.sendPlain(event, "ai-chat-error", mapOf("error" to (t.message ?: t.toString())))
            return true
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
 // 全量转发只看总配置 features.full-amount（1.7.5 覆盖第三轮：群级
 // 独立开关已删——旧版 command-state.ini 里的 [full-forwarding] 段
 // 不再读取，随下次保存自然消失；/全量 命令现在切换总配置本身）
            val enabled = plugin.fullAmount
            if (!enabled || !plugin.chatFormat.postChat) return

            var senderName = "unknown"
            val contact: Contact? = event.sender
            if (contact?.username != null) senderName = contact.username!!

            val baseMessage = event as? BaseMessageEvent<*>
            val metadata: JSONObject? = baseMessage?.metadata
            val attachments: JSONArray? = metadata?.getJSONArray("attachments")

 // 解析引用消息（message_type=103，被引原文在 msg_elements[0]）
            val quote = QqText.extractQuote(metadata)

            val parts = ArrayList<String>()
            val rawContent = event.rawMessage?.content
 // 净化 QQ 表情标签——旧实现把 <faceType=...,ext="base64…"> 原样
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
 // 合并转发消息（聊天记录）防刷屏——QQ 会把合并聊天记录
            // 整段渲染成「=== 消息 N === / [消息内容] / [发送者]」的多行文本
            // 模板（或 message_type=102 聊天记录）下发，旧版原样转发到游戏
            // 聊天一次刷几十上百行；1.6.9.5 起直接以固定占位「[合并聊天记录]」
            // 单行转发（与 [图片] / [语音] / [视频] 占位同风格，不透出内容
            // 与条数），玩家想知道内容回 QQ 群点开卡片看
            if (QqText.isMergedForward(metadata, rawContent)) {
                parts.add("[合并聊天记录]")
            } else if (!hasImage && trimmed.isNotEmpty()) {
                parts.add(trimmed)
            }
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
 // 引用消息带被引上下文（[回复 昵称「被引摘要」] 前缀）
            if (quote != null) message = QqText.quotePrefix(quote) + message
 // 敏感词审核（配置审核接口时为同步 HTTP，最坏 15s）移出
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
