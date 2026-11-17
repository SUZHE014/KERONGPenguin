package cn.kerong.penguin.fabric

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity

/**
 * 把 Fabric 死亡事件（ServerLivingEntityEvents.ALLOW_DEATH）转成中文播报文案
 * （1.7.4 覆盖版新增，供死亡播报用）。
 *
 * 文案口径与 Spigot 版 [cn.kerong.penguin.spigot.events.DeathMessage] /
 * NeoForge 版 DeathMessageNeo 一致（两者共同参考 HuHoBot 的死亡播报实现，
 * 占位符与中文描述逐分支对齐）。
 *
 * 差异说明：原版死亡消息（DamageSource.getLocalizedDeathMessage）取自语言包
 * 默认英文，直接转发可读性差；这里按伤害类型 msgId（death.attack.* 的键尾）
 * 自行拼装中文描述。
 *
 * 1.7.6.4 首发轮两项增强（与 Spigot / NeoForge 同步，用户口径「修复死亡播报
 * 没有玩家昵称的问题；尝试获取更多的死亡原因，能获取一些模组/插件
 * 自定义的更好」）：
 * - **玩家昵称**：描述仍不含死者名，由 `formatPlayerDeathMessage` 的
 *   {name} 占位符输出（见 ConfigProvider）；
 * - **模组自定义死亡原因**：msgId 不在中文表内时回退服务端解析的
 *   getLocalizedDeathMessage 文本（英文，开头带死者名，出口剥除不重复拼；
 *   intermediary 名 method_5506 在 1.20.1 / 1.20.4 两张 tiny 表一致，
 *   重映射安全）；
 * - **死亡原因表扩充**：与 NeoForge 版同步补齐 14 个分支。
 *
 * 1.7.6.4 覆盖轮（用户口径「死亡播报添加一个配置开关（默认开），当关闭
 * 时，死亡原因只展示插件内置的原版死亡原因，其他死亡原因统一显示
 * “死了”」）：[describe] 增加 customCauses 参数（player-events.death.
 * custom-causes，默认开）——关闭时跳过模组文案回退，只用内置原版死法
 * 表，表外统一「死了」（击杀者拼名分支属内置口径保留）。
 */
object DeathMessageFabric {

    /** 击杀者名称：直接击杀者（玩家优先）；投射物取其主人。环境死亡返回 null。 */
    fun killerOf(entity: LivingEntity, source: DamageSource): String? {
        val victim = entity
        val direct = source.directEntity
        val attacker = source.entity
        val killerEntity = when {
            attacker != null && attacker !== victim -> attacker
            direct != null && direct !== victim -> direct
            else -> return null
        }
        return nameOf(killerEntity)
    }

    /**
     * 中文死亡描述（不含死者名，出口 {name} 单独输出）。
     *
     * @param customCauses 自定义死亡原因开关（player-events.death.custom-causes，
     *   默认开）：关闭时跳过模组文案回退，只用内置原版死法表，表外
     *   统一「死了」（1.7.6.4 覆盖轮）
     */
    @JvmOverloads
    fun describe(entity: LivingEntity, source: DamageSource, customCauses: Boolean = true): String {
        val killer = killerOf(entity, source)
        if (killer != null) return "被${killer}杀死了"
        val msgId = msgIdOf(source)
        describeByMsgId(msgId)?.let { return it }
        // 模组自定义伤害类型：回退服务端解析的死亡消息（英文，开头带死者名；
        // customCauses 关闭时跳过——统一「死了」）
        if (customCauses) {
            localizedFallback(source, entity)?.let { return it }
        }
        return "死了"
    }

    /** 伤害类型键尾（death.attack.&lt;msgId&gt;），取不到返回空串。 */
    private fun msgIdOf(source: DamageSource): String = try {
        source.msgId.orEmpty()
    } catch (_: Throwable) {
        ""
    }

    /** 实体显示名（玩家取游戏名；生物取名称字符串，取不到返回 null）。 */
    private fun nameOf(entity: Entity): String? = try {
        when (entity) {
            is ServerPlayer -> entity.gameProfile.name
            else -> entity.name?.string?.takeIf { it.isNotBlank() }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 服务端解析的原版 / 模组死亡消息回退（1.7.6.4，与 DeathMessageNeo
     * 同口径）：解析失败（原始翻译键 / 占位符形态）或异常时返回 null。
     */
    private fun localizedFallback(source: DamageSource, victim: LivingEntity): String? {
        return try {
            val text = source.getLocalizedDeathMessage(victim).string.trim()
            if (text.isEmpty() || looksLikeTranslationKey(text)) null else text
        } catch (_: Throwable) {
            null
        }
    }

    /** 解析失败形态：原始翻译键（death.attack.xxx / %s 占位）或明显非正文。 */
    private fun looksLikeTranslationKey(text: String): Boolean {
        if (text.startsWith("death.") || text.startsWith("death ")) return true
        if ("%1\$s" in text || "%s" in text) return true
        if (text.startsWith("translate.") || text.startsWith("chat.")) return true
        return false
    }

    /**
     * 按伤害类型键尾拼中文描述（与 HuHoBot / Spigot / NeoForge 版分支一致；
     * 1.7.6.4 扩充：返回 null = 未知类型，交给上层模组文案回退）。
     */
    private fun describeByMsgId(msgId: String): String? = when (msgId) {
        "fall" -> "从高处摔落而死"
        "lava" -> "掉进了岩浆"
        "inFire", "onFire", "hotFloor" -> if (msgId == "hotFloor") "被岩浆烫死了" else "被火烧死了"
        "drown" -> "溺水而死了"
        "inWall" -> "窒息而死了"
        "outOfWorld" -> "掉进了虚空"
        "lightningBolt" -> "被闪电劈死了"
        "explosion", "explosion.player", "fireworks" -> "被炸死了"
        "magic", "indirectMagic", "poison", "wither" -> "中毒而死了"
        "starve" -> "饿死了"
        "fallingBlock" -> "被掉落的方块砸死了"
        "thorns" -> "被荆棘刺死了"
        "flyIntoWall" -> "撞墙死了"
        "cactus", "sweetBerryBush" -> if (msgId == "cactus") "被仙人掌扎死了" else "被甜浆果丛扎死了"
        "freeze" -> "被冻死了"
        // 1.7.6.4 补齐的原版分支（与 NeoForge 版同步）
        "dragonBreath" -> "被龙的吐息喷死了"
        "dryout" -> "脱水而死了"
        "sonicBoom" -> "被音波震死了"
        "genericKill" -> "被命令杀死了"
        "anvil" -> "被掉落的铁砧砸死了"
        "fallingStalactite" -> "被掉落的钟乳石砸死了"
        "stalagmite" -> "摔在了石笋上"
        "sting" -> "被蜇死了"
        "fireball" -> "被火球砸死了"
        "cramming" -> "被挤死了"
        "trident" -> "被三叉戟刺死了"
        "arrow" -> "被箭射死了"
        else -> null
    }
}
