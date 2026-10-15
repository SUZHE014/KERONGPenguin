import cn.huohuas001.bot.UpdateChecker;
import cn.huohuas001.bot.tools.PluginFileLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 1.6.5 功能冒烟（独立 JVM，类路径 = NeoForge 分发 JAR + deps.jar +
 * minecraft-mojmap + neoforge-universal + bus + loader + authlib）。
 *
 * 覆盖：
 *  1. 1.6.4 回归：PluginFileLog.stripColorCodes / UpdateChecker.isNewer /
 *     BindLoginGate 放行兜底 / BindGateTask；
 *  2. 自定义命令协议前缀 kerong（新）与 huhobot（旧，兼容）双解析；
 *  3. ReconnectManager 重连成功提示（1.6.5 核心）：
 *     - 首次连接的 READY 不宣告「重连成功」；
 *     - 短命会话后的 READY 不宣告（互踢防抖）；
 *     - 健康判定达成时宣告（含第 N/5 次尝试）；
 *     - 健康会话结束后的下一次重连在 READY 即宣告（修复「部分重连成功没有提示」）。
 */
public class Test165 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name + "  [" + detail + "]"); }
    }

    public static void main(String[] args) throws Exception {
        // ---------- 1. stripColorCodes（1.6.4 回归） ----------
        String colored = "§e[更新检查] §f发现新版本：§a§l1.6.5 §7（当前 1.6.4）";
        String stripped = PluginFileLog.INSTANCE.stripColorCodes(colored);
        check("stripColorCodes 剥离全部 § 码",
                !stripped.contains("§") && stripped.contains("[更新检查]") && stripped.contains("1.6.5"),
                stripped);
        check("stripColorCodes 无码文本原样",
                "普通文本".equals(PluginFileLog.INSTANCE.stripColorCodes("普通文本")), "-");
        check("stripColorCodes 前置 § 不误伤后续文本",
                PluginFileLog.INSTANCE.stripColorCodes("§a绿§r普通§b蓝").equals("绿普通蓝"), "-");

        // ---------- 2. isNewer 回归 ----------
        Method newer = null;
        for (Method m : UpdateChecker.class.getDeclaredMethods()) {
            if (m.getName().startsWith("isNewer") && m.getParameterCount() == 2) { newer = m; break; }
        }
        check("isNewer 方法存在", newer != null, "-");
        newer.setAccessible(true);
        check("isNewer 1.6.5 > 1.6.4", Boolean.TRUE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.6.5", "1.6.4")), "-");
        check("isNewer 1.6.5 == 1.6.5 不提示", Boolean.FALSE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.6.5", "1.6.5")), "-");
        check("isNewer 1.6.10 > 1.6.9（多位段）", Boolean.TRUE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.6.10", "1.6.9")), "-");

        // ---------- 3. 自定义命令协议 kerong / huhobot（1.6.5） ----------
        Class<?> ccr = Class.forName("cn.huohuas001.bot.events.commands.CustomCommandRegistry");
        Object ccrInst = ccr.getDeclaredField("INSTANCE").get(null);
        Class<?> detailClass = Class.forName("cn.huohuas001.bot.provider.CustomCommandDetail");
        Object detail = detailClass.getConstructor(String.class, String.class, int.class)
                .newInstance("测试", "say 前缀 {params}", 0);
        ccr.getMethod("replace", Collection.class).invoke(ccrInst, Arrays.asList(detail));
        Method resolve = ccr.getMethod("resolve", String.class);

        Object r1 = resolve.invoke(ccrInst, "kerong run G123 U456 测试 你好");
        check("协议前缀 kerong：占位符解析",
                "say 前缀 你好".equals(r1.getClass().getMethod("getCommand").invoke(r1)),
                String.valueOf(r1.getClass().getMethod("getCommand").invoke(r1)));
        Object r2 = resolve.invoke(ccrInst, "huhobot run G123 U456 测试 你好");
        check("旧前缀 huhobot 兼容解析",
                "say 前缀 你好".equals(r2.getClass().getMethod("getCommand").invoke(r2)),
                String.valueOf(r2.getClass().getMethod("getCommand").invoke(r2)));
        Object r3 = resolve.invoke(ccrInst, "list");
        check("非协议命令原样透传",
                "list".equals(r3.getClass().getMethod("getCommand").invoke(r3)),
                String.valueOf(r3.getClass().getMethod("getCommand").invoke(r3)));

        // ---------- 4. ReconnectManager 重连成功提示（1.6.5 核心） ----------
        List<String> logs = new CopyOnWriteArrayList<>();
        Class<?> botInterface = Class.forName("cn.huohuas001.bot.HuHoBot");
        Object botProxy = Proxy.newProxyInstance(
                Test165.class.getClassLoader(), new Class<?>[]{botInterface}, (p, m, a) -> {
                    if (m.getName().equals("log_info") || m.getName().equals("log_warning") || m.getName().equals("log_error")) {
                        logs.add(String.valueOf(a[0]));
                    }
                    return null;
                });
        Class<?> botShared = Class.forName("cn.huohuas001.bot.provider.BotShared");
        botShared.getMethod("setInstance", botInterface).invoke(botShared.getDeclaredField("INSTANCE").get(null), botProxy);

        Class<?> rm = Class.forName("cn.huohuas001.bot.ReconnectManager");
        Object rmInst = rm.getDeclaredField("INSTANCE").get(null);
        Method onLaunch = rm.getMethod("onLaunch");
        Method onOpen = rm.getMethod("onOpen");
        Method onSessionReady = rm.getMethod("onSessionReady");
        Method onClosedAfter = rm.getMethod("onClosedAfter", long.class, boolean.class);

        logs.clear();
        onLaunch.invoke(rmInst);
        onOpen.invoke(rmInst);
        onSessionReady.invoke(rmInst);
        check("首次连接的 READY 不宣告「重连成功」",
                logs.stream().noneMatch(s -> s.contains("重连成功")),
                String.join(" | ", logs));

        onClosedAfter.invoke(rmInst, 0L, false);     // 鉴权期被关（短命）
        onOpen.invoke(rmInst);
        onSessionReady.invoke(rmInst);
        check("短命会话后的 READY 不宣告（互踢防抖）",
                logs.stream().noneMatch(s -> s.contains("重连成功")),
                String.join(" | ", logs));

        onClosedAfter.invoke(rmInst, 120_000L, true); // 健康会话关闭 → 宣告成功
        check("健康判定达成时宣告「重连成功」",
                logs.stream().anyMatch(s -> s.contains("重连成功") && s.contains("2/5")),
                String.join(" | ", logs));

        long announced1 = logs.stream().filter(s -> s.contains("重连成功")).count();
        onOpen.invoke(rmInst);                         // 网关例行踢线后的自动重连
        onSessionReady.invoke(rmInst);
        check("健康会话后的下一次重连 READY 即宣告（修复无提示）",
                logs.stream().filter(s -> s.contains("重连成功")).count() == announced1 + 1
                        && logs.stream().anyMatch(s -> s.contains("重连成功") && s.contains("1/5")),
                String.join(" | ", logs));

        // ---------- 5. BindLoginGate / BindGateTask（1.6.4 回归） ----------
        Class<?> gate = Class.forName("cn.huohuas001.huhobotPenguin.neoforge.BindLoginGate");
        Class<?> listenerInterface = Class.forName(
                "net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener");
        Object proxyListener = Proxy.newProxyInstance(
                gate.getClassLoader(), new Class<?>[]{listenerInterface}, (p, m, a) -> null);
        Method evaluate = gate.getDeclaredMethod("evaluateKick", listenerInterface);
        evaluate.setAccessible(true);
        Object gateInstance = gate.getDeclaredField("INSTANCE").get(null);
        Object verdict = evaluate.invoke(gateInstance, proxyListener);
        check("evaluateKick 服务器未就绪时放行（null）", verdict == null, String.valueOf(verdict));

        Class<?> taskClass = Class.forName("cn.huohuas001.huhobotPenguin.neoforge.BindLoginGate$BindGateTask");
        Class<?> componentClass = Class.forName("net.minecraft.network.chat.Component");
        Object reason = componentClass.getMethod("literal", String.class).invoke(null, "冒烟测试断开原因");
        Constructor<?> ctor = taskClass.getDeclaredConstructor(listenerInterface, componentClass);
        ctor.setAccessible(true);
        Object task = ctor.newInstance(proxyListener, reason);
        Method typeMethod = taskClass.getMethod("type");
        typeMethod.setAccessible(true);
        Object type = typeMethod.invoke(task);
        Object id = type.getClass().getMethod("id").invoke(type);
        check("BindGateTask.type() = kerongpenguin:bind_gate",
                "kerongpenguin:bind_gate".equals(id), String.valueOf(id));
        Method startMethod = taskClass.getMethod("start", Class.forName("java.util.function.Consumer"));
        startMethod.setAccessible(true);
        startMethod.invoke(task, (java.util.function.Consumer<Object>) x -> { });
        check("BindGateTask.start() 代理监听器不抛异常", true, "-");

        System.out.println("结果：" + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }
}
