package cn.huohuas001.huhobotPenguin.spigot.stats

import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.bot.tools.PluginFileLog
import cn.huohuas001.bot.provider.plugin
import cn.huohuas001.huhobotPenguin.spigot.qqbind.BeijingTimeUtil
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 玩家统计数据记录器（/查信息 卡片的数据源）。
 *
 * 设计目标：低开销、崩溃安全、按玩家 UUID 一人一条。
 * - 事件实时累计：挖掘残骸 / 击杀怪物 / 屠龙 / 击杀玩家（1.5.2）/ 死亡 / 钓鱼 / 繁殖 / 触发袭击；
 * - 原版统计快照：行走与飞行距离、总伤害（进入时快照 + 每 60 秒增量同步 + 退出结算），
 *   借助 MC 自带的统计系统，读取仅为主线程内几次 int 读取，开销可忽略；
 * - 游戏时间：会话实时累计（显示时加上当前会话未落账部分）；
 * - 记录维度（1.5.3.1 覆盖版）：**按玩家 UUID**——同名玩家 UUID 不同就是两个人，
 *   各自独立计数互不串数据；
 * - 持久化（1.5.3.1 覆盖版）：统计数据写入玩家自己的 **QUUID 主文件**
 *   （`QUUID/<QUUID>.yml` 的 `stats` 节，与绑定 / 签到 / 昵称同在一人一文件里），
 *   不再有独立的 stats 子目录或第二套 ID 键；内存懒加载（进服 / 查询到该玩家时
 *   才读他那一份主文件），保存只写**有变动**的玩家（脏标记：5 分钟周期 +
 *   玩家退出 + 关服，先摘除再写防丢数，写失败重新标脏）；
 *   旧版数据自动迁移：stats.yml（全部玩家合一）与首轮 1.5.3.1 的
 *   QUUID/stats/<UUID>.yml 均在首次启动时并入各自玩家的主文件，
 *   迁移失败下次启动自动重试；
 * - 写入经 QqBindManager 的统一锁与原子写，与签到 / 昵称等写入方互不覆盖。
 *
 * 1.5.4：今日在线时长不再随重启清零——
 * - `todaySeconds` / `today` 随统计一并持久化到 QUUID 主文件 stats 节
 *   （`stats.today-seconds` / `stats.today` 键，每 60 秒增量落账 + 退出 / 关服结算 +
 *   5 分钟保存周期），同一天内重启 / 重进服务器从上次落账值继续累计；
 * - “今日”边界改用异步网络北京时间（[BeijingTimeUtil] 多时间源容错）：
 *   每 60 秒后台线程刷新内存缓存，主线程（进服 / 退出 / 结算路径）只读缓存
 *   绝不阻塞；网络不可用自动回退本地 Asia/Shanghai 时钟，与签到同一时间基准。
 */
object PlayerStatsManager {

    /** 单个玩家的统计数据（可安全跨线程读取的快照字段）。 */
    data class PlayerStats(
        var playSeconds: Long = 0L,
        var mobKills: Long = 0L,
        var dragonKills: Long = 0L,
        var playerKills: Long = 0L,
        var deaths: Long = 0L,
        var fishCaught: Long = 0L,
        var ancientDebris: Long = 0L,
        var raidTriggers: Long = 0L,
        var animalsBred: Long = 0L,
        var walkCm: Long = 0L,
        var flyCm: Long = 0L,
        var damageDealt: Long = 0L,
    ) {
        /** 今日在线（秒，1.5.4 起随统计持久化到 QUUID 主文件 stats 节）。 */
        var todaySeconds: Long = 0L

        /** 今日日期（yyyy-MM-dd，跨天重置判定；1.5.4 起持久化）。 */
        var today: String = ""
    }

    /** 在线会话的运行时状态（仅主线程访问）。 */
    private class Session(
        val joinAt: Long,
        var lastWalkCm: Int = 0,
        var lastFlyCm: Int = 0,
        var lastDamage: Int = 0,
        var lastFlushAt: Long = joinAt,
    )

    private val statsByUuid = ConcurrentHashMap<UUID, PlayerStats>()
    private val sessions = ConcurrentHashMap<UUID, Session>()

    /**
     * 待保存玩家（脏标记，1.5.3.1）：只重写有变动的玩家文件，
     * 未变动的玩家（含大量离线历史玩家）保存周期内零磁盘 IO。
     */
    private val dirtyUuids: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var saveTask: Cancelable? = null

