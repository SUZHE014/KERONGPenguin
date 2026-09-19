package cn.huohuas001.huhobotPenguin.spigot.render

import com.alibaba.fastjson.JSON
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
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO

/**
 * 玩家信息卡片渲染器（/个人信息）。
 *
 * 以 Java2D 绘制毛玻璃风格统计卡片：
 * - 顶部欢迎区（玩家名 + 问候 + MC 头像正面贴图）
 * - 玩家身份条
 * - 统计面板（1.5.0 起为 6 项：金币 / 称号 / 在线时长 / 今日在线时长 / 累计签到 / 点券，
 *   3 列 × 2 行布局）
 *
 * 0.1.5.2 性能修复：
 * - 背景与头像处理结果按文件 / 玩家缓存，不再每次渲染重新解码处理；
 * - 派生字体缓存，避免重复 deriveFont 分配；
 * - 关闭 ImageIO 磁盘缓存，减少临时文件 churn。
 *
 * 0.1.5.3 随机背景：背景图从 img/ 目录候选图中随机挑选，
 * 处理结果按张缓存，随机切换零额外解码开销。
 *
 * 0.1.5.5 彩色称号：数值支持 MC 旧版颜色码（&/§ + 0-9a-fk-or 与 &#RRGGBB），
 * 解析为彩色分段后按原色渲染（DeluxeTags 称号颜色得以真实还原）；
 * 背景图改为子采样解码，超长边限制在约 1600px，防止大尺寸照片整图解码导致内存峰值 / OOM。
 *
 * 1.5.0：渲染完成后 flush 单次图像（立即释放栅格占用的堆内存，配合渲染后的
 * 节流 GC 提示降低内存驻留，见 QueryInfoService）。
 */
object InfoCardRenderer {

    /** 画布尺寸（1.5.0：6 项统计 3 列 × 2 行，高度维持 660）。 */
    internal const val WIDTH = 900
    internal const val HEIGHT = 660

    /** 配色（毛玻璃深色系）。 */
    private val COLOR_EYEBROW = Color(0x93, 0xA7, 0xBC)
    private val COLOR_LABEL = Color(0x9F, 0xB3, 0xC8)
    private val COLOR_VALUE = Color.WHITE
    private val COLOR_BADGE = Color(0xA8, 0xBA, 0xCC)
    private val COLOR_HINT = Color(0xB8, 0xC8, 0xD8)

    /** 内置中文字体（子集化后随 JAR 分发）。 */
    private val FONT_REGULAR: Font by lazy { loadFont("fonts/NotoSansSC-Regular-subset.ttf") }
    private val FONT_BOLD: Font by lazy { loadFont("fonts/NotoSansSC-Bold-subset.ttf") }

    /** 派生字体缓存（键 = 粗细:字号），避免每次绘制重复分配 Font 对象。 */
    private val derivedFontCache = ConcurrentHashMap<String, Font>()

    init {
        // 关闭 ImageIO 磁盘缓存：卡片渲染均为小图，无需临时落盘
        ImageIO.setUseCache(false)
    }

    /** 获取缓存过的派生字体（1.5.0 起开放给本模块其他渲染器复用，如在线列表卡片）。 */
    internal fun font(size: Float, bold: Boolean): Font {
        val key = (if (bold) "B" else "R") + size
        return derivedFontCache.computeIfAbsent(key) {
            (if (bold) FONT_BOLD else FONT_REGULAR).deriveFont(size)
        }
    }

    /**
     * 卡片数据项。
     */
    data class CardItem(val label: String, val value: String)

    /** 卡片渲染入参。 */
    data class CardData(
        val playerName: String,
        val items: List<CardItem>,
        val avatar: BufferedImage? = null,
        /** 已预处理的背景（尺寸须为 WIDTH × HEIGHT，含模糊与暗化）；null 表示无背景。 */
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
        val output = ByteArrayOutputStream(256 * 1024)
        ImageIO.write(image, "png", output)
        // 1.5.0：PNG 已写出，立即释放单次画布的栅格内存（缓存中的背景/头像不受影响）
        image.flush()
        return output.toByteArray()
    }

