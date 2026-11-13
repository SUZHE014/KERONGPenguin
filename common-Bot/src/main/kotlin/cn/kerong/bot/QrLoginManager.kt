package cn.kerong.bot

import cn.kerong.bot.provider.BotShared
import cn.kerong.bot.provider.plugin
import cn.kerong.bot.tools.PluginFileLog
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import org.bukkit.plugin.java.JavaPlugin
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * QQ 机器人扫码登录。
 *
 * 触发点：
 * - 启动时未配置 bot.app-id / bot.secret → **固定延时 30 秒**后再显示
 *   二维码（插件启动阶段控制台仍在刷启动日志，立即打印会被冲掉；
 *   不依赖 ServerLoadEvent / ServerStartedEvent——魔改核心可能移除这些
 *   事件，固定延时不挑核心，见 [scheduleStartupStart]）；
 * - 控制台执行 /hbkrg qr → 随时手动发起（凭据已配置时也可用，扫码成功后
 *   **替换**现有 bot.app-id / bot.secret 并以新凭据重连）。
 *
 * 流程（对齐同类产品 HuHoBot 的扫码授权体验）：
 *
 * 1. 生成 32 字节随机密钥（Base64）→ POST q.qq.com/lite/create_bind_task
 *    创建绑定任务，取得 task_id；
 * 2. 控制台打印 connect.html 授权链接的 ASCII 二维码 + **扫码链接文本**
 *    （手机 QQ 扫码或手动复制链接均可完成授权）；
 * 3. 每 2 秒轮询 q.qq.com/lite/poll_bind_result，等待用户在手机上确认；
 * 4. 授权成功：响应携带 AES-256-GCM 加密的 bot_encrypt_secret，用第 1 步
 *    的密钥解密得到 Secret，连同 bot_appid 写入 config.yml 并自动启动机器人
 *    （已配置场景先关闭旧 WSS 会话再以新凭据重连）；
 * 5. 二维码过期（status=3）：自动重新创建任务并重发新码，**每次会话自动
 *    刷新上限 3 次**，超过后停止自动刷新，可用 /hbkrg qr 手动重新发起
 * （旧版「官网教程二维码 + 教程链接」回落引导
 *    已按用户指令删除）。
 *
 * 协议参考实现（鸣谢）：
 * - HuHoBot 的扫码登录快速开始指引
 * - HuHoBot 源码：https://github.com/HuHoBot/PenguinAgent
 *   （manager/QrLoginManager.kt——create_bind_task / poll_bind_result /
 *   connect.html 链接拼装 / AES-GCM 解密 / 过期自动刷新，本实现与其对齐）
 */
object QrLoginManager {

    /** 绑定任务创建接口。 */
    private const val CREATE_URL = "https://q.qq.com/lite/create_bind_task"

    /** 绑定结果轮询接口。 */
    private const val POLL_URL = "https://q.qq.com/lite/poll_bind_result"

    /** 授权页链接前缀（task_id 拼在其后）。 */
    private const val CONNECT_URL_PREFIX = "https://q.qq.com/qqbot/openclaw/connect.html?task_id="

    /** 轮询间隔（毫秒）。 */
    private const val POLL_INTERVAL_MS = 2_000L

    /** HTTP 超时（毫秒）。 */
    private const val HTTP_TIMEOUT_MS = 10_000L

    /**
     * 每次会话二维码自动刷新上限：超过即停止自动刷新，提示用 /hbkrg qr
     * 手动重新发起（手动重新发起后计数从 0 重新计）。
     */
    private const val MAX_REFRESH_COUNT = 3

    /**
     * 启动延时（毫秒）：未配置凭据时延迟显示二维码——插件 enable 阶段
     * 控制台仍在刷启动日志（TAB / spark / AuthMe 等），立即打印会被淹没。
     * 第五轮修订：不依赖 ServerLoadEvent / ServerStartedEvent（魔改核心
     * 可能移除这些事件），固定延时任何核心都成立；原事件方案兜底 12 秒
     * 延长到 30 秒，覆盖多数核心的启动尾部日志。
     */
    private const val STARTUP_DELAY_MS = 30_000L

