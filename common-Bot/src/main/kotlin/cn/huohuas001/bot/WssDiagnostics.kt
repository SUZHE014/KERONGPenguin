package cn.huohuas001.bot

import cn.huohuas001.bot.tools.PluginFileLog
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.network.WebSocketListener
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake

/**
 * WebSocket 连接诊断与自愈（1.5.3）。
 *
 * ## 根因修复：钩子返回值语义
 *
 * 反编译 SDK（kloping 0.6.3-R4）确认 `WssWorker$1` 的回调约定：
 * - `onMessage`：`if (!listener.onMessage(...)) return;`
 * - `send`/`onOpen`/`onClose`/`onError`：经 `preMethods`，钩子返回 false → 直接 return
 *
 * 即 **钩子返回 false = 拦截，SDK 不再做任何自身处理**。1.5.3 首版钩子全部
 * 返回了 false，等于把整条协议链掐断：SDK 收到 Hello 后不再发鉴权包（op 2），
 * 平台 60 秒超时踢线（code 1006）→ 看门狗重连 → 再被踢的死循环；
 * 同时 closeListeners 不被通知，SDK 自身的 3 秒自动重连也不触发。
 * 现所有回调一律返回 **true（放行）**：鉴权、心跳、SDK 重连链路完整恢复，
 * 诊断钩子退回纯"观察者"角色。
 *
 * ## 诊断与自愈能力
 *
 * - 协议帧落盘：Hello / READY / RESUMED / Invalid Session(op 9) / Reconnect(op 7) /
 *   Identify(op 2) / Resume(op 6) / 连接关闭 / 连接错误（含完整堆栈）写入插件日志文件，
 *   事后可从 logs/qq/qq-bind-日期.log 完整还原鉴权对话（修复"堆栈没有记录在日志里面"）；
 * - 错误堆栈落盘：onError 拿到原始 Exception，输出"异常类型 + 消息 + 完整堆栈前 15 帧"；
 * - 心跳自愈：会话就绪后 SDK 心跳停跳超过 1.5 倍心跳间隔，由本对象代发 op 1 心跳；
 *   心跳已发出但平台连续 3 个周期无应答（连接假死）时主动重连，
 *   不再干等平台的 4009 踢线或底层 86 秒断线检测；
 * - 鉴权失败快速恢复：收到 Hello 后 60 秒仍未 READY/RESUMED 主动重连重新鉴权
 *   （90 秒限频，防止重连风暴）。
 *
 * ## 性能设计（极低占用）
 *
 * - 业务事件帧（op 0 非 READY）：**不做 JSON 解析、不落盘**，仅做一次内存计数；
 *   帧的 op/t/s 用廉价字符串扫描提取（indexOf），避免每帧 fastjson 全量解析；
 * - 心跳（op 1）与心跳应答（op 11）：不落盘，仅更新 volatile 时间戳；
 *   异常（停跳/无应答）才写日志；
 * - 看门狗为单守护线程、最低优先级，10 秒一次 volatile 读检查，空转开销可忽略；
 * - 文件日志按需打开即关、追加写，无后台刷盘线程（见 PluginFileLog）。
 */
object WssDiagnostics : WebSocketListener {

    /** 最近一次收到 Hello(op 10) 的时间戳（毫秒）。 */
    @Volatile
    private var helloAt = 0L

    /** 最近一次收到 READY / RESUMED 的时间戳（毫秒），鉴权成功标志。 */
    @Volatile
    private var readyAt = 0L

    /** 平台下发的心跳间隔（毫秒）。 */
    @Volatile
    private var heartbeatIntervalMs = 0L

    /** 最近一次心跳发出时间戳（SDK 或插件代发，均经 onSend 钩子统一记录）。 */
    @Volatile
    private var lastHeartbeatSentAt = 0L

    /** 最近一次收到心跳应答(op 11) 时间戳（0 表示本连接尚未收到过应答）。 */
    @Volatile
    private var lastAckAt = 0L

    /** 最近一次收到的调度事件序号 s（兜底心跳的 d 字段）。 */
    @Volatile
    private var lastEventSeq: Long? = null

