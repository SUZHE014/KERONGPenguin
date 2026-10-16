package cn.kerong.bot

import cn.kerong.bot.compat.SptCompat
import cn.kerong.bot.events.GroupMessageHandler
import cn.kerong.bot.events.commands.BaseCommand
import cn.kerong.bot.provider.BotShared
import cn.kerong.bot.provider.plugin
import cn.kerong.bot.tools.PluginFileLog
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.Starter
import io.github.kloping.qqbot.api.Intents
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import io.github.kloping.qqbot.entities.ex.Markdown
import io.github.kloping.qqbot.entities.ex.msg.MessageChain
import io.github.kloping.qqbot.entities.qqpd.Channel
import io.github.kloping.qqbot.http.data.V2MsgData

/**
 * QQ 机器人客户端：封装 kloping SDK 的启动与消息收发。
 */
object QClient {

    /** 当前 SDK 会话实例（未启动时为 null）。 */
    @Volatile
    var starter: Starter? = null
        private set

    private var groupMessageHandler: GroupMessageHandler? = null

    /** 是否处于主动停机（插件卸载 / 服务器关服），看门狗与兑底重连停止工作。 */
    @Volatile
    private var shuttingDown = false

    /** 最近一次观测到 WebSocket 处于连接状态的时间戳（从未连接过为 0）。 */
    @Volatile
    private var lastOpenAt = 0L

    /** 注册额外的群命令处理器（需在客户端启动后调用）。 */
    fun registerCommand(command: BaseCommand) {
        val handler = groupMessageHandler ?: error("QQ client has not been launched")
        handler.registerCommand(command)
    }

