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
 * 公开命令：菜单 / 玩家名片 / 查信息 / 查在线 / 发信息 / 黑名单 / 重新绑定 / AI 管理 / 切换服务器等。
 *
 * 指令改名（与 HuHoBot 语义对齐）——
 * - 旧「查询open ID」改名为「查信息」（查 OpenId，配置 bot.groups 用）;
 * - 旧「查信息」（玩家统计卡片）改名为「玩家名片」。
 *
 * 
 * - 旧版指令兜底全部下线（用户指令：「那些旧版指令兜底都去掉」）——
 *   「查询open ID」及其大小写变体不再是 /查信息 的隐藏别名，指令只认现名；
 * - 新增 /切换服务器（多服务器集群，见 [ClusterState]；与 HuHoBot 的
 *   多服务器概念一致）；
 * - /帮助 替换为 /菜单（输出全部指令按钮），原 AI 上下文两条命令
 *   合并为 /AI管理（AI 关闭时同步关闭）。
 *
 * 全部文本回复改走消息模板系统（[MessageTemplates]）——
 * 每条回复对应数据目录 Markdown/ 下一个 .md 模板文件（配置 messages 段可换文件），
 * 模板渲染出口统一应用经济别名（economy-alias）。
 *
 * /查在线 新增别名 /在线（不出现在快捷指令菜单，
 * 命令开关与 commands.查在线 共用）。
 *
 * /查信息（现玩家名片）@成员 查询该成员绑定的玩家；/查询OpenID（现查信息）反查 QQ 账号。
 *
 * 移除 /在线服务器 与 /motd 两条命令（用户需求；服务器地址
 * 改由服主自行在群里公告，插件侧不再提供查询）。
 */
class PublicCommands : CommandSupport() {

