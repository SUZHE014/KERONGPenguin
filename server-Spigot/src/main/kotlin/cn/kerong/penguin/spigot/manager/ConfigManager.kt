package cn.kerong.penguin.spigot.manager

import cn.kerong.bot.provider.AdminMode
import cn.kerong.bot.provider.ChatFormat
import cn.kerong.bot.provider.ConfigUpgrader
import cn.kerong.bot.provider.CustomCommandDetail
import cn.kerong.bot.provider.Motd
import cn.kerong.bot.provider.PlayerEventFormat
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.LinkedHashMap

/**
 * 配置管理器：加载 config.yml、补齐缺失键、提供类型安全的配置访问。
 *
 * 配置结构修复：查在线与在线排行榜不依赖 QQ 绑定，相关配置从
 * qq-bind 段移出——渲染设置迁至顶层 render 段（含新增性能键
 * command-cooldown-seconds）、排行榜迁至顶层 leaderboard 段、新增金币排行榜
 * coins-leaderboard 段与经济别名 economy-alias、新增消息模板 messages 段
 * （每条机器人回复对应一个 .md 模板文件）。旧值经版本迁移 16→17 自动搬家。
 */
class ConfigManager(private val plugin: JavaPlugin) {

    /** 配置文件路径。 */
    fun getConfigFile(): File = File(plugin.dataFolder, "config.yml")

    /** 初始化：版本升级时先以 JAR 新版重建，再释放默认配置并装载。 */
    fun initialize() {
 // 先恢复损坏的配置文件（语法错误 → 轻量修复或备份重建默认，
        // 修复旧版「解析失败 → 空配置 → 机器人不启动」的死胡同，
        // 见 ConfigBrokenRecovery KDoc）
        try {
            ConfigBrokenRecovery.recoverIfBroken(plugin)
        } catch (_: Throwable) {
        }
 // 磁盘 config-version 低于 JAR 内置时，以 JAR 新版
        // config.yml（注释完整）为底重建用户文件（保留自定义值；重建
        // 先校验后落盘，失败时旧文件不受影响）——修复旧版只追加裸键、
        // 注释与结构永不更新的问题；全新安装不触发，由下方 saveDefaultConfig
        // 原样释放
        ConfigFileRebuilder.rebuildIfNeeded(
            plugin,
            CURRENT_CONFIG_VERSION,
            LEGACY_PATH_MOVES,
            REMOVED_CONFIG_KEYS,
        )
        plugin.saveDefaultConfig()
        reload()
    }

