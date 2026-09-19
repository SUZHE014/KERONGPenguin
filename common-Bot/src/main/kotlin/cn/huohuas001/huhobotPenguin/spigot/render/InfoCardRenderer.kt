package cn.huohuas001.huhobotPenguin.spigot.render

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.imageio.ImageIO

/**
 * 玩家信息卡片渲染器（/查信息）。
 *
 * 以 Java2D 绘制毛玻璃风格统计卡片：
 * - 顶部欢迎区（玩家名 + 问候 + MC 头像）
 * - 玩家身份条
 * - 生涯统计网格（3 列 × 5 行 = 15 项）
 *
 * 背景：优先使用插件数据目录 img/ 下的第一张图片（cover 铺满 + 轻微模糊），
 * 无图片时使用纯黑背景。
 */
object InfoCardRenderer {

    /** 画布尺寸。 */
    private const val WIDTH = 900
    private const val HEIGHT = 948

    /** 配色（毛玻璃深色系）。 */
    private val COLOR_EYEBROW = Color(0x93, 0xA7, 0xBC)
    private val COLOR_LABEL = Color(0x9F, 0xB3, 0xC8)
    private val COLOR_VALUE = Color.WHITE
    private val COLOR_BADGE = Color(0xA8, 0xBA, 0xCC)
    private val COLOR_HINT = Color(0xB8, 0xC8, 0xD8)

    /** 内置中文字体（子集化后随 JAR 分发）。 */
    private val FONT_REGULAR: Font by lazy { loadFont("fonts/NotoSansSC-Regular-subset.ttf") }
    private val FONT_BOLD: Font by lazy { loadFont("fonts/NotoSansSC-Bold-subset.ttf") }

    /**
     * 卡片数据项。
     */
    data class CardItem(val label: String, val value: String)

    /** 卡片渲染入参。 */
    data class CardData(
        val playerName: String,
        val items: List<CardItem>,
        val avatar: BufferedImage? = null,
        val background: BufferedImage? = null,
    )

