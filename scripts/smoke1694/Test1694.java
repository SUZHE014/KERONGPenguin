package scripts.smoke1694;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import javax.imageio.ImageIO;

/**
 * 1.6.9.4 功能冒烟（独立 JVM，headless，NF 分发 JAR 真实字节码）。
 *
 * 本版核心：背景遮罩样式配置（gray-mask 原版 / blur-mask 模糊遮罩）+
 * QQ 合并转发消息防刷屏 + reload 可用性验证（配置实时读取即重载机制）。
 *
 * 覆盖：
 *  BS 组  背景遮罩样式：
 *   - BS1  无插件上下文 backgroundStyle() = GRAY_MASK（安全默认）
 *   - BS2  配置通路：blur-mask / blur 别名 → BLUR_MASK；乱值 / 留空 → GRAY_MASK
 *   - BS3  blurImage 行为：高频棋盘图模糊后相邻像素差大幅下降（>85%）
 *   - BS4  blurImage 小图快速路径（<16px 原样返回同一实例）
 *   - BS5  applyBackgroundStyle(builtin=false, blur)：先模糊再叠暗色遮罩
 *          （亮度低于纯模糊结果，且与 gray-mask 输出不同）
 *   - BS6  端到端 resolveBackground(builtin)：blur 输出平滑度显著高于 gray
 *   - BS7  配置实时切换（= reload 机制）：改 config 值后下一次读取立即变化
 *   - BS8  端到端整卡渲染（blur 背景）→ PNG 魔数可解码
 *  MF 组  合并转发识别：
 *   - MF1  实测样例（QQ 渲染的聊天记录模板串）→ 识别 + 条数 2
 *   - MF2  嵌套模板（--- 第N条 --- / [消息类型] 合并转发消息）→ 识别 + 条数
 *   - MF3  message_type=102 + 空正文 → 识别（条数 null）
 *   - MF4  正常闲聊文本 → 不识别（null）
 *   - MF5  只有单个标记（=== 消息 1 === 无 [发送者]）→ 不识别（防误伤）
 *   - MF6  引用消息（103，回复者正文）→ 不识别（引用链路不受影响）
 *  RL 组  reload 可用性：
 *   - RL1  配置实时读取：改 render.background-style 立即生效（无重启）
 *   - RL2  配置实时读取：render.global-opacity 同样实时
 *   - RL3  blur 与 gray 缓存互不污染（同尺寸两次处理，两次不同键）
 *  J 组   plugin.yml 1.6.9.4
 *
 * 编译运行（仓库根目录，javac/java 用 tooling JDK21）：
 *   javac -cp build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.9.4.jar \
 *     -d scripts/smoke1694-out scripts/smoke1694/*.java
 *   java -Djava.awt.headless=true \
 *     -cp scripts/smoke1694-out:build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.9.4.jar \
 *     scripts.smoke1694.Test1694
 */
public class Test1694 {

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

