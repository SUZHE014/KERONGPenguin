import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * 1.7.6 二次覆盖轮冒烟：StatsFileService（排行榜存档源）+ 排行榜时长格式端到端。
 * 在纯 JVM 里以工作目录为「服务器根」构造 world/stats/<uuid>.json 与
 * usercache.json，真实走 readPlayTime / readPlayTimeAll 链：
 *   A. formatPlayText 统一「X时X分」格式（回归）
 *   B. readPlayTime 绑定 UUID 有文件 → 直接命中（回归）
 *   C. readPlayTime 绑定无文件 → usercache 解析（回归）
 *   F/G/E. play_time=0 / 旧键 / 全 miss（回归）
 *   H. readPlayTimeAll 同名多账号全部读出（本轮核心）：
 *      绑定 UUID + usercache 同名全部服务器 UUID，每个能读到 play_time 的
 *      UUID 一条结果——离线服常见同名（改名旧账号 / 大小写变体）同显
 *   I. resolveAllServerUuids 同名多条按 expiresOn 降序 + 忽略大小写
 *   J. LeaderboardRenderer.formatPlayTime 统一「X时X分」（旧「X小时X分钟」停用）
 * 注：Bukkit.getWorlds() 在纯 JVM 抛异常 → statsDirectory 回退工作目录
 * world/stats、serverRoot 回退 CWD——恰好构造真实文件系统场景。
 */
public class Smoke176o3 {
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

        // ---- A. formatPlayText（回归）----
        Method fmt = cls.getMethod("formatPlayText", long.class);
        check("A1 72000*35+24000 刻 → 35时20分", 
              "35时20分".equals(fmt.invoke(svc, 72000L * 35 + 24000)),
              String.valueOf(fmt.invoke(svc, 72000L * 35 + 24000)));
        check("A2 0 刻 → 0分钟", "0分钟".equals(fmt.invoke(svc, 0L)), String.valueOf(fmt.invoke(svc, 0L)));
        check("A3 7200 刻 → 6分（无小时带分）", "6分".equals(fmt.invoke(svc, 7200L)),
              String.valueOf(fmt.invoke(svc, 7200L)));

        // ---- 构造玩家（同名多账号场景：绑定 / 服务器新口径 / 改名旧口径）----
        String player = "SmokeTester";
        UUID boundUuid = UUID.randomUUID();      // 绑定记录（插件口径）
        UUID serverUuid = UUID.randomUUID();     // 服务器口径（与绑定不同）
        UUID oldUuid = UUID.randomUUID();        // 改名前的旧记录（expiresOn 较旧）
        UUID ghostUuid = UUID.randomUUID();      // usercache 有名但无统计文件

        // usercache.json：同名三条（大小写混合，模拟离线服改名历史）+ 无关玩家
        StringBuilder uc = new StringBuilder("[");
        uc.append(String.format("{\"name\":\"%s\",\"uuid\":\"%s\",\"expiresOn\":\"2026-01-01 00:00:00 +0800\"}", "OtherGuy", UUID.randomUUID()));
        uc.append(",");
        uc.append(String.format("{\"name\":\"%s\",\"uuid\":\"%s\",\"expiresOn\":\"2025-01-01 00:00:00 +0800\"}", player.toLowerCase(), oldUuid));
        uc.append(",");
        uc.append(String.format("{\"name\":\"%s\",\"uuid\":\"%s\",\"expiresOn\":\"2026-06-01 00:00:00 +0800\"}", player, serverUuid));
        uc.append(",");
        uc.append(String.format("{\"name\":\"%s\",\"uuid\":\"%s\",\"expiresOn\":\"2026-07-01 00:00:00 +0800\"}", player, ghostUuid));
        uc.append("]");
        Files.write(new File(root, "usercache.json").toPath(), uc.toString().getBytes(StandardCharsets.UTF_8));

