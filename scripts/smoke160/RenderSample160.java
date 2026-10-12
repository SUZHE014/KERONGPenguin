import cn.huohuas001.huhobotPenguin.spigot.render.InfoCardAssets;
import cn.huohuas001.huhobotPenguin.spigot.render.LeaderboardRenderer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 在线排行榜旧用法回归样本生成（1.5.7.3 与 1.6.0 各跑一次，比对输出 PNG 字节）：
 * 使用与旧版完全一致的调用形态（不带 title/listHeader/valueText 的新参数在
 * 1.5.7.3 上不可用——本类走 1.6.0 的默认值路径：显式传默认值等价旧版调用）。
 * 1.5.7.3 侧用 RenderLegacy173（四参构造）。
 */
public class RenderSample160 {

    public static void main(String[] args) throws Exception {
        LeaderboardRenderer lb = LeaderboardRenderer.INSTANCE;
        List<LeaderboardRenderer.LeaderEntry> entries = new ArrayList<>();
        long[] seconds = {864000, 691200, 518400, 432000, 345600, 259200, 172800,
            86400, 7200, 3600, 1800, 600, 120, 60, 30};
        for (int i = 1; i <= 15; i++) {
            // 1.6.0 泛化签名：valueText 传 null → 走 formatPlayTime（旧版行为）
            entries.add(new LeaderboardRenderer.LeaderEntry(i, "RegPlayer" + i, seconds[i - 1], null, null));
        }
        int h = lb.measurePageHeight(15, true);
        Object bg = InfoCardAssets.INSTANCE.processedBuiltinBackground(
            InfoCardAssets.BUILTIN_BACKGROUND_LEADERBOARD, 1200, h);
        // 显式传默认 title/listHeader（等价旧版硬编码值）
        LeaderboardRenderer.LeaderboardData data = new LeaderboardRenderer.LeaderboardData(
            "KERONGPenguin", "12:00", entries, 15L, 15,
            (java.awt.image.BufferedImage) bg, "在线排行榜", "总在线时长");
        byte[] png = lb.renderPage(data, true);
        File out = new File(args[0]);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            fos.write(png);
        }
        System.out.println("written: " + out.getAbsolutePath() + " (" + png.length + " bytes)");
        // 顺带解码自检
        System.out.println("decode: " + (ImageIO.read(out) != null));
    }
}
