package cn.kerong.penguin.spigot.qqbind

import cn.kerong.bot.QClient
import cn.kerong.bot.provider.BotShared
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * 消息模板系统（1.6.0 新增）：机器人在 QQ 发出的每一条文本回复都对应一个
 * Markdown 模板文件，可在配置文件 messages 段改写「这条回复用哪个 .md 文件」。
 *
 * - 模板文件位于数据目录 Markdown/ 下（插件版 plugins/KERONGPenguin/Markdown/，
 *   模组版 kerongpenguin/Markdown/），首次启动自动释放默认模板（不覆盖已有文件）；
 * - 模板为 .md（Markdown）格式，机器人经 QQ Markdown 渲染发送，失败回退纯文本；
 * - 占位符形如 {player} / {error}（各模板可用变量见配置文件 messages 段注释），
 *   未提供的占位符原样保留；
 * - 模板渲染出口统一应用经济别名（economy-alias，见 [EconomyName]）；
 * - 默认模板内容与 1.5.7.3 及之前版本硬编码的回复一字不差（升级零变化）。
 *
 * /查在线 的 Markdown 模板沿用 1.5.1 的 markdown.queryOnline（online.md），
 * 不在本系统内（该模板带独立占位符体系，见 motd 段说明）。
 */
object MessageTemplates {

    /** 模板目录名（位于插件数据目录下）。 */
    private const val DIRECTORY = "Markdown"

    /**
     * 全部模板 ID（同时是默认文件名：<ID>.md）。
     * 新增回复模板时同步：①本列表 ②资源文件 Markdown/<ID>.md
     * ③ConfigManager 的 messages 默认值 ④config.yml 注释 ⑤官网教程。
     */
    val TEMPLATE_IDS = listOf(
        // —— 通用（多条命令共用） ——
        "command-disabled",              // 命令被管理员关闭（commands 段）
        "no-admin-permission",           // 无管理员权限
        "query-too-frequent",            // 查询太频繁（图片命令冷却内）
        "server-busy",                   // 渲染队列繁忙兜底
        "image-send-failed",             // 图片发送失败（查信息 / 查在线 / 排行榜）
        "manager-not-ready",             // 管理器未就绪（简短版）
        "manager-not-ready-bind",        // 绑定管理器未就绪（简短版）
        "manager-not-ready-retry",       // 管理器未就绪，请稍后再试
        "manager-not-ready-bind-retry",  // 绑定管理器未就绪，请稍后再试
        "qq-unknown",                    // 无法识别 QQ 账号
        "server-status",                 // 服务器 IP 与端口（/在线服务器、/motd）
        // —— QQ 绑定 ——
        "bind-usage",                    // /绑定 无参数用法
        "bind-usage-fallback",           // /绑定 用法（管理器异常时的简短回退）
        "bind-disabled",                 // QQ 绑定功能未开启
        "bind-error",                    // 绑定处理异常
        "bind-success",                  // 绑定成功（/绑定 与纯数字自动绑定共用）
        "bind-invalid",                  // 绑定码无效或已过期
        // —— 签到 ——
        "checkin-disabled",              // 签到功能未开启
        "checkin-not-bound",             // QQ 未绑定玩家
        "checkin-data-error",            // 数据异常
        "checkin-time-error",            // 获取北京时间失败
        "checkin-already",               // 今日已签到
        "checkin-success-no-economy",    // 签到成功（无经济插件，不发金币）
        "checkin-reward-invalid",        // 奖励金币数量配置错误
        "checkin-success",               // 签到成功（获得金币）
        "checkin-pay-error",             // 金币系统未就绪
        // —— 查信息 ——
        "info-bind-disabled",            // 查信息未开启（QQ 绑定未启用）
        "info-closed",                   // 查信息被管理员关闭
        "info-not-bound",                // 自己未绑定
        "info-target-not-bound",         // 被查询成员未绑定
        "info-render-failed",            // 信息卡片生成失败
        // —— 查在线 ——
        "online-data-unavailable",       // 服务器数据暂不可用
        "online-render-failed",          // 在线列表图片生成失败
        "performance-render-failed",     // 性能卡片图片生成失败（1.6.7 /查性能）
        // —— 在线排行榜 ——
        "leaderboard-no-data",           // 暂无在线时长数据
        "leaderboard-render-failed",     // 排行榜图片生成失败
        // —— 金币排行榜（1.6.0） ——
        "coins-leaderboard-no-economy",  // 未安装 Vault 经济插件
        "coins-leaderboard-no-data",     // 暂无金币数据
        "coins-leaderboard-render-failed", // 金币排行图片生成失败
        // —— 查询 OpenID ——
        "openid-self",                   // 自己的 OpenId 与群 OpenId
        "openid-lookup",                 // OpenId 反查结果
        // —— 发信息 ——
        "forward-disabled",              // 群聊转发功能已关闭
        // —— AI 对话 ——
        "ai-context-usage",              // /AI对话上下文 用法与当前状态
        "ai-context-on",                 // 上下文已开启
        "ai-context-off",                // 上下文已关闭
        "ai-context-cleared",            // 上下文已清除
        "ai-chat-empty",                 // @机器人 但没输入内容
        "ai-chat-error",                 // AI 对话失败
        // —— 黑名单 ——
        "blacklist-empty",               // 黑名单为空（/黑名单）
        "blacklist-list",                // 黑名单列表（/黑名单）
        "blacklist-added-kicked",        // 加入黑名单且踢出了玩家
        "blacklist-added",               // 加入黑名单（未绑定玩家）
        "blacklist-removed",             // 移出黑名单
        "unblacklist-empty",             // 黑名单为空（/解除黑名单）
        "unblacklist-list",              // 黑名单列表（/解除黑名单）
        // —— 重新绑定 ——
        "rebind-success",                // 解绑成功并踢出
        "rebind-not-bound",              // QQ 未绑定任何账号
        // —— 帮助 ——
        "help",                          // /帮助 全量命令手册
        // —— 命令执行 ——
        "invalid-params",                // 参数不正确
        "bridge-error",                  // 游戏桥接未配置或执行失败
        "sent-request",                  // 已发送执行请求
        "execute-failed",                // 执行失败（异常）
        "full-amount-toggled",           // 全量转发切换结果
        // —— MOTD 查询 ——
        "motd-usage",                    // /motd 用法
        "motd-query-failed",             // 查询失败
        "motd-offline",                  // 目标服务器离线
        "motd-error",                    // 查询异常
        "motd-status",                   // 在线服务器的状态报告
    )

