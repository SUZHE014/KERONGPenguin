package cn.huohuas001.bot

import cn.huohuas001.bot.agent.AgentInteractionListener
import cn.huohuas001.bot.events.GroupMessageHandler
import cn.huohuas001.bot.events.commands.BaseCommand
import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.provider.plugin
import cn.huohuas001.bot.tools.QqBotConsoleOutputFilter
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
     * @param appid         QQ 机器人 AppId
     * @param secret        QQ 机器人 Secret
     * @param logFilePattern SDK 日志文件名模板（null 表示不落盘）
     */
    fun launchClient(appid: String, secret: String, logFilePattern: String? = null) {
        val currentPlugin = plugin
        val suppressConsoleOutput = currentPlugin.shouldSuppressQqBotConsoleOutput()
        if (suppressConsoleOutput) {
            QqBotConsoleOutputFilter.install()
        } else {
            QqBotConsoleOutputFilter.uninstall()
        }
        try {
            groupMessageHandler = GroupMessageHandler(currentPlugin)
            val session = Starter(appid, "", secret).also { starter = it }
            // 仅订阅群消息相关事件
            session.config.code = Intents.PUBLIC_INTENTS.and(Intents.GROUP_INTENTS)
            session.run()
            session.registerListenerHost(groupMessageHandler)
            session.registerListenerHost(AgentInteractionListener())
            session.APPLICATION.logger.setLogLevel(1)
            session.APPLICATION.logger.setOutFile(logFilePattern)
            MenuManager.syncGroupPanels(session, currentPlugin.groupOpenIdList())
        } catch (error: Exception) {
            if (suppressConsoleOutput) {
                QqBotConsoleOutputFilter.uninstall()
            }
            throw error
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
     */
    fun replyWithImgBytes(event: GroupMessageEvent, imageBytes: ByteArray) {
        val currentPlugin = plugin
        val session = starter
        if (session == null) {
            currentPlugin.log_warning("QQ 机器人未启动，无法回复图片消息")
            return
        }
        if (imageBytes.isEmpty()) {
            currentPlugin.log_warning("图片字节为空，无法回复图片消息")
            return
        }
        val message = MessageChain().image(imageBytes)
        try {
            event.sendMessage(message)
        } catch (error: Exception) {
            currentPlugin.log_error("回复图片消息失败: ${error.message}")
        }
    }

    /** 停止客户端并还原控制台输出。 */
    fun shutdown() {
        try {
            starter?.shutdown()
        } finally {
            QqBotConsoleOutputFilter.uninstall()
        }
    }
}
