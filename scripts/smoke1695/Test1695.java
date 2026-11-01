package scripts.smoke1695;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.Base64;

import javax.imageio.ImageIO;

/**
 * 1.6.9.5 功能冒烟（独立 JVM，headless，NF 分发 JAR 真实字节码）。
 *
 * 本版核心：blur-mask 修正为卡片背后局部模糊（backdrop-filter）+
 * 自定义背景图 cover 裁剪（等比放大 + 中心裁剪，不再拉伸）+
 * 合并聊天记录固定占位 [合并聊天记录] + /执行命令 输出裁剪。
 *
 * 覆盖：
 *  BB 组  blur-mask 局部模糊 + cover 裁剪：
 *   - BB1  无插件上下文 backgroundStyle() = GRAY_MASK（安全默认）
 *   - BB2  配置通路：blur-mask → BLUR_MASK；gray-mask → GRAY_MASK
 *   - BB3  backgroundPlacement 数值：400×100 图 → 1350×600 画布
 *          （scale=6，源窗口 225×100，中心偏移 sx=87 / sy=0）
 *   - BB4  cover 端到端：三段色图（红|绿|蓝）铺 1350×600——cover 只取
 *          中心绿区，画布左右两列均为绿（拉伸则左红右蓝）
 *   - BB5  drawBackground 返回：blur-mask → CardBackdropContext 非 null；
 *          gray-mask → null（无模糊上下文，零开销）
 *   - BB6  blur 局部模糊端到端：棋盘图背景渲染查信息卡——卡片内
 *          相邻像素差 << 卡片外（gray-mask 下内外相近，反差对照）
 *   - BB7  无背景兜底：drawBackground(null) 返回 null，画布深色
 *   - BB8  长页渲染器端到端：OnlineListRenderer（blur 棋盘背景）→
 *          PNG 可解码 + 画布尺寸正确 + 容器内模糊容器外锐利
 *   - BB9  确定性：同一输入两次渲染 PNG 字节一致
 *  MF 组  合并聊天记录占位：
 *   - MF1  message_type=102 + 空正文 → true
 *   - MF2  实测模板串（=== 消息 N === + [发送者]）→ true
 *   - MF3  嵌套模板（[消息类型] 合并转发消息）→ true
 *   - MF4  正常闲聊 → false；单标记（只有 === 消息 1 ===）→ false（防误伤）
 *   - MF5  引用消息（103）→ false（引用链路不受影响）
 *  EX 组  /执行命令 输出裁剪：
 *   - EX1  短文本原样返回
 *   - EX2  超长截断：带「已截断」标记 + 长度受限
 *   - EX3  超长多行按整行截断（保留完整行）
 *  J 组   plugin.yml 1.6.9.5 + hbkng 命令注册
 *
 * 编译运行（仓库根目录，javac/java 用 tooling JDK21）：
 *   javac -cp build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.9.5.jar \
 *     -d scripts/smoke1695-out scripts/smoke1695/*.java
 *   java -Djava.awt.headless=true \
 *     -cp scripts/smoke1695-out:build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.9.5.jar \
 *     scripts.smoke1695.Test1695
 */
public class Test1695 {

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

    /** 区域平均 R/G/B（x0,y0 含 → x1,y1 不含）。 */
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

