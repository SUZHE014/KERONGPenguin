package cn.huohuas001.bot

import cn.huohuas001.bot.events.GroupMessageHandler
import cn.huohuas001.bot.events.commands.BaseCommand
import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.provider.plugin
import cn.huohuas001.bot.tools.PluginFileLog
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
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

    /** 群命令处理器（未启动时为 null）。 */
    val messageHandler: GroupMessageHandler?
        get() = groupMessageHandler

    /**
     * 启动 QQ 客户端。
     *
     * 0.1.5.2：移除了 System.out 输出过滤器（旧实现对每次控制台输出
     * 都捕获全线程栈，是 CPU / 内存占用的重要来源）；SDK 日志已通过
     * LogSink 全量接管（见 HuHoBot.initializeRuntime），不会再进入控制台。
     *
     * 0.1.5.4：连接生命周期日志（初始化/鉴权/装配/连接成功/超时）改为
     * 控制台 + 插件日志文件双写，不再只留存在控制台，事后可追溯。
     *
     * 0.1.5.5：断线自动恢复——
     * 1. 开启 SDK 的 anyCloseReconnect（对任意关闭码，含 QQ 网关正常关闭的 1000，
     *    3 秒后自动重新鉴权重连，修复旧版“wss closed with code 1000 后机器人永久掉线”）；
     * 2. 新增常驻连接看门狗：连接持续断开超过约 2 分钟（SDK 自身重连卡死时）
     *    由插件兑底触发重连，并写入日志文件。
     *
     * 1.5.3 连接诊断与自愈：
     * - Starter.Config.webSocketListener 挂载 [WssDiagnostics]，SDK 每帧收发/连接事件都会回调；
     *   WSS 错误（含完整堆栈）与全部帧对话写入插件日志文件，事后可从
     *   logs/qq/qq-bind-日期.log 完整还原鉴权过程（修复“堆栈没有记录在日志里面”）；
     * - 鉴权失败（Hello 后 60 秒无 READY）主动重连，不再干等平台 2 分钟的 4009 踢线；
     * - SDK 心跳停跳时插件代发心跳，防止 4009 会话超时；
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
            PluginFileLog.infoAndKeep("QQ 机器人客户端初始化（AppID: $appid）…")
            groupMessageHandler = GroupMessageHandler(currentPlugin)
            val session = Starter(appid, "", secret).also { starter = it }
            // 仅订阅群消息相关事件
            session.config.code = Intents.PUBLIC_INTENTS.and(Intents.GROUP_INTENTS)
            // 0.1.5.5：任意关闭码均自动重连（含 1000 正常关闭；QQ 网关定期踢连接属正常现象，
            // SDK 会在 3 秒后重新鉴权并恢复会话）
            session.config.anyCloseReconnect = true
            // 1.5.3：挂载连接诊断钩子（全帧落盘 / 错误堆栈落盘 / 心跳兜底 / 鉴权失败快速重连）
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
    private fun watchConnectionState(currentPlugin: cn.huohuas001.bot.HuHoBot) {
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
    private fun startConnectionWatchdog(currentPlugin: cn.huohuas001.bot.HuHoBot) {
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
        val filtered = currentPlugin.auditText(messageWithoutPrefix)
        val content = currentPlugin.formatGameMessage(playerName, filtered)
        sendPayloadToGroups(V2MsgData().setContent(content), "转发游戏聊天")
    }

    /** 播报玩家加入。 */
    fun broadcastPlayerJoin(playerName: String) {
        if (starter == null) return
        if (!plugin.playerEventFormat().joinEnabled) return
        sendTextToGroups(plugin.formatPlayerJoinMessage(playerName), "发送玩家进服通知")
    }

    /** 播报玩家退出。 */
    fun broadcastPlayerQuit(playerName: String) {
        if (starter == null) return
        if (!plugin.playerEventFormat().quitEnabled) return
        sendTextToGroups(plugin.formatPlayerQuitMessage(playerName), "发送玩家退服通知")
    }

    /** 向所有配置的群发送文本。 */
    private fun sendTextToGroups(content: String, action: String) {
        if (content.isBlank()) return
        sendPayloadToGroups(V2MsgData().setContent(content), action)
    }

    /** 将消息载荷发送到所有配置的群。 */
    private fun sendPayloadToGroups(payload: V2MsgData, action: String) {
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
        sendPayloadToGroups(payload, "发送 Markdown")
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
        try {
            session.bot?.groupBaseV2?.send(groupOpenId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
        } catch (error: Exception) {
            currentPlugin.log_error("向QQ群 $groupOpenId 发送 Markdown 失败: ${error.message}")
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
            session.bot?.groupBaseV2?.send(groupId, payload.toJSONString(), Channel.SEND_MESSAGE_HEADERS)
            QqBindManager.logVerbose(
                "[AI引用] 引用回复发送成功（引用 ID=$referenceId，内容长度=${markdownContent.length}）"
            )
        } catch (error: Throwable) {
            QqBindManager.logVerbose(
                "[AI引用] 引用回复发送失败: ${error.message}，回退普通被动回复"
            )
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
            event.sendMessage(message)
            true
        } catch (error: Exception) {
            currentPlugin.log_error("回复图片消息失败: ${error.message}")
            false
        }
    }

    /** 停止客户端。 */
    fun shutdown() {
        shuttingDown = true
        try {
            WssDiagnostics.stop()
        } catch (_: Throwable) {
        }
        try {
            starter?.shutdown()
        } catch (_: Throwable) {
        } finally {
            starter = null
        }
    }
}
