package cn.kerong.penguin.spigot.stats

import cn.kerong.bot.tools.PluginFileLog
import com.alibaba.fastjson.JSON
import org.bukkit.Bukkit
import java.io.File
import java.util.UUID

/**
 * 服务器存档统计数据读取（1.7.6 新增，总在线时长「服务器存档数据」源）：
 * 读取主世界存档目录下 vanilla 统计文件 `stats/<uuid>.json` 的
 * `stats → minecraft:custom → minecraft:play_time`（游戏刻），供玩家名片
 * 卡片「在线时长」项在 variable-fetch.total-playtime-source = save-data
 * 时使用（[QueryInfoService.buildItems] 的回退链首环）。
 *
 * ## 数据口径
 *
 * - `minecraft:play_time`：玩家在服务器上的累计游戏刻数（1.13+ 名称；
 *   更早版本叫 `minecraft:play_one_minute`，两者数值同源——旧世界升级后
 *   vanilla 会迁移，这里同时认两个键，取先命中者）；
 * - 换算：20 刻 = 1 秒，72000 刻 = 1 小时（[TICKS_PER_HOUR]）；
 * - 展示格式 [formatHours]：「35.5 小时」（一位小数，整数不带小数点）
 *   ——与插件统计源的「X时X分」格式刻意区分，管理员一眼能看出当前
 *   读的是哪个源。
 *
 * ## 目录定位（四端通用）
 *
 * 优先 `Bukkit.getWorlds()` 首个世界的 worldFolder/stats（Spigot 真实
 * API；NeoForge / Fabric 兼容层返回主世界存档目录，等价）；取不到时
 * 回退服务器工作目录下的 `world/stats`（MC 服务端进程的工作目录就是
 * 服务器根目录，level-name 默认 world）。两处都不是目录 → 返回 null
 * （源不可用，走回退链）。
 *
 * ## 安全边界
 *
 * - 文件不存在 / 超过 [MAX_FILE_BYTES]（2MB，正常统计文件几 KB~几十 KB）
 *   / 解析异常 / play_time 缺失或为 0 → 一律返回 null（null = 该源
 *   「获取不到」，由调用方退回变量 / 插件统计，不抛异常）；
 * - 读取发生在渲染池线程（玩家名片渲染流程），无主线程开销；
 * - 每次查询实时读取（文件小、频率低，不做缓存——管理员改档 / 玩家
 *   在线时长增长都能即时反映）。
 */
object StatsFileService {

    /** 1 小时的游戏刻数（20 刻/秒 × 3600 秒）。 */
    const val TICKS_PER_HOUR = 72_000L

    /** 单个统计文件的大小上限（超出视为异常数据，按读取失败处理）。 */
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L

    /** vanilla 统计里累计游戏时间的键（1.13+ 名称）。 */
    private const val KEY_PLAY_TIME = "minecraft:play_time"

    /** 1.13 之前的同义键名（旧世界升级迁移用，取先命中者）。 */
    private const val KEY_PLAY_ONE_MINUTE = "minecraft:play_one_minute"

    /** 主世界存档 stats 目录（定位失败返回 null；成功结果惰性缓存）。 */
    @Volatile
    private var cachedDirectory: File? = null

    /**
     * 读取玩家的累计游戏时间（游戏刻）。
     *
     * @return play_time 刻数（>0）；文件缺失 / 解析失败 / 数值为 0 返回 null
     */
    fun readPlayTicks(uuid: UUID?): Long? {
        if (uuid == null) return null
        val directory = statsDirectory() ?: return null
        val file = File(directory, "${uuid}.json")
        if (!file.isFile) return null
        return try {
            if (file.length() <= 0L || file.length() > MAX_FILE_BYTES) return null
            val root = JSON.parseObject(file.readBytes().toString(Charsets.UTF_8))
            val custom = root.getJSONObject("stats")?.getJSONObject("minecraft:custom")
                // 极旧（1.12-）格式：顶层直接是 custom 段——顺手兼容，读不到不致命
                ?: root.getJSONObject("minecraft:custom")
            val ticks = custom?.getLong(KEY_PLAY_TIME) ?: custom?.getLong(KEY_PLAY_ONE_MINUTE)
            if (ticks != null && ticks > 0L) ticks else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 游戏刻格式化为小时文本：「35.5 小时」/「35 小时」
     * （一位小数、去尾零；0 刻不会出现在本格式——读取为 0 已按失败回退）。
     */
    fun formatHours(ticks: Long): String {
        val hours = ticks.toDouble() / TICKS_PER_HOUR
        val text = String.format("%.1f", hours).trimEnd('0').trimEnd('.')
        return "${text.ifEmpty { "0" }} 小时"
    }

    /**
     * 主世界 stats 目录：首个世界目录下的 stats（四端通用，兼容层
     * getWorlds 返回主世界），失败回退服务器工作目录 world/stats。
     * 只缓存定位成功的结果（失败不缓存——世界尚未加载的极早期查询
     * 不会把 null 定死，下次查询自动重试）。
     */
    private fun statsDirectory(): File? {
        cachedDirectory?.let { return it }
        val resolved = try {
            val worldFolder = Bukkit.getWorlds().firstOrNull()?.worldFolder
            val primary = worldFolder?.resolve("stats")
            if (primary?.isDirectory == true) {
                primary
            } else {
                val fallback = File("world/stats")
                if (fallback.isDirectory) fallback else null
            }
        } catch (_: Throwable) {
            null
        }
        if (resolved != null) cachedDirectory = resolved
        return resolved
    }

    /** 重置目录缓存（测试 / 服务器重载世界用；正常流程不会触发）。 */
    fun resetCache() {
        cachedDirectory = null
    }

    /** 读取失败写一行文件日志（排障用；同一会话只提示一次，不刷屏）。 */
    fun noteUnavailableOnce() {
        if (!notedUnavailable) {
            notedUnavailable = true
            PluginFileLog.write("[存档统计] 未找到主世界 stats 目录（world/stats），「服务器存档数据」源不可用，总在线时长回退变量 / 插件统计")
        }
    }

    @Volatile
    private var notedUnavailable = false
}
