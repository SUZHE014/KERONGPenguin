package scripts.smoke1700;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * 视觉复核：渲染一张 1.7.0 新样式（毛玻璃 + 调浅卡片）查信息卡片 PNG，
 * 供人工检查观感（不是断言测试）。
 */
public class VisualCheck1700 {
    public static void main(String[] args) throws Exception {
        FakeKeronRuntime keron = new FakeKeronRuntime();
        Object botShared = Class.forName("cn.kerong.bot.provider.BotShared").getField("INSTANCE").get(null);
        Class.forName("cn.kerong.bot.provider.BotShared")
                .getMethod("setInstance", cn.kerong.bot.KERONG.class)
                .invoke(botShared, keron);
        org.bukkit.configuration.file.FileConfiguration config = keron.getConfig();
        config.set("render.blur-strength", 50);

        // 彩色渐变 + 高频条纹背景（能同时看出模糊与卡片底色深浅）
        BufferedImage bg = new BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 900; y++) {
            for (int x = 0; x < 900; x++) {
                int r = (x * 255 / 900);
                int g = (y * 255 / 900);
                int b = 128;
                if ((x / 8) % 2 == 0) {
                    r = Math.min(255, r + 40);
                    g = Math.min(255, g + 40);
                }
                bg.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }

        Class<?> cardDataCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer$CardData");
        Class<?> cardItemCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer$CardItem");
        List<Object> itemList = new ArrayList<>();
        String[][] items = {
                {"总在线时长", "77时20分"}, {"今日在线", "2时15分"}, {"金币", "1.6k"},
                {"点券", "320"}, {"称号", "建筑大师"}, {"累计签到", "42 天"},
                {"屠龙次数", "8"}, {"击杀玩家", "156"}, {"死亡次数", "89"},
        };
        for (String[] it : items) {
            itemList.add(cardItemCls.getConstructor(String.class, String.class).newInstance(it[0], it[1]));
        }
        Object cardData = cardDataCls.getConstructors()[0].newInstance("Steve", itemList, null, bg);
        Object infoRenderer = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer")
                .getField("INSTANCE").get(null);
        byte[] png = (byte[]) infoRenderer.getClass().getMethod("render", cardDataCls).invoke(infoRenderer, cardData);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        File out = new File("build/visual-check-1700.png");
        ImageIO.write(img, "png", out);
        System.out.println("已生成：" + out.getAbsolutePath() + "（" + img.getWidth() + "x" + img.getHeight() + "）");
    }
}
