package scripts.smoke173;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.bot.provider.BotShared;
import cn.kerong.penguin.spigot.render.InfoCardRenderer;
import cn.kerong.penguin.spigot.render.LeaderboardRenderer;
import cn.kerong.penguin.spigot.render.PerformanceRenderer;
import cn.kerong.penguin.spigot.render.RenderSettings;

/**
 * 1.7.3 独立 JVM 冒烟（跑在 NF 分发 JAR 的兼容层之上）。
 *
 * 覆盖：
 *  ST 组 副标题灰度与排行榜分页配置（默认值 / 覆盖 / 越界收敛）
 *  PX 组 查信息像素（标题卡 / 玩家名称项 / 副标题条 / 统计面板分别随三键变化 + 高度公式）
 *  LB 组 排行榜（副标题条高度 +102 / 全宽标题卡灰度 / 副标题条灰度 / PNG 端到端）
 *  PF 组 查性能（副标题条高度 +102 / 全宽标题卡灰度 / 副标题条灰度 / PNG 端到端）
 *
 * 用法（在仓库根目录）：
 *   export J=/home/z/my-project/tooling/jdk21/bin
 *   $J/javac -cp <NF JAR>:scripts/slf4j-api-2.0.13.jar -d scripts/smoke173/out \
 *       scripts/smoke173/FakeRuntime.java scripts/smoke173/Test173.java
 *   $J/java -cp scripts/smoke173/out:<NF JAR>:scripts/slf4j-api-2.0.13.jar:scripts/slf4j-nop-2.0.13.jar \
 *       scripts.smoke173.Test173
 */
public final class Test173 {

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

    private static double avgBrightness(java.awt.image.BufferedImage img, int x, int y, int w, int h) {
        double sum = 0;
        int n = 0;
        for (int yy = y; yy < y + h; yy += 4) {
            for (int xx = x; xx < x + w; xx += 8) {
                int rgb = img.getRGB(xx, yy);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                sum += 0.299 * r + 0.587 * g + 0.114 * b;
                n++;
            }
        }
        return n == 0 ? -1 : sum / n;
    }

    private static java.awt.image.BufferedImage whiteBg(int w, int h) {
        java.awt.image.BufferedImage bg = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = bg.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return bg;
    }

    private static java.awt.image.BufferedImage decode(byte[] png) throws Exception {
        return javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
    }

    /** internal 方法带模块后缀（cardHeight$server_NeoForge 等）——按前缀反射查找。 */
    private static int cardHeight(int itemCount) throws Exception {
        for (java.lang.reflect.Method m : InfoCardRenderer.INSTANCE.getClass().getMethods()) {
            if (m.getName().startsWith("cardHeight") && m.getParameterCount() == 1) {
                return (Integer) m.invoke(InfoCardRenderer.INSTANCE, itemCount);
            }
        }
        throw new IllegalStateException("cardHeight 方法未找到");
    }

