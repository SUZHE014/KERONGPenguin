package cn.huohuas001.bot

import cn.huohuas001.bot.tools.PluginFileLog
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 机器人重连编排器（1.6.2 新增）。
 *
 * ## 背景
 *
 * 旧版（≤1.6.1）的断线处理：
 * - SDK 自身 anyCloseReconnect 任意关闭码 3 秒后自动重连——重连过程对控制台完全静默，
 *   管理员只看到反复的「[WSS] 连接关闭」帧日志，不知道机器人正在重试、重试了几次、
 *   还会不会继续重试；
 * - 检测到两台服务器绑定同一个机器人（互踢）时直接 [QClient.stopForConflict] 完整停机，
 *   服务器不再响应任何 QQ 消息，只能重启插件恢复。
 *
 * ## 新行为（1.6.2）
 *
 * 1. **重试全程可见**：SDK 每发起一次重连（连接重新打开）都计入本轮第 N 次，
 *    控制台输出「第 N 次（1/5）重试连接…」；连接恢复并健康存活后输出「重连成功」；
 * 2. **每轮最多 5 次**：连续 5 次重连失败（连接打开但未存活到健康阈值又被关闭）
 *    则暂时放弃自动重连，输出「重连超时」提示：可使用指令 /hb chs 立即手动重试，
 *    或等待 30 分钟后自动进行下一轮（最多 3 轮）；
 * 3. **不再直接停机**：同 AppID 多服务器互踢判定成立时只输出醒目原因
 *    （「已有另一台服务器绑定了同一个机器人」），连接继续走同一套 5 次×3 轮重试体系；
 * 4. **防刷屏开关**：`bot.reconnect-notify: false` 可关闭「第 N 次（1/5）重试连接」
 *    的过程提示；但「重连超时 + 可用 /hb chs 手动重连」的失败提示**不可关闭**
 *    （否则管理员不知道机器人已停、怎么恢复）；
 * 5. **手动重试**：/hb chs 重置全部计数并立即恢复连接（也可在 30 分钟退避期间随时使用）。
 *
 * ## 计数模型
 *
 * - 每次 WebSocket 连接打开（[onOpen]）= 一次重试尝试（首次启动连接也计入，
 *   启动失败同样受 5 次上限保护，防止凭据错误时无限刷屏重连）；
 * - 「健康」= READY/RESUMED 鉴权成功且存活 ≥ 90 秒（与互踢检测的短命会话阈值一致；
 *   QQ 网关正常踢线的间隔远长于 90 秒）：健康会话把 attempt / bigRound 全部清零；
 * - 「失败」= 连接未健康存活即被关闭：SDK 将自动发起下一次重连（attempt 继续 +1）；
 * - attempt ≥ 5 时的关闭 → 触发 [giveUpAutoReconnect]（挂起 SDK 自动重连 + 退避）。
 *
 * 状态读取集中在 [WssDiagnostics] 的回调与看门狗线程，全部为 volatile /
 * AtomicInteger 写，无锁。
 */
object ReconnectManager {

    /** 每轮自动重试的最大次数（用户需求：共重试 5 次）。 */
    const val SMALL_RETRY_MAX = 5

    /** 大轮次上限（30 分钟一轮，最多 3 轮自动重试）。 */
    const val BIG_ROUND_MAX = 3

    /** 两轮之间的退避时长（30 分钟）。 */
    private const val BIG_BACKOFF_MILLIS = 30L * 60L * 1000L

    /** 会话「健康存活」阈值（毫秒，与互踢检测的短命会话阈值一致）。 */
    internal const val HEALTHY_SESSION_MS = 90_000L

    /** 当前小轮已尝试的重连次数（连接打开即 +1）。 */
    private val attempt = AtomicInteger(0)

    /** 已消耗完的退避大轮次数（0 = 未进入过退避）。 */
    private val bigRound = AtomicInteger(0)

    /** 挂起代际号（每次 giveUp / 手动重置自增；旧退避线程醒来时据此判断自己是否已过期）。 */
    private val generation = AtomicInteger(0)

    /** 是否已挂起 SDK 自动重连（重连超时后 true，恢复重连或重连成功后 false）。 */
    @Volatile
    private var suspended = false

    /** 最近一次连接打开的时间戳（用于健康判定）。 */
    @Volatile
    private var lastOpenAt = 0L

    /** 是否已输出过「重连成功」（本轮内去重，防止看门狗反复清零时重复提示）。 */
    @Volatile
    private var successAnnounced = false

    /** 是否输出过本轮的冲突原因（互踢判定提示只醒目输出一次）。 */
    @Volatile
    private var conflictAnnounced = false

    /** 互踢冲突提示（1.6.2：不再停机，仅醒目提示原因后继续重试体系）。 */
    @Volatile
    private var conflictReason: String? = null

