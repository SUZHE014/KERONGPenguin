package scripts.smoke1693;

/**
 * 1.6.9.3 功能冒烟（独立 JVM，headless，NF 分发 JAR 真实字节码）。
 *
 * 本版核心：变量获取扩展（玩家名 / TPS 可配置、自定义查信息项目、
 * 填了变量必展示读不到显示「未获取成功」、排行榜 / 财富榜 / 查性能
 * 同步拉取变量）+ 查性能在线人数 N / 最大人数 + 移除 /在线服务器、/motd。
 *
 * 覆盖：
 *  VF 组  变量获取扩展（反射调用分发 JAR 内真实 object 单例）：
 *   - VF1  PlaceholderVariableService 单例可加载 + 新嵌套类在位
 *   - VF2  无插件上下文 isEnabled=false、snapshotContext 空上下文
 *   - VF3  配置通路：开关 / 六键 / tps 读取、isConfigured 判定
 *   - VF4  customItems() 配置读取（完整项 / 缺获取变量跳过 / 留空跳过）
 *   - VF5  PAPI 缺失：overrides 全 null 但 configured 标记保持（显隐不回退）
 *   - VF6  假 PAPI 下 snapshotContext：六键解析 + 自定义项目（正常返回
 *          的保留、未注册的不展示）+ playerName 解析
 *   - VF7  buildItems：coinsConfigured 且读取失败且无 Vault → 金币展示
 *          「未获取成功」（不再隐藏）；titleConfigured 同理
 *   - VF8  buildItems：自定义项目追加在末尾
 *   - VF9  resolveServerTps：假 PAPI → "19.6"（采用）；未配置 → null
 *   - VF10 resolveBatch：两玩家批量解析（名 + 值覆盖；未注册 → null 覆盖）
 *  PF 组  查性能最大人数：
 *   - PF1  PerformanceSnapshot 五参 capture → maxPlayers 读回
 *   - PF2  MotdDisplayTracker.record / lastDisplayMax（0 忽略）
 *   - PF3  PerformanceRenderer 端到端渲染（maxPlayers + tpsVariableText +
 *          MOTD 展示值标记）→ PNG 魔数与尺寸
 *   - PF4  parseDoubleOrNull（反射）：数值 / 千分位 / 非数值
 *  CD 组  命令删除：
 *   - CD1  MotdCommands 类不存在（ClassNotFoundException）
 *   - CD2  PublicCommands 注解面无 motd / 在线服务器，方法名无 motd/queryServers
 *   - CD3  MenuManager PANEL_ITEMS 19 项且无两命令按钮
 *   - CD4  MessageTemplates.TEMPLATE_IDS 无 motd-* / server-status
 *  TF 组  1.6.8 回归：formatPlayTime 小时制
 *  J 组   plugin.yml / NF 双 toml 1.6.9.3
 *
 * 编译运行（仓库根目录，javac/java 用 tooling JDK21）：
 *   javac -cp build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.9.3.jar \
 *     -d scripts/smoke1693-out scripts/smoke1693/*.java \
 *     scripts/smoke1693/me/clip/placeholderapi/PlaceholderAPI.java
 *   java -Djava.awt.headless=true \
 *     -cp scripts/smoke1693-out:build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.9.3.jar \
 *     scripts.smoke1693.Test1693
 */
