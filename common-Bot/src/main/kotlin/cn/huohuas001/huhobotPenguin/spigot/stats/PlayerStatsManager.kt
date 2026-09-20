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
 * 设计目标：低开销、崩溃安全。
 * - 事件实时累计：挖掘残骸 / 击杀怪物 / 屠龙 / 击杀玩家（1.5.2）/ 死亡 / 钓鱼 / 繁殖 / 触发袭击；
 * - 原版统计快照：行走与飞行距离、总伤害（进入时快照 + 每 60 秒增量同步 + 退出结算），
 *   借助 MC 自带的统计系统，读取仅为主线程内几次 int 读取，开销可忽略；
 * - 游戏时间：会话实时累计（显示时加上当前会话未落账部分）；
 * - 持久化：内存累计 + 每 5 分钟异步保存 + 玩家退出异步保存 + 关服保存，
 *   崩溃最多丢失 5 分钟数据。
 *
 * 数据文件：plugins/KERONGPenguin/stats.yml（全部玩家一个文件，异步读写）。
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

    @Volatile
    private var saveTask: Cancelable? = null

    @Volatile
    private var flushTask: Cancelable? = null

    /** 由插件启用时调用：装载数据并启动定时任务。 */
    fun initialize() {
        loadFromDisk()
        // 每 5 分钟异步保存
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

    /** 玩家进入：初始化会话与统计。 */
    fun onJoin(player: Player) {
        val stats = statsByUuid.computeIfAbsent(player.uniqueId) { PlayerStats() }
        rollTodayIfNeeded(stats)
        sessions[player.uniqueId] = Session(
            joinAt = System.currentTimeMillis(),
            lastWalkCm = safeStatistic(player, Statistic.WALK_ONE_CM),
            lastFlyCm = safeStatistic(player, Statistic.FLY_ONE_CM),
            lastDamage = safeStatistic(player, Statistic.DAMAGE_DEALT),
        )
    }

    /** 玩家退出：结算会话并异步保存。 */
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
            val stats = statsByUuid.computeIfAbsent(uuid) { PlayerStats() }
            rollTodayIfNeeded(stats)
            applyStatDelta(player, session, stats)
            val elapsed = (now - session.lastFlushAt).coerceAtLeast(0L) / 1000L
            stats.playSeconds += elapsed
            stats.todaySeconds += elapsed
            session.lastFlushAt = now
        }
    }

    /** 破坏方块（远古残骸）。 */
    fun onBlockBreak(player: Player, material: Material) {
        if (material == Material.ANCIENT_DEBRIS) {
            statsByUuid.computeIfAbsent(player.uniqueId) { PlayerStats() }.ancientDebris++
        }
    }

    /** 实体死亡（怪物击杀 / 屠龙 / 击杀玩家）。 */
    fun onEntityDeath(killer: Player, entityType: EntityType) {
        val stats = statsByUuid.computeIfAbsent(killer.uniqueId) { PlayerStats() }
        if (entityType == EntityType.PLAYER) {
            // 1.5.2：击杀玩家单独计数（此前玩家击杀既不计屠龙也不计怪物击杀）；
            // 自伤/自杀已在监听器过滤，不会计入
            stats.playerKills++
        } else if (entityType == EntityType.ENDER_DRAGON) {
            stats.dragonKills++
        } else if (entityType.isAlive) {
            stats.mobKills++
        }
    }

    /** 玩家死亡。 */
    fun onPlayerDeath(player: Player) {
        statsByUuid.computeIfAbsent(player.uniqueId) { PlayerStats() }.deaths++
    }

    /** 钓到鱼。 */
    fun onFishCaught(player: Player) {
        statsByUuid.computeIfAbsent(player.uniqueId) { PlayerStats() }.fishCaught++
    }

    /** 繁殖动物。 */
    fun onAnimalBred(player: Player) {
        statsByUuid.computeIfAbsent(player.uniqueId) { PlayerStats() }.animalsBred++
    }

    /** 触发袭击。 */
    fun onRaidTrigger(player: Player) {
        statsByUuid.computeIfAbsent(player.uniqueId) { PlayerStats() }.raidTriggers++
    }

    /**
     * 查询玩家统计快照（含在线会话的实时增量）。
     * 线程安全：异步线程调用时自动切回主线程读取。
     */
    fun query(uuid: UUID): PlayerStats {
        val stored = statsByUuid[uuid] ?: PlayerStats()
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
        val stats = statsByUuid.computeIfAbsent(player.uniqueId) { PlayerStats() }
        rollTodayIfNeeded(stats)
        applyStatDelta(player, session, stats)
        val elapsed = (System.currentTimeMillis() - session.lastFlushAt).coerceAtLeast(0L) / 1000L
        stats.playSeconds += elapsed
        stats.todaySeconds += elapsed
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

    /** 从磁盘装载数据（阻塞，启用时调用一次）。 */
    private fun loadFromDisk() {
        try {
            val file = dataFile()
            if (!file.isFile) return
            val startedAt = System.currentTimeMillis()
            val config = YamlConfiguration.loadConfiguration(file)
            val section = config.getConfigurationSection("players") ?: return
            var loaded = 0
            for (key in section.getKeys(false)) {
                val uuid = try {
                    UUID.fromString(key)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                statsByUuid[uuid] = PlayerStats(
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
                loaded++
            }
            // 0.1.5.3：数据保存日志；0.1.5.4 起改为仅写入插件日志文件
            // （logs/qq/qq-bind-日期.log），不刷控制台
            PluginFileLog.write("[数据保存] 已从 stats.yml 装载 $loaded 名玩家的统计数据，耗时 ${System.currentTimeMillis() - startedAt}ms")
        } catch (_: Exception) {
        }
    }

    /** 异步保存。 */
    fun saveToDiskAsync() {
        try {
            plugin.submitAsync { saveToDiskBlocking() }
        } catch (_: Throwable) {
            // 插件停用阶段忽略
        }
    }

    /** 阻塞保存（异步线程或关服时调用）。 */
    @Synchronized
    fun saveToDiskBlocking() {
        val startedAt = System.currentTimeMillis()
        try {
            val file = dataFile()
            file.parentFile?.mkdirs()
            val config = YamlConfiguration()
            for ((uuid, stats) in statsByUuid) {
                val key = "players.$uuid"
                config.set("$key.play-seconds", stats.playSeconds)
                config.set("$key.mob-kills", stats.mobKills)
                config.set("$key.dragon-kills", stats.dragonKills)
                config.set("$key.player-kills", stats.playerKills)
                config.set("$key.deaths", stats.deaths)
                config.set("$key.fish-caught", stats.fishCaught)
                config.set("$key.ancient-debris", stats.ancientDebris)
                config.set("$key.raid-triggers", stats.raidTriggers)
                config.set("$key.animals-bred", stats.animalsBred)
                config.set("$key.walk-cm", stats.walkCm)
                config.set("$key.fly-cm", stats.flyCm)
                config.set("$key.damage-dealt", stats.damageDealt)
            }
            config.save(file)
            // 0.1.5.3：数据保存日志——每次保存仅记录一行汇总（玩家数 + 耗时 + 文件大小）；
            // 0.1.5.4 起仅写入插件日志文件（logs/qq/qq-bind-日期.log），不再显示在控制台，
            // 异步线程执行、无额外磁盘写，对服务器性能几乎零影响
            val sizeKb = (file.length() + 512) / 1024
            PluginFileLog.write("[数据保存] 玩家统计数据已保存：${statsByUuid.size} 名玩家，耗时 ${System.currentTimeMillis() - startedAt}ms，文件 ${sizeKb}KB")
        } catch (error: Exception) {
            PluginFileLog.errorAndKeep("[数据保存] 保存统计数据失败: ${error.message}")
        }
    }

    /** 玩家是否已有统计记录。 */
    fun hasData(uuid: UUID): Boolean = statsByUuid.containsKey(uuid)

    private fun dataFile(): File = File(plugin.configFile?.parentFile, "stats.yml")

    /** 调试用：当前记录的玩家数量。 */
    fun trackedCount(): Int = statsByUuid.size
}
