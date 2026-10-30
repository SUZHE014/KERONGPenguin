import cn.kerong.bot.UpdateChecker;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 1.6.7.9 功能冒烟（独立 JVM，headless）。
 *
 * 覆盖：
 *  1. 快捷面板先删后建修复：
 *     - 错误码归一化（30013 与 40030013 双形态折算为 30013）
 *     - 错误码人话解释（30009 / 30013 / 30020 / 未知）
 *     - panel_id 解析、文案长度单位
 *     - 类结构：createPanelWithLimitRecovery / deleteAllPanels 在位，
 *       旧 PUT 原地更新路径（updatePanel / fetchPanelDetail）已移除
 *  2. 更新检查镜像回退：
 *     - sourceUrls 默认双源（官网域名 + 内置 IP 直连镜像）
 *     - enabled 默认开；isNewer 逐段比较
 *     - httpGet 本地 HTTP 服务器（200 / 404 / mirror 形态）
 *  3. NeoForge 多版本适配（NF 分发 JAR 独立类加载器）：
 *     - 配置阶段事件类探测：20.4 构件 → OnGameConfigurationEvent；
 *       21.1 构件 → RegisterConfigurationTasksEvent（先试新形态）
 *     - BindGateBusListener（Event 基类监听器）与 ICustomConfigurationTask 桥
 *     - ServerChatEvent.getRawText 双代签名一致
 *  4. 双 toml：mods.toml 与 neoforge.mods.toml 并存、[[mods]] 段头正确
 *     （1.6.7.8 及之前误写 [ods]]）、版本范围 [1.20.4,1.22)
 *  5. 基线：双端 config.yml 的 update-check 段含 mirror-url 注释；
 *     plugin.yml 1.6.7.9；目录条目；Bukkit 流重载 Reader 重载在位
 *
 * 编译运行（仓库根目录）：
 *   javac -cp build/dist/KERONGPenguin_Spigot-1.6.7.9.jar \
 *     -d scripts/smoke1679-out scripts/smoke1679/Test1679.java
 *   java -Djava.awt.headless=true \
 *     -cp scripts/smoke1679-out:build/dist/KERONGPenguin_Spigot-1.6.7.9.jar:\
 *     scripts/deps-work/paper-api-1.20.4.jar:scripts/deps-work/adventure-api-4.17.0.jar:\
 *     scripts/deps-work/adventure-key-4.17.0.jar:scripts/deps-work/examination-api-1.3.0.jar:\
 *     scripts/deps-work/examination-string-1.3.0.jar:scripts/deps-work/snakeyaml-2.2.jar \
 *     Test1679
 */
public class Test1679 {

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

