import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 1.6.7.11 功能冒烟（独立 JVM，headless）。
 *
 * 本版核心：修复 NeoForge 登录闸门（BindLoginGate）挂载失败——
 * 1.6.7.9 起「@SubscribeEvent + 事件基类 Event 参数」的监听器注册 mod 总线，
 * 而 FML mod 总线以 markerType(IModBusEvent) 构造，EventBus 校验监听参数必须
 * 实现 IModBusEvent，基类 Event 不满足 → register() 抛
 * IllegalArgumentException（"takes an argument that is not valid for this bus"），
 * 闸门整个没挂上；且外层无论成败一律报「已挂载」误导排查。
 * 修复：反射调用 IEventBus.addListener(Class, Consumer) 以「运行时探测到的
 * 具体事件类」精确注册（20.4 OnGameConfigurationEvent / 20.5+
 * RegisterConfigurationTasksEvent，两代事件类都实现 IModBusEvent，可通过校验），
 * registerTo 返回真实结果。
 *
 * 教训（本次补上的冒烟缺口）：smoke1679 只测了「事件类探测」，没有在
 * **真实 EventBus** 上走一遍注册+分发——这正是 1.6.7.9 带病发版的根因。
 * 本冒烟的 G 组全部在按 FML 方式构造的真实 mod 总线上端到端执行。
 *
 * 覆盖：
 *  G 组（登录闸门修复）：
 *   - G1 20.4 环境：registerTo 在真实 mod 总线上返回 true（线上故障回归）
 *   - G2 负向对照：旧式 Event 基类监听器在同款总线上被拒（复现原始故障）
 *   - G3 20.4 环境：post OnGameConfigurationEvent → 闸门消费器真实收到
 *     （getListenerMethod/registerMethod 反射缓存被填充）
 *   - G4 幂等：重复 registerTo 返回 true 不抛异常
 *   - G5 21.1 环境（bus 8.0.5 + loader 4.0.44）：registerTo 返回 true 且
 *     post RegisterConfigurationTasksEvent → 消费器真实收到
 *   - G6 未识别环境（无 neoforge universal）：registerTo 如实返回 false
 *   - G7 registerTo 签名为 (IEventBus)Z（boolean 返回，供调用方如实反馈）
 *  TO 组（双 toml）：[[mods]] 段头、无损坏段头、版本 1.6.7.11、版本范围、
 *     双文件一致
 *  B 组（基线）：JAR 无 BindGateBusListener 残留；plugin.yml 1.6.7.11；
 *     目录条目；PanelSyncResult 保持（1.6.7.10 修复回归）
 *
 * 编译运行（仓库根目录，javac/java 用 tooling JDK21）：
 *   javac -cp build/dist/KERONGPenguin_Spigot-1.6.7.11.jar \
 *     -d scripts/smoke16711-out scripts/smoke16711/Test16711.java
 *   javac -cp server-NeoForge/libs/bus-7.2.0.jar \
 *     -d scripts/smoke16711-out-cp scripts/smoke16711/LegacyListener16711.java
 *   java -Djava.awt.headless=true \
 *     -cp scripts/smoke16711-out:build/dist/KERONGPenguin_Spigot-1.6.7.11.jar:\
 *     scripts/deps-work/paper-api-1.20.4.jar:scripts/deps-work/adventure-api-4.17.0.jar:\
 *     scripts/deps-work/adventure-key-4.17.0.jar:scripts/deps-work/examination-api-1.3.0.jar:\
 *     scripts/deps-work/examination-string-1.3.0.jar:scripts/deps-work/snakeyaml-2.2.jar \
 *     Test16711
 */
public class Test16711 {

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

    static ZipEntry zf0(ZipFile zf, String name) {
        ZipEntry e = zf.getEntry(name);
        if (e == null) throw new IllegalStateException("JAR 缺少 " + name);
        return e;
    }

