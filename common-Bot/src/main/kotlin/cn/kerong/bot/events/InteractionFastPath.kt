package cn.kerong.bot.events

import cn.kerong.bot.QClient
import cn.kerong.bot.tools.PluginFileLog
import com.alibaba.fastjson.JSON
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 消息按钮互动（INTERACTION_CREATE）快速回应路径。
 *
 * ## 背景：修复「按按钮后机器人正常回复操作，但 QQ 提示第三方连接失败」
 *
 * 官方要求收到按钮互动事件后 **3 秒内** PUT /interactions/{id} 回应，超时后
 * interaction_id 失效，客户端弹「第三方连接失败」。两重根因都已修复：
 *
 * 1. **回应从未送达（真根因）**——SDK 的 HTTP 代理层只处理 POST 注解，对
 *    声明 PUT 的互动回应接口既不设方法也不带请求体（实际发出无 body 的
 *    GET 被平台 405 拒绝），且 4xx/5xx 只记日志不抛异常。现改走
 *    [InteractionAcker] 直发 PUT（详见其 KDoc）；
 * 2. **回应排队被拖超（旧诊断，一并规避）**——SDK 事件分发走共享 8 线程池，
 *    命令处理（图片渲染 / AI 对话 / 反查扫盘）动辄数秒。本路径由
 *    [cn.kerong.bot.WssDiagnostics] 在**收到帧的第一时间**回调（先于 SDK
 *    解析与线程池分发），转投专用线程：校验（内存查表，微秒级）→ 立刻
 *    PUT 回应 → 再做实际操作，全程不与命令线程池竞争。
 *
 * 接管成功后吞掉该帧（钩子返回 false），SDK 不再重复分发。
 *
 * 解析失败 / 非本插件按钮 / 依赖未就绪时放行给 SDK 旧路径
 * （[InterActionListener] 兜底，回应同样走 [InteractionAcker]）。
 *
 * 回应码（官方语义）：0 = 成功；1 = 操作失败；2 = 操作频繁；3 = 重复
 * 操作；4 = 没有权限；5 = 仅管理员操作。同一 interaction_id 只能回应
 * 一次；多实例集群下每台实例都收到广播并各自回应时，平台只受理最先
 * 到达的一个，其余静默失败（无影响）。
 */
object InteractionFastPath {

