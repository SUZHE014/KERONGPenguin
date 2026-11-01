import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * 1.6.8 功能冒烟（独立 JVM，headless）。
 *
 * 本版核心：
 *  ① 时长格式小时化——formatPlayTime（查信息 / 在线排行榜两处实现）此前以
 *    「天」为最大单位（3天5时20分），现改为小时为最大单位（77时20分 /
 *    77小时20分钟）；不足 1 小时仍展示到秒（查信息）/ 分（排行榜）；
 *    formatTodayTime（今日在线时长）本就以小时为最大单位，回归验证。
 *  ② 头像线路顺序——Mojang 官方 API（sessionserver 直查 + api.mojang.com
 *    名字反查）从外置链路首位移到最后兜底，外部多线路（MC-Heads → Minotar
 *    → MinePic → Crafatar）先行。顺序与常量在位由门禁 verify_jar_168 的
 *    AV1-AV6 字节级断言；本冒烟验证重排后链路类初始化不炸（单例可加载）。
 *
 * 覆盖：
 *  TF 组  时长格式行为（反射调用分发 JAR 内真实 object 单例）：
 *   - TF1  3天5时20分（278400s）→ 77时20分（查信息）
 *   - TF2  42分33秒（2553s）→ 42分33秒（不足 1 小时保持秒级）
 *   - TF3  0 → 0分钟；TF4 1 小时整 → 1时；TF5 59 秒 → 59秒
 *   - TF6  400 天（34560000s）→ 9600时（跨天大数值不再出现「天」）
 *   - TF7  输出不含「天」
 *   - TF8  formatTodayTime 回归（77时20分）
 *   - TF9-TF13  排行榜实现：77小时20分钟 / 42分钟 / 暂无记录 / 不足1分钟 /
 *               9900 天 → 237600 小时
 *  AV 组  头像链路完整性：InfoCardAssets 单例加载（重排后类初始化不炸）
 *  LB 组  排行榜渲染端到端（真实字体从 JAR 资源加载）：
 *   - LB1 renderPage 返回 PNG；LB2 PNG 魔数；LB3 画布 1200×640
 *  J 组   JAR 基线：plugin.yml 1.6.8
 *
 * 编译运行（仓库根目录，javac/java 用 tooling JDK21）：
 *   javac -cp build/dist/KERONGPenguin_Spigot-1.6.8.jar \
 *     -d scripts/smoke168-out scripts/smoke168/Test168.java
 *   java -Djava.awt.headless=true \
 *     -cp scripts/smoke168-out:build/dist/KERONGPenguin_Spigot-1.6.8.jar \
 *     scripts.smoke168.Test168 build/dist/KERONGPenguin_Spigot-1.6.8.jar
 */