    /**
     * 渲染卡片并返回 PNG 字节。
     */
    fun render(data: CardData): ByteArray {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            drawBackground(g, data.background)
            drawTopPanel(g, data)
            drawIdentityBar(g, data.playerName)
            drawStatsPanel(g, data.items)
        } finally {
            g.dispose()
        }
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return output.toByteArray()
    }

    /** 绘制背景：图片 cover 铺满 + 暗化遮罩；无图片时纯黑。 */
    private fun drawBackground(g: Graphics2D, background: BufferedImage?) {
        if (background != null && background.width > 0 && background.height > 0) {
            val cover = coverImage(background, WIDTH, HEIGHT)
            g.drawImage(softBlur(cover), 0, 0, null)
            // 半透明暗化遮罩，保证前景可读
            g.color = Color(0x00, 0x00, 0x00, 92)
            g.fillRect(0, 0, WIDTH, HEIGHT)
        } else {
            g.color = Color(0x05, 0x05, 0x08)
            g.fillRect(0, 0, WIDTH, HEIGHT)
            // 纯黑模式加一点极淡的渐变光晕避免完全死黑
            val gradient = java.awt.GradientPaint(
                0f, 0f, Color(0x10, 0x12, 0x18),
                0f, HEIGHT.toFloat(), Color(0x05, 0x05, 0x08),
            )
            g.paint = gradient
            g.fillRect(0, 0, WIDTH, HEIGHT)
        }
    }

    /** 顶部欢迎区。 */
    private fun drawTopPanel(g: Graphics2D, data: CardData) {
        val x = 24f
        val y = 24f
        val w = (WIDTH - 48).toFloat()
        val h = 200f

        // 毛玻璃容器（浅色半透明）
        g.color = Color(0xFF, 0xFF, 0xFF, 38)
        g.fill(RoundRectangle2D.Float(x, y, w, h, 24f, 24f))

        // 头像（右上角，圆角描边）
        val avatarSize = 108
        val avatarX = WIDTH - 24 - 40 - avatarSize
        val avatarY = 24 + (200 - avatarSize) / 2
        if (data.avatar != null) {
            val rounded = roundImage(data.avatar, avatarSize)
            g.drawImage(rounded, null, avatarX, avatarY)
            g.color = Color(0xFF, 0xFF, 0xFF, 200)
            g.stroke = BasicStroke(2f)
            g.draw(RoundRectangle2D.Float(avatarX + 0.5f, avatarY + 0.5f, avatarSize - 1f, avatarSize - 1f, 16f, 16f))
        } else {
            drawAvatarPlaceholder(g, avatarX, avatarY, avatarSize)
        }

        // 英文眉题
        g.font = FONT_BOLD.deriveFont(20f)
        g.color = COLOR_EYEBROW
        g.drawString("M I N E C R A F T   P L A Y E R", 52f, 84f)

        // 主问候
        g.font = FONT_BOLD.deriveFont(46f)
        g.color = COLOR_VALUE
        g.drawString("你好, ${data.playerName}!", 48f, 138f)

        // 副标题
        g.font = FONT_REGULAR.deriveFont(24f)
        g.color = COLOR_HINT
        g.drawString("这是你的统计数据", 50f, 178f)
    }

    /** 玩家身份条。 */
    private fun drawIdentityBar(g: Graphics2D, playerName: String) {
        val x = 24f
        val y = 244f
        val w = (WIDTH - 48).toFloat()
        val h = 64f

        g.color = Color(0xFF, 0xFF, 0xFF, 22)
        g.fill(RoundRectangle2D.Float(x, y, w, h, 16f, 16f))

        g.font = FONT_REGULAR.deriveFont(21f)
        g.color = COLOR_LABEL
        g.drawString("玩家名称", 52f, y + 41f)

        g.font = FONT_BOLD.deriveFont(26f)
        g.color = COLOR_VALUE
        // 值显示在标签右侧
        g.drawString(playerName, 190f, y + 42f)
    }

    /** 生涯统计面板（3 列 × 5 行网格）。 */
    private fun drawStatsPanel(g: Graphics2D, items: List<CardItem>) {
        val x = 24f
        val y = 336f
        val w = (WIDTH - 48).toFloat()
        val h = (HEIGHT - 24 - 336).toFloat()

        g.color = Color(0x00, 0x00, 0x00, 142)
        g.fill(RoundRectangle2D.Float(x, y, w, h, 24f, 24f))

        // 面板标题
        g.font = FONT_BOLD.deriveFont(30f)
        g.color = COLOR_VALUE
        g.drawString("生涯统计", 52f, y + 46f)

        // 项数徽标
        val badgeText = "${items.size} 项"
        g.font = FONT_REGULAR.deriveFont(19f)
        val badgeWidth = g.getFontMetrics().stringWidth(badgeText) + 28
        val badgeX = x + w - 28 - badgeWidth
        g.color = Color(0xFF, 0xFF, 0xFF, 30)
        g.fill(RoundRectangle2D.Float(badgeX, y + 18f, badgeWidth.toFloat(), 34f, 17f, 17f))
        g.color = COLOR_BADGE
        g.drawString(badgeText, badgeX + 14f, y + 42f)

        // 网格
        val columns = 3
        val gridLeft = 52f
        val gridTop = y + 84f
        val gridWidth = w - 56f
        val gridHeight = h - 84f - 26f
        val rows = (items.size + columns - 1) / columns
        val cellWidth = gridWidth / columns
        val cellHeight = gridHeight / rows
        val gapX = 7f
        val gapY = 7f

        items.forEachIndexed { index, item ->
            val col = index % columns
            val row = index / columns
            val cellX = gridLeft + col * cellWidth + gapX / 2
            val cellY = gridTop + row * cellHeight + gapY / 2
            val cw = cellWidth - gapX
            val ch = cellHeight - gapY

            // 单元格底色
            g.color = Color(0xFF, 0xFF, 0xFF, 16)
            g.fill(RoundRectangle2D.Float(cellX, cellY, cw, ch, 12f, 12f))

            // 标签
            g.font = FONT_REGULAR.deriveFont(20f)
            g.color = COLOR_LABEL
            g.drawString(item.label, cellX + 16f, cellY + 34f)

            // 数值
            g.font = FONT_BOLD.deriveFont(29f)
            g.color = COLOR_VALUE
            g.drawString(item.value, cellX + 16f, cellY + 70f)

            // 右下角序号水印
            g.font = FONT_BOLD.deriveFont(16f)
            g.color = Color(0xFF, 0xFF, 0xFF, 52)
            val number = "%02d".format(index + 1)
            val metrics = g.getFontMetrics()
            val numberWidth = metrics.stringWidth(number)
            g.drawString(number, cellX + cw - numberWidth - 12f, cellY + ch - 12f)
        }
    }

    /** 头像占位（无网络图片时绘制）。 */
    private fun drawAvatarPlaceholder(g: Graphics2D, x: Int, y: Int, size: Int) {
        g.color = Color(0x2A, 0x32, 0x3E)
        g.fill(RoundRectangle2D.Float(x + 0.5f, y + 0.5f, size - 1f, size - 1f, 16f, 16f))
        g.font = FONT_BOLD.deriveFont(26f)
        g.color = COLOR_HINT
        val text = "MC"
        val metrics = g.getFontMetrics()
        g.drawString(text, x + (size - metrics.stringWidth(text)) / 2f, y + size / 2f + 9f)
    }

    /** cover 铺满裁剪。 */
    private fun coverImage(source: BufferedImage, targetWidth: Int, targetHeight: Int): BufferedImage {
        val scale = maxOf(
            targetWidth.toFloat() / source.width,
            targetHeight.toFloat() / source.height,
        )
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(source, 0, 0, scaledWidth, scaledHeight, null)
        } finally {
            g.dispose()
        }
        val offsetX = (scaledWidth - targetWidth) / 2
        val offsetY = (scaledHeight - targetHeight) / 2
        return scaled.getSubimage(offsetX.coerceAtLeast(0), offsetY.coerceAtLeast(0), targetWidth, targetHeight)
    }

    /** 轻度模糊（降采样-升采样，开销极低）。 */
    private fun softBlur(source: BufferedImage): BufferedImage {
        val smallWidth = (source.width / 12).coerceAtLeast(1)
        val smallHeight = (source.height / 12).coerceAtLeast(1)
        val small = BufferedImage(smallWidth, smallHeight, BufferedImage.TYPE_INT_RGB)
        val g1 = small.createGraphics()
        try {
            g1.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g1.drawImage(source, 0, 0, smallWidth, smallHeight, null)
        } finally {
            g1.dispose()
        }
        val result = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val g2 = result.createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            // 半透明叠加产生轻微模糊观感
            g2.composite = AlphaComposite.SrcOver.derive(0.85f)
            g2.drawImage(small, 0, 0, source.width, source.height, null)
            g2.composite = AlphaComposite.SrcOver.derive(0.5f)
            g2.drawImage(source, 0, 0, null)
        } finally {
            g2.dispose()
        }
        return result
    }

    /** 圆角头像。 */
    private fun roundImage(source: BufferedImage, size: Int): BufferedImage {
        val result = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = result.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.clip(RoundRectangle2D.Float(0f, 0f, size.toFloat(), size.toFloat(), 16f, 16f))
            g.drawImage(source, 0, 0, size, size, null)
        } finally {
            g.dispose()
        }
        return result
    }

    /** 从类路径加载内置字体。 */
    private fun loadFont(path: String): Font = try {
        val stream: InputStream? = InfoCardRenderer::class.java.classLoader.getResourceAsStream(path)
        if (stream != null) {
            stream.use { Font.createFont(Font.TRUETYPE_FONT, it) }
        } else {
            Font(Font.SANS_SERIF, Font.PLAIN, 12)
        }
    } catch (_: Exception) {
        Font(Font.SANS_SERIF, Font.PLAIN, 12)
    }
}