    private val secureRandom = SecureRandom()

    /** 当前授权会话（null = 未在进行）。 */
    @Volatile
    private var session: AuthSession? = null

    /** 授权会话状态。 */
    private enum class State { WAITING, EXPIRED, SUCCESS, FAILED, CANCELLED }

    /** 一次扫码授权会话（含取消标记 / 过期刷新计数 / 当前二维码链接 / 是否手动发起）。 */
    private class AuthSession(val manual: Boolean) {
        @Volatile var cancelled = false
        @Volatile var state = State.WAITING
        @Volatile var qrUrl: String = ""
        @Volatile var refreshCount = 0
    }

    /** 授权得到的凭据。 */
    private data class QrCredentials(val appId: String, val appSecret: String)

    /**
     * 启动扫码登录会话：
     * - /hbkrg qr 手动发起：若已有进行中的会话则**先取消旧会话再新建**——
     *   1.7.5.6 口径：手动发起永远得到一个全新二维码（旧实现重显旧码且
     *   旧会话的过期自动刷新继续叠加，用户反馈“发一次 hbkrg qr 出多个
     *   二维码”，观感混乱；取消后旧轮询线程自行退出，不再叠码）；
     * - 启动时未配置凭据的自动发起：已有会话则重显当前二维码，不重复起线程。
     *
     * @param manual 是否由 /hbkrg qr 手动发起（凭据已配置时也允许，扫码
     *   成功后替换现有配置并以新凭据重连）
     * @return 提示文本（写入命令反馈或日志）
     */
    fun start(manual: Boolean = false): String {
        synchronized(this) {
            val current = session
            if (current != null && !current.cancelled &&
                (current.state == State.WAITING || current.state == State.EXPIRED)
            ) {
                if (manual) {
                    // 手动重新发起：取消旧会话（旧轮询线程在下一轮检查时退出），
                    // 本次命令输出一个全新的二维码，不与旧会话的刷新叠加
                    current.cancelled = true
                    current.state = State.CANCELLED
                    PluginFileLog.infoAndKeep("已取消此前的扫码会话，重新发起新的二维码…")
                } else {
                    reprintCurrent()
                    return "扫码登录会话已在进行中，已重新显示当前二维码与链接"
                }
            }
            val newSession = AuthSession(manual)
            session = newSession
            Thread({
                try {
                    runSession(newSession)
                } catch (error: Throwable) {
                    PluginFileLog.errorAndKeep("扫码登录会话异常退出: ${error.message}")
                }
            }, "KERONG-QrAuth").apply {
                isDaemon = true
                start()
            }
            return "已启动 QQ 机器人扫码登录（控制台查看二维码）"
        }
    }

    /** 取消当前会话（插件卸载时调用，防止后台线程继续轮询）。 */
    fun cancel() {
        val current = session ?: return
        if (current.state == State.WAITING || current.state == State.EXPIRED) {
            current.cancelled = true
            current.state = State.CANCELLED
        }
    }

    /** 重显当前会话的二维码与链接（无会话时返回 false）。 */
    fun reprintCurrent(): Boolean {
        val current = session ?: return false
        if (current.qrUrl.isEmpty()) return false
        printQrToConsole(current.qrUrl)
        return true
    }

    /** 当前是否正在进行扫码登录（WAITING / EXPIRED）。 */
    val isActive: Boolean
        get() {
            val current = session ?: return false
            return !current.cancelled &&
                (current.state == State.WAITING || current.state == State.EXPIRED)
        }

