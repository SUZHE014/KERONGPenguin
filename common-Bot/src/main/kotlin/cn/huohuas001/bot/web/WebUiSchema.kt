package cn.huohuas001.bot.web

import com.alibaba.fastjson.JSON

/**
 * Web 面板配置字段定义。
 *
 * @param path        配置键路径（如 bot.app-id）
 * @param label       展示名称
 * @param type        控件类型（text / password / boolean / number / list / textarea / select / boolean-map / object-list）
 * @param description 字段说明
 * @param options     select 类型的可选项
 * @param placeholder 输入提示
 * @param fields      object-list 类型的子字段定义
 */
data class FieldSpec(
    val path: String,
    val label: String,
    val type: String,
    val description: String? = null,
    val options: List<String>? = null,
    val placeholder: String? = null,
    val fields: List<FieldSpec>? = null,
)

/**
 * Web 面板配置分区定义。
 */
data class SectionSpec(
    val key: String,
    val title: String,
    val fields: List<FieldSpec>,
)

/**
 * Web 面板配置结构（全部配置分区的静态描述）。
 */
object WebUiSchema {

    private val BOT_SECTION = SectionSpec("bot", "QQ 机器人", listOf(
        FieldSpec("bot.app-id", "AppID", "text", "QQ 开放平台机器人 AppID", placeholder = "102123456"),
        FieldSpec("bot.secret", "Secret", "password", "QQ 开放平台机器人密钥"),
        FieldSpec("bot.name", "机器人名称", "text", "机器人显示的昵称（serverName 留空时作为后备显示名）"),
        FieldSpec("bot.groups", "群列表", "list", "允许机器人工作的群 OpenID（每行一个，需双引号包裹；留空则命令不限群，但转发/播报无目标群不发）"),
        FieldSpec("bot.reconnect-notify", "重连过程提示", "boolean", "断线重连时输出「第 N 次（1/5）重试连接…」（关闭防刷屏；「重连超时 + /hb chs 恢复指引」的失败提示不受此开关影响；1.6.2 新增）"),
    ))

    private val SERVER_SECTION = SectionSpec("server", "服务器", listOf(
        FieldSpec("serverName", "服务器名称", "text", "用于玩家事件消息中的 {server} 占位符"),
        FieldSpec("command-sender", "命令执行器", "select", "Hybrid=混合控制台；其他=模拟控制台", options = listOf("Hybrid", "Console")),
    ))

    private val CHAT_FORMAT_SECTION = SectionSpec("chat-format", "聊天格式", listOf(
        FieldSpec("chat-format.from-game", "游戏→群 格式", "text", "占位符：{name} 玩家名，{message}/{msg} 消息", placeholder = "[游戏] {message}"),
        FieldSpec("chat-format.from-group", "群→游戏 格式", "text", "占位符：{name}/{nick} 昵称，{message}/{msg} 消息", placeholder = "[QQ] {name}: {message}"),
        FieldSpec("chat-format.post-chat", "转发游戏聊天", "boolean", "是否开启游戏↔群双向聊天转发"),
        FieldSpec("chat-format.start-with", "触发前缀", "text", "游戏消息必须以此前缀开头才转发（转发时去掉前缀）；留空转发全部", placeholder = "#"),
    ))

    private val PLAYER_EVENTS_SECTION = SectionSpec("player-events", "玩家事件", listOf(
        FieldSpec("player-events.join.enabled", "进服通知", "boolean", "玩家进服时在群内发送通知（仅发送给 groups 列表内的群）"),
        FieldSpec("player-events.join.format", "进服格式", "text", "占位符：{name}/{player} 玩家名、{server} 服务器名、{platform} 平台", placeholder = "[游戏] {name} 加入了服务器"),
        FieldSpec("player-events.quit.enabled", "退服通知", "boolean", "玩家退服时在群内发送通知"),
        FieldSpec("player-events.quit.format", "退服格式", "text", "占位符同进服格式", placeholder = "[游戏] {name} 离开了服务器"),
    ))

    private val MARKDOWN_SECTION = SectionSpec("markdown", "Markdown", listOf(
        FieldSpec("markdown.queryOnline", "在线查询模板", "text", "Markdown 目录下的模板文件名（占位符 {online}/{players}/{server}/{img_url}）", placeholder = "online.md"),
    ))

