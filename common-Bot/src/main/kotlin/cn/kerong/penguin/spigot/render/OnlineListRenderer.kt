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
 * 在线玩家列表卡片渲染器（/查在线 图片输出，1.5.0 覆盖更新新增）。
 *
 * 设计参考（issue #1）：图片渲染界面参考自 BaiZhouziYou / 新亭泪
 * 发布在 EasyBot 资源市场的图片模板（https://xintinglei.cn/）——早期版本
 * 的毛玻璃深色风格模板是参照该模板图复刻的，其后的分页、背景、头像渲染
 * 等为本项目的独立改进。感谢原作者的设计。
 *
 * 毛玻璃深色风格（1350 宽，高度随在线人数动态加长）：
 * - 顶部标题区：英文眉题 MINECRAFT SERVER + 主标题"服务器在线列表" +
 *   右侧"在线 N"人数卡片；
 * - 状态栏：服务器 / 更新时间 / 状态 三张并排小卡；
 * - 列表头：在线玩家 + "N 人在线"胶囊徽标；
 * - 玩家网格：3 列，每格 = 头像（真实 MC 皮肤正面）+ 玩家名，超长截断；
 * - 分段发送（1.5.5）：超过 [MAX_PLAYERS_PER_PAGE] 人自动切页 —— 第一张带完整
 *   标题区（眉题 / 主标题 / 人数卡 / 状态栏 / 列表头），后续各张只绘制玩家
 *   网格内容，不再绘制标题；由编排层逐张发送；
 * - 画布 PNG 写出后立即 flush 释放栅格内存（与个人信息卡片一致）。
 *
 * 字体 / 配色与 [InfoCardRenderer] 同源（复用其字体缓存与毛玻璃色板），
 * 背景同样取自插件目录 img/ 随机挑选（[InfoCardAssets.processedBackground]）。
 *
 * 1.5.1：背景不再要求与画布等尺寸 —— 长图背景处理高度封顶
 * （[InfoCardAssets.ONLINE_BACKGROUND_MAX_HEIGHT]），渲染时按 cover
 * 裁剪铺满整个画布（1.6.9.5 起不再拉伸：等比放大不足的边 + 图片中心
 * 裁剪，见 [InfoCardAssets.drawBackground]），既保留随机背景效果又避免
 * 大画布背景栅格膨胀到数十 MB。
 *
 * 卡片背后区域
 * 铺背景模糊副本（backdrop-filter 局部模糊，卡片外背景保持清晰）。
 *
 * 1.5.5：分段发送改造 —— 长图改为分页多张（每页 [MAX_PLAYERS_PER_PAGE] 人），
 * 首页带标题、续页只画内容，避免超长图在 QQ 客户端被压缩得看不清。
 */
object OnlineListRenderer {

    /** 画布宽度（与用户模板一致）。 */
    internal const val WIDTH = 1350

    // ---------- 布局常量（模板：3 列玩家网格） ----------

    /** 页边距。 */
    private const val MARGIN = 40

    /** 顶部标题区高度（眉题 + 主标题 + 右侧人数卡；1.7.3 覆盖版二轮：150 → 164；
     * 1.7.3 覆盖版三轮：164 → 182，眉题视觉顶边距 27 → 45px——对齐查信息
     * 欢迎面板（最上框参照标准，实测 45px），用户反馈二轮 27px 仍太贴边；
     * +18 全部给顶部，底边距 42px 与眉题→主标题间距保持不变）。 */
    private const val HEADER_HEIGHT = 182

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

    /** 每页最大玩家数（3 列 × 20 行；超出由 [renderPages] 切页分段发送，1.5.5）。 */
    const val MAX_PLAYERS_PER_PAGE = 60