    /** 最近一次主动重连时间戳（鉴权超时/假死共用限频，防止重连风暴）。 */
    @Volatile
    private var lastForcedReconnectAt = 0L

    /** 业务事件帧计数（仅内存，用于状态观测，不落盘）。 */
    @Volatile
    private var eventCount = 0L

    /** 心跳应答计数（仅内存）。 */
    @Volatile
    private var ackCount = 0L

    /** 插件代发心跳计数（仅内存）。 */
    @Volatile
    private var fallbackHeartbeatCount = 0L

    /** 看门狗线程（单守护线程，随连接状态自检）。 */
    @Volatile
    private var watchdogThread: Thread? = null

    /** 主动停机标记（插件卸载时置位，线程退出）。 */
    @Volatile
    private var stopped = false

    // ---------- 1.6.1：同 AppID 多连接互踢检测 ----------

    /** 判定为「短命会话」的阈值：READY 后存活不足该毫秒数即被平台关闭。 */
    private const val SHORT_SESSION_MS = 90_000L

    /** 连续短命会话达到该次数判定为多连接互踢冲突。 */
    private const val CONFLICT_SHORT_SESSIONS = 3

    /** 短命会话窗口：首次短命关闭到触发判定的最大间隔（毫秒）。 */
    private const val CONFLICT_WINDOW_MS = 600_000L

    /** 连续短命会话计数（出现一次存活≥阈值的会话即清零）。 */
    @Volatile
    private var shortSessionCount = 0

    /** 本次窗口内首次短命关闭的时间戳。 */
    @Volatile
    private var firstShortCloseAt = 0L

    /** 已触发冲突停机（防止重复判定）。 */
    @Volatile
    private var conflictStopped = false

    /** 启动诊断看门狗（幂等）。 */
    fun start() {
        stopped = false
        shortSessionCount = 0
        firstShortCloseAt = 0L
        conflictStopped = false
        if (watchdogThread?.isAlive == true) return
        val thread = Thread({
            runWatchdog()
        }, "qq-wss-diagnostics")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        watchdogThread = thread
        thread.start()
        PluginFileLog.write(
            "[WSS诊断] 钩子已挂载（观察者模式，放行 SDK 处理）：协议帧落盘 / 错误堆栈落盘 / " +
                "心跳自愈（代发 + 假死检测）/ 鉴权失败快速重连已启用；业务事件仅计数不落盘"
        )
    }

    /**
     * 1.6.3：互踢证据统一入口。
     *
     * 1.6.2 只统计「鉴权成功后存活 <90 秒被关闭」的短命会话；实测双服务器
     * 场景还存在另一种形态——第二台服务器发起的连接在**鉴权完成前**就被
     * 平台关闭（Identify/Resume 被拒），此时 readyAt 恒为 0，旧版只把它当
     * 普通失败重连，永不触发互踢判定，第二台服务器控制台因此只有
     * 「第 N 次（1/5）重试连接…」和一条没有原因的「重连超时」——
     * 这正是「两台服务器同时绑同一机器人却毫无冲突提示」的另一半根因。
     *
     * 现在：凭据自检（[QqTokenHealth.lastCheckOk]）已通过时，鉴权期被关
     * 的连接同样计入互踢证据窗口（凭据有效却连不上，指向网关拒绝本连接，
     * 而非凭据问题）；凭据自检失败则不计（那是凭据问题，另有明确提示）。
     */
    private fun trackAuthRejected() {
        recordConflictEvidence(
            hint = "本服务器连接在鉴权完成前即被平台关闭（AppID 凭据自检已通过，" +
                "凭据本身可用）。若 bot.app-id 没有填在另一台服务器上，则多为网关/网络问题；" +
                "若填了，QQ 同一机器人只允许一条稳定连接，本服大概率是被抢占的后来者"
        ) { count ->
            "已检测到机器人连接疑似被另一台服务器抢占：凭据有效但连接连续 $count 次" +
                "在鉴权完成前被平台关闭（同 AppID 多连接冲突特征之一）。" +
                "原因：极可能已有另一台服务器绑定了同一个机器人（bot.app-id 重复）"
        }
    }

