package cn.kerong.bot.tools

import io.github.kloping.qqbot.entities.ex.Keyboard

/**
 * QQ 群消息按钮（内联键盘）构建工具（1.7.4 覆盖版新增）。
 *
 * 交互模式参考同类产品 HuHoBot 的消息按钮（确定 / 取消键盘、点击回调、
 * 权限与身份校验）：文档 、
 * 源码 https://github.com/HuHoBot/PenguinAgent （UnbindConfirmation.kt）。
 *
 * kloping SDK 的键盘构建要点（与 HuHoBot 使用的 API 一致）：
 * - 按钮经 KeyboardBuilder → RowBuilder → ButtonBuilder 链式构建；
 * - actionType 1 = 回调（点击后触发 INTERACTION_CREATE 事件，actionData
 *   原样到达 button_data 字段）；权限 2 = 所有人可点；
 * - **必须调用 row.build() 把行提交回 builder**，否则 content.rows 为空、
 *   QQ 不渲染按钮（HuHoBot 踩过的坑，此处封装保证不漏）；
 * - visitedLabel 为点击者看到的置灰文案（如「已选择」），不影响他人。
 */
object Keyboards {

    /** 单行按钮数上限（QQ 内联键盘每行最多 5 个按钮）。 */
    private const val BUTTONS_PER_ROW = 5

    /** 行数上限（QQ 内联键盘最多 5 行）。 */
    private const val MAX_ROWS = 5

    /** 按钮定义。 */
    data class Button(
        /** 按钮文案（点击前）。 */
        val label: String,
        /** 点击者看到的置灰文案（如「已选择」）。 */
        val visitedLabel: String,
        /** 回调数据（到达 INTERACTION_CREATE 的 button_data）。 */
        val actionData: String,
        /** 样式：1 = 主按钮（蓝），0 = 次按钮（灰）。 */
        val style: Int = 1,
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
            row.addButton()
                .setLabel(button.label)
                .setVisitedLabel(button.visitedLabel)
                .setStyle(button.style)
                .setActionType(1)
                .setActionData(button.actionData)
                .setPermissionType(2)
                .setUnSupportTips("请升级QQ客户端后再操作")
                .build()
            countInRow++
        }
        row.build()
        return builder.build()
    }
}