    public static void main(String[] args) throws Exception {
        FakeRuntime fake = new FakeRuntime();
        BotShared.INSTANCE.setInstance(fake);

        System.out.println("== ST 组：副标题灰度与排行榜分页配置 ==");

        check("ST1 默认 subtitle 40 / page-size 20",
                RenderSettings.INSTANCE.subtitleCardGray() == 40
                        && RenderSettings.INSTANCE.leaderboardPageSize() == 20,
                RenderSettings.INSTANCE.subtitleCardGray() + "/" + RenderSettings.INSTANCE.leaderboardPageSize());

        fake.getConfig().set("render.subtitle-card-gray", 200);
        fake.getConfig().set("leaderboard.page-size", 8);
        check("ST2 配置覆盖生效（200 / 8）",
                RenderSettings.INSTANCE.subtitleCardGray() == 200
                        && RenderSettings.INSTANCE.leaderboardPageSize() == 8,
                RenderSettings.INSTANCE.subtitleCardGray() + "/" + RenderSettings.INSTANCE.leaderboardPageSize());

        fake.getConfig().set("render.subtitle-card-gray", 999);
        fake.getConfig().set("leaderboard.page-size", 500);
        check("ST3 越界收敛（999→255 / 500→100）",
                RenderSettings.INSTANCE.subtitleCardGray() == 255
                        && RenderSettings.INSTANCE.leaderboardPageSize() == 100,
                RenderSettings.INSTANCE.subtitleCardGray() + "/" + RenderSettings.INSTANCE.leaderboardPageSize());

        fake.getConfig().set("render.subtitle-card-gray", -5);
        fake.getConfig().set("leaderboard.page-size", 0);
        check("ST4 越界收敛（-5→0 / 0→1）",
                RenderSettings.INSTANCE.subtitleCardGray() == 0
                        && RenderSettings.INSTANCE.leaderboardPageSize() == 1,
                RenderSettings.INSTANCE.subtitleCardGray() + "/" + RenderSettings.INSTANCE.leaderboardPageSize());

        fake.getConfig().set("render.subtitle-card-gray", null);
        fake.getConfig().set("leaderboard.page-size", null);

        System.out.println("== PX 组：查信息像素（三灰度键分别接线） ==");

        fake.getConfig().set("render.blur-strength", 0);
        List<InfoCardRenderer.CardItem> items = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            items.add(new InfoCardRenderer.CardItem("项目" + i, String.valueOf(i)));
        }

        check("PX1 cardHeight 公式（9 项 3 行 = 572+96*3 = 860）",
                cardHeight(9) == 860,
                cardHeight(9));
        check("PX2 cardHeight 公式（2 项 1 行 = 668）",
                cardHeight(2) == 668,
                cardHeight(2));

