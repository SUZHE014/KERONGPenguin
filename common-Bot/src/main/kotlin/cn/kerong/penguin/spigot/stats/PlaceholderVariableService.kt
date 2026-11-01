package cn.kerong.penguin.spigot.stats

import cn.kerong.bot.tools.PluginFileLog
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * 变量获取信息（1.6.9 新增，1.6.9.3 扩展）：/查信息 卡片的指定项目可改为
 * 读取服主在 config.yml variable-fetch 段填写的 PlaceholderAPI 变量输出。
 *
 * 规则（与官网教程一致）：
 * - 总开关 variable-fetch.enabled 默认关闭；关闭时全部项目照常使用插件自带数据；
 * - 可配置六个玩家项目：总在线时长（total-playtime）/ 今日在线时长（today-playtime）/
 *   金币（coins）/ 点券（points）/ 称号（title）/ 玩家名（player-name）；
 *   另有服务器项目 TPS（tps，作用于 /查性能 卡片，走 [resolveServerTps]）；
 * - 某项目填写了变量且解析成功 → 卡片直接输出变量返回的内容（原文输出，
 *   不再套用插件的数量缩写 / 时长格式化——想带单位请把单位写进变量或混排文本）；
 * - 变量留空 → 该项目使用插件自带数据（默认行为）；
 * - 变量读取失败（未装 PlaceholderAPI / 变量未注册返回原文 / 返回空白 /
 *   解析异常）→ 自动回退插件自带数据；
 * - 1.6.9.3 显隐规则：金币 / 点券 / 称号三项只要**填写了变量**（无论读取成败），
 *   即使对应依赖插件（Vault / PlayerPoints / 称号来源）未安装也照常展示该项；
 *   读取失败且无插件自带数据可回退时显示「未获取成功」而不是隐藏；
 * - 自定义查信息项目（variable-fetch.custom-items 段，1.6.9.3 新增）：
 *   每项 = 项目ID（段名，仅区分用不展示）+ 项目名称 + 获取变量；查询时逐项解析，
 *   正常返回的展示在卡片末尾，未返回的不展示（无插件自带数据可回退）；
 * - 变量值支持与固定文本混排（如 "%x% 小时"，PAPI 会替换其中的变量部分）；
 *   混排文本里的变量未解析时同样整体回退。
 *
 * 线程约束：PlaceholderAPI 的部分第三方扩展非线程安全，玩家级变量统一在
 * [QueryInfoService.gatherOnMainThread] / 排行榜主线程快照内调用（不增加额外的
 * 主线程往返）；服务器级变量（TPS）经 [resolveOnMainThread] 跳主线程解析。
 *
 * NeoForge 端：无 PlaceholderAPI 生态（兼容层 getPlugin 恒 null），
 * 变量解析永远走回退分支，卡片数据与旧版一致；配置段照常写出（键无害）。
 *
 * 排障：每次开启状态下的真实解析结果会写进插件日志文件
 * （logs/qq/qq-bind-日期.log，一行汇总：每个已配置变量的取值与采用/回退结论）。
 */
object PlaceholderVariableService {

    /** 未读取到变量时的兜底文案（对应依赖插件未装且变量失败的项目）。 */
    const val FETCH_FAILED_TEXT = "未获取成功"

    /** 可配置的玩家级变量项目：配置键 → 卡片项名（顺序即日志输出顺序）。 */
    private val PLAYER_FIELDS = listOf(
        "total-playtime" to "总在线时长",
        "today-playtime" to "今日在线时长",
        "coins" to "金币",
        "points" to "点券",
        "title" to "称号",
        "player-name" to "玩家名",
    )

    /** TPS（服务器级变量，作用于 /查性能，不在查信息卡片上）。 */
    private const val KEY_TPS = "tps"

    /**
     * 变量覆盖结果：六个玩家项目的解析值（null = 留空 / 未开启 / 解析失败，
     * 均表示该项目使用插件自带数据）。
     */
    data class Overrides(
        val playtime: String?,
        val today: String?,
        val coins: String?,
        val points: String?,
        val title: String?,
        val playerName: String?,
    )

    /** 自定义查信息项目配置（variable-fetch.custom-items 段读出）。 */
    data class CustomItem(val id: String, val name: String, val placeholder: String)

    /** 解析成功的自定义项目（正常返回的才保留展示）。 */
    data class ResolvedCustomItem(val id: String, val name: String, val value: String)

    /**
     * 一次查信息查询的完整变量上下文（主线程快照内一次解析产出）。
     * configured 标记与解析成败无关（开关开启 + 变量非空即为 true），
     * 用于「填了变量就展示、读不到显示未获取成功」的显隐规则。
     */
    data class Context(
        val overrides: Overrides,
        /** 金币项是否填写了变量（开启且非空）——控制显隐与「未获取成功」。 */
        val coinsConfigured: Boolean,
        /** 点券项是否填写了变量。 */
        val pointsConfigured: Boolean,
        /** 称号项是否填写了变量。 */
        val titleConfigured: Boolean,
        /** 自定义项目解析结果（仅保留正常返回的项，按配置顺序）。 */
        val customItems: List<ResolvedCustomItem>,
    )

