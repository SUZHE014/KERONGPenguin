package cn.kerong.bot.provider

import java.io.File

/**
 * 配置数据模型集合：AdminMode / ChatFormat / Motd / PlayerEventFormat / CustomCommandDetail。
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
    /**
     * 群消息转发到游戏时显示的发送者名称来源（chat-format.group-sender-name，1.7.5.8）：
     * - "group"（默认）：群昵称——消息事件返回的成员昵称（该成员在群内显示的名字；
     *   QQ 官方机器人接口在群消息里只提供这一个昵称，群名片优先）；
     * - "player"：绑定玩家名——该成员绑定的游戏玩家名，未绑定时回退群昵称。
     * 其他取值一律按 group 处理（未知值不阻断转发）。
     */
    val groupSenderName: String = "group",
)

/** 服务器 MOTD 展示配置。 */
data class Motd(
    val serverIP: String,
    val serverPort: Int,
    val api: String,
    val postImg: Boolean,
 /** Markdown 开关：false 时 /查在线 自动改用渲染图片输出。 */
    val useMarkdown: Boolean,
)

/** 玩家进出服播报格式。 */
data class PlayerEventFormat(
    val joinEnabled: Boolean,
    val joinFormat: String,
    val quitEnabled: Boolean,
    val quitFormat: String,
 /** 死亡播报开关（参考 HuHoBot；默认关闭）。 */
    val deathEnabled: Boolean = false,
    /** 死亡播报格式（占位符与 HuHoBot 一致：{name}/{player}/{server}/{platform}/{message}/{killer}）。 */
    val deathFormat: String = "[游戏] {message}",
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

    /** 敏感词审核接口地址（环境变量 KERONG_AUDIT_BASE_URL）。 */
    fun auditBaseUrl(): String? = System.getenv("KERONG_AUDIT_BASE_URL")

    /** 敏感词审核 API Key（环境变量 KERONG_AUDIT_API_KEY）。 */
    fun auditApiKey(): String? = System.getenv("KERONG_AUDIT_API_KEY")

    /** 敏感词审核模型名（环境变量 KERONG_AUDIT_MODEL）。 */
    fun auditModel(): String? = System.getenv("KERONG_AUDIT_MODEL")

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
        deathEnabled = false,
        deathFormat = "[游戏] {message}",
    )

    /** 配置文件路径（用于定位 Markdown / 敏感词目录），无配置文件的平台返回 null。 */
    val configFile: File?
        get() = null

    /** 文本过滤正则列表。 */
    fun filterRegexList(): List<String> = emptyList()

    /** 按正则过滤文本。 */
    fun filterText(text: String): String =
        cn.kerong.bot.tools.filterTextByRegex(text, filterRegexList())

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

    /**
 * 玩家死亡播报（占位符与 HuHoBot 对齐）。
     *
     * @param name 死者玩家名
     * @param deathMessage 自动生成的中文死亡描述（如「Steve 被苦力怕杀死了」，不含死者名）
     * @param killerName 击杀者名（环境死亡时为 null，输出为「未知」）
     */
    fun formatPlayerDeathMessage(name: String, deathMessage: String?, killerName: String?): String =
        formatPlayerEventMessage(playerEventFormat().deathFormat, name)
            .replace("{message}", deathMessage ?: "死了")
            .replace("{killer}", killerName ?: "未知")

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

    /**
     * QQ 快捷指令面板管理员联动（1.7.5.3，默认开）：
     * admin.mode=qq/both 时，自动把快捷面板里管理类指令条目
     * （执行命令 / 管理员执行 / 全量 / AI管理 / 黑名单 / 解除黑名单）
     * 设为「仅管理员可见可点」（面板 only_admin，QQ 端按当前群身份实时
     * 渲染，与菜单按钮平台门禁同一口径）；admin.mode=config 或联动关闭
     * 时不设——面板全员可见，由命令链路按名单实时判定。
     *
     * 配置键：admin.panel-admin-linkage（/hbkrg reload 后随面板重同步生效）。
     */
    fun panelAdminLinkageEnabled(): Boolean = true

    /** 允许交互的 QQ 群 OpenId 列表（空表示不限制）。 */
    fun groupOpenIdList(): List<String> = emptyList()

    /**
 * 多服务器集群：本服显示名。
     *
     * 多台服务器共用同一 QQ 机器人凭据时，在每台服的 bot.cluster.name
     * 填写本服在「选择服务器」按钮上显示的名称；未配置（空）时集群
     * 用 bot.name 充当显示名。详见 [cn.kerong.bot.ClusterState]。
     */
    fun clusterName(): String = ""

    /**
 * 多服务器集群：全部成员服务器名列表。
     *
     * 各服务器必须配置完全一致的列表（含自己）；少于 2 个（或不含
     * 本服）时集群逻辑全部旁路，行为与单服版本完全一致。
     */
    fun clusterMembers(): List<String> = emptyList()

 // ---------- 服务器开关消息（引入；去表情 ----------
    // ---------- 文案 + 180 秒重启判定窗口，重启 / 普通开机两类文案分开） ----------

    /** 服务器状态通知总开关（关闭后关机 / 重启 / 开机 / 意外关闭都不发送）。 */
    fun isServerNotifyEnabled(): Boolean = true

    /**
 * 重启判定窗口（秒，恢复该键，默认 180）：
     * 上次正常关闭后在这个秒数内再次开机判定为「重启」（发 restart 重启文案），
     * 超过这个时长再开机按普通开机处理（发 start 开机文案）；
     * 配 0 = 不判定重启，一律按普通开机播报。
     */
    fun serverNotifyRestartWindow(): Int = 180

    /** 正常关闭通知开关。 */
    fun isServerNotifyShutdownEnabled(): Boolean = true

    /** 正常关闭通知文案（占位符 {server} / {platform}）。 */
    fun serverNotifyShutdownMessage(): String = "[状态]\n服务器已正常关闭"

    /** 重启通知开关（上次正常关闭后在 [serverNotifyRestartWindow] 秒内再次开机时发送）。 */
    fun isServerNotifyRestartEnabled(): Boolean = true

    /** 重启通知文案（占位符 {server} / {platform}）。 */
    fun serverNotifyRestartMessage(): String = "[状态]\n服务器重启完毕，已正常运行(o‿∩)"

    /** 普通开机通知开关（上次正常关闭后超过 [serverNotifyRestartWindow] 秒再开机时发送）。 */
    fun isServerNotifyStartEnabled(): Boolean = true

    /** 普通开机通知文案（占位符 {server} / {platform}；首次安装不发送）。 */
    fun serverNotifyStartMessage(): String = "[状态]\n服务器已开启！！(≧∇≦)/"

    /** 意外关闭（崩溃 / 强制结束）通知开关。 */
    fun isServerNotifyCrashEnabled(): Boolean = true

    /** 意外关闭通知文案（占位符 {server} / {platform}）。 */
    fun serverNotifyCrashMessage(): String = "[状态]\n服务器...."

    /** 是否开启全量消息转发。 */
    val fullAmount: Boolean
        get() = false

    /**
     * 设置全量转发总开关（features.full-amount）并写回配置文件。
     *
     * 由 /全量 命令调用（1.7.5 覆盖第三轮：群级独立开关已删除，命令直接
     * 切换总配置，对全部配置群统一生效）。写回走注释保留保存链路
     * （Spigot 平台自带 / NeoForge 兼容层差分合并），改完即时生效。
     *
     * @return 是否写入成功（失败时值已生效但重启可能还原，调用方如实提示）
     */
    fun setFullAmountEnabled(enabled: Boolean): Boolean = false

    /** QQ 群命令开关表。 */
    fun commandList(): Map<String, Boolean> = emptyMap()

    /** 自定义命令列表。 */
    fun customCommands(): List<CustomCommandDetail> = emptyList()
}
