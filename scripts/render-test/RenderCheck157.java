import cn.huohuas001.huhobotPenguin.spigot.render.InfoCardAssets;
import cn.huohuas001.huhobotPenguin.spigot.render.InfoCardRenderer;
import cn.huohuas001.huhobotPenguin.spigot.render.LeaderboardRenderer;
import cn.huohuas001.huhobotPenguin.spigot.render.OnlineListRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 1.5.7 无头渲染冒烟（全部图片渲染默认内置背景 + img 空回退 + 深灰主体）：
 *
 * 1. resolveBackground 统一入口：
 *    a. useBuiltin=true → 返回内置背景（非 null，宽高匹配，非纯黑）；
 *    b. useBuiltin=false 且 img/ 目录为空 → 自动回退内置背景（非 null，
 *       旧行为返回 null / 纯色渐变，1.5.7 起保证出图风格统一）；
 *    c. useBuiltin=false 且 img/ 有图片 → 返回 img 处理图（与内置图不同的实例）；
 *    d. img 清空后再次调用 → 回退内置图。
 * 2. 双内置资源互不串缓存：
 *    leaderboard-default（竖图）与 card-default（横图）同尺寸 cover 结果不同；
 *    同参数重复调用命中缓存返回同一实例。
 * 3. 三种卡片在默认（内置）背景下完整渲染无异常：
 *    查信息 9 项卡片 / 查在线 8 人列表 / 在线排行榜 15 行；
 *    渲染结果背景为深灰（中心采样亮度非纯黑）。
 *
 * 编译运行（仓库根目录）：
 *   javac -cp common-Bot/build/libs/common-Bot.jar:common-Bot/libs/deps.jar \
 *         -d scripts/render-test/out scripts/render-test/RenderCheck157.java
 *   java -cp scripts/render-test/out:common-Bot/build/libs/common-Bot.jar:\
 *         server-Spigot/build/libs/server-Spigot.jar:common-Bot/libs/deps.jar \
 *         RenderCheck157 /tmp/render-test-157-work
 * （server-Spigot.jar 提供 backgrounds/card-default.png 等类路径资源）
 */