    /** 全空的覆盖结果。 */
    val EMPTY_OVERRIDES = Overrides(null, null, null, null, null, null)

    /** 全部回退（未开启）的空上下文（主线程超时快照等同路径复用）。 */
    val EMPTY_CONTEXT = Context(EMPTY_OVERRIDES, false, false, false, emptyList())

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
     * 某项目是否填写了变量（总开关开启 + 变量非空；与解析成败无关）。
     * 1.6.9.3：显隐规则的判定依据——金币 / 点券 / 称号填了变量就展示，
     * 即使对应依赖插件未装（读不到显示「未获取成功」）。
     */
    fun isConfigured(key: String): Boolean = isEnabled() && placeholderOf(key) != null

    /**
     * 读出自定义查信息项目配置（variable-fetch.custom-items 段；
     * 每项：段名 = 项目ID，子键 项目名称 / 获取变量）。
     * 名称或变量留空的项跳过；段为空 / 读取异常返回空表。
     * /kerong reload 重载配置后即读到新内容（每次查询实时读取）。
     */
    fun customItems(): List<CustomItem> = try {
        val section = (Bukkit.getPluginManager().getPlugin("KERONGPenguin") as? JavaPlugin)
            ?.config?.getConfigurationSection("variable-fetch.custom-items") ?: return emptyList()
        section.getKeys(false).mapNotNull { id ->
            val name = section.getString("$id.项目名称")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val placeholder = section.getString("$id.获取变量")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            CustomItem(id, name, placeholder)
        }
    } catch (_: Throwable) {
        emptyList()
    }

