package scripts.blurtest;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import cn.kerong.bot.provider.BotShared;
import cn.kerong.penguin.spigot.render.LeaderboardRenderer;
import cn.kerong.penguin.spigot.render.OnlineListRenderer;

/** 1.7.3.1 第二次覆盖版实图复核：默认灰度80渲染 + QQ缩小显示模拟。 */
public final class DumpFinal {

    public static void main(String[] args) throws Exception {
        scripts.smoke1731o.FakeRuntime fake = new scripts.smoke1731o.FakeRuntime();
        BotShared.INSTANCE.setInstance(fake);
        fake.getConfig().set("render.blur-strength", 50);
        // 不设 subtitle-card-gray —— 走新默认 80

        java.awt.image.BufferedImage builtin = ImageIO.read(
                DumpFinal.class.getResourceAsStream("/backgrounds/leaderboard-default.png"));

        List<LeaderboardRenderer.LeaderEntry> entries = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            entries.add(new LeaderboardRenderer.LeaderEntry(
                    i, "Player" + i, 3600L * (60 - i), null, null));
        }
        byte[] lb = LeaderboardRenderer.INSTANCE.renderPage(
                new LeaderboardRenderer.LeaderboardData(
                        "主服务器", "08:20", entries, 68L, 20, builtin, "在线排行榜", "总在线时长"),
                true);
        try (FileOutputStream out = new FileOutputStream("/tmp/final_lb.png")) {
            out.write(lb);
        }

        // 查在线 30 人（另一张信息密集卡）
        List<OnlineListRenderer.OnlineEntry> many = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            many.add(new OnlineListRenderer.OnlineEntry("Player" + i, null));
        }
        byte[] ol = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData("主服务器", "08:20", many, builtin)).get(0);
        try (FileOutputStream out = new FileOutputStream("/tmp/final_ol.png")) {
            out.write(ol);
        }

        // QQ 缩小显示模拟（375px 宽 + JPEG）
        for (String tag : new String[]{"lb", "ol"}) {
            java.awt.image.BufferedImage full = ImageIO.read(
                    new java.io.FileInputStream("/tmp/final_" + tag + ".png"));
            int dw = 375, dh = Math.round(full.getHeight() * 375f / full.getWidth());
            java.awt.image.BufferedImage small = new java.awt.image.BufferedImage(
                    dw, dh, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = small.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(full, 0, 0, dw, dh, null);
            g.dispose();
            javax.imageio.ImageIO.write(small, "jpg", new java.io.File("/tmp/final_" + tag + "_qq.jpg"));
            System.out.println(tag + ": " + full.getWidth() + "x" + full.getHeight() + " -> " + dw + "x" + dh);
        }

        // 全尺寸副标题区域裁片（人工/VLM复核）
        java.awt.image.BufferedImage fullLb = ImageIO.read(
                new java.io.FileInputStream("/tmp/final_lb.png"));
        javax.imageio.ImageIO.write(fullLb.getSubimage(30, 230, 500, 340), "png",
                new java.io.File("/tmp/final_lb_sub.png"));
        System.out.println("DONE");
    }
}
