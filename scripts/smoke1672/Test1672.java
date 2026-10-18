import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.penguin.spigot.manager.YamlTextMerge;
import cn.kerong.penguin.spigot.render.GlobalOpacity;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1.6.7.2 覆盖版冒烟：配置重建列表修复 / 液态玻璃移除 / 缓存默认 60 / 全局透明度（零网络请求）。
 *
 * 覆盖：
 *  1. PerformanceSnapshot.capture 三参重载（cpuOverride 覆盖 / null 回退即时值）
 *  2. SparkBridge 反射桥（未装载 null / 全指标 / 部分缺失 / 全 null / 注销恢复）
 *  3. YamlTextMerge 文本级合并：
 *     - 标量替换 / §b 特殊字符引号 / 多元素列表块状 / 自定义键段尾插入 / 注释保留
 *     - 覆盖版回归：单元素列表替换为块状（用户单群 bot.groups 踩坑场景）、
 *       不再产出「groups: - xxx」非法行、单元素自定义键插入、List<Map>
 *       （custom-commands 形态）回填、空列表 []
 *     - 合并结果经 snakeyaml 重新解析校验语义正确
 *  4. 渲染与全局透明度（覆盖版）：
 *     - PerformanceStyle 枚举已删除（液态玻璃移除）；PerformanceData 无 style 参数
 *     - 原版出图 PNG 魔数与高度 766
 *     - GlobalOpacity.applyOpacity：1.0 原实例零开销 / 0.5 半透明（PNG alpha 抽样）/
 *       尺寸保持；RenderSettings.globalOpacity() 默认 1.0（BotShared 未初始化）
 *     - 性能数据缓存默认 60 秒（DEFAULT_PERFORMANCE_CACHE_SECONDS / millis）
 *  5. PublicCommands 命令注册表保持含「查性能」
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
        @Override public DoubleAverageInfo[] poll() { return new DoubleAverageInfo[0]; }
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
                "config-version: 21",
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
                "custom-commands: []",
                "",
                "markdown:",
                "  # 查在线模板",
                "  queryOnline: online.md",
                "",
                "render:",
                "  global-opacity: 1.0",
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
        check("3.9 版本号未被覆盖", merged.contains("config-version: 21"));

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

        // ---- 3.16~3.21 覆盖版回归：列表渲染修复（单群用户 bot.groups 踩坑场景） ----
        Map<String, Object> single = new LinkedHashMap<>();
        single.put("bot.groups", Collections.singletonList("2532FC1C7629849FCE6491F3B72A06D2"));
        String singleMerged = YamlTextMerge.INSTANCE.merge(template, single);
        check("3.16 单元素列表 → 块状（键行 + 项行）",
                singleMerged.contains("groups:\n  - 2532FC1C7629849FCE6491F3B72A06D2"));
        boolean noBadLine = true;
        for (String line : singleMerged.split("\n", -1)) {
            if (line.trim().startsWith("groups:") && line.matches("^\\s*groups:\\s*-.*")) {
                noBadLine = false;
                break;
            }
        }
        check("3.17 单元素列表不再产出非法行内列表（groups: - xxx）", noBadLine);
        Object singleParsed = new cn.kerong.shaded.org.yaml.snakeyaml.Yaml().load(singleMerged);
        @SuppressWarnings("unchecked")
        Map<String, Object> singleBot = (Map<String, Object>) ((Map<String, Object>) singleParsed).get("bot");
        check("3.18 单元素列表语义正确（完整 OpenId 保留）",
                singleBot != null && Collections.singletonList("2532FC1C7629849FCE6491F3B72A06D2").equals(singleBot.get("groups")));

        // 自定义键（模板没有）+ 单元素列表 → 插入后合法
        Map<String, Object> insertSingle = new LinkedHashMap<>();
        insertSingle.put("admin.openids", Collections.singletonList("ADMINOPENID123"));
        String insertMerged = YamlTextMerge.INSTANCE.merge(template, insertSingle);
        Object insertParsed = new cn.kerong.shaded.org.yaml.snakeyaml.Yaml().load(insertMerged);
        boolean insertOk = false;
        if (insertParsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> admin = (Map<String, Object>) ((Map<String, Object>) insertParsed).get("admin");
            insertOk = admin != null && Collections.singletonList("ADMINOPENID123").equals(admin.get("openids"));
        }
        check("3.19 模板外自定义键 + 单元素列表插入合法且语义正确", insertOk);

        // List<Map>（custom-commands 形态）回填 → 合法可解析
        Map<String, Object> cmdMap = new LinkedHashMap<>();
        cmdMap.put("key", "重启");
        cmdMap.put("command", "restart");
        cmdMap.put("permission", 2);
        Map<String, Object> listMap = new LinkedHashMap<>();
        listMap.put("custom-commands", Collections.singletonList(cmdMap));
        String cmdMerged = YamlTextMerge.INSTANCE.merge(template, listMap);
        Object cmdParsed = new cn.kerong.shaded.org.yaml.snakeyaml.Yaml().load(cmdMerged);
        boolean cmdOk = false;
        if (cmdParsed instanceof Map) {
            Object list = ((Map<String, Object>) cmdParsed).get("custom-commands");
            if (list instanceof java.util.List && ((java.util.List<?>) list).size() == 1) {
                @SuppressWarnings("unchecked")
                Map<String, Object> first = (Map<String, Object>) ((java.util.List<?>) list).get(0);
                cmdOk = "重启".equals(first.get("key")) && "restart".equals(first.get("command"))
                        && Integer.valueOf(2).equals(first.get("permission"));
            }
        }
        check("3.20 List<Map>（custom-commands 形态）回填合法且语义正确", cmdOk);

        // 空列表 override → 行内 []
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("bot.groups", Collections.emptyList());
        String emptyMerged = YamlTextMerge.INSTANCE.merge(template, empty);
        boolean emptyOk = false;
        Object emptyParsed = new cn.kerong.shaded.org.yaml.snakeyaml.Yaml().load(emptyMerged);
        if (emptyParsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> emptyBot = (Map<String, Object>) ((Map<String, Object>) emptyParsed).get("bot");
            emptyOk = emptyBot != null && java.util.Collections.emptyList().equals(emptyBot.get("groups"));
        }
        check("3.21 空列表回填语义正确（[]）", emptyOk && emptyMerged.contains("groups: []"));

        // ---- 4. 渲染与全局透明度（覆盖版） ----
        boolean styleGone;
        try {
            Class.forName("cn.kerong.penguin.spigot.render.RenderSettings$PerformanceStyle");
            styleGone = false;
        } catch (ClassNotFoundException expected) {
            styleGone = true;
        }
        check("4.1 PerformanceStyle 枚举已删除（液态玻璃移除）", styleGone);
        int height = PerformanceRenderer.INSTANCE.measureHeight();
        check("4.2 固定四项高度 766 保持", height == 766);
        PerformanceSnapshot snap = PerformanceSnapshot.capture(19.8, 12.5, 0.3);
        byte[] vanillaPng = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "冒烟测试服", "23:59", snap, null));
        check("4.3 原版样式出图（PNG 魔数，无 style 入参）",
                vanillaPng.length > 10000 && (vanillaPng[0] & 0xFF) == 0x89 && vanillaPng[1] == 'P');

        // GlobalOpacity.applyOpacity 纯函数
        BufferedImage plain = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D pg = plain.createGraphics();
        pg.setColor(java.awt.Color.WHITE);
        pg.fillRect(0, 0, 40, 40);
        pg.dispose();
        BufferedImage identity = GlobalOpacity.INSTANCE.applyOpacity(plain, 1.0f);
        check("4.4 透明度 1.0 → 原实例原样返回（零开销）", identity == plain);
        BufferedImage half = GlobalOpacity.INSTANCE.applyOpacity(plain, 0.5f);
        check("4.5 透明度 0.5 → 新 ARGB 画布（尺寸保持）",
                half != plain && half.getWidth() == 40 && half.getHeight() == 40
                        && half.getType() == BufferedImage.TYPE_INT_ARGB);
        java.io.ByteArrayOutputStream halfOut = new java.io.ByteArrayOutputStream();
        ImageIO.write(half, "png", halfOut);
        BufferedImage halfBack = ImageIO.read(new ByteArrayInputStream(halfOut.toByteArray()));
        boolean alphaOk = halfBack != null && halfBack.getWidth() == 40;
        if (alphaOk) {
            int[][] samples = {{0, 0}, {39, 0}, {0, 39}, {39, 39}, {20, 20}};
            for (int[] s : samples) {
                int a = (halfBack.getRGB(s[0], s[1]) >>> 24) & 0xFF;
                if (a < 120 || a > 135) { alphaOk = false; break; }
            }
        }
        check("4.6 透明度 0.5 → PNG 整体 alpha≈128（抽样五点）", alphaOk);
        BufferedImage clamped = GlobalOpacity.INSTANCE.applyOpacity(plain, 5.0f);
        check("4.7 透明度超界（5.0）按 1.0 处理（原样返回）", clamped == plain);
        check("4.8 RenderSettings.globalOpacity() 默认 1.0（配置未初始化）",
                Math.abs(RenderSettings.INSTANCE.globalOpacity() - 1.0f) < 1e-6);
        check("4.9 性能数据缓存默认 60 秒（覆盖版 5 → 60）",
                RenderSettings.DEFAULT_PERFORMANCE_CACHE_SECONDS == 60);
        check("4.10 performanceCacheMillis() 默认 60000ms",
                RenderSettings.INSTANCE.performanceCacheMillis() == 60000L);

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
