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
 * 1.6.7.7 功能冒烟（独立 JVM，headless）。
 *
 * 覆盖：
 *  1. TPS 物理上限 20 三层封顶：
 *     - capture 工厂：300 → 20、18.2 → 18.2、null → null
 *     - readTps 反射链：getTPS() 返回 {300, 20, 20} → 20.0
 *     - tpsFromMspt 回归：3.3ms → 20.0（既有 cap）
 *  2. SparkBridge pollTps 封顶：真实形态假 spark（包私有实现 + 独立
 *     URLClassLoader）tps poll 返回 300.0 → read().tps == 20.0；
 *     mspt/cpu/ping 正常值不受封顶影响（12.4 / 0.234 / 42.5）
 *  3. 渲染器延迟卡三态：ping=null+online=3 →「暂不可读」；
 *     ping=null+online=0 →「暂无玩家」；ping=0+online=1 →「流畅 0 ms」
 *  4. 全参渲染 PNG 成功 + 六卡高度 1034 回归
 *  5. 分发 JAR：plugin.yml 1.6.7.7 / 无 aliases；help.md 无 /hb
 *
 * 编译运行（仓库根目录）：
 *   javac -d scripts/smoke1677-fakespark \
 *     $(find scripts/smoke1677-fakespark -name "*.java")
 *   javac -cp build/dist/KERONGPenguin_Spigot-1.6.7.7.jar \
 *     -d scripts/smoke1677-out scripts/smoke1677/Test1677.java
 *   java -Djava.awt.headless=true \
 *     -cp scripts/smoke1677-out:build/dist/KERONGPenguin_Spigot-1.6.7.7.jar:\
 *     scripts/deps-work/paper-api-1.20.4.jar:scripts/deps-work/adventure-api-4.17.0.jar:\
 *     scripts/deps-work/adventure-key-4.17.0.jar:scripts/deps-work/examination-api-1.3.0.jar:\
 *     scripts/deps-work/examination-string-1.3.0.jar:scripts/deps-work/snakeyaml-2.2.jar \
 *     Test1677
 *   （fakespark 不进应用类路径，仅经 URLClassLoader 隔离装载）
 */
public class Test1677 {

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
        // ============ 1. TPS 三层封顶 ============
        System.out.println("---- 1. TPS 物理上限 20 三层封顶 ----");

        // capture 工厂
        PerformanceSnapshot s = PerformanceSnapshot.capture(300.0, 50.0, null, null, 3);
        checkEq("TC1 capture(300) 封顶到 20", 20.0, s.getTps());
        s = PerformanceSnapshot.capture(18.2, 51.0, null, null, 3);
        checkEq("TC2 capture(18.2) 正常值不变", 18.2, s.getTps());
        s = PerformanceSnapshot.capture(null, 51.0, null, null, 3);
        check("TC3 capture(null) 保持 null", s.getTps() == null);

        // readTps 反射链：getTPS() 数组
        checkEq("TC4 readTps(getTPS={300,20,20}) 封顶 20",
                20.0, PerformanceSnapshot.readTps(new FakeServer(new double[]{300.0, 20.0, 20.0})));
        checkEq("TC5 readTps(getTPS={19.5,...}) 正常",
                19.5, PerformanceSnapshot.readTps(new FakeServer(new double[]{19.5, 19.0, 18.0})));
        // 装箱数组分支
        checkEq("TC6 readTps 装箱 Double[]{400,...} 封顶 20",
                20.0, PerformanceSnapshot.readTps(new BoxedServer(new Double[]{400.0, 20.0, 20.0})));
        // recentTps 字段分支（NMS 跳板前一层）
        checkEq("TC7 readTps(recentTps={320,...}) 封顶 20",
                20.0, PerformanceSnapshot.readTps(new RecentTpsServer(new double[]{320.0, 20.0, 20.0})));
        // 无任何指标
        check("TC8 readTps(无指标对象) null",
                PerformanceSnapshot.readTps(new NoTpsServer()) == null);

        // tpsFromMspt 既有 cap 回归
        checkEq("TC9 tpsFromMspt(3.3) 封顶 20", 20.0, PerformanceSnapshot.tpsFromMspt(3.3));
        checkEq("TC10 tpsFromMspt(55.6) 正常换算", 1000.0 / 55.6, PerformanceSnapshot.tpsFromMspt(55.6));

