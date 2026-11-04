package scripts.smoke173;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.bot.provider.BotShared;
import cn.kerong.penguin.spigot.render.InfoCardAssets;
import cn.kerong.penguin.spigot.render.InfoCardRenderer;
import cn.kerong.penguin.spigot.render.LeaderboardRenderer;
import cn.kerong.penguin.spigot.render.OnlineListRenderer;
import cn.kerong.penguin.spigot.render.PerformanceRenderer;

/**
 * 1.7.3 覆盖版视觉预览：默认配置渲染五张卡片落盘 PNG 供人工复核。
 */
public final class RenderDump173 {

    public static void main(String[] args) throws Exception {
        FakeRuntime fake = new FakeRuntime();
        BotShared.INSTANCE.setInstance(fake);

        File dir = new File("/tmp/render173");
        dir.mkdirs();
        // 1. 查性能
        int pfH = PerformanceRenderer.INSTANCE.measureHeight();
        java.awt.image.BufferedImage pfBg = InfoCardAssets.INSTANCE.resolveBackground(
                dir, PerformanceRenderer.WIDTH, pfH, true);
        PerformanceSnapshot snap = new PerformanceSnapshot(19.6, 12.4, 6144L, 12288L, 0.23, 3, 20);
        dump(dir, "performance.png", PerformanceRenderer.INSTANCE.render(
                new PerformanceRenderer.PerformanceData("梦想服务器", "22:41", snap, pfBg, 20, false, null)));

        // 2. 查在线
        List<OnlineListRenderer.OnlineEntry> online = new ArrayList<>();
        online.add(new OnlineListRenderer.OnlineEntry("Steve", null));
        online.add(new OnlineListRenderer.OnlineEntry("Alex", null));
        online.add(new OnlineListRenderer.OnlineEntry("Notch", null));
        int olH = OnlineListRenderer.INSTANCE.measurePageHeight(3, true);
        java.awt.image.BufferedImage olBg = InfoCardAssets.INSTANCE.resolveBackground(
                dir, OnlineListRenderer.WIDTH, olH, true);
        dump(dir, "online.png", OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData("梦想服务器", "22:41", online, olBg)).get(0));

        // 3. 在线排行榜
        List<LeaderboardRenderer.LeaderEntry> entries = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            entries.add(new LeaderboardRenderer.LeaderEntry(i, "Player" + i, 3600L * (9 - i), null, null));
        }
        int lbH = LeaderboardRenderer.INSTANCE.measurePageHeight(8, true);
        java.awt.image.BufferedImage lbBg = InfoCardAssets.INSTANCE.resolveBackground(
                dir, LeaderboardRenderer.WIDTH, lbH, true);
        dump(dir, "leaderboard.png", LeaderboardRenderer.INSTANCE.renderPage(
                new LeaderboardRenderer.LeaderboardData("梦想服务器", "22:41", entries, 8L, 20, lbBg,
                        "在线排行榜", "总在线时长"), true));

        // 4. 查信息
        List<InfoCardRenderer.CardItem> items = new ArrayList<>();
        items.add(new InfoCardRenderer.CardItem("总在线时长", "128小时"));
        items.add(new InfoCardRenderer.CardItem("今日在线", "2小时15分钟"));
        items.add(new InfoCardRenderer.CardItem("金币", "3200"));
        items.add(new InfoCardRenderer.CardItem("点券", "150"));
        items.add(new InfoCardRenderer.CardItem("走路距离", "35公里"));
        items.add(new InfoCardRenderer.CardItem("称号", "新手冒险家"));
        int infoH = cardHeight(6);
        java.awt.image.BufferedImage infoBg = InfoCardAssets.INSTANCE.resolveBackground(
                dir, InfoCardRenderer.WIDTH, infoH, true);
        dump(dir, "info.png", InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Steve", items, null, infoBg, "梦想服务器", "22:41")));

        System.out.println("渲染完成 → " + dir.getAbsolutePath());
    }

    /** internal 方法带模块后缀（NF 产物 $server_NeoForge）——按前缀反射查找。 */
    private static int cardHeight(int itemCount) throws Exception {
        for (java.lang.reflect.Method m : InfoCardRenderer.INSTANCE.getClass().getMethods()) {
            if (m.getName().startsWith("cardHeight") && m.getParameterCount() == 1) {
                return (Integer) m.invoke(InfoCardRenderer.INSTANCE, itemCount);
            }
        }
        throw new IllegalStateException("cardHeight 方法未找到");
    }

    private static void dump(File dir, String name, byte[] png) throws Exception {
        File out = new File(dir, name);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            fos.write(png);
        }
        System.out.println("  " + name + " = " + png.length + " bytes");
    }
}
