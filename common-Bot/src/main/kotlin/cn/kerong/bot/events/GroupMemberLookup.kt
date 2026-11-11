package cn.kerong.bot.events

import cn.kerong.bot.QClient
import cn.kerong.bot.tools.PluginFileLog
import com.alibaba.fastjson.JSON
import io.github.kloping.qqbot.Start0
import io.github.kloping.qqbot.Starter
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 群成员角色实时查询（1.7.5.2：替代 1.7.5.1 引入的 30 分钟群角色缓存
 * MemberRoleCache——该缓存类已随本方案删除）。
 *
 * ## 背景：按钮回调不带点击者的群身份
 *
 * 按钮点击的互动回调（INTERACTION_CREATE）只带 `group_openid` +
 * `group_member_openid`，没有 `member_role`（群主/管理员/成员）——
 * admin.mode=qq 的管理员判定在按钮路径拿不到角色。1.7.5.1 曾用
 * 30 分钟角色缓存（随真实消息登记）补齐，但存在两个问题：
 * 1. 撤掉管理员后，只要其 30 分钟内不再发言，点管理按钮仍按旧角色放行；
 * 2. 管理员超过 30 分钟未发言后点按钮，缓存为空，判定回退 manual 名单，
 *    QQ 群主/管理员同样被判无权限（缓存方案从未真正可靠）。
 *
 * 现改为**每次点击实时查询官方群成员接口**取最新身份：
 * `GET /v2/groups/{group_openid}/members/{member_openid}`，撤掉管理员
 * 立即生效，无需任何人先发一条消息。
 *
 * ## 时序与降级
 *
 * - 查询发生在**回应之后的命令分发阶段**（[MenuButtons.dispatchAsClicker]），
 *   不拖慢 3 秒回应窗口；
 * - 该接口属平台白名单能力：机器人无权限时平台返回错误（错误码 11253），
 *   按钮的管理员判定回退 admin.manual 名单（首次会在日志说明一次），
 *   手打命令路径不受影响（消息事件自带角色字段）；
 * - 任何异常（网络/解析/凭据未就绪）返回 null，行为同上回退，零影响。
 */
object GroupMemberLookup {

    /** 连接超时（毫秒）——命令阶段查询，不影响回应时限，给足握手时间。 */
    private const val CONNECT_TIMEOUT_MILLIS = 3_000

    /** 读取超时（毫秒）。 */
    private const val READ_TIMEOUT_MILLIS = 4_000

    /** 「无接口权限」提醒只写一次（避免每次点击刷日志）。 */
    private val warnedNoPermission = AtomicBoolean(false)

    /**
     * 查询成员在本群的当前角色。
     *
     * @return owner / admin / member；查询失败或无权限返回 null（调用方
     *   不写角色字段，管理员判定回退 manual 名单路径）
     */
    fun fetchRole(groupOpenId: String, memberOpenId: String): String? {
        if (groupOpenId.isEmpty() || memberOpenId.isEmpty()) return null
        val starter = QClient.starter ?: return null
        // API 基地址（正式 / 沙箱环境自适应，与消息发送同一配置）
        val base = try {
            starter.net?.trim()?.takeUnless { it.isEmpty() }
        } catch (_: Throwable) {
            null
        } ?: return null
        // 鉴权头（Start0.getHeaders 会在 token 过期时先自动续期）
        val headers = try {
            (starter.APPLICATION.INSTANCE.contextManager.getContextEntity(Start0::class.java))
                .getHeaders()
        } catch (_: Throwable) {
            null
        } ?: return null

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("${base.trimEnd('/')}/v2/groups/${encode(groupOpenId)}/members/${encode(memberOpenId)}")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                useCaches = false
                for ((key, value) in headers) {
                    if (key.isBlank() || value.isBlank()) continue
                    setRequestProperty(key, value)
                }
            }
            val status = connection.responseCode
            val body = readAll(if (status in 200..299) connection.inputStream else connection.errorStream)
            if (status in 200..299) {
                try {
                    JSON.parseObject(body)?.getString("member_role")?.trim()
                        ?.lowercase(Locale.ROOT)
                } catch (_: Throwable) {
                    null
                }
            } else {
                // 11253 = 平台白名单能力未开通：提示一次，后续点击静默回退
                if (body?.contains("11253") == true && warnedNoPermission.compareAndSet(false, true)) {
                    try {
                        PluginFileLog.write(
                            "[按钮互动] 机器人无群成员信息查询权限（平台白名单接口，错误码 11253），" +
                                "按钮的管理员判定回退 admin.manual 名单——QQ 群主/管理员点管理类按钮将判无权限，" +
                                "可将其 OpenId 加入 admin.manual，或向平台申请该接口权限"
                        )
                    } catch (_: Throwable) {
                    }
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** URL 路径段转义（OpenId 为字母数字串，防御性处理）。 */
    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    /** 读尽响应体（读取失败返回 null）。 */
    private fun readAll(stream: InputStream?): String? {
        if (stream == null) return null
        return try {
            stream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        }
    }
}