        // ============ 2. SparkBridge pollTps 封顶 ============
        System.out.println("---- 2. SparkBridge pollTps 封顶（真实形态假 spark） ----");
        URL fakeDir = Paths.get("scripts/smoke1677-fakespark").toUri().toURL();
        try (URLClassLoader fakeLoader = new URLClassLoader(
                new URL[]{fakeDir}, Test1677.class.getClassLoader())) {
            Class<?> installer = Class.forName("FakeSpark1677", true, fakeLoader);
            installer.getMethod("install").invoke(null);
            SparkBridge.SparkMetrics m = SparkBridge.INSTANCE.read(fakeLoader);
            check("SB1 假 spark 可读", m != null);
            if (m != null) {
                checkEq("SB2 spark tps=300 封顶 20", 20.0, m.getTps());
                checkEq("SB3 spark mspt 正常不受影响", 12.4, m.getMspt());
                checkEq("SB4 spark cpu 正常不受影响", 0.234, m.getCpu());
                checkEq("SB5 spark ping 正常不受影响", 42.5, m.getPing());
            }
        }
        // spark 未装载（URLClassLoader 丢弃后）——本类 loader 查不到
        check("SB6 无 spark 时 read 回退 null（修复后链路健康）",
                SparkBridge.INSTANCE.read(null) == null);

        // ---- 内置 spark 通道（bundledSparkClassLoader，1.6.7.7 重做） ----
        // 场景：Paper 1.21+ 系核心把 spark 挂在系统类路径（不注册插件表）——
        // 冒烟以独立 URLClassLoader 模拟「服务器加载器里有 spark」
        URL fakeDir2 = Paths.get("scripts/smoke1677-fakespark").toUri().toURL();
        try (URLClassLoader fakeLoader2 = new URLClassLoader(
                new URL[]{fakeDir2}, Test1677.class.getClassLoader())) {
            Class<?> installer2 = Class.forName("FakeSpark1677", true, fakeLoader2);
            installer2.getMethod("install").invoke(null);
            ClassLoader resolved = SparkBridge.INSTANCE.bundledSparkClassLoader(fakeLoader2);
            check("SB7 内置通道解析到候选加载器（extraCandidate 命中）",
                    resolved == fakeLoader2, "resolved=" + resolved);
            SparkBridge.SparkMetrics m2 = resolved == null ? null : SparkBridge.INSTANCE.read(resolved);
            check("SB8 内置通道读数（tps 封顶 20 透传）", m2 != null && m2.getTps() == 20.0);
        }
        // 应用类路径无 spark（fakespark 只在隔离 loader 里）→ 内置通道 null
        check("SB9 全候选无 spark 时内置通道返回 null",
                SparkBridge.INSTANCE.bundledSparkClassLoader(null) == null);

        // ============ 3. 渲染器延迟卡三态 ============
        System.out.println("---- 3. 渲染器延迟卡三态（反射 buildCards） ----");
        Method buildCards = PerformanceRenderer.class.getDeclaredMethod("buildCards",
                PerformanceRenderer.PerformanceData.class);
        buildCards.setAccessible(true);

        PerformanceRenderer.PerformanceData d1 = mkData(20.0, 40.0, null, 3);
        List<?> cards1 = (List<?>) buildCards.invoke(PerformanceRenderer.INSTANCE, d1);
        Object pingCard1 = cards1.get(4); // 第五张：延迟卡
        checkEq("PG1 ping=null+online=3 → 状态「暂不可读」", "暂不可读", statusOf(pingCard1));
        checkEq("PG2 副说明指向读不到", "有玩家在线但延迟读不到（核心不支持 getPing）", noteOf(pingCard1));
        checkEq("PG3 数值 --", "--", valueOf(pingCard1));

        PerformanceRenderer.PerformanceData d2 = mkData(20.0, 40.0, null, 0);
        List<?> cards2 = (List<?>) buildCards.invoke(PerformanceRenderer.INSTANCE, d2);
        Object pingCard2 = cards2.get(4);
        checkEq("PG4 ping=null+online=0 → 状态「暂无玩家」", "暂无玩家", statusOf(pingCard2));
        checkEq("PG5 副说明正常文案", "全部在线玩家网络延迟平均 · 毫秒", noteOf(pingCard2));

