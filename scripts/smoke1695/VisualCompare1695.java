package scripts.smoke1695;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * 1.6.9.5 视觉复核：gray-mask vs blur-mask（卡片背后局部模糊）左右拼接对照图。
 * 生成 /tmp/render/visual1695.png，人工肉眼复核：
 * - blur-mask 页：卡片背后呈磨砂玻璃（柔化），卡片外背景保持清晰；
 * - gray-mask 页：整页背景清晰（原版观感）。
 */
public class VisualCompare1695 {

    /** 彩色渐变 + 色块 + 高频细纹测试背景（接近真实照片的频率分布）。 */
    static BufferedImage photoLike(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            java.awt.GradientPaint sky = new java.awt.GradientPaint(
                    0, 0, new Color(0x3a7bd5), 0, height, new Color(0xdc2430));
            g.setPaint(sky);
            g.fillRect(0, 0, width, height);
            for (int i = 0; i < 14; i++) {
                g.setColor(new Color((int) (Math.random() * 0xFFFFFF)));
                int w = 60 + (int) (Math.random() * 240);
                int h = 40 + (int) (Math.random() * 160);
                g.fillRect((int) (Math.random() * (width - w)), (int) (Math.random() * (height - h)), w, h);
            }
            for (int x = 0; x < width; x += 6) {
                g.setColor(new Color(0, 0, 0, 40));
                g.drawLine(x, 0, x, height);
            }
            g.setColor(Color.WHITE);
            g.setFont(g.getFont().deriveFont(28f));
            g.drawString("BACKGROUND DETAIL 12345", 40, height / 2);
        } finally {
            g.dispose();
        }
        return img;
    }

    public static void main(String[] args) throws Exception {
        new File("/tmp/render").mkdirs();
        Class<?> infoRendererCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer");
        Object infoRenderer = infoRendererCls.getField("INSTANCE").get(null);
        Class<?> cardDataCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer$CardData");
        Class<?> cardItemCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer$CardItem");
        Class<?> renderSettingsCls = Class.forName("cn.kerong.penguin.spigot.render.RenderSettings");
        Object settings = renderSettingsCls.getField("INSTANCE").get(null);

        FakeKeronRuntime keron = new FakeKeronRuntime();
        Object botShared = Class.forName("cn.kerong.bot.provider.BotShared").getField("INSTANCE").get(null);
        Class.forName("cn.kerong.bot.provider.BotShared")
                .getMethod("setInstance", cn.kerong.bot.KERONG.class).invoke(botShared, keron);

        BufferedImage bg = photoLike(900, 760);
        List<Object> itemList = new ArrayList<>();
        String[] labels = {"金币", "称号", "在线时长", "今日在线", "累计签到", "点券"};
        for (int i = 0; i < labels.length; i++) {
            itemList.add(cardItemCls.getConstructor(String.class, String.class).newInstance(labels[i], "示例值 " + i));
        }
        Object cardData = cardDataCls.getConstructors()[0].newInstance("Steve", itemList, null, bg);

        java.lang.reflect.Field configField = null;
        org.bukkit.configuration.file.FileConfiguration config = keron.getConfig();

        config.set("render.background-style", "gray-mask");
        byte[] pngGray = (byte[]) infoRendererCls.getMethod("render", cardDataCls).invoke(infoRenderer, cardData);
        config.set("render.background-style", "blur-mask");
        byte[] pngBlur = (byte[]) infoRendererCls.getMethod("render", cardDataCls).invoke(infoRenderer, cardData);

        BufferedImage gray = ImageIO.read(new ByteArrayInputStream(pngGray));
        BufferedImage blur = ImageIO.read(new ByteArrayInputStream(pngBlur));
        int gap = 16;
        BufferedImage combined = new BufferedImage(gray.getWidth() * 2 + gap, gray.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = combined.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, combined.getWidth(), combined.getHeight());
            g.drawImage(gray, 0, 0, null);
            g.drawImage(blur, gray.getWidth() + gap, 0, null);
            g.setColor(Color.YELLOW);
            g.setFont(g.getFont().deriveFont(22f));
            g.drawString("gray-mask (left)  |  blur-mask backdrop-filter (right)", 20, 30);
        } finally {
            g.dispose();
        }
        ImageIO.write(combined, "png", new java.io.File("/tmp/render/visual1695.png"));
        System.out.println("written /tmp/render/visual1695.png " + combined.getWidth() + "x" + combined.getHeight());
    }

    static class File {
        java.io.File f;
        File(String p) { f = new java.io.File(p); }
        void mkdirs() { f.mkdirs(); }
    }
}
