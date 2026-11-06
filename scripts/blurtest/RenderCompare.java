package scripts.blurtest;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import cn.kerong.bot.provider.BotShared;
import cn.kerong.penguin.spigot.render.LeaderboardRenderer;

/**
 * 副标题灰度 106 vs 80 实图对比 + QQ 缩小显示模拟（v1.7.3.1 第二次覆盖评估用）。
 * 运行在 NF 分发 JAR 兼容层之上，与 smoke1731o 同 classpath 结构。
 */
public final class RenderCompare {

    public static void main(String[] args) throws Exception {
        scripts.smoke1731o.FakeRuntime fake = new scripts.smoke1731o.FakeRuntime();
        BotShared.INSTANCE.setInstance(fake);
        fake.getConfig().set("render.blur-strength", 50);

        // 真实内置排行榜背景（深灰主体+深绿装饰）
        java.awt.image.BufferedImage builtin = ImageIO.read(
                RenderCompare.class.getResourceAsStream("/backgrounds/leaderboard-default.png"));

        // 亮色繁忙背景（模拟 bpng 图包：蔚蓝风格亮图，大量细节色块）
        java.awt.image.BufferedImage busy = makeBusyBackground();

        // 20 行排行榜数据
        List<LeaderboardRenderer.LeaderEntry> entries = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            entries.add(new LeaderboardRenderer.LeaderEntry(
                    i, "Player" + i, 3600L * (60 - i), null, null));
        }

        // 四组对比：{背景, 灰度, 标签}
        Object[][] cases = {
            {"builtin_106", builtin, 106},
            {"builtin_080", builtin, 80},
            {"busy_106", busy, 106},
            {"busy_080", busy, 80},
        };
        for (Object[] c : cases) {
            String tag = (String) c[0];
            java.awt.image.BufferedImage bg = (java.awt.image.BufferedImage) c[1];
            int gray = (Integer) c[2];
            fake.getConfig().set("render.subtitle-card-gray", gray);
            byte[] png = LeaderboardRenderer.INSTANCE.renderPage(
                    new LeaderboardRenderer.LeaderboardData(
                            "主服务器", "08:20", entries, 68L, 20, bg, "在线排行榜", "总在线时长"),
                    true);
            try (FileOutputStream out = new FileOutputStream("/tmp/cmp_" + tag + ".png")) {
                out.write(png);
            }
            java.awt.image.BufferedImage full = ImageIO.read(new ByteArrayInputStream(png));
            // QQ 手机端显示模拟：缩到 375px 宽 + JPEG q80（QQ 端再压缩）
            int dw = 375, dh = Math.round(full.getHeight() * 375f / full.getWidth());
            java.awt.image.BufferedImage small = new java.awt.image.BufferedImage(
                    dw, dh, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = small.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(full, 0, 0, dw, dh, null);
            g.dispose();
            java.io.File jf = new java.io.File("/tmp/cmp_" + tag + "_qq.jpg");
            javax.imageio.ImageIO.write(small, "jpg", jf);
            // 副标题条区域（缩小后 y≈(240..324)*0.3125 → 75..101）整段裁出
            java.awt.image.BufferedImage sub = small.getSubimage(0, 65, dw, 45);
            javax.imageio.ImageIO.write(sub, "png",
                    new java.io.File("/tmp/cmp_" + tag + "_sub.png"));
            System.out.println("rendered " + tag + " full=" + full.getWidth() + "x" + full.getHeight()
                    + " qq=" + dw + "x" + dh);
        }
        System.out.println("DONE");
    }

    /** 亮色繁忙背景：色块+圆+条纹，模拟高信息量图包背景。 */
    private static java.awt.image.BufferedImage makeBusyBackground() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                1200, 2200, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        java.awt.GradientPaint sky = new java.awt.GradientPaint(
                0, 0, new java.awt.Color(200, 225, 250),
                0, 2200, new java.awt.Color(255, 245, 220));
        g.setPaint(sky);
        g.fillRect(0, 0, 1200, 2200);
        java.util.Random r = new java.util.Random(42);
        for (int i = 0; i < 160; i++) {
            g.setColor(new java.awt.Color(120 + r.nextInt(135), 120 + r.nextInt(135), 120 + r.nextInt(135)));
            g.fillOval(r.nextInt(1100), r.nextInt(2100), 30 + r.nextInt(120), 20 + r.nextInt(80));
        }
        for (int i = 0; i < 24; i++) {
            g.setColor(new java.awt.Color(150 + r.nextInt(105), 150 + r.nextInt(105), 150 + r.nextInt(105)));
            g.fillRect(r.nextInt(1000), r.nextInt(2000), 60 + r.nextInt(200), 40 + r.nextInt(140));
        }
        g.dispose();
        return img;
    }
}
