import cn.huohuas001.huhobotPenguin.spigot.render.InfoCardAssets;
import cn.huohuas001.huhobotPenguin.spigot.render.LeaderboardRenderer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 1.5.7.3 旧签名渲染样本（四参 LeaderEntry 构造，类路径 = 1.5.7.3 JAR）：
 * 与 RenderSample160 生成同输入样本，用于新旧输出字节比对
 * （LeaderboardRenderer 泛化必须对旧用法逐字节不变）。
 */
public class RenderLegacy173 {

    public static void main(String[] args) throws Exception {
        LeaderboardRenderer lb = LeaderboardRenderer.INSTANCE;
        long[] seconds = {864000, 691200, 518400, 432000, 345600, 259200, 172800,
            86400, 7200, 3600, 1800, 600, 120, 60, 30};
        List<LeaderboardRenderer.LeaderEntry> entries = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            entries.add(new LeaderboardRenderer.LeaderEntry(i, "RegPlayer" + i, seconds[i - 1], null));
        }
        int h = lb.measurePageHeight(15, true);
        Object bg = InfoCardAssets.INSTANCE.processedBuiltinBackground(
            InfoCardAssets.BUILTIN_BACKGROUND_LEADERBOARD, 1200, h);
        LeaderboardRenderer.LeaderboardData data = new LeaderboardRenderer.LeaderboardData(
            "KERONGPenguin", "12:00", entries, 15L, 15,
            (java.awt.image.BufferedImage) bg);
        byte[] png = lb.renderPage(data, true);
        File out = new File(args[0]);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            fos.write(png);
        }
        System.out.println("written: " + out.getAbsolutePath() + " (" + png.length + " bytes)");
        System.out.println("decode: " + (ImageIO.read(out) != null));
    }
}
