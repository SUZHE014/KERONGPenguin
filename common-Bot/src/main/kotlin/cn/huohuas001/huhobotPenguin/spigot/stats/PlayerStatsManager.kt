package cn.huohuas001.huhobotPenguin.spigot.stats

import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.bot.tools.PluginFileLog
import cn.huohuas001.bot.provider.plugin
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
 * 设计目标：低开销、崩溃安全、单文件永不膨胀。
 * - 事件实时累计：挖掘残骸 / 击杀怪物 / 屠龙 / 击杀玩家（1.5.2）/ 死亡 / 钓鱼 / 繁殖 / 触发袭击；
 * - 原版统计快照：行走与飞行距离、总伤害（进入时快照 + 每 60 秒增量同步 + 退出结算），
 *   借助 MC 自带的统计系统，读取仅为主线程内几次 int 读取，开销可忽略；
 * - 游戏时间：会话实时累计（显示时加上当前会话未落账部分）；
 * - 持久化（1.5.3.1）：**一人一文件**，位于 QUUID/stats/<玩家UUID>.yml，
 *   彻底消除旧版“全部玩家合一个 stats.yml”的单文件膨胀风险；
 *   启动零装载（进服 / 查询到该玩家时才读他那一个小文件），
 *   保存只写**有变动**的玩家文件（脏标记：5 分钟周期 + 玩家退出 + 关服），
 *   未变动的玩家零磁盘 IO；崩溃最多丢失 5 分钟数据；
 *   旧版 stats.yml 首次启动自动拆分迁移，完成后归档为 stats.yml.migrated
 *   （一次性备份，不再增长），迁移失败下次启动自动重试。
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
        /** 今日在线（秒）。 */
        @Transient
        var todaySeconds: Long = 0L

        /** 今日日期（yyyy-MM-dd，用于跨天重置）。 */
        @Transient
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

    /** 由插件启用时调用：迁移旧数据并启动定时任务。 */
    fun initialize() {
        migrateLegacyFile()
        // 每 5 分钟异步保存（仅脏文件）
        saveTask = plugin.submitTimer(20L * 60 * 5, 20L * 60 * 5) { saveToDiskAsync() }
        // 每 60 秒主线程增量同步（读取在线玩家原版统计 + 结算游戏时间）
        flushTask = plugin.submitTimer(20L * 60, 20L * 60) { flushOnlineSessions() }
    }

    /** 由插件停用时调用：结算并保存。 */
    fun shutdown() {
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

    /** 玩家进入：懒加载该玩家数据文件并初始化会话。 */
    fun onJoin(player: Player) {
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

    // ---------- 装载（1.5.3.1：懒加载一人一文件） ----------

    /**
     * 懒加载：内存没有该玩家时读他的数据文件（QUUID/stats/<UUID>.yml）。
     * 无文件或读取失败返回 null；并发下先到者写入内存，后到者用先到者的结果。
     */
    private fun ensureLoaded(uuid: UUID): PlayerStats? {
        statsByUuid[uuid]?.let { return it }
        val file = playerFile(uuid) ?: return null
        if (!file.isFile) return null
        return try {
            val yaml = YamlConfiguration.loadConfiguration(file)
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

    // ---------- 持久化（1.5.3.1：仅写脏文件） ----------

    /** 异步保存。 */
    fun saveToDiskAsync() {
        try {
            plugin.submitAsync { saveToDiskBlocking() }
        } catch (_: Throwable) {
            // 插件停用阶段忽略
        }
    }

    /** 阻塞保存（异步线程或关服时调用；只写脏标记的玩家文件）。 */
    @Synchronized
    fun saveToDiskBlocking() {
        try {
            if (dirtyUuids.isEmpty()) return
            val startedAt = System.currentTimeMillis()
            val folder = statsFolder() ?: return
            var saved = 0
            val iterator = dirtyUuids.iterator()
            while (iterator.hasNext()) {
                val uuid = iterator.next()
                // 先摘除再写：写入期间产生的新变动会重新标脏，下轮补写，不丢数据
                iterator.remove()
                val stats = statsByUuid[uuid] ?: continue
                val yaml = YamlConfiguration()
                yaml.set("play-seconds", stats.playSeconds)
                yaml.set("mob-kills", stats.mobKills)
                yaml.set("dragon-kills", stats.dragonKills)
                yaml.set("player-kills", stats.playerKills)
                yaml.set("deaths", stats.deaths)
                yaml.set("fish-caught", stats.fishCaught)
                yaml.set("ancient-debris", stats.ancientDebris)
                yaml.set("raid-triggers", stats.raidTriggers)
                yaml.set("animals-bred", stats.animalsBred)
                yaml.set("walk-cm", stats.walkCm)
                yaml.set("fly-cm", stats.flyCm)
                yaml.set("damage-dealt", stats.damageDealt)
                try {
                    yaml.save(File(folder, "$uuid.yml"))
                    saved++
                } catch (_: Throwable) {
                    markDirty(uuid) // 该文件写失败：保住脏标记，下轮重试
                }
            }
            // 0.1.5.3：数据保存日志——仅一行汇总；0.1.5.4 起仅写插件日志文件不刷控制台
            PluginFileLog.write(
                "[数据保存] 玩家统计数据已保存：$saved 个玩家文件（仅变动文件），耗时 ${System.currentTimeMillis() - startedAt}ms"
            )
        } catch (error: Exception) {
            PluginFileLog.errorAndKeep("[数据保存] 保存统计数据失败: ${error.message}")
        }
    }

    /**
     * 一次性迁移（1.5.3.1）：旧版全部玩家合一的 stats.yml →
     * QUUID/stats/<UUID>.yml 一人一文件；完成后旧文件改名 stats.yml.migrated
     * 归档（保留一次性备份，不再增长）。启用期同步执行（早于任何玩家进服），
     * 失败时旧文件保留、已拆出的文件幂等跳过，下次启动自动重试。
     */
    private fun migrateLegacyFile() {
        try {
            val dataFolder = plugin.configFile?.parentFile ?: return
            val legacy = File(dataFolder, "stats.yml")
            if (!legacy.isFile) return
            val startedAt = System.currentTimeMillis()
            val config = YamlConfiguration.loadConfiguration(legacy)
            val section = config.getConfigurationSection("players")
            val folder = File(dataFolder, "QUUID/stats")
            if (!folder.isDirectory) folder.mkdirs()
            var migrated = 0
            if (section != null) {
                for (key in section.getKeys(false)) {
                    val uuid = try {
                        UUID.fromString(key)
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                    val target = File(folder, "$uuid.yml")
                    // 已有同 UUID 文件时跳过（上次迁移中断重试 / 新数据优先）
                    if (target.isFile) {
                        migrated++
                        continue
                    }
                    val yaml = YamlConfiguration()
                    yaml.set("play-seconds", section.getLong("$key.play-seconds"))
                    yaml.set("mob-kills", section.getLong("$key.mob-kills"))
                    yaml.set("dragon-kills", section.getLong("$key.dragon-kills"))
                    yaml.set("player-kills", section.getLong("$key.player-kills"))
                    yaml.set("deaths", section.getLong("$key.deaths"))
                    yaml.set("fish-caught", section.getLong("$key.fish-caught"))
                    yaml.set("ancient-debris", section.getLong("$key.ancient-debris"))
                    yaml.set("raid-triggers", section.getLong("$key.raid-triggers"))
                    yaml.set("animals-bred", section.getLong("$key.animals-bred"))
                    yaml.set("walk-cm", section.getLong("$key.walk-cm"))
                    yaml.set("fly-cm", section.getLong("$key.fly-cm"))
                    yaml.set("damage-dealt", section.getLong("$key.damage-dealt"))
                    try {
                        yaml.save(target)
                        migrated++
                    } catch (_: Throwable) {
                    }
                }
            }
            if (!legacy.renameTo(File(dataFolder, "stats.yml.migrated"))) {
                PluginFileLog.errorAndKeep(
                    "[数据迁移] stats.yml 归档失败（已拆分 $migrated 名玩家），下次启动将重试（已拆出的文件自动跳过）"
                )
                return
            }
            PluginFileLog.write(
                "[数据迁移] stats.yml（$migrated 名玩家）已拆分为 QUUID/stats/ 一人一文件，" +
                    "旧文件归档为 stats.yml.migrated，耗时 ${System.currentTimeMillis() - startedAt}ms"
            )
        } catch (error: Throwable) {
            PluginFileLog.errorAndKeep("[数据迁移] stats.yml 迁移失败: ${error.message}")
        }
    }

    // ---------- 工具 ----------

    private fun markDirty(uuid: UUID) {
        dirtyUuids.add(uuid)
    }

    /** 玩家是否已有统计记录（内存或数据文件）。 */
    fun hasData(uuid: UUID): Boolean = statsByUuid.containsKey(uuid) || playerFile(uuid)?.isFile == true

    /** 数据目录：<插件目录>/QUUID/stats（1.5.3.1 起一人一文件）。 */
    private fun statsFolder(): File? = try {
        val dataFolder = plugin.configFile?.parentFile ?: return null
        val folder = File(dataFolder, "QUUID/stats")
        if (!folder.isDirectory) folder.mkdirs()
        folder
    } catch (_: Throwable) {
        null
    }

    /** 单个玩家的数据文件路径（读用，不创建目录）。 */
    private fun playerFile(uuid: UUID): File? = try {
        val dataFolder = plugin.configFile?.parentFile ?: return null
        File(File(dataFolder, "QUUID/stats"), "$uuid.yml")
    } catch (_: Throwable) {
        null
    }

    /** 调试用：当前数据文件数量（懒加载下内存仅含本次启动触达的玩家）。 */
    fun trackedCount(): Int = try {
        statsFolder()?.listFiles { file -> file.isFile && file.name.endsWith(".yml") }?.size ?: 0
    } catch (_: Throwable) {
        statsByUuid.size
    }

    /** 北京时区当前日期（本地时钟计算，无网络开销）。 */
    private fun beijingToday(): String =
        SimpleDateFormat("yyyy-MM-dd")
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date())

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
