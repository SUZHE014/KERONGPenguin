import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 1.7.6 三次覆盖轮冒烟：排行榜存档源直枚举 + 群 OpenId 空串归一 + 默认源。
 * 在纯 JVM 里以工作目录为「服务器根」构造 world/stats/<uuid>.json 与
 * usercache.json，真实走 readAllPlayTimeEntries / readPlayTime 链：
 *   A. formatPlayText 统一「X时X分」格式（回归）
 *   B. readPlayTime 绑定 UUID 有文件 → 直接命中（回归，名片链路不变）
 *   C. readPlayTime 绑定无文件 → usercache 解析（回归）
 *   F/G. play_time=0 / 旧键（回归）
 *   H. readAllPlayTimeEntries 服务器直枚举（本轮核心）：
 *      usercache 全量条目逐个读 stats（同名多账号各一行；同一 UUID 改名
 *      两条取 expiresOn 最新名）+ stats 目录补充（usercache 被清理的老
 *      统计文件用名字提示补名，无提示跳过）——不依赖任何绑定记录
 *   I. resolveAllServerUuids 同名多条按 expiresOn 降序（回归，名片用）
 *   J. LeaderboardRenderer.formatPlayTime 统一「X时X分」（回归）
 *   N. ConfigManager.normalizeGroupIds 群 OpenId 归一（本轮核心）：
 *      只写一对双引号 ""（及纯空白 / 纯引号）视同未填写
 *   S. PlaceholderVariableService 默认源 = save-data（本轮核心）：
 *      纯 JVM 无 Bukkit 配置 → 走默认分支
 * 注：Bukkit.getWorlds() 在纯 JVM 抛 NoClassDefFoundError（惰性解析被
 * try-catch 捕获）→ statsDirectory 回退工作目录 world/stats、serverRoot
 * 回退 CWD——恰好构造真实文件系统场景。
 */
