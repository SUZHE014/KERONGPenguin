package cn.kerong.penguin.spigot.stats

import cn.kerong.bot.tools.PluginFileLog
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * 变量获取信息（1.6.9 新增）：/查信息 卡片的指定项目可改为读取服主
 * 在 config.yml variable-fetch 段填写的 PlaceholderAPI 变量输出。
 *
 * 规则（与官网教程一致）：
 * - 总开关 variable-fetch.enabled 默认关闭；关闭时全部项目照常使用插件自带数据；
 * - 可配置五个项目：总在线时长（total-playtime）/ 今日在线时长（today-playtime）/
 *   金币（coins）/ 点券（points）/ 称号（title）；
 * - 某项目填写了变量且解析成功 → 卡片直接输出变量返回的内容（原文输出，
 *   不再套用插件的数量缩写 / 时长格式化——想带单位请把单位写进变量或混排文本）；
 * - 变量留空 → 该项目使用插件自带数据（默认行为）；
 * - 变量读取失败（未装 PlaceholderAPI / 变量未注册返回原文 / 返回空白 /
 *   解析异常）→ 自动回退插件自带数据，卡片不缺项；
 * - 变量值支持与固定文本混排（如 "%x% 小时"，PAPI 会替换其中的变量部分）；
 *   混排文本里的变量未解析时同样整体回退。
 *
 * 线程约束：PlaceholderAPI 的部分第三方扩展非线程安全，本服务统一在
 * [QueryInfoService.gatherOnMainThread] 的主线程快照内调用（与称号 / 金币
 * 采集同一次跳转，不增加额外的主线程往返）。
 *
 * NeoForge 端：无 PlaceholderAPI 生态（兼容层 getPlugin 恒 null），
 * 变量解析永远走回退分支，卡片数据与旧版一致；配置段照常写出（键无害）。
 *
 * 排障：每次开启状态下的真实解析结果会写进插件日志文件
 * （logs/qq/qq-bind-日期.log，一行汇总：每个已配置变量的取值与采用/回退结论）。
 */
object PlaceholderVariableService {

    /** 可配置变量项目：配置键 → 卡片项名（顺序即日志输出顺序）。 */
    private val FIELDS = listOf(
        "total-playtime" to "总在线时长",
        "today-playtime" to "今日在线时长",
        "coins" to "金币",
        "points" to "点券",
        "title" to "称号",
    )

    /**
     * 变量覆盖结果：五个项目的解析值（null = 留空 / 未开启 / 解析失败，
     * 均表示该项目使用插件自带数据）。
     */
    data class Overrides(
        val playtime: String?,
        val today: String?,
        val coins: String?,
        val points: String?,
        val title: String?,
    )

    /** 全部回退（未开启 / PAPI 缺失）的空结果。 */
    private val EMPTY_OVERRIDES = Overrides(null, null, null, null, null)

    /** variable-fetch.enabled 总开关（默认 false）。 */
    fun isEnabled(): Boolean = try {
        (Bukkit.getPluginManager().getPlugin("KERONGPenguin") as? JavaPlugin)
            ?.config?.getBoolean("variable-fetch.enabled", false) ?: false
    } catch (_: Throwable) {
        false
    }

