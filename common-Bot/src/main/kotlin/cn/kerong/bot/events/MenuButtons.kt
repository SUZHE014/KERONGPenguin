package cn.kerong.bot.events

import cn.kerong.bot.ClusterState
import cn.kerong.bot.KERONG
import cn.kerong.bot.QClient
import cn.kerong.bot.ReplySeqGuard
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
 * ## 按钮形态（两类，按命令是否需要补参数分流）
 *
 * 群聊里「点击即执行」只有**回调按钮**（actionType=1）一种实现方式：
 * 官方文档明确指令按钮的 enter 自动发送仅单聊可用，群聊点击指令按钮只会
 * 把 @机器人 + 命令插进输入框等用户手动发送。因此：
 * - **无参命令**（查在线 / 查性能 / 玩家名片 / 排行榜 / 签到 / 全量 /
 *   AI管理 / 切换服务器 / 重新绑定 / 黑名单 / 解除黑名单…）用回调按钮：
 *   点击 → 机器人收到互动事件 → 3 秒内回应 → 以点击者身份执行命令并
 *   正常回复结果，一步到位；
 * - **需补参数的命令**（绑定 / 发信息 / 执行命令 / 执行 / 管理员执行）用
 *   **指令按钮**（actionType=2）：点击把命令复制进输入栏，用户补全参数
 *   后手动发送——这类命令点回调只能得到一句用法提示，不如直接填进输入框。
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

    /**
     * 需要用户补参数的命令：菜单里用**指令按钮**（点击复制进输入栏），
     * 其余命令用回调按钮（点击即执行）。命令名与 [visibleCommands]
     * 的 label 一致。
     */
    private val INPUT_COMMANDS = setOf("绑定", "发信息", "执行命令", "执行", "管理员执行")

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
     * 以点击者身份执行命令：构造合成群消息事件后经
     * [QClient.dispatchSynthetic] 进正常命令分发——管理员校验 / 功能开关 /
     * 群白名单 / 多服务器路由与手打命令完全一致。
     *
     * ## 修复「QQ 显示操作成功但机器人没反应」（1.7.5 覆盖第三轮真根因）
     *
     * 旧版合成事件的 metadata 只写了 group_openid，而 SDK 的
     * getGroupOpenId() 实际走 Group.getId()（读 metadata 的 **group_id**
     * 字段）——取出来是 null，[GroupMessageHandler.dispatchSynthetic] 的
     * 群白名单检查「groupId == null → 拦截」直接把命令挡下，命令从未
     * 执行（回应已发出所以 QQ 显示操作成功）。现在 metadata 同时写
     * group_id 与 group_openid，两个读取通道都拿得到。
     *
     * 回复通道同理修复：合成事件本身没有消息 id，被动回复（msg_id 引用）
     * 无从谈起。现从 [RecentGroupMessages] 轮转取本群未过期真实消息
     * id 塞进 metadata 的 id 字段与 RawMessage.id，命令回复即走正常
     * **被动回复**（与手打命令一致，不受主动消息配额限制）；连续点击
     * 依次引用不同的消息 id（被动回复对同一条消息约 5 次上限，轮转分摊
     * 防平台 40034128 拒收）；群内超过 4 分钟没有任何消息时取不到，回复
     * 自动落为主动消息（仍可送达）。
     * msg_seq 经 [ReplySeqGuard] 分配新基数，与原消息已用的序号不重叠
     * （防平台 40054005 消息去重拒收）。
     *
     * ## 修复「管理员点按钮被判无权限」（1.7.5.1）
     *
     * 按钮回调（INTERACTION_CREATE）只带 group_openid + group_member_openid，
     * **不带 member_role**——群消息事件（GROUP_AT_MESSAGE_CREATE）的
     * author 块才有；合成事件缺角色字段时 admin.mode=qq 的管理员判定
     * （[CommandSupport.memberRole]）对群主/管理员一律 false。现从
     * [MemberRoleCache]（每条真实群消息登记发送者角色）取点击者最近
     * 已知角色写进 author.member_role，与手打命令判定一致；未知角色
     * （30 分钟未发言）不写，回退 manual 名单路径。
     */
    private fun dispatchAsClicker(groupOpenId: String, userOpenId: String, commandText: String) {
        try {
            val starter = QClient.starter ?: return
            val bot = starter.bot ?: return
            val recentMsgId = RecentGroupMessages.nextId(groupOpenId)
            // 点击者群身份：按钮回调（INTERACTION_CREATE）只带 group_member_openid
            // 不带 member_role，管理员判定（admin.mode=qq）无角色可用——从
            // [MemberRoleCache] 取该成员最近已知角色写进合成事件（1.7.5.1 修复
            // 管理员点按钮被判无权限）；未知（30 分钟未发言）不写，回退 manual 名单
            val clickerRole = MemberRoleCache.roleOf(groupOpenId, userOpenId)
            val raw = RawMessage().setContent(commandText)
            if (recentMsgId != null) raw.setId(recentMsgId)
            val metadata = JSONObject().apply {
                // group_id：SDK getGroupOpenId() → Group.getId() 的读取字段
                //（群白名单 / 集群路由 / 回复目标都靠它，旧版缺失即根因）
                put("group_id", groupOpenId)
                // group_openid：Group.getOpenid() → getCid() 的发送目标字段
                put("group_openid", groupOpenId)
                // id：BaseMessageEvent.getMsgId() 的读取字段（被动回复 msg_id）
                if (recentMsgId != null) put("id", recentMsgId)
                put("author", JSONObject().apply {
                    put("id", userOpenId)
                    put("member_openid", userOpenId)
                    // member_role：CommandSupport.memberRole 的读取字段——群主/
                    // 管理员点管理类按钮不再被判无权限
                    if (clickerRole != null) put("member_role", clickerRole)
                })
            }
            val synthetic = BaseGroupMessageEvent(raw, metadata, bot)
            if (recentMsgId != null) {
                // 被动回复序号：与原消息已用序号错开（基数 +100000 递进）
                synthetic.setMsgSeq(ReplySeqGuard.allocateBase(recentMsgId))
            }
            val dispatched = QClient.dispatchSynthetic(synthetic)
            QqBindManager.logFileOnly(
                "[菜单按钮] 群=$groupOpenId 用户=$userOpenId 命令=$commandText " +
                    "被动id=${recentMsgId ?: "无(主动消息)"} 角色=${clickerRole ?: "未知"} " +
                    "分发=${if (dispatched) "已执行" else "未命中"}"
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
        // 查询类在前（最常用），管理类在后，需补参数的命令最后（指令按钮）
        if (commandEnabled("查在线")) list.add("查在线" to "/查在线")
        if (commandEnabled("查性能")) list.add("查性能" to "/查性能")
        if (commandEnabled("玩家名片") && qqBindEnabled) list.add("玩家名片" to "/玩家名片")
        if (commandEnabled("查信息") && qqBindEnabled && personalInfoEnabled) list.add("查信息" to "/查信息")
        if (commandEnabled("在线排行榜")) list.add("在线排行榜" to "/在线排行榜")
        if (commandEnabled("财富榜") && economyAvailable) list.add("财富榜" to "/财富榜")
        if (commandEnabled("签到") && checkinEnabled && qqBindEnabled) list.add("签到" to "/签到")
        // 全量转发总配置关闭时按钮同步消失（与 QQ 快捷面板同口径）
        if (commandEnabled("全量") && fullAmountEnabled(plugin)) list.add("全量" to "/全量")
        if (commandEnabled("AI管理") && aiEnabled) list.add("AI管理" to "/AI管理")
        if (commandEnabled("切换服务器") && ClusterState.isMultiServer) list.add("切换服务器" to "/切换服务器")
        if (commandEnabled("重新绑定") && qqBindEnabled) list.add("重新绑定" to "/重新绑定")
        if (commandEnabled("黑名单") && qqBindEnabled) list.add("黑名单" to "/黑名单")
        if (commandEnabled("解除黑名单") && qqBindEnabled) list.add("解除黑名单" to "/解除黑名单")
        // 需补参数的命令：点击复制进输入栏（指令按钮），用户补全后手动发送
        if (commandEnabled("绑定") && qqBindEnabled) list.add("绑定" to "/绑定")
        if (commandEnabled("发信息")) list.add("发信息" to "/发信息")
        if (commandEnabled("执行命令")) list.add("执行命令" to "/执行命令")
        if (commandEnabled("执行")) list.add("执行" to "/执行")
        if (commandEnabled("管理员执行")) list.add("管理员执行" to "/管理员执行")
        return list
    }

    /** 全量转发总配置是否开启（features.full-amount，异常时按关闭处理）。 */
    private fun fullAmountEnabled(plugin: KERONG): Boolean = try {
        plugin.fullAmount
    } catch (_: Throwable) {
        false
    }

    /** 总页数。 */
    private fun pageCount(commandCount: Int): Int =
        if (commandCount == 0) 0 else (commandCount + COMMANDS_PER_PAGE - 1) / COMMANDS_PER_PAGE

    /**
     * 构建第 [pageIndex]（0 起）页的键盘：命令按钮每行一个；多页时末行
     * 放翻页按钮（首页只有「下一页」、末页只有「上一页」、中间页两者都有）。
     *
     * 按钮类型按命令分流：需补参数的命令（[INPUT_COMMANDS]）用指令按钮
     * （actionType=2，点击复制进输入栏），其余用回调按钮（actionType=1，
     * 点击即执行）。
     */
    private fun pageKeyboard(commands: List<Pair<String, String>>, pageIndex: Int): io.github.kloping.qqbot.entities.ex.Keyboard {
        val pages = pageCount(commands.size)
        val from = pageIndex * COMMANDS_PER_PAGE
        val pageCommands = commands.subList(from, minOf(from + COMMANDS_PER_PAGE, commands.size))
        val rows = mutableListOf<List<Keyboards.Button>>()
        for ((_, entry) in pageCommands.withIndex()) {
            val isInputCommand = entry.first in INPUT_COMMANDS
            rows.add(listOf(
                Keyboards.Button(
                    label = entry.first,
                    visitedLabel = entry.first,
                    actionData = if (isInputCommand) entry.second else "$ACTION_PREFIX${entry.second}",
                    style = 1,
                    // 指令按钮（2）= 点击复制进输入栏；回调按钮（1）= 点击即执行
                    actionType = if (isInputCommand) 2 else 1,
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
