package cn.kerong.bot.events

import cn.kerong.bot.ClusterState
import cn.kerong.bot.KERONG
import cn.kerong.bot.QClient
import cn.kerong.penguin.spigot.qqbind.MessageTemplates
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import cn.kerong.bot.tools.Keyboards
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.qqpd.message.RawMessage
import io.github.kloping.qqbot.impl.message.v2.BaseGroupMessageEvent

/**
 * /菜单 的按钮键盘与按钮互动处理。
 *
 * ## 按钮形态（QQ 平台限制决定）
 *
 * 群聊里「点击即执行」只有**回调按钮**（actionType=1）一种实现方式：
 * 官方文档明确指令按钮的 enter 自动发送仅单聊可用，群聊点击指令按钮只会
 * 把 @机器人 + 命令插进输入框等用户手动发送——这就是此前菜单按钮
 * 「全部都是复制到输入栏」的根因。因此菜单里的全部命令按钮都是回调按钮：
 * 点击 → 机器人收到互动事件 → 3 秒内回应（无「第三方连接失败」弹窗）→
 * 以点击者身份执行命令并正常回复结果。需要补参数的命令（绑定 / 发信息 /
 * 执行命令 / 执行 / 管理员执行）点击后回复该命令的用法提示，同样是
 * 一步到位的直接互动。
 *
 * ## 布局与分页
 *
 * 每行一个按钮（键盘上限 5 行）：前 4 行为命令按钮，末行为翻页按钮
 * （「← 上一页」「下一页 →」按需出现）；全部命令超过一页时点翻页按钮
 * 发送下一页。按钮可见性与 QQ 快捷面板同一套功能开关。
 *
 * ## 回调数据
 *
 * - 命令按钮：[CMD_PREFIX] + 命令文本（如 `krg:cmd:/查在线`）
 * - 翻页按钮：[PAGE_PREFIX] + 页码（如 `krg:menu:page:2`）
 */
object MenuButtons {

    /** 命令按钮回调前缀（互动事件据此识别本流程）。 */
    const val ACTION_PREFIX = "krg:cmd:"

    /** 翻页按钮回调前缀。 */
    const val PAGE_PREFIX = "krg:menu:page:"

    /** 每页命令按钮数（5 行键盘：4 行命令 + 1 行翻页）。 */
    private const val COMMANDS_PER_PAGE = 4

    // ---------------- 菜单发送 ----------------

    /**
     * /菜单 命令入口：发送第 1 页（被动回复触发命令的消息）。
     *
     * @return 是否成功发出按钮消息（失败时调用方回退纯文本菜单）
     */
    fun sendFirstPage(plugin: KERONG, event: GroupMessageEvent): Boolean {
        val commands = visibleCommands(plugin)
        if (commands.isEmpty()) return false
        val keyboard = pageKeyboard(commands, 0)
        val message = pageText(plugin, 0, pageCount(commands.size))
        val user = try {
            val contact = event.sender
            contact?.openid ?: contact?.id ?: ""
        } catch (_: Throwable) {
            ""
        }
        val content = if (user.isNotEmpty()) "<@$user>\n$message" else message
        return try {
            QClient.replyMarkdown(event, content, keyboard)
        } catch (_: Throwable) {
            false
        }
    }

    /** 纯文本兜底清单（键盘被平台拒收时）：一行一个命令。 */
    fun plainList(plugin: KERONG): String {
        return visibleCommands(plugin).joinToString("\n") { (_, command) -> command }
    }

    // ---------------- 按钮互动 ----------------