    /**
     * /玩家名片 —— 查询 QQ 绑定玩家的生涯统计卡片（渲染为图片发送，不 @ 提及）。
 * 前名为 /个人信息，1.5.2-1.7.4 初版名为 /查信息，更名 /玩家名片
 * （「查信息」名字让位给原 /查询open ID 的 OpenId 查询）。未绑定则提示先完成绑定。
     *
 * 支持 `/玩家名片 @成员` 查询该成员绑定的玩家信息（
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
 // 若 @ 了其他成员则查询该成员的绑定玩家（排除机器人自身）
        val mentioned = extractMentionedQqInfo(event)
        if (mentioned != null) {
            val targetId = mentioned.firstOrNull() ?: return
            val targetName = mentioned.getOrNull(1) ?: targetId
 // 目标成员名下绑定多个玩家时，由发起者点击按钮（或回复数字）选择要查询的玩家
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
     *   名下绑定的全部玩家；多个绑定玩家同时显示；QQ 号行全员可见）；
     * - `/查信息 <OpenId>`：反查该 OpenId 对应的 QQ 账号（群昵称 + 绑定玩家）；
     * - `/查信息 @成员`：查看被 @ 成员的绑定信息。
     *
     * 参数分派：先按玩家名查绑定（玩家名精确匹配），命中即玩家名反查；
     * 未命中再按 OpenId 反查（玩家名 ≤16 字符且字符集受限，OpenId 更长，
     * 两者实际无重叠）。
     *
 * OpenId 反查（`/查信息 <OpenId>` / @成员）结果中的 OpenId 行
     * 仅管理员可见（避免普通群成员批量收集 OpenId 冒充绑定）；非管理员
     * 只看到群昵称、最后活跃、绑定玩家。玩家名反查的 QQ(OpenId) 行
     * 全员可见（用于核实玩家与账号的对应关系）。
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
     * QQ(OpenId) 行全员可见（用户指令：QQ 号行不再限管理员，方便普通
     * 成员核实玩家与账号的对应关系）；该 QQ 名下绑定多个玩家时全部列出。
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
        // QQ(OpenId) 行全员可见（不再限管理员；OpenId 反查查信息 <OpenId>
        // / @成员 路径仍保留管理员口径——OpenId 是 admin.openids 的配置凭证）
        val openidPart = "QQ(OpenId): $openId\n"
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
 * 按钮切换（开关 commands.切换服务器）。
     *
     * - 未配置集群（bot.cluster.members 少于 2 个）：回复单服提示；
     * - 带参数（1.7.5.4，/切换服务器 服务器名）：直接切换到该成员——
     *   参数为「全局」成员时是特权操作需管理员（平台门禁按钮凭验身
     *   标记放行 / 文本路径按名单判定）；
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
        val target = params?.trim() ?: ""
        if (target.isNotEmpty()) {
            // 带参直接切换（1.7.5.4）：按钮点击的合成命令与手动输入共用本路径
            if (target !in ClusterState.serverMembers) {
                val members = ClusterState.serverMembers.joinToString("、") { it }
                MessageTemplates.sendDirect(event, "cluster-list-fallback", mapOf(
                    "list" to members,
                ))
                return
            }
            if (target == cn.kerong.bot.events.ServerSelectButtons.GLOBAL_MEMBER) {
                // 「全局」是特权操作：管理员才能切（按钮已验身的凭标记直接过）
                if (!requireAdmin(plugin, event)) return
            }
            val previous = ClusterState.selectedServer(groupId(event))
            ClusterState.setSelectedServer(groupId(event), target)
            QClient.replyMarkdown(
                event,
                "已切换到服务器：$target（原：${previous ?: "未选择"}）",
                null,
            )
            cn.kerong.bot.tools.PluginFileLog.write(
                "[多服务器] 群=${groupId(event)} 用户=${userId(event)} 带参切换服务器：${previous ?: "（未选择）"} → $target"
            )
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
 * OpenId 行仅管理员可见——非管理员查询时 {openidPart} 为空
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
 // {openidPart}：管理员查询时为「OpenId: xxx\n」，非管理员为空（行不显示）
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
 // 显示名来源与全量转发一致（1.7.5.8 chat-format.group-sender-name）：
 // player 模式显示绑定玩家名（未绑定回退群昵称），group 模式保持群昵称
            senderName = cn.kerong.bot.tools.GroupSenderName.resolve(plugin, event, senderName)
            plugin.broadcastMessage(plugin.formatGroupMessage(senderName, content))
        } else {
            MessageTemplates.sendPlain(event, "forward-disabled")
        }
    }

    /**
 * /查性能 —— 服务器性能监控卡片（渲染图片发送）。
     *
 * 指标：TPS / MSPT / JVM 堆内存 / 进程 CPU（起固定四项，
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
 * /查在线 —— 查询在线玩家列表：markdown 开启走模板，关闭自动渲染图片。
     *
 * motd.use-markdown 默认值改为 false —— 新装服务器 / 查在线 默认
     * 输出渲染图片（毛玻璃卡片 + 真实皮肤头像）；想要简洁文字列表的把
     * motd.use-markdown 改回 true 即可（旧用户已保存的配置不受影响）。
     *
 * 新增别名 /在线（更短的输入变体，与 /查在线 完全同一行为）；
     * 别名不出现在 QQ 快捷指令菜单（菜单仍只展示 /查在线 按钮），
     * 命令开关与 commands.查在线 共用（关闭 查在线 时 /在线 一并失效）。
     */
    @Commands("查在线", "在线")
    fun queryOnline(plugin: KERONG, event: GroupMessageEvent, params: String) {
 // 移除 text 模板回退与 query-online.image-output 配置，行为固定二选一：
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

    /**
     * /AI管理 —— AI 上下文统一管理入口（管理员，合并原「AI对话上下文 开|关」
     * 与「清除当前上下文」两条命令）。
     *
     * - ai.enabled 关闭时本命令一并关闭（回复 ai-manage-disabled 模板）；
     * - 带参数：/AI管理 开|关 /AI管理 清除 直接执行对应操作；
     * - 不带参数：回复当前上下文状态并弹出三个**回调按钮**（开启上下文 /
     *   关闭上下文 / 清除上下文，一行一个），点击即以点击者身份执行对应
     *   子命令（旧客户端不渲染按钮时手动输入即可）。模板：
     *   ai-context-usage/on/off/cleared、ai-manage-disabled。
     */
    @Commands("AI管理", adminOnly = true)
    fun aiManage(plugin: KERONG, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            MessageTemplates.sendDirect(event, "manager-not-ready-bind")
            return
        }
        if (!manager.isAiEnabled) {
            MessageTemplates.sendDirect(event, "ai-manage-disabled")
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
            arg == "清除" || arg.equals("clear", true) -> {
                manager.clearAiContext(group, user)
                MessageTemplates.sendDirect(event, "ai-context-cleared")
            }
            else -> {
                // 状态 + 三个按钮（一行一个，点击即以点击者身份执行子命令）。
                // 1.7.5.4：管理按钮与主菜单同一收敛口径——qq/both 下平台门禁
                // （krg:acmd: + permission.type=1，点击到达即平台已验管理员，
                // 修复真管理员点子面板按钮被误报「无权限」）；MANUAL 名单
                // 模式下改指令按钮（actionType=2 点击填入输入栏），授权交给
                // 文本命令路径按名单校验。
                val enabled = manager.isAiContextEnabled(group, user)
                val message = MessageTemplates.render("ai-context-usage", mapOf(
                    "status" to (if (enabled) "开" else "关"),
                ))
                if (message.isEmpty()) return
                val adminGate = cn.kerong.bot.events.MenuButtons.adminGateFor(plugin, group)
                fun aiButton(label: String, action: String): cn.kerong.bot.tools.Keyboards.Button =
                    cn.kerong.bot.tools.Keyboards.Button(
                        label = label,
                        visitedLabel = label,
                        actionData = if (adminGate) {
                            "${cn.kerong.bot.events.MenuButtons.ACMD_PREFIX}/AI管理 $action"
                        } else {
                            "/AI管理 $action"
                        },
                        style = 1,
                        // 名单模式走指令按钮（填输入栏）；平台门禁模式走回调（点击即执行）
                        actionType = if (adminGate) 1 else 2,
                        // 平台门禁模式下仅群主/群管理员可点；名单模式由命令链路判名单
                        permissionType = if (adminGate) 1 else 2,
                    )
                val keyboard = cn.kerong.bot.tools.Keyboards.buildVertical(
                    listOf(
                        aiButton("开启上下文", "开"),
                        aiButton("关闭上下文", "关"),
                        aiButton("清除上下文", "清除"),
                    )
                )
                val user2 = try {
                    val contact = event.sender
                    contact?.openid ?: contact?.id ?: ""
                } catch (_: Throwable) {
                    ""
                }
                val content = if (user2.isNotEmpty()) "<@$user2>\n$message" else message
                val sent = try {
                    QClient.replyMarkdown(event, content, keyboard)
                } catch (_: Throwable) {
                    false
                }
                if (!sent) {
                    // 键盘发送失败（平台拒收 / 旧客户端）：退纯文本状态提示（用法在模板文案里）
                    MessageTemplates.sendDirect(event, "ai-context-usage", mapOf(
                        "status" to (if (enabled) "开" else "关"),
                    ))
                }
            }
        }
    }

