package cn.kerong.bot.events.commands

import cn.kerong.bot.ClusterState
import cn.kerong.bot.KERONG
import cn.kerong.bot.QClient
import cn.kerong.penguin.spigot.qqbind.MessageTemplates
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import cn.kerong.penguin.spigot.stats.OnlineListService
import cn.kerong.penguin.spigot.stats.QueryInfoService
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.qqpd.User
import java.util.concurrent.CompletableFuture

/**
 * 公开命令：帮助 / 玩家名片 / 查信息 / 查在线 / 发信息 / 黑名单 / 重新绑定 / AI 上下文 / 切换服务器等。
 *
 * 1.7.4 覆盖版：指令改名（与 HuHoBot 语义对齐）——
 * - 旧「查询open ID」改名为「查信息」（查 OpenId，配置 bot.groups 用）;
 * - 旧「查信息」（玩家统计卡片）改名为「玩家名片」。
 *
 * 1.7.4 第三轮覆盖版：
 * - 旧版指令兜底全部下线（用户指令：「那些旧版指令兜底都去掉」）——
 *   「查询open ID」及其大小写变体不再是 /查信息 的隐藏别名，指令只认现名；
 * - 新增 /切换服务器（多服务器集群，见 [ClusterState]；与 HuHoBot 的
 *   多服务器概念一致）。
 *
 * 1.6.0：全部文本回复改走消息模板系统（[MessageTemplates]）——
 * 每条回复对应数据目录 Markdown/ 下一个 .md 模板文件（配置 messages 段可换文件），
 * 默认模板内容与 1.5.7.3 硬编码文本一字不差；模板渲染出口统一应用经济别名
 * （economy-alias）。
 *
 * 1.5.7.4：/查在线 新增别名 /在线（不出现在快捷指令菜单，
 * 命令开关与 commands.查在线 共用）。
 *
 * 1.5.3：/查信息（现玩家名片）@成员 查询该成员绑定的玩家；/查询OpenID（现查信息）反查 QQ 账号。
 *
 * 1.6.9.3：移除 /在线服务器 与 /motd 两条命令（用户需求；服务器地址
 * 改由服主自行在群里公告，插件侧不再提供查询）。
 */
class PublicCommands : CommandSupport() {

