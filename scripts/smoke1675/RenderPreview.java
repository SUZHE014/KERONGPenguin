import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.penguin.spigot.render.InfoCardAssets;
import cn.kerong.penguin.spigot.render.PerformanceRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;

/** 渲染 1.6.7.5 覆盖版查性能卡片 PNG（内置背景）供视觉复核。 */
public class RenderPreview {
    public static void main(String[] args) throws Exception {
        File work = Files.createTempDirectory("penguin-preview").toFile();
        int height = PerformanceRenderer.INSTANCE.measureHeight();
        BufferedImage bg = InfoCardAssets.INSTANCE.resolveBackground(work,
                PerformanceRenderer.WIDTH, height, true);
        PerformanceRenderer.PerformanceData data = new PerformanceRenderer.PerformanceData(
                "预览服务器", "22:41",
                PerformanceSnapshot.capture(19.87, 12.4, 0.234), bg);
        byte[] png = PerformanceRenderer.INSTANCE.render(data);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        ImageIO.write(img, "png", new File(args[0]));
        System.out.println("saved " + args[0] + " " + img.getWidth() + "x" + img.getHeight());
    }
}
