package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import cn.huohuas001.huhobotPenguin.spigot.stats.OnlineListService
import cn.huohuas001.huhobotPenguin.spigot.stats.QueryInfoService
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.qqpd.User
import java.util.concurrent.CompletableFuture

/**
 * 公开命令：帮助 / 查信息 / 查询open ID / 查在线 / 发信息 / 黑名单 / 重新绑定 / AI 上下文等。
 *
 * 1.5.2 命令更名：
 * - 查询 OpenId 的命令 `/查信息` 更名为 `/查询open ID`（大小写/空格输入变体均可触发）；
 * - 玩家统计卡片命令 `/个人信息` 更名为 `/查信息`；
 * - `/查在线`（查询在线玩家）保持不变。
 *
 * 1.5.3：
 * - `/查信息 @成员`：查询该成员 QQ 绑定的玩家统计卡片（未绑定则提示）；
 * - `/查询OpenID <OpenId>`：反查该 OpenId 对应的 QQ 账号（群昵称与绑定状态）；
 *   也可 `/查询OpenID @成员` 直接查看被 @ 成员的 OpenId 与绑定信息。
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
     */
    @Commands("查信息")
    fun queryPersonalInfo(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val bindManager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            null
        }
        if (bindManager == null) {
            sendDirect(event, "❌ 绑定管理器未就绪，请稍后再试")
            return
        }
        if (!bindManager.isEnabled) {
            sendDirect(event, "查信息功能未开启（QQ 绑定功能未启用）")
            return
        }
        if (!bindManager.isPersonalInfoEnabled) {
            sendDirect(event, "查信息功能已被管理员关闭")
            return
        }
        val qq = try {
            userId(event)
        } catch (_: Throwable) {
            "<unknown>"
        }
        if (qq.isEmpty() || qq == "<unknown>") {
            sendDirect(event, "❌ 无法识别你的 QQ 账号")
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
     */
    @Commands("查询open ID", "查询Open ID", "查询open id", "查询Open id", "查询openID", "查询OpenID", "查询openid", "查询OPENID")
    fun queryInfo(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val arg = params?.trim() ?: ""
        val mentioned = extractMentionedQqInfo(event)
        if (arg.isEmpty() && mentioned == null) {
            sendDirect(event, "你的OpenId: " + userId(event) + "\n群的OpenId: " + groupId(event))
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
        val text = buildString {
            append("【OpenID 反查】\n")
            append("OpenId: $openId\n")
            append(
                "群昵称: " + (nickname ?: "未知（该账号未在机器人可见的群消息中出现过）") + "\n"
            )
            if (lastSeen != null) append("最后活跃: $lastSeen\n")
            append(
                "绑定玩家: " + (boundPlayer?.takeIf { it.isNotEmpty() } ?: "未绑定任何游戏玩家")
            )
        }
        sendDirect(event, text.trim())
    }

    /** /发信息 <内容> —— 发送消息到游戏内。 */
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
            event.sendMessage("群聊转发功能已关闭")
        }
    }

    /** /查在线 —— 查询在线玩家列表：markdown 开启走模板，关闭自动渲染图片（1.5.1）。 */
    @Commands("查在线")
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
            sendDirect(event, rendered)
        }
    }

    /** /在线服务器 —— 查看服务器状态文本。 */
    @Commands("在线服务器")
    fun queryServers(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val motd = plugin.motd
        sendDirect(event, "服务器: ${motd.serverIP}:${motd.serverPort}")
    }

    /** /AI对话上下文 开|关 —— 切换全局 AI 对话上下文（管理员）。 */
    @Commands("AI对话上下文")
    fun aiContextToggle(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            sendDirect(event, "❌ 绑定管理器未就绪")
            return
        }
        val arg = params?.trim() ?: ""
        val group = groupId(event)
        val user = userId(event)
        when {
            arg == "开" || arg.equals("on", true) || arg == "true" -> {
                manager.setAiContextEnabled(group, user, true)
                sendDirect(event, "✅ AI 对话上下文已全局开启，将保留最近对话历史。")
            }
            arg == "关" || arg.equals("off", true) || arg == "false" -> {
                manager.setAiContextEnabled(group, user, false)
                sendDirect(event, "✅ AI 对话上下文已全局关闭，每次对话独立。")
            }
            else -> {
                val enabled = manager.isAiContextEnabled(group, user)
                sendDirect(event, "用法: /AI对话上下文 开|关\n当前状态: " + (if (enabled) "开" else "关"))
            }
        }
    }

    /** /清除当前上下文 —— 清除全局 AI 对话历史（管理员）。 */
    @Commands("清除当前上下文")
    fun clearAiContext(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            sendDirect(event, "❌ 绑定管理器未就绪")
            return
        }
        manager.clearAiContext(groupId(event), userId(event))
        sendDirect(event, "✅ 已清除全局 AI 对话上下文。")
    }

    /** /黑名单 @QQ —— 将 QQ 加入黑名单并解绑踢出（管理员）。 */
    @Commands("黑名单")
    fun blacklist(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            sendDirect(event, "❌ 管理器未就绪")
            return
        }
        val mentioned = extractMentionedQqInfo(event)
        val mentionedId = mentioned?.firstOrNull()
        val mentionedName = mentioned?.getOrNull(1)
        if (mentionedId.isNullOrEmpty()) {
            val list = manager.listBlacklist()
            if (list.isEmpty()) {
                sendDirect(event, "当前黑名单为空。\n用法: /黑名单 @QQ")
            } else {
                val text = buildString {
                    append("黑名单列表（${list.size} 个）：\n")
                    list.forEachIndexed { index, id ->
                        val name = manager.getBlacklistName(id)
                        append("${index + 1}. ${name ?: id}\n")
                    }
                }
                sendDirect(event, text.trim())
            }
            return
        }
        manager.addBlacklist(mentionedId, mentionedName)
        val kicked = manager.unbindAndKickByQq(mentionedId)
        if (kicked != null) {
            sendDirect(event, "✅ 已将 ${mentionedName ?: mentionedId} 加入黑名单。\n已解绑并踢出玩家: $kicked")
        } else {
            sendDirect(event, "✅ 已将 ${mentionedName ?: mentionedId} 加入黑名单。该 QQ 将不能绑定任何游戏账号。")
        }
    }

    /** /解除黑名单 @QQ —— 从黑名单移除 QQ（管理员）。 */
    @Commands("解除黑名单")
    fun unblacklist(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!requireAdmin(plugin, event)) return
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            sendDirect(event, "❌ 管理器未就绪")
            return
        }
        val mentioned = extractMentionedQqInfo(event)
        val mentionedId = mentioned?.firstOrNull()
        val mentionedName = mentioned?.getOrNull(1)
        if (!mentionedId.isNullOrEmpty()) {
            manager.removeBlacklist(mentionedId)
            sendDirect(event, "✅ 已将 ${mentionedName ?: mentionedId} 从黑名单移除。")
            return
        }
        val list = manager.listBlacklist()
        if (list.isEmpty()) {
            sendDirect(event, "当前黑名单为空。")
            return
        }
        val text = buildString {
            append("黑名单列表（${list.size} 个）：\n")
            list.forEachIndexed { index, id ->
                val name = manager.getBlacklistName(id)
                append("${index + 1}. ${name ?: id}\n")
            }
            append("\n解除请发送: /解除黑名单 @QQ")
        }
        sendDirect(event, text.trim())
    }

    /** /重新绑定 —— 解除当前 QQ 绑定并踢出玩家（自助）。 */
    @Commands("重新绑定")
    fun rebindQq(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            sendDirect(event, "❌ 管理器未就绪")
            return
        }
        val qq = userId(event)
        val kicked = manager.unbindAndKickByQq(qq)
        if (kicked != null) {
            sendDirect(event, "✅ 已解除你的 QQ 绑定。玩家 $kicked 已被踢出服务器。\n请重新进入服务器获取新的绑定码。")
        } else {
            sendDirect(event, "ℹ️ 你的 QQ 未绑定任何游戏账号。")
        }
    }

    /** /帮助 —— 查看命令列表。 */
    @Commands("帮助")
    fun help(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val text = buildString {
            append("===== KERONG Penguin 命令列表 =====\n\n")
            append("【QQ 群命令】\n")
            append("  /帮助 —— 查看本帮助\n")
            append("  /查信息 —— 查询你的玩家统计卡片\n")
            append("  /查信息 @成员 —— 查询该成员绑定的玩家信息\n")
            append("  /查询open ID —— 查询你的 OpenId 与群 OpenId\n")
            append("  /查询open ID <OpenId> —— 反查该 OpenId 的 QQ 账号与绑定\n")
            append("  /查在线 —— 查询在线玩家\n")
            append("  /在线服务器 —— 查看服务器状态\n")
            append("  /发信息 <内容> —— 发送消息到游戏\n")
            append("  /motd —— 查询服务器状态\n")
            append("  /绑定 <绑定码> —— 绑定 QQ（玩家自助）\n")
            append("  /重新绑定 —— 解除当前 QQ 绑定（踢出玩家）\n")
            append("  /签到 —— 每日签到领金币\n\n")
            append("【管理员命令】（需 QQ 群管理员）\n")
            append("  /执行命令 <命令> —— 执行服务器命令\n")
            append("  /执行 <key> —— 执行自定义命令\n")
            append("  /管理员执行 <key> —— 管理员执行自定义命令\n")
            append("  /全量 —— 切换全量聊天转发\n\n")
            append("【AI 对话命令】（管理员）\n")
            append("  /AI对话上下文 开|关 —— 开关全局对话上下文\n")
            append("  /清除当前上下文 —— 清除全局 AI 对话历史\n")
            append("  @机器人 + 消息 —— 与 AI 对话\n\n")
            append("【黑名单命令】（管理员）\n")
            append("  /黑名单 @QQ —— 将 QQ 加入黑名单\n")
            append("  /解除黑名单 @QQ —— 从黑名单移除 QQ\n\n")
            append("【服务器内命令】（MC 内）\n")
            append("  /qq help —— 查看 QQ 绑定管理命令\n")
            append("  /hb help —— 查看插件命令")
        }
        sendDirect(event, text)
    }

    /** /motd —— 查询服务器状态文本。 */
    @Commands("motd")
    fun motd(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val config = plugin.motd
        sendDirect(event, "服务器: ${config.serverIP}:${config.serverPort}")
    }

    /** /执行 <key> —— 执行自定义命令（普通权限）。 */
    @Commands("执行")
    fun runCustomCommand(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (params.trim().isEmpty()) {
            sendDirect(event, "参数不正确")
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

    /** 派发命令并带 @ 提及回复执行结果。 */
    private fun executeGameCommandWithMention(plugin: HuHoBot, event: GroupMessageEvent, command: String, direct: Boolean) {
        try {
            val outgoingCommand = if (direct) command
            else "huhobot run ${groupId(event)} ${userId(event)} $command"
            val future: CompletableFuture<cn.huohuas001.bot.provider.HExecution> = plugin.sendCommand(outgoingCommand)
            future.whenComplete { result, error ->
                try {
                    when {
                        error != null || result == null -> sendDirect(event, "游戏桥接未配置或执行失败")
                        result.rawString.isBlank() -> sendDirect(event, "已发送执行请求")
                        else -> sendDirect(event, plugin.auditText(result.rawString))
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            sendDirect(event, "执行失败: ${t.message}")
        }
    }

    /** 带 @ 提及的 Markdown 回复（失败时退化为纯文本）。 */
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
