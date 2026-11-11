package cn.kerong.bot.events

import cn.kerong.bot.QClient
import cn.kerong.bot.tools.PluginFileLog
import io.github.kloping.qqbot.Start0
import io.github.kloping.qqbot.Starter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 按钮互动（INTERACTION_CREATE）官方回应发送器。
 *
 * ## 为什么不走 SDK 的 interActionBase.response / event.response
 *
 * 官方要求收到互动事件后 3 秒内 `PUT /interactions/{id}` 回应，否则客户端
 * 一直 loading 到超时（用户看到的弹窗就是「第三方连接失败」）。SDK 的
 * HTTP 代理层（HttpClientManagerImpl$Invoker）构建请求时**只处理 POST 注解**，
 * 其余方法（含本接口声明的 PUT）既不设置请求方法也不携带请求体——实际发出
 * 的是无 body 的 GET，被平台 405 拒绝；且代理层对 4xx/5xx 只打日志不抛异常，
 * 调用方一直以为回应成功。这就是「机器人正常执行了操作、QQ 却弹第三方连接
 * 失败」的根因（该路径从未成功过，与回应快慢无关）。
 *
 * ## 本发送器
 *
 * 绕开 SDK 代理，直接 `HttpURLConnection` 发 PUT：鉴权头取自 SDK 的
 * [Start0.getHeaders]（与消息发送同一 token，自动续期），URL 基于
 * Starter.net（正式 / 沙箱环境自适应），响应码 2xx 才算送达。
 *
 * 1.7.5.2 连接复用：发送后读尽响应体、不主动断开——连接归还 JVM
 * keep-alive 池；配合 [ConnectionWarmer] 的 4 秒保活心跳，按钮回应
 * 直接复用热连接，不再经历全新 DNS+TCP+TLS 握手（慢网络下冷握手
 * 贴着官方 3 秒回应窗口的边，是「第三方连接失败」的残余根因）。
 *
 * 回应码官方语义：0 = 成功；1 = 操作失败；2 = 操作频繁；3 = 重复操作；
 * 4 = 没有权限；5 = 仅管理员操作。同一 interaction_id 只能回应一次。
 */
object InteractionAcker {

    /**
     * 连接超时（毫秒）——官方回应窗口只有 3 秒，旧值 4 秒意味着握手一慢
     * 就算最终成功客户端也早已超时报「第三方连接失败」；收紧到 1.5 秒，
     * 失败立即快速重试一次，两次机会全部落在窗口内。
     */
    private const val CONNECT_TIMEOUT_MILLIS = 1_500

    /** 读取超时（毫秒）——同上，两次尝试含重试间隔控制在 3 秒窗口内。 */
    private const val READ_TIMEOUT_MILLIS = 2_500

    /** 网络抖动（连接/读取异常、5xx、429 限频）后的快速重试间隔（毫秒）。 */
    private const val RETRY_INTERVAL_MILLIS = 400L

    /**
     * 发送互动回应。
     *
     * @param interactionId 互动事件 ID（事件体 d.id）
     * @param code 官方回应码（0 成功 / 1 失败 / 2 频繁 / 3 重复 / 4 没权限 / 5 仅管理员）
     * @return true = 平台 2xx 受理
     */
    fun respond(interactionId: String, code: Int): Boolean {
        if (interactionId.isBlank()) return false
        val starter = QClient.starter ?: return false
        val url = buildUrl(starter, interactionId) ?: return false
        val headers = try {
            // getHeaders() 会在 token 过期时先自动续期（与 SDK 消息发送同源鉴权）
            start0(starter)?.getHeaders()?.toMutableMap()
        } catch (_: Throwable) {
            null
        } ?: return false
        if (headers["Authorization"].isNullOrBlank()) return false

        /**
         * 单次发送，返回三态：null = 网络抖动（可快速重试）、true = 已受理、
         * false = 确定性失败（4xx 语义错误，重试无意义）。
         */
        fun sendOnce(): Boolean? {
            var connection: HttpURLConnection? = null
            return try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    connectTimeout = CONNECT_TIMEOUT_MILLIS
                    readTimeout = READ_TIMEOUT_MILLIS
                    for ((key, value) in headers) {
                        if (key.isBlank() || value.isBlank()) continue
                        setRequestProperty(key, value)
                    }
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    useCaches = false
                }
                connection.outputStream.use { it.write("{\"code\": $code}".toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                when {
                    status in 200..299 -> true
                    // 5xx / 429 属平台瞬时状况，400ms 后再试一次仍有救
                    status >= 500 || status == 429 -> {
                        PluginFileLog.write("[按钮互动] 回应遇平台瞬时状况（HTTP $status），${RETRY_INTERVAL_MILLIS}ms 后重试（interaction=${interactionId.take(24)}…）")
                        null
                    }
                    else -> {
                        PluginFileLog.write("[按钮互动] 回应未被受理（HTTP $status，interaction=${interactionId.take(24)}…）")
                        false
                    }
                }
            } catch (error: Throwable) {
                // 网络抖动（连接超时/读超时/重置）：快速重试一次（两次总耗时仍在 3 秒窗口内）
                PluginFileLog.write("[按钮互动] 回应发送异常（${error.javaClass.simpleName}: ${error.message}），快速重试")
                null
            } finally {
                // 1.7.5.2 连接复用：读尽响应体后仅关流、不 disconnect——连接
                // 归还 JVM keep-alive 池，下次回应（配合 [ConnectionWarmer]
                // 保活）直接复用热连接，免去 DNS+TCP+TLS 冷握手
                drainBody(connection)
            }
        }

        return when (sendOnce()) {
            true -> true
            false -> false
            null -> {
                try {
                    Thread.sleep(RETRY_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                sendOnce() == true
            }
        }
    }

    /**
     * 读尽并关闭响应流（连接保持可复用；任何异常静默——清理动作不影响
     * 已得出的回应结果）。残留字节不读尽的话，keep-alive 池会判定连接
     * 脏而直接关闭它，复用就无从谈起。
     */
    private fun drainBody(connection: HttpURLConnection?) {
        if (connection == null) return
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            if (stream == null) return
            try {
                stream.use {
                    val buffer = ByteArray(2048)
                    while (it.read(buffer) != -1) {
                    }
                }
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
    }

    /** 拼接回应端点：{api基地址}/interactions/{id}（基地址含 / 结尾与否都兼容）。 */
    private fun buildUrl(starter: Starter, interactionId: String): String? {
        val base = try {
            starter.net
        } catch (_: Throwable) {
            null
        }?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return "${base.trimEnd('/')}/interactions/${interactionId.encodePath()}"
    }

    /** 取 SDK 鉴权组件（token 与消息发送同源；getHeaders 会先自动续期）。 */
    private fun start0(starter: Starter): Start0? = try {
        starter.APPLICATION.INSTANCE.contextManager.getContextEntity(Start0::class.java)
    } catch (_: Throwable) {
        null
    }

    /** URL 路径段转义（interaction_id 形如 UUID，仅防御性处理）。 */
    private fun String.encodePath(): String = replace(Regex("[^A-Za-z0-9._~\\-]")) { "%" }
}
