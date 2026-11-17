package cn.kerong.penguin.spigot.events

import org.bukkit.entity.Entity
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent

/**
 * 把 Bukkit 死亡事件转成中文播报文案（供死亡播报用）。
 *
 * 原版 [PlayerDeathEvent.getDeathMessage] 取自服务端语言包，默认是英文，
 * 直接转发到 QQ 群可读性很差，因此这里按击杀者与死亡原因自行拼装中文描述。
 *
 * 1.7.6.4 首发轮两项增强（用户口径「修复死亡播报没有玩家昵称的问题；
 * 尝试获取更多的死亡原因，能获取一些模组/插件自定义的更好」）：
 * - **玩家昵称**：本类产出的描述仍不含死者名，由
 *   `formatPlayerDeathMessage` 的 {name} 占位符输出（见 ConfigProvider）；
 * - **插件自定义死亡文案优先**：其他插件用 `setDeathMessage` 改写过
 *   死亡消息（MythicMobs / PvP 插件等的自定义文案）时原样采用——
 *   原版英文消息必然「以死者名开头 + 命中已知原版句式」，两者都不
 *   满足即视为插件自定义文案（[customMessageOf]）；
 * - **死亡原因表扩充**：补齐 CONTACT / FREEZE / CRAMMING / DRYOUT /
 *   DRAGON_BREATH / SONIC_BOOM / KILL / SUICIDE / PROJECTILE 等缺失分支。
 *
 * 1.7.6.4 覆盖轮（用户口径「死亡播报添加一个配置开关（默认开），当关闭
 * 时，死亡原因只展示插件内置的原版死亡原因，其他死亡原因统一显示
 * “死了”」）：[describe] 增加 customCauses 参数（player-events.death.
 * custom-causes，默认开）——关闭时跳过插件自定义文案优先分支，只走
 * 内置原版死法表（表外分支回退「死了」，击杀者拼名分支属内置口径保留）。
 *
 * 参考实现（鸣谢）：HuHoBot 的 DeathMessage.kt
 * （玩家事件通知 / 死亡播报占位符口径），
 * 源码 https://github.com/HuHoBot/PenguinAgent （server/Spigot events/DeathMessage.kt，
 * 本文件与其逐分支对齐：击杀者判定 / 环境死亡中文文案 / {message} 不含死者名）。
 */
object DeathMessage {

    /**
     * 击杀者名称：玩家击杀取玩家名，投射物取发射者。
     *
     * @return 环境死亡（摔落、岩浆等）时返回 null
     */
    fun killerOf(event: PlayerDeathEvent): String? {
        val victim = event.entity.name
        val killer = event.entity.killer
        if (killer != null && !killer.name.equals(victim, ignoreCase = true)) {
            return killer.name
        }
        val damager = (event.entity.lastDamageCause as? EntityDamageByEntityEvent)?.damager
        val sourceName = damager?.let { sourceName(it) }
        return sourceName?.takeIf { it.isNotBlank() && !it.equals(victim, ignoreCase = true) }
    }

