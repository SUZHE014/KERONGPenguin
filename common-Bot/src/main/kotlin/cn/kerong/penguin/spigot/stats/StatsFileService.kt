package cn.kerong.penguin.spigot.stats

import cn.kerong.bot.tools.PluginFileLog
import com.alibaba.fastjson.JSON
import org.bukkit.Bukkit
import java.io.File
import java.util.UUID

/**
 * 服务器存档统计数据读取（总在线时长「服务器存档数据」源）：
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
 * - 展示格式 [formatPlayText]：与其他获取源（插件统计 / 变量）完全一致的
 *   「X时X分」口径（用户口径：所有获取源展示格式统一，不再用旧的
 *   「X.X 小时」区分格式）。
 *
 * ## UUID 查找（1.7.6 覆盖轮重做——修复读不到存档时间刻）
 *
 * 服务器 stats 文件名是**服务器认定的玩家 UUID**；绑定记录（QUUID 文件
 * 的 playerUuid 字段）是插件在玩家登录时记下的，两者可能不一致（历史
 * 老数据没有该字段、离线服玩家改名、绑定建档与实际登录走了不同路径等）。
 * 现按两级查找，**确保用服务器自己的 UUID 去找文件**：
 *
 * 1. 先用绑定记录的 UUID 找 `stats/<uuid>.json`（文件存在 = 服务器认它）；
 * 2. 找不到时读**服务器工作目录的 usercache.json**（vanilla 服务器自己
 *    维护的「玩家名 → UUID」缓存，凡在服务器登录过的玩家都有记录——
 *    这份映射就是服务器口径），按玩家名（忽略大小写）解析出同名全部
 *    服务器 UUID，按 expiresOn 从新到旧逐条试，第一个有统计文件且能
 *    读到 play_time 的命中（最新条目可能登录过但从未进世界没有统计
 *    文件，此时自动试次新条目）。
 *
 * 查找全过程（每一环的目录、UUID、文件名、结果）写入日志文件
 * （logs/qq/qq-bind-日期.log 的「[存档统计]」行），排障时可直接对照
 * 服务器 stats 目录与 usercache.json 核对。
 *
 * ## 同名多账号（1.7.6 二次覆盖轮，在线排行榜用）
 *
 * 离线服务器常见同名：同一玩家名在服务器里可能有多个 UUID（改名前的
 * 旧账号、大小写变体，或多个同名玩家）。玩家名片取单条（上述两级取
 * 最先命中）；[readPlayTimeAll] 把该名字命中的**每一个**服务器 UUID
 * 的统计全部读出（绑定 UUID + usercache.json 同名全部条目，去重），
 * 供在线排行榜同名多账号同时展示。
 *
 * ## 目录定位（四端通用）
 *
 * 优先 `Bukkit.getWorlds()` 首个世界的 worldFolder/stats（Spigot 真实
 * API；NeoForge / Fabric 兼容层返回主世界存档目录，等价）；取不到时
 * 回退服务器工作目录下的 `world/stats`（MC 服务端进程的工作目录就是
 * 服务器根目录，level-name 默认 world）。usercache.json 位于服务器根
 * 目录（world 目录的上一级），同样两级定位。两处都不是目录 → 返回
 * null（源不可用，走回退链）。
 *
 * ## 安全边界
 *
 * - 文件不存在 / 超过 [MAX_FILE_BYTES]（2MB，正常统计文件几 KB~几十 KB）
 *   / 解析异常 / play_time 缺失或为 0 → 一律按「获取不到」处理（由调用
 *   方退回变量 / 插件统计，不抛异常）；
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

    /** 服务器根目录（usercache.json 定位用；成功结果惰性缓存）。 */
    @Volatile
    private var cachedServerRoot: File? = null

    /** 读取结果：游戏刻数 + 实际命中的 UUID 与来源（日志 / 排障用）。 */
    data class PlayTimeResult(
        val ticks: Long,
        /** 实际读到统计文件的 UUID（服务器口径）。 */
        val uuid: UUID,
        /** UUID 来源说明：绑定记录 / usercache.json。 */
        val uuidSource: String,
        /** stats 目录路径（日志用）。 */
        val directory: File,
    )

    /** usercache.json 单条同名记录（服务器口径的 玩家名 → UUID）。 */
    data class ServerUuidEntry(val uuid: UUID, val name: String, val expiresOn: String)

    /**
     * 读取玩家的累计游戏时间（游戏刻）——按两级 UUID 查找，全程写日志。
     *
     * @param playerName 玩家名（usercache.json 按名解析服务器 UUID 用）
     * @param boundUuid 绑定记录里的 UUID（可能为 null——老绑定数据无此字段）
     * @return 读取成功的结果；任何一环失败返回 null（调用方走回退链）
     */
    fun readPlayTime(playerName: String?, boundUuid: UUID?): PlayTimeResult? {
        val directory = statsDirectory()
        if (directory == null) {
            PluginFileLog.write(
                "[存档统计] 玩家=$playerName 获取失败：未找到主世界 stats 目录" +
                    "（已尝试首世界 worldFolder/stats 与工作目录 world/stats），「服务器存档数据」源不可用，总在线时长回退变量 / 插件统计"
            )
            return null
        }
        // 第一级：绑定记录的 UUID（文件存在 = 服务器认这个 UUID）
        if (boundUuid != null) {
            val file = File(directory, "${boundUuid}.json")
            if (file.isFile) {
                val ticks = parsePlayTicks(file)
                PluginFileLog.write(
                    "[存档统计] 玩家=$playerName UUID=$boundUuid（来源=绑定记录）" +
                        "文件=${file.path} → ${if (ticks != null) "play_time=$ticks 刻" else "文件存在但无有效 play_time"}"
                )
                if (ticks != null) {
                    return PlayTimeResult(ticks, boundUuid, "绑定记录", directory)
                }
            } else {
                PluginFileLog.write(
                    "[存档统计] 玩家=$playerName 绑定UUID=$boundUuid 在 ${directory.path} 下无对应统计文件，尝试用服务器 usercache.json 的 UUID 重查"
                )
            }
        } else {
            PluginFileLog.write(
                "[存档统计] 玩家=$playerName 绑定记录无 UUID（老数据），尝试用服务器 usercache.json 的 UUID 查找"
            )
        }
        // 第二级：usercache.json 同名服务器 UUID（expiresOn 降序逐条试，
        // 第一个有统计文件且能读到 play_time 的命中——最新的条目可能
        // 「登录过但从未进世界」没有统计文件，此时自动试次新条目）
        val candidates = resolveAllServerUuids(playerName)
        if (candidates.isEmpty()) {
            PluginFileLog.write(
                "[存档统计] 玩家=$playerName usercache.json 未解析到该玩家的服务器 UUID" +
                    "（服务器目录=${serverRoot()?.path ?: "未定位"}），存档源获取失败，回退变量 / 插件统计"
            )
            return null
        }
        for ((index, candidate) in candidates.withIndex()) {
            val file = File(directory, "${candidate.uuid}.json")
            if (!file.isFile) {
                PluginFileLog.write(
                    "[存档统计] 玩家=$playerName 服务器UUID=${candidate.uuid}（来源=usercache.json 第${index + 1}条）" +
                        "在 ${directory.path} 下无统计文件" + (if (index < candidates.size - 1) "，试下一条" else "，无更多候选")
                )
                continue
            }
            val ticks = parsePlayTicks(file)
            PluginFileLog.write(
                "[存档统计] 玩家=$playerName 服务器UUID=${candidate.uuid}（来源=usercache.json 第${index + 1}条）" +
                    "文件=${file.path} → ${if (ticks != null) "play_time=$ticks 刻" else "文件存在但无有效 play_time，试下一条"}"
            )
            if (ticks != null) {
                return PlayTimeResult(ticks, candidate.uuid, "usercache.json", directory)
            }
        }
        PluginFileLog.write(
            "[存档统计] 玩家=$playerName usercache.json 同名 ${candidates.size} 条均未读到存档 play_time，存档源获取失败，回退变量 / 插件统计"
        )
        return null
    }

    /**
     * 读取玩家名命中的**全部**存档在线时长记录（在线排行榜「服务器存档数据」源）。
     *
     * 离线服务器常见同名：同一玩家名在服务器里可能有多个 UUID 统计文件
     * （改名前的旧账号 / 大小写变体 / 多个同名玩家）——本方法把候选里
     * 每一个能读到 play_time 的服务器 UUID 各返回一条（同名多账号全部
     * 读出、全部展示；用户口径「获取到同名的也同时显示」）。
     *
     * 候选顺序（去重）：绑定记录 UUID（若非空，文件存在 = 服务器认它）
     * → usercache.json 同名（忽略大小写）全部条目（expiresOn 最新在前）。
     * 目录定位失败 / 全部候选未命中 → 空表（调用方回退插件统计）。
     * 每个玩家一条汇总日志（候选数 / 命中明细 / 回退去向）。
     */
    fun readPlayTimeAll(playerName: String?, boundUuid: UUID?): List<PlayTimeResult> {
        val directory = statsDirectory()
        if (directory == null) {
            PluginFileLog.write(
                "[存档统计] 排行榜 玩家=$playerName 获取失败：未找到主世界 stats 目录" +
                    "（已尝试首世界 worldFolder/stats 与工作目录 world/stats），该条目回退插件统计"
            )
            return emptyList()
        }
        val results = ArrayList<PlayTimeResult>()
        val seen = HashSet<UUID>()
        // 候选 1：绑定记录的 UUID（文件存在 = 服务器认这个 UUID）
        if (boundUuid != null && seen.add(boundUuid)) {
            val file = File(directory, "${boundUuid}.json")
            val ticks = if (file.isFile) parsePlayTicks(file) else null
            if (ticks != null) {
                results.add(PlayTimeResult(ticks, boundUuid, "绑定记录", directory))
            }
        }
        // 候选 2：usercache.json 同名（忽略大小写）的全部服务器 UUID
        val sameName = resolveAllServerUuids(playerName)
        for (entry in sameName) {
            if (!seen.add(entry.uuid)) continue
            val file = File(directory, "${entry.uuid}.json")
            val ticks = if (file.isFile) parsePlayTicks(file) else null
            if (ticks != null) {
                results.add(PlayTimeResult(ticks, entry.uuid, "usercache.json", directory))
            }
        }
        // 每玩家一条汇总日志（候选与命中明细，够排障对照，不逐条刷行）
        val summary = if (results.isEmpty()) {
            "存档全部未命中（绑定UUID=${boundUuid ?: "无"}，usercache同名=${sameName.size}条），回退插件统计"
        } else {
            "存档命中${results.size}条（同名多账号全部展示）：" +
                results.joinToString("；") { "${it.uuid}（${it.uuidSource}）=${formatPlayText(it.ticks)}" }
        }
        PluginFileLog.write(
            "[存档统计] 排行榜 玩家=$playerName 候选：绑定UUID=${boundUuid ?: "无"} + usercache.json同名${sameName.size}条 → $summary"
        )
        return results
    }

    /** 解析单个统计文件里的 play_time 刻数（缺失 / 为 0 / 异常返回 null）。 */
    private fun parsePlayTicks(file: File): Long? {
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
     * 从服务器 usercache.json 解析同名（忽略大小写）的**全部**服务器口径
     * UUID 条目，按 expiresOn 降序（最新在前）。文件缺失 / 解析失败 /
     * 无该玩家 → 空表（失败原因写日志，同一文件版本只写一次）。
     *
     * 解析结果按文件 lastModified 缓存（usercache.json 由服务器自己维护，
     * 文件一变缓存即失效）——排行榜按全量绑定玩家逐个解析同名，同一份
     * 文件不重复读盘。
     */
    fun resolveAllServerUuids(playerName: String?): List<ServerUuidEntry> {
        if (playerName.isNullOrBlank()) return emptyList()
        val root = serverRoot() ?: run {
            PluginFileLog.write("[存档统计] 未定位到服务器根目录（usercache.json 不可用）")
            return emptyList()
        }
        val entries = loadUsercacheEntries(root)
        val matched = ArrayList<ServerUuidEntry>()
        for ((name, uuid, expires) in entries) {
            if (!name.equals(playerName, ignoreCase = true)) continue
            val parsed = runCatching { UUID.fromString(uuid) }.getOrNull() ?: continue
            matched.add(ServerUuidEntry(parsed, name, expires))
        }
        matched.sortByDescending { it.expiresOn }
        return matched
    }

    /** usercache.json 解析缓存：文件 lastModified（缓存键；文件一变即失效）。 */
    @Volatile
    private var usercacheStamp: Long = Long.MIN_VALUE

    /** usercache.json 全量条目（name, uuid, expiresOn）；null = 尚未加载。 */
    @Volatile
    private var usercacheEntries: List<Triple<String, String, String>>? = null

    /**
     * 读取并缓存 usercache.json 全量条目：同一文件版本（lastModified 相同）
     * 直接用缓存——命中不读盘、失败不重试不刷日志；文件变化后重新解析。
     */
    private fun loadUsercacheEntries(root: File): List<Triple<String, String, String>> {
        val file = File(root, "usercache.json")
        val stamp = try {
            if (file.isFile) file.lastModified() else Long.MIN_VALUE
        } catch (_: Throwable) {
            Long.MIN_VALUE
        }
        usercacheEntries?.let { cached ->
            if (usercacheStamp == stamp) return cached
        }
        if (stamp == Long.MIN_VALUE) {
            PluginFileLog.write("[存档统计] 服务器目录 ${root.path} 下无 usercache.json（从未有玩家登录过或非标准目录结构）")
            usercacheEntries = emptyList()
            usercacheStamp = stamp
            return emptyList()
        }
        return try {
            if (file.length() <= 0L || file.length() > MAX_FILE_BYTES) {
                usercacheEntries = emptyList()
                usercacheStamp = stamp
                return emptyList()
            }
            val array = JSON.parseArray(file.readBytes().toString(Charsets.UTF_8))
            val list = ArrayList<Triple<String, String, String>>(array.size)
            for (i in 0 until array.size) {
                val entry = array.getJSONObject(i) ?: continue
                val name = entry.getString("name") ?: continue
                val uuid = entry.getString("uuid") ?: continue
                list.add(Triple(name, uuid, entry.getString("expiresOn") ?: ""))
            }
            usercacheEntries = list
            usercacheStamp = stamp
            list
        } catch (error: Throwable) {
            PluginFileLog.write("[存档统计] usercache.json 解析失败: ${error.message}")
            usercacheEntries = emptyList()
            usercacheStamp = stamp
            emptyList()
        }
    }

    /**
     * 游戏刻格式化为时长文本——与其他获取源统一的「X时X分」口径：
     * 「35时20分」/「20分」/「0分钟」（ticks 为 0 / 负时兜底）。
     */
    fun formatPlayText(ticks: Long): String {
        val totalSeconds = ticks / 20
        if (totalSeconds <= 0) return "0分钟"
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return buildString {
            if (hours > 0) append("${hours}时")
            if (minutes > 0) append("${minutes}分")
            if (isEmpty()) append("0分钟")
        }
    }

    /**
     * 主世界 stats 目录：首个世界目录下的 stats（四端通用，兼容层
     * getWorlds 返回主世界），失败回退服务器工作目录 world/stats。
     * 只缓存定位成功的结果（失败不缓存——世界尚未加载的极早期查询
     * 不会把 null 定死，下次查询自动重试）。
     *
     * ⚠ 回退在 try 外：旧版把工作目录回退写在 try 内的 else 分支，
     * Bukkit.getWorlds() 抛异常（Fabric 兼容层异常 / 世界未加载阶段）
     * 时直接进 catch，回退永不执行、源恒不可用（1.7.6 覆盖轮冒烟抓出）。
     */
    private fun statsDirectory(): File? {
        cachedDirectory?.let { return it }
        val primary = try {
            Bukkit.getWorlds().firstOrNull()?.worldFolder?.resolve("stats")
        } catch (_: Throwable) {
            null
        }
        val resolved = when {
            primary?.isDirectory == true -> primary
            else -> {
                val fallback = File("world/stats")
                if (fallback.isDirectory) fallback else null
            }
        }
        if (resolved != null) cachedDirectory = resolved
        return resolved
    }

    /**
     * 服务器根目录（usercache.json 所在处）：首世界目录的上一级
     * （world 目录的父目录就是服务器根），失败回退进程工作目录
     * （回退在 try 外，同 [statsDirectory] 的异常路径修复）。
     * 只缓存成功结果。
     */
    private fun serverRoot(): File? {
        cachedServerRoot?.let { return it }
        val parent = try {
            Bukkit.getWorlds().firstOrNull()?.worldFolder?.parentFile
        } catch (_: Throwable) {
            null
        }
        val resolved = when {
            parent != null && parent.resolve("usercache.json").isFile -> parent
            else -> {
                val fallback = File(".")
                if (fallback.resolve("usercache.json").isFile) fallback else parent ?: fallback
            }
        }
        if (resolved != null) cachedServerRoot = resolved
        return resolved
    }

    /** 重置目录与 usercache 缓存（测试 / 服务器重载世界用；正常流程不会触发）。 */
    fun resetCache() {
        cachedDirectory = null
        cachedServerRoot = null
        usercacheEntries = null
        usercacheStamp = Long.MIN_VALUE
    }
}
