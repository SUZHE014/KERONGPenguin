package cn.kerong.penguin.spigot.render

import cn.kerong.bot.provider.BotShared
import cn.kerong.bot.tools.PluginFileLog
import com.alibaba.fastjson.JSON
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
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.imageio.ImageIO

/**
 * 玩家信息卡片渲染器（/查信息，1.5.2 前为 /个人信息）。
 *
 * 设计参考（issue #1）：图片渲染界面参考自 BaiZhouziYou / 新亭泪
 * 发布在 EasyBot 资源市场的图片模板（https://xintinglei.cn/）——早期版本
 * 的毛玻璃深色风格模板是参照该模板图复刻的，其后的独立改进见各版本
 * 更新日志。感谢原作者的设计。
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
 *
 * 1.5.2：统计扩为 9 项（新增屠龙次数 / 击杀玩家次数 / 死亡次数），
 * 布局 3 列 × 3 行，画布高度 660 → 760，其余绘制逻辑不变。
 *
 * 1.5.3：卡片高度随统计项数自适应：
 * - 依赖插件（Vault 金币 / DeluxeTags 称号 / PlayerPoints 点券 / 签到系统）
 *   未安装或未开启时对应统计项直接不展示（见 QueryInfoService.buildItems）；
 * - 统计面板行数随之减少，画布高度按 472 + 96×行数 自适应
 *   （9/5 项 = 760，6~8 项 = 760 或 664，4 项 = 664，2~3 项 = 664 或 568），
 *   底部不再留白，背景图按实际高度处理与缓存。
 *
 * 1.5.4.4：背景不再模糊，直接叠一层暗色遮罩：
 * - 金字塔模糊整段移除，背景保留原图全部细节与锐度；
 * - 暗色遮罩保持单遍合成（等效总黑度约 41%），前景可读性与历史版本一致
 *   （背景原图直出，毛玻璃观感由卡片背后的局部模糊承担）。
 *
 * 1.5.7：全部图片渲染（查信息 / 查在线 / 在线排行榜）默认改用内置背景图
 * （深灰主体 + 深绿装饰，随 JAR 分发，各自配置开关默认 true，见
 * [InfoCardAssets.resolveBackground]）；img/ 目录随机图改为可选行为
 * （开关关闭），且 img 无可用图片时自动回退内置图。
 */
object InfoCardRenderer {

    /** 画布宽度。 */
    internal const val WIDTH = 900

    /** 满项卡片高度（9 项 / 3 行；1.5.3 起实际高度随项数自适应，见 [cardHeight]）。 */

    /** 配色（毛玻璃深色系）。 */
    private val COLOR_EYEBROW = Color(0x93, 0xA7, 0xBC)
    private val COLOR_LABEL = Color(0x9F, 0xB3, 0xC8)

    /** 副标题条小标签填充色——深色卡变体（1.7.3 覆盖版五轮自适应配色：
     * 0x8296AA → 0xB8C8D8。三轮纯调深在浅色背景下仍看不清；四轮改近白
     * 0xD9E2EC + 22f 加粗 + 黑 55% 描边——用户反馈「不美观、太突兀」；
     * 五轮改为自适应：亮底自动切深色标签（[COLOR_SUB_LABEL_DARK]），
     * 深底用柔和浅蓝灰，描边机制整个移除，字号 20f 常规字重。
     * 只作用于副标题条标签，眉题 / 身份条等其他标签色不变）。 */
    private val COLOR_SUB_LABEL = Color(0xB8, 0xC8, 0xD8)

    /** 副标题条小标签填充色——亮色卡变体（1.7.3 覆盖版五轮新增）：深蓝灰
     * 0x5C7288，浅色背景（亮底 × 副标题灰度叠加后仍亮的卡）上清晰可读。 */
    private val COLOR_SUB_LABEL_DARK = Color(0x5C, 0x72, 0x88)
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
        /** 已预处理的背景（尺寸须为 WIDTH × cardHeight(items.size)，含暗色遮罩）；null 表示无背景。 */
        val background: BufferedImage? = null,
        /** 服务器名称（1.7.3：副标题条「服务器」项展示）。 */
        val serverName: String = "",
        /** 更新时间（HH:mm，调用方格式化好传入；1.7.3 副标题条「更新时间」项展示）。 */
        val updateTime: String = "",
    )

    /**
     * 卡片高度（1.5.3：随统计项数自适应；1.7.3：插入副标题条后整体 +100；
     * 1.7.3 覆盖版：统计面板上移 4px（等距微调），高度同步 -4）：
     * - 顶部欢迎区 + 身份条 + 副标题条共 428px，底部留白 24px；
     * - 统计面板 = 面板头 84 + 网格行数 × 96 + 底垫 26；
     * - 网格列数与绘制逻辑一致（≥ 6 项 3 列，否则 2 列避免孤项）；
     * - 满项（3 行）= 856，少一行减 96px。
     */
    internal fun cardHeight(itemCount: Int): Int {
        val columns = if (itemCount >= 6) 3 else 2
        val rows = (itemCount + columns - 1) / columns
        return 568 + 96 * rows
    }

    /**
     * 渲染卡片并返回 PNG 字节。
     */
    fun render(data: CardData): ByteArray {
        // 1.7.3 覆盖版：渲染耗时计时起点（副标题条第三格展示从开始渲染到
        // 全部面板绘制完成的毫秒数——从开始渲染图片起算，不含接收指令后的
        // 取数 / 头像获取 / 排队环节；全部绘制完成后补画，再编码 PNG）
        val renderStart = System.nanoTime()
        val height = cardHeight(data.items.size)
        val image = BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            // 背景统一走共享入口（cover 裁剪铺满：等比放大不足的边 +
            // 以图片中心裁剪，不拉伸）；返回卡片背后局部模糊上下文
            // （backdrop-filter，模糊度 render.blur-strength），由各卡片绘制前自取
            val backdrop = InfoCardAssets.drawBackground(g, data.background, WIDTH, height)
            drawTopPanel(g, data, backdrop)
            drawIdentityBar(g, data.playerName, backdrop)
            drawSubtitle(g, image, data.serverName, data.updateTime, backdrop)
            drawStatsPanel(g, data.items, height, backdrop)

            // 1.7.3 覆盖版：补画渲染耗时（全部面板已画完；PNG 编码与发送不预先包含）
            val elapsedMs = (System.nanoTime() - renderStart) / 1_000_000L
            drawRenderElapsed(g, elapsedMs)
        } finally {
            g.dispose()
        }
        val output = ByteArrayOutputStream(256 * 1024)
        ImageIO.write(image, "png", output)
        // 1.5.0：PNG 已写出，立即释放单次画布的栅格内存（缓存中的背景/头像不受影响）
        image.flush()
        return output.toByteArray()
    }

    /** 顶部欢迎区（先铺卡片背后的局部模糊背景，再叠半透明底色）。 */
    private fun drawTopPanel(g: Graphics2D, data: CardData, backdrop: InfoCardAssets.CardBackdropContext?) {
        val x = 24f
        val y = 24f
        val w = (WIDTH - 48).toFloat()
        val h = 200f

        // 毛玻璃容器（背后先铺模糊背景）；1.7.2 覆盖版：标题框黑度
        // 可配（render.title-card-gray，默认 80）——旧版硬编码半透明白，
        // 视觉上「透明」且改配置调不动
        backdrop?.paintCard(g, x, y, w, h, 24f)
        g.color = Color(0x00, 0x00, 0x00, RenderSettings.titleCardGray())
        g.fill(RoundRectangle2D.Float(x, y, w, h, 24f, 24f))

        // 头像（右上角，圆角描边；1.6.6.1 覆盖版：零中间图直接绘制——
        // 在画布上裁剪圆角区域后直接把头像缩放画进去，不再先拷贝一份
        // 圆角小图，省一次栅格分配与拷贝）
        val avatarSize = 108
        val avatarX = WIDTH - 24 - 40 - avatarSize
        val avatarY = 24 + (200 - avatarSize) / 2
        if (data.avatar != null) {
            drawRoundedAvatar(g, data.avatar, avatarX, avatarY, avatarSize)
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

    /** 玩家身份条（先铺卡片背后的局部模糊背景，再叠半透明底色；
     * 1.7.3：玩家名称项底色改接副标题灰度 render.subtitle-card-gray——
     * 旧版硬编码半透明白 22，灰度配置调不动）。 */
    private fun drawIdentityBar(g: Graphics2D, playerName: String, backdrop: InfoCardAssets.CardBackdropContext?) {
        val x = 24f
        val y = 244f
        val w = (WIDTH - 48).toFloat()
        val h = 64f

        backdrop?.paintCard(g, x, y, w, h, 16f)
        // 1.7.3：玩家名称项属副标题类小卡，接副标题灰度（默认 40）
        g.color = Color(0x00, 0x00, 0x00, RenderSettings.subtitleCardGray())
        g.fill(RoundRectangle2D.Float(x, y, w, h, 16f, 16f))

        g.font = font(21f, false)
        g.color = COLOR_LABEL
        g.drawString("玩家名称", 52f, y + 41f)

        g.font = font(26f, true)
        g.color = COLOR_VALUE
        // 值显示在标签右侧
        g.drawString(playerName, 190f, y + 42f)
    }

    /** 副标题条：服务器 / 更新时间 / 渲染耗时 三张并排小卡（1.7.3 新增，与
     * 其他卡片状态栏同款；底色接副标题灰度 render.subtitle-card-gray；
     * 1.7.3 覆盖版：第三格改「渲染耗时」——数值在全部面板绘制完成后由
     * [drawRenderElapsed] 补画，从开始渲染到绘制完成的毫秒数）。 */
    private fun drawSubtitle(g: Graphics2D, image: BufferedImage, serverName: String, updateTime: String, backdrop: InfoCardAssets.CardBackdropContext?) {
        val y = 328f
        val gap = 14f
        val cardWidth = ((WIDTH - 48) - 2 * gap).toFloat() / 3f
        val cardHeight = 84f

        val cells = listOf(
            "服务器" to serverName,
            "更新时间" to updateTime,
            "渲染耗时" to null as String?,
        )
        cells.forEachIndexed { index, (label, value) ->
            val x = 24f + index * (cardWidth + gap)
            // 1.7.3 覆盖版五轮：先在卡体绘制前采样画布底色（此刻该区域是背景原样），
            // 亮卡 → 深色标签，深卡 → 浅色标签（采样若在绘制后会把灰度叠加两遍误判深卡）
            val brightCard = subtitleCardBright(image, x, y, cardWidth, cardHeight)
            backdrop?.paintCard(g, x, y, cardWidth, cardHeight, 14f)
            // 副标题条接副标题灰度（render.subtitle-card-gray，覆盖版默认 56）
            g.color = Color(0x00, 0x00, 0x00, RenderSettings.subtitleCardGray())
            g.fill(RoundRectangle2D.Float(x, y, cardWidth, cardHeight, 14f, 14f))
            // 1.7.3 覆盖版四轮起：小标签统一走 drawSubLabel；五轮：自适应配色
            drawSubLabel(g, label, x + 24f, y + 34f, brightCard)
            g.font = font(30f, true)
            g.color = COLOR_VALUE
            value?.let { g.drawString(clipToWidth(g, it, (cardWidth - 48).toInt()), x + 24f, y + 68f) }
        }
    }

    /** 副标题条小标签绘制（1.7.3 覆盖版四轮新增共享入口、五轮自适应配色）：
     * 20f 常规字重（四轮 22f 加粗过重——观感「不美观、太突兀」的根源之一）；
     * 配色由 [subtitleCardBright] 按卡底亮度自动切换——亮色卡用
     * [COLOR_SUB_LABEL_DARK]（浅色背景下看不清的根治：深色小字直接立于亮卡），
     * 深色卡用 [COLOR_SUB_LABEL]（柔和浅蓝灰，层级弱于白色加粗数值，不再突兀）；
     * 四轮的八向描边环整个移除（描边叠加正是突兀感的另一半根源）。
     * 五张卡片副标题条标签共用，其他标签色不走此入口。 */
    internal fun drawSubLabel(g: Graphics2D, text: String, x: Float, y: Float, brightCard: Boolean) {
        g.font = font(20f, false)
        g.color = if (brightCard) COLOR_SUB_LABEL_DARK else COLOR_SUB_LABEL
        g.drawString(text, x, y)
    }

    /** 副标题小卡亮暗判定（1.7.3 覆盖版五轮：自适应配色的开关）：
     * 卡片实际亮度 = 画布底色 × (1 - 副标题灰度 / 255)，≥ 150 判亮卡。
     * 采样时机在卡体绘制前，画布该区域此刻就是背景原样（模糊副本与原图
     * 亮度几乎一致，模糊与否、有无背景均可用同一机制）；无背景时画布为
     * 深色兑底 → 判深色卡 → 浅色标签，与实际观感一致。 */
    internal fun subtitleCardBright(image: BufferedImage, x: Float, y: Float, w: Float, h: Float): Boolean {
        val gray = RenderSettings.subtitleCardGray()
        return canvasLuminance(image, x, y, w, h) * (255 - gray) / 255f >= 150f
    }

    /** 画布区域平均亮度（0-255，8×4 网格采样；1.7.3 覆盖版五轮：
     * 副标题小标签自适应配色判定用）。 */
    internal fun canvasLuminance(image: BufferedImage, x: Float, y: Float, w: Float, h: Float): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until 8) {
            for (j in 0 until 4) {
                val px = (x + w * (i + 0.5f) / 8f).toInt().coerceIn(0, image.width - 1)
                val py = (y + h * (j + 0.5f) / 4f).toInt().coerceIn(0, image.height - 1)
                val rgb = image.getRGB(px, py)
                sum += 0.299f * ((rgb shr 16) and 0xFF) + 0.587f * ((rgb shr 8) and 0xFF) + 0.114f * (rgb and 0xFF)
                n++
            }
        }
        return sum / n
    }

    /**
     * 补画副标题条「渲染耗时」数值（1.7.3 覆盖版）：全部面板绘制完成后、
     * PNG 编码前写入——从开始渲染（进入 [render]）到此刻的毫秒数；
     * 不含取数 / 头像获取 / 排队环节（用户口径：不是从接收指令开始算）。 */
    private fun drawRenderElapsed(g: Graphics2D, elapsedMs: Long) {
        val y = 328f
        val gap = 14f
        val cardWidth = ((WIDTH - 48) - 2 * gap).toFloat() / 3f
        val x = 24f + 2 * (cardWidth + gap)
        g.font = font(30f, true)
        g.color = COLOR_VALUE
        g.drawString(clipToWidth(g, "$elapsedMs ms", (cardWidth - 48).toInt()), x + 24f, y + 68f)
    }

    /**
     * 生涯统计面板（1.5.2：9 项 → 3 列 × 3 行；4-5 项时 2 列避免孤项；
     * 1.5.3：高度随项数自适应，缺失依赖的项不渲染；
     * 1.7.3：插入副标题条后下移；1.7.3 覆盖版：436 → 432，与上方三张卡
     * （欢迎面板 / 身份条 / 副标题条）的 20px 间距全卡等距；
     * 先铺卡片背后的局部模糊背景，再叠半透明底色。 */
    private fun drawStatsPanel(g: Graphics2D, items: List<CardItem>, height: Int, backdrop: InfoCardAssets.CardBackdropContext?) {
        val x = 24f
        val y = 432f
        val w = (WIDTH - 48).toFloat()
        val h = (height - 24 - 432).toFloat()

        backdrop?.paintCard(g, x, y, w, h, 24f)
        // 1.7.2 覆盖版：统计面板属列表容器，改走项目卡片黑度
        // （render.item-card-gray，默认 106；标题框黑度让位给顶部欢迎区）
        g.color = Color(0x00, 0x00, 0x00, RenderSettings.itemCardGray())
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

    /**
     * 圆角头像零拷贝绘制（1.6.6.1 覆盖版，三个渲染器共用）。
     *
     * 旧版先 `roundImage` 拷贝一张 size×size ARGB 圆角小图再画到画布，
     * 每页每个头像都多一次栅格分配 + 全图拷贝（在线列表一页 60 人）；
     * 现在直接在目标画布上设置圆角裁剪区域后把头像源图缩放画进去，
     * 无中间图、无额外分配，画完恢复原裁剪区。
     *
     * @param g      目标画布 Graphics2D
     * @param source 头像源图（通常 216×216）
     * @param x      画布上的左上角 x
     * @param y      画布上的左上角 y
     * @param size   目标边长（绘制时双线性缩放到该尺寸）
     * @param radius 圆角半径（像素）
     */
    internal fun drawRoundedAvatar(
        g: Graphics2D,
        source: BufferedImage,
        x: Int,
        y: Int,
        size: Int,
        radius: Int = 16,
    ) {
        if (size <= 0 || source.width <= 0 || source.height <= 0) return
        val oldClip = g.clip
        val oldHint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.clip(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), size.toFloat(), size.toFloat(), radius.toFloat(), radius.toFloat()))
            g.drawImage(source, x, y, size, size, null)
        } finally {
            g.clip = oldClip
            if (oldHint != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldHint)
            }
        }
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
 * 头像获取链（每一步失败都可检测，绝不回落到默认皮肤；1.7.0：本地
 * 贴图链路已移除，统一在线获取）：
 * 1. 外部头像服务多线路（MC-Heads → Minotar → MinePic → Crafatar，
 *    按实测速度排序；基岩玩家走 MC-Heads xuid 线路；各线路均带
 *    帽子层/外层贴图）；
 * 2. Mojang 官方会话服务器 / 玩家名反查兼底（官方线路国内服务器
 *    普遍超时，排末位不挤占预热预算）；
 * 3. 全部失败 → null → 渲染占位图标。
 *
 * 头像与背景均带 TTL 缓存，重复查询不再重复解码 / 请求。
 * 卡片背景从 img/ 目录的候选图中随机挑选一张（每张图的处理结果
 * 按文件签名缓存，重复抽中时不再重新解码）。
 */
