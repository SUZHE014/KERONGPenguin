package scripts.smoke173;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.bot.provider.BotShared;
import cn.kerong.penguin.spigot.render.InfoCardRenderer;
import cn.kerong.penguin.spigot.render.LeaderboardRenderer;

/**
 * 1.7.3 覆盖版五轮：浅色背景视觉转储——亮底 + 关模糊渲染排行榜与查信息，
 * 验证副标题小标签（柔和填充 + 淡描边）在浅色背景下的可读性与观感。
 */
public final class LightDump173 {

    public static void main(String[] args) throws Exception {
        FakeRuntime fake = new FakeRuntime();
        BotShared.INSTANCE.setInstance(fake);
        // 关模糊：亮底最差场景（模糊会把底色再摊开一层浅色）
        fake.getConfig().set("render.blur-strength", 0);

        File dir = new File("/tmp/render173-light");
        dir.mkdirs();

        // 1. 排行榜（浅底）
        List<LeaderboardRenderer.LeaderEntry> entries = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            entries.add(new LeaderboardRenderer.LeaderEntry(i, "Player" + i, 3600L * (9 - i), null, null));
        }
        int lbH = LeaderboardRenderer.INSTANCE.measurePageHeight(8, true);
        dump(dir, "leaderboard-light.png", LeaderboardRenderer.INSTANCE.renderPage(
                new LeaderboardRenderer.LeaderboardData("梦想服务器", "22:41", entries, 8L, 20,
                        lightBg(LeaderboardRenderer.WIDTH, lbH), "在线排行榜", "总在线时长"), true));

        // 2. 查信息（浅底）
        List<InfoCardRenderer.CardItem> items = new ArrayList<>();
        items.add(new InfoCardRenderer.CardItem("总在线时长", "128小时"));
        items.add(new InfoCardRenderer.CardItem("今日在线", "2小时15分钟"));
        items.add(new InfoCardRenderer.CardItem("累计签到", "46天"));
        int infoH = cardHeight(items.size());
        dump(dir, "info-light.png", InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Steve", items, null,
                        lightBg(InfoCardRenderer.WIDTH, infoH), "梦想服务器", "22:41")));

        System.out.println("浅色背景渲染完成 → " + dir);
    }

    /** 亮底（暖纸色，模拟浅色背景图）。 */
    private static java.awt.image.BufferedImage lightBg(int w, int h) {
        java.awt.image.BufferedImage bg =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = bg.createGraphics();
        g.setColor(new java.awt.Color(244, 240, 233));
        g.fillRect(0, 0, w, h);
        g.setColor(new java.awt.Color(228, 223, 214));
        for (int y = 0; y < h; y += 7) {
            g.fillRect(0, y, w, 3);
        }
        g.dispose();
        return bg;
    }

    /** internal 方法带模块后缀——按前缀反射查找（同 RenderDump173）。 */
    private static int cardHeight(int itemCount) throws Exception {
        for (java.lang.reflect.Method m : InfoCardRenderer.INSTANCE.getClass().getMethods()) {
            if (m.getName().startsWith("cardHeight") && m.getParameterCount() == 1) {
                return (Integer) m.invoke(InfoCardRenderer.INSTANCE, itemCount);
            }
        }
        throw new IllegalStateException("cardHeight 方法未找到");
    }

    private static void dump(File dir, String name, byte[] png) throws Exception {
        new File(dir, name).delete();
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(new File(dir, name))) {
            fos.write(png);
        }
        System.out.println("  " + name + " = " + png.length + " bytes");
    }
}