    @SuppressWarnings("unchecked")
    static <T> T callStatic(Class<?> cls, String method, Object... args) throws Exception {
        Method m = null;
        outer:
        for (Method mm : cls.getDeclaredMethods()) {
            // Kotlin internal 方法在字节码带 $production_sources_for_module 后缀，
            // 按前缀匹配
            if (!mm.getName().startsWith(method) || mm.getParameterCount() != args.length) continue;
            for (int i = 0; i < args.length; i++) {
                Class<?> pt = mm.getParameterTypes()[i];
                if (!pt.isInstance(args[i]) && !boxMatches(pt, args[i])) continue outer;
            }
            m = mm;
            break;
        }
        if (m == null) throw new NoSuchMethodException(method);
        m.setAccessible(true);
        // Kotlin object 的方法是实例方法：静态上下文调用时自动取 INSTANCE
        Object receiver = null;
        if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
            try {
                receiver = cls.getDeclaredField("INSTANCE").get(null);
            } catch (NoSuchFieldException ignored) {
                throw new IllegalStateException(method + " 是实例方法但类无 INSTANCE 单例");
            }
        }
        return (T) m.invoke(receiver, args);
    }

    static boolean boxMatches(Class<?> param, Object arg) {
        if (param == int.class && arg instanceof Integer) return true;
        if (param == long.class && arg instanceof Long) return true;
        if (param == boolean.class && arg instanceof Boolean) return true;
        return false;
    }

    static ZipEntry zf0(ZipFile zf, String name) {
        ZipEntry e = zf.getEntry(name);
        if (e == null) throw new IllegalStateException("JAR 缺少 " + name);
        return e;
    }

    public static void main(String[] args) throws Exception {
        String spigotJar = "build/dist/KERONGPenguin_Spigot-1.6.7.9.jar";
        String nfJar = "build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.7.9.jar";

        // ---------------- 1. 快捷面板先删后建 ----------------
        Class<?> menu = Class.forName("cn.kerong.bot.MenuManager");

        int n1 = callStatic(menu, "normalizedErrorCode", "{\"code\":30013}");
        check("PM-1 归一化：v2 形态 30013 → 30013", n1 == 30013, String.valueOf(n1));
        int n2 = callStatic(menu, "normalizedErrorCode", "{\"code\":40030013}");
        check("PM-2 归一化：400 前缀形态 40030013 → 30013", n2 == 30013, String.valueOf(n2));
        int n3 = callStatic(menu, "normalizedErrorCode", "{\"message\":\"x\"}");
        // fastjson getIntValue 缺字段返回 0（非 -1）：0 不匹配任何面板错误码常量，
        // describePanelError 对 0 返回空串，行为无害——断言实际语义
        check("PM-3 归一化：无 code → 0（非错误码，不触发人话解释）", n3 == 0, String.valueOf(n3));
        int n4 = callStatic(menu, "normalizedErrorCode", "{\"code\":40030009}");
        check("PM-4 归一化：40030009 → 30009", n4 == 30009, String.valueOf(n4));

        String d1 = callStatic(menu, "describePanelError", "{\"code\":30013}");
        check("PM-5 人话：30013 → 超出面板数量限制", d1.contains("超出面板数量限制"), d1);
        String d2 = callStatic(menu, "describePanelError", "{\"code\":40030009}");
        check("PM-6 人话：30009 → 操作进行中", d2.contains("操作进行中"), d2);
        String d3 = callStatic(menu, "describePanelError", "{\"code\":30020}");
        check("PM-7 人话：30020 → 内容安全拦截", d3.contains("内容安全拦截"), d3);
        String d4 = callStatic(menu, "describePanelError", "{\"code\":99999}");
        check("PM-8 人话：未知码 → 空串", d4 != null && d4.isEmpty(), String.valueOf(d4));

        String p1 = callStatic(menu, "parsePanelId", "{\"panel_id\":\"p-abc-1\"}");
        check("PM-9 parsePanelId 命中", "p-abc-1".equals(p1), String.valueOf(p1));
        String p2 = callStatic(menu, "parsePanelId", "{}");
        check("PM-10 parsePanelId 缺字段 → null", p2 == null, String.valueOf(p2));

        int u1 = callStatic(menu, "displayUnits", "帮助");
        check("PM-11 长度单位：中文按 2", u1 == 4, String.valueOf(u1));
        int u2 = callStatic(menu, "displayUnits", "motd");
        check("PM-12 长度单位：半角按 1", u2 == 4, String.valueOf(u2));

        boolean hasRecovery = false, hasDeleteAll = false, noUpdate = true, noDetail = true;
        for (Method m : menu.getDeclaredMethods()) {
            if (m.getName().equals("createPanelWithLimitRecovery")) hasRecovery = true;
            if (m.getName().equals("deleteAllPanels")) hasDeleteAll = true;
            if (m.getName().equals("updatePanel")) noUpdate = false;
            if (m.getName().equals("fetchPanelDetail")) noDetail = false;
        }
        check("PM-13 createPanelWithLimitRecovery 在位", hasRecovery);
        check("PM-14 deleteAllPanels（先删后建主体）在位", hasDeleteAll);
        check("PM-15 旧 PUT 原地更新 updatePanel 已移除", noUpdate);
        check("PM-16 旧 fetchPanelDetail 已移除", noDetail);

        Field errLimit = menu.getDeclaredField("PANEL_ERR_LIMIT");
        errLimit.setAccessible(true);
        check("PM-17 PANEL_ERR_LIMIT = 30013", errLimit.getInt(null) == 30013);
        Field errConflict = menu.getDeclaredField("PANEL_ERR_CONFLICT");
        errConflict.setAccessible(true);
        check("PM-18 PANEL_ERR_CONFLICT = 30009", errConflict.getInt(null) == 30009);

        // ---------------- 2. 更新检查镜像回退 ----------------
        List<String> sources = callStatic(UpdateChecker.class, "sourceUrls");
        check("UC-1 默认双源（官网 + 内置镜像）", sources.size() == 2
                        && sources.get(0).equals("https://kerong.xin/penguin/version.json")
                        && sources.get(1).equals("https://156.233.228.248/penguin/version.json"),
                String.valueOf(sources));
        Object checkerInstance = UpdateChecker.class.getDeclaredField("INSTANCE").get(null);
        boolean enabledDefault = (Boolean) callStatic(UpdateChecker.class, "enabled");
        check("UC-2 enabled 默认开（无实例环境）", enabledDefault);
        boolean newer1 = callStatic(UpdateChecker.class, "isNewer", "1.6.7.9", "1.6.7.8");
        check("UC-3 isNewer：1.6.7.9 > 1.6.7.8", newer1);
        boolean newer2 = callStatic(UpdateChecker.class, "isNewer", "1.6.7.8", "1.6.7.9");
        check("UC-4 isNewer：1.6.7.8 不 > 1.6.7.9", !newer2);
        boolean newer3 = callStatic(UpdateChecker.class, "isNewer", "1.6.7.9", "1.6.7.9");
        check("UC-5 isNewer：等版本不为新", !newer3);

        // 本地 HTTP 服务器：httpGet 行为（200 / 404 / mirror 形态）
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/ok", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{\"version\":\"9.9.9\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        server.createContext("/gone", exchange -> exchange.sendResponseHeaders(404, -1));
        server.start();
        try {
            Method httpGet = UpdateChecker.class.getDeclaredMethod("httpGet", String.class, int.class, boolean.class);
            httpGet.setAccessible(true);
            Object inst = UpdateChecker.class.getDeclaredField("INSTANCE").get(null);
            byte[] body = (byte[]) httpGet.invoke(inst, "http://127.0.0.1:" + port + "/ok", 3000, false);
            String parsed = new String(body, StandardCharsets.UTF_8);
            check("UC-6 httpGet 主源形态 200", parsed.contains("9.9.9"), parsed);
            byte[] body2 = (byte[]) httpGet.invoke(inst, "http://127.0.0.1:" + port + "/ok", 3000, true);
            check("UC-7 httpGet 镜像形态（http 直连语义不变）",
                    body2 != null && new String(body2, StandardCharsets.UTF_8).contains("9.9.9"));
            boolean threw = false;
            try {
                httpGet.invoke(inst, "http://127.0.0.1:" + port + "/gone", 3000, false);
            } catch (Exception e) {
                threw = e.getCause() instanceof java.io.IOException || e instanceof java.io.IOException;
            }
            check("UC-8 httpGet 非 200 抛 IOException（供多源回退捕获）", threw);
        } finally {
            server.stop(0);
        }
        check("UC-9 本地服务器被实际访问", hits.get() >= 2, String.valueOf(hits.get()));

        // ---------------- 3. NeoForge 多版本：配置阶段事件探测 ----------------
        String libsDir = "server-NeoForge/libs";
        String nf204 = libsDir + "/neoforge-20.4.253-universal.jar";
        String nf211 = "scripts/deps-work/nf-libs/neoforge-21.1.252-universal.jar";
        String mojmap204 = libsDir + "/minecraft-1.20.4-mojmap.jar";
        String loader204 = libsDir + "/loader-2.0.17.jar";
        String bus204 = libsDir + "/bus-7.2.0.jar";

        // 3a. 20.4 形态：classpath 只有 20.4 构件 → 探测到 OnGameConfigurationEvent
        // （brigadier / authlib 一并放入：方法签名解析会级联引用）
        try (URLClassLoader cl204 = new URLClassLoader(new URL[]{
                Paths.get(nfJar).toUri().toURL(),
                Paths.get(nf204).toUri().toURL(),
                Paths.get(mojmap204).toUri().toURL(),
                Paths.get(loader204).toUri().toURL(),
                Paths.get(bus204).toUri().toURL(),
                Paths.get(libsDir + "/brigadier.jar").toUri().toURL(),
                Paths.get(libsDir + "/authlib.jar").toUri().toURL(),
        }, Test1679.class.getClassLoader())) {
            Class<?> gate = cl204.loadClass("cn.kerong.penguin.neoforge.BindLoginGate");
            Method resolve = gate.getDeclaredMethod("resolveConfigurationEventClass");
            resolve.setAccessible(true);
            Object gateInstance = gate.getDeclaredField("INSTANCE").get(null);
            Class<?> eventClass = (Class<?>) resolve.invoke(gateInstance);
            check("MV-1 20.4 环境：探测到 OnGameConfigurationEvent",
                    eventClass != null
                            && eventClass.getName().equals("net.neoforged.neoforge.network.event.OnGameConfigurationEvent"),
                    eventClass == null ? "null" : eventClass.getName());
            Class<?> listener = cl204.loadClass("cn.kerong.penguin.neoforge.BindLoginGate$BindGateBusListener");
            Class<?> eventBase = cl204.loadClass("net.neoforged.bus.api.Event");
            Class<? extends java.lang.annotation.Annotation> subscribeAnn =
                    (Class<? extends java.lang.annotation.Annotation>) cl204.loadClass("net.neoforged.bus.api.SubscribeEvent");
            Method onEvent = listener.getDeclaredMethod("onModBusEvent", eventBase);
            check("MV-2 BindGateBusListener 以 Event 基类监听（跨版本桥）",
                    onEvent.isAnnotationPresent(subscribeAnn));
            // BindGateTask 实现 ICustomConfigurationTask（双时代一致接口）
            Class<?> task = cl204.loadClass("cn.kerong.penguin.neoforge.BindLoginGate$BindGateTask");
            Class<?> ict = cl204.loadClass("net.neoforged.neoforge.network.configuration.ICustomConfigurationTask");
            check("MV-3 BindGateTask 实现 ICustomConfigurationTask（跨版本桥梁）",
                    ict.isAssignableFrom(task));
        }

        // 3b. 21.1 形态：classpath 换 21.1 universal（net.minecraft 仍借 20.4 mojmap
        //     解析签名，不影响事件类探测语义）→ 先试 RegisterConfigurationTasksEvent
        try (URLClassLoader cl211 = new URLClassLoader(new URL[]{
                Paths.get(nfJar).toUri().toURL(),
                Paths.get(nf211).toUri().toURL(),
                Paths.get(mojmap204).toUri().toURL(),
                Paths.get(loader204).toUri().toURL(),
                Paths.get(bus204).toUri().toURL(),
                Paths.get(libsDir + "/brigadier.jar").toUri().toURL(),
                Paths.get(libsDir + "/authlib.jar").toUri().toURL(),
        }, Test1679.class.getClassLoader())) {
            Class<?> gate = cl211.loadClass("cn.kerong.penguin.neoforge.BindLoginGate");
            Method resolve = gate.getDeclaredMethod("resolveConfigurationEventClass");
            resolve.setAccessible(true);
            Object gateInstance = gate.getDeclaredField("INSTANCE").get(null);
            Class<?> eventClass = (Class<?>) resolve.invoke(gateInstance);
            check("MV-4 21.1 环境：优先探测 RegisterConfigurationTasksEvent",
                    eventClass != null
                            && eventClass.getName().equals("net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent"),
                    eventClass == null ? "null" : eventClass.getName());
        }

        // 3c. ServerChatEvent.getRawText 在 20.4 与 21.1 字节码签名一致（编译目标
        //     20.4 直接调用，运行 1.21.x 无 NoSuchMethodError 风险）
        try (URLClassLoader cl204b = new URLClassLoader(new URL[]{
                Paths.get(nf204).toUri().toURL(), Paths.get(mojmap204).toUri().toURL(),
                Paths.get(bus204).toUri().toURL(),
                Paths.get(libsDir + "/brigadier.jar").toUri().toURL(),
                Paths.get(libsDir + "/authlib.jar").toUri().toURL(),
        }, Test1679.class.getClassLoader());
             URLClassLoader cl211b = new URLClassLoader(new URL[]{
                     Paths.get(nf211).toUri().toURL(), Paths.get(mojmap204).toUri().toURL(),
                     Paths.get(bus204).toUri().toURL(),
                     Paths.get(libsDir + "/brigadier.jar").toUri().toURL(),
                     Paths.get(libsDir + "/authlib.jar").toUri().toURL(),
             }, Test1679.class.getClassLoader())) {
            Method m204 = cl204b.loadClass("net.neoforged.neoforge.event.ServerChatEvent")
                    .getMethod("getRawText");
            Method m211 = cl211b.loadClass("net.neoforged.neoforge.event.ServerChatEvent")
                    .getMethod("getRawText");
            check("MV-5 ServerChatEvent.getRawText 双代签名一致（String）",
                    m204.getReturnType() == String.class && m211.getReturnType() == String.class
                            && m204.getReturnType() == m211.getReturnType());
        }

        // ---------------- 4. 双 toml ----------------
        try (ZipFile zf = new ZipFile(nfJar)) {
            String modern = new String(zf.getInputStream(
                    zf.getEntry("META-INF/neoforge.mods.toml")).readAllBytes(), StandardCharsets.UTF_8);
            String legacy = new String(zf.getInputStream(
                    zf.getEntry("META-INF/mods.toml")).readAllBytes(), StandardCharsets.UTF_8);
            check("TO-1 neoforge.mods.toml 在位（1.20.5+ 读取）", modern.contains("[[mods]]"));
            check("TO-2 mods.toml 在位（1.20.4 读取）", legacy.contains("[[mods]]"));
            check("TO-3 段头 typo 已修复（无 [ods]]）",
                    !modern.contains("[ods]]") && !legacy.contains("[ods]]"));
            check("TO-4 minecraft 版本范围 [1.20.4,1.22)",
                    modern.contains("[1.20.4,1.22)") && legacy.contains("[1.20.4,1.22)"));
            check("TO-5 neoforge 依赖范围 [20.4.0,)",
                    modern.contains("[20.4.0,)") && legacy.contains("[20.4.0,)"));
            check("TO-6 版本 1.6.7.9",
                    modern.contains("version = \"1.6.7.9\"") && legacy.contains("version = \"1.6.7.9\""));
            check("TO-7 displayTest 保持 IGNORE_SERVER_VERSION",
                    modern.contains("IGNORE_SERVER_VERSION") && legacy.contains("IGNORE_SERVER_VERSION"));
            check("TO-8 双文件内容一致", modern.equals(legacy));
        }

        // ---------------- 5. 基线 ----------------
        try (ZipFile zs = new ZipFile(spigotJar)) {
            String pluginYml = new String(zs.getInputStream(zf0(zs, "plugin.yml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            check("B-1 plugin.yml 版本 1.6.7.9", pluginYml.contains("version: '1.6.7.9'"));
            String config = new String(zs.getInputStream(zf0(zs, "config.yml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            check("B-2 Spigot config 含 mirror-url 注释", config.contains("mirror-url"));
            check("B-3 Spigot config 含镜像回退说明", config.contains("镜像源"));
            int dirCount = 0, total = 0;
            java.util.Enumeration<? extends ZipEntry> e = zs.entries();
            while (e.hasMoreElements()) {
                ZipEntry en = e.nextElement();
                total++;
                if (en.isDirectory()) dirCount++;
            }
            check("B-4 目录条目在位（SDK 组件扫描）", dirCount > 500, String.valueOf(dirCount));
            check("B-5 条目总量量级正常", total > 11000, String.valueOf(total));
        }
        try (ZipFile zn = new ZipFile(nfJar)) {
            String config = new String(zn.getInputStream(zf0(zn, "config.yml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            check("B-6 NeoForge config 含 mirror-url 注释", config.contains("mirror-url"));
        }
        // Bukkit 流重载（1.6.7.2 坑回归）
        Class<?> yaml = Class.forName("org.bukkit.configuration.file.YamlConfiguration");
        boolean hasReaderLoad = false;
        for (Method m : yaml.getMethods()) {
            if (m.getName().equals("loadConfiguration")
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == java.io.Reader.class) hasReaderLoad = true;
        }
        check("B-7 YamlConfiguration.loadConfiguration(Reader) 在位", hasReaderLoad);
        // 旧包名残留（1.6.6.2 坑回归）
        try (ZipFile zs = new ZipFile(spigotJar)) {
            boolean noLegacy = zs.getEntry("cn/huohuas001/") == null;
            check("B-8 无旧包名残留", noLegacy);
        }

        System.out.println();
        System.out.println("== smoke1679 结果：PASS " + passed + " / FAIL " + failed + " ==");
        if (failed > 0) System.exit(1);
    }
}
