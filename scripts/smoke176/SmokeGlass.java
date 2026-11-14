import cn.kerong.penguin.spigot.render.InfoCardAssets;
import cn.kerong.penguin.spigot.render.LiquidGlassPainter;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import javax.imageio.*;

public class SmokeGlass {
    static int fails = 0;
    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + "  " + detail);
        if (!ok) fails++;
    }

    static BufferedImage gradientSource(int W, int H) {
        BufferedImage src = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int r = x * 255 / W, g = y * 255 / H, b = ((x / 24 + y / 24) % 2 == 0) ? 220 : 40;
                src.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        return src;
    }

    public static void main(String[] args) throws Exception {
        int W = 800, H = 520;
        BufferedImage src = gradientSource(W, H);

        // ---- 1. 主卡片:玻璃效果 ----
        BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = canvas.createGraphics();
        g2.drawImage(src, 0, 0, null);
        InfoCardAssets.BackgroundPlacement pl = new InfoCardAssets.BackgroundPlacement(0, 0, W, H);
        long t0 = System.nanoTime();
        LiquidGlassPainter.INSTANCE.paintCard(g2, src, pl, W, H, 120f, 100f, 560f, 320f, 28f);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        g2.dispose();
        ImageIO.write(canvas, "png", new File("/tmp/render/glass_main.png"));

        int[] center = px(canvas, 400, 260);
        int[] srcCenter = px(src, 400, 260);
        // 内部(法线≈0): 只有轻微提亮 sheen(+9)
        check("中心区近似透传(±40)", Math.abs(center[0] - srcCenter[0]) <= 40
                && Math.abs(center[1] - srcCenter[1]) <= 40, arr(center) + " vs " + arr(srcCenter));
        // 左上边缘带:高光(比源亮)
        int[] tl = px(canvas, 128, 108);   // 左上圆角弧上(对角法线,高光最强)
        int[] srcTl = px(src, 128, 108);
        check("左上边缘高光(增亮)", tl[0] > srcTl[0] || tl[1] > srcTl[1] || tl[2] > srcTl[2],
                arr(tl) + " vs " + arr(srcTl));
        // 右下边缘带:内阴影(比源暗)
        int[] br = px(canvas, 670, 410);   // 右下圆角弧内2px(背光,内阴影最强)
        int[] srcBr = px(src, 670, 410);
        check("右下边缘内阴影(变暗)", br[0] < srcBr[0] || br[1] < srcBr[1] || br[2] < srcBr[2],
                arr(br) + " vs " + arr(srcBr));
        int[] te = px(canvas, 400, 103);
        int[] srcTe = px(src, 400, 103);
        check("顶边中段也有高光(边脊渐变)", te[0] > srcTe[0] || te[1] > srcTe[1] || te[2] > srcTe[2],
                arr(te) + " vs " + arr(srcTe));
        // 圆角外不绘制
        int[] cornerOut = px(canvas, 121, 101); // 左上圆角外(28px 半径)
        int[] srcCornerOut = px(src, 121, 101);
        check("圆角外不受影响", cornerOut[0] == srcCornerOut[0] && cornerOut[1] == srcCornerOut[1],
                arr(cornerOut));
        // 边缘折射:采样偏移使边缘带颜色偏离源同位置(非纯透传)
        boolean refracted = false;
        for (int x = 122; x < 140; x++) {
            int[] c = px(canvas, x, 260);
            int[] s = px(src, x, 260);
            if (Math.abs(c[2] - s[2]) > 24) { refracted = true; break; } // 蓝色棋盘通道位移明显
        }
        check("边缘折射生效(中部左边缘采样偏移)", refracted, "");
        check("单卡渲染耗时<600ms", ms < 600, ms + "ms");

        // ---- 2. 边界场景:小卡片 ----
        BufferedImage c2 = new BufferedImage(60, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g22 = c2.createGraphics();
        g22.drawImage(src, 0, 0, null);
        LiquidGlassPainter.INSTANCE.paintCard(g22, src, pl, W, H, 10f, 10f, 6f, 6f, 2f);
        g22.dispose();
        check("3x3 极小卡片不崩溃", true, "");

        // ---- 3. 半径 0 ----
        BufferedImage c3 = new BufferedImage(60, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g23 = c3.createGraphics();
        g23.drawImage(src, 0, 0, null);
        LiquidGlassPainter.INSTANCE.paintCard(g23, src, pl, W, H, 5f, 5f, 50f, 50f, 0f);
        g23.dispose();
        check("radius=0 不崩溃", true, "");

        // ---- 4. 源图小于卡片(区域钳制) ----
        BufferedImage tiny = gradientSource(40, 30);
        InfoCardAssets.BackgroundPlacement plTiny = new InfoCardAssets.BackgroundPlacement(0, 0, 40, 30);
        BufferedImage c4 = new BufferedImage(60, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g24 = c4.createGraphics();
        g24.drawImage(tiny, 0, 0, 60, 60, null);
        LiquidGlassPainter.INSTANCE.paintCard(g24, tiny, plTiny, 60, 60, 5f, 5f, 50f, 50f, 8f);
        g24.dispose();
        check("小于卡片的源图不崩溃(钳制)", true, "");

        System.out.println(fails == 0 ? "===== 全部通过 =====" : "===== 失败 " + fails + " =====");
        System.exit(fails == 0 ? 0 : 1);
    }

    static int[] px(BufferedImage img, int x, int y) {
        int c = img.getRGB(x, y);
        return new int[]{(c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF};
    }

    static String arr(int[] a) {
        return a[0] + "," + a[1] + "," + a[2];
    }
}
