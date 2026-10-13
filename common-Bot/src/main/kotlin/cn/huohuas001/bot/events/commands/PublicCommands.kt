package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.huhobotPenguin.spigot.qqbind.MessageTemplates
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import cn.huohuas001.huhobotPenguin.spigot.stats.OnlineListService
import cn.huohuas001.huhobotPenguin.spigot.stats.QueryInfoService
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.qqpd.User
import java.util.concurrent.CompletableFuture

/**
 * 公开命令：帮助 / 查信息 / 查询open ID / 查在线 / 发信息 / 黑名单 / 重新绑定 / AI 上下文等。
 *
 * 1.6.0：全部文本回复改走消息模板系统（[MessageTemplates]）——
 * 每条回复对应数据目录 Markdown/ 下一个 .md 模板文件（配置 messages 段可换文件），
 * 默认模板内容与 1.5.7.3 硬编码文本一字不差；模板渲染出口统一应用经济别名
 * （economy-alias）。
 *
 * 1.5.7.4：/查在线 新增别名 /在线（不出现在快捷指令菜单，
 * 命令开关与 commands.查在线 共用）。
 *
 * 1.5.3：/查信息 @成员 查询该成员绑定的玩家；/查询OpenID 反查 QQ 账号。
 */
class PublicCommands : CommandSupport() {

