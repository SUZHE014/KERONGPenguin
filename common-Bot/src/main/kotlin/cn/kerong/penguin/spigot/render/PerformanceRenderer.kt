package cn.kerong.penguin.spigot.render

import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 服务器性能监控卡片渲染器（/查性能 图片输出，1.6.7 新增）。
 *
 * 设计来源：图片渲染界面的参考设计来自 BaiZhouziYou / 新亭泪
 * 发布在 EasyBot 资源市场的图片模板（https://xintinglei.cn/，
 * 模板合集 https://github.com/BaiizhouziYou/easybot-image-templates/）
 * ——性能卡片延续本项目整套毛玻璃深色风格（与查信息 / 查在线 /
 * 排行榜同源），本卡片为本项目的独立实现。
 *
 * 渲染样式（1.6.7.2 新增 render.performance.style，默认原版）：
 * - vanilla：原版毛玻璃深色（经典样式，与其他卡片同源）；
 * - liquid-glass：液态玻璃（参考 EasyBot 服务器性能模板的 WebGL 液态
 *   玻璃主题：更亮更通透的玻璃质感——亮色渐变卡身 + 边缘折射亮框 +
 *   顶部高光带 + 光泽进度条；Java2D 拟物实现，多层高光渐变的渲染
 *   开销略高于原版）。
 *
 * 毛玻璃深色风格（1350 宽，固定版式单页图）：
 * - 顶部标题区：英文眉题 MINECRAFT SERVER + 主标题「服务器性能监控」
 *   + 右侧「更新时间」卡；
 * - 指标卡网格（2 列 × 2 行，固定四项：TPS / MSPT / 内存 / CPU）：
 *   每卡 = 标签 + 状态胶囊 + 大数值 + 进度条（或「--」表示核心
 *   不支持读取）；1.6.7.1 起不再展示在线人数（查在线命令已有专门
 *   卡片，性能卡专注性能指标），单项显隐配置 show-* 同步移除；
 * - 背景与其他渲染共用 [InfoCardAssets.resolveBackground]
 *   （render.performance.builtin-background，默认内置底图）；
 * - 画布 PNG 写出后立即 flush 释放栅格内存（与其他渲染器一致）。
 *
 * 字体 / 配色与 [InfoCardRenderer] 同源（复用其字体缓存）。
 */
object PerformanceRenderer {

    /** 画布宽度（与在线列表卡片一致）。 */
    internal const val WIDTH = 1350

    // ---------- 布局常量 ----------

    /** 页边距。 */
    private const val MARGIN = 40

    /** 顶部标题区高度（眉题 + 主标题 + 右侧时间卡）。 */
    private const val HEADER_HEIGHT = 150

    /** 标题区与指标卡区间距。 */
    private const val HEADER_GAP = 18

    /** 单张指标卡高度。 */
    private const val CARD_HEIGHT = 250

    /** 指标卡间距。 */
    private const val CARD_GAP = 18

    /** 指标卡列数。 */
    private const val COLUMNS = 2

    /** 固定指标卡数（TPS / MSPT / 内存 / CPU，1.6.7.1 起不再有在线卡）。 */
    private const val CARD_COUNT = 4

    /** 底部留白。 */
    private const val BOTTOM_PAD = 40

    // ---------- 配色（毛玻璃深色系，与其他卡片同源） ----------

    private val COLOR_EYEBROW = Color(0x8A, 0x9B, 0xAE)
    private val COLOR_LABEL = Color(0x9F, 0xB3, 0xC8)
    private val COLOR_VALUE = Color.WHITE
    private val COLOR_SUB = Color(0x8A, 0x9B, 0xAE)

    /** 状态分级颜色：流畅 / 正常（绿）、轻度负载 / 偏高（黄）、过载 / 接近上限（红）。 */
    private val COLOR_GOOD = Color(0x3E, 0xC9, 0x6B)
    private val COLOR_WARN = Color(0xF6, 0xA6, 0x23)
    private val COLOR_BAD = Color(0xF0, 0x54, 0x4F)

    /** 渲染入参。 */
    data class PerformanceData(
        /** 服务器名称（标题区展示）。 */
        val serverName: String,
        /** 更新时间（HH:mm，调用方格式化好传入）。 */
        val updateTime: String,
        /** 性能快照（数据源）。 */
        val snapshot: cn.kerong.bot.PerformanceSnapshot,
        /** 预处理背景（尺寸可与画布不一致，渲染时拉伸铺满）；null 表示无背景。 */
        val background: BufferedImage? = null,
        /** 渲染样式（1.6.7.2 新增，默认原版；液态玻璃见 [RenderSettings.PerformanceStyle]）。 */
        val style: RenderSettings.PerformanceStyle = RenderSettings.PerformanceStyle.VANILLA,
    )