    /**
     * 按钮点击处理（菜单命令按钮 / 翻页按钮）。
     *
     * @param groupOpenId 互动事件所属群
     * @param userOpenId 点击者 OpenId
     * @param buttonData 按钮 actionData
     * @param respond 互动回应（先回应后操作，回应码见 [InteractionAcker]）
     * @return 是否接管了这次互动（不属于本流程返回 null 交后续流程）
     */
    fun onInteraction(
        groupOpenId: String,
        userOpenId: String,
        buttonData: String,
        respond: (Int) -> Unit,
    ): Boolean? {
        if (buttonData.startsWith(PAGE_PREFIX)) {
            respond(0)
            val page = buttonData.removePrefix(PAGE_PREFIX).toIntOrNull() ?: 0
            sendPage(groupOpenId, page)
            return true
        }
        if (!buttonData.startsWith(ACTION_PREFIX)) return null
        val commandText = buttonData.removePrefix(ACTION_PREFIX).trim()
        if (commandText.isEmpty() || groupOpenId.isEmpty()) {
            respond(1)
            return true
        }
        if (userOpenId.isBlank()) {
            // 拿不到点击者身份：无法以任何人身份执行命令
            respond(4)
            return true
        }
        respond(0)
        dispatchAsClicker(groupOpenId, userOpenId, commandText)
        return true
    }

    /** 向指定群发送指定页（翻页按钮触发，主动消息）。 */
    private fun sendPage(groupOpenId: String, page: Int) {
        val plugin = cn.kerong.bot.provider.BotShared.instance ?: return
        val commands = visibleCommands(plugin)
        if (commands.isEmpty()) return
        val pages = pageCount(commands.size)
        val index = page.coerceIn(0, pages - 1)
        QClient.sendMarkdownToGroup(groupOpenId, pageText(plugin, index, pages), pageKeyboard(commands, index))
    }

    /**
     * 以点击者身份执行命令：构造合成群消息事件（无真实消息 id，命令回复
     * 走主动消息）后经 [QClient.dispatchSynthetic] 进正常命令分发——
     * 管理员校验 / 功能开关 / 群白名单 / 多服务器路由与手打命令完全一致。
     */
    private fun dispatchAsClicker(groupOpenId: String, userOpenId: String, commandText: String) {
        try {
            val starter = QClient.starter ?: return
            val bot = starter.bot ?: return
            val raw = RawMessage().setContent(commandText)
            val metadata = JSONObject().apply {
                put("group_openid", groupOpenId)
                put("author", JSONObject().apply {
                    put("id", userOpenId)
                    put("member_openid", userOpenId)
                })
            }
            val synthetic = BaseGroupMessageEvent(raw, metadata, bot)
            val dispatched = QClient.dispatchSynthetic(synthetic)
            QqBindManager.logFileOnly(
                "[菜单按钮] 群=$groupOpenId 用户=$userOpenId 命令=$commandText 分发=${if (dispatched) "已执行" else "未命中"}"
            )
        } catch (error: Throwable) {
            QqBindManager.logFileOnly("[菜单按钮] 命令分发异常（$commandText）: ${error.message}")
        }
    }

    // ---------------- 页面构建 ----------------