    /** 模板内容缓存：文件路径 →（修改时间戳，内容）。 */
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * 默认模板内容升级表（1.6.5 新增）：旧默认内容 → 新默认内容。
     *
     * 模板文件首次释放后不会覆盖用户修改，但默认内容本身偶尔需要演进
     * （如 1.6.5 OpenId 反查的 OpenId 行改为管理员可见）。仅当磁盘上的
     * 文件仍是**旧默认内容**（未被用户改过）时才替换，自定义内容一律不动。
     */
    private val DEFAULT_CONTENT_UPGRADES: Map<String, Pair<String, String>> = mapOf(
        // 1.6.5：OpenId 行改为 {openidPart}（管理员查询才显示）
        "openid-lookup" to (
            "【OpenID 反查】\nOpenId: {openid}\n群昵称: {nickname}{lastSeenPart}\n绑定玩家: {player}" to
                "【OpenID 反查】\n{openidPart}群昵称: {nickname}{lastSeenPart}\n绑定玩家: {player}"
            ),
    )

    /** 平台插件实例（Spigot / NeoForge 适配器均为 JavaPlugin 子类）。 */
    private fun plugin(): JavaPlugin? = try {
        BotShared.instance as? JavaPlugin
    } catch (_: Throwable) {
        null
    }

    /** 模板目录（数据目录下 Markdown/）。 */
    private fun directory(): File? = plugin()?.dataFolder?.resolve(DIRECTORY)

    /**
     * 从 messages 配置段读取模板 ID 对应的文件名（缺省 = <ID>.md）。
     * 用户把 messages.<ID> 改成自定义文件名即可换用自己写的模板。
     */
    fun fileNameOf(id: String): String {
        val configured = try {
            plugin()?.config?.getConfigurationSection("messages")?.getString(id)?.trim()
        } catch (_: Throwable) {
            null
        }
        return if (configured.isNullOrEmpty()) "$id.md" else configured
    }

    /**
     * 释放全部默认模板到数据目录 Markdown/（已存在的文件不覆盖；
     * 自定义文件名的模板不在此释放，读取时回退内置默认内容）。
     * 由运行时初始化（reloadRuntimeConfig）在启动与 /hb reload 时调用。
     */
    fun initialize() {
        val dir = directory() ?: run {
            BotShared.instance?.log_warning("无法确定插件数据目录，未初始化消息模板")
            return
        }
        if (!dir.isDirectory && !dir.mkdirs()) {
            BotShared.instance?.log_warning("无法创建消息模板目录: ${dir.path}")
            return
        }
        var released = 0
        for (id in TEMPLATE_IDS) {
            val fileName = fileNameOf(id)
            // 自定义文件名：不自动释放（内容需用户自己写；缺失时读取回退内置默认）
            if (fileName != "$id.md") continue
            val target = dir.resolve(fileName)
            if (target.exists()) continue
            val resource = MessageTemplates::class.java.classLoader.getResourceAsStream("$DIRECTORY/$id.md") ?: continue
            try {
                resource.use { Files.copy(it, target.toPath()) }
                released++
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // 并发初始化时忽略
            } catch (error: Exception) {
                BotShared.instance?.log_warning("初始化消息模板 $fileName 失败: ${error.message}")
            }
        }
        // 1.6.5：仍是旧默认内容的模板升级到新默认（用户改过的不动）
        upgradedDefaultContents(dir)
        if (released > 0) {
            BotShared.instance?.log_info("已释放 $released 个默认消息模板到 ${dir.path}（可自行编辑，删除后重启恢复默认）")
        }
    }

