import cn.kerong.penguin.spigot.render.InfoCardAssets;
import cn.kerong.penguin.spigot.render.PerformanceRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 1.7.6.5 覆盖轮渲染复核:构造 committed=6144MB / max=12288MB 的快照
 * (已提交口径,占 50%),渲染 /查性能 卡输出 PNG 供视觉复核;
 * 校验 data class 构造第 3 参落位 committedMemoryMb 字段。
 */
public class RenderPerf1765b {

    public static void main(String[] args) throws Exception {
        File work = new File(args.length > 0 ? args[0] : "/tmp/render");
        work.mkdirs();
        File imgDir = new File(work, "img");
        imgDir.mkdirs();

        cn.kerong.bot.PerformanceSnapshot snapshot = new cn.kerong.bot.PerformanceSnapshot(
            19.7, 12.4, 6144L, 12288L, 0.23, 8, 20);
        if (snapshot.getCommittedMemoryMb() != 6144L || snapshot.getMaxMemoryMb() != 12288L) {
            throw new IllegalStateException("构造第 3/4 参未落位 committedMemoryMb/maxMemoryMb: "
                + snapshot.getCommittedMemoryMb() + "/" + snapshot.getMaxMemoryMb());
        }
        System.out.println("OK 构造落位: committedMemoryMb=6144 maxMemoryMb=12288"
            + " (ratio=" + (6144.0 / 12288.0) + ")");

        int height = PerformanceRenderer.INSTANCE.measureHeight();
        BufferedImage bg = InfoCardAssets.INSTANCE.resolveBackground(
            work, PerformanceRenderer.WIDTH, height, true);
        PerformanceRenderer.PerformanceData data = new PerformanceRenderer.PerformanceData(
            "渲染复核服务器", "20:30", snapshot, bg, null, null, false, null);
        byte[] png = PerformanceRenderer.INSTANCE.render(data);
        File out = new File(work, "perf1765b.png");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            fos.write(png);
        }
        System.out.println("渲染完成: " + out.getAbsolutePath()
            + "  大小=" + png.length + " bytes  尺寸=" + PerformanceRenderer.WIDTH + "x" + height);
        if (png.length < 50000) {
            throw new IllegalStateException("PNG 过小,疑似空白: " + png.length);
        }
        BufferedImage img = ImageIO.read(out);
        System.out.println("PNG 实际尺寸: " + img.getWidth() + "x" + img.getHeight());
    }
}
