package cn.huohuas001.bot

import cn.huohuas001.bot.tools.PluginFileLog
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.network.WebSocketListener
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * WebSocket 连接诊断与自愈（1.5.3）。
 *
 * 背景：1.5.2 起出现“连接 → 鉴权 → wss error → 2 分钟后 4009 Session timed out → 重连”死循环。
 * 根因排查受限于两点：
 * 1. SDK 的 onError 只输出“wss error”五个字，异常消息与堆栈走 printStackTrace（仅控制台），
 *    插件日志文件里没有任何可定位的信息；
 * 2. SDK 日志级别被压到 1，收发的 WSS 帧不落盘，无法看到鉴权后平台到底回了什么。
 *
 * 本对象通过 Starter.Config.webSocketListener 钩子（SDK 在每帧收发/连接事件上都会回调）
 * 实现：
 * - 全帧落盘：收到的每帧（op/事件名/序号/载荷摘要）与发出的鉴权/心跳帧写入插件日志文件，
 *   事后可直接翻日志还原完整鉴权对话；
 * - 错误堆栈落盘：onError 拿到原始 Exception，输出“异常类型 + 消息 + 完整堆栈前 15 帧”，
 *   控制台与日志文件双写（修复用户反馈的“堆栈没有记录在日志里面”）；
 * - 心跳兜底：会话就绪后若 SDK 心跳停跳超过 1.5 倍心跳间隔，由本对象代发 op 1 心跳，
 *   防止“心跳停止 → 4009 会话超时”型掉线；
 * - 鉴权失败快速恢复：收到 Hello 后 60 秒仍未 READY/RESUMED（鉴权被平台拒绝或未回应），
 *   主动重连触发重新取 token 并重新鉴权，不再干等 QQ 两分钟的 4009 踢线，
 *   每轮循环时间从约 2 分钟缩短到约 1 分钟。
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

    /** SDK 自己发出的最近一次心跳(op 1) 时间戳。 */
    @Volatile
    private var lastSdkHeartbeatAt = 0L

    /** 插件兜底心跳最近一次发出时间戳。 */
    @Volatile
    private var lastFallbackHeartbeatAt = 0L

    /** 最近一次收到的调度事件序号 s（兜底心跳的 d 字段）。 */
    @Volatile
    private var lastEventSeq: Long? = null

    /** 最近一次因“未见 READY”主动重连的时间戳（限频，防止重连风暴）。 */
    @Volatile
    private var lastNoReadyReconnectAt = 0L

    /** 看门狗线程（单守护线程，随连接状态自检）。 */
    @Volatile
    private var watchdogThread: Thread? = null

    /** 主动停机标记（插件卸载时置位，线程退出）。 */
    @Volatile
    private var stopped = false

    /** 启动诊断看门狗（幂等）。 */
    fun start() {
        stopped = false
        if (watchdogThread?.isAlive == true) return
        val thread = Thread({
            runWatchdog()
        }, "qq-wss-diagnostics")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        watchdogThread = thread
        thread.start()
        PluginFileLog.write("[WSS诊断] 钩子已挂载：全帧落盘 / 错误堆栈落盘 / 心跳兜底 / 鉴权失败快速重连已启用")
    }

    /** 停止看门狗（插件停机）。 */
    fun stop() {
        stopped = true
    }

    // ---------- WebSocketListener 回调（SDK 在 WssWorker 每个连接事件上回调） ----------

    override fun onOpen(client: WebSocketClient?, handshake: ServerHandshake?): Boolean {
        // 状态复位：新连接的 Hello/READY 重新计时
        helloAt = 0L
        readyAt = 0L
        heartbeatIntervalMs = 0L
        lastSdkHeartbeatAt = 0L
        PluginFileLog.write("[WSS] 连接已打开，等待平台下发 Hello(op 10)…")
        return false
    }

    override fun onMessage(client: WebSocketClient?, msg: String?): Boolean {
        if (msg.isNullOrEmpty()) return false
        try {
            val frame = JSON.parseObject(msg) ?: return false
            val op = frame.getIntValue("op")
            val type = frame.getString("t") ?: ""
            val seq = frame.get("s")
            if (seq is Number) lastEventSeq = seq.toLong()
            when (op) {
                10 -> { // Hello：携带心跳间隔
                    helloAt = System.currentTimeMillis()
                    heartbeatIntervalMs = frame.getJSONObject("d")?.getLong("heartbeat_interval") ?: 0L
                    PluginFileLog.write(
                        "[WSS] <- Hello(op 10)，心跳间隔 ${heartbeatIntervalMs}ms，等待 SDK 发送鉴权包…"
                    )
                }
                0 -> { // 调度事件：READY / RESUMED / 业务事件
                    if (type == "READY" || type == "RESUMED") {
                        readyAt = System.currentTimeMillis()
                        PluginFileLog.write(
                            "[WSS] <- ${type}（鉴权成功，会话已建立）sessionId=${frame.getJSONObject("d")?.getString("session_id") ?: "未知"}"
                        )
                    } else {
                        // 业务事件仅记录摘要（载荷截断，避免刷屏）
                        PluginFileLog.write("[WSS] <- 事件 $type s=$seq ${summarize(frame)}")
                    }
                }
                7 -> PluginFileLog.warnAndKeep("[WSS] <- 平台要求重连(op 7 Reconnect)，SDK 将自动处理")
                9 -> PluginFileLog.warnAndKeep("[WSS] <- 会话无效(op 9 Invalid Session)，平台要求重新鉴权")
                11 -> PluginFileLog.write("[WSS] <- 心跳应答(op 11)")
                else -> PluginFileLog.write("[WSS] <- 帧 op=$op t=$type ${summarize(frame)}")
            }
        } catch (_: Throwable) {
            // 解析失败不影响 SDK 处理
        }
        return false
    }

    override fun onSend(client: WebSocketClient?, msg: String?): Boolean {
        if (msg.isNullOrEmpty()) return false
        try {
            val frame = JSON.parseObject(msg) ?: return false
            val op = frame.getIntValue("op")
            when (op) {
                1 -> { // SDK 心跳
                    lastSdkHeartbeatAt = System.currentTimeMillis()
                    PluginFileLog.write("[WSS] -> 心跳(op 1) d=${frame.get("d")}")
                }
                2 -> PluginFileLog.write("[WSS] -> 鉴权 Identify(op 2) 已发出（intents=${frame.getJSONObject("d")?.get("intents")}）")
                6 -> PluginFileLog.write("[WSS] -> 鉴权 Resume(op 6) 已发出（会话恢复）")
                else -> PluginFileLog.write("[WSS] -> 发送 op=$op ${summarize(frame)}")
            }
        } catch (_: Throwable) {
        }
        return false
    }

    override fun onClose(client: WebSocketClient?, code: Int, reason: String?, remote: Boolean): Boolean {
        PluginFileLog.warnAndKeep(
            "[WSS] 连接关闭：code=$code 原因=${reason ?: "无"} 由${if (remote) "平台" else "本端"}发起" +
                (if (code == 4009) "（会话超时，官方指引：重连并执行 Resume；SDK 将在 3 秒后自动重连）" else "")
        )
        return false
    }

    /**
     * 连接错误：异常类型 + 消息 + 完整堆栈前 15 帧，控制台与日志文件双写。
     *
     * 1.5.3 修复“堆栈没有记录在日志里面”：SDK 的 onError 只输出“wss error”，
     * 异常堆栈走 printStackTrace（仅控制台），此前文件里定位不到任何线索；
     * 现在原始异常在本回调直接落盘。
     */
    override fun onError(client: WebSocketClient?, e: Exception?): Boolean {
        if (e == null) return false
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
        return false
    }

    // ---------- 看门狗：鉴权失败快速恢复 + 心跳兜底 ----------

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

            // 1) 鉴权失败快速恢复：Hello 已到但 60 秒仍未见 READY/RESUMED，
            //    主动重连触发重新取 token 并重新鉴权（90 秒限频，防止重连风暴）
            if (helloAt > 0 && readyAt < helloAt && now - helloAt > 60_000) {
                if (now - lastNoReadyReconnectAt > 90_000) {
                    lastNoReadyReconnectAt = now
                    PluginFileLog.warnAndKeep(
                        "[WSS看门狗] 连接后 ${(now - helloAt) / 1000} 秒仍未完成鉴权（未见 READY/RESUMED），" +
                            "主动重连以重新获取 token 并重新鉴权（官方 4009 会等待 2 分钟才踢线，这里提前恢复）"
                    )
                    try {
                        socket.reconnect()
                    } catch (t: Throwable) {
                        PluginFileLog.errorAndKeep("[WSS看门狗] 主动重连失败: ${t.message}")
                    }
                }
            }

            // 2) 心跳兜底：会话就绪后，SDK 心跳停跳超过 1.5 倍间隔时由插件代发，
            //    防止“心跳停止 → 平台判定会话超时(4009)”
            if (heartbeatIntervalMs > 0 && readyAt > 0) {
                val lastAnyHeartbeat = maxOf(lastSdkHeartbeatAt, lastFallbackHeartbeatAt)
                val quietFor = now - lastAnyHeartbeat
                if (lastAnyHeartbeat == 0L || quietFor > heartbeatIntervalMs * 1.5) {
                    sendFallbackHeartbeat(socket)
                }
            }
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
            lastFallbackHeartbeatAt = System.currentTimeMillis()
            PluginFileLog.warnAndKeep(
                "[WSS心跳兜底] SDK 心跳已停跳，插件代发心跳 op=1 d=$seq" +
                    "（该机制用于防止 4009 会话超时）"
            )
        } catch (t: Throwable) {
            PluginFileLog.errorAndKeep("[WSS心跳兜底] 代发心跳失败: ${t.message}")
        }
    }

    /** 载荷摘要（截断到 400 字符，超长折叠）。 */
    private fun summarize(frame: JSONObject): String {
        return try {
            val d = frame.get("d")?.toString() ?: "无载荷"
            if (d.length > 400) "d=${d.substring(0, 400)}…(截断)" else "d=$d"
        } catch (_: Throwable) {
            ""
        }
    }
}

