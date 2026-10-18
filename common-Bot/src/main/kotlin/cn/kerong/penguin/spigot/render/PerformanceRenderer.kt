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
 * ——性能卡片延续本项目整套深色卡片风格基调（与查信息 / 查在线 /
 * 排行榜同源），本卡片为本项目的独立实现。
 *
 * 1.6.7.2 覆盖版：移除液态玻璃样式分支（render.performance.style 配置
 * 同步废弃，见 ConfigManager.REMOVED_CONFIG_KEYS），固定原版毛玻璃深色
 * 样式；新增全局透明度（render.global-opacity，编码前经
 * [GlobalOpacity.apply] 统一应用）。
 *
 * 1.6.7.5 同日覆盖版：面板透明度与其他卡片**完全统一**（用户反馈：
 * 实色不透明面板与查信息 / 查在线 / 排行榜的观感不一致）——头部卡 /
 * 网格容器 / 指标卡 / 状态胶囊 / 进度槽全部改回半透明体系，alpha 取值
 * 与其他卡片逐一相同（头部卡白 34 / 容器黑 120 / 指标卡白 16 / 胶囊白 30，
 * 无面板描边）；整卡透明度由全局 render.global-opacity 统一控制（与
 * 其他卡片同一份配置，[GlobalOpacity.apply] 编码前应用，见
 * [RenderSettings.globalOpacity]）；字体 / 布局 / 指标口径不变。
 *
 * 半透明卡片风格（1.6.7.5 覆盖版，1350 宽，固定版式单页图）：
 * - 顶部标题区：英文眉题 MINECRAFT SERVER + 主标题「服务器性能监控」
 *   + 右侧「更新时间」卡；
 * - 指标卡网格（2 列 × 3 行，固定六项：TPS / MSPT / 内存 / CPU / 延迟 / 在线人数）：
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

    /** 固定指标卡数（TPS / MSPT / 内存 / CPU / 延迟 / 在线人数，1.6.7.6 起六项）。 */
    private const val CARD_COUNT = 6

    /** 底部留白。 */
    private const val BOTTOM_PAD = 40

    // ---------- 配色（1.6.7.5 覆盖版：半透明面板，alpha 与其他卡片逐一相同） ----------

    private val COLOR_EYEBROW = Color(0x8A, 0x9B, 0xAE)
    private val COLOR_LABEL = Color(0x9F, 0xB3, 0xC8)
    private val COLOR_VALUE = Color.WHITE
    private val COLOR_SUB = Color(0x8A, 0x9B, 0xAE)

    /** 头部「更新时间」卡底色（半透明白，与查在线/排行榜头部卡同款 alpha 34）。 */
    private val COLOR_HEADER_CARD = Color(0xFF, 0xFF, 0xFF, 34)

    /** 指标卡底色（半透明白，与查信息/查在线/排行榜单元格同款 alpha 16）。 */
    private val COLOR_CARD = Color(0xFF, 0xFF, 0xFF, 16)

    /** 网格容器底色（半透明黑，与查在线/排行榜网格容器同款 alpha 120）。 */
    private val COLOR_CONTAINER = Color(0x00, 0x00, 0x00, 120)

    /** 状态胶囊底色（半透明白，与其他卡片徽标同款 alpha 30；语义色由文字承担）。 */
    private val COLOR_PILL_BG = Color(0xFF, 0xFF, 0xFF, 30)

    /** 进度条轨道底色（半透明白，与其他卡片胶囊同档 alpha 30；填充用状态色）。 */
    private val COLOR_BAR_TRACK = Color(0xFF, 0xFF, 0xFF, 30)

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
    )

    /** 单个指标卡的绘制模型（由快照派生，固定六项）。 */
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
     * 计算画布高度（固定六项指标：TPS / MSPT / 内存 / CPU / 延迟 / 在线人数）。
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
            drawHeader(g, data.serverName, data.updateTime)
            drawMetricGrid(g, cards)
        } finally {
            g.dispose()
        }
        val output = ByteArrayOutputStream(384 * 1024)
        // 全局透明度（render.global-opacity，默认 1.0 原样零开销）
        ImageIO.write(GlobalOpacity.apply(image), "png", output)
        // PNG 已写出，立即释放单次画布的栅格内存（缓存中的背景不受影响）
        image.flush()
        return output.toByteArray()
    }

    // ---------- 指标卡派生 ----------

    /** 由快照派生要绘制的指标卡列表（顺序即展示顺序，固定六项）。 */
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

        // 延迟（在线玩家平均 Ping，毫秒；1.6.7.6 新增）：
        // <80 流畅 / 80~150 偏高 / >=150 高；无人在线为 null（显示「--」）
        run {
            val ping = snapshot.avgPingMillis
            val color = when {
                ping == null -> COLOR_SUB
                ping < 80.0 -> COLOR_GOOD
                ping < 150.0 -> COLOR_WARN
                else -> COLOR_BAD
            }
            cards.add(
                MetricCard(
                    label = "延迟（玩家平均）",
                    valueText = ping?.let { "${String.format("%.0f", it)} ms" } ?: "--",
                    statusText = when {
                        ping == null -> "暂无玩家"
                        ping < 80.0 -> "流畅"
                        ping < 150.0 -> "偏高"
                        else -> "高"
                    },
                    statusColor = color,
                    // 进度条以 300 ms 为满（超过即视为严重延迟）
                    progress = ping?.let { (it / 300.0).coerceIn(0.0, 1.0) },
                    barColor = color,
                    note = "全部在线玩家网络延迟平均 · 毫秒",
                )
            )
        }

        // 在线玩家（仅人数展示，1.6.7.6 新增：名单详情用 /查在线 命令）
        run {
            val online = snapshot.onlinePlayers
            val color = when {
                online == null -> COLOR_SUB
                online > 0 -> COLOR_GOOD
                else -> COLOR_SUB
            }
            cards.add(
                MetricCard(
                    label = "在线玩家（人数）",
                    valueText = online?.toString() ?: "--",
                    statusText = when {
                        online == null -> "暂不可读"
                        online > 0 -> "在线中"
                        else -> "空服"
                    },
                    statusColor = color,
                    // 人数卡不画进度条（无满值语义）
                    progress = null,
                    barColor = color,
                    note = "仅展示人数 · 玩家名单用 /查在线 查看",
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

    // ---------- 面板绘制 ----------

    /**
     * 面板底色（1.6.7.5 覆盖版：半透明圆角面板，无描边，与其他卡片同源）。
     *
     * @param fill 面板半透明底色（背景经 alpha 混合透出，与其他卡片一致）
     */
    private fun paintPanel(
        g: Graphics2D,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        fill: Color = COLOR_CARD,
        radius: Float = 18f,
    ) {
        g.color = fill
        g.fill(RoundRectangle2D.Float(x, y, w, h, radius, radius))
    }

    /** 顶部标题区：眉题 + 主标题 + 右侧更新时间卡。 */
    private fun drawHeader(g: Graphics2D, serverName: String, updateTime: String) {
        val top = MARGIN

        // 英文眉题（字距拉宽）
        g.font = InfoCardRenderer.font(26f, true)
        g.color = COLOR_EYEBROW
        g.drawString("M I N E C R A F T   S E R V E R", (MARGIN + 8).toFloat(), (top + 38).toFloat())

        // 主标题（服务器名随副题展示，主标题固定）
        g.font = InfoCardRenderer.font(58f, true)
        g.color = COLOR_VALUE
        g.drawString("服务器性能监控", (MARGIN + 4).toFloat(), (top + 108).toFloat())

        // 右侧更新时间卡（半透明白头部卡，与查在线/排行榜头部卡同款）
        val cardWidth = 240
        val cardHeight = HEADER_HEIGHT - 16
        val cardX = WIDTH - MARGIN - cardWidth
        val cardY = top + 8
        paintPanel(
            g, cardX.toFloat(), cardY.toFloat(), cardWidth.toFloat(), cardHeight.toFloat(),
            COLOR_HEADER_CARD, 20f,
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
    private fun drawMetricGrid(g: Graphics2D, cards: List<MetricCard>) {
        if (cards.isEmpty()) return
        val gridTop = MARGIN + HEADER_HEIGHT + HEADER_GAP
        val contentWidth = WIDTH - 2 * MARGIN
        val cardWidth = (contentWidth - (COLUMNS - 1) * CARD_GAP) / COLUMNS
        val rows = (cards.size + COLUMNS - 1) / COLUMNS
        val gridHeight = rows * CARD_HEIGHT + (rows - 1) * CARD_GAP

        // 网格容器（半透明黑底板：与查在线/排行榜网格容器同款 alpha 120）
        paintPanel(
            g,
            MARGIN.toFloat(), (gridTop - 14).toFloat(),
            contentWidth.toFloat(), (gridHeight + 28).toFloat(),
            COLOR_CONTAINER,
            18f,
        )

        cards.forEachIndexed { index, card ->
            val col = index % COLUMNS
            val row = index / COLUMNS
            drawMetricCard(
                g, card,
                MARGIN + col * (cardWidth + CARD_GAP),
                gridTop + row * (CARD_HEIGHT + CARD_GAP),
                cardWidth,
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
    ) {
        // 卡片底色（半透明白，与其他卡片单元格同款）
        paintPanel(g, x.toFloat(), y.toFloat(), cardWidth.toFloat(), CARD_HEIGHT.toFloat(), COLOR_CARD, 18f)

        // 左上标签
        g.font = InfoCardRenderer.font(24f, false)
        g.color = COLOR_LABEL
        g.drawString(card.label, (x + 32).toFloat(), (y + 52).toFloat())

        // 右上状态胶囊（半透明白底 + 状态色文字，与其他卡片徽标同款）
        g.font = InfoCardRenderer.font(22f, true)
        val statusWidth = g.getFontMetrics().stringWidth(card.statusText)
        val pillWidth = statusWidth + 40
        val pillX = x + cardWidth - 32 - pillWidth
        g.color = COLOR_PILL_BG
        g.fill(RoundRectangle2D.Float(pillX.toFloat(), (y + 22).toFloat(), pillWidth.toFloat(), 40f, 20f, 20f))
        g.color = card.statusColor
        g.drawString(card.statusText, (pillX + 20).toFloat(), (y + 50).toFloat())

        // 中部大数值（不可读时灰色，其余用白色）
        g.font = InfoCardRenderer.font(64f, true)
        g.color = if (card.valueText == "--") COLOR_SUB else COLOR_VALUE
        g.drawString(card.valueText, (x + 32).toFloat(), (y + 148).toFloat())

        // 进度条（底部，半透明轨道 + 状态色填充）
        val barX = x + 32
        val barY = y + 178
        val barWidth = cardWidth - 64
        val barHeight = 12
        g.color = COLOR_BAR_TRACK
        g.fill(RoundRectangle2D.Float(barX.toFloat(), barY.toFloat(), barWidth.toFloat(), barHeight.toFloat(), 6f, 6f))
        if (card.progress != null && card.progress > 0.0) {
            val fillWidth = (barWidth * card.progress).toFloat().coerceAtMost(barWidth.toFloat())
            g.color = card.barColor
            g.fill(RoundRectangle2D.Float(
                barX.toFloat(), barY.toFloat(),
                fillWidth.coerceAtLeast(6f), barHeight.toFloat(), 6f, 6f))
        }

        // 底部副说明
        g.font = InfoCardRenderer.font(22f, false)
        g.color = COLOR_SUB
        g.drawString(card.note, (barX).toFloat(), (y + 228).toFloat())
    }
}