    /** 续页顶部留白（无标题区，顶部收窄）。 */
    private const val PAGE_TOP_PAD = 24

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
         * 预处理背景（含暗色遮罩；尺寸可与画布不一致，渲染时按 cover
         * 裁剪铺满，1.6.9.5 起不再拉伸）；null 表示无背景。
         */
        val background: BufferedImage? = null,
    )

    /**
     * 按本页玩家数计算画布高度：第一页含完整标题区，续页只含玩家网格。
     * 必须在取背景（尺寸相关）与渲染前调用，两者共用本结果。
     */
    fun measurePageHeight(playerCount: Int, firstPage: Boolean): Int {
        val rows = if (playerCount <= 0) 0 else (playerCount + COLUMNS - 1) / COLUMNS
        val gridHeight = if (rows <= 0) {
            EMPTY_HEIGHT
        } else {
            rows * CELL_HEIGHT + (rows - 1) * CELL_GAP
        }
        val total = if (firstPage) {
            MARGIN + HEADER_HEIGHT + HEADER_GAP + STATUS_HEIGHT + STATUS_GAP +
                LIST_HEADER_HEIGHT + LIST_HEADER_GAP + gridHeight + BOTTOM_PAD
        } else {
            PAGE_TOP_PAD + gridHeight + BOTTOM_PAD
        }
        return total.coerceAtLeast(if (firstPage) 640 else 200)
    }

    /** 兼容入口（1.5.5 前的单一长图高度计算）：单页场景等价于首页高度。 */
    fun measureHeight(playerCount: Int): Int = measurePageHeight(playerCount, true)

    /**
     * 渲染在线列表卡片并返回 PNG 字节列表（1.5.5 分段发送）：
     * 不超过 [MAX_PLAYERS_PER_PAGE] 人时返回单张；超出自动切页 ——
     * 第一张带完整标题区，后续各张只绘制玩家网格（不显示标题）。
     */
    fun renderPages(data: OnlineListData): List<ByteArray> {
        val all = data.entries
        if (all.size <= MAX_PLAYERS_PER_PAGE) {
            val bytes = renderSingle(data, all, true)
            return if (bytes.isEmpty()) emptyList() else listOf(bytes)
        }
        val pages = ArrayList<ByteArray>(4)
        var start = 0
        while (start < all.size) {
            val end = minOf(start + MAX_PLAYERS_PER_PAGE, all.size)
            val bytes = renderSingle(data, all.subList(start, end), start == 0)
            if (bytes.isNotEmpty()) pages.add(bytes)
            start = end
        }
        return pages
    }

    /** 渲染单页（首页带标题区，续页只画玩家网格；1.7.3 覆盖版：首页副标题条
     * 第三格改「渲染耗时」——从开始渲染到本页全部面板绘制完成的毫秒数，
     * 绘制完成后补画；注意从开始渲染图片起算，不含接收指令后的取数 /
     * 头像预热 / 排队环节）。 */
    private fun renderSingle(data: OnlineListData, pageEntries: List<OnlineEntry>, firstPage: Boolean): ByteArray {
        // 1.7.3 覆盖版：渲染耗时计时起点（全部面板绘制完成后补画进副标题条）
        val renderStart = System.nanoTime()
        val height = measurePageHeight(pageEntries.size, firstPage)
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
                drawHeader(g, backdrop, data.entries.size)
                drawStatusBar(g, image, backdrop, data.serverName, data.updateTime)
                drawListHeader(g, data.entries.size)
            }
            drawPlayerGrid(g, backdrop, pageEntries, firstPage)

            // 1.7.3 覆盖版：补画渲染耗时（全部面板已画完；PNG 编码与发送不预先包含）
            if (firstPage) {
                val elapsedMs = (System.nanoTime() - renderStart) / 1_000_000L
                drawRenderElapsed(g, elapsedMs)
            }
        } finally {
            g.dispose()
        }
        val output = ByteArrayOutputStream(384 * 1024)
        ImageIO.write(image, "png", output)
        // PNG 已写出，立即释放单次画布的栅格内存（缓存中的背景/头像不受影响）
        image.flush()
        return output.toByteArray()
    }

    /** 顶部标题区：眉题 + 主标题 + 右侧在线人数卡（1.7.3：整块标题卡 —— 背后铺
     * 局部模糊 + 标题框黑度 + 圆角边缘，与查信息顶部欢迎区同款；旧版标题
     * 文字直画在背景上，看不出卡片边界，灰度也只作用于右侧小方框；
     * 1.7.3 覆盖版二轮：眉题 / 主标题基线下移 8px（上边缘间距加大），
     * 右上角小方框与标题框四周留 14px 可见间距——旧版 8/8/0 太贴边；
     * 1.7.3 覆盖版三轮：眉题 / 主标题基线再下移 18px（顶边距 27 → 45px
     * 对齐查信息最上框），副标题框零改动——用户口径：间距调的是主标题框）。 */
    private fun drawHeader(g: Graphics2D, backdrop: InfoCardAssets.CardBackdropContext?, onlineCount: Int) {
        val top = MARGIN

        // 1.7.3：全宽标题卡（模糊 + 灰度 + 圆角边缘一次到位）
        val headerWidth = (WIDTH - 2 * MARGIN).toFloat()
        backdrop?.paintCard(g, MARGIN.toFloat(), top.toFloat(), headerWidth, HEADER_HEIGHT.toFloat(), 24f)
        g.color = Color(0x00, 0x00, 0x00, RenderSettings.titleCardGray())
        g.fill(RoundRectangle2D.Float(MARGIN.toFloat(), top.toFloat(), headerWidth, HEADER_HEIGHT.toFloat(), 24f, 24f))

        // 英文眉题（字距拉宽；1.7.3 覆盖版：距卡左缘 28px，与查信息欢迎面板一致；
        // 1.7.3 覆盖版三轮：基线 46 → 64，上边缘视觉间距 27 → 45px，
        // 对齐查信息欢迎面板眉题顶边距——二轮 27px 用户反馈仍偏贴）
        g.font = InfoCardRenderer.font(26f, true)
        g.color = COLOR_EYEBROW
        g.drawString("M I N E C R A F T   S E R V E R", (MARGIN + 32).toFloat(), (top + 64).toFloat())

        // 主标题（距卡左缘 24px；1.7.3 覆盖版三轮：基线 118 → 136，与眉题
        // 同步 +18——眉题→主标题间距与底边距 42px 均保持不变）
        g.font = InfoCardRenderer.font(58f, true)
        g.color = COLOR_VALUE
        g.drawString("服务器在线列表", (MARGIN + 28).toFloat(), (top + 136).toFloat())

        // 右侧在线人数卡（叠在标题卡上；1.7.3 覆盖版二轮：与标题框四周留
        // 14px 可见间距——旧版上/下 8px、右侧 0px 贴边）
        val cardWidth = 240
        val cardInset = 14
        val cardHeight = HEADER_HEIGHT - 2 * cardInset
        val cardX = WIDTH - MARGIN - cardInset - cardWidth
        val cardY = top + cardInset
        backdrop?.paintCard(g, cardX.toFloat(), cardY.toFloat(), cardWidth.toFloat(), cardHeight.toFloat(), 20f)
        // 1.7.3 覆盖版：右上角小方框灰度永远比标题框深一点（render.title-card-gray +36）
        // ——旧版与标题框同灰度，叠在同色标题卡上视觉融合看不出边界
        g.color = Color(0x00, 0x00, 0x00, RenderSettings.headerBadgeCardGray())
        g.fill(RoundRectangle2D.Float(cardX.toFloat(), cardY.toFloat(), cardWidth.toFloat(), cardHeight.toFloat(), 20f, 20f))
        g.font = InfoCardRenderer.font(24f, false)
        g.color = COLOR_LABEL
        // 1.7.3 覆盖版三轮：标题框加高后卡内容整体下移 9px（卡高 136 → 154，
        // 四周 14px 间距不变），保持标签 / 数值在卡内视觉居中
        g.drawString("在线", (cardX + 28).toFloat(), (cardY + 51).toFloat())
        g.font = InfoCardRenderer.font(64f, true)
        g.color = COLOR_VALUE
        val countText = onlineCount.toString()
        val countWidth = g.getFontMetrics().stringWidth(countText)
        g.drawString(countText, (cardX + cardWidth - 28 - countWidth).toFloat(), (cardY + 109).toFloat())
    }

    /** 状态栏：服务器 / 更新时间 / 渲染耗时 三张并排小卡（卡片背后铺局部模糊；
     * 1.7.3：副标题条改接副标题灰度 render.subtitle-card-gray；1.7.3 覆盖版：
     * 第三格改「渲染耗时」——数值在全部面板绘制完成后由 [drawRenderElapsed]
     * 补画（从开始渲染到绘制完成的毫秒数，非从接收指令起算）。 */
    private fun drawStatusBar(g: Graphics2D, image: BufferedImage, backdrop: InfoCardAssets.CardBackdropContext?, serverName: String, updateTime: String) {
        val top = (MARGIN + HEADER_HEIGHT + HEADER_GAP).toFloat()
        val gap = 16f
        val cardWidth = (WIDTH - 2 * MARGIN - 2 * gap).toFloat() / 3f
        val cardHeight = STATUS_HEIGHT.toFloat()

        val cells = listOf(
            "服务器" to serverName,
            "更新时间" to updateTime,
            "渲染耗时" to null as String?,
        )
        cells.forEachIndexed { index, (label, value) ->
            val x = MARGIN + index * (cardWidth + gap)
            // 1.7.3 覆盖版五轮：绘制前采样画布底色（背景原样）→ 自适应配色
            val brightCard = InfoCardRenderer.subtitleCardBright(image, x, top, cardWidth, cardHeight)
            backdrop?.paintCard(g, x, top, cardWidth, cardHeight, 14f)
            // 副标题条接副标题灰度（render.subtitle-card-gray，覆盖版默认 56）
            g.color = Color(0x00, 0x00, 0x00, RenderSettings.subtitleCardGray())
            g.fill(RoundRectangle2D.Float(x, top, cardWidth, cardHeight, 14f, 14f))
            // 1.7.3 覆盖版四轮起：小标签统一走 InfoCardRenderer.drawSubLabel
            InfoCardRenderer.drawSubLabel(g, label, x + 26f, top + 34f, brightCard)
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

    /** 玩家网格：3 列，每格头像 + 玩家名；空列表画提示卡；网格整体套深色容器（与模板一致；
     * 容器背后铺局部模糊，容器内的单元格不再重复模糊）。 */
    private fun drawPlayerGrid(g: Graphics2D, backdrop: InfoCardAssets.CardBackdropContext?, entries: List<OnlineEntry>, firstPage: Boolean) {
        val gridTop = if (firstPage) {
            MARGIN + HEADER_HEIGHT + HEADER_GAP + STATUS_HEIGHT + STATUS_GAP +
                LIST_HEADER_HEIGHT + LIST_HEADER_GAP
        } else {
            PAGE_TOP_PAD
        }
        if (entries.isEmpty()) {
            val cardWidth = WIDTH - 2 * MARGIN
            backdrop?.paintCard(g, MARGIN.toFloat(), (gridTop - 14).toFloat(), cardWidth.toFloat(), (EMPTY_HEIGHT + 28).toFloat(), 18f)
            g.color = Color(0x00, 0x00, 0x00, RenderSettings.itemCardGray())
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
        backdrop?.paintCard(g, MARGIN.toFloat(), (gridTop - 14).toFloat(), contentWidth.toFloat(), (gridHeight + 28).toFloat(), 18f)
        g.color = Color(0x00, 0x00, 0x00, RenderSettings.itemCardGray())
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

            // 头像（无皮肤数据画占位块：深色圆角 + 名字首字符）；
            // 1.6.6.1 覆盖版：圆角头像零拷贝直绘（不再先拷一张圆角小图）
            val avatarX = cellX + 16
            val avatarY = cellY + (CELL_HEIGHT - AVATAR_SIZE) / 2
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
}
