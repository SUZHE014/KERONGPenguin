package cn.kerong.penguin.spigot.render

import java.awt.Graphics2D
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 真实玻璃渲染（render.real-glass，1.7.6 测试功能）：
 * martin65536/liquid-glass-webgl（Apple 液态玻璃效果的 WebGL 移植）核心
 * 算法的 Java2D 逐像素移植——开启后卡片玻璃从「单纯背景模糊」升级为：
 *
 * 1. **折射（Refraction）**：按圆角矩形距离场（SDF）求表面法线，卡片
 *    边缘带内按法线方向偏移采样背景，产生玻璃边缘的放大 / 挤压形变
 *    （WebGL 版 lens 折射；内部平坦区法线为零、不偏移，视觉通透）；
 * 2. **色差（Chromatic Aberration）**：R / G / B 三通道按略不同的偏移量
 *    分别采样（WebGL 版 lens(chromaticAberration=true)），边缘带出现
 *    细微彩虹色散；
 * 3. **边缘高光（Specular Highlight）**：固定左上光源方向，法线朝向
 *    光源的边缘出现亮脊（WebGL 版随重力方向的高光；静态图固定光源）；
 * 4. **背光内阴影 + 内部提亮**：右下背光侧边缘轻微变暗、玻璃内部整体
 *    微亮，营造厚度感。
 *
 * ## 接入点
 *
 * [InfoCardAssets.CardBackdropContext.paintCard] 在开关闭合时改走本渲染器
 * （五张卡片全部生效，单一入口）；玻璃后的背景源仍取模糊副本
 * （render.blur-strength，模糊度 0 时直接用原图——清晰玻璃同样成立），
 * 各卡片后续的灰度叠层（title/item/subtitle-card-gray）照常叠加。
 *
 * ## 性能
 *
 * 逐像素 5 次距离场求值 + 3 通道近邻采样；整页卡片合计约 140 万像素，
 * 常见 CPU 单页增加几十毫秒（渲染池内完成，结果 60 秒缓存复用）。
 * 测试功能默认关闭，不影响默认渲染开销。
 */
object LiquidGlassPainter {

    /** 边缘折射带宽度（像素；距卡片边缘该距离内产生折射 / 高光）。 */
    private const val EDGE_BAND = 18f

    /** 折射最大位移（像素；边缘处背景采样点的最大偏移量）。 */
    private const val REFRACT_STRENGTH = 9f

    /** 色差强度（R/B 通道相对 G 的偏移偏移比例，0.06 = ±6%）。 */
    private const val CHROMATIC_DELTA = 0.06f

    /** 光源方向（单位向量，指向光源；左上偏对角——对角取值让上下/左右
     *  两条边都有可见高光，圆角处最亮，贴近液态玻璃的边脊观感）。 */
    private const val LIGHT_X = -0.65f
    private const val LIGHT_Y = -0.76f

    /** 高光锐度（幂次；8 = 沿边渐变、圆角最亮的柔和边脊）。 */
    private const val SPEC_POWER = 8f

    /** 高光亮度增益（0-255 附加白）。 */
    private const val SPEC_GAIN = 190

    /** 背光侧内阴影深度（0-255 扣减）。 */
    private const val INNER_SHADOW = 46

    /** 玻璃内部整体提亮（0-255 附加；模拟玻璃透射增亮）。 */
    private const val SHEEN = 9