        // 统计文件：三份（绑定 10 时 / 服务器新口径 35 时 20 分 30 秒 / 旧口径 3 时）
        long boundTicks = 72000L * 10;
        long serverTicks = 72000L * 35 + 12000 * 20 + 600;
        long oldTicks = 72000L * 3;
        Files.write(new File(stats, boundUuid + ".json").toPath(),
            String.format("{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":%d}}}", boundTicks)
                .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(stats, serverUuid + ".json").toPath(),
            String.format("{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":%d,\"minecraft:walk_one_cm\":123}}}", serverTicks)
                .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(stats, oldUuid + ".json").toPath(),
            String.format("{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":%d}}}", oldTicks)
                .getBytes(StandardCharsets.UTF_8));

        Method read = cls.getMethod("readPlayTime", String.class, java.util.UUID.class);
        Method readAll = cls.getMethod("readPlayTimeAll", String.class, java.util.UUID.class);
        Class<?> resultCls = Class.forName("cn.kerong.penguin.spigot.stats.StatsFileService$PlayTimeResult");

        // ---- B. readPlayTime 绑定 UUID 有文件 → 直接命中（回归）----
        Object rB = read.invoke(svc, player, boundUuid);
        check("B1 绑定 UUID 有文件 → 直接命中", rB != null, "null");
        check("B2 来源 = 绑定记录",
              rB != null && "绑定记录".equals(resultCls.getMethod("getUuidSource").invoke(rB)),
              rB == null ? "" : String.valueOf(resultCls.getMethod("getUuidSource").invoke(rB)));

        // ---- C. readPlayTime 绑定无文件 → usercache 解析最新（回归）----
        Object rC = read.invoke(svc, player, UUID.randomUUID());
        check("C1 绑定 UUID 无文件 → usercache 最新 UUID 命中（ghostUuid 无统计文件跳到 serverUuid）",
              rC != null && serverUuid.equals(resultCls.getMethod("getUuid").invoke(rC)),
              rC == null ? "null" : String.valueOf(resultCls.getMethod("getUuid").invoke(rC)));

        // ---- H. readPlayTimeAll 同名多账号全部读出（本轮核心）----
        List<?> all = (List<?>) readAll.invoke(svc, player, boundUuid);
        check("H1 同名三账号全部读出（绑定 + usercache 两条有文件的，ghost 无文件不计）→ 3 条",
              all != null && all.size() == 3, "size=" + (all == null ? -1 : all.size()));
        if (all != null) {
            boolean hasBound = false, hasServer = false, hasOld = false, hasGhost = false;
            long boundFound = -1, serverFound = -1, oldFound = -1;
            for (Object r : all) {
                UUID u = (UUID) resultCls.getMethod("getUuid").invoke(r);
                long t = (Long) resultCls.getMethod("getTicks").invoke(r);
                if (u.equals(boundUuid)) { hasBound = true; boundFound = t; }
                else if (u.equals(serverUuid)) { hasServer = true; serverFound = t; }
                else if (u.equals(oldUuid)) { hasOld = true; oldFound = t; }
                else if (u.equals(ghostUuid)) { hasGhost = true; }
            }
            check("H2 绑定记录条目在列（10 时）", hasBound && boundFound == boundTicks, "ticks=" + boundFound);
            check("H3 服务器新口径条目在列（35 时 20 分 30 秒）", hasServer && serverFound == serverTicks, "ticks=" + serverFound);
            check("H4 改名旧口径条目在列（3 时）", hasOld && oldFound == oldTicks, "ticks=" + oldFound);
            check("H5 usercache 有名无统计文件的 UUID 不计入", !hasGhost, "ghost included");
        }

        // ---- H2. 绑定 UUID 与 usercache 条目相同 → 去重不重复 ----
        List<?> dedup = (List<?>) readAll.invoke(svc, player, serverUuid);
        check("H6 绑定 UUID = usercache 某条 → 去重后 2 条（serverUuid 只计一次 + oldUuid）",
              dedup != null && dedup.size() == 2, "size=" + (dedup == null ? -1 : dedup.size()));

        // ---- H3. 全 miss → 空表（调用方回退插件统计）----
        List<?> none = (List<?>) readAll.invoke(svc, "Nobody", null);
        check("H7 无绑定无 usercache 记录 → 空表", none != null && none.isEmpty(),
              "size=" + (none == null ? -1 : none.size()));

        // ---- I. resolveAllServerUuids 同名多条按 expiresOn 降序 ----
        Method resolveAll = cls.getMethod("resolveAllServerUuids", String.class);
        List<?> sameName = (List<?>) resolveAll.invoke(svc, player);
        check("I1 同名三条（忽略大小写）全部解析", sameName != null && sameName.size() == 3,
              "size=" + (sameName == null ? -1 : sameName.size()));
        if (sameName != null && sameName.size() == 3) {
            Class<?> entryCls = Class.forName("cn.kerong.penguin.spigot.stats.StatsFileService$ServerUuidEntry");
            Method getUuid = entryCls.getMethod("getUuid");
            UUID first = (UUID) getUuid.invoke(sameName.get(0));
            UUID second = (UUID) getUuid.invoke(sameName.get(1));
            UUID third = (UUID) getUuid.invoke(sameName.get(2));
            check("I2 按 expiresOn 降序（ghost 2026-07 → server 2026-06 → old 2025-01）",
                  ghostUuid.equals(first) && serverUuid.equals(second) && oldUuid.equals(third),
                  first + "," + second + "," + third);
        }

        // ---- J. LeaderboardRenderer.formatPlayTime 统一「X时X分」 ----
        Class<?> lr = Class.forName("cn.kerong.penguin.spigot.render.LeaderboardRenderer");
        Object lrObj = null;
        for (java.lang.reflect.Field f : lr.getDeclaredFields()) {
            if (f.getName().equals("INSTANCE")) { f.setAccessible(true); lrObj = f.get(null); }
        }
        Method lrFmt = lr.getMethod("formatPlayTime", long.class);
        Object j1 = lrFmt.invoke(lrObj, 7800L);   // 2 时 10 分
        Object j2 = lrFmt.invoke(lrObj, 1200L);   // 20 分
        Object j3 = lrFmt.invoke(lrObj, 0L);      // 0 分钟
        Object j4 = lrFmt.invoke(lrObj, 45L);     // 不足一分钟
        check("J1 7800 秒 → 2时10分（旧格式为 2小时10分钟）", "2时10分".equals(j1), String.valueOf(j1));
        check("J2 1200 秒 → 20分", "20分".equals(j2), String.valueOf(j2));
        check("J3 0 秒 → 0分钟（旧「暂无记录」停用）", "0分钟".equals(j3), String.valueOf(j3));
        check("J4 45 秒 → 0分钟（旧「不足1分钟」停用）", "0分钟".equals(j4), String.valueOf(j4));
        check("J5 格式与名片三源一致（不含「小时」字样）",
              !String.valueOf(j1).contains("小时") && !String.valueOf(j2).contains("小时"),
              String.valueOf(j1) + "/" + j2);

        // ---- F/G. play_time=0 / 旧键（回归）----
        UUID zeroUuid = UUID.randomUUID();
        Files.write(new File(stats, zeroUuid + ".json").toPath(),
            "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":0}}}".getBytes(StandardCharsets.UTF_8));
        Object rF = read.invoke(svc, "ZeroGuy", zeroUuid);
        check("F1 play_time=0 → 获取不到（null）", rF == null, String.valueOf(rF));

        UUID legacyUuid = UUID.randomUUID();
        Files.write(new File(stats, legacyUuid + ".json").toPath(),
            "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_one_minute\":144000}}}".getBytes(StandardCharsets.UTF_8));
        Object rG = read.invoke(svc, "LegacyGuy", legacyUuid);
        check("G1 play_one_minute 旧键兼容（144000 刻 = 2 时）",
              rG != null && ((Long) resultCls.getMethod("getTicks").invoke(rG)) == 144000L,
              rG == null ? "null" : String.valueOf(resultCls.getMethod("getTicks").invoke(rG)));

        // ---- 目录缓存复位（隔离用）----
        cls.getMethod("resetCache").invoke(svc);

        System.out.println("\n===== smoke176o3: " + pass + " 通过, " + fail + " 失败 =====");
        System.exit(fail == 0 ? 0 : 1);
    }
}