/**
 * AppID 凭据健康自检（1.5.3）：启动时直接调用 QQ 开放平台的 token 接口
 * （与 SDK AuthV2Base 使用的同一端点），验证 appid + secret 是否有效。
 *
 * 用途：当机器人陷入“鉴权失败 → 4009”循环时，第一步就是确认凭据本身是否可用——
 * 若此处失败（如 secret 失效 / 被重置），无需再排查协议问题，直接到
 * QQ 开放平台重置凭据即可。
 */
object QqTokenHealth {

    /** 执行自检并返回结果描述（调用方负责落盘，token 只返回前 8 位避免泄露）。 */
    fun check(appid: String, secret: String): String {
        var connection: HttpURLConnection? = null
        return try {
            val body = JSONObject()
            body["appId"] = appid
            body["clientSecret"] = secret
            connection = URL("https://bots.qq.com/app/getAppAccessToken").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.outputStream.use { stream ->
                stream.writer(StandardCharsets.UTF_8).use { it.write(body.toJSONString()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code == 200) {
                val token = try {
                    JSON.parseObject(response)?.getString("access_token")
                } catch (_: Throwable) {
                    null
                }
                if (!token.isNullOrEmpty()) {
                    "[鉴权自检] AppID 凭据有效（token 获取成功：${token.take(8)}****，接口返回 200）。" +
                        "若机器人仍无法鉴权，请查看日志中的 [WSS] 帧对话与错误堆栈定位协议层问题"
                } else {
                    "[鉴权自检] 接口返回 200 但响应缺少 access_token：${
                        response.take(200)
                    }"
                }
            } else {
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
