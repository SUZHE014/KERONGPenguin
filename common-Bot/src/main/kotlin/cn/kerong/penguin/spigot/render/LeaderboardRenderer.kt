package cn.kerong.penguin.spigot.render

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 在线时长排行榜渲染器（/在线排行榜，1.5.5 新增）。
 *
 * 设计参考（issue #1）：图片渲染界面参考自 BaiZhouziYou / 新亭泪
 * 发布在 EasyBot 资源市场的图片模板（https://xintinglei.cn/），本项目在其
 * 风格基础上做了独立改进。感谢原作者的设计。
 *
 * 每名玩家一行：排名徽章（前三名金 / 银 / 铜配色）+ 真实皮肤头像 + 玩家名 +
 * 右侧总在线时长。
 *
 * 背景（1.5.7）：默认使用内置专属背景图（深灰色主体 + 深绿色装饰，随 JAR 分发，
 * 零配置零素材）；qq-bind.leaderboard.builtin-background 关闭后改用插件
 * img/ 目录随机图（img 无可用图片时自动回退内置图）。
 *
 * 分段发送（1.5.5）：每页最多 leaderboard.page-size 行（1.7.3 起可配全局
 * 上限，默认 20；旧版硬编码 15）—— 第一页带完整标题区（眉题 + 主标题 +
 * 右侧统计卡 + 副标题条 + 列表头），后续页只绘制行内容不再绘制标题，
 * 由编排层逐张发送。
 *
 * 字体 / 圆角头像 / 截断策略与 [OnlineListRenderer] / [InfoCardRenderer] 同源；
 * 画布 PNG 写出后立即 flush 释放栅格内存（与其他渲染器一致）。
 *
 * 1.6.9.5：背景统一走共享入口（cover 裁剪铺满：等比放大不足的边 +
 * 图片中心裁剪，不拉伸变形）；毛玻璃模糊度（render.blur-strength）
 * 时卡片背后区域铺背景模糊副本（backdrop-filter 局部模糊，
 * 卡片外背景保持清晰）。
 */
object LeaderboardRenderer {

    /** 画布宽度。 */
    internal const val WIDTH = 1200

    // ---------- 布局常量 ----------

    /** 页边距。 */
    private const val MARGIN = 40

    /** 续页顶部留白（无标题区，顶部收窄）。 */
    private const val PAGE_TOP_PAD = 24

    /** 第一页标题区高度（眉题 + 主标题 + 右侧统计卡）。 */
    private const val HEADER_HEIGHT = 150

    /** 标题区与副标题条间距。 */
    private const val HEADER_GAP = 18

    /** 副标题条高度（服务器 / 更新时间 / 状态 三张小卡，1.7.3 新增）。 */
    private const val SUBTITLE_HEIGHT = 84

    /** 副标题条与列表头间距。 */
    private const val SUBTITLE_GAP = 18

    /** 列表头高度。 */
    private const val LIST_HEADER_HEIGHT = 56

    /** 列表头与首行间距。 */
    private const val LIST_HEADER_GAP = 14

    /** 单行高度。 */
    private const val ROW_HEIGHT = 84

    /** 行间距。 */
    private const val ROW_GAP = 12

    /** 头像尺寸。 */
    private const val AVATAR_SIZE = 56

    /** 底部留白。 */
    private const val BOTTOM_PAD = 40

    /** 空数据提示卡高度。 */
    private const val EMPTY_HEIGHT = 120

    // ---------- 配色（毛玻璃深色系，与其他卡片同源） ----------

    private val COLOR_EYEBROW = Color(0x8A, 0x9B, 0xAE)
    private val COLOR_LABEL = Color(0x9F, 0xB3, 0xC8)
    private val COLOR_VALUE = Color.WHITE
    private val COLOR_BADGE = Color(0xA8, 0xBA, 0xCC)

