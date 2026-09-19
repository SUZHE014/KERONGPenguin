package cn.huohuas001.huhobotPenguin.spigot

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.agent.AgentConfig
import cn.huohuas001.bot.provider.AdminMode
import cn.huohuas001.bot.provider.ChatFormat
import cn.huohuas001.bot.provider.CustomCommandDetail
import cn.huohuas001.bot.provider.HExecution
import cn.huohuas001.bot.provider.Motd
import cn.huohuas001.bot.provider.PlayerEventFormat
import cn.huohuas001.bot.provider.WhiteList
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobotPenguin.spigot.commands.BukkitConsoleSender
import cn.huohuas001.huhobotPenguin.spigot.commands.CommandOutputAppender
import cn.huohuas001.huhobotPenguin.spigot.commands.HuHoBotCommand
import cn.huohuas001.huhobotPenguin.spigot.commands.HuHoBotTask
import cn.huohuas001.huhobotPenguin.spigot.commands.HybridCommandExecutor
import cn.huohuas001.huhobotPenguin.spigot.events.GameChat
import cn.huohuas001.huhobotPenguin.spigot.events.PlayerEventsListener
import cn.huohuas001.huhobotPenguin.spigot.manager.ConfigManager
import cn.huohuas001.huhobotPenguin.spigot.stats.PlayerStatsManager
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginCommand
import org.bukkit.command.TabCompleter
import org.bukkit.command.CommandExecutor
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale

/**
 * KERONG Penguin 插件主类（Spigot 平台）。
 *
 * 生命周期：
 * 1. onEnable：初始化配置 → 注册命令与事件 → 启动 QQ 机器人运行时；
 * 2. onDisable：停止 QQ 机器人、卸载日志捕获器、保存统计数据。
 */
class HuHoBotSpigot : JavaPlugin(), HuHoBot {
    private lateinit var configManager: ConfigManager

    // ---------- 生命周期 ----------

    override fun onEnable() {
        configManager = ConfigManager(this)
        configManager.initialize()

        // 自动创建卡片背景图目录
        File(dataFolder, "img").mkdirs()

        // 启动 QQ 机器人运行时（注册共享实例 → 装载状态 → 异步启动 QQ 客户端）
        initializeRuntime()

        // 初始化命令
        val command = HuHoBotCommand(this)
        getCommand("huhobot")?.let { pluginCommand ->
            pluginCommand.setExecutor(command)
            pluginCommand.setTabCompleter(command)
        } ?: log_error("无法注册 /huhobot 命令，请检查 plugin.yml")

        // 注册游戏事件监听（GameChat 构造时会初始化 QqBindManager，需在共享实例注册后）
        server.pluginManager.registerEvents(GameChat(), this)
        server.pluginManager.registerEvents(PlayerEventsListener(), this)

        // 注册统计记录器（/查信息 数据源）
        PlayerStatsManager.initialize()

        logCommandExecutor()
        log_info("HuHoBot Penguin 已加载")
    }

    override fun onDisable() {
        // 保存统计数据
        try {
            PlayerStatsManager.shutdown()
        } catch (_: Throwable) {
        }
        shutdownRuntime()
        CommandOutputAppender.Companion.removeInstance()
    }

    override fun reloadPluginConfig() {
        configManager.reload()
        reloadRuntimeConfig()
        logCommandExecutor()
    }

    // ---------- 命令执行器选择（含混合端自动检测） ----------

    /**
     * 日志提示当前使用的命令执行器。
     *
     * 修复记录（0.1.5.0）：
     * 旧版本默认 command-sender=Hybrid，导致纯 Spigot/Paper 服务器
     * 也提示“已启用混合控制台命令执行器”。现在：
     * - Auto（默认）：检测到混合端才使用混合执行器；
     * - Hybrid：明确指定混合端；若检测到当前并非混合端，自动回退模拟控制台并给出提示；
     * - 其他值：模拟控制台。
     */
    private fun logCommandExecutor() {
        when (commandSenderType) {
            CommandSenderType.HYBRID -> log_info("已启用混合控制台命令执行器（检测到混合端服务端）")
            CommandSenderType.HYBRID_FALLBACK -> log_warning("配置指定了 Hybrid，但当前服务器不是混合端，已自动使用模拟控制台命令执行器")
            CommandSenderType.BUKKIT -> log_info("已启用模拟控制台命令执行器")
        }
    }

    /** 解析后的命令执行器类型。 */
    private val commandSenderType: CommandSenderType
        get() {
            val configured = configManager.commandSender()
            return when (configured.lowercase(Locale.ROOT)) {
                "auto" -> if (isHybridServer) CommandSenderType.HYBRID else CommandSenderType.BUKKIT
                "hybrid" -> if (isHybridServer) CommandSenderType.HYBRID else CommandSenderType.HYBRID_FALLBACK
                else -> CommandSenderType.BUKKIT
            }
        }

