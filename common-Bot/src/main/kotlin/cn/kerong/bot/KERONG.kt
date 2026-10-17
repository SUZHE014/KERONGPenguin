package cn.kerong.bot

import cn.kerong.bot.events.commands.CustomCommandRegistry
import cn.kerong.bot.provider.BotShared
import cn.kerong.bot.events.commands.SensitiveFilter
import cn.kerong.bot.provider.CommandProvider
import cn.kerong.bot.provider.ConfigProvider
import cn.kerong.bot.provider.HExecution
import cn.kerong.bot.provider.LoggerProvider
import cn.kerong.bot.provider.MessageProvider
import cn.kerong.bot.provider.SchedulerProvider
import cn.kerong.bot.state.CommandRepositories
import cn.kerong.bot.tools.PluginFileLog
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import io.github.kloping.qqbot.utils.LoggerImpl
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

/**
 * KERONG 插件平台契约。
 *
 * 各平台适配器（Spigot / Allay / Nukkit / Proxy ...）实现本接口，
 * 由 common-Bot 中的 QQ 客户端与命令系统驱动。
 */
interface KERONG : LoggerProvider, ConfigProvider, CommandProvider, SchedulerProvider, MessageProvider {

    /** QQ 机器人的 AppId。 */
    val botAppId: String

    /** QQ 机器人的 Secret。 */
    val botSecret: String

    /** 创建当前平台的一次命令执行器。 */
    fun createCommandExecutor(): HExecution

    /** 重新加载插件配置（/kerong reload）。 */
    fun reloadPluginConfig()

    /** 在线玩家名列表。 */
    val onlineList: List<String>

    /**
     * 服务器性能快照（1.6.7 新增，/查性能 渲染数据源）。
     *
     * 平台各自实现：TPS / MSPT 为核心层指标（原版 Spigot 无公开 API，
     * 读不到时为 null，卡片对应项显示「--」）；JVM 内存与进程 CPU 为
     * JVM 级数据双端一致，统一由 [PerformanceSnapshot.capture] 采集；
     * 服务端整体不可用时返回 null（渲染层提示数据暂不可用）。
     */
    fun performanceSnapshot(): PerformanceSnapshot?

    /** QQ 机器人日志文件名模板（位于插件数据目录），null 表示不落盘。 */
    fun qqBotLogFilePattern(): String? {
        val configDirectory = configFile?.parentFile ?: return null
        return configDirectory.resolve("logs/Bot-%s.log").path
    }

    // ---------- MessageProvider 默认实现（委托 QClient） ----------

    /** 向所有配置的群发送 Markdown 消息。 */
    override fun sendMarkdown(markdownContent: String, keyboard: Keyboard?) {
        QClient.sendMarkdown(markdownContent, keyboard)
    }

    /** 向指定群发送 Markdown 消息。 */
    override fun sendMarkdownToGroup(groupOpenId: String, markdownContent: String, keyboard: Keyboard?) {
        QClient.sendMarkdownToGroup(groupOpenId, markdownContent, keyboard)
    }

    /** 在群消息事件上下文中回复 Markdown 消息。 */
    override fun replyMarkdown(event: GroupMessageEvent, content: String, keyboard: Keyboard?) {
        QClient.replyMarkdown(event, content, keyboard)
    }

    /** 在群消息事件上下文中回复图片。 */
    override fun replyWithImg(event: GroupMessageEvent, text: String, imgUrl: String) {
        QClient.replyWithImg(event, text, imgUrl)
    }

    /**
     * 初始化运行时：注册共享实例、装载状态、启动 QQ 客户端。
     * 由平台实现于插件启用时调用。
     * （1.6.6.1 覆盖版：Web 面板占位模块已随 WebUI 功能整体移除。）
     */
    fun initializeRuntime() {
        BotShared.setInstance(this)

        // SDK 日志接管（0.1.5.2 隐私 / 控制台刷屏修复）：
        // 仅错误级别上控制台（log_error），其余一律写入插件日志文件，
        // 避免含消息内容的 Info / Debug 日志（如事件分发、WSS 帧）泄露到控制台。
        // 0.1.5.4：错误级别同步写入日志文件；普通级别改走 PluginFileLog
        // （同一份 qq-bind-日期.log，不再依赖 QqBindManager 实例初始化时序）。
        // 1.6.6.3：SDK 断线重连路径的三条固定「ERROR」提示（kloping SDK 在
        // delayIdentifyConnect / 未归类关闭码时按 error 级别输出）在重连风暴
        // 期间会逐次刷屏控制台且语义误导（如 code 1000 正常踢线也报
        // 「websocket closed with code 1011,server internal exception」）——
        // 这三条改走仅文件；真正的协议异常仍由 WssDiagnostics.onError /
        // ReconnectManager 汇总后在控制台醒目输出，其余未知 SDK 错误保留控制台。
        LoggerImpl.setLogSink(object : LoggerImpl.LogSink {
            override fun log(message: String, level: Int) {
                if (level == -1 && !isSdkReconnectNoise(message)) {
                    // 0.1.5.5：log_error 已双写（控制台 + 文件），直接委托避免重复写文件
                    log_error("[Bot] $message")
                } else {
                    PluginFileLog.write("[Bot] $message")
                }
            }
        })

        val configDirectory = configFile?.parentFile
        CommandRepositories.initialize(configDirectory)
        reloadRuntimeConfig()
        launchQqClient()
        // 1.6.2：更新检查（异步延迟 8 秒，错开启动高峰；update-check.enabled 可关闭）
        scheduleUpdateCheck()
    }