    /**
     * 1.6.1：短命会话计数与互踢判定。
     *
     * 「鉴权成功后存活不足 90 秒即被平台关闭」连续出现 3 次（且都在 10 分钟内）
     * 是同 AppID 多连接互踢的典型特征：两台服务器填同一个机器人凭据时会轮流
     * 把对方踢下线，谁也连不稳。
     *
     * 1.6.2 行为变更：判定成立后**不再停机**——改为经 [ReconnectManager]
     * 醒目输出原因（「已有另一台服务器绑定了同一个机器人」），连接继续走
     * 统一的重试体系（每轮 5 次、最多 3 轮自动重试，期间可用 /hb chs 手动重试），
     * 服务器本体与插件其余功能不受影响。
     * 单次短命关闭即输出醒目提示（error 级，修复旧版启动期无任何明确提示的问题：
     * 网络抖动也可能偶发一次，但提示里已说明如何排查，不再静默）。
     */
    private fun trackShortSession(aliveMs: Long) {
        recordConflictEvidence(
            hint = "本服务器连接鉴权成功后仅存活 ${aliveMs / 1000} 秒即被平台关闭。" +
                "若 bot.app-id 也填在了另一台服务器上，QQ 同一机器人只允许一条连接，会互相抢占轮流掉线；" +
                "若确认未重复配置，则多为网络抖动，SDK 将自动重连（重连过程见「第 N 次（1/5）」提示）"
        ) { count ->
            "已检测到机器人连接被另一台服务器抢占：连接鉴权成功后连续 " +
                "$count 次在数秒内被平台关闭（同 AppID 多连接互踢特征）。" +
                "原因：已有另一台服务器绑定了同一个机器人（bot.app-id 重复）"
        }
    }

    /** 互踢证据计数（窗口内累计达到阈值即判定冲突，只判定一次）。 */
    private fun recordConflictEvidence(hint: String, reasonBuilder: (Int) -> String) {
        val now = System.currentTimeMillis()
        if (firstShortCloseAt == 0L || now - firstShortCloseAt > CONFLICT_WINDOW_MS) {
            // 重新开窗：窗口外的零星证据不计入
            firstShortCloseAt = now
            shortSessionCount = 0
        }
        shortSessionCount++
        PluginFileLog.errorAndKeep(
            "[WSS] $hint（第 $shortSessionCount/$CONFLICT_SHORT_SESSIONS 次）"
        )
        if (shortSessionCount < CONFLICT_SHORT_SESSIONS) {
            return
        }
        if (conflictStopped) return
        conflictStopped = true
        QClient.stopForConflict(reasonBuilder(shortSessionCount))
    }

    /** 停止看门狗（插件停机）。 */
    fun stop() {
        stopped = true
    }

    // ---------- WebSocketListener 回调 ----------
    // 约定：返回 true = 放行 SDK 自身处理（鉴权/心跳/重 close 重连链路）。
    // 1.5.3 首版误返回 false 拦截了全部协议处理，导致"Hello 后无鉴权 → 60 秒被平台
    // 1006 踢线"死循环；所有回调必须返回 true。

    override fun onOpen(client: WebSocketClient?, handshake: ServerHandshake?): Boolean {
        // 状态复位：新连接的 Hello/READY/心跳重新计时，连接级计数清零
        helloAt = 0L
        readyAt = 0L
        heartbeatIntervalMs = 0L
        lastHeartbeatSentAt = 0L
        lastAckAt = 0L
        lastEventSeq = null
        eventCount = 0L
        ackCount = 0L
        fallbackHeartbeatCount = 0L
        // 1.6.2：本次连接计入重连编排（首次启动连接也计，输出「第 N 次（1/5）重试连接…」）
        ReconnectManager.onOpen()
        PluginFileLog.write("[WSS] 连接已打开，等待平台下发 Hello(op 10)…")
        return true
    }