    /** 创建当前配置对应的命令执行器。 */
    override fun createCommandExecutor(): HExecution = when (commandSenderType) {
        CommandSenderType.HYBRID -> HybridCommandExecutor(this)
        CommandSenderType.HYBRID_FALLBACK, CommandSenderType.BUKKIT -> BukkitConsoleSender(this)
    }

    /** 命令执行器类型。 */
    private enum class CommandSenderType {
        HYBRID,
        HYBRID_FALLBACK,
        BUKKIT,
    }

    /**
     * 混合端服务端检测（Mohist / CatServer / Arclight / Uranium 等 Forge+Bukkit 服务端）。
     * 依次检查：服务端名称标识 → Forge/Fabric 内部类是否存在。
     */
    private val isHybridServer: Boolean by lazy {
        val server = Bukkit.getServer()
        val identity = (server.name + " " + server.version).lowercase(Locale.ROOT)
        val nameMarkers = listOf(
            "mohist", "catserver", "arclight", "uranium", "lumina",
            "banner", "hybrid", "crucible", "flexremap", "sponge",
        )
        if (nameMarkers.any { identity.contains(it) }) {
            true
        } else {
            // 混合端必然携带 Forge/Fabric 引导类
            val loaderClasses = listOf(
                "net.minecraftforge.fml.loading.FMLLoader",
                "net.minecraftforge.fml.common.Loader",
                "net.fabricmc.loader.impl.FabricLoaderImpl",
            )
            loaderClasses.any { className ->
                try {
                    Class.forName(className, false, javaClass.classLoader) != null
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }

    // ---------- 调度 ----------

    override fun broadcastMessage(msg: String) {
        server.scheduler.runTask(this, Runnable { Bukkit.broadcastMessage(msg) })
    }

    override fun submit(task: Runnable): Cancelable {
        val bukkitTask: BukkitTask = server.scheduler.runTask(this, task as Runnable)
        return HuHoBotTask(bukkitTask)
    }

    override fun submitAsync(task: Runnable): Cancelable {
        val bukkitTask: BukkitTask = server.scheduler.runTaskAsynchronously(this, task)
        return HuHoBotTask(bukkitTask)
    }

    override fun submitLater(delay: Long, task: Runnable): Cancelable {
        val bukkitTask: BukkitTask = server.scheduler.runTaskLater(this, task, delay)
        return HuHoBotTask(bukkitTask)
    }

    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable {
        val bukkitTask: BukkitTask = server.scheduler.runTaskTimer(this, task, delay, period)
        return HuHoBotTask(bukkitTask)
    }

    // ---------- 平台信息 ----------

    override val onlineList: List<String>
        get() = server.onlinePlayers.map { it.name }

    override val configFile: File
        get() = configManager.getConfigFile()

    override val botAppId: String
        get() = configManager.botAppId()

    override val botSecret: String
        get() = configManager.botSecret()

    override val chatFormat: ChatFormat
        get() = configManager.chatFormat()

    override fun playerEventFormat(): PlayerEventFormat = configManager.playerEventFormat()

    override fun markdownFiles(): Map<String, String> = configManager.markdownFiles()

    override val motd: Motd
        get() = configManager.motd()

    override fun whiteList(): WhiteList = configManager.whiteList()

    override fun filterRegexList(): List<String> = configManager.filterRegexList()

    override fun adminMode(): AdminMode = configManager.adminMode()

    override fun adminList(): List<String> = configManager.adminOpenIds()

    override fun groupOpenIdList(): List<String> = configManager.groupOpenIds()

    override fun shouldSuppressQqBotConsoleOutput(): Boolean = configManager.suppressQqBotConsoleOutput()

    override val fullAmount: Boolean
        get() = configManager.fullForwardingByDefault()

    override fun commandList(): Map<String, Boolean> = configManager.commandSwitches()

    override fun auditBaseUrl(): String? = configManager.auditBaseUrl()

    override fun auditApiKey(): String? = configManager.auditApiKey()

    override fun auditModel(): String = configManager.auditModel()

    override fun sensitiveWords(): List<String> = super.sensitiveWords()

    override fun customCommands(): List<CustomCommandDetail> = configManager.customCommands()

    override val agentEnabled: Boolean
        get() = configManager.agentEnabled()

    override fun agentBaseUrl(): String? = configManager.agentBaseUrl()

    override fun agentApiKey(): String? = configManager.agentApiKey()

    override fun agentModel(): String? = configManager.agentModel()

    override fun agentCommandMode() = configManager.agentCommandMode()

    override val agentConfig: AgentConfig?
        get() = super.agentConfig

    override val botName: String
        get() = configManager.botName()

    override val serverName: String
        get() = configManager.serverName()

    override val platform: String
        get() = "spigot"

    override val pluginVersion: String
        get() = description.version

    override val serverVersion: String
        get() = server.version

    // ---------- Web 面板配置 ----------

    override fun webUiConfigValues(): Map<String, Any> = config.getValues(true)

    override fun applyWebUiConfigChanges(changes: JSONObject): Boolean = try {
        for ((path, value) in changes) {
            config.set(path, convertJsonValue(value))
        }
        saveConfig()
        reloadPluginConfig()
        true
    } catch (error: Exception) {
        log_error("WebUI 保存配置失败: ${error.message}")
        false
    }

    /** fastjson 值转 Bukkit 配置值（递归转换 Map/List）。 */
    private fun convertJsonValue(value: Any?): Any? = when (value) {
        is JSONObject -> value.entries.associate { (k, v) -> k to convertJsonValue(v) }
        is JSONArray -> value.map { convertJsonValue(it) }
        else -> value
    }

    // ---------- Agent 工具数据 ----------

    override fun serverPluginList(): List<String> =
        server.pluginManager.plugins.map { it.name }.sorted()

    override fun serverCommandHelp(pluginName: String?, commandName: String?): String {
        return if (commandName != null) {
            formatSingleCommand(commandName)
        } else if (pluginName != null) {
            formatPluginCommands(pluginName)
        } else {
            formatAllCommands()
        }
    }

    /** 格式化单个命令的详情。 */
    private fun formatSingleCommand(commandName: String): String {
        val command = findCommand(commandName) ?: return "未找到命令: /$commandName"
        return formatCommandDetail(command)
    }

    /** 查找命令：插件命令 → CommandMap → knownCommands 反射。 */
    private fun findCommand(commandName: String): Command? {
        server.getPluginCommand(commandName)?.let { return it }
        val commandMap = resolveCommandMapObject() ?: return null
        try {
            if (commandMap is CommandMap) {
                commandMap.getCommand(commandName)?.let { return it }
            }
        } catch (_: Exception) {
        }
        val known = readKnownCommands(commandMap)
        if (known != null) {
            val exact = known[commandName.lowercase(Locale.ROOT)]
            if (exact != null) return exact
            return known.values.firstOrNull { it.name.equals(commandName, ignoreCase = true) }
        }
        return null
    }

    /** 命令详情文本。 */
    private fun formatCommandDetail(cmd: Command): String = buildString {
        append("命令：/${cmd.name}").append('\n')
        if (cmd.aliases.isNotEmpty()) {
            append("别名：${cmd.aliases.joinToString(", ")}").append('\n')
        }
        if (!cmd.description.isNullOrBlank()) {
            append("描述：${cmd.description}").append('\n')
        }
        val usage = cmd.usage?.trim()
        if (!usage.isNullOrBlank() && usage != "/<command>") {
            append("用法：$usage").append('\n')
        }
        if (!cmd.permission.isNullOrBlank()) {
            append("权限：${cmd.permission}").append('\n')
        }
        if (cmd is PluginCommand) {
            cmd.plugin?.let { append("所属插件：${it.name}").append('\n') }
        } else {
            append("类型：原版命令（服务器版本：${server.version}）").append('\n')
            append("提示：原版命令的具体参数与语法请按服务器版本使用，可在游戏内执行 /help <命令> 查看。").append('\n')
        }
    }.trimEnd()

    /** 格式化指定插件注册的命令。 */
    private fun formatPluginCommands(pluginName: String): String {
        val plugin = server.pluginManager.getPlugin(pluginName)
            ?: return "未找到插件：$pluginName（可用插件：${serverPluginList().joinToString(", ")}）"
        val commands = plugin.description.commands
        if (commands.isEmpty()) {
            return "插件 ${plugin.name} 没有注册命令。"
        }
        return buildString {
            append("插件 ${plugin.name} 注册的命令：").append('\n')
            for ((label, meta) in commands.toSortedMap()) {
                val desc = meta["description"]?.toString()?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""
                append("/$label$desc").append('\n')
            }
        }.trimEnd()
    }

    /** 格式化全部命令概览（按插件分组）。 */
    private fun formatAllCommands(): String {
        val commandMap = resolveCommandMapObject()
        val known = commandMap?.let { readKnownCommands(it) }
        val perPlugin = sortedMapOf<String, MutableList<String>>()
        if (known != null) {
            val seen = LinkedHashSet<String>()
            for (cmd in known.values) {
                val label = cmd.name ?: continue
                if (!seen.add(label)) continue
                val owner = (cmd as? PluginCommand)?.plugin?.name ?: "原版命令"
                perPlugin.getOrPut(owner) { ArrayList() }.add(label)
            }
        } else {
            for (plugin in server.pluginManager.plugins) {
                val labels = plugin.description.commands.keys
                if (labels.isEmpty()) continue
                perPlugin.getOrPut(plugin.name) { ArrayList() }.addAll(labels)
            }
        }
        if (perPlugin.isEmpty()) {
            return "服务器没有可查询的命令。"
        }
        return buildString {
            append("服务器命令概览：").append('\n')
            for ((owner, labels) in perPlugin) {
                append("- $owner：${labels.sorted().joinToString(", ")}").append('\n')
            }
            if (known == null) {
                append("（仅列出插件命令，原版命令表访问失败）").append('\n')
            }
        }.trimEnd()
    }

    /** 反射读取命令表（knownCommands / commandMap 字段或 getKnownCommands 方法）。 */
    @Suppress("UNCHECKED_CAST")
    private fun readKnownCommands(commandMap: Any): Map<String, Command>? {
        for (fieldName in listOf("knownCommands", "commandMap")) {
            try {
                val field: Field = commandMap.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                val value = field.get(commandMap)
                if (value is Map<*, *>) {
                    return value as Map<String, Command>
                }
            } catch (error: Exception) {
                log_debug("Agent 读取 $fieldName 字段失败: ${error.message}")
            }
        }
        try {
            val method: Method = commandMap.javaClass.getMethod("getKnownCommands")
            val value = method.invoke(commandMap)
            if (value is Map<*, *>) {
                return value as Map<String, Command>
            }
        } catch (error: Exception) {
            log_debug("Agent 调用 getKnownCommands 失败: ${error.message}")
        }
        return null
    }

    /** 反射获取服务器 CommandMap 对象。 */
    private fun resolveCommandMapObject(): Any? {
        try {
            val method = server.javaClass.getMethod("getCommandMap")
            val result = method.invoke(server)
            if (result == null) {
                log_debug("Agent getCommandMap() 返回 null")
            }
            return result
        } catch (error: Exception) {
            log_debug("Agent 反射 getCommandMap 失败: ${error.message}")
            try {
                for (method in server.javaClass.methods) {
                    if (method.parameterCount != 0) continue
                    if (!CommandMap::class.java.isAssignableFrom(method.returnType)) continue
                    val result = method.invoke(server) ?: continue
                    return result
                }
            } catch (error2: Exception) {
                log_debug("Agent 遍历命令表方法失败: ${error2.message}")
            }
            log_warning("Agent 无法访问服务器命令表，AI 命令帮助功能将不可用")
            return null
        }
    }

    // ---------- 服务端日志 ----------

    override fun serverLogs(lines: Int?, keyword: String?): String {
        val logFile = resolveLogFile() ?: return "无法定位服务端日志文件（logs/latest.log）"
        val allLines = try {
            logFile.readLines(Charsets.UTF_8)
        } catch (error: Throwable) {
            return "读取服务端日志失败：${error.message}"
        }
        val lineCount = (lines ?: 50).coerceIn(1, 500)
        val kw = keyword?.trim()?.takeIf { it.isNotEmpty() }
        val context = 5

        val selected: List<String>
        val tip: String
        if (kw != null) {
            val indices = allLines.indices.filter { allLines[it].contains(kw, ignoreCase = true) }
            if (indices.isEmpty()) {
                return "未在服务端日志中找到包含「$kw」的内容。"
            }
            val from = (indices.first() - context).coerceAtLeast(0)
            val to = (indices.last() + context).coerceAtMost(allLines.size - 1)
            selected = allLines.subList(from, to + 1)
            tip = "\n\n(按关键词「$kw」过滤，共匹配 ${indices.size} 处)"
        } else {
            selected = allLines.takeLast(lineCount)
            tip = ""
        }
        return selected.joinToString("\n").trimEnd() + tip
    }

    /** 定位服务端日志文件。 */
    private fun resolveLogFile(): File? {
        val candidates = listOfNotNull(
            server.worldContainer.resolve("logs").resolve("latest.log"),
            server.worldContainer.parentFile?.resolve("logs")?.resolve("latest.log"),
            File("logs/latest.log"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    // ---------- 日志 ----------

    override fun log_info(msg: String) {
        logger.info(msg)
    }

    override fun log_warning(msg: String) {
        logger.warning(msg)
    }

    override fun log_error(msg: String) {
        logger.severe(msg)
    }
}