    /** 专用线程池：每条互动一线程（按钮点击是人手频率，池规模天然极小）。 */
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        val thread = Thread(runnable, "qq-interaction-fastpath")
        thread.isDaemon = true
        thread
    }

    /** 本插件的按钮回调前缀集合（与 [InterActionListener] 的分发集合保持一致）。 */
    private val OWNED_PREFIXES = arrayOf(
        PlayerSelection.ACTION_PREFIX,
        UnblacklistButtons.ACTION_PREFIX,
        ServerSelectButtons.ACTION_PREFIX,
        MenuButtons.ACTION_PREFIX,
        MenuButtons.ACMD_PREFIX,
        MenuButtons.PAGE_PREFIX,
    )

    /**
     * 尝试在帧层接管一条 INTERACTION_CREATE 帧（WSS 读线程调用，必须快进快出）。
     *
     * 只做轻量解析与判断，实际处理（含网络回应）全部转投专用线程池；
     * 本方法本身仅微秒级开销，不阻塞 WebSocket 读循环。
     *
     * @return true = 已接管（调用方应吞掉本帧，SDK 不再分发）；
     *   false = 不属于本插件按钮 / 依赖未就绪 / 解析失败（放行 SDK 旧路径）
     */
    fun tryHandle(frameText: String): Boolean {
        val frame = try {
            JSON.parseObject(frameText)
        } catch (_: Throwable) {
            null
        } ?: return false
        val data = try {
            frame.getJSONObject("d")
        } catch (_: Throwable) {
            null
        } ?: return false
        val interactionId = data.getString("id") ?: return false
        val buttonData = try {
            data.getJSONObject("data")?.getJSONObject("resolved")?.getString("button_data")
        } catch (_: Throwable) {
            null
        }
        if (buttonData.isNullOrEmpty()) return false
        if (OWNED_PREFIXES.none { buttonData.startsWith(it) }) return false
        val groupOpenId = data.getString("group_openid") ?: ""
        val userOpenId = data.getString("group_member_openid") ?: ""
        // 依赖就绪检查：回应与命令分发都依赖已连接的会话
        if (QClient.starter == null) return false
        val startedAt = System.currentTimeMillis()
        try {
            executor.execute {
                try {
                    dispatch(interactionId, groupOpenId, userOpenId, buttonData, startedAt)
                } catch (error: Throwable) {
                    // 任何意外都不让客户端干等：尽力回应操作失败并留档
                    try {
                        InteractionAcker.respond(interactionId, 1)
                    } catch (_: Throwable) {
                    }
                    PluginFileLog.write("[按钮互动] 快速路径处理异常: ${error.message}")
                }
            }
        } catch (_: Throwable) {
            // 线程池异常（极端）：放行 SDK 旧路径兜底
            return false
        }
        return true
    }

    /**
     * 分发到各按钮流程（专用线程执行）：先回应后操作——各流程的校验全是
     * 内存查表，回应在毫秒级内发出 PUT，实际操作（移除黑名单 / 执行选中
     * 命令 / 记录集群选择 / 派发菜单命令）在回应之后进行。
     */
    private fun dispatch(
        interactionId: String,
        groupOpenId: String,
        userOpenId: String,
        buttonData: String,
        startedAt: Long,
    ) {
        // 1.7.5.6 覆盖轮：同一用户按钮互动冷却（bot.button-cooldown-seconds，
        // 默认 5 秒）——间隔未满直接回应「操作频繁」(2)，不再分发
        if (!ButtonInteractionCooldown.tryAcquire(groupOpenId, userOpenId)) {
            try {
                InteractionAcker.respond(interactionId, 2)
            } catch (_: Throwable) {
            }
            PluginFileLog.write(
                "[按钮互动] 已限频（冷却内）user=$userOpenId button=$buttonData " +
                    "耗时=${System.currentTimeMillis() - startedAt}ms"
            )
            return
        }
        var responded = false
        val respondOnce: (Int) -> Unit = { code ->
            if (!responded) {
                responded = true
                val delivered = try {
                    InteractionAcker.respond(interactionId, code)
                } catch (error: Throwable) {
                    PluginFileLog.write("[按钮互动] 回应异常（interaction=$interactionId）: ${error.message}")
                    false
                }
                PluginFileLog.write(
                    "[按钮互动] 回应 code=$code 送达=$delivered button=$buttonData " +
                        "耗时=${System.currentTimeMillis() - startedAt}ms"
                )
            }
        }
        val handled = when {
            buttonData.startsWith(PlayerSelection.ACTION_PREFIX) ->
                PlayerSelection.onInteraction(groupOpenId, userOpenId, buttonData, respondOnce) != null
            buttonData.startsWith(UnblacklistButtons.ACTION_PREFIX) ->
                UnblacklistButtons.onInteraction(groupOpenId, userOpenId, buttonData, respondOnce) != null
            buttonData.startsWith(ServerSelectButtons.ACTION_PREFIX) ->
                ServerSelectButtons.onInteraction(groupOpenId, userOpenId, buttonData, respondOnce) != null
            buttonData.startsWith(MenuButtons.ACTION_PREFIX) ||
                buttonData.startsWith(MenuButtons.ACMD_PREFIX) ||
                buttonData.startsWith(MenuButtons.PAGE_PREFIX) ->
                MenuButtons.onInteraction(groupOpenId, userOpenId, buttonData, respondOnce) != null
            else -> false
        }
        if (!handled) respondOnce(1)
    }
}
