import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.bot.tools.PluginFileLog;
import cn.kerong.penguin.spigot.manager.YamlTextMerge;
import cn.kerong.penguin.spigot.stats.SparkBridge;
import cn.kerong.penguin.spigot.stats.SparkStartupCheck;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1.6.7.4 冒烟：日志单文件 512KB 轮转 / spark 提示三态决策 / 控制台降噪行为
 * （零网络请求；1.6.7.3 行内格式与 SparkBridge 桥回归）。
 *
 * 覆盖：
 *  1. PluginFileLog.resolveRotated 轮转（空目录主文件 / 主文件满 → -1 /
 *     -1 满 → -2 / -1 未满复用 / 阈值常量 512KB）
 *  2. SparkStartupCheck 三态 ProbeOutcome（语义构造）+ schedule 短延迟
 *     四路径（NotInstalled / Installed / Failed / probe 抛异常）均不抛
 *     （无插件环境下 PluginFileLog 降级被吞，线程正常结束）
 *  3. SparkBridge 反射桥回归（FakeSpark 全指标读取）
 *  4. YamlTextMerge 行内格式回归（1.6.7.3 修复保持：多元素行内双引号 /
 *     单元素列表语义完整 / 混合标量 / 合并结果 snakeyaml 重解析合法）
 *  5. 分发 JAR plugin.yml 版本 1.6.7.4
 */
