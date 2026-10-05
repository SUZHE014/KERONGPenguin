package cn.huohuas001.huhobotPenguin.spigot.render

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
 * 每名玩家一行：排名徽章（前三名金 / 银 / 铜配色）+ 真实皮肤头像 + 玩家名 +
 * 右侧总在线时长；背景与在线列表 / 查信息卡片共用 img/ 目录随机图。
 *
 * 分段发送（1.5.5）：每页最多 [ROWS_PER_PAGE] 行 —— 第一页带完整标题区
 * （眉题 + 主标题 + 右侧统计卡 + 列表头），后续页只绘制行内容不再绘制标题，
 * 由编排层逐张发送。
 *
 * 字体 / 圆角头像 / 截断策略与 [OnlineListRenderer] / [InfoCardRenderer] 同源；
 * 画布 PNG 写出后立即 flush 释放栅格内存（与其他渲染器一致）。
 */
object LeaderboardRenderer {

    /** 画布宽度。 */
    internal const val WIDTH = 1200

    /** 每页最大行数（超出由编排层切页分段发送）。 */
    const val ROWS_PER_PAGE = 20

    // ---------- 布局常量 ----------

    /** 页边距。 */
    private const val MARGIN = 40

    /** 续页顶部留白（无标题区，顶部收窄）。 */
    private const val PAGE_TOP_PAD = 24

    /** 第一页标题区高度（眉题 + 主标题 + 右侧统计卡）。 */
    private const val HEADER_HEIGHT = 150