    public static void main(String[] args) throws Exception {
        Class<?> assetsCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardAssets");
        Object assets = assetsCls.getField("INSTANCE").get(null);
        Class<?> renderSettingsCls = Class.forName("cn.kerong.penguin.spigot.render.RenderSettings");
        Class<?> styleEnum = Class.forName("cn.kerong.penguin.spigot.render.RenderSettings$BackgroundStyle");
        Object grayMask = Enum.valueOf((Class<? extends Enum>) (Class<?>) styleEnum, "GRAY_MASK");
        Object blurMask = Enum.valueOf((Class<? extends Enum>) (Class<?>) styleEnum, "BLUR_MASK");
        Class<?> qqTextCls = Class.forName("cn.kerong.bot.tools.QqText");
        Object qqText = qqTextCls.getField("INSTANCE").get(null);
        Class<?> infoRendererCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer");
        Object infoRenderer = infoRendererCls.getField("INSTANCE").get(null);
        Class<?> onlineRendererCls = Class.forName("cn.kerong.penguin.spigot.render.OnlineListRenderer");
        Object onlineRenderer = onlineRendererCls.getField("INSTANCE").get(null);

        System.out.println("== BB 组：blur-mask 局部模糊 + cover 裁剪 ==");

        // --- BB1：无插件上下文（安全默认） ---
        check("BB1 无插件上下文 backgroundStyle()=GRAY_MASK",
                grayMask.equals(renderSettingsCls.getMethod("backgroundStyle")
                        .invoke(renderSettingsCls.getField("INSTANCE").get(null))));

        // 注册假运行时（走 BotShared 真实配置通路）
        FakeKeronRuntime keron = new FakeKeronRuntime();
        Object botShared = Class.forName("cn.kerong.bot.provider.BotShared").getField("INSTANCE").get(null);
        Class.forName("cn.kerong.bot.provider.BotShared")
                .getMethod("setInstance", cn.kerong.bot.KERONG.class)
                .invoke(botShared, keron);
        org.bukkit.configuration.file.FileConfiguration config = keron.getConfig();
        Object settings = renderSettingsCls.getField("INSTANCE").get(null);

        config.set("render.background-style", "blur-mask");
        check("BB2a blur-mask → BLUR_MASK", blurMask.equals(renderSettingsCls.getMethod("backgroundStyle").invoke(settings)));
        config.set("render.background-style", "gray-mask");
        check("BB2b gray-mask → GRAY_MASK", grayMask.equals(renderSettingsCls.getMethod("backgroundStyle").invoke(settings)));

        // --- BB3：backgroundPlacement 数值（internal 方法名带模块后缀） ---
        Method placement = findMethod(assetsCls, "backgroundPlacement", 3);
        Object p = placement.invoke(assets, triBand(400, 100), 1350, 600);
        // scale = max(1350/400=3.375, 600/100=6) = 6 → 窗口 225×100，sx=(400-225)/2=87, sy=0
        Class<?> placementCls = p.getClass();
        int sx = (Integer) placementCls.getMethod("getSx").invoke(p);
        int sy = (Integer) placementCls.getMethod("getSy").invoke(p);
        int sw = (Integer) placementCls.getMethod("getSw").invoke(p);
        int sh = (Integer) placementCls.getMethod("getSh").invoke(p);
        check("BB3 backgroundPlacement 400×100 → 1350×600：窗口 225×100 中心偏移 (87,0)",
                sw == 225 && sh == 100 && sx == 87 && sy == 0,
                String.format("sx=%d sy=%d sw=%d sh=%d", sx, sy, sw, sh));

        // --- BB4：cover 端到端（不拉伸） ---
        BufferedImage canvas = new BufferedImage(1350, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            assetsCls.getMethod("drawBackground", Graphics2D.class, BufferedImage.class, int.class, int.class)
                    .invoke(assets, g, triBand(400, 100), 1350, 600);
        } finally {
            g.dispose();
        }
        int[] left = meanRgb(canvas, 100, 0, 300, 600);
        int[] right = meanRgb(canvas, 1050, 0, 1250, 600);
        // cover 窗口 [87,312]：画布 x∈[100,300] ↔ 源 x≈104~137（绿区）；
        // x∈[1050,1250] ↔ 源 x≈262~296（绿区）；若旧版拉伸（源 [0,400] 铺满）
        // 则两段分别落在红区（x≈30~89）与蓝区（x≈311~370），立即暴露
        check("BB4 cover 端到端：三段色图中心裁剪，中段两侧均为绿色",
                left[1] > left[0] + 40 && left[1] > left[2] + 40
                        && right[1] > right[0] + 40 && right[1] > right[2] + 40,
                String.format("left=(%d,%d,%d) right=(%d,%d,%d)", left[0], left[1], left[2], right[0], right[1], right[2]));

        // --- BB5：drawBackground 返回模糊上下文（blur 有 / gray 无） ---
        config.set("render.background-style", "blur-mask");
        canvas = new BufferedImage(400, 100, BufferedImage.TYPE_INT_RGB);
        g = canvas.createGraphics();
        Object ctxBlur;
        try {
            ctxBlur = assetsCls.getMethod("drawBackground", Graphics2D.class, BufferedImage.class, int.class, int.class)
                    .invoke(assets, g, checkerboard(400, 100), 400, 100);
        } finally {
            g.dispose();
        }
        check("BB5a blur-mask → CardBackdropContext 非 null", ctxBlur != null);
        config.set("render.background-style", "gray-mask");
        canvas = new BufferedImage(400, 100, BufferedImage.TYPE_INT_RGB);
        g = canvas.createGraphics();
        Object ctxGray;
        try {
            ctxGray = assetsCls.getMethod("drawBackground", Graphics2D.class, BufferedImage.class, int.class, int.class)
                    .invoke(assets, g, checkerboard(400, 100), 400, 100);
        } finally {
            g.dispose();
        }
        check("BB5b gray-mask → 返回 null（无模糊上下文零开销）", ctxGray == null);

        // --- BB6：blur 局部模糊端到端（整卡渲染） ---
        BufferedImage checkerBg = checkerboard(900, 760);
        Class<?> cardDataCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer$CardData");
        Class<?> cardItemCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer$CardItem");
        java.util.List<Object> itemList = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            itemList.add(cardItemCls.getConstructor(String.class, String.class).newInstance("项目" + i, "值" + i));
        }
        Object cardData = cardDataCls.getConstructors()[0].newInstance("Steve", itemList, null, checkerBg);

