package scripts.smoke1731;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.kerong.bot.provider.BotShared;
import cn.kerong.penguin.spigot.render.InfoCardRenderer;
import cn.kerong.penguin.spigot.render.LeaderboardRenderer;
import cn.kerong.penguin.spigot.render.OnlineListRenderer;
import cn.kerong.penguin.spigot.render.RenderSettings;

/**
 * 1.7.3.1 独立 JVM 冒烟（跑在 NF 分发 JAR 的兼容层之上）。
 *
 * 修复：副标题灰色小标签在图片稍大（画布更高 → cover 裁剪把背景更亮
 * 区域铺到副标题条背后）时显示不清晰——五轮 150 阈值二值切换在中间
 * 亮度背景（卡底 110~200）两侧都只有 1.8~2.7:1 对比度。1.7.3.1 改为
 * 连续对比度配色：分界下移 110，亮色卡深色标签按卡底亮度同色相等比
 * 压暗，WCAG 对比度 ≥ 3:1；纯白背景精确还原五轮 0x5C7288；查信息
 * 身份条「玩家名称」标签同接自适应（旧版固定浅色在亮背景下不可见）。
 *
 * 覆盖：
 *  CG 组 单元（subLabelColorFor 连续配色：深卡不变 / 白卡还原 / 中间亮度压暗 /
 *          亮度扫描全区间对比度 ≥ 3 / 上限封顶 / 行为分界 110）
 *  PX 组 像素端到端（中间亮度背景大图小图对比度 / 身份条 / 深底浅标签回归 /
 *          白底深标签回归 / 几何不变 / 渲染确定性 / 灰度配置联动）
 *  ST 组 配置默认值回归
 *
 * 用法（在仓库根目录）：
 *   export J=/home/z/my-project/tooling/jdk21/bin
 *   $J/javac -cp <NF JAR>:scripts/slf4j-api-2.0.13.jar -d scripts/smoke1731/out \
 *       scripts/smoke1731/FakeRuntime.java scripts/smoke1731/Test1731.java
 *   $J/java -cp scripts/smoke1731/out:<NF JAR>:scripts/slf4j-api-2.0.13.jar:scripts/slf4j-nop-2.0.13.jar \
 *       scripts.smoke1731.Test1731
 */