public class Test1674 {

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
        @Override public DoubleAverageInfo[] poll() { return new DoubleAverageInfo[0]; }
    }

    /** 全指标假 spark。 */
    public static class FakeSpark implements Spark {
        @Override public DoubleStatistic<StatisticWindow.CpuUsage> cpuProcess() { return new FakeCpuStat(); }
        @Override public DoubleStatistic<StatisticWindow.TicksPerSecond> tps() { return new FakeTpsStat(); }
        @Override public GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> mspt() { return new FakeMsptStat(); }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        // ================= 1. 日志 512KB 轮转（1.6.7.4 核心） =================
        java.io.File dir = Files.createTempDirectory("penguin-smoke-1674").toFile();
        String base = "qq-bind-2026-10-18";
        check("1.0 阈值常量 = 512KB（ROTATE_THRESHOLD_BYTES）",
                PluginFileLog.ROTATE_THRESHOLD_BYTES == 512L * 1024);
        java.io.File f1 = PluginFileLog.INSTANCE.resolveRotated(dir, base);
        check("1.1 空目录 → 解析到主文件（qq-bind-2026-10-18.log）",
                f1 != null && f1.getName().equals(base + ".log") && f1.getParentFile().equals(dir));
        // 主文件恰好写满 512KB（>= 阈值即轮转）
        byte[] fullChunk = new byte[512 * 1024];
        Files.write(f1.toPath(), fullChunk);
        java.io.File f2 = PluginFileLog.INSTANCE.resolveRotated(dir, base);
        check("1.2 主文件 ≥512KB → 轮转到 -1（qq-bind-2026-10-18-1.log）",
                f2 != null && f2.getName().equals(base + "-1.log"));
        // -1 也写满
        Files.write(new java.io.File(dir, base + "-1.log").toPath(), fullChunk);
        java.io.File f3 = PluginFileLog.INSTANCE.resolveRotated(dir, base);
        check("1.3 -1 也满 → 轮转到 -2（顺次找第一个不满的）",
                f3 != null && f3.getName().equals(base + "-2.log"));
        // -1 删除重建为小文件（未满）→ 复用 -1
        java.io.File one = new java.io.File(dir, base + "-1.log");
        one.delete();
        Files.write(one.toPath(), new byte[100]);
        java.io.File f4 = PluginFileLog.INSTANCE.resolveRotated(dir, base);
        check("1.4 -1 未满 → 继续复用 -1（不跳号）",
                f4 != null && f4.getName().equals(base + "-1.log"));
        // 主文件 511KB（低于阈值）→ 用主文件
        java.io.File mainF = new java.io.File(dir, base + ".log");
        mainF.delete();
        Files.write(mainF.toPath(), new byte[512 * 1024 - 1]);
        java.io.File f5 = PluginFileLog.INSTANCE.resolveRotated(dir, base);
        check("1.5 主文件 511KB（未达阈值）→ 仍用主文件",
                f5 != null && f5.getName().equals(base + ".log"));

        // ================= 2. SparkStartupCheck 三态（1.6.7.4） =================
        SparkStartupCheck.ProbeOutcome notInstalled = SparkStartupCheck.ProbeOutcome.NotInstalled.INSTANCE;
        SparkStartupCheck.ProbeOutcome.Installed installed =
                new SparkStartupCheck.ProbeOutcome.Installed(new SparkBridge.SparkMetrics(19.7, 25.0, 0.37));
        SparkStartupCheck.ProbeOutcome.Failed probeFailed =
                new SparkStartupCheck.ProbeOutcome.Failed("各指标均暂不可读");
        check("2.1 三态类型语义（NotInstalled 单例 / Installed 带指标 / Failed 带原因）",
                notInstalled != null && installed != null && probeFailed != null);
        check("2.2 Installed 携带指标（SparkMetrics 可读）",
                Math.abs(installed.getMetrics().getTps() - 19.7) < 1e-9);
        check("2.3 Failed 携带原因", "各指标均暂不可读".equals(probeFailed.getReason()));
        // schedule 短延迟四路径均不抛（无插件环境：PluginFileLog 降级被吞、
        // 线程守护正常结束——决策路径全量覆盖不崩溃）
        boolean schedOk = true;
        try {
            SparkStartupCheck.INSTANCE.schedule(1L, () -> notInstalled);
            SparkStartupCheck.INSTANCE.schedule(1L, () -> installed);
            SparkStartupCheck.INSTANCE.schedule(1L, () -> probeFailed);
            SparkStartupCheck.INSTANCE.schedule(1L, () -> {
                throw new IllegalStateException("probe boom");
            });
            Thread.sleep(400L); // 等守护线程跑完
        } catch (Throwable t) {
            schedOk = false;
        }
        check("2.4 schedule 四路径（未安装/已接入/失败/探测异常）均不抛", schedOk);
        check("2.5 neoforgeDelay = 启动成功后 5 秒节奏",
                SparkStartupCheck.INSTANCE.neoforgeDelay() == 5_000L);

        // ================= 3. SparkBridge 桥回归（1.6.7.2 行为保持） =================
        SparkBridge.SparkMetrics nothing = SparkBridge.INSTANCE.read(null);
        check("3.1 spark 未装载 → read 返回 null（回退自研链）", nothing == null);
        SparkProvider.setForTest(new FakeSpark());
        SparkBridge.SparkMetrics fullMetrics = SparkBridge.INSTANCE.read(null);
        boolean fullOk = fullMetrics != null && Math.abs(fullMetrics.getTps() - 19.7) < 1e-9
                && Math.abs(fullMetrics.getMspt() - 25.0) < 1e-9 && Math.abs(fullMetrics.getCpu() - 0.37) < 1e-9;
        check("3.2 假 spark 全指标（TPS 19.7 / MSPT 25.0 / CPU 0.37）", fullOk);

        // ================= 4. YamlTextMerge 行内格式回归（1.6.7.3 保持） =================
        String template;
        try (InputStream in = Test1674.class.getResourceAsStream("/config.yml")) {
            template = in != null ? new String(in.readAllBytes(), StandardCharsets.UTF_8) : null;
        }
        check("4.1 JAR 模板读取（含 config-version: 22）",
                template != null && template.contains("config-version: 22"));

        Map<String, Object> multi = new LinkedHashMap<>();
        multi.put("bot.groups", Arrays.asList("AAA", "BBB"));
        String multiMerged = YamlTextMerge.INSTANCE.merge(template, multi);
        check("4.2 多元素列表行内双引号（groups: [\"AAA\", \"BBB\"]）",
                multiMerged.contains("groups: [\"AAA\", \"BBB\"]"));
        check("4.3 不产出非法块状行（groups: - xxx）", !multiMerged.contains("groups: - "));

        Map<String, Object> single = new LinkedHashMap<>();
        single.put("bot.groups", Collections.singletonList("2532FC1C7629849FCE6491F3B72A06D2"));
        String singleMerged = YamlTextMerge.INSTANCE.merge(template, single);
        Object singleParsed = new cn.kerong.shaded.org.yaml.snakeyaml.Yaml().load(singleMerged);
        @SuppressWarnings("unchecked")
        Map<String, Object> singleBot = (Map<String, Object>) ((Map<String, Object>) singleParsed).get("bot");
        check("4.4 单元素列表语义完整（OpenId 全串保留）",
                singleBot != null && Collections.singletonList("2532FC1C7629849FCE6491F3B72A06D2").equals(singleBot.get("groups")));

        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("bot.groups", Arrays.asList("ABC", 123, true));
        String mixedMerged = YamlTextMerge.INSTANCE.merge(template, mixed);
        check("4.5 混合标量行内（字符串引号 / 数值布尔原样）",
                mixedMerged.contains("groups: [\"ABC\", 123, true]"));

        // ================= 5. 分发 JAR 版本与性能快照回归 =================
        String pluginYml;
        try (InputStream in = Test1674.class.getResourceAsStream("/plugin.yml")) {
            pluginYml = in != null ? new String(in.readAllBytes(), StandardCharsets.UTF_8) : null;
        }
        check("5.1 JAR plugin.yml 版本 1.6.7.4",
                pluginYml != null && pluginYml.contains("version: '1.6.7.4'"));
        PerformanceSnapshot snap = PerformanceSnapshot.capture(19.8, 12.5, 0.3);
        check("5.2 PerformanceSnapshot 三参 capture 回归（cpuOverride 生效）",
                snap != null && snap.getCpuLoad() != null && Math.abs(snap.getCpuLoad() - 0.3) < 1e-9);

        System.out.println("\n===== 冒烟结果：" + passed + " 通过 / " + failed + " 失败 =====");
        System.exit(failed == 0 ? 0 : 1);
    }
}