public class Test1693 {

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
        if (primitive == float.class) return Float.class;
        return primitive;
    }

    static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== VF 组：变量获取扩展（真实 object 单例反射）==");

        // --- VF1/VF2：无插件上下文（安全默认） ---
        Class<?> svcCls = Class.forName("cn.kerong.penguin.spigot.stats.PlaceholderVariableService");
        Object svc = svcCls.getField("INSTANCE").get(null);
        check("VF1 单例可加载 + 新嵌套类在位",
                svc != null
                        && Class.forName("cn.kerong.penguin.spigot.stats.PlaceholderVariableService$Context") != null
                        && Class.forName("cn.kerong.penguin.spigot.stats.PlaceholderVariableService$CustomItem") != null
                        && Class.forName("cn.kerong.penguin.spigot.stats.PlaceholderVariableService$ResolvedCustomItem") != null,
                "INSTANCE=" + svc);

        check("VF2a 无插件上下文 isEnabled=false", Boolean.FALSE.equals(call(svc, "isEnabled")));
        Object ctx0 = call(svc, "snapshotContext", "TestPlayer", java.util.UUID.randomUUID(), null);
        Object ov0 = field(ctx0, "overrides");
        check("VF2b 无插件上下文 snapshotContext 全回退",
                ctx0 != null && allFieldsNull(ov0, "playtime", "today", "coins", "points", "title", "playerName")
                        && Boolean.FALSE.equals(field(ctx0, "coinsConfigured"))
                        && ((java.util.List<?>) field(ctx0, "customItems")).isEmpty(),
                String.valueOf(ctx0));

        // --- VF3/VF4：配置通路 ---
        org.bukkit.plugin.PluginManager fm = org.bukkit.plugin.PluginManager.INSTANCE;
        FakeKeronPlugin keron = new FakeKeronPlugin();
        fm.register(keron);
        org.bukkit.configuration.file.FileConfiguration config = keron.getConfig();
        config.set("variable-fetch.enabled", true);
        config.set("variable-fetch.total-playtime", "%fake_hours%");
        config.set("variable-fetch.coins", "%fake_coins%");
        config.set("variable-fetch.title", " %fake_title% ");   // 带首尾空白（清洗验证）
        config.set("variable-fetch.player-name", "%fake_nickname%");
        config.set("variable-fetch.tps", "%fake_tps%");
        config.set("variable-fetch.today-playtime", "");        // 留空
        // custom-items：一个完整项 + 一个缺获取变量（跳过） + 一个名称留空（跳过）
        config.set("variable-fetch.custom-items.vip.项目名称", "VIP等级");
        config.set("variable-fetch.custom-items.vip.获取变量", "%fake_title%");
        config.set("variable-fetch.custom-items.nokey.项目名称", "缺变量的项");
        config.set("variable-fetch.custom-items.emptyname.获取变量", "%fake_hours%");

        check("VF3a 开关开启 isEnabled=true", Boolean.TRUE.equals(call(svc, "isEnabled")));
        check("VF3b placeholderOf(player-name) 读回", "%fake_nickname%".equals(call(svc, "placeholderOf", "player-name")));
        check("VF3c placeholderOf(tps) 读回", "%fake_tps%".equals(call(svc, "placeholderOf", "tps")));
        check("VF3d isConfigured(coins)=true", Boolean.TRUE.equals(call(svc, "isConfigured", "coins")));
        check("VF3e isConfigured(points)=false（留空）", Boolean.FALSE.equals(call(svc, "isConfigured", "points")));
        check("VF3f isConfigured(tps)=true", Boolean.TRUE.equals(call(svc, "isConfigured", "tps")));

        java.util.List<?> customs = (java.util.List<?>) call(svc, "customItems");
        check("VF4 customItems 读回 1 项（缺获取变量 / 名称留空均跳过）",
                customs != null && customs.size() == 1
                        && "vip".equals(field(customs.get(0), "id"))
                        && "VIP等级".equals(field(customs.get(0), "name"))
                        && "%fake_title%".equals(field(customs.get(0), "placeholder")),
                "customItems=" + customs);

        // --- VF5：PAPI 缺失 → overrides 全 null 但 configured 保持 ---
        Object ctx1 = call(svc, "snapshotContext", "TestPlayer", java.util.UUID.randomUUID(), null);
        Object ov1 = field(ctx1, "overrides");
        check("VF5 PAPI 缺失 overrides 全 null（回退）",
                allFieldsNull(ov1, "playtime", "today", "coins", "points", "title", "playerName"));
        check("VF5b PAPI 缺失 configured 标记保持（显隐不回退）",
                Boolean.TRUE.equals(field(ctx1, "coinsConfigured"))
                        && Boolean.TRUE.equals(field(ctx1, "titleConfigured")),
                "填了变量就展示的判定与 PAPI 无关");
        check("VF5c PAPI 缺失自定义项目不展示", ((java.util.List<?>) field(ctx1, "customItems")).isEmpty());

        // --- VF6：假 PAPI（动态代理名为 PlaceholderAPI）下端到端解析 ---
        org.bukkit.plugin.Plugin fakePapi = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                Test1693.class.getClassLoader(),
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

        Object ctx2 = call(svc, "snapshotContext", "TestPlayer", java.util.UUID.randomUUID(), null);
        Object ov2 = field(ctx2, "overrides");
        check("VF6a total-playtime 解析", "77时20分".equals(field(ov2, "playtime")), "实际 " + field(ov2, "playtime"));
        check("VF6b coins 解析", "9999.5".equals(field(ov2, "coins")), "实际 " + field(ov2, "coins"));
        check("VF6c title 首尾空白清洗后解析", "&6[大佬]&f".equals(field(ov2, "title")), "实际 " + field(ov2, "title"));
        check("VF6d player-name 解析", "大老板".equals(field(ov2, "playerName")), "实际 " + field(ov2, "playerName"));
        check("VF6e 留空项 today=null", field(ov2, "today") == null);
        java.util.List<?> resolved2 = (java.util.List<?>) field(ctx2, "customItems");
        check("VF6f 自定义项目解析（正常返回的保留）",
                resolved2.size() == 1
                        && "VIP等级".equals(field(resolved2.get(0), "name"))
                        && "&6[大佬]&f".equals(field(resolved2.get(0), "value")),
                "resolved=" + resolved2);

        // --- VF7/VF8：buildItems 反射端到端 ---
        Class<?> statsCls = Class.forName("cn.kerong.penguin.spigot.stats.PlayerStatsManager$PlayerStats");
        Object stats = statsCls.getDeclaredConstructor().newInstance();

        // coinsConfigured=true 且读取失败（null）且无 Vault → 「未获取成功」而不是隐藏
        // （点券未填变量 → 保持旧版隐藏行为，与「填了变量才展示」对照）
        Object ctxFail = newContext(svcCls, stats,
                newOverrides(svcCls, null, null, null, null, null, null),
                true, false, true, java.util.List.of());
        Object snapFail = newSnapshot(stats, "暂无", "未检测到", null, "无", "暂无", "无", null, null,
                false, false, false, ctxFail);
        java.util.List<?> itemsFail = buildItems(snapFail, null);
        String coinsFail = itemValue(itemsFail, "金币");
        check("VF7a 填了变量读不到且无 Vault → 金币展示「未获取成功」",
                "未获取成功".equals(coinsFail), "实际 " + coinsFail);
        String titleFail = itemValue(itemsFail, "称号");
        check("VF7b 填了变量读不到且无称号来源 → 称号「未获取成功」",
                "未获取成功".equals(titleFail), "实际 " + titleFail);
        String pointsFail = itemValue(itemsFail, "点券");
        check("VF7c 未填变量且 PlayerPoints 未装 → 点券仍隐藏", pointsFail == null, "实际 " + pointsFail);

        // 未配置变量 + 依赖在 → 旧版行为（Vault 有值 formatAmount）
        Object ctxOld = newContext(svcCls, stats,
                newOverrides(svcCls, null, null, null, null, null, null),
                false, false, false, java.util.List.of());
        Object snapOld = newSnapshot(stats, "暂无", "未检测到", 1600.0, "Vault", "暂无", "无", null, null,
                true, false, false, ctxOld);
        java.util.List<?> itemsOld = buildItems(snapOld, null);
        check("VF7d 未配置变量 → 金币走自带 formatAmount(1600)=1.6k",
                "1.6k".equals(itemValue(itemsOld, "金币")), "实际 " + itemValue(itemsOld, "金币"));
        check("VF7e 未配置变量 → 无 Vault 的旧配置隐藏金币",
                itemValue(buildItems(newSnapshot(stats, "暂无", "未检测到", null, "无", "暂无", "无", null, null,
                        false, false, false, ctxOld), null), "金币") == null);

        // 自定义项目追加在末尾（快照上下文带解析结果）
        Object customResolved = svcCls.getClassLoader().loadClass(
                "cn.kerong.penguin.spigot.stats.PlaceholderVariableService$ResolvedCustomItem")
                .getConstructor(String.class, String.class, String.class)
                .newInstance("vip", "VIP等级", "Lv.6");
        Object ctxCustom = newContext(svcCls, stats,
                newOverrides(svcCls, null, null, null, null, null, null),
                false, false, false, java.util.List.of(customResolved));
        Object snapCustom = newSnapshot(stats, "暂无", "未检测到", null, "无", "暂无", "无", null, null,
                false, false, false, ctxCustom);
        java.util.List<?> itemsCustom = buildItems(snapCustom, null);
        check("VF8 自定义项目追加在卡片末尾",
                "Lv.6".equals(itemValue(itemsCustom, "VIP等级")), "实际 " + itemValue(itemsCustom, "VIP等级"));

        // --- VF9：resolveServerTps ---
        Object tps = call(svc, "resolveServerTps");
        check("VF9a resolveServerTps → 19.6（假 PAPI 采用）", "19.6".equals(tps), "实际 " + tps);
        config.set("variable-fetch.tps", "");
        Object tps2 = call(svc, "resolveServerTps");
        check("VF9b tps 未配置 → null（回退自采集）", tps2 == null, "实际 " + tps2);
        config.set("variable-fetch.tps", "%fake_tps%");

        // --- VF10：resolveBatch ---
        java.util.UUID aliceUuid = java.util.UUID.randomUUID();
        Class<?> pairCls = Class.forName("cn.kerong.shaded.kotlin.Pair");
        java.util.List<Object> players = java.util.List.of(
                pairCls.getConstructor(Object.class, Object.class).newInstance("Alice", aliceUuid),
                pairCls.getConstructor(Object.class, Object.class).newInstance("Bob", null));
        Object batch = call(svc, "resolveBatch", players, "player-name", "coins");
        @SuppressWarnings("unchecked")
        java.util.Map<String, ?> batchMap = (java.util.Map<String, ?>) batch;
        Object alicePair = batchMap.get("Alice");
        check("VF10a resolveBatch Alice 名覆盖", alicePair != null && "大老板".equals(field(alicePair, "first")),
                "实际 " + (alicePair == null ? null : field(alicePair, "first")));
        check("VF10b resolveBatch Alice 值覆盖", alicePair != null && "9999.5".equals(field(alicePair, "second")),
                "实际 " + (alicePair == null ? null : field(alicePair, "second")));
        Object bobPair = batchMap.get("Bob");
        check("VF10c resolveBatch Bob 同样解析", bobPair != null && "大老板".equals(field(bobPair, "first")));
        // 未注册变量作值键 → null 覆盖（回退自带）
        Object batch2 = call(svc, "resolveBatch", players, "player-name", "title");
        @SuppressWarnings("unchecked")
        java.util.Map<String, ?> batch2Map = (java.util.Map<String, ?>) batch2;
        // title 配置为 %fake_title%（可解析）——换成未注册场景：临时改配置
        config.set("variable-fetch.title", "%unregistered_var%");
        Object batch3 = call(svc, "resolveBatch", players, "player-name", "title");
        @SuppressWarnings("unchecked")
        java.util.Map<String, ?> batch3Map = (java.util.Map<String, ?>) batch3;
        Object alice3 = batch3Map.get("Alice");
        check("VF10d 未注册变量 → 名覆盖保留 / 值覆盖 null（回退自带）",
                alice3 != null && "大老板".equals(field(alice3, "first")) && field(alice3, "second") == null,
                "实际 " + alice3);
        config.set("variable-fetch.title", " %fake_title% ");
        check("VF10e 全未配置时 resolveBatch 空表（零开销路径）",
                ((java.util.Map<?, ?>) call(svc, "resolveBatch", players, "today-playtime", "points")).isEmpty());

        System.out.println("== PF 组：查性能最大人数 ==");
        Class<?> snapCls = Class.forName("cn.kerong.bot.PerformanceSnapshot");
        Object companion = snapCls.getField("Companion").get(null);
        Object captured = snapCls.getClasses(); // 触发类链接
        java.lang.reflect.Method capture = null;
        for (java.lang.reflect.Method m : companion.getClass().getMethods()) {
            if (m.getName().equals("capture") && m.getParameterCount() == 5) {
                capture = m;
                break;
            }
        }
        check("PF1a 五参 capture 工厂在位", capture != null);
        Object snapshot = capture.invoke(companion, 19.5, 12.3, 0.25, 7, 20);
        check("PF1b maxPlayers=20 读回", Integer.valueOf(20).equals(call(snapshot, "getMaxPlayers")),
                "实际 " + call(snapshot, "getMaxPlayers"));

        Class<?> trackerCls = Class.forName("cn.kerong.penguin.spigot.stats.MotdDisplayTracker");
        Object tracker = trackerCls.getField("INSTANCE").get(null);
        call(tracker, "record", 50);
        check("PF2a MotdDisplayTracker.record(50) → lastDisplayMax=50",
                Integer.valueOf(50).equals(call(tracker, "getLastDisplayMax")));
        call(tracker, "record", 0);
        check("PF2b record(0) 忽略（非法值）",
                Integer.valueOf(50).equals(call(tracker, "getLastDisplayMax")));

        Class<?> rendererCls = Class.forName("cn.kerong.penguin.spigot.render.PerformanceRenderer");
        Object renderer = rendererCls.getField("INSTANCE").get(null);
        Class<?> dataCls = Class.forName("cn.kerong.penguin.spigot.render.PerformanceRenderer$PerformanceData");
        Object data = dataCls.getConstructor(
                String.class, String.class, snapCls,
                java.awt.image.BufferedImage.class,
                Integer.class, boolean.class, String.class)
                .newInstance("测试服", "12:30", snapshot, null, 20, true, "19.6");
        byte[] png = (byte[]) call(renderer, "render", data);
        check("PF3a 性能卡片端到端渲染（N/M + MOTD 展示值 + TPS 变量）",
                png != null && png.length > 10_000 && (png[0] & 0xFF) == 0x89 && png[1] == 0x50,
                "PNG " + (png == null ? 0 : png.length) + " 字节");
        int height = (Integer) call(renderer, "measureHeight");
        check("PF3b measureHeight 返回正常", height > 700, "实际 " + height);

        java.lang.reflect.Method parse = rendererCls.getDeclaredMethod("parseDoubleOrNull", String.class);
        parse.setAccessible(true);
        check("PF4a parseDoubleOrNull(\"19.6\") → 19.6",
                Double.valueOf(19.6).equals(parse.invoke(renderer, "19.6")));
        check("PF4b parseDoubleOrNull(\"1,234.5\") → 1234.5（千分位）",
                Double.valueOf(1234.5).equals(parse.invoke(renderer, "1,234.5")));
        check("PF4c parseDoubleOrNull(\"TPS 正常\") → null（非数值仅展示）",
                parse.invoke(renderer, "TPS 正常") == null);

        System.out.println("== CD 组：命令删除 ==");
        boolean motdGone;
        try {
            Class.forName("cn.kerong.bot.events.commands.MotdCommands");
            motdGone = false;
        } catch (ClassNotFoundException expected) {
            motdGone = true;
        }
        check("CD1 MotdCommands 类不存在", motdGone);

        Class<?> publicCls = Class.forName("cn.kerong.bot.events.commands.PublicCommands");
        Class<?> commandsAnn = Class.forName("cn.kerong.bot.events.commands.Commands");
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.Set<String> methodNames = new java.util.HashSet<>();
        for (java.lang.reflect.Method m : publicCls.getDeclaredMethods()) {
            methodNames.add(m.getName());
            java.lang.annotation.Annotation ann = m.getAnnotation((Class<? extends java.lang.annotation.Annotation>) commandsAnn);
            if (ann != null) {
                try {
                    Object value = commandsAnn.getMethod("value").invoke(ann);
                    for (String s : (String[]) value) {
                        names.add(s);
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        check("CD2a PublicCommands 注解面无 motd / 在线服务器",
                !names.contains("motd") && !names.contains("在线服务器"),
                "命令面 " + names);
        check("CD2b 方法名无 motd / queryServers",
                !methodNames.contains("motd") && !methodNames.contains("queryServers"));
        check("CD2c 常规命令保留", names.contains("查信息") && names.contains("查在线") && names.contains("帮助"));

        Class<?> menuCls = Class.forName("cn.kerong.bot.MenuManager");
        Object menu = menuCls.getField("INSTANCE").get(null);
        java.util.List<?> panelItems = (java.util.List<?>) field(menu, "PANEL_ITEMS");
        java.util.Set<String> panelNames = new java.util.HashSet<>();
        for (Object item : panelItems) {
            panelNames.add((String) field(item, "name"));
        }
        check("CD3 面板 19 项且无「在线服务器」/「motd」",
                panelItems.size() == 19 && !panelNames.contains("在线服务器") && !panelNames.contains("motd"),
                "面板 " + panelItems.size() + " 项");

        Class<?> tplCls = Class.forName("cn.kerong.penguin.spigot.qqbind.MessageTemplates");
        Object tpl = tplCls.getField("INSTANCE").get(null);
        java.util.List<?> templateIds = (java.util.List<?>) call(tpl, "getTEMPLATE_IDS");
        check("CD4 模板清单无 motd-* / server-status",
                templateIds.stream().noneMatch(id -> String.valueOf(id).startsWith("motd-")
                        || "server-status".equals(id))
                        && templateIds.contains("help"),
                "模板 " + templateIds.size() + " 项");

        System.out.println("== TF 组：1.6.8 回归 ==");
        Class<?> qs = Class.forName("cn.kerong.penguin.spigot.stats.QueryInfoService");
        Object qsInst = qs.getField("INSTANCE").get(null);
        String r1 = (String) qs.getMethod("formatPlayTime", long.class).invoke(qsInst, 278400L);
        check("TF1 3天5时20分 → 77时20分（小时制保持）", "77时20分".equals(r1), "实际 " + r1);

        System.out.println("== J 组：JAR 基线 ==");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(
                "build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.9.3.jar")) {
            String toml = new String(zip.getInputStream(
                    zip.getEntry("META-INF/neoforge.mods.toml")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("J1 neoforge.mods.toml 1.6.9.3", toml.contains("version = \"1.6.9.3\""), "版本行");
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(
                "build/dist/KERONGPenguin_Spigot-1.6.9.3.jar")) {
            String pluginYml = new String(zip.getInputStream(
                    zip.getEntry("plugin.yml")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("J2 plugin.yml 1.6.9.3", pluginYml.contains("version: '1.6.9.3'"), "版本行");
        }

        fm.unregister();

        System.out.println();
        System.out.println("共 " + (pass + fail) + " 项：PASS " + pass + " / FAIL " + fail);
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("smoke1693 全部通过");
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

    /** 反射构造 Overrides（六字段全 String?）。 */
    static Object newOverrides(Class<?> svcCls, Object... values) throws Exception {
        Class<?> ovCls = Class.forName(
                "cn.kerong.penguin.spigot.stats.PlaceholderVariableService$Overrides");
        java.lang.reflect.Constructor<?> ctor = ovCls.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(values);
    }

    /** 反射构造 Context（overrides + 三 configured + customItems）。 */
    static Object newContext(Class<?> svcCls, Object stats, Object overrides,
                             boolean coins, boolean points, boolean title,
                             java.util.List<?> customItems) throws Exception {
        Class<?> ctxCls = Class.forName(
                "cn.kerong.penguin.spigot.stats.PlaceholderVariableService$Context");
        java.lang.reflect.Constructor<?> ctor = ctxCls.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(overrides, coins, points, title, customItems);
    }

    /** 反射构造 MainThreadSnapshot（13 参私有构造器，末位为 Context）。 */
    static Object newSnapshot(Object stats, Object... tail) throws Exception {
        Class<?> cls = Class.forName("cn.kerong.penguin.spigot.stats.QueryInfoService$MainThreadSnapshot");
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