    /** 按 FMLModContainer 的方式构造 mod 总线：markerType(IModBusEvent)+allowPerPhasePost。 */
    static Object buildFmlStyleModBus(ClassLoader cl) throws Exception {
        Class<?> busBuilderCls = cl.loadClass("net.neoforged.bus.api.BusBuilder");
        Object builder = busBuilderCls.getMethod("builder").invoke(null);
        Class<?> marker = cl.loadClass("net.neoforged.fml.event.IModBusEvent");
        builder = busBuilderCls.getMethod("markerType", Class.class).invoke(builder, marker);
        builder = busBuilderCls.getMethod("allowPerPhasePost").invoke(builder);
        return busBuilderCls.getMethod("build").invoke(builder);
    }

    /** 调 BindLoginGate.registerTo(bus)，返回 Boolean 结果。 */
    static Boolean callRegisterTo(Class<?> gate, Object gateInstance, Object bus, ClassLoader cl) throws Exception {
        Method m = gate.getMethod("registerTo", cl.loadClass("net.neoforged.bus.api.IEventBus"));
        return (Boolean) m.invoke(gateInstance, bus);
    }

    /** 造一个 ServerConfigurationPacketListener 假监听器（动态代理，全默认返回）。 */
    static Object fakeConfigListener(ClassLoader cl) throws Exception {
        Class<?> scpl = cl.loadClass("net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener");
        return Proxy.newProxyInstance(cl, new Class<?>[]{scpl}, (proxy, method, args) -> {
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) return false;
            if (rt == int.class) return 0;
            if (rt == long.class) return 0L;
            if (rt == double.class) return 0d;
            if (rt == float.class) return 0f;
            if (rt == short.class) return (short) 0;
            if (rt == byte.class) return (byte) 0;
            if (rt == char.class) return (char) 0;
            return null;
        });
    }

    /** 读 BindLoginGate 对象的私有字段（getListenerMethod / registerMethod）。 */
    static Object readGateField(Class<?> gate, Object gateInstance, String field) throws Exception {
        Field f = gate.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(gateInstance);
    }

    public static void main(String[] args) throws Exception {
        String spigotJar = "build/dist/KERONGPenguin_Spigot-1.6.7.11.jar";
        String nfJar = "build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.7.11.jar";
        String libsDir = "server-NeoForge/libs";
        String nf204 = libsDir + "/neoforge-20.4.253-universal.jar";
        String nf211 = "scripts/deps-work/nf-libs/neoforge-21.1.252-universal.jar";
        String mojmap204 = libsDir + "/minecraft-1.20.4-mojmap.jar";
        String loader204 = libsDir + "/loader-2.0.17.jar";
        String loader4 = "scripts/deps-work/nf-libs/loader-4.0.44.jar";
        String bus204 = libsDir + "/bus-7.2.0.jar";
        String bus8 = "scripts/deps-work/nf-libs/bus-8.0.5.jar";
        String outCp = "scripts/smoke16711-out-cp";

        // ---------------- G 组：登录闸门真实总线端到端 ----------------

        // G1~G4：20.4 形态（与线上 1.20.4 相同构件组合）
        try (URLClassLoader cl204 = new URLClassLoader(new URL[]{
                Paths.get(nfJar).toUri().toURL(),
                Paths.get(nf204).toUri().toURL(),
                Paths.get(mojmap204).toUri().toURL(),
                Paths.get(loader204).toUri().toURL(),
                Paths.get(bus204).toUri().toURL(),
                Paths.get(libsDir + "/brigadier.jar").toUri().toURL(),
                Paths.get(libsDir + "/authlib.jar").toUri().toURL(),
                Paths.get("scripts/deps-work/nf-libs/log4j-api-2.22.1.jar").toUri().toURL(),
                Paths.get(outCp).toUri().toURL(),
        }, Test16711.class.getClassLoader())) {
            Class<?> gate = cl204.loadClass("cn.kerong.penguin.neoforge.BindLoginGate");
            Object gateInstance = gate.getDeclaredField("INSTANCE").get(null);

            // G7 registerTo 签名 (IEventBus)Z
            Method reg = gate.getMethod("registerTo", cl204.loadClass("net.neoforged.bus.api.IEventBus"));
            check("G7 registerTo 返回 boolean（供调用方如实反馈）",
                    reg.getReturnType() == boolean.class, reg.getReturnType().getName());

            // G1 真实 mod 总线上挂载成功（1.6.7.9/1.6.7.10 在此抛
            //    IllegalArgumentException 导致闸门从未挂上——线上故障回归点）
            Object bus = buildFmlStyleModBus(cl204);
            Boolean ok = callRegisterTo(gate, gateInstance, bus, cl204);
            check("G1 20.4 真实 mod 总线：registerTo 成功（线上故障回归）",
                    Boolean.TRUE.equals(ok), String.valueOf(ok));

            // G2 负向对照：旧式 Event 基类监听器在同款总线被拒
            //    （证明测试总线具备 FML 校验语义，G1 的通过不是侥幸）
            Object bus2 = buildFmlStyleModBus(cl204);
            Class<?> legacy = cl204.loadClass("LegacyListener16711");
            Object legacyInstance = legacy.getDeclaredConstructor().newInstance();
            boolean rejected = false;
            String rejectMsg = "";
            try {
                bus2.getClass().getMethod("register", Object.class).invoke(bus2, legacyInstance);
            } catch (InvocationTargetException e) {
                Throwable c = e.getCause();
                rejected = c instanceof IllegalArgumentException;
                rejectMsg = String.valueOf(c.getMessage());
            }
            check("G2 旧式 Event 基类监听被 mod 总线拒绝（复现原始故障）",
                    rejected && rejectMsg.contains("not valid for this bus"), rejectMsg);

            // G3 post 真实 OnGameConfigurationEvent → 闸门消费器收到
            //    （放行路径：NeoServerRef 未绑定服务器 → 不注册任务、不踢人）
            Object fakeListener = fakeConfigListener(cl204);
            Class<?> eventCls = cl204.loadClass("net.neoforged.neoforge.network.event.OnGameConfigurationEvent");
            Class<?> scpl204 = cl204.loadClass("net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener");
            Object event = eventCls.getConstructor(scpl204).newInstance(fakeListener);
            Class<?> eventBase = cl204.loadClass("net.neoforged.bus.api.Event");
            bus.getClass().getMethod("post", eventBase).invoke(bus, event);
            Object glm = readGateField(gate, gateInstance, "getListenerMethod");
            Object rm = readGateField(gate, gateInstance, "registerMethod");
            check("G3 20.4 post OnGameConfigurationEvent → 闸门消费器真实收到（getListener 反射链已走通）",
                    glm instanceof Method && ((Method) glm).getName().equals("getListener")
                            && ((Method) glm).getDeclaringClass() == eventCls,
                    glm == null ? "getListenerMethod=null" : "ok");
            check("G3b 事件 register 方法已解析（registerMethod 缓存在位）",
                    rm instanceof Method && ((Method) rm).getName().equals("register"),
                    rm == null ? "registerMethod=null" : "ok");

            // G4 幂等：重复 registerTo 返回 true 不抛
            Boolean again = callRegisterTo(gate, gateInstance, bus2, cl204);
            check("G4 重复 registerTo 幂等返回 true",
                    Boolean.TRUE.equals(again), String.valueOf(again));
        }

        // G5：21.1 形态（bus 8.0.5 + loader 4.0.44 + 21.1 universal）
        try (URLClassLoader cl211 = new URLClassLoader(new URL[]{
                Paths.get(nfJar).toUri().toURL(),
                Paths.get(nf211).toUri().toURL(),
                Paths.get(mojmap204).toUri().toURL(),
                Paths.get(loader4).toUri().toURL(),
                Paths.get(bus8).toUri().toURL(),
                Paths.get(libsDir + "/brigadier.jar").toUri().toURL(),
                Paths.get(libsDir + "/authlib.jar").toUri().toURL(),
                Paths.get("scripts/deps-work/nf-libs/log4j-api-2.22.1.jar").toUri().toURL(),
        }, Test16711.class.getClassLoader())) {
            Class<?> gate = cl211.loadClass("cn.kerong.penguin.neoforge.BindLoginGate");
            Object gateInstance = gate.getDeclaredField("INSTANCE").get(null);
            Object bus = buildFmlStyleModBus(cl211);
            Boolean ok = callRegisterTo(gate, gateInstance, bus, cl211);
            check("G5a 21.1 真实 mod 总线（bus 8.0.5）：registerTo 成功",
                    Boolean.TRUE.equals(ok), String.valueOf(ok));

            Object fakeListener = fakeConfigListener(cl211);
            Class<?> eventCls = cl211.loadClass("net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent");
            Class<?> scpl = cl211.loadClass("net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener");
            Object event = eventCls.getConstructor(scpl).newInstance(fakeListener);
            Class<?> eventBase = cl211.loadClass("net.neoforged.bus.api.Event");
            bus.getClass().getMethod("post", eventBase).invoke(bus, event);
            Object glm = readGateField(gate, gateInstance, "getListenerMethod");
            check("G5b 21.1 post RegisterConfigurationTasksEvent → 闸门消费器真实收到",
                    glm instanceof Method && ((Method) glm).getName().equals("getListener")
                            && ((Method) glm).getDeclaringClass() == eventCls,
                    glm == null ? "getListenerMethod=null" : "ok");
            Object rm = readGateField(gate, gateInstance, "registerMethod");
            check("G5c 21.1 事件 register 方法已解析",
                    rm instanceof Method && ((Method) rm).getName().equals("register"),
                    rm == null ? "registerMethod=null" : "ok");
        }

        // G6：未识别环境（FML/bus 在位但无任何 neoforge universal，事件类不可解析）
        //     → 如实返回 false
        try (URLClassLoader clNone = new URLClassLoader(new URL[]{
                Paths.get(nfJar).toUri().toURL(),
                Paths.get(mojmap204).toUri().toURL(),
                Paths.get(loader204).toUri().toURL(),
                Paths.get(bus204).toUri().toURL(),
                Paths.get(libsDir + "/brigadier.jar").toUri().toURL(),
                Paths.get(libsDir + "/authlib.jar").toUri().toURL(),
                Paths.get("scripts/deps-work/nf-libs/log4j-api-2.22.1.jar").toUri().toURL(),
        }, Test16711.class.getClassLoader())) {
            Class<?> gate = clNone.loadClass("cn.kerong.penguin.neoforge.BindLoginGate");
            Object gateInstance = gate.getDeclaredField("INSTANCE").get(null);
            Object bus = buildFmlStyleModBus(clNone);
            Boolean ok = callRegisterTo(gate, gateInstance, bus, clNone);
            check("G6 无配置阶段事件形态：registerTo 如实返回 false（不误报已挂载）",
                    Boolean.FALSE.equals(ok), String.valueOf(ok));
        }

        // ---------------- TO 组：双 toml ----------------
        try (ZipFile zf = new ZipFile(nfJar)) {
            String modern = new String(zf.getInputStream(
                    zf.getEntry("META-INF/neoforge.mods.toml")).readAllBytes(), StandardCharsets.UTF_8);
            String legacy = new String(zf.getInputStream(
                    zf.getEntry("META-INF/mods.toml")).readAllBytes(), StandardCharsets.UTF_8);
            java.util.List<String> nml = java.util.Arrays.asList(modern.split("\n"));
            check("TO-1 neoforge.mods.toml 段头行 = [[mods]]（字节级）",
                    nml.stream().anyMatch(l -> l.trim().equals("[[mods]]")));
            // 段头断言按「行」判定：注释行允许提及历史损坏形态（1.6.7.10 注释
            // 里有 "[ods]]" 字样属合法说明，非段头本身；终端显示层会吞字符，
            // 一律以字节为准——[[mods]] 的 16 进制 5b 5b 6d 6f 64 73 5d 5d）
            java.util.List<String> ml = java.util.Arrays.asList(legacy.split("\n"));
            boolean modsHeaderLine = ml.stream().anyMatch(l -> l.trim().equals("[[mods]]"));
            boolean noDamagedHeaderLine = ml.stream()
                    .noneMatch(l -> !l.trim().startsWith("#") && l.contains("[ods]]"));
            check("TO-2 mods.toml 段头行 = [[mods]]（非注释行无损坏段头）",
                    modsHeaderLine && noDamagedHeaderLine);
            check("TO-3 版本 1.6.7.11",
                    modern.contains("version = \"1.6.7.11\"") && legacy.contains("version = \"1.6.7.11\""));
            check("TO-4 minecraft 版本范围 [1.20.4,1.22)",
                    modern.contains("[1.20.4,1.22)") && legacy.contains("[1.20.4,1.22)"));
            check("TO-5 neoforge 依赖范围 [20.4.0,)",
                    modern.contains("[20.4.0,)") && legacy.contains("[20.4.0,)"));
            check("TO-6 displayTest 保持 IGNORE_SERVER_VERSION",
                    modern.contains("IGNORE_SERVER_VERSION") && legacy.contains("IGNORE_SERVER_VERSION"));
            check("TO-7 双文件内容一致", modern.equals(legacy));
        }

        // ---------------- B 组：基线 ----------------
        try (ZipFile zn = new ZipFile(nfJar)) {
            check("B-1 NF JAR 无旧 BindGateBusListener 残留",
                    zn.getEntry("cn/kerong/penguin/neoforge/BindLoginGate$BindGateBusListener.class") == null);
            check("B-2 BindLoginGate 在位",
                    zn.getEntry("cn/kerong/penguin/neoforge/BindLoginGate.class") != null);
            check("B-3 BindGateTask（ICustomConfigurationTask 桥）在位",
                    zn.getEntry("cn/kerong/penguin/neoforge/BindLoginGate$BindGateTask.class") != null);
            int dirCount = 0, total = 0;
            java.util.Enumeration<? extends ZipEntry> e = zn.entries();
            while (e.hasMoreElements()) {
                ZipEntry en = e.nextElement();
                total++;
                if (en.isDirectory()) dirCount++;
            }
            check("B-4 NF 目录条目量级正常（SDK 组件扫描）", dirCount > 500, String.valueOf(dirCount));
            check("B-5 NF 条目总量量级正常", total > 9000, String.valueOf(total));
            // 1.6.7.10 面板修复回归：PanelSyncResult 与兼容桥保持
            check("B-6 PanelSyncResult 保持（1.6.7.10 修复回归，MenuManager 内部类）",
                    zn.getEntry("cn/kerong/bot/MenuManager$PanelSyncResult.class") != null);
        }
        try (ZipFile zs = new ZipFile(spigotJar)) {
            String pluginYml = new String(zs.getInputStream(zf0(zs, "plugin.yml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            check("B-7 plugin.yml 版本 1.6.7.11", pluginYml.contains("version: '1.6.7.11'"));
            int dirCount = 0, total = 0;
            java.util.Enumeration<? extends ZipEntry> e = zs.entries();
            while (e.hasMoreElements()) {
                ZipEntry en = e.nextElement();
                total++;
                if (en.isDirectory()) dirCount++;
            }
            check("B-8 Spigot 目录条目在位", dirCount > 500, String.valueOf(dirCount));
            check("B-9 Spigot 条目总量量级正常", total > 11000, String.valueOf(total));
            check("B-10 无旧包名残留", zs.getEntry("cn/huohuas001/") == null);
        }

        System.out.println();
        System.out.println("== smoke16711 结果：PASS " + passed + " / FAIL " + failed + " ==");
        if (failed > 0) System.exit(1);
    }
}
