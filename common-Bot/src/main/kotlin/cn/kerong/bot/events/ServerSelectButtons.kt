package cn.kerong.bot.events

import cn.kerong.bot.ClusterState
import cn.kerong.bot.QClient
import cn.kerong.bot.tools.Keyboards
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * 多服务器「选择服务器 / 切换服务器」按钮。
 *
 * 两个入口共用一套按钮：
 * - 群未选择时执行任意命令 → 按消息哈希选出的仲裁实例弹出选择按钮
 *   （[GroupMessageHandler] 路由层调用 [sendSelector]）；
 * - /切换服务器 指令 → 当前处理该群的实例弹出按钮，已选服务器带
 *   「当前」标记。
 *
 * 按钮互动（INTERACTION_CREATE）与群消息一样广播到每条连接：
 * **每台实例都会收到点击回调**，各自记录群选择（[ClusterState.setSelectedServer]），
 * 从此该群的命令 / 聊天转发 / 播报都只由所选服务器处理。
 *
 * 回应口径（参考 HuHoBot 的按钮交互实现，
 * 源码 https://github.com/HuHoBot/PenguinAgent ）：
 * 收到互动必须 response(code)，0 = 客户端「操作成功」toast；多实例
 * 同时回应时平台只受理第一个，其余静默失败（无影响）。
 *
 * 切换完成的反馈不走额外群消息（避免占用每月主动消息配额）：
 * response(0) toast + 按钮置灰文案（visitedLabel「已切换」）即为确认。
 *
 * ## 「全局」按钮门禁（1.7.5.4）
 *
 * 集群成员名等于 [GLOBAL_MEMBER]（「全局」）的按钮是特权操作，复用
 * 菜单按钮的门禁口径：
 * - qq/both 下平台门禁（permission.type=1 + krg:acmd:/切换服务器 全局
 *   回调）：点击到达即平台已验管理员，命令链路凭
 *   [cn.kerong.bot.events.commands.CommandSupport.BUTTON_ADMIN_VERIFIED]
 *   验身标记直接放行；
 * - MANUAL 名单模式下改指令按钮（actionType=2 点击把 /切换服务器 全局
 *   填入输入栏）：授权交给文本命令路径按名单校验；
 * - 旧版残留的无门禁回调按钮（krg:cluster:全局）点击时转发合成命令
 *   /切换服务器 全局（不带验身标记），由命令链路按**当前**模式重新
 *   判定（不因旧按钮形态放行）。
 */
object ServerSelectButtons {

    /** 按钮回调数据前缀（互动事件据此识别本流程）。 */
    const val ACTION_PREFIX = "krg:cluster:"

    /** 特权成员名：选中「全局」的按钮需要管理员（1.7.5.4 用户口径）。 */
    const val GLOBAL_MEMBER = "全局"

    /** 选择按钮的弹出节流（毫秒）：同群 60 秒内不重复弹（等待用户点击）。 */
    private const val PROMPT_THROTTLE_MILLIS = 60_000L

    /** 每群最近一次弹出选择按钮的时间戳（各实例本地节流）。 */
    private val lastPromptAt = ConcurrentHashMap<String, Long>()