    /** 重新加载配置（含损坏自动恢复、格式错误保护与缺失键补齐）。 */
    fun reload() {
 // 运行期重载同样先做损坏恢复（/hbkrg reload 时改坏的文件
        // 也能自动救回，不再「跳过保存」空配置硬跑）
        try {
            ConfigBrokenRecovery.recoverIfBroken(plugin)
        } catch (_: Throwable) {
        }
        try {
            plugin.reloadConfig()
        } catch (t: Throwable) {
            plugin.logger.warning("配置文件格式错误，跳过重载以避免数据丢失: ${t.message}")
            return
        }
        val config: FileConfiguration = plugin.config
        if (config == null || config.getKeys(false).isEmpty()) {
            plugin.logger.warning("配置文件为空或格式错误，跳过保存以避免数据丢失")
            return
        }

        var changed = false
        // 补齐缺失键（isSet 不穿透 JAR 内置默认值，确保新键真正写入用户配置文件，
        // 否则 contains 会因 defaults 回退恒为 true，导致新键对用户不可见）
        changed = ConfigUpgrader.fillMissing(DEFAULT_VALUES, { config.isSet(it) }) { path, value ->
            config.set(path, value)
        } || changed

        var oldVersion = config.getInt(CONFIG_VERSION_PATH, 0)
        if (oldVersion != CURRENT_CONFIG_VERSION) {
            config.set(CONFIG_VERSION_PATH, CURRENT_CONFIG_VERSION)
            changed = true
        }
 // 游戏→群 转发默认格式带上玩家名（旧默认值未含发送者，群友看不出
        // 是谁说的话；仅仍是旧默认值的配置升级，用户自定义值不动）
        if (oldVersion in 1 until 19 &&
            "[游戏] {message}" == config.getString("chat-format.from-game")
        ) {
            config.set("chat-format.from-game", "[游戏] <{name}> {message}")
            plugin.logger.info("聊天转发：游戏→群默认格式已升级为 [游戏] <{name}> {message}（带上玩家名，可自行修改）")
            changed = true
        }
 // 命令更名 /金币榜 → /财富榜（commands 段开关同步迁移保留用户取值；
 // 别名彻底移除，命令只保留 /财富榜）
        if (oldVersion in 1 until 18 && config.isSet("commands.金币榜")) {
            config.set("commands.财富榜", config.get("commands.金币榜"))
            config.set("commands.金币榜", null)
            plugin.logger.info("命令更名：/金币榜 → /财富榜（原 commands.金币榜 开关取值已迁移）")
            changed = true
        }
 // 排行榜每张图片展示玩家数 20 → 15（旧默认值 20 属 1.5.5 自动补齐，
        // 非用户手动意图，升级时同步改为新默认；用户自定义的其他值（如 30）不动）
        if (oldVersion in 1 until 15 && config.getInt("qq-bind.leaderboard.top", 15) == 20) {
            config.set("qq-bind.leaderboard.top", 15)
            plugin.logger.info("在线排行榜每张图片展示玩家数：20 → 15（新默认，可在 leaderboard.top 改回）")
            changed = true
        }
 // random-background（默认 false = 内置图）反向更名 builtin-background
        //（默认 true = 内置图）—— 旧配置手动开启过随机的用户升级后保持随机效果
        if (oldVersion in 1 until 16 && config.isSet("qq-bind.leaderboard.random-background") &&
            config.getBoolean("qq-bind.leaderboard.random-background", false)
        ) {
            config.set("qq-bind.leaderboard.builtin-background", false)
            plugin.logger.info("在线排行榜背景：已按旧配置随机使用 img/ 目录图片（leaderboard.builtin-background=false）")
            changed = true
        }
 // 渲染与排行榜配置移出 qq-bind 段（查在线 / 排行榜不依赖 QQ 绑定）：
        // 路径平移 + 保留用户已改值 + 清掉迁空的旧子段
        if (oldVersion in 1 until 17) {
            var movedAny = false
            for ((old, new) in LEGACY_PATH_MOVES) {
                if (config.isSet(old)) {
                    config.set(new, config.get(old))
                    config.set(old, null)
                    movedAny = true
                }
            }
            if (movedAny) {
                // 移空后的旧子段一并清掉（两个子段的全部子键都已迁走）
                config.set("qq-bind.render", null)
                config.set("qq-bind.leaderboard", null)
                plugin.logger.info("配置结构升级：渲染与排行榜设置已从 qq-bind 段移至顶层 render / leaderboard 段（原有取值全部保留）")
                changed = true
            }
        }
 // 性能数据缓存默认 5 → 60（仍是旧默认 5 的配置升级；
        // 用户自定义值不动；配置重建器内同款收敛，这里是重建失败兑底路径的
        // 双保险）
        if (oldVersion in 1 until 21 &&
            config.isSet("render.performance.cache-seconds") &&
            config.getInt("render.performance.cache-seconds", -1) == 5
        ) {
            config.set("render.performance.cache-seconds", 60)
            changed = true
        }
 // 标题框接线改版（四张卡片顶部标题区从硬编码改接
        // render.title-card-gray，默认 96 → 80）——升级时旧默认 96 自动收敛
        // 为新默认 80；自行改过该值的配置不受影响（重建器内同款收敛，
        // 这里是重建失败兑底路径的双保险）
        if (oldVersion in 1 until 30 &&
            config.isSet("render.title-card-gray") &&
            config.getInt("render.title-card-gray", -1) == 96
        ) {
            config.set("render.title-card-gray", 80)
            plugin.logger.info("标题框黑度默认值调整：96 → 80（render.title-card-gray，可自行改回）")
            changed = true
        }
        // 副标题灰度改为 106（用户口径「副标题灰度默认值改为 106」，106
        // 与项目卡 item-card-gray 相同）——升级时历史默认 40 / 56 / 80 自动
        // 收敛为新默认 106；自行改过该值的配置不受影响（重建器内同款
        // 收敛，这里是重建失败兑底路径的双保险）
        if (oldVersion in 1 until 37 &&
            config.isSet("render.subtitle-card-gray")
        ) {
            val subGray = config.getInt("render.subtitle-card-gray", -1)
            if (subGray == 40 || subGray == 56 || subGray == 80) {
                config.set("render.subtitle-card-gray", 106)
                plugin.logger.info("副标题灰度默认值调整：$subGray → 106（render.subtitle-card-gray，与项目卡片相同，可自行改回）")
                changed = true
            }
        }
 // 服务器开关消息默认文案换新（去表情 + 重启 / 开机文案
        // 分开）——历史默认文案（第三轮初始版 / 第六轮带表情版 / 第六轮误写版 /
        // 第七轮颜文字修正版）都是默认写入而非用户意图，升级时自动收敛为新
        // 默认；自行改过文案的配置不受影响（重建器内同款收敛，这里是重建失败
        // 兑底路径的双保险）
        if (oldVersion in 1 until 40) {
            var notifyTextUpdated = false
            val shutdownText = config.getString("server-notify.shutdown.message")
            if (shutdownText == "服务器已正常关闭" ||
                shutdownText == "[状态]\n服务器已正常关闭 (╯︵╰,)"
            ) {
                config.set("server-notify.shutdown.message", "[状态]\n服务器已正常关闭")
                notifyTextUpdated = true
            }
            val restartText = config.getString("server-notify.restart.message")
            if (restartText == "服务器已重启完成" ||
                restartText == "[状态]\n服务器已开启！！(≧∇≦)/"
            ) {
                config.set("server-notify.restart.message", "[状态]\n服务器重启完毕，已正常运行(o‿∩)")
                notifyTextUpdated = true
            }
            // 意外关闭：全部历史默认（初始版 / 第六轮误写版 / 第七轮颜文字修正版）一并收敛
            val crashText = config.getString("server-notify.crash.message")
            if (crashText == "服务器意外关闭（崩溃或被强制结束），请管理员检查控制台" ||
                crashText == "[状态]\n服务器....༽◺_轨迹༼" ||
                crashText == "[状态]\n服务器....༽◺_◿༼"
            ) {
                config.set("server-notify.crash.message", "[状态]\n服务器....")
                notifyTextUpdated = true
            }
            if (notifyTextUpdated) {
                plugin.logger.info("服务器开关消息默认文案已换新（server-notify 各 message 去表情 / 重启与开机文案分开，可自行改回）")
                changed = true
            }
        }
 // QQ 绑定拦截文案回到「绑定码」口径（1.7.5.2：验证码 → 绑定码，
        // 引导改为「请在 QQ 群发送 绑定码」）——历史默认文案（v0.1.5.0 绑定码版 /
        // 1.7.2 验证码版）都是默认写入而非用户意图，升级时自动收敛为新默认；
        // 自行改过文案的配置不受影响（重建器内同款收敛，这里是重建失败
        // 兕底路径的双保险）
        if (oldVersion in 1 until 44 && config.isSet("qq-bind.kick-message")) {
            // 单行字符串写法（非列表）读出来是空表，不匹配任何旧默认，自然不动
            val current = config.getStringList("qq-bind.kick-message").joinToString("\n")
            val oldDefaults = setOf(
                // v0.1.5.0 ~ 1.7.1 默认（绑定码口径）
                "&c===== QQ 绑定验证 =====\n&f你还未绑定 QQ，无法进入服务器。\n\n&e你的专属绑定码: &a&l{code}\n&7请在 QQ 群发送: &f/绑定 {code}\n&7（绑定码 {expire} 分钟内有效）",
                // 1.7.2 ~ 1.7.5.1 默认（验证码口径）
                "&c===== QQ 绑定验证 =====\n&f你还未绑定 QQ，无法进入服务器。\n\n&e你的专属验证码: &a&l{code}\n&7可以直接发送纯数字验证码: &f{code}\n&7（也可发送 /绑定 {code}；验证码 {expire} 分钟内有效）",
            )
            if (current in oldDefaults) {
                config.set("qq-bind.kick-message", java.util.Arrays.asList(
                    "&c===== QQ 绑定验证 =====",
                    "&f你还未绑定 QQ，无法进入服务器",
                    "",
                    "&e你的专属绑定码: &a&l{code}",
                    "&7请在 QQ 群发送: &f{code}",
                    "&7（绑定码 {expire} 分钟内有效）",
                ))
                plugin.logger.info("QQ 绑定拦截文案已更新为新默认（绑定码口径；自行改过 kick-message 的配置不受影响）")
                changed = true
            }
        }
        // 清理已废弃的配置段
 // 移除 query-online 段（图片输出改为 markdown 关闭时自动启用）与 motd.text（文本模板已删除）
 // 移除 commands.个人信息（该命令已更名为 查信息）
 // 移除 qq-bind.leaderboard.random-background（反向更名为 builtin-background，
        // 手动开启过随机的用户已在上方迁移逻辑中保留意图）
 // 升 48：删除旧键 ai.reply-quote（开关语义已被 ai.reminder-mode 三选一
        // 取代）——先把旧开关偏好归一到 reminder-mode（旧键为 false 且新键
        // 未选择 → 写 none 保留「不引用」偏好；其余 → 写 quote，与空串语义
        // 一致），再由下方废弃键清单统一删除旧键本体
        if (oldVersion in 1 until 48) {
            val reminder = config.getString("ai.reminder-mode", "")?.trim() ?: ""
            if (reminder.isEmpty() && config.isSet("ai.reply-quote")) {
                config.set(
                    "ai.reminder-mode",
                    if (config.getBoolean("ai.reply-quote", true)) "quote" else "none",
                )
                plugin.logger.info("配置升级：ai.reply-quote 开关已并入 ai.reminder-mode（三选一 at/quote/none），旧键已删除")
                changed = true
            }
        }
 // 移除 ai.context-global（旧默认值误写进用户配置，实际无任何功能——
        // AI 上下文自 1.5.3 起即为全局一份记忆，不存在按群/按人切换）
 // 移除 render.gc-after-render（渲染后 GC 提示已废除——System.gc
        // 触发的 Full GC 停顿伤 tick，内存释放改为确定性 flush，见 CardRenderPool）
 // 移除 render.idle-gc-hint（空闲 GC 提示——Full GC 全停顿
        // 可能落在玩家游玩时刻造成卡顿，内存归还交给 JVM 自然节奏）
 // 移除 render.performance.show-*（查性能卡片指标固定四项
        // TPS / MSPT / 内存 / CPU，不再提供单项显隐；在线人数卡已删）
        for (removed in REMOVED_CONFIG_KEYS) {
            if (config.contains(removed)) {
                config.set(removed, null)
                changed = true
            }
        }
        if (changed) {
            plugin.saveConfig()
            plugin.logger.info("配置文件已升级到版本 $CURRENT_CONFIG_VERSION（旧版本：$oldVersion）")
        }
    }