    /**
     * 延时发起扫码登录（未配置凭据时由启动流程调用，不立即显示二维码）。
     *
     * 第五轮修订：原「启动待发标记 + Spigot ServerLoadEvent / NeoForge
     * ServerStartedEvent 尾部消费」方案废弃——魔改 / 极端核心可能移除这些
     * 事件，标记永远等不到消费；改为守护线程固定延时 [STARTUP_DELAY_MS]，
     * 任何核心都成立。延时期间已用 /hbkrg qr 手动发起（或会话仍在进行）
     * 则跳过，不重复打印；插件已卸载等极端情形静默退出。
     */
    fun scheduleStartupStart() {
        Thread({
            try {
                Thread.sleep(STARTUP_DELAY_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            try {
                if (!isActive) start(manual = false)
            } catch (_: Throwable) {
                // 守护线程兜底：卸载 / 关服后类加载器关闭等，静默退出
            }
        }, "KERONG-QrDeferred").apply {
            isDaemon = true
            start()
        }
    }

    // ---------- 会话主流程 ----------

    /**
     * 会话主循环：创建任务 → 打印二维码与链接 → 轮询结果 →
     * 过期自动刷新重发（直到成功 / 取消 / 自动刷新次数超限）。
     */
    private fun runSession(session: AuthSession) {
        // 会话开始时凭据是否已配置（手动扫码替换场景），决定头部文案与
        // 成功后是否需要关闭旧连接
        val alreadyConfigured = try {
            plugin.botAppId.isNotBlank() && plugin.botSecret.isNotBlank()
        } catch (_: Throwable) {
            false
        }
        PluginFileLog.infoAndKeep("========== QQ Bot 扫码登录 ==========")
        if (alreadyConfigured) {
            PluginFileLog.infoAndKeep("当前已配置 QQ 机器人凭据——本次扫码成功后将替换 bot.app-id / bot.secret 并以新凭据重新连接")
        } else {
            PluginFileLog.infoAndKeep("未检测到 bot.app-id / bot.secret，请使用手机 QQ 扫描以下二维码完成机器人绑定")
            PluginFileLog.infoAndKeep("（也可以等下方链接出来后手动复制到手机 QQ 打开）")
        }
        PluginFileLog.infoAndKeep("")
        while (!session.cancelled) {
            try {
                val key = generateBindKey()
                val taskId = createBindTask(key)
                val connectUrl = buildConnectUrl(taskId)
                session.qrUrl = connectUrl
                session.state = State.WAITING
                printQrToConsole(connectUrl)
                PluginFileLog.infoAndKeep("扫码链接: $connectUrl")
                PluginFileLog.infoAndKeep("（扫码后自动继续，二维码过期会自动刷新重发，本次会话自动刷新上限 $MAX_REFRESH_COUNT 次）")
                PluginFileLog.infoAndKeep("")
                val credentials = pollUntilResult(session, taskId, key)
                if (credentials != null) {
                    writeCredentials(credentials)
                    session.state = State.SUCCESS
                    PluginFileLog.infoAndKeep("")
                    PluginFileLog.infoAndKeep("========== 扫码成功 ==========")
                    if (alreadyConfigured) {
                        PluginFileLog.infoAndKeep("已替换原有凭据：AppID: ${credentials.appId}")
                    } else {
                        PluginFileLog.infoAndKeep("AppID: ${credentials.appId}")
                    }
                    PluginFileLog.infoAndKeep("Secret: ${credentials.appSecret.take(6)}****（已写入配置文件）")
                    PluginFileLog.infoAndKeep("已将 AppID 和 Secret 写入 config.yml，正在启动机器人…")
                    // 授权成功后立即以新凭据连接（launchQqClient 内部读取最新配置）；
                    // 已配置场景（手动扫码替换）先关闭旧 WSS 会话，避免新旧双连接
                    try {
                        QClient.closeSessionForCredentialSwap()
                    } catch (_: Throwable) {
                    }
                    try {
                        plugin.launchQqClient()
                    } catch (error: Throwable) {
                        PluginFileLog.errorAndKeep("扫码授权后启动机器人失败: ${error.message}（重启服务器后自动连接）")
                    }
                    return
                }
                if (session.cancelled) break
                // 凭据为 null 且未取消 = 二维码过期：自动刷新重发
                session.refreshCount += 1
                if (session.refreshCount > MAX_REFRESH_COUNT) {
                    session.state = State.FAILED
                    PluginFileLog.warnAndKeep("二维码已连续过期 $MAX_REFRESH_COUNT 次，自动刷新已停止——可用 /hbkrg qr 手动重新发起（重新发起后计数重置）。")
                    return
                }
                session.state = State.EXPIRED
                PluginFileLog.warnAndKeep("二维码已过期，正在刷新…（第 ${session.refreshCount}/$MAX_REFRESH_COUNT 次）")
                PluginFileLog.infoAndKeep("")
            } catch (error: Exception) {
                if (session.cancelled) break
                session.state = State.FAILED
                PluginFileLog.errorAndKeep("扫码授权失败: ${error.message}——已停止扫码登录，可稍后用 /hbkrg qr 重试。")
                return
            }
        }
        session.state = State.CANCELLED
    }

    // ---------- 协议细节（与参考实现对齐） ----------

    /** 生成 32 字节随机 AES-256 密钥（Base64 文本，随 create_bind_task 上送）。 */
    private fun generateBindKey(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** 创建绑定任务：POST {"key": <base64>}，返回 task_id。 */
    private fun createBindTask(key: String): String {
        val body = JSON.toJSONString(mapOf("key" to key))
        val response = httpPost(CREATE_URL, body)
        val json = JSON.parseObject(response)
        if (json.getIntValue("retcode") != 0) {
            throw RuntimeException("create_bind_task failed: ${json.getString("msg")}")
        }
        val taskId = json.getJSONObject("data")?.getString("task_id")
        if (taskId.isNullOrBlank()) {
            throw RuntimeException("create_bind_task: missing task_id")
        }
        return taskId
    }

    /** 拼装授权页链接（task_id 与 source 做 URL 编码，+ 号按 %20 处理）。 */
    private fun buildConnectUrl(taskId: String): String {
        val encodedTaskId = URLEncoder.encode(taskId, "UTF-8").replace("+", "%20")
        val encodedSource = URLEncoder.encode("openclaw", "UTF-8").replace("+", "%20")
        return "${CONNECT_URL_PREFIX}$encodedTaskId&source=$encodedSource&_wv=2"
    }

    /**
     * 轮询绑定结果直到成功 / 过期 / 取消。
     *
     * @return 成功返回凭据；过期（status=3）返回 null；取消返回 null（调用方查 cancelled）
     */
    private fun pollUntilResult(session: AuthSession, taskId: String, key: String): QrCredentials? {
        while (!session.cancelled) {
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return null
            }
            if (session.cancelled) return null
            try {
                val body = JSON.toJSONString(mapOf("task_id" to taskId))
                val response = httpPost(POLL_URL, body)
                val json = JSON.parseObject(response)
                if (json.getIntValue("retcode") != 0) {
                    PluginFileLog.warnAndKeep("扫码轮询失败: ${json.getString("msg")}，继续重试…")
                    continue
                }
                val data: JSONObject? = json.getJSONObject("data") ?: continue
                when (data!!.getIntValue("status")) {
                    2 -> {
                        // 授权成功：取出凭据并解密
                        val appId = data.get("bot_appid")?.toString() ?: ""
                        val encryptSecret = data.getString("bot_encrypt_secret") ?: ""
                        return QrCredentials(appId, decryptSecret(encryptSecret, key))
                    }
                    3 -> return null // 二维码过期
                    else -> continue // 仍在等待扫码
                }
            } catch (error: Exception) {
                PluginFileLog.warnAndKeep("扫码轮询异常: ${error.message}，继续重试…")
            }
        }
        return null
    }

    /**
     * AES-256-GCM 解密 Secret：
     * 密文 = 12 字节 IV + 密文体 + 16 字节认证标签，整体 Base64；
     * 密钥为 create_bind_task 时上送的 32 字节随机数（Base64）。
     */
    private fun decryptSecret(encryptSecretBase64: String, keyBase64: String): String {
        val keyBytes = Base64.getDecoder().decode(keyBase64)
        if (keyBytes.size != 32) {
            throw IllegalArgumentException("key must be 32 bytes (AES-256), got ${keyBytes.size}")
        }
        val cipherBytes = Base64.getDecoder().decode(encryptSecretBase64)
        if (cipherBytes.size <= 28) {
            throw IllegalArgumentException("ciphertext too short: ${cipherBytes.size}")
        }
        val iv = cipherBytes.copyOfRange(0, 12)
        val cipherText = cipherBytes.copyOfRange(12, cipherBytes.size - 16)
        val tag = cipherBytes.copyOfRange(cipherBytes.size - 16, cipherBytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherText + tag), Charsets.UTF_8)
    }

    /** 极简 JSON POST（Content-Type / Accept: application/json，10 秒超时）。 */
    private fun httpPost(url: String, body: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = HTTP_TIMEOUT_MS.toInt()
            connection.readTimeout = HTTP_TIMEOUT_MS.toInt()
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                val errorText = try {
                    connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (_: Throwable) {
                    null
                }
                throw RuntimeException("HTTP $code: $errorText")
            }
        } finally {
            connection.disconnect()
        }
    }

