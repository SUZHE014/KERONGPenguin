package scripts.smoke1731o;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import cn.kerong.bot.provider.BotShared;
import cn.kerong.penguin.spigot.render.InfoCardRenderer;
import cn.kerong.penguin.spigot.render.OnlineListRenderer;

/** 渲染实图供人工视觉复核（1.7.3.1 覆盖版）。 */
public final class DumpCard {

    public static void main(String[] args) throws Exception {
        FakeRuntime fake = new FakeRuntime();
        BotShared.INSTANCE.setInstance(fake);
        fake.getConfig().set("render.blur-strength", 50);

        List<OnlineListRenderer.OnlineEntry> many = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            many.add(new OnlineListRenderer.OnlineEntry("Player" + i, null));
        }
        // 中亮渐变背景（天空风格：上亮下更亮——「图片稍大」的原始反馈场景）
        java.awt.image.BufferedImage bg = new java.awt.image.BufferedImage(
                OnlineListRenderer.WIDTH, 1400, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = bg.createGraphics();
        java.awt.GradientPaint gp = new java.awt.GradientPaint(
                0, 0, new java.awt.Color(150, 170, 200),
                0, 1400, new java.awt.Color(235, 240, 250));
        g.setPaint(gp);
        g.fillRect(0, 0, OnlineListRenderer.WIDTH, 1400);
        g.dispose();

        byte[] png = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData("主服务器", "08:20", many, bg)).get(0);
        try (FileOutputStream out = new FileOutputStream("/tmp/render_ol.png")) {
            out.write(png);
        }

        List<InfoCardRenderer.CardItem> items = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            items.add(new InfoCardRenderer.CardItem("统计项目" + i, String.valueOf(1000 + i)));
        }
        int infoH = 568 + 96 * 3; // cardHeight(9)，internal 方法 Java 侧直调不可见，直接用公式
        byte[] infoPng = InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Steve", items, null, bg, "主服务器", "08:20"));
        try (FileOutputStream out = new FileOutputStream("/tmp/render_info.png")) {
            out.write(infoPng);
        }
        System.out.println("dumped: /tmp/render_ol.png / /tmp/render_info.png");
    }
}