    /**
     * 启动 QQ 客户端。
     *
     * 0.1.5.2：移除了 System.out 输出过滤器（旧实现对每次控制台输出
     * 都捕获全线程栈，是 CPU / 内存占用的重要来源）；SDK 日志已通过
     * LogSink 全量接管（见运行时初始化 initializeRuntime），不会再进入控制台。
     *
     * 0.1.5.4：连接生命周期日志（初始化/鉴权/装配/连接成功/超时）改为
     * 控制台 + 插件日志文件双写，不再只留存在控制台，事后可追溯。
     *
     * 0.1.5.5：断线自动恢复——
     * 1. 开启 SDK 的 anyCloseReconnect（对任意关闭码，含 QQ 网关正常关闭的 1000，
     *    3 秒后自动重新鉴权重连，修复旧版“wss closed with code 1000 后机器人永久掉线”）
     *    1.6.2：断线重连改由 [ReconnectManager] 统一编排计数——控制台输出本轮
     *    第 1 次 / 最后一次 / 成功 / 超时等关键提示（1.6.6.3 起中间过程只写
     *    文件，重连风暴不再刷屏），连续 5 次失败暂停自动重连
     *    （提示可用 /hb chs 手动重试，30 分钟后自动再试，最多 3 轮）；
     * 2. 新增常驻连接看门狗：连接持续断开超过约 2 分钟（SDK 自身重连卡死时）
     *    由插件兑底触发重连，并写入日志文件。
     *
     * 1.5.3 连接诊断与自愈（钩子为纯观察者，放行 SDK 全部协议处理）：
     * - Starter.Config.webSocketListener 挂载 [WssDiagnostics]，SDK 每帧收发/连接事件都会回调；
     *   连接错误（含完整堆栈）与协议关键帧（Hello/READY/Invalid Session/鉴权包）写入插件
     *   日志文件，事后可从 logs/qq/qq-bind-日期.log 完整还原鉴权过程
     *   （修复“堆栈没有记录在日志里面”）；业务事件帧仅内存计数，不解析不落盘；
     * - 鉴权失败（Hello 后 60 秒无 READY）主动重连，不再干等平台 2 分钟的 4009 踢线；
     * - SDK 心跳停跳时插件代发心跳；心跳已发但平台连续 3 周期无应答（假死）时主动重连；
     * - 启动时对 AppID 凭据做一次健康自检（与 SDK 同源的 token 接口），
     *   凭据失效时日志会给出明确提示。
     *
     * @param appid         QQ 机器人 AppId
     * @param secret        QQ 机器人 Secret
     * @param logFilePattern SDK 日志文件名模板（null 表示不落盘）
     */
    fun launchClient(appid: String, secret: String, logFilePattern: String? = null) {
        val currentPlugin = plugin
        try {
            shuttingDown = false
            lastOpenAt = 0L
            ReconnectManager.onLaunch()
            PluginFileLog.infoAndKeep("QQ 机器人客户端初始化（AppID: $appid）…")
            groupMessageHandler = GroupMessageHandler(currentPlugin)
            val session = Starter(appid, "", secret).also { starter = it }
            // 仅订阅群消息相关事件
            session.config.code = Intents.PUBLIC_INTENTS.and(Intents.GROUP_INTENTS)
            // 0.1.5.5：任意关闭码均自动重连（含 1000 正常关闭；QQ 网关定期踢连接属正常现象，
            // SDK 会在 3 秒后重新鉴权并恢复会话）
            session.config.anyCloseReconnect = true
            // 1.5.3：挂载连接诊断钩子（纯观察者，放行 SDK 协议处理；
            // 注意 kloping SDK 约定：钩子返回 false 会拦截 SDK 自身逻辑，必须返回 true）
            session.config.webSocketListener = WssDiagnostics
            WssDiagnostics.start()
            // 1.5.3：AppID 凭据健康自检（异步，不阻塞连接流程）
            currentPlugin.submitAsync {
                try {
                    PluginFileLog.infoAndKeep(QqTokenHealth.check(appid, secret))
                } catch (_: Throwable) {
                }
            }
            PluginFileLog.infoAndKeep("正在连接 QQ 开放平台并鉴权…")
            // 1.5.4.2：模块化类加载兼容层（NeoForge 模组环境 union: 协议下 SDK 包扫描
            // NPE，导致组件装配失败、命令系统全瘫；兼容层优先走原生扫描，
            // 失败时按 jar 条目枚举兜底，Spigot 行为不变）
            SptCompat.injectModuleCompatScanner(session.APPLICATION)
            session.run()

            // SDK 组件装配检测：扫描失败时 bot 实例不会创建，命令将无法响应
            val botId = try {
                session.bot?.id
            } catch (_: Throwable) {
                null
            }
            if (botId.isNullOrEmpty()) {
                PluginFileLog.errorAndKeep(
                    "QQ 机器人组件装配异常（Bot 实例未创建），命令将无法响应，请携带完整日志反馈给开发者"
                )
            } else {
                PluginFileLog.infoAndKeep("QQ 机器人组件装配完成（Bot ID: $botId）")
            }

            session.registerListenerHost(groupMessageHandler)
            PluginFileLog.infoAndKeep("群消息监听已注册（命令系统就绪）")
            session.APPLICATION.logger.setLogLevel(1)
            session.APPLICATION.logger.setOutFile(logFilePattern)
            if (logFilePattern != null) {
                PluginFileLog.infoAndKeep("QQ 机器人 SDK 日志将写入文件")
            }
            PluginFileLog.infoAndKeep("正在同步群快捷菜单…")
            MenuManager.syncGroupPanels(session, currentPlugin.groupOpenIdList())

            // 异步轮询 WebSocket 连接状态，输出明确的连接成功/失败日志
            watchConnectionState(currentPlugin)
            // 常驻看门狗：检测长时断线并兑底重连（0.1.5.5）
            startConnectionWatchdog(currentPlugin)
        } catch (error: Exception) {
            PluginFileLog.errorAndKeep("QQ 机器人启动异常: ${error.message}")
            throw error
        }
    }

    /**
     * 轮询检测 WebSocket 连接状态（最多 30 秒）。
     * 连接成功输出明确日志；超时给出可排查的失败提示。
     */
    private fun watchConnectionState(currentPlugin: cn.kerong.bot.KERONG) {
        currentPlugin.submitAsync {
            var waited = 0
            while (waited < 30_000) {
                Thread.sleep(2_000)
                waited += 2_000
                val connected = try {
                    starter?.wssWorker?.webSocket?.isOpen == true
                } catch (_: Throwable) {
                    false
                }
                if (connected) {
                    lastOpenAt = System.currentTimeMillis()
                    PluginFileLog.infoAndKeep("QQ 机器人已成功连接，等待消息中…")
                    return@submitAsync
                }
                if (starter == null) return@submitAsync
            }
            PluginFileLog.warnAndKeep(
                "QQ 机器人 WebSocket 连接超时（30 秒），请检查网络与机器人凭据；也可查看日志文件确认鉴权是否失败"
            )
        }
    }

