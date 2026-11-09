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
 * ——性能卡片延续本项目整套深色卡片风格基调（与玩家名片 / 查在线 /
 * 排行榜同源），本卡片为本项目的独立实现。
 *
 * 面板透明度与其他卡片统一（头部卡 / 网格容器 / 指标卡 / 状态胶囊 /
 * 进度槽全部为半透明体系，头部卡白 34 / 容器黑 64 / 指标卡白 16 /
 * 胶囊白 30，无面板描边）；字体 / 布局 / 指标口径不变。
 *
 * 半透明卡片风格（1350 宽，固定版式单页图）：
 * - 顶部标题区（1.7.3：整块全宽标题卡——模糊 + 标题框黑度 + 圆角边缘一次到位；
 * 标题文字 padding 对齐玩家名片欢迎面板（距卡左缘 24~28px），
 *   右上角小方框改「综合状态」（TPS / MSPT / 内存 / CPU 综合评级，不含人数）
 *   且灰度永远比标题框深一点（同灰度叠加视觉融合看不出边界）；
 * - 副标题条：服务器 / 更新时间 / 渲染耗时 三张小卡，
 * 副标题灰度 render.subtitle-card-gray；第三格改渲染耗时
 *   （开始渲染到本图绘制完成的毫秒数，绘制完成后补画）且与标题框 /
 *   指标网格容器上下等距（视觉间距均为 18px）；
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
 * 背景统一走共享入口（cover 裁剪铺满：等比放大不足的边 +
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

 /** 顶部标题区高度（眉题 + 主标题 + 右侧时间卡；二轮：150 → 164；
 * 三轮：164 → 182，眉题视觉顶边距 27 → 45px——对齐玩家名片
     * 欢迎面板（最上框参照标准，实测 45px），用户反馈二轮 27px 仍太贴边；
     * +18 全部给顶部，底边距 42px 与眉题→主标题间距保持不变）。 */
    private const val HEADER_HEIGHT = 182

    /** 标题区与副标题条间距。 */
    private const val HEADER_GAP = 18

    /** 副标题条高度（服务器 / 更新时间 / 状态 三张小卡，1.7.3 新增）。 */
    private const val SUBTITLE_HEIGHT = 84

    /**
 * 副标题条与指标卡区间距（18 → 32 = HEADER_GAP 18 +
     * 网格容器上垫 14——指标卡网格容器比指标卡上扩 14px，旧值 18 时
     * 副标题条到容器的视觉间距只有 4px，与标题框到副标题条的 18px
     * 不等距；补偿后视觉间距两边都是 18px）。 */
    private const val SUBTITLE_GAP = 32

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

 // ---------- 配色（半透明面板，alpha 与其他卡片逐一相同） ----------

    private val COLOR_EYEBROW = Color(0x8A, 0x9B, 0xAE)
    private val COLOR_LABEL = Color(0x9F, 0xB3, 0xC8)

    private val COLOR_VALUE = Color.WHITE
    private val COLOR_SUB = Color(0x8A, 0x9B, 0xAE)

    /**
 * 头部标题卡底色（半透明黑）。接标题框黑度
     * （render.title-card-gray，默认 80）；右上角小方框另接徽标灰度
     * （永远比标题框深 +36，防同灰度叠加融合）。
     */
    private val COLOR_HEADER_CARD: Color get() = Color(0x00, 0x00, 0x00, RenderSettings.titleCardGray())

 /** 右上角小方框（徽标卡）底色：永远比标题框深一点。 */
    private val COLOR_HEADER_BADGE: Color get() = Color(0x00, 0x00, 0x00, RenderSettings.headerBadgeCardGray())

    /** 指标卡底色（半透明白，与玩家名片/查在线/排行榜单元格同款 alpha 16）。 */
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
        /**
 * 在线人数展示覆盖值：非 null 时在线人数卡
         * 的人数 N 用它（motd 模式 = 服务器列表展示值），不再用快照真实值。
         */
        val onlinePlayersDisplay: Int? = null,
 /** 在线人数卡展示的最大人数（null = 不展示「/ M」部分）。 */
        val maxPlayers: Int? = null,
        /** 人数与最大人数是否为服务器列表展示值（MOTD 展示值；副说明标记）。 */
        val maxPlayersFromMotd: Boolean = false,
        /**
 * TPS 变量文本（variable-fetch.tps）：非 null 时 TPS 卡
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
 // 渲染耗时计时起点（副标题条「渲染耗时」展示从开始
        // 渲染到全部面板绘制完成的毫秒数——注意不是从接收指令开始，取数 /
        // 排队 / 冷却判断不计入；全部绘制完成后补画进第三格，再编码 PNG）
        val renderStart = System.nanoTime()
        val cards = buildCards(data)
        val status = buildOverallStatus(data.snapshot, data.tpsVariableText)
        val height = measureHeight()
        val image = BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

 // 背景统一走共享入口（cover 裁剪铺满，不再拉伸变形）；
            // 返回卡片背后局部模糊上下文（毛玻璃，模糊度 render.blur-strength）
            val backdrop = InfoCardAssets.drawBackground(g, data.background, WIDTH, height)
            drawHeader(g, backdrop, status)
            drawSubtitle(g, backdrop, data.serverName, data.updateTime)
            drawMetricGrid(g, backdrop, cards)

 // 补画渲染耗时（此时全部面板已画完，毫秒数含背景 /
            // 标题 / 副标题 / 指标网格的全部绘制耗时；PNG 编码与发送不预先包含）
            val elapsedMs = (System.nanoTime() - renderStart) / 1_000_000L
            drawRenderElapsed(g, elapsedMs)
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
 // TPS 变量（variable-fetch.tps）优先——数值改为变量原文，
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

 // 在线玩家（仅人数展示，名单详情用 /查在线 命令；
 // 原第五位「延迟（玩家平均）」卡已移除——玩家 Ping 只
        // 反映客户端到服务器连接的往返，本地 / 同机连接下恒为 0ms，
        // 数据永远只代表服务器本地网络，对性能监控无实际意义；
 // 展示「N / M」——M 为服务器最大人数，默认真实值，
        // render.performance.max-players-source = motd 时用服务器列表展示值
        // （副说明标记「MOTD 展示值」，进度条按 N/M 占比绘制）；
 // motd 模式下人数 N 与最大 M 成对切换——
        // N 用 onlinePlayersDisplay 展示值，状态胶囊与进度条同源）
        run {
            val online = data.onlinePlayersDisplay ?: snapshot.onlinePlayers
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
                        data.maxPlayersFromMotd -> "人数与最大值为 MOTD 展示值 · 名单用 /查在线 查看"
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

 // ---------- 综合状态（右上角小方框改综合评级，不含人数） ----------

    /** 综合状态评估结果（文字 + 语义色）。 */
    private data class OverallStatus(val text: String, val color: Color)

    /**
 * 综合状态评估：TPS / MSPT / 内存 / CPU 四项各评
     * 0（好）/ 1（中）/ 2（差）三级，取最差一项定级（保守口径，任一指标
     * 异常即如实体现）；**不含在线人数**（人数多寡不是性能问题）；
     * 四项全部不可读时返回「暂不可读」（灰色）。
     * 分级阈值与各指标卡状态胶囊一致（单一口径）。 */
    private fun buildOverallStatus(
        snapshot: cn.kerong.bot.PerformanceSnapshot,
        tpsVariableText: String?,
    ): OverallStatus {
        var worst = -1
        fun grade(level: Int) {
            if (level > worst) worst = level
        }
        // TPS（变量文本可解析时用变量值，与指标卡口径一致）
        val tps = if (tpsVariableText != null) parseDoubleOrNull(tpsVariableText) else snapshot.tps
        if (tps != null) grade(
            when {
                tps >= 18.5 -> 0
                tps >= 15.0 -> 1
                else -> 2
            }
        )
        // MSPT
        val mspt = snapshot.mspt
        if (mspt != null) grade(
            when {
                mspt < 40.0 -> 0
                mspt < 50.0 -> 1
                else -> 2
            }
        )
        // 内存（JVM 堆占用比）
        val max = snapshot.maxMemoryMb
        if (max > 0) {
            val ratio = snapshot.usedMemoryMb.toDouble() / max.toDouble()
            grade(
                when {
                    ratio > 0.90 -> 2
                    ratio > 0.75 -> 1
                    else -> 0
                }
            )
        }
        // CPU（进程占用）
        val cpu = snapshot.cpuLoad
        if (cpu != null) grade(
            when {
                cpu > 0.90 -> 2
                cpu > 0.75 -> 1
                else -> 0
            }
        )
        return when (worst) {
            0 -> OverallStatus("运行良好", COLOR_GOOD)
            1 -> OverallStatus("轻度负载", COLOR_WARN)
            2 -> OverallStatus("负载过高", COLOR_BAD)
            else -> OverallStatus("暂不可读", COLOR_SUB)
        }
    }

    // ---------- 面板绘制 ----------

    /**
 * 面板底色（半透明圆角面板，无描边，与其他卡片同源）。
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

    /**
     * 顶部标题区：眉题 + 主标题 + 右侧综合状态卡（1.7.3：整块全宽标题卡
 * ——模糊 + 标题框黑度 + 圆角边缘；标题文字 padding 对齐
     * 玩家名片欢迎面板（眉题距卡左缘 28px、主标题 24px，旧版 8/4px 太贴边）；
     * 右上角小方框改「综合状态」——四项指标综合评级不含人数，灰度永远
 * 比标题框深一点防融合；二轮：眉题 / 主标题基线下移 8px
     * （上边缘间距加大），小方框与标题框四周留 14px 可见间距——旧 8/8/0
 * 太贴边看不出内嵌层次；三轮：眉题 / 主标题基线再下移
     * 18px（顶边距 27 → 45px 对齐玩家名片最上框），副标题框零改动——
     * 用户口径：间距调的是主标题框）。 */
    private fun drawHeader(g: Graphics2D, backdrop: InfoCardAssets.CardBackdropContext?, status: OverallStatus) {
        val top = MARGIN

 // 全宽标题卡（模糊 + 灰度 + 圆角边缘一次到位）
        val headerWidth = (WIDTH - 2 * MARGIN).toFloat()
        backdrop?.paintCard(g, MARGIN.toFloat(), top.toFloat(), headerWidth, HEADER_HEIGHT.toFloat(), 24f)
        paintPanel(
            g, MARGIN.toFloat(), top.toFloat(), headerWidth, HEADER_HEIGHT.toFloat(),
            COLOR_HEADER_CARD, 24f,
        )

 // 英文眉题（字距拉宽；距卡左缘 28px，与玩家名片欢迎面板一致；
 // 三轮：基线 46 → 64，上边缘视觉间距 27 → 45px，
        // 对齐玩家名片欢迎面板眉题顶边距——二轮 27px 用户反馈仍偏贴）
        g.font = InfoCardRenderer.font(26f, true)
        g.color = COLOR_EYEBROW
        g.drawString("M I N E C R A F T   S E R V E R", (MARGIN + 32).toFloat(), (top + 64).toFloat())

        // 主标题（固定；服务器名在副标题条展示；距卡左缘 24px；
 // 三轮：基线 118 → 136，与眉题同步 +18——
        // 眉题→主标题间距与底边距 42px 均保持不变）
        g.font = InfoCardRenderer.font(58f, true)
        g.color = COLOR_VALUE
        g.drawString("服务器性能监控", (MARGIN + 28).toFloat(), (top + 136).toFloat())

 // 右上角综合状态卡（叠在标题卡上；更新时间→综合状态，
        // 灰度永远比标题框深一点——同灰度叠加视觉融合看不出边界；
 // 二轮：与标题框四周留 14px 可见间距——旧 8/8/0 贴边）
        val cardWidth = 240
        val cardInset = 14
        val cardHeight = HEADER_HEIGHT - 2 * cardInset
        val cardX = WIDTH - MARGIN - cardInset - cardWidth
        val cardY = top + cardInset
        backdrop?.paintCard(g, cardX.toFloat(), cardY.toFloat(), cardWidth.toFloat(), cardHeight.toFloat(), 20f)
        paintPanel(
            g, cardX.toFloat(), cardY.toFloat(), cardWidth.toFloat(), cardHeight.toFloat(),
            COLOR_HEADER_BADGE, 20f,
        )
        g.font = InfoCardRenderer.font(24f, false)
        g.color = COLOR_LABEL
 // 三轮：标题框加高后卡内容整体下移 9px（卡高 136 → 154，
        // 四周 14px 间距不变），保持标签 / 状态文字在卡内视觉居中
        g.drawString("综合状态", (cardX + 28).toFloat(), (cardY + 51).toFloat())
        // 状态文字用语义色（绿 / 黄 / 红 / 灰），右对齐；字号按四字宽度适配卡宽
        g.font = InfoCardRenderer.font(44f, true)
        g.color = status.color
        val statusWidth = g.getFontMetrics().stringWidth(status.text)
        g.drawString(status.text, (cardX + cardWidth - 28 - statusWidth).toFloat(), (cardY + 109).toFloat())
    }

 /** 副标题条：服务器 / 更新时间 / 渲染耗时 三张并排小卡（与
     * 查在线状态栏同款；底色接副标题灰度 render.subtitle-card-gray；
 * 第三格改「渲染耗时」——从开始渲染到本图全部面板
     * 绘制完成的毫秒数，数值在绘制完成后由 [drawRenderElapsed] 补画）。 */
    private fun drawSubtitle(g: Graphics2D, backdrop: InfoCardAssets.CardBackdropContext?, serverName: String, updateTime: String) {
        val top = (MARGIN + HEADER_HEIGHT + HEADER_GAP).toFloat()
        val gap = 16f
        val cardWidth = (WIDTH - 2 * MARGIN - 2 * gap).toFloat() / 3f
        val cardHeight = SUBTITLE_HEIGHT.toFloat()

        val cells = listOf(
            "服务器" to serverName,
            "更新时间" to updateTime,
            "渲染耗时" to null as String?,
        )
        cells.forEachIndexed { index, (label, value) ->
            val x = MARGIN + index * (cardWidth + gap)
            backdrop?.paintCard(g, x, top, cardWidth, cardHeight, 14f)
            // 副标题条接副标题灰度（render.subtitle-card-gray，1.7.3.1 第二次
 // 默认 80，与标题框相同）
            g.color = Color(0x00, 0x00, 0x00, RenderSettings.subtitleCardGray())
            g.fill(RoundRectangle2D.Float(x, top, cardWidth, cardHeight, 14f, 14f))
 // 四轮起：小标签统一走 InfoCardRenderer.drawSubLabel
 // （固定灰 0x9FB3C8，1.7.0 同款）
            InfoCardRenderer.drawSubLabel(g, label, x + 26f, top + 34f)
            g.font = InfoCardRenderer.font(30f, true)
            g.color = COLOR_VALUE
            value?.let { g.drawString(clip(g, it, (cardWidth - 52).toInt()), x + 26f, top + 68f) }
        }
    }

    /**
 * 补画副标题条「渲染耗时」数值：全部面板绘制完成后、
     * PNG 编码前写入——从开始渲染（进入 [render]）到此刻的毫秒数；
     * 不含取数 / 排队 / 冷却判断（用户口径：不是从接收指令开始算）。 */
    private fun drawRenderElapsed(g: Graphics2D, elapsedMs: Long) {
        val gap = 16f
        val cardWidth = (WIDTH - 2 * MARGIN - 2 * gap).toFloat() / 3f
        val x = MARGIN + 2 * (cardWidth + gap)
        val top = (MARGIN + HEADER_HEIGHT + HEADER_GAP).toFloat()
        g.font = InfoCardRenderer.font(30f, true)
        g.color = COLOR_VALUE
        g.drawString(clip(g, "$elapsedMs ms", (cardWidth - 52).toInt()), x + 26f, top + 68f)
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
 // 末行首列且是最后一张卡时整行通栏（五项布局的
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
