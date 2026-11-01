package scripts.smoke1694;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;

/**
 * 视觉复核工具：同一张彩色测试背景分别按 gray-mask / blur-mask 处理，
 * 左右拼接输出 PNG 供人工/AI 目检模糊遮罩观感（EasyBot 液态玻璃风格）。
 *
 * 运行后查看 /tmp/render-compare-1694.png
 */
public class VisualCompare1694 {

    public static void main(String[] args) throws Exception {
        Class<?> assetsCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardAssets");
        Object assets = assetsCls.getField("INSTANCE").get(null);
        FakeKeronRuntime keron = new FakeKeronRuntime();
        Class.forName("cn.kerong.bot.provider.BotShared")
                .getMethod("setInstance", cn.kerong.bot.KERONG.class)
                .invoke(Class.forName("cn.kerong.bot.provider.BotShared").getField("INSTANCE").get(null), keron);

        // 彩色测试背景：渐变 + 圆形色块 + 高频细纹（模拟照片细节）
        int w = 900, h = 568;
        BufferedImage photo = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = photo.createGraphics();
        try {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int r = (x * 255 / w);
                    int gr = (y * 255 / h);
                    int b = 255 - (x * 255 / w);
                    photo.setRGB(x, y, (r << 16) | (gr << 8) | b);
                }
            }
            g.setColor(new Color(0xFF7700));
            g.fillOval(120, 100, 260, 260);
            g.setColor(new Color(0x00CCFF));
            g.fillOval(520, 220, 300, 300);
            g.setColor(new Color(0xFFEE00));
            g.fillRect(300, 380, 320, 130);
            // 高频细纹（模拟细节）
            for (int i = 0; i < 1200; i++) {
                int x = (int) (Math.random() * w);
                int y = (int) (Math.random() * h);
                photo.setRGB(x, y, Math.random() > 0.5 ? 0xFFFFFF : 0x000000);
            }
        } finally {
            g.dispose();
        }

        Method cover = assetsCls.getDeclaredMethod("coverImage", BufferedImage.class, int.class, int.class);
        cover.setAccessible(true);
        Method applyStyle = assetsCls.getDeclaredMethod("applyBackgroundStyle", BufferedImage.class, boolean.class);
        applyStyle.setAccessible(true);

        keron.getConfig().set("render.background-style", "gray-mask");
        BufferedImage grayOut = (BufferedImage) applyStyle.invoke(assets, cover.invoke(assets, photo, w, h), false);
        keron.getConfig().set("render.background-style", "blur-mask");
        BufferedImage blurOut = (BufferedImage) applyStyle.invoke(assets, cover.invoke(assets, photo, w, h), false);

        // 左右拼接（中间留 8px 白缝）
        BufferedImage combined = new BufferedImage(w * 2 + 8, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D cg = combined.createGraphics();
        try {
            cg.drawImage(grayOut, 0, 0, null);
            cg.setColor(Color.WHITE);
            cg.fillRect(w, 0, 8, h);
            cg.drawImage(blurOut, w + 8, 0, null);
        } finally {
            cg.dispose();
        }
        File out = new File("/tmp/render-compare-1694.png");
        javax.imageio.ImageIO.write(combined, "png", out);
        System.out.println("对比图输出: " + out.getAbsolutePath() + "（左=gray-mask 原版，右=blur-mask 模糊遮罩）");
    }
}