    /**
     * 在卡片圆角矩形内绘制真实玻璃（替代普通「铺模糊副本」路径）。
     *
     * @param g 画布（卡片后续灰度叠层尚未绘制）
     * @param source 玻璃后的背景源（模糊副本或原图，与画布同坐标系经 placement 映射）
     * @param placement 背景铺放的 cover 裁剪窗口（画布 → 源图坐标映射用）
     * @param canvasWidth / canvasHeight 画布尺寸
     * @param x / y / w / h 卡片矩形（画布坐标，同现有 paintCard 参数）
     * @param radius 圆角半径
     */
    fun paintCard(
        g: Graphics2D,
        source: BufferedImage,
        placement: InfoCardAssets.BackgroundPlacement,
        canvasWidth: Int,
        canvasHeight: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
    ) {
        if (source.width <= 1 || source.height <= 1) return
        // 像素对齐的覆盖矩形（ceil/floor 防 1px 缝）
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val cw = (ceil(x + w).toInt() - x0).coerceAtLeast(1)
        val ch = (ceil(y + h).toInt() - y0).coerceAtLeast(1)
        if (cw <= 2 || ch <= 2) return
        val rad = radius.coerceIn(0f, minOf(cw, ch) / 2f)

        // ---------- 1. 折射源区域提取（一次 getRGB，后续全部走数组） ----------
        val scaleX = placement.sw.toFloat() / canvasWidth
        val scaleY = placement.sh.toFloat() / canvasHeight
        val srcMargin = (REFRACT_STRENGTH * 2f * maxOf(scaleX, scaleY)).toInt() + 2
        val sx0 = (x0 * scaleX + placement.sx).toInt() - srcMargin
        val sy0 = (y0 * scaleY + placement.sy).toInt() - srcMargin
        val sx1 = ((x0 + cw) * scaleX + placement.sx).toInt() + srcMargin + 1
        val sy1 = ((y0 + ch) * scaleY + placement.sy).toInt() + srcMargin + 1
        val rx0 = sx0.coerceIn(0, source.width - 1)
        val ry0 = sy0.coerceIn(0, source.height - 1)
        val rx1 = sx1.coerceIn(rx0 + 1, source.width)
        val ry1 = sy1.coerceIn(ry0 + 1, source.height)
        val regionW = rx1 - rx0
        val regionH = ry1 - ry0
        val region = source.getRGB(rx0, ry0, regionW, regionH, null, 0, regionW)

        // ---------- 2. 逐像素：SDF → 法线 → 折射采样 → 色差 → 高光 ----------
        val halfW = cw * 0.5f
        val halfH = ch * 0.5f
        val centerX = cw * 0.5f
        val centerY = ch * 0.5f
        val inner = halfW - rad
        val innerH = halfH - rad

        /** 圆角矩形 SDF（像素中心坐标系；负值在内部、正值在外部）。 */
        fun sdf(fx: Float, fy: Float): Float {
            val qx = abs(fx - centerX) - inner
            val qy = abs(fy - centerY) - innerH
            val ox = max(qx, 0f)
            val oy = max(qy, 0f)
            return min(max(qx, qy), 0f) + sqrt(ox * ox + oy * oy) - rad
        }

        val out = IntArray(cw * ch)
        for (py in 0 until ch) {
            val fy = py + 0.5f
            for (px in 0 until cw) {
                val fx = px + 0.5f
                val dist = sdf(fx, fy) // 正 = 外部，负 = 内部
                val inside = -dist // 内部到边缘的距离（>0 在内部）
                if (inside <= -0.5f) continue // 圆角外不绘制
                // 边缘 1px 抗锯齿 alpha
                val alpha = if (inside < 0.5f) ((inside + 0.5f) / 1f).coerceIn(0f, 1f) else 1f

                // SDF 数值梯度 = 外向法线方向（平坦区近似零）
                val gx = sdf(fx + 1f, fy) - sdf(fx - 1f, fy)
                val gy = sdf(fx, fy + 1f) - sdf(fx, fy - 1f)
                val glen = sqrt(gx * gx + gy * gy)

                // 边缘带衰减（越靠边折射越强，平方平滑衰减到 0）
                val band = if (inside < EDGE_BAND) (1f - inside / EDGE_BAND).coerceIn(0f, 1f) else 0f
                val bandSq = band * band

                // 画布坐标系下的折射位移（沿外向法线）
                var ox = 0f
                var oy = 0f
                var nx = 0f
                var ny = 0f
                if (glen > 1e-4f) {
                    nx = gx / glen
                    ny = gy / glen
                    ox = nx * REFRACT_STRENGTH * bandSq
                    oy = ny * REFRACT_STRENGTH * bandSq
                }

                // 画布坐标 → 源图 region 数组坐标（三通道按色差分别偏移）
                val canvasX = x0 + px + 0.5f
                val canvasY = y0 + py + 0.5f
                val baseU = (canvasX * scaleX + placement.sx) - rx0
                val baseV = (canvasY * scaleY + placement.sy) - ry0
                val uScale = REFRACT_STRENGTH * bandSq * scaleX
                val vScale = REFRACT_STRENGTH * bandSq * scaleY
                val rIdx = sampleIndex(
                    region, regionW, regionH,
                    baseU + nx * uScale * (1f + CHROMATIC_DELTA),
                    baseV + ny * vScale * (1f + CHROMATIC_DELTA),
                )
                val gIdx = sampleIndex(region, regionW, regionH, baseU + nx * uScale, baseV + ny * vScale)
                val bIdx = sampleIndex(
                    region, regionW, regionH,
                    baseU + nx * uScale * (1f - CHROMATIC_DELTA),
                    baseV + ny * vScale * (1f - CHROMATIC_DELTA),
                )
                val rBase = (region[rIdx] shr 16) and 0xFF
                val gBase = (region[gIdx] shr 8) and 0xFF
                val bBase = region[bIdx] and 0xFF

                // 光照：边缘高光（法线朝向光源）+ 背光内阴影 + 内部提亮
                var add = SHEEN
                var sub = 0
                if (glen > 1e-4f && bandSq > 0f) {
                    val diffuse = max(0f, nx * LIGHT_X + ny * LIGHT_Y)
                    val spec = diffuse.pow(SPEC_POWER)
                    add += (spec * SPEC_GAIN).toInt()
                    val back = max(0f, -(nx * LIGHT_X + ny * LIGHT_Y))
                    sub += (back.pow(SPEC_POWER) * INNER_SHADOW).toInt()
                }

                val r = (rBase + add - sub).coerceIn(0, 255)
                val gg = (gBase + add - sub).coerceIn(0, 255)
                val b = (bBase + add - sub).coerceIn(0, 255)
                val a = (alpha * 255f).toInt().coerceIn(0, 255)
                out[py * cw + px] = (a shl 24) or (r shl 16) or (gg shl 8) or b
            }
        }

        // ---------- 3. 贴回画布（裁剪到圆角矩形，与普通路径同口径） ----------
        val tile = BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB)
        tile.setRGB(0, 0, cw, ch, out, 0, cw)
        val oldClip = g.clip
        g.clip(java.awt.geom.RoundRectangle2D.Float(x, y, w, h, radius, radius))
        try {
            g.drawImage(tile, x0, y0, null)
        } finally {
            g.clip = oldClip
        }
    }

    /** 近邻采样索引（越界钳到边缘；模糊源上近邻与双线性差异不可见）。 */
    private fun sampleIndex(region: IntArray, regionW: Int, regionH: Int, u: Float, v: Float): Int {
        val ix = if (u <= 0f) 0 else if (u >= regionW - 1) regionW - 1 else u.toInt()
        val iy = if (v <= 0f) 0 else if (v >= regionH - 1) regionH - 1 else v.toInt()
        return iy * regionW + ix
    }
}