    /**
     * 弹出「选择服务器」按钮（仲裁实例调用；节流内跳过）。
     *
     * @param reason 提示文案（未选择引导 / 切换服务器）
     * @return 是否实际弹出（节流内 / 发送失败 / 非多服务器时 false）
     */
    fun sendSelector(event: GroupMessageEvent, reason: String): Boolean {
        if (!ClusterState.isMultiServer) return false
        val group = try {
            event.groupOpenId ?: event.groupId ?: ""
        } catch (_: Throwable) {
            ""
        }
        if (group.isEmpty()) return false
        val now = System.currentTimeMillis()
        val last = lastPromptAt[group] ?: 0L
        if (now - last < PROMPT_THROTTLE_MILLIS) return false
        val members = ClusterState.serverMembers
        val current = ClusterState.selectedServer(group)
        // 本群管理员判定模式（群级覆盖后实际生效）：全局按钮门禁口径与菜单一致
        val plugin = cn.kerong.bot.provider.BotShared.instance
        val adminGate = plugin != null &&
            cn.kerong.bot.events.MenuButtons.adminGateFor(plugin, group)
        val keyboard = Keyboards.build(
            members.map { name ->
                if (name == GLOBAL_MEMBER && adminGate) {
                    // 「全局」特权按钮（1.7.5.4）qq/both：平台门禁回调——点击到达即
                    // QQ 已实时验身（仅群主/群管理员可点），命令链路凭验身标记放行
                    Keyboards.Button(
                        label = if (name == current) "$name（当前）" else name,
                        visitedLabel = if (name == current) "正在使用 $name" else "已切换到 $name",
                        actionData = "${cn.kerong.bot.events.MenuButtons.ACMD_PREFIX}/切换服务器 $name",
                        style = if (name == current) 0 else 1,
                        actionType = 1,
                        permissionType = 1,
                    )
                } else if (name == GLOBAL_MEMBER) {
                    // 「全局」特权按钮 MANUAL 名单模式：指令按钮填入 /切换服务器 全局，
                    // 授权交给文本命令路径按名单校验
                    Keyboards.Button(
                        label = if (name == current) "$name（当前）" else name,
                        visitedLabel = if (name == current) "正在使用 $name" else "已切换到 $name",
                        actionData = "/切换服务器 $name",
                        style = if (name == current) 0 else 1,
                        actionType = 2,
                        permissionType = 2,
                    )
                } else {
                    Keyboards.Button(
                        label = if (name == current) "$name（当前）" else name,
                        visitedLabel = if (name == current) "正在使用 $name" else "已切换到 $name",
                        actionData = "$ACTION_PREFIX$name",
                        style = if (name == current) 0 else 1,
                    )
                }
            }
        )
        val list = members.joinToString(" / ") { name ->
            if (name == current) "$name✓" else name
        }
        // 1.7.5.6 覆盖轮：选择器消息同样带 @（与 QQ 普通消息行为对齐——
        // 消息开头 @ 触发者，与 /菜单 首页及命令回复口径一致）
        val triggerUser = try {
            val contact = event.sender
            contact?.openid ?: contact?.id ?: ""
        } catch (_: Throwable) {
            ""
        }
        val messageText = "$reason\n当前可选服务器：$list\n点击按钮选择要操作的服务器："
        val content = if (triggerUser.isNotEmpty()) "<@$triggerUser>\n$messageText" else messageText
        val sent = try {
            QClient.replyMarkdown(event, content, keyboard)
        } catch (_: Throwable) {
            false
        }
        if (sent) {
            lastPromptAt[group] = now
        }
        return sent
    }

    /**
     * 按钮点击处理：记录群选择（每台实例都执行，广播同步）。
     *
     * 「全局」成员的旧形态回调（krg:cluster:全局，1.7.5.3 及更早的无门禁
     * 按钮）转发为合成命令 /切换服务器 全局（不带验身标记），由命令
     * 链路按当前模式重新判定管理员——不因旧按钮形态放行；新形态按钮
     * 不走本回调（qq/both 走 krg:acmd: 前缀由 [cn.kerong.bot.events.MenuButtons]
     * 处理，MANUAL 走指令按钮填输入栏不走回调）。
     *
     * @return 是否接管了这次互动（不属于本流程时返回 null）
     */
    fun onInteraction(
        groupOpenId: String,
        userOpenId: String,
        buttonData: String,
        respond: (Int) -> Unit,
    ): Boolean? {
        if (!buttonData.startsWith(ACTION_PREFIX)) return null
        val target = buttonData.removePrefix(ACTION_PREFIX)
        if (target.isEmpty() || groupOpenId.isEmpty()) {
            respond(1)
            return true
        }
        if (target !in ClusterState.serverMembers) {
            // 成员列表已变更（该服务器被移出集群）：静默拒绝
            respond(1)
            return true
        }
        if (target == GLOBAL_MEMBER && userOpenId.isNotEmpty()) {
            // 旧形态「全局」按钮：转发合成命令按当前模式重判（回应交给命令链路回复）
            respond(0)
            cn.kerong.bot.events.MenuButtons.dispatchAsClicker(
                groupOpenId, userOpenId, "/切换服务器 $target", false
            )
            return true
        }
        val previous = ClusterState.selectedServer(groupOpenId)
        ClusterState.setSelectedServer(groupOpenId, target)
        respond(0)
        cn.kerong.bot.tools.PluginFileLog.write(
            "[多服务器] 群=$groupOpenId 选择服务器：${previous ?: "（未选择）"} → $target" +
                "（本服=\"${ClusterState.selfServerName}\"${if (target == ClusterState.selfServerName) "，接管该群" else ""}）"
        )
        return true
    }

    /** 清空节流记录（/hbkrg reload 时调用）。 */
    fun clearAll() = lastPromptAt.clear()
}
