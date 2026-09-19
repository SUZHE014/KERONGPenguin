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
 * 在线玩家列表卡片渲染器（/查在线 图片输出，1.5.0 覆盖更新新增）。
 *
 * 按用户提供的模板复刻毛玻璃深色风格（1350 宽，高度随在线人数动态加长）：
 * - 顶部标题区：英文眉题 MINECRAFT SERVER + 主标题"服务器在线列表" +
 *   右侧"在线 N"人数卡片；
 * - 状态栏：服务器 / 更新时间 / 状态 三张并排小卡；
 * - 列表头：在线玩家 + "N 人在线"胶囊徽标；
 * - 玩家网格：3 列，每格 = 头像（真实 MC 皮肤正面）+ 玩家名，超长截断；
 * - 人数多时高度自动加长（长图，一图展示全部玩家，无需翻页）；
 * - 画布 PNG 写出后立即 flush 释放栅格内存（与个人信息卡片一致）。
 *
 * 字体 / 配色与 [InfoCardRenderer] 同源（复用其字体缓存与毛玻璃色板），
 * 背景同样取自插件目录 img/ 随机挑选（[InfoCardAssets.processedBackground]）。
 *
 * 1.5.1：背景不再要求与画布等尺寸 —— 长图背景处理高度封顶
 * （[InfoCardAssets.ONLINE_BACKGROUND_MAX_HEIGHT]），渲染时直接拉伸铺满整个画布
 * （裁剪/拉伸自适应，模糊背景下视觉无差异），既保留随机背景效果又避免
 * 大画布背景栅格膨胀到数十 MB。
 */
object OnlineListRenderer {

    /** 画布宽度（与用户模板一致）。 */
    internal const val WIDTH = 1350

    // ---------- 布局常量（模板：3 列玩家网格） ----------

    /** 页边距。 */
    private const val MARGIN = 40

    /** 顶部标题区高度（眉题 + 主标题 + 右侧人数卡）。 */
    private const val HEADER_HEIGHT = 150

    /** 标题区与状态栏间距。 */
    private const val HEADER_GAP = 18

    /** 状态栏高度（服务器 / 更新时间 / 状态 三张小卡）。 */
    private const val STATUS_HEIGHT = 84

    /** 状态栏与列表头间距。 */
    private const val STATUS_GAP = 18

    /** 列表头高度。 */
    private const val LIST_HEADER_HEIGHT = 56

    /** 列表头与玩家网格间距。 */
    private const val LIST_HEADER_GAP = 14

    /** 玩家卡片高度。 */
    private const val CELL_HEIGHT = 84

    /** 玩家卡片间距（横向与纵向相同）。 */
    private const val CELL_GAP = 12

    /** 玩家网格列数。 */
    private const val COLUMNS = 3

    /** 头像尺寸。 */
    private const val AVATAR_SIZE = 56

    /** 底部留白。 */
    private const val BOTTOM_PAD = 40

    /** 空列表提示卡高度。 */
    private const val EMPTY_HEIGHT = 120

    // ---------- 配色（毛玻璃深色系，与个人信息卡片同源） ----------

    private val COLOR_EYEBROW = Color(0x8A, 0x9B, 0xAE)
    private val COLOR_LABEL = Color(0x9F, 0xB3, 0xC8)
    private val COLOR_VALUE = Color.WHITE
    private val COLOR_BADGE = Color(0xA8, 0xBA, 0xCC)

    /** 单个玩家条目（头像可为 null：无皮肤数据时画占位块）。 */
    data class OnlineEntry(val name: String, val avatar: BufferedImage? = null)

    /** 渲染入参。 */
    data class OnlineListData(
        /** 服务器名称（状态栏与列表头展示）。 */
        val serverName: String,
        /** 更新时间（HH:mm，调用方格式化好传入）。 */
        val updateTime: String,
        /** 在线玩家（顺序即展示顺序）。 */
        val entries: List<OnlineEntry>,
        /**
         * 预处理背景（含模糊与暗化；1.5.1 起尺寸可与画布不一致，渲染时拉伸铺满）；
         * null 表示无背景。
         */
        val background: BufferedImage? = null,
    )

    /**
     * 按玩家数计算画布高度：人数多时自动加长为长图（一图展示全部玩家）。
     * 必须在取背景（尺寸相关）与渲染前调用，两者共用本结果。
     */
    fun measureHeight(playerCount: Int): Int {
        val rows = if (playerCount <= 0) 0 else (playerCount + COLUMNS - 1) / COLUMNS
        val gridHeight = if (rows <= 0) {
            EMPTY_HEIGHT
        } else {
            rows * CELL_HEIGHT + (rows - 1) * CELL_GAP
        }
        val total = MARGIN + HEADER_HEIGHT + HEADER_GAP + STATUS_HEIGHT + STATUS_GAP +
            LIST_HEADER_HEIGHT + LIST_HEADER_GAP + gridHeight + BOTTOM_PAD
        return total.coerceAtLeast(640)
    }