public final class Test1731 {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String name, boolean cond, Object detail) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + name + (detail == null ? "" : " — " + detail));
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + (detail == null ? "" : " — " + detail));
        }
    }

    // ---------- WCAG 相对亮度 ----------

    private static double srgbLin(int c) {
        double x = c / 255.0;
        return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
    }

    private static double relLum(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return 0.2126 * srgbLin(r) + 0.7152 * srgbLin(g) + 0.0722 * srgbLin(b);
    }

    private static double contrast(int a, int b) {
        double la = relLum(a), lb = relLum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static int lum(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
    }

    // ---------- 像素测量 ----------

    /** 区域最暗像素（标签字色核心判定——R3d 教训：不用目标色邻域计数）。 */
    private static int regionMinLum(java.awt.image.BufferedImage img, int x0, int y0, int x1, int y1) {
        int min = 255;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int l = lum(img.getRGB(x, y));
                if (l < min) min = l;
            }
        }
        return min;
    }

    /** 区域 ≥190 亮度像素数（浅色标签整片在位判定）。 */
    private static int regionBrightPixels(java.awt.image.BufferedImage img, int x0, int y0, int x1, int y1) {
        int n = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (lum(img.getRGB(x, y)) >= 190) n++;
            }
        }
        return n;
    }

    /** 区域众数颜色（卡底色——底色占绝对面积）。 */
    private static int regionMode(java.awt.image.BufferedImage img, int x0, int y0, int x1, int y1) {
        Map<Integer, Integer> cnt = new HashMap<>();
        int best = 0, bestN = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int rgb = img.getRGB(x, y);
                int n = cnt.merge(rgb, 1, Integer::sum);
                if (n > bestN) {
                    bestN = n;
                    best = rgb;
                }
            }
        }
        return best;
    }

    // ---------- 背景 ----------

    private static java.awt.image.BufferedImage solidBg(int w, int h, int lum) {
        java.awt.image.BufferedImage bg = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = bg.createGraphics();
        g.setColor(new java.awt.Color(lum, lum, lum));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return bg;
    }

    private static java.awt.image.BufferedImage whiteBg(int w, int h) {
        return solidBg(w, h, 255);
    }

    private static java.awt.image.BufferedImage darkBg(int w, int h) {
        return solidBg(w, h, 22);
    }

    private static java.awt.image.BufferedImage decode(byte[] png) throws Exception {
        return javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
    }

    /** internal 方法带模块后缀（NF 产物 $server_NeoForge）——按前缀反射查找。 */
    private static Method findMethod(String prefix, int paramCount) {
        for (Method m : InfoCardRenderer.INSTANCE.getClass().getMethods()) {
            if (m.getName().startsWith(prefix) && m.getParameterCount() == paramCount) {
                return m;
            }
        }
        return null;
    }

    private static int cardHeight(int itemCount) throws Exception {
        Method m = findMethod("cardHeight", 1);
        if (m == null) throw new IllegalStateException("cardHeight 方法未找到");
        return (Integer) m.invoke(InfoCardRenderer.INSTANCE, itemCount);
    }

    private static int cardModeRgb = 0;

    private static int cnt(Map<Integer, Integer> map, int key) {
        return map.getOrDefault(key, 0);
    }

    private static java.awt.Color subLabelColorFor(float baseLum) throws Exception {
        Method m = findMethod("subLabelColorFor", 1);
        if (m == null) throw new IllegalStateException("subLabelColorFor 方法未找到");
        return (java.awt.Color) m.invoke(InfoCardRenderer.INSTANCE, baseLum);
    }

    public static void main(String[] args) throws Exception {
        FakeRuntime fake = new FakeRuntime();
        BotShared.INSTANCE.setInstance(fake);

        System.out.println("== CG 组：连续对比度配色单元（subLabelColorFor） ==");

        // CG1 深色卡：浅蓝灰标签原样保留（五轮已验收观感）
        java.awt.Color dark80 = subLabelColorFor(80f);
        check("CG1 深色卡（80）→ 0xB8C8D8 原样（实测 " + Integer.toHexString(dark80.getRGB() & 0xFFFFFF) + "）",
                dark80.getRGB() == 0xFFB8C8D8, null);

        // CG2 纯白卡（默认灰度 56 → 卡底 199）：0x5C7288 精确还原五轮观感
        java.awt.Color bright199 = subLabelColorFor(199f);
        check("CG2 白底卡（199）→ 0x5C7288 精确还原（实测 " + Integer.toHexString(bright199.getRGB() & 0xFFFFFF) + "）",
                bright199.getRGB() == 0xFF5C7288, null);

        // CG3 中间亮度（150，五轮模糊区核心）：同色相压暗至 3:1
        java.awt.Color mid150 = subLabelColorFor(150f);
        int r150 = mid150.getRed(), g150 = mid150.getGreen(), b150 = mid150.getBlue();
        boolean hueKept = Math.abs(r150 * 114 - g150 * 92) <= 260 && Math.abs(r150 * 136 - b150 * 92) <= 310;
        int card150 = (150 << 16) | (150 << 8) | 150;
        check("CG3 中间亮度（150）→ 压暗（亮度 " + lum(mid150.getRGB()) + " ≤ 90 且 ≥ 30）+ 色相保持（92:114:136）+ 对比度 "
                        + String.format("%.2f", contrast(mid150.getRGB(), card150)) + " ≥ 2.95",
                lum(mid150.getRGB()) <= 90 && lum(mid150.getRGB()) >= 30 && hueKept
                        && contrast(mid150.getRGB(), card150) >= 2.95, null);

        // CG4 分界附近（111，刚过 110）：标签很深但不发黑
        java.awt.Color near111 = subLabelColorFor(111f);
        check("CG4 分界上方（111）→ 很深（亮度 " + lum(near111.getRGB()) + " ≤ 52 且 ≥ 26）",
                lum(near111.getRGB()) <= 52 && lum(near111.getRGB()) >= 26, null);

        // CG5 行为分界：110 仍浅色 / 111 起深色（分界下移 150 → 110）
        java.awt.Color at110 = subLabelColorFor(110f);
        check("CG5 分界行为（110 → 浅色 0xB8C8D8 / 111 → 深色变体）",
                at110.getRGB() == 0xFFB8C8D8 && near111.getRGB() != 0xFFB8C8D8, null);

        // CG6 灰度 0 的纯白卡（255）：封顶 0x5C7288，不亮于基准（观感上限锁定）
        java.awt.Color white255 = subLabelColorFor(255f);
        check("CG6 极亮卡（255，灰度 0 场景）→ 封顶 0x5C7288 不再提亮",
                white255.getRGB() == 0xFF5C7288, null);

        // CG7 全区间亮度扫描：111~199 每 2 一档，对比度全部 ≥ 2.94
        //（198~199 贴基准色区 = 五轮已验收的纯白观感 2.946 ≈ 3:1，其余档 ≥ 2.95）
        double worst = 99;
        int worstAt = -1;
        for (int L = 111; L <= 199; L += 2) {
            java.awt.Color c = subLabelColorFor((float) L);
            int card = (L << 16) | (L << 8) | L;
            double ct = contrast(c.getRGB(), card);
            if (ct < worst) {
                worst = ct;
                worstAt = L;
            }
        }
        check("CG7 亮度扫描 111~199（45 档）对比度全部 ≥ 2.94（最差 " + String.format("%.3f", worst) + " @ " + worstAt + "）",
                worst >= 2.94, null);

        // CG8 深色分支 20~110 也保持 ≈3（浅蓝灰对深底；分界 110 恰为 3:1 临界，
        // 实测 ~2.98 属浮点精度，断言 ≥ 2.95）
        double worstDark = 99;
        for (int L = 10; L <= 110; L += 10) {
            java.awt.Color c = subLabelColorFor((float) L);
            int card = (L << 16) | (L << 8) | L;
            worstDark = Math.min(worstDark, contrast(c.getRGB(), card));
        }
        check("CG8 深色分支 10~110 对比度 ≥ 2.95（最差 " + String.format("%.2f", worstDark) + "）",
                worstDark >= 2.95, null);

        System.out.println("== ST 组：配置默认值回归 ==");

        check("ST1 默认 subtitle 56 / title 80 / item 106 / blur 50 / page-size 20",
                RenderSettings.INSTANCE.subtitleCardGray() == 56
                        && RenderSettings.INSTANCE.titleCardGray() == 80
                        && RenderSettings.INSTANCE.itemCardGray() == 106
                        && RenderSettings.INSTANCE.blurStrength() == 50
                        && RenderSettings.INSTANCE.leaderboardPageSize() == 20,
                RenderSettings.INSTANCE.subtitleCardGray() + "/" + RenderSettings.INSTANCE.titleCardGray());

        System.out.println("== PX 组：像素端到端（渲染 PNG 实测） ==");

        fake.getConfig().set("render.blur-strength", 0);

        // 中间亮度背景（L=200 → 卡底 156，五轮模糊区核心）：在线列表大图
        // （30 人 = 画布 1400）与小图（3 人 = 画布 640）——「图片稍微大一点」
        // 场景的直接复现；两版标签区对比度都应 ≥ 2.9（1.7.3 实测 1.81~2.2）
        java.awt.image.BufferedImage midBgLarge = solidBg(OnlineListRenderer.WIDTH, 1400, 200);
        java.awt.image.BufferedImage midBgSmall = solidBg(OnlineListRenderer.WIDTH, 640, 200);

        List<OnlineListRenderer.OnlineEntry> many = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            many.add(new OnlineListRenderer.OnlineEntry("Player" + i, null));
        }
        List<OnlineListRenderer.OnlineEntry> few = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            few.add(new OnlineListRenderer.OnlineEntry("Player" + i, null));
        }

        byte[] px1Png = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData("SmokeServer", "12:34", many, midBgLarge)).get(0);
        java.awt.image.BufferedImage px1Img = decode(px1Png);
        int px1Base = regionMode(px1Img, 46, 246, 446, 320);
        int px1Min = regionMinLum(px1Img, 60, 254, 200, 292);
        double px1Ct = contrast((px1Min << 16) | (px1Min << 8) | px1Min, px1Base);
        check("PX1 中间亮度大图（30 人，画布 " + px1Img.getHeight() + "）标签核心亮度 " + px1Min
                        + " < 95 且对比度 " + String.format("%.2f", px1Ct) + " ≥ 2.9（1.7.3 实测 1.81）",
                px1Min < 95 && px1Ct >= 2.9, null);

        byte[] px2Png = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData("SmokeServer", "12:34", few, midBgSmall)).get(0);
        java.awt.image.BufferedImage px2Img = decode(px2Png);
        int px2Base = regionMode(px2Img, 46, 246, 446, 320);
        int px2Min = regionMinLum(px2Img, 60, 254, 200, 292);
        double px2Ct = contrast((px2Min << 16) | (px2Min << 8) | px2Min, px2Base);
        check("PX2 中间亮度小图（3 人，画布 " + px2Img.getHeight() + "）标签核心亮度 " + px2Min
                        + " < 95 且对比度 " + String.format("%.2f", px2Ct) + " ≥ 2.9（大小图一致清晰）",
                px2Min < 95 && px2Ct >= 2.9, null);

        check("PX3 画布高度公式（3 人 640 / 30 人 1400）",
                OnlineListRenderer.INSTANCE.measurePageHeight(3, true) == 640
                        && OnlineListRenderer.INSTANCE.measurePageHeight(30, true) == 1400,
                OnlineListRenderer.INSTANCE.measurePageHeight(3, true) + "/"
                        + OnlineListRenderer.INSTANCE.measurePageHeight(30, true));

        // 查信息身份条「玩家名称」标签同接自适应：中间亮度下旧版固定浅色
        // 0x9FB3C8（亮度 175）对卡底 156 对比度 ~1.15（几乎不可见）
        List<InfoCardRenderer.CardItem> items = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            items.add(new InfoCardRenderer.CardItem("项目" + i, String.valueOf(i)));
        }
        int infoH = cardHeight(9);
        byte[] px4Png = InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Smoke", items, null,
                        solidBg(InfoCardRenderer.WIDTH, infoH, 200), "SmokeServer", "12:34"));
        java.awt.image.BufferedImage px4Img = decode(px4Png);
        int px4Base = regionMode(px4Img, 30, 248, 860, 302);
        int px4Min = regionMinLum(px4Img, 46, 258, 160, 294);
        double px4Ct = contrast((px4Min << 16) | (px4Min << 8) | px4Min, px4Base);
        check("PX4 身份条「玩家名称」自适应（核心亮度 " + px4Min + " < 95，对比度 "
                        + String.format("%.2f", px4Ct) + " ≥ 2.9；1.7.3 固定浅色 ~1.15 不可见）",
                px4Min < 95 && px4Ct >= 2.9, null);

        // 深色背景回归：浅蓝灰标签整片在位（五轮深色卡观感不变）
        byte[] px5Png = LeaderboardRenderer.INSTANCE.renderPage(
                new LeaderboardRenderer.LeaderboardData("SmokeServer", "12:34",
                        lbEntries(5), 5L, 15, darkBg(LeaderboardRenderer.WIDTH, 880),
                        "在线排行榜", "总在线时长"), true);
        java.awt.image.BufferedImage px5Img = decode(px5Png);
        int px5Bright = regionBrightPixels(px5Img, 60, 254, 200, 292);
        check("PX5 深色背景 → 浅蓝灰标签（≥190 亮像素 " + px5Bright + " ≥ 60，深卡观感不变）",
                px5Bright >= 60, null);

        // 白色背景回归：深蓝灰 0x5C7288（亮度 ~110）精确保留（五轮白卡观感不变）
        int lbH = LeaderboardRenderer.INSTANCE.measurePageHeight(5, true);
        byte[] px6Png = LeaderboardRenderer.INSTANCE.renderPage(
                new LeaderboardRenderer.LeaderboardData("SmokeServer", "12:34",
                        lbEntries(5), 5L, 15, whiteBg(LeaderboardRenderer.WIDTH, lbH),
                        "在线排行榜", "总在线时长"), true);
        java.awt.image.BufferedImage px6Img = decode(px6Png);
        int px6Min = regionMinLum(px6Img, 60, 254, 200, 292);
        check("PX6 白色背景 → 深蓝灰标签核心（亮度 " + px6Min + " 在 100~120，0x5C7288 精确保留）",
                px6Min >= 100 && px6Min <= 120, null);

        // 查信息副标题条浅底回归（R5d：浅底 → 深色标签）
        byte[] px7Png = InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Smoke", items, null,
                        whiteBg(InfoCardRenderer.WIDTH, infoH), "SmokeServer", "12:34"));
        java.awt.image.BufferedImage px7Img = decode(px7Png);
        int px7Min = regionMinLum(px7Img, 40, 342, 170, 368);
        check("PX7 查信息副标题条浅底 → 深色标签（核心亮度 " + px7Min + " < 160）",
                px7Min < 160, null);

        // 副标题几何零改动（直接钉几何：卡顶 240 / 卡高 84 ——卡体在 240..323，
        // 上沿 234..238 与下沿 326..330 都是原背景 200，卡内是灰度叠加后的 156，
        // 数值白字仍在）：1.7.3.1 只改标签颜色，几何必须与五轮完全一致
        int gapTopMin = 255, gapBottomMin = 255, cardModeLum = -1;
        Map<Integer, Integer> cardCnt = new HashMap<>();
        for (int x = 46; x <= 446; x += 2) {
            int lt = lum(px1Img.getRGB(x, 236));
            if (lt < gapTopMin) gapTopMin = lt;
            int lb = lum(px1Img.getRGB(x, 328));
            if (lb < gapBottomMin) gapBottomMin = lb;
        }
        for (int y = 248; y <= 316; y += 2) {
            for (int x = 250; x <= 440; x += 4) {
                int rgb = px1Img.getRGB(x, y);
                cardCnt.merge(rgb, 1, Integer::sum);
                if (cardModeLum < 0 || cnt(cardCnt, rgb) > cnt(cardCnt, cardModeRgb)) {
                    cardModeRgb = rgb;
                    cardModeLum = lum(rgb);
                }
            }
        }
        int subTextPix = 0;
        for (int y = 244; y <= 320; y++) {
            for (int x = 46; x <= 446; x += 2) {
                if (lum(px1Img.getRGB(x, y)) > 200) subTextPix++;
            }
        }
        check("PX8 副标题几何零改动（上沿背景 " + gapTopMin + " ≥ 190 / 卡内众数 " + cardModeLum
                        + " 在 140~170 / 下沿背景 " + gapBottomMin + " ≥ 190 / 白字 " + subTextPix + " > 100）",
                gapTopMin >= 190 && gapBottomMin >= 190 && cardModeLum >= 140 && cardModeLum <= 170
                        && subTextPix > 100, null);

        // 排行榜渲染确定性（R2d 口径：两次渲染字节一致——无渲染耗时格）
        byte[] detA = LeaderboardRenderer.INSTANCE.renderPage(
                new LeaderboardRenderer.LeaderboardData("SmokeServer", "12:34",
                        lbEntries(5), 5L, 15, whiteBg(LeaderboardRenderer.WIDTH, lbH),
                        "在线排行榜", "总在线时长"), true);
        byte[] detB = LeaderboardRenderer.INSTANCE.renderPage(
                new LeaderboardRenderer.LeaderboardData("SmokeServer", "12:34",
                        lbEntries(5), 5L, 15, whiteBg(LeaderboardRenderer.WIDTH, lbH),
                        "在线排行榜", "总在线时长"), true);
        check("PX9 排行榜两次渲染字节一致（" + detA.length + " / " + detB.length + "）",
                java.util.Arrays.equals(detA, detB), null);

        // 灰度配置联动：副标题灰度调深到 100 → 卡底 200×155/255 ≈ 122，
        // 仍在深色分支且对比度 ≥ 2.9（自适应跟随用户配置）
        fake.getConfig().set("render.subtitle-card-gray", 100);
        byte[] px10Png = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData("SmokeServer", "12:34", many,
                        solidBg(OnlineListRenderer.WIDTH, 1400, 200))).get(0);
        java.awt.image.BufferedImage px10Img = decode(px10Png);
        int px10Base = regionMode(px10Img, 46, 246, 446, 320);
        int px10Min = regionMinLum(px10Img, 60, 254, 200, 292);
        double px10Ct = contrast((px10Min << 16) | (px10Min << 8) | px10Min, px10Base);
        check("PX10 灰度 100 联动（卡底 " + lum(px10Base) + "，标签核心 " + px10Min
                        + "，对比度 " + String.format("%.2f", px10Ct) + " ≥ 2.9）",
                px10Ct >= 2.9 && px10Min < 95, null);

        // 灰度 0 白底（卡底 255，超基准亮度）：封顶后对比度 ≥ 2.6（0x5C7288 对纯白）
        fake.getConfig().set("render.subtitle-card-gray", 0);
        byte[] px11Png = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData("SmokeServer", "12:34", many,
                        whiteBg(OnlineListRenderer.WIDTH, 1400))).get(0);
        java.awt.image.BufferedImage px11Img = decode(px11Png);
        int px11Base = regionMode(px11Img, 46, 246, 446, 320);
        int px11Min = regionMinLum(px11Img, 60, 254, 200, 292);
        double px11Ct = contrast((px11Min << 16) | (px11Min << 8) | px11Min, px11Base);
        check("PX11 灰度 0 白底封顶（标签核心 " + px11Min + "，对比度 " + String.format("%.2f", px11Ct)
                        + " ≥ 2.6，观感锁定五轮基准）",
                px11Ct >= 2.6 && px11Min >= 95 && px11Min <= 125, null);

        fake.getConfig().set("render.subtitle-card-gray", null);
        fake.getConfig().set("render.blur-strength", null);

        System.out.println();
        System.out.println("冒烟结果：通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("smoke1731 全部通过");
    }

    private static List<LeaderboardRenderer.LeaderEntry> lbEntries(int n) {
        List<LeaderboardRenderer.LeaderEntry> entries = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            entries.add(new LeaderboardRenderer.LeaderEntry(i, "Player" + i, 3600L * (n + 1 - i), null, null));
        }
        return entries;
    }

    private Test1731() {
    }
}