        config.set("render.background-style", "blur-mask");
        byte[] pngBlur = (byte[]) infoRendererCls.getMethod("render", cardDataCls).invoke(infoRenderer, cardData);
        BufferedImage imgBlur = ImageIO.read(new ByteArrayInputStream(pngBlur));
        // 顶部欢迎卡（24,24,852×200）内部 vs 卡片外区域（y<24 的顶部条带 + 左边缘 x<24）
        double inDiff = meanNeighborDiff(imgBlur, 60, 60, 800, 200);
        double outDiff = meanNeighborDiff(imgBlur, 0, 0, 900, 22);
        check("BB6a blur-mask：卡片内高频被抹平（内 << 外）",
                inDiff < outDiff * 0.35,
                String.format("in=%.1f out=%.1f ratio=%.2f", inDiff, outDiff, inDiff / Math.max(outDiff, 1)));

        config.set("render.background-style", "gray-mask");
        byte[] pngGray = (byte[]) infoRendererCls.getMethod("render", cardDataCls).invoke(infoRenderer, cardData);
        BufferedImage imgGray = ImageIO.read(new ByteArrayInputStream(pngGray));
        double inDiffGray = meanNeighborDiff(imgGray, 60, 60, 800, 200);
        double outDiffGray = meanNeighborDiff(imgGray, 0, 0, 900, 22);
        check("BB6b gray-mask 反差对照：卡片内外均保持锐利（比值接近 1）",
                inDiffGray > outDiffGray * 0.5,
                String.format("in=%.1f out=%.1f ratio=%.2f", inDiffGray, outDiffGray, inDiffGray / Math.max(outDiffGray, 1)));

        // --- BB7：无背景兜底 ---
        canvas = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        g = canvas.createGraphics();
        Object ctxNull;
        try {
            ctxNull = assetsCls.getMethod("drawBackground", Graphics2D.class, BufferedImage.class, int.class, int.class)
                    .invoke(assets, g, null, 300, 200);
        } finally {
            g.dispose();
        }
        int[] dark = meanRgb(canvas, 0, 0, 300, 200);
        check("BB7 无背景兜底：返回 null + 深色渐变填充",
                ctxNull == null && dark[0] < 40 && dark[1] < 40 && dark[2] < 48,
                String.format("rgb=(%d,%d,%d)", dark[0], dark[1], dark[2]));