    /**
     * /黑名单 @QQ —— 将 QQ 加入黑名单并解绑踢出（管理员）。
     * 无 @ 时列出黑名单（与 /解除黑名单 同一张**按钮清单**：每个条目
     * 一个「解除」按钮，点击即移除；也可艾特对方发送 /解除黑名单 解除）。
     * 模板：blacklist-empty/added-kicked/added、unblacklist-list。
     */
    @Commands("黑名单", adminOnly = true)
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
                // 与 /解除黑名单 一致的按钮清单（每个条目一个解除按钮）
                val entries = list.map { id -> id to (manager.getBlacklistName(id) ?: id) }
                val sent = cn.kerong.bot.events.UnblacklistButtons.sendList(event, entries)
                if (!sent) {
                    MessageTemplates.sendDirect(event, "blacklist-list", mapOf(
                        "count" to list.size.toString(),
                        "list" to entries.mapIndexed { index, (_, name) ->
                            "${index + 1}. $name"
                        }.joinToString("\n"),
                    ))
                }
            }
            return
        }
        manager.addBlacklist(mentionedId, mentionedName)
 // 多玩家绑定——拉黑时该 QQ 名下全部绑定玩家一并解绑踢出
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
 * 不带 @ 时列出的清单消息附带**按钮键盘**——每个黑名单条目
     * 一个「解除」按钮，点击即移除（交互模式参考 HuHoBot 的消息按钮，
     * 见 [cn.kerong.bot.events.UnblacklistButtons]）；@成员 的直接解除路径不变。
     */
    @Commands("解除黑名单", adminOnly = true)
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
 * 名下绑定多个玩家时先数字选择，只解绑选中的那一个（其余绑定不受影响）。
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
     * /菜单 —— 输出全部可用命令的按钮菜单（替代原 /帮助 命令列表）。
     *
     * - 无参命令（查在线 / 查性能 / 玩家名片…）用**回调按钮**：点一下
     *   机器人即以点击者身份执行命令并回复结果（群聊里指令按钮的自动
     *   发送仅单聊可用，点击即执行只有回调一种实现）；
     * - 需补参数的命令（绑定 / 发信息 / 执行命令 / 执行 / 管理员执行）用
     *   **指令按钮**：点击把命令复制进输入栏，用户补全参数后手动发送；
     * - 按钮一行一个（键盘上限 5 行 = 4 个命令 + 1 行翻页），全部命令分多页，
     *   点「下一页」继续翻；
     * - 按钮可见性与 QQ 快捷面板同一套功能开关（commands 段 / QQ 绑定 /
     *   签到 / 经济 / AI / 集群 / 全量转发总配置），关闭的功能按钮同步消失；
     * - 键盘发送失败时回退纯文本命令清单（一行一个）。
     */
    @Commands("菜单")
    fun menu(plugin: KERONG, event: GroupMessageEvent, params: String) {
        val sent = try {
            cn.kerong.bot.events.MenuButtons.sendFirstPage(plugin, event)
        } catch (_: Throwable) {
            false
        }
        if (!sent) {
            // 键盘发送失败（平台拒收 / 无可用命令）：退纯文本命令清单
            val fallback = cn.kerong.bot.events.MenuButtons.plainList(plugin)
            if (fallback.isEmpty()) return
            val user = try {
                val contact = event.sender
                contact?.openid ?: contact?.id ?: ""
            } catch (_: Throwable) {
                ""
            }
            val content = if (user.isNotEmpty()) "<@$user>\n$fallback" else fallback
            try {
                if (!QClient.replyMarkdown(event, content, null)) event.sendMessage(content)
            } catch (_: Throwable) {
                try {
                    event.sendMessage(content)
                } catch (_: Throwable) {
                }
            }
        }
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
 * 回复移到异步线程（完成回调可能在主线程，同步 HTTP 卡主线程）；
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
 * 回退按 replyMarkdown 的真实送达结果（响应含消息 id 才算成功）。
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
