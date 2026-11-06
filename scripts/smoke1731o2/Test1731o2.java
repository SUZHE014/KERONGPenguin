package scripts.smoke1731o2;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import cn.kerong.bot.provider.BotShared;
import scripts.smoke1731o.FakeRuntime;
import cn.kerong.penguin.spigot.render.InfoCardRenderer;
import cn.kerong.penguin.spigot.render.LeaderboardRenderer;
import cn.kerong.penguin.spigot.render.OnlineListRenderer;
import cn.kerong.penguin.spigot.render.RenderSettings;

/**
 * 1.7.3.1 第二次覆盖版独立 JVM 冒烟（跑在 NF 分发 JAR 的兼容层之上）。
 *
 * 用户两项（同版本号 1.7.3.1 直接覆盖）：
 * ① 修复图片信息过多（如排行榜）被 QQ 缩小展示时副标题灰色小文字
 *    虚化不清晰——副标签样式维持 1.7.3.1 覆盖版回退后的 1.7.0 同款
 *    固定灰 0x9FB3C8 + 22f 常规字重（不引入任何新配色 / 描边效果）。
 * ② 把副标题默认灰度改为 80——subtitle-card-gray 默认 106 → 80
 *    （与标题框 title-card-gray 相同，回归 1.7.0 轻卡观感），
 *    白底卡底亮度 149 → 175。
 *
 * 覆盖：
 *  CG 组 单元（drawSubLabel 四参签名 / 连续配色方法移除 / 默认灰度 80）
 *  ST 组 配置默认值与实时读取（80 / 自配 120 / 回默认）
 *  PX 组 像素端到端（白底 / 中亮 / 深底 / 小图标签全部精确 0x9FB3C8
 *        固定灰 ≥40 整像素 / 白底卡底 175 / 身份条同色 / 副标题条几何
 *        零改动 / 灰度 106 与 56 联动 / 渲染确定性）
 *
 * 用法（在仓库根目录）：
 *   export J=/home/z/my-project/tooling/jdk21/bin
 *   $J/javac -cp <NF JAR>:scripts/slf4j-api-2.0.13.jar -d scripts/smoke1731o2/out \
 *       scripts/smoke1731o/FakeRuntime.java scripts/smoke1731o2/Test1731o2.java
 *   $J/java -cp scripts/smoke1731o2/out:<NF JAR>:scripts/slf4j-api-2.0.13.jar:scripts/slf4j-nop-2.0.13.jar \
 *       scripts.smoke1731o2.Test1731o2
 */
public final class Test1731o2 {

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

    /** 1.7.0 查在线状态栏同款固定灰。 */
    private static final int LABEL_RGB = 0xFF9FB3C8;

    private static int lum(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
    }

    // ---------- 像素测量 ----------

