package scripts.smoke169;

/**
 * 1.6.9 功能冒烟（独立 JVM，headless，NF 分发 JAR 真实字节码）。
 *
 * 本版核心：变量获取信息（variable-fetch，默认关闭）——/查信息 卡片的
 * 总在线时长 / 今日在线时长 / 金币 / 点券 / 称号五个项目可改为输出
 * PlaceholderAPI 变量内容；变量留空或读取失败自动回退插件自带数据。
 *
 * 覆盖：
 *  VF 组  变量获取信息行为（反射调用分发 JAR 内真实 object 单例）：
 *   - VF1  PlaceholderVariableService 单例可加载（类初始化不炸）
 *   - VF2  无插件上下文 isEnabled = false（配置缺失安全回退）
 *   - VF3  无插件上下文 snapshot() → Overrides 五项全 null
 *   - VF4  resolve("") → null（空占位符）
 *   - VF5  注册 FakeKeron（未开开关）→ isEnabled false、placeholderOf null
 *   - VF6  FakeKeron config 写入 variable-fetch 段 → isEnabled true、
 *          placeholderOf 读回五个键（含留空项 null）
 *   - VF7  开关开但 PAPI 缺失 → snapshot() 全 null 回退（不炸）
 *   - VF8  resolve 经假 PAPI（动态代理 + me.clip.placeholderapi 假类）
 *          成功路径：%fake_coins% → 9999.5（采用）
 *   - VF9  未注册变量原样返回 → null（回退判定）
 *   - VF10 纯文本（无 %）原样返回 → 直通输出（非变量文本允许）
 *   - VF11 变量与固定文本混排 "共 %fake_hours%" → "共 77时20分"（采用）
 *   - VF12 MainThreadSnapshot 五个 var 字段在位（反射元数据）
 *   - VF13 buildItems 反射端到端：varCoins 覆盖金币值（vaultAvailable=false
 *          也展示该变量值）；varTitle 覆盖称号；varPlaytime/varToday 覆盖时长；
 *          全 null 时与旧版行为一致（金币隐藏-无Vault、时长走 formatPlayTime）
 *   - VF14 buildItems：金币项 vaultAvailable=true 且 varCoins=null → formatAmount
 *  TF 组  1.6.8 回归：formatPlayTime 小时制（77时20分）
 *  J 组   plugin.yml 1.6.9；NF 双 toml 1.6.9
 *
 * 编译运行（仓库根目录，javac/java 用 tooling JDK21）：
 *   javac -cp build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.9.jar \
 *     -d scripts/smoke169-out scripts/smoke169/*.java \
 *     scripts/smoke169/me/clip/placeholderapi/PlaceholderAPI.java
 *   java -Djava.awt.headless=true \
 *     -cp scripts/smoke169-out:build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.9.jar \
 *     scripts.smoke169.Test169
 */