    override fun onMessage(client: WebSocketClient?, msg: String?): Boolean {
        if (msg.isNullOrEmpty()) return true
        try {
            // 廉价预检：先扫 op，业务事件帧（op 0 非 READY）不进入 JSON 解析
            when (quickOp(msg)) {
                10 -> onHello(msg)
                0 -> onDispatch(msg)
                7 -> PluginFileLog.warnAndKeep("[WSS] <- 平台要求重连(op 7 Reconnect)，SDK 将自动处理")
                9 -> PluginFileLog.warnAndKeep(
                    "[WSS] <- 会话无效(op 9 Invalid Session d=${JSON.parseObject(msg)?.get("d")}），平台要求重新鉴权"
                )
                11 -> {
                    // 心跳应答：不落盘，仅时间戳 + 计数
                    lastAckAt = System.currentTimeMillis()
                    ackCount++
                }
            }
        } catch (_: Throwable) {
            // 诊断失败绝不影响 SDK 处理
        }
        return true
    }

    /** Hello 帧：完整解析（每连接一次），记录心跳间隔。 */
    private fun onHello(msg: String) {
        val frame = JSON.parseObject(msg) ?: return
        helloAt = System.currentTimeMillis()
        heartbeatIntervalMs = frame.getJSONObject("d")?.getLong("heartbeat_interval") ?: 0L
        PluginFileLog.write(
            "[WSS] <- Hello(op 10)，心跳间隔 ${heartbeatIntervalMs}ms，等待 SDK 发送鉴权包…"
        )
    }

    /** 调度事件帧（op 0）：READY/RESUMED 落盘，其余业务事件仅计数。 */
    private fun onDispatch(msg: String) {
        val seq = quickSeq(msg)
        if (seq != null) lastEventSeq = seq
        val type = quickType(msg)
        if (type == "READY" || type == "RESUMED") {
            readyAt = System.currentTimeMillis()
            lastEventSeq = seq // READY 后以新会话序号为准
            val sessionId = try {
                JSON.parseObject(msg)?.getJSONObject("d")?.getString("session_id") ?: "未知"
            } catch (_: Throwable) {
                "未知"
            }
            PluginFileLog.write(
                "[WSS] <- $type（鉴权成功，会话已建立）sessionId=$sessionId"
            )
            // 1.6.2：鉴权成功通知重连编排（健康与否由存活时长判定）
            ReconnectManager.onSessionReady()
        } else {
            // 业务事件：内存计数（volatile 写，单次纳秒级），不解析不落盘
            eventCount++
        }
    }

    override fun onSend(client: WebSocketClient?, msg: String?): Boolean {
        if (msg.isNullOrEmpty()) return true
        try {
            when (quickOp(msg)) {
                1 -> {
                    // SDK 或插件代发的心跳：仅时间戳，不落盘
                    lastHeartbeatSentAt = System.currentTimeMillis()
                }
                2 -> {
                    // Identify：仅记录 intents，避免 token 泄露（每连接一次）
                    val intents = try {
                        JSON.parseObject(msg)?.getJSONObject("d")?.get("intents")
                    } catch (_: Throwable) {
                        null
                    }
                    PluginFileLog.write("[WSS] -> 鉴权 Identify(op 2) 已发出（intents=$intents）")
                }
                6 -> PluginFileLog.write("[WSS] -> 鉴权 Resume(op 6) 已发出（会话恢复）")
            }
        } catch (_: Throwable) {
        }
        return true
    }