    private val MOTD_SECTION = SectionSpec("motd", "MOTD", listOf(
        FieldSpec("motd.server-ip", "服务器 IP", "text", "仅用于 api 地址里的 {ip} 变量替换", placeholder = "127.0.0.1"),
        FieldSpec("motd.server-port", "服务器端口", "number", "仅用于 api 地址里的 {port} 变量替换"),
        FieldSpec("motd.api", "MOTD API 地址", "text", "服务器状态图接口，{ip}/{port} 自动替换；不需要可留空"),
        FieldSpec("motd.post-img", "发送图片", "boolean", "是否随消息发送 api 生成的状态图片"),
        FieldSpec("motd.use-markdown", "使用 Markdown", "boolean", "true=/查在线走模板；false=自动渲染图片（模板缺失时也自动改图片）"),
    ))

    private val FILTER_SECTION = SectionSpec("filter-regex", "正则过滤", listOf(
        FieldSpec("filter-regex", "过滤正则列表", "list", "命中这些正则的消息片段会被直接删除，剩余内容照常转发（双向生效；每行一条，需双引号包裹）"),
    ))

    private val ADMIN_SECTION = SectionSpec("admin", "管理员", listOf(
        FieldSpec("admin.mode", "管理员模式", "select", "qq=仅群主/群管理；config=仅配置文件指定；both=两者皆可", options = listOf("both", "qq", "config")),
        FieldSpec("admin.openids", "管理员 OpenID", "list", "配置文件指定的管理员 OpenID（每行一个）"),
    ))

    private val FEATURES_SECTION = SectionSpec("features", "功能", listOf(
        FieldSpec("features.full-amount", "全量转发", "boolean", "开启后群内所有消息（不止命令）都转发进游戏；可由管理员 /全量 按群切换"),
    ))

    private val QQ_BIND_SECTION = SectionSpec("qq-bind", "QQ 绑定", listOf(
        FieldSpec("qq-bind.enabled", "启用 QQ 绑定", "boolean", "开启后未绑定 QQ 的玩家无法进入服务器"),
        FieldSpec("qq-bind.personal-info", "查信息命令", "boolean", "/查信息 统计卡片命令（1.5.2 前为 /个人信息；默认开启，需 QQ 绑定开启）"),
        FieldSpec("qq-bind.code-expire-minutes", "绑定码有效期（分钟）", "number"),
        FieldSpec("qq-bind.code-length", "绑定码长度", "number", "4-8 位，超出自动按边界处理"),
        FieldSpec("qq-bind.checkin.enabled", "启用签到", "boolean", "群内每日签到领金币（需 Vault；未装时照常签到不发金币）"),
        FieldSpec("qq-bind.checkin.prefix", "签到回复前缀", "text"),
        FieldSpec("qq-bind.checkin.reward", "签到奖励金币", "number"),
    ))

    private val RENDER_SECTION = SectionSpec("render", "图片渲染", listOf(
        FieldSpec("render.gc-after-render", "渲染后 GC", "boolean", "渲染后 GC 提示（已节流 60 秒一次）"),
        FieldSpec("render.result-cache-seconds", "结果复用秒数", "number", "同一玩家查询结果复用秒数（0=每次重渲染，上限 600）"),
        FieldSpec("render.command-cooldown-seconds", "命令冷却秒数", "number", "同一用户两次触发图片命令（查信息/查在线/排行榜/财富榜）的最小间隔秒数（0=关闭，默认 5）"),
        FieldSpec("render.info-card-builtin-background", "查信息内置背景", "boolean", "查信息卡片是否使用内置深灰底图（默认开启；关闭后随机用 img/ 图片，img 无图自动回退内置图；1.5.7 新增，1.6.0 迁至顶层 render 段）"),
        FieldSpec("render.online-list-builtin-background", "查在线内置背景", "boolean", "查在线图片是否使用内置深灰底图（默认开启；语义同查信息；1.6.0 迁至顶层 render 段）"),
    ))

    private val LEADERBOARD_SECTION = SectionSpec("leaderboard", "在线排行榜", listOf(
        FieldSpec("leaderboard.top", "显示人数", "number", "在线排行榜显示前 N 名（1-100，默认 15；单张图片最多 15 行，超出自动分段发送；1.6.0 迁至顶层）"),
        FieldSpec("leaderboard.builtin-background", "内置背景", "boolean", "在线排行榜是否使用内置深灰底图（默认开启；关闭后随机用 img/ 图片，img 无图自动回退内置图；1.6.0 迁至顶层）"),
    ))

