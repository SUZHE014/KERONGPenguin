import cn.kerong.bot.GroupAtTracker;
import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.bot.RecentMessageGate;
import cn.kerong.bot.tools.PluginFileLog;
import cn.kerong.bot.tools.QqText;
import cn.kerong.penguin.spigot.manager.YamlTextMerge;
import cn.kerong.penguin.spigot.render.InfoCardAssets;
import cn.kerong.penguin.spigot.render.PerformanceRenderer;
import cn.kerong.penguin.spigot.stats.SparkBridge;
import cn.kerong.penguin.spigot.stats.SparkStartupCheck;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1.6.7.5 冒烟：三项用户反馈修复的端到端验证（零网络请求）。
 *
 * 覆盖：
 *  1. SparkBridge 反射链修复（核心）：**包私有统计实现** + 独立类加载器
 *     （真实 spark 场景——1.6.7.2~1.6.7.4 对实现类取 Method 反射调用抛
 *     IllegalAccessException 全指标被吞；1.6.7.5 改走公共接口解析）
 *  2. SparkStartupCheck 三态 + schedule 路径（1.6.7.4 行为保持，失败路径
 *     现含 15 秒静默复测——守护线程后台完成，不抛即通过）
 *  3. AI 聊天艾特判定（1.6.7.5）：GroupAtTracker 记录/查询、
 *     RecentMessageGate msg_id 去重、QqText 行首占位剥离 / 全文剔除
 *  4. 查性能卡片实色面板（1.6.7.5）：渲染后逐像素断言 = 实色常量
 *     （任何 alpha 混合都会偏离常量值）
 *  5. YamlTextMerge 行内格式回归（1.6.7.3 保持）+ 512KB 日志轮转（1.6.7.4 保持）
 *  6. 分发 JAR plugin.yml 版本 1.6.7.5 + PerformanceSnapshot capture 回归
 *
 * 编译运行（仓库根目录）：
 *   javac -d scripts/smoke1675/fakespark1675 \
 *     $(find scripts/smoke1675/fakespark1675 -name "*.java")
 *   javac -cp build/dist/KERONGPenguin_Spigot-1.6.7.5.jar:\
 *     build/dist/KERONGPenguin_NeoForge-1.21.1-1.6.7.5.jar \
 *     -d scripts/smoke1675/out scripts/smoke1675/Test1675.java
 *   java -Djava.awt.headless=true -cp scripts/smoke1675/out:\
 *     build/dist/KERONGPenguin_Spigot-1.6.7.5.jar:\
 *     build/dist/KERONGPenguin_NeoForge-1.21.1-1.6.7.5.jar:\
 *     scripts/deps-work/snakeyaml-2.2.jar Test1675
 *   （⚠️ fakespark1675 不能出现在应用类路径——否则父委派会让「全新
 *   未装配 loader」用例命中已装配实例，1.3 用例失真；它只经
 *   URLClassLoader 隔离装载）
 */
