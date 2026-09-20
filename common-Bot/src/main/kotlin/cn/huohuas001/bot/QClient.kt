package cn.huohuas001.bot

import cn.huohuas001.bot.events.GroupMessageHandler
import cn.huohuas001.bot.events.commands.BaseCommand
import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.provider.plugin
import cn.huohuas001.bot.tools.PluginFileLog
import com.alibaba.fastjson.JSON
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

    /** 在群消息事件上下文中回复 Markdown。 */
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
            starter?.shutdown()
        } catch (_: Throwable) {
        } finally {
            starter = null
        }
    }
}
