import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 1.7.6 覆盖轮冒烟：StatsFileService（总在线时长存档源修复）端到端。
 * 在纯 JVM 里以工作目录为「服务器根」构造 world/stats/<uuid>.json 与
 * usercache.json，真实走 readPlayTime 两级 UUID 查找链：
 *   A. formatPlayText 统一「X时X分」格式（35时20分 / 0分钟 / 不足一分钟）
 *   B. 绑定 UUID 有统计文件 → 直接命中（来源=绑定记录）
 *   C. 绑定 UUID 无文件 → usercache.json 按名解析服务器 UUID → 命中
 *      （来源=usercache.json；模拟离线服改名 / 老绑定数据 UUID 对不上）
 *   D. usercache 同名多条（改名历史）→ 取 expiresOn 最新的一条
 *   E. 全部 miss（无统计文件无 usercache 记录）→ null（回退链由调用方接管）
 *   F. play_time=0 → 按获取不到处理（null）
 *   G. 旧键 play_one_minute（1.12- 世界）兼容读取
 * 注：Bukkit.getWorlds() 在纯 JVM 抛异常 → statsDirectory 回退工作目录
 * world/stats、serverRoot 回退 CWD——恰好构造真实文件系统场景。
 */
public class Smoke176o2 {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    public static void main(String[] args) throws Exception {
        File root = new File(".").getAbsoluteFile();
        File stats = new File(root, "world/stats");
        stats.mkdirs();

        Class<?> cls = Class.forName("cn.kerong.penguin.spigot.stats.StatsFileService");
        Object svc = null; // Kotlin object：取 INSTANCE
        for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
            if (f.getName().equals("INSTANCE")) { f.setAccessible(true); svc = f.get(null); }
        }
        check("S0 单例 INSTANCE 获取", svc != null, "no INSTANCE");

        // ---- A. formatPlayText ----
        Method fmt = cls.getMethod("formatPlayText", long.class);
        check("A1 72000*35+24000 刻 → 35时20分（24000 刻 = 20 分）",
              "35时20分".equals(fmt.invoke(svc, 72000L * 35 + 24000)),
              String.valueOf(fmt.invoke(svc, 72000L * 35 + 24000)));
        check("A2 0 刻 → 0分钟", "0分钟".equals(fmt.invoke(svc, 0L)), String.valueOf(fmt.invoke(svc, 0L)));
        Object oneMin = fmt.invoke(svc, 1199L);
        check("A3 59 秒 → 0分钟（不足一分）", "0分钟".equals(oneMin), String.valueOf(oneMin));
        check("A4 7200 刻 → 6分（无小时带分）", "6分".equals(fmt.invoke(svc, 7200L)),
              String.valueOf(fmt.invoke(svc, 7200L)));

        // ---- 构造玩家 ----
        String player = "SmokeTester";
        UUID boundUuid = UUID.randomUUID();      // 绑定记录（插件口径）
        UUID serverUuid = UUID.randomUUID();     // 服务器口径（与绑定不同）
        UUID oldUuid = UUID.randomUUID();        // 改名前的旧记录（expiresOn 较旧）

        Method read = cls.getMethod("readPlayTime", String.class, java.util.UUID.class);
        Class<?> resultCls = Class.forName("cn.kerong.penguin.spigot.stats.StatsFileService$PlayTimeResult");

        // ---- C. 绑定 UUID 无文件 → usercache 解析（先写 usercache 再测）----
        StringBuilder uc = new StringBuilder("[");
        uc.append(String.format("{\"name\":\"%s\",\"uuid\":\"%s\",\"expiresOn\":\"2026-01-01 00:00:00 +0800\"}", "OtherGuy", oldUuid));
        uc.append(",");
        uc.append(String.format("{\"name\":\"%s\",\"uuid\":\"%s\",\"expiresOn\":\"2025-01-01 00:00:00 +0800\"}", player.toLowerCase(), oldUuid));
        uc.append(",");
        uc.append(String.format("{\"name\":\"%s\",\"uuid\":\"%s\",\"expiresOn\":\"2026-06-01 00:00:00 +0800\"}", player, serverUuid));
        uc.append("]");
        Files.write(new File(root, "usercache.json").toPath(), uc.toString().getBytes(StandardCharsets.UTF_8));
        // 服务器 UUID 的统计文件（play_time = 35 时 20 分 + 30 秒的刻数）
        long ticks = 72000L * 35 + 12000 * 20 + 600;
        String statJson = String.format(
            "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":%d,\"minecraft:walk_one_cm\":123}}}", ticks);
        Files.write(new File(stats, serverUuid + ".json").toPath(), statJson.getBytes(StandardCharsets.UTF_8));

