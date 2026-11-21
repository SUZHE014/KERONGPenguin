package cn.kerong.penguin.spigot.qqbind

import java.util.concurrent.ThreadLocalRandom

/**
 * 签到金币奖励配置（1.7.6.7）：固定数额或随机区间，双写法兼容。
 *
 * 配置 `qq-bind.checkin.reward` 支持两种写法：
 * - 固定数额：`reward: 100.0`（数字或纯数字字符串，与旧版完全一致）；
 * - 随机区间：`reward: 0~100`（字符串「最小~最大」，每次签到在区间内
 *   随机发放，含两端——如 0~100 表示本次可能抽到 0 到 100 之间任意整数）。
 *
 * 解析规则（[parse]，纯函数）：
 * - YAML 数字 / 纯数字字符串 → [Fixed]；
 * - 含「~」且两端都能解析为数字的字符串 → [Range]（两端点自动排序，
 *   负数下限按 0 处理）；
 * - 写错的值（如 `50~` / `a~b`）与缺键 → 回退默认 [DEFAULT_FIXED]
 *   并记录警告日志（跟随旧版 getDouble 的 default 语义，签到不会被卡死）；
 * - [Fixed]（0）/ [Range]（上限 0）视为金币奖励关闭（签到照常累计，
 *   不发放金币——与旧版 reward: 0 行为一致）。
 *
 * 随机精度：两端都是整数值时按整数随机（0~100 抽出的是 0、1、2…100，
 * 不会出现 87.34）；任一端是小数时按连续小数随机（保留两位）。
 */
sealed class CheckinRewardSpec {

    /** 随机上界 / 固定值（「金币奖励是否启用」判定用：> 0 = 启用）。 */
    abstract val maxAmount: Double

    /** 配置展示文本（「100」/「100.5」/「0~100」，用于日志与 API 查询）。 */
    abstract fun displayText(): String

    /** 本次签到实际发放数额（[Fixed] 恒定；[Range] 每次调用重新随机）。 */
    abstract fun roll(): Double

    /** 固定数额（旧版唯一形态）。 */
    data class Fixed(val amount: Double) : CheckinRewardSpec() {
        override val maxAmount: Double get() = amount
        override fun displayText(): String = formatValue(amount)
        override fun roll(): Double = amount
    }

    /** 随机区间（每次签到在 [min, max] 内随机，含两端）。 */
    data class Range(val min: Double, val max: Double) : CheckinRewardSpec() {
        override val maxAmount: Double get() = max
        override fun displayText(): String = "${formatValue(min)}~${formatValue(max)}"
        override fun roll(): Double {
            if (max <= min) return min
            // 两端都是整数值时按整数随机（签到场景最常见，0~100 抽整数）
            val integerMode = min == min.toLong().toDouble() && max == max.toLong().toDouble()
            return if (integerMode) {
                ThreadLocalRandom.current().nextLong(min.toLong(), max.toLong() + 1).toDouble()
            } else {
                // 连续小数随机，四舍五入到两位（避免余额里出现长尾小数）
                val raw = min + (max - min) * ThreadLocalRandom.current().nextDouble()
                Math.round(raw * 100.0) / 100.0
            }
        }
    }

    companion object {

        /** 缺键 / 写错时的默认固定数额（与旧版 getDouble default 100.0 一致）。 */
        const val DEFAULT_FIXED = 100.0

        /**
         * 解析配置原始值（YAML 已转成 Java 对象：Number / String / null）。
         *
         * 解析失败时记录一条文件日志（提示写法）并回退 [DEFAULT_FIXED]——
         * 不抛异常、不返回 null，签到链路永远拿到可用配置。
         */
        @JvmStatic
        fun parse(raw: Any?): CheckinRewardSpec {
            val spec = parseQuietly(raw)
            if (spec == null) {
                val shown = raw?.toString()?.take(40) ?: "null"
                QqBindManager.logQuiet(
                    "[签到] 金币奖励配置 qq-bind.checkin.reward 无法解析（值: $shown），" +
                        "已按默认 ${formatValue(DEFAULT_FIXED)} 处理；支持固定数额（如 100.0）或随机区间（如 0~100）两种写法"
                )
                return Fixed(DEFAULT_FIXED)
            }
            return spec
        }

        /**
         * 纯解析内核（不写日志；供冒烟测试直接调用）。
         * 返回 null = 无法识别（调用方决定回退策略）。
         */
        @JvmStatic
        fun parseQuietly(raw: Any?): CheckinRewardSpec? {
            return when (raw) {
                null -> Fixed(DEFAULT_FIXED)
                is Number -> Fixed(raw.toDouble())
                is String -> parseString(raw.trim())
                else -> null
            }
        }

        /** 字符串形态：纯数字 → 固定；「最小~最大」→ 区间。 */
        private fun parseString(text: String): CheckinRewardSpec? {
            if (text.isEmpty()) return Fixed(DEFAULT_FIXED)
            // 纯数字字符串（如 "100"）按固定数额处理
            text.toDoubleOrNull()?.let { return Fixed(it) }
            // 随机区间：单段波浪号分隔（只认第一个 ~，其余视为写错）
            if (text.contains('~')) {
                val parts = text.split('~')
                if (parts.size == 2) {
                    val left = parts[0].trim().toDoubleOrNull()
                    val right = parts[1].trim().toDoubleOrNull()
                    if (left != null && right != null) {
                        val lower = minOf(left, right).coerceAtLeast(0.0)
                        val upper = maxOf(left, right).coerceAtLeast(0.0)
                        return Range(lower, upper)
                    }
                }
            }
            return null
        }

        /** 数值显示（整数去掉小数点）。 */
        internal fun formatValue(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }
}