        PerformanceRenderer.PerformanceData d3 = mkData(20.0, 40.0, 0.0, 1);
        List<?> cards3 = (List<?>) buildCards.invoke(PerformanceRenderer.INSTANCE, d3);
        Object pingCard3 = cards3.get(4);
        checkEq("PG6 ping=0（合法）→ 状态「流畅」", "流畅", statusOf(pingCard3));
        checkEq("PG7 ping=0 → 数值 0 ms", "0 ms", valueOf(pingCard3));

        PerformanceRenderer.PerformanceData d4 = mkData(20.0, 40.0, 95.0, 2);
        List<?> cards4 = (List<?>) buildCards.invoke(PerformanceRenderer.INSTANCE, d4);
        Object pingCard4 = cards4.get(4);
        checkEq("PG8 ping=95 → 状态「偏高」", "偏高", statusOf(pingCard4));

        // TPS 卡满条回归（20 → progress 1.0）
        Object tpsCard = cards4.get(0);
        checkEq("PG9 TPS 卡 progress=1.0（20/20）", 1.0, progressOf(tpsCard));

        // ============ 4. 全参渲染 PNG ============
        System.out.println("---- 4. 全参渲染 PNG ----");
        checkEq("H1 六卡画布高度 1034", 1034, PerformanceRenderer.INSTANCE.measureHeight());
        byte[] png = PerformanceRenderer.INSTANCE.render(mkData(20.0, 40.0, 42.5, 5));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        check("H2 PNG 渲染成功 1350x1034", img != null && img.getWidth() == 1350 && img.getHeight() == 1034);
        Files.write(Paths.get("scripts/smoke1677/perf-1677.png"), png);

        // ============ 5. 分发 JAR 内容 ============
        System.out.println("---- 5. 分发 JAR 内容 ----");
        try (ZipFile zf = new ZipFile("build/dist/KERONGPenguin_Spigot-1.6.7.7.jar")) {
            String pluginYml = new String(zf.getInputStream(zf.getEntry("plugin.yml")).readAllBytes(), StandardCharsets.UTF_8);
            check("J1 plugin.yml 版本 1.6.7.7", pluginYml.contains("version: '1.6.7.7'"));
            check("J2 plugin.yml 无 aliases（/hb 已移除）", !pluginYml.contains("aliases"));
            String help = new String(zf.getInputStream(zf.getEntry("Markdown/help.md")).readAllBytes(), StandardCharsets.UTF_8);
            check("J3 help.md 无 /hb", !help.contains("/hb"));
            check("J4 help.md 指引 /kerong help", help.contains("/kerong help"));
            String cfg = new String(zf.getInputStream(zf.getEntry("config.yml")).readAllBytes(), StandardCharsets.UTF_8);
            check("J5 config.yml 重连指引 /kerong chs", cfg.contains("/kerong chs"));
        }

        System.out.println("\n========== 1.6.7.7 冒烟："
                + passed + " 通过 / " + failed + " 失败 / " + (passed + failed) + " 总计 ==========");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---- 工具 ----

    static PerformanceRenderer.PerformanceData mkData(
            Double tps, Double mspt, Double ping, Integer online) {
        return new PerformanceRenderer.PerformanceData(
                "冒烟服务器", "12:34",
                mkSnapshot(tps, mspt, ping, online), null);
    }

    static cn.kerong.bot.PerformanceSnapshot mkSnapshot(
            Double tps, Double mspt, Double ping, Integer online) {
        Constructor<cn.kerong.bot.PerformanceSnapshot> c;
        try {
            c = cn.kerong.bot.PerformanceSnapshot.class.getDeclaredConstructor(
                    Double.class, Double.class, Long.TYPE, Long.TYPE,
                    Double.class, Double.class, Integer.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("构造器缺失: " + e);
        }
        c.setAccessible(true);
        try {
            return c.newInstance(tps, mspt, 1024L, 4096L, 0.25, ping, online);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("构造失败: " + e);
        }
    }

    static String statusOf(Object card) throws Exception {
        return (String) fieldOf(card, "statusText");
    }

    static String noteOf(Object card) throws Exception {
        return (String) fieldOf(card, "note");
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