object InfoCardAssets {

    /** 头像缓存有效期（毫秒）。 */
    private const val AVATAR_TTL = 10 * 60_000L

    /**
     * 头像内存缓存容量上限（1.6.6.2：64 → 128）。配套 [AVATAR_MEMORY_MAX_BYTES]
     * 门槛，只缓存 PNG 编码后 ≤ 10KB 的小头像；大头像只落磁盘缓存，
     * 不占常驻内存，淘汰条目也可从磁盘秒级回填，上限翻回 128 条。
     */
    private const val AVATAR_CACHE_MAX = 128

    /**
     * 头像进内存缓存的单张大小门槛（1.6.6.2 新增）：PNG 编码后超过
     * 10KB 的头像不进内存缓存，只写入磁盘缓存（下次渲染从磁盘解码，
     * 毫秒级）；磁盘缓存关闭时退回全量内存缓存（避免无任何缓存层）。
     */
    internal const val AVATAR_MEMORY_MAX_BYTES = 10 * 1024

    /** 查询失败后的短期冷却，避免连续请求打爆外部接口。
     *
     * 1.6.6.2 修复「极少玩家在清除缓存后头像获取失败」：磁盘缓存整批
     * 同时到期时，下一张渲染会集中重取几十个头像，容易触发 Mojang
     * 429 / 网络级失败（官方链路进入 5 分钟全局冷却），风暴尾部的少数
     * 玩家本地无数据且外置线路慢 → 获取失败。旧版把这类**网络抖动型
     * 失败**也写入本冷却（60 秒内重试无门，反复查询都判失败）。现在
     * 只有确定性失败（404 名字无正版账号等）才写冷却；网络抖动型
     * 失败不写（见 [networkTrouble]），下次查询立即重试。 */
    private const val MISS_TTL = 60_000L

    /** 名字反查负缓存（404 = 名字无正版账号）的有效期（1.6.2：从永久改为 30 分钟）。 */
    private const val NEGATIVE_UUID_TTL = 30 * 60_000L

    /**
     * 1.6.4：Mojang 官方接口网络级失败后的全局冷却（5 分钟）。
     *
     * 「大部分玩家头像获取失败」根因：服务器网络访问 sessionserver.mojang.com /
     * api.mojang.com 不稳定（中国大陆线路大面积超时/阻断）时，旧版每个玩家都要
     * 先等 5 秒超时才能失败，在线列表 8 秒预热预算全部耗在等超时上——结果
     * 大部分玩家头像取不到。现在任一次网络级失败（超时/IO 异常/429/5xx）
     * 会开启全局冷却：冷却期内所有玩家直接跳过 Mojang 官方链路（走 Crafatar
     * CDN 回退或直接失败），不再逐个等超时。
     */
    private const val MOJANG_NETWORK_COOLDOWN_MS = 5 * 60_000L

    /** 最近一次 Mojang 官方接口网络级失败时间戳（0 = 无记录）。 */
    @Volatile
    private var mojangNetworkFailureAt = 0L

    /**
     * 本次头像获取尝试的网络抖动标记（1.6.6.2 新增，ThreadLocal：
     * 渲染线程一次只处理一个获取请求，无串扰）。任一链路发生网络级
     * 失败（超时 / IO 异常 / 官方链路冷却期内跳过 / 429 / 5xx）时置
     * true：最终失败属于网络抖动型，不写入 missCache，下次查询立即
     * 重试（修复「清除缓存后集中重取风暴中极少玩家获取失败且 60 秒
     * 内重试无门」的问题）；确定性失败（404 等）仍写冷却保护外部接口。
     */
    private val networkTrouble = ThreadLocal.withInitial { false }

    /** Mojang 官方接口是否处于网络冷却期（冷却内跳过官方链路）。 */
    private fun mojangInCooldown(): Boolean {
        val at = mojangNetworkFailureAt
        return at > 0L && System.currentTimeMillis() - at < MOJANG_NETWORK_COOLDOWN_MS
    }

    /** 记录一次 Mojang 官方接口网络级失败（开启全局冷却 + 标记本次尝试网络抖动）。 */
    private fun markMojangUnreachable() {
        mojangNetworkFailureAt = System.currentTimeMillis()
        networkTrouble.set(true)
    }

    /** 输出头像尺寸（渲染前再缩到展示尺寸）。 */
    private const val FACE_SIZE = 216

    /** 头像请求参数。 */
    data class AvatarRequest(
        val uuid: UUID?,
        val playerName: String,
    )

    private class CacheEntry(val image: BufferedImage, val createdAt: Long)

    private class NegativeEntry(val createdAt: Long)

    /**
     * 带获取方式标签的头像获取结果（1.6.6.2 新增）：标签用于把「头像
     * 获取用的方法」写进日志（本地贴图 / Mojang 官方 / 各外置线路），
     * 方便运维排查某玩家的头像到底走的哪条链路。
     */
    private class FetchedFace(val image: BufferedImage, val method: String)

    /** 磁盘缓存命中结果（image + 文件字节数，字节数用于 10KB 内存门槛判定）。 */
    private class DiskAvatar(val image: BufferedImage, val fileSize: Long)

    /**
     * 进行中的头像获取门闩（1.6.6.2 覆盖版新增）。
     *
     * 第一个到达的请求成为「执行者」真正走网络获取；并发到达的其余
     * 请求成为「等待者」，等待执行者完成后直接取其结果——同一玩家
     * 的并发查询只产生一次网络请求与一套中间对象。
     */
    private class FetchLatch {
        val done = java.util.concurrent.CountDownLatch(1)

        /** 执行者的获取结果（成功时直接经门闩传给等待者，绕过 10KB 内存门槛）。 */
        @Volatile
        var image: BufferedImage? = null
    }

    /**
     * 正在进行的头像获取（key → 门闩）。1.6.6.2 覆盖版：启动 / 刷屏场景
     * 下多张渲染并发预热同一批玩家的头像时，旧版每个渲染都各自发起一
     * 轮完整获取（N 个并发渲染 = N 倍网络请求、解码与 PNG 编码分配，
     * 是启动期内存与 IO 突增的放大器）；现在同 key 只有一个执行者。
     */
    private val inFlightFetches = ConcurrentHashMap<String, FetchLatch>()

    /** 等待并发获取结果的最长时间（与在线列表头像预热预算同量级）。 */
    private const val IN_FLIGHT_WAIT_MILLIS = 8_000L

    /**
     * 已记录过获取方式的玩家（key → 上次记录的方式）。
     * 1.6.6.2 覆盖版：获取方式日志改「仅写文件日志 + 会话内按玩家去重」
     * ——同一玩家首次获取与更换线路时各记一条，不再逐次记录（旧版
     * 一页 60 人在线列表即 60 行控制台 + 60 行文件，刷屏且刷盘）。
     */
    private val loggedAvatarSources = ConcurrentHashMap<String, String>()

    /** 获取方式日志的去重表容量上限（超过即整体重置，防长期运行缓慢增长）。 */
    private const val LOGGED_SOURCES_MAX = 2048


    /** 头像缓存：uuid（或 name:name）→ 正面脸部图。 */
    private val avatarCache = ConcurrentHashMap<String, CacheEntry>()