    override fun onClose(client: WebSocketClient?, code: Int, reason: String?, remote: Boolean): Boolean {
        val hint = when (code) {
            1006 -> "连接异常断开（未收到关闭帧，常见于鉴权超时/网络中断），SDK 将重连并重新鉴权"
            4009 -> "会话超时（心跳停止），SDK 将重连并重新鉴权"
            1000 -> "平台正常关闭（网关定期踢线），SDK 将在 3 秒后自动重连"
            else -> "SDK 将按 anyCloseReconnect 策略在 3 秒后自动重连"
        }
        PluginFileLog.warnAndKeep(
            "[WSS] 连接关闭：code=$code 原因=${reason ?: "无"} 由${if (remote) "平台" else "本端"}发起（$hint）" +
                " [本次连接累计：事件 $eventCount 帧 / 心跳应答 $ackCount 次${if (fallbackHeartbeatCount > 0) " / 插件代发心跳 $fallbackHeartbeatCount 次" else ""}]"
        )
        // 1.6.1：同 AppID 多连接互踢检测（在 readyAt 清零前先取本次会话存活时长）
        // 1.6.2：会话存活同时交重连编排计数（健康会话重置重试计数并宣告重连成功）
        val sessionReadyAt = readyAt
        if (sessionReadyAt > 0) {
            val aliveMs = System.currentTimeMillis() - sessionReadyAt
            if (aliveMs < SHORT_SESSION_MS) {
                trackShortSession(aliveMs)
                ReconnectManager.onClosedAfter(aliveMs, healthy = false)
            } else {
                // 出现一次健康长会话：清零（QQ 网关正常踢线间隔远长于阈值）
                shortSessionCount = 0
                firstShortCloseAt = 0L
                ReconnectManager.onClosedAfter(aliveMs, healthy = true)
            }
        } else {
            // 未能完成鉴权即被关闭：一次失败的重连尝试；
            // 1.6.3：凭据自检已通过时，鉴权期被关的连接计入互踢证据窗口
            // （凭据有效却连不上 = 网关拒绝本连接，同 AppID 被另一台服务器占用的特征）
            val tokenOk = try {
                QqTokenHealth.lastCheckOk == true
            } catch (_: Throwable) {
                false
            }
            if (tokenOk) {
                trackAuthRejected()
            }
            ReconnectManager.onClosedAfter(0L, healthy = false)
        }
        // 会话随连接失效，READY 需要重新等待
        readyAt = 0L
        return true
    }