 // 凭据读取自动清洗首尾空白与不可见
    // 字符——用户从网页/聊天工具复制 app-id 与 secret 时极易带上零宽空格
    // （\u200B）、BOM（\uFEFF）、全角空格等，肉眼不可见但平台按字面校验
    // 直接拒绝（appid invalid 100007，且报错不含糊其辞地指向「凭据错了」，
    // 用户反复核对肉眼可见的字符永远找不到原因）。读取时统一剥掉，
    // 检测到污染时醒目提示一次并说明去哪里修正。
    fun botAppId(): String = sanitizeCredential(plugin.config.getString("bot.app-id"), "bot.app-id")

    fun botSecret(): String = sanitizeCredential(plugin.config.getString("bot.secret"), "bot.secret")

    /**
 * 凭据清洗：剥除首尾空白与不可见字符，检测到污染时
     * 控制台 warning 提示（带原长/清理后长度对照）。
     */
    private fun sanitizeCredential(raw: String?, key: String): String {
        val value = raw ?: return ""
        val cleaned = trimInvisible(value)
        if (cleaned != value) {
            plugin.logger.warning(
                "配置 $key 读到首尾空白/不可见字符（原长 ${value.length} → 清理后 ${cleaned.length}），已自动清理；" +
                    "请修正 config.yml 中该行的多余空格/引号内空白，避免凭据被平台拒绝（appid invalid）"
            )
        }
        return cleaned
    }

    /**
     * 剥除首尾空白与不可见字符（空格/制表符等常规空白 + 全角空格/BOM/
     * 零宽字符/窄窄空格等 20 个不可见码位）。
     */
    private fun trimInvisible(value: String): String {
        val invisible = charArrayOf(
            '\u3000', '\u00A0', '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005',
            '\u2006', '\u2007', '\u2008', '\u2009', '\u200A', '\u200B', '\u200C', '\u200D',
            '\u202F', '\u205F', '\uFEFF', '\u2060',
        )
        var start = 0
        var end = value.length
        while (start < end && (value[start].isWhitespace() || value[start] in invisible)) start++
        while (end > start && (value[end - 1].isWhitespace() || value[end - 1] in invisible)) end--
        return value.substring(start, end)
    }

    fun botName(): String = plugin.config.getString("bot.name", "KERONGPenguin") ?: "KERONGPenguin"

    fun serverName(): String = plugin.config.getString("serverName", botName()) ?: botName()

    fun groupOpenIds(): List<String> = plugin.config.getStringList("bot.groups")

 /** 多服务器集群：本服显示名（未配置时用 bot.name）。 */
    fun clusterName(): String = plugin.config.getString("bot.cluster.name", "") ?: ""

    /** 多服务器集群：全部成员服务器名列表（各服必须一致；少于 2 个不启用）。 */
    fun clusterMembers(): List<String> = plugin.config.getStringList("bot.cluster.members")

 // ---------- 服务器开关消息（引入；180 秒重启判定窗口 + 重启 / 普通开机文案分开） ----------

    fun isServerNotifyEnabled(): Boolean = plugin.config.getBoolean("server-notify.enabled", true)

    /**
 * 重启判定窗口（秒，恢复该键，默认 180）：
     * 上次正常关闭后在这个秒数内再次开机判定为「重启」（发 restart 重启文案），
     * 超过这个时长再开机按普通开机处理（发 start 开机文案）；
     * 配 0 = 不判定重启，一律按普通开机播报；负数按 0 处理。
     */
    fun serverNotifyRestartWindow(): Int =
        plugin.config.getInt("server-notify.restart-window", 180).coerceAtLeast(0)

    fun isServerNotifyShutdownEnabled(): Boolean =
        isServerNotifyEnabled() && plugin.config.getBoolean("server-notify.shutdown.enabled", true)

    fun serverNotifyShutdownMessage(): String =
        plugin.config.getString("server-notify.shutdown.message", "[状态]\n服务器已正常关闭") ?: "[状态]\n服务器已正常关闭"

    /** 重启通知开关（上次正常关闭后在 restart-window 秒内再次开机时发送）。 */
    fun isServerNotifyRestartEnabled(): Boolean =
        isServerNotifyEnabled() && plugin.config.getBoolean("server-notify.restart.enabled", true)

    /** 重启通知文案（占位符 {server} / {platform}）。 */
    fun serverNotifyRestartMessage(): String =
        plugin.config.getString("server-notify.restart.message", "[状态]\n服务器重启完毕，已正常运行(o‿∩)") ?: "[状态]\n服务器重启完毕，已正常运行(o‿∩)"

