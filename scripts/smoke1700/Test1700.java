package scripts.smoke1700;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * 1.7.0 功能冒烟（独立 JVM，headless，NF 分发 JAR 真实字节码）。
 *
 * 本版核心：头像统一在线获取（本地链路移除）+ 外层纹理修复 +
 * 毛玻璃统一（原版遮罩删除，模糊度 render.blur-strength）+
 * /执行命令 内容清洗 + 配置重建不再自动升级旧默认值。
 *
 * 覆盖：
 *  SB 组  控制台输出清洗（sanitizeConsoleText / clipCommandOutput）：
 *   - SB1 ANSI CSI 转义剥离
 *   - SB2 § 颜色码剥离
 *   - SB3 控制字符剥离（\n 保留）
 *   - SB4 OSC 序列剥离
 *   - SB5 超长裁剪（带截断标记）
 *   - SB6 清洗 + 裁剪组合
 *  BL 组  毛玻璃统一与模糊度：
 *   - BL1 无插件上下文 blurStrength() 默认 50
 *   - BL2 配置读取：0 / 100 / 越界收敛（150→100、-5→0）
 *   - BL3 强度映射：blurDownscaleOf / blurBoxRadiusOf 三档数值
 *   - BL4 drawBackground：strength 0 → null（不模糊零开销）；50 → 模糊上下文
 *   - BL5 端到端：棋盘背景默认 50 档，卡片内高频 << 卡片外
 *   - BL6 strength 0 → 内外相近；100 → 比 50 更平
 *   - BL7 卡片底色调浅：白底 + strength 0，统计面板亮度均值 > 150
 *          （旧 alpha 142 时 ≈ 113，新 76 ≈ 179，150 为分界）
 *   - BL8 cover 回归：三段色图中心裁剪（中段两侧均绿）
 *  AV 组  头像链路：
 *   - AV1 AvatarRequest 两参构造在、四参不在（本地参数已删）
 *   - AV2 cropFace 64×64：帽子层（外层）叠加生效（脸红帽蓝 → 蓝）
 *   - AV3 cropFace 64×32：无有效帽子层 → 仅内层（红）
 *  CF 组  配置重建（v26 → v27）：
 *   - CF1 重建后 config-version 27
 *   - CF2 旧默认值保留不升级（performance.cache-seconds: 5 仍是 5）
 *   - CF3 自定义值保留（bot.name / serverName / groups 行内格式）
 *   - CF4 废弃键清理 + 新键来自 JAR 模板
 *          （avatar-fetch-priority / background-style / global-opacity 消失，
 *           blur-strength: 50 出现）
 *
 * 编译运行（仓库根目录，javac/java 用 tooling JDK21）：
 *   javac -cp build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.0.jar \
 *     -d scripts/smoke1700-out scripts/smoke1700/*.java
 *   java -Djava.awt.headless=true \
 *     -cp scripts/smoke1700-out:build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.0.jar \
 *     scripts.smoke1700.Test1700
 */
