import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.penguin.spigot.render.PerformanceRenderer;
import cn.kerong.penguin.spigot.stats.SparkBridge;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * 1.6.7.8 功能冒烟（独立 JVM，headless）。
 *
 * 覆盖：
 *  1. TPS 物理上限 20 三层封顶（回归，capture 改四参后重验）：
 *     capture(300) → 20 / 18.2 原样 / null 保持；readTps 反射链全分支封顶
 *  2. 快照结构：无 avgPingMillis 字段与 getter（延迟指标下线）；
 *     onlinePlayers 保持；构造器六参；旧五参工厂已移除
 *  3. SparkBridge：真实形态假 spark（含 playerPing 统计——1.6.7.8 起
 *     不再轮询，read 照常成功且 SparkMetrics 无 getPing）；
 *     tps=300 封顶 20，mspt/cpu 不受影响；内置通道回归
 *  4. 渲染器：buildCards 恰五项（无延迟卡）；在线人数卡三态
 *     （在线中 / 空服 / 暂不可读）；高度 1034 不变；PNG 渲染成功
 *  5. 分发 JAR：plugin.yml 1.6.7.8 / 无 aliases；config.yml 固定五项
 *
 * 编译运行（仓库根目录）：
 *   javac -cp build/dist/KERONGPenguin_Spigot-1.6.7.8.jar \
 *     -d scripts/smoke1678-out scripts/smoke1678/Test1678.java
 *   java -Djava.awt.headless=true \
 *     -cp scripts/smoke1678-out:build/dist/KERONGPenguin_Spigot-1.6.7.8.jar:\
 *     scripts/deps-work/paper-api-1.20.4.jar:scripts/deps-work/adventure-api-4.17.0.jar:\
 *     scripts/deps-work/adventure-key-4.17.0.jar:scripts/deps-work/examination-api-1.3.0.jar:\
 *     scripts/deps-work/examination-string-1.3.0.jar:scripts/deps-work/snakeyaml-2.2.jar \
 *     Test1678
 */
