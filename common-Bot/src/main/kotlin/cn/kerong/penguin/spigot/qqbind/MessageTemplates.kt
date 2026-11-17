package cn.kerong.penguin.spigot.qqbind

import cn.kerong.bot.QClient
import cn.kerong.bot.provider.BotShared
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 消息模板系统：机器人在 QQ 发出的每一条文本回复都对应一个
 * Markdown 模板文件，可在配置文件 messages 段改写「这条回复用哪个 .md 文件」。
 *
 * - 模板文件位于数据目录 globalconfig/Markdown/ 下（插件版
 *   plugins/KERONGPenguin/globalconfig/Markdown/，模组版同理；旧版
 *   位于插件根目录 Markdown/，1.7.6.3 起启动时自动迁移），首次启动
 *   自动释放默认模板；
 * - 模板为 .md（Markdown）格式，机器人经 QQ Markdown 渲染发送，失败回退纯文本；
 * - 占位符形如 {player} / {error}（各模板可用变量见配置文件 messages 段注释），
 *   未提供的占位符原样保留；
 * - 模板渲染出口统一应用经济别名（economy-alias，见 [EconomyName]）。
 *
 * /查在线 的 Markdown 模板沿用 1.5.1 的 markdown.queryOnline（online.md），
 * 不在本系统内（该模板带独立占位符体系，见 motd 段说明）。
 *
 * ## 模板更新机制（1.7.6.4 重写，用户口径「默认改为全部删掉替换为插件
 * ## 内置，配置文件可添加白名单，白名单内的模板文件不会更新替换任何内容」）
 *
 * - **默认：全部替换为插件内置**——每次启动 / 重载时，不在白名单里的
 *   模板文件只要内容与当前 JAR 内置默认不一致（含被手改过），一律
 *   删除重建为内置最新默认（先 delete 再 writeBytes，全新文件零追加）；
 *   想保留自己改过的模板：把它的文件名加进 messages.keep-files 白名单；
 * - **白名单（messages.keep-files）**：模板文件名列表（带不带 .md 后缀
 *   都认），命中的文件不释放、不替换，原样保留——自己改的优先级
 *   高于插件内置；
 * - 自定义文件名（messages.<ID> 改名）同样参与：改名后的文件不在
 *   白名单时按默认规则替换为内置默认；
 * - 删除某个模板文件（且不在白名单）→ 下次启动重新释放内置默认，
 *   相当于「重置为默认」。
 *
 * 历史注：1.7.5.6–1.7.6.3 的「释放记录指纹」升级机制（未自改的旧默认
 * 自动替换、手改的永不动）已随本轮语义重写整体下线，旧版留下的
 * .release-record.json 记录文件启动时自动清理（机制已删，文件无意义）。
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
        "image-send-failed",             // 图片发送失败（玩家名片 / 查在线 / 排行榜）
        "manager-not-ready",             // 管理器未就绪（简短版）
        "manager-not-ready-bind",        // 绑定管理器未就绪（简短版）
        "manager-not-ready-retry",       // 管理器未就绪，请稍后再试
        "manager-not-ready-bind-retry",  // 绑定管理器未就绪，请稍后再试
        "qq-unknown",                    // 无法识别 QQ 账号
        // —— QQ 绑定 ——
        "bind-usage",                    // /绑定 无参数用法
        "bind-usage-fallback",           // /绑定 用法（管理器异常时的简短回退）
        "bind-disabled",                 // QQ 绑定功能未开启
        "bind-error",                    // 绑定处理异常
        "bind-success",                  // 绑定成功（/绑定 与纯数字自动绑定共用）
        "bind-invalid",                  // 绑定码无效或已过期
        "bind-limit",                    // 已达每 QQ 可绑定玩家数上限（1.7.4）
        "select-player",                 // 多玩家选择消息（1.7.4）
        "select-invalid",                // 选择序号超范围（1.7.4）
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
        // —— 玩家名片（原 /查信息）——
        "info-bind-disabled",            // 玩家名片未开启（QQ 绑定未启用）
        "info-closed",                   // 玩家名片被管理员关闭
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
 // —— 金币排行榜 ——
        "coins-leaderboard-no-economy",  // 未安装 Vault 经济插件
        "coins-leaderboard-no-data",     // 暂无金币数据
        "coins-leaderboard-render-failed", // 金币排行图片生成失败
        // —— 查询 OpenID / 玩家反查 ——
        "openid-self",                   // 自己的 OpenId 与群 OpenId
        "openid-lookup",                 // OpenId 反查结果
        "player-lookup",                 // 玩家名反查结果（QQ 账号 + 名下全部绑定玩家）
 // —— 多服务器集群 ——
        "cluster-single",                // 单服模式下的 /切换服务器 提示
        "cluster-list-fallback",         // 按钮不可用时的纯文本服务器列表
        // —— 发信息 ——
        "forward-disabled",              // 群聊转发功能已关闭
        // —— AI 对话 ——
        "ai-context-usage",              // /AI管理 不带参数时的状态与按钮提示（{status}）
        "ai-context-on",                 // 上下文已开启
        "ai-context-off",                // 上下文已关闭
        "ai-context-cleared",            // 上下文已清除
        "ai-manage-disabled",            // AI 功能未开启时的 /AI管理 提示
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
        // —— 菜单 ——
        "menu",                          // /菜单 指令按钮菜单的头部文案（{version}）
        // —— 命令执行 ——
        "invalid-params",                // 参数不正确
        "bridge-error",                  // 游戏桥接未配置或执行失败
        "sent-request",                  // 已发送执行请求
        "execute-failed",                // 执行失败（异常）
        "full-amount-toggled",           // 全量转发切换结果
 // MOTD 查询模板（motd-*）与 server-status 已随 /motd、
        // /在线服务器 命令一并移除；已释放到用户数据目录的旧 .md 文件无害
    )

    /** 模板内容缓存：文件路径 →（修改时间戳，内容）。 */
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * 去除全部空白字符（空格 / 制表 / 换行 / 回车）与 BOM 后的比对形态
     * （容忍带 BOM 释放的默认文件与任意换行风格差异；白名单外的文件
     * 内容与内置默认一致时不重写，mtime 不变）。
     */
    private fun normalize(text: String): String = text.replace(Regex("[\\s\\uFEFF]"), "")

    /**
     * 白名单（messages.keep-files，1.7.6.4 新键）：其中的模板文件不会被
     * 更新或替换，保留原样（用户自定义优先于插件内置）。条目为模板
     * 文件名（带不带 .md 后缀都认，比对前自动补齐）；配置缺失 / 读取
     * 异常按空名单处理（全部按默认规则替换为内置）。
     */
    fun keepFileNames(): Set<String> = try {
        val raw = plugin()?.config?.getConfigurationSection("messages")?.getStringList("keep-files")
            ?: emptyList()
        raw.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.endsWith(".md", ignoreCase = true)) it else "$it.md" }
            .toSet()
    } catch (_: Throwable) {
        emptySet()
    }

    /**
     * 旧版释放记录文件名（1.7.5.6–1.7.6.3 机制的遗留）：机制已下线，
     * 启动时自动删除清理（含写入中途的 .tmp 残留）。
     */
    private const val LEGACY_RELEASE_RECORD_FILE = ".release-record.json"

    /** 平台插件实例（Spigot / NeoForge 适配器均为 JavaPlugin 子类）。 */
    private fun plugin(): JavaPlugin? = try {
        BotShared.instance as? JavaPlugin
    } catch (_: Throwable) {
        null
    }

    /** 模板目录（数据目录 globalconfig/Markdown/，1.7.6.3 覆盖轮重构）。 */
    private fun directory(): File? = plugin()?.dataFolder?.resolve("globalconfig/$DIRECTORY")

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
     * 初始化消息模板（启动与 /hbkrg reload 时调用）：
     *
     * 1.7.6.4 更新机制——默认全部替换为插件内置，白名单保留：
     * - messages.keep-files 白名单里的文件名：完全不碰（不释放 / 不
     *   替换，原样保留，自己改的优先）；
     * - 其余模板：文件缺失 → 释放内置默认；内容与内置默认不一致
     *   （含被手改过 / 旧版默认）→ 删除重建为内置最新默认；
     * - 旧版释放记录文件（.release-record.json 与 .tmp 残留）自动删除。
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
        val keep = keepFileNames()
        var released = 0
        var replaced = 0
        var keptCount = 0
        for (id in TEMPLATE_IDS) {
            val fileName = fileNameOf(id)
            val bundled = bundledResource(id) ?: continue
            if (fileName in keep) {
                keptCount++
                continue
            }
            val target = dir.resolve(fileName)
            target.parentFile?.let { parent ->
                if (!parent.isDirectory) parent.mkdirs()
            }
            val current: String? = try {
                if (target.isFile) target.readText(Charsets.UTF_8) else null
            } catch (_: Throwable) {
                null
            }
            try {
                if (current == null) {
                    // 文件缺失：释放内置默认（删除文件后重启 / 重载即恢复默认）
                    target.writeBytes(bundled.toByteArray(Charsets.UTF_8))
                    released++
                } else if (normalize(current) != normalize(bundled)) {
                    // 1.7.6.4：默认全部替换为插件内置——白名单外的文件
                    // 与内置默认不一致（手改过 / 旧版默认）一律删除重建
                    applyReplacement(dir, target, bundled)
                    cache.remove(target.path)
                    replaced++
                }
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // 并发初始化时忽略
            } catch (error: Exception) {
                BotShared.instance?.log_warning("初始化消息模板 $fileName 失败: ${error.message}")
            }
        }
        // 旧版释放记录清理（机制已删，记录文件无意义；含写入中途的 .tmp 残留）
        try {
            dir.resolve(LEGACY_RELEASE_RECORD_FILE).let { if (it.isFile) it.delete() }
            dir.resolve("$LEGACY_RELEASE_RECORD_FILE.tmp").let { if (it.isFile) it.delete() }
        } catch (_: Throwable) {
        }
        if (released > 0) {
            BotShared.instance?.log_info("已释放 $released 个默认消息模板到 ${dir.path}（想保留自己改过的模板，把文件名加进配置 messages.keep-files 白名单即可）")
        }
        if (replaced > 0) {
            BotShared.instance?.log_info("$replaced 个消息模板已替换为插件内置默认（不在 messages.keep-files 白名单里的模板每次启动都会重置为内置最新默认）")
        }
        if (keptCount > 0) {
            BotShared.instance?.log_info("$keptCount 个消息模板在白名单里，已保留原样不更新（messages.keep-files）")
        }
    }

    /**
     * 删除旧模板文件后重新释放当前内置默认（1.7.5.9 用户口径：
     * 「直接删掉重新生成本版默认」）——先 [File.delete] 再
     * [File.writeBytes]，落盘的是全新文件（全新 inode / mtime），
     * 与原地 writeText 覆写在内容上等价、在语义上按用户指令显式化；
     * delete 失败（权限/只读等）时退回原地 writeText，保证升级不中断。
     */
    private fun applyReplacement(dir: File, target: File, bundled: String) {
        try {
            if (target.isFile && !target.delete()) {
                target.writeText(bundled, Charsets.UTF_8)
            } else {
                target.writeBytes(bundled.toByteArray(Charsets.UTF_8))
            }
        } catch (_: Throwable) {
            // 兜底：删除重建失败时退回原地覆盖，内容一致性优先
            target.writeText(bundled, Charsets.UTF_8)
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
     * 带 @ 提及的模板回复：Markdown 优先、失败回退纯文本；渲染结果为空串时直接跳过（模板被清空的场景）。
     *
 * 按 [QClient.replyMarkdown] 的返回值回退——旧版只 catch
     * 抛出的异常，但 SDK 的 HTTP 代理对平台拒收（4xx/5xx）不抛异常，
     * 失败时消息静默丢失；现在返回 false 即回退 event.sendMessage。
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
            val sent = try {
                QClient.replyMarkdown(event, content, null)
            } catch (_: Throwable) {
                false
            }
            if (!sent) {
                try {
                    event.sendMessage(content)
                } catch (_: Throwable) {
                }
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

    /** 清空缓存（/hbkrg reload 后重新读文件）。 */
    fun clearCache() = cache.clear()
}