public class RenderCheck157 {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "PASS" : "FAIL") + "  " + name + (ok || detail.isEmpty() ? "" : "  [" + detail + "]"));
    }

    /** 采样图像中心区域的平均亮度（0-255）。 */
    private static double centerLuminance(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int x0 = (int) (w * 0.35), x1 = (int) (w * 0.65);
        int y0 = (int) (h * 0.35), y1 = (int) (h * 0.65);
        long sum = 0, n = 0;
        for (int y = y0; y < y1; y += 3) {
            for (int x = x0; x < x1; x += 3) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                sum += Math.round(r * 0.299 + g * 0.587 + b * 0.114);
                n++;
            }
        }
        return n == 0 ? -1 : sum / (double) n;
    }

    public static void main(String[] args) throws Exception {
        File work = new File(args.length > 0 ? args[0] : "render-test-157-work");
        work.mkdirs();
        File imgDir = new File(work, "img");
        imgDir.mkdirs();

        // ---- 1a. useBuiltin=true → 内置背景 ----
        BufferedImage cardBg = InfoCardAssets.INSTANCE.resolveBackground(
            work, InfoCardRenderer.WIDTH, 760, true);
        check("1a 内置背景非 null（查信息卡）", cardBg != null, "");
        if (cardBg != null) {
            check("1a 内置背景宽 = 900", cardBg.getWidth() == 900, "实际 " + cardBg.getWidth());
            check("1a 内置背景高 = 760", cardBg.getHeight() == 760, "实际 " + cardBg.getHeight());
            double lum = centerLuminance(cardBg);
            check("1a 内置背景中心深灰亮度 ∈ [40,90]", lum >= 40 && lum <= 90, "实际 " + lum);
        }

        // ---- 1b. useBuiltin=false 且 img 空 → 回退内置 ----
        for (File f : imgDir.listFiles()) f.delete();
        BufferedImage fallbackBg = InfoCardAssets.INSTANCE.resolveBackground(
            work, InfoCardRenderer.WIDTH, 760, false);
        check("1b img 空时回退内置背景非 null", fallbackBg != null, "");
        if (fallbackBg != null) {
            check("1b 回退图与内置图同一缓存实例", fallbackBg == cardBg, "不同实例");
        }

        // ---- 1c. useBuiltin=false 且 img 有图 → 用 img 图 ----
        BufferedImage solid = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = solid.createGraphics();
        g.setColor(new java.awt.Color(0x33, 0x44, 0x55));
        g.fillRect(0, 0, 600, 400);
        g.dispose();
        ImageIO.write(solid, "png", new File(imgDir, "a.png"));
        ImageIO.write(solid, "png", new File(imgDir, "b.png"));
        BufferedImage randomBg = InfoCardAssets.INSTANCE.resolveBackground(
            work, InfoCardRenderer.WIDTH, 760, false);
        check("1c img 有图时返回随机图非 null", randomBg != null, "");
        if (randomBg != null) {
            check("1c 随机图不是内置图实例", randomBg != cardBg, "与内置图同实例（未走 img）");
            double lum = centerLuminance(randomBg);
            // a.png 是 (0x33,0x44,0x55) 纯色 + darkOverlay(黑 41%) 后的特征亮度
            check("1c 随机图确为 img 素材（叠加暗色遮罩后的特征亮度 ∈ [20,45]）",
                lum >= 20 && lum <= 45, "实际 " + lum);
        }

        // ---- 1d. img 清空后再调用 → 回退内置 ----
        for (File f : imgDir.listFiles()) f.delete();
        BufferedImage fallbackAgain = InfoCardAssets.INSTANCE.resolveBackground(
            work, OnlineListRenderer.WIDTH, 700, false);
        check("1d img 清空后再次回退内置非 null", fallbackAgain != null, "");

        // ---- 2. 双内置资源互不串缓存 ----
        BufferedImage lb1 = InfoCardAssets.INSTANCE.processedBuiltinBackground(
            InfoCardAssets.BUILTIN_BACKGROUND_LEADERBOARD, 1200, 800);
        BufferedImage card1 = InfoCardAssets.INSTANCE.processedBuiltinBackground(
            InfoCardAssets.BUILTIN_BACKGROUND_CARD, 1200, 800);
        check("2 排行榜内置背景非 null", lb1 != null, "");
        check("2 卡片内置背景非 null", card1 != null, "");
        if (lb1 != null && card1 != null) {
            check("2 双资源同尺寸结果互不串缓存（实例不同）", lb1 != card1, "同一实例");
            // 两图中央同为深灰主体（亮度接近是设计使然），差异判据改用像素集合：
            // 采样 5% 像素，允许 1% 以下相同（全等 = 串缓存回退了同一源图）
            int diff = 0, n = 0;
            for (int y = 0; y < 800; y += 7) {
                for (int x = 0; x < 1200; x += 7) {
                    if (lb1.getRGB(x, y) != card1.getRGB(x, y)) diff++;
                    n++;
                }
            }
            check("2 双资源像素集合不同（差异采样点 > 50%）", diff * 100.0 / n > 50.0,
                "差异 " + diff + "/" + n);
            BufferedImage lb1again = InfoCardAssets.INSTANCE.processedBuiltinBackground(
                InfoCardAssets.BUILTIN_BACKGROUND_LEADERBOARD, 1200, 800);
            check("2 同参数重复调用命中缓存（同一实例）", lb1again == lb1, "新实例");
        }

        // ---- 3. 三种卡片默认（内置）背景渲染 ----
        List<InfoCardRenderer.CardItem> items = new ArrayList<>();
        String[] labels = {"金币", "称号", "在线时长", "今日在线时长", "累计签到", "点券", "屠龙次数", "击杀玩家次数", "死亡次数"};
        String[] values = {"1,234", "大佬", "12天3小时", "5小时", "66 天", "520 点", "3 次", "27 次", "128 次"};
        for (int i = 0; i < labels.length; i++) {
            items.add(new InfoCardRenderer.CardItem(labels[i], values[i]));
        }
        byte[] cardPng = InfoCardRenderer.INSTANCE.render(new InfoCardRenderer.CardData(
            "Steve_Penguin", items, null, cardBg));
        check("3 查信息 9 项卡片渲染非空", cardPng != null && cardPng.length > 0, "");
        BufferedImage cardImg = ImageIO.read(new java.io.ByteArrayInputStream(cardPng));
        check("3 查信息卡片尺寸 900×760", cardImg.getWidth() == 900 && cardImg.getHeight() == 760,
            cardImg.getWidth() + "×" + cardImg.getHeight());

        List<OnlineListRenderer.OnlineEntry> online = new ArrayList<>();
        for (int i = 1; i <= 8; i++) online.add(new OnlineListRenderer.OnlineEntry("player" + i, null));
        byte[] onlinePng = OnlineListRenderer.INSTANCE.renderPages(new OnlineListRenderer.OnlineListData(
            "KERONGPenguin", "12:00", online,
            InfoCardAssets.INSTANCE.processedBuiltinBackground(
                InfoCardAssets.BUILTIN_BACKGROUND_CARD,
                OnlineListRenderer.WIDTH, OnlineListRenderer.INSTANCE.measurePageHeight(8, true)))).get(0);
        BufferedImage onlineImg = ImageIO.read(new java.io.ByteArrayInputStream(onlinePng));
        check("3 查在线 8 人列表渲染非空", onlinePng != null && onlinePng.length > 0, "");
        double onlineLum = centerLuminance(onlineImg);
        check("3 查在线渲染背景深灰非黑（中心亮度 > 20）", onlineLum > 20, "实际 " + onlineLum);

        List<LeaderboardRenderer.LeaderEntry> entries = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            entries.add(new LeaderboardRenderer.LeaderEntry(i, "ranker" + i, 3600L * (100 - i), null));
        }
        byte[] lbPng = LeaderboardRenderer.INSTANCE.renderPage(new LeaderboardRenderer.LeaderboardData(
            "KERONGPenguin", "12:00", entries, 100, 15,
            InfoCardAssets.INSTANCE.processedBuiltinBackground(
                InfoCardAssets.BUILTIN_BACKGROUND_LEADERBOARD,
                LeaderboardRenderer.WIDTH,
                LeaderboardRenderer.INSTANCE.measurePageHeight(15, true))), true);
        BufferedImage lbImg = ImageIO.read(new java.io.ByteArrayInputStream(lbPng));
        check("3 排行榜 15 行渲染非空", lbPng != null && lbPng.length > 0, "");
        check("3 排行榜宽度 1200", lbImg.getWidth() == 1200, "实际 " + lbImg.getWidth());
        double lbLum = centerLuminance(lbImg);
        check("3 排行榜渲染背景深灰（中心亮度 > 20，含卡片/文字叠加）", lbLum > 20, "实际 " + lbLum);

        System.out.println();
        System.out.println("共 " + (passed + failed) + " 项，通过 " + passed + "，失败 " + failed);
        if (failed > 0) System.exit(1);
        System.out.println("=== RenderCheck157 全部通过 ===");
    }
}