    /**
     * 常驻连接看门狗（0.1.5.5）：每 60 秒检查一次 WebSocket 状态。
     *
     * 背景：QQ 网关会不定期断开连接（正常关闭 code 1000 / 网络波动），
     * SDK 的 anyCloseReconnect 已开启时会自行在 3 秒后重连；本看门狗只处理
     * SDK 自身重连链路卡死的极端情况——连接断开持续超过约 2 分钟时：
     * - 记录错误日志（控制台 + 文件），事后可追溯；
     * - 对当前 socket 调用非阻塞 reconnect() 兑底触发重连，
     *   连接成功后服务端会重新下发 Hello，SDK 将自动重新鉴权。
     *
     * 注意：只在“曾经连接成功过”的情况下兑底（从未连上属启动失败，另有日志），
     * 插件停机（shuttingDown）时立即退出，不与正常关服流程叠加。
     */
    private fun startConnectionWatchdog(currentPlugin: cn.kerong.bot.KERONG) {
        currentPlugin.submitAsync {
            var closedChecks = 0
            while (!shuttingDown) {
                try {
                    Thread.sleep(60_000)
                } catch (_: InterruptedException) {
                    break
                }
                if (shuttingDown || starter == null) break
                val socket = try {
                    starter?.wssWorker?.webSocket
                } catch (_: Throwable) {
                    null
                }
                if (socket != null && socket.isOpen) {
                    closedChecks = 0
                    lastOpenAt = System.currentTimeMillis()
                    continue
                }
                closedChecks++
                // 首次发现断开先等待下一轮：给 SDK 自身 3 秒重连留出足够窗口
                if (closedChecks < 2 || lastOpenAt == 0L) continue
                // 1.6.2：重连超时挂起期间（等待 30 分钟退避或 /hb chs）不兑底重连
                if (ReconnectManager.isSuspended()) continue
                PluginFileLog.errorAndKeep(
                    "检测到 QQ 机器人连接已持续断开超过 1 分钟，尝试兑底重连…"
                )
                try {
                    socket?.reconnect()
                } catch (error: Throwable) {
                    PluginFileLog.errorAndKeep("QQ 机器人兑底重连异常: ${error.message}")
                }
            }
        }
    }

    /** 转发游戏聊天到所有配置的群（需以配置的前缀开头）。 */
    fun broadcastGameMessage(playerName: String, message: String) {
        if (starter == null) return
        val currentPlugin = plugin
        val format = currentPlugin.chatFormat
        if (!format.postChat) return
        if (!message.startsWith(format.startWith)) return
        val messageWithoutPrefix = message.removePrefix(format.startWith)
        // 1.5.4.5：修复“QQ 消息同步导致服务器内发送消息有延迟”。
        // Spigot 的 AsyncPlayerChatEvent 中，所有监听器执行完毕消息才会广播给
        // 服务器内玩家；旧版在此同步执行敏感词审核（OpenAI 兼容接口，
        // connect 5s + read 10s 超时）与每群一次 QQ API 发送，全部阻塞聊天管线，
        // 导致玩家在游戏内看到自己的消息有延迟。现整体移入异步发送队列：
        // 入队即返回，聊天线程零阻塞；队列单线程 FIFO 保序，消息不会乱序。
        OutboxQueue.submit {
            val filtered = currentPlugin.auditText(messageWithoutPrefix)
            val content = currentPlugin.formatGameMessage(playerName, filtered)
            sendPayloadToGroupsSync(V2MsgData().setContent(content), "转发游戏聊天")
        }
    }

