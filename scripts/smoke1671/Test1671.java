import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.penguin.spigot.render.PerformanceRenderer;
import cn.kerong.penguin.spigot.render.RenderSettings;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * 1.6.7.1 冒烟：/查性能 TPS/MSPT 分层反射读取 + 固定四项渲染（零网络请求）。
 *
 * 覆盖：
 *  1. PerformanceSnapshot.capture（2 参新签名）：JVM 内存采集 + null TPS/MSPT 兼容
 *  2. 分层反射读取器（1.6.7.1 修复核心）：
 *     - Paper 系 API 层：getTPS() + getAverageTickTime()
 *     - 纯 Spigot：CraftServer#getServer() 跳板 → recentTps 公有字段 + tickTimes 私有字段
 *     - NeoForge 1.21.1：getAverageTickTimeNanos()（1.6.7 反射错名导致读不到的元凶）
 *     - 什么都没有的对象 → 双 null 优雅降级
 *  3. tpsFromMspt 换算兜底（Spark 同款算法，封顶 20）
 *  4. PerformanceRenderer：固定四项指标（无显隐参数）、高度 766、PNG 输出
 *  5. null 指标降级渲染（显示 --）
 *  6. RenderSettings：仅 performanceBuiltinBackground（performanceItemEnabled 已删）
 *  7. PublicCommands 命令注册表含「查性能」
 */
