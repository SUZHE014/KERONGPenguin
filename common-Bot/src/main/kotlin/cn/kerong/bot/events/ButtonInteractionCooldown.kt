package cn.kerong.bot.events

import cn.kerong.bot.provider.BotShared
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ 按钮互动频率限制（1.7.5.6 覆盖轮新增，A5）。
 *
 * 背景：按钮回调没有任何节流——同一用户可以连点任意快，每次点击都
 * 走一遍「回应 + 合成命令分发 / 主动消息」，群里瞬间被刷屏；高频点击
 * 还会放大平台 40034128（被动回复对同一条消息约 5 次上限）与
 * interaction 回应竞态。
 *
 * 现为**同一用户**的按钮互动加冷却：距上次放行不足配置间隔的点击直接
 * 拒绝（回应码 2 = 操作频繁，客户端弹「操作频繁」toast，不再执行）。
 *
 * - 计数键 = 群 OpenId + "|" + 点击者 OpenId（group_member_openid 按群
 *   隔离，同一人在不同群互不影响；仅内存态，重启清零）；
 * - 覆盖全部本插件按钮互动（krg:cmd: / krg:acmd: / krg:menu: 翻页 /
 *   krg:psel: / krg:unban: / krg:cluster:），两条分发路径
 *   （[InteractionFastPath] 帧层接管 + [InterActionListener] SDK 兜底）
 *   在分发前统一过本门禁；
 * - 间隔由配置 bot.button-cooldown-seconds 控制（默认 5 秒；0 = 关闭；
 *   改完 /hbkrg reload 即时生效——每次点击实时读取，无缓存）；
 * - 拿不到点击者 OpenId 的事件不冷却（无法归属到人；各流程自身会以
 *   「没有权限」拒绝，本门禁不做重复裁决）。
 */
object ButtonInteractionCooldown {

    private const val DEFAULT_SECONDS = 5

    /** 群|用户 → 上次放行时间戳（毫秒）。 */
    private val lastAdmitAt = ConcurrentHashMap<String, Long>()

    /** 当前配置的冷却秒数（实时读取；≤0 = 关闭）。 */
    fun cooldownSeconds(): Long {
        val plugin = try {
            BotShared.instance as? JavaPlugin
        } catch (_: Throwable) {
            null
        } ?: return DEFAULT_SECONDS.toLong()
        return try {
            val raw = plugin.config.getInt("bot.button-cooldown-seconds", DEFAULT_SECONDS)
            raw.toLong().coerceAtLeast(0L)
        } catch (_: Throwable) {
            DEFAULT_SECONDS.toLong()
        }
    }

    /**
     * 尝试放行一次按钮互动：间隔未满返回 false（调用方回应操作频繁，
     * 不再分发到各按钮流程）；放行即记录本次时间戳。
     *
     * compute 原子操作：同一用户连点的并发帧只有一个能刷新时间戳，
     * 其余在同一旧时间戳上判负，无竞态漏放。
     */
    fun tryAcquire(groupOpenId: String, userOpenId: String): Boolean {
        if (userOpenId.isBlank()) return true
        val seconds = cooldownSeconds()
        if (seconds <= 0L) return true
        val intervalMs = seconds * 1000L
        val key = "$groupOpenId|$userOpenId"
        var admitted = false
        lastAdmitAt.compute(key) { _, old ->
            val now = System.currentTimeMillis()
            if (old == null || now - old >= intervalMs) {
                admitted = true
                now
            } else {
                old
            }
        }
        return admitted
    }

    /** 清空冷却记录（/hbkrg reload 时随其他互动状态一并作废）。 */
    fun clearAll() = lastAdmitAt.clear()
}