public class Test1700 {

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        check(name, cond, "");
    }

    static void check(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : " — " + detail));
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : " — " + detail));
        }
    }

    /** 按名前缀查找方法（Kotlin internal 方法字节码名带模块后缀 $server_NeoForge / $common_Bot）。 */
    static Method findMethod(Class<?> cls, String namePrefix, int paramCount) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(namePrefix) || m.getName().startsWith(namePrefix + "$")) {
                if (m.getParameterCount() == paramCount) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new RuntimeException("method not found: " + cls.getName() + "." + namePrefix + "/" + paramCount);
    }

    /** 相邻像素平均亮度差（越高越“高频”，模糊后大幅下降）。 */
    static double meanNeighborDiff(BufferedImage img, int x0, int y0, int x1, int y1) {
        long total = 0;
        long count = 0;
        for (int y = y0; y < y1; y += 2) {
            for (int x = x0; x + 1 < x1; x += 1) {
                int a = img.getRGB(x, y);
                int b = img.getRGB(x + 1, y);
                int la = ((a >> 16) & 0xFF) + ((a >> 8) & 0xFF) + (a & 0xFF);
                int lb = ((b >> 16) & 0xFF) + ((b >> 8) & 0xFF) + (b & 0xFF);
                total += Math.abs(la - lb);
                count++;
            }
        }
        return count == 0 ? 0 : (double) total / count;
    }

    /** 区域平均亮度（0-255）。 */
    static double meanLuma(BufferedImage img, int x0, int y0, int x1, int y1) {
        long total = 0;
        long count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int c = img.getRGB(x, y);
                total += (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3.0;
                count++;
            }
        }
        return count == 0 ? 0 : (double) total / count;
    }

    /** 区域平均 R/G/B。 */
    static int[] meanRgb(BufferedImage img, int x0, int y0, int x1, int y1) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int c = img.getRGB(x, y);
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        return n == 0 ? new int[]{0, 0, 0} : new int[]{(int) (r / n), (int) (g / n), (int) (b / n)};
    }

    /** 黑白 2px 棋盘图（高频，模糊的理想测试输入）。 */
    static BufferedImage checkerboard(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean white = ((x / 2) + (y / 2)) % 2 == 0;
                img.setRGB(x, y, white ? 0xFFFFFF : 0x101010);
            }
        }
        return img;
    }

    /** 三段色横条图：x<100 红 | 100≤x<300 绿 | x≥300 蓝（cover 裁剪测试输入）。 */
    static BufferedImage triBand(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c = x < 100 ? 0xCC2020 : (x < 300 ? 0x20CC40 : 0x2040CC);
                img.setRGB(x, y, c);
            }
        }
        return img;
    }

    /** 纯白底图。 */
    static BufferedImage white(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, 0xFFFFFF);
            }
        }
        return img;
    }

    /** 合成皮肤：64×64 脸红帽蓝 / 64×32 仅脸红。 */
    static BufferedImage syntheticSkin(int height) {
        BufferedImage img = new BufferedImage(64, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < 64; x++) {
                img.setRGB(x, y, 0x00FF00);
            }
        }
        for (int y = 8; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                img.setRGB(x, y, 0xCC2020); // 内层脸：红
            }
        }
        if (height >= 64) {
            for (int y = 8; y < 16; y++) {
                for (int x = 40; x < 48; x++) {
                    img.setRGB(x, y, 0x2040CC); // 外层帽子：蓝（不透明）
                }
            }
        }
        return img;
    }

    public static void main(String[] args) throws Exception {
        // ================= SB 组：控制台输出清洗 =================
        System.out.println("== SB 组：/执行命令 输出清洗 ==");
        Class<?> adminCls = Class.forName("cn.kerong.bot.events.commands.AdministrationCommands");
        Object admin = adminCls.getDeclaredConstructor().newInstance();
        Class<?> supportCls = Class.forName("cn.kerong.bot.events.commands.CommandSupport");
        Method sanitize = findMethod(supportCls, "sanitizeConsoleText", 1);
        Method clip = findMethod(supportCls, "clipCommandOutput", 1);

        String s1 = (String) sanitize.invoke(admin, "\u001B[38;2;255;0;0m红色文字\u001B[0m");
        check("SB1 ANSI CSI 转义剥离", s1.equals("红色文字"), "[" + s1 + "]");
        String s2 = (String) sanitize.invoke(admin, "§a绿色§r文字§l粗体");
        check("SB2 § 颜色码剥离", s2.equals("绿色文字粗体"), "[" + s2 + "]");
        String s3 = (String) sanitize.invoke(admin, "a\u0000b\u0007c\nd\u007F");
        check("SB3 控制字符剥离（\\n 保留）", s3.equals("abc\nd"), "[" + s3.replace("\n", "\\n") + "]");
        String s4 = (String) sanitize.invoke(admin, "\u001B]0;窗口标题\u0007正文");
        check("SB4 OSC 序列剥离", s4.equals("正文"), "[" + s4 + "]");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longText.append("第").append(i).append("行输出数据\n");
        }
        String s5 = (String) clip.invoke(admin, longText.toString());
        check("SB5 超长裁剪带截断标记", s5.length() < 1350 && s5.contains("已截断"),
                String.format("len=%d", s5.length()));
        String mixed = "\u001B[32m" + longText + "§a";
        String s6 = (String) clip.invoke(admin, mixed);
        boolean noEscape = true;
        for (int i = 0; i < s6.length(); i++) {
            char ch = s6.charAt(i);
            if (ch < 32 && ch != '\n' || ch == 127 || ch == '§') {
                noEscape = false;
                break;
            }
        }
        check("SB6 清洗 + 裁剪组合（无 ESC/§/控制字符）", noEscape && s6.length() < 1350,
                String.format("len=%d", s6.length()));

        // ================= BL 组：毛玻璃统一与模糊度 =================
        System.out.println("== BL 组：毛玻璃统一与模糊度 ==");
        Class<?> assetsCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardAssets");
        Object assets = assetsCls.getField("INSTANCE").get(null);
        Class<?> renderSettingsCls = Class.forName("cn.kerong.penguin.spigot.render.RenderSettings");
        Class<?> infoRendererCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer");
        Object infoRenderer = infoRendererCls.getField("INSTANCE").get(null);

        // --- BL1：无插件上下文（安全默认 50） ---
        int defStrength = (Integer) renderSettingsCls.getMethod("blurStrength")
                .invoke(renderSettingsCls.getField("INSTANCE").get(null));
        check("BL1 无插件上下文 blurStrength()=50", defStrength == 50, String.valueOf(defStrength));

        // 注册假运行时（走 BotShared 真实配置通路）
        FakeKeronRuntime keron = new FakeKeronRuntime();
        Object botShared = Class.forName("cn.kerong.bot.provider.BotShared").getField("INSTANCE").get(null);
        Class.forName("cn.kerong.bot.provider.BotShared")
                .getMethod("setInstance", cn.kerong.bot.KERONG.class)
                .invoke(botShared, keron);
        org.bukkit.configuration.file.FileConfiguration config = keron.getConfig();
        Object settings = renderSettingsCls.getField("INSTANCE").get(null);

        config.set("render.blur-strength", 0);
        int v0 = (Integer) renderSettingsCls.getMethod("blurStrength").invoke(settings);
        config.set("render.blur-strength", 100);
        int v100 = (Integer) renderSettingsCls.getMethod("blurStrength").invoke(settings);
        config.set("render.blur-strength", 150);
        int v150 = (Integer) renderSettingsCls.getMethod("blurStrength").invoke(settings);
        config.set("render.blur-strength", -5);
        int vneg = (Integer) renderSettingsCls.getMethod("blurStrength").invoke(settings);
        check("BL2 配置读取与收敛（0/100/150→100/-5→0）",
                v0 == 0 && v100 == 100 && v150 == 100 && vneg == 0,
                String.format("%d/%d/%d/%d", v0, v100, v150, vneg));

        Method downscaleOf = findMethod(assetsCls, "blurDownscaleOf", 1);
        Method boxRadiusOf = findMethod(assetsCls, "blurBoxRadiusOf", 1);
        int d0 = (Integer) downscaleOf.invoke(assets, 0);
        int d50 = (Integer) downscaleOf.invoke(assets, 50);
        int d100 = (Integer) downscaleOf.invoke(assets, 100);
        int r0 = (Integer) boxRadiusOf.invoke(assets, 0);
        int r50 = (Integer) boxRadiusOf.invoke(assets, 50);
        int r100 = (Integer) boxRadiusOf.invoke(assets, 100);
        check("BL3 强度映射三档（d: 2/5/8，r: 1/2/3）",
                d0 == 2 && d50 == 5 && d100 == 8 && r0 == 1 && r50 == 2 && r100 == 3,
                String.format("d=%d/%d/%d r=%d/%d/%d", d0, d50, d100, r0, r50, r100));

        // --- BL4：drawBackground 返回（0 → null；50 → 上下文） ---
        config.set("render.blur-strength", 0);
        BufferedImage canvas = new BufferedImage(400, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        Object ctx0;
        try {
            ctx0 = assetsCls.getMethod("drawBackground", Graphics2D.class, BufferedImage.class, int.class, int.class)
                    .invoke(assets, g, checkerboard(400, 100), 400, 100);
        } finally {
            g.dispose();
        }
        check("BL4a strength 0 → 无模糊上下文（null）", ctx0 == null);
        config.set("render.blur-strength", 50);
        canvas = new BufferedImage(400, 100, BufferedImage.TYPE_INT_RGB);
        g = canvas.createGraphics();
        Object ctx50;
        try {
            ctx50 = assetsCls.getMethod("drawBackground", Graphics2D.class, BufferedImage.class, int.class, int.class)
                    .invoke(assets, g, checkerboard(400, 100), 400, 100);
        } finally {
            g.dispose();
        }
        check("BL4b strength 50 → 模糊上下文非 null", ctx50 != null);

        // --- BL5/BL6/BL7：端到端渲染 ---
        Class<?> cardDataCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer$CardData");
        Class<?> cardItemCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer$CardItem");
        java.util.List<Object> itemList = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            itemList.add(cardItemCls.getConstructor(String.class, String.class).newInstance("项目" + i, "值" + i));
        }

        config.set("render.blur-strength", 50);
        Object cardChecker = cardDataCls.getConstructors()[0].newInstance("Steve", itemList, null, checkerboard(900, 760));
        byte[] png50 = (byte[]) infoRendererCls.getMethod("render", cardDataCls).invoke(infoRenderer, cardChecker);
        BufferedImage img50 = ImageIO.read(new ByteArrayInputStream(png50));
        double in50 = meanNeighborDiff(img50, 60, 60, 800, 200);
        double out50 = meanNeighborDiff(img50, 0, 0, 900, 22);
        check("BL5 默认 50 档：卡片内高频被抹平（内 << 外）",
                in50 < out50 * 0.35,
                String.format("in=%.1f out=%.1f ratio=%.2f", in50, out50, in50 / Math.max(out50, 1)));

        config.set("render.blur-strength", 0);
        byte[] png0 = (byte[]) infoRendererCls.getMethod("render", cardDataCls).invoke(infoRenderer, cardChecker);
        BufferedImage img0 = ImageIO.read(new ByteArrayInputStream(png0));
        double in0 = meanNeighborDiff(img0, 60, 60, 800, 200);
        double out0 = meanNeighborDiff(img0, 0, 0, 900, 22);
        check("BL6a strength 0：卡片内外均保持锐利",
                in0 > out0 * 0.5,
                String.format("in=%.1f out=%.1f ratio=%.2f", in0, out0, in0 / Math.max(out0, 1)));

        config.set("render.blur-strength", 100);
        byte[] png100 = (byte[]) infoRendererCls.getMethod("render", cardDataCls).invoke(infoRenderer, cardChecker);
        BufferedImage img100 = ImageIO.read(new ByteArrayInputStream(png100));
        double in100 = meanNeighborDiff(img100, 60, 60, 800, 200);
        check("BL6b strength 100 比 50 更平（更模糊）", in100 <= in50 * 1.05,
                String.format("in100=%.1f in50=%.1f", in100, in50));

        config.set("render.blur-strength", 0);
        Object cardWhite = cardDataCls.getConstructors()[0].newInstance("Steve", itemList, null, white(900, 760));
        byte[] pngWhite = (byte[]) infoRendererCls.getMethod("render", cardDataCls).invoke(infoRenderer, cardWhite);
        BufferedImage imgWhite = ImageIO.read(new ByteArrayInputStream(pngWhite));
        int y1 = Math.min(imgWhite.getHeight() - 30, 700);
        double panelLuma = meanLuma(imgWhite, 60, 400, 850, y1);
        check("BL7 统计面板底色调浅（亮度 > 150，旧 alpha142 时 ≈113）",
                panelLuma > 150,
                String.format("luma=%.1f", panelLuma));

        // --- BL8：cover 回归 ---
        config.set("render.blur-strength", 50);
        canvas = new BufferedImage(1350, 600, BufferedImage.TYPE_INT_RGB);
        g = canvas.createGraphics();
        try {
            assetsCls.getMethod("drawBackground", Graphics2D.class, BufferedImage.class, int.class, int.class)
                    .invoke(assets, g, triBand(400, 100), 1350, 600);
        } finally {
            g.dispose();
        }
        int[] left = meanRgb(canvas, 100, 0, 300, 600);
        int[] right = meanRgb(canvas, 1050, 0, 1250, 600);
        check("BL8 cover 回归：三段色图中心裁剪，中段两侧均为绿色",
                left[1] > left[0] + 40 && left[1] > left[2] + 40
                        && right[1] > right[0] + 40 && right[1] > right[2] + 40,
                String.format("left=(%d,%d,%d) right=(%d,%d,%d)", left[0], left[1], left[2], right[0], right[1], right[2]));

        // ================= AV 组：头像 =================
        System.out.println("== AV 组：头像链路 ==");
        Class<?> reqCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardAssets$AvatarRequest");
        boolean twoArg = false;
        boolean fourArg = false;
        for (java.lang.reflect.Constructor<?> c : reqCls.getConstructors()) {
            if (c.getParameterCount() == 2) twoArg = true;
            if (c.getParameterCount() == 4) fourArg = true;
        }
        check("AV1 AvatarRequest 两参构造在 / 四参不在", twoArg && !fourArg);

        Method cropFace = findMethod(assetsCls, "cropFace", 2);
        BufferedImage face64 = (BufferedImage) cropFace.invoke(assets, syntheticSkin(64), 64);
        int hatRgb = face64.getRGB(32, 32);
        check("AV2 64×64 皮肤：帽子层（外层）叠加生效（蓝色盖过红脸）",
                ((hatRgb >> 16) & 0xFF) < 100 && ((hatRgb & 0xFF) > 150),
                String.format("rgb=#%06X", hatRgb & 0xFFFFFF));
        BufferedImage face32 = (BufferedImage) cropFace.invoke(assets, syntheticSkin(32), 64);
        int innerRgb = face32.getRGB(32, 32);
        check("AV3 64×32 皮肤：无有效帽子层 → 仅内层红脸",
                ((innerRgb >> 16) & 0xFF) > 150,
                String.format("rgb=#%06X", innerRgb & 0xFFFFFF));

        // ================= CF 组：配置重建 =================
        System.out.println("== CF 组：配置重建（v26 → v27，旧默认不升级） ==");
        File dataFolder = keron.getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();
        File configFile = new File(dataFolder, "config.yml");
        String v26 = String.join("\n",
                "config-version: 26",
                "serverName: \"CustomServer\"",
                "bot:",
                "  app-id: \"\"",
                "  secret: \"\"",
                "  name: \"MyCustomBot\"",
                "  reconnect-notify: true",
                "  groups: [\"ABC123\"]",
                "render:",
                "  avatar-fetch-priority: local",
                "  background-style: \"gray-mask\"",
                "  global-opacity: 1.0",
                "  idle-release-minutes: 15",
                "  performance:",
                "    cache-seconds: 5",
                "commands:",
                "  帮助: true",
                "messages:",
                "  bind-usage: \"bind-usage.md\"",
                "");
        Files.write(configFile.toPath(), v26.getBytes(StandardCharsets.UTF_8));

        Class<?> rebuilderCls = Class.forName("cn.kerong.penguin.spigot.manager.ConfigFileRebuilder");
        Object rebuilder = rebuilderCls.getField("INSTANCE").get(null);
        Class<?> pairCls = Class.forName("cn.kerong.shaded.kotlin.Pair");
        List<Object> moves = java.util.Collections.emptyList();
        List<String> removed = java.util.Arrays.asList(
                "render.avatar-fetch-priority", "render.background-style", "render.global-opacity");
        boolean rebuilt = (Boolean) rebuilderCls
                .getMethod("rebuildIfNeeded", org.bukkit.plugin.java.JavaPlugin.class, int.class, List.class, List.class)
                .invoke(rebuilder, keron, 27, moves, removed);
        check("CF1 重建执行成功", rebuilt);
        String out = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
        check("CF1 重建后 config-version 27", out.contains("config-version: 27"));
        check("CF2 旧默认值保留不升级（performance.cache-seconds 仍是 5）",
                out.matches("(?s).*[^-]cache-seconds: 5\\s*\\n.*") && !out.matches("(?s).*[^-]cache-seconds: 60\\s*\\n.*"),
                out.lines().filter(l -> l.trim().startsWith("cache-seconds")).map(String::trim).findFirst().orElse("(无)"));
        check("CF3 自定义值保留（bot.name / serverName / groups）",
                out.contains("\"MyCustomBot\"") && out.contains("\"CustomServer\"") && out.contains("groups: [\"ABC123\"]"));
        check("CF4 废弃键清理 + 新键来自模板",
                !out.contains("avatar-fetch-priority") && !out.contains("background-style")
                        && !out.contains("global-opacity") && out.contains("blur-strength: 50"));

        // 收尾
        System.out.printf("%n结果：%d/%d 通过%n", pass, pass + fail);
        if (fail > 0) {
            System.out.println("存在失败项 ❌");
            System.exit(1);
        }
        System.out.println("全部通过 ✅");
    }
}
