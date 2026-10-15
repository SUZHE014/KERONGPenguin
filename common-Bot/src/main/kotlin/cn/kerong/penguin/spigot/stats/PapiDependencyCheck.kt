package cn.kerong.penguin.spigot.stats

import cn.kerong.bot.tools.PluginFileLog
import org.bukkit.Bukkit
import java.util.concurrent.ConcurrentHashMap

/**
 * PlaceholderAPI 依赖检测（1.6.6 新增）。
 *
 * 背景：金币（Vault Economy 直连）与 DeluxeTags 称号（实例 / 静态 API 直连）
 * 都不依赖 PlaceholderAPI；但以下功能需要 PlaceholderAPI 才能读取：
 * - PlayerTitle 称号（%playerTitle_use%）；
 * - PlayTimeManager 在线时长同步（%PTM_playtime_s_<名>%）；
 * - DeluxeTags 的占位符兜底层（%deluxetags_tag%，前三层失败才用到）。
 *
 * 玩家常装了 PlayerTitle / PlayTimeManager 却漏装 PlaceholderAPI，导致
 * 功能静默失效还以为插件坏了。现两级提示（都不刷屏）：
 * - 启动后延迟检测一次（等全部插件 enable 完，避免加载顺序误判）；
 * - 运行中第一次因缺 PlaceholderAPI 导致查询失败时，再提醒一次
 *   （每次会话最多一次，[NOTIFY_KEY] 去重）。
 *
 * 仅 Spigot 系平台有效；NeoForge 端没有 PlaceholderAPI 生态
 * （插件体系本身不同），检测与提示都不触发。
 */
object PapiDependencyCheck {

    /** 运行中「缺 PAPI」提示的会话级去重键。 */
    private const val NOTIFY_KEY = "papi-missing-runtime"

    /** 会话级已提示标记（启动检查与运行中提醒分开，各自最多一次）。 */
    private val notifiedKeys = ConcurrentHashMap.newKeySet<String>()

    /** PlaceholderAPI 是否已安装并启用。 */
    fun isPapiAvailable(): Boolean = try {
        val papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI")
        papi != null && papi.isEnabled
    } catch (_: Throwable) {
        false
    }

    /**
     * 启动后延迟检测（Spigot 端 onEnable 挂载，异步延迟 12 秒：
     * 错开启动高峰与更新检查的 8 秒，且确保所有插件已 enable）。
     *
     * 未安装时按「是否装了 PAPI 依赖插件」输出两档提示：
     * - 装了 PlayerTitle / PlayTimeManager：功能已装但无法工作，强烈建议补装；
     * - 都没装：温和提示一次（将来装称号 / 时长同步类插件前先装 PAPI）。
     * 金币与 DeluxeTags 称号不受影响这一点在两档提示里都说明，避免误解。
     */
    fun scheduleStartupCheck(platform: String) {
        if (platform != "spigot") return
        Thread({
            try {
                Thread.sleep(12_000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            try {
                if (isPapiAvailable()) {
                    PluginFileLog.write("[依赖检测] PlaceholderAPI 已就绪")
                    return@Thread
                }
                val playerTitle = pluginEnabled("PlayerTitle")
                val playTimeManager = pluginEnabled("PlayTimeManager")
                val lines = StringBuilder("[依赖检测] 未检测到 PlaceholderAPI 变量插件：")
                lines.append("\n  - 金币（Vault）与 DeluxeTags 称号不依赖它，不受影响；")
                if (playerTitle || playTimeManager) {
                    val broken = buildList {
                        if (playerTitle) add("PlayerTitle 称号")
                        if (playTimeManager) add("PlayTimeManager 在线时长同步")
                    }.joinToString("、")
                    lines.append("\n  - 已安装 $broken，但缺少 PlaceholderAPI 无法读取（当前不生效）；")
                } else {
                    lines.append("\n  - 当前未安装需要它的功能插件，暂无影响；")
                }
                lines.append("\n  - 如需 PlayerTitle 称号 / PlayTimeManager 时长同步，")
                lines.append("请安装 PlaceholderAPI 后重启服务器。")
                PluginFileLog.warnAndKeep(lines.toString())
                PluginFileLog.write("[依赖检测] PlaceholderAPI 缺失提示已输出（启动档）")
            } catch (_: Throwable) {
            }
        }, "qq-papi-check").apply { isDaemon = true }.start()
    }

    /**
     * 运行中提醒（会话级一次）：查询路径因缺 PlaceholderAPI 失败时调用。
     *
     * @param feature 依赖 PAPI 的功能名（如 "PlayerTitle 称号读取"、"PlayTimeManager 时长同步"）
     */
    fun notifyMissingOnce(feature: String) {
        if (notifiedKeys.contains(NOTIFY_KEY)) return
        if (!notifiedKeys.add(NOTIFY_KEY)) return
        try {
            PluginFileLog.warnAndKeep(
                "[依赖检测] $feature 需要 PlaceholderAPI（未检测到），本次跳过。" +
                    "金币（Vault）与 DeluxeTags 称号不受影响；" +
                    "安装 PlaceholderAPI 并重启后该功能自动启用。"
            )
            PluginFileLog.write("[依赖检测] PlaceholderAPI 缺失提示已输出（运行档，来源：$feature）")
        } catch (_: Throwable) {
        }
    }

    /** 插件是否已安装并启用（任何异常视为未安装）。 */
    private fun pluginEnabled(name: String): Boolean = try {
        val plugin = Bukkit.getPluginManager().getPlugin(name)
        plugin != null && plugin.isEnabled
    } catch (_: Throwable) {
        false
    }
}