    /** 播报玩家加入。 */
    fun broadcastPlayerJoin(playerName: String) {
        if (starter == null) return
        if (!plugin.playerEventFormat().joinEnabled) return
        // 1.5.4.5：PlayerJoinEvent 在主线程触发，入队发送避免同步 HTTP 阻塞主线程 tick。
        OutboxQueue.submit {
            sendTextToGroupsSync(plugin.formatPlayerJoinMessage(playerName), "发送玩家进服通知")
        }
    }

    /** 播报玩家退出。 */
    fun broadcastPlayerQuit(playerName: String) {
        if (starter == null) return
        if (!plugin.playerEventFormat().quitEnabled) return
        // 1.5.4.5：同上，主线程事件入队发送。
        OutboxQueue.submit {
            sendTextToGroupsSync(plugin.formatPlayerQuitMessage(playerName), "发送玩家退服通知")
        }
    }

    /** 向所有配置的群发送文本（同步内核，仅由发送队列线程调用）。 */
    private fun sendTextToGroupsSync(content: String, action: String) {
        if (content.isBlank()) return
        sendPayloadToGroupsSync(V2MsgData().setContent(content), action)
    }

    /** 同步发送内核：直接向所有群发送（仅由发送队列线程调用，不阻塞调用线程）。 */
    private fun sendPayloadToGroupsSync(payload: V2MsgData, action: String) {
        val currentPlugin = plugin
        val payloadJson = JSON.toJSONString(payload)
        for (groupId in currentPlugin.groupOpenIdList()) {
            try {
                starter?.bot?.groupBaseV2?.send(groupId, payloadJson, Channel.SEND_MESSAGE_HEADERS)
            } catch (e: Exception) {
                currentPlugin.log_error("向QQ群 $groupId $action 失败: ${e.message}")
            }
        }
    }

    /** 向所有配置的群发送 Markdown（可带按钮键盘）。 */
    fun sendMarkdown(markdownContent: String, keyboard: Keyboard? = null) {
        val currentPlugin = plugin
        if (starter == null) {
            currentPlugin.log_warning("QQ 机器人未启动，无法发送 Markdown")
            return
        }
        val markdown = Markdown().setContent(markdownContent)
        val payload = V2MsgData().setContent(markdownContent).setMsg_type(2).setMarkdown(markdown)
        if (keyboard != null) {
            markdown.keyboard = keyboard
            payload.keyboard = keyboard
        }
        // 1.5.4.5：主动 Markdown 推送同样经异步发送队列，不阻塞调用线程。
        OutboxQueue.submit { sendPayloadToGroupsSync(payload, "发送 Markdown") }
    }