    /** 近期失败记录：键 → 时间。 */
    private val missCache = ConcurrentHashMap<String, NegativeEntry>()

    /** 预处理背景缓存条目：文件签名 + 尺寸签名 + 处理完的图 + 最近使用时间 + 占用字节。 */
    private class BackgroundEntry(
        val stamp: Long,
        val sizeKey: Long,
        val image: BufferedImage,
        val bytes: Long,
        @Volatile var lastUsedAt: Long,
    ) {
        /**
         * 卡片背后局部模糊副本：惰性生成一次（1.7.0 起模糊度可配），
         * 随本条目淘汰 / 覆盖 / 空闲释放一起 flush，不单独占缓存配额。
         */
        @Volatile
        var backdrop: BufferedImage? = null

        /** 模糊副本的栅格字节占用（计入背景缓存字节预算，见 evict）。 */
        @Volatile
        var backdropBytes: Long = 0L

        /** 生成模糊副本时使用的模糊度（render.blur-strength；配置变化后重生成）。 */
        @Volatile
        var backdropStrength: Int = 0
    }

    /** 预处理背景缓存：文件绝对路径 → 条目（0.1.5.3 起支持多张图随机切换）。 */
    private val backgroundCache = ConcurrentHashMap<String, BackgroundEntry>()

    /** 背景缓存容量上限（张）；超出时淘汰最久未使用的，控制内存占用。 */
    private const val BACKGROUND_CACHE_MAX = 8

    /**
     * 背景缓存字节预算（1.5.1）：缓存图像栅格总占用上限。
     * 大画布（查在线长图）背景单张可达 MB 级，仅按张数限制不足以约束内存，
     * 超预算时按最久未使用淘汰，配合张数上限双保险。
     */
    private const val BACKGROUND_CACHE_MAX_BYTES = 36L * 1024 * 1024

    /**
     * 在线列表背景处理高度上限（1.5.1）：长图背景不再按整幅画布高度处理，
     * 而是封顶后由渲染器拉伸铺满（暗色遮罩背景下视觉无差异），
     * 避免百人在线时背景栅格膨胀到数十 MB。
     */
    internal const val ONLINE_BACKGROUND_MAX_HEIGHT = 1600

    /** 背景图子采样解码的最长边限制（像素）：超长边照片降采样后再解码，防整图解码 OOM（0.1.5.5）。 */
    private const val BACKGROUND_MAX_DIM = 1600

    /**
     * 卡片背后局部模糊的强度映射（1.7.0：模糊度可配 render.blur-strength，
     * 0-100）。模糊度映射到「降采样倍率 + 盒式模糊半径」两档参数：
     * - 降采样倍率 d = 2 + strength * 6 / 100（2 ~ 8 倍）；
     * - 盒式模糊半径 r = 1 + strength * 2 / 100（1 ~ 3，作用在降采样
     *   后的小图上，两趟水平 + 垂直）；
     * - strength=100 等效旧版固定参数（d=8、r=3，观感约 40px 柔化）；
     *   默认 50（d=5、r=2，观感约 20px，比旧版更轻）；
     * - strength=0 不做任何模糊（无模糊副本，卡片仅叠半透明底色）。
     */
    private fun blurDownscaleOf(strength: Int): Int = 2 + Math.round(strength * 6f / 100f).toInt()

    private fun blurBoxRadiusOf(strength: Int): Int = 1 + Math.round(strength * 2f / 100f).toInt()

    /** 近期解码失败的背景文件（路径 → 失败时间），10 分钟内不再抽中，避免坏图反复触发解码（0.1.5.5）。 */
    private val backgroundFailures = ConcurrentHashMap<String, Long>()

    /**
     * 内置专属背景（1.5.6）：随 JAR 分发的默认底图，深灰色主体 + 深绿色装饰
     * （径向渐变：中心深灰 → 四周渐入深绿）。
     * 1.5.7：主体由黑色改为深灰色（与半透明卡片叠加色保持 ΔL ≥ 20，
     * 方框卡片边界清晰可辨），并拆为两张同风格图：
     * - [BUILTIN_BACKGROUND_LEADERBOARD]：排行榜竖图（1200×1800）；
     * - [BUILTIN_BACKGROUND_CARD]：查信息卡片 / 查在线列表共用横图（1800×1200）。
     * 设计已自带可读性，不叠暗色遮罩。解码源图按类路径各缓存一次，
     * 各尺寸的 cover 结果按「资源路径 + 尺寸」复用 backgroundCache
     * （零 stamp 永不失效，随 LRU / 字节预算淘汰）。
     */
    const val BUILTIN_BACKGROUND_LEADERBOARD = "backgrounds/leaderboard-default.png"
    const val BUILTIN_BACKGROUND_CARD = "backgrounds/card-default.png"

    /** 内置背景解码源图（资源路径 → 源图；类路径资源缺失时无条目，渲染层退回纯色渐变）。 */
    private val builtinBackgroundSources = ConcurrentHashMap<String, BufferedImage>()

    /** 已探测过的资源路径（缺失时负缓存，避免每次查询都开流探测）。 */
    private val builtinBackgroundResolvedPaths = ConcurrentHashMap.newKeySet<String>()

    /** 记录的玩家名 → 正版 UUID（Mojang API），含未查到的负缓存。 */
    private val premiumUuidCache = ConcurrentHashMap<String, Any>()