    /** 区域内精确等于目标 RGB 的像素数（正向断言：22px 字核心有充足整色像素；
     *  gamma 抗锯齿混叠色不会精确等于目标色整像素值——R3d 教训只影响反向断言）。 */
    private static int countExact(java.awt.image.BufferedImage img, int x0, int y0, int x1, int y1, int rgb) {
        int n = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (img.getRGB(x, y) == rgb) n++;
            }
        }
        return n;
    }

    /** 区域众数颜色（卡底色——底色占绝对面积）。 */
    private static int regionMode(java.awt.image.BufferedImage img, int x0, int y0, int x1, int y1) {
        Map<Integer, Integer> cnt = new java.util.HashMap<>();
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

    /** 区域最暗像素（背景间隙带判定）。 */
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

    private static java.awt.image.BufferedImage decode(byte[] png) throws Exception {
        return javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
    }

    /** internal 方法带模块后缀（NF 产物 $server_NeoForge）——按名前缀反射查找。 */
    private static Method findMethod(String prefix, int paramCount) {
        for (Method m : InfoCardRenderer.INSTANCE.getClass().getMethods()) {
            if (m.getName().startsWith(prefix) && m.getParameterCount() == paramCount) {
                return m;
            }
        }
        return null;
    }

    private static boolean hasMethod(String prefix, int paramCount) {
        return findMethod(prefix, paramCount) != null;
    }

    private static int cardHeight(int itemCount) throws Exception {
        Method m = findMethod("cardHeight", 1);
        if (m == null) throw new IllegalStateException("cardHeight 方法未找到");
        return (Integer) m.invoke(InfoCardRenderer.INSTANCE, itemCount);
    }

    public static void main(String[] args) throws Exception {
        FakeRuntime fake = new FakeRuntime();
        BotShared.INSTANCE.setInstance(fake);

        System.out.println("== CG 组：覆盖版单元（回退固定灰 + 连续配色移除 + 灰度对齐） ==");

        // CG1 drawSubLabel 新签名（Graphics2D, String, float, float——无 baseLum）
        Method drawSub = findMethod("drawSubLabel", 4);
        Class<?>[] ptypes = drawSub == null ? new Class<?>[0] : drawSub.getParameterTypes();
        check("CG1 drawSubLabel 新签名（Graphics2D, String, float, float）",
                drawSub != null && ptypes[0] == java.awt.Graphics2D.class
                        && ptypes[1] == String.class && ptypes[2] == float.class
                        && ptypes[3] == float.class,
                drawSub == null ? "方法未找到" : Arrays.toString(ptypes));

        // CG2 连续配色方法整体移除（1.7.3.1 初版 subLabelColorFor(1)/subtitleBaseLum(5)/canvasLuminance(5)）
        check("CG2 连续配色方法移除（subLabelColorFor / subtitleBaseLum / canvasLuminance 全无）",
                !hasMethod("subLabelColorFor", 1) && !hasMethod("subtitleBaseLum", 5)
                        && !hasMethod("canvasLuminance", 5), null);

        // CG3 RenderSettings.DEFAULT_SUBTITLE_CARD_GRAY == 80（const 字段反射）
        int constGray = -1;
        for (Field f : RenderSettings.class.getFields()) {
            if (f.getName().equals("DEFAULT_SUBTITLE_CARD_GRAY")) {
                constGray = f.getInt(null);
            }
        }
        check("CG3 DEFAULT_SUBTITLE_CARD_GRAY = 80（实测 " + constGray + "）",
                constGray == 80, null);

        // CG4 无配置时 subtitleCardGray() == 80（与标题框相同，浅于项目卡 106）
        int subDefault = RenderSettings.INSTANCE.subtitleCardGray();
        int itemDefault = RenderSettings.INSTANCE.itemCardGray();
        int titleDefault = RenderSettings.INSTANCE.titleCardGray();
        check("CG4 默认副标题灰度 80 = 标题框 80（浅于项目卡 " + itemDefault + "）",
                subDefault == 80 && titleDefault == 80 && itemDefault == 106, null);

        System.out.println("== ST 组：配置默认值与实时读取 ==");

        check("ST1 默认 subtitle 80 / title 80 / item 106 / blur 50 / page-size 20",
                RenderSettings.INSTANCE.subtitleCardGray() == 80
                        && RenderSettings.INSTANCE.titleCardGray() == 80
                        && RenderSettings.INSTANCE.itemCardGray() == 106
                        && RenderSettings.INSTANCE.blurStrength() == 50
                        && RenderSettings.INSTANCE.leaderboardPageSize() == 20,
                "sub=" + RenderSettings.INSTANCE.subtitleCardGray()
                        + "/title=" + RenderSettings.INSTANCE.titleCardGray()
                        + "/item=" + RenderSettings.INSTANCE.itemCardGray());

        // ST2 用户仍可自配（56 或任意值都按用户值走，收敛只发生在升级重建）
        fake.getConfig().set("render.subtitle-card-gray", 120);
        check("ST2 用户自配 120 实时生效（实测 " + RenderSettings.INSTANCE.subtitleCardGray() + "）",
                RenderSettings.INSTANCE.subtitleCardGray() == 120, null);
        fake.getConfig().set("render.subtitle-card-gray", null);
        check("ST3 移除配置键回默认 80（实测 " + RenderSettings.INSTANCE.subtitleCardGray() + "）",
                RenderSettings.INSTANCE.subtitleCardGray() == 80, null);

        System.out.println("== PX 组：像素端到端（渲染 PNG 实测） ==");

        fake.getConfig().set("render.blur-strength", 0);

        List<OnlineListRenderer.OnlineEntry> many = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            many.add(new OnlineListRenderer.OnlineEntry("Player" + i, null));
        }
        List<InfoCardRenderer.CardItem> items = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            items.add(new InfoCardRenderer.CardItem("项目" + i, String.valueOf(i)));
        }

        // PX1 白底（255）：标签精确 0x9FB3C8 ≥40 整像素（1.7.0 固定灰在位；
        // 1.7.3.1 初版白卡标签是 0x5C7288 → 本断言能区分）
        byte[] px1Png = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData("SmokeServer", "12:34", many,
                        whiteBg(OnlineListRenderer.WIDTH, 1400))).get(0);
        java.awt.image.BufferedImage px1Img = decode(px1Png);
        int px1Label = countExact(px1Img, 46, 246, 446, 320, LABEL_RGB);
        check("PX1 白底标签精确 0x9FB3C8（" + px1Label + " ≥ 40 像素；1.7.3.1 初版白卡是 0x5C7288）",
                px1Label >= 40, null);

        // PX1b 白底卡底 = 255×(255-80)/255 = 175（灰度 80 生效；106 时是 149、56 时是 199）
        int px1Base = regionMode(px1Img, 46, 246, 446, 320);
        check("PX1b 白底副标题条卡底亮度 " + lum(px1Base) + " == 175（106 时 149 / 56 时 199，均能区分）",
                lum(px1Base) >= 170 && lum(px1Base) <= 180, null);

        // PX2 中间亮度背景（L=200，卡底 200×149/255 ≈ 117）：标签色不变（1.7.3.1 初版
        // 在此压暗成深色、五轮是 0xB8C8D8——回退后必须仍是精确 0x9FB3C8）
        byte[] px2Png = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData("SmokeServer", "12:34", many,
                        solidBg(OnlineListRenderer.WIDTH, 1400, 200))).get(0);
        java.awt.image.BufferedImage px2Img = decode(px2Png);
        int px2Label = countExact(px2Img, 46, 246, 446, 320, LABEL_RGB);
        check("PX2 中间亮度（L200）标签仍精确 0x9FB3C8（" + px2Label + " ≥ 40；不随背景变化）",
                px2Label >= 40, null);

        // PX3 深底（L=22）：标签色不变（五轮深卡是 0xB8C8D8 → 本断言能区分五轮观感）
        byte[] px3Png = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData("SmokeServer", "12:34", many,
                        solidBg(OnlineListRenderer.WIDTH, 1400, 22))).get(0);
        java.awt.image.BufferedImage px3Img = decode(px3Png);
        int px3Label = countExact(px3Img, 46, 246, 446, 320, LABEL_RGB);
        check("PX3 深底（L22）标签仍精确 0x9FB3C8（" + px3Label + " ≥ 40；五轮深卡是 0xB8C8D8）",
                px3Label >= 40, null);

        // PX4 大小图一致（「图片稍微大一点」场景：3 人小图 / 30 人大图同款标签）
        List<OnlineListRenderer.OnlineEntry> few = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            few.add(new OnlineListRenderer.OnlineEntry("Player" + i, null));
        }
        byte[] px4Png = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData("SmokeServer", "12:34", few,
                        solidBg(OnlineListRenderer.WIDTH, 640, 200))).get(0);
        java.awt.image.BufferedImage px4Img = decode(px4Png);
        int px4Label = countExact(px4Img, 46, 246, 446, 320, LABEL_RGB);
        check("PX4 小图（3 人）标签同款 0x9FB3C8（" + px4Label + " ≥ 40）",
                px4Label >= 40, null);

        // PX5 查信息身份条「玩家名称」标签同款固定灰（1.7.3.1 初版曾接自适应）
        int infoH = cardHeight(9);
        byte[] px5Png = InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Smoke", items, null,
                        whiteBg(InfoCardRenderer.WIDTH, infoH), "SmokeServer", "12:34"));
        java.awt.image.BufferedImage px5Img = decode(px5Png);
        int px5Label = countExact(px5Img, 40, 250, 170, 302, LABEL_RGB);
        check("PX5 身份条「玩家名称」标签精确 0x9FB3C8（" + px5Label + " ≥ 30）",
                px5Label >= 30, null);

        // PX6 查信息副标题条标签同款固定灰（1.7.3.1 初版白底时是深标签）
        byte[] px6Png = InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Smoke", items, null,
                        solidBg(InfoCardRenderer.WIDTH, infoH, 200), "SmokeServer", "12:34"));
        java.awt.image.BufferedImage px6Img = decode(px6Png);
        int px6Label = countExact(px6Img, 40, 342, 270, 400, LABEL_RGB);
        check("PX6 查信息副标题条（中亮底）标签仍 0x9FB3C8（" + px6Label + " ≥ 30）",
                px6Label >= 30, null);

        // PX7 副标题条几何零改动（白底渲染直接钉几何：卡体 240..323——
        // 上沿 236 行是背景白 255 / 卡内众数 149 / 下沿 328 行背景白 255）
        int gapTopMin = 255, gapBottomMin = 255;
        for (int x = 46; x <= 446; x += 2) {
            gapTopMin = Math.min(gapTopMin, lum(px1Img.getRGB(x, 236)));
            gapBottomMin = Math.min(gapBottomMin, lum(px1Img.getRGB(x, 328)));
        }
        check("PX7 副标题条几何零改动（上沿 " + gapTopMin + " ≥ 250 / 下沿 " + gapBottomMin + " ≥ 250）",
                gapTopMin >= 250 && gapBottomMin >= 250, null);

        // PX8 灰度配置联动：副标题灰度 106（1.7.3.1 覆盖版旧默认）→ 卡底 149
        fake.getConfig().set("render.subtitle-card-gray", 106);
        byte[] px8Png = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData("SmokeServer", "12:34", many,
                        whiteBg(OnlineListRenderer.WIDTH, 1400))).get(0);
        java.awt.image.BufferedImage px8Img = decode(px8Png);
        int px8Base = regionMode(px8Img, 46, 246, 446, 320);
        int px8Label = countExact(px8Img, 46, 246, 446, 320, LABEL_RGB);
        check("PX8 灰度 106 联动（卡底 " + lum(px8Base) + " 在 145~153；标签色不变 " + px8Label + " ≥ 40）",
                lum(px8Base) >= 145 && lum(px8Base) <= 153 && px8Label >= 40, null);
        // PX8b 灰度 56（更早旧默认）→ 卡底 199（配置通路完整）
        fake.getConfig().set("render.subtitle-card-gray", 56);
        byte[] px8bPng = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData("SmokeServer", "12:34", many,
                        whiteBg(OnlineListRenderer.WIDTH, 1400))).get(0);
        java.awt.image.BufferedImage px8bImg = decode(px8bPng);
        int px8bBase = regionMode(px8bImg, 46, 246, 446, 320);
        check("PX8b 灰度 56 联动（卡底 " + lum(px8bBase) + " 在 196~202）",
                lum(px8bBase) >= 196 && lum(px8bBase) <= 202, null);
        fake.getConfig().set("render.subtitle-card-gray", null);

        // PX9 渲染确定性（排行榜无渲染耗时格，两次渲染字节一致——R2d 口径沿用）
        int lbH = LeaderboardRenderer.INSTANCE.measurePageHeight(5, true);
        byte[] detA = LeaderboardRenderer.INSTANCE.renderPage(
                new LeaderboardRenderer.LeaderboardData("SmokeServer", "12:34",
                        lbEntries(5), 5L, 15, whiteBg(LeaderboardRenderer.WIDTH, lbH),
                        "在线排行榜", "总在线时长"), true);
        byte[] detB = LeaderboardRenderer.INSTANCE.renderPage(
                new LeaderboardRenderer.LeaderboardData("SmokeServer", "12:34",
                        lbEntries(5), 5L, 15, whiteBg(LeaderboardRenderer.WIDTH, lbH),
                        "在线排行榜", "总在线时长"), true);
        check("PX9 排行榜两次渲染字节一致（" + detA.length + " / " + detB.length + "）",
                Arrays.equals(detA, detB), null);

        // PX10 排行榜副标题条标签同款固定灰（浅底；1.7.3.1 初版浅底是深标签）
        java.awt.image.BufferedImage detImg = decode(detA);
        int px10Label = countExact(detImg, 46, 246, 446, 320, LABEL_RGB);
        check("PX10 排行榜（白底）标签精确 0x9FB3C8（" + px10Label + " ≥ 40）",
                px10Label >= 40, null);

        fake.getConfig().set("render.blur-strength", null);

        System.out.println();
        System.out.println("冒烟结果：通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("smoke1731o2 全部通过");
    }

    private static List<LeaderboardRenderer.LeaderEntry> lbEntries(int n) {
        List<LeaderboardRenderer.LeaderEntry> entries = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            entries.add(new LeaderboardRenderer.LeaderEntry(i, "Player" + i, 3600L * (n + 1 - i), null, null));
        }
        return entries;
    }

    private Test1731o2() {
    }
}