public class Test169 {

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        check(name, cond, "");
    }

    static void check(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : " — " + detail));
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : " — " + detail));
        }
    }

    static Object call(Object instance, String method, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? null : args[i].getClass();
        }
        for (java.lang.reflect.Method m : instance.getClass().getMethods()) {
            if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                boolean ok = true;
                for (int i = 0; i < args.length; i++) {
                    if (types[i] == null) continue;
                    Class<?> pt = m.getParameterTypes()[i];
                    if (!pt.isAssignableFrom(types[i])
                            && !(pt.isPrimitive() && boxed(pt).isAssignableFrom(types[i]))) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return m.invoke(instance, args);
                }
            }
        }
        throw new NoSuchMethodException(method + " / " + args.length + " 参数");
    }

    static Class<?> boxed(Class<?> primitive) {
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == long.class) return Long.class;
        if (primitive == int.class) return Integer.class;
        if (primitive == double.class) return Double.class;
        return primitive;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== VF 组：变量获取信息（真实 object 单例反射）==");

        // --- VF1-VF3：无插件上下文（安全默认） ---
        Class<?> svcCls = Class.forName("cn.kerong.penguin.spigot.stats.PlaceholderVariableService");
        Object svc = svcCls.getField("INSTANCE").get(null);
        check("VF1 单例可加载（初始化不炸）", svc != null, "INSTANCE=" + svc);

        Object enabled0 = call(svc, "isEnabled");
        check("VF2 无插件上下文 isEnabled=false", Boolean.FALSE.equals(enabled0), "实际 " + enabled0);

        Object snap0 = call(svc, "snapshot", "TestPlayer", java.util.UUID.randomUUID(), null);
        check("VF3 无插件上下文 snapshot 全 null",
                snap0 != null && allFieldsNull(snap0, "playtime", "today", "coins", "points", "title"),
                String.valueOf(snap0));

        check("VF4 resolve(\"\") → null", call(svc, "resolve", "", "TestPlayer", null, null) == null, "空占位符");

        // --- VF5：注册 FakeKeron（开关未开） ---
        org.bukkit.plugin.PluginManager fm = org.bukkit.plugin.PluginManager.INSTANCE;
        FakeKeronPlugin keron = new FakeKeronPlugin();
        fm.register(keron);
        Object enabled1 = call(svc, "isEnabled");
        check("VF5 注册插件但开关未开 isEnabled=false", Boolean.FALSE.equals(enabled1), "实际 " + enabled1);

        // --- VF6：写入 variable-fetch 配置 → 读取通路 ---
        org.bukkit.configuration.file.FileConfiguration config = keron.getConfig();
        config.set("variable-fetch.enabled", true);
        config.set("variable-fetch.total-playtime", "%fake_hours%");
        config.set("variable-fetch.coins", "%fake_coins%");
        config.set("variable-fetch.title", " %fake_title% "); // 带首尾空白（清洗验证）
        // today-playtime / points 留空
        Object enabled2 = call(svc, "isEnabled");
        check("VF6a 开关开启 isEnabled=true", Boolean.TRUE.equals(enabled2), "实际 " + enabled2);
        Object coinsPh = call(svc, "placeholderOf", "coins");
        check("VF6b placeholderOf(coins) 读回", "%fake_coins%".equals(coinsPh), "实际 " + coinsPh);
        Object todayPh = call(svc, "placeholderOf", "today-playtime");
        check("VF6c 留空项 placeholderOf=null", todayPh == null, "实际 " + todayPh);
        Object titlePh = call(svc, "placeholderOf", "title");
        check("VF6d 首尾空白清洗", "%fake_title%".equals(titlePh), "实际 " + titlePh);

        // --- VF7：开关开但 PAPI 缺失 → 全 null 回退 ---
        Object snap1 = call(svc, "snapshot", "TestPlayer", java.util.UUID.randomUUID(), null);
        check("VF7 PAPI 缺失 snapshot 全 null（回退）",
                snap1 != null && allFieldsNull(snap1, "playtime", "today", "coins", "points", "title"),
                String.valueOf(snap1));

        // --- VF8-VF11：假 PAPI（动态代理名为 PlaceholderAPI）成功/回退路径 ---
        org.bukkit.plugin.Plugin fakePapi = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                Test169.class.getClassLoader(),
                new Class<?>[]{org.bukkit.plugin.Plugin.class},
                (proxy, method, margs) -> {
                    switch (method.getName()) {
                        case "getName": return "PlaceholderAPI";
                        case "isEnabled": return Boolean.TRUE;
                        case "toString": return "FakePapiProxy";
                        default: return null;
                    }
                });
        fm.register(fakePapi);

        Object r8 = call(svc, "resolve", "%fake_coins%", "TestPlayer", null, null);
        check("VF8 成功路径 %fake_coins% → 9999.5", "9999.5".equals(r8), "实际 " + r8);

        Object r9 = call(svc, "resolve", "%unregistered_var%", "TestPlayer", null, null);
        check("VF9 未注册变量 → null（回退）", r9 == null, "实际 " + r9);

        Object r10 = call(svc, "resolve", "纯文本", "TestPlayer", null, null);
        check("VF10 纯文本（无%）直通", "纯文本".equals(r10), "实际 " + r10);

        Object r11 = call(svc, "resolve", "共 %fake_hours%", "TestPlayer", null, null);
        check("VF11 混排文本 → 共 77时20分", "共 77时20分".equals(r11), "实际 " + r11);

        // --- VF12：MainThreadSnapshot 五个 var 字段在位 ---
        Class<?> snapCls = Class.forName("cn.kerong.penguin.spigot.stats.QueryInfoService$MainThreadSnapshot");
        java.util.Set<String> fieldNames = new java.util.HashSet<>();
        for (java.lang.reflect.Field f : snapCls.getDeclaredFields()) {
            fieldNames.add(f.getName());
        }
        check("VF12 MainThreadSnapshot var 字段在位",
                fieldNames.containsAll(java.util.List.of(
                        "varPlaytime", "varToday", "varCoins", "varPoints", "varTitle")),
                "字段 " + fieldNames);

        // --- VF13/VF14：buildItems 反射端到端 ---
        Class<?> statsCls = Class.forName("cn.kerong.penguin.spigot.stats.PlayerStatsManager$PlayerStats");
        Object stats = statsCls.getDeclaredConstructor().newInstance();

        // varCoins/varTitle/varPlaytime/varToday 有值，依赖插件全未装
        Object snapVar = newSnapshot(snapCls, stats,
                "暂无", "未检测到", null, "无", "暂无", "无", null, null,
                false, false, false,
                "77时20分", "3时5分", "9999.5", null, "&6[大佬]&f");
        java.util.List<?> itemsVar = buildItems(snapVar, null);
        String coinsVar = itemValue(itemsVar, "金币");
        check("VF13a varCoins 覆盖金币值（无 Vault 也展示）", "9999.5".equals(coinsVar), "实际 " + coinsVar);
        String titleVar = itemValue(itemsVar, "称号");
        check("VF13b varTitle 覆盖称号（无来源插件也展示）", "&6[大佬]&f".equals(titleVar), "实际 " + titleVar);
        String ptVar = itemValue(itemsVar, "在线时长");
        check("VF13c varPlaytime 覆盖在线时长", "77时20分".equals(ptVar), "实际 " + ptVar);
        String tdVar = itemValue(itemsVar, "今日在线时长");
        check("VF13d varToday 覆盖今日在线", "3时5分".equals(tdVar), "实际 " + tdVar);
        check("VF13e 点券未填变量且 PlayerPoints 未装 → 不展示",
                itemValue(itemsVar, "点券") == null, "点券项应隐藏");

        // 全 null → 旧行为（金币隐藏、时长走 formatPlayTime）
        Object snapOld = newSnapshot(snapCls, stats,
                "暂无", "未检测到", null, "无", "暂无", "无", null, null,
                false, false, false,
                null, null, null, null, null);
        java.util.List<?> itemsOld = buildItems(snapOld, null);
        check("VF13f 全 null 时金币隐藏（无 Vault）", itemValue(itemsOld, "金币") == null, "金币项应隐藏");
        String ptOld = itemValue(itemsOld, "在线时长");
        check("VF13g 全 null 时时长走自带格式（0 → 0分钟）", "0分钟".equals(ptOld), "实际 " + ptOld);

        // vaultAvailable=true 且 varCoins=null → formatAmount(1600) = 1.6k（trimZero 去尾零）
        Object snapFmt = newSnapshot(snapCls, stats,
                "暂无", "未检测到", 1600.0, "Vault", "暂无", "无", null, null,
                true, false, false,
                null, null, null, null, null);
        java.util.List<?> itemsFmt = buildItems(snapFmt, null);
        String coinsFmt = itemValue(itemsFmt, "金币");
        check("VF14 金币自带 formatAmount(1600) → 1.6k", "1.6k".equals(coinsFmt), "实际 " + coinsFmt);

        // 清理：注销假插件，避免影响后续（本轮即结束）
        fm.unregister();

        System.out.println("== TF 组：1.6.8 回归 ==");
        Class<?> qs = Class.forName("cn.kerong.penguin.spigot.stats.QueryInfoService");
        Object qsInst = qs.getField("INSTANCE").get(null);
        String r1 = (String) qs.getMethod("formatPlayTime", long.class).invoke(qsInst, 278400L);
        check("TF1 3天5时20分 → 77时20分（小时制保持）", "77时20分".equals(r1), "实际 " + r1);
        String r2 = (String) qs.getMethod("formatTodayTime", long.class).invoke(qsInst, 278400L);
        check("TF2 今日在线 77时20分（保持）", "77时20分".equals(r2), "实际 " + r2);

        System.out.println("== J 组：JAR 基线 ==");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(
                "build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.9.jar")) {
            String pluginYml = new String(zip.getInputStream(
                    zip.getEntry("META-INF/neoforge.mods.toml")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("J1 neoforge.mods.toml 1.6.9", pluginYml.contains("version = \"1.6.9\""), "版本行");
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(
                "build/dist/KERONGPenguin_Spigot-1.6.9.jar")) {
            String pluginYml = new String(zip.getInputStream(
                    zip.getEntry("plugin.yml")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("J2 plugin.yml 1.6.9", pluginYml.contains("version: '1.6.9'"), "版本行");
        }

        System.out.println();
        System.out.println("共 " + (pass + fail) + " 项：PASS " + pass + " / FAIL " + fail);
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("smoke169 全部通过");
    }

    /** Overrides / 各结果对象的指定字段是否全为 null。 */
    static boolean allFieldsNull(Object target, String... fields) {
        try {
            for (String name : fields) {
                java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
                f.setAccessible(true);
                if (f.get(target) != null) {
                    return false;
                }
            }
            return true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 反射构造 MainThreadSnapshot（17 参私有构造器）。 */
    static Object newSnapshot(Class<?> cls, Object stats, Object... tail) throws Exception {
        Object[] all = new Object[1 + tail.length];
        all[0] = stats;
        System.arraycopy(tail, 0, all, 1, tail.length);
        java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(all);
    }

    /** 反射调用 QueryInfoService.buildItems（私有）。 */
    static java.util.List<?> buildItems(Object snapshot, String checkin) throws Exception {
        Class<?> qs = Class.forName("cn.kerong.penguin.spigot.stats.QueryInfoService");
        Object inst = qs.getField("INSTANCE").get(null);
        java.lang.reflect.Method m = qs.getDeclaredMethod("buildItems", snapshot.getClass(), String.class);
        m.setAccessible(true);
        return (java.util.List<?>) m.invoke(inst, snapshot, checkin);
    }

    /** 从 CardItem 列表取指定标签的值（无该标签返回 null）。 */
    static String itemValue(java.util.List<?> items, String label) {
        for (Object item : items) {
            try {
                java.lang.reflect.Field labelF = item.getClass().getDeclaredField("label");
                java.lang.reflect.Field valueF = item.getClass().getDeclaredField("value");
                labelF.setAccessible(true);
                valueF.setAccessible(true);
                if (label.equals(labelF.get(item))) {
                    return (String) valueF.get(item);
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return null;
    }
}