    /**
     * 读取某项目的变量配置（variable-fetch.<key>，留空 / 读取异常返回 null）。
     * 首尾空白会被剥掉（与凭据清洗同思路：网页复制变量常带不可见空白）。
     */
    fun placeholderOf(key: String): String? = try {
        (Bukkit.getPluginManager().getPlugin("KERONGPenguin") as? JavaPlugin)
            ?.config?.getString("variable-fetch.$key", "")?.trim()
            ?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    /**
     * 主线程解析全部已配置变量（在查信息的主线程快照内调用一次）。
     *
     * @return [Overrides] 五个项目的覆盖值；同时把每个已配置变量的解析
     *   结果（采用 / 回退与原因）汇总写一行插件日志，便于排障。
     */
    fun snapshot(playerName: String, playerUuid: UUID?, onlinePlayer: Player?): Overrides {
        if (!isEnabled()) return EMPTY_OVERRIDES
        if (!papiAvailable()) {
            // 总开关开了却没有 PlaceholderAPI：会话级提醒一次（控制台/文件），
            // 之后每次查询只写文件日志，不刷屏
            PapiDependencyCheck.notifyMissingOnce("变量获取信息（variable-fetch）")
            PluginFileLog.write("[变量] variable-fetch 已开启但未检测到 PlaceholderAPI，本次全部使用插件自带数据")
            return EMPTY_OVERRIDES
        }
        val resolved = arrayOfNulls<String?>(FIELDS.size)
        val logLine = StringBuilder()
        for ((index, entry) in FIELDS.withIndex()) {
            val (key, label) = entry
            val placeholder = placeholderOf(key)
            if (placeholder == null) {
                logLine.append(" $label=留空")
                continue
            }
            val value = resolve(placeholder, playerName, playerUuid, onlinePlayer)
            resolved[index] = value
            logLine.append(
                if (value == null) " $label='$placeholder'→失败(回退自带)"
                else " $label='$placeholder'→'$value'(采用)"
            )
        }
        if (logLine.isNotEmpty()) {
            PluginFileLog.write("[变量] 查信息 $playerName:$logLine")
        }
        return Overrides(
            playtime = resolved[0],
            today = resolved[1],
            coins = resolved[2],
            points = resolved[3],
            title = resolved[4],
        )
    }

    /** PlaceholderAPI 是否已安装并启用。 */
    private fun papiAvailable(): Boolean = try {
        val papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI")
        papi != null && papi.isEnabled
    } catch (_: Throwable) {
        false
    }

    /**
     * 解析单个变量（反射调用 PlaceholderAPI.setPlaceholders(OfflinePlayer, String)，
     * 与称号链路同模式：用 PAPI 插件自己的类加载器加载，跨插件类不可见）。
     *
     * 失败回退判定（任一命中返回 null → 使用插件自带数据）：
     * - PlaceholderAPI 不可用 / 方法缺失 / 反射调用异常；
     * - 解析结果为 null 或 trim 后为空（空白视为无内容）；
     * - 解析结果与原文完全相同且原文含 %（变量未注册时 PAPI 原样返回）；
     *   不含 % 的纯文本视为合法输出（服主可填固定文本混排）。
     *
     * @param placeholder 变量文本（可含 % 变量与固定文本混排）
     */
    fun resolve(
        placeholder: String,
        playerName: String,
        playerUuid: UUID?,
        onlinePlayer: Player?,
    ): String? {
        if (placeholder.isEmpty()) return null
        val papi = try {
            Bukkit.getPluginManager().getPlugin("PlaceholderAPI")
        } catch (_: Throwable) {
            null
        } ?: return null
        try {
            if (!papi.isEnabled) return null
        } catch (_: Throwable) {
            return null
        }
        return try {
            val papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI", false, papi.javaClass.classLoader)
            val method = papiClass.methods.firstOrNull {
                it.name == "setPlaceholders" && it.parameterCount == 2 &&
                    OfflinePlayer::class.java == it.parameterTypes[0] && String::class.java == it.parameterTypes[1]
            } ?: return null
            val target: OfflinePlayer = onlinePlayer
                ?: playerUuid?.let { Bukkit.getOfflinePlayer(it) }
                ?: Bukkit.getOfflinePlayer(playerName)
            val resolved = method.invoke(null, target, placeholder) as? String ?: return null
            val cleaned = resolved.trim()
            when {
                cleaned.isEmpty() -> null
                cleaned == placeholder.trim() && placeholder.contains('%') -> null
                else -> cleaned
            }
        } catch (_: Throwable) {
            null
        }
    }
}