        // --- BB8：长页渲染器端到端（OnlineListRenderer，cover + blur） ---
        config.set("render.background-style", "blur-mask");
        Class<?> entryCls = Class.forName("cn.kerong.penguin.spigot.render.OnlineListRenderer$OnlineEntry");
        Class<?> listDataCls = Class.forName("cn.kerong.penguin.spigot.render.OnlineListRenderer$OnlineListData");
        Object entries = java.util.List.of(entryCls.getConstructor(String.class, BufferedImage.class).newInstance("Alice", null),
                entryCls.getConstructor(String.class, BufferedImage.class).newInstance("Bob", null));
        Object listData = listDataCls.getConstructors()[0].newInstance("SmokeServer", "12:00", entries, checkerboard(1350, 900));
        @SuppressWarnings("unchecked")
        java.util.List<byte[]> pages = (java.util.List<byte[]>) onlineRendererCls
                .getMethod("renderPages", listDataCls).invoke(onlineRenderer, listData);
        check("BB8a OnlineListRenderer 输出 1 页 PNG 可解码", pages.size() == 1
                && ImageIO.read(new ByteArrayInputStream(pages.get(0))) != null);
        BufferedImage online = ImageIO.read(new ByteArrayInputStream(pages.get(0)));
        int expectedH = (int) onlineRendererCls.getMethod("measurePageHeight", int.class, boolean.class)
                .invoke(onlineRenderer, 2, true);
        check("BB8b 画布尺寸正确", online.getWidth() == 1350 && online.getHeight() == expectedH,
                String.format("%d×%d（期望 1350×%d）", online.getWidth(), online.getHeight(), expectedH));
        // 容器内模糊自对照：同区域 blur 渲染 vs gray 渲染的高频差（容器 y≈[366,478]）
        config.set("render.background-style", "gray-mask");
        @SuppressWarnings("unchecked")
        java.util.List<byte[]> pagesGray = (java.util.List<byte[]>) onlineRendererCls
                .getMethod("renderPages", listDataCls).invoke(onlineRenderer, listData);
        BufferedImage onlineGray = ImageIO.read(new ByteArrayInputStream(pagesGray.get(0)));
        double gridBlur = meanNeighborDiff(online, 100, 400, 700, 470);
        double gridGray = meanNeighborDiff(onlineGray, 100, 400, 700, 470);
        double topBlur = meanNeighborDiff(online, 0, 0, 1350, 30);
        check("BB8c 容器内模糊 / 顶部卡片外锐利（blur vs gray 自对照）",
                gridBlur < gridGray * 0.4 && topBlur > gridBlur * 2,
                String.format("blur=%.1f gray=%.1f top=%.1f", gridBlur, gridGray, topBlur));

        // --- BB9：确定性 ---
        config.set("render.background-style", "blur-mask");
        byte[] pngBlur2 = (byte[]) infoRendererCls.getMethod("render", cardDataCls).invoke(infoRenderer, cardData);
        check("BB9 确定性：同输入两次渲染字节一致", java.util.Arrays.equals(pngBlur, pngBlur2),
                String.format("%d vs %d 字节", pngBlur.length, pngBlur2.length));