    @Volatile
    private var flushTask: Cancelable? = null

    /** 北京当前日期缓存（1.5.4：异步网络校时，主线程只读，绝不阻塞）。 */
    @Volatile
    private var beijingTodayCache: String = ""

    /** 北京日期周期刷新任务（1.5.4）。 */
    @Volatile
    private var beijingRefreshTask: Cancelable? = null

    /** 迁移未完成（绑定管理器未就绪等）时置 true，首次进服重试。 */
    @Volatile
    private var migrationPending = false

    /** 由插件启用时调用：迁移旧数据并启动定时任务。 */
    fun initialize() {
        migrateLegacyData()
        // 1.5.4：立即异步网络校时一次 + 每 60 秒后台刷新北京日期缓存
        // （BeijingTimeUtil 内部缓存 5 分钟，刷新周期 60 秒时多数为零网络开销）
        refreshBeijingTodayAsync()
        beijingRefreshTask = plugin.submitTimer(20L, 20L * 60) { refreshBeijingTodayAsync() }
        // 每 5 分钟异步保存（仅脏玩家）
        saveTask = plugin.submitTimer(20L * 60 * 5, 20L * 60 * 5) { saveToDiskAsync() }
        // 每 60 秒主线程增量同步（读取在线玩家原版统计 + 结算游戏时间）
        flushTask = plugin.submitTimer(20L * 60, 20L * 60) { flushOnlineSessions() }
    }

    /** 由插件停用时调用：结算并保存。 */
    fun shutdown() {
        beijingRefreshTask?.cancel()
        saveTask?.cancel()
        flushTask?.cancel()
        if (Bukkit.isPrimaryThread()) {
            flushOnlineSessions()
            saveToDiskBlocking()
        } else {
            val bukkitPlugin = Bukkit.getPluginManager().getPlugin("KERONGPenguin")
            if (bukkitPlugin != null) {
                Bukkit.getScheduler().runTask(bukkitPlugin, Runnable {
                    flushOnlineSessions()
                    saveToDiskBlocking()
                })
            }
        }
    }

    /** 玩家进入：确保 QUUID 主文件存在（按 UUID，同名不同 UUID 各自独立）并懒加载统计。 */
    fun onJoin(player: Player) {
        // 1.5.3.1 覆盖版：统计写入玩家自己的 QUUID 主文件，进服时确保存在
        val manager = bindManagerOrNull()
        if (manager != null) {
            if (migrationPending) migrateLegacyData()
            try {
                manager.getOrCreateQuuidByUuid(player.name, player.uniqueId.toString())
            } catch (_: Throwable) {
            }
        }
        val stats = loadOrCreate(player.uniqueId)
        rollTodayIfNeeded(stats)
        sessions[player.uniqueId] = Session(
            joinAt = System.currentTimeMillis(),
            lastWalkCm = safeStatistic(player, Statistic.WALK_ONE_CM),
            lastFlyCm = safeStatistic(player, Statistic.FLY_ONE_CM),
            lastDamage = safeStatistic(player, Statistic.DAMAGE_DEALT),
        )
    }

    /** 玩家退出：结算会话并异步保存（仅该玩家等脏文件）。 */
    fun onQuit(player: Player) {
        val uuid = player.uniqueId
        val session = sessions.remove(uuid) ?: return
        settleSession(player, session)
        saveToDiskAsync()
    }