    /**
     * 获取玩家皮肤正面贴图头像（异步线程调用）。
     * 返回 FACE_SIZE × FACE_SIZE 的脸部图像；无皮肤数据时返回 null。
     *
     * 查找顺序：内存缓存 → 磁盘缓存（plugins/KERONGPenguin/cache/avatars/，
     * 默认 3 小时，render.avatar-cache-hours 可配置；过期文件由后台清扫
     * 线程删除，命中即回填内存）→ 在线获取（外部多线路 → Mojang 官方
     * API 兼底）。
     *
     * 1.7.0：本地获取链路（在线玩家 Profile / 本服存档 NBT 解析皮肤贴图）
     * 已移除——贴图文件托管在 textures.minecraft.net，国内服务器普遍
     * 不可达导致大部分头像获取失败；现统一走外部头像线路（各线路均带
     * 帽子层/外层贴图），全部失败才记入失败冷却。
     *
     * 每次成功获取（含磁盘缓存命中）把获取方式写入日志（[logAvatarSource]）；
     * 单张 PNG 超过 [AVATAR_MEMORY_MAX_BYTES]（10KB）的头像只走磁盘缓存，
     * 不再进内存缓存（内存缓存条目上限 128 条，见 [AVATAR_CACHE_MAX]）。
     */
    fun fetchAvatar(request: AvatarRequest): BufferedImage? {
        val key = request.uuid?.toString() ?: "name:${request.playerName.lowercase()}"
        val now = System.currentTimeMillis()
        // 1.6.6.2：每次尝试重置网络抖动标记（渲染线程复用，防上次尝试的
        // 抖动状态串扰本次失败分类）
        networkTrouble.set(false)

        // 1. 命中内存缓存直接返回（内存命中不算获取动作，首次获取时已记录来源日志）
        val cached = avatarCache[key]
        if (cached != null && now - cached.createdAt < AVATAR_TTL) return cached.image

        // 2. 近期失败冷却
        val missed = missCache[key]
        if (missed != null && now - missed.createdAt < MISS_TTL) return null

        // 3. 磁盘缓存（默认 3 小时，render.avatar-cache-hours 可配置；
        // 命中按 10KB 门槛决定是否回填内存，重启后不重复请求外部接口；缓存关闭时直接跳过）
        ensureAvatarSweeper()
        val disk = loadDiskAvatar(key, now)
        if (disk != null) {
            // 1.6.6.2：超过 10KB 的大头像不进内存缓存（只走磁盘），
            // 小头像回填内存供后续渲染秒级复用
            if (disk.fileSize <= AVATAR_MEMORY_MAX_BYTES) {
                avatarCache[key] = CacheEntry(disk.image, now)
                evictIfOversized()
            }
            missCache.remove(key)
            logAvatarSource(request, "本地磁盘缓存（${disk.fileSize / 1024}KB）")
            return disk.image
        }

        // 4. 在线获取：外部多线路（MC-Heads → Minotar → MinePic → Crafatar）
        //    优先，Mojang 官方 API 兼底（1.7.0：本地链路已移除）。
        //
        // single-flight 去重：并发查询同一玩家时只有第一个请求真正走网络
        // （执行者），其余请求等待其完成后直接取结果（等待者），超时未等到
        // 按获取失败渲染占位块。启动 / 刷屏场景下多张渲染并发预热同一批
        // 头像时，网络请求与中间对象分配量按并发数成倍削减
        // （见 [inFlightFetches]）。
        val latch = FetchLatch()
        val existing = inFlightFetches.putIfAbsent(key, latch)
        if (existing != null) {
            try {
                existing.done.await(IN_FLIGHT_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            return existing.image
        }
        try {
            val fetched: FetchedFace? = fetchViaExternalChain(request)

            if (fetched != null) {
                cacheAndStoreAvatar(key, fetched.image, now)
                missCache.remove(key)
                logAvatarSource(request, fetched.method)
                latch.image = fetched.image
                return fetched.image
            }
            // 1.6.6.2 修复「清除缓存后极少玩家头像获取失败」：区分失败类型——
            // 网络抖动型（任一链路超时 / IO 异常 / 官方链路冷却期内跳过 / 429 / 5xx）
            // 不写 missCache，下次查询立即重试；确定性失败（404 等）才写 60 秒冷却
            if (networkTrouble.get()) {
                trimMissCache()
                logAvatarMiss(request, retryable = true)
                return null
            }
            missCache[key] = NegativeEntry(now)
            trimMissCache()
            logAvatarMiss(request, retryable = false)
            return null
        } finally {
            inFlightFetches.remove(key, latch)
            latch.done.countDown()
        }
    }

    // ---------- 头像批量预热（1.7.2：并发加速 + 不截断在途下载） ----------

    /** 头像批量预热线程数（网络 IO 密集，不占渲染 CPU 预算；大名单并行收齐；自动模式默认 8）。 */
    private const val AVATAR_BATCH_THREADS = 8

    /** 实际生效的头像批量预热线程数（render.custom-render-threads 开关开启时用 avatar-threads，否则默认 8；池创建时定格）。 */
    private val AVATAR_THREADS: Int =
        RenderSettings.customAvatarThreads().takeIf { it > 0 } ?: AVATAR_BATCH_THREADS

    /**
     * 批量预热渲染等待预算（毫秒）：预算内尽量收齐头像再渲染发送；超预算的
     * 玩家本轮渲染占位块，但其下载任务不取消——后台继续，完成后进缓存，
     * 下一轮查询即显示真实头像。
     */
    internal const val AVATAR_BATCH_BUDGET_MILLIS = 12_000L

    /** 批量预热迟到硬上限（毫秒）：预算过后仍在跑的任务到点取消（防慢源链路占满线程池）。 */
    private const val AVATAR_BATCH_HARD_CANCEL_MILLIS = 40_000L

    /** 头像批量预热共享线程池（在线列表 / 排行榜 / 财富榜 / 查信息共用，避免各建一池线程数翻倍；
     * 1.7.3 覆盖版：线程数可经 render.custom-render-threads.avatar-threads 自定义（重启生效））。 */
    private val avatarBatchPool by lazy {
        Executors.newFixedThreadPool(AVATAR_THREADS) { runnable ->
            Thread(runnable, "PenguinAvatarFetch").apply { isDaemon = true }
        }
    }

    /** 批量预热迟到取消调度器（单守护线程）。 */
    private val avatarBatchReaper by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "PenguinAvatarReaper").apply { isDaemon = true }
        }
    }

    /**
     * 批量预热头像（在线列表 / 排行榜 / 财富榜 / 查信息共用入口）。
     *
     * 1.7.2 重做并发策略。旧版各服务自建 4 线程小池 + invokeAll(8 秒) 硬预算，
     * 到点会**中断**在途下载——慢速可用的源读到一半被切断，已下载字节全部
     * 作废，本轮占位块；大名单尾部玩家还排队等不到线程。新版：
     * - 8 线程共享池并行（网络 IO 密集），大名单也几秒内收齐，QQ 回复不久等；
     * - 预算内逐个收结果，超预算先渲染发送，任务**不取消**：后台下载完成后
     *   进内存/磁盘缓存，下一轮查询即有真实头像；
     * - 迟到硬上限兜底取消，防个别玩家慢源链路长期占满预热线程池；
     * - 返回 Map 只含获取成功的玩家（失败/超预算由调用方渲染占位块）。
     */
    fun fetchAvatarBatch(
        requests: List<AvatarRequest>,
        budgetMillis: Long = AVATAR_BATCH_BUDGET_MILLIS,
    ): Map<String, BufferedImage> {
        if (requests.isEmpty()) return emptyMap()
        val futures = requests.map { request ->
            avatarBatchPool.submit(Callable { request.playerName to fetchAvatar(request) })
        }
        val result = mutableMapOf<String, BufferedImage>()
        val deadline = System.currentTimeMillis() + budgetMillis
        for (future in futures) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0) {
                try {
                    val pair = future.get(remaining, TimeUnit.MILLISECONDS)
                    pair.second?.let { face -> result[pair.first] = face }
                    continue
                } catch (_: TimeoutException) {
                    // 该玩家超渲染等待预算：本轮占位块（任务不取消，后台跑完进缓存）
                } catch (_: Throwable) {
                    continue
                }
            }
            // 预算已尽：不阻塞收尾，但已完成的任务仍然捡回结果
            if (future.isDone) {
                try {
                    val pair = future.get()
                    pair.second?.let { face -> result[pair.first] = face }
                } catch (_: Throwable) {
                }
            }
        }
        scheduleBatchHardCancel(futures)
        return result
    }

    /** 安排迟到硬取消（已完成的任务 cancel 是空操作）。 */
    private fun scheduleBatchHardCancel(futures: List<Future<*>>) {
        try {
            avatarBatchReaper.schedule({
                for (future in futures) {
                    try {
                        future.cancel(true)
                    } catch (_: Throwable) {
                    }
                }
            }, AVATAR_BATCH_HARD_CANCEL_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
    }

    /**
     * 获取成功的来源日志（1.6.6.2：把头像获取用的方法写到日志里）。
     *
     * 1.6.6.2 覆盖版修复「获取方式日志泄漏到控制台」：
     * - 仅写入插件日志文件（PluginFileLog，logs/qq/qq-bind-日期.log），
     *   不再进控制台——旧版走 log_info 双写，一页 60 人在线列表即向
     *   控制台刷 60 行，刷屏且每行都开一次日志文件句柄；
     * - 会话内按玩家去重（[loggedAvatarSources]）：同一玩家首次获取
     *   与更换获取方式时各记一条；内存缓存命中不记录（非获取动作）。
     */
    private fun logAvatarSource(request: AvatarRequest, method: String) {
        try {
            val key = request.uuid?.toString() ?: "name:${request.playerName.lowercase()}"
            val previous = loggedAvatarSources.putIfAbsent(key, method)
            if (previous == null) {
                if (loggedAvatarSources.size > LOGGED_SOURCES_MAX) loggedAvatarSources.clear()
                PluginFileLog.write("[头像] ${request.playerName} ← $method")
            } else if (previous != method) {
                loggedAvatarSources[key] = method
                PluginFileLog.write("[头像] ${request.playerName} ← $method（线路已切换，上次为 $previous）")
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 获取失败日志（本地与外置链路均未命中；占位块渲染属正常降级）。
     * 1.6.6.2：[retryable] = true 表示网络抖动型失败（未写失败冷却，
     * 下次查询立即重试）；false = 确定性失败（60 秒冷却内不重试）。
     *
     * 1.6.6.2 覆盖版：同样改仅写文件日志（失败风暴日逐玩家逐次刷控制
     * 台屏同样是泄漏，且失败已由 missCache 冷却与 networkTrouble 机制
     * 约束，不需要控制台实时提示）。
     */
    private fun logAvatarMiss(request: AvatarRequest, retryable: Boolean) {
        try {
            if (retryable) {
                PluginFileLog.write("[头像] ${request.playerName} 获取失败（本次链路遇网络抖动，未写失败冷却，下次查询立即重试；本张渲染占位块）")
            } else {
                PluginFileLog.write("[头像] ${request.playerName} 获取失败（本地与外置链路均未命中，60 秒内不重试；本张渲染占位块）")
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 获取成功后的缓存落位（1.6.6.2）：
     * - PNG 编码一次，磁盘写入与 10KB 内存门槛共用同一份数据；
     * - PNG ≤ [AVATAR_MEMORY_MAX_BYTES] → 内存缓存 + 磁盘缓存；
     * - PNG 超门槛 → 只写磁盘缓存（下次渲染从磁盘毫秒级解码）；
     * - 编码失败（罕见）或磁盘缓存关闭 → 退回全量内存缓存。
     */
    private fun cacheAndStoreAvatar(key: String, image: BufferedImage, now: Long) {
        val png = encodeAvatarPng(image)
        if (png != null) {
            writeDiskAvatar(key, png)
        }
        val diskEnabled = avatarDiskTtlMillis() > 0L
        if (png == null || !diskEnabled || png.size <= AVATAR_MEMORY_MAX_BYTES) {
            avatarCache[key] = CacheEntry(image, now)
            evictIfOversized()
        }
    }

    /** 清空全部素材缓存（插件停用时调用，释放图像内存）。 */
    fun clearCaches() {
        // 1.6.5：先 flush 再清空——立即释放各图像的栅格与绘制缓存占用的内存，
        // 不等 GC 兜底（缓存里的背景长图可达 MB 级）
        for (entry in avatarCache.values) {
            try { entry.image.flush() } catch (_: Throwable) {}
        }
        for (entry in backgroundCache.values) {
            try { entry.image.flush() } catch (_: Throwable) {}
            try { entry.backdrop?.flush() } catch (_: Throwable) {}
        }
        for (source in builtinBackgroundSources.values) {
            try { source.flush() } catch (_: Throwable) {}
        }
        avatarCache.clear()
        missCache.clear()
        premiumUuidCache.clear()
        backgroundCache.clear()
        builtinBackgroundSources.clear()
        builtinBackgroundResolvedPaths.clear()
        mojangNetworkFailureAt = 0L
        lastRenderCompletedAt = 0L
        // 1.6.6.2 覆盖版：清空并发获取与来源日志去重表
        inFlightFetches.clear()
        loggedAvatarSources.clear()
        // 1.6.6.1：停用磁盘缓存清扫线程（下次使用头像时自动重启）
        avatarSweeper?.shutdownNow()
        avatarSweeper = null
        // 1.6.6.2：停用渲染空闲释放线程（下次渲染完成时自动重启）
        idleReleaser?.shutdownNow()
        idleReleaser = null
    }


    /** 缓存超限时淘汰最旧条目（1.6.5：淘汰即 flush，栅格立即释放）。 */
    private fun evictIfOversized() {
        if (avatarCache.size <= AVATAR_CACHE_MAX) return
        val toRemove = avatarCache.entries
            .sortedBy { it.value.createdAt }
            .take(AVATAR_CACHE_MAX / 8 + 1)
        for (entry in toRemove) {
            if (avatarCache.remove(entry.key, entry.value)) {
                try { entry.value.image.flush() } catch (_: Throwable) {}
            }
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

    // ---------- 皮肤贴图解析链（1.7.0：仅 Mojang 官方线路，本地链路已移除） ----------

    /**
     * 外置方式获取头像（1.6.8：外部多线路优先，Mojang 官方 API 移到最后兼底）。
     *
     * 顺序调整原因（用户反馈「排行榜大多数头像没显示」）：官方线路
     * （sessionserver / api.mojang.com）在国内服务器普遍不可达，此前
     * 排首位时，排行榜 4 线程 × 8 秒预热预算全部烧在逐玩家 3+5 秒的
     * 官方超时上（且官方冷却结束后又会重复烧一轮），多数头像在预算
     * 耗尽后被整批取消。现改为快线路（MC-Heads ≈ 0.4s 等）先行，
     * 官方 API 仅在四条外部线路全部失败后才尝试——外部线路全部不可达
     * 的情况下才轮到官方，此时多等几秒可接受。
     *
     * 官方链路（会话服务器直查 + 玩家名反查，网络冷却见
     * [MOJANG_NETWORK_COOLDOWN_MS]）解析贴图后下载裁脸。
     */
    private fun fetchViaExternalChain(request: AvatarRequest): FetchedFace? {
        val external = fetchExternalAvatar(request)
        if (external != null) return external
        val skinUrl = resolveMojangSkinUrl(request)
        if (skinUrl != null) {
            downloadAndCropFace(skinUrl)?.let { return FetchedFace(it, "Mojang官方API") }
        }
        return null
    }

    /**
     * 下载皮肤贴图并裁脸（含帽子层叠加；下载失败 / 尺寸异常返回 null；
     * 中间贴图用后即 flush）。
     */
    private fun downloadAndCropFace(skinUrl: String): BufferedImage? {
        val skin = downloadSkin(skinUrl) ?: return null
        val cropped = cropFace(skin, FACE_SIZE)
        try { skin.flush() } catch (_: Throwable) {}
        return cropped
    }

    /**
     * Mojang 官方原生 API 解析皮肤贴图 URL（用户要求始终保留）：
     * 会话服务器 UUID 直查 + 玩家名反查。
     *
     * 1.6.2 修复「极少个数玩家头像始终无法获取」：
     * 旧版第 4 步要求「名字反查到的正版 UUID ≠ 传入 UUID」才查询会话服务器，
     * 而 Mojang 极少数老账号的 UUID 不是 v4（如 v3），第 3 步的 v4 快路径
     * 会跳过它们，第 4 步又因「相同即跳过」被挡——两层全挡 = 头像永远失败；
     * 现在只要名字反查到正版 UUID 就查询会话服务器（传入 UUID 是 v4 且
     * 第 3 步已查过的除外，避免重复请求）。
     *
     * 1.6.4：官方链路在网络冷却期（见 [MOJANG_NETWORK_COOLDOWN_MS]）内
     * 整体跳过，避免每个玩家都等一次 5 秒超时；跳过时由外部多线路回退
     * 兜底（见 [fetchExternalAvatar]）。
     */
    private fun resolveMojangSkinUrl(request: AvatarRequest): String? {
        // 官方链路网络冷却期内不再逐玩家等超时（1.6.4）；
        // 1.6.6.2：冷却期内跳过属于网络抖动型失败（本次尝试标记，
        // 最终失败时不写 missCache，下次查询立即重试）
        if (mojangInCooldown()) {
            networkTrouble.set(true)
            return null
        }

        val uuid = request.uuid
        // 3. 正版 UUID（v4 快路径）：会话服务器查询（未知 UUID 返回 204，可检测失败）。
        //    非 v4 的老账号正版 UUID 走第 4 步名字反查后同样能查到
        if (uuid != null && uuid.version() == 4) {
            sessionServerSkinUrl(uuid)?.let { return it }
        }

        // 4. 离线 UUID / 无 UUID / 非 v4 正版 UUID：按玩家名查正版账号（非正版名返回 404）
        val premiumUuid = resolvePremiumUuidByName(request.playerName)
        if (premiumUuid != null &&
            (uuid == null || premiumUuid != uuid || uuid.version() != 4)
        ) {
            sessionServerSkinUrl(premiumUuid)?.let { return it }
        }

        return null
    }

    /** base64 贴图 JSON → 皮肤 URL。 */
    private fun decodeSkinUrl(base64: String): String? = try {
        val json = JSON.parseObject(String(Base64.getDecoder().decode(base64), Charsets.UTF_8))
        json.getJSONObject("textures")?.getJSONObject("SKIN")?.getString("url")
    } catch (_: Throwable) {
        null
    }

    /**
     * 会话服务器查询贴图 URL。
     *
     * 1.6.4：超时 5 秒→3 秒（更快释放预热预算），并区分失败类型：
     * - 204 / 404：确定性无数据（该 UUID 无皮肤记录），不触发冷却；
     * - 429 / 5xx / 网络异常（超时/连接失败）：网络级失败，开启全局冷却
     *   （[markMojangUnreachable]，5 分钟内后续玩家不再访问官方链路，
     *   避免在线列表预热预算全部耗在逐玩家等超时上）。
     */
    private fun sessionServerSkinUrl(uuid: UUID): String? {
        return try {
            val url =
                "https://sessionserver.mojang.com/session/minecraft/profile/${uuid.toString().replace("-", "")}"
            val (code, body) = httpGetWithStatus(url, 3000)
            when {
                code == 204 || code == 404 -> return null
                code != 200 || body == null -> {
                    markMojangUnreachable()
                    return null
                }
            }
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
            markMojangUnreachable()
            null
        }
    }

    /**
     * 按玩家名解析正版 UUID（带正负缓存；非正版名返回 404 → null）。
     *
     * 1.6.2 修复「极少个数玩家头像始终无法获取」：旧版把 Mojang API 的
     * 限流（429）/ 5xx / 网络异常与「名字确实不存在（404）」一律写进
     * **永久**负缓存——排行榜并发预热 15+ 个名字很容易触发 Mojang
     * 限流，被 429 的名字从此被判为「无正版账号」，头像永远失败。
     * 现在区分处理：
     * - 200 + 有效 id → 正缓存（名字 → UUID，长期有效）；
     * - 404 / 204 → 负缓存，但带 30 分钟有效期（防玩家改名后永久失效）；
     * - 429 / 5xx / 网络异常 → 本次 null 但**不写负缓存**，下次查询重试；
     * - 负缓存条目超时视为无缓存，重新查询。
     */
    private fun resolvePremiumUuidByName(name: String): UUID? {
        if (name.isBlank()) return null
        val key = name.lowercase()
        val now = System.currentTimeMillis()
        val cached = premiumUuidCache[key]
        if (cached is UUID) return cached
        if (cached is Long) {
            if (now - cached < NEGATIVE_UUID_TTL) return null
            // 负缓存过期（如玩家已改名）：清掉，本次重新查询
            premiumUuidCache.remove(key, cached)
        }
        return try {
            val (code, body) = httpGetWithStatus("https://api.mojang.com/users/profiles/minecraft/$name", 5000)
            when (code) {
                200 -> {
                    val uuid = body?.let {
                        parseDashedUuid(JSON.parseObject(String(it, Charsets.UTF_8))?.getString("id"))
                    }
                    if (uuid != null) {
                        premiumUuidCache[key] = uuid
                        uuid
                    } else {
                        // 200 但响应异常：不写负缓存，下次重试
                        null
                    }
                }
                204, 404 -> {
                    // 名字确实无正版账号：短期负缓存（30 分钟）
                    premiumUuidCache[key] = now
                    null
                }
                else -> {
                    // 429 限流 / 5xx / 网络异常（-1）：不写负缓存，下次查询自动重试；
                    // 1.6.4：同时开启 Mojang 官方链路全局冷却（5 分钟），
                    // 后续玩家不再逐个等超时（见 [MOJANG_NETWORK_COOLDOWN_MS]）
                    markMojangUnreachable()
                    null
                }
            }
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

    /**
     * 外部头像服务回退链（在线头像获取的主链路）。
     *
     * 1.7.2 超时重试修复（用户实测：头像下载流读到一部分后触发读超时，
     * 连接被直接切断，本轮渲染占位块、下一轮查询重试即成功）：
     * - 头像下载 HTTP 的读超时从旧版短超时延长到 25 秒（连接超时保持 5 秒
     *   短平快——慢源连接不上就立刻换源，慢在传数据的源给足时间）；
     * - 任一线路读超时（SocketTimeoutException）时本轮渲染内立即换下一个
     *   头像源重试；第一轮全部失败且属超时/网络抖动型时，再对两条快线路
     *   （MC-Heads / Minotar）补一轮换源重试（共 1-2 次），重试成功就不
     *   渲染占位块；
     * - 超时型失败继续不写 60 秒冷却（下次查询立即重试，现逻辑保持）；
     *   只有确定性失败（HTTP 明确无此玩家，404 等）才写冷却。
     *
     * 1.7.0 外层贴图（帽子层）修复：Minotar 的 /avatar 与 MinePic 的
     * /avatar 端点都只回内层皮肤（实测像素比对确认），玩家头像缺少
     * 外层纹理。现改为：Minotar 走 /helm（带帽子层）；MinePic 改走
     * /skin 原始皮肤贴图 + 本地裁脸（[downloadAndCropFace]，含帽子层
     * 叠加与高清皮肤等比支持）。
     *
     * 生产实测可达性（2026-10）：MC-Heads ≈ 0.4s、Minotar ≈ 0.5s、
     * MinePic ≈ 1.7~6s（可用但波动大，偶发超时，见 [fetchMinePicAvatar]）、
     * Crafatar ≈ 5.8s（慢但可用）；线路顺序按实测速度排（快 → 慢），
     * 任一成功即返回。
     *
     * 注：本链路之后还有 Mojang 官方原生 API（会话服务器 + 名字反查，
     * 见 [resolveMojangSkinUrl]）——官方仅在四条外部线路全部失败后尝试。
     *
     * - 基岩玩家（Floodgate UUID，见 [floodgateXuid]）：MC-Heads 的
     *   xuid 线路（实测 200，官方支持 Xbox 头像）；
     * - 正版 UUID（v4）：MC-Heads → Minotar → MinePic → Crafatar；
     * - 仅玩家名（无 UUID / 离线 UUID）：名字线路（MC-Heads →
     *   Minotar → MinePic）；
     * - 404 / 网络失败 / 解码失败一律返回 null（渲染占位块，不假回退）。
     */
    private fun fetchExternalAvatar(request: AvatarRequest): FetchedFace? {
        val first = fetchExternalAvatarOnce(request)
        if (first != null) return first
        // 1.7.2：第一轮全部失败且属网络抖动/超时型——立即换源补一轮快线路
        // 重试（MC-Heads / Minotar 各一次）；确定性失败（404 无此玩家）
        // 重试没有意义，不补
        if (!networkTrouble.get()) return null
        return fetchExternalAvatarOnce(request, retryPass = true)
    }

    /**
     * 外部线路单轮尝试（[fetchExternalAvatar] 的本体；[retryPass] = true
     * 时为超时后的换源补试轮，只走两条快线路）。基岩 xuid 线路不重试
     * （无备选源）。
     */
    private fun fetchExternalAvatarOnce(request: AvatarRequest, retryPass: Boolean = false): FetchedFace? {
        val uuid = request.uuid
        if (uuid != null) {
            // 基岩玩家：Floodgate UUID（前 64 位全 0 + 标志段 0x0009）
            val xuid = floodgateXuid(uuid)
            if (xuid != null) {
                if (retryPass) return null
                return fetchAvatarFromUrl("https://mc-heads.net/avatar/xuid:$xuid/$FACE_SIZE", "MC-Heads（基岩xuid线路）")
            }
            if (uuid.version() == 4) {
                // 正版账号：快线路优先，慢线路（MinePic / Crafatar）仅在前两者都失败后尝试
                val face = fetchAvatarFromUrl("https://mc-heads.net/avatar/$uuid/$FACE_SIZE", "MC-Heads")
                    ?: fetchAvatarFromUrl("https://minotar.net/helm/$uuid/$FACE_SIZE", "Minotar")
                if (face != null) return face
                if (retryPass) return null
                return fetchMinePicAvatar(uuid.toString().replace("-", ""))
                    ?: fetchCrafatarAvatar(uuid)
            }
        }
        // 仅玩家名（离线 UUID / 无 UUID）：名字线路（补试轮只走两条快线路）
        if (request.playerName.isNotBlank()) {
            val face = fetchAvatarFromUrl("https://mc-heads.net/avatar/${request.playerName}/$FACE_SIZE", "MC-Heads（名字线路）")
                ?: fetchAvatarFromUrl("https://minotar.net/helm/${request.playerName}/$FACE_SIZE", "Minotar（名字线路）")
            if (face != null || retryPass) return face
            return fetchMinePicAvatar(request.playerName)
        }
        return null
    }

    /**
     * MinePic 线路：https://minepic.org/skin/<UUID 或玩家名>。
     *
     * - 1.7.0：改走 /skin 原始皮肤贴图 + 本地裁脸（[downloadAndCropFace]）——
     *   MinePic 的 /avatar 端点只回内层皮肤（实测确认，帽子层缺失），
     *   /skin 返回原始贴图（含帽子层区域，支持高清皮肤），裁脸时叠上
     *   外层，与其他线路观感一致；
     * - 同时支持 32 位无横线 UUID 与玩家名（调用方传参前已归一化）；
     * - 实测可用但波动较大：成功时 1.7~6s，偶发超时 / 连接中断，
     *   未知账号不响应（按失败处理，不会返回默认皮肤假头像）；
     *   因此作为第三线路插在 Minotar 之后、Crafatar 之前，不挤占
     *   后续线路预算；http 会 301 跳 https，此处直接走 https。
     */
    private fun fetchMinePicAvatar(id: String): FetchedFace? {
        val face = downloadAndCropFace("https://minepic.org/skin/$id") ?: return null
        return FetchedFace(face, "MinePic（皮肤裁脸）")
    }

    /**
     * Floodgate 基岩玩家 UUID → Xbox xuid（非基岩 UUID 返回 null）。
     *
     * Floodgate 生成的 UUID 固定形如 00000000-0000-0000-0009-XXXXXXXXXXXX：
     * - 前 64 位（mostSignificantBits）全 0；
     * - 后 16 位（leastSignificantBits）高 16 位为基岩标志 0x0009，
     *   低 48 位即 xuid。离线模式 UUID（v3、非全 0 msb）不会被误判。
     */
    private fun floodgateXuid(uuid: UUID): Long? {
        if (uuid.mostSignificantBits != 0L) return null
        if (uuid.leastSignificantBits ushr 48 != 0x0009L) return null
        return uuid.leastSignificantBits and 0x0000FFFFFFFFFFFFL
    }

    /**
     * 通用外部头像下载（PNG 直出，无需裁剪）：尺寸不符时最近邻缩放到
     * [FACE_SIZE]（保持像素风），源图用后即 flush。
     *
     * 1.7.2：不再接收外部传入的统一超时（旧版连接/读同为 3 秒），改走
     * [httpGetAvatar]——连接 5 秒 / 读 25 秒分开控制（见 KDoc）；读超时
     * 抛出后由调用链立即换下一头像源。
     *
     * 1.6.6.2：[method] 为线路名，随结果返回用于获取方式日志。
     */
    private fun fetchAvatarFromUrl(url: String, method: String): FetchedFace? {
        return try {
            val bytes = httpGetAvatar(url) ?: return null
            val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
            when {
                image.width <= 0 || image.height <= 0 -> null
                image.width == FACE_SIZE && image.height == FACE_SIZE -> FetchedFace(image, method)
                // 缩放完成即释放源图栅格
                else -> FetchedFace(scaleNearest(image, FACE_SIZE), method).also {
                    try { image.flush() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---------- 头像磁盘缓存（1.6.6；1.6.6.1 有效期可配置 + 过期清扫） ----------

    /** 磁盘缓存默认有效期（小时）：render.avatar-cache-hours 可改。 */
    internal const val DEFAULT_AVATAR_DISK_TTL_HOURS = 3.0

    /** 头像磁盘缓存文件数上限（超出删最旧；PNG 约 2-8KB/个，总量很小）。 */
    private const val AVATAR_DISK_MAX_FILES = 512

    /**
     * 磁盘缓存有效期（毫秒），实时读配置（/hbkrg reload 后立即生效）：
     * - render.avatar-cache-hours（默认 3 小时，支持小数如 0.5）；
     * - 0 = 关闭磁盘缓存（不读不写，清扫时把已有文件全部删掉）；
     * - 负数按默认处理；有效值下限 1 分钟（防误填 0.0001 之类的抖动值）。
     */
    internal fun avatarDiskTtlMillis(): Long {
        val hours = RenderSettings.avatarCacheHours()
        if (hours <= 0.0) return 0L
        return (hours * 3600_000.0).toLong().coerceAtLeast(60_000L)
    }

    /**
     * 过期文件清扫线程（1.6.6.1：有效期缩短为小时级后，仅靠「查询命中时
     * 顺手删」会让不再上线的玩家头像长期滞留磁盘，改为后台主动清扫）。
     *
     * - 惰性启动：首次取头像时启动（[ensureAvatarSweeper]），守护线程；
     * - 首轮 10 秒后（错开启动高峰，顺带删掉停服期间过期的文件），
     *   此后每 30 分钟一轮：删过期文件 + 超上限删最旧；
     * - 1.6.6.2：双端主类 onEnable / 服务器启动时也会调用
     *   [ensureAvatarSweeper]（不再等到首次取头像）——强制关机 / 崩溃
     *   后残留的过期磁盘缓存文件，在下次启动 10 秒内就会被清掉，
     *   磁盘清理不依赖任何运行期状态；
     * - [clearCaches] 停机时关闭线程，下次使用时自动重启；
     * - 每轮顺带清理内存缓存中超过 TTL 的过期条目（见
     *   [purgeMemoryAvatarCache]，内存缓存及时清理）。
     */
    @Volatile
    private var avatarSweeper: ScheduledExecutorService? = null

    /**
     * 启动磁盘缓存清扫线程（幂等；双端启动时与首次取头像时都会调用；
     * public：双端主类模块需跨模块调用）。
     */
    fun ensureAvatarSweeper() {
        if (avatarSweeper != null) return
        synchronized(this) {
            if (avatarSweeper != null) return
            try {
                val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "avatar-cache-sweeper").apply { isDaemon = true }
                }
                executor.scheduleAtFixedRate(
                    { sweepDiskCache() },
                    10_000L,
                    30 * 60_000L,
                    TimeUnit.MILLISECONDS,
                )
                avatarSweeper = executor
            } catch (_: Throwable) {
                // 清扫线程只是磁盘卫生，起不来不影响头像功能本身
            }
        }
    }

    /** 清扫一轮磁盘缓存：删过期文件（缓存关闭时全删）+ 超上限删最旧 + 顺手清内存过期条目。 */
    internal fun sweepDiskCache() {
        // 1.6.6.2：内存过期条目清理放在最前（不依赖磁盘目录是否可得）
        purgeMemoryAvatarCache()
        try {
            val dir = avatarDiskDir() ?: return
            val ttl = avatarDiskTtlMillis()
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return
            val now = System.currentTimeMillis()
            for (file in files) {
                if (ttl <= 0L || now - file.lastModified() > ttl) {
                    try { file.delete() } catch (_: Throwable) {}
                }
            }
            trimDiskCache(dir)
        } catch (_: Throwable) {
        }
    }

    /**
     * 内存缓存及时清理（1.6.6.2）：清除超过 [AVATAR_TTL] 未被使用的
     * 内存头像条目（flush 后移除，栅格立即释放）。磁盘缓存仍在，后续
     * 渲染从磁盘秒级回填。仅在无渲染进行中时执行，避免 flush 正在
     * 画布上的图像。
     */
    private fun purgeMemoryAvatarCache() {
        if (avatarCache.isEmpty()) return
        if (CardRenderPool.activeRenders() > 0) return
        val now = System.currentTimeMillis()
        avatarCache.entries.removeIf { entry ->
            if (now - entry.value.createdAt >= AVATAR_TTL) {
                try { entry.value.image.flush() } catch (_: Throwable) {}
                true
            } else {
                false
            }
        }
    }

    /** 磁盘缓存目录（plugins/KERONGPenguin/cache/avatars/）。 */
    private fun avatarDiskDir(): File? {
        return try {
            val pluginDir = cn.kerong.bot.provider.BotShared.instance
                ?.configFile?.parentFile ?: return null
            val dir = pluginDir.resolve("cache/avatars")
            if (!dir.exists() && !dir.mkdirs()) return null
            dir
        } catch (_: Throwable) {
            null
        }
    }

    /** 磁盘缓存文件（键中不允许出现在文件名的字符安全替换）。 */
    private fun avatarDiskFile(key: String): File? {
        val dir = avatarDiskDir() ?: return null
        return File(dir, "${key.replace(':', '_')}.png")
    }

    /**
     * 读取磁盘缓存（缓存关闭时跳过；过期文件顺手删除；解码失败返回
     * null）。1.6.6.2：返回 [DiskAvatar]（图像 + 文件字节数），调用方
     * 用字节数判定是否回填内存（超过 [AVATAR_MEMORY_MAX_BYTES] 的大
     * 头像只走磁盘，不进内存缓存）。
     */
    private fun loadDiskAvatar(key: String, now: Long): DiskAvatar? {
        return try {
            val ttl = avatarDiskTtlMillis()
            if (ttl <= 0L) return null
            val file = avatarDiskFile(key) ?: return null
            if (!file.isFile) return null
            if (now - file.lastModified() > ttl) {
                try { file.delete() } catch (_: Throwable) {}
                return null
            }
            val bytes = file.readBytes()
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            image?.takeIf { it.width > 0 && it.height > 0 }?.let { DiskAvatar(it, bytes.size.toLong()) }
        } catch (_: Throwable) {
            null
        }
    }

    /** 头像 PNG 编码（1.6.6.2：磁盘写入与 10KB 内存门槛共用；失败返回 null）。 */
    private fun encodeAvatarPng(image: BufferedImage): ByteArray? = try {
        val out = ByteArrayOutputStream()
        if (ImageIO.write(image, "png", out)) out.toByteArray() else null
    } catch (_: Throwable) {
        null
    }

    /**
     * 写入磁盘缓存（1.6.6.2：调用方先 [encodeAvatarPng] 编码，此处只负责
     * 落盘；缓存关闭时跳过；临时文件原子替换，失败静默）。
     */
    private fun writeDiskAvatar(key: String, png: ByteArray) {
        try {
            if (avatarDiskTtlMillis() <= 0L) return
            val file = avatarDiskFile(key) ?: return
            val tmp = File(file.parentFile, "${file.name}.tmp")
            java.io.FileOutputStream(tmp).use { out ->
                out.write(png)
                out.flush()
            }
            if (!tmp.renameTo(file)) {
                try { tmp.delete() } catch (_: Throwable) {}
            }
            trimDiskCache(file.parentFile)
        } catch (_: Throwable) {
        }
    }

    /** 磁盘缓存数量控制：超上限删最旧（保存路径低频调用，列目录开销可忽略）。 */
    private fun trimDiskCache(dir: File?) {
        if (dir == null) return
        try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return
            if (files.size <= AVATAR_DISK_MAX_FILES) return
            files.sortedBy { it.lastModified() }
                .take(files.size - AVATAR_DISK_MAX_FILES)
                .forEach { file ->
                    try { file.delete() } catch (_: Throwable) {}
                }
        } catch (_: Throwable) {
        }
    }

    // ---------- 渲染空闲内存释放（1.6.6.2 新增） ----------

    /** 最近一次渲染完成时间戳（0 = 尚未渲染过；CardRenderPool 任务收尾时刷新）。 */
    @Volatile
    private var lastRenderCompletedAt = 0L

    /** 空闲释放检查线程（守护；首次渲染完成时惰性启动）。 */
    @Volatile
    private var idleReleaser: ScheduledExecutorService? = null

    /**
     * 渲染完成时间戳刷新（1.6.6.2）：[CardRenderPool] 在每个渲染任务
     * 收尾时调用（含异常收尾——渲染尝试结束即可重算空闲起点），并
     * 惰性启动空闲释放检查线程。
     */
    fun markRenderCompleted() {
        lastRenderCompletedAt = System.currentTimeMillis()
        ensureIdleReleaser()
    }

    /** 惰性启动空闲释放检查线程（每 30 秒检查一次，守护线程，幂等）。 */
    private fun ensureIdleReleaser() {
        if (idleReleaser != null) return
        synchronized(this) {
            if (idleReleaser != null) return
            try {
                val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "render-idle-releaser").apply { isDaemon = true }
                }
                executor.scheduleAtFixedRate(
                    { releaseIfIdle() },
                    30_000L,
                    30_000L,
                    TimeUnit.MILLISECONDS,
                )
                idleReleaser = executor
            } catch (_: Throwable) {
                // 释放线程只是内存卫生，起不来不影响渲染功能本身
            }
        }
    }

    /**
     * 空闲释放检查（1.6.6.2）：一次渲染完成后 [RenderSettings.idleReleaseMillis]
     * （render.idle-release-minutes，默认 5 分钟）内没有下一次渲染、且当前
     * 无进行中的渲染任务时，释放头像 / 背景等图像素材缓存占用的内存
     * （flush + 清空，栅格立即归还；磁盘头像缓存与 miss / UUID 等轻量
     * 元数据缓存保留，下次渲染从磁盘 / 外部接口秒级恢复）。
     *
     * - 释放后记录一条日志（释放条目数）；
     * - 配置为 0 = 关闭空闲释放；
     * - 下次渲染完成后自动重新填充缓存并重新计时。
     */
    internal fun releaseIfIdle() {
        try {
            val idleMillis = RenderSettings.idleReleaseMillis()
            if (idleMillis <= 0L) return
            val last = lastRenderCompletedAt
            if (last <= 0L) return
            if (System.currentTimeMillis() - last < idleMillis) return
            if (CardRenderPool.activeRenders() > 0) return
            if (avatarCache.isEmpty() && backgroundCache.isEmpty() && builtinBackgroundSources.isEmpty()) return

            val avatars = avatarCache.size
            val backgrounds = backgroundCache.size
            val sources = builtinBackgroundSources.size
            for (entry in avatarCache.values) {
                try { entry.image.flush() } catch (_: Throwable) {}
            }
            for (entry in backgroundCache.values) {
                try { entry.image.flush() } catch (_: Throwable) {}
                try { entry.backdrop?.flush() } catch (_: Throwable) {}
            }
            for (source in builtinBackgroundSources.values) {
                try { source.flush() } catch (_: Throwable) {}
            }
            avatarCache.clear()
            backgroundCache.clear()
            builtinBackgroundSources.clear()
            builtinBackgroundResolvedPaths.clear()
            // 重置计时基准：释放完成后需等下一次渲染完成才重新开始计时
            lastRenderCompletedAt = 0L
            // 1.6.6.3：释放动作只写插件日志文件，不上控制台
            //（GC 提示与释放属于内部例行维护，控制台仅在排查时才有价值，
            //  完整记录见 logs/qq/qq-bind-日期.log）
            PluginFileLog.write(
                "[渲染] 已 ${"%.1f".format(idleMillis / 60_000.0)} 分钟无渲染，释放素材缓存内存" +
                    "（头像 $avatars 条 / 背景处理结果 $backgrounds 张 / 背景源图 $sources 张，下次渲染自动从磁盘恢复）"
            )
            // 1.6.6.4：移除空闲后向 JVM 发 GC 提示（原 render.idle-gc-hint），
            // 线上 1.6.6.3 曾短暂引入「已提交超存活自动收缩」（auto-shrink），
            // 连同本 GC 提示一并移除——System.gc 触发的 Full GC 全停顿
            //（0.05~0.6 秒）可能落在玩家正在游玩的时刻（「无渲染」不等于
            // 服务器空闲），卡顿风险与收益不成比例；堆内存归还交给 JVM
            // 自然节奏，插件侧只做确定性的图像缓存释放（flush + 清空）。
        } catch (_: Throwable) {
        }
    }

    /**
     * 缓存与内存诊断快照（1.6.6.2 覆盖版：/hbkrg mem 子命令输出）。
     *
     * 用于排查「面板内存高」类问题：JVM 已提交堆 ≠ 插件实际占用的
     * 存活内存（渲染高峰后 G1 扩张的堆按 JVM 自然节奏归还；
     * 1.6.6.4 起插件不主动触发任何 GC，避免全停顿卡顿）。
     */
    fun cacheStats(): String {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
        val committedMb = runtime.totalMemory() / 1048576L
        val maxMb = runtime.maxMemory() / 1048576L
        val backgroundBytes = try {
            backgroundCache.values.sumOf { it.bytes }
        } catch (_: Throwable) {
            0L
        }
        return "JVM 堆: 存活 ${usedMb}MB / 已提交 ${committedMb}MB / 上限 ${maxMb}MB" +
            "（已提交为 JVM 预留量，渲染高峰后扩张、不代表插件实际占用）\n" +
            "头像内存缓存: ${avatarCache.size} 条（上限 $AVATAR_CACHE_MAX，单张 ≤ ${AVATAR_MEMORY_MAX_BYTES / 1024}KB；" +
            "大头像只走磁盘缓存 cache/avatars/）\n" +
            "失败冷却: ${missCache.size} 条（60 秒 TTL）/ 名字反查缓存: ${premiumUuidCache.size} 条\n" +
            "背景缓存: ${backgroundCache.size} 张（约 ${backgroundBytes / 1024}KB，上限 8 张 / 36MB）" +
            " / 内置背景源图: ${builtinBackgroundSources.size} 张\n" +
            "进行中的头像获取: ${inFlightFetches.size} 个 / 渲染中任务: ${CardRenderPool.activeRenders()} 个" +
            "（渲染线程上限 ${CardRenderPool.MAX_THREADS}）"
    }

    /**
     * Crafatar CDN 头像回退（1.6.4：官方皮肤链路失败后的兜底数据源，
     * 1.6.6：作为多线路的末位）。
     *
     * `https://crafatar.com/avatars/<uuid>?size=216&overlay` 直接返回该玩家
     * 的头部头像 PNG（含帽子层，与 cropFace 的裁剪结果同观感），Cloudflare
     * CDN 分发，中国大陆可达性显著好于 sessionserver.mojang.com。
     *
     * - 仅对 v4 UUID 有效（Crafatar 只收录正版账号；404 = 该 UUID 无皮肤，
     *   可检测、与「网络失败」区分开）；
     * - 请求尺寸即 [FACE_SIZE]（Crafatar 支持 1-512），命中时无需二次缩放；
     * - 404 / 网络失败 / 解码失败一律返回 null（渲染占位块，不缓存为失败
     *   之外的任何错误状态）；
     * - 离线 UUID（非 v4）玩家无皮肤数据时依然诚实返回 null（占位块），
     *   不做「人人史蒂夫」的假回退（0.1.5.2 设计决策保持不变）。
     */
    private fun fetchCrafatarAvatar(uuid: UUID?): FetchedFace? {
        if (uuid == null || uuid.version() != 4) return null
        return try {
            // 1.6.6：实测 Crafatar 响应较慢（约 5.8s）；1.7.2 起与其他头像线路
            // 同走 httpGetAvatar（连接 5s / 读 25s，末位线路给足读取时间）
            val bytes = httpGetAvatar("https://crafatar.com/avatars/$uuid?size=$FACE_SIZE&overlay") ?: return null
            val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
            when {
                image.width <= 0 || image.height <= 0 -> null
                image.width == FACE_SIZE -> FetchedFace(image, "Crafatar")
                // 1.6.5：缩放完成即释放源图栅格
                else -> FetchedFace(scaleNearest(image, FACE_SIZE), "Crafatar").also {
                    try { image.flush() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 最近邻缩放（保持头像像素风，与 cropFace 的放大方式一致）。 */
    private fun scaleNearest(source: BufferedImage, size: Int): BufferedImage {
        val result = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = result.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g.drawImage(source, 0, 0, size, size, null)
        } finally {
            g.dispose()
        }
        return result
    }

    /** 下载皮肤贴图 PNG（64×64 / 64×32 / 高清皮肤；1.7.2 起走头像专用超时 5s/25s）。 */
    private fun downloadSkin(url: String): BufferedImage? = try {
        val bytes = httpGetAvatar(url) ?: return null
        val skin = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        if (skin.width >= 64 && skin.height >= 32) skin else null
    } catch (_: Throwable) {
        null
    }

    /** HTTP GET 带状态码（网络异常返回 code = -1；用于区分 404 与限流/网络失败）。 */
    private fun httpGetWithStatus(url: String, timeoutMillis: Int): Pair<Int, ByteArray?> {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.setRequestProperty("User-Agent", "KERONGPenguin")
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            val bytes = if (code in 200..299) connection.inputStream.use { it.readBytes() } else null
            code to bytes
        } catch (_: Exception) {
            // 1.6.6.2：网络级异常（超时 / 连接失败）标记本次尝试为网络抖动型
            networkTrouble.set(true)
            -1 to null
        } finally {
            connection?.disconnect()
        }
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
     * 获取预处理完成的背景（cover 裁剪 + 暗色遮罩，尺寸 width × height）。
     *
     * 0.1.5.3：从 img/ 目录全部候选图中随机挑选一张（旧版固定取第一张），
     * 每张图的处理结果按文件路径 + 修改时间 + 大小缓存；图片更换后自动重新处理。
     * 同一张图重复抽中时直接命中缓存，随机切换不额外增加 CPU 开销。
     *
     * 0.1.5.5：
     * - 解码改用 ImageReader 子采样（最长边限制约 1600px），超大尺寸照片
     *   （如手机原图 4000×3000）不再整图解码，避免内存峰值 / OOM 导致卡片生成失败；
     * - 解码失败的文件进入 10 分钟负缓存，随机挑选时自动跳过。
     *
     * 1.5.1（内存限制优化）：
     * - 缓存除张数上限外增加字节预算（见 [BACKGROUND_CACHE_MAX_BYTES]），
     *   大画布背景超预算时按最久未使用淘汰；
     * - 尺寸签名改用 width * 1_000_000 + height，消除长图高度超过 4096 时的碰撞。
     *
     * 1.5.4.4：移除背景模糊（金字塔整段删除），背景直接叠暗色遮罩
     * ，处理开销与峰值内存进一步降低。
     *
     * 1.7.0：不再叠暗色遮罩（原版灰色遮罩样式已移除，只保留毛玻璃
     * 观感），处理结果即 cover 裁剪后的原图；模糊副本按需另行生成
     * （见 [cardBackdropOf]），缓存键不含样式。
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
        val sizeKey = width.toLong() * 1_000_000L + height
        // 1.6.9.5：样式不再进缓存键（两种样式的处理结果相同，见 KDoc）
        val cached = backgroundCache[path]
        if (cached != null && cached.stamp == stamp && cached.sizeKey == sizeKey) {
            cached.lastUsedAt = now
            return cached.image
        }

        // 解码并处理（仅在文件变化或首次抽中时执行；子采样限制解码尺寸）
        val decoded = decodeBackground(file) ?: return null
        if (decoded.width <= 0 || decoded.height <= 0) return null

        val cover = coverImage(decoded, width, height)
        // 1.7.0：不再叠整图暗色遮罩（原版灰色遮罩样式已移除）——背景原图
        // 直接作为画布底，观感交给卡片背后的局部模糊（毛玻璃）承担
        val processed = cover
        val bytes = processed.width.toLong() * processed.height * 4
        // 1.6.5：中间图即用即释（cover 与 scaled 父图共享栅格，flush cover 即一并释放）
        try { cover.flush() } catch (_: Throwable) {}
        try { decoded.flush() } catch (_: Throwable) {}
        // 旧条目（文件已更换或尺寸不同）被覆盖前 flush，避免其栅格等 GC；
        // 1.6.9.5：模糊副本一并释放
        val stale = backgroundCache.put(path, BackgroundEntry(stamp, sizeKey, processed, bytes, now))
        if (stale != null) {
            try { stale.image.flush() } catch (_: Throwable) {}
            try { stale.backdrop?.flush() } catch (_: Throwable) {}
        }
        evictBackgroundsIfOversized()
        return processed
    }

    /**
     * 全部图片渲染的统一背景入口（1.5.7）。
     *
     * - [useBuiltin] 为 true（默认）→ 直接使用 [builtinResource] 内置背景；
     * - false → 随机取数据目录 img/ 文件夹图片；**img 里没有任何可用图片时
     *   自动回退内置背景**（不再退纯色渐变，保证出图风格统一）；
     * - 长图背景处理高度封顶 [ONLINE_BACKGROUND_MAX_HEIGHT] 防栅格膨胀（1.5.1
     *   仅随机图封顶；1.6.6.1 覆盖版内置图同样封顶，且不再为每种页高
     *   各存一张 10MB 级 cover 条目；查信息卡片高 ≤ 856px 不受影响），
     *   渲染时按 cover 裁剪铺满（1.6.9.5 起不再拉伸变形，见
     *   [drawBackground]）；
     * - 卡片矮图不受影响。
     *
     * @param dataDirectory 插件 / 模组数据目录（img/ 的父目录），可为 null
     * @param useBuiltin    是否使用内置背景图（对应各自配置开关，默认 true）
     * @param builtinResource 内置背景资源路径（卡片类默认 [BUILTIN_BACKGROUND_CARD]，
     *                        排行榜传 [BUILTIN_BACKGROUND_LEADERBOARD]）
     */
    @JvmOverloads
    fun resolveBackground(
        dataDirectory: File?,
        width: Int,
        height: Int,
        useBuiltin: Boolean,
        builtinResource: String = BUILTIN_BACKGROUND_CARD,
    ): BufferedImage? {
        // 1.6.6.1 覆盖版：背景处理高度统一封顶（详见 KDoc）
        val cappedHeight = height.coerceAtMost(ONLINE_BACKGROUND_MAX_HEIGHT)
        if (!useBuiltin) {
            val random = dataDirectory?.let { processedBackground(it, width, cappedHeight) }
            if (random != null) return random
        }
        return processedBuiltinBackground(builtinResource, width, cappedHeight)
    }

    /**
     * 获取内置专属背景（1.5.6：在线排行榜默认背景；1.5.7 起卡片类共用入口）。
     *
     * - 源图 [resource] 随 JAR 分发（深灰色主体 + 深绿色装饰），
     *   仅解码一次并按类路径缓存，插件停用时随 [clearCaches] 释放；
     * - 处理结果按「资源路径 + 尺寸」键入 [backgroundCache]（stamp 恒 0 永不失效，
     *   与随机背景共用 LRU / 字节预算淘汰），同一尺寸的后续页直接命中，
     *   首页 / 续页不同尺寸各存一份互不挤占；
     * - 图像设计已自带可读性（深灰主体与卡片色拉开差距）；
     * - 资源缺失 / 解码失败返回 null（渲染层退回纯色渐变，不影响出图）。
     */
    @Synchronized
    fun processedBuiltinBackground(resource: String, width: Int, height: Int): BufferedImage? {
        if (width <= 0 || height <= 0) return null
        val now = System.currentTimeMillis()
        val sizeKey = width.toLong() * 1_000_000L + height
        // 键须含宽与高：首页 / 续页同宽不同高，尺寸不同各自缓存互不覆盖；
        // 1.6.9.5：样式不再进键（两种样式的处理结果相同，见 processedBackground KDoc）
        val cacheKey = "builtin:$resource#${width}x$height"
        val cached = backgroundCache[cacheKey]
        if (cached != null && cached.stamp == 0L && cached.sizeKey == sizeKey) {
            cached.lastUsedAt = now
            return cached.image
        }

        // 源图解码一次（按资源路径负缓存，缺失时不再重复开流探测）
        val source = builtinBackgroundSources[resource] ?: run {
            if (resource in builtinBackgroundResolvedPaths) return null
            builtinBackgroundResolvedPaths.add(resource)
            val decoded = try {
                InfoCardAssets::class.java.classLoader.getResourceAsStream(resource)?.use { stream ->
                    stream.buffered().let { ImageIO.read(it) }
                }
            } catch (_: Throwable) {
                null
            }
            if (decoded != null && decoded.width > 0 && decoded.height > 0) {
                builtinBackgroundSources[resource] = decoded
                decoded
            } else {
                null
            }
        } ?: return null

        val processed = coverImage(source, width, height)
        val bytes = processed.width.toLong() * processed.height * 4
        // 1.6.5：旧条目被覆盖前 flush（尺寸变化重入时旧图不再等 GC）；
        // 1.6.9.5：模糊副本一并释放
        val stale = backgroundCache.put(cacheKey, BackgroundEntry(0L, sizeKey, processed, bytes, now))
        if (stale != null) {
            try { stale.image.flush() } catch (_: Throwable) {}
            try { stale.backdrop?.flush() } catch (_: Throwable) {}
        }
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

    /**
     * 背景缓存超限淘汰：先按张数上限，再按字节预算（含 1.6.9.5 起
     * 模糊副本的占用），均淘汰最久未使用的条目（1.5.1；
     * 1.6.5 淘汰即 flush 释放栅格；1.6.9.5 淘汰连带 flush 模糊副本）。
     */
    private fun evictBackgroundsIfOversized() {
        val overflow = backgroundCache.size - BACKGROUND_CACHE_MAX
        if (overflow > 0) {
            backgroundCache.entries
                .sortedBy { it.value.lastUsedAt }
                .take(overflow)
                .forEach {
                    if (backgroundCache.remove(it.key, it.value)) {
                        try { it.value.image.flush() } catch (_: Throwable) {}
                        try { it.value.backdrop?.flush() } catch (_: Throwable) {}
                    }
                }
        }
        var totalBytes = backgroundCache.values.sumOf { it.bytes + it.backdropBytes }
        if (totalBytes > BACKGROUND_CACHE_MAX_BYTES) {
            backgroundCache.entries
                .sortedBy { it.value.lastUsedAt }
                .forEach { entry ->
                    if (totalBytes <= BACKGROUND_CACHE_MAX_BYTES) return@forEach
                    if (backgroundCache.remove(entry.key, entry.value)) {
                        try { entry.value.image.flush() } catch (_: Throwable) {}
                        try { entry.value.backdrop?.flush() } catch (_: Throwable) {}
                        totalBytes -= entry.value.bytes + entry.value.backdropBytes
                    }
                }
        }
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

    // ---------- 背景铺放与卡片背后局部模糊（1.6.9.5） ----------

    /** 背景 cover 铺放参数：源图上被铺到画布的区域（等比缩放 + 中心裁剪）。 */
    class BackgroundPlacement internal constructor(val sx: Int, val sy: Int, val sw: Int, val sh: Int)

    /**
     * 单次渲染的背景上下文：持有背景铺放参数与
     * 背景的模糊副本，卡片绘制前在其矩形内铺出「卡片背后局部模糊」
     * 效果（CSS backdrop-filter 语义）。
     */
    class CardBackdropContext internal constructor(
        private val blurred: BufferedImage,
        private val placement: BackgroundPlacement,
        private val canvasWidth: Int,
        private val canvasHeight: Int,
    ) {
        /** 在圆角卡片矩形内绘制背景模糊副本（卡片外不受影响）。 */
        fun paintCard(g: Graphics2D, x: Float, y: Float, w: Float, h: Float, radius: Float) {
            val oldClip = g.clip
            g.clip(RoundRectangle2D.Float(x, y, w, h, radius, radius))
            try {
                // drawImage 十参形式：目标矩形在前（画布全幅），源矩形在后（cover 裁剪窗口）
                g.drawImage(
                    blurred,
                    0, 0, canvasWidth, canvasHeight,
                    placement.sx, placement.sy, placement.sx + placement.sw, placement.sy + placement.sh,
                    null,
                )
            } finally {
                g.clip = oldClip
            }
        }
    }

    /**
     * 计算背景铺满画布的 cover 裁剪区域（1.6.9.5）：取宽高两个方向
     * 缩放比的较大者等比缩放——图片不够大就整张放大到覆盖画布，
     * 再以图片中心为基准裁掉多余部分；不拉伸变形。
     */
    internal fun backgroundPlacement(background: BufferedImage, canvasWidth: Int, canvasHeight: Int): BackgroundPlacement {
        val scale = maxOf(
            canvasWidth.toFloat() / background.width,
            canvasHeight.toFloat() / background.height,
        )
        // 源窗口 = 画布 / 缩放比（cover 语义：窗口不大于源图、恰好铺满画布）
        val sw = (canvasWidth / scale).toInt().coerceIn(1, background.width)
        val sh = (canvasHeight / scale).toInt().coerceIn(1, background.height)
        val sx = ((background.width - sw) / 2).coerceAtLeast(0)
        val sy = ((background.height - sh) / 2).coerceAtLeast(0)
        return BackgroundPlacement(sx, sy, sw, sh)
    }

    /**
     * 绘制背景（全部图片渲染的统一入口，1.6.9.5）：
     * - 有图：按 [backgroundPlacement] 的 cover 区域双线性铺满画布
     *   （等比放大不足的边 + 中心裁剪，不再拉伸变形——旧版对封顶高度的
     *   背景直接拉伸铺满，长图页会把背景纵向压扁）；
     * - 无图 / 尺寸非法：深色渐变兜底（各渲染器旧版自带实现原样收编）；
     * - 模糊度（render.blur-strength）大于 0 时返回 [CardBackdropContext]，
     *   供卡片绘制前铺「卡片背后局部模糊」（backdrop-filter 毛玻璃）；
     *   模糊度为 0 或无背景返回 null（调用方 `backdrop?.paintCard(...)` 即可）。
     */
    fun drawBackground(
        g: Graphics2D,
        background: BufferedImage?,
        canvasWidth: Int,
        canvasHeight: Int,
    ): CardBackdropContext? {
        if (background != null && background.width > 0 && background.height > 0) {
            val placement = backgroundPlacement(background, canvasWidth, canvasHeight)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            // drawImage 十参形式：目标矩形在前（铺满画布），源矩形在后（cover 裁剪窗口）
            g.drawImage(
                background,
                0, 0, canvasWidth, canvasHeight,
                placement.sx, placement.sy, placement.sx + placement.sw, placement.sy + placement.sh,
                null,
            )
            val blurred = cardBackdropOf(background)
            if (blurred != null) {
                return CardBackdropContext(blurred, placement, canvasWidth, canvasHeight)
            }
            return null
        }
        // 无图兑底：纯黑底 + 极淡纵向渐变光晕（与各渲染器旧版兑底一致）
        g.color = Color(0x05, 0x05, 0x08)
        g.fillRect(0, 0, canvasWidth, canvasHeight)
        val gradient = java.awt.GradientPaint(
            0f, 0f, Color(0x10, 0x12, 0x18),
            0f, canvasHeight.toFloat(), Color(0x05, 0x05, 0x08),
        )
        g.paint = gradient
        g.fillRect(0, 0, canvasWidth, canvasHeight)
        return null
    }

    /**
     * 取背景的模糊副本（卡片背后局部模糊，1.7.0 起唯一样式）：
     * - 模糊度（render.blur-strength）为 0 时返回 null（不模糊，零开销）；
     * - 模糊副本挂在背景缓存条目上惰性生成一次（[blurImage] 毫秒级），
     *   模糊度变化后自动重新生成（条目记录生成时的强度）；
     * - 随条目淘汰 / 覆盖 / 空闲释放一起 flush，字节占用计入背景缓存
     *   预算（[evictBackgroundsIfOversized]）；
     * - 与背景处理 / 淘汰同为 [Synchronized]，渲染线程取副本与缓存
     *   维护互斥，不会拿到已被淘汰释放的图像。
     */
    @Synchronized
    private fun cardBackdropOf(background: BufferedImage): BufferedImage? {
        val strength = RenderSettings.blurStrength()
        if (strength <= 0) return null
        for (entry in backgroundCache.values) {
            if (entry.image !== background) continue
            val existing = entry.backdrop
            if (existing != null && entry.backdropStrength == strength) return existing
            // 首次生成或模糊度已变化：释放旧副本，按当前模糊度重新生成
            if (existing != null) {
                try { existing.flush() } catch (_: Throwable) {}
            }
            val blurred = blurImage(entry.image, strength)
            entry.backdrop = blurred
            entry.backdropStrength = strength
            entry.backdropBytes = blurred.width.toLong() * blurred.height * 4
            evictBackgroundsIfOversized()
            // 淘汰可能把本条目清掉（字节预算超限时），此时返回未挂缓存的副本
            return entry.backdrop ?: blurred
        }
        // 不在缓存中的背景（防御路径，正常链路不会发生）：直接模糊不缓存
        return blurImage(background, strength)
    }

    /**
     * 背景高斯模糊（卡片背后局部模糊的模糊副本生成，见 [cardBackdropOf]；
     * 1.7.0 起模糊度由 render.blur-strength 控制）。
     *
     * 实现走「降采样 → 小图盒式模糊 → 双线性放大」三步，而不是整图卷积：
     * - 先双线性缩到小尺寸（背景本就只做底板，细节无意义，降采样本身
     *   即第一重柔化；倍率随模糊度 2 ~ 8 倍）；
     * - 小图上做两趟可分离盒式模糊（水平 + 垂直各两遍，半径随模糊度
     *   1 ~ 3，多趟盒式卷积逼近高斯分布）；
     * - 双线性放大回目标尺寸——观感对齐 CSS backdrop-filter 的液态玻璃底板。
     *
     * 性能：1350×1034 画布缩为小图后四趟整数滑动窗口求和，单次处理
     * 毫秒级，结果随背景缓存条目保存，渲染期零重复开销。
     */
    private fun blurImage(image: BufferedImage, strength: Int): BufferedImage {
        val width = image.width
        val height = image.height
        val downscale = blurDownscaleOf(strength)
        val boxRadius = blurBoxRadiusOf(strength)
        if (width < downscale * 2 || height < downscale * 2) return image
        val smallWidth = (width + downscale - 1) / downscale
        val smallHeight = (height + downscale - 1) / downscale

        // 1. 降采样（双线性）
        val small = BufferedImage(smallWidth, smallHeight, BufferedImage.TYPE_INT_RGB)
        var g = small.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(image, 0, 0, smallWidth, smallHeight, null)
        } finally {
            g.dispose()
        }

        // 2. 小图盒式模糊（两趟水平 + 两趟垂直，多趟叠加逼近高斯分布）
        val blurred = boxBlur(small, boxRadius, passes = 2)
        if (blurred !== small) {
            try { small.flush() } catch (_: Throwable) {}
        }

        // 3. 放大回目标尺寸（双线性，模糊后的色块平滑铺满）
        val result = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        g = result.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(blurred, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        try { blurred.flush() } catch (_: Throwable) {}
        return result
    }

    /**
     * 可分离盒式模糊（水平 + 垂直各 [passes] 遍，整数滑动窗口求和）。
     * 多趟盒式卷积的叠加分布逼近高斯（每趟半径 [radius]）。
     */
    private fun boxBlur(image: BufferedImage, radius: Int, passes: Int): BufferedImage {
        var current = image
        repeat(passes.coerceAtLeast(1)) {
            current = boxBlurAxis(current, radius, horizontal = true)
            current = boxBlurAxis(current, radius, horizontal = false)
        }
        return current
    }

    /** 单轴盒式模糊（滑动窗口和，边界按最近像素扩展）。 */
    private fun boxBlurAxis(source: BufferedImage, radius: Int, horizontal: Boolean): BufferedImage {
        val width = source.width
        val height = source.height
        if (radius <= 0 || width <= 0 || height <= 0) return source
        val pixels = source.getRGB(0, 0, width, height, null, 0, width)
        val output = IntArray(pixels.size)
        val lineCount = if (horizontal) height else width
        val lineLength = if (horizontal) width else height
        for (line in 0 until lineCount) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            // 窗口初始化：[0, radius] 区间（左边界按最近像素扩展）
            for (offset in -radius..radius) {
                val clamped = offset.coerceIn(0, lineLength - 1)
                val pixel = if (horizontal) pixels[line * width + clamped] else pixels[clamped * width + line]
                sumR += (pixel shr 16) and 0xFF
                sumG += (pixel shr 8) and 0xFF
                sumB += pixel and 0xFF
            }
            val windowSize = radius * 2 + 1
            for (index in 0 until lineLength) {
                val average = 0xFF shl 24 or
                    (((sumR / windowSize) and 0xFF) shl 16) or
                    (((sumG / windowSize) and 0xFF) shl 8) or
                    ((sumB / windowSize) and 0xFF)
                if (horizontal) {
                    output[line * width + index] = average
                } else {
                    output[index * width + line] = average
                }
                // 滑动窗口：移出最左像素，移入最右新像素（边界按最近像素扩展）
                val outgoing = (index - radius).coerceIn(0, lineLength - 1)
                val incoming = (index + radius + 1).coerceIn(0, lineLength - 1)
                val outPixel = if (horizontal) pixels[line * width + outgoing] else pixels[outgoing * width + line]
                val inPixel = if (horizontal) pixels[line * width + incoming] else pixels[incoming * width + line]
                sumR += ((inPixel shr 16) and 0xFF) - ((outPixel shr 16) and 0xFF)
                sumG += ((inPixel shr 8) and 0xFF) - ((outPixel shr 8) and 0xFF)
                sumB += (inPixel and 0xFF) - (outPixel and 0xFF)
            }
        }
        val result = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        result.setRGB(0, 0, width, height, output, 0, width)
        return result
    }

    // ---------- HTTP ----------

    /** 头像下载连接超时（毫秒，1.7.2：短平快——连不上立刻换源）。 */
    private const val AVATAR_CONNECT_TIMEOUT = 5_000

    /**
     * 头像下载读超时（毫秒，1.7.2）：从旧版短超时延长到 25 秒。
     *
     * 根因（用户实测日志确认）：头像下载流读到一部分后触发读超时，
     * 连接被直接切断，本轮渲染占位块（「获取失败（本次链路遇网络抖动…）」），
     * 下一轮查询重试即成功（「头像 xxx ← Minotar（名字线路）」）。
     * HttpURLConnection 的 readTimeout 是单次 read 阻塞上限（数据在流动
     * 就会不断重置计时），不是总时长上限——拉长到 25 秒只影响真正断流的
     * 连接，慢速可用的源得以完成下载。
     */
    private const val AVATAR_READ_TIMEOUT = 25_000

    /**
     * 头像专用 HTTP GET（连接 5 秒 / 读 25 秒分开控制，1.7.2 新增）。
     *
     * 读超时（SocketTimeoutException）同样标记 networkTrouble（不写冷却，
     * 下次查询立即重试），调用链（fetchExternalAvatarOnce 的 ?: 换源 +
     * fetchExternalAvatar 的补试轮）负责立即换下一个头像源重试。
     */
    private fun httpGetAvatar(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = AVATAR_CONNECT_TIMEOUT
            connection.readTimeout = AVATAR_READ_TIMEOUT
            connection.setRequestProperty("User-Agent", "KERONGPenguin")
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code !in 200..299) return null
            connection.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            // 网络级异常（含读超时 SocketTimeoutException）标记网络抖动型：
            // 不写失败冷却，下次查询立即重试；本轮内由调用链立即换源
            networkTrouble.set(true)
            null
        } finally {
            connection?.disconnect()
        }
    }
}
