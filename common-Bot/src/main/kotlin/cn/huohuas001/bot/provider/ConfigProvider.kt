package cn.huohuas001.bot.provider

import java.io.File

/**
 * 配置数据模型集合：AdminMode / ChatFormat / Motd / PlayerEventFormat / WhiteList / CustomCommandDetail。
 * 由 [ConfigProvider] 的各平台实现负责从真实配置文件中构造。
 */

/** 管理员判定方式。 */
enum class AdminMode(val value: String) {
    /** 仅 QQ 群主 / 群管理员。 */
    QQ("qq"),

    /** 仅手动配置名单。 */
    CONFIG("config"),

    /** 任一满足即可。 */
    BOTH("both");

    companion object {
        fun from(value: String?): AdminMode? =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
    }
}

/** 游戏内 / QQ 群双向聊天格式。 */
data class ChatFormat(
    val fromGame: String,
    val fromGroup: String,
    val postChat: Boolean,
    val startWith: String,
)

/** 服务器 MOTD 展示配置。 */
data class Motd(
    val serverIP: String,
    val serverPort: Int,
    val api: String,
    val postImg: Boolean,
    /** Markdown 开关：false 时 /查在线 自动改用渲染图片输出（1.5.1）。 */
    val useMarkdown: Boolean,
)

/** 玩家进出服播报格式。 */
data class PlayerEventFormat(
    val joinEnabled: Boolean,
    val joinFormat: String,
    val quitEnabled: Boolean,
    val quitFormat: String,
)

/** 白名单增删命令模板（{name} 占位）。 */
data class WhiteList(
    val addCommand: String,
    val delCommand: String,
)

/** 自定义命令详情（QQ 群触发 → 服务器执行）。 */
data class CustomCommandDetail(
    val key: String,
    val command: String,
    val permission: Int,
)

/** 平台级配置提供者接口。 */
interface ConfigProvider {
    /** 聊天格式。 */
    val chatFormat: ChatFormat

    /** MOTD 配置。 */
    val motd: Motd

    /** 机器人名称。 */
    val botName: String

    /** 运行平台标识（spigot / allay / ...）。 */
    val platform: String

    /** 插件版本。 */
    val pluginVersion: String

    /** 敏感词审核接口地址（环境变量 HUHOBOT_AUDIT_BASE_URL）。 */
    fun auditBaseUrl(): String? = System.getenv("HUHOBOT_AUDIT_BASE_URL")

    /** 敏感词审核 API Key（环境变量 HUHOBOT_AUDIT_API_KEY）。 */
    fun auditApiKey(): String? = System.getenv("HUHOBOT_AUDIT_API_KEY")

    /** 敏感词审核模型名（环境变量 HUHOBOT_AUDIT_MODEL）。 */
    fun auditModel(): String? = System.getenv("HUHOBOT_AUDIT_MODEL")

    /** 敏感词列表：读取插件目录与工作目录下 sensitive-words 目录内的 txt 文件。 */
    fun sensitiveWords(): List<String> {
        val configDir = configFile?.parentFile
        val directories = listOfNotNull(configDir?.resolve("sensitive-words"), File("sensitive-words"))
        return directories.asSequence()
            .filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles { f -> f.isFile && f.extension.equals("txt", true) }.orEmpty().asSequence() }
            .flatMap { it.readLines(Charsets.UTF_8).asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    /** 玩家进出服格式。 */
    fun playerEventFormat(): PlayerEventFormat = PlayerEventFormat(
        joinEnabled = true,
        joinFormat = "[游戏] {name} 加入了服务器",
        quitEnabled = true,
        quitFormat = "[游戏] {name} 离开了服务器",
    )

    /** 白名单命令模板。 */
    fun whiteList(): WhiteList = WhiteList("whitelist add {name}", "whitelist remove {name}")

    /** 配置文件路径（用于定位 Markdown / 敏感词目录），无配置文件的平台返回 null。 */
    val configFile: File?
        get() = null

    /** 文本过滤正则列表。 */
    fun filterRegexList(): List<String> = emptyList()

    /** 按正则过滤文本。 */
    fun filterText(text: String): String =
        cn.huohuas001.bot.tools.filterTextByRegex(text, filterRegexList())

    /** 格式化 QQ 群消息转发到游戏的文本。 */
    fun formatGroupMessage(name: String, message: String): String {
        val filtered = filterText(message)
        return chatFormat.fromGroup
            .replace("{name}", name)
            .replace("{nick}", name)
            .replace("{message}", filtered)
            .replace("{msg}", filtered)
    }

    /** 格式化游戏消息转发到 QQ 群的文本。 */
    fun formatGameMessage(name: String, message: String): String {
        val filtered = filterText(message)
        return chatFormat.fromGame
            .replace("{name}", name)
            .replace("{message}", filtered)
            .replace("{msg}", filtered)
    }

    /** 玩家加入播报。 */
    fun formatPlayerJoinMessage(name: String): String =
        formatPlayerEventMessage(playerEventFormat().joinFormat, name)

    /** 玩家退出播报。 */
    fun formatPlayerQuitMessage(name: String): String =
        formatPlayerEventMessage(playerEventFormat().quitFormat, name)

    /** 应用占位符后的事件播报文本。 */
    fun formatPlayerEventMessage(format: String, name: String): String = format
        .replace("{name}", name)
        .replace("{player}", name)
        .replace("{server}", serverName)
        .replace("{platform}", platform)

    /** 服务器名称。 */
    val serverName: String
        get() = botName

    /** 服务器版本。 */
    val serverVersion: String
        get() = "未知"

    /** Markdown 模板文件映射（key → 文件名）。 */
    fun markdownFiles(): Map<String, String> = mapOf("queryOnline" to "online.md")

    /** 读取 Markdown 模板内容（限制在 Markdown 目录内，防止路径穿越）。 */
    fun markdown(key: String): String? {
        val fileName = markdownFiles()[key]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val configDir = configFile?.absoluteFile?.parentFile ?: return null
        return try {
            val markdownDirectory = configDir.resolve("Markdown").canonicalFile
            val markdownFile = markdownDirectory.resolve(fileName).canonicalFile
            if (markdownFile.toPath().startsWith(markdownDirectory.toPath()) && markdownFile.isFile) {
                markdownFile.readText(Charsets.UTF_8)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /** 管理员判定方式。 */
    fun adminMode(): AdminMode = AdminMode.BOTH

    /** 手动配置的管理员名单。 */
    fun adminList(): List<String> = emptyList()

    /** 允许交互的 QQ 群 OpenId 列表（空表示不限制）。 */
    fun groupOpenIdList(): List<String> = emptyList()

    /** 是否开启全量消息转发。 */
    val fullAmount: Boolean
        get() = false

    /** QQ 群命令开关表。 */
    fun commandList(): Map<String, Boolean> = emptyMap()

    /** 自定义命令列表。 */
    fun customCommands(): List<CustomCommandDetail> = emptyList()
}