    /** 当前重连尝试次数（第 N 次（1/5））。 */
    fun currentAttempt(): Int = attempt.get()

    /** 是否处于挂起状态（SDK 自动重连已关闭，等待退避或手动重试）。 */
    fun isSuspended(): Boolean = suspended

    // ---------- 回调（由 WssDiagnostics / QClient 调用） ----------

    /** 客户端启动：全部状态复位。 */
    fun onLaunch() {
        attempt.set(0)
        bigRound.set(0)
        suspended = false
        successAnnounced = false
        conflictAnnounced = false
        conflictReason = null
        lastOpenAt = 0L
        ConflictWatch.reset()
    }

    /**
     * WebSocket 连接打开（SDK 首次连接或自动重连的每一次）。
     *
     * 计入本轮第 N 次并输出过程提示（`bot.reconnect-notify` 关闭时不输出，
     * 防刷屏；挂起期间到达属防御路径，照常计数）。
     */
    fun onOpen() {
        lastOpenAt = System.currentTimeMillis()
        successAnnounced = false
        val n = attempt.incrementAndGet()
        if (notifyEnabled()) {
            PluginFileLog.infoAndKeep(
                "[重连] 第 $n/$SMALL_RETRY_MAX 次重试连接…（连接已打开，等待鉴权）"
            )
        }
    }

    /**
     * READY / RESUMED 到达：本次尝试鉴权成功。
     * 健康与否由存活时长判定（见 [onClosedAfter] 与看门狗健康检查）。
     */
    fun onSessionReady() {
        // 暂不输出：等健康存活 90 秒后由 [onHealthySession] 统一宣告成功，
        // 避免互踢场景下「连接成功→3 秒后又被踢」造成成功/失败提示来回抖动
    }

    /**
     * 会话健康存活（READY 后存活 ≥ [HEALTHY_SESSION_MS]，或关闭时存活达到阈值）。
     * 重置全部计数并宣告「重连成功」（成功提示一句话，不属防刷屏范围）。
     */
    fun onHealthySession() {
        if (!successAnnounced && attempt.get() > 0) {
            PluginFileLog.infoAndKeep(
                "[重连] QQ 机器人重连成功（第 ${attempt.get()}/$SMALL_RETRY_MAX 次尝试），消息收发已恢复"
            )
        }
        successAnnounced = true
        attempt.set(0)
        bigRound.set(0)
        suspended = false
        conflictReason = null
    }

    /**
     * 连接被关闭时回调（在关闭时刻由 [WssDiagnostics.onClose] 调用）。
     *
     * @param survivedMs 本次会话 READY 后的存活毫秒数（0 = 未能完成鉴权）
     * @param healthy 是否达到健康存活阈值（达到则视为一次成功恢复，随后又开始新一轮）
     * @return 是否已触发「重连超时」挂起（挂起后 SDK 不再自动重连）
     */
    fun onClosedAfter(survivedMs: Long, healthy: Boolean): Boolean {
        if (healthy) {
            // 健康会话正常关闭（QQ 网关例行踢线）：视为已成功过，计数清零，
            // SDK 3 秒后的自动重连按第 1 次重新计
            onHealthySession()
            return false
        }
        val n = attempt.get()
        if (n < SMALL_RETRY_MAX) {
            // 未到上限：SDK 会自动重连，下一次 onOpen 计第 n+1 次
            if (notifyEnabled() && n >= SMALL_RETRY_MAX - 1) {
                // 最后一次机会：提示这轮快用完了（仅文件留档不刷控制台）
                PluginFileLog.write("[重连] 本轮重试已用 $n/$SMALL_RETRY_MAX 次，即将进行最后一次自动重连")
            }
            return false
        }
        giveUpAutoReconnect(survivedMs)
        return true
    }

    /**
     * 重连超时：挂起 SDK 自动重连，输出不可关闭的恢复指引，
     * 并调度 30 分钟后的下一轮（最多 [BIG_ROUND_MAX] 轮）。
     */
    private fun giveUpAutoReconnect(survivedMs: Long) {
        if (suspended) return
        suspended = true
        val myGen = generation.incrementAndGet()
        val nextRound = bigRound.incrementAndGet()
        val conflict = conflictReason

        // —— 不可关闭的失败提示（用户明确要求：重试失败的重连指引不能关） ——
        val builder = StringBuilder()
        builder.append("[重连] QQ 机器人已连续 $SMALL_RETRY_MAX 次重连失败（重连超时），已暂停自动重连。")
        if (conflict != null) {
            builder.append("疑似原因：$conflict")
        }
        builder.append(" 可在服务器内使用指令 /hb chs 立即重新尝试连接。")
        if (nextRound <= BIG_ROUND_MAX) {
            val minutes = BIG_BACKOFF_MILLIS / 60000
            builder.append("未手动干预时，$minutes 分钟后将自动进行下一轮重试（第 $nextRound/$BIG_ROUND_MAX 轮，每轮 $SMALL_RETRY_MAX 次）。")
        } else {
            builder.append("自动重试的 $BIG_ROUND_MAX 轮已全部用尽，此后需要使用 /hb chs 手动恢复。")
        }
        PluginFileLog.errorAndKeep(builder.toString())

        // 关闭 SDK 自动重连 + 断开当前 socket（给 QQ 网关降温，也停止无意义的互踢循环）
        suspendConnection()

        // 30 分钟退避后自动恢复（最后一轮之后不再恢复，等待 /hb chs）
        if (nextRound <= BIG_ROUND_MAX) {
            scheduleBackoffWake(myGen, nextRound)
        }
    }