    /**
     * 1.6.2：异步延迟执行更新检查（不阻塞启用流程）。
     *
     * 有新版本时控制台输出「最新版本号 + 下载页 + 简要更新日志」；
     * 检测失败只输出一句话，异常细节写入插件日志文件。
     */
    private fun scheduleUpdateCheck() {
        val app = this
        Thread({
            try {
                Thread.sleep(8_000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            try {
                UpdateChecker.check(app.pluginVersion)
            } catch (_: Throwable) {
            }
        }, "qq-update-check").apply { isDaemon = true }.start()
    }

    /**
     * 停止运行时：断开 QQ 客户端。
     */
    fun shutdownRuntime() {
        try {
            QClient.shutdown()
        } finally {
            // 1.5.3：OpenId 目录脏数据落盘（昵称记录用于 /查询OpenID 反查）
            try {
                cn.kerong.penguin.spigot.qqbind.OpenIdDirectory.flush()
            } catch (_: Throwable) {
            }
            LoggerImpl.clearLogSink()
        }
    }

    /** 重新加载运行时配置：Markdown 模板、消息模板与自定义命令。 */
    fun reloadRuntimeConfig() {
        initializeMarkdownTemplates()
        // 1.6.0：消息模板（每条群回复对应的 .md 文件，messages 段配置）
        try {
            cn.kerong.penguin.spigot.qqbind.MessageTemplates.initialize()
            cn.kerong.penguin.spigot.qqbind.MessageTemplates.clearCache()
        } catch (error: Throwable) {
            log_warning("消息模板初始化失败: ${error.message}")
        }
        CustomCommandRegistry.replace(customCommands())
    }

    /** 释放内置 Markdown 模板到插件目录（不覆盖已有文件）。 */
    fun initializeMarkdownTemplates() {
        val configDirectory = configFile?.absoluteFile?.parentFile
        if (configDirectory == null) {
            log_warning("无法确定插件配置目录，未初始化 Markdown 模板")
            return
        }
        val markdownDirectory = configDirectory.resolve("Markdown")
        if (!markdownDirectory.isDirectory && !markdownDirectory.mkdirs()) {
            log_warning("无法创建 Markdown 目录: ${markdownDirectory.path}")
            return
        }
        for ((fileName, resourcePath) in DEFAULT_MARKDOWN_TEMPLATES) {
            val target = markdownDirectory.resolve(fileName)
            if (target.exists()) continue
            val resource = KERONG::class.java.classLoader.getResourceAsStream(resourcePath)
            if (resource == null) {
                log_warning("找不到内置 Markdown 模板资源: $resourcePath")
                continue
            }
            try {
                resource.use { Files.copy(it, target.toPath()) }
                log_info("已初始化 Markdown 模板: ${target.path}")
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // 并发初始化时忽略
            } catch (error: Exception) {
                log_warning("初始化 Markdown 模板 ${target.path} 失败: ${error.message}")
            }
        }
    }

    /** 直接派发命令到服务器控制台（去除开头的 "/"）。 */
    override fun dispatchCommand(command: String): CompletableFuture<HExecution> =
        createCommandExecutor().execute(command.removePrefix("/"))

    /** 解析自定义命令后派发执行。 */
    fun sendCommand(command: String): CompletableFuture<HExecution> {
        val resolved = CustomCommandRegistry.resolve(command)
        if (resolved.error != null) {
            return CompletableFuture.completedFuture(TextExecution(resolved.error!!, this))
        }
        return dispatchCommand(resolved.command!!)
    }

    /** 异步启动 QQ 机器人客户端（0.1.5.4：启动过程日志同步落盘）。 */
    fun launchQqClient() {
        val appId = botAppId
        val secret = botSecret
        if (appId.isBlank() || secret.isBlank()) {
            PluginFileLog.warnAndKeep("未配置 bot.app-id 或 bot.secret，QQ 机器人未启动")
            return
        }
        PluginFileLog.infoAndKeep("正在后台启动 QQ 机器人客户端（AppID: $appId）…")
        submitAsync {
            try {
                QClient.launchClient(appId, secret, qqBotLogFilePattern())
            } catch (error: Exception) {
                PluginFileLog.errorAndKeep("QQ 机器人启动失败: ${error.message}")
                PluginFileLog.errorAndKeep("启动失败堆栈: ${error.stackTraceToString().lineSequence().take(6).joinToString("\n")}")
                PluginFileLog.warnAndKeep("QQ 命令将无法响应，请检查网络连接与机器人凭据（bot.app-id / bot.secret）")
            }
        }
    }

    /** 本地正则过滤 + 敏感词审核。 */
    fun auditText(text: String): String =
        SensitiveFilter.filter(filterText(text), auditBaseUrl(), auditApiKey(), auditModel(), sensitiveWords())

    /**
     * 判断 SDK 消息是否为断线重连路径的固定噪声（1.6.6.3）。
     *
     * kloping SDK 在连接关闭重试时会按 error 级别输出以下三条固定文案，
     * 与真实错误无关（连接最终会自动恢复）：
     * - 「暂未处理的异常code(%s)」——SDK 对未归类关闭码（如 QQ 网关正常踢线 1000）的兜底输出；
     * - 「websocket closed with code 1011,server internal exception」——任何延迟重连
     *   （含 -1 / 1001 / 1011 / 未归类码）都输出这条，并不代表服务端 1011；
     * - 「reconnect in 3 seconds」——重连倒计时提示。
     */
    private fun isSdkReconnectNoise(message: String): Boolean {
        return message.startsWith("暂未处理的异常code") ||
            message == "websocket closed with code 1011,server internal exception" ||
            message == "reconnect in 3 seconds"
    }

    /** 内置 Markdown 模板资源映射。 */
    companion object {
        private val DEFAULT_MARKDOWN_TEMPLATES = mapOf(
            "online.md" to "Markdown/online.md",
        )
    }
}

/**
 * 纯文本执行结果：用于自定义命令解析失败等场景，
 * 让调用方直接拿到错误文本而不经过服务器控制台。
 */
internal class TextExecution(
    private val text: String,
    private val bot: KERONG,
) : HExecution {
    override val rawString: String = text

    override fun execute(command: String): CompletableFuture<HExecution> = bot.sendCommand(command)
}

/**
 * 服务器性能快照（1.6.7 新增，/查性能 卡片数据）。
 *
 * - TPS / MSPT 由平台实现采集（Paper / Purpur / Folia / NeoForge 可读，
 *   原版 Spigot 无公开 API 时为 null，卡片显示「--」）；
 * - JVM 堆内存与进程 CPU 为 JVM 级数据，双端一致，统一由
 *   [capture] 伴生工厂采集，平台实现只补核心层指标。
 */
data class PerformanceSnapshot(
    /** 最近 1 分钟平均 TPS（满值 20；核心不支持读取时为 null）。 */
    val tps: Double?,
    /** 平均每 tick 耗时（毫秒；核心不支持读取时为 null）。 */
    val mspt: Double?,
    /** JVM 堆已用（MB）。 */
    val usedMemoryMb: Long,
    /** JVM 堆上限（MB）。 */
    val maxMemoryMb: Long,
    /** 本进程 CPU 占用（0.0 ~ 1.0；读取失败时为 null）。 */
    val cpuLoad: Double?,
    /** 当前在线人数。 */
    val onlineCount: Int,
    /** 服务器人数上限。 */
    val maxPlayers: Int,
) {
    companion object {
        /**
         * 统一采集 JVM 内存与进程 CPU（双端一致），平台实现只需补
         * TPS / MSPT / 在线人数与上限。
         *
         * - CPU 用进程级占用（getProcessCpuLoad，即任务管理器里本进程
         *   的 CPU 列；首次调用 JVM 可能返回 -1 表示暂不可用 → null）；
         * - 堆内存为 totalMemory - freeMemory（存活 + 待回收的堆占用，
         *   与面板上 JVM 已提交内存不是一回事，见 /kerong mem）。
         */
        @JvmStatic
        fun capture(
            tps: Double?,
            mspt: Double?,
            onlineCount: Int,
            maxPlayers: Int,
        ): PerformanceSnapshot {
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
            val maxMb = runtime.maxMemory() / (1024L * 1024L)
            val cpu = try {
                val bean = java.lang.management.ManagementFactory.getPlatformMXBean(
                    com.sun.management.OperatingSystemMXBean::class.java
                )
                bean.processCpuLoad.takeIf { it >= 0.0 }
            } catch (_: Throwable) {
                null
            }
            return PerformanceSnapshot(tps, mspt, usedMb, maxMb, cpu, onlineCount, maxPlayers)
        }
    }
}
