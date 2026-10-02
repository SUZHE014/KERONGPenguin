package cn.huohuas001.bot

import cn.huohuas001.bot.tools.PluginFileLog
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QQ 消息异步发送队列（1.5.4.2：修复“QQ 消息同步导致服务器内发送消息有延迟”）。
 *
 * ## 问题背景
 *
 * 旧版把“发送 QQ 群消息”直接放在调用线程上同步执行，而发送链路包含两类
 * 重 IO（最坏合计 15 秒以上）：
 * 1. 敏感词审核二审（OpenAI 兼容接口，connect 5s + read 10s 超时）；
 * 2. 每个群一次同步 HTTP POST（QQ 开放平台 API）。
 *
 * 调用线程分别是：
 * - Spigot `AsyncPlayerChatEvent` 聊天管线：所有监听器执行完毕后消息才会
 *   广播给服务器内玩家——同步发送 = 玩家在游戏内看到自己的聊天有延迟；
 * - `PlayerJoinEvent` / `PlayerQuitEvent` 主线程：同步发送 = 全服 tick 卡顿。
 *
 * ## 修复方式
 *
 * 所有“游戏 → QQ”方向的主动消息（聊天转发、进出服播报、主动 Markdown 推送）
 * 统一提交到本队列，**入队即返回**，调用线程零阻塞：
 * - 单线程 FIFO 串行发送：消息顺序与聊天顺序严格一致，不会乱序；
 * - 相邻两次发送之间保留最小间隔（QQ 群消息接口频控友好，避免触发限流丢消息）；
 * - daemon 线程：队列永不阻止 JVM 退出（不加重服务器关闭问题）；
 * - 任务异常全部捕获并记日志，队列线程不会死亡。
 *
 * QQ 事件线程上的被动回复（replyMarkdown 等带 msg_id / msg_seq 的引用回复）
 * **不走本队列**：保持同步发送以维持被动回复的时序语义。
 */
internal object OutboxQueue {

    /** 相邻两次发送的最小间隔（毫秒），防 QQ 群消息接口频控。 */
    private const val MIN_SEND_INTERVAL_MS = 250L

    /** 停机时等待队列清空的最长时间（毫秒），不阻塞服务器关闭。 */
    private const val DRAIN_TIMEOUT_MS = 2_000L

    /** 队列线程名（日志排查用）。 */
    private const val THREAD_NAME = "KERONGPenguin-QQ-Outbox"

    private val closed = AtomicBoolean(false)

    /** 最近一次实际发出消息的时间戳（频控间隔用）。 */
    @Volatile
    private var lastSendAt = 0L

    /** 单线程串行执行器（daemon）。lazy：插件未启动 QQ 客户端时不建线程。 */
    private val executor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
        }
    }

    /**
     * 提交一个发送任务（FIFO 串行执行）。
     *
     * 停机后入队的任务会被静默丢弃（仅记 debug 日志），调用方无需感知。
     */
    fun submit(action: () -> Unit) {
        if (closed.get()) return
        try {
            executor.execute {
                try {
                    waitInterval()
                    action()
                } catch (t: Throwable) {
                    try {
                        PluginFileLog.write("[消息队列] 发送任务异常: ${t.message ?: t.toString()}")
                    } catch (_: Throwable) {
                    }
                } finally {
                    lastSendAt = System.currentTimeMillis()
                }
            }
        } catch (_: RejectedExecutionException) {
            // 停机竞态：executor 已关闭，丢弃即可
        }
    }

    /** 频控间隔：距离上一次发送不足最小间隔时等待补齐。 */
    private fun waitInterval() {
        val last = lastSendAt
        if (last <= 0L) return
        val delta = System.currentTimeMillis() - last
        if (delta >= MIN_SEND_INTERVAL_MS) return
        try {
            Thread.sleep(MIN_SEND_INTERVAL_MS - delta)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 停机：停止接收新任务，尽力让已排队消息发完（最多 [DRAIN_TIMEOUT_MS]）。
     * 队列线程是 daemon，即使没等到也不阻塞 JVM 退出。
     */
    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        try {
            executor.shutdown()
            executor.awaitTermination(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
    }
}
