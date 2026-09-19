package cn.huohuas001.bot

import cn.huohuas001.bot.events.commands.CustomCommandRegistry
import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.events.commands.SensitiveFilter
import cn.huohuas001.bot.provider.CommandProvider
import cn.huohuas001.bot.provider.ConfigProvider
import cn.huohuas001.bot.provider.HExecution
import cn.huohuas001.bot.provider.LoggerProvider
import cn.huohuas001.bot.provider.MessageProvider
import cn.huohuas001.bot.provider.SchedulerProvider
import cn.huohuas001.bot.state.CommandRepositories
import cn.huohuas001.bot.tools.PluginFileLog
import cn.huohuas001.bot.web.WebUiServer
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import io.github.kloping.qqbot.utils.LoggerImpl
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

/**
 * HuHoBot 插件平台契约。
 *
 * 各平台适配器（Spigot / Allay / Nukkit / Proxy ...）实现本接口，
 * 由 common-Bot 中的 QQ 客户端与命令系统驱动。
 */
interface HuHoBot : LoggerProvider, ConfigProvider, CommandProvider, SchedulerProvider, MessageProvider {

    /** QQ 机器人的 AppId。 */
    val botAppId: String

    /** QQ 机器人的 Secret。 */
    val botSecret: String

    /** 创建当前平台的一次命令执行器。 */
    fun createCommandExecutor(): HExecution

    /** 重新加载插件配置（/huhobot reload）。 */
    fun reloadPluginConfig()

    /** 在线玩家名列表。 */
    val onlineList: List<String>

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
     * 初始化运行时：注册共享实例、装载状态、启动 QQ 客户端与 Web 面板。
     * 由平台实现于插件启用时调用。
     */
    fun initializeRuntime() {
        BotShared.setInstance(this)

        // SDK 日志接管（0.1.5.2 隐私 / 控制台刷屏修复）：
        // 仅错误级别上控制台（log_error），其余一律写入插件日志文件，
        // 避免含消息内容的 Info / Debug 日志（如事件分发、WSS 帧）泄露到控制台。
        // 0.1.5.4：错误级别同步写入日志文件；普通级别改走 PluginFileLog
        // （同一份 qq-bind-日期.log，不再依赖 QqBindManager 实例初始化时序）。
        LoggerImpl.setLogSink(object : LoggerImpl.LogSink {
            override fun log(message: String, level: Int) {
                if (level == -1) {
                    log_error(message)
                    PluginFileLog.write("[Bot][错误] $message")
                } else {
                    PluginFileLog.write("[Bot] $message")
                }
            }
        })

        val configDirectory = configFile?.parentFile
        CommandRepositories.initialize(configDirectory)
        reloadRuntimeConfig()
        launchQqClient()
        WebUiServer.start()
    }

    /** 停止运行时：断开 QQ 客户端、关闭 Web 面板。 */
    fun shutdownRuntime() {
        try {
            QClient.shutdown()
        } finally {
            WebUiServer.stop()
            LoggerImpl.clearLogSink()
        }
    }

    /** 重新加载运行时配置：Markdown 模板与自定义命令。 */
    fun reloadRuntimeConfig() {
        initializeMarkdownTemplates()
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
            val resource = HuHoBot::class.java.classLoader.getResourceAsStream(resourcePath)
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

    /** Web 面板可展示的配置值（平台按需覆写）。 */
    fun webUiConfigValues(): Map<String, Any> = emptyMap()

    /** 应用 Web 面板提交的配置修改（平台按需覆写）。 */
    fun applyWebUiConfigChanges(changes: JSONObject): Boolean = false

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
    private val bot: HuHoBot,
) : HExecution {
    override val rawString: String = text

    override fun execute(command: String): CompletableFuture<HExecution> = bot.sendCommand(command)
}