    // ---------- 控制台二维码 ----------

    /**
     * 控制台打印 ASCII 二维码（复用 1.7.4 自研编码器；失败退化为只打链接）。
     *
     * 1.7.5.5：按控制台实际编码选渲染器（中文 Windows 服务器控制台默认 GBK，
     * 半块字符 ▀▄█ 不在 GBK 字符集、会被替换成 ? 导致二维码不可扫）：
     * - 控制台可编码半块字符 → 原版半块渲染（Linux/macOS UTF-8 终端）；
     * - 可编码 ■ 与全角空格 → 全角方块渲染（GBK 类控制台）；
     * - 其余 → 纯 ASCII ## 渲染（任何终端编码兜底）。
     * 探测取 System.out 的字符集（Java 21 起标准控制台流随终端编码初始化；
     * 输出重定向到文件/管道时为 UTF-8，走原版渲染——写日志文件本身按 UTF-8）。
     */
    private fun printQrToConsole(url: String) {
        try {
            val qr = renderQrForConsole(url)
            for (line in qr.lineSequence()) {
                PluginFileLog.infoAndKeep("  $line")
            }
        } catch (t: Throwable) {
            PluginFileLog.warnAndKeep("无法渲染二维码，请复制下方链接到手机 QQ 打开: ${t.message}")
        }
    }