    /** 旧默认内容 → 新默认内容（仅未修改过的文件替换；幂等）。 */
    private fun upgradedDefaultContents(dir: File) {
        for ((id, upgrade) in DEFAULT_CONTENT_UPGRADES) {
            val fileName = fileNameOf(id)
            if (fileName != "$id.md") continue
            val target = dir.resolve(fileName)
            val current = try {
                if (target.isFile) target.readText(Charsets.UTF_8) else null
            } catch (_: Throwable) {
                null
            } ?: continue
            if (current.trim() != upgrade.first.trim()) continue
            try {
                target.writeText(upgrade.second, Charsets.UTF_8)
                cache.remove(target.path)
                BotShared.instance?.log_info("消息模板 $fileName 已升级到新默认内容（自定义过的模板不受影响）")
            } catch (error: Exception) {
                BotShared.instance?.log_warning("升级消息模板 $fileName 失败: ${error.message}")
            }
        }
    }

    /**
     * 渲染模板：读取文件内容（mtime 缓存）→ 占位符替换 → 经济别名替换。
     * 文件缺失时回退 JAR 内置默认模板，仍缺失返回空串（调用方自行兜底）。
     */
    fun render(id: String, vars: Map<String, String> = emptyMap()): String {
        if (id !in TEMPLATE_IDS) return ""
        var text = loadContent(id) ?: return ""
        for ((key, value) in vars) {
            if (key.isNotEmpty()) text = text.replace("{$key}", value)
        }
        return EconomyName.apply(text)
    }

    /** 读取模板原始内容（带 mtime 缓存；文件缺失回退内置资源）。 */
    private fun loadContent(id: String): String? {
        val dir = directory() ?: return bundledResource(id)
        val file = dir.resolve(fileNameOf(id)).canonicalFile
        return try {
            if (!file.toPath().startsWith(dir.canonicalFile.toPath()) || !file.isFile) {
                return bundledResource(id)
            }
            val mtime = file.lastModified()
            val cached = cache[file.path]
            if (cached != null && cached.first == mtime) return cached.second
            val content = file.readText(Charsets.UTF_8)
            cache[file.path] = mtime to content
            content
        } catch (_: Throwable) {
            bundledResource(id)
        }
    }

    /** 读取 JAR 内置默认模板。 */
    private fun bundledResource(id: String): String? = try {
        MessageTemplates::class.java.classLoader.getResourceAsStream("$DIRECTORY/$id.md")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Throwable) {
        null
    }

    /**
     * 带 @ 提及的模板回复（与旧版 sendDirect 行为一致）：
     * Markdown 优先、失败回退纯文本；渲染结果为空串时直接跳过（模板被清空的场景）。
     */
    fun sendDirect(event: GroupMessageEvent, id: String, vars: Map<String, String> = emptyMap()) {
        val message = render(id, vars)
        if (message.isEmpty()) return
        try {
            val userId = try {
                val contact = event.sender
                contact?.openid ?: contact?.id
            } catch (_: Throwable) {
                null
            }
            val content = if (!userId.isNullOrEmpty()) "<@$userId>\n$message" else message
            try {
                QClient.replyMarkdown(event, content, null)
            } catch (t: Throwable) {
                event.sendMessage(content)
            }
        } catch (t: Throwable) {
            try {
                event.sendMessage(message)
            } catch (_: Throwable) {
            }
        }
    }

    /** 不带 @ 提及的纯文本模板回复（与旧版 replyText 行为一致）。 */
    fun sendPlain(event: GroupMessageEvent, id: String, vars: Map<String, String> = emptyMap()) {
        val message = render(id, vars)
        if (message.isEmpty()) return
        try {
            event.sendMessage(message)
        } catch (_: Throwable) {
        }
    }

    /** 清空缓存（/hb reload 后重新读文件）。 */
    fun clearCache() = cache.clear()
}