    static Object call(Object instance, String method, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? null : args[i].getClass();
        }
        for (java.lang.reflect.Method m : instance.getClass().getMethods()) {
            if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                boolean ok = true;
                for (int i = 0; i < args.length; i++) {
                    if (types[i] == null) continue;
                    Class<?> pt = m.getParameterTypes()[i];
                    if (!pt.isAssignableFrom(types[i])
                            && !(pt.isPrimitive() && boxed(pt).isAssignableFrom(types[i]))) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return m.invoke(instance, args);
                }
            }
        }
        throw new NoSuchMethodException(method + " / " + args.length + " 参数");
    }

    static Class<?> boxed(Class<?> primitive) {
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == long.class) return Long.class;
        if (primitive == int.class) return Integer.class;
        if (primitive == double.class) return Double.class;
        if (primitive == float.class) return Float.class;
        return primitive;
    }

    /** 相邻像素平均亮度差（越高越“高频”，模糊后应大幅下降）。 */
    static double meanNeighborDiff(BufferedImage img) {
        long total = 0;
        long count = 0;
        int w = img.getWidth();
        int h = img.getHeight();
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x + 1 < w; x += 1) {
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

    /** 平均亮度（0-765）。 */
    static double meanBrightness(BufferedImage img) {
        long total = 0;
        long count = 0;
        for (int y = 0; y < img.getHeight(); y += 3) {
            for (int x = 0; x < img.getWidth(); x += 3) {
                int c = img.getRGB(x, y);
                total += ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF);
                count++;
            }
        }
        return count == 0 ? 0 : (double) total / count;
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

    public static void main(String[] args) throws Exception {
        Class<?> assetsCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardAssets");
        Object assets = assetsCls.getField("INSTANCE").get(null);
        Class<?> renderSettingsCls = Class.forName("cn.kerong.penguin.spigot.render.RenderSettings");
        Class<?> styleEnum = Class.forName("cn.kerong.penguin.spigot.render.RenderSettings$BackgroundStyle");
        Object grayMask = Enum.valueOf((Class<? extends Enum>) (Class<?>) styleEnum, "GRAY_MASK");
        Object blurMask = Enum.valueOf((Class<? extends Enum>) (Class<?>) styleEnum, "BLUR_MASK");
        Class<?> qqTextCls = Class.forName("cn.kerong.bot.tools.QqText");
        Object qqText = qqTextCls.getField("INSTANCE").get(null);

        System.out.println("== BS 组：背景遮罩样式 ==");

        // --- BS1：无插件上下文（安全默认） ---
        check("BS1 无插件上下文 backgroundStyle()=GRAY_MASK",
                grayMask.equals(call(renderSettingsCls.getField("INSTANCE").get(null), "backgroundStyle")));

        // --- BS2：配置通路（经 BotShared 的真实读取路径） ---
        FakeKeronRuntime keron = new FakeKeronRuntime();
        Object botShared = Class.forName("cn.kerong.bot.provider.BotShared").getField("INSTANCE").get(null);
        Class.forName("cn.kerong.bot.provider.BotShared")
                .getMethod("setInstance", cn.kerong.bot.KERONG.class)
                .invoke(botShared, keron);
        org.bukkit.configuration.file.FileConfiguration config = keron.getConfig();

        Object settings = renderSettingsCls.getField("INSTANCE").get(null);
        config.set("render.background-style", "blur-mask");
        check("BS2a blur-mask → BLUR_MASK", blurMask.equals(call(settings, "backgroundStyle")));
        config.set("render.background-style", "BLUR");
        check("BS2b 别名 blur（不区分大小写）→ BLUR_MASK", blurMask.equals(call(settings, "backgroundStyle")));
        config.set("render.background-style", "随便乱填");
        check("BS2c 乱值 → GRAY_MASK（原版兜底）", grayMask.equals(call(settings, "backgroundStyle")));
        config.set("render.background-style", "");
        check("BS2d 留空 → GRAY_MASK", grayMask.equals(call(settings, "backgroundStyle")));

        // --- BS3/BS4：blurImage 行为（反射私有方法） ---
        Method blurImage = assetsCls.getDeclaredMethod("blurImage", BufferedImage.class);
        blurImage.setAccessible(true);
        BufferedImage checker = checkerboard(320, 240);
        BufferedImage blurred = (BufferedImage) blurImage.invoke(assets, checker);
        double diffBefore = meanNeighborDiff(checker);
        double diffAfter = meanNeighborDiff(blurred);
        check("BS3 棋盘图模糊后相邻像素差下降 >85%",
                diffAfter < diffBefore * 0.15,
                String.format("before=%.1f after=%.1f", diffBefore, diffAfter));

        BufferedImage tiny = checkerboard(8, 8);
        BufferedImage tinyOut = (BufferedImage) blurImage.invoke(assets, tiny);
        check("BS4 小图快速路径（<16px 原样返回同实例）", tinyOut == tiny);

        // --- BS5：applyBackgroundStyle（builtin=false → 模糊 + 暗色遮罩） ---
        Method applyStyle = assetsCls.getDeclaredMethod("applyBackgroundStyle", BufferedImage.class, boolean.class);
        applyStyle.setAccessible(true);
        config.set("render.background-style", "blur-mask");
        BufferedImage styleBlur = (BufferedImage) applyStyle.invoke(assets, checkerboard(320, 240), false);
        BufferedImage styleGray = (BufferedImage) applyStyle.invoke(assets, checkerboard(320, 240), false);
        config.set("render.background-style", "gray-mask");
        BufferedImage styleGrayReal = (BufferedImage) applyStyle.invoke(assets, checkerboard(320, 240), false);
        BufferedImage pureBlurred = (BufferedImage) blurImage.invoke(assets, checkerboard(320, 240));
        check("BS5a blur 样式输出与 gray 样式不同（模糊生效）",
                meanNeighborDiff(styleBlur) < meanNeighborDiff(styleGrayReal) * 0.2,
                String.format("blur=%.1f gray=%.1f", meanNeighborDiff(styleBlur), meanNeighborDiff(styleGrayReal)));
        check("BS5b blur 样式叠了暗色遮罩（亮度低于纯模糊）",
                meanBrightness(styleBlur) < meanBrightness(pureBlurred),
                String.format("styled=%.0f pure=%.0f", meanBrightness(styleBlur), meanBrightness(pureBlurred)));
        check("BS5c 同样式两次输出一致（确定性）",
                meanNeighborDiff(styleBlur) == meanNeighborDiff(styleGray));

        // --- BS6：端到端 resolveBackground（内置背景图） ---
        String builtinCard = (String) assetsCls.getField("BUILTIN_BACKGROUND_CARD").get(null);
        config.set("render.background-style", "gray-mask");
        Object grayBg = call(assets, "resolveBackground", (Object) null, 400, 300, true, builtinCard);
        config.set("render.background-style", "blur-mask");
        Object blurBg = call(assets, "resolveBackground", (Object) null, 400, 300, true, builtinCard);
        check("BS6a 内置背景 blur 输出平滑度显著高于 gray",
                grayBg != null && blurBg != null
                        && meanNeighborDiff((BufferedImage) blurBg) < meanNeighborDiff((BufferedImage) grayBg) * 0.3,
                String.format("blur=%.1f gray=%.1f", meanNeighborDiff((BufferedImage) blurBg), meanNeighborDiff((BufferedImage) grayBg)));
        check("BS6b 输出尺寸正确 400x300",
                blurBg != null && ((BufferedImage) blurBg).getWidth() == 400 && ((BufferedImage) blurBg).getHeight() == 300);

        // --- BS7/RL1：配置实时切换（= reload 机制：改值 → 下次读取立即生效） ---
        config.set("render.background-style", "gray-mask");
        check("BS7 配置实时切换回 gray（无需重启）",
                grayMask.equals(call(settings, "backgroundStyle")));
        config.set("render.background-style", "blur-mask");
        check("BS7b 再切回 blur（实时）",
                blurMask.equals(call(settings, "backgroundStyle")));

        // --- RL2：global-opacity 实时读取（另一配置项同机制） ---
        config.set("render.global-opacity", 0.5);
        check("RL2 render.global-opacity 实时读取 0.5",
                0.5f == (Float) call(settings, "globalOpacity"));
        config.set("render.global-opacity", 1.0);

        // --- RL3：blur 与 gray 缓存互不污染（各自配置下重新处理） ---
        config.set("render.background-style", "gray-mask");
        Object grayAgain = call(assets, "resolveBackground", (Object) null, 400, 300, true, builtinCard);
        config.set("render.background-style", "blur-mask");
        Object blurAgain = call(assets, "resolveBackground", (Object) null, 400, 300, true, builtinCard);
        check("RL3 样式切换后缓存互不污染（两种输出仍然可区分）",
                meanNeighborDiff((BufferedImage) blurAgain) < meanNeighborDiff((BufferedImage) grayAgain) * 0.3,
                String.format("blur=%.1f gray=%.1f", meanNeighborDiff((BufferedImage) blurAgain), meanNeighborDiff((BufferedImage) grayAgain)));

        // --- BS8：端到端整卡渲染（blur 背景）→ PNG ---
        Class<?> rendererCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer");
        Object renderer = rendererCls.getField("INSTANCE").get(null);
        Class<?> cardItemCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer$CardItem");
        Class<?> cardDataCls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer$CardData");
        Object item1 = cardItemCls.getConstructor(String.class, String.class).newInstance("总在线时长", "77时20分");
        Object item2 = cardItemCls.getConstructor(String.class, String.class).newInstance("金币", "1600");
        // internal 方法名带模块后缀（cardHeight$common_Bot / $server_NeoForge），动态查找
        Method cardHeightMethod = null;
        for (Method m : rendererCls.getMethods()) {
            if (m.getName().startsWith("cardHeight") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == int.class) {
                cardHeightMethod = m;
                break;
            }
        }
        int cardHeight = (int) cardHeightMethod.invoke(renderer, 2);
        Object bg = call(assets, "resolveBackground", (Object) null,
                (int) rendererCls.getField("WIDTH").get(null), cardHeight, true, builtinCard);
        Object cardData = cardDataCls.getConstructor(String.class, java.util.List.class,
                        BufferedImage.class, BufferedImage.class)
                .newInstance("SmokePlayer", java.util.List.of(item1, item2), null, bg);
        byte[] png = (byte[]) call(renderer, "render", cardData);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        check("BS8 整卡渲染（blur 背景）PNG 可解码且尺寸正确",
                png != null && png.length > 4 && decoded != null
                        && decoded.getWidth() == (int) rendererCls.getField("WIDTH").get(null)
                        && decoded.getHeight() == cardHeight,
                String.format("%d 字节 %dx%d", png.length, decoded.getWidth(), decoded.getHeight()));

        System.out.println("== MF 组：合并转发识别 ==");

        // 实测样例（QQ 渲染的聊天记录模板串，形态取自真实事件）
        String mergedSample = "[群聊的聊天记录]\n"
                + "=== 消息 1 ===\n[消息内容] 今天的学习计划已完成\n[发送者] A\n\n"
                + "=== 消息 2 ===\n[消息内容] 很棒！继续保持，明天继续加油\n[发送者] B";
        Object detected = call(qqText, "detectMergedForward", (Object) null, mergedSample);
        check("MF1 实测模板串 → 识别 + 条数 2",
                detected != null && Integer.valueOf(2).equals(field(detected, "count")));

        // 嵌套模板（--- 第N条 --- + [消息类型] 合并转发消息）
        String nestedSample = "[A和B的聊天记录]\n[发送者] A\n[消息类型] 合并转发消息\n[关联消息]\n"
                + "--- 第1条 ---\n[发送者] A\n--- 第2条 ---\n[发送者] B\n--- 第3条 ---\n[发送者] A";
        Object nested = call(qqText, "detectMergedForward", (Object) null, nestedSample);
        check("MF2 嵌套模板 → 识别 + 条数 3",
                nested != null && Integer.valueOf(3).equals(field(nested, "count")));

        // message_type=102 + 空正文（fastjson 在 NF JAR 内已重定位到 cn.kerong.shaded）
        Object meta102 = relocatedJson("102");
        Object type102 = call(qqText, "detectMergedForward", meta102, " ");
        check("MF3 message_type=102 + 空正文 → 识别（条数 null）",
                type102 != null && field(type102, "count") == null);

        // 正常闲聊
        check("MF4 正常闲聊 → 不识别",
                call(qqText, "detectMergedForward", (Object) null, (Object) "大家好呀，今天开服吗？") == null);

        // 单标记（防误伤：只有 === 消息 1 === 没有 [发送者]）
        check("MF5 单标记文本 → 不识别（防误伤）",
                call(qqText, "detectMergedForward", (Object) null,
                        (Object) "我们组 === 消息 1 === 就差一个人了") == null);

        // 引用消息（103，回复者正文无模板标记）
        Object meta103 = relocatedJson("103");
        check("MF6 引用消息（103）回复正文 → 不识别（引用链路不受影响）",
                call(qqText, "detectMergedForward", meta103, (Object) "我也觉得") == null);

        System.out.println("== J 组：分发 JAR 版本 ==");

        try (var zip = new java.util.zip.ZipFile("build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.9.4.jar")) {
            // NF JAR 无 plugin.yml（Spigot 资源已剔除），版本在 META-INF 双 toml
            var entry = zip.getEntry("META-INF/neoforge.mods.toml");
            String toml = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            check("J1 NF JAR neoforge.mods.toml 1.6.9.4 + 段头 [[mods]]",
                    toml.contains("1.6.9.4") && toml.contains("[[mods]]"));
        }
        try (var zip = new java.util.zip.ZipFile("build/dist/KERONGPenguin_Spigot-1.6.9.4.jar")) {
            var entry = zip.getEntry("plugin.yml");
            String yml = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            check("J2 Spigot JAR plugin.yml 1.6.9.4", yml.contains("1.6.9.4"));
            var cfgEntry = zip.getEntry("config.yml");
            String cfg = new String(zip.getInputStream(cfgEntry).readAllBytes(), StandardCharsets.UTF_8);
            check("J3 Spigot JAR config.yml：background-style + reload 边界注释",
                    cfg.contains("background-style: \"gray-mask\"") && cfg.contains("只有以下配置不能用 reload"));
        }

        System.out.printf("%n共 %d 项，通过 %d，失败 %d%n", pass + fail, pass, fail);
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("smoke1694 全部通过");
    }

    static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /** 构造重定位后的 fastjson JSONObject（NF JAR 内 com.alibaba 已迁至 cn.kerong.shaded）。 */
    static Object relocatedJson(String messageType) throws Exception {
        Class<?> cls = Class.forName("cn.kerong.shaded.com.alibaba.fastjson.JSONObject");
        Object instance = cls.getConstructor().newInstance();
        cls.getMethod("put", String.class, Object.class).invoke(instance, "message_type", Integer.valueOf(messageType));
        return instance;
    }
}
