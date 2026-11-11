package cn.kerong.bot.events

import cn.kerong.bot.tools.PluginFileLog
import io.github.kloping.qqbot.api.event.InterActionEvent
import io.github.kloping.qqbot.impl.ListenerHost

/**
 * QQ 消息按钮互动（INTERACTION_CREATE）监听（兑底路径）。
 *
 * 注册于 QQ 客户端启动时（与群消息监听并列的第二个 ListenerHost），
 * 把按钮点击分派给各按钮流程：
 * - [PlayerSelection]（多绑定玩家选择按钮，前缀 krg:psel:）
 * - [UnblacklistButtons]（解除黑名单按钮，前缀 krg:unban:）
 * - [ServerSelectButtons]（多服务器选择/切换按钮，前缀 krg:cluster:，
 *   多实例集群下每台实例都收到回调并各自记录群选择，仅按钮持有会话的
 *   实例处理其余两类按钮，天然去重）
 * - [MenuButtons]（菜单命令/翻页按钮，前缀 krg:cmd: / krg:acmd: / krg:menu:）
 *
 * 本监听走 SDK 的共享线程池分发，作为**兑底路径**保留：本插件按钮的
 * 互动帧优先由 [InteractionFastPath] 在 WSS 帧层接管并即时回应（官方
 * 要求 3 秒内回应，排队易被命令处理拖超），仅在帧层未接管（依赖未就绪 /
 * 解析失败）时落到本监听。
 *
 * 回应统一走 [InteractionAcker] 直发 PUT——SDK 的 event.response 同样
 * 受 HTTP 代理层只处理 POST 的缺陷影响（发出的是无 body 的 GET，
 * 从未真正送达），不能再用。
 *
 * 官方回应码：0 = 成功；1 = 操作失败；2 = 操作频繁；3 = 重复操作；
 * 4 = 没有权限；5 = 仅管理员操作。
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
                buttonData.startsWith(MenuButtons.ACTION_PREFIX) ||
                    buttonData.startsWith(MenuButtons.ACMD_PREFIX) ->
                    MenuButtons.onInteraction(groupOpenId, userOpenId, buttonData, respondOnce) != null
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

    /** 回应互动事件（直发 PUT，不走 SDK 代理；失败仅写文件日志——不影响按钮功能）。 */
    private fun tryRespond(event: InterActionEvent, code: Int) {
        try {
            val id = event.interAction?.id ?: return
            InteractionAcker.respond(id, code)
        } catch (error: Throwable) {
            try {
                PluginFileLog.write("[按钮互动] 回应互动事件失败: ${error.message}")
            } catch (_: Throwable) {
            }
        }
    }
}