    /** 排名徽章配色：第 1 / 2 / 3 名金 / 银 / 铜，其余灰蓝。 */
    private fun rankColor(rank: Int): Color = when (rank) {
        1 -> Color(0xE6, 0xB4, 0x22)
        2 -> Color(0xC0, 0xC7, 0xD1)
        3 -> Color(0xCD, 0x8F, 0x52)
        else -> Color(0x5A, 0x6B, 0x80)
    }

    /** 单行条目（头像可为 null：无皮肤数据时画占位块）。 */
    data class LeaderEntry(
        /** 全局排名（1 起，跨页连续编号）。 */
        val rank: Int,
        val name: String,
        /** 总在线秒数（展示用文本由调用方格式化传入或用 [formatPlayTime]）。 */
        val playSeconds: Long,
        val avatar: BufferedImage? = null,
        /**
         * 右侧数值的自定义展示文本（1.6.0 金币榜新增）。
         * 非 null 时优先于 [formatPlayTime] 绘制（金币榜传入金额，
         * 在线排行不传保持时长文本，旧版渲染逐字节不变）。
         */
        val valueText: String? = null,
    )

    /** 单页渲染入参（entries 即本页内容，调用方已切好页）。 */
    data class LeaderboardData(
        val serverName: String,
        val updateTime: String,
        /** 本页条目（顺序即展示顺序）。 */
        val entries: List<LeaderEntry>,
        /** 参与排行的玩家总数（统计卡展示）。 */
        val totalPlayers: Long,
        /** 配置的显示人数上限（列表头展示）。 */
        val topLimit: Int,
        /**
         * 预处理背景（含暗色遮罩；尺寸可与画布不一致，渲染时按 cover
         * 裁剪铺满，1.6.9.5 起不再拉伸）；null 表示无背景。
         */
        val background: BufferedImage? = null,
        /** 主标题（1.6.0：金币榜传入「金币排行榜」等；默认在线排行榜，旧版不变）。 */
        val title: String = "在线排行榜",
        /** 列表头前缀（与 TOP N 拼接；默认总在线时长，旧版不变）。 */
        val listHeader: String = "总在线时长",
    )

    /**
     * 按本页行数计算画布高度：第一页含标题区与副标题条，续页只含行内容。
     * 必须在取背景（尺寸相关）与渲染前调用，两者共用本结果。
     */
    fun measurePageHeight(rowCount: Int, firstPage: Boolean): Int {
        val gridHeight = if (rowCount <= 0) {
            EMPTY_HEIGHT
        } else {
            rowCount * ROW_HEIGHT + (rowCount - 1) * ROW_GAP
        }
        val total = if (firstPage) {
            MARGIN + HEADER_HEIGHT + HEADER_GAP + SUBTITLE_HEIGHT + SUBTITLE_GAP +
                LIST_HEADER_HEIGHT + LIST_HEADER_GAP + gridHeight + BOTTOM_PAD
        } else {
            PAGE_TOP_PAD + gridHeight + BOTTOM_PAD
        }
        return total.coerceAtLeast(if (firstPage) 640 else 200)
    }