    /** 普通开机通知开关（上次正常关闭后超过 restart-window 秒再开机时发送）。 */
    fun isServerNotifyStartEnabled(): Boolean =
        isServerNotifyEnabled() && plugin.config.getBoolean("server-notify.start.enabled", true)

    /** 普通开机通知文案（占位符 {server} / {platform}；首次安装不发送）。 */
    fun serverNotifyStartMessage(): String =
        plugin.config.getString("server-notify.start.message", "[状态]\n服务器已开启！！(≧∇≦)/") ?: "[状态]\n服务器已开启！！(≧∇≦)/"

    fun isServerNotifyCrashEnabled(): Boolean =
        isServerNotifyEnabled() && plugin.config.getBoolean("server-notify.crash.enabled", true)

    fun serverNotifyCrashMessage(): String =
        plugin.config.getString("server-notify.crash.message", "[状态]\n服务器....") ?: "[状态]\n服务器...."

    /**
     * 命令执行器配置。
     * 可取值：Auto（自动检测，推荐）/ Hybrid（混合控制台）/ 其他（模拟控制台）。
     */
    fun commandSender(): String = plugin.config.getString("command-sender", "Auto") ?: "Auto"

    fun chatFormat(): ChatFormat = ChatFormat(
        plugin.config.getString("chat-format.from-game", "[游戏] <{name}> {message}") ?: "[游戏] <{name}> {message}",
        plugin.config.getString("chat-format.from-group", "[§bQQ§r] {name}: {message}") ?: "[§bQQ§r] {name}: {message}",
        plugin.config.getBoolean("chat-format.post-chat", true),
        plugin.config.getString("chat-format.start-with", "") ?: "",
 // 发送者名称来源（1.7.5.8）：group=群昵称（默认，事件返回的群内显示昵称）/
 // player=绑定玩家名（未绑定回退群昵称）；未知取值回退 group
        (plugin.config.getString("chat-format.group-sender-name", "group") ?: "group").trim().lowercase(),
    )

    fun playerEventFormat(): PlayerEventFormat = PlayerEventFormat(
        plugin.config.getBoolean("player-events.join.enabled", true),
        plugin.config.getString("player-events.join.format", "[游戏] {name} 加入了服务器") ?: "[游戏] {name} 加入了服务器",
        plugin.config.getBoolean("player-events.quit.enabled", true),
        plugin.config.getString("player-events.quit.format", "[游戏] {name} 离开了服务器") ?: "[游戏] {name} 离开了服务器",
 // 死亡播报（参考 HuHoBot，配置键与其一致；默认关闭）
        plugin.config.getBoolean("player-events.death.enabled", false),
        plugin.config.getString("player-events.death.format", "[游戏] {message}") ?: "[游戏] {message}",
    )

    fun markdownFiles(): Map<String, String> {
        val files = LinkedHashMap<String, String>()
        val section: ConfigurationSection = plugin.config.getConfigurationSection("markdown") ?: return files
        for (key in section.getKeys(false)) {
            val value = section.getString(key) ?: continue
            files[key] = value
        }
        if (!files.containsKey("queryOnline")) {
            files["queryOnline"] = "online.md"
        }
        return files
    }

    fun motd(): Motd = Motd(
        plugin.config.getString("motd.server-ip", "") ?: "",
        plugin.config.getInt("motd.server-port", 0),
        plugin.config.getString(
            "motd.api",
            "http://motd.txssb.cn/api/app_img?ip={ip}&port={port}&dark=true&lang=zh-CN",
        ) ?: "",
        plugin.config.getBoolean("motd.post-img", true),
 // 默认改为 false —— /查在线 默认输出渲染图片（毛玻璃卡片 + 真实皮肤头像）；
        // true = Markdown 文字列表（旧用户已保存的配置不受影响，行为不变）
        plugin.config.getBoolean("motd.use-markdown", false),
    )

    fun filterRegexList(): List<String> = plugin.config.getStringList("filter-regex")

    fun adminMode(): AdminMode = AdminMode.from(plugin.config.getString("admin.mode", "qq")) ?: AdminMode.QQ

    fun adminOpenIds(): List<String> = plugin.config.getStringList("admin.openids")

    /** QQ 快捷面板管理员联动（1.7.5.3，admin.panel-admin-linkage，默认开）。 */
    fun panelAdminLinkageEnabled(): Boolean = plugin.config.getBoolean("admin.panel-admin-linkage", true)

    fun fullForwardingByDefault(): Boolean = plugin.config.getBoolean("features.full-amount", false)

    /**
     * 设置全量转发总开关（features.full-amount）并写回配置文件
     * （1.7.5 覆盖第三轮：/全量 命令改切总配置，群级独立开关已删）。
     *
     * 写回走 plugin.saveConfig()——Spigot 端 1.18.1+ 平台自带注释往返、
     * NeoForge 端兼容层走注释保留保存（差分合并），用户注释不丢。
     * 内存值随 config.set 即时生效（fullForwardingByDefault 实时读取）。
     *
     * @return 是否写入成功（失败时内存值已生效但重启可能还原）
     */
    fun setFullAmountEnabled(enabled: Boolean): Boolean {
        return try {
            plugin.config.set("features.full-amount", enabled)
            plugin.saveConfig()
            plugin.logger.info("全量转发总开关已切换为：${if (enabled) "开启" else "关闭"}（已写回 config.yml，对全部配置群生效）")
            true
        } catch (t: Throwable) {
            plugin.logger.warning("全量转发开关写回配置文件失败（本次已生效，重启后可能还原）: ${t.message}")
            false
        }
    }

    /** 命令开关表。 */
    fun commandSwitches(): Map<String, Boolean> {
        val switches = LinkedHashMap<String, Boolean>()
        val section: ConfigurationSection = plugin.config.getConfigurationSection("commands") ?: return switches
        for (key in section.getKeys(false)) {
            val value = section.get(key)
            switches[key] = if (value is Boolean) value else true
        }
 // 命令别名共享主命令开关（别名自身不注册进 commands 段，
        // 关闭主命令时别名一并失效，快捷菜单也不展示别名按钮）
        for ((alias, main) in COMMAND_ALIASES) {
            if (!switches.containsKey(alias)) {
                switches[alias] = switches[main] ?: true
            }
        }
        return switches
    }

    fun auditBaseUrl(): String? = null

    fun auditApiKey(): String? = null

    fun auditModel(): String = "gpt-4o-mini"

    /** 自定义命令列表。 */
    fun customCommands(): List<CustomCommandDetail> {
        val commands = ArrayList<CustomCommandDetail>()
        val rawList: List<Map<*, *>> = plugin.config.getMapList("custom-commands")
        for (map in rawList) {
            if (map == null) continue
            val key = map["key"]?.toString()
            val command = map["command"]?.toString()
            val permission = map["permission"]?.toString()?.toIntOrNull() ?: 0
            if (key.isNullOrEmpty() || command.isNullOrEmpty()) continue
            commands.add(CustomCommandDetail(key, command, permission))
        }
        return commands
    }