    /**
     * /查信息 —— 查询 QQ 绑定玩家的生涯统计卡片（渲染为图片发送，不 @ 提及）。
     * 1.5.2 前名为 /个人信息。未绑定则提示先完成绑定。
     *
     * 1.5.3：支持 `/查信息 @成员` 查询该成员绑定的玩家信息（
     * 提及数据来自消息 mentions 字段而非命令参数——@ 片段在命令分发前已被剥离）；
     * 未绑定时提示该成员先完成绑定。
     *
     * 开关位于 QQ 绑定配置节：qq-bind.personal-info（默认开启）；
     * 若 QQ 绑定功能（qq-bind.enabled）关闭，本命令同时关闭。
     * 提示文本模板：info-bind-disabled / info-closed / qq-unknown（messages 段）。
     */
    @Commands("查信息")
    fun queryPersonalInfo(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
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
            QueryInfoService.handle(plugin, event, qq, targetId, targetName)
            return
        }
        // 卡片渲染与数据汇总在服务内异步完成
        QueryInfoService.handle(plugin, event, qq)
    }

    /**
     * /查询open ID —— OpenId 查询与反查。
     *
     * 用法（1.5.3）：
     * - `/查询open ID`：显示自己的 OpenId 与群 OpenId（用于配置 bot.groups）；
     * - `/查询open ID <OpenId>`：反查该 OpenId 对应的 QQ 账号（群昵称 + 绑定玩家）；
     * - `/查询open ID @成员`：查看被 @ 成员的 OpenId 与其绑定信息。
     *
     * 群昵称来自插件维护的 OpenId 目录（从每条群消息的发送者与被 @ 成员积累，
     * 见 OpenIdDirectory）；机器人无法通过接口按 OpenId 查询群资料。
     * 提示文本模板：openid-self / openid-lookup（messages 段）。
     */
    @Commands("查询open ID", "查询Open ID", "查询open id", "查询Open id", "查询openID", "查询OpenID", "查询openid", "查询OPENID")
    fun queryInfo(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val arg = params?.trim() ?: ""
        val mentioned = extractMentionedQqInfo(event)
        if (arg.isEmpty() && mentioned == null) {
            MessageTemplates.sendDirect(event, "openid-self", mapOf(
                "openid" to userId(event),
                "group" to groupId(event),
            ))
            return
        }
        // 1.5.3：反查 / 查看 @ 成员
        val targetOpenId = if (arg.isNotEmpty()) arg else mentioned!!.firstOrNull() ?: return
        reverseLookupOpenId(plugin, event, targetOpenId)
    }

    /**
     * 反查 OpenId 对应的 QQ 账号：群昵称 + 最后活跃 + 绑定玩家。
     * 绑定查找（磁盘扫描）在当前消息线程完成，规模小可接受。
     */
    private fun reverseLookupOpenId(plugin: HuHoBot, event: GroupMessageEvent, openId: String) {
        val bindManager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            null
        }
        val nickname = try {
            cn.huohuas001.huhobotPenguin.spigot.qqbind.OpenIdDirectory.lookupNickname(openId)
        } catch (_: Throwable) {
            null
        }
        val lastSeen = try {
            cn.huohuas001.huhobotPenguin.spigot.qqbind.OpenIdDirectory.lookupLastSeenText(openId)
        } catch (_: Throwable) {
            null
        }
        val boundPlayer = try {
            bindManager?.findPlayerByQq(openId)
        } catch (_: Throwable) {
            null
        }
        // {lastSeenPart}：有记录时为「\n最后活跃: xxx」，无记录时为空（行不显示）
        val lastSeenPart = if (lastSeen != null) "\n最后活跃: $lastSeen" else ""
        MessageTemplates.sendDirect(event, "openid-lookup", mapOf(
            "openid" to openId,
            "nickname" to (nickname ?: "未知（该账号未在机器人可见的群消息中出现过）"),
            "lastSeenPart" to lastSeenPart,
            "player" to (boundPlayer?.takeIf { it.isNotEmpty() } ?: "未绑定任何游戏玩家"),
        ))
    }

    /** /发信息 <内容> —— 发送消息到游戏内。转发关闭时回复模板 forward-disabled。 */
    @Commands("发信息")
    fun sendGameMessage(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
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
     * /查在线 —— 查询在线玩家列表：markdown 开启走模板，关闭自动渲染图片（1.5.1）。
     *
     * 1.5.7.4：新增别名 /在线（更短的输入变体，与 /查在线 完全同一行为）；
     * 别名不出现在 QQ 快捷指令菜单（菜单仍只展示 /查在线 按钮），
     * 命令开关与 commands.查在线 共用（关闭 查在线 时 /在线 一并失效）。
     */
    @Commands("查在线", "在线")
    fun queryOnline(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
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

    /** /在线服务器 —— 查看服务器状态文本（模板 server-status）。 */
    @Commands("在线服务器")
    fun queryServers(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val motd = plugin.motd
        MessageTemplates.sendDirect(event, "server-status", mapOf(
            "ip" to motd.serverIP,
            "port" to motd.serverPort.toString(),
        ))
    }

    /** /AI对话上下文 开|关 —— 切换全局 AI 对话上下文（管理员）。模板：ai-context-usage/on/off。 */
    @Commands("AI对话上下文")
    fun aiContextToggle(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
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
    fun clearAiContext(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
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
    fun blacklist(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
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
        val kicked = manager.unbindAndKickByQq(mentionedId)
        if (kicked != null) {
            MessageTemplates.sendDirect(event, "blacklist-added-kicked", mapOf(
                "name" to (mentionedName ?: mentionedId),
                "player" to kicked,
            ))
        } else {
            MessageTemplates.sendDirect(event, "blacklist-added", mapOf(
                "name" to (mentionedName ?: mentionedId),
            ))
        }
    }

    /** /解除黑名单 @QQ —— 从黑名单移除 QQ（管理员）。模板：blacklist-removed/unblacklist-empty/list。 */
    @Commands("解除黑名单")
    fun unblacklist(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
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
        MessageTemplates.sendDirect(event, "unblacklist-list", mapOf(
            "count" to list.size.toString(),
            "list" to list.mapIndexed { index, id ->
                "${index + 1}. ${manager.getBlacklistName(id) ?: id}"
            }.joinToString("\n"),
        ))
    }

    /** /重新绑定 —— 解除当前 QQ 绑定并踢出玩家（自助）。模板：rebind-success/not-bound。 */
    @Commands("重新绑定")
    fun rebindQq(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            MessageTemplates.sendDirect(event, "manager-not-ready")
            return
        }
        val qq = userId(event)
        val kicked = manager.unbindAndKickByQq(qq)
        if (kicked != null) {
            MessageTemplates.sendDirect(event, "rebind-success", mapOf("player" to kicked))
        } else {
            MessageTemplates.sendDirect(event, "rebind-not-bound")
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
    fun help(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
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

    /**
     * /motd —— 无参数：查询本服状态（模板 server-status）；
     * 带参数（/motd <host>）：委托 [MotdCommands] 查询任意 MC 服务器状态
     * （minebbs MOTD API，模板 motd-*）。
     *
     * 1.6.3 修复：旧版注释声称「带参数时走 MotdCommands 的状态查询」，但
     * PublicCommands 先于 MotdCommands 注册、命令分发首个匹配即短路，
     * MotdCommands 实际永不可达（带参数也只会回本服状态）——现补上真正的
     * 转发调用，/motd <host> 恢复可用，motd-* 五个模板恢复被渲染。
     */
    @Commands("motd")
    fun motd(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (params.isNotBlank()) {
            MotdCommands().motd(plugin, event, params)
            return
        }
        val config = plugin.motd
        MessageTemplates.sendDirect(event, "server-status", mapOf(
            "ip" to config.serverIP,
            "port" to config.serverPort.toString(),
        ))
    }

    /** /执行 <key> —— 执行自定义命令（普通权限）。无参数回复模板 invalid-params。 */
    @Commands("执行")
    fun runCustomCommand(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (params.trim().isEmpty()) {
            MessageTemplates.sendDirect(event, "invalid-params")
            return
        }
        val cmd = "huhobot run ${groupId(event)} ${userId(event)} $params"
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
     */
    private fun executeGameCommandWithMention(plugin: HuHoBot, event: GroupMessageEvent, command: String, direct: Boolean) {
        try {
            val outgoingCommand = if (direct) command
            else "huhobot run ${groupId(event)} ${userId(event)} $command"
            val future: CompletableFuture<cn.huohuas001.bot.provider.HExecution> = plugin.sendCommand(outgoingCommand)
            future.whenComplete { result, error ->
                try {
                    when {
                        error != null || result == null -> MessageTemplates.sendDirect(event, "bridge-error")
                        result.rawString.isBlank() -> MessageTemplates.sendDirect(event, "sent-request")
                        // 执行结果为动态内容（非固定文案），原样审核后转发
                        else -> sendDirect(event, plugin.auditText(result.rawString))
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            MessageTemplates.sendDirect(event, "execute-failed", mapOf("error" to (t.message ?: t.toString())))
        }
    }

    /** 带 @ 提及的纯文本回复（命令执行结果原样转发，非模板内容）。 */
    private fun sendDirect(event: GroupMessageEvent, message: String) {
        try {
            val userId = try {
                userId(event)
            } catch (_: Throwable) {
                null
            }
            val content = if (!userId.isNullOrEmpty() && userId != "<unknown>") "<@$userId>\n$message" else message
            try {
                QClient.replyMarkdown(event, content, null)
            } catch (_: Throwable) {
                event.sendMessage(content)
            }
        } catch (_: Throwable) {
            try {
                event.sendMessage(message)
            } catch (_: Throwable) {
            }
        }
    }
}