    /**
     * 中文死亡描述，**不含死者名**，由播报出口的 {name} 占位符单独输出。
     *
     * 例如「被苦力怕杀死了」「从高处摔落而死」；其他插件改写过死亡
     * 消息时直接采用其完整文案（开头通常带死者名，出口剥掉不重复拼）。
     *
     * @param customCauses 自定义死亡原因开关（player-events.death.custom-causes，
     *   默认开）：关闭时跳过插件自定义文案优先分支，只展示内置原版死法表，
     *   其余统一「死了」（1.7.6.4 覆盖轮）
     */
    @JvmOverloads
    fun describe(event: PlayerDeathEvent, customCauses: Boolean = true): String {
        // 其他插件的自定义死亡文案优先于内置中文描述（开关关闭时跳过）
        if (customCauses) customMessageOf(event)?.let { return it }

        val killer = killerOf(event)
        if (killer != null) return "被${killer}杀死了"

        return when (event.entity.lastDamageCause?.cause) {
            EntityDamageEvent.DamageCause.FALL -> "从高处摔落而死"
            EntityDamageEvent.DamageCause.LAVA -> "掉进了岩浆"
            EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.MELTING -> "被火烧死了"
            EntityDamageEvent.DamageCause.HOT_FLOOR -> "被岩浆烫死了"
            EntityDamageEvent.DamageCause.DROWNING -> "溺水而死了"
            EntityDamageEvent.DamageCause.SUFFOCATION -> "窒息而死了"
            EntityDamageEvent.DamageCause.VOID -> "掉进了虚空"
            EntityDamageEvent.DamageCause.LIGHTNING -> "被闪电劈死了"
            EntityDamageEvent.DamageCause.BLOCK_EXPLOSION,
            EntityDamageEvent.DamageCause.ENTITY_EXPLOSION -> "被炸死了"
            EntityDamageEvent.DamageCause.POISON,
            EntityDamageEvent.DamageCause.WITHER,
            EntityDamageEvent.DamageCause.MAGIC -> "中毒而死了"
            EntityDamageEvent.DamageCause.STARVATION -> "饿死了"
            EntityDamageEvent.DamageCause.FALLING_BLOCK -> "被掉落的方块砸死了"
            EntityDamageEvent.DamageCause.THORNS -> "被荆棘刺死了"
            EntityDamageEvent.DamageCause.FLY_INTO_WALL -> "撞墙死了"
            // 1.7.6.4 补齐的分支
            EntityDamageEvent.DamageCause.CONTACT -> "被仙人掌扎死了"
            EntityDamageEvent.DamageCause.FREEZE -> "被冻死了"
            EntityDamageEvent.DamageCause.CRAMMING -> "被挤死了"
            EntityDamageEvent.DamageCause.DRYOUT -> "脱水而死了"
            EntityDamageEvent.DamageCause.DRAGON_BREATH -> "被龙的吐息喷死了"
            EntityDamageEvent.DamageCause.SONIC_BOOM -> "被音波震死了"
            EntityDamageEvent.DamageCause.KILL -> "被命令杀死了"
            EntityDamageEvent.DamageCause.SUICIDE -> "自杀了"
            EntityDamageEvent.DamageCause.PROJECTILE -> "被射死了"
            EntityDamageEvent.DamageCause.ENTITY_ATTACK,
            EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK -> "被打死了"
            else -> "死了"
        }
    }

    /**
     * 其他插件的自定义死亡文案（1.7.6.4）：
     * [PlayerDeathEvent.getDeathMessage] 被插件改写为非原版形态时原样
     * 返回——原版英文消息恒为「死者名 + 已知原版句式」（en_us 语言包），
     * 两者任一不满足 = 插件自己拼的文案，直接采用（不翻译、不改写）。
     */
    private fun customMessageOf(event: PlayerDeathEvent): String? {
        val message = try {
            event.deathMessage
        } catch (_: Throwable) {
            null
        } ?: return null
        if (message.isBlank()) return null
        if (looksVanilla(message, event.entity.name)) return null
        return message
    }

    /**
     * 是否原版英文死亡消息（死者名开头 + 命中原版句式，或恰为
     * 「<名> died」的 generic 形态）。命中 → 走内置中文分支；
     * 不命中（如「Steve 在决斗中被 Alex 击败」这类插件文案）→ 自定义。
     */
    private fun looksVanilla(message: String, victimName: String): Boolean {
        val text = message.trim()
        val lower = text.lowercase()
        if (!text.startsWith(victimName, ignoreCase = true)) return false
        if (text.equals("$victimName died", ignoreCase = true)) return true
        return VANILLA_DEATH_PHRASES.any { it in lower }
    }

    /** 原版英文死亡消息句式片段（en_us death.attack.* / death.fell.* 常用集合，小写比对）。 */
    private val VANILLA_DEATH_PHRASES = listOf(
        "was slain by", "was shot by", "was pricked to death", "was squashed by",
        "was skewered by", "was poked to death", "was stung to death", "was fireballed by",
        "was killed by", "was blown up by", "blew up", "drowned", "died from dehydration",
        "didn't want to live in the same world as", "withered away", "starved to death",
        "was squished by", "was pummeled by", "froze to death", "was frozen to death",
        "was struck by lightning", "experienced kinetic energy", "hit the ground too hard",
        "fell from a high place", "fell while climbing", "was doomed to fall",
        "was impaled on", "burned to death", "burnt to a crisp",
        "went up in flames", "tried to swim in lava", "discovered the floor was lava",
        "suffocated in a wall", "fell out of the world", "was slain by magic",
        "was killed by magic",
    )

    private fun sourceName(damager: Entity): String? = when (damager) {
        is Projectile -> (damager.shooter as? Entity)?.name
        else -> damager.name
    }
}