    /** 绘制背景：传入的已是预处理完成的 WIDTH×HEIGHT 图；无图片时纯黑。 */
    private fun drawBackground(g: Graphics2D, background: BufferedImage?) {
        if (background != null && background.width == WIDTH && background.height == HEIGHT) {
            g.drawImage(background, 0, 0, null)
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
        g.font = font(20f, true)
        g.color = COLOR_EYEBROW
        g.drawString("M I N E C R A F T   P L A Y E R", 52f, 84f)

        // 主问候
        g.font = font(46f, true)
        g.color = COLOR_VALUE
        g.drawString("你好, ${data.playerName}!", 48f, 138f)

        // 副标题
        g.font = font(24f, false)
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

        g.font = font(21f, false)
        g.color = COLOR_LABEL
        g.drawString("玩家名称", 52f, y + 41f)

        g.font = font(26f, true)
        g.color = COLOR_VALUE
        // 值显示在标签右侧
        g.drawString(playerName, 190f, y + 42f)
    }

    /**
     * 生涯统计面板（1.5.0：6 项 → 3 列 × 2 行；4-5 项时 2 列避免孤项）。 */
    private fun drawStatsPanel(g: Graphics2D, items: List<CardItem>) {
        val x = 24f
        val y = 336f
        val w = (WIDTH - 48).toFloat()
        val h = (HEIGHT - 24 - 336).toFloat()

        g.color = Color(0x00, 0x00, 0x00, 142)
        g.fill(RoundRectangle2D.Float(x, y, w, h, 24f, 24f))

        // 面板标题
        g.font = font(30f, true)
        g.color = COLOR_VALUE
        g.drawString("统计信息", 52f, y + 46f)

        // 项数徽标
        val badgeText = "${items.size} 项"
        g.font = font(19f, false)
        val badgeWidth = g.getFontMetrics().stringWidth(badgeText) + 28
        val badgeX = x + w - 28 - badgeWidth
        g.color = Color(0xFF, 0xFF, 0xFF, 30)
        g.fill(RoundRectangle2D.Float(badgeX, y + 18f, badgeWidth.toFloat(), 34f, 17f, 17f))
        g.color = COLOR_BADGE
        g.drawString(badgeText, badgeX + 14f, y + 42f)

        // 网格（1.5.0：6 项时 3 列，4-5 项时 2 列避免最后一行孤项）
        val columns = if (items.size >= 6) 3 else 2
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
            g.font = font(20f, false)
            g.color = COLOR_LABEL
            g.drawString(item.label, cellX + 16f, cellY + 34f)

            // 数值（0.1.5.5：支持颜色码分段彩色渲染与超宽截断，用于称号等彩色文本）
            drawItemValue(g, item.value, cellX + 16f, cellY + 72f, cw - 32f)

            // 右下角序号水印
            g.font = font(16f, true)
            g.color = Color(0xFF, 0xFF, 0xFF, 52)
            val number = "%02d".format(index + 1)
            val metrics = g.getFontMetrics()
            val numberWidth = metrics.stringWidth(number)
            g.drawString(number, cellX + cw - numberWidth - 12f, cellY + ch - 12f)
        }
    }

    /** 头像占位（无皮肤数据时绘制，避免展示错误的默认皮肤）。 */
    private fun drawAvatarPlaceholder(g: Graphics2D, x: Int, y: Int, size: Int) {
        g.color = Color(0x2A, 0x32, 0x3E)
        g.fill(RoundRectangle2D.Float(x + 0.5f, y + 0.5f, size - 1f, size - 1f, 16f, 16f))
        g.font = font(26f, true)
        g.color = COLOR_HINT
        val text = "MC"
        val metrics = g.getFontMetrics()
        g.drawString(text, x + (size - metrics.stringWidth(text)) / 2f, y + size / 2f + 9f)
    }

    // ---------- 彩色文本（0.1.5.5，称号颜色码还原） ----------

    /** MC 旧版 16 色板（索引 0-9a-f）。 */
    private val CHAT_COLORS: Map<Char, Color> = mapOf(
        '0' to Color(0x000000), '1' to Color(0x0000AA), '2' to Color(0x00AA00), '3' to Color(0x00AAAA),
        '4' to Color(0xAA0000), '5' to Color(0xAA00AA), '6' to Color(0xFFAA00), '7' to Color(0xAAAAAA),
        '8' to Color(0x555555), '9' to Color(0x5555FF), 'a' to Color(0x55FF55), 'b' to Color(0x55FFFF),
        'c' to Color(0xFF5555), 'd' to Color(0xFF55FF), 'e' to Color(0xFFFF55), 'f' to Color(0xFFFFFF),
    )

    /** 彩色文本段。 */
    data class TextSegment(val text: String, val color: Color, val bold: Boolean)

