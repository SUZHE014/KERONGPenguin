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
 *  LB 组 排行榜（副标题条高度 +116（覆盖版二轮头部 +14）/ 全宽标题卡灰度 / 副标题条灰度 / PNG 端到端）
 *  PF 组 查性能（副标题条高度 +130 / 全宽标题卡灰度 / 副标题条灰度 / PNG 端到端）
 *  R2 组 覆盖版二轮（标题框上边距留白 / 徽标四周间距 / 排行榜展示玩家 + 渲染确定性）
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

    private static double regionVariance(java.awt.image.BufferedImage img, int x, int y, int w, int h) {
        double sum = 0, sum2 = 0;
        int n = 0;
        for (int yy = y; yy < y + h; yy += 2) {
            for (int xx = x; xx < x + w; xx += 4) {
                int rgb = img.getRGB(xx, yy);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                double lum = 0.299 * r + 0.587 * g + 0.114 * b;
                sum += lum;
                sum2 += lum * lum;
                n++;
            }
        }
        if (n == 0) return -1;
        double mean = sum / n;
        return sum2 / n - mean * mean;
    }

    private static double maxBrightness(java.awt.image.BufferedImage img, int x, int y, int w, int h) {
        double max = -1;
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                int rgb = img.getRGB(xx, yy);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                double lum = 0.299 * r + 0.587 * g + 0.114 * b;
                if (lum > max) max = lum;
            }
        }
        return max;
    }

    private static boolean hasColorNear(java.awt.image.BufferedImage img, int x, int y, int w, int h,
                                        int[] target, int tol) {
        for (int yy = y; yy < y + h; yy += 2) {
            for (int xx = x; xx < x + w; xx += 2) {
                int rgb = img.getRGB(xx, yy);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (Math.abs(r - target[0]) <= tol && Math.abs(g - target[1]) <= tol && Math.abs(b - target[2]) <= tol) {
                    return true;
                }
            }
        }
        return false;
    }

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

        check("ST1 默认 subtitle 56 / page-size 20（1.7.3 覆盖版调深 40 → 56）",
                RenderSettings.INSTANCE.subtitleCardGray() == 56
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

        check("PX1 cardHeight 公式（9 项 3 行 = 568+96*3 = 856，覆盖版等距微调 -4）",
                cardHeight(9) == 856,
                cardHeight(9));
        check("PX2 cardHeight 公式（2 项 1 行 = 664）",
                cardHeight(2) == 664,
                cardHeight(2));

        // 标题卡 255 / 副标题 0 / 项目 0：玩家名称项与副标题条应为亮（白底透出）
        fake.getConfig().set("render.title-card-gray", 255);
        fake.getConfig().set("render.subtitle-card-gray", 0);
        fake.getConfig().set("render.item-card-gray", 0);
        byte[] pngA = InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Smoke", items, null, whiteBg(900, 856),
                        "SmokeServer", "12:34"));
        java.awt.image.BufferedImage imgA = decode(pngA);
        check("PX3 渲染高度 856 + PNG 魔数", imgA != null && imgA.getHeight() == 856,
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
                new InfoCardRenderer.CardData("Smoke", items, null, whiteBg(900, 856),
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
                new InfoCardRenderer.CardData("Smoke", items, null, whiteBg(900, 856),
                        "SmokeServer", "12:34"));
        java.awt.image.BufferedImage imgC = decode(pngC);
        double titleBright = avgBrightness(imgC, 24, 24, 460, 196);
        check("PX9 标题卡随 title-card-gray 0 变亮（" + String.format("%.0f", titleBright) + "）",
                titleBright > 180, null);

        // 项目卡 255：统计面板变暗
        fake.getConfig().set("render.item-card-gray", 255);
        byte[] pngD = InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Smoke", items, null, whiteBg(900, 856),
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

        int oldFirst = 40 + 150 + 18 + 56 + 14 + (15 * 84 + 14 * 12) + 40; // 旧版（无副标题，头 150）
        int newFirst = LeaderboardRenderer.INSTANCE.measurePageHeight(15, true);
        check("LB1 首页高度比旧版多 116（副标题条 84+18 + 二轮头部 +14；" + oldFirst + " → " + newFirst + "）",
                newFirst - oldFirst == 116, null);
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
        // 副标题条：y = 40+164+18 = 222 起 84 高（二轮头部 +14 后）
        double lbSubBright = avgBrightness(lbImg, 60, 226, 500, 76);
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
        double lbSubDark = avgBrightness(lbImg2, 60, 226, 500, 76);
        check("LB6 标题卡随 0 变亮（" + String.format("%.0f", lbTitleBright) + "）",
                lbTitleBright > 150, null);
        check("LB7 副标题条随 255 变暗（" + String.format("%.0f", lbSubDark) + "）",
                lbSubDark < 100, null);

        fake.getConfig().set("render.title-card-gray", null);
        fake.getConfig().set("render.subtitle-card-gray", null);
        fake.getConfig().set("render.item-card-gray", null);
        fake.getConfig().set("render.blur-strength", null);

        System.out.println("== PF 组：查性能（副标题条 + 服务器名小字移除） ==");

        int pfOld = 40 + 150 + 18 + (3 * 250 + 2 * 18) + 40; // 旧版 1034（头 150）
        int pfNew = PerformanceRenderer.INSTANCE.measureHeight();
        check("PF1 高度比旧版多 130（" + pfOld + " → " + pfNew + "，副标题条 84+32 + 二轮头部 +14）",
                pfNew - pfOld == 130, null);

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
        double pfSubBright = avgBrightness(pfImg, 60, 226, 500, 76);
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
        double pfSubDark = avgBrightness(pfImg2, 60, 226, 500, 76);
        check("PF5 标题卡随 0 变亮（" + String.format("%.0f", pfTitleBright) + "）",
                pfTitleBright > 150, null);
        check("PF6 副标题条随 255 变暗（" + String.format("%.0f", pfSubDark) + "）",
                pfSubDark < 100, null);

        fake.getConfig().set("render.title-card-gray", null);
        fake.getConfig().set("render.subtitle-card-gray", null);
        fake.getConfig().set("render.item-card-gray", null);
        fake.getConfig().set("render.blur-strength", null);

        System.out.println("== RC 组：1.7.3 覆盖版（徽标灰度 / 渲染耗时 / 综合状态 / 等距 / 自定义线程） ==");

        // 徽标灰度：永远比标题框深 +36（clamp 255）
        fake.getConfig().set("render.title-card-gray", 80);
        check("RC1 徽标灰度 = 标题 + 36（80 → 116）",
                RenderSettings.INSTANCE.headerBadgeCardGray() == 116,
                RenderSettings.INSTANCE.headerBadgeCardGray());
        fake.getConfig().set("render.title-card-gray", 250);
        check("RC2 徽标灰度上限 255（250 + 36 收敛）",
                RenderSettings.INSTANCE.headerBadgeCardGray() == 255,
                RenderSettings.INSTANCE.headerBadgeCardGray());
        fake.getConfig().set("render.title-card-gray", null);

        // 自定义线程：默认关闭 = 自动（0 哨兵）
        check("RC3 线程开关默认关（customRenderThreads=0 / customAvatarThreads=0）",
                RenderSettings.INSTANCE.customRenderThreads() == 0
                        && RenderSettings.INSTANCE.customAvatarThreads() == 0,
                RenderSettings.INSTANCE.customRenderThreads() + "/" + RenderSettings.INSTANCE.customAvatarThreads());
        fake.getConfig().set("render.custom-render-threads.enabled", true);
        fake.getConfig().set("render.custom-render-threads.render-threads", 7);
        fake.getConfig().set("render.custom-render-threads.avatar-threads", 3);
        check("RC4 开关开后线程数生效（7 / 3）",
                RenderSettings.INSTANCE.customRenderThreads() == 7
                        && RenderSettings.INSTANCE.customAvatarThreads() == 3,
                RenderSettings.INSTANCE.customRenderThreads() + "/" + RenderSettings.INSTANCE.customAvatarThreads());
        fake.getConfig().set("render.custom-render-threads.render-threads", 99);
        fake.getConfig().set("render.custom-render-threads.avatar-threads", 0);
        check("RC5 线程数越界收敛（99→16 / 0→1）",
                RenderSettings.INSTANCE.customRenderThreads() == 16
                        && RenderSettings.INSTANCE.customAvatarThreads() == 1,
                RenderSettings.INSTANCE.customRenderThreads() + "/" + RenderSettings.INSTANCE.customAvatarThreads());
        fake.getConfig().set("render.custom-render-threads.enabled", null);
        fake.getConfig().set("render.custom-render-threads.render-threads", null);
        fake.getConfig().set("render.custom-render-threads.avatar-threads", null);

        // 性能卡端到端（默认灰度 + 白底 + 不模糊）：徽标区比标题卡深、副标题比标题浅、
        // 渲染耗时格有文字、综合状态文字（语义色像素）在右上角卡、等距（副标题条底到容器顶 18px）
        fake.getConfig().set("render.blur-strength", 0);
        int pfH = PerformanceRenderer.INSTANCE.measureHeight();
        PerformanceSnapshot snap = new PerformanceSnapshot(19.6, 12.4, 4096L, 8192L, 0.15, 3, 20);
        byte[] rcPng = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "SmokeServer", "12:34", snap, whiteBg(PerformanceRenderer.WIDTH, pfH), 20, false, null));
        java.awt.image.BufferedImage rcImg = decode(rcPng);
        check("RC6 性能卡渲染成功（" + PerformanceRenderer.WIDTH + "x" + pfH + "）", rcImg != null, null);
        // 标题卡中段（避开文字与小方框）与右上角小方框中段亮度对比
        double rcTitleMid = avgBrightness(rcImg, 520, 50, 260, 120);
        double rcBadgeMid = avgBrightness(rcImg, 1090, 62, 150, 100);
        check("RC7 徽标区比标题卡深（" + String.format("%.0f < %.0f", rcBadgeMid, rcTitleMid) + "）",
                rcBadgeMid < rcTitleMid - 12, null);
        // 副标题条（56）比标题卡（80）浅
        double rcSubMid = avgBrightness(rcImg, 200, 226, 300, 60);
        check("RC8 副标题条比标题卡浅（" + String.format("%.0f > %.0f", rcSubMid, rcTitleMid) + "）",
                rcSubMid > rcTitleMid + 10, null);
        // 渲染耗时格（第三格）有数值文字：白字亮度 > 235（纯背景区域 ~200）
        double elapsedMax = maxBrightness(rcImg, 920, 258, 300, 50);
        check("RC9 渲染耗时格有文字（最大亮度 " + String.format("%.0f", elapsedMax) + " > 235）",
                elapsedMax > 235, null);
        // 综合状态：右上角卡存在语义色（绿 0x3EC96B 附近）像素
        boolean hasGreen = hasColorNear(rcImg, 1080, 120, 200, 60,
                new int[]{0x3E, 0xC9, 0x6B}, 40);
        check("RC10 综合状态文字为绿色（运行良好）", hasGreen, null);
        // 等距：副标题条底（40+164+18+84=306）→ 容器顶（40+164+18+84+32-14=324）= 18px 背景
        // 与 标题卡底（40+164=204）→ 副标题条顶（40+164+18=222）= 18px 一致：
        // 采样两条间隙都是纯背景（白），亮度都 > 200
        double gap1 = avgBrightness(rcImg, 300, 206, 60, 14);
        double gap2 = avgBrightness(rcImg, 300, 308, 60, 14);
        check("RC11 等距：两段间隙均为背景亮（" + String.format("%.0f/%.0f", gap1, gap2) + "）",
                gap1 > 200 && gap2 > 200, null);

        // 查信息等距微调：身份条底 308 → 副标题顶 328（20px）与 副标题底 412 → 统计面板顶 432（20px）
        List<InfoCardRenderer.CardItem> rcItems = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            rcItems.add(new InfoCardRenderer.CardItem("项目" + i, String.valueOf(i)));
        }
        byte[] rcInfo = InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Smoke", rcItems, null, whiteBg(900, 856),
                        "SmokeServer", "12:34"));
        java.awt.image.BufferedImage rcInfoImg = decode(rcInfo);
        double infoGap1 = avgBrightness(rcInfoImg, 100, 309, 60, 16);
        double infoGap2 = avgBrightness(rcInfoImg, 100, 413, 60, 16);
        check("RC12 查信息等距（" + String.format("%.0f/%.0f", infoGap1, infoGap2) + " > 200）",
                infoGap1 > 200 && infoGap2 > 200, null);
        // 查信息渲染耗时格（第三格 x≈658 起）有文字
        double infoMax = maxBrightness(rcInfoImg, 700, 328, 190, 84);
        check("RC13 查信息渲染耗时格有文字（最大亮度 " + String.format("%.0f", infoMax) + " > 235）",
                infoMax > 235, null);

        System.out.println("== R2 组：覆盖版二轮（上边距 / 徽标四周间距 / 展示玩家） ==");

        // R2a 标题框上边缘间距：眉题基线 46（旧 38）后文字顶 ≈ 67px——顶部 41..63px 条带
        // 应为纯标题卡底色（255 黑卡时最大亮度 ≈ 0；旧版眉题顶 ≈ 59px 会落入条带）
        fake.getConfig().set("render.title-card-gray", 255);
        int pfH2 = PerformanceRenderer.INSTANCE.measureHeight();
        byte[] r2aPng = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "SmokeServer", "12:34", snap, whiteBg(PerformanceRenderer.WIDTH, pfH2), 20, false, null));
        java.awt.image.BufferedImage r2aImg = decode(r2aPng);
        double topStripMax = maxBrightness(r2aImg, 100, 41, 400, 22);
        check("R2a 标题框顶部 22px 无文字（最大亮度 " + String.format("%.0f", topStripMax) + " < 10）",
                topStripMax < 10, null);

        // R2b/c 徽标卡四周间距：title 80（底 ≈ 175）时徽标 116（底 ≈ 139）
        // 右侧间距条带（x 1298..1308，徽标右缘 1296 与标题卡右缘 1310 之间）：
        // 旧版徽标右缘与标题卡齐平（0px 间距），该条带亮度 = 139；新版 = 标题卡底 175
        fake.getConfig().set("render.title-card-gray", 80);
        byte[] r2bPng = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "SmokeServer", "12:34", snap, whiteBg(PerformanceRenderer.WIDTH, pfH2), 20, false, null));
        java.awt.image.BufferedImage r2bImg = decode(r2bPng);
        double rightStrip = avgBrightness(r2bImg, 1298, 60, 10, 110);
        check("R2b 徽标右侧 14px 间距条带为标题卡底色（" + String.format("%.0f", rightStrip) + " > 165）",
                rightStrip > 165, null);
        // 上方间距：行 y=50（新徽标顶 54 之上是标题卡底 175；旧徽标顶 48，该行是徽标底 139）
        double topRow = avgBrightness(r2bImg, 1100, 50, 150, 2);
        check("R2c 徽标上方留白为标题卡底色（" + String.format("%.0f", topRow) + " > 160）",
                topRow > 160, null);
        // 下方间距：行 y=196（新徽标底 190 与标题卡底 204 之间 = 标题卡底 175；
        // 旧标题卡底 190，该行已是卡外背景白 255）
        double bottomRow = avgBrightness(r2bImg, 1100, 196, 150, 2);
        check("R2c2 徽标下方留白为标题卡底色（" + String.format("%.0f", bottomRow) + " < 200）",
                bottomRow < 200, null);

        // R2d 排行榜副标题第三格「展示玩家」：默认灰度白底渲染，第三格数值文字（xTOP）
        // 为白色 30f 粗体（旧版渲染耗时也是白字——确定性检查区分：两次渲染字节应完全一致，
        // 旧版含毫秒计时文字必然不同）
        byte[] r2d1 = LeaderboardRenderer.INSTANCE.renderPage(
                new LeaderboardRenderer.LeaderboardData(
                        "SmokeServer", "12:34", entries, 5L, 15,
                        whiteBg(LeaderboardRenderer.WIDTH, lbHeight),
                        "在线排行榜", "总在线时长"), true);
        byte[] r2d2 = LeaderboardRenderer.INSTANCE.renderPage(
                new LeaderboardRenderer.LeaderboardData(
                        "SmokeServer", "12:34", entries, 5L, 15,
                        whiteBg(LeaderboardRenderer.WIDTH, lbHeight),
                        "在线排行榜", "总在线时长"), true);
        check("R2d 排行榜两次渲染字节一致（无计时文字，展示玩家 5TOP 固定）",
                java.util.Arrays.equals(r2d1, r2d2), null);
        java.awt.image.BufferedImage r2dImg = decode(r2d1);
        double topMax = maxBrightness(r2dImg, 820, 258, 300, 46);
        check("R2e 展示玩家格有数值文字（最大亮度 " + String.format("%.0f", topMax) + " > 235）",
                topMax > 235, null);

        fake.getConfig().set("render.title-card-gray", null);
        fake.getConfig().set("render.blur-strength", null);

        System.out.println();
        System.out.println("冒烟结果：通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("smoke173 全部通过");
    }
}