public class Smoke176o4 {
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
        for (Field f : cls.getDeclaredFields()) {
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

        // ---- 构造玩家数据（直枚举场景）----
        UUID uuidA = UUID.randomUUID();      // SmokeTester 新名（usercache 2026-06）
        UUID uuidB = UUID.randomUUID();      // SmokeTester 同名另一账号（2025-01）
        UUID uuidG = UUID.randomUUID();      // usercache 有名但无统计文件（ghost）
        UUID uuidO = UUID.randomUUID();      // OtherGuy（正常玩家）
        UUID uuidH = UUID.randomUUID();      // 孤儿统计文件：usercache 无条目，有名字提示
        UUID uuidN = UUID.randomUUID();      // 孤儿统计文件：usercache 无条目，无名字提示 → 跳过

        StringBuilder uc = new StringBuilder("[");
        uc.append(String.format("{\"name\":\"%s\",\"uuid\":\"%s\",\"expiresOn\":\"2026-01-01 00:00:00 +0800\"}", "OtherGuy", uuidO));
        uc.append(",");
        uc.append(String.format("{\"name\":\"%s\",\"uuid\":\"%s\",\"expiresOn\":\"2025-01-01 00:00:00 +0800\"}", "SmokeTester", uuidB));
        uc.append(",");
        uc.append(String.format("{\"name\":\"%s\",\"uuid\":\"%s\",\"expiresOn\":\"2026-06-01 00:00:00 +0800\"}", "SmokeTester", uuidA));
        uc.append(",");
        uc.append(String.format("{\"name\":\"%s\",\"uuid\":\"%s\",\"expiresOn\":\"2024-01-01 00:00:00 +0800\"}", "OldName_Legacy", uuidA));
        uc.append(",");
        uc.append(String.format("{\"name\":\"%s\",\"uuid\":\"%s\",\"expiresOn\":\"2026-07-01 00:00:00 +0800\"}", "GhostGuy", uuidG));
        uc.append("]");
        Files.write(new File(root, "usercache.json").toPath(), uc.toString().getBytes(StandardCharsets.UTF_8));

        long ticksA = 72000L * 35 + 12000 * 20 + 600;   // 35 时 20 分 30 秒
        long ticksB = 72000L * 3;                        // 3 时
        long ticksO = 72000L * 2;                        // 2 时
        long ticksH = 72000L * 5;                        // 5 时（提示补名）
        long ticksN = 72000L * 4;                        // 4 时（无提示，跳过）
        Map<UUID, Long> statsData = new HashMap<>();
        statsData.put(uuidA, ticksA);
        statsData.put(uuidB, ticksB);
        statsData.put(uuidO, ticksO);
        statsData.put(uuidH, ticksH);
        statsData.put(uuidN, ticksN);
        for (Map.Entry<UUID, Long> e : statsData.entrySet()) {
            Files.write(new File(stats, e.getKey() + ".json").toPath(),
                String.format("{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":%d}}}", e.getValue())
                    .getBytes(StandardCharsets.UTF_8));
        }

        // ---- B. readPlayTime 绑定 UUID 有文件 → 直接命中（回归，名片链路不变）----
        Method read = cls.getMethod("readPlayTime", String.class, java.util.UUID.class);
        Class<?> resultCls = Class.forName("cn.kerong.penguin.spigot.stats.StatsFileService$PlayTimeResult");
        Object rB = read.invoke(svc, "SmokeTester", uuidA);
        check("B1 名片链路回归：绑定 UUID 有文件 → 直接命中", rB != null, "null");
        check("B2 来源 = 绑定记录",
              rB != null && "绑定记录".equals(resultCls.getMethod("getUuidSource").invoke(rB)),
              rB == null ? "" : String.valueOf(resultCls.getMethod("getUuidSource").invoke(rB)));

        // ---- C. readPlayTime 绑定无文件 → usercache 解析（回归）----
        Object rC = read.invoke(svc, "SmokeTester", UUID.randomUUID());
        check("C1 名片链路回归：绑定无文件 → usercache 最新候选命中",
              rC != null && uuidA.equals(resultCls.getMethod("getUuid").invoke(rC)),
              rC == null ? "null" : String.valueOf(resultCls.getMethod("getUuid").invoke(rC)));

        // ---- H. readAllPlayTimeEntries 服务器直枚举（本轮核心）----
        Method readAll = cls.getMethod("readAllPlayTimeEntries", java.util.Map.class);
        Map<UUID, String> hints = new HashMap<>();
        hints.put(uuidH, "HintedPlayer");   // 孤儿统计文件的名字提示（绑定提示角色）
        List<?> all = (List<?>) readAll.invoke(svc, hints);
        check("H1 直枚举条目数 = 4（uuidA/uuidB/uuidO/uuidH；ghost 无文件与无提示孤儿不计）",
              all != null && all.size() == 4, "size=" + (all == null ? -1 : all.size()));
        Class<?> entryCls = Class.forName("cn.kerong.penguin.spigot.stats.StatsFileService$ServerPlayTimeEntry");
        Method eName = entryCls.getMethod("getName");
        Method eUuid = entryCls.getMethod("getUuid");
        Method eTicks = entryCls.getMethod("getTicks");
        Set<UUID> seenUuids = new HashSet<>();
        String uuidAName = null, uuidHName = null;
        long uuidATicks = -1, uuidBTicks = -1, uuidHTicks = -1;
        boolean hasGhost = false, hasNoHint = false;
        if (all != null) {
            for (Object r : all) {
                UUID u = (UUID) eUuid.invoke(r);
                String n = (String) eName.invoke(r);
                long t = (Long) eTicks.invoke(r);
                seenUuids.add(u);
                if (u.equals(uuidA)) { uuidAName = n; uuidATicks = t; }
                else if (u.equals(uuidB)) { uuidBTicks = t; }
                else if (u.equals(uuidH)) { uuidHName = n; uuidHTicks = t; }
                if (u.equals(uuidG)) hasGhost = true;
                if (u.equals(uuidN)) hasNoHint = true;
            }
        }
        check("H2 同名多账号同显（SmokeTester 两个账号各一行）",
              seenUuids.contains(uuidA) && seenUuids.contains(uuidB),
              "seen=" + seenUuids);
        check("H3 改名取最新名（同一 UUID 两条 usercache 记录 → 新名 SmokeTester）",
              "SmokeTester".equals(uuidAName), "name=" + uuidAName);
        check("H4 刻数原样（uuidA=35时20分30秒 / uuidB=3时）",
              uuidATicks == ticksA && uuidBTicks == ticksB,
              "A=" + uuidATicks + " B=" + uuidBTicks);
        check("H5 孤儿统计文件用名字提示补名（HintedPlayer，5 时）",
              "HintedPlayer".equals(uuidHName) && uuidHTicks == ticksH,
              "name=" + uuidHName + " ticks=" + uuidHTicks);
        check("H6 无提示孤儿跳过（uuidN 不在结果中）", !hasNoHint, "uuidN included");
        check("H7 ghost（usercache 有名无统计文件）不计入", !hasGhost, "ghost included");
        check("H8 无重复 UUID", seenUuids.size() == (all == null ? 0 : all.size()),
              "seen=" + seenUuids.size() + " all=" + (all == null ? 0 : all.size()));
        // 零绑定场景：不传任何提示，服务器数据照样出榜（本轮用户核心诉求）
        List<?> noBind = (List<?>) readAll.invoke(svc, new HashMap<UUID, String>());
        check("H9 零绑定场景照常出榜（无提示仍有 usercache 三条：uuidA/uuidB/uuidO）",
              noBind != null && noBind.size() == 3, "size=" + (noBind == null ? -1 : noBind.size()));
        // 无 stats 目录 → 空表（整榜回退插件统计）
        cls.getMethod("resetCache").invoke(svc);
        File oldUsercache = new File(root, "usercache.json");
        File bakUsercache = new File(root, "usercache.json.bak");
        Files.move(oldUsercache.toPath(), bakUsercache.toPath());
        try {
            File bakStats = new File(root, "world/stats-bak");
            Files.move(stats.toPath(), bakStats.toPath());
            try {
                cls.getMethod("resetCache").invoke(svc);
                List<?> none = (List<?>) readAll.invoke(svc, new HashMap<UUID, String>());
                check("H10 无 stats 目录 → 空表（调用方整榜回退插件统计）",
                      none != null && none.isEmpty(), "size=" + (none == null ? -1 : none.size()));
            } finally {
                Files.move(bakStats.toPath(), stats.toPath());
            }
        } finally {
            Files.move(bakUsercache.toPath(), oldUsercache.toPath());
        }
        cls.getMethod("resetCache").invoke(svc);

        // ---- I. resolveAllServerUuids 同名多条（回归，名片用）----
        Method resolveAll = cls.getMethod("resolveAllServerUuids", String.class);
        List<?> sameName = (List<?>) resolveAll.invoke(svc, "SmokeTester");
        check("I1 同名两条（忽略大小写；改名旧条目名字不同不算同名）",
              sameName != null && sameName.size() == 2,
              "size=" + (sameName == null ? -1 : sameName.size()));

        // ---- J. LeaderboardRenderer.formatPlayTime 统一「X时X分」（回归）----
        Class<?> lr = Class.forName("cn.kerong.penguin.spigot.render.LeaderboardRenderer");
        Object lrObj = null;
        for (Field f : lr.getDeclaredFields()) {
            if (f.getName().equals("INSTANCE")) { f.setAccessible(true); lrObj = f.get(null); }
        }
        Method lrFmt = lr.getMethod("formatPlayTime", long.class);
        Object j1 = lrFmt.invoke(lrObj, 7800L);   // 2 时 10 分
        Object j3 = lrFmt.invoke(lrObj, 0L);      // 0 分钟
        check("J1 7800 秒 → 2时10分", "2时10分".equals(j1), String.valueOf(j1));
        check("J2 0 秒 → 0分钟", "0分钟".equals(j3), String.valueOf(j3));

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

        // ---- N. ConfigManager.normalizeGroupIds 群 OpenId 归一（本轮核心）----
        Class<?> cm = Class.forName("cn.kerong.penguin.spigot.manager.ConfigManager");
        Method norm = cm.getMethod("normalizeGroupIds", List.class);
        Object n1 = norm.invoke(null, java.util.Arrays.asList(""));
        check("N1 只写一对双引号 \"\" → 视同未填写（空表）",
              n1 instanceof List && ((List<?>) n1).isEmpty(), String.valueOf(n1));
        Object n2 = norm.invoke(null, java.util.Arrays.asList("", "   ", "\"", "\"\""));
        check("N2 纯空白 / 纯引号 → 全部剔除",
              n2 instanceof List && ((List<?>) n2).isEmpty(), String.valueOf(n2));
        @SuppressWarnings("unchecked")
        List<String> n3 = (List<String>) norm.invoke(null, java.util.Arrays.asList("ABC", " DEF "));
        check("N3 正常 ID 保留 + 去首尾空白",
              "ABC,DEF".equals(String.join(",", n3)), String.valueOf(n3));
        @SuppressWarnings("unchecked")
        List<String> n4 = (List<String>) norm.invoke(null, java.util.Arrays.asList("\"GHI\""));
        check("N4 残留包裹引号剥除（\"GHI\" → GHI）",
              "GHI".equals(String.join(",", n4)), String.valueOf(n4));
        @SuppressWarnings("unchecked")
        List<String> n5 = (List<String>) norm.invoke(null, java.util.Arrays.asList("", "REALID", ""));
        check("N5 空串夹真 ID → 只剩真 ID（自动绑定可触发）",
              "REALID".equals(String.join(",", n5)), String.valueOf(n5));

        // ---- S. PlaceholderVariableService 默认源（本轮核心）----
        Class<?> pvs = Class.forName("cn.kerong.penguin.spigot.stats.PlaceholderVariableService");
        Object pvsObj = null;
        for (Field f : pvs.getDeclaredFields()) {
            if (f.getName().equals("INSTANCE")) { f.setAccessible(true); pvsObj = f.get(null); }
        }
        Method src = pvs.getMethod("totalPlaytimeSource");
        Object s1 = src.invoke(pvsObj);   // 纯 JVM 无 Bukkit 配置 → 默认分支
        check("S1 默认源 = save-data（未配置走默认）", "save-data".equals(s1), String.valueOf(s1));
        Object constSave = pvs.getField("SOURCE_SAVE_DATA").get(null);
        check("S2 SOURCE_SAVE_DATA 常量 = save-data", "save-data".equals(constSave),
              String.valueOf(constSave));
        Object constVar = pvs.getField("SOURCE_VARIABLE").get(null);
        Object constPlug = pvs.getField("SOURCE_PLUGIN").get(null);
        check("S3 三源常量保持（variable/plugin 回归）",
              "variable".equals(constVar) && "plugin".equals(constPlug),
              constVar + "/" + constPlug);

        // ---- 目录缓存复位（隔离用）----
        cls.getMethod("resetCache").invoke(svc);

        System.out.println("\n===== smoke176o4: " + pass + " 通过, " + fail + " 失败 =====");
        System.exit(fail == 0 ? 0 : 1);
    }
}