    /**
     * 收集当前可见的菜单命令（label → 命令文本），可见性与 QQ 快捷面板
     * 同一套功能开关（commands 段 / QQ 绑定 / 签到 / 经济 / AI / 集群）。
     */
    fun visibleCommands(plugin: KERONG): List<Pair<String, String>> {
        fun commandEnabled(name: String): Boolean = try {
            plugin.commandList()[name] != false
        } catch (_: Throwable) {
            true
        }
        var qqBindEnabled = false
        var personalInfoEnabled = true
        var checkinEnabled = false
        var economyAvailable = false
        var aiEnabled = false
        try {
            val manager = QqBindManager.getInstance()
            qqBindEnabled = manager.isEnabled
            personalInfoEnabled = manager.isPersonalInfoEnabled
            checkinEnabled = manager.isCheckinEnabled
            economyAvailable = cn.kerong.bot.events.commands.CheckInCommands().isEconomyAvailable()
            aiEnabled = manager.isAiEnabled
        } catch (_: Throwable) {
        }
        val list = mutableListOf<Pair<String, String>>()
        // 查询类在前（最常用），管理类与带参数类在后
        if (commandEnabled("查在线")) list.add("查在线" to "/查在线")
        if (commandEnabled("查性能")) list.add("查性能" to "/查性能")
        if (commandEnabled("玩家名片") && qqBindEnabled) list.add("玩家名片" to "/玩家名片")
        if (commandEnabled("查信息") && qqBindEnabled && personalInfoEnabled) list.add("查信息" to "/查信息")
        if (commandEnabled("在线排行榜")) list.add("在线排行榜" to "/在线排行榜")
        if (commandEnabled("财富榜") && economyAvailable) list.add("财富榜" to "/财富榜")
        if (commandEnabled("签到") && checkinEnabled && qqBindEnabled) list.add("签到" to "/签到")
        if (commandEnabled("全量")) list.add("全量" to "/全量")
        if (commandEnabled("AI管理") && aiEnabled) list.add("AI管理" to "/AI管理")
        if (commandEnabled("切换服务器") && ClusterState.isMultiServer) list.add("切换服务器" to "/切换服务器")
        if (commandEnabled("重新绑定") && qqBindEnabled) list.add("重新绑定" to "/重新绑定")
        if (commandEnabled("黑名单") && qqBindEnabled) list.add("黑名单" to "/黑名单")
        if (commandEnabled("解除黑名单") && qqBindEnabled) list.add("解除黑名单" to "/解除黑名单")
        // 带参数命令：点击后回复用法提示
        if (commandEnabled("绑定") && qqBindEnabled) list.add("绑定" to "/绑定")
        if (commandEnabled("发信息")) list.add("发信息" to "/发信息")
        if (commandEnabled("执行命令")) list.add("执行命令" to "/执行命令")
        if (commandEnabled("执行")) list.add("执行" to "/执行")
        if (commandEnabled("管理员执行")) list.add("管理员执行" to "/管理员执行")
        return list
    }

    /** 总页数。 */
    private fun pageCount(commandCount: Int): Int =
        if (commandCount == 0) 0 else (commandCount + COMMANDS_PER_PAGE - 1) / COMMANDS_PER_PAGE

    /**
     * 构建第 [pageIndex]（0 起）页的键盘：命令按钮每行一个；多页时末行
     * 放翻页按钮（首页只有「下一页」、末页只有「上一页」、中间页两者都有）。
     */
    private fun pageKeyboard(commands: List<Pair<String, String>>, pageIndex: Int): io.github.kloping.qqbot.entities.ex.Keyboard {
        val pages = pageCount(commands.size)
        val from = pageIndex * COMMANDS_PER_PAGE
        val pageCommands = commands.subList(from, minOf(from + COMMANDS_PER_PAGE, commands.size))
        val rows = mutableListOf<List<Keyboards.Button>>()
        for ((_, entry) in pageCommands.withIndex()) {
            val commandText = entry.second
            rows.add(listOf(
                Keyboards.Button(
                    label = entry.first,
                    visitedLabel = entry.first,
                    actionData = "$ACTION_PREFIX$commandText",
                    style = 1,
                )
            ))
        }
        if (pages > 1) {
            val nav = mutableListOf<Keyboards.Button>()
            if (pageIndex > 0) {
                nav.add(Keyboards.Button(
                    label = "← 上一页",
                    visitedLabel = "← 上一页",
                    actionData = "$PAGE_PREFIX${pageIndex - 1}",
                    style = 0,
                ))
            }
            if (pageIndex < pages - 1) {
                nav.add(Keyboards.Button(
                    label = "下一页 →",
                    visitedLabel = "下一页 →",
                    actionData = "$PAGE_PREFIX${pageIndex + 1}",
                    style = 1,
                ))
            }
            if (nav.isNotEmpty()) rows.add(nav)
        }
        return Keyboards.buildRows(rows)
    }

    /** 第 [pageIndex]（0 起）页的文案：menu 模板（{page}/{pages}/{version}）。 */
    private fun pageText(plugin: KERONG, pageIndex: Int, pages: Int): String {
        var message = MessageTemplates.render("menu", mapOf(
            "page" to (pageIndex + 1).toString(),
            "pages" to pages.toString(),
            "version" to plugin.pluginVersion,
        ))
        if (message.isEmpty()) message = "### 指令菜单（${pageIndex + 1}/$pages）"
        return message
    }
}