        Object rC = read.invoke(svc, player, boundUuid);
        check("C1 绑定 UUID 无文件 → usercache UUID 命中（非 null）", rC != null, "result null");
        if (rC != null) {
            check("C2 命中刻数正确", ((Long) resultCls.getMethod("getTicks").invoke(rC)) == ticks,
                  String.valueOf(resultCls.getMethod("getTicks").invoke(rC)));
            check("C3 UUID 来源 = usercache.json",
                  "usercache.json".equals(resultCls.getMethod("getUuidSource").invoke(rC)),
                  String.valueOf(resultCls.getMethod("getUuidSource").invoke(rC)));
            check("C4 命中 UUID = 服务器口径",
                  serverUuid.equals(resultCls.getMethod("getUuid").invoke(rC)),
                  String.valueOf(resultCls.getMethod("getUuid").invoke(rC)));
        }

        // ---- D. 同名两条（小写旧 + 原样新）→ 取 expiresOn 最新 ----
        check("D1 命中的是 expiresOn 最新的服务器 UUID（大小写混合名已按忽略大小写解析）",
              rC != null && serverUuid.equals(resultCls.getMethod("getUuid").invoke(rC)), "");

        // ---- B. 绑定 UUID 也有文件 → 直接命中（优先绑定记录）----
        long boundTicks = 72000L * 10;
        String boundJson = String.format(
            "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":%d}}}", boundTicks);
        Files.write(new File(stats, boundUuid + ".json").toPath(), boundJson.getBytes(StandardCharsets.UTF_8));
        Object rB = read.invoke(svc, player, boundUuid);
        check("B1 绑定 UUID 有文件 → 直接命中", rB != null, "null");
        check("B2 来源 = 绑定记录",
              rB != null && "绑定记录".equals(resultCls.getMethod("getUuidSource").invoke(rB)),
              rB == null ? "" : String.valueOf(resultCls.getMethod("getUuidSource").invoke(rB)));

        // ---- F. play_time=0 → null ----
        UUID zeroUuid = UUID.randomUUID();
        Files.write(new File(stats, zeroUuid + ".json").toPath(),
            "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":0}}}".getBytes(StandardCharsets.UTF_8));
        Object rF = read.invoke(svc, "ZeroGuy", zeroUuid);
        check("F1 play_time=0 → 获取不到（null）", rF == null, String.valueOf(rF));

        // ---- G. 旧键 play_one_minute ----
        UUID legacyUuid = UUID.randomUUID();
        Files.write(new File(stats, legacyUuid + ".json").toPath(),
            "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_one_minute\":144000}}}".getBytes(StandardCharsets.UTF_8));
        Object rG = read.invoke(svc, "LegacyGuy", legacyUuid);
        check("G1 play_one_minute 旧键兼容（144000 刻 = 2 时）",
              rG != null && ((Long) resultCls.getMethod("getTicks").invoke(rG)) == 144000L,
              rG == null ? "null" : String.valueOf(resultCls.getMethod("getTicks").invoke(rG)));

        // ---- E. 全 miss → null ----
        Object rE = read.invoke(svc, "Nobody", null);
        check("E1 无绑定 UUID 且 usercache 无记录 → null", rE == null, String.valueOf(rE));

        // ---- 目录缓存复位（隔离用）----
        cls.getMethod("resetCache").invoke(svc);

        System.out.println("\n===== smoke176o2: " + pass + " 通过, " + fail + " 失败 =====");
        System.exit(fail == 0 ? 0 : 1);
    }
}
