import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.penguin.spigot.render.PerformanceRenderer;
import cn.kerong.penguin.spigot.stats.SparkBridge;
import cn.kerong.penguin.spigot.stats.TickMonitor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 1.6.7.6 功能冒烟（独立 JVM，headless）。
 *
 * 覆盖：
 *  1. ConfigBrokenRecovery：健康快路径 / 坏文件严格解析 / 缺空格轻量修复 /
 *     结构坏走兜底（`player-events:true` 用户实测样例）
 *  2. TickMonitor：采样前 null / 采样后窗口读数 / stop 重置
 *  3. PerformanceSnapshot：新字段（延迟 / 在线人数）+ capture 工厂回归
 *  4. SparkBridge：playerPing 反射（真实形态假 spark，包私有实现）
 *  5. PerformanceRenderer：六项卡片高度 1034 + 全参渲染 PNG 成功
 *  6. 分发 JAR plugin.yml 版本 1.6.7.6
 *
 * 编译运行（仓库根目录）：
 *   javac -d scripts/smoke1676-fakespark \
 *     $(find scripts/smoke1676-fakespark -name "*.java")
 *   javac -cp build/dist/KERONGPenguin_Spigot-1.6.7.6.jar \
 *     -d scripts/smoke1676-out scripts/smoke1676/Test1676.java
 *   java -Djava.awt.headless=true \
 *     -cp scripts/smoke1676-out:build/dist/KERONGPenguin_Spigot-1.6.7.6.jar:\
 *     scripts/deps-work/paper-api-1.20.4.jar:scripts/deps-work/adventure-api-4.17.0.jar:\
 *     scripts/deps-work/adventure-key-4.17.0.jar:scripts/deps-work/examination-api-1.3.0.jar:\
 *     scripts/deps-work/examination-string-1.3.0.jar:scripts/deps-work/snakeyaml-2.2.jar \
 *     Test1676
 *   （fakespark 不进应用类路径，仅经 URLClassLoader 隔离装载）
 */
public class Test1676 {

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

    static Object instance(Class<?> clazz) throws Exception {
        Field f = clazz.getDeclaredField("INSTANCE");
        f.setAccessible(true);
        return f.get(null);
    }

