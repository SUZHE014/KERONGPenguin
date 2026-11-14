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
 *
 * 模板升级路径（1.7.5.6 覆盖轮：升级 = 完整替换，全模板通用）：
 * - 首次释放后不覆盖用户修改；
 * - 磁盘内容仍是某一版**未自改的默认**（含 BOM / 空白 / 换行差异）时，
 *   自动**整体替换**为当前 JAR 内置最新默认——替换是全量 writeText，
 *   绝不追加，旧文案零残留；
 * - 「未自改的默认」的判定有三条，命中任意一条即替换：
 *   ① 磁盘内容 == 当前内置默认（无需动作，只补指纹）；
 *   ② 释放记录（.release-record.json）里记着本文件上次的默认指纹且
 *      磁盘内容仍与该指纹一致——上一次写入/确认后用户没动过，属于
 *      旧版默认，跨任意历史版本自动收敛（不再逐版手抄旧默认表）；
 *   ③ [DEFAULT_CONTENT_UPGRADES] 历代旧默认逐版比对 + 默认特征兜底
 *      （老安装升级上来没有释放记录时的过渡判定，表已收录的形态继续有效）。
 * - 自己改过的内容一律不动（三条都不命中）。
 *
 * 历史注：1.7.5.1 曾把 menu 设为「系统 UI 模板」无条件重置（每次启动
 * 强制覆盖为默认），目的是收敛线上历史残留形态；但该机制会把用户
 * 自定义的菜单一并覆盖，且实际验证发现原地覆写在部分环境未生效。
 * 1.7.5.2 移除强制重置，回到升级表路径；但升级表只覆盖了 5 个模板，
 * 其余模板的默认文案变更后老安装永远拿不到新默认（用户实测「内容
 * 更新了但『帮助』等旧文字没被删除，手动删掉重载才恢复」即此类残留），
 * 故 1.7.5.6 覆盖轮改为「释放记录」机制：每个模板释放/确认时都记下
 * 默认内容指纹，升级后指纹仍匹配 = 未自改的旧默认 → 整体替换为最新默认；
 * 手改过的文件指纹不再匹配 → 永不动。升级表保留作为无记录老安装的兜底。
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
     * 单条默认内容升级规则（1.7.5.6 覆盖轮起仅作为释放记录缺失时的兜底
     * 判定；替换内容一律取当前 JAR 内置资源，本表 newContent 字段不再
     * 单独使用，保留用于历史对照与 [shouldUpgrade] 决策）。
     *
     * @property olds 历代旧默认内容（normalize 后精确比对，覆盖已知的每一版）
     * @param features 默认内容特征（当前内容含任一即视为「未被自定义改过的
     *   默认模板」——兜底收敛表没收录到的中间态默认版本；null = 不做特征匹配）
     * @property newContent 新默认内容（历史对照用；实际写入以 JAR 内置资源为准）
     */
    private class UpgradeSpec(
        val olds: List<String>,
        private val features: List<String>?,
        val newContent: String,
    ) {
        /** 当前磁盘内容是否应升级到新默认（幂等：已等于新默认返回 false）。 */
        fun shouldUpgrade(current: String): Boolean {
            val normalizedCurrent = normalize(current)
            if (normalizedCurrent == normalize(newContent)) return false
            if (olds.any { normalize(it) == normalizedCurrent }) return true
            val marks = features ?: return false
            return marks.any { current.contains(it) }
        }
    }

    /** 去除全部空白字符（空格 / 制表 / 换行 / 回车）与 BOM 后的比对形态（1.7.5.2：
     * 容忍历史上带 BOM 释放的默认文件，以及任意换行风格差异）。 */
    private fun normalize(text: String): String = text.replace(Regex("[\\s\\uFEFF]"), "")

    /**
     * 内容指纹（1.7.5.6 覆盖轮）：normalize 后取 SHA-256，用于释放记录——
     * 空白 / 换行 / BOM 差异不影响判定，与磁盘实际内容严格对应。
     */
    private fun fingerprint(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(normalize(text).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * 释放记录文件名（位于模板目录内，隐藏文件）：模板文件名 → 该文件
     * 上一次由插件释放 / 确认为默认内容时的指纹。作用见类头「模板升级路径」②。
     */
    private const val RELEASE_RECORD_FILE = ".release-record.json"

    /** 读取释放记录（文件缺失 / 损坏一律回空表，不阻断模板流程）。 */
    private fun loadReleaseRecord(dir: File): MutableMap<String, String> {
        val file = dir.resolve(RELEASE_RECORD_FILE)
        val result = mutableMapOf<String, String>()
        if (!file.isFile) return result
        try {
            val text = file.readText(Charsets.UTF_8)
            val parsed = com.alibaba.fastjson.JSON.parseObject(text) ?: return result
            for (entry in parsed.entries) {
                val name = entry.key ?: continue
                val fp = entry.value as? String ?: continue
                if (fp.isNotEmpty()) result[name] = fp
            }
        } catch (_: Throwable) {
        }
        return result
    }

    /** 原子写入释放记录（临时文件 + rename；失败仅告警，不影响模板流程）。 */
    private fun saveReleaseRecord(dir: File, record: Map<String, String>) {
        try {
            val file = dir.resolve(RELEASE_RECORD_FILE)
            val tmp = dir.resolve("$RELEASE_RECORD_FILE.tmp")
            tmp.writeText(com.alibaba.fastjson.JSON.toJSONString(record), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(com.alibaba.fastjson.JSON.toJSONString(record), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (error: Throwable) {
            BotShared.instance?.log_warning("写入释放记录失败: ${error.message}")
        }
    }

    /**
     * 默认模板内容升级表：历代旧默认内容 → 新默认内容。
     *
     * 1.7.5.6 覆盖轮起本表降级为释放记录缺失（老安装首次升级）时的
     * 兜底判定：命中 [UpgradeSpec.shouldUpgrade]（历代旧默认逐版比对 +
     * 默认特征）即整体替换为当前 JAR 内置默认。日常发版改默认文案不再
     * 需要往本表逐版抄旧默认——释放记录会自动覆盖。
     */
    private val DEFAULT_CONTENT_UPGRADES: Map<String, UpgradeSpec> = mapOf(
 // OpenId 行改为 {openidPart}（管理员查询才显示）
        "openid-lookup" to UpgradeSpec(listOf(
            "【OpenID 反查】\nOpenId: {openid}\n群昵称: {nickname}{lastSeenPart}\n绑定玩家: {player}",
        ), listOf("【OpenID 反查】"), "【OpenID 反查】\n{openidPart}群昵称: {nickname}{lastSeenPart}\n绑定玩家: {player}"),
 // menu 回到升级表（1.7.5.2：系统模板强制重置机制移除）：
        // 两版历史默认逐版比对 + 「### 指令菜单」特征兜底（收敛表未收录的
        // 中间态默认、历次部署残留形态），自定义内容不动。
        // 1.7.5.4 排版改版：版本号从标题下方的粗体行移到模板末尾、非粗体
        // 小字（QQ Markdown 子集无字号标记，用普通文本行实现「字体放小」）
        "menu" to UpgradeSpec(listOf(
            "### 指令菜单\n\n点击下方按钮即可执行；带参数的指令（绑定 / 发信息 / 执行命令等）点击后复制到输入框，补全内容再发送。\n\n---\n**当前插件版本：v{version}**",
            "### 指令菜单（{page}/{pages}）",
            "### 指令菜单（{page}/{pages}）\n\n**当前插件版本：v{version}**",
        ), listOf("### 指令菜单"), "### 指令菜单（{page}/{pages}）\n\n---\nv{version}"),
 // AI 管理合并：旧命令名版本与旧提示句版本收敛为纯状态行（操作都在按钮里）
        "ai-context-usage" to UpgradeSpec(listOf(
            "用法: /AI对话上下文 开|关\n当前状态: {status}",
            "当前 AI 上下文状态：{status}\n\n可发送 /AI管理 开|关|清除，或点击下方按钮选择操作：",
        ), listOf("AI 上下文状态", "AI对话上下文"), "当前 AI 上下文状态：{status}"),
 // 解除黑名单清单：历代旧默认收敛到「命令在前、按钮在后」口径（用户口径
        // 「先 /解除黑名单 @对应的人」；1.7.5 覆盖第三轮调序）
        "unblacklist-list" to UpgradeSpec(listOf(
            "黑名单列表（{count} 个）：\n{list}\n\n解除请发送: /解除黑名单 @QQ",
            "黑名单列表（{count} 个）：\n{list}\n\n点击下方按钮即可解除对应条目（也可发送 /解除黑名单 @QQ）",
            "黑名单列表（{count} 个）：\n{list}\n\n可以按下对应的按钮，或者艾特对方发送 /解除黑名单 解除",
        ), listOf("黑名单列表（{count} 个）"), "黑名单列表（{count} 个）：\n{list}\n\n发送 /解除黑名单 @对应的人 解除；也可以点击下方对应的按钮解除"),
 // 全量转发改为总开关口径（群级独立开关删除，1.7.5 覆盖第三轮）
        "full-amount-toggled" to UpgradeSpec(listOf(
            "本群全量转发已{state}",
        ), listOf("全量转发已", "本群全量转发已"), "全量转发已{state}（总开关，全部配置群统一生效{writeResult}）"),
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
     * 自定义文件名的模板不在此释放，读取时回退内置默认内容），并把仍是
     * 未自改旧默认的模板整体替换为当前内置默认（用户改过的不动）。
     * 由运行时初始化（reloadRuntimeConfig）在启动与 /hbkrg reload 时调用。
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
        val record = loadReleaseRecord(dir)
        var recordDirty = false
        var released = 0
        var upgraded = 0
        for (id in TEMPLATE_IDS) {
            val fileName = fileNameOf(id)
            // 自定义文件名：不自动释放（内容需用户自己写；缺失时读取回退内置默认）
            if (fileName != "$id.md") continue
            val bundled = bundledResource(id) ?: continue
            val target = dir.resolve(fileName)
            val current: String? = try {
                if (target.isFile) target.readText(Charsets.UTF_8) else null
            } catch (_: Throwable) {
                null
            }
            try {
                if (current == null) {
                    // 首次释放（不覆盖已有文件；bundled 与 JAR 内置资源同源）
                    target.writeBytes(bundled.toByteArray(Charsets.UTF_8))
                    released++
                    if (record[fileName] != fingerprint(bundled)) {
                        record[fileName] = fingerprint(bundled)
                        recordDirty = true
                    }
                    continue
                }
                val currentFp = fingerprint(current)
                when {
                    // ① 已是当前默认：无需动作，补齐指纹
                    normalize(current) == normalize(bundled) -> {
                        if (record[fileName] != currentFp) {
                            record[fileName] = currentFp
                            recordDirty = true
                        }
                    }
                    // ② 释放记录指纹仍匹配：上一次释放/确认后用户没动过——
                    //    属于旧版默认，整体替换为当前默认（完整替换，零追加）
                    record[fileName] != null && record[fileName] == currentFp -> {
                        target.writeText(bundled, Charsets.UTF_8)
                        cache.remove(target.path)
                        upgraded++
                        record[fileName] = fingerprint(bundled)
                        recordDirty = true
                        BotShared.instance?.log_info(
                            "消息模板 $fileName 已升级到新默认内容（整体替换；自定义过的模板不受影响）"
                        )
                    }
                    // ③ 升级表兜底（无释放记录的老安装）：历代旧默认逐版比对
                    //    + 默认特征匹配，命中同样整体替换
                    DEFAULT_CONTENT_UPGRADES[id]?.shouldUpgrade(current) == true -> {
                        target.writeText(bundled, Charsets.UTF_8)
                        cache.remove(target.path)
                        upgraded++
                        record[fileName] = fingerprint(bundled)
                        recordDirty = true
                        BotShared.instance?.log_info(
                            "消息模板 $fileName 已升级到新默认内容（整体替换；自定义过的模板不受影响）"
                        )
                    }
                    // 其余：用户自改——不动、不记录（手改文件永不收敛，
                    // 直到自行删除恢复默认；删除后重载即重新释放最新默认）
                }
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // 并发初始化时忽略
            } catch (error: Exception) {
                BotShared.instance?.log_warning("初始化消息模板 $fileName 失败: ${error.message}")
            }
        }
        if (recordDirty) {
            saveReleaseRecord(dir, record)
        }
        if (released > 0) {
            BotShared.instance?.log_info("已释放 $released 个默认消息模板到 ${dir.path}（可自行编辑，删除后重启恢复默认）")
        }
        if (upgraded > 0) {
            BotShared.instance?.log_info("$upgraded 个仍是旧默认的消息模板已整体替换到新默认（自定义过的模板不受影响）")
        }
    }

    /**
     * （1.7.5.6 覆盖轮起本方法不再单独调用——判定已并入 [initialize] 的
     * 三路径流程；保留空实现防旧编译产物链接失败。）
     */
    private fun upgradedDefaultContents(dir: File) {
        // 已并入 initialize()
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
