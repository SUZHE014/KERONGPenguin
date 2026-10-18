import cn.kerong.penguin.spigot.render.InfoCardAssets;
import cn.kerong.penguin.spigot.render.PerformanceRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 渲染当前版本（1.6.7.4 源码）的 /查性能 卡片，输出 PNG 供人工查看。
 */
public class RenderPerf175 {

    public static void main(String[] args) throws Exception {
        File work = new File(args.length > 0 ? args[0] : "/tmp/render-perf-175");
        work.mkdirs();
        File imgDir = new File(work, "img");
        imgDir.mkdirs();

        int height = PerformanceRenderer.INSTANCE.measureHeight();
        BufferedImage bg = InfoCardAssets.INSTANCE.resolveBackground(
            work, PerformanceRenderer.WIDTH, height, true);

        cn.kerong.bot.PerformanceSnapshot snapshot = new cn.kerong.bot.PerformanceSnapshot(
            19.7, 12.4, 6144L, 12288L, 0.23);
        PerformanceRenderer.PerformanceData data = new PerformanceRenderer.PerformanceData(
            "测试服务器", "22:41", snapshot, bg);
        byte[] png = PerformanceRenderer.INSTANCE.render(data);
        File out = new File(work, "performance-current.png");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            fos.write(png);
        }
        System.out.println("渲染完成: " + out.getAbsolutePath()
            + "  大小=" + png.length + " bytes  尺寸=" + PerformanceRenderer.WIDTH + "x" + height);

        BufferedImage img = ImageIO.read(out);
        System.out.println("PNG 实际尺寸: " + img.getWidth() + "x" + img.getHeight()
            + "  hasAlpha: " + img.getColorModel().hasAlpha());
    }
}
