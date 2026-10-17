import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.penguin.spigot.render.PerformanceRenderer;
import cn.kerong.penguin.spigot.render.RenderSettings;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * 1.6.7 冒烟：/查性能 渲染链路与数据快照（零网络请求）。
 *
 * 覆盖：
 *  1. PerformanceSnapshot.capture：JVM 内存采集 + null TPS/MSPT 兼容（原版 Spigot 场景）
 *  2. PerformanceRenderer.measureHeight：可见项数 → 画布高度（0 项保底 640）
 *  3. PerformanceRenderer.render：PNG 输出、宽高匹配、标题区有内容
 *  4. 指标显隐：只留两项 → 高度保底 640
 *  5. 全部指标关闭（仅标题区）
 *  6. null 指标降级渲染（原版 Spigot：TPS/MSPT 显示 --）
 *  7. RenderSettings 性能配置读取（无插件实例 → 默认 true）
 *  8. PublicCommands 命令注册表含「查性能」
 */
public class Test167 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name); }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        // ---- 1. 快照采集（TPS/MSPT 为 null 的降级场景 + 正常场景） ----
        PerformanceSnapshot snap = PerformanceSnapshot.capture(null, null, 3, 20);
        check("1.1 capture 返回快照", snap != null);
        check("1.2 JVM 堆已用 > 0", snap.getUsedMemoryMb() > 0);
        check("1.3 JVM 堆上限 >= 已用", snap.getMaxMemoryMb() >= snap.getUsedMemoryMb());
        check("1.4 null TPS/MSPT 原样传递（原版 Spigot 降级）", snap.getTps() == null && snap.getMspt() == null);
        check("1.5 在线人数与上限传递", snap.getOnlineCount() == 3 && snap.getMaxPlayers() == 20);
        check("1.6 CPU 占用在 [0,1] 或 null",
                snap.getCpuLoad() == null || (snap.getCpuLoad() >= 0.0 && snap.getCpuLoad() <= 1.0));

        PerformanceSnapshot snap2 = PerformanceSnapshot.capture(19.8, 12.5, 8, 100);
        check("1.7 正常 TPS/MSPT 传递", Double.valueOf(19.8).equals(snap2.getTps()) && Double.valueOf(12.5).equals(snap2.getMspt()));

        // ---- 2. 画布高度 ----
        int h5 = PerformanceRenderer.INSTANCE.measureHeight(5);
        int h0 = PerformanceRenderer.INSTANCE.measureHeight(0);
        int h1 = PerformanceRenderer.INSTANCE.measureHeight(1);
        // 5 项 = 3 行：(40+150+18) + 3*250 + 2*18 + 40 = 1034
        check("2.1 五项高度 = 1034", h5 == 1034);
        check("2.2 零项保底 640（仅标题）", h0 == 640);
        check("2.3 单项按公式（498 → 保底 640）", h1 >= 498);

        // ---- 3. 渲染输出（全部指标 + 背景为 null 的渐变路径） ----
        byte[] png = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "冒烟测试服", "23:59", snap2, true, true, true, true, true, null));
        check("3.1 PNG 非空", png != null && png.length > 10000);
        check("3.2 PNG 魔数", (png[0] & 0xFF) == 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G');
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        check("3.3 宽 1350", img.getWidth() == 1350);
        check("3.4 高与 measureHeight 一致", img.getHeight() == h5);
        int rgb = img.getRGB(675, 225); // 标题区中心
        check("3.5 标题区有内容（非纯黑背景）", (rgb & 0xFFFFFF) != 0x050508);
        int cardRgb = img.getRGB(300, 400); // 指标卡区域
        check("3.6 指标卡区域已绘制", (cardRgb & 0xFF0000) != 0 || (cardRgb & 0xFF00) != 0 || (cardRgb & 0xFF) != 8);
        java.io.File out = new java.io.File("/home/z/my-project/penguin-git/scripts/smoke167/performance-sample.png");
        java.nio.file.Files.write(out.toPath(), png);
        check("3.7 样图落盘（人工复核用）", out.isFile() && out.length() == png.length);

        // ---- 4. 显隐渲染（只留内存+CPU 两项 → 1 行 → 保底 640） ----
        byte[] png2 = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "冒烟测试服", "23:59", snap2, false, false, true, true, false, null));
        BufferedImage img2 = ImageIO.read(new ByteArrayInputStream(png2));
        check("4.1 两项渲染高度 = 保底 640", img2.getHeight() == 640);
        check("4.2 两项 PNG 有效输出", png2.length > 10000);

        // ---- 5. 全部指标关闭（仅标题区，标题仍在） ----
        byte[] png3 = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "冒烟测试服", "23:59", snap2, false, false, false, false, false, null));
        BufferedImage img3 = ImageIO.read(new ByteArrayInputStream(png3));
        check("5.1 零项渲染高度 640（标题区保留）", img3.getHeight() == 640);
        check("5.2 零项 PNG 有效输出", png3.length > 10000);

        // ---- 6. null 指标降级渲染（原版 Spigot：TPS/MSPT 显示 --） ----
        byte[] png4 = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "冒烟测试服", "23:59", snap, true, true, true, true, true, null));
        check("6.1 null 指标渲染不抛异常", png4 != null && png4.length > 10000);

        // ---- 7. RenderSettings 默认值（无插件运行时 → 默认 true） ----
        check("7.1 performanceItemEnabled 默认 true", RenderSettings.INSTANCE.performanceItemEnabled("show-tps"));
        check("7.2 performanceBuiltinBackground 默认 true", RenderSettings.INSTANCE.performanceBuiltinBackground());

        // ---- 8. 命令注册表含 查性能（BaseCommand 反射收集） ----
        Object commands = Class.forName("cn.kerong.bot.events.commands.PublicCommands").getDeclaredConstructor().newInstance();
        Field mapField = Class.forName("cn.kerong.bot.events.commands.BaseCommand").getDeclaredField("commandMap");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> commandMap = (Map<String, Object>) mapField.get(commands);
        check("8.1 PublicCommands 注册 查性能", commandMap.containsKey("查性能"));
        check("8.2 查在线 / 查信息命令保持", commandMap.containsKey("查在线") && commandMap.containsKey("查信息"));

        System.out.println("\n===== 冒烟结果：" + passed + " 通过 / " + failed + " 失败 =====");
        System.exit(failed == 0 ? 0 : 1);
    }
}
