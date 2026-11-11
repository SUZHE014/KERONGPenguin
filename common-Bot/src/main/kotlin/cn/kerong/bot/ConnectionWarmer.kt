package cn.kerong.bot

import cn.kerong.bot.tools.PluginFileLog
import io.github.kloping.qqbot.Start0
import io.github.kloping.qqbot.Starter
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * API 热连接保活线程（1.7.5.2：修「极小概率按按钮 QQ 弹第三方连接失败」
 * 的残余概率）。
 *
 * ## 背景
 *
 * 按钮回应（[InteractionAcker]）此前每次都经历全新 DNS + TCP + TLS 握手；
 * 1.7.5.1 已把超时收紧并快速重试，但慢网络 / 抖动时冷握手仍可能 1~3 秒，
 * 叠加平台处理时间就贴着官方 3 秒回应窗口的边——极小概率仍会弹
 * 「第三方连接失败」。
 *
 * ## 机制
 *
 * 1. **连接复用**：[InteractionAcker] 发完回应后读尽响应体、不主动
 *    disconnect——连接归还 JVM keep-alive 池，下次回应直接复用热连接；
 * 2. **热连接保持**：本线程在机器人在线期间每 [WARM_INTERVAL_MILLIS]
 *    秒对 API 基址发一个极轻量的 HEAD 请求并读尽响应体——池里始终有
 *    一条到 api.sgroup.qq.com 的热连接，且请求会带上 [Start0.getHeaders]
 *    的鉴权头（token 临期时该调用会先自动续期，回应不再卡在取新 token）；
 * 3. 机器人启动（[QClient.launchClient]）即开启，换凭据 / 停机时
 *    （[QClient.closeSessionForCredentialSwap] / [QClient.shutdown]）停止。
 *
 * 保活失败只写一次日志、继续重试（恢复后重置提醒位），不影响任何功能。
 */
object ConnectionWarmer {

    /** 保活间隔（毫秒）——小于常见服务端 keep-alive 超时，池中连接不过期。 */
    private const val WARM_INTERVAL_MILLIS = 4_000L

    /** 保活请求连接 / 读取超时（毫秒）——保活本身允许失败，不占资源。 */
    private const val TIMEOUT_MILLIS = 3_000

    /** 运行标志（stop 置 false，线程自然退出）。 */
    @Volatile
    private var running = false

    /** 保活线程（守护线程，不阻止 JVM 退出）。 */
    @Volatile
    private var thread: Thread? = null

    /** 保活失败提醒只写一次（恢复成功后重置，下次失败再提醒）。 */
    private val warned = AtomicBoolean(false)

    /** 启动保活线程（已在运行时幂等）。 */
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            while (running) {
                try {
                    Thread.sleep(WARM_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                try {
                    warmOnce()
                    warned.set(false)
                } catch (error: Throwable) {
                    if (warned.compareAndSet(false, true)) {
                        try {
                            PluginFileLog.write(
                                "[连接保活] 保活请求失败（将继续重试，不影响功能）: " +
                                    "${error.javaClass.simpleName}: ${error.message}"
                            )
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }, "qq-connection-warmer").apply {
            isDaemon = true
            start()
        }
    }

    /** 停止保活线程（幂等）。 */
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /**
     * 一次保活：对 API 基址发 HEAD 并读尽响应体（连接留在 keep-alive 池；
     * 携带鉴权头，顺路触发 token 预续期）。
     */
    private fun warmOnce() {
        val starter = QClient.starter ?: return
        val base = try {
            starter.net?.trim()?.takeUnless { it.isEmpty() }
        } catch (_: Throwable) {
            null
        } ?: return
        val headers = try {
            (starter.APPLICATION.INSTANCE.contextManager.getContextEntity(Start0::class.java))
                .getHeaders()
        } catch (_: Throwable) {
            null
        }
        val connection = (URL(base).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            useCaches = false
            if (headers != null) {
                for ((key, value) in headers) {
                    if (key.isBlank() || value.isBlank()) continue
                    setRequestProperty(key, value)
                }
            }
        }
        val status = connection.responseCode
        // 读尽响应体后仅关流、不断开连接（disconnect 会关底层套接字）——
        // 连接归还 JVM keep-alive 池，回应请求直接复用这条热连接
        val stream: InputStream? = try {
            if (status in 200..299) connection.inputStream else connection.errorStream
        } catch (_: Throwable) {
            null
        }
        if (stream != null) {
            try {
                stream.use {
                    val buffer = ByteArray(1024)
                    while (it.read(buffer) != -1) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }
}