    private val COINS_LEADERBOARD_SECTION = SectionSpec("coins-leaderboard", "金币排行榜", listOf(
        FieldSpec("coins-leaderboard.top", "显示人数", "number", "/财富榜 显示前 N 名（1-100，默认 15；1.6.0 新增为 /金币榜，1.6.2 更名，旧命令仍可用）"),
        FieldSpec("coins-leaderboard.builtin-background", "内置背景", "boolean", "财富榜是否使用内置底图（默认开启，与在线排行榜共用；1.6.0 新增）"),
    ))

    private val ECONOMY_SECTION = SectionSpec("economy-alias", "经济名称", listOf(
        FieldSpec("economy-alias", "金币别名", "text", "留空默认「金币」；填写后机器人所有输出中的「金币」二字替换为该名称（财富榜图片、查信息卡片、签到回复、消息模板等；1.6.0 新增）", placeholder = "金币"),
    ))

    private val UPDATE_CHECK_SECTION = SectionSpec("update-check", "更新检查", listOf(
        FieldSpec("update-check.enabled", "启用更新检查", "boolean", "启动后异步访问官网版本文件：有新版本时控制台输出版本号/下载页/简要日志；检测失败仅一句话提示（细节进日志）；1.6.2 新增"),
    ))

    private val AI_SECTION = SectionSpec("ai", "AI 对话", listOf(
        FieldSpec("ai.enabled", "AI 总开关", "boolean", "关闭后群内（@机器人）和游戏内 AI 都不可用"),
        FieldSpec("ai.server-enabled", "游戏内 AI", "boolean", "玩家输入前缀触发（需 ai.enabled 同时开启）"),
        FieldSpec("ai.server-prefix", "游戏内前缀", "text", "玩家输入“前缀 你好”即触发"),
        FieldSpec("ai.server-output-prefix", "游戏内回复前缀", "text"),
        FieldSpec("ai.qq-output-prefix", "群内回复前缀", "text"),
        FieldSpec("ai.base-url", "接口地址", "text", "OpenAI 兼容接口（DeepSeek 不加 /v1）"),
        FieldSpec("ai.api-key", "API Key", "password"),
        FieldSpec("ai.model", "模型名", "text", "如 deepseek-chat / gpt-4o-mini"),
        FieldSpec("ai.system-prompt", "角色设定", "textarea"),
        FieldSpec("ai.url-auto-append", "自动补后缀", "boolean"),
        FieldSpec("ai.context-limit", "上下文轮数", "number", "全局一份记忆（0 = 不保留）"),
    ))

    private val COMMANDS_SECTION = SectionSpec("commands", "命令开关", listOf(
        FieldSpec("commands", "命令开关", "boolean-map", "逐个开关机器人在群内的可用命令"),
    ))

    private val CUSTOM_COMMANDS_SECTION = SectionSpec("custom-commands", "自定义命令", listOf(
        FieldSpec(
            "custom-commands", "自定义命令列表", "object-list",
            "配置机器人自定义的命令（key=触发词，command=执行的服务器命令，permission：0/不填=所有人可用，其他值=仅管理员可用）",
            fields = listOf(
                FieldSpec("key", "触发词", "text"),
                FieldSpec("command", "执行命令", "text"),
                FieldSpec("permission", "权限", "number"),
            ),
        ),
    ))

    /** 全部配置分区（与 config.yml 保持同步；1.6.0：渲染/排行榜/金币榜/经济别名独立分区；1.6.2：新增更新检查分区，消息模板 messages 段需改配置文件调整）。 */
    val SECTIONS: List<SectionSpec> = listOf(
        BOT_SECTION,
        SERVER_SECTION,
        CHAT_FORMAT_SECTION,
        PLAYER_EVENTS_SECTION,
        MARKDOWN_SECTION,
        MOTD_SECTION,
        RENDER_SECTION,
        LEADERBOARD_SECTION,
        COINS_LEADERBOARD_SECTION,
        ECONOMY_SECTION,
        UPDATE_CHECK_SECTION,
        FILTER_SECTION,
        ADMIN_SECTION,
        FEATURES_SECTION,
        QQ_BIND_SECTION,
        AI_SECTION,
        COMMANDS_SECTION,
        CUSTOM_COMMANDS_SECTION,
    )

    /** 序列化为 JSON。 */
    fun toJson(): String = JSON.toJSONString(SECTIONS)
}