        // 标题卡 255 / 副标题 0 / 项目 0：玩家名称项与副标题条应为亮（白底透出）
        fake.getConfig().set("render.title-card-gray", 255);
        fake.getConfig().set("render.subtitle-card-gray", 0);
        fake.getConfig().set("render.item-card-gray", 0);
        byte[] pngA = InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Smoke", items, null, whiteBg(900, 860),
                        "SmokeServer", "12:34"));
        java.awt.image.BufferedImage imgA = decode(pngA);
        check("PX3 渲染高度 860 + PNG 魔数", imgA != null && imgA.getHeight() == 860,
                imgA == null ? "null" : imgA.getWidth() + "x" + imgA.getHeight());
        double titleDark = avgBrightness(imgA, 24, 24, 460, 196);      // 顶部标题卡
        double idBright = avgBrightness(imgA, 24, 244, 852, 60);       // 玩家名称项
        double subBright = avgBrightness(imgA, 24, 328, 852, 80);      // 副标题条
        check("PX4 标题卡随 title-card-gray 255 变暗（" + String.format("%.0f", titleDark) + "）",
                titleDark < 80, null);
        check("PX5 副标题 0 时玩家名称项亮（" + String.format("%.0f", idBright) + "）",
                idBright > 180, null);
        check("PX6 副标题 0 时副标题条亮（" + String.format("%.0f", subBright) + "）",
                subBright > 180, null);

        // 副标题 255：玩家名称项与副标题条都应变暗（这是「玩家名称项可调」的直接证据）
        fake.getConfig().set("render.subtitle-card-gray", 255);
        byte[] pngB = InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Smoke", items, null, whiteBg(900, 860),
                        "SmokeServer", "12:34"));
        java.awt.image.BufferedImage imgB = decode(pngB);
        double idDark = avgBrightness(imgB, 24, 244, 852, 60);
        double subDark = avgBrightness(imgB, 24, 328, 852, 80);
        check("PX7 副标题 255 时玩家名称项变暗（" + String.format("%.0f", idDark) + "）——旧版硬编码调不动",
                idDark < 100, null);
        check("PX8 副标题 255 时副标题条变暗（" + String.format("%.0f", subDark) + "）",
                subDark < 100, null);

        // 标题 0：顶部标题卡变亮
        fake.getConfig().set("render.title-card-gray", 0);
        fake.getConfig().set("render.subtitle-card-gray", 0);
        byte[] pngC = InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Smoke", items, null, whiteBg(900, 860),
                        "SmokeServer", "12:34"));
        java.awt.image.BufferedImage imgC = decode(pngC);
        double titleBright = avgBrightness(imgC, 24, 24, 460, 196);
        check("PX9 标题卡随 title-card-gray 0 变亮（" + String.format("%.0f", titleBright) + "）",
                titleBright > 180, null);

        // 项目卡 255：统计面板变暗
        fake.getConfig().set("render.item-card-gray", 255);
        byte[] pngD = InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Smoke", items, null, whiteBg(900, 860),
                        "SmokeServer", "12:34"));
        java.awt.image.BufferedImage imgD = decode(pngD);
        double statsDark = avgBrightness(imgD, 24, 436, 852, 300);
        check("PX10 统计面板随 item-card-gray 255 变暗（" + String.format("%.0f", statsDark) + "）",
                statsDark < 80, null);

        fake.getConfig().set("render.title-card-gray", null);
        fake.getConfig().set("render.subtitle-card-gray", null);
        fake.getConfig().set("render.item-card-gray", null);
        fake.getConfig().set("render.blur-strength", null);

        System.out.println("== LB 组：排行榜（副标题条 + 全宽标题卡） ==");

        int oldFirst = 40 + 150 + 18 + 56 + 14 + (15 * 84 + 14 * 12) + 40; // 旧版（无副标题）
        int newFirst = LeaderboardRenderer.INSTANCE.measurePageHeight(15, true);
        check("LB1 首页高度比旧版多 102（副标题条 84+18；" + oldFirst + " → " + newFirst + "）",
                newFirst - oldFirst == 102, null);
        int contOld = 24 + (10 * 84 + 9 * 12) + 40;
        int contNew = LeaderboardRenderer.INSTANCE.measurePageHeight(10, false);
        check("LB2 续页高度不变（" + contNew + " = " + contOld + "）", contNew == contOld, null);

        fake.getConfig().set("render.blur-strength", 0);
        fake.getConfig().set("render.title-card-gray", 255);
        fake.getConfig().set("render.subtitle-card-gray", 0);
        fake.getConfig().set("render.item-card-gray", 0);
        List<LeaderboardRenderer.LeaderEntry> entries = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            entries.add(new LeaderboardRenderer.LeaderEntry(i, "Player" + i, 3600L * i, null, null));
        }
        int lbHeight = LeaderboardRenderer.INSTANCE.measurePageHeight(5, true);
        byte[] lbPng = LeaderboardRenderer.INSTANCE.renderPage(
                new LeaderboardRenderer.LeaderboardData(
                        "SmokeServer", "12:34", entries, 5L, 15,
                        whiteBg(LeaderboardRenderer.WIDTH, lbHeight),
                        "在线排行榜", "总在线时长"),
                true);
        java.awt.image.BufferedImage lbImg = decode(lbPng);
        check("LB3 端到端 PNG 高度 = " + lbHeight,
                lbImg != null && lbImg.getHeight() == lbHeight && lbImg.getWidth() == LeaderboardRenderer.WIDTH, null);
        // 全宽标题卡：标题区左侧（原来无卡的区域）应随 titleCardGray 255 变暗
        double lbTitleDark = avgBrightness(lbImg, 60, 50, 500, 130);
        // 副标题条：y = 40+150+18 = 208 起 84 高
        double lbSubBright = avgBrightness(lbImg, 60, 212, 500, 76);
        check("LB4 全宽标题卡（左侧标题文字区随 255 变暗，" + String.format("%.0f", lbTitleDark) + "）",
                lbTitleDark < 100, null);
        check("LB5 副标题条随 subtitle 0 亮（" + String.format("%.0f", lbSubBright) + "）",
                lbSubBright > 180, null);

        fake.getConfig().set("render.title-card-gray", 0);
        fake.getConfig().set("render.subtitle-card-gray", 255);
        byte[] lbPng2 = LeaderboardRenderer.INSTANCE.renderPage(
                new LeaderboardRenderer.LeaderboardData(
                        "SmokeServer", "12:34", entries, 5L, 15,
                        whiteBg(LeaderboardRenderer.WIDTH, lbHeight),
                        "在线排行榜", "总在线时长"),
                true);
        java.awt.image.BufferedImage lbImg2 = decode(lbPng2);
        double lbTitleBright = avgBrightness(lbImg2, 60, 50, 500, 130);
        double lbSubDark = avgBrightness(lbImg2, 60, 212, 500, 76);
        check("LB6 标题卡随 0 变亮（" + String.format("%.0f", lbTitleBright) + "）",
                lbTitleBright > 150, null);
        check("LB7 副标题条随 255 变暗（" + String.format("%.0f", lbSubDark) + "）",
                lbSubDark < 100, null);

        fake.getConfig().set("render.title-card-gray", null);
        fake.getConfig().set("render.subtitle-card-gray", null);
        fake.getConfig().set("render.item-card-gray", null);
        fake.getConfig().set("render.blur-strength", null);

        System.out.println("== PF 组：查性能（副标题条 + 服务器名小字移除） ==");

        int pfOld = 40 + 150 + 18 + (3 * 250 + 2 * 18) + 40; // 旧版 1034
        int pfNew = PerformanceRenderer.INSTANCE.measureHeight();
        check("PF1 高度比旧版多 102（" + pfOld + " → " + pfNew + "）", pfNew - pfOld == 102, null);

        fake.getConfig().set("render.blur-strength", 0);
        fake.getConfig().set("render.title-card-gray", 255);
        fake.getConfig().set("render.subtitle-card-gray", 0);
        fake.getConfig().set("render.item-card-gray", 0);
        PerformanceSnapshot snapshot = new PerformanceSnapshot(19.8, 12.4, 4096L, 8192L, 0.15, 3, 20);
        byte[] pfPng = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "SmokeServer", "12:34", snapshot, whiteBg(PerformanceRenderer.WIDTH, pfNew),
                20, false, null));
        java.awt.image.BufferedImage pfImg = decode(pfPng);
        check("PF2 端到端 PNG 高度 = " + pfNew,
                pfImg != null && pfImg.getHeight() == pfNew, null);
        double pfTitleDark = avgBrightness(pfImg, 60, 50, 500, 130);
        double pfSubBright = avgBrightness(pfImg, 60, 212, 500, 76);
        check("PF3 全宽标题卡随 255 变暗（" + String.format("%.0f", pfTitleDark) + "）",
                pfTitleDark < 100, null);
        check("PF4 副标题条随 0 亮（" + String.format("%.0f", pfSubBright) + "）",
                pfSubBright > 180, null);

        fake.getConfig().set("render.title-card-gray", 0);
        fake.getConfig().set("render.subtitle-card-gray", 255);
        byte[] pfPng2 = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "SmokeServer", "12:34", snapshot, whiteBg(PerformanceRenderer.WIDTH, pfNew),
                20, false, null));
        java.awt.image.BufferedImage pfImg2 = decode(pfPng2);
        double pfTitleBright = avgBrightness(pfImg2, 60, 50, 500, 130);
        double pfSubDark = avgBrightness(pfImg2, 60, 212, 500, 76);
        check("PF5 标题卡随 0 变亮（" + String.format("%.0f", pfTitleBright) + "）",
                pfTitleBright > 150, null);
        check("PF6 副标题条随 255 变暗（" + String.format("%.0f", pfSubDark) + "）",
                pfSubDark < 100, null);

        fake.getConfig().set("render.title-card-gray", null);
        fake.getConfig().set("render.subtitle-card-gray", null);
        fake.getConfig().set("render.item-card-gray", null);
        fake.getConfig().set("render.blur-strength", null);

        System.out.println();
        System.out.println("冒烟结果：通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("smoke173 全部通过");
    }
}