public class Test1675 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + (ok || detail == null || detail.isEmpty() ? "" : "  [" + detail + "]"));
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        // ========== 1. SparkBridge 反射链修复（1.6.7.5 核心） ==========
        // 独立 URLClassLoader 模拟 Bukkit 插件类加载器隔离；
        // fakespark1675 的统计实现全部包私有（真实 spark 同款可见性）。
        java.io.File fakeDir = new java.io.File("scripts/smoke1675/fakespark1675");
        URLClassLoader sparkLoader = new URLClassLoader(
                new URL[]{fakeDir.toURI().toURL()}, Test1675.class.getClassLoader());

        // 1.1 未装配（SparkProvider 未 set）→ get() 抛 ISE → read() 返回 null
        Object cold = SparkBridge.INSTANCE.read(sparkLoader);
        check("1.1 spark 未装配 → read 返回 null（按无 spark 回退）", cold == null, String.valueOf(cold));

        // 1.2 装配包私有实现的假 spark → 全指标经公共接口读到
        Class<?> fake = Class.forName("FakeSpark1675", true, sparkLoader);
        fake.getMethod("install").invoke(null);
        SparkBridge.SparkMetrics metrics = SparkBridge.INSTANCE.read(sparkLoader);
        boolean fullOk = metrics != null
                && Math.abs(metrics.getTps() - 19.87) < 1e-9
                && Math.abs(metrics.getMspt() - 12.4) < 1e-9
                && Math.abs(metrics.getCpu() - 0.234) < 1e-9;
        check("1.2 包私有实现 + 独立类加载器 → TPS 19.87 / MSPT 12.4 / CPU 0.234",
                fullOk, String.valueOf(metrics));

        // 1.3 全新未装配 loader → read 返回 null（每次 read 独立判定）
        URLClassLoader fresh = new URLClassLoader(
                new URL[]{fakeDir.toURI().toURL()}, Test1675.class.getClassLoader());
        Object freshRead = SparkBridge.INSTANCE.read(fresh);
        check("1.3 全新未装配 loader → read 返回 null", freshRead == null, String.valueOf(freshRead));

        // ========== 2. SparkStartupCheck 三态与调度（1.6.7.4 保持 + 1.6.7.5 复测） ==========
        SparkStartupCheck.ProbeOutcome notInstalled = SparkStartupCheck.ProbeOutcome.NotInstalled.INSTANCE;
        SparkStartupCheck.ProbeOutcome.Installed installed =
                new SparkStartupCheck.ProbeOutcome.Installed(new SparkBridge.SparkMetrics(19.7, 25.0, 0.37));
        SparkStartupCheck.ProbeOutcome.Failed probeFailed =
                new SparkStartupCheck.ProbeOutcome.Failed("各指标均暂不可读");
        check("2.1 三态类型语义（NotInstalled 单例 / Installed 带指标 / Failed 带原因）",
                notInstalled != null && installed != null && probeFailed != null);
        check("2.2 Installed 携带指标", Math.abs(installed.getMetrics().getTps() - 19.7) < 1e-9);
        check("2.3 Failed 携带原因", "各指标均暂不可读".equals(probeFailed.getReason()));
        // schedule 路径均不抛（Failed 路径现含 15 秒静默复测，守护线程后台完成）
        boolean schedOk = true;
        try {
            SparkStartupCheck.INSTANCE.schedule(1L, () -> notInstalled);
            SparkStartupCheck.INSTANCE.schedule(1L, () -> installed);
            SparkStartupCheck.INSTANCE.schedule(1L, () -> probeFailed);
            SparkStartupCheck.INSTANCE.schedule(1L, () -> {
                throw new IllegalStateException("probe boom");
            });
            Thread.sleep(400L);
        } catch (Throwable t) {
            schedOk = false;
        }
        check("2.4 schedule 四路径均不抛（失败路径含 15s 静默复测）", schedOk);
        check("2.5 neoforgeDelay = 5 秒节奏", SparkStartupCheck.INSTANCE.neoforgeDelay() == 5_000L);

        // ========== 3. AI 聊天艾特判定（1.6.7.5 核心） ==========
        check("3.1 未记录 → isAtBot=false", !GroupAtTracker.INSTANCE.isAtBot("smoke-m1"));
        GroupAtTracker.INSTANCE.recordAtBot("smoke-m1");
        check("3.2 记录后 → isAtBot=true", GroupAtTracker.INSTANCE.isAtBot("smoke-m1"));
        check("3.3 其他 id → false", !GroupAtTracker.INSTANCE.isAtBot("smoke-m2"));
        check("3.4 null id → false 且记录不抛",
                !GroupAtTracker.INSTANCE.isAtBot(null) && recordNullSafe());

        check("3.5 首次 tryAcquire=true", RecentMessageGate.INSTANCE.tryAcquire("smoke-ga"));
        check("3.6 重复 tryAcquire=false（双事件/重投递拦截）",
                !RecentMessageGate.INSTANCE.tryAcquire("smoke-ga")
                        && !RecentMessageGate.INSTANCE.tryAcquire("smoke-ga"));
        check("3.7 不同 id 放行 + null/空恒放行",
                RecentMessageGate.INSTANCE.tryAcquire("smoke-gb")
                        && RecentMessageGate.INSTANCE.tryAcquire(null)
                        && RecentMessageGate.INSTANCE.tryAcquire(""));

        String s1 = QqText.INSTANCE.stripLeadingMentionPlaceholders("<@BOTID123> /查性能");
        check("3.8 行首群格式占位剥离 → /查性能", "/查性能".equals(s1), s1);
        String s2 = QqText.INSTANCE.stripLeadingMentionPlaceholders("  <@A1> <@!B2>   /查在线");
        check("3.9 多个连续 + 前导空白剥离", "/查在线".equals(s2), s2);
        String s3 = QqText.INSTANCE.stripLeadingMentionPlaceholders("你好 <@A1> 世界");
        check("3.10 正文中间占位不剥（转发语义保留）", "你好 <@A1> 世界".equals(s3), s3);
        String plain = "普通消息";
        check("3.11 无占位原样返回（同一实例）",
                QqText.INSTANCE.stripLeadingMentionPlaceholders(plain) == plain);
        String r1 = QqText.INSTANCE.removeMentionPlaceholders("你好 <@A1> 再 <@!B2> 见");
        check("3.12 全文剔除所有占位（AI 正文清理）", "你好  再  见".equals(r1), r1);

        // ========== 4. 查性能卡片实色面板（1.6.7.5 核心） ==========
        java.io.File work = Files.createTempDirectory("penguin-smoke-1675").toFile();
        int height = PerformanceRenderer.INSTANCE.measureHeight();
        BufferedImage bg = InfoCardAssets.INSTANCE.resolveBackground(work,
                PerformanceRenderer.WIDTH, height, true);
        PerformanceRenderer.PerformanceData data = new PerformanceRenderer.PerformanceData(
                "冒烟服务器", "22:41",
                PerformanceSnapshot.capture(19.87, 12.4, 0.234), bg);
        byte[] png = PerformanceRenderer.INSTANCE.render(data);
        BufferedImage img = javax.imageio.ImageIO.read(
                new java.io.ByteArrayInputStream(png));
        check("4.1 渲染成功（1350 x " + height + "）",
                img != null && img.getWidth() == 1350 && img.getHeight() == height);
        // 布局常量（与渲染器一致）：MARGIN=40 HEADER=150 GAP=18 CARD=250
        // 网格容器：x∈[40,1310] y∈[194,740]；卡 1：x∈[40,666] y∈[208,458]
        // CPU 卡（第 4 张）：x∈[684,1310] y∈[476,726]；进度条 y∈[654,666] x∈[716,1210]
        check("4.2 容器面板像素 = 实色 0x101319（无 alpha 混合）",
                pixelIs(img, 45, 200, 0x10, 0x13, 0x19), hex(img, 45, 200));
        check("4.3 指标卡内部像素 = 实色 0x151A21（背景完全被遮）",
                pixelIs(img, 300, 300, 0x15, 0x1A, 0x21), hex(img, 300, 300));
        // CPU 进度 23.4% → 填充止于 x≈831；x=1150 处应为轨道实色
        check("4.4 进度轨道像素 = 实色 0x1E242D",
                pixelIs(img, 1150, 482 + 178, 0x1E, 0x24, 0x2D), hex(img, 1150, 660));
        check("4.5 画布不含 alpha 通道（TYPE_INT_RGB 输出）",
                !img.getColorModel().hasAlpha());

        // ========== 5. 既有行为回归（1.6.7.3 行内格式 + 1.6.7.4 轮转） ==========
        String template;
        try (InputStream in = Test1675.class.getResourceAsStream("/config.yml")) {
            template = in != null ? new String(in.readAllBytes(), StandardCharsets.UTF_8) : null;
        }
        check("5.1 JAR 模板读取（含 config-version: 22）",
                template != null && template.contains("config-version: 22"));
        Map<String, Object> multi = new LinkedHashMap<>();
        multi.put("bot.groups", Arrays.asList("AAA", "BBB"));
        String multiMerged = YamlTextMerge.INSTANCE.merge(template, multi);
        check("5.2 多元素列表行内双引号", multiMerged.contains("groups: [\"AAA\", \"BBB\"]"));
        check("5.3 不产出非法块状行", !multiMerged.contains("groups: - "));
        Map<String, Object> single = new LinkedHashMap<>();
        single.put("bot.groups", Collections.singletonList("2532FC1C7629849FCE6491F3B72A06D2"));
        String singleMerged = YamlTextMerge.INSTANCE.merge(template, single);
        Object singleParsed = new cn.kerong.shaded.org.yaml.snakeyaml.Yaml().load(singleMerged);
        @SuppressWarnings("unchecked")
        Map<String, Object> singleBot = (Map<String, Object>) ((Map<String, Object>) singleParsed).get("bot");
        check("5.4 单元素列表语义完整",
                singleBot != null && Collections.singletonList("2532FC1C7629849FCE6491F3B72A06D2").equals(singleBot.get("groups")));
        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("bot.groups", Arrays.asList("ABC", 123, true));
        check("5.5 混合标量行内",
                YamlTextMerge.INSTANCE.merge(template, mixed).contains("groups: [\"ABC\", 123, true]"));

        check("5.6 日志轮转阈值常量 = 512KB",
                PluginFileLog.ROTATE_THRESHOLD_BYTES == 512L * 1024);
        java.io.File dir = Files.createTempDirectory("penguin-smoke-1675-log").toFile();
        String base = "qq-bind-2026-10-18";
        java.io.File m1 = PluginFileLog.INSTANCE.resolveRotated(dir, base);
        Files.write(m1.toPath(), new byte[512 * 1024]);
        java.io.File m2 = PluginFileLog.INSTANCE.resolveRotated(dir, base);
        check("5.7 主文件满 → 轮转 -1", m2 != null && m2.getName().equals(base + "-1.log"));

        // ========== 6. 分发 JAR 版本与快照回归 ==========
        String pluginYml;
        try (InputStream in = Test1675.class.getResourceAsStream("/plugin.yml")) {
            pluginYml = in != null ? new String(in.readAllBytes(), StandardCharsets.UTF_8) : null;
        }
        check("6.1 JAR plugin.yml 版本 1.6.7.5",
                pluginYml != null && pluginYml.contains("version: '1.6.7.5'"));
        PerformanceSnapshot snap = PerformanceSnapshot.capture(19.8, 12.5, 0.3);
        check("6.2 PerformanceSnapshot 三参 capture 回归",
                snap != null && snap.getCpuLoad() != null && Math.abs(snap.getCpuLoad() - 0.3) < 1e-9);

        System.out.println("\n===== 冒烟结果：" + passed + " 通过 / " + failed + " 失败 =====");
        System.exit(failed == 0 ? 0 : 1);
    }

    static boolean recordNullSafe() {
        try {
            GroupAtTracker.INSTANCE.recordAtBot(null);
            GroupAtTracker.INSTANCE.recordAtBot("");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean pixelIs(BufferedImage img, int x, int y, int r, int g, int b) {
        int rgb = img.getRGB(x, y);
        return ((rgb >> 16) & 0xFF) == r && ((rgb >> 8) & 0xFF) == g && (rgb & 0xFF) == b;
    }

    static String hex(BufferedImage img, int x, int y) {
        if (img == null) return "null";
        return String.format("0x%06X", img.getRGB(x, y) & 0xFFFFFF);
    }
}
