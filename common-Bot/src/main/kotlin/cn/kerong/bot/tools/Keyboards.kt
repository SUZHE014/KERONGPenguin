package cn.kerong.bot.tools

import io.github.kloping.qqbot.entities.ex.Keyboard

/**
 * QQ 群消息按钮（内联键盘）构建工具。
 *
 * 按钮经 KeyboardBuilder → RowBuilder → ButtonBuilder 链式构建：
 * - actionType 1 = 回调按钮（点击触发 INTERACTION_CREATE 事件，actionData
 *   原样到达 button_data 字段；机器人收到后在 3 秒内回应，客户端无弹窗、
 *   交互一步到位——群聊里「点击即执行」只能用这一种按钮）；
 * - actionType 2 = 指令按钮（点击把 @机器人 + actionData 插入输入框，
 *   需用户自己补全后手动发送；官方文档明确 enter 自动发送**仅单聊可用**，
 *   群聊设置 enter 也不会自动发送）；
 * - 权限 2 = 所有人可点；权限 1 = 仅群主 / 群管理员可点（**平台侧点击时
 *   实时校验**，撤掉管理员立即点不了，无需机器人接口权限——1.7.5.2 覆盖；
 *   插件侧不缓存任何群角色）；
 * - 必须调用 row.build() 把行提交回 builder，否则 content.rows 为空、
 *   QQ 不渲染按钮（此处封装保证不漏）；
 * - visitedLabel 为点击者看到的置灰文案（如「已选择」），不影响他人；
 * - style：0 = 灰色线框，1 = 蓝色线框（本插件按钮统一蓝色）。
 *
 * 平台硬限制：键盘最多 5 行、每行最多 5 个按钮（超限整条消息被拒收）。
 */
object Keyboards {

    /** 单行按钮数上限（QQ 内联键盘每行最多 5 个按钮）。 */
    private const val BUTTONS_PER_ROW = 5

    /** 行数上限（QQ 内联键盘最多 5 行）。 */
    private const val MAX_ROWS = 5

    /** 按钮定义。 */
    data class Button(
        /** 按钮文案（点击前；QQ 限制最多 10 字符）。 */
        val label: String,
        /** 点击者看到的置灰文案（如「已选择」）。 */
        val visitedLabel: String,
        /** 回调数据 / 指令内容（回调按钮到达 button_data；指令按钮插入输入框）。 */
        val actionData: String,
        /** 样式：1 = 蓝色线框（默认），0 = 灰色线框。 */
        val style: Int = 1,
        /** 行为类型：1 = 回调按钮（默认），2 = 指令按钮，0 = 跳转按钮。 */
        val actionType: Int = 1,
        /** 指令按钮专用：true = 点击直接自动发送；false / null = 填入输入框待补全。 */
        val enter: Boolean? = null,
        /**
         * 操作权限类型（官方按钮协议 permission.type）：
         * 2 = 所有人可点（默认）；1 = 仅群主 / 群管理员可点（平台点击时
         * 实时校验，非管理员点不动，撤权立即生效）。
         *
         * 管理类按钮在 admin.mode=qq/both 下用 1（平台门禁，替代旧版
         * 接口实时查询 / 30 分钟缓存方案）；admin.mode=config 下保持 2，
         * 由插件按 admin.openids 名单实时判定。
         */
        val permissionType: Int = 2,
    )

    /**
     * 构建键盘：按钮自动按每行 [BUTTONS_PER_ROW] 个分行，超出
     * [BUTTONS_PER_ROW] × [MAX_ROWS] 上限的按钮被截断（调用方在
     * 文本里兜底提示其余走文字指令）。
     */
    fun build(buttons: List<Button>): Keyboard {
        val builder = Keyboard.KeyboardBuilder.create()
        val capped = buttons.take(BUTTONS_PER_ROW * MAX_ROWS)
        var row = builder.addRow()
        var countInRow = 0
        for (button in capped) {
            if (countInRow == BUTTONS_PER_ROW) {
                row.build() // 提交上一行（漏掉这步 QQ 不渲染按钮）
                row = builder.addRow()
                countInRow = 0
            }
            addButton(row, button)
            countInRow++
        }
        row.build()
        return builder.build()
    }

    /**
     * 构建竖排键盘：每行只放一个按钮（按钮与清单条目一一对应竖排，
     * 用于「解除黑名单」这类按钮需要逐条对应的场景）。
     *
     * 超过 [MAX_ROWS] 行自动回落为横排网格（每行 [BUTTONS_PER_ROW] 个），
     * 保证条目多时每个按钮仍然放得下（竖排最多 5 个）。
     */
    fun buildVertical(buttons: List<Button>): Keyboard {
        return if (buttons.size <= MAX_ROWS) {
            val builder = Keyboard.KeyboardBuilder.create()
            for (button in buttons.take(MAX_ROWS)) {
                val row = builder.addRow()
                addButton(row, button)
                row.build()
            }
            builder.build()
        } else {
            build(buttons)
        }
    }

    /**
     * 按显式行布局构建键盘（调用方逐行给出按钮列表，行数 ≤ [MAX_ROWS]、
     * 每行 ≤ [BUTTONS_PER_ROW]，超出部分截断）。
     *
     * 菜单分页用它实现「每行一个命令按钮 + 末行翻页按钮」的混合布局。
     */
    fun buildRows(rows: List<List<Button>>): Keyboard {
        val builder = Keyboard.KeyboardBuilder.create()
        for (rowButtons in rows.take(MAX_ROWS)) {
            if (rowButtons.isEmpty()) continue
            val row = builder.addRow()
            for (button in rowButtons.take(BUTTONS_PER_ROW)) {
                addButton(row, button)
            }
            row.build()
        }
        return builder.build()
    }

    /** 按定义给行添加按钮（统一应用样式 / 行为类型 / enter / 权限）。 */
    private fun addButton(row: Keyboard.RowBuilder, button: Button) {
        row.addButton()
            .setLabel(button.label)
            .setVisitedLabel(button.visitedLabel)
            .setStyle(button.style)
            .setActionType(button.actionType)
            .setActionData(button.actionData)
            .apply {
                // 指令按钮才设置 enter（回调 / 跳转按钮无此语义）
                if (button.actionType == 2 && button.enter != null) {
                    setActionEnter(button.enter)
                }
            }
            .setPermissionType(button.permissionType)
            .setUnSupportTips("请升级QQ客户端后再操作")
            .build()
    }
}
