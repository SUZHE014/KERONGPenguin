package cn.kerong.bot

import cn.kerong.bot.provider.BotShared
import cn.kerong.bot.provider.plugin
import cn.kerong.bot.tools.PluginFileLog
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.entities.qqpd.Channel
import io.github.kloping.qqbot.http.data.V2MsgData
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.management.ManagementFactory
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 服务器开关消息发送（配置口径参考 HuHoBot 的
 * server-notify 段；默认文案去表情 + 180 秒重启判定窗口，
 * 重启与普通开机两类文案分开）。
 *
 * 机制：
 * - 用状态文件跨进程记录上一次运行是怎么结束的（正常收尾 clean 标记 +
 *   停止时间 + JVM 启动时间）；
 * - 服务器启动完成（QQ 客户端开始异步连接）时读上次状态：
 *   上次未正常收尾 → 崩溃 / kill -9 / 断电，补发「意外关闭」通知；
 *   上次正常收尾（记录过真实停止时间）→ 按 180 秒重启判定窗口分流：
 *   距上次停止 ≤ restart-window 秒（默认 180，可配置）判定为「重启」，
 *   发「服务器重启完毕，已正常运行(o‿∩)」；超过窗口按普通开机处理，
 *   发「服务器已开启！！(≧∇≦)/」；窗口配 0 = 不判定重启，一律按普通开机；
 *   首次安装（无状态记录）不发送；
 * - 服务器真实停止时（平台层判定，插件重载不算）发「服务器已正常关闭」，
 *   同时记录停止时间；
 * - 启动侧通知是异步等 QQ 就绪后再发（守护线程轮询，最多 60 秒），
 *   发送失败重试 3 次。
 *
 * 双端接线：Spigot 在 onEnable 服务器启动完成后调 [onServerStarted]、
 * onDisable 最开始调 [onServerStopping]（传 Paper isStopping / NMS
 * isRunning 判定结果）；NeoForge 在服务器启动 / 停止事件里调用
 * （模组无热重载概念，停止事件按真实停止处理）。
 *
 * 多服务器集群：发送前经 [ClusterState.shouldServeGroup] 路由——该群
 * 归属其他服务器时让位，避免多台实例重复播报。
 */
object ServerNotifyManager {

    /** 状态文件名（位于插件数据目录）。 */
    private const val STATE_FILE_NAME = "server-notify-state.properties"

    /** 轮询 QQ 就绪的间隔（毫秒）。 */
    private const val RETRY_INTERVAL_MS = 2_000L

    /** 最多等待 60 秒让 QQ 完成鉴权。 */
    private const val READY_MAX_ATTEMPTS = 30

    /** 鉴权成功后发送失败时的重试次数。 */
    private const val SEND_MAX_ATTEMPTS = 3

    /** 本实例是否仍在等待发送（插件停止时置 false，中断等待线程）。 */
    @Volatile
    private var active = true

    @Volatile
    private var startupNotifier: Thread? = null

    /** 同一 JVM 内是否已执行过启动检查（防重复 onEnable）。 */
    private val startedOnce = AtomicBoolean(false)

    /** 状态文件（无数据目录的平台返回 null，功能自动退化为只发关服消息）。 */
    private fun stateFile(): File? {
        // 插件实例未就绪（理论不会发生）或无配置文件的平台：返回 null，
        // 功能退化为只发关服消息、不做跨进程状态判定
        val configDir = try {
            plugin.configFile?.absoluteFile?.parentFile ?: return null
        } catch (_: Throwable) {
            return null
        }
        return configDir.resolve(STATE_FILE_NAME)
    }

    /** 服务器启动完成、QQ 客户端已开始异步连接后调用（双端各一次）。 */
    fun onServerStarted() {
        if (!startedOnce.compareAndSet(false, true)) return
        active = true
        val previous = readState()
        val currentJvmStart = ManagementFactory.getRuntimeMXBean().startTime
        val previousJvmStart = previous?.getProperty(KEY_JVM_START)?.toLongOrNull()
        val previousClean = previous?.getProperty(KEY_CLEAN)?.toBooleanStrictOrNull() ?: false
        val previousStopTime = previous?.getProperty(KEY_STOP_TIME)?.toLongOrNull() ?: 0L

        // 同一个 JVM 内重新 onEnable 属于插件重载，不按「上一次运行」判断
        if (previousJvmStart != null && previousJvmStart > 0 && previousJvmStart != currentJvmStart) {
            when {
                !previousClean -> {
                    PluginFileLog.infoAndKeep("[服务器通知] 检测到上次服务器未正常关闭，将在 QQ 就绪后发送意外关闭通知")
                    notifyAfterStartup(
                        plugin.isServerNotifyCrashEnabled(),
                        plugin.serverNotifyCrashMessage(),
                    )
                }
                // 上次正常收尾且记录过真实停止时间：按重启判定窗口分流
 //（距上次停止 ≤ restart-window 秒判定为「重启」
                //  发重启完毕文案，超过窗口按普通开机发开启文案；插件重载 /
                //  手动停用的收尾不带停止时间，不会误发）
                previousStopTime > 0 -> {
                    val windowMs = plugin.serverNotifyRestartWindow() * 1000L
                    val elapsedMs = System.currentTimeMillis() - previousStopTime
                    if (windowMs > 0 && elapsedMs <= windowMs) {
                        PluginFileLog.infoAndKeep("[服务器通知] 检测到上次正常关闭后 ${elapsedMs / 1000} 秒内重新启动（窗口 ${plugin.serverNotifyRestartWindow()} 秒），判定为重启，将在 QQ 就绪后发送重启通知")
                        notifyAfterStartup(
                            plugin.isServerNotifyRestartEnabled(),
                            plugin.serverNotifyRestartMessage(),
                        )
                    } else {
                        PluginFileLog.infoAndKeep("[服务器通知] 检测到上次正常运行后重新启动（距上次关闭已超过 ${plugin.serverNotifyRestartWindow()} 秒窗口），将在 QQ 就绪后发送开启通知")
                        notifyAfterStartup(
                            plugin.isServerNotifyStartEnabled(),
                            plugin.serverNotifyStartMessage(),
                        )
                    }
                }
            }
        }

        // 先标记为「未收尾」，之后无论崩溃还是正常停止都会覆盖这个状态
        writeState(clean = false, stopTime = 0L, jvmStart = currentJvmStart)
    }