public class Test1671 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name); }
    }

    // ---------- 假服务器类（模拟各核心的指标暴露面，纯反射可读） ----------

    /** 模拟 Paper / Purpur 的 Bukkit Server：API 接口直接给 TPS / MSPT。 */
    public static class FakePaperApi {
        public double[] getTPS() { return new double[]{19.5, 19.2, 18.8}; }
        public double getAverageTickTime() { return 12.3; }
        public Object getServer() { return null; } // API 层命中即止（跳板存在但不会走到）
    }

    /** 模拟纯 Spigot 的内部 MinecraftServer：recentTps 公有 + tickTimes 私有（Spigot 1.20.4 映射名）。 */
    public static class FakeSpigotCore {
        public final double[] recentTps = {18.4, 18.0, 17.5};
        private final long[] tickTimes = makeTickTimes(41_500_000L);
        static long[] makeTickTimes(long nanos) {
            long[] arr = new long[100];
            java.util.Arrays.fill(arr, nanos);
            return arr;
        }
    }

    /** 模拟纯 Spigot 的 CraftServer：只有 getServer() 跳板（API 层无任何指标方法）。 */
    public static class FakeSpigotCraftServer {
        public Object getServer() { return new FakeSpigotCore(); }
    }

    /** 模拟 NeoForge 1.21.1 的 MinecraftServer（Mojang 映射）：公有 getAverageTickTimeNanos + 私有 tickTimesNanos，无 TPS 字段。 */
    public static class FakeNeoForgeCore {
        public long getAverageTickTimeNanos() { return 42_000_000L; }
        private final long[] tickTimesNanos = FakeSpigotCore.makeTickTimes(60_000_000L);
    }

    /** 模拟什么都读不到的核心：优雅降级双 null。 */
    public static class FakeBareCore { }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        // ---- 1. 快照采集（2 参新签名） ----
        PerformanceSnapshot snap = PerformanceSnapshot.capture(null, null);
        check("1.1 capture 返回快照", snap != null);
        check("1.2 JVM 堆已用 > 0", snap.getUsedMemoryMb() > 0);
        check("1.3 JVM 堆上限 >= 已用", snap.getMaxMemoryMb() >= snap.getUsedMemoryMb());
        check("1.4 null TPS/MSPT 原样传递（降级场景）", snap.getTps() == null && snap.getMspt() == null);
        check("1.5 CPU 占用在 [0,1] 或 null",
                snap.getCpuLoad() == null || (snap.getCpuLoad() >= 0.0 && snap.getCpuLoad() <= 1.0));

        PerformanceSnapshot snap2 = PerformanceSnapshot.capture(19.8, 12.5);
        check("1.6 正常 TPS/MSPT 传递", Double.valueOf(19.8).equals(snap2.getTps()) && Double.valueOf(12.5).equals(snap2.getMspt()));

        // ---- 2. 分层反射读取器（1.6.7.1 修复核心） ----
        // 2a. Paper 系 API 层直接命中
        Double paperTps = PerformanceSnapshot.readTps(new FakePaperApi());
        Double paperMspt = PerformanceSnapshot.readMspt(new FakePaperApi());
        check("2.1 Paper API 层 getTPS()[0]", paperTps != null && Math.abs(paperTps - 19.5) < 1e-9);
        check("2.2 Paper API 层 getAverageTickTime()", paperMspt != null && Math.abs(paperMspt - 12.3) < 1e-9);

        // 2b. 纯 Spigot：getServer() 跳板 → recentTps 公有字段 + tickTimes 私有字段
        Double spigotTps = PerformanceSnapshot.readTps(new FakeSpigotCraftServer());
        Double spigotMspt = PerformanceSnapshot.readMspt(new FakeSpigotCraftServer());
        check("2.3 Spigot 跳板 → recentTps 公有字段", spigotTps != null && Math.abs(spigotTps - 18.4) < 1e-9);
        check("2.4 Spigot 跳板 → tickTimes 私有字段平均（41.5ms）",
                spigotMspt != null && Math.abs(spigotMspt - 41.5) < 1e-6);

        // 2c. NeoForge 1.21.1：getAverageTickTimeNanos（公有方法优先于字段）
        Double nfMspt = PerformanceSnapshot.readMspt(new FakeNeoForgeCore());
        check("2.5 NeoForge getAverageTickTimeNanos() → 42.0ms（1.6.7 错名读不到的修复）",
                nfMspt != null && Math.abs(nfMspt - 42.0) < 1e-9);
        Double nfTps = PerformanceSnapshot.readTps(new FakeNeoForgeCore());
        check("2.6 NeoForge 无 TPS 字段 → readTps null", nfTps == null);

        // 2d. 什么都没有 → 双 null
        check("2.7 裸核心 readTps → null", PerformanceSnapshot.readTps(new FakeBareCore()) == null);
        check("2.8 裸核心 readMspt → null", PerformanceSnapshot.readMspt(new FakeBareCore()) == null);
        check("2.9 null 对象不抛异常", PerformanceSnapshot.readTps(null) == null &&
                PerformanceSnapshot.readMspt(null) == null);

        // ---- 3. tpsFromMspt 换算兜底（Spark 同款：min(20, 1000/mspt)） ----
        check("3.1 tpsFromMspt(42.0) 封顶 20.0", Double.valueOf(20.0).equals(PerformanceSnapshot.tpsFromMspt(42.0)));
        check("3.2 tpsFromMspt(80.0) = 12.5", Math.abs(PerformanceSnapshot.tpsFromMspt(80.0) - 12.5) < 1e-9);
        check("3.3 tpsFromMspt(null) = null", PerformanceSnapshot.tpsFromMspt(null) == null);
        check("3.4 tpsFromMspt(0.0) = null", PerformanceSnapshot.tpsFromMspt(0.0) == null);

        // ---- 4. 渲染输出（固定四项指标，无显隐参数） ----
        int h = PerformanceRenderer.INSTANCE.measureHeight();
        // 2 行：(40+150+18) + 2*250 + 1*18 + 40 = 766
        check("4.1 固定四项高度 = 766", h == 766);
        byte[] png = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "冒烟测试服", "23:59", snap2, null));
        check("4.2 PNG 非空", png != null && png.length > 10000);
        check("4.3 PNG 魔数", (png[0] & 0xFF) == 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G');
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        check("4.4 宽 1350", img.getWidth() == 1350);
        check("4.5 高与 measureHeight 一致（766）", img.getHeight() == h);
        int rgb = img.getRGB(675, 225); // 标题区中心
        check("4.6 标题区有内容（非纯黑背景）", (rgb & 0xFFFFFF) != 0x050508);
        int cardRgb = img.getRGB(300, 400); // 指标卡区域
        check("4.7 指标卡区域已绘制", (cardRgb & 0xFF0000) != 0 || (cardRgb & 0xFF00) != 0 || (cardRgb & 0xFF) != 8);
        java.io.File out = new java.io.File("/home/z/my-project/penguin-git/scripts/smoke1671/performance-sample.png");
        java.nio.file.Files.write(out.toPath(), png);
        check("4.8 样图落盘（人工复核用）", out.isFile() && out.length() == png.length);

        // ---- 5. null 指标降级渲染（读不到的核心：TPS/MSPT 显示 --） ----
        byte[] png4 = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "冒烟测试服", "23:59", snap, null));
        check("5.1 null 指标渲染不抛异常", png4 != null && png4.length > 10000);
        BufferedImage img4 = ImageIO.read(new ByteArrayInputStream(png4));
        check("5.2 null 指标渲染高度仍 766（四卡都在，显示 --）", img4.getHeight() == 766);

        // ---- 6. RenderSettings：显隐函数已删、背景开关默认 true ----
        check("6.1 performanceBuiltinBackground 默认 true", RenderSettings.INSTANCE.performanceBuiltinBackground());
        boolean itemEnabledGone = false;
        try {
            RenderSettings.class.getDeclaredMethod("performanceItemEnabled", String.class);
        } catch (NoSuchMethodException expected) {
            itemEnabledGone = true;
        }
        check("6.2 performanceItemEnabled 方法已删除", itemEnabledGone);

        // ---- 7. 命令注册表含 查性能（BaseCommand 反射收集） ----
        Object commands = Class.forName("cn.kerong.bot.events.commands.PublicCommands").getDeclaredConstructor().newInstance();
        Field mapField = Class.forName("cn.kerong.bot.events.commands.BaseCommand").getDeclaredField("commandMap");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> commandMap = (Map<String, Object>) mapField.get(commands);
        check("7.1 PublicCommands 注册 查性能", commandMap.containsKey("查性能"));
        check("7.2 查在线 / 查信息命令保持", commandMap.containsKey("查在线") && commandMap.containsKey("查信息"));

        System.out.println("\n===== 冒烟结果：" + passed + " 通过 / " + failed + " 失败 =====");
        System.exit(failed == 0 ? 0 : 1);
    }
}
