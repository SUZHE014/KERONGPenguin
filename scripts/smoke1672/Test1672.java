import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.penguin.spigot.manager.YamlTextMerge;
import cn.kerong.penguin.spigot.render.PerformanceRenderer;
import cn.kerong.penguin.spigot.render.RenderSettings;
import cn.kerong.penguin.spigot.stats.SparkBridge;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1.6.7.2 冒烟：Spark 联动 / 液态玻璃样式 / 性能数据缓存链路 / 配置文本合并（零网络请求）。
 *
 * 覆盖：
 *  1. PerformanceSnapshot.capture 三参重载（cpuOverride 覆盖 / null 回退即时值）
 *  2. SparkBridge 反射桥（1.6.7.2 核心新增）：
 *     - spark 未装载（SparkProvider.get 抛 IllegalStateException）→ null
 *     - 假 spark 全指标 → TPS（MINUTES_1）/ MSPT（SECONDS_10 均值）/ CPU（MINUTES_1）逐项读出
 *     - 部分指标 null（tps 缺失）→ 其余项照常、单项 null 不拖累
 *     - 全部 null → read 返回 null
 *  3. YamlTextMerge 文本级合并（配置版本重建核心算法）：
 *     - 标量替换 / 特殊字符（§b 染色串）引号 / 空列表→块状列表
 *     - 自定义键插入到对应段尾（保缩进）
 *     - 注释与段落结构原样保留
 *     - 合并结果经 snakeyaml 重新解析校验语义正确
 *     - 空覆盖 → 原文返回
 *  4. 液态玻璃渲染：LIQUID_GLASS 出图（766 高 / PNG 魔数）且与 VANILLA 字节不同
 *  5. RenderSettings.PerformanceStyle 枚举在位
 *  6. PublicCommands 命令注册表保持含「查性能」
 */