    /** 标题区与列表头间距。 */
    private const val HEADER_GAP = 18

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
         * 预处理背景（含暗色遮罩；尺寸可与画布不一致，渲染时拉伸铺满）；
         * null 表示无背景。
         */
        val background: BufferedImage? = null,
    )

    /**
     * 按本页行数计算画布高度：第一页含标题区，续页只含行内容。
     * 必须在取背景（尺寸相关）与渲染前调用，两者共用本结果。
     */
    fun measurePageHeight(rowCount: Int, firstPage: Boolean): Int {
        val gridHeight = if (rowCount <= 0) {
            EMPTY_HEIGHT
        } else {
            rowCount * ROW_HEIGHT + (rowCount - 1) * ROW_GAP
        }
        val total = if (firstPage) {
            MARGIN + HEADER_HEIGHT + HEADER_GAP + LIST_HEADER_HEIGHT + LIST_HEADER_GAP + gridHeight + BOTTOM_PAD
        } else {
            PAGE_TOP_PAD + gridHeight + BOTTOM_PAD
        }
        return total.coerceAtLeast(if (firstPage) 640 else 200)
    }

    /** 渲染单页并返回 PNG 字节（首页带标题区，续页只画行内容）。 */
    fun renderPage(data: LeaderboardData, firstPage: Boolean): ByteArray {
        val height = measurePageHeight(data.entries.size, firstPage)
        val image = BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            drawBackground(g, data.background, height)
            if (firstPage) {
                drawHeader(g, data.totalPlayers)
                drawListHeader(g, data.topLimit, data.totalPlayers)
            }
            if (data.entries.isEmpty()) {
                drawEmpty(g, firstPage)
            } else {
                drawRows(g, data.entries, firstPage)
            }
        } finally {
            g.dispose()
        }
        val output = ByteArrayOutputStream(384 * 1024)
        ImageIO.write(image, "png", output)
        // PNG 已写出，立即释放单次画布的栅格内存
        image.flush()
        return output.toByteArray()
    }

    /**
     * 秒数格式化为时长文本（与查信息卡片「在线时长」同风格）：
     * 天 / 时 / 分组合，全零时显示「暂无记录」。
     */
    fun formatPlayTime(seconds: Long): String {
        if (seconds <= 0) return "暂无记录"
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return buildString {
            if (days > 0) append("${days}天")
            if (hours > 0) append("${hours}小时")
            if (minutes > 0) append("${minutes}分钟")
            if (isEmpty()) append("不足1分钟")
        }
    }

    /** 绘制背景：任意尺寸背景双线性拉伸铺满（与在线列表一致）；无图深色渐变。 */
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

    /** 第一页标题区：眉题 + 主标题 + 右侧统计卡。 */
    private fun drawHeader(g: Graphics2D, totalPlayers: Long) {
        val top = MARGIN

        // 英文眉题（字距拉宽，与其他卡片统一风格）
        g.font = InfoCardRenderer.font(26f, true)
        g.color = COLOR_EYEBROW
        g.drawString("M I N E C R A F T   S E R V E R", (MARGIN + 8).toFloat(), (top + 38).toFloat())

        // 主标题
        g.font = InfoCardRenderer.font(58f, true)
        g.color = COLOR_VALUE
        g.drawString("在线排行榜", (MARGIN + 4).toFloat(), (top + 108).toFloat())

        // 右侧统计卡（参与排行玩家总数）
        val cardWidth = 260
        val cardHeight = HEADER_HEIGHT - 16
        val cardX = WIDTH - MARGIN - cardWidth
        val cardY = top + 8
        g.color = Color(0xFF, 0xFF, 0xFF, 34)
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

    /** 第一页列表头：标题 + 右侧人数胶囊。 */
    private fun drawListHeader(g: Graphics2D, topLimit: Int, totalPlayers: Long) {
        val top = (MARGIN + HEADER_HEIGHT + HEADER_GAP).toFloat()

        g.font = InfoCardRenderer.font(34f, true)
        g.color = COLOR_VALUE
        g.drawString("总在线时长 TOP $topLimit", (MARGIN + 4).toFloat(), top + 40f)

        g.font = InfoCardRenderer.font(24f, false)
        val badgeText = "$totalPlayers 名玩家"
        val badgeWidth = g.getFontMetrics().stringWidth(badgeText) + 44
        val badgeX = WIDTH - MARGIN - badgeWidth
        g.color = Color(0xFF, 0xFF, 0xFF, 30)
        g.fill(RoundRectangle2D.Float(badgeX.toFloat(), top + 6f, badgeWidth.toFloat(), 42f, 21f, 21f))
        g.color = COLOR_BADGE
        g.drawString(badgeText, (badgeX + 22).toFloat(), top + 34f)
    }

    /** 玩家行：排名徽章 + 头像 + 玩家名 + 右侧总在线时长。 */
    private fun drawRows(g: Graphics2D, entries: List<LeaderEntry>, firstPage: Boolean) {
        val contentWidth = WIDTH - 2 * MARGIN
        val gridTop = if (firstPage) {
            MARGIN + HEADER_HEIGHT + HEADER_GAP + LIST_HEADER_HEIGHT + LIST_HEADER_GAP
        } else {
            PAGE_TOP_PAD
        }

        // 行容器深色底（整块列表区域裹一层半透明深色底，与在线列表统一）
        val gridHeight = entries.size * ROW_HEIGHT + (entries.size - 1) * ROW_GAP
        g.color = Color(0x00, 0x00, 0x00, 120)
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

            // 头像（无皮肤数据画占位块：深色圆角 + 名字首字符）
            val avatarX = badgeX + badgeSize + 16
            val avatarY = rowY + (ROW_HEIGHT - AVATAR_SIZE) / 2
            if (entry.avatar != null) {
                val rounded = roundImage(entry.avatar, AVATAR_SIZE)
                g.drawImage(rounded, null, avatarX, avatarY)
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
            val timeText = formatPlayTime(entry.playSeconds)
            g.font = InfoCardRenderer.font(26f, false)
            val timeWidth = g.getFontMetrics().stringWidth(timeText)
            val timeX = WIDTH - MARGIN - 28 - timeWidth
            val nameMaxWidth = timeX - nameX - 24
            g.font = InfoCardRenderer.font(28f, true)
            g.color = COLOR_VALUE
            g.drawString(clip(g, entry.name, nameMaxWidth), nameX.toFloat(), (rowY + ROW_HEIGHT / 2 + 10).toFloat())

            // 总在线时长（右侧对齐）
            g.font = InfoCardRenderer.font(26f, false)
            g.color = COLOR_LABEL
            g.drawString(timeText, timeX.toFloat(), (rowY + ROW_HEIGHT / 2 + 10).toFloat())
        }
    }

    /** 空数据提示卡。 */
    private fun drawEmpty(g: Graphics2D, firstPage: Boolean) {
        val gridTop = if (firstPage) {
            MARGIN + HEADER_HEIGHT + HEADER_GAP + LIST_HEADER_HEIGHT + LIST_HEADER_GAP
        } else {
            PAGE_TOP_PAD
        }
        val cardWidth = WIDTH - 2 * MARGIN
        g.color = Color(0x00, 0x00, 0x00, 120)
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

    /** 圆角头像（与其他卡片一致的圆角处理）。 */
    private fun roundImage(source: BufferedImage, size: Int): BufferedImage {
        val result = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = result.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.clip(RoundRectangle2D.Float(0f, 0f, size.toFloat(), size.toFloat(), 12f, 12f))
            g.drawImage(source, 0, 0, size, size, null)
        } finally {
            g.dispose()
        }
        return result
    }
}
