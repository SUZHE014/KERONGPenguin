package scripts.smoke1731;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import cn.kerong.bot.provider.BotShared;
import cn.kerong.penguin.spigot.render.InfoCardRenderer;
import cn.kerong.penguin.spigot.render.LeaderboardRenderer;
import cn.kerong.penguin.spigot.render.OnlineListRenderer;

/**
 * 1.7.3.1 复现与修复验证渲染器：副标题灰色小标签在「图片稍大」时显示不清晰。
 *
 * 原理：画布越高（在线人数多 → 图更高），cover 裁剪把背景的不同区域
 * 铺到副标题条背后——同一张背景图，小图与大图副标题条背后的亮度不同，
 * 自适应配色（五轮 ≥150 阈值）在中等亮度背景上出现低对比的模糊区。
 *
 * 渲染矩阵：
 *  A. 纯色背景亮度扫描 L ∈ {120,140,160,180,200,220,240,255}——隔离「背景亮度」单变量
 *     （在线小图 3 人 / 大图 30 人各一张；纯色背景下两者副标题区域理论一致）
 *  B. 真实渐变背景（上亮下暗的天空图 1920×1080）——同一背景小图 vs 大图，
 *     验证「图片稍微大一点 → 副标题条背后变成更亮区域 → 文字不清晰」
 *  C. 四张卡片小/大两版（在线 / 排行榜 / 查信息）在中间亮度背景下的对比
 *
 * 用法（在仓库根目录，cp 用 NF 分发 JAR）：
 *   export J=/home/z/my-project/tooling/jdk21/bin
 *   $J/javac -cp <NF JAR>:scripts/slf4j-api-2.0.13.jar -d scripts/smoke1731/out \
 *       scripts/smoke1731/FakeRuntime.java scripts/smoke1731/Repro1731.java
 *   $J/java -cp scripts/smoke1731/out:<NF JAR>:scripts/slf4j-api-2.0.13.jar:scripts/slf4j-nop-2.0.13.jar \
 *       scripts.smoke1731.Repro1731 [输出目录] [模式: base|fix]
 */
public final class Repro1731 {

    public static void main(String[] args) throws Exception {
        FakeRuntime fake = new FakeRuntime();
        BotShared.INSTANCE.setInstance(fake);

        File dir = new File(args.length > 0 ? args[0] : "/tmp/repro1731");
        dir.mkdirs();

        // ---------- A. 纯色背景亮度扫描 ----------
        int[] levels = {120, 140, 160, 180, 200, 220, 240, 255};
        for (int lum : levels) {
            BufferedImage solid = solidBackground(1350, 800, lum);
            // 小图：3 人（画布 640）
            dump(dir, String.format("A_solid_L%d_online_small.png", lum),
                    renderOnline(solid, 3));
            // 大图：30 人（画布 1400）
            dump(dir, String.format("A_solid_L%d_online_large.png", lum),
                    renderOnline(solid, 30));
        }

        // ---------- B. 天空渐变背景：同背景 小图 vs 大图 ----------
        BufferedImage sky = skyBackground(1920, 1080);
        dump(dir, "B_sky_online_small.png", renderOnline(sky, 3));
        dump(dir, "B_sky_online_large.png", renderOnline(sky, 30));
        dump(dir, "B_sky_leader_small.png", renderLeaderboard(sky, 5));
        dump(dir, "B_sky_leader_large.png", renderLeaderboard(sky, 20));

        // ---------- C. 中间亮度（模糊区核心）四卡片 ----------
        BufferedImage mid = solidBackground(1920, 1080, 200);
        dump(dir, "C_mid200_leader_small.png", renderLeaderboard(mid, 5));
        dump(dir, "C_mid200_leader_large.png", renderLeaderboard(mid, 20));
        dump(dir, "C_mid200_info.png", renderInfo(solidBackground(900, 900, 200)));

        System.out.println("渲染完成 → " + dir.getAbsolutePath());
    }

    // ================= 渲染辅助 =================

    private static byte[] renderOnline(BufferedImage bg, int players) {
        List<OnlineListRenderer.OnlineEntry> online = new ArrayList<>();
        for (int i = 1; i <= players; i++) {
            online.add(new OnlineListRenderer.OnlineEntry("Player" + i, null));
        }
        return OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData("梦想服务器", "22:41", online, bg)).get(0);
    }

    private static byte[] renderLeaderboard(BufferedImage bg, int rows) {
        List<LeaderboardRenderer.LeaderEntry> entries = new ArrayList<>();
        for (int i = 1; i <= rows; i++) {
            entries.add(new LeaderboardRenderer.LeaderEntry(i, "Player" + i, 3600L * (rows + 1 - i), null, null));
        }
        return LeaderboardRenderer.INSTANCE.renderPage(
                new LeaderboardRenderer.LeaderboardData("梦想服务器", "22:41", entries, (long) rows, 20, bg,
                        "在线排行榜", "总在线时长"), true);
    }

    private static byte[] renderInfo(BufferedImage bg) throws Exception {
        List<InfoCardRenderer.CardItem> items = new ArrayList<>();
        items.add(new InfoCardRenderer.CardItem("总在线时长", "128小时"));
        items.add(new InfoCardRenderer.CardItem("今日在线", "2小时15分钟"));
        items.add(new InfoCardRenderer.CardItem("金币", "3200"));
        items.add(new InfoCardRenderer.CardItem("点券", "150"));
        items.add(new InfoCardRenderer.CardItem("走路距离", "35公里"));
        items.add(new InfoCardRenderer.CardItem("称号", "新手冒险家"));
        return InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Steve", items, null, bg, "梦想服务器", "22:41"));
    }

    // ================= 背景生成 =================

    private static BufferedImage solidBackground(int w, int h, int lum) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(lum, lum, lum));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    /** 上亮（L≈228）下暗（L≈96）的垂直渐变——典型动漫天空背景。 */
    private static BufferedImage skyBackground(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        java.awt.GradientPaint paint = new java.awt.GradientPaint(
                0, 0, new Color(228, 232, 240), 0, h, new Color(96, 104, 118));
        g.setPaint(paint);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static void dump(File dir, String name, byte[] png) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(new File(dir, name))) {
            fos.write(png);
        }
        System.out.println("  " + name + " = " + png.length + " bytes");
    }

    private Repro1731() {
    }
}