    companion object {
        private const val CONFIG_VERSION_PATH = "config-version"

        // 升 48——1.7.5.7 覆盖轮删旧键（配置注释同步去版本字样）：
        // 删除旧键 ai.reply-quote（1.7.5.5 引入的开关，1.7.5.6 起已被
        // ai.reminder-mode 三选一取代）：升级时若 reminder-mode 仍为空且旧键
        // 为 false，先把 reminder-mode 归一写为 none（保留用户「不引用」的
        // 偏好），其余情况归一写为 quote（空串语义本就是引用）；随后从用户
        // 配置里删除 reply-quote 键。新装配置不再包含该键。
        // 升 47——1.7.5.6 覆盖轮新增两键（只加新键+默认值）：
        // ai.reminder-mode（AI 回复提醒方式三选一，可选取值 at/quote/none
        // 或中文 艾特/引用/无；at=每次回复开头艾特发送人，quote=引用原消息，
        // none=既不艾特也不引用；留空默认引用）；
        // bot.button-cooldown-seconds（QQ 按钮互动冷却秒数，默认 5——同一用户
        // 两次点击按钮的最小间隔，间隔内连点回应「操作频繁」不执行；0=关闭）。
        // 升 45——1.7.5.3 新增一键（只加新键+默认值，不做任何旧值迁移）：
        // admin.panel-admin-linkage（QQ 快捷面板管理员联动，默认 true——
        // admin.mode=qq/both 时自动把快捷面板管理类指令条目设为仅群主/
        // 群管理员可见可点，与 /菜单 管理按钮平台门禁同一口径；
        // mode=config 或联动关闭时面板全员可见，命令按名单判定；
        // /hbkrg reload 后随面板重同步生效）。
        // 升 46——1.7.5.5 新增 AI 艾特接口开关（ai.at-enabled，语义见上）。
        // 升 44——1.7.5.2 kick-message 单行字符串写法支持（无新增键）：
        // qq-bind.kick-message 允许写成一行用 \n 分行（getStringList 为空
        // 时按 getString 拆行兜底）；两版历史默认文案（v0.1.5.0 绑定码版 /
        // 1.7.2 验证码版）升级自动收敛为「绑定码」新默认，自改文案不动。

        // 升 43——1.7.5 覆盖第三轮（无新增键，注释刷新 + 语义变更）：
        // features.full-amount 语义改为全量转发**总开关**（/全量 命令切换它
        // 并写回本文件，对全部配置群统一生效；群级独立开关已删除——
        // command-state.ini 的 [full-forwarding] 段不再读取）；commands.全量
        // 与 commands.菜单 注释同步更新（菜单按钮：无参命令点击即执行、
        // 带参命令点击复制进输入栏）。
        // 升 42——菜单按钮化注释刷新（commands 段全部命令注释改为按钮
        // 交互口径：点击即执行 / 复制到输入栏补全）。
        // 升 41——指令与消息模板键迁移：commands.帮助 → commands.菜单（/帮助
        // 替换为 /菜单，旧键值保留迁移），commands.AI对话上下文 →
        // commands.AI管理（原「AI对话上下文 开|关」与「清除当前上下文」两条
        // 命令合并为 /AI管理，开关值从原 AI对话上下文 键迁移），
        // commands.清除当前上下文 / messages.help 进废弃键清单，
        // 新增模板 menu（菜单头部文案）与 ai-manage-disabled（AI 未开启提示）。
        // 升 40——服务器开关消息默认文案去表情（shutdown 去
        // (╯︵╰,) / crash 去 ༽◺_◿༼）+ restart 语义改为「重启完毕」新文案 +
        // 新增 server-notify.start（普通开机）与 server-notify.restart-window
        // （180 秒重启判定窗口，1.7.4 第三轮旧键恢复，自废弃清单移除）；
        // 全部历史默认文案升级自动收敛，自改文案不动。
 // 升 39——意外关闭默认文案颜文字修正（去掉误写的
        // 「轨迹」二字）+ 删除重启时间窗口（server-notify.restart-window 进
        // 废弃键清单；上次正常关闭后再启动一律发开启通知，不限间隔时长；
        // 历史默认文案（含第六轮误写版）升级自动收敛，自改文案不动）。
 // 升 38——服务器开关消息三条默认文案换新
        // （server-notify.shutdown / restart / crash 的 message；历史默认
        // 文案升级自动收敛，自改值不动；无新增键）。
 // 升 37——副标题灰度改为 106（render.subtitle-card-gray
        // 默认 80 → 106 = 与项目卡 item-card-gray 相同；历史默认 40 / 56 / 80
        // 升级自动收敛，自改值不动；无新增键）。
 // 升 36——新增集群与服务器开关消息键
        // （bot.cluster.name / bot.cluster.members / commands.切换服务器 /
        // server-notify 段八键）与两个消息模板（cluster-single /
        // cluster-list-fallback）；「查询open ID」旧名开关键 8 个变体进
        // 废弃键清单（别名兜底已下线）。
 // 升 35——新增两键（只加新键+默认值，不做任何旧值迁移）：
        // qq-bind.max-players-per-qq（每个 QQ 账号最多能绑定多少个玩家，默认 1，
        // 只能等于或大于 1，配 0 / 负数按 1 处理）与多玩家数字选择机制
        // （无独立配置键：选择有效期固定 2 分钟）；新增消息模板 bind-limit /
        // select-player / select-invalid；同时 QQ 绑定开启提醒不要开白名单。
 // 升 34——副标题灰度改为 80（render.subtitle-card-gray
        // 默认 106 → 80 = 与标题框 title-card-gray 相同，回归 1.7.0 轻卡观感，
        // 用户口径「把副标题默认灰度改为 80」；历史默认 40 / 56 / 106 升级
        // 自动收敛，自改值不动；无新增键）。
 // 升 33——副标题灰度对齐项目卡（render.subtitle-card-gray
        // 默认 56 → 106 = 与 render.item-card-gray 相同，用户口径「副标题框的
        // 默认灰度换成和项目框一样的灰度」；历史默认 40 / 56 升级自动收敛，
        // 自改值不动）+ 副标题小标签配色回退 1.7.0 查在线同款固定灰
        // 0x9FB3C8（五轮自适应双色 / 1.7.3.1 连续对比度配色整体回退，无配置变化）。
 // 升 32——新增三键（render.custom-render-threads 段：
        // enabled 默认 false / render-threads 默认 2 / avatar-threads 默认 8，
        // 自定义图片渲染线程开关开启后生效，改后需重启）+ 副标题灰度旧默认
        // 40 → 56 一次性收敛（初版太浅看不出卡片底色；用户自改值不动）+
        // 渲染视觉修正（标题 padding / 徽标灰度 +36 / 性能卡等距 / 综合状态 /
        // 渲染耗时副标题格，无配置变化）；
 // 升 31——新增三键（只加新键+默认值，不做任何旧值迁移）：
        // render.subtitle-card-gray（副标题灰度，默认 40 = 玩家名片玩家名称项 +
        // 五张卡片副标题条（服务器 / 更新时间 / 状态），明显低于标题卡 80 与
        // 项目卡 106）、leaderboard.page-size（排行榜单页上限，全局：在线
        // 排行榜 / 财富榜共用，默认 20，旧版硬编码 15）、同时标题卡改全宽化
        // （模糊 + 灰度 + 圆角边缘）与今日在线跨天误报修复（无配置变化）；
 // 升 29——新增四键（只加新键+默认值，不做任何旧值迁移）；
        // 同版追加注释精简刷新：升 30——无新增键，仅以新版 config.yml
        // 模板重写全部注释（删版本标注、简化表述），用户已改值一律原样保留；
        // 仅标题框旧默认 96 → 80 一次性收敛（接线改版，见重建器与 reload）
        // image-download.enabled（图片下载功能总开关，默认 true）、
        // render.title-card-gray（标题框黑度，默认 80 = 四张卡片顶部标题区，
        // 比项目卡片略浅）、
        // render.item-card-gray（项目卡片黑度，默认 106 = 中间偏深值）、
        // qq-bind.checkin.points-reward（签到点券奖励，默认 0 关闭）
 // 升 28——无新增键纯注释刷新（卡片背景图说明段补 /hbkrg bpng
        // 图片包下载与 /hbkrg bpngsz 一键切换的用法说明）
        private const val CURRENT_CONFIG_VERSION = 49

        /**
         * 历史路径搬家表（旧路径 → 新路径，1.6.0 版本 17 迁移；
         * 配置重建器与下方 reload 迁移逻辑共用同一张表）。
         */
        internal val LEGACY_PATH_MOVES = listOf(
            "qq-bind.render.gc-after-render" to "render.gc-after-render",
            "qq-bind.render.result-cache-seconds" to "render.result-cache-seconds",
            "qq-bind.render.info-card-builtin-background" to "render.info-card-builtin-background",
            "qq-bind.render.online-list-builtin-background" to "render.online-list-builtin-background",
            "qq-bind.leaderboard.top" to "leaderboard.top",
            "qq-bind.leaderboard.builtin-background" to "leaderboard.builtin-background",
            // 升 41：指令替换与合并（旧键值迁移到新键，见 CURRENT_CONFIG_VERSION 注释）
            "commands.帮助" to "commands.菜单",
            "commands.AI对话上下文" to "commands.AI管理",
            "messages.help" to "messages.menu",
        )

        /**
         * 历史废弃键清单（配置重建器回填时跳过；与 reload 里清理列表一致）。
 * 新增 render.performance.style（液态玻璃样式已移除，
         * 卡片固定原版毛玻璃深色；透明度统一走 render.global-opacity）。
         */
        internal val REMOVED_CONFIG_KEYS = listOf(
            "agent", "audit", "whitelist", "query-online", "motd.text", "commands.个人信息",
            "qq-bind.leaderboard.random-background", "ai.context-global", "render.gc-after-render",
            "render.idle-gc-hint",
 // 升 48：AI 回复引用开关已并入 ai.reminder-mode 三选一（升级时
            // 先归一用户偏好再删键，见 reload 迁移逻辑）
            "ai.reply-quote",
            "render.performance.show-tps", "render.performance.show-mspt",
            "render.performance.show-memory", "render.performance.show-cpu",
            "render.performance.show-online",
            "render.performance.style",
 // /在线服务器 与 /motd 命令已删除（开关与模板映射一并清理）
            "commands.在线服务器", "commands.motd", "messages.server-status",
            "messages.motd-usage", "messages.motd-query-failed", "messages.motd-offline",
            "messages.motd-error", "messages.motd-status",
 // 「查询open ID」旧名别名已下线，旧配置里
            // 写死的开关键一并清理（现名 commands.查信息）
            "commands.查询open ID", "commands.查询Open ID", "commands.查询open id",
            "commands.查询Open id", "commands.查询openID", "commands.查询OpenID",
            "commands.查询openid", "commands.查询OPENID",
 // 本地头像获取链路移除（统一在线获取）；
            // 原版遮罩样式与整图透明度移除（只保留毛玻璃，模糊度改 blur-strength）
            "render.avatar-fetch-priority", "render.background-style", "render.global-opacity",
            // 升 41：原「清除当前上下文」命令已并入 /AI管理（开关值从
            // commands.AI对话上下文 迁移到 commands.AI管理，本键不再单独存在）
            "commands.清除当前上下文",
 // 注：server-notify.restart-window 已恢复
            //（180 秒重启判定窗口，见上方 CURRENT_CONFIG_VERSION 注释）
        )

        /**
         * 支持开关的群命令名单（1.5.2：查信息更名对应个人信息卡片命令，新增 查询open ID；
 * 新增金币榜；更名财富榜；移除 在线服务器 与 motd；
 * 查询open ID 更名为 查信息，查信息（玩家统计卡片）
 * 更名为 玩家名片；新增 切换服务器（多服务器
         * 集群指令），旧名「查询open ID」的别名兜底全部下线）。
         */
        private val COMMAND_NAMES = listOf(
            "查信息", "玩家名片", "绑定", "重新绑定", "查在线", "查性能", "在线排行榜", "财富榜", "发信息",
            "执行命令", "执行", "管理员执行", "全量", "菜单",
            "AI管理", "黑名单", "解除黑名单", "签到", "切换服务器",
        )

        /**
         * 命令别名表（别名 → 主命令）。
         * 别名与主命令共用 commands 段里的开关；别名不出现在快捷指令菜单与
         * commands 配置段（dispatch 层按别名命中后仍查主命令开关值）。
 * 「在线」为 /查在线 的输入别名；
 * 「金币榜」为 /财富榜 的旧名别名——1.7.4 移除（更名已两个
         * 大版本周期，配置迁移 1.6.2 时已完成，官网与配置注释同步清理）；
 * 「查询open ID」及其 8 个大小写变体别名全部
         * 移除（用户指令「那些旧版指令兜底都去掉」，指令只认现名）。
         */
        private val COMMAND_ALIASES = linkedMapOf(
            "在线" to "查在线",
        )

        /**
 * 全部消息模板 ID。默认文件名 = <ID>.md（位于数据目录 Markdown/ 下，
         * 首次启动自动释放默认模板；messages.<ID> 可改为自定义文件名）。
         * 与 MessageTemplates.TEMPLATE_IDS 保持一致。
         */
        private val MESSAGE_TEMPLATE_IDS = listOf(
            // 通用
            "command-disabled", "no-admin-permission", "query-too-frequent", "server-busy",
            "image-send-failed", "manager-not-ready", "manager-not-ready-bind",
            "manager-not-ready-retry", "manager-not-ready-bind-retry", "qq-unknown",
 // server-status 与 motd-* 五个模板已随 /在线服务器、/motd 命令移除
            // QQ 绑定
            "bind-usage", "bind-usage-fallback", "bind-disabled", "bind-error", "bind-success", "bind-invalid",
            "bind-limit", "select-player", "select-invalid",
            // 签到
            "checkin-disabled", "checkin-not-bound", "checkin-data-error", "checkin-time-error",
            "checkin-already", "checkin-success-no-economy", "checkin-reward-invalid",
            "checkin-success", "checkin-pay-error",
            // 查信息
            "info-bind-disabled", "info-closed", "info-not-bound", "info-target-not-bound", "info-render-failed",
            // 查在线
            "online-data-unavailable", "online-render-failed",
 // 查性能
            "performance-render-failed",
            // 在线排行榜
            "leaderboard-no-data", "leaderboard-render-failed",
            // 金币排行榜
            "coins-leaderboard-no-economy", "coins-leaderboard-no-data", "coins-leaderboard-render-failed",
            // 查询 OpenID
            "openid-self", "openid-lookup",
 // 多服务器集群（cluster-single 单服提示 /
            // cluster-list-fallback 按钮不可用时的纯文本服务器列表）
            "cluster-single", "cluster-list-fallback",
            // 发信息
            "forward-disabled",
            // AI 对话
            "ai-context-usage", "ai-context-on", "ai-context-off", "ai-context-cleared",
            "ai-manage-disabled", "ai-chat-empty", "ai-chat-error",
            // 黑名单
            "blacklist-empty", "blacklist-list", "blacklist-added-kicked", "blacklist-added",
            "blacklist-removed", "unblacklist-empty", "unblacklist-list",
            // 重新绑定
            "rebind-success", "rebind-not-bound",
            // 菜单
            "menu",
            // 命令执行
            "invalid-params", "bridge-error", "sent-request", "execute-failed", "full-amount-toggled",
        )

        /** 全部默认配置值（用于补齐缺失键；1.6.7.2：from-group 新默认带 §b 染色）。 */
        private val DEFAULT_VALUES: Map<String, Any> = linkedMapOf(
            CONFIG_VERSION_PATH to 24,
            "bot.app-id" to "",
            "bot.secret" to "",
            "bot.name" to "KERONGPenguin",
            "bot.groups" to emptyList<Any>(),
 // 多服务器集群（多台服务器共用同一 QQ 机器人
            // 凭据时配置；name = 本服在按钮上显示的名称，members = 全部成员
            // 名称列表[含自己]，各服必须完全一致；未配置 / 少于 2 个成员时
            // 集群逻辑全部旁路，单服行为零变化）
            "bot.cluster.name" to "",
            "bot.cluster.members" to emptyList<Any>(),
            "bot.button-cooldown-seconds" to 5,
 // 服务器开关消息（配置口径与 HuHoBot 的
 // server-notify 段一致，默认全部开启）；默认文案
            // 去表情 + 新增 start 段（普通开机）与 restart-window（180 秒
            // 重启判定窗口）
            "server-notify.enabled" to true,
            "server-notify.restart-window" to 180,
            "server-notify.shutdown.enabled" to true,
            "server-notify.shutdown.message" to "[状态]\n服务器已正常关闭",
            "server-notify.restart.enabled" to true,
            "server-notify.restart.message" to "[状态]\n服务器重启完毕，已正常运行(o‿∩)",
            "server-notify.start.enabled" to true,
            "server-notify.start.message" to "[状态]\n服务器已开启！！(≧∇≦)/",
            "server-notify.crash.enabled" to true,
            "server-notify.crash.message" to "[状态]\n服务器....",
 // 断线重连过程提示开关（起默认防刷屏：控制台只输出本轮第 1 次 /
            // 最后一次 / 成功 / 超时里程碑，中间过程写文件；false = 里程碑过程提示也不输出；
            // 重连成功与重连超时后的 /hbkrg chs 恢复指引不受此开关影响，始终输出）
            "bot.reconnect-notify" to true,
 // 更新检查开关（启动后异步访问官网版本文件，发现新版本时控制台提示
            // 版本号 / 下载页 / 简要日志；检测失败仅一句话提示，细节进日志）
            "update-check.enabled" to true,
 // 更新检查镜像源（官网域名不可达时回退：默认官网服务器 IP 直连；
            // 填 off 关闭镜像回退只试主源；也可填自建镜像地址）
            "update-check.mirror-url" to "",
            "serverName" to "KERONGPenguin",
            "chat-format.from-game" to "[游戏] <{name}> {message}",
 // 新默认仅染 QQ 字样（§b 青色），§r 重置后再接玩家名
            "chat-format.from-group" to "[§bQQ§r] {name}: {message}",
            "chat-format.post-chat" to true,
            "chat-format.start-with" to "#",
 // 发送者名称来源（1.7.5.8）：group=群昵称（默认）/ player=绑定玩家名（未绑定回退群昵称）
            "chat-format.group-sender-name" to "group",
            "player-events.join.enabled" to true,
            "player-events.join.format" to "[游戏] {name} 加入了服务器",
            "player-events.quit.enabled" to true,
            "player-events.quit.format" to "[游戏] {name} 离开了服务器",
            "markdown.queryOnline" to "online.md",
 // query-online.image-output 已移除（markdown 关闭时自动启用图片输出）
            // 默认 Auto：自动检测混合端，纯 Spigot/Paper 不再误提示“已启用混合控制台”
            "command-sender" to "Auto",
            "motd.server-ip" to "",
            "motd.server-port" to 0,
            "motd.api" to "http://motd.txssb.cn/api/app_img?ip={ip}&port={port}&dark=true&lang=zh-CN",
 // motd.text（文本模板）已移除
            "motd.post-img" to true,
 // 默认 false = /查在线 走渲染图片输出（旧用户已保存值不受影响）
            "motd.use-markdown" to false,
            "filter-regex" to emptyList<Any>(),
            "admin.mode" to "qq",
            "admin.openids" to emptyList<Any>(),
            "features.full-amount" to false,
 // 图片下载功能总开关（关闭后 /hbkrg bpng 列表与下载入口
            // 直接回复「管理员已关闭图片下载功能」；bpngsz 不受影响）
            "image-download.enabled" to true,
            "custom-commands" to emptyList<Any>(),
            "qq-bind.enabled" to false,
            "qq-bind.personal-info" to true,
            "qq-bind.code-expire-minutes" to 10,
            "qq-bind.code-length" to 5,
 // 每个 QQ 账号最多能绑定多少个玩家（默认 1 = 旧版单绑定行为；
            // 只能等于或大于 1，配 0 / 负数按 1 处理；名下多个玩家时查信息 /
            // 查询open ID / 重新绑定 / 签到等指令会先让用户数字选择）
            "qq-bind.max-players-per-qq" to 1,
            "qq-bind.checkin.enabled" to false,
            "qq-bind.checkin.prefix" to "[签到]",
            "qq-bind.checkin.reward" to 100.0,
 // 签到点券奖励（经 PlayerPoints 发放；0 = 不发点券，
            // 未装 PlayerPoints 时优雅跳过）
            "qq-bind.checkin.points-reward" to 0,
 // 渲染设置迁至顶层 render 段（查在线 / 卡片渲染不依赖 QQ 绑定）；
 // gc-after-render 已移除（改确定性 flush 释放，见 CardRenderPool）
            "render.result-cache-seconds" to 60,
 // 头像磁盘缓存有效期（小时；默认 3，支持小数，0 = 关闭）
            "render.avatar-cache-hours" to 3,
 // 渲染空闲内存释放阈值（分钟，支持小数，0 = 关闭）
            "render.idle-release-minutes" to 15,
 // 图片渲染命令的用户冷却秒数（旧版硬编码 5 秒；0 = 关闭）
            "render.command-cooldown-seconds" to 5,
 // 全部图片渲染的内置背景开关（默认 true = 随 JAR 分发的深灰底图，
            // 关闭后随机用 img/ 目录图片，img 无可用图片时自动回退内置图）
            "render.info-card-builtin-background" to true,
            "render.online-list-builtin-background" to true,
 // 查性能卡片（/查性能，命令总开关 commands.查性能）；
 // 指标固定四项（TPS / MSPT / 内存 / CPU），show-* 单项显隐已移除
            "render.performance.builtin-background" to true,
 // 性能数据缓存默认 5 → 60（渲染慢的服推荐保持开启）
            "render.performance.cache-seconds" to 60,
 // 查性能在线人数卡的最大人数来源
            // （real = 服务器真实最大人数（默认）；motd = 其他插件修改过的展示人数，
            // 即 MOTD 里显示的人数，取自最近一次玩家 ping 服务器时的值，
            // 无人 ping 过时回退真实值；模组端无此事件恒用真实值）
            "render.performance.max-players-source" to "real",
 // 毛玻璃模糊度（0-100，越大越模糊，0 = 不模糊；只模糊卡片
            // 背后的背景，卡片外保持清晰）
            "render.blur-strength" to 50,
 // 卡片底色黑度（0-255 半透明黑 alpha）；title =
 // 四张卡片顶部标题区（默认 80 比项目卡片略浅，前这些区域
            // 硬编码半透明白调不动）；item = 列表容器（玩家名片统计面板 +
            // 查在线 / 排行榜 / 查性能容器，默认 106 = 旧值 120 与浅值 64
            // 中间偏深）
            "render.title-card-gray" to 80,
            "render.item-card-gray" to 106,
            // 副标题灰度（0-255 半透明黑 alpha）：玩家名片玩家名称项 +
            // 五张卡片副标题条（服务器 / 更新时间 / 渲染耗时）；
            // 默认 106 与项目卡片相同
            "render.subtitle-card-gray" to 106,
 // 自定义图片渲染线程（高级，默认关闭 = 自动模式：
            // 渲染池 CPU 核数一半 1~4 线程、头像下载池 8 线程）；开启后线程数
            // 用下方两键；线程池首次渲染时创建，改后需重启生效；⚠ 线程数
            // 越大 CPU 争抢越强，不建议超过物理核数一半
            "render.custom-render-threads.enabled" to false,
            "render.custom-render-threads.render-threads" to 2,
            "render.custom-render-threads.avatar-threads" to 8,
 // 在线排行榜（从 qq-bind.leaderboard 迁至顶层）
            "leaderboard.top" to 15,
 // 排行榜单页上限（全局：在线排行榜 / 财富榜共用；
            // 超出自动分多张图发送；旧版硬编码 15）
            "leaderboard.page-size" to 20,
            "leaderboard.builtin-background" to true,
 // 金币排行榜（配置与在线排行榜一致）
            "coins-leaderboard.top" to 15,
            "coins-leaderboard.builtin-background" to true,
 // 经济别名（留空 = 默认「金币」；填写后所有输出的金币字样替换为该名称）
            "economy-alias" to "",
 // 变量获取信息（查信息卡片的指定项目改读 PlaceholderAPI 变量输出；
            // 默认关闭；变量留空 / 解析失败自动回退插件自带数据）；
 // 新增 player-name（玩家名，查信息 / 排行榜生效）、
            // tps（查性能生效）与 custom-items（自定义玩家名片项目：段名=项目ID，
            // 子键 项目名称 / 获取变量；正常返回才展示）
            "variable-fetch.enabled" to false,
            "variable-fetch.total-playtime" to "",
            "variable-fetch.today-playtime" to "",
            "variable-fetch.coins" to "",
            "variable-fetch.points" to "",
            "variable-fetch.title" to "",
            "variable-fetch.player-name" to "",
            "variable-fetch.tps" to "",
            "variable-fetch.custom-items" to java.util.LinkedHashMap<String, Any>(),
            "ai.enabled" to false,
            "ai.server-enabled" to false,
            "ai.server-prefix" to "ai",
            "ai.server-output-prefix" to "[AI]",
            "ai.qq-output-prefix" to "[AI]",
            "ai.base-url" to "https://api.deepseek.com",
            "ai.api-key" to "",
            "ai.model" to "deepseek-chat",
            "ai.system-prompt" to "你是一个友好的游戏群助手，请简洁回答。",
            "ai.url-auto-append" to true,
            "ai.context-limit" to 6,
            "ai.at-enabled" to true,
            "ai.reminder-mode" to "",
        ).apply {
 // 消息模板映射（每条回复 → Markdown 模板文件名，详见 config.yml 注释）
            for (id in MESSAGE_TEMPLATE_IDS) {
                put("messages.$id", "$id.md")
            }
            for (name in COMMAND_NAMES) {
                put("commands.$name", true)
            }
        }
    }
}