    static Object invokePrivate(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    public static void main(String[] args) throws Exception {
        File work = Files.createTempDirectory("smoke1676").toFile();

        // ---------- 1. ConfigBrokenRecovery ----------
        Class<?> recovery = Class.forName("cn.kerong.penguin.spigot.manager.ConfigBrokenRecovery");
        Object recoveryObj = instance(recovery);
        Class<?> fileClass = File.class;

        // 1.1 健康文件：tryParse 返回 null
        File healthy = new File(work, "healthy.yml");
        try (FileWriter w = new FileWriter(healthy)) {
            w.write("config-version: 22\nbot:\n  app-id: \"123\"\n  groups: [\"A\", \"B\"]\n");
        }
        Object err1 = invokePrivate(recoveryObj, "tryParse", new Class[]{fileClass}, healthy);
        check("1.1 健康文件严格解析通过（null）", err1 == null, String.valueOf(err1));

        // 1.2 用户实测样例：player-events:true（冒号后无空格）→ 解析失败
        File broken = new File(work, "broken.yml");
        try (FileWriter w = new FileWriter(broken)) {
            w.write("config-version: 22\nplayer-events:true\n  join:\n    enabled: true\n");
        }
        Object err2 = invokePrivate(recoveryObj, "tryParse", new Class[]{fileClass}, broken);
        check("1.2 player-events:true 解析失败（可感知）", err2 != null && !err2.toString().isEmpty());

        // 1.3 缺空格修复：识别 1 行并改写为带空格
        Object repairedPair = invokePrivate(recoveryObj, "tryRepairMissingSpace", new Class[]{fileClass}, broken);
        check("1.3a 缺空格修复产出（Pair）", repairedPair != null);
        if (repairedPair != null) {
            kotlin.Pair<?, ?> pair = (kotlin.Pair<?, ?>) repairedPair;
            check("1.3b 修复行数 = 1", Integer.valueOf(1).equals(pair.getSecond()), String.valueOf(pair.getSecond()));
            List<String> lines = Files.readAllLines(((File) pair.getFirst()).toPath(), StandardCharsets.UTF_8);
            check("1.3c 修复后行带空格", lines.get(1).equals("player-events: true"), lines.get(1));
            // 1.4 修复空格后仍解析失败（标量值下嵌套子键结构非法）→ 走兜底重建
            Object err3 = invokePrivate(recoveryObj, "tryParse", new Class[]{fileClass}, pair.getFirst());
            check("1.4 结构坏修复后仍不可解析（触发兜底重建）", err3 != null);
        }

        // 1.5 纯缺空格笔误（键值本身合法）：修复后可解析
        File typo = new File(work, "typo.yml");
        try (FileWriter w = new FileWriter(typo)) {
            w.write("config-version: 22\nserverName:\"MyServer\"\nbot:\n  app-id: \"123\"\n");
        }
        Object typoPair = invokePrivate(recoveryObj, "tryRepairMissingSpace", new Class[]{fileClass}, typo);
        check("1.5a 纯笔误修复产出", typoPair != null);
        if (typoPair != null) {
            kotlin.Pair<?, ?> pair = (kotlin.Pair<?, ?>) typoPair;
            Object err4 = invokePrivate(recoveryObj, "tryParse", new Class[]{fileClass}, pair.getFirst());
            check("1.5b 修复后解析通过", err4 == null, String.valueOf(err4));
        }

        // 1.6 健康文件无可修内容：修复器返回 null（不动原文件）
        Object noFix = invokePrivate(recoveryObj, "tryRepairMissingSpace", new Class[]{fileClass}, healthy);
        check("1.6 健康文件零修复", noFix == null);

        // ---------- 2. TickMonitor ----------
        Object tick = instance(TickMonitor.class);
        check("2.1 采样前 sampledMspt 为 null", callSampled(tick, "sampledMspt") == null);
        for (int i = 0; i < 80; i++) {
            invokePrivate(tick, "onTick", new Class[]{});
        }
        Object mspt = callSampled(tick, "sampledMspt");
        Object tps = callSampled(tick, "sampledTps");
        check("2.2a 80 次采样后 mspt 可读", mspt != null, String.valueOf(mspt));
        check("2.2b tps 封顶 20.0（快速采样）", Double.parseDouble(String.valueOf(tps)) == 20.0, String.valueOf(tps));
        Method sc = TickMonitor.class.getDeclaredMethod("sampleCount");
        sc.setAccessible(true);
        int count = (Integer) sc.invoke(tick);
        check("2.2c 采样计数 = 79（首次只记时间戳，正确）", count == 79, String.valueOf(count));
        Method stop = TickMonitor.class.getDeclaredMethod("stop");
        stop.setAccessible(true);
        stop.invoke(tick);
        check("2.3 stop 后 mspt 复位 null", callSampled(tick, "sampledMspt") == null);

        // ---------- 3. PerformanceSnapshot ----------
        PerformanceSnapshot full = PerformanceSnapshot.capture(20.0, 41.2, 0.35, 88.5, 12);
        check("3.1a avgPingMillis = 88.5", full.getAvgPingMillis() != null && full.getAvgPingMillis() == 88.5);
        check("3.1b onlinePlayers = 12", full.getOnlinePlayers() != null && full.getOnlinePlayers() == 12);
        check("3.1c cpuOverride 0.35 直采", full.getCpuLoad() != null && Math.abs(full.getCpuLoad() - 0.35) < 1e-9);
        PerformanceSnapshot legacy = PerformanceSnapshot.capture(18.0, 55.0);
        check("3.2 旧工厂延迟/人数为 null", legacy.getAvgPingMillis() == null && legacy.getOnlinePlayers() == null);
        check("3.3 堆内存字段在位（>0）", legacy.getUsedMemoryMb() > 0 && legacy.getMaxMemoryMb() > 0);

        // ---------- 4. SparkBridge.playerPing（真实形态假 spark） ----------
        URL fakeUrl = new File("scripts/smoke1676-fakespark").toURI().toURL();
        URLClassLoader fakeLoader = new URLClassLoader(new URL[]{fakeUrl}, Test1676.class.getClassLoader());
        Class<?> fake = Class.forName("FakeSpark1676", true, fakeLoader);
        fake.getMethod("install").invoke(null);
        Object bridge = instance(SparkBridge.class);
        Method read = SparkBridge.class.getDeclaredMethod("read", ClassLoader.class);
        read.setAccessible(true);
        Object metrics = read.invoke(bridge, fakeLoader);
        check("4.1 假 spark（包私有实现）全指标可读", metrics != null);
        if (metrics != null) {
            Object ping = readField(metrics, "ping");
            Object tpsM = readField(metrics, "tps");
            Object msptM = readField(metrics, "mspt");
            check("4.2 playerPing 读到 12.4ms", ping != null && Math.abs((Double) ping - 12.4) < 1e-9, String.valueOf(ping));
            check("4.3 tps/mspt 基线保持", tpsM != null && msptM != null);
        }

        // ---------- 5. PerformanceRenderer 六项卡 ----------
        Object renderer = instance(PerformanceRenderer.class);
        Method height = PerformanceRenderer.class.getDeclaredMethod("measureHeight");
        height.setAccessible(true);
        int h = (Integer) height.invoke(renderer);
        check("5.1 六项卡高度 1034（3 行 × 250）", h == 1034, String.valueOf(h));

        PerformanceSnapshot snap = new PerformanceSnapshot(19.6, 42.8, 4096L, 8192L, 0.22, 87.0, 12);
        PerformanceRenderer.PerformanceData data = new PerformanceRenderer.PerformanceData(
            "SmokeServer", "12:34", snap, null);
        Method render = PerformanceRenderer.class.getDeclaredMethod("render", PerformanceRenderer.PerformanceData.class);
        render.setAccessible(true);
        byte[] png = (byte[]) render.invoke(renderer, data);
        check("5.2 渲染产出非空 PNG", png != null && png.length > 4 && (png[0] & 0xFF) == 0x89 && png[1] == 'P');
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        check("5.3 解码高度 1034 / 宽 1350", img != null && img.getHeight() == 1034 && img.getWidth() == 1350,
            img == null ? "null" : img.getWidth() + "x" + img.getHeight());

        // ---------- 6. 分发 JAR 版本 ----------
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile("build/dist/KERONGPenguin_Spigot-1.6.7.6.jar")) {
            String yml = new String(zf.getInputStream(zf.getEntry("plugin.yml")).readAllBytes(), StandardCharsets.UTF_8);
            check("6.1 plugin.yml 版本 1.6.7.6", yml.contains("version: '1.6.7.6'"));
        }

        System.out.println("\n========== 1.6.7.6 冒烟：" + passed + " 通过 / " + failed + " 失败 / " + (passed + failed) + " 总计 ==========");
        System.exit(failed == 0 ? 0 : 1);
    }

    static Object readField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    static Object callSampled(Object tick, String name) throws Exception {
        Method m = TickMonitor.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(tick);
    }
}
