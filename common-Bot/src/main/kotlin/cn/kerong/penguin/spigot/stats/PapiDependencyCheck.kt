package cn.kerong.penguin.spigot.stats

import cn.kerong.bot.tools.PluginFileLog
import org.bukkit.Bukkit
import java.util.concurrent.ConcurrentHashMap

/**
 * PlaceholderAPI 依赖检测（1.6.6 新增；1.6.6.1 覆盖版：PlayTimeManager
 * 时长同步功能已删除，检测面相应收窄为 PlayerTitle 称号）。
 *
 * 背景：金币（Vault Economy 直连）与 DeluxeTags 称号（实例 / 静态 API 直连）
 * 都不依赖 PlaceholderAPI；但以下功能需要 PlaceholderAPI 才能读取：
 * - PlayerTitle 称号（%playerTitle_use%）；
 * - DeluxeTags 的占位符兜底层（%deluxetags_tag%，前三层失败才用到）。
 *
 * 1.6.7.3 提示策略调整（用户反馈）：仅当检测到 PlayerTitle 等依赖
 * PlaceholderAPI 的功能插件已安装时才进行检测并提示——没装这些插件
 * 的服务器与 PAPI 毫无关系，旧版「未装相关插件也温和提示」属于噪音。
 * 检测到 PlayerTitle 时：
 * - PlaceholderAPI 已装 → 控制台正向确认「称号依赖就绪」；
 * - PlaceholderAPI 缺失 → 明确提示功能不生效并给出补救指引。
 * 运行中第一次因缺 PlaceholderAPI 导致查询失败时仍会再提醒一次
 * （每次会话最多一次，[NOTIFY_KEY] 去重；该路径只有装了 PlayerTitle
 * 才可能触发，与新策略天然一致）。
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
     * 需要 PlaceholderAPI 才能工作的功能插件清单（1.6.7.3：检测到其中任一
     * 已安装才触发检测与提示；列表可随功能扩展追加）。
     */
    private val papiConsumerPlugins = listOf("PlayerTitle")

    /**
     * 启动后延迟检测（Spigot 端 onEnable 挂载，异步延迟 12 秒：
     * 错开启动高峰与更新检查的 8 秒，且确保所有插件已 enable）。
     *
     * 1.6.7.3：仅当检测到 [papiConsumerPlugins] 里的功能插件已安装时才
     * 检测并提示——未装相关插件的服务器完全静默（PAPI 与它无关，不提示
     * 也不扫日志）；检测到 PlayerTitle 时按 PAPI 有无输出就绪确认或缺失
     * 指引（金币 Vault 直连与 DeluxeTags 称号不受影响在缺失提示中说明）。
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
                val consumer = papiConsumerPlugins.firstOrNull { pluginEnabled(it) }
                if (consumer == null) return@Thread
                if (isPapiAvailable()) {
                    PluginFileLog.infoAndKeep("[依赖检测] $consumer 称号依赖就绪（PlaceholderAPI 已启用）")
                    return@Thread
                }
                val lines = StringBuilder("[依赖检测] 已安装 $consumer 称号，但缺少 PlaceholderAPI 无法读取（当前不生效）：")
                lines.append("\n  - 金币（Vault）与 DeluxeTags 称号不依赖它，不受影响；")
                lines.append("\n  - 请安装 PlaceholderAPI 后重启服务器，$consumer 称号自动启用。")
                PluginFileLog.warnAndKeep(lines.toString())
                PluginFileLog.write("[依赖检测] PlaceholderAPI 缺失提示已输出（启动档，检测到 $consumer）")
            } catch (_: Throwable) {
            }
        }, "qq-papi-check").apply { isDaemon = true }.start()
    }

    /**
     * 运行中提醒（会话级一次）：查询路径因缺 PlaceholderAPI 失败时调用。
     *
     * @param feature 依赖 PAPI 的功能名（如 "PlayerTitle 称号读取"）
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