    /**
     * 解析 MC 旧版颜色码（& / § 前缀）：
     * - `&c` / `§c`：颜色 0-9 a-f；`&#RRGGBB` / `§#RRGGBB`：十六进制色；
     * - `&l` 粗体、`&r` 重置；其余格式码（k n m o）忽略；
     * - 非法/未知码原样输出（不影响普通文本）；
     * - 顺带剔除常见 MiniMessage 标签（<gold> 等），避免渲染出尖括号原文。
     */
    internal fun parseLegacyColors(raw: String): List<TextSegment> {
        if (raw.isEmpty()) return emptyList()
        val text = raw.replace(MINI_MESSAGE_TAG, "")
        val segments = ArrayList<TextSegment>()
        val builder = StringBuilder()
        var color = COLOR_VALUE
        var bold = false

        fun flush() {
            if (builder.isNotEmpty()) {
                segments.add(TextSegment(builder.toString(), ensureReadable(color), bold))
                builder.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if ((ch == '&' || ch == '§') && i + 1 < text.length) {
                val next = text[i + 1]
                if (next == '#' && i + 7 <= text.length) {
                    val hex = text.substring(i + 2, i + 8)
                    val parsed = parseHexColor(hex)
                    if (parsed != null) {
                        flush()
                        color = parsed
                        bold = false
                        i += 8
                        continue
                    }
                }
                val lower = next.lowercaseChar()
                if (CHAT_COLORS.containsKey(lower)) {
                    flush()
                    color = CHAT_COLORS.getValue(lower)
                    bold = false
                    i += 2
                    continue
                }
                if (lower == 'l') {
                    flush()
                    bold = true
                    i += 2
                    continue
                }
                if (lower == 'r') {
                    flush()
                    color = COLOR_VALUE
                    bold = false
                    i += 2
                    continue
                }
                if (lower in "kmno") {
                    // 混淆/下划线/删除线/斜体：图片上不支持，直接吞掉
                    i += 2
                    continue
                }
            }
            builder.append(ch)
            i++
        }
        flush()
        return segments
    }

    /** 常见 MiniMessage 标签（含闭合与带参形式），用于剔除。 */
    private val MINI_MESSAGE_TAG = Regex("</?(?:gold|red|blue|green|dark_green|dark_blue|dark_red|dark_aqua|dark_purple|dark_gray|gray|black|white|yellow|aqua|light_purple|bold|italic|obfuscated|strikethrough|underlined|underline|reset|gradient|transition|rainbow|color|font|newline|br)(?::[^>]*)?>", RegexOption.IGNORE_CASE)

    /** 十六进制颜色解析（不合法返回 null）。 */
    private fun parseHexColor(hex: String): Color? = try {
        Color(hex.toInt(16))
    } catch (_: Throwable) {
        null
    }

    /** 过暗颜色提亮到可读亮度（黑色称号在深色卡片上直接渲染会看不见）。 */
    private fun ensureReadable(color: Color): Color {
        val luminance = (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) / 255.0
        if (luminance >= 0.22) return color
        val mix = 0.55
        return Color(
            (color.red + (255 - color.red) * mix).toInt().coerceIn(0, 255),
            (color.green + (255 - color.green) * mix).toInt().coerceIn(0, 255),
            (color.blue + (255 - color.blue) * mix).toInt().coerceIn(0, 255),
        )
    }

    /**
     * 绘制项数值：无颜色码时按白色粗体单次绘制；
     * 含颜色码时按分段彩色绘制；超宽时截断加省略号。
     */
    private fun drawItemValue(g: Graphics2D, raw: String, x: Float, y: Float, maxWidth: Float) {
        val segments = parseLegacyColors(raw)
        if (segments.size == 1 && segments[0].color == COLOR_VALUE && !segments[0].bold) {
            g.font = font(29f, true)
            g.color = COLOR_VALUE
            g.drawString(ellipsize(g, segments[0].text, maxWidth), x, y)
            return
        }
        var cursor = x
        val ellipsisWidth = g.getFontMetrics(font(29f, false)).stringWidth("…")
        for (segment in segments) {
            // 数值基础样式为粗体（与旧版一致），&l 解析结果保留在段属性中
            g.font = font(29f, true)
            g.color = segment.color
            var text = segment.text
            val width = g.getFontMetrics().stringWidth(text)
            if (cursor + width > x + maxWidth) {
                // 当前段放不下：截到剩余宽度内并终止
                val remain = (x + maxWidth - cursor - ellipsisWidth).toInt()
                if (remain > 12) {
                    text = clipToWidth(g, text, remain)
                    g.drawString(text, cursor, y)
                    cursor += g.getFontMetrics().stringWidth(text)
                }
                g.font = font(29f, false)
                g.color = COLOR_VALUE
                g.drawString("…", cursor, y)
                return
            }
            g.drawString(text, cursor, y)
            cursor += width
        }
    }

    /** 按最大宽度截断文本（尾部加省略号）。 */
    private fun ellipsize(g: Graphics2D, text: String, maxWidth: Float): String {
        if (g.getFontMetrics().stringWidth(text) <= maxWidth) return text
        return clipToWidth(g, text, (maxWidth - g.getFontMetrics().stringWidth("…")).toInt()) + "…"
    }

    /** 截取不超给定宽度的最长前缀。 */
    private fun clipToWidth(g: Graphics2D, text: String, maxWidth: Int): String {
        val metrics = g.getFontMetrics()
        if (metrics.stringWidth(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) > maxWidth) end--
        return text.substring(0, end)
    }

    /** 圆角头像。 */
    internal fun roundImage(source: BufferedImage, size: Int): BufferedImage {
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
 * 卡片素材获取与缓存：MC 皮肤正面贴图头像 + 背景图。
 *
 * 0.1.5.2 头像修复：
 * 旧版直接请求 minotar 3D 头像，离线服（离线 UUID）与查不到的账号都会
 * 得到 200 的默认史蒂夫皮肤，导致头像永远显示史蒂夫。
 * 现在改为解析玩家真实皮肤贴图并裁剪正面脸部（8×8 + 帽子层），
 * 优先级（每一步失败都可检测，绝不回落到默认皮肤）：
 * 1. 在线玩家的 Profile 贴图 URL（含 SkinsRestorer 等改皮肤的情况）；
 * 2. 本服 playerdata/<UUID>.dat 中的 textures 属性（NBT 解析）；
 * 3. Mojang 会话服务器按 UUID 查询（仅正版 UUID，未知返回 204）；
 * 4. 离线 UUID / 未知 UUID 时按玩家名到 Mojang 查正版账号（查不到返回 404）；
 * 5. 全部失败 → null → 渲染占位图标。
 *
 * 0.1.5.2 性能修复：头像与背景均带 TTL 缓存，重复查询不再重复解码 / 请求。
 *
 * 0.1.5.3 随机背景：卡片背景从 img/ 目录的候选图中随机挑选一张
 * （旧版固定取排序后的第一张），每张图的处理结果仍按文件签名缓存，
 * 重复抽中时不再重新解码，随机切换不增加 CPU 开销。
 */
object InfoCardAssets {

    /** 头像缓存有效期（毫秒）。 */
    private const val AVATAR_TTL = 10 * 60_000L

    /** 头像缓存容量上限。 */
    private const val AVATAR_CACHE_MAX = 128

    /** 查询失败后的短期冷却，避免连续请求打爆外部接口。 */
    private const val MISS_TTL = 60_000L

    /** 输出头像尺寸（渲染前再缩到展示尺寸）。 */
    private const val FACE_SIZE = 216

    /** 头像请求参数。 */
    data class AvatarRequest(
        val uuid: UUID?,
        val playerName: String,
        /** 在线玩家 Profile 的皮肤贴图 URL（主线程捕获，可为 null）。 */
        val skinUrl: String? = null,
        /** 服务器 playerdata 目录（主线程捕获，可为 null）。 */
        val playerDataDir: File? = null,
    )

    private class CacheEntry(val image: BufferedImage, val createdAt: Long)

    private class NegativeEntry(val createdAt: Long)

    /** 头像缓存：uuid（或 name:name）→ 正面脸部图。 */
    private val avatarCache = ConcurrentHashMap<String, CacheEntry>()

    /** 近期失败记录：键 → 时间。 */
    private val missCache = ConcurrentHashMap<String, NegativeEntry>()

    /** 预处理背景缓存条目：文件签名 + 尺寸签名 + 处理完的图 + 最近使用时间。 */
    private class BackgroundEntry(
        val stamp: Long,
        val sizeKey: Long,
        val image: BufferedImage,
        @Volatile var lastUsedAt: Long,
    )

    /** 预处理背景缓存：文件绝对路径 → 条目（0.1.5.3 起支持多张图随机切换）。 */
    private val backgroundCache = ConcurrentHashMap<String, BackgroundEntry>()

    /** 背景缓存容量上限（张）；超出时淘汰最久未使用的，控制内存占用。 */
    private const val BACKGROUND_CACHE_MAX = 8

    /** 背景图子采样解码的最长边限制（像素）：超长边照片降采样后再解码，防整图解码 OOM（0.1.5.5）。 */
    private const val BACKGROUND_MAX_DIM = 1600

    /** 近期解码失败的背景文件（路径 → 失败时间），10 分钟内不再抽中，避免坏图反复触发解码（0.1.5.5）。 */
    private val backgroundFailures = ConcurrentHashMap<String, Long>()

    /** 记录的玩家名 → 正版 UUID（Mojang API），含未查到的负缓存。 */
    private val premiumUuidCache = ConcurrentHashMap<String, Any>()

    /**
     * 获取玩家皮肤正面贴图头像（异步线程调用）。
     * 返回 FACE_SIZE × FACE_SIZE 的脸部图像；无皮肤数据时返回 null。
     */
    fun fetchAvatar(request: AvatarRequest): BufferedImage? {
        val key = request.uuid?.toString() ?: "name:${request.playerName.lowercase()}"
        val now = System.currentTimeMillis()

        // 1. 命中缓存直接返回
        val cached = avatarCache[key]
        if (cached != null && now - cached.createdAt < AVATAR_TTL) return cached.image

        // 2. 近期失败冷却
        val missed = missCache[key]
        if (missed != null && now - missed.createdAt < MISS_TTL) return null

        // 3. 解析皮肤贴图（URL 列表，逐个尝试）
        val skinUrl = resolveSkinTexture(request)
        val face: BufferedImage? = skinUrl?.let { url ->
            downloadSkin(url)?.let { skin -> cropFace(skin, FACE_SIZE) }
        }

        if (face != null) {
            avatarCache[key] = CacheEntry(face, now)
            evictIfOversized()
            missCache.remove(key)
            return face
        }
        missCache[key] = NegativeEntry(now)
        trimMissCache()
        return null
    }

    /** 清空全部素材缓存（插件停用时调用，释放图像内存）。 */
    fun clearCaches() {
        avatarCache.clear()
        missCache.clear()
        premiumUuidCache.clear()
        backgroundCache.clear()
    }

    /** 缓存超限时淘汰最旧条目。 */
    private fun evictIfOversized() {
        if (avatarCache.size <= AVATAR_CACHE_MAX) return
        val toRemove = avatarCache.entries
            .sortedBy { it.value.createdAt }
            .take(AVATAR_CACHE_MAX / 8 + 1)
        for (entry in toRemove) {
            avatarCache.remove(entry.key, entry.value)
        }
    }

    /** miss 缓存兜底清理（容量非常小，仅防泄漏）。 */
    private fun trimMissCache() {
        if (missCache.size <= 512) return
        val now = System.currentTimeMillis()
        for ((k, v) in missCache) {
            if (now - v.createdAt > MISS_TTL * 2) missCache.remove(k, v)
        }
    }

    // ---------- 皮肤贴图解析链 ----------

    /**
     * 解析皮肤贴图 URL（纹理图 URL，非头像服务）。
     * 顺序：Profile 直供 → 本服 NBT → 会话服务器（正版 UUID）→ 玩家名反查。
     */
    private fun resolveSkinTexture(request: AvatarRequest): String? {
        // 1. 在线玩家 Profile（最准确，含皮肤插件修改）
        request.skinUrl?.takeIf { it.startsWith("http") }?.let { return it }

        // 2. 本服 playerdata NBT（离线服 + SkinsRestorer 的主要来源）
        val uuid = request.uuid
        val playerDataDir = request.playerDataDir
        if (uuid != null && playerDataDir != null) {
            val datFile = File(playerDataDir, "$uuid.dat")
            if (datFile.isFile) {
                extractNbtSkinUrl(datFile)?.let { return it }
            }
        }

        // 3. 正版 UUID：会话服务器查询（未知 UUID 返回 204，可检测失败）
        if (uuid != null && uuid.version() == 4) {
            sessionServerSkinUrl(uuid)?.let { return it }
        }

        // 4. 离线 UUID / 无 UUID：按玩家名查正版账号（非正版名返回 404）
        val premiumUuid = resolvePremiumUuidByName(request.playerName)
        if (premiumUuid != null && premiumUuid != uuid) {
            sessionServerSkinUrl(premiumUuid)?.let { return it }
        }

        return null
    }

    /** 解析 playerdata .dat（gzip NBT）中的贴图属性 → 皮肤 URL。 */
    internal fun extractNbtSkinUrl(datFile: File): String? {
        return try {
            GZIPInputStream(datFile.inputStream().buffered()).use { raw ->
                val input = DataInputStream(raw.buffered())
                // 根标签必须是 Compound
                if (input.readUnsignedByte() != 10) return null
                skipName(input)
                walkRootCompound(input)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 遍历根复合标签，寻找 Properties → textures → value。 */
    private fun walkRootCompound(input: DataInputStream): String? {
        while (true) {
            val type = input.readUnsignedByte()
            if (type == 0) return null
            val name = input.readUTF()
            if (type == 10 && name == "Properties") {
                walkProperties(input)?.let { return it }
            } else {
                skipPayload(input, type)
            }
        }
    }

    /** 遍历 Properties 复合标签，寻找 textures 列表。 */
    private fun walkProperties(input: DataInputStream): String? {
        while (true) {
            val type = input.readUnsignedByte()
            if (type == 0) return null
            val name = input.readUTF()
            if (type == 9 && name == "textures") {
                val elemType = input.readUnsignedByte()
                val count = input.readInt()
                if (elemType == 10 && count > 0) {
                    for (i in 0 until count) {
                        walkTextureEntry(input)?.let { return it }
                    }
                } else {
                    for (i in 0 until count) skipPayload(input, elemType)
                }
            } else {
                skipPayload(input, type)
            }
        }
    }

    /** 遍历单个贴图属性复合标签，读取 value（base64 的贴图 JSON）。 */
    private fun walkTextureEntry(input: DataInputStream): String? {
        var value: String? = null
        while (true) {
            val type = input.readUnsignedByte()
            if (type == 0) break
            val name = input.readUTF()
            if (type == 8 && name == "value") {
                value = input.readUTF()
            } else {
                skipPayload(input, type)
            }
        }
        return value?.let { decodeSkinUrl(it) }
    }

    /** base64 贴图 JSON → 皮肤 URL。 */
    private fun decodeSkinUrl(base64: String): String? = try {
        val json = JSON.parseObject(String(Base64.getDecoder().decode(base64), Charsets.UTF_8))
        json.getJSONObject("textures")?.getJSONObject("SKIN")?.getString("url")
    } catch (_: Throwable) {
        null
    }

    /** 跳过 NBT 标签名（短长度 + UTF 字节）。 */
    private fun skipName(input: DataInputStream) {
        skipFully(input, input.readUnsignedShort())
    }

    /** 跳过指定类型标签的负载。 */
    private fun skipPayload(input: DataInputStream, type: Int) {
        when (type) {
            1 -> skipFully(input, 1)
            2 -> skipFully(input, 2)
            3 -> skipFully(input, 4)
            4 -> skipFully(input, 8)
            5 -> skipFully(input, 4)
            6 -> skipFully(input, 8)
            7 -> skipFully(input, input.readInt())
            8 -> skipFully(input, input.readUnsignedShort())
            9 -> {
                val elemType = input.readUnsignedByte()
                val count = input.readInt()
                for (i in 0 until count) skipPayload(input, elemType)
            }
            10 -> while (true) {
                val t = input.readUnsignedByte()
                if (t == 0) break
                skipName(input)
                skipPayload(input, t)
            }
            11 -> skipFully(input, 4 * input.readInt())
            12 -> skipFully(input, 8 * input.readInt())
        }
    }

    /** 确保跳过 n 字节（skipBytes 可能部分跳过）。 */
    private fun skipFully(input: DataInputStream, count: Int) {
        var left = count
        while (left > 0) {
            val skipped = input.skipBytes(left)
            if (skipped > 0) {
                left -= skipped
            } else if (input.read() < 0) {
                throw java.io.EOFException("NBT stream ended unexpectedly")
            } else {
                left -= 1
            }
        }
    }

    /** 会话服务器查询贴图 URL（未知 UUID 返回 204 → null）。 */
    private fun sessionServerSkinUrl(uuid: UUID): String? {
        return try {
            val body = httpGet(
                "https://sessionserver.mojang.com/session/minecraft/profile/${uuid.toString().replace("-", "")}",
                5000,
            ) ?: return null
            val json = JSON.parseObject(String(body, Charsets.UTF_8)) ?: return null
            val properties = json.getJSONArray("properties") ?: return null
            for (i in 0 until properties.size) {
                val property = properties.getJSONObject(i) ?: continue
                if (property.getString("name") == "textures") {
                    val value = property.getString("value") ?: continue
                    return decodeSkinUrl(value)
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** 按玩家名解析正版 UUID（带正负缓存；非正版名返回 404 → null）。 */
    private fun resolvePremiumUuidByName(name: String): UUID? {
        if (name.isBlank()) return null
        val key = name.lowercase()
        val cached = premiumUuidCache[key]
        if (cached is UUID) return cached
        if (cached is Long) return null // 负缓存
        return try {
            val body = httpGet("https://api.mojang.com/users/profiles/minecraft/$name", 5000)
            val uuid = body?.let { parseDashedUuid(JSON.parseObject(String(it, Charsets.UTF_8))?.getString("id")) }
            premiumUuidCache[key] = uuid ?: System.currentTimeMillis()
            uuid
        } catch (_: Throwable) {
            null
        }
    }

    /** 无横线 UUID → 标准 UUID。 */
    private fun parseDashedUuid(hex: String?): UUID? = try {
        if (hex == null || hex.length != 32) null else UUID.fromString(
            "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}",
        )
    } catch (_: Throwable) {
        null
    }

    /** 下载皮肤贴图 PNG（64×64 / 64×32 / 高清皮肤）。 */
    private fun downloadSkin(url: String): BufferedImage? = try {
        val bytes = httpGet(url, 5000) ?: return null
        val skin = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        if (skin.width >= 64 && skin.height >= 32) skin else null
    } catch (_: Throwable) {
        null
    }

    /**
     * 从皮肤贴图裁剪头部正面（8×8 + 帽子层叠加），最近邻放大到 [size]。
     * 支持经典 64×32 / 64×64 与等比高清皮肤。
     */
    internal fun cropFace(skin: BufferedImage, size: Int): BufferedImage {
        val cell = (skin.width / 64).coerceAtLeast(1)
        val faceX = 8 * cell
        val faceY = 8 * cell
        val faceSize = 8 * cell
        val hatX = 40 * cell

        val result = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = result.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            // 正面脸（保持像素风放大）
            g.drawImage(skin.getSubimage(faceX, faceY, faceSize, faceSize), 0, 0, size, size, null)
            // 帽子层：仅 64×64+ 现代皮肤叠加。
            // 旧版 64×32 皮肤的帽子区域是无效数据（常为不透明黑），
            // 游戏本体对 64×32 皮肤也不渲染帽子层，直接跳过。
            if (skin.height >= 64 * cell) {
                val hat = skin.getSubimage(hatX, faceY, faceSize, faceSize)
                val step = size / 8
                for (y in 0 until 8) {
                    for (x in 0 until 8) {
                        val rgb = hat.getRGB(x * cell, y * cell)
                        if ((rgb ushr 24) and 0xFF > 0) {
                            g.color = Color(rgb, true)
                            g.fillRect(x * step, y * step, step, step)
                        }
                    }
                }
            }
        } finally {
            g.dispose()
        }
        return result
    }

    // ---------- 背景图 ----------

    /**
     * 获取预处理完成的背景（cover 裁剪 + 轻模糊 + 暗化，尺寸 width × height）。
     *
     * 0.1.5.3：从 img/ 目录全部候选图中随机挑选一张（旧版固定取第一张），
     * 每张图的处理结果按文件路径 + 修改时间 + 大小缓存；图片更换后自动重新处理。
     * 同一张图重复抽中时直接命中缓存，随机切换不额外增加 CPU 开销。
     *
     * 0.1.5.5：
     * - 解码改用 ImageReader 子采样（最长边限制约 1600px），超大尺寸照片
     *   （如手机原图 4000×3000）不再整图解码，避免内存峰值 / OOM 导致卡片生成失败；
     * - 解码失败的文件进入 10 分钟负缓存，随机挑选时自动跳过。
     */
    @Synchronized
    fun processedBackground(dataDirectory: File, width: Int, height: Int): BufferedImage? {
        val folder = File(dataDirectory, "img")
        val now = System.currentTimeMillis()
        // 偶发清理负缓存（容量极小，仅防泄漏）
        if (backgroundFailures.size > 32) {
            backgroundFailures.entries.removeIf { now - it.value > 10 * 60_000L }
        }
        val candidates = folder.listFiles { file ->
            file.isFile && file.length() > 0 &&
                file.extension.lowercase() in setOf("png", "jpg", "jpeg", "bmp", "gif") &&
                (backgroundFailures[file.absolutePath] ?: 0L).let { now - it > 10 * 60_000L }
        }?.toList() ?: emptyList()
        if (candidates.isEmpty()) return null

        // 0.1.5.3：随机挑选背景图（不再固定第一张）
        val file = candidates[ThreadLocalRandom.current().nextInt(candidates.size)]

        val path = file.absolutePath
        val stamp = file.lastModified() + file.length()
        val sizeKey = width.toLong() * 4096 + height
        val cached = backgroundCache[path]
        if (cached != null && cached.stamp == stamp && cached.sizeKey == sizeKey) {
            cached.lastUsedAt = now
            return cached.image
        }

        // 解码并处理（仅在文件变化或首次抽中时执行；子采样限制解码尺寸）
        val decoded = decodeBackground(file) ?: return null
        if (decoded.width <= 0 || decoded.height <= 0) return null

        val processed = darken(softBlur(coverImage(decoded, width, height)))
        backgroundCache[path] = BackgroundEntry(stamp, sizeKey, processed, now)
        evictBackgroundsIfOversized()
        return processed
    }

    /**
     * 子采样解码背景图（0.1.5.5）：先读尺寸，再按 2 的幂降采样到
     * 最长边约 [BACKGROUND_MAX_DIM] 以内后读取像素，控制解码内存。
     * 解码失败返回 null（不抛异常）。
     */
    private fun decodeBackground(file: File): BufferedImage? {
        var input: javax.imageio.stream.ImageInputStream? = null
        var reader: javax.imageio.ImageReader? = null
        return try {
            input = ImageIO.createImageInputStream(file) ?: return null
            val readerIterator = ImageIO.getImageReaders(input)
            if (!readerIterator.hasNext()) return null
            reader = readerIterator.next()
            reader.setInput(input, true, false)
            val sourceWidth = try {
                reader.getWidth(0)
            } catch (_: Throwable) {
                -1
            }
            val sourceHeight = try {
                reader.getHeight(0)
            } catch (_: Throwable) {
                -1
            }
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                backgroundFailures[file.absolutePath] = System.currentTimeMillis()
                return null
            }
            val longest = maxOf(sourceWidth, sourceHeight)
            var subsampling = 1
            while (longest / (subsampling.toLong() * 2) >= BACKGROUND_MAX_DIM) subsampling *= 2
            val param = reader.defaultReadParam
            param.setSourceSubsampling(subsampling, subsampling, 0, 0)
            val image = reader.read(0, param)
            if (image.width <= 0 || image.height <= 0) {
                backgroundFailures[file.absolutePath] = System.currentTimeMillis()
                null
            } else {
                image
            }
        } catch (_: Throwable) {
            backgroundFailures[file.absolutePath] = System.currentTimeMillis()
            null
        } finally {
            try {
                reader?.dispose()
            } catch (_: Throwable) {
            }
            try {
                input?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /** 背景缓存超限时淘汰最久未使用的条目，控制图像内存占用。 */
    private fun evictBackgroundsIfOversized() {
        val overflow = backgroundCache.size - BACKGROUND_CACHE_MAX
        if (overflow <= 0) return
        backgroundCache.entries
            .sortedBy { it.value.lastUsedAt }
            .take(overflow)
            .forEach { backgroundCache.remove(it.key, it.value) }
    }

    /**
     * cover 铺满裁剪。
     *
     * 1.5.0 修复：浮点缩放宽度经 toInt() 截断可能比目标小 1px（如 900 * (660/900f) = 659.999…），
     * 此时 getSubimage(0, 0, targetW, targetH) 的 (x + width) 会超出 raster 触发
     * RasterFormatException —— 概率随背景图尺寸不同而不同（随机背景下"有概率加载失败"）。
     * 修复：向上取整（ceil）并保证缩放结果不小于目标尺寸，使居中偏移恒为非负且不越界。
     */
    private fun coverImage(source: BufferedImage, targetWidth: Int, targetHeight: Int): BufferedImage {
        if (source.width <= 0 || source.height <= 0) {
            // 退化输入兜底：返回纯黑目标尺寸画布，不再传播异常
            return BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        }
        val scale = maxOf(
            targetWidth.toFloat() / source.width,
            targetHeight.toFloat() / source.height,
        )
        val scaledWidth = kotlin.math.ceil(source.width * scale).toInt().coerceAtLeast(targetWidth)
        val scaledHeight = kotlin.math.ceil(source.height * scale).toInt().coerceAtLeast(targetHeight)
        val scaled = BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(source, 0, 0, scaledWidth, scaledHeight, null)
        } finally {
            g.dispose()
        }
        // 缩放尺寸 ≥ 目标尺寸恒成立（见上），偏移非负且偏移+目标 ≤ 缩放尺寸
        val offsetX = (scaledWidth - targetWidth) / 2
        val offsetY = (scaledHeight - targetHeight) / 2
        return scaled.getSubimage(offsetX, offsetY, targetWidth, targetHeight)
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

    /** 半透明暗化遮罩（保证前景可读），直接烘焙进背景。 */
    private fun darken(source: BufferedImage): BufferedImage {
        val result = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val g = result.createGraphics()
        try {
            g.drawImage(source, 0, 0, null)
            g.color = Color(0x00, 0x00, 0x00, 92)
            g.fillRect(0, 0, source.width, source.height)
        } finally {
            g.dispose()
        }
        return result
    }

    // ---------- HTTP ----------

    /** 简单 HTTP GET。 */
    private fun httpGet(url: String, timeoutMillis: Int): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.setRequestProperty("User-Agent", "KERONGPenguin")
            connection.instanceFollowRedirects = true
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