    /**
     * 连接错误：异常类型 + 消息 + 完整堆栈前 15 帧，控制台与日志文件双写。
     *
     * 1.5.3 修复"堆栈没有记录在日志里面"：SDK 的 onError 只输出"wss error"，
     * 异常堆栈走 printStackTrace（仅控制台），此前文件里定位不到任何线索；
     * 现在原始异常在本回调直接落盘。
     */
    override fun onError(client: WebSocketClient?, e: Exception?): Boolean {
        if (e == null) return true
        try {
            val detail = StringBuilder("[WSS] 连接错误：${e.javaClass.name}: ${e.message ?: "无消息"}")
            val frames = e.stackTrace
            if (frames.isNotEmpty()) {
                detail.append("\n[WSS] 堆栈：")
                for ((index, element) in frames.take(15).withIndex()) {
                    detail.append("\n[WSS]     ${index + 1}. ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
                }
                if (frames.size > 15) detail.append("\n[WSS]     …共 ${frames.size} 帧")
            }
            val cause = e.cause
            if (cause != null && cause !== e) {
                detail.append("\n[WSS] 根因：${cause.javaClass.name}: ${cause.message ?: "无消息"}")
                cause.stackTrace.take(6).forEachIndexed { index, element ->
                    detail.append("\n[WSS]     C${index + 1}. ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
                }
            }
            PluginFileLog.errorAndKeep(detail.toString())
        } catch (_: Throwable) {
        }
        return true
    }

    // ---------- 看门狗：鉴权失败快速恢复 + 心跳自愈 ----------

    private fun runWatchdog() {
        while (!stopped) {
            try {
                Thread.sleep(10_000)
            } catch (_: InterruptedException) {
                break
            }
            if (stopped) break
            val starter = QClient.starter ?: break
            val socket = try {
                starter.wssWorker?.webSocket
            } catch (_: Throwable) {
                null
            }
            if (socket == null || !socket.isOpen) continue
            val now = System.currentTimeMillis()

            // 1.6.2：连接处于打开状态且鉴权成功后存活 ≥ 90 秒 → 健康会话，
            // 重置重连计数并宣告「重连成功」（看门狗口径与连接关闭时判定口径一致）
            if (readyAt > 0 && now - readyAt >= ReconnectManager.HEALTHY_SESSION_MS) {
                ReconnectManager.onHealthySession()
            }

            // 1) 鉴权失败快速恢复：Hello 已到但 60 秒仍未见 READY/RESUMED，
            //    主动重连触发重新取 token 并重新鉴权（90 秒限频，防止重连风暴）
            if (helloAt > 0 && readyAt < helloAt && now - helloAt > 60_000) {
                forcedReconnect(
                    socket,
                    "[WSS看门狗] 连接后 ${(now - helloAt) / 1000} 秒仍未完成鉴权（未见 READY/RESUMED），" +
                        "主动重连以重新获取 token 并重新鉴权（官方 4009 会等待 2 分钟才踢线，这里提前恢复）"
                )
            }

            // 2) 心跳停跳兜底：会话就绪后，心跳（SDK 或代发）静默超过 1.5 倍间隔时代发，
            //    防止"心跳停止 → 平台判定会话超时(4009)"
            if (heartbeatIntervalMs > 0 && readyAt > 0) {
                val lastSent = if (lastHeartbeatSentAt > 0) lastHeartbeatSentAt else readyAt
                if (now - lastSent > heartbeatIntervalMs * 3 / 2) {
                    sendFallbackHeartbeat(socket)
                }

                // 3) 心跳假死检测：心跳已在发但平台连续 3 个周期无应答（op 11），
                //    说明连接已单向失效，等待 4009 踢线或底层 86 秒检测都太慢，直接重连
                if (lastHeartbeatSentAt > 0 && lastAckAt > 0 &&
                    now - lastAckAt > heartbeatIntervalMs * 3 &&
                    now - lastForcedReconnectAt > 90_000
                ) {
                    forcedReconnect(
                        socket,
                        "[WSS看门狗] 心跳已发出但平台连续 ${(now - lastAckAt) / 1000} 秒无应答（连接假死），主动重连恢复"
                    )
                }
            }
        }
    }

    /**
     * 主动重连（90 秒限频共用，防重连风暴）。
     *
     * 1.6.2：重连超时挂起期间（ReconnectManager 已关闭 SDK 自动重连，
     * 等待 30 分钟退避或 /hb chs）看门狗不再兑底重连；
     * 互踢冲突不再阻断看门狗（1.6.2 起冲突不停机，重试体系继续）。
     */
    private fun forcedReconnect(socket: WebSocketClient, message: String) {
        if (ReconnectManager.isSuspended()) return
        val now = System.currentTimeMillis()
        if (now - lastForcedReconnectAt <= 90_000) return
        lastForcedReconnectAt = now
        PluginFileLog.warnAndKeep(message)
        try {
            socket.reconnect()
        } catch (t: Throwable) {
            PluginFileLog.errorAndKeep("[WSS看门狗] 主动重连失败: ${t.message}")
        }
    }

    /** 代发一次 op 1 心跳（d = 最近收到的事件序号，无则为 null）。 */
    private fun sendFallbackHeartbeat(socket: WebSocketClient) {
        try {
            val seq = lastEventSeq
            val payload = JSONObject()
            payload["op"] = 1
            payload["d"] = seq
            socket.send(payload.toJSONString())
            fallbackHeartbeatCount++
            // 发出后 onSend 钩子会更新 lastHeartbeatSentAt，无需重复赋值
            PluginFileLog.warnAndKeep(
                "[WSS心跳兜底] SDK 心跳已停跳，插件代发心跳 op=1 d=$seq（防 4009 会话超时）"
            )
        } catch (t: Throwable) {
            PluginFileLog.errorAndKeep("[WSS心跳兜底] 代发心跳失败: ${t.message}")
        }
    }

    // ---------- 廉价字段提取（避免业务事件全帧 JSON 解析） ----------

    /** 扫描 "op" 键的数值（找不到返回 -1）。仅 indexOf 扫描，无对象分配（除小数字串）。 */
    private fun quickOp(msg: String): Int {
        val i = msg.indexOf("\"op\"")
        if (i < 0) return -1
        var j = msg.indexOf(':', i + 4)
        if (j < 0) return -1
        j++
        while (j < msg.length && msg[j] == ' ') j++
        val start = j
        while (j < msg.length && msg[j].isDigit()) j++
        if (j == start) return -1
        return msg.substring(start, j).toIntOrNull() ?: -1
    }

    /** 扫描 "t" 键的字符串值（帧类型，如 READY / GROUP_AT_MESSAGE_CREATE）。 */
    private fun quickType(msg: String): String? {
        val i = msg.indexOf("\"t\":\"")
        if (i < 0) return null
        val start = i + 5
        val end = msg.indexOf('"', start)
        if (end < 0) return null
        return msg.substring(start, end)
    }

    /** 扫描 "s" 键的数值（事件序号，仅 op 0 帧调用）。 */
    private fun quickSeq(msg: String): Long? {
        val i = msg.indexOf("\"s\":")
        if (i < 0) return null
        var j = i + 4
        while (j < msg.length && msg[j] == ' ') j++
        val start = j
        while (j < msg.length && msg[j].isDigit()) j++
        if (j == start) return null
        return msg.substring(start, j).toLongOrNull()
    }
}

/**
 * AppID 凭据健康自检（1.5.3）：启动时直接调用 QQ 开放平台的 token 接口
 * （与 SDK AuthV2Base 使用的同一端点），验证 appid + secret 是否有效。
 *
 * 用途：当机器人陷入"鉴权失败 → 4009"循环时，第一步就是确认凭据本身是否可用——
 * 若此处失败（如 secret 失效 / 被重置），无需再排查协议问题，直接到
 * QQ 开放平台重置凭据即可。
 */
object QqTokenHealth {

    /**
     * 最近一次自检结果（1.6.3 新增）：true = 凭据有效，false = 凭据无效，
     * null = 自检未完成或无法访问接口。供 [WssDiagnostics] 鉴别「鉴权期被关」
     * 的连接是凭据问题还是同 AppID 被另一台服务器占用。
     */
    @Volatile
    var lastCheckOk: Boolean? = null
        private set

    /** 执行自检并返回结果描述（调用方负责落盘，token 只返回前 8 位避免泄露）。 */
    fun check(appid: String, secret: String): String {
        lastCheckOk = null
        var connection: java.net.HttpURLConnection? = null
        return try {
            val body = JSONObject()
            body["appId"] = appid
            body["clientSecret"] = secret
            connection = java.net.URL("https://bots.qq.com/app/getAppAccessToken").openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.outputStream.use { stream ->
                stream.writer(Charsets.UTF_8).use { it.write(body.toJSONString()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = java.io.BufferedReader(java.io.InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code == 200) {
                val token = try {
                    JSON.parseObject(response)?.getString("access_token")
                } catch (_: Throwable) {
                    null
                }
                // 1.6.3：记录自检结果（供 WssDiagnostics 鉴别鉴权期被关的连接成因）
                lastCheckOk = !token.isNullOrEmpty()
                if (!token.isNullOrEmpty()) {
                    "[鉴权自检] AppID 凭据有效（token 获取成功：${token.take(8)}****，接口返回 200）。" +
                        "若机器人仍无法鉴权，请查看日志中的 [WSS] 帧对话与错误堆栈定位协议层问题"
                } else {
                    "[鉴权自检] 接口返回 200 但响应缺少 access_token：${response.take(200)}"
                }
            } else {
                // 1.6.3：记录自检结果（false = 凭据无效，不计入互踢证据）
                lastCheckOk = false
                "[鉴权自检] AppID 凭据可能无效（接口 HTTP $code）：${response.take(200)}。" +
                    "请到 QQ 开放平台核对 AppID 与 Secret；凭据无效时机器人会持续陷入鉴权失败重连循环"
            }
        } catch (t: Throwable) {
            "[鉴权自检] 无法访问 QQ 开放平台 token 接口：${t.javaClass.simpleName}: ${t.message}。" +
                "服务器网络无法访问 bots.qq.com 时，SDK 同样无法完成鉴权"
        } finally {
            connection?.disconnect()
        }
    }
}