    /** 渲染在线列表卡片并返回 PNG 字节。 */
    fun render(data: OnlineListData): ByteArray {
        val height = measureHeight(data.entries.size)
        val image = BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            drawBackground(g, data.background, height)
            drawHeader(g, data.entries.size)
            drawStatusBar(g, data.serverName, data.updateTime)
            drawListHeader(g, data.entries.size)
            drawPlayerGrid(g, data.entries)
        } finally {
            g.dispose()
        }
        val output = ByteArrayOutputStream(384 * 1024)
        ImageIO.write(image, "png", output)
        // PNG 已写出，立即释放单次画布的栅格内存（缓存中的背景/头像不受影响）
        image.flush()
        return output.toByteArray()
    }

    /**
     * 绘制背景（1.5.1）：任意尺寸背景直接拉伸铺满整个画布
     * （长图背景高度封顶后仍能覆盖全画布）；无图片时深色渐变。
     */
    private fun drawBackground(g: Graphics2D, background: BufferedImage?, height: Int) {
        if (background != null && background.width > 0 && background.height > 0) {
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

    /** 顶部标题区：眉题 + 主标题 + 右侧在线人数卡。 */
    private fun drawHeader(g: Graphics2D, onlineCount: Int) {
        val top = MARGIN

        // 英文眉题（字距拉宽）
        g.font = InfoCardRenderer.font(26f, true)
        g.color = COLOR_EYEBROW
        g.drawString("M I N E C R A F T   S E R V E R", (MARGIN + 8).toFloat(), (top + 38).toFloat())

        // 主标题
        g.font = InfoCardRenderer.font(58f, true)
        g.color = COLOR_VALUE
        g.drawString("服务器在线列表", (MARGIN + 4).toFloat(), (top + 108).toFloat())

        // 右侧在线人数卡
        val cardWidth = 240
        val cardHeight = HEADER_HEIGHT - 16
        val cardX = WIDTH - MARGIN - cardWidth
        val cardY = top + 8
        g.color = Color(0xFF, 0xFF, 0xFF, 34)
        g.fill(RoundRectangle2D.Float(cardX.toFloat(), cardY.toFloat(), cardWidth.toFloat(), cardHeight.toFloat(), 20f, 20f))
        g.font = InfoCardRenderer.font(24f, false)
        g.color = COLOR_LABEL
        g.drawString("在线", (cardX + 28).toFloat(), (cardY + 42).toFloat())
        g.font = InfoCardRenderer.font(64f, true)
        g.color = COLOR_VALUE
        val countText = onlineCount.toString()
        val countWidth = g.getFontMetrics().stringWidth(countText)
        g.drawString(countText, (cardX + cardWidth - 28 - countWidth).toFloat(), (cardY + 100).toFloat())
    }

    /** 状态栏：服务器 / 更新时间 / 状态 三张并排小卡。 */
    private fun drawStatusBar(g: Graphics2D, serverName: String, updateTime: String) {
        val top = (MARGIN + HEADER_HEIGHT + HEADER_GAP).toFloat()
        val gap = 16f
        val cardWidth = (WIDTH - 2 * MARGIN - 2 * gap).toFloat() / 3f
        val cardHeight = STATUS_HEIGHT.toFloat()

        val cells = listOf(
            "服务器" to serverName,
            "更新时间" to updateTime,
            "状态" to "正常",
        )
        cells.forEachIndexed { index, (label, value) ->
            val x = MARGIN + index * (cardWidth + gap)
            g.color = Color(0x00, 0x00, 0x00, 120)
            g.fill(RoundRectangle2D.Float(x, top, cardWidth, cardHeight, 14f, 14f))
            g.font = InfoCardRenderer.font(22f, false)
            g.color = COLOR_LABEL
            g.drawString(label, x + 26f, top + 34f)
            g.font = InfoCardRenderer.font(30f, true)
            g.color = COLOR_VALUE
            g.drawString(clip(g, value, (cardWidth - 52).toInt()), x + 26f, top + 68f)
        }
    }

    /** 列表头：标题 + 右侧"N 人在线"胶囊。 */
    private fun drawListHeader(g: Graphics2D, onlineCount: Int) {
        val top = (MARGIN + HEADER_HEIGHT + HEADER_GAP + STATUS_HEIGHT + STATUS_GAP).toFloat()

        g.font = InfoCardRenderer.font(34f, true)
        g.color = COLOR_VALUE
        g.drawString("在线玩家", (MARGIN + 4).toFloat(), top + 40f)

        g.font = InfoCardRenderer.font(24f, false)
        val badgeText = "$onlineCount 人在线"
        val badgeWidth = g.getFontMetrics().stringWidth(badgeText) + 44
        val badgeX = WIDTH - MARGIN - badgeWidth
        g.color = Color(0xFF, 0xFF, 0xFF, 30)
        g.fill(RoundRectangle2D.Float(badgeX.toFloat(), top + 6f, badgeWidth.toFloat(), 42f, 21f, 21f))
        g.color = COLOR_BADGE
        g.drawString(badgeText, (badgeX + 22).toFloat(), top + 34f)
    }

    /** 玩家网格：3 列，每格头像 + 玩家名；空列表画提示卡；网格整体套深色容器（与模板一致）。 */
    private fun drawPlayerGrid(g: Graphics2D, entries: List<OnlineEntry>) {
        val gridTop = MARGIN + HEADER_HEIGHT + HEADER_GAP + STATUS_HEIGHT + STATUS_GAP +
            LIST_HEADER_HEIGHT + LIST_HEADER_GAP
        if (entries.isEmpty()) {
            val cardWidth = WIDTH - 2 * MARGIN
            g.color = Color(0x00, 0x00, 0x00, 120)
            g.fill(RoundRectangle2D.Float(MARGIN.toFloat(), (gridTop - 14).toFloat(), cardWidth.toFloat(), (EMPTY_HEIGHT + 28).toFloat(), 18f, 18f))
            g.color = Color(0xFF, 0xFF, 0xFF, 16)
            g.fill(RoundRectangle2D.Float(MARGIN.toFloat(), gridTop.toFloat(), cardWidth.toFloat(), EMPTY_HEIGHT.toFloat(), 18f, 18f))
            g.font = InfoCardRenderer.font(30f, true)
            g.color = COLOR_LABEL
            val text = "当前没有玩家在线"
            val width = g.getFontMetrics().stringWidth(text)
            g.drawString(text, (MARGIN + (cardWidth - width) / 2).toFloat(), (gridTop + EMPTY_HEIGHT / 2 + 10).toFloat())
            return
        }

        val contentWidth = WIDTH - 2 * MARGIN
        val cellWidth = (contentWidth - (COLUMNS - 1) * CELL_GAP) / COLUMNS
        val rows = (entries.size + COLUMNS - 1) / COLUMNS
        val gridHeight = rows * CELL_HEIGHT + (rows - 1) * CELL_GAP

        // 网格深色容器（模板风格：整块列表区域裹一层半透明深色底）
        g.color = Color(0x00, 0x00, 0x00, 120)
        g.fill(RoundRectangle2D.Float(
            MARGIN.toFloat(), (gridTop - 14).toFloat(),
            contentWidth.toFloat(), (gridHeight + 28).toFloat(), 18f, 18f))

        entries.forEachIndexed { index, entry ->
            val col = index % COLUMNS
            val row = index / COLUMNS
            val cellX = MARGIN + col * (cellWidth + CELL_GAP)
            val cellY = gridTop + row * (CELL_HEIGHT + CELL_GAP)

            // 单元格底色
            g.color = Color(0xFF, 0xFF, 0xFF, 16)
            g.fill(RoundRectangle2D.Float(cellX.toFloat(), cellY.toFloat(), cellWidth.toFloat(), CELL_HEIGHT.toFloat(), 14f, 14f))

            // 头像（无皮肤数据画占位块：深色圆角 + 名字首字符）
            val avatarX = cellX + 16
            val avatarY = cellY + (CELL_HEIGHT - AVATAR_SIZE) / 2
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
            g.font = InfoCardRenderer.font(28f, true)
            g.color = COLOR_VALUE
            val nameMaxWidth = cellWidth - 16 - AVATAR_SIZE - 16 - 12
            g.drawString(clip(g, entry.name, nameMaxWidth), (cellX + 16 + AVATAR_SIZE + 12).toFloat(), (cellY + CELL_HEIGHT / 2 + 10).toFloat())
        }
    }

    /** 截取不超给定宽度的最长前缀（复用个人信息卡片的截断策略）。 */
    private fun clip(g: Graphics2D, text: String, maxWidth: Int): String {
        val metrics = g.getFontMetrics()
        if (metrics.stringWidth(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) > maxWidth) end--
        return text.substring(0, end).dropLast(1) + "…"
    }

    /** 圆角头像（与个人信息卡片一致的圆角处理）。 */
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
