package cn.kerong.penguin.neoforge

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent

/**
 * 把 NeoForge 死亡事件转成中文播报文案（1.7.4 覆盖版新增，供死亡播报用）。
 *
 * 文案口径与 Spigot 版 [cn.kerong.penguin.spigot.events.DeathMessage] 一致，
 * 两者共同参考 HuHoBot 的死亡播报实现（占位符与中文描述逐分支对齐）：
 * 文档 http://docs.huhobot.dpdns.org/ 、源码 https://github.com/HuHoBot/PenguinAgent 。
 *
 * 差异说明：原版死亡消息（DamageSource.getLocalizedDeathMessage）取自语言包
 * 默认英文，直接转发可读性差；这里按伤害类型 msgId（death.attack.* 的键尾）
 * 自行拼装中文描述。
 */
object DeathMessageNeo {

    /** 击杀者名称：直接击杀者（玩家优先）；投射物取其主人。环境死亡返回 null。 */
    fun killerOf(event: LivingDeathEvent): String? {
        val victim = event.entity
        val direct = event.source.directEntity
        val attacker = event.source.entity
        val killerEntity = when {
            attacker != null && attacker !== victim -> attacker
            direct != null && direct !== victim -> direct
            else -> return null
        }
        return nameOf(killerEntity)
    }

    /** 中文死亡描述（不含死者名）。 */
    fun describe(event: LivingDeathEvent): String {
        val killer = killerOf(event)
        if (killer != null) return "被${killer}杀死了"
        return describeByMsgId(msgIdOf(event.source))
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

    /** 按伤害类型键尾拼中文描述（与 HuHoBot / Spigot 版分支一致）。 */
    private fun describeByMsgId(msgId: String): String = when (msgId) {
        "fall" -> "从高处摔落而死"
        "lava" -> "掉进了岩浆"
        "inFire", "onFire", "hotFloor" -> if (msgId == "hotFloor") "被岩浆烫死了" else "被火烧死了"
        "drown" -> "溺水而死了"
        "inWall" -> "窒息而死了"
        "outOfWorld" -> "掉进了虚空"
        "lightningBolt" -> "被闪电劈死了"
        "explosion", "explosion.player", "fireworks" -> "被炸死了"
        "magic", "poison", "wither" -> "中毒而死了"
        "starve" -> "饿死了"
        "fallingBlock" -> "被掉落的方块砸死了"
        "thorns" -> "被荆棘刺死了"
        "flyIntoWall" -> "撞墙死了"
        "cactus" -> "被仙人掌扎死了"
        "freeze" -> "被冻死了"
        else -> "死了"
    }
}