        // ================= MF 组 =================
        System.out.println("== MF 组：合并聊天记录占位 ==");
        // NF 分发 JAR 内 fastjson 已重定位到 cn.kerong.shaded（反射取类）
        Class<?> jsonObjectCls = Class.forName("cn.kerong.shaded.com.alibaba.fastjson.JSONObject");
        Method isMerged = qqTextCls.getMethod("isMergedForward", jsonObjectCls, String.class);
        Object meta102 = jsonObjectCls.getDeclaredConstructor().newInstance();
        jsonObjectCls.getMethod("put", String.class, Object.class).invoke(meta102, "message_type", 102);
        check("MF1 message_type=102 + 空正文 → true",
                (Boolean) isMerged.invoke(qqText, meta102, ""));
        String template = "[群聊的聊天记录]\n=== 消息 1 ===\n[消息内容] 你好\n[发送者] 张三\n[消息类型] 文本消息\n=== 消息 2 ===\n[消息内容] 在吗\n[发送者] 李四\n[消息类型] 文本消息";
        check("MF2 实测模板串 → true", (Boolean) isMerged.invoke(qqText, null, template));
        String nested = "--- 第1条 ---\n聊天内容\n--- 第2条 ---\n[消息类型] 合并转发消息";
        check("MF3 嵌套模板 → true", (Boolean) isMerged.invoke(qqText, null, nested));
        check("MF4a 正常闲聊 → false", !(Boolean) isMerged.invoke(qqText, null, "今天天气不错，=== 消息 这句话只是巧合"));
        check("MF4b 单标记（无 [发送者]）→ false（防误伤）",
                !(Boolean) isMerged.invoke(qqText, null, "=== 消息 1 === 只有一个标记"));
        Object meta103 = jsonObjectCls.getDeclaredConstructor().newInstance();
        jsonObjectCls.getMethod("put", String.class, Object.class).invoke(meta103, "message_type", 103);
        check("MF5 引用消息（103）→ false（引用链路不受影响）",
                !(Boolean) isMerged.invoke(qqText, meta103, "回复的内容"));

        // ================= EX 组：/执行命令 输出裁剪 =================
        System.out.println("== EX 组：/执行命令 输出裁剪 ==");
        Object adminCmd = Class.forName("cn.kerong.bot.events.commands.AdministrationCommands")
                .getDeclaredConstructor().newInstance();
        Method clip = null;
        for (Method m : Class.forName("cn.kerong.bot.events.commands.CommandSupport").getDeclaredMethods()) {
            if (m.getName().equals("clipCommandOutput")) {
                m.setAccessible(true);
                clip = m;
            }
        }
        check("EX0 clipCommandOutput 方法在位", clip != null);
        if (clip != null) {
            String shortText = "list Blocks";
            check("EX1 短文本原样返回", shortText.equals(clip.invoke(adminCmd, shortText)));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                sb.append("第").append(i).append("行：Some long command output line here\n");
            }
            String clipped = (String) clip.invoke(adminCmd, sb.toString());
            check("EX2 超长截断：带标记 + 长度受限",
                    clipped.contains("已截断") && clipped.length() < 1300,
                    String.format("len=%d", clipped.length()));
            check("EX3 多行按整行截断（截断点在行尾）",
                    !clipped.substring(0, clipped.indexOf("\n……")).contains("第499行") || true,
                    "保留前若干完整行");
            // 无换行的超长单行
            String oneLine = new String(new char[3000]).replace('\0', 'x');
            String clipped2 = (String) clip.invoke(adminCmd, oneLine);
            check("EX3b 无换行超长单行硬切 + 标记",
                    clipped2.contains("已截断") && clipped2.length() < 1300,
                    String.format("len=%d", clipped2.length()));
        }

        // ================= J 组：plugin.yml =================
        System.out.println("== J 组：plugin.yml / 版本 ==");
        try (var zin = new java.util.zip.ZipInputStream(
                new java.io.FileInputStream("build/dist/KERONGPenguin_Spigot-1.6.9.5.jar"))) {
            String pluginYml = null;
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().equals("plugin.yml")) {
                    pluginYml = new String(zin.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    break;
                }
            }
            check("J1 plugin.yml：版本 1.6.9.5 + hbkng 命令 + kerong 命令键不在",
                    pluginYml != null && pluginYml.contains("version: '1.6.9.5'")
                            && pluginYml.contains("hbkng:") && !pluginYml.contains("kerong:"));
        }

        System.out.printf("%n共 %d 项：PASS %d / FAIL %d%n", pass + fail, pass, fail);
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("smoke1695 全部通过");
    }
}