    /**
     * 平台 onDisable / 服务器停止事件的最开始调用（此时 QQ 客户端仍可用）。
     *
     * @param realStop 是否为真实的服务器停止（插件重载 / 手动停用时传 false：
     *   不发关机消息也不记停止时间，但记一次正常收尾，避免误报崩溃）
     */
    fun onServerStopping(realStop: Boolean) {
        active = false
        startupNotifier?.interrupt()
        startupNotifier = null

        if (realStop) {
            try {
                val message = plugin.serverNotifyShutdownMessage()
                if (plugin.isServerNotifyShutdownEnabled() && message.isNotBlank()) {
                    val text = formatMessage(message)
                    for (groupId in plugin.groupOpenIdList()) {
                        sendToGroupSync(groupId, text)
                    }
                }
            } catch (error: Throwable) {
                // 通知失败不能阻断插件卸载流程，但正常收尾状态仍要记录
                PluginFileLog.warnAndKeep("[服务器通知] 发送关机通知失败: ${error.message}")
            }
        } else {
            PluginFileLog.infoAndKeep("[服务器通知] 检测到插件重载或手动停用（服务器仍在运行），跳过关机通知")
        }

        // 无论是否真的关服都记一次正常收尾；只有真实停止才写停止时间
        writeState(
            clean = true,
            stopTime = if (realStop) System.currentTimeMillis() else 0L,
            jvmStart = ManagementFactory.getRuntimeMXBean().startTime,
        )
    }

    /**
     * QQ 是异步连接的，启动后立刻发送通常还没鉴权成功：
     * 守护线程轮询就绪后再发，避免错过通知。
     */
    private fun notifyAfterStartup(enabled: Boolean, message: String) {
        if (!enabled || message.isBlank()) return
        val groups = plugin.groupOpenIdList()
        if (groups.isEmpty()) {
            PluginFileLog.infoAndKeep("[服务器通知] 未配置 QQ 群，跳过服务器状态通知")
            return
        }
        val text = formatMessage(message)
        val thread = Thread({
            var waited = 0
            while (active && !isQqReady()) {
                if (waited++ >= READY_MAX_ATTEMPTS) {
                    PluginFileLog.warnAndKeep("[服务器通知] 未发送：等待 QQ 鉴权超时")
                    return@Thread
                }
                try {
                    Thread.sleep(RETRY_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            if (!active) return@Thread
            repeat(SEND_MAX_ATTEMPTS) { attempt ->
                val sentCount = groups.count { sendToGroupSync(it, text) }
                if (sentCount > 0) return@Thread
                if (attempt < SEND_MAX_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(RETRY_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
            PluginFileLog.warnAndKeep("[服务器通知] 发送失败: $text")
        }, "KERONG-ServerNotify")
        thread.isDaemon = true
        startupNotifier = thread
        thread.start()
    }

    /** QQ 客户端是否已完成鉴权（bot 实例就绪）。 */
    private fun isQqReady(): Boolean = try {
        QClient.starter?.bot?.id?.isNotEmpty() == true
    } catch (_: Throwable) {
        false
    }

    /**
     * 同步向单个群发送文本（不走发送队列——关服时队列线程可能已被关闭；
     * 多服务器集群下该群归属其他服务器时让位）。
     */
    private fun sendToGroupSync(groupOpenId: String, text: String): Boolean {
        val session = try {
            QClient.starter
        } catch (_: Throwable) {
            null
        } ?: return false
        if (!ClusterState.shouldServeGroup(groupOpenId)) return true // 让位视为已处理
        return try {
            val payload = V2MsgData().setContent(text)
            session.bot?.groupBaseV2?.send(groupOpenId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
            true
        } catch (error: Throwable) {
            PluginFileLog.warnAndKeep("[服务器通知] 向群 $groupOpenId 发送失败: ${error.message}")
            false
        }
    }

    /** 应用占位符（与进出服播报同口径：{server} / {platform}）。 */
    private fun formatMessage(raw: String): String = raw
        .replace("{server}", plugin.serverName)
        .replace("{platform}", plugin.platform)
        .trim()

    // ---------- 状态文件 ----------

    private fun readState(): Properties? {
        val file = stateFile() ?: return null
        if (!file.isFile) return null
        return try {
            Properties().apply { FileInputStream(file).use { load(it) } }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeState(clean: Boolean, stopTime: Long, jvmStart: Long) {
        val file = stateFile() ?: return
        try {
            val properties = Properties().apply {
                setProperty(KEY_JVM_START, jvmStart.toString())
                setProperty(KEY_STOP_TIME, stopTime.toString())
                setProperty(KEY_CLEAN, clean.toString())
            }
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { properties.store(it, "KERONGPenguin server notify state") }
        } catch (error: Throwable) {
            PluginFileLog.warnAndKeep("[服务器通知] 写入状态文件失败: ${error.message}")
        }
    }

    private const val KEY_JVM_START = "jvm-start"
    private const val KEY_STOP_TIME = "stop-time"
    private const val KEY_CLEAN = "clean"
}