public class Test168 {

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : " — " + detail));
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : " — " + detail));
        }
    }

    static String fmt(Method m, Object inst, long seconds) throws Exception {
        return (String) m.invoke(inst, seconds);
    }

    public static void main(String[] args) throws Exception {
        String jar = args.length > 0 ? args[0] : "build/dist/KERONGPenguin_Spigot-1.6.8.jar";

        System.out.println("== TF 组：时长格式小时化（真实 object 单例反射）==");
        Class<?> qs = Class.forName("cn.kerong.penguin.spigot.stats.QueryInfoService");
        Object qsInst = qs.getField("INSTANCE").get(null);
        Method qsFmt = qs.getMethod("formatPlayTime", long.class);
        Method qsToday = qs.getMethod("formatTodayTime", long.class);

        String r1 = fmt(qsFmt, qsInst, 278400L); // 3天5时20分
        check("TF1 3天5时20分 → 77时20分（查信息）", "77时20分".equals(r1), "实际 " + r1);
        String r2 = fmt(qsFmt, qsInst, 2553L); // 42分33秒
        check("TF2 42分33秒保持秒级", "42分33秒".equals(r2), "实际 " + r2);
        String r3 = fmt(qsFmt, qsInst, 0L);
        check("TF3 0 → 0分钟", "0分钟".equals(r3), "实际 " + r3);
        String r4 = fmt(qsFmt, qsInst, 3600L);
        check("TF4 1 小时整 → 1时", "1时".equals(r4), "实际 " + r4);
        String r5 = fmt(qsFmt, qsInst, 59L);
        check("TF5 59 秒 → 59秒", "59秒".equals(r5), "实际 " + r5);
        String r6 = fmt(qsFmt, qsInst, 400L * 86400L);
        check("TF6 400 天 → 9600时", "9600时".equals(r6), "实际 " + r6);
        check("TF7 输出不含「天」", !r1.contains("天") && !r6.contains("天"), "r1=" + r1 + " r6=" + r6);
        String r8 = fmt(qsToday, qsInst, 278400L);
        check("TF8 今日在线回归（本就小时制）→ 77时20分", "77时20分".equals(r8), "实际 " + r8);

        Class<?> lb = Class.forName("cn.kerong.penguin.spigot.render.LeaderboardRenderer");
        Object lbInst = lb.getField("INSTANCE").get(null);
        Method lbFmt = lb.getMethod("formatPlayTime", long.class);
        String l1 = fmt(lbFmt, lbInst, 278400L);
        check("TF9 排行榜 3天5小时20分钟 → 77小时20分钟", "77小时20分钟".equals(l1), "实际 " + l1);
        String l2 = fmt(lbFmt, lbInst, 2553L);
        check("TF10 排行榜 42分33秒 → 42分钟", "42分钟".equals(l2), "实际 " + l2);
        String l3 = fmt(lbFmt, lbInst, 0L);
        check("TF11 排行榜 0 → 暂无记录", "暂无记录".equals(l3), "实际 " + l3);
        String l4 = fmt(lbFmt, lbInst, 30L);
        check("TF12 排行榜 30 秒 → 不足1分钟（60 秒 = 1分钟整）", "不足1分钟".equals(l4), "实际 " + l4);
        String l4b = fmt(lbFmt, lbInst, 60L);
        check("TF12b 排行榜 60 秒 → 1分钟", "1分钟".equals(l4b), "实际 " + l4b);
        String l5 = fmt(lbFmt, lbInst, 855360000L); // 9900 天
        check("TF13 排行榜 9900 天 → 237600小时", "237600小时".equals(l5), "实际 " + l5);

        System.out.println("== AV 组：头像链路完整性 ==");
        Class<?> assets = Class.forName("cn.kerong.penguin.spigot.render.InfoCardAssets");
        Object assetsInst = assets.getField("INSTANCE").get(null);
        check("AV1 InfoCardAssets 单例可加载（重排后初始化不炸）", assetsInst != null, "INSTANCE=" + assetsInst);
        // 线路顺序与四条外部 URL / Mojang官方API 标签在位：门禁 AV1-AV6 字节级断言
        Method mojangInCooldown = assets.getDeclaredMethod("mojangInCooldown");
        mojangInCooldown.setAccessible(true);
        check("AV2 冷却判定方法可反射调用", mojangInCooldown.invoke(assetsInst) instanceof Boolean, "返回 Boolean");

        System.out.println("== LB 组：排行榜渲染端到端 ==");
        Class<?> entryCls = Class.forName(
                "cn.kerong.penguin.spigot.render.LeaderboardRenderer$LeaderEntry");
        Class<?> dataCls = Class.forName(
                "cn.kerong.penguin.spigot.render.LeaderboardRenderer$LeaderboardData");
        Object e1 = entryCls.getConstructor(int.class, String.class, long.class,
                BufferedImage.class, String.class).newInstance(1, "TestPlayer", 278400L, null, null);
        Object e2 = entryCls.getConstructor(int.class, String.class, long.class,
                BufferedImage.class, String.class).newInstance(2, "Second", 2553L, null, null);
        Object data = dataCls.getConstructor(String.class, String.class, List.class,
                long.class, int.class, BufferedImage.class, String.class, String.class)
                .newInstance("冒烟服务器", "12:00", List.of(e1, e2), 2L, 15, null,
                        "在线排行榜", "总在线时长");
        Method render = lb.getMethod("renderPage", dataCls, boolean.class);
        byte[] png = (byte[]) render.invoke(lbInst, data, Boolean.TRUE);
        check("LB1 renderPage 返回 PNG 字节", png != null && png.length > 10_000,
                png == null ? "null" : png.length + " 字节");
        check("LB2 PNG 魔数", png != null && png.length > 4
                && png[0] == (byte) 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G', "PNG");
        BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
        check("LB3 画布 1200×640（2 行首页）",
                img != null && img.getWidth() == 1200 && img.getHeight() == 640,
                img == null ? "解码失败" : img.getWidth() + "×" + img.getHeight());

        System.out.println("== J 组：JAR 基线 ==");
        try (ZipFile zf = new ZipFile(jar)) {
            String yml = new String(
                    zf.getInputStream(zf.getEntry("plugin.yml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            check("J1 plugin.yml 1.6.8", yml.contains("version: '1.6.8'"), "plugin.yml");
        }

        System.out.println();
        System.out.println("共 " + (pass + fail) + " 项，通过 " + pass + "，失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("smoke168 全部通过");
    }
}
