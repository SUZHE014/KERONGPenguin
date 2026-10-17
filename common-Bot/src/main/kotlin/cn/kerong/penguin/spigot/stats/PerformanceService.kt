package cn.kerong.penguin.spigot.stats

import cn.kerong.bot.KERONG
import cn.kerong.bot.QClient
import cn.kerong.penguin.spigot.render.CardRenderPool
import cn.kerong.penguin.spigot.render.InfoCardAssets
import cn.kerong.penguin.spigot.render.PerformanceRenderer
import cn.kerong.penguin.spigot.render.RenderSettings
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * /查性能 命令图片输出服务（1.6.7 新增，1.6.7.1 调整）。
 *
 * 全流程异步（与 /查在线 一致）：机器人消息线程只做冷却判断
 * （render.command-cooldown-seconds，同一配置共用冷却秒数但独立计数），
 * 其余进入 [CardRenderPool]：
 * 1. 取平台性能快照（[KERONG.performanceSnapshot]：TPS / MSPT /
 *    JVM 堆 / 进程 CPU；核心不支持读取的项为 null，卡片显示「--」）；
 * 2. 指标固定四项（TPS / MSPT / 内存 / CPU，1.6.7.1 起不再展示
 *    在线人数，单项显隐配置已移除）；
 * 3. [PerformanceRenderer] 渲染毛玻璃卡片（背景与其他渲染共用
 *    [InfoCardAssets.resolveBackground]，render.performance.builtin-background
 *    默认内置底图）；
 * 4. 单图发送（性能数据实时变化，不做结果缓存；防刷屏靠用户冷却）。
 *
 * 命令总开关走 commands.查性能（与其他群命令一致：关闭后命令失效、
 * 快捷菜单按钮同步消失、回复 command-disabled 模板）。
 */
object PerformanceService {

    /** 用户冷却记录：OpenId → 上次触发时间。 */
    private val lastQueryAt = ConcurrentHashMap<String, Long>()

    /**
     * 渲染与发送全流程在共享渲染池执行，消息线程仅做冷却判断。
     */
    fun handle(plugin: KERONG, event: GroupMessageEvent, qqOpenId: String) {
        // 查询冷却（与在线列表共用 render.command-cooldown-seconds，独立计数）
        val cooldownMillis = RenderSettings.commandCooldownMillis()
        val now = System.currentTimeMillis()
        if (cooldownMillis > 0) {
            val lastAt = lastQueryAt[qqOpenId] ?: 0L
            if (now - lastAt < cooldownMillis) {
                cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "query-too-frequent")
                return
            }
            lastQueryAt[qqOpenId] = now
            if (lastQueryAt.size > 1024) lastQueryAt.clear()
        }

        try {
            CardRenderPool.submit {
                try {
                    // 1. 平台性能快照（null = 服务端不可用，如 NeoForge 未完成启动）
                    val snapshot = plugin.performanceSnapshot()
                    if (snapshot == null) {
                        cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "online-data-unavailable")
                        return@submit
                    }

                    // 2. 固定四项指标（1.6.7.1 起无单项显隐配置）
                    val height = PerformanceRenderer.measureHeight()
                    val dataDirectory = plugin.configFile?.parentFile
                    val background = InfoCardAssets.resolveBackground(
                        dataDirectory,
                        PerformanceRenderer.WIDTH,
                        height,
                        RenderSettings.performanceBuiltinBackground(),
                    )
                    val updateTime = SimpleDateFormat("HH:mm").format(Date())
                    val png = PerformanceRenderer.render(
                        PerformanceRenderer.PerformanceData(
                            serverName = plugin.serverName,
                            updateTime = updateTime,
                            snapshot = snapshot,
                            background = background,
                        )
                    )

                    // 4. 单图发送
                    if (png.isEmpty() || !QClient.replyWithImgBytes(event, png)) {
                        cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "image-send-failed")
                    }
                } catch (error: Throwable) {
                    // 失败日志带堆栈前几帧，与 /查在线 一致可事后追溯
                    plugin.log_error("[查性能] 性能卡片渲染失败: ${error.message}")
                    plugin.log_error(
                        "[查性能] 失败堆栈: " +
                            error.stackTraceToString().lineSequence().take(8).joinToString(" | ")
                    )
                    cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "performance-render-failed")
                }
            }
        } catch (_: Throwable) {
            // 理论上不会触发（队列无上限），兜底防止线程池异常时静默丢消息
            cn.kerong.penguin.spigot.qqbind.MessageTemplates.sendPlain(event, "server-busy")
        }
    }
}
