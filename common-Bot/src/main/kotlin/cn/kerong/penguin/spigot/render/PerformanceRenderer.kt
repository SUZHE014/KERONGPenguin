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
 * 设计参考：图片渲染界面参考自 BaiZhouziYou / 新亭泪
 * 发布在 EasyBot 资源市场的图片模板（https://xintinglei.cn/，
 * 模板合集 https://github.com/BaiizhouziYou/easybot-image-templates/）
 * ——性能卡片延续本项目整套深色卡片风格基调（与查信息 / 查在线 /
 * 排行榜同源），本卡片为本项目的独立实现。
 *
 * 面板透明度与其他卡片统一（头部卡 / 网格容器 / 指标卡 / 状态胶囊 /
 * 进度槽全部为半透明体系，头部卡白 34 / 容器黑 64 / 指标卡白 16 /
 * 胶囊白 30，无面板描边）；字体 / 布局 / 指标口径不变。
 *
 * 半透明卡片风格（1.6.7.5 覆盖版，1350 宽，固定版式单页图）：
 * - 顶部标题区（1.7.3：整块全宽标题卡——模糊 + 标题框黑度 + 圆角边缘；
 *   旧版标题文字直画在背景上，看不出卡片边界）：英文眉题
 *   MINECRAFT SERVER + 主标题「服务器性能监控」+ 右侧「更新时间」卡
 *   （1.7.3：标题下方原服务器名小字移除，改由副标题条展示）；
 * - 副标题条（1.7.3 新增）：服务器 / 更新时间 / 状态 三张小卡，
 *   副标题灰度 render.subtitle-card-gray；
 * - 指标卡网格（2 列网格，1.6.7.8 起固定五项：TPS / MSPT / 内存 / CPU /
 *   在线人数，末行单卡整行通栏）：
 *   每卡 = 标签 + 状态胶囊 + 大数值 + 进度条（或「--」表示核心
 *   不支持读取）；1.6.7.1 起单项显隐配置 show-* 已移除；
 * - 1.6.7.8 移除「延迟（玩家平均）」卡——玩家 Ping 反映的是「客户端到
 *   服务器」连接的往返，本地 / 同机 / 内网穿透部署下恒为 0ms，数据
 *   永远只代表服务器本地网络，对性能监控无实际意义（用户反馈），
 *   采集与展示链路一并下线；在线人数卡保留（名单用 /查在线）；
 * - 1.6.9.3：在线人数卡展示「N / M」（M = 服务器最大人数，默认真实值，
 *   可配置改用其他插件修改过的 MOTD 展示人数并绘制占比进度条）；
 *   TPS 卡支持变量覆盖（variable-fetch.tps，数值改为变量原文输出）；
 * - 背景与其他渲染共用 [InfoCardAssets.resolveBackground]
 *   （render.performance.builtin-background，默认内置底图）；
 *   1.6.9.5：背景统一走共享入口（cover 裁剪铺满：等比放大不足的边 +
 *   图片中心裁剪，不拉伸变形）；毛玻璃模糊度（render.blur-strength）
 *   时卡片背后区域铺背景模糊副本（backdrop-filter 局部模糊，
 *   卡片外背景保持清晰）；
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

    /** 标题区与副标题条间距。 */
    private const val HEADER_GAP = 18

    /** 副标题条高度（服务器 / 更新时间 / 状态 三张小卡，1.7.3 新增）。 */
    private const val SUBTITLE_HEIGHT = 84

    /** 副标题条与指标卡区间距。 */
    private const val SUBTITLE_GAP = 18

    /** 单张指标卡高度。 */
    private const val CARD_HEIGHT = 250

    /** 指标卡间距。 */
    private const val CARD_GAP = 18

    /** 指标卡列数。 */
    private const val COLUMNS = 2

    /** 固定指标卡数（TPS / MSPT / 内存 / CPU / 在线人数，1.6.7.8 起五项——延迟项已移除）。 */
    private const val CARD_COUNT = 5

    /** 底部留白。 */
    private const val BOTTOM_PAD = 40

    // ---------- 配色（1.6.7.5 覆盖版：半透明面板，alpha 与其他卡片逐一相同） ----------

    private val COLOR_EYEBROW = Color(0x8A, 0x9B, 0xAE)
    private val COLOR_LABEL = Color(0x9F, 0xB3, 0xC8)
    private val COLOR_VALUE = Color.WHITE
    private val COLOR_SUB = Color(0x8A, 0x9B, 0xAE)

    /**
     * 头部「更新时间」卡底色（半透明黑）。1.7.2 覆盖版起接标题框黑度
     * （render.title-card-gray，默认 80）——旧版硬编码半透明白 alpha 34，
     * 改配置调不动（视觉上「透明」）；与其他卡片头部卡同源。
     */
    private val COLOR_HEADER_CARD: Color get() = Color(0x00, 0x00, 0x00, RenderSettings.titleCardGray())

    /** 指标卡底色（半透明白，与查信息/查在线/排行榜单元格同款 alpha 16）。 */
    private val COLOR_CARD = Color(0xFF, 0xFF, 0xFF, 16)

    /**
     * 网格容器底色（半透明黑）。1.7.2 起黑度可配（render.item-card-gray，
     * 默认 106 = 旧值 120 与 1.7.0 浅值 64 的中间偏旧值一侧）；每次渲染
     * 实时读取，与其他渲染器保持一致。
     */
    private val COLOR_CONTAINER: Color get() = Color(0x00, 0x00, 0x00, RenderSettings.itemCardGray())

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
        /** 服务器名称（1.7.3：副标题条「服务器」项展示；标题区原小字已移除）。 */
        val serverName: String,
        /** 更新时间（HH:mm，调用方格式化好传入）。 */
        val updateTime: String,
        /** 性能快照（数据源）。 */
        val snapshot: cn.kerong.bot.PerformanceSnapshot,
        /** 预处理背景（尺寸可与画布不一致，渲染时拉伸铺满）；null 表示无背景。 */
        val background: BufferedImage? = null,
        /** 在线人数卡展示的最大人数（1.6.9.3 新增；null = 不展示「/ M」部分）。 */
        val maxPlayers: Int? = null,
        /** 最大人数是否为其他插件修改过的展示值（MOTD 展示人数；副说明标记）。 */
        val maxPlayersFromMotd: Boolean = false,
        /**
         * TPS 变量文本（1.6.9.3 新增，variable-fetch.tps）：非 null 时 TPS 卡
         * 数值改为原文输出该文本（不套 %.1f 格式化）；可解析为数值的同步
         * 用于状态分级与进度条，非数值文本仅展示（状态「已读取」）。
         */
        val tpsVariableText: String? = null,
    )

    /** 单个指标卡的绘制模型（由快照派生，固定五项）。 */
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
     * 计算画布高度（固定五项指标：TPS / MSPT / 内存 / CPU / 在线人数；
     * 五项仍占三行，末行通栏；1.7.3：新增副标题条高度）。
     * 必须在取背景（尺寸相关）与渲染前调用，两者共用本结果。
     */
    fun measureHeight(): Int {
        val rows = (CARD_COUNT + COLUMNS - 1) / COLUMNS
        val gridHeight = rows * CARD_HEIGHT + (rows - 1) * CARD_GAP
        return MARGIN + HEADER_HEIGHT + HEADER_GAP + SUBTITLE_HEIGHT + SUBTITLE_GAP + gridHeight + BOTTOM_PAD
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

            // 1.6.9.5：背景统一走共享入口（cover 裁剪铺满，不再拉伸变形）；
            // 返回卡片背后局部模糊上下文（毛玻璃，模糊度 render.blur-strength）
            val backdrop = InfoCardAssets.drawBackground(g, data.background, WIDTH, height)
            drawHeader(g, backdrop, data.updateTime)
            drawSubtitle(g, backdrop, data.serverName, data.updateTime)
            drawMetricGrid(g, backdrop, cards)
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

    /** 由快照派生要绘制的指标卡列表（顺序即展示顺序，固定五项）。 */
    private fun buildCards(data: PerformanceData): List<MetricCard> {
        val snapshot = data.snapshot
        val cards = ArrayList<MetricCard>(CARD_COUNT)

        // TPS：满值 20；≥18.5 流畅 / 15~18.5 轻度负载 / <15 过载
        // 1.6.9.3：TPS 变量（variable-fetch.tps）优先——数值改为变量原文，
        // 可解析为数值的仍用于状态分级与进度条，非数值文本仅展示
        run {
            val variableText = data.tpsVariableText
            val tps = if (variableText != null) parseDoubleOrNull(variableText) else snapshot.tps
            val color = when {
                tps == null -> COLOR_SUB
                tps >= 18.5 -> COLOR_GOOD
                tps >= 15.0 -> COLOR_WARN
                else -> COLOR_BAD
            }
            cards.add(
                MetricCard(
                    label = "TPS（每秒刻数）",
                    valueText = variableText ?: tps?.let { String.format("%.1f", it) } ?: "--",
                    statusText = when {
                        variableText != null && tps == null -> "已读取"
                        tps == null -> "暂不可读"
                        tps >= 18.5 -> "流畅"
                        tps >= 15.0 -> "轻度负载"
                        else -> "过载"
                    },
                    statusColor = color,
                    progress = tps?.let { (it / 20.0).coerceIn(0.0, 1.0) },
                    barColor = color,
                    note = if (variableText != null) {
                        "来自变量 variable-fetch.tps · 满值 20.0"
                    } else {
                        "满值 20.0 · 最近 1 分钟平均"
                    },
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

        // 在线玩家（仅人数展示，1.6.7.6 新增：名单详情用 /查在线 命令；
        // 1.6.7.8：原第五位「延迟（玩家平均）」卡已移除——玩家 Ping 只
        // 反映客户端到服务器连接的往返，本地 / 同机连接下恒为 0ms，
        // 数据永远只代表服务器本地网络，对性能监控无实际意义；
        // 1.6.9.3：展示「N / M」——M 为服务器最大人数，默认真实值，
        // render.performance.max-players-source = motd 时用其他插件修改过的
        // 展示人数（副说明标记「MOTD 展示值」），进度条按 N/M 占比绘制）
        run {
            val online = snapshot.onlinePlayers
            val max = data.maxPlayers
            val color = when {
                online == null -> COLOR_SUB
                online > 0 -> COLOR_GOOD
                else -> COLOR_SUB
            }
            // 人数占比（max 无效时不画进度条，与旧版一致）
            val occupancy = if (online != null && max != null && max > 0) {
                (online.toDouble() / max.toDouble()).coerceIn(0.0, 1.0)
            } else {
                null
            }
            cards.add(
                MetricCard(
                    label = "在线玩家（人数）",
                    valueText = when {
                        online == null -> "--"
                        max != null && max > 0 -> "$online / $max"
                        else -> online.toString()
                    },
                    statusText = when {
                        online == null -> "暂不可读"
                        online > 0 -> "在线中"
                        else -> "空服"
                    },
                    statusColor = color,
                    progress = occupancy,
                    barColor = color,
                    note = when {
                        max == null || max <= 0 -> "仅展示人数 · 玩家名单用 /查在线 查看"
                        data.maxPlayersFromMotd -> "最大 $max 人（MOTD 展示值）· 名单用 /查在线 查看"
                        else -> "服务器最大 $max 人 · 名单用 /查在线 查看"
                    },
                )
            )
        }

        return cards
    }

    /** 解析数值文本（容错：空白 / 千分位逗号 / 非法字符返回 null；TPS 变量用）。 */
    private fun parseDoubleOrNull(text: String): Double? = try {
        text.replace(",", "").trim().toDoubleOrNull()
    } catch (_: Throwable) {
        null
    }

    /** MB 数值转 GB 文本（一位小数；小于 1GB 显示 0.x）。 */
    private fun formatGb(mb: Long): String = String.format("%.1f", mb / 1024.0)

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

    /** 顶部标题区：眉题 + 主标题 + 右侧更新时间卡（1.7.3：整块全宽标题卡
     * ——模糊 + 标题框黑度 + 圆角边缘；标题下方原服务器名小字已移除，
     * 服务器名改由副标题条展示）。 */
    private fun drawHeader(g: Graphics2D, backdrop: InfoCardAssets.CardBackdropContext?, updateTime: String) {
        val top = MARGIN

        // 1.7.3：全宽标题卡（模糊 + 灰度 + 圆角边缘一次到位）
        val headerWidth = (WIDTH - 2 * MARGIN).toFloat()
        backdrop?.paintCard(g, MARGIN.toFloat(), top.toFloat(), headerWidth, HEADER_HEIGHT.toFloat(), 24f)
        paintPanel(
            g, MARGIN.toFloat(), top.toFloat(), headerWidth, HEADER_HEIGHT.toFloat(),
            COLOR_HEADER_CARD, 24f,
        )

        // 英文眉题（字距拉宽）
        g.font = InfoCardRenderer.font(26f, true)
        g.color = COLOR_EYEBROW
        g.drawString("M I N E C R A F T   S E R V E R", (MARGIN + 8).toFloat(), (top + 38).toFloat())

        // 主标题（固定；服务器名在副标题条展示）
        g.font = InfoCardRenderer.font(58f, true)
        g.color = COLOR_VALUE
        g.drawString("服务器性能监控", (MARGIN + 4).toFloat(), (top + 108).toFloat())

        // 右侧更新时间卡（叠在标题卡上；与查在线/排行榜头部卡同款）
        val cardWidth = 240
        val cardHeight = HEADER_HEIGHT - 16
        val cardX = WIDTH - MARGIN - cardWidth
        val cardY = top + 8
        backdrop?.paintCard(g, cardX.toFloat(), cardY.toFloat(), cardWidth.toFloat(), cardHeight.toFloat(), 20f)
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
    }

    /** 副标题条：服务器 / 更新时间 / 状态 三张并排小卡（1.7.3 新增，与
     * 查在线状态栏同款；底色接副标题灰度 render.subtitle-card-gray）。 */
    private fun drawSubtitle(g: Graphics2D, backdrop: InfoCardAssets.CardBackdropContext?, serverName: String, updateTime: String) {
        val top = (MARGIN + HEADER_HEIGHT + HEADER_GAP).toFloat()
        val gap = 16f
        val cardWidth = (WIDTH - 2 * MARGIN - 2 * gap).toFloat() / 3f
        val cardHeight = SUBTITLE_HEIGHT.toFloat()

        val cells = listOf(
            "服务器" to serverName,
            "更新时间" to updateTime,
            "状态" to "正常",
        )
        cells.forEachIndexed { index, (label, value) ->
            val x = MARGIN + index * (cardWidth + gap)
            backdrop?.paintCard(g, x, top, cardWidth, cardHeight, 14f)
            // 1.7.3：副标题条接副标题灰度（render.subtitle-card-gray，默认 40）
            g.color = Color(0x00, 0x00, 0x00, RenderSettings.subtitleCardGray())
            g.fill(RoundRectangle2D.Float(x, top, cardWidth, cardHeight, 14f, 14f))
            g.font = InfoCardRenderer.font(22f, false)
            g.color = COLOR_LABEL
            g.drawString(label, x + 26f, top + 34f)
            g.font = InfoCardRenderer.font(30f, true)
            g.color = COLOR_VALUE
            g.drawString(clip(g, value, (cardWidth - 52).toInt()), x + 26f, top + 68f)
        }
    }

    /**
     * 指标卡网格：2 列 × N 行，每卡 = 标签 + 状态胶囊 + 大数值 + 进度条 + 副说明；
     * 末行仅一张卡时（五项布局的在线人数卡）整行通栏，避免右下留空位
     * （容器背后铺局部模糊，容器内的指标卡不再重复模糊）。
     */
    private fun drawMetricGrid(g: Graphics2D, backdrop: InfoCardAssets.CardBackdropContext?, cards: List<MetricCard>) {
        if (cards.isEmpty()) return
        val gridTop = MARGIN + HEADER_HEIGHT + HEADER_GAP + SUBTITLE_HEIGHT + SUBTITLE_GAP
        val contentWidth = WIDTH - 2 * MARGIN
        val cardWidth = (contentWidth - (COLUMNS - 1) * CARD_GAP) / COLUMNS
        val rows = (cards.size + COLUMNS - 1) / COLUMNS
        val gridHeight = rows * CARD_HEIGHT + (rows - 1) * CARD_GAP

        // 网格容器（半透明黑底板：与查在线/排行榜网格容器同款 alpha 64）
        backdrop?.paintCard(
            g,
            MARGIN.toFloat(), (gridTop - 14).toFloat(),
            contentWidth.toFloat(), (gridHeight + 28).toFloat(),
            18f,
        )
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
            // 1.6.7.8：末行首列且是最后一张卡时整行通栏（五项布局的
            // 在线人数卡）；偶数项布局不受影响
            val lone = index == cards.size - 1 && col == 0
            drawMetricCard(
                g, card,
                MARGIN + col * (cardWidth + CARD_GAP),
                gridTop + row * (CARD_HEIGHT + CARD_GAP),
                if (lone) contentWidth else cardWidth,
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

    /** 截取不超给定宽度的最长前缀（与其他卡片一致的截断策略；1.7.3 副标题条用）。 */
    private fun clip(g: Graphics2D, text: String, maxWidth: Int): String {
        if (maxWidth <= 4) return "…"
        val metrics = g.getFontMetrics()
        if (metrics.stringWidth(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) > maxWidth) end--
        return if (end == 0) "…" else text.substring(0, end).dropLast(1) + "…"
    }
}
