package cn.kerong.bot.events

import cn.kerong.bot.ClusterState
import cn.kerong.bot.KERONG
import cn.kerong.bot.QClient
import cn.kerong.bot.ReplySeqGuard
import cn.kerong.bot.events.commands.CommandAdminRegistry
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
 * ## 布局与分页（1.7.5.4 排版改版）
 *
 * 需要独占一行的按钮（文字超过 3 字，如「玩家名片」「在线排行榜」
 * 「切换服务器」「重新绑定」「解除黑名单」「执行命令」「管理员执行」）
 * 排在菜单最上面，每行一个；文字 ≤ 3 字的按钮（含「AI管理」按 3 字
 * 计）一行放两个。每页 4 行命令 + 末行翻页按钮（「← 上一页」「下一页
 * →」按需出现）；平台键盘硬限制 5 行 × 每行 5 按钮，全部命令分多页
 * 展示。按钮可见性与 QQ 快捷面板同一套功能开关。
 *
 * ## 回调数据
 *
 * - 普通命令按钮：[CMD_PREFIX] + 命令文本（如 `krg:cmd:/查在线`）
 * - 平台门禁管理按钮：[ACMD_PREFIX] + 命令文本（如 `krg:acmd:/黑名单`，
 *   仅 admin.mode=qq/both 下使用，配合 permission.type=1——非管理员点不动）
 * - 翻页按钮：[PAGE_PREFIX] + 页码（如 `krg:menu:page:2`）
 */
object MenuButtons {

    /** 命令按钮回调前缀（互动事件据此识别本流程）。 */
    const val ACTION_PREFIX = "krg:cmd:"

    /**
     * 平台门禁管理命令按钮回调前缀（1.7.5.2 覆盖）：这类按钮带
     * permission.type=1 下发，点击到达即意味着 QQ 平台已实时校验过
     * 点击者是现任群主 / 群管理员（撤权立即点不动）——分发时直接把
     * 平台已验身份写进合成事件，不再查询任何接口。
     */
    const val ACMD_PREFIX = "krg:acmd:"

    /** 翻页按钮回调前缀。 */
    const val PAGE_PREFIX = "krg:menu:page:"

    /** 每页命令行数（5 行键盘：4 行命令 + 1 行翻页）。 */
    private const val COMMAND_ROWS_PER_PAGE = 4

    /** 一行两个短按钮的宽度门槛：文字不超过 3 字（含）的按钮一行放两个。 */
    private const val SHORT_BUTTON_LABEL_CHARS = 3