    /** 向指定群发送 Markdown。 */
    fun sendMarkdownToGroup(groupOpenId: String, markdownContent: String, keyboard: Keyboard? = null) {
        val currentPlugin = plugin
        val session = starter
        if (session == null) {
            currentPlugin.log_warning("QQ 机器人未启动，无法发送 Markdown")
            return
        }
        if (markdownContent.isBlank()) return
        val markdown = Markdown().setContent(markdownContent)
        val payload = V2MsgData().setContent(markdownContent).setMsg_type(2).setMarkdown(markdown)
        if (keyboard != null) {
            markdown.keyboard = keyboard
            payload.keyboard = keyboard
        }
        // 1.5.4.5：入队发送，调用线程（命令/事件处理）零阻塞。
        OutboxQueue.submit {
            try {
                session.bot?.groupBaseV2?.send(groupOpenId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
            } catch (error: Exception) {
                currentPlugin.log_error("向QQ群 $groupOpenId 发送 Markdown 失败: ${error.message}")
            }
        }
    }

    /** 在群消息事件上下文中回复 Markdown（msg_id 被动回复）。 */
    fun replyMarkdown(event: GroupMessageEvent, markdownContent: String, keyboard: Keyboard? = null) {
        val currentPlugin = plugin
        val session = starter
        if (session == null) {
            currentPlugin.log_warning("QQ 机器人未启动，无法回复 Markdown")
            return
        }
        if (markdownContent.isBlank()) return
        val markdown = Markdown().setContent(markdownContent)
        if (keyboard != null) {
            markdown.keyboard = keyboard
        }
        val payload = V2MsgData().setContent(markdownContent).setMsg_type(2).setMarkdown(markdown)
            .setMsg_id(event.rawMessage?.id).setMsg_seq(event.msgSeq)
        if (keyboard != null) {
            payload.keyboard = keyboard
        }
        try {
            val groupId = event.groupOpenId ?: event.groupId ?: return
            session.bot?.groupBaseV2?.send(groupId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
        } catch (error: Exception) {
            currentPlugin.log_error("回复 Markdown 失败: ${error.message}")
        }
    }

    /**
     * 在群消息事件上下文中回复 Markdown 并引用发送者的原消息（1.5.3，AI 对话引用回复）。
     *
     * 引用原理（QQ 官方机器人文档 MessageReference）：
     * - 请求体附带 message_reference：{"message_id": 被引用消息 ID, "ignore_get_message_error": true}；
     * - 新版平台事件中，被引用消息 ID 从消息事件 MessageScene 的 ext 数组（key=value 形式）
     *   的 msg_idx 字段获取（形如 REFIDX_xxxx）；旧版事件无该字段时回退使用事件消息 ID；
     * - SDK 的 V2MsgData 不含 message_reference 字段，故此处手工构造请求体 JSON，
     *   仍走 SDK 同一发送接口 /v2/groups/{group_openid}/messages；
     * - 失败回退链：带引用发送失败 → 普通被动回复（replyMarkdown） → 纯文本 sendMessage，
     *   确保 AI 回复任何情况下都能送达。
     */
    fun replyMarkdownWithReference(event: GroupMessageEvent, markdownContent: String) {
        val currentPlugin = plugin
        val session = starter
        if (session == null) {
            currentPlugin.log_warning("QQ 机器人未启动，无法回复引用消息")
            return
        }
        if (markdownContent.isBlank()) return
        val referenceId = extractQuoteMessageId(event)
        try {
            val groupId = event.groupOpenId ?: event.groupId ?: return
            val payload = JSONObject()
            payload["content"] = markdownContent
            payload["msg_type"] = 2
            payload["markdown"] = JSONObject().apply { put("content", markdownContent) }
            val msgId = event.rawMessage?.id
            if (!msgId.isNullOrEmpty()) payload["msg_id"] = msgId
            event.msgSeq?.let { payload["msg_seq"] = it }
            if (!referenceId.isNullOrEmpty()) {
                payload["message_reference"] = JSONObject().apply {
                    put("message_id", referenceId)
                    put("ignore_get_message_error", true)
                }
            }
            val result = session.bot?.groupBaseV2?.send(groupId, payload.toJSONString(), Channel.SEND_MESSAGE_HEADERS)
            // 送达确认：平台成功受理时返回带 message id 的 V2Result；
            // 拒绝引用协议 / 引用 ID 无效时返回体无 id（或 HTTP 层抛 RequestException 进 catch），
            // 若不校验会把“静默拒绝”误当成功，导致 AI 回复整体丢失
            if (result != null && !result.id.isNullOrEmpty()) {
                QqBindManager.logVerbose(
                    "[AI引用] 引用回复发送成功（引用 ID=$referenceId，消息 ID=${result.id}，内容长度=${markdownContent.length}）"
                )
                return
            }
            QqBindManager.logVerbose(
                "[AI引用] 引用回复未被平台确认（result=${result}），回退普通被动回复"
            )
        } catch (error: Throwable) {
            QqBindManager.logVerbose(
                "[AI引用] 引用回复发送失败: ${error.message}，回退普通被动回复"
            )
        }
        // 回退 1：不带引用的普通被动回复（既有行为）
        try {
            replyMarkdown(event, markdownContent, null)
        } catch (_: Throwable) {
            // 回退 2：纯文本
            try {
                event.sendMessage(markdownContent)
            } catch (t2: Throwable) {
                QqBindManager.logVerbose("[AI引用] 纯文本回退也失败: ${t2.message}")
            }
        }
    }

    /**
     * 提取用于引用回复的消息 ID（1.5.3）：
     * 1. 深度遍历事件原始 JSON，查找新版平台字段 msg_idx（MessageScene.ext 数组，
     *    key=value 形式，形如 "msg_idx=REFIDX_xxxx"）——命中则直接返回其值；
     * 2. 旧版事件：回退事件顶层 msg_id；
     * 3. 最终回退 rawMessage.id（既有被动回复使用的 ID，兼容旧协议）。
     */
    internal fun extractQuoteMessageId(event: GroupMessageEvent): String? {
        try {
            val metadata = (event as? io.github.kloping.qqbot.impl.message.v2.BaseMessageEvent<*>)?.metadata
            if (metadata != null) {
                deepFindValue(metadata, "msg_idx")?.let { return it }
                metadata.getString("msg_id")?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        } catch (_: Throwable) {
        }
        return event.rawMessage?.id
    }

    /** 深度优先遍历 JSON 树，返回首个匹配键的字符串值（限深 6 层防递归失控）。 */
    private fun deepFindValue(node: Any?, key: String, depth: Int = 0): String? {
        if (depth > 6 || node == null) return null
        return try {
            when (node) {
                is com.alibaba.fastjson.JSONObject -> {
                    node.getString(key)?.takeIf { it.isNotEmpty() }?.let { return it }
                    for (entry in node.entries) {
                        deepFindValue(entry.value, key, depth + 1)?.let { return it }
                    }
                    null
                }
                is com.alibaba.fastjson.JSONArray -> {
                    for (item in node) {
                        deepFindValue(item, key, depth + 1)?.let { return it }
                    }
                    null
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 在群消息事件上下文中回复图片（imgUrl 必须可公网访问）。 */
    fun replyWithImg(event: GroupMessageEvent, text: String, imgUrl: String) {
        val currentPlugin = plugin
        val session = starter
        if (session == null) {
            currentPlugin.log_warning("QQ 机器人未启动，无法回复图片消息")
            return
        }
        if (imgUrl.isBlank()) {
            currentPlugin.log_warning("图片 URL 为空，无法回复图片消息")
            return
        }
        val message = MessageChain()
            .text(if (text.isBlank()) "[图片]" else text)
            .image(imgUrl)
        try {
            event.sendMessage(message)
        } catch (error: Exception) {
            currentPlugin.log_error("回复图片消息失败: ${error.message}")
        }
    }

    /**
     * 在群消息事件上下文中回复本地图片（PNG 字节，直接 base64 上传，无需公网托管）。
     * 不附带 @ 提及。
     *
     * 0.1.5.5：返回是否发送成功（机器人断线 / 上传接口异常时 false），
     * 调用方（如 /查信息，1.5.2 前为 /个人信息）据此回退文本提示，与“卡片生成失败”区分开；
     * 失败详情已通过 log_error 双写控制台与插件日志文件。
     *
     * 1.6.1 修复「图片已发出但仍提示发送失败」：
     * - QQ 群富媒体消息（图片）的发送接口普遍要 10 秒以上才返回应答，而 SDK
     *   （kloping）内置 OkHttp 客户端 readTimeout 硬编码 10 秒——请求已经送达
     *   QQ 侧并实际投递了图片，但客户端等应答先超时抛 SocketTimeoutException，
     *   旧版一律按失败处理，于是群里出现「图片发出来了 + 紧跟一条发送失败提示」；
     * - 现在把异常分类：读超时（请求已发出、结果未知，图片大概率已投递）不再
     *   当作失败，只记一条 WARNING 日志供事后核查；连接失败 / 上传失败等
     *   （请求未送达）仍按失败处理并提示重试；
     * - 正常返回时校验 QQ 应答码 ret：200 / 空 = 成功；2022021x = QQ 已受理
     *   （异步下发 / 风控审核中，消息实际已发出）同样算成功；其余码才视为拒收。
     */
    fun replyWithImgBytes(event: GroupMessageEvent, imageBytes: ByteArray): Boolean {
        val currentPlugin = plugin
        val session = starter
        if (session == null) {
            currentPlugin.log_warning("QQ 机器人未启动，无法回复图片消息")
            return false
        }
        if (imageBytes.isEmpty()) {
            currentPlugin.log_warning("图片字节为空，无法回复图片消息")
            return false
        }
        val message = MessageChain().image(imageBytes)
        return try {
            val result = event.sendMessage(message)
            val ret = result?.ret
            when {
                // null = 平台未回执（旧 SDK 行为）/ 200 = 同步成功 / 2022021x = 已受理（异步下发或审核中）
                ret == null || ret == 200 || (ret >= 20220210 && ret <= 20220219) -> true
                else -> {
                    currentPlugin.log_error("回复图片消息被 QQ 拒收（ret=$ret, msg=${result.msg ?: "无"}）")
                    false
                }
            }
        } catch (error: Exception) {
            if (isReadTimeout(error)) {
                // 请求已送达 QQ 侧，只是没等到应答：图片大概率已经发出，不再提示失败
                currentPlugin.log_warning(
                    "回复图片消息等待 QQ 应答超时（${error.javaClass.simpleName}: ${error.message ?: "无"}）——" +
                        "请求已发出，图片大概率已投递，不再按失败处理"
                )
                true
            } else {
                currentPlugin.log_error("回复图片消息失败: ${error.message}")
                false
            }
        }
    }

    /**
     * 判断异常链里是否是「读超时」（请求已发出、应答未等到）。
     *
     * 只匹配 SocketTimeoutException 与 message 含 timeout 的 InterruptedIOException
     * （OkHttp 同步调用的超时形态）；连接被拒 / 未送达等异常不匹配，仍按失败处理。
     * 最多回溯 3 层 cause，避免深链递归。
     */
    private fun isReadTimeout(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 3) {
            when {
                current is java.net.SocketTimeoutException -> return true
                current is java.io.InterruptedIOException &&
                    (current.message ?: "").contains("timeout", ignoreCase = true) -> return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * 1.6.2：恢复机器人连接（/hb chs 手动重试 / 重连退避结束后自动重试）。
     *
     * 重新打开 SDK 自动重连（重连超时挂起期间会被关掉），并对当前 socket
     * 触发一次非阻塞重连——连接成功后服务端重新下发 Hello，SDK 自动重新鉴权，
     * 重连计数与提示由 [ReconnectManager] 统一编排。
     *
     * @return 是否已发起重连（客户端未启动时 false）
     */
    fun resumeConnection(): Boolean {
        val session = starter ?: return false
        if (shuttingDown) return false
        try {
            session.config.anyCloseReconnect = true
        } catch (_: Throwable) {
        }
        val socket = try {
            session.wssWorker?.webSocket
        } catch (_: Throwable) {
            null
        }
        return if (socket != null) {
            try {
                if (!socket.isOpen) {
                    socket.reconnect()
                }
                true
            } catch (error: Throwable) {
                PluginFileLog.errorAndKeep("[重连] 手动重连触发失败: ${error.message}")
                false
            }
        } else {
            // socket 不可达（SDK 装配异常）：重新走完整启动流程
            try {
                val appid = plugin.botAppId
                val secret = plugin.botSecret
                if (appid.isBlank() || secret.isBlank()) return false
                plugin.submitAsync {
                    try {
                        launchClient(appid, secret, plugin.qqBotLogFilePattern())
                    } catch (_: Exception) {
                    }
                }
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    /** 停止客户端。 */
    fun shutdown() {
        shuttingDown = true
        try {
            WssDiagnostics.stop()
        } catch (_: Throwable) {
        }
        val session = starter
        try {
            session?.shutdown()
        } catch (_: Throwable) {
        } finally {
            // 1.5.4.2：SDK 的 spt 框架与 common 库持有 50+ 个非 daemon 线程
            // （任务队列 40 + 定时器 + Public 静态池 9 + WSS 线程），
            // Starter.shutdown 只关 WSS，不清这些池 → 服务器 stop 后进程永不退出。
            // 此处反射清扫全部 kloping 线程池/Timer/Writer（仅触碰 io.github.kloping.*）
            try {
                SptCompat.shutdownSptRuntime(session)
            } catch (_: Throwable) {
            }
            starter = null
        }
        // 1.5.4.5：关闭异步发送队列（尽力清空已排队消息，最多 2 秒，不阻塞关服；
        // 队列线程是 daemon，即使没等到也不阻止 JVM 退出）。
        try {
            OutboxQueue.shutdown()
        } catch (_: Throwable) {
        }
    }
}