    /** 渲染单页并返回 PNG 字节（首页带标题区，续页只画行内容；1.7.3 覆盖版：
     * 首页副标题条第三格改「渲染耗时」——从开始渲染到本页全部面板绘制
     * 完成的毫秒数，绘制完成后补画；从开始渲染图片起算，不含接收指令后的
     * 取数 / 头像预热 / 排队环节）。 */
    fun renderPage(data: LeaderboardData, firstPage: Boolean): ByteArray {
        // 1.7.3 覆盖版：渲染耗时计时起点（全部面板绘制完成后补画进副标题条）
        val renderStart = System.nanoTime()
        val height = measurePageHeight(data.entries.size, firstPage)
        val image = BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            // 1.6.9.5：背景统一走共享入口（cover 裁剪铺满，不再拉伸变形）；
            // 返回卡片背后局部模糊上下文（毛玻璃，模糊度 render.blur-strength）
            val backdrop = InfoCardAssets.drawBackground(g, data.background, WIDTH, height)
            if (firstPage) {
                drawHeader(g, backdrop, data.title, data.totalPlayers)
                drawSubtitle(g, backdrop, data.serverName, data.updateTime)
                drawListHeader(g, data.listHeader, data.topLimit, data.totalPlayers)
            }
            if (data.entries.isEmpty()) {
                drawEmpty(g, backdrop, firstPage)
            } else {
                drawRows(g, backdrop, data.entries, firstPage)
            }

            // 1.7.3 覆盖版：补画渲染耗时（全部面板已画完；PNG 编码与发送不预先包含）
            if (firstPage) {
                val elapsedMs = (System.nanoTime() - renderStart) / 1_000_000L
                drawRenderElapsed(g, elapsedMs)
            }
        } finally {
            g.dispose()
        }
        val output = ByteArrayOutputStream(384 * 1024)
        // 全局透明度（render.global-opacity，默认 1.0 原样零开销）
        ImageIO.write(image, "png", output)
        // PNG 已写出，立即释放单次画布的栅格内存
        image.flush()
        return output.toByteArray()
    }

    /**
     * 秒数格式化为时长文本（与查信息卡片「在线时长」同风格）：
     * 小时 / 分组合（1.6.8 起最大单位为小时，不再展示「天」——
     * 如 3天5小时20分钟 现展示为 77小时20分钟），全零时显示「暂无记录」。
     */
    fun formatPlayTime(seconds: Long): String {
        if (seconds <= 0) return "暂无记录"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return buildString {
            if (hours > 0) append("${hours}小时")
            if (minutes > 0) append("${minutes}分钟")
            if (isEmpty()) append("不足1分钟")
        }
    }

    /** 第一页标题区：眉题 + 主标题 + 右侧统计卡（1.6.0：主标题可自定义；
     * 1.7.3：整块全宽标题卡——背后铺局部模糊 + 标题框黑度 + 圆角边缘，
     * 与查在线 / 查性能头部同款；旧版标题文字直画在背景上，看不出卡片
     * 边界，灰度也只作用于右侧统计小卡）。 */
    private fun drawHeader(g: Graphics2D, backdrop: InfoCardAssets.CardBackdropContext?, title: String, totalPlayers: Long) {
        val top = MARGIN

        // 1.7.3：全宽标题卡（模糊 + 灰度 + 圆角边缘一次到位）
        val headerWidth = (WIDTH - 2 * MARGIN).toFloat()
        backdrop?.paintCard(g, MARGIN.toFloat(), top.toFloat(), headerWidth, HEADER_HEIGHT.toFloat(), 24f)
        // 标题卡底色接标题框黑度（render.title-card-gray，默认 80）
        g.color = Color(0x00, 0x00, 0x00, RenderSettings.titleCardGray())
        g.fill(RoundRectangle2D.Float(MARGIN.toFloat(), top.toFloat(), headerWidth, HEADER_HEIGHT.toFloat(), 24f, 24f))

        // 英文眉题（字距拉宽，与其他卡片统一风格；1.7.3 覆盖版：距卡左缘 28px）
        g.font = InfoCardRenderer.font(26f, true)
        g.color = COLOR_EYEBROW
        g.drawString("M I N E C R A F T   S E R V E R", (MARGIN + 32).toFloat(), (top + 38).toFloat())

        // 主标题（距卡左缘 24px）
        g.font = InfoCardRenderer.font(58f, true)
        g.color = COLOR_VALUE
        g.drawString(title, (MARGIN + 28).toFloat(), (top + 108).toFloat())

        // 右侧统计卡（参与排行玩家总数；叠在标题卡上）
        val cardWidth = 260
        val cardHeight = HEADER_HEIGHT - 16
        val cardX = WIDTH - MARGIN - cardWidth
        val cardY = top + 8
        backdrop?.paintCard(g, cardX.toFloat(), cardY.toFloat(), cardWidth.toFloat(), cardHeight.toFloat(), 20f)
        // 1.7.3 覆盖版：右上角小方框灰度永远比标题框深一点（render.title-card-gray +36）
        // ——旧版与标题框同灰度，叠在同色标题卡上视觉融合看不出边界
        g.color = Color(0x00, 0x00, 0x00, RenderSettings.headerBadgeCardGray())
        g.fill(RoundRectangle2D.Float(cardX.toFloat(), cardY.toFloat(), cardWidth.toFloat(), cardHeight.toFloat(), 20f, 20f))
        g.font = InfoCardRenderer.font(24f, false)
        g.color = COLOR_LABEL
        g.drawString("统计玩家", (cardX + 28).toFloat(), (cardY + 42).toFloat())
        g.font = InfoCardRenderer.font(64f, true)
        g.color = COLOR_VALUE
        val countText = totalPlayers.toString()
        val countWidth = g.getFontMetrics().stringWidth(countText)
        g.drawString(countText, (cardX + cardWidth - 28 - countWidth).toFloat(), (cardY + 100).toFloat())
    }

    /** 副标题条：服务器 / 更新时间 / 渲染耗时 三张并排小卡（1.7.3 新增，与
     * 查在线状态栏同款；底色接副标题灰度 render.subtitle-card-gray；
     * 1.7.3 覆盖版：第三格改「渲染耗时」——数值在全部面板绘制完成后由
     * [drawRenderElapsed] 补画（从开始渲染到绘制完成的毫秒数，非从接收指令起算）。 */
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
            // 副标题条接副标题灰度（render.subtitle-card-gray，覆盖版默认 56）
            g.color = Color(0x00, 0x00, 0x00, RenderSettings.subtitleCardGray())
            g.fill(RoundRectangle2D.Float(x, top, cardWidth, cardHeight, 14f, 14f))
            g.font = InfoCardRenderer.font(22f, false)
            g.color = COLOR_LABEL
            g.drawString(label, x + 26f, top + 34f)
            g.font = InfoCardRenderer.font(30f, true)
            g.color = COLOR_VALUE
            value?.let { g.drawString(clip(g, it, (cardWidth - 52).toInt()), x + 26f, top + 68f) }
        }
    }

    /**
     * 补画副标题条「渲染耗时」数值（1.7.3 覆盖版）：全部面板绘制完成后、
     * PNG 编码前写入——从开始渲染到此刻的毫秒数（不含取数 / 排队环节）。 */
    private fun drawRenderElapsed(g: Graphics2D, elapsedMs: Long) {
        val gap = 16f
        val cardWidth = (WIDTH - 2 * MARGIN - 2 * gap).toFloat() / 3f
        val x = MARGIN + 2 * (cardWidth + gap)
        val top = (MARGIN + HEADER_HEIGHT + HEADER_GAP).toFloat()
        g.font = InfoCardRenderer.font(30f, true)
        g.color = COLOR_VALUE
        g.drawString(clip(g, "$elapsedMs ms", (cardWidth - 52).toInt()), x + 26f, top + 68f)
    }

    /** 第一页列表头：标题 + 右侧人数胶囊（1.6.0：列表头前缀可自定义）。 */
    private fun drawListHeader(g: Graphics2D, listHeader: String, topLimit: Int, totalPlayers: Long) {
        val top = (MARGIN + HEADER_HEIGHT + HEADER_GAP + SUBTITLE_HEIGHT + SUBTITLE_GAP).toFloat()

        g.font = InfoCardRenderer.font(34f, true)
        g.color = COLOR_VALUE
        g.drawString("$listHeader TOP $topLimit", (MARGIN + 4).toFloat(), top + 40f)

        g.font = InfoCardRenderer.font(24f, false)
        val badgeText = "$totalPlayers 名玩家"
        val badgeWidth = g.getFontMetrics().stringWidth(badgeText) + 44
        val badgeX = WIDTH - MARGIN - badgeWidth
        g.color = Color(0xFF, 0xFF, 0xFF, 30)
        g.fill(RoundRectangle2D.Float(badgeX.toFloat(), top + 6f, badgeWidth.toFloat(), 42f, 21f, 21f))
        g.color = COLOR_BADGE
        g.drawString(badgeText, (badgeX + 22).toFloat(), top + 34f)
    }

    /** 玩家行：排名徽章 + 头像 + 玩家名 + 右侧总在线时长
     * （行容器背后铺局部模糊，容器内的行底色不再重复模糊）。 */
    private fun drawRows(g: Graphics2D, backdrop: InfoCardAssets.CardBackdropContext?, entries: List<LeaderEntry>, firstPage: Boolean) {
        val contentWidth = WIDTH - 2 * MARGIN
        val gridTop = if (firstPage) {
            MARGIN + HEADER_HEIGHT + HEADER_GAP + SUBTITLE_HEIGHT + SUBTITLE_GAP + LIST_HEADER_HEIGHT + LIST_HEADER_GAP
        } else {
            PAGE_TOP_PAD
        }

        // 行容器深色底（整块列表区域裹一层半透明深色底，与在线列表统一）
        val gridHeight = entries.size * ROW_HEIGHT + (entries.size - 1) * ROW_GAP
        backdrop?.paintCard(g, MARGIN.toFloat(), (gridTop - 14).toFloat(), contentWidth.toFloat(), (gridHeight + 28).toFloat(), 18f)
        // 1.7.2：项目卡片底色黑度可配（render.item-card-gray，默认 106）
        g.color = Color(0x00, 0x00, 0x00, RenderSettings.itemCardGray())
        g.fill(RoundRectangle2D.Float(
            MARGIN.toFloat(), (gridTop - 14).toFloat(),
            contentWidth.toFloat(), (gridHeight + 28).toFloat(), 18f, 18f))

        entries.forEachIndexed { index, entry ->
            val rowY = gridTop + index * (ROW_HEIGHT + ROW_GAP)

            // 行底色
            g.color = Color(0xFF, 0xFF, 0xFF, 16)
            g.fill(RoundRectangle2D.Float(MARGIN.toFloat(), rowY.toFloat(), contentWidth.toFloat(), ROW_HEIGHT.toFloat(), 14f, 14f))

            // 排名徽章（前三名金 / 银 / 铜）
            val badgeSize = 52
            val badgeX = MARGIN + 20
            val badgeY = rowY + (ROW_HEIGHT - badgeSize) / 2
            g.color = Color(rankColor(entry.rank).red, rankColor(entry.rank).green, rankColor(entry.rank).blue, 235)
            g.fill(RoundRectangle2D.Float(badgeX.toFloat(), badgeY.toFloat(), badgeSize.toFloat(), badgeSize.toFloat(), 14f, 14f))
            g.font = InfoCardRenderer.font(if (entry.rank >= 100) 24f else 28f, true)
            g.color = if (entry.rank <= 3) Color(0x1A, 0x1A, 0x22) else Color.WHITE
            val rankText = entry.rank.toString()
            val rankWidth = g.getFontMetrics().stringWidth(rankText)
            g.drawString(rankText, (badgeX + (badgeSize - rankWidth) / 2).toFloat(), (badgeY + badgeSize / 2 + 10).toFloat())

            // 头像（无皮肤数据画占位块：深色圆角 + 名字首字符）；
            // 1.6.6.1 覆盖版：圆角头像零拷贝直绘（不再先拷一张圆角小图）
            val avatarX = badgeX + badgeSize + 16
            val avatarY = rowY + (ROW_HEIGHT - AVATAR_SIZE) / 2
            if (entry.avatar != null) {
                InfoCardRenderer.drawRoundedAvatar(g, entry.avatar, avatarX, avatarY, AVATAR_SIZE, 12)
                g.color = Color(0xFF, 0xFF, 0xFF, 170)
                g.stroke = BasicStroke(1.5f)
                g.draw(RoundRectangle2D.Float(avatarX + 0.5f, avatarY + 0.5f, (AVATAR_SIZE - 1).toFloat(), (AVATAR_SIZE - 1).toFloat(), 12f, 12f))
            } else {
                g.color = Color(0x2A, 0x34, 0x44, 230)
                g.fill(RoundRectangle2D.Float(avatarX.toFloat(), avatarY.toFloat(), AVATAR_SIZE.toFloat(), AVATAR_SIZE.toFloat(), 12f, 12f))
                val first = entry.name.take(1)
                g.font = InfoCardRenderer.font(28f, true)
                g.color = COLOR_LABEL
                val charWidth = g.getFontMetrics().stringWidth(first)
                g.drawString(first, (avatarX + (AVATAR_SIZE - charWidth) / 2).toFloat(), (avatarY + AVATAR_SIZE / 2 + 10).toFloat())
            }

            // 玩家名（垂直居中，超宽截断）
            val nameX = avatarX + AVATAR_SIZE + 16
            val timeText = entry.valueText ?: formatPlayTime(entry.playSeconds)
            g.font = InfoCardRenderer.font(26f, false)
            val timeWidth = g.getFontMetrics().stringWidth(timeText)
            val timeX = WIDTH - MARGIN - 28 - timeWidth
            val nameMaxWidth = timeX - nameX - 24
            g.font = InfoCardRenderer.font(28f, true)
            g.color = COLOR_VALUE
            g.drawString(clip(g, entry.name, nameMaxWidth), nameX.toFloat(), (rowY + ROW_HEIGHT / 2 + 10).toFloat())

            // 右侧数值（总在线时长或金币金额；1.6.0 起可由调用方自定义文本）
            g.font = InfoCardRenderer.font(26f, false)
            g.color = COLOR_LABEL
            g.drawString(timeText, timeX.toFloat(), (rowY + ROW_HEIGHT / 2 + 10).toFloat())
        }
    }

    /** 空数据提示卡（容器背后铺局部模糊）。 */
    private fun drawEmpty(g: Graphics2D, backdrop: InfoCardAssets.CardBackdropContext?, firstPage: Boolean) {
        val gridTop = if (firstPage) {
            MARGIN + HEADER_HEIGHT + HEADER_GAP + SUBTITLE_HEIGHT + SUBTITLE_GAP + LIST_HEADER_HEIGHT + LIST_HEADER_GAP
        } else {
            PAGE_TOP_PAD
        }
        val cardWidth = WIDTH - 2 * MARGIN
        backdrop?.paintCard(g, MARGIN.toFloat(), (gridTop - 14).toFloat(), cardWidth.toFloat(), (EMPTY_HEIGHT + 28).toFloat(), 18f)
        g.color = Color(0x00, 0x00, 0x00, RenderSettings.itemCardGray())
        g.fill(RoundRectangle2D.Float(MARGIN.toFloat(), (gridTop - 14).toFloat(), cardWidth.toFloat(), (EMPTY_HEIGHT + 28).toFloat(), 18f, 18f))
        g.color = Color(0xFF, 0xFF, 0xFF, 16)
        g.fill(RoundRectangle2D.Float(MARGIN.toFloat(), gridTop.toFloat(), cardWidth.toFloat(), EMPTY_HEIGHT.toFloat(), 18f, 18f))
        g.font = InfoCardRenderer.font(30f, true)
        g.color = COLOR_LABEL
        val text = "暂无统计数据"
        val width = g.getFontMetrics().stringWidth(text)
        g.drawString(text, (MARGIN + (cardWidth - width) / 2).toFloat(), (gridTop + EMPTY_HEIGHT / 2 + 10).toFloat())
    }

    /** 截取不超给定宽度的最长前缀（与其他卡片一致的截断策略）。 */
    private fun clip(g: Graphics2D, text: String, maxWidth: Int): String {
        if (maxWidth <= 4) return "…"
        val metrics = g.getFontMetrics()
        if (metrics.stringWidth(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) > maxWidth) end--
        return if (end == 0) "…" else text.substring(0, end).dropLast(1) + "…"
    }
}