public class Test1678 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  [" + detail + "]");
        }
    }

    static void checkEq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(name, ok, "expect=" + expected + " actual=" + actual);
    }

    // ---- 假服务器对象：getTPS() 返回可控数组（反射链测试） ----

    public static class FakeServer {
        private final double[] tps;
        public FakeServer(double[] tps) { this.tps = tps; }
        public double[] getTPS() { return tps; }
    }

    public static class BoxedServer {
        private final Double[] tps;
        public BoxedServer(Double[] tps) { this.tps = tps; }
        public Double[] getTPS() { return tps; }
    }

    public static class RecentTpsServer {
        public final double[] recentTps;
        public RecentTpsServer(double[] tps) { this.recentTps = tps; }
    }

    public static class NoTpsServer {
        public String toString() { return "no-tps"; }
    }

    public static void main(String[] args) throws Exception {
        // ============ 1. TPS 三层封顶（capture 四参回归） ============
        System.out.println("---- 1. TPS 物理上限 20 三层封顶 ----");

        PerformanceSnapshot s = PerformanceSnapshot.capture(300.0, 50.0, null, 3);
        checkEq("TC1 capture(300) 封顶到 20", 20.0, s.getTps());
        s = PerformanceSnapshot.capture(18.2, 51.0, null, 3);
        checkEq("TC2 capture(18.2) 正常值不变", 18.2, s.getTps());
        s = PerformanceSnapshot.capture(null, 51.0, null, 3);
        check("TC3 capture(null) 保持 null", s.getTps() == null);
        checkEq("TC3b capture 四参 online 透传", 3, s.getOnlinePlayers());

        checkEq("TC4 readTps(getTPS={300,20,20}) 封顶 20",
                20.0, PerformanceSnapshot.readTps(new FakeServer(new double[]{300.0, 20.0, 20.0})));
        checkEq("TC5 readTps(getTPS={19.5,...}) 正常",
                19.5, PerformanceSnapshot.readTps(new FakeServer(new double[]{19.5, 19.0, 18.0})));
        checkEq("TC6 readTps 装箱 Double[]{400,...} 封顶 20",
                20.0, PerformanceSnapshot.readTps(new BoxedServer(new Double[]{400.0, 20.0, 20.0})));
        checkEq("TC7 readTps(recentTps={320,...}) 封顶 20",
                20.0, PerformanceSnapshot.readTps(new RecentTpsServer(new double[]{320.0, 20.0, 20.0})));
        check("TC8 readTps(无指标对象) null",
                PerformanceSnapshot.readTps(new NoTpsServer()) == null);
        checkEq("TC9 tpsFromMspt(3.3) 封顶 20", 20.0, PerformanceSnapshot.tpsFromMspt(3.3));
        checkEq("TC10 tpsFromMspt(55.6) 正常换算", 1000.0 / 55.6, PerformanceSnapshot.tpsFromMspt(55.6));

        // ============ 2. 快照结构（延迟字段下线） ============
        System.out.println("---- 2. 快照结构 ----");
        try {
            PerformanceSnapshot.class.getDeclaredField("avgPingMillis");
            check("S1 无 avgPingMillis 字段", false, "字段仍存在");
        } catch (NoSuchFieldException e) {
            check("S1 无 avgPingMillis 字段", true);
        }
        try {
            PerformanceSnapshot.class.getMethod("getAvgPingMillis");
            check("S2 无 getAvgPingMillis getter", false, "getter 仍存在");
        } catch (NoSuchMethodException e) {
            check("S2 无 getAvgPingMillis getter", true);
        }
        try {
            PerformanceSnapshot.class.getDeclaredField("onlinePlayers");
            check("S3 onlinePlayers 字段保持", true);
        } catch (NoSuchFieldException e) {
            check("S3 onlinePlayers 字段保持", false, e.toString());
        }
        try {
            Constructor<PerformanceSnapshot> c = PerformanceSnapshot.class.getDeclaredConstructor(
                    Double.class, Double.class, Long.TYPE, Long.TYPE, Double.class, Integer.class);
            check("S4 六参构造器（无 ping 参数）", true);
            c.setAccessible(true);
            PerformanceSnapshot manual = c.newInstance(19.0, 41.0, 1024L, 4096L, 0.25, 7);
            checkEq("S5 手工构造 online=7", 7, manual.getOnlinePlayers());
        } catch (NoSuchMethodException e) {
            check("S4 六参构造器（无 ping 参数）", false, e.toString());
        }
        // 旧五参工厂（含 ping）必须已不存在
        try {
            PerformanceSnapshot.class.getDeclaredMethod(
                    "capture", Double.class, Double.class, Double.class, Double.class, Integer.class);
            check("S6 旧五参 capture 工厂已移除", false, "旧工厂仍存在");
        } catch (NoSuchMethodException e) {
            check("S6 旧五参 capture 工厂已移除", true);
        }

        // ============ 3. SparkBridge（playerPing 不再轮询） ============
        System.out.println("---- 3. SparkBridge（假 spark 含 playerPing——应被忽略） ----");
        URL fakeDir = Paths.get("scripts/smoke1677-fakespark").toUri().toURL();
        try (URLClassLoader fakeLoader = new URLClassLoader(
                new URL[]{fakeDir}, Test1678.class.getClassLoader())) {
            Class<?> installer = Class.forName("FakeSpark1677", true, fakeLoader);
            installer.getMethod("install").invoke(null);
            SparkBridge.SparkMetrics m = SparkBridge.INSTANCE.read(fakeLoader);
            check("SB1 假 spark 可读（playerPing 统计不再轮询不报错）", m != null);
            if (m != null) {
                checkEq("SB2 spark tps=300 封顶 20", 20.0, m.getTps());
                checkEq("SB3 spark mspt 正常不受影响", 12.4, m.getMspt());
                checkEq("SB4 spark cpu 正常不受影响", 0.234, m.getCpu());
            }
            try {
                SparkBridge.SparkMetrics.class.getMethod("getPing");
                check("SB5 SparkMetrics 无 getPing（延迟字段下线）", false, "getPing 仍存在");
            } catch (NoSuchMethodException e) {
                check("SB5 SparkMetrics 无 getPing（延迟字段下线）", true);
            }
        }
        check("SB6 无 spark 时 read 回退 null",
                SparkBridge.INSTANCE.read(null) == null);

        // 内置 spark 通道（bundledSparkClassLoader 回归）
        URL fakeDir2 = Paths.get("scripts/smoke1677-fakespark").toUri().toURL();
        try (URLClassLoader fakeLoader2 = new URLClassLoader(
                new URL[]{fakeDir2}, Test1678.class.getClassLoader())) {
            Class<?> installer2 = Class.forName("FakeSpark1677", true, fakeLoader2);
            installer2.getMethod("install").invoke(null);
            ClassLoader resolved = SparkBridge.INSTANCE.bundledSparkClassLoader(fakeLoader2);
            check("SB7 内置通道解析到候选加载器（extraCandidate 命中）",
                    resolved == fakeLoader2, "resolved=" + resolved);
            SparkBridge.SparkMetrics m2 = resolved == null ? null : SparkBridge.INSTANCE.read(resolved);
            check("SB8 内置通道读数（tps 封顶 20 透传）", m2 != null && m2.getTps() == 20.0);
        }
        check("SB9 全候选无 spark 时内置通道返回 null",
                SparkBridge.INSTANCE.bundledSparkClassLoader(null) == null);

        // ============ 4. 渲染器五项卡 ============
        System.out.println("---- 4. 渲染器五项卡（延迟卡移除 + 末行通栏） ----");
        Method buildCards = PerformanceRenderer.class.getDeclaredMethod("buildCards",
                PerformanceRenderer.PerformanceData.class);
        buildCards.setAccessible(true);

        List<?> cards1 = (List<?>) buildCards.invoke(PerformanceRenderer.INSTANCE, mkData(20.0, 40.0, 3));
        checkEq("R1 指标卡恰五项", 5, cards1.size());
        checkEq("R2 第 1 卡 TPS", "TPS（每秒刻数）", labelOf(cards1.get(0)));
        checkEq("R3 第 2 卡 MSPT", "MSPT（每刻耗时）", labelOf(cards1.get(1)));
        checkEq("R4 第 3 卡 内存", "内存占用（JVM 堆）", labelOf(cards1.get(2)));
        checkEq("R5 第 4 卡 CPU", "CPU 占用（进程）", labelOf(cards1.get(3)));
        checkEq("R6 第 5 卡 在线人数（末行通栏）", "在线玩家（人数）", labelOf(cards1.get(4)));
        boolean noLatency = true;
        for (Object card : cards1) {
            if (labelOf(card).contains("延迟")) noLatency = false;
        }
        check("R7 五卡无一含「延迟」标签", noLatency);
        checkEq("R8 online=3 → 状态「在线中」", "在线中", statusOf(cards1.get(4)));
        checkEq("R9 online=3 → 数值 3", "3", valueOf(cards1.get(4)));

        List<?> cards2 = (List<?>) buildCards.invoke(PerformanceRenderer.INSTANCE, mkData(20.0, 40.0, 0));
        checkEq("R10 online=0 → 状态「空服」", "空服", statusOf(cards2.get(4)));

        List<?> cards3 = (List<?>) buildCards.invoke(PerformanceRenderer.INSTANCE, mkData(20.0, 40.0, null));
        checkEq("R11 online=null → 状态「暂不可读」", "暂不可读", statusOf(cards3.get(4)));
        checkEq("R12 online=null → 数值 --", "--", valueOf(cards3.get(4)));

        // TPS 卡满条回归（20 → progress 1.0）
        checkEq("R13 TPS 卡 progress=1.0（20/20）", 1.0, progressOf(cards1.get(0)));

        // ============ 5. 全参渲染 PNG ============
        System.out.println("---- 5. 全参渲染 PNG ----");
        checkEq("H1 五卡画布高度 1034（与六项版一致）", 1034, PerformanceRenderer.INSTANCE.measureHeight());
        byte[] png = PerformanceRenderer.INSTANCE.render(mkData(20.0, 40.0, 5));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        check("H2 PNG 渲染成功 1350x1034", img != null && img.getWidth() == 1350 && img.getHeight() == 1034);
        Files.write(Paths.get("scripts/smoke1678/perf-1678.png"), png);

        // ============ 6. 分发 JAR 内容 ============
        System.out.println("---- 6. 分发 JAR 内容 ----");
        try (ZipFile zf = new ZipFile("build/dist/KERONGPenguin_Spigot-1.6.7.8.jar")) {
            String pluginYml = new String(zf.getInputStream(zf.getEntry("plugin.yml")).readAllBytes(), StandardCharsets.UTF_8);
            check("J1 plugin.yml 版本 1.6.7.8", pluginYml.contains("version: '1.6.7.8'"));
            check("J2 plugin.yml 无 aliases（/hb 已移除）", !pluginYml.contains("aliases"));
            String help = new String(zf.getInputStream(zf.getEntry("Markdown/help.md")).readAllBytes(), StandardCharsets.UTF_8);
            check("J3 help.md 无 /hb", !help.contains("/hb"));
            String cfg = new String(zf.getInputStream(zf.getEntry("config.yml")).readAllBytes(), StandardCharsets.UTF_8);
            check("J4 config.yml 指标注释「固定五项」", cfg.contains("固定五项"));
            check("J5 config.yml 无延迟指标说明行", !cfg.contains("延迟 = 全部在线玩家"));
            check("J6 config.yml 含移除原因说明", cfg.contains("无实际监控意义"));
        }

        System.out.println("\n========== 1.6.7.8 冒烟："
                + passed + " 通过 / " + failed + " 失败 / " + (passed + failed) + " 总计 ==========");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---- 工具 ----

    static PerformanceRenderer.PerformanceData mkData(Double tps, Double mspt, Integer online) {
        return new PerformanceRenderer.PerformanceData(
                "冒烟服务器", "12:34",
                mkSnapshot(tps, mspt, online), null);
    }

    static cn.kerong.bot.PerformanceSnapshot mkSnapshot(
            Double tps, Double mspt, Integer online) {
        Constructor<cn.kerong.bot.PerformanceSnapshot> c;
        try {
            c = cn.kerong.bot.PerformanceSnapshot.class.getDeclaredConstructor(
                    Double.class, Double.class, Long.TYPE, Long.TYPE,
                    Double.class, Integer.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("构造器缺失: " + e);
        }
        c.setAccessible(true);
        try {
            return c.newInstance(tps, mspt, 1024L, 4096L, 0.25, online);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("构造失败: " + e);
        }
    }

    static String labelOf(Object card) throws Exception {
        return (String) fieldOf(card, "label");
    }

    static String statusOf(Object card) throws Exception {
        return (String) fieldOf(card, "statusText");
    }

    static String valueOf(Object card) throws Exception {
        return (String) fieldOf(card, "valueText");
    }

    static Object progressOf(Object card) throws Exception {
        return fieldOf(card, "progress");
    }

    static Object fieldOf(Object card, String name) throws Exception {
        Field f = card.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(card);
    }
}