public class Test1672 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name); }
    }

    // ---------- 假 spark 实现（签名对齐真实 API，纯内存取值） ----------

    public static class FakeTpsStat implements DoubleStatistic<StatisticWindow.TicksPerSecond> {
        @Override public double poll(StatisticWindow.TicksPerSecond window) { return 19.7; }
        @Override public double[] poll() { return new double[]{19.7, 19.5, 19.2}; }
    }

    public static class FakeCpuStat implements DoubleStatistic<StatisticWindow.CpuUsage> {
        @Override public double poll(StatisticWindow.CpuUsage window) { return 0.37; }
        @Override public double[] poll() { return new double[]{0.37, 0.35, 0.33}; }
    }

    public static class FakeMsptInfo implements DoubleAverageInfo {
        @Override public double mean() { return 25.0; }
        @Override public double max() { return 41.0; }
        @Override public double min() { return 12.0; }
        @Override public double percentile(double percentile) { return 30.0; }
    }

    public static class FakeMsptStat implements GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> {
        @Override public DoubleAverageInfo poll(StatisticWindow.MillisPerTick window) { return new FakeMsptInfo(); }
        @Override public DoubleAverageInfo[] poll() { return new FakeMsptInfo[0]; }
    }

    /** 全指标假 spark。 */
    public static class FakeSpark implements Spark {
        @Override public DoubleStatistic<StatisticWindow.CpuUsage> cpuProcess() { return new FakeCpuStat(); }
        @Override public DoubleStatistic<StatisticWindow.TicksPerSecond> tps() { return new FakeTpsStat(); }
        @Override public GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> mspt() { return new FakeMsptStat(); }
    }

    /** TPS 统计缺失的假 spark（部分核心 tps() 返回 null）。 */
    public static class PartialSpark implements Spark {
        @Override public DoubleStatistic<StatisticWindow.CpuUsage> cpuProcess() { return new FakeCpuStat(); }
        @Override public DoubleStatistic<StatisticWindow.TicksPerSecond> tps() { return null; }
        @Override public GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> mspt() { return new FakeMsptStat(); }
    }

    /** 全部指标 null 的假 spark。 */
    public static class BareSpark implements Spark {
        @Override public DoubleStatistic<StatisticWindow.CpuUsage> cpuProcess() { return null; }
        @Override public DoubleStatistic<StatisticWindow.TicksPerSecond> tps() { return null; }
        @Override public GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> mspt() { return null; }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        // ---- 1. 快照 capture 三参重载 ----
        PerformanceSnapshot overrode = PerformanceSnapshot.capture(19.5, 12.5, 0.42);
        check("1.1 capture 三参：cpuOverride 生效", overrode.getCpuLoad() != null && Math.abs(overrode.getCpuLoad() - 0.42) < 1e-9);
        check("1.2 capture 三参：TPS/MSPT 传递",
                Double.valueOf(19.5).equals(overrode.getTps()) && Double.valueOf(12.5).equals(overrode.getMspt()));
        PerformanceSnapshot fallback = PerformanceSnapshot.capture(19.5, 12.5, null);
        check("1.3 capture null CPU 回退即时采集（null 或 [0,1]）",
                fallback.getCpuLoad() == null || (fallback.getCpuLoad() >= 0.0 && fallback.getCpuLoad() <= 1.0));
        PerformanceSnapshot legacy = PerformanceSnapshot.capture(19.5, 12.5);
        check("1.4 capture 两参旧签名仍可用", legacy != null && Double.valueOf(19.5).equals(legacy.getTps()));

        // ---- 2. SparkBridge 反射桥（先测未装载，再注入假 spark） ----
        SparkBridge.SparkMetrics nothing = SparkBridge.INSTANCE.read(null);
        check("2.1 spark 未装载 → read 返回 null", nothing == null);

        SparkProvider.setForTest(new FakeSpark());
        SparkBridge.SparkMetrics full = SparkBridge.INSTANCE.read(null);
        check("2.2 假 spark 读出全部指标", full != null);
        check("2.3 TPS（TicksPerSecond.MINUTES_1）= 19.7",
                full != null && full.getTps() != null && Math.abs(full.getTps() - 19.7) < 1e-9);
        check("2.4 MSPT（MillisPerTick.SECONDS_10 均值）= 25.0",
                full != null && full.getMspt() != null && Math.abs(full.getMspt() - 25.0) < 1e-9);
        check("2.5 CPU（CpuUsage.MINUTES_1）= 0.37",
                full != null && full.getCpu() != null && Math.abs(full.getCpu() - 0.37) < 1e-9);

        SparkProvider.setForTest(new PartialSpark());
        SparkBridge.SparkMetrics partial = SparkBridge.INSTANCE.read(null);
        check("2.6 tps 统计缺失 → tps null 其余照常（单项不拖累）",
                partial != null && partial.getTps() == null && partial.getMspt() != null && partial.getCpu() != null);

        SparkProvider.setForTest(new BareSpark());
        check("2.7 全部指标 null → read 返回 null", SparkBridge.INSTANCE.read(null) == null);

        SparkProvider.setForTest(null);
        check("2.8 注销后恢复未装载（get 抛出 → null）", SparkBridge.INSTANCE.read(null) == null);

        // ---- 3. YamlTextMerge 文本级合并（配置版本重建核心算法） ----
        String template = String.join("\n",
                "# 配置文件版本号（插件自动维护）",
                "config-version: 20",
                "",
                "# 服务器名",
                "serverName: KERONGPenguin",
                "",
                "chat-format:",
                "  # 群消息转发格式",
                "  from-group: \"[QQ] {name}: {message}\"",
                "  post-chat: true",
                "",
                "bot:",
                "  app-id: \"\"",
                "  groups: []",
                "",
                "markdown:",
                "  # 查在线模板",
                "  queryOnline: online.md",
                "",
                "render:",
                "  command-cooldown-seconds: 5",
                "");

        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("serverName", "MyServer");
        overrides.put("chat-format.from-group", "[§bQQ§r] {name}: {message}");
        overrides.put("bot.groups", Arrays.asList("AAA", "BBB"));
        overrides.put("markdown.custom", "custom.md");
        overrides.put("render.command-cooldown-seconds", 30);

        String merged = YamlTextMerge.INSTANCE.merge(template, overrides);
        check("3.1 注释原样保留（头部注释仍在）", merged.startsWith("# 配置文件版本号（插件自动维护）"));
        check("3.2 标量替换（serverName: MyServer）", merged.contains("\nserverName: MyServer\n"));
        check("3.3 特殊字符引号（§b 染色串）",
                merged.contains("from-group: '[§bQQ§r] {name}: {message}'"));
        check("3.4 空列表 → 块状列表", merged.contains("groups:\n  - AAA\n  - BBB"));
        check("3.5 自定义键插入段尾（缩进 2）", merged.contains("  custom: custom.md"));
        check("3.6 数值替换（cooldown 30）", merged.contains("command-cooldown-seconds: 30"));
        check("3.7 未动键保持（queryOnline: online.md）", merged.contains("queryOnline: online.md"));
        check("3.8 段落结构保持（render: 段头仍在）", merged.contains("\nrender:\n"));
        check("3.9 版本号未被覆盖", merged.contains("config-version: 20"));

        Object parsed = new cn.kerong.shaded.org.yaml.snakeyaml.Yaml().load(merged);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) parsed;
        check("3.10 合并结果可重新解析", root != null && "MyServer".equals(root.get("serverName")));
        @SuppressWarnings("unchecked")
        Map<String, Object> bot = (Map<String, Object>) root.get("bot");
        check("3.11 块状列表语义正确（AAA/BBB）",
                bot != null && Arrays.asList("AAA", "BBB").equals(bot.get("groups")));
        @SuppressWarnings("unchecked")
        Map<String, Object> markdown = (Map<String, Object>) root.get("markdown");
        check("3.12 插入的自定义键语义正确", markdown != null && "custom.md".equals(markdown.get("custom")));
        @SuppressWarnings("unchecked")
        Map<String, Object> chatFormat = (Map<String, Object>) root.get("chat-format");
        check("3.13 §b 染色串解析回原值",
                chatFormat != null && "[§bQQ§r] {name}: {message}".equals(chatFormat.get("from-group")));
        check("3.14 数值类型保持（cooldown 整数 30）",
                root.get("render") instanceof Map && Integer.valueOf(30).equals(((Map<?, ?>) root.get("render")).get("command-cooldown-seconds")));

        String untouched = YamlTextMerge.INSTANCE.merge(template, new LinkedHashMap<String, Object>());
        check("3.15 空覆盖 → 原文返回", untouched.equals(template));

        // ---- 4. 液态玻璃渲染 ----
        check("4.1 PerformanceStyle 枚举在位（VANILLA / LIQUID_GLASS）",
                RenderSettings.PerformanceStyle.VANILLA != null && RenderSettings.PerformanceStyle.LIQUID_GLASS != null);
        int height = PerformanceRenderer.INSTANCE.measureHeight();
        check("4.2 固定四项高度 766 保持", height == 766);
        PerformanceSnapshot snap = PerformanceSnapshot.capture(19.8, 12.5, 0.3);
        byte[] vanillaPng = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "冒烟测试服", "23:59", snap, null, RenderSettings.PerformanceStyle.VANILLA));
        byte[] liquidPng = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "冒烟测试服", "23:59", snap, null, RenderSettings.PerformanceStyle.LIQUID_GLASS));
        check("4.3 原版样式出图（PNG 魔数）",
                vanillaPng.length > 10000 && (vanillaPng[0] & 0xFF) == 0x89 && vanillaPng[1] == 'P');
        check("4.4 液态玻璃出图（PNG 魔数）",
                liquidPng.length > 10000 && (liquidPng[0] & 0xFF) == 0x89 && liquidPng[1] == 'P');
        BufferedImage liquidImg = ImageIO.read(new ByteArrayInputStream(liquidPng));
        check("4.5 液态玻璃尺寸（1350 × 766）",
                liquidImg.getWidth() == 1350 && liquidImg.getHeight() == 766);
        check("4.6 两样式产出不同（液态玻璃确实换了画法）", !Arrays.equals(vanillaPng, liquidPng));
        java.io.File sample = new java.io.File("/home/z/my-project/penguin-git/scripts/smoke1672/performance-liquid-glass.png");
        java.nio.file.Files.write(sample.toPath(), liquidPng);
        check("4.7 液态玻璃样图落盘（人工复核用）", sample.isFile() && sample.length() == liquidPng.length);

        // ---- 5. 命令注册表保持（查性能） ----
        Object commands = Class.forName("cn.kerong.bot.events.commands.PublicCommands").getDeclaredConstructor().newInstance();
        Field mapField = Class.forName("cn.kerong.bot.events.commands.BaseCommand").getDeclaredField("commandMap");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> commandMap = (Map<String, Object>) mapField.get(commands);
        check("5.1 PublicCommands 注册 查性能", commandMap.containsKey("查性能"));
        check("5.2 查在线 / 查信息命令保持", commandMap.containsKey("查在线") && commandMap.containsKey("查信息"));

        System.out.println("\n===== 冒烟结果：" + passed + " 通过 / " + failed + " 失败 =====");
        System.exit(failed == 0 ? 0 : 1);
    }
}