    /** 单个指标卡的绘制模型（由快照派生，固定四项）。 */
    private data class MetricCard(
        val label: String,
        val valueText: String,
        val statusText: String,
        val statusColor: Color,
        /** 进度条填充比例（0~1；null = 不画填充，如核心不支持读取）。 */
        val progress: Double?,
        /** 进度条颜色（与状态色一致）。 */
        val barColor: Color,
        /** 卡片底部副说明（如「JVM 堆内存 · 进程占用」）。 */
        val note: String,
    )

    /**
     * 计算画布高度（固定四项指标：TPS / MSPT / 内存 / CPU）。
     * 必须在取背景（尺寸相关）与渲染前调用，两者共用本结果。
     */
    fun measureHeight(): Int {
        val rows = (CARD_COUNT + COLUMNS - 1) / COLUMNS
        val gridHeight = rows * CARD_HEIGHT + (rows - 1) * CARD_GAP
        return MARGIN + HEADER_HEIGHT + HEADER_GAP + gridHeight + BOTTOM_PAD
    }

    /** 渲染性能卡片并返回 PNG 字节（单页固定版式）。 */
    fun render(data: PerformanceData): ByteArray {
        val cards = buildCards(data)
        val height = measureHeight()
        val image = BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            drawBackground(g, data.background, height)
            drawHeader(g, data.serverName, data.updateTime, data.style)
            drawMetricGrid(g, cards, data.style)
        } finally {
            g.dispose()
        }
        val output = ByteArrayOutputStream(384 * 1024)
        ImageIO.write(image, "png", output)
        // PNG 已写出，立即释放单次画布的栅格内存（缓存中的背景不受影响）
        image.flush()
        return output.toByteArray()
    }

    // ---------- 指标卡派生 ----------

    /** 由快照派生要绘制的指标卡列表（顺序即展示顺序，固定四项）。 */
    private fun buildCards(data: PerformanceData): List<MetricCard> {
        val snapshot = data.snapshot
        val cards = ArrayList<MetricCard>(CARD_COUNT)

        // TPS：满值 20；≥18.5 流畅 / 15~18.5 轻度负载 / <15 过载
        run {
            val tps = snapshot.tps
            val color = when {
                tps == null -> COLOR_SUB
                tps >= 18.5 -> COLOR_GOOD
                tps >= 15.0 -> COLOR_WARN
                else -> COLOR_BAD
            }
            cards.add(
                MetricCard(
                    label = "TPS（每秒刻数）",
                    valueText = tps?.let { String.format("%.1f", it) } ?: "--",
                    statusText = when {
                        tps == null -> "暂不可读"
                        tps >= 18.5 -> "流畅"
                        tps >= 15.0 -> "轻度负载"
                        else -> "过载"
                    },
                    statusColor = color,
                    progress = tps?.let { (it / 20.0).coerceIn(0.0, 1.0) },
                    barColor = color,
                    note = "满值 20.0 · 最近 1 分钟平均",
                )
            )
        }

        // MSPT（每刻耗时，毫秒）：<40 流畅 / 40~50 偏高 / ≥50 过载
        run {
            val mspt = snapshot.mspt
            val color = when {
                mspt == null -> COLOR_SUB
                mspt < 40.0 -> COLOR_GOOD
                mspt < 50.0 -> COLOR_WARN
                else -> COLOR_BAD
            }
            cards.add(
                MetricCard(
                    label = "MSPT（每刻耗时）",
                    valueText = mspt?.let { "${String.format("%.1f", it)} ms" } ?: "--",
                    statusText = when {
                        mspt == null -> "暂不可读"
                        mspt < 40.0 -> "流畅"
                        mspt < 50.0 -> "偏高"
                        else -> "过载"
                    },
                    statusColor = color,
                    // 每刻 100ms 即为严重掉刻，进度条以 100ms 为满
                    progress = mspt?.let { (it / 100.0).coerceIn(0.0, 1.0) },
                    barColor = color,
                    note = "每刻可用预算 50 ms · 越低越流畅",
                )
            )
        }

        // 内存：JVM 堆已用 / 上限；>90% 接近上限 / >75% 偏高 / 其余正常
        run {
            val used = snapshot.usedMemoryMb
            val max = snapshot.maxMemoryMb
            val ratio = if (max > 0) used.toDouble() / max.toDouble() else 0.0
            val color = when {
                max <= 0 -> COLOR_SUB
                ratio > 0.90 -> COLOR_BAD
                ratio > 0.75 -> COLOR_WARN
                else -> COLOR_GOOD
            }
            cards.add(
                MetricCard(
                    label = "内存占用（JVM 堆）",
                    valueText = "${formatGb(used)} GB / ${formatGb(max)} GB",
                    statusText = when {
                        max <= 0 -> "暂不可读"
                        ratio > 0.90 -> "接近上限"
                        ratio > 0.75 -> "偏高"
                        else -> "正常"
                    },
                    statusColor = color,
                    progress = ratio.coerceIn(0.0, 1.0),
                    barColor = color,
                    note = "已用 ${formatGb(used)} GB · 占 ${String.format("%.0f", ratio * 100)}%",
                )
            )
        }

        // CPU：进程级占用；>90% 过载 / >75% 偏高 / 其余正常
        run {
            val cpu = snapshot.cpuLoad
            val color = when {
                cpu == null -> COLOR_SUB
                cpu > 0.90 -> COLOR_BAD
                cpu > 0.75 -> COLOR_WARN
                else -> COLOR_GOOD
            }
            cards.add(
                MetricCard(
                    label = "CPU 占用（进程）",
                    valueText = cpu?.let { "${String.format("%.1f", it * 100)}%" } ?: "--",
                    statusText = when {
                        cpu == null -> "暂不可读"
                        cpu > 0.90 -> "过载"
                        cpu > 0.75 -> "偏高"
                        else -> "正常"
                    },
                    statusColor = color,
                    progress = cpu?.coerceIn(0.0, 1.0),
                    barColor = color,
                    note = "服务器进程占用 · 多核按总和归一",
                )
            )
        }

        return cards
    }

    /** MB 数值转 GB 文本（一位小数；小于 1GB 显示 0.x）。 */
    private fun formatGb(mb: Long): String = String.format("%.1f", mb / 1024.0)

    // ---------- 绘制 ----------

    /** 绘制背景（与在线列表同策略：任意尺寸双线性拉伸铺满；无图深色渐变）。 */
    private fun drawBackground(g: Graphics2D, background: BufferedImage?, height: Int) {
        if (background != null && background.width > 0 && background.height > 0) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(background, 0, 0, WIDTH, height, null)
        } else {
            g.color = Color(0x05, 0x05, 0x08)
            g.fillRect(0, 0, WIDTH, height)
            val gradient = java.awt.GradientPaint(
                0f, 0f, Color(0x10, 0x12, 0x18),
                0f, height.toFloat(), Color(0x05, 0x05, 0x08),
            )
            g.paint = gradient
            g.fillRect(0, 0, WIDTH, height)
        }
    }

    // ---------- 液态玻璃样式绘制（1.6.7.2 新增） ----------

    /** 与白色混合提亮（fraction 0~1）。 */
    private fun brighten(color: Color, fraction: Double): Color {
        val f = fraction.coerceIn(0.0, 1.0)
        val r = color.red + ((255 - color.red) * f).toInt()
        val gg = color.green + ((255 - color.green) * f).toInt()
        val b = color.blue + ((255 - color.blue) * f).toInt()
        return Color(r, gg, b)
    }

    /**
     * 面板底色：原版 = 平淡半透明白；液态玻璃 = 顶部亮渐变卡身 +
     * 边缘折射亮框（顶部亮→往下渐隐）+ 顶部高光带。
     */
    private fun paintPanel(
        g: Graphics2D,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        style: RenderSettings.PerformanceStyle,
        radius: Float = 18f,
    ) {
        val shape = RoundRectangle2D.Float(x, y, w, h, radius, radius)
        if (style == RenderSettings.PerformanceStyle.VANILLA) {
            g.color = Color(0xFF, 0xFF, 0xFF, 16)
            g.fill(shape)
            return
        }
        // 卡身：顶部亮→底部暗的透亮玻璃
        g.paint = java.awt.GradientPaint(x, y, Color(0xFF, 0xFF, 0xFF, 72), x, y + h, Color(0xFF, 0xFF, 0xFF, 16))
        g.fill(shape)
        // 边缘折射：亮框，顶部最亮往下渐隐
        g.paint = java.awt.GradientPaint(x, y, Color(0xFF, 0xFF, 0xFF, 175), x, y + h, Color(0xFF, 0xFF, 0xFF, 38))
        g.stroke = java.awt.BasicStroke(2f)
        g.draw(shape)
        // 顶部高光带：裁剪上部后粗描圆角，视觉即顶缘一条亮弧
        val previousClip = g.clip
        g.clip(java.awt.geom.Rectangle2D.Float(x, y, w, h * 0.30f))
        g.paint = java.awt.GradientPaint(
            x, y, Color(0xFF, 0xFF, 0xFF, 125),
            x, y + h * 0.30f, Color(0xFF, 0xFF, 0xFF, 0),
        )
        g.stroke = java.awt.BasicStroke(5f)
        g.draw(shape)
        g.clip = previousClip
    }

    /** 顶部标题区：眉题 + 主标题 + 右侧更新时间卡。 */
    private fun drawHeader(g: Graphics2D, serverName: String, updateTime: String, style: RenderSettings.PerformanceStyle) {
        val top = MARGIN

        // 英文眉题（字距拉宽）
        g.font = InfoCardRenderer.font(26f, true)
        g.color = COLOR_EYEBROW
        g.drawString("M I N E C R A F T   S E R V E R", (MARGIN + 8).toFloat(), (top + 38).toFloat())

        // 主标题（服务器名随副题展示，主标题固定）
        g.font = InfoCardRenderer.font(58f, true)
        g.color = COLOR_VALUE
        g.drawString("服务器性能监控", (MARGIN + 4).toFloat(), (top + 108).toFloat())

        // 右侧更新时间卡
        val cardWidth = 240
        val cardHeight = HEADER_HEIGHT - 16
        val cardX = WIDTH - MARGIN - cardWidth
        val cardY = top + 8
        paintPanel(
            g, cardX.toFloat(), cardY.toFloat(), cardWidth.toFloat(), cardHeight.toFloat(), style, 20f,
        )
        g.font = InfoCardRenderer.font(24f, false)
        g.color = COLOR_LABEL
        g.drawString("更新时间", (cardX + 28).toFloat(), (cardY + 42).toFloat())
        g.font = InfoCardRenderer.font(56f, true)
        g.color = COLOR_VALUE
        val timeWidth = g.getFontMetrics().stringWidth(updateTime)
        g.drawString(updateTime, (cardX + cardWidth - 28 - timeWidth).toFloat(), (cardY + 100).toFloat())

        // 服务器名（标题下方小字）
        g.font = InfoCardRenderer.font(24f, false)
        g.color = COLOR_SUB
        g.drawString(serverName, (MARGIN + 6).toFloat(), (top + 138).toFloat())
    }

    /** 指标卡网格：2 列 × N 行，每卡 = 标签 + 状态胶囊 + 大数值 + 进度条 + 副说明。 */
    private fun drawMetricGrid(g: Graphics2D, cards: List<MetricCard>, style: RenderSettings.PerformanceStyle) {
        if (cards.isEmpty()) return
        val gridTop = MARGIN + HEADER_HEIGHT + HEADER_GAP
        val contentWidth = WIDTH - 2 * MARGIN
        val cardWidth = (contentWidth - (COLUMNS - 1) * CARD_GAP) / COLUMNS
        val rows = (cards.size + COLUMNS - 1) / COLUMNS
        val gridHeight = rows * CARD_HEIGHT + (rows - 1) * CARD_GAP

        // 网格容器（模板风格：整块区域裹一层半透明深色底；液态玻璃同款玻璃面板）
        paintPanel(
            g,
            MARGIN.toFloat(), (gridTop - 14).toFloat(),
            contentWidth.toFloat(), (gridHeight + 28).toFloat(),
            style, 18f,
        )

        cards.forEachIndexed { index, card ->
            val col = index % COLUMNS
            val row = index / COLUMNS
            drawMetricCard(
                g, card,
                MARGIN + col * (cardWidth + CARD_GAP),
                gridTop + row * (CARD_HEIGHT + CARD_GAP),
                cardWidth,
                style,
            )
        }
    }

    /** 绘制单张指标卡。 */
    private fun drawMetricCard(
        g: Graphics2D,
        card: MetricCard,
        x: Int,
        y: Int,
        cardWidth: Int,
        style: RenderSettings.PerformanceStyle,
    ) {
        val liquidGlass = style == RenderSettings.PerformanceStyle.LIQUID_GLASS

        // 卡片底色（液态玻璃 = 玻璃面板）
        paintPanel(g, x.toFloat(), y.toFloat(), cardWidth.toFloat(), CARD_HEIGHT.toFloat(), style, 18f)

        // 左上标签
        g.font = InfoCardRenderer.font(24f, false)
        g.color = if (liquidGlass) COLOR_VALUE else COLOR_LABEL
        g.drawString(card.label, (x + 32).toFloat(), (y + 52).toFloat())

        // 右上状态胶囊（液态玻璃更透亮并带亮边）
        g.font = InfoCardRenderer.font(22f, true)
        val statusWidth = g.getFontMetrics().stringWidth(card.statusText)
        val pillWidth = statusWidth + 40
        val pillX = x + cardWidth - 32 - pillWidth
        val pillColor = Color(
            (card.statusColor.red.coerceAtLeast(60)),
            (card.statusColor.green.coerceAtLeast(60)),
            (card.statusColor.blue.coerceAtLeast(60)),
            if (liquidGlass) 150 else 110,
        )
        val pillShape = RoundRectangle2D.Float(pillX.toFloat(), (y + 22).toFloat(), pillWidth.toFloat(), 40f, 20f, 20f)
        g.color = pillColor
        g.fill(pillShape)
        if (liquidGlass) {
            g.paint = java.awt.GradientPaint(
                pillX.toFloat(), (y + 22).toFloat(), Color(0xFF, 0xFF, 0xFF, 95),
                pillX.toFloat(), (y + 62).toFloat(), Color(0xFF, 0xFF, 0xFF, 10),
            )
            g.fill(pillShape)
            g.color = Color(0xFF, 0xFF, 0xFF, 90)
            g.stroke = java.awt.BasicStroke(1.5f)
            g.draw(pillShape)
        }
        g.color = if (liquidGlass) brighten(card.statusColor, 0.25) else card.statusColor
        g.drawString(card.statusText, (pillX + 20).toFloat(), (y + 50).toFloat())

        // 中部大数值（带状态色：不可读时灰色，其余用状态色渐变到白太花哨——数值用白，仅状态区带色）
        g.font = InfoCardRenderer.font(64f, true)
        g.color = if (card.valueText == "--") COLOR_SUB else COLOR_VALUE
        g.drawString(card.valueText, (x + 32).toFloat(), (y + 148).toFloat())

        // 进度条（底部；液态玻璃 = 光泽渐变填充 + 顶部亮线）
        val barX = x + 32
        val barY = y + 178
        val barWidth = cardWidth - 64
        val barHeight = 12
        g.color = Color(0xFF, 0xFF, 0xFF, if (liquidGlass) 44 else 30)
        g.fill(RoundRectangle2D.Float(barX.toFloat(), barY.toFloat(), barWidth.toFloat(), barHeight.toFloat(), 6f, 6f))
        if (card.progress != null && card.progress > 0.0) {
            val fillWidth = (barWidth * card.progress).toFloat().coerceAtMost(barWidth.toFloat())
            if (liquidGlass) {
                val left = brighten(card.barColor, 0.45)
                g.paint = java.awt.GradientPaint(
                    barX.toFloat(), barY.toFloat(), left,
                    (barX + fillWidth).coerceAtLeast((barX + 1).toFloat()), barY.toFloat(), card.barColor,
                )
            } else {
                g.color = card.barColor
            }
            g.fill(RoundRectangle2D.Float(
                barX.toFloat(), barY.toFloat(),
                fillWidth.coerceAtLeast(6f), barHeight.toFloat(), 6f, 6f))
            if (liquidGlass) {
                // 进度条顶部光泽线（玻璃反光）
                g.paint = java.awt.GradientPaint(
                    barX.toFloat(), barY.toFloat(), Color(0xFF, 0xFF, 0xFF, 105),
                    barX.toFloat(), (barY + barHeight / 2).toFloat(), Color(0xFF, 0xFF, 0xFF, 0),
                )
                g.fill(RoundRectangle2D.Float(
                    barX.toFloat(), barY.toFloat(),
                    fillWidth.coerceAtLeast(6f), (barHeight / 2).toFloat(), 3f, 3f))
            }
        }

        // 底部副说明
        g.font = InfoCardRenderer.font(22f, false)
        g.color = COLOR_SUB
        g.drawString(card.note, (barX).toFloat(), (y + 228).toFloat())
    }
}