/**
 * 卡片素材获取：MC 头像与背景图。
 */
object InfoCardAssets {

    /**
     * 下载玩家 MC 头像（minotar 3D 头 / 平面头），失败返回 null。
     * 在异步线程调用。
     */
    fun fetchAvatar(uuid: String, playerName: String): BufferedImage? {
        val candidates = listOf(
            "https://minotar.net/cube/$uuid/216",
            "https://minotar.net/helm/$uuid/216",
            "https://minotar.net/helm/$playerName/216",
            "https://mc-heads.net/avatar/$uuid/216",
        )
        for (url in candidates) {
            try {
                val bytes = httpGet(url, 5000) ?: continue
                val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: continue
                if (image.width > 0 && image.height > 0) return image
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    /**
     * 从插件数据目录 img/ 读取第一张背景图（png/jpg/jpeg/bmp/gif）。
     */
    fun loadBackground(dataDirectory: File): BufferedImage? {
        val folder = File(dataDirectory, "img")
        val files = folder.listFiles { file ->
            file.isFile && file.extension.lowercase() in setOf("png", "jpg", "jpeg", "bmp", "gif")
        } ?: return null
        for (file in files.sortedBy { it.name }) {
            try {
                val image = ImageIO.read(file)
                if (image != null && image.width > 0 && image.height > 0) return image
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    /** 简单 HTTP GET。 */
    private fun httpGet(url: String, timeoutMillis: Int): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.setRequestProperty("User-Agent", "KERONGPenguin")
            val code = connection.responseCode
            if (code !in 200..299) return null
            connection.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