    /** 独占一行的「长」按钮文字上限之外特例：这些按短按钮计（用户口径「AI管理」按 3 字）。 */
    private val SHORT_LABEL_EXCEPTIONS = setOf("AI管理")

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
        val gate = adminGateFor(plugin, try {
            event.groupOpenId ?: event.groupId ?: ""
        } catch (_: Throwable) {
            ""
        })
        val rows = layoutRows(commands, gate)
        val keyboard = pageKeyboard(rows, 0)
        val message = pageText(plugin, 0, pageCount(rows.size))
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
        val isAdminGated = buttonData.startsWith(ACMD_PREFIX)
        if (!isAdminGated && !buttonData.startsWith(ACTION_PREFIX)) return null
        val commandText = buttonData.removePrefix(if (isAdminGated) ACMD_PREFIX else ACTION_PREFIX).trim()
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
        dispatchAsClicker(groupOpenId, userOpenId, commandText, isAdminGated)
        return true
    }

    /** 向指定群发送指定页（翻页按钮触发，主动消息）。 */
    private fun sendPage(groupOpenId: String, page: Int) {
        val plugin = cn.kerong.bot.provider.BotShared.instance ?: return
        val commands = visibleCommands(plugin)
        if (commands.isEmpty()) return
        val rows = layoutRows(commands, adminGateFor(plugin, groupOpenId))
        val pages = pageCount(rows.size)
        val index = page.coerceIn(0, pages - 1)
        QClient.sendMarkdownToGroup(
            groupOpenId,
            pageText(plugin, index, pages),
            pageKeyboard(rows, index),
        )
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
     * ## 管理员判定（1.7.5.4：按钮判定收敛到下发处 + 验身标记）

     * 按钮交互的所有管理员命令只在按钮下发处（平台侧）判定，与菜单
     * 一致——点击到达的 krg:acmd: 按钮即代表 QQ 平台已实时验身（点击
     * 者此刻是群主 / 群管理员，撤权立即点不动）；命令链路凭
     * [cn.kerong.bot.events.commands.CommandSupport.BUTTON_ADMIN_VERIFIED]
     * 验身标记直接放行（QQ 官方消息 payload 无此字段，真实消息无法
     * 伪造），不再因合成事件缺省没有群角色字段而误报「无权限」。
     * MANUAL（名单）模式下管理类按钮改指令按钮（actionType=2 填输入
     * 栏），不走本回调路径——授权交给文本命令路径按名单校验。
     *
     * ## 管理员判定（1.7.5.2 覆盖：平台门禁，零接口零缓存）
     *
     * 按钮回调（INTERACTION_CREATE）只带 group_openid + group_member_openid，
     * **不带 member_role**——旧版为此先后用过 30 分钟角色缓存（撤权有
     * 30 分钟窗口，安全隐患）与官方群成员接口实时查询（需平台白名单
     * 接口权限，申请麻烦），均已移除。现改为**双门禁**：
     *
     * 1. **平台门禁（主判定）**——admin.mode=qq/both 下，管理类按钮
     *    （[CommandAdminRegistry] 注解自动标记）以 permission.type=1 下发，
     *    QQ 平台在点击时实时校验点击者是否现任群主 / 群管理员：撤掉
     *    管理员立即点不动，回调根本不会到达机器人；点击到达即代表
     *    平台已验身（[ACMD_PREFIX] 回调携带这一事实），分发时把
     *    member_role=admin 与 krg_btn_admin 验身标记写进合成事件，与
     *    手打命令判定完全一致；
     * 2. **插件门禁（兑底）**——命令分发链路内的 requireAdmin 原样生效：
     *    admin.mode=config 下按钮人人可点，由 [CommandSupport.isAdmin]
     *    按 admin.openids 名单实时判定（名单增删立即生效，无缓存）；
     *    平台门禁下发的旧菜单在群模式切换后点击，同样由命令链路按
     *    当前模式重新判定，不首平台旧门禁放行。
     *
     * 已知边界：both 模式下仅往 admin.openids 名单里（非群管理员）的人
     * 点不动管理按钮（平台权限字段只认群角色，无法表达「群管理员 ∪
     * 名单」并集）——手打命令不受影响，补救办法是给其群管理员身份。
     */
    fun dispatchAsClicker(
        groupOpenId: String,
        userOpenId: String,
        commandText: String,
        platformVerifiedAdmin: Boolean,
    ) {
        try {
            val starter = QClient.starter ?: return
            val bot = starter.bot ?: return
            val recentMsgId = RecentGroupMessages.nextId(groupOpenId)
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
                    // member_role：平台门禁按钮（krg:acmd:）点击到达 = QQ
                    // 已实时验身（点击者此刻是群主/群管理员，撤权立即点
                    // 不动）；未门禁按钮不写角色，管理员判定按名单路径走
                    if (platformVerifiedAdmin) put("member_role", "admin")
                    // 1.7.5.4：按钮验身标记（isAdmin 凭此直接放行；QQ 官方
                    // 消息 payload 无此字段，真实手打消息不可能携带）
                    if (platformVerifiedAdmin) {
                        put(cn.kerong.bot.events.commands.CommandSupport.BUTTON_ADMIN_VERIFIED, "1")
                    }
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
                    "被动id=${recentMsgId ?: "无(主动消息)"} " +
                    "身份=${if (platformVerifiedAdmin) "平台已验管理员" else "未验(名单判定)"} " +
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

    /**
     * 本群管理类按钮是否走**平台门禁**（permission.type=1 + [ACMD_PREFIX]）：
     * admin.mode=qq/both 时开启（QQ 平台点击时实时校验群主/群管理员，
     * 撤权立即点不动）；admin.mode=config 时关闭（按钮人人可点，由命令
     * 链路按 admin.openids 名单实时判定）。
     *
     * 群级模式覆盖（历史遗留状态段）只影响按钮门禁，不影响手打命令。
     */
    fun adminGateFor(plugin: KERONG, groupOpenId: String): Boolean = try {
        val defaultMode = cn.kerong.bot.datapack.AdministratorAccessMode.fromConfig(plugin.adminMode())
        val effective = cn.kerong.bot.state.CommandRepositories.groupSettings
            .administratorMode(groupOpenId, defaultMode)
        effective != cn.kerong.bot.datapack.AdministratorAccessMode.MANUAL
    } catch (_: Throwable) {
        // 读取失败按开启门禁处理（更保守：qq/both 是推荐默认）
        true
    }

    /** 总页数（按行计：1.7.5.4 排版改版后每页 4 行命令行，独占行 1 命令 / 短行 2 命令）。 */
    private fun pageCount(rowCount: Int): Int =
        if (rowCount == 0) 0 else (rowCount + COMMAND_ROWS_PER_PAGE - 1) / COMMAND_ROWS_PER_PAGE

    /**
     * 全部命令按新排版行化：独占行按钮（长文字）在前、短按钮两两成行
     * 在后（组内各自保持 [visibleCommands] 原相对顺序）。
     *
     * 按钮文字（含「AI管理」按 3 字计）超过 3 字的独占一行；其余一行两个。
     */
    private fun layoutRows(
        commands: List<Pair<String, String>>,
        adminGate: Boolean,
    ): List<List<Keyboards.Button>> {
        fun isShort(label: String): Boolean =
            label in SHORT_LABEL_EXCEPTIONS || label.length <= SHORT_BUTTON_LABEL_CHARS

        val rows = mutableListOf<List<Keyboards.Button>>()
        // 需要独占一行的按钮放到菜单最上面（1.7.5.4 用户口径）
        for (entry in commands.filterNot { (label, _) -> isShort(label) }) {
            rows.add(listOf(buttonFor(entry, adminGate)))
        }
        // 短按钮一行两个（末尾奇数个时最后一个单独占一行）
        val shorts = commands.filter { (label, _) -> isShort(label) }
        shorts.chunked(2).forEach { pair ->
            rows.add(pair.map { buttonFor(it, adminGate) })
        }
        return rows
    }

    /** 单个命令按钮构造（门禁 / actionType 按 1.7.5.4 口径分流）。 */
    private fun buttonFor(entry: Pair<String, String>, adminGate: Boolean): Keyboards.Button {
        val isInputCommand = entry.first in INPUT_COMMANDS
        // 管理类命令且本群开启平台门禁（qq/both）：按钮仅群主/群管理员可点
        val isAdminGated = adminGate && CommandAdminRegistry.isAdminCommand(entry.first)
        // MANUAL（名单模式）下的管理类命令：指令按钮填输入栏，命令文本无前缀，
        // 用户手动发送后走文本命令路径按名单校验（1.7.5.4 任务 B 口径）
        val isManualAdminCommand = !adminGate && !isInputCommand &&
                CommandAdminRegistry.isAdminCommand(entry.first)
        return Keyboards.Button(
            label = entry.first,
            visitedLabel = entry.first,
            actionData = when {
                isInputCommand -> entry.second
                isManualAdminCommand -> entry.second
                isAdminGated -> "$ACMD_PREFIX${entry.second}"
                else -> "$ACTION_PREFIX${entry.second}"
            },
            style = 1,
            // 指令按钮（2）= 点击复制进输入栏；回调按钮（1）= 点击即执行；
            // MANUAL（名单模式）下管理类命令同样走指令按钮（无平台门禁可用，
            // 授权交给文本命令路径按名单校验）
            actionType = if (isInputCommand || isManualAdminCommand) 2 else 1,
            // 权限：2 = 所有人；1 = 仅群主/群管理员（平台实时校验）
            permissionType = if (isAdminGated) 1 else 2,
        )
    }

    /**
     * 构建第 [pageIndex]（0 起）页的键盘：命令行取自 [layoutRows] 的行化
     * 结果（独占行长按钮置顶 + 短按钮一行两个），每页 [COMMAND_ROWS_PER_PAGE]
     * 行；多页时末行放翻页按钮（首页只有「下一页」、末页只有「上一页」、
     * 中间页两者都有）。
     *
     * ## 管理类按钮门禁（1.7.5.4 收敛口径）
     *
     * 按钮交互的管理员命令只在**按钮下发处（平台侧）**判定，与菜单一致：
     * - qq / both（[adminGate] 开启）：[CommandAdminRegistry] 标记的管理类
     *   命令按钮统一加**平台权限门禁**（permission.type=1，仅群主/群管理
     *   员可点，QQ 点击时实时校验）；回调按钮的回调数据改用 [ACMD_PREFIX]
     *   （点击到达 = 平台已验身，分发时写 [cn.kerong.bot.events.commands.CommandSupport.BUTTON_ADMIN_VERIFIED]
     *   验身标记，零接口零缓存）；
     * - MANUAL（名单模式）：管理类命令按钮改**指令按钮**（actionType=2，
     *   点击把命令复制进输入栏），不走平台门禁 / 回调，授权交给文本命令
     *   路径按名单校验（requireAdmin 实时判定 admin.openids，名单增删
     *   立即生效）；
     * - 需补参数的命令（[INPUT_COMMANDS]，含执行命令 / 管理员执行）本就
     *   是指令按钮：点击复制进输入栏，命令最终由用户手动发送，走正常
     *   消息路径自带真实角色。
     */
    private fun pageKeyboard(
        rows: List<List<Keyboards.Button>>,
        pageIndex: Int,
    ): io.github.kloping.qqbot.entities.ex.Keyboard {
        val pages = pageCount(rows.size)
        val from = pageIndex * COMMAND_ROWS_PER_PAGE
        val pageRows = mutableListOf<List<Keyboards.Button>>()
        pageRows.addAll(rows.subList(from, minOf(from + COMMAND_ROWS_PER_PAGE, rows.size)))
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
            if (nav.isNotEmpty()) pageRows.add(nav)
        }
        return Keyboards.buildRows(pageRows)
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
