package cn.kerong.bot.events

import cn.kerong.bot.tools.PluginFileLog
import io.github.kloping.qqbot.api.event.InterActionEvent
import io.github.kloping.qqbot.impl.ListenerHost

/**
 * QQ 消息按钮互动（INTERACTION_CREATE）监听（1.7.4 覆盖版新增）。
 *
 * 注册于 QQ 客户端启动时（与群消息监听并列的第二个 ListenerHost），
 * 把按钮点击分派给各按钮流程：
 * - [PlayerSelection]（多绑定玩家选择按钮，前缀 krg:psel:）
 * - [UnblacklistButtons]（解除黑名单按钮，前缀 krg:unban:）
 * - [ServerSelectButtons]（多服务器选择/切换按钮，前缀 krg:cluster:，
 *   1.7.4 第三轮覆盖版新增——多实例集群下每台实例都收到回调并各自记录
 *   群选择，仅按钮持有会话的实例处理其余两类按钮，天然去重）
 *
 * 官方要求：收到互动事件后必须回应（event.response(code)），否则客户端
 * 一直 loading 到超时；code 0 时客户端弹「操作成功」——只有真正处理成功
 * 才回 0，被拒绝 / 已过期 / 他人点击一律回非零静默。该回应口径与实现
 * 参考 HuHoBot（源码 https://github.com/HuHoBot/PenguinAgent 的 AgentInteractionListener）。
 */
class InterActionListener : ListenerHost() {

    @ListenerHost.EventReceiver
    fun onInterAction(event: InterActionEvent) {
        val interaction = try {
            event.interAction
        } catch (_: Throwable) {
            null
        } ?: run {
            tryRespond(event, 1)
            return
        }
        val buttonData = try {
            interaction.data?.resolved?.button_data
        } catch (_: Throwable) {
            null
        }
        if (buttonData.isNullOrEmpty()) {
            tryRespond(event, 1)
            return
        }
        // 旧版 kloping SDK 的访问器为下划线风格（getGroup_openid 等），
        // 与 HuHoBot 使用的新版 SDK（getGroupOpenid）不同
        val groupOpenId = try {
            interaction.group_openid.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val userOpenId = try {
            interaction.group_member_openid.orEmpty()
        } catch (_: Throwable) {
            ""
        }

        // 回应只做一次；流程内按结果决定 0 / 非 0
        var responded = false
        val respondOnce: (Int) -> Unit = { code ->
            if (!responded) {
                responded = true
                tryRespond(event, code)
            }
        }

        try {
            val handled = when {
                buttonData.startsWith(PlayerSelection.ACTION_PREFIX) ->
                    PlayerSelection.onInteraction(groupOpenId, userOpenId, buttonData, respondOnce) != null
                buttonData.startsWith(UnblacklistButtons.ACTION_PREFIX) ->
                    UnblacklistButtons.onInteraction(groupOpenId, userOpenId, buttonData, respondOnce) != null
                buttonData.startsWith(ServerSelectButtons.ACTION_PREFIX) ->
                    ServerSelectButtons.onInteraction(groupOpenId, buttonData, respondOnce) != null
                else -> false
            }
            if (!handled) {
                // 不属于本插件流程的按钮（理论上不会出现）：静默
                respondOnce(1)
            }
        } catch (error: Throwable) {
            try {
                PluginFileLog.write("[按钮互动] 处理互动事件失败: ${error.message}")
            } catch (_: Throwable) {
            }
            respondOnce(1)
        } finally {
            // 流程里没有明确回应过时兜底静默，避免客户端一直 loading
            respondOnce(1)
        }
    }

    /** 回应互动事件（失败仅写文件日志——不影响按钮功能）。 */
    private fun tryRespond(event: InterActionEvent, code: Int) {
        try {
            event.response(code)
        } catch (error: Throwable) {
            try {
                PluginFileLog.write("[按钮互动] 回应互动事件失败: ${error.message}")
            } catch (_: Throwable) {
            }
        }
    }
}