    /**
     * /玩家名片 —— 查询 QQ 绑定玩家的生涯统计卡片（渲染为图片发送，不 @ 提及）。
     * 1.5.2 前名为 /个人信息，1.5.2-1.7.4 初版名为 /查信息，1.7.4 覆盖版更名 /玩家名片
 * （「查信息」名字让位给原 /查询open ID 的 OpenId 查询）。未绑定则提示先完成绑定。
     *
     * 1.5.3：支持 `/玩家名片 @成员` 查询该成员绑定的玩家信息（
     * 提及数据来自消息 mentions 字段而非命令参数——@ 片段在命令分发前已被剥离）；
     * 未绑定时提示该成员先完成绑定。
     *
     * 开关位于 QQ 绑定配置节：qq-bind.personal-info（默认开启）；
     * 若 QQ 绑定功能（qq-bind.enabled）关闭，本命令同时关闭。
     * 提示文本模板：info-bind-disabled / info-closed / qq-unknown（messages 段）。
     */
    @Commands("玩家名片")
    fun queryPersonalInfo(plugin: KERONG, event: GroupMessageEvent, params: String) {
        val bindManager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            null
        }
        if (bindManager == null) {
            MessageTemplates.sendDirect(event, "manager-not-ready-bind-retry")
            return
        }
        if (!bindManager.isEnabled) {
            MessageTemplates.sendDirect(event, "info-bind-disabled")
            return
        }
        if (!bindManager.isPersonalInfoEnabled) {
            MessageTemplates.sendDirect(event, "info-closed")
            return
        }
        val qq = try {
            userId(event)
        } catch (_: Throwable) {
            "<unknown>"
        }
        if (qq.isEmpty() || qq == "<unknown>") {
            MessageTemplates.sendDirect(event, "qq-unknown")
            return
        }
        // 1.5.3：若 @ 了其他成员则查询该成员的绑定玩家（排除机器人自身）
        val mentioned = extractMentionedQqInfo(event)
        if (mentioned != null) {
            val targetId = mentioned.firstOrNull() ?: return
            val targetName = mentioned.getOrNull(1) ?: targetId
            // 1.7.4：目标成员名下绑定多个玩家时，由发起者点击按钮（或回复数字）选择要查询的玩家
            cn.kerong.bot.events.PlayerSelection.requireBoundPlayer(
                event, bindManager, targetId,
                notBoundTemplate = "info-target-not-bound",
                notBoundVars = mapOf("name" to targetName),
            ) { selected ->
                QueryInfoService.handle(plugin, event, qq, targetId, targetName, selected)
            }
            return
        }
        // 卡片渲染与数据汇总在服务内异步完成；1.7.4：自己名下绑定多个
        // 玩家时先选择（单个玩家 / 未绑定与旧版一致）
        cn.kerong.bot.events.PlayerSelection.requireBoundPlayer(
            event, bindManager, qq,
            notBoundTemplate = "info-not-bound",
        ) { selected ->
            QueryInfoService.handle(plugin, event, qq, selectedPlayer = selected)
        }
    }

    /**
     * /查信息 —— OpenId 查询与反查。
     *
     * 用法：
     * - `/查信息`：显示自己的 OpenId 与群 OpenId（用于配置 bot.groups）；
     *   未配置 bot.groups 时群里任意互动消息会自动完成绑定（见
     *   GroupMessageHandler 的自动绑定逻辑，无需手动填群 ID）；
     * - `/查信息 <玩家名>`：反查该玩家绑定的 QQ 账号（群昵称 + 该 QQ
     *   名下绑定的全部玩家；多个绑定玩家同时显示）；
     * - `/查信息 <OpenId>`：反查该 OpenId 对应的 QQ 账号（群昵称 + 绑定玩家）；
     * - `/查信息 @成员`：查看被 @ 成员的绑定信息。
     *
     * 参数分派：先按玩家名查绑定（玩家名精确匹配），命中即玩家名反查；
     * 未命中再按 OpenId 反查（玩家名 ≤16 字符且字符集受限，OpenId 更长，
     * 两者实际无重叠）。
     *
     * 1.6.5：反查结果中的 OpenId 行仅管理员可见（避免普通群成员批量收集
     * OpenId 冒充绑定）；非管理员只看到群昵称、最后活跃、绑定玩家。
     *
     * 群昵称来自插件维护的 OpenId 目录（从每条群消息的发送者与被 @ 成员积累，
     * 见 OpenIdDirectory）；机器人无法通过接口按 OpenId 查询群资料。
     * 提示文本模板：openid-self / openid-lookup / player-lookup（messages 段）。
     */
    @Commands("查信息")
    fun queryInfo(plugin: KERONG, event: GroupMessageEvent, params: String) {
        val arg = params?.trim() ?: ""
        val mentioned = extractMentionedQqInfo(event)
        if (arg.isEmpty() && mentioned == null) {
            MessageTemplates.sendDirect(event, "openid-self", mapOf(
                "openid" to userId(event),
                "group" to groupId(event),
            ))
            return
        }
        // @成员：查看被 @ 成员的绑定信息
        if (arg.isEmpty()) {
            val targetOpenId = mentioned!!.firstOrNull() ?: return
            reverseLookupOpenId(plugin, event, targetOpenId)
            return
        }
        // 带参数：先按玩家名反查（命中即返回），再按 OpenId 反查
        if (lookupByPlayerName(plugin, event, arg)) return
        reverseLookupOpenId(plugin, event, arg)
    }

    /**
     * 按玩家名反查：显示该玩家绑定的 QQ 账号与该 QQ 名下绑定的全部玩家。
     *
     * 命中绑定即回复并返回 true（调用方不再走 OpenId 反查）；
     * 未找到该玩家 / 该玩家未绑定返回 false（交由 OpenId 反查继续处理）。
     *
     * OpenId 行仅管理员可见（与 OpenId 反查同口径，防普通群成员收集
     * OpenId 冒充绑定）；该 QQ 名下绑定多个玩家时全部列出。
     * 提示文本模板：player-lookup（messages 段）。
     */
    private fun lookupByPlayerName(plugin: KERONG, event: GroupMessageEvent, playerName: String): Boolean {
        val bindManager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            null
        } ?: return false
        // 内存索引精确匹配（键为玩家名小写）→ 扫盘按 playerName 字段兜底
        val quuid = try {
            bindManager.getQuuid(playerName) ?: bindManager.findQuuidByPlayerName(playerName)
        } catch (_: Throwable) {
            null
        } ?: return false
        val openId = try {
            bindManager.readQuuidRecord(quuid)?.getString("qq", null)
        } catch (_: Throwable) {
            null
        } ?: return false
        val nickname = try {
            cn.kerong.penguin.spigot.qqbind.OpenIdDirectory.lookupNickname(openId)
        } catch (_: Throwable) {
            null
        }
        // 该 QQ 名下绑定的全部玩家（多绑定时同时显示）
        val players = try {
            bindManager.listBoundPlayersByQq(openId)
        } catch (_: Throwable) {
            emptyList()
        }
        val openidPart = if (isAdmin(plugin, event)) "QQ(OpenId): $openId\n" else ""
        MessageTemplates.sendDirect(event, "player-lookup", mapOf(
            "player" to playerName,
            "openidPart" to openidPart,
            "nickname" to (nickname ?: "未知（该账号未在机器人可见的群消息中出现过）"),
            "players" to if (players.isEmpty()) {
                "未绑定任何游戏玩家"
            } else {
                players.joinToString("、") { it.playerName }
            },
        ))
        return true
    }

    /**
     * /切换服务器 —— 多服务器集群：展示当前连接本机器人的服务器列表并
     * 按钮切换（1.7.4 第三轮覆盖版新增，开关 commands.切换服务器）。
     *
     * - 未配置集群（bot.cluster.members 少于 2 个）：回复单服提示；
     * - 已配置：输出成员列表（当前选择带 ✓ 标记）+ 内联键盘按钮，点击后
     *   **每台实例**同步记录该群的选择（互动事件广播到达），此后该群的
     *   命令 / 聊天转发 / 播报都由所选服务器处理。
     *
     * 与 HuHoBot 的多服务器体验对齐（其为主控-适配器架构，本插件为对等
     * 实例广播协调，差异见 [ClusterState] KDoc）：
     * 文档 。
     * 提示文本模板：cluster-single（messages 段）。
     */
    @Commands("切换服务器")
    fun switchServer(plugin: KERONG, event: GroupMessageEvent, params: String) {
        if (!ClusterState.isMultiServer) {
            MessageTemplates.sendDirect(event, "cluster-single", mapOf(
                "server" to ClusterState.selfServerName,
            ))
            return
        }
        // 按钮消息为引用本条指令的被动回复（不占主动消息配额）；
        // 弹出失败（旧版客户端不渲染键盘等）时回退纯文本列表
        val sent = cn.kerong.bot.events.ServerSelectButtons.sendSelector(
            event,
            "当前连接本机器人的服务器如下，点击按钮切换：",
        )
        if (!sent) {
            val current = ClusterState.selectedServer(groupId(event))
            val list = ClusterState.serverMembers.joinToString("\n") { name ->
                if (name == current) "- $name（当前）" else "- $name"
            }
            MessageTemplates.sendDirect(event, "cluster-list-fallback", mapOf(
                "list" to list,
            ))
        }
    }

    /**
     * 反查 OpenId 对应的 QQ 账号：群昵称 + 最后活跃 + 绑定玩家。
     * 绑定查找（磁盘扫描）在当前消息线程完成，规模小可接受。
     * 名下绑定多个玩家时全部列出（不再弹选择）。
     *
     * 1.6.5：OpenId 行仅管理员可见——非管理员查询时 {openidPart} 为空
     * （模板整行隐藏），只展示群昵称 / 最后活跃 / 绑定玩家，防止 OpenId
     * 被普通群成员收集冒用（OpenId 是配置 admin.openids 与冒充绑定的凭证）。
     */
    private fun reverseLookupOpenId(plugin: KERONG, event: GroupMessageEvent, openId: String) {
        val bindManager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            null
        }
        val nickname = try {
            cn.kerong.penguin.spigot.qqbind.OpenIdDirectory.lookupNickname(openId)
        } catch (_: Throwable) {
            null
        }
        val lastSeen = try {
            cn.kerong.penguin.spigot.qqbind.OpenIdDirectory.lookupLastSeenText(openId)
        } catch (_: Throwable) {
            null
        }
        // {lastSeenPart}：有记录时为「\n最后活跃: xxx」，无记录时为空（行不显示）
        val lastSeenPart = if (lastSeen != null) "\n最后活跃: $lastSeen" else ""
        // {openidPart}（1.6.5）：管理员查询时为「OpenId: xxx\n」，非管理员为空（行不显示）
        val openidPart = if (isAdmin(plugin, event)) "OpenId: $openId\n" else ""
        fun renderLookup(playerText: String) {
            MessageTemplates.sendDirect(event, "openid-lookup", mapOf(
                "openidPart" to openidPart,
                "nickname" to (nickname ?: "未知（该账号未在机器人可见的群消息中出现过）"),
                "lastSeenPart" to lastSeenPart,
                "player" to playerText,
            ))
        }
        // 名下绑定的玩家全部显示（多个绑定时同时列出，不弹选择）
        val players = try {
            bindManager?.listBoundPlayersByQq(openId) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        renderLookup(
            if (players.isEmpty()) "未绑定任何游戏玩家"
            else players.joinToString("、") { it.playerName }
        )
    }

    /** /发信息 <内容> —— 发送消息到游戏内。转发关闭时回复模板 forward-disabled。 */
    @Commands("发信息")
    fun sendGameMessage(plugin: KERONG, event: GroupMessageEvent, params: String) {
        if (params.trim().isEmpty()) return
        val content = plugin.auditText(params)
        if (plugin.chatFormat.postChat) {
            // 使用发送者 QQ 名称（而非 OpenId）
            var senderName = try {
                userId(event)
            } catch (_: Throwable) {
                ""
            }
            try {
                val contact = event.sender
                if (contact?.username != null) senderName = contact.username
            } catch (_: Throwable) {
            }
            plugin.broadcastMessage(plugin.formatGroupMessage(senderName, content))
        } else {
            MessageTemplates.sendPlain(event, "forward-disabled")
        }
    }

    /**
     * /查性能 —— 服务器性能监控卡片（1.6.7 新增，渲染图片发送）。
     *
     * 指标：TPS / MSPT / JVM 堆内存 / 进程 CPU（1.6.7.1 起固定四项，
     * 不再展示在线人数——查在线已有专门卡片；命令总开关
     * commands.查性能，默认开，关闭后命令失效、快捷菜单按钮同步消失）。
     *
     * 输出方式固定为渲染图片（性能数据不适合文字列表）；冷却与在线列表
     * 共用 render.command-cooldown-seconds（独立计数）。
     */
    @Commands("查性能")
    fun queryPerformance(plugin: KERONG, event: GroupMessageEvent, params: String) {
        val qq = try {
            userId(event)
        } catch (_: Throwable) {
            "<unknown>"
        }
        val cooldownKey = if (qq.isEmpty() || qq == "<unknown>") "group:${groupId(event)}" else qq
        cn.kerong.penguin.spigot.stats.PerformanceService.handle(plugin, event, cooldownKey)
    }

    /**
     * /查在线 —— 查询在线玩家列表：markdown 开启走模板，关闭自动渲染图片（1.5.1）。
     *
     * 1.6.7：motd.use-markdown 默认值改为 false —— 新装服务器 / 查在线 默认
     * 输出渲染图片（毛玻璃卡片 + 真实皮肤头像）；想要简洁文字列表的把
     * motd.use-markdown 改回 true 即可（旧用户已保存的配置不受影响）。
     *
     * 1.5.7.4：新增别名 /在线（更短的输入变体，与 /查在线 完全同一行为）；
     * 别名不出现在 QQ 快捷指令菜单（菜单仍只展示 /查在线 按钮），
     * 命令开关与 commands.查在线 共用（关闭 查在线 时 /在线 一并失效）。
     */
    @Commands("查在线", "在线")
    fun queryOnline(plugin: KERONG, event: GroupMessageEvent, params: String) {
        // 1.5.1：移除 text 模板回退与 query-online.image-output 配置，行为固定二选一：
        // motd.use-markdown 开启且模板文件存在 → Markdown 模板输出；
        // 关闭（或模板缺失）→ 自动改用渲染图片输出（见 OnlineListService）。
        val template = if (plugin.motd.useMarkdown) plugin.markdown("queryOnline") else null
        if (template == null) {
            val qq = try {
                userId(event)
            } catch (_: Throwable) {
                "<unknown>"
            }
            val cooldownKey = if (qq.isEmpty() || qq == "<unknown>") "group:${groupId(event)}" else qq
            OnlineListService.handle(plugin, event, cooldownKey)
            return
        }
        val online = plugin.onlineList
        val motd = plugin.motd
        val players = online.joinToString("\n") { "${online.indexOf(it) + 1}. **$it**" }
        var imgUrl = motd.api
            .replace("{ip}", motd.serverIP)
            .replace("{port}", motd.serverPort.toString())
        if (imgUrl.isNotEmpty()) {
            val separator = if (imgUrl.contains("?")) "&" else "?"
            imgUrl += "${separator}_t=${System.currentTimeMillis() / 1000L}"
        }
        val rendered = template
            .replace("{{.server}}", plugin.serverName)
            .replace("{{.online_num}}", online.size.toString())
            .replace("{{.player}}", players)
            .replace("{{.players}}", players)
            .replace("{{.img_url}}", imgUrl)
            .replace("{online}", online.size.toString())
            .replace("{players}", players)
            .replace("{server}", plugin.serverName)
            .replace("{img_url}", imgUrl)
        try {
            plugin.replyMarkdown(event, rendered, null)
        } catch (_: Throwable) {
            // 回退路径发送的是动态渲染的模板内容（非固定文案）
            event.sendMessage(rendered)
        }
    }

    /** /AI对话上下文 开|关 —— 切换全局 AI 对话上下文（管理员）。模板：ai-context-usage/on/off。 */
    @Commands("AI对话上下文")
    fun aiContextToggle(plugin: KERONG, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            MessageTemplates.sendDirect(event, "manager-not-ready-bind")
            return
        }
        val arg = params?.trim() ?: ""
        val group = groupId(event)
        val user = userId(event)
        when {
            arg == "开" || arg.equals("on", true) || arg == "true" -> {
                manager.setAiContextEnabled(group, user, true)
                MessageTemplates.sendDirect(event, "ai-context-on")
            }
            arg == "关" || arg.equals("off", true) || arg == "false" -> {
                manager.setAiContextEnabled(group, user, false)
                MessageTemplates.sendDirect(event, "ai-context-off")
            }
            else -> {
                val enabled = manager.isAiContextEnabled(group, user)
                MessageTemplates.sendDirect(event, "ai-context-usage", mapOf(
                    "status" to (if (enabled) "开" else "关"),
                ))
            }
        }
    }

    /** /清除当前上下文 —— 清除全局 AI 对话历史（管理员）。模板：ai-context-cleared。 */
    @Commands("清除当前上下文")
    fun clearAiContext(plugin: KERONG, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            MessageTemplates.sendDirect(event, "manager-not-ready-bind")
            return
        }
        manager.clearAiContext(groupId(event), userId(event))
        MessageTemplates.sendDirect(event, "ai-context-cleared")
    }

    /**
     * /黑名单 @QQ —— 将 QQ 加入黑名单并解绑踢出（管理员）。
     * 无 @ 时列出黑名单。模板：blacklist-empty/list/added-kicked/added。
     */
    @Commands("黑名单")
    fun blacklist(plugin: KERONG, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            MessageTemplates.sendDirect(event, "manager-not-ready")
            return
        }
        val mentioned = extractMentionedQqInfo(event)
        val mentionedId = mentioned?.firstOrNull()
        val mentionedName = mentioned?.getOrNull(1)
        if (mentionedId.isNullOrEmpty()) {
            val list = manager.listBlacklist()
            if (list.isEmpty()) {
                MessageTemplates.sendDirect(event, "blacklist-empty")
            } else {
                MessageTemplates.sendDirect(event, "blacklist-list", mapOf(
                    "count" to list.size.toString(),
                    "list" to list.mapIndexed { index, id ->
                        "${index + 1}. ${manager.getBlacklistName(id) ?: id}"
                    }.joinToString("\n"),
                ))
            }
            return
        }
        manager.addBlacklist(mentionedId, mentionedName)
        // 1.7.4：多玩家绑定——拉黑时该 QQ 名下全部绑定玩家一并解绑踢出
        val unbound = manager.unbindAllAndKickByQq(mentionedId)
        if (unbound.isNotEmpty()) {
            MessageTemplates.sendDirect(event, "blacklist-added-kicked", mapOf(
                "name" to (mentionedName ?: mentionedId),
                "player" to unbound.joinToString("、"),
            ))
        } else {
            MessageTemplates.sendDirect(event, "blacklist-added", mapOf(
                "name" to (mentionedName ?: mentionedId),
            ))
        }
    }

    /**
     * /解除黑名单 @QQ —— 从黑名单移除 QQ（管理员）。模板：blacklist-removed/unblacklist-empty/list。
     *
     * 1.7.4 覆盖版：不带 @ 时列出的清单消息附带**按钮键盘**——每个黑名单条目
     * 一个「解除」按钮，点击即移除（交互模式参考 HuHoBot 的消息按钮，
     * 见 [cn.kerong.bot.events.UnblacklistButtons]）；@成员 的直接解除路径不变。
     */
    @Commands("解除黑名单")
    fun unblacklist(plugin: KERONG, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            MessageTemplates.sendDirect(event, "manager-not-ready")
            return
        }
        val mentioned = extractMentionedQqInfo(event)
        val mentionedId = mentioned?.firstOrNull()
        val mentionedName = mentioned?.getOrNull(1)
        if (!mentionedId.isNullOrEmpty()) {
            manager.removeBlacklist(mentionedId)
            MessageTemplates.sendDirect(event, "blacklist-removed", mapOf(
                "name" to (mentionedName ?: mentionedId),
            ))
            return
        }
        val list = manager.listBlacklist()
        if (list.isEmpty()) {
            MessageTemplates.sendDirect(event, "unblacklist-empty")
            return
        }
        val entries = list.map { id -> id to (manager.getBlacklistName(id) ?: id) }
        // 优先发按钮清单；发送失败（平台拒收键盘 / 客户端未启动）回落旧版纯文本
        val sent = cn.kerong.bot.events.UnblacklistButtons.sendList(event, entries)
        if (!sent) {
            MessageTemplates.sendDirect(event, "unblacklist-list", mapOf(
                "count" to list.size.toString(),
                "list" to entries.mapIndexed { index, (_, name) ->
                    "${index + 1}. $name"
                }.joinToString("\n"),
            ))
        }
    }

    /**
     * /重新绑定 —— 解除当前 QQ 绑定并踢出玩家（自助）。模板：rebind-success/not-bound。
     * 1.7.4：名下绑定多个玩家时先数字选择，只解绑选中的那一个（其余绑定不受影响）。
     */
    @Commands("重新绑定")
    fun rebindQq(plugin: KERONG, event: GroupMessageEvent, params: String) {
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            MessageTemplates.sendDirect(event, "manager-not-ready")
            return
        }
        val qq = userId(event)
        cn.kerong.bot.events.PlayerSelection.requireBoundPlayer(
            event, manager, qq,
            notBoundTemplate = "rebind-not-bound",
        ) { selected ->
            val kicked = manager.unbindByQuuid(selected.quuuid, selected.playerName)
            if (kicked) {
                MessageTemplates.sendDirect(event, "rebind-success", mapOf("player" to selected.playerName))
            } else {
                MessageTemplates.sendDirect(event, "rebind-not-bound")
            }
        }
    }

    /**
     * /帮助 —— 查看命令列表（1.5.3 重排版，1.6.0 起走 help.md 模板）。
     *
     * 模板 help.md 支持占位符：
     * - {version}：当前插件版本号；
     * - {leaderboardLine}：在线排行榜条目行（commands.在线排行榜 关闭时为空，
     *   开启时为「- /在线排行榜 —— 总在线时长排行\n」）。
     * 输出 Markdown 格式，纯文本回退时语法符号原样显示，不影响可读性。
     */
    @Commands("帮助")
    fun help(plugin: KERONG, event: GroupMessageEvent, params: String) {
        // 1.5.5：在线排行榜条目按命令开关动态显示（commands.在线排行榜 关闭时同步消失）
        val leaderboardEnabled = try {
            plugin.commandList()["在线排行榜"] != false
        } catch (_: Throwable) {
            true
        }
        MessageTemplates.sendDirect(event, "help", mapOf(
            "version" to plugin.pluginVersion,
            "leaderboardLine" to (if (leaderboardEnabled) "- /在线排行榜 —— 总在线时长排行\n" else ""),
        ))
    }

    /** /执行 <key> —— 执行自定义命令（普通权限）。无参数回复模板 invalid-params。 */
    @Commands("执行")
    fun runCustomCommand(plugin: KERONG, event: GroupMessageEvent, params: String) {
        if (params.trim().isEmpty()) {
            MessageTemplates.sendDirect(event, "invalid-params")
            return
        }
        val cmd = "kerong run ${groupId(event)} ${userId(event)} $params"
        executeGameCommandWithMention(plugin, event, cmd, true)
    }

    /** 提取消息中被 @ 的首个用户（排除机器人自身）。 */
    private fun extractMentionedQqInfo(event: GroupMessageEvent): List<String>? {
        return try {
            val mentions: Array<User>? = event.rawMessage?.mentions
            if (mentions.isNullOrEmpty()) return null
            for (user in mentions) {
                try {
                    if (user.bot == true) continue
                } catch (_: Throwable) {
                }
                val id = user.id ?: continue
                val name = user.username
                return listOf(id, name ?: id)
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 派发命令并带 @ 提及回复执行结果。
     * 回复模板：bridge-error（桥接失败）/ sent-request（已发送）/ execute-failed（异常）。
     * 1.7.0：回复移到异步线程（完成回调可能在主线程，同步 HTTP 卡主线程）；
     * replyMarkdown 按平台响应校验真实送达，拒收时回退纯文本（见
     * CommandSupport.clipCommandOutput 与 QClient.replyMarkdown）。
     */
    private fun executeGameCommandWithMention(plugin: KERONG, event: GroupMessageEvent, command: String, direct: Boolean) {
        try {
            val outgoingCommand = if (direct) command
            else "kerong run ${groupId(event)} ${userId(event)} $command"
            val future: CompletableFuture<cn.kerong.bot.provider.HExecution> = plugin.sendCommand(outgoingCommand)
            future.whenComplete { result, error ->
                plugin.submitAsync {
                    try {
                        when {
                            error != null || result == null -> MessageTemplates.sendDirect(event, "bridge-error")
                            result.rawString.isBlank() -> MessageTemplates.sendDirect(event, "sent-request")
                            // 执行结果为动态内容（非固定文案），先裁剪后原样审核转发
                            else -> sendDirect(event, plugin.auditText(clipCommandOutput(result.rawString)))
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (t: Throwable) {
            MessageTemplates.sendDirect(event, "execute-failed", mapOf("error" to (t.message ?: t.toString())))
        }
    }

    /**
     * 带 @ 提及的纯文本回复（命令执行结果原样转发，非模板内容）。
     * 1.7.0：回退按 replyMarkdown 的真实送达结果（响应含消息 id 才算成功）。
     */
    private fun sendDirect(event: GroupMessageEvent, message: String) {
        try {
            val userId = try {
                userId(event)
            } catch (_: Throwable) {
                null
            }
            val content = if (!userId.isNullOrEmpty() && userId != "<unknown>") "<@$userId>\n$message" else message
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
        } catch (_: Throwable) {
            try {
                event.sendMessage(message)
            } catch (_: Throwable) {
            }
        }
    }
}