    /** 按控制台可编码性选择二维码渲染形态（探测失败按原版半块渲染）。 */
    private fun renderQrForConsole(url: String): String {
        val encoder = try {
            System.out.charset().newEncoder()
        } catch (_: Throwable) {
            null
        } ?: return cn.kerong.bot.tools.QrCode.encodeAscii(url, quietZone = 2)
        val halfBlocksOk = encoder.canEncode('▀') &&
            encoder.canEncode('▄') &&
            encoder.canEncode('█')
        if (halfBlocksOk) {
            return cn.kerong.bot.tools.QrCode.encodeAscii(url, quietZone = 2)
        }
        if (encoder.canEncode('■') && encoder.canEncode('\u3000')) {
            return cn.kerong.bot.tools.QrCode.encodeCjk(url, quietZone = 2)
        }
        return cn.kerong.bot.tools.QrCode.encodePlain(url, quietZone = 2)
    }

    // ---------- 凭据落盘 ----------

    /**
     * 把授权得到的凭据写入 config.yml（bot.app-id / bot.secret）并重载配置。
     *
     * 写入路径与 /hbkrg bpngsz 相同：平台 YamlConfiguration.set + saveConfig
     * （Spigot 1.18.1+ / NeoForge 兼容层均保留配置注释）。
     */
    private fun writeCredentials(credentials: QrCredentials) {
        val kerong = try {
            BotShared.instance
        } catch (_: Throwable) {
            null
        } ?: throw IllegalStateException("插件实例未初始化，无法写入凭据")
        val javaPlugin = kerong as? JavaPlugin
            ?: throw IllegalStateException("当前平台不支持自动写入配置，请手动填入 bot.app-id / bot.secret")
        val config = javaPlugin.config
        config.set("bot.app-id", credentials.appId)
        config.set("bot.secret", credentials.appSecret)
        javaPlugin.saveConfig()
        kerong.reloadPluginConfig()
    }
}