    /**
     * 主线程解析全部已配置的玩家级变量 + 自定义项目（在查信息的主线程
     * 快照内调用一次）。
     *
     * @return [Context] 覆盖值 + 显隐标记 + 自定义项目；同时把每个已配置变量
     *   的解析结果（采用 / 回退与原因）汇总写一行插件日志，便于排障。
     */
    fun snapshotContext(playerName: String, playerUuid: UUID?, onlinePlayer: Player?): Context {
        if (!isEnabled()) return EMPTY_CONTEXT
        // 显隐标记与 PAPI 可用性无关：没装 PAPI 时变量读不到，
        // 但「填了变量」的项目仍要走展示 + 未获取成功 分支
        val coinsConfigured = placeholderOf("coins") != null
        val pointsConfigured = placeholderOf("points") != null
        val titleConfigured = placeholderOf("title") != null
        if (!papiAvailable()) {
            // 总开关开了却没有 PlaceholderAPI：会话级提醒一次（控制台/文件），
            // 之后每次查询只写文件日志，不刷屏
            PapiDependencyCheck.notifyMissingOnce("变量获取信息（variable-fetch）")
            PluginFileLog.write("[变量] variable-fetch 已开启但未检测到 PlaceholderAPI，本次全部使用插件自带数据")
            return Context(EMPTY_OVERRIDES, coinsConfigured, pointsConfigured, titleConfigured, emptyList())
        }
        val resolved = arrayOfNulls<String?>(PLAYER_FIELDS.size)
        val logLine = StringBuilder()
        for ((index, entry) in PLAYER_FIELDS.withIndex()) {
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
        // 自定义项目：正常返回的才展示（无自带数据可回退，不展示即隐藏该项）
        val custom = customItems()
        val customResolved = ArrayList<ResolvedCustomItem>(custom.size)
        if (custom.isNotEmpty()) {
            val customLog = StringBuilder()
            for (item in custom) {
                val value = resolve(item.placeholder, playerName, playerUuid, onlinePlayer)
                if (value != null) {
                    customResolved.add(ResolvedCustomItem(item.id, item.name, value))
                    customLog.append(" ${item.name}='${item.placeholder}'→'$value'(采用)")
                } else {
                    customLog.append(" ${item.name}='${item.placeholder}'→失败(不展示)")
                }
            }
            if (customLog.isNotEmpty()) {
                PluginFileLog.write("[变量] 查信息 $playerName 自定义项目:$customLog")
            }
        }
        if (logLine.isNotEmpty()) {
            PluginFileLog.write("[变量] 查信息 $playerName:$logLine")
        }
        return Context(
            overrides = Overrides(
                playtime = resolved[0],
                today = resolved[1],
                coins = resolved[2],
                points = resolved[3],
                title = resolved[4],
                playerName = resolved[5],
            ),
            coinsConfigured = coinsConfigured,
            pointsConfigured = pointsConfigured,
            titleConfigured = titleConfigured,
            customItems = customResolved,
        )
    }

    /**
     * 批量解析排行榜条目的变量覆盖（1.6.9.3 新增，主线程快照内调用）：
     * 变量除了查信息以外，排行榜 / 财富榜等有对应数据项的地方同样拉取变量
     * （不返回则用插件自带的）。
     *
     * @param players 玩家（真实名 → UUID）列表，仅对展示的 top 条目解析
     * @param nameKey 玩家名变量键（"player-name"，行首玩家名展示覆盖）
     * @param valueKey 数值变量键（在线排行榜 "total-playtime" / 财富榜 "coins"）
     * @return 真实玩家名 →（展示名覆盖, 数值文本覆盖）；null = 回退插件自带。
     *   两键均未配置时返回空表（零解析开销）。
     */
    fun resolveBatch(
        players: List<Pair<String, UUID?>>,
        nameKey: String,
        valueKey: String,
    ): Map<String, Pair<String?, String?>> {
        if (!isEnabled()) return emptyMap()
        val namePlaceholder = placeholderOf(nameKey)
        val valuePlaceholder = placeholderOf(valueKey)
        if (namePlaceholder == null && valuePlaceholder == null) return emptyMap()
        if (!papiAvailable()) {
            PapiDependencyCheck.notifyMissingOnce("变量获取信息（variable-fetch）")
            PluginFileLog.write("[变量] variable-fetch 已开启但未检测到 PlaceholderAPI，排行榜使用插件自带数据")
            return emptyMap()
        }
        val result = HashMap<String, Pair<String?, String?>>()
        val logLine = StringBuilder()
        for ((name, uuid) in players) {
            val nameOverride = if (namePlaceholder != null) resolve(namePlaceholder, name, uuid, null) else null
            val valueOverride = if (valuePlaceholder != null) resolve(valuePlaceholder, name, uuid, null) else null
            result[name] = nameOverride to valueOverride
            if (nameOverride != null || valueOverride != null) {
                logLine.append(" $name→(名=${nameOverride ?: "回退"},值=${valueOverride ?: "回退"})")
            }
        }
        if (logLine.isNotEmpty()) {
            PluginFileLog.write("[变量] 排行榜:$logLine")
        }
        return result
    }

    /**
     * 解析服务器级 TPS 变量（variable-fetch.tps，1.6.9.3 新增；/查性能 用）。
     *
     * TPS 不是玩家数据，PAPI 的服务器类变量（如 %server_tps%）无需玩家上下文；
     * 有在线玩家时借第一个在线玩家解析（兼容个别需要玩家上下文的扩展），
     * 否则传 null OfflinePlayer（PAPI 支持，server 类变量照常解析）。
     * 必须在主线程调用（[resolveOnMainThread] 已保证）。
     *
     * @return 变量文本；未开启 / 未填写 / 解析失败返回 null（用插件自采集 TPS）
     */
    fun resolveServerTps(): String? {
        if (!isConfigured(KEY_TPS)) return null
        if (!papiAvailable()) {
            PapiDependencyCheck.notifyMissingOnce("变量获取信息（variable-fetch）")
            return null
        }
        val placeholder = placeholderOf(KEY_TPS) ?: return null
        val online = try {
            Bukkit.getOnlinePlayers().firstOrNull()
        } catch (_: Throwable) {
            null
        }
        val value = resolveForOffline(placeholder, online)
        PluginFileLog.write(
            if (value == null) "[变量] 查性能 tps='$placeholder'→失败(回退自带)"
            else "[变量] 查性能 tps='$placeholder'→'$value'(采用)"
        )
        return value
    }

    /**
     * 在主线程执行一段变量解析（异步调用时自动跳主线程，3 秒超时回退 null；
     * 已在主线程时直接执行）。PlaceholderAPI 第三方扩展非线程安全，
     * 任何异步路径的变量解析都必须经本方法（或直接在主线程快照内调用）。
     */
    fun <T> resolveOnMainThread(compute: () -> T): T? {
        if (Bukkit.isPrimaryThread()) {
            return try {
                compute()
            } catch (_: Throwable) {
                null
            }
        }
        val bukkitPlugin = try {
            Bukkit.getPluginManager().getPlugin("KERONGPenguin")
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            cn.kerong.penguin.spigot.scheduler.FoliaCompat.callSync(bukkitPlugin, 3L) { compute() }
        } catch (_: Throwable) {
            null
        }
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
        val target: OfflinePlayer = onlinePlayer
            ?: playerUuid?.let { Bukkit.getOfflinePlayer(it) }
            ?: Bukkit.getOfflinePlayer(playerName)
        return resolveForOffline(placeholder, target)
    }

    /** 与 [resolve] 同判定的离线玩家解析（服务器级变量传 null 亦可）。 */
    private fun resolveForOffline(placeholder: String, target: OfflinePlayer?): String? {
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