    /**
     * 挂起连接：关闭 SDK 自动重连并断开当前 socket。
     *
     * 供「重连超时退避」与「确凿冲突处置」两条路径共用：
     * - anyCloseReconnect=false → SDK 不再自行重连；
     * - 主动断开 socket → 看门狗/兑底重连均凭 [isSuspended] 停连，
     *   本服不再与另一台服务器争抢网关与消息。
     */
    private fun suspendConnection() {
        try {
            QClient.starter?.config?.anyCloseReconnect = false
        } catch (_: Throwable) {
        }
        try {
            QClient.starter?.wssWorker?.webSocket?.close()
        } catch (_: Throwable) {
        }
    }

    /** 调度退避结束后的自动恢复（旧线程凭代际号自动失效）。 */
    private fun scheduleBackoffWake(myGen: Int, round: Int) {
        val thread = Thread({
            try {
                Thread.sleep(BIG_BACKOFF_MILLIS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            // 期间发生过手动重试或重新挂起：本线程作废
            if (generation.get() != myGen || !suspended) return@Thread
            PluginFileLog.warnAndKeep(
                "[重连] 30 分钟退避结束，开始第 $round/$BIG_ROUND_MAX 轮自动重试（每轮 $SMALL_RETRY_MAX 次）"
            )
            QClient.resumeConnection()
        }, "qq-reconnect-backoff").apply { isDaemon = true }
        thread.start()
    }

    /**
     * 互踢冲突判定成立（1.6.2：不再停机）。
     * 醒目输出原因一次，之后连接走同一套 5 次×3 轮重试体系。
     */
    fun onConflictDetected(reason: String) {
        conflictReason = reason
        if (conflictAnnounced) return
        conflictAnnounced = true
        PluginFileLog.errorAndKeep(
            "[重连] $reason。本服务器不会停止运行：将继续重试连接（每轮 $SMALL_RETRY_MAX 次、" +
                "最多 $BIG_ROUND_MAX 轮自动重试，期间可用 /hb chs 手动重试）；" +
                "两台服务器共用一个机器人会互相抢占轮流掉线，建议为每台服务器配置不同的机器人（bot.app-id / bot.secret）"
        )
    }

    /**
     * 1.6.3：确凿冲突处置（[ConflictWatch] 判定：本服被动回复被 QQ 去重拒收，
     * 另一台同 AppID 服务器抢先回复了同一条群消息）。
     *
     * 与「疑似冲突」（连接级特征，[onConflictDetected] 仅提示后继续重试）不同，
     * 这是实锤：继续在线只会继续互抢消息（双方各有一半回复被拒收）。因此：
     * - 醒目输出原因与处理方法（不可关闭，与重连超时提示同一原则）；
     * - 断开本服机器人连接并停止自动重连（服务器本体与插件其余功能不受影响）；
     * - **不走 30 分钟自动轮次**：配置错误无法靠重试解决，自动重试只会反复
     *   抢占、反复断开；管理员关闭其中一台服务器（或换用不同机器人）后，
     *   用 /hb chs 手动恢复本服连接。
     */
    fun onDedupConflictConfirmed() {
        conflictReason =
            "同一机器人（bot.app-id）已绑定在另一台服务器上，两台服务器同时在回复群消息（本服回复被 QQ 去重拒收）"
        conflictAnnounced = true
        PluginFileLog.errorAndKeep(
            "[冲突] 已确认同一机器人被两台服务器同时使用：本服回复连续被 QQ 按「消息被去重(40054005)」拒收——" +
                "另一台服务器抢先回复了同一条群消息（本进程回复序号不可能自发重复）。\n" +
                "[冲突] 两台服务器共用一个机器人会导致双方回复互相挤占、图片与文本随机发送失败。" +
                "已断开本服机器人连接并停止自动重连（服务器本体与插件其余功能不受影响；" +
                "重试无法解决配置问题，不再自动重试）。\n" +
                "[冲突] 处理方法：关闭其中一台服务器的机器人（bot.app-id / secret 留空即可），" +
                "或为每台服务器申请各自的机器人（免费）；处理完成后在服务器内使用 /hb chs 手动恢复本服连接。"
        )
        if (suspended) return
        suspended = true
        generation.incrementAndGet()
        suspendConnection()
    }

    /**
     * 手动重连（/hb chs）：重置全部计数，立即恢复连接。
     *
     * @return 给命令发送者的结果文本
     */
    fun manualReconnect(): String {
        generation.incrementAndGet()
        val wasSuspended = suspended
        val usedRound = bigRound.get()
        attempt.set(0)
        bigRound.set(0)
        suspended = false
        successAnnounced = false
        ConflictWatch.reset()
        val launched = QClient.resumeConnection()
        return if (launched) {
            if (wasSuspended) {
                "已重置重连计数（此前已完成 $usedRound 轮退避）并开始重新连接，连接过程与结果见控制台"
            } else {
                "已开始重新连接，连接过程与结果见控制台"
            }
        } else {
            "QQ 客户端未启动（未配置 bot.app-id / bot.secret 或插件已停用），无法重连"
        }
    }

    /** 重试过程提示是否开启（bot.reconnect-notify，默认 true；失败指引不受此开关控制）。 */
    private fun notifyEnabled(): Boolean {
        return try {
            val instance = cn.huohuas001.bot.provider.BotShared.instance
            (instance as? org.bukkit.plugin.java.JavaPlugin)
                ?.config?.getBoolean("bot.reconnect-notify", true) ?: true
        } catch (_: Throwable) {
            true
        }
    }
}

/**
 * 回复序号守卫（1.6.2 新增）：修复 QQ 官方接口 40054005「消息被去重」。
 *
 * ## 根因
 *
 * QQ 群机器人被动回复的去重键是（msg_id, msg_seq）：同一条群消息（msg_id）的
 * 多条回复必须使用互不相同的 msg_seq。SDK（kloping）在事件对象内维护自增序号
 * （初始为 1），但 **QQ 网关在弱网 / 会话恢复时会重投递同一条消息**（at-least-once），
 * SDK 为重投递的消息创建全新事件对象——序号又从 1 开始。此时第二条回复的
 * msg_seq 与第一条完全相同，被平台按「重复消息」拒收：
 * `HTTP 400 code=40054005 {"message":"消息被去重，请检查请求msgseq"}`。
 * 典型受影响场景：分页渲染的多张图片（第二张起全部被去重）、重投递后的任何回复。
 *
 * ## 修复
 *
 * 每条群消息到达（[GroupMessageHandler] 入口）时，按 msg_id 为本次投递分配一个
 * **互不重叠的序号基数**并写入事件对象（SDK 的 setMsgSeq）：
 * - 第 1 次投递：基数 1（与旧行为一致，msg_seq 从 1 开始递增）；
 * - 第 N 次重投递：基数 1 + (N-1)×100000——同一条消息的任何两次投递、
 *   任意次回复的 msg_seq 绝不重复；
 * - 计数按 msg_id 隔离、30 分钟未活跃自动清理（QQ 被动回复的判定窗口约 5 分钟，
 *   远小于清理周期），内存占用可忽略。
 */
object ReplySeqGuard {

    /** 同一 msg_id 两次投递的基数间隔（保证任何回复序号永不重叠）。 */
    private const val BASE_STRIDE = 100_000L

    /** 条目清理周期与保留时长（毫秒）。 */
    private const val CLEANUP_INTERVAL_MS = 10L * 60L * 1000L
    private const val RETAIN_MS = 30L * 60L * 1000L

    /** msg_id →（已投递次数, 最近活跃时间）。 */
    private class DeliveryCounter(@Volatile var count: Long, @Volatile var lastAt: Long)

    private val counters = ConcurrentHashMap<String, DeliveryCounter>()

    @Volatile
    private var lastCleanupAt = 0L

    /**
     * 为本次消息投递分配序号基数（写入 SDK 事件对象的 seq 字段，
     * 后续该事件的所有回复按基数、基数+1、基数+2… 递增，SDK 自增语义保持不变）。
     */
    fun allocateBase(msgId: String?): Int {
        val key = msgId?.takeIf { it.isNotEmpty() } ?: return 1
        val now = System.currentTimeMillis()
        maybeCleanup(now)
        val counter = counters.computeIfAbsent(key) { DeliveryCounter(0, now) }
        synchronized(counter) {
            counter.count += 1
            counter.lastAt = now
            val base = 1L + (counter.count - 1) * BASE_STRIDE
            return base.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    /** 惰性清理过期条目（低频，无后台线程；volatile 时间戳限频）。 */
    private fun maybeCleanup(now: Long) {
        if (now - lastCleanupAt < CLEANUP_INTERVAL_MS) return
        lastCleanupAt = now
        val iterator = counters.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastAt > RETAIN_MS) iterator.remove()
        }
    }
}