    /**
     * 主线程同步一次在线玩家的增量（原版统计差量 + 游戏时间落账）。
     * 每次仅做几次 int 读取与减法，开销可忽略。
     */
    fun flushOnlineSessions() {
        val now = System.currentTimeMillis()
        for ((uuid, session) in sessions) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            val stats = loadOrCreate(uuid)
            rollTodayIfNeeded(stats)
            applyStatDelta(player, session, stats)
            val elapsed = (now - session.lastFlushAt).coerceAtLeast(0L) / 1000L
            stats.playSeconds += elapsed
            stats.todaySeconds += elapsed
            session.lastFlushAt = now
            markDirty(uuid)
        }
    }

    /** 破坏方块（远古残骸）。 */
    fun onBlockBreak(player: Player, material: Material) {
        if (material == Material.ANCIENT_DEBRIS) {
            loadOrCreate(player.uniqueId).ancientDebris++
            markDirty(player.uniqueId)
        }
    }

    /** 实体死亡（怪物击杀 / 屠龙 / 击杀玩家）。 */
    fun onEntityDeath(killer: Player, entityType: EntityType) {
        val stats = loadOrCreate(killer.uniqueId)
        if (entityType == EntityType.PLAYER) {
            // 1.5.2：击杀玩家单独计数（此前玩家击杀既不计屠龙也不计怪物击杀）；
            // 自伤/自杀已在监听器过滤，不会计入
            stats.playerKills++
        } else if (entityType == EntityType.ENDER_DRAGON) {
            stats.dragonKills++
        } else if (entityType.isAlive) {
            stats.mobKills++
        }
        markDirty(killer.uniqueId)
    }

    /** 玩家死亡。 */
    fun onPlayerDeath(player: Player) {
        loadOrCreate(player.uniqueId).deaths++
        markDirty(player.uniqueId)
    }

    /** 钓到鱼。 */
    fun onFishCaught(player: Player) {
        loadOrCreate(player.uniqueId).fishCaught++
        markDirty(player.uniqueId)
    }

    /** 繁殖动物。 */
    fun onAnimalBred(player: Player) {
        loadOrCreate(player.uniqueId).animalsBred++
        markDirty(player.uniqueId)
    }

    /** 触发袭击。 */
    fun onRaidTrigger(player: Player) {
        loadOrCreate(player.uniqueId).raidTriggers++
        markDirty(player.uniqueId)
    }

    /**
     * 查询玩家统计快照（含在线会话的实时增量）。
     * 线程安全：异步线程调用时自动切回主线程读取；
     * 该玩家尚未在内存时先懒加载他的数据文件（单个小文件，开销可忽略）。
     */
    fun query(uuid: UUID): PlayerStats {
        val stored = ensureLoaded(uuid) ?: PlayerStats()
        val session = sessions[uuid] ?: return copyOf(stored)
        val bukkitPlugin = Bukkit.getPluginManager().getPlugin("KERONGPenguin") ?: return copyOf(stored)
        return if (Bukkit.isPrimaryThread()) {
            queryOnMainThread(uuid, session, stored)
        } else {
            try {
                // 限时等待，避免主线程卡顿时拖死消息线程
                Bukkit.getScheduler()
                    .callSyncMethod(bukkitPlugin) { queryOnMainThread(uuid, session, stored) }
                    .get(3, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
                copyOf(stored)
            }
        }
    }

    /** 在主线程计算实时快照（不落账，仅用于展示）。 */
    private fun queryOnMainThread(uuid: UUID, session: Session, stored: PlayerStats): PlayerStats {
        val stats = statsByUuid[uuid] ?: stored
        val player = Bukkit.getPlayer(uuid) ?: return copyOf(stats)
        val copy = copyOf(stats)
        val now = System.currentTimeMillis()
        val elapsed = (now - session.lastFlushAt).coerceAtLeast(0L) / 1000L
        copy.playSeconds = stats.playSeconds + elapsed
        copy.todaySeconds = if (rollDay(copy)) elapsed else stats.todaySeconds + elapsed
        copy.walkCm = stats.walkCm + (safeStatistic(player, Statistic.WALK_ONE_CM) - session.lastWalkCm).coerceAtLeast(0)
        copy.flyCm = stats.flyCm + (safeStatistic(player, Statistic.FLY_ONE_CM) - session.lastFlyCm).coerceAtLeast(0)
        copy.damageDealt = stats.damageDealt + (safeStatistic(player, Statistic.DAMAGE_DEALT) - session.lastDamage).coerceAtLeast(0)
        return copy
    }

    /** 将会话的原版统计差量与游戏时间落账。 */
    private fun settleSession(player: Player, session: Session) {
        val stats = loadOrCreate(player.uniqueId)
        rollTodayIfNeeded(stats)
        applyStatDelta(player, session, stats)
        val elapsed = (System.currentTimeMillis() - session.lastFlushAt).coerceAtLeast(0L) / 1000L
        stats.playSeconds += elapsed
        stats.todaySeconds += elapsed
        markDirty(player.uniqueId)
    }

    /** 应用原版统计差量并更新快照。 */
    private fun applyStatDelta(player: Player, session: Session, stats: PlayerStats) {
        val walk = safeStatistic(player, Statistic.WALK_ONE_CM)
        val fly = safeStatistic(player, Statistic.FLY_ONE_CM)
        val damage = safeStatistic(player, Statistic.DAMAGE_DEALT)
        stats.walkCm += (walk - session.lastWalkCm).coerceAtLeast(0)
        stats.flyCm += (fly - session.lastFlyCm).coerceAtLeast(0)
        stats.damageDealt += (damage - session.lastDamage).coerceAtLeast(0)
        session.lastWalkCm = walk
        session.lastFlyCm = fly
        session.lastDamage = damage
    }

    // ---------- 装载（1.5.3.1 覆盖版：读 QUUID 主文件 stats 节） ----------

    /** 绑定管理器（不可用时返回 null，读写方自行降级）。 */
    private fun bindManagerOrNull(): QqBindManager? = try {
        QqBindManager.getInstance()
    } catch (_: Throwable) {
        null
    }

    /**
     * 懒加载：内存没有该玩家时读他的 QUUID 主文件 stats 节。
     * 无主文件或读取失败返回 null；并发下先到者写入内存，后到者用先到者的结果。
     */
    private fun ensureLoaded(uuid: UUID): PlayerStats? {
        statsByUuid[uuid]?.let { return it }
        val manager = bindManagerOrNull() ?: return null
        val quuid = manager.getQuuidByPlayerUuid(uuid.toString()) ?: return null
        val yaml = manager.readQuuidRecord(quuid) ?: return null
        return try {
            val stats = statsFromSection(yaml)
            statsByUuid.putIfAbsent(uuid, stats) ?: stats
        } catch (_: Throwable) {
            null
        }
    }

    /** 取统计数据：内存优先 → 懒加载 → 最终创建空记录。 */
    private fun loadOrCreate(uuid: UUID): PlayerStats {
        ensureLoaded(uuid)?.let { return it }
        val fresh = PlayerStats()
        return statsByUuid.putIfAbsent(uuid, fresh) ?: fresh
    }

    // ---------- 持久化（1.5.3.1 覆盖版：写入各自 QUUID 主文件 stats 节） ----------

    /** 异步保存。 */
    fun saveToDiskAsync() {
        try {
            plugin.submitAsync { saveToDiskBlocking() }
        } catch (_: Throwable) {
            // 插件停用阶段忽略
        }
    }

    /**
     * 阻塞保存（异步线程或关服时调用；只写脏玩家的主文件 stats 节，
     * 经绑定管理器统一锁 + 原子写，与签到 / 昵称等写入方互不覆盖）。
     */
    @Synchronized
    fun saveToDiskBlocking() {
        try {
            if (dirtyUuids.isEmpty()) return
            val manager = bindManagerOrNull() ?: return // 管理器不可用：保留脏标记下轮重试
            val startedAt = System.currentTimeMillis()
            var saved = 0
            val iterator = dirtyUuids.iterator()
            while (iterator.hasNext()) {
                val uuid = iterator.next()
                // 先摘除再写：写入期间产生的新变动会重新标脏，下轮补写，不丢数据
                iterator.remove()
                val stats = statsByUuid[uuid] ?: continue
                val quuid = manager.getQuuidByPlayerUuid(uuid.toString())
                if (quuid == null) {
                    // 无主文件（仅出现在管理器异常窗口）：保住脏标记下轮重试
                    markDirty(uuid)
                    continue
                }
                val written = manager.updateQuuidRecord(quuid) { yaml ->
                    writeStatsSection(yaml, stats)
                }
                if (written) {
                    saved++
                } else {
                    markDirty(uuid) // 写失败：保住脏标记，下轮重试
                }
            }
            // 0.1.5.3：数据保存日志——仅一行汇总；0.1.5.4 起仅写插件日志文件不刷控制台
            PluginFileLog.write(
                "[数据保存] 玩家统计数据已保存：$saved 个玩家（写入各自 QUUID 主文件），耗时 ${System.currentTimeMillis() - startedAt}ms"
            )
        } catch (error: Exception) {
            PluginFileLog.errorAndKeep("[数据保存] 保存统计数据失败: ${error.message}")
        }
    }

    /**
     * 一次性迁移（1.5.3.1 覆盖版）：把两个历史布局的统计数据并入各自玩家的
     * QUUID 主文件 stats 节——
     * ① stats.yml（老版全部玩家合一，含已归档的 stats.yml.migrated 前身）；
     * ② 首轮 1.5.3.1 的 QUUID/stats/<UUID>.yml 子目录（本次覆盖发布后不再使用）。
     * 幂等：主文件已有 stats 节时跳过（新数据优先）；QUUID 主文件不存在时
     * 按玩家 UUID 定位（索引 → 用户缓存名字 → 新建）后再写入；
     * 旧文件迁移成功后删除（stats.yml 归档为 stats.yml.migrated），
     * 失败的保留下来下次启动自动重试。
     */
    private fun migrateLegacyData() {
        val manager = bindManagerOrNull() ?: run {
            migrationPending = true
            return
        }
        try {
            // 迁移顺序：过渡目录（首轮 1.5.3.1 拆出，数据更新）先行，老 stats.yml
            // 后行（其条目在主文件已有 stats 节时自动跳过）——两源共存时新数据优先
            val fromFolder = migrateInterimStatsFolder(manager)
            val fromYml = migrateLegacyStatsYml(manager)
            migrationPending = false
            if (fromYml + fromFolder > 0) {
                PluginFileLog.write(
                    "[数据迁移] 已将 $fromYml 条（stats.yml）与 $fromFolder 条（QUUID/stats/）" +
                        "统计数据并入对应玩家的 QUUID 主文件"
                )
            }
        } catch (error: Throwable) {
            migrationPending = true
            PluginFileLog.errorAndKeep("[数据迁移] 统计数据迁移失败: ${error.message}（下次启动自动重试）")
        }
    }

    /** 迁移老版 stats.yml（全部玩家合一）→ 各自 QUUID 主文件；返回迁移条数。 */
    private fun migrateLegacyStatsYml(manager: QqBindManager): Int {
        try {
            val dataFolder = plugin.configFile?.parentFile ?: return 0
            val legacy = File(dataFolder, "stats.yml")
            if (!legacy.isFile) return 0
            val section = YamlConfiguration.loadConfiguration(legacy).getConfigurationSection("players")
                ?: run {
                    // 空文件直接归档，避免每次启动重复解析
                    legacy.renameTo(File(dataFolder, "stats.yml.migrated"))
                    return 0
                }
            var migrated = 0
            var failed = 0
            for (key in section.getKeys(false)) {
                val uuid = try {
                    UUID.fromString(key)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                val stats = PlayerStats(
                    playSeconds = section.getLong("$key.play-seconds"),
                    mobKills = section.getLong("$key.mob-kills"),
                    dragonKills = section.getLong("$key.dragon-kills"),
                    playerKills = section.getLong("$key.player-kills"),
                    deaths = section.getLong("$key.deaths"),
                    fishCaught = section.getLong("$key.fish-caught"),
                    ancientDebris = section.getLong("$key.ancient-debris"),
                    raidTriggers = section.getLong("$key.raid-triggers"),
                    animalsBred = section.getLong("$key.animals-bred"),
                    walkCm = section.getLong("$key.walk-cm"),
                    flyCm = section.getLong("$key.fly-cm"),
                    damageDealt = section.getLong("$key.damage-dealt"),
                )
                if (mergeIntoQuuidRecord(manager, uuid, stats)) {
                    migrated++
                } else {
                    failed++
                }
            }
            if (failed > 0) {
                // 有条目定位不到玩家（查不到名字建不了索引 key）：保留 stats.yml
                // 下次启动重试（已并入的自动跳过），不归档防丢数
                PluginFileLog.errorAndKeep(
                    "[数据迁移] stats.yml 有 $failed 名玩家暂未能并入主文件（已在索引/名字可解析时自动完成），" +
                        "保留旧文件下次启动重试（已并入 $migrated 名自动跳过）"
                )
                return migrated
            }
            if (!legacy.renameTo(File(dataFolder, "stats.yml.migrated"))) {
                PluginFileLog.errorAndKeep(
                    "[数据迁移] stats.yml 归档失败（已迁移 $migrated 名玩家），下次启动将重试（已并入的自动跳过）"
                )
            }
            return migrated
        } catch (_: Throwable) {
            return 0
        }
    }

    /**
     * 迁移首轮 1.5.3.1 的 QUUID/stats/<UUID>.yml 子目录 → 各自 QUUID 主文件；
     * 成功一条删一条，全部处理后删除空目录；返回迁移条数。
     */
    private fun migrateInterimStatsFolder(manager: QqBindManager): Int {
        try {
            val dataFolder = plugin.configFile?.parentFile ?: return 0
            val folder = File(dataFolder, "QUUID/stats")
            if (!folder.isDirectory) return 0
            val files = folder.listFiles { f -> f.isFile && f.name.endsWith(".yml") } ?: return 0
            var migrated = 0
            for (file in files) {
                val uuid = try {
                    UUID.fromString(file.name.removeSuffix(".yml"))
                } catch (_: IllegalArgumentException) {
                    file.delete() // 非法命名（非 UUID）：不是玩家数据，清理
                    continue
                }
                val yaml = try {
                    YamlConfiguration.loadConfiguration(file)
                } catch (_: Throwable) {
                    continue
                }
                val stats = PlayerStats(
                    playSeconds = yaml.getLong("play-seconds"),
                    mobKills = yaml.getLong("mob-kills"),
                    dragonKills = yaml.getLong("dragon-kills"),
                    playerKills = yaml.getLong("player-kills"),
                    deaths = yaml.getLong("deaths"),
                    fishCaught = yaml.getLong("fish-caught"),
                    ancientDebris = yaml.getLong("ancient-debris"),
                    raidTriggers = yaml.getLong("raid-triggers"),
                    animalsBred = yaml.getLong("animals-bred"),
                    walkCm = yaml.getLong("walk-cm"),
                    flyCm = yaml.getLong("fly-cm"),
                    damageDealt = yaml.getLong("damage-dealt"),
                )
                if (mergeIntoQuuidRecord(manager, uuid, stats)) {
                    file.delete() // 已并入主文件：删除过渡文件（保留则违反一人一文件）
                    migrated++
                }
            }
            // 目录清空后移除，QUUID/ 下不再有任何子目录
            val remaining = folder.listFiles()
            if (remaining != null && remaining.isEmpty()) folder.delete()
            return migrated
        } catch (_: Throwable) {
            return 0
        }
    }

    /**
     * 把一份统计并入玩家 UUID 对应的 QUUID 主文件（幂等：已有 stats 节则跳过，
     * 新数据优先）；QUUID 不存在时定位（内存索引 → 服务器用户缓存名字 → 新建）。
     */
    private fun mergeIntoQuuidRecord(manager: QqBindManager, uuid: UUID, stats: PlayerStats): Boolean {
        return try {
            var quuid = manager.getQuuidByPlayerUuid(uuid.toString())
            if (quuid == null) {
                val name = resolveLegacyName(uuid)
                if (name == null) return false // 查不到名字建不了索引 key：留给下次重试
                quuid = manager.getOrCreateQuuidByUuid(name, uuid.toString())
            }
            manager.updateQuuidRecord(quuid) { yaml ->
                if (!yaml.contains("stats")) {
                    writeStatsSection(yaml, stats)
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 从服务器用户缓存解析历史玩家名（迁移建索引用；解析不到返回 null）。 */
    private fun resolveLegacyName(uuid: UUID): String? = try {
        Bukkit.getOfflinePlayer(uuid).name
    } catch (_: Throwable) {
        null
    }

    // ---------- stats 节读写 ----------

    /** 从 QUUID 主文件的 stats 节读出统计。 */
    private fun statsFromSection(yaml: YamlConfiguration): PlayerStats = PlayerStats(
        playSeconds = yaml.getLong("stats.play-seconds"),
        mobKills = yaml.getLong("stats.mob-kills"),
        dragonKills = yaml.getLong("stats.dragon-kills"),
        playerKills = yaml.getLong("stats.player-kills"),
        deaths = yaml.getLong("stats.deaths"),
        fishCaught = yaml.getLong("stats.fish-caught"),
        ancientDebris = yaml.getLong("stats.ancient-debris"),
        raidTriggers = yaml.getLong("stats.raid-triggers"),
        animalsBred = yaml.getLong("stats.animals-bred"),
        walkCm = yaml.getLong("stats.walk-cm"),
        flyCm = yaml.getLong("stats.fly-cm"),
        damageDealt = yaml.getLong("stats.damage-dealt"),
    ).also {
        // 1.5.4：今日在线与日期回读（旧记录无此键 → 空日期，首次跨天判定补齐）
        it.todaySeconds = yaml.getLong("stats.today-seconds")
        it.today = yaml.getString("stats.today") ?: ""
    }

    /** 把统计写入 QUUID 主文件的 stats 节（同一玩家：同名不同 UUID = 不同主文件）。 */
    private fun writeStatsSection(yaml: YamlConfiguration, stats: PlayerStats) {
        yaml.set("stats.play-seconds", stats.playSeconds)
        yaml.set("stats.mob-kills", stats.mobKills)
        yaml.set("stats.dragon-kills", stats.dragonKills)
        yaml.set("stats.player-kills", stats.playerKills)
        yaml.set("stats.deaths", stats.deaths)
        yaml.set("stats.fish-caught", stats.fishCaught)
        yaml.set("stats.ancient-debris", stats.ancientDebris)
        yaml.set("stats.raid-triggers", stats.raidTriggers)
        yaml.set("stats.animals-bred", stats.animalsBred)
        yaml.set("stats.walk-cm", stats.walkCm)
        yaml.set("stats.fly-cm", stats.flyCm)
        yaml.set("stats.damage-dealt", stats.damageDealt)
        // 1.5.4：今日在线随统计落盘（重启 / 重进不再清零，跨天才重置）
        yaml.set("stats.today-seconds", stats.todaySeconds)
        yaml.set("stats.today", stats.today)
    }

    // ---------- 工具 ----------

    private fun markDirty(uuid: UUID) {
        dirtyUuids.add(uuid)
    }

    /** 玩家是否已有统计记录（内存或其 QUUID 主文件 stats 节）。 */
    fun hasData(uuid: UUID): Boolean {
        if (statsByUuid.containsKey(uuid)) return true
        val manager = bindManagerOrNull() ?: return false
        val quuid = manager.getQuuidByPlayerUuid(uuid.toString()) ?: return false
        return manager.readQuuidRecord(quuid)?.contains("stats") == true
    }

    /** 调试用：当前内存中已加载统计的玩家数（懒加载，仅含本次启动触达的玩家）。 */
    fun trackedCount(): Int = statsByUuid.size

    /**
     * 北京当前日期（1.5.4）：优先异步网络校时缓存（与签到同一时间基准）；
     * 缓存未就绪（启动初期 / 断网）时本地 Asia/Shanghai 时钟兑底，
     * 主线程调用零网络开销、零阻塞。
     */
    private fun beijingToday(): String {
        val cached = beijingTodayCache
        if (cached.isNotEmpty()) return cached
        return SimpleDateFormat("yyyy-MM-dd")
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date())
    }

    /**
     * 后台异步刷新北京日期缓存（1.5.4）。网络失败保留旧值（读取方本地兑底），
     * 跨天时写一行日志便于事后核对。
     */
    private fun refreshBeijingTodayAsync() {
        try {
            plugin.submitAsync {
                val date = try {
                    BeijingTimeUtil.getBeijingDate()
                } catch (_: Throwable) {
                    null
                }
                if (!date.isNullOrEmpty()) {
                    if (beijingTodayCache.isNotEmpty() && beijingTodayCache != date) {
                        PluginFileLog.write("[统计] 北京日期已跨天：$beijingTodayCache → $date")
                    }
                    beijingTodayCache = date
                }
            }
        } catch (_: Throwable) {
            // 插件停用阶段忽略
        }
    }

    /** 跨天时重置今日在线。 */
    private fun rollTodayIfNeeded(stats: PlayerStats) {
        val today = beijingToday()
        if (stats.today != today) {
            stats.today = today
            stats.todaySeconds = 0L
        }
    }

    private fun rollDay(stats: PlayerStats): Boolean {
        val today = beijingToday()
        if (stats.today != today) {
            stats.today = today
            stats.todaySeconds = 0L
            return true
        }
        return false
    }

    /** 复制一份数据（避免外部修改内存数据）。 */
    private fun copyOf(stats: PlayerStats): PlayerStats = PlayerStats(
        playSeconds = stats.playSeconds,
        mobKills = stats.mobKills,
        dragonKills = stats.dragonKills,
        playerKills = stats.playerKills,
        deaths = stats.deaths,
        fishCaught = stats.fishCaught,
        ancientDebris = stats.ancientDebris,
        raidTriggers = stats.raidTriggers,
        animalsBred = stats.animalsBred,
        walkCm = stats.walkCm,
        flyCm = stats.flyCm,
        damageDealt = stats.damageDealt,
    ).also {
        it.todaySeconds = stats.todaySeconds
        it.today = stats.today
    }

    /** 安全读取原版统计（不支持时返回 0）。 */
    private fun safeStatistic(player: Player, statistic: Statistic): Int = try {
        player.getStatistic(statistic)
    } catch (_: Throwable) {
        0
    }
}
