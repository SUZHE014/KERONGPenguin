package cn.huohuas001.bot.provider

import cn.huohuas001.bot.tools.Cancelable
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import java.util.concurrent.CompletableFuture

/**
 * 平台能力提供者接口集合：日志 / 调度 / 命令 / 消息。
 */

/** 日志输出能力。 */
interface LoggerProvider {
    fun log_info(msg: String)
    fun log_warning(msg: String)
    fun log_error(msg: String)
    fun log_debug(msg: String) = log_info(msg)
}

/** 任务调度能力。 */
interface SchedulerProvider {
    /** 在主线程尽快执行任务。 */
    fun submit(task: Runnable): Cancelable

    /** 在异步线程池执行任务。 */
    fun submitAsync(task: Runnable): Cancelable {
        val future = CompletableFuture.runAsync(task)
        return object : Cancelable {
            override fun cancel() {
                future.cancel(true)
            }
        }
    }

    /** 延迟 [delay]（tick）后执行任务。 */
    fun submitLater(delay: Long, task: Runnable): Cancelable

    /** 延迟 [delay]（tick）后开始，每 [period]（tick）重复执行任务。 */
    fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable
}

/** 服务器命令执行能力。 */
interface CommandProvider {
    /** 向服务器控制台派发命令，返回带结果的 Future。 */
    fun dispatchCommand(command: String): CompletableFuture<HExecution>
}

/** QQ 消息发送能力。 */
interface MessageProvider {
    /** 向所有游戏内玩家广播消息。 */
    fun broadcastMessage(msg: String)

    /** 向所有配置的群发送 Markdown 消息。 */
    fun sendMarkdown(markdownContent: String, keyboard: Keyboard? = null)

    /** 向指定群发送 Markdown 消息。 */
    fun sendMarkdownToGroup(groupOpenId: String, markdownContent: String, keyboard: Keyboard? = null)

    /** 在群消息事件的上下文中回复 Markdown 消息。 */
    fun replyMarkdown(event: GroupMessageEvent, content: String, keyboard: Keyboard? = null)

    /** 在群消息事件的上下文中回复图片（[imgUrl] 必须可公网访问）。 */
    fun replyWithImg(event: GroupMessageEvent, text: String, imgUrl: String)
}
