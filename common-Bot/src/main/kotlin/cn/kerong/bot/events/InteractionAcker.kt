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
 * 回应码官方语义：0 = 成功；1 = 操作失败；2 = 操作频繁；3 = 重复操作；
 * 4 = 没有权限；5 = 仅管理员操作。同一 interaction_id 只能回应一次。
 */
object InteractionAcker {

    /** 连接超时（毫秒）——回应窗口只有 3 秒，不能在握手上耗太久。 */
    private const val CONNECT_TIMEOUT_MILLIS = 4_000

    /** 读取超时（毫秒）。 */
    private const val READ_TIMEOUT_MILLIS = 6_000

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
            if (status in 200..299) {
                true
            } else {
                PluginFileLog.write("[按钮互动] 回应未被受理（HTTP $status，interaction=${interactionId.take(24)}…）")
                false
            }
        } catch (error: Throwable) {
            PluginFileLog.write("[按钮互动] 回应发送失败（${error.javaClass.simpleName}: ${error.message}）")
            false
        } finally {
            connection?.disconnect()
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
