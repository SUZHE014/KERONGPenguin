import cn.huohuas001.bot.UpdateChecker;
import cn.huohuas001.bot.tools.PluginFileLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 1.6.4 功能冒烟（独立 JVM，类路径 = NeoForge 分发 JAR + deps.jar +
 * minecraft-mojmap + neoforge-universal + bus + loader + authlib）。
 *
 * 覆盖：
 *  1. PluginFileLog.stripColorCodes：剥离全部 § 颜色码、无码文本原样返回
 *     （NeoForge 控制台 § 剥离与更新检查彩色输出的公共依赖）；
 *  2. UpdateChecker.isNewer 回归：逐段数字比较行为不变；
 *  3. BindLoginGate 类加载 + 放行兜底：服务器引用缺失 / 反射失败时
 *     evaluateKick 返回 null（放行，加入期踢出兜底接管）；
 *  4. BindGateTask：任务类型 kerongpenguin:bind_gate；start() 对非
 *     ServerCommonPacketListenerImpl 的监听器安全跳过（cast 为 null）。
 */
public class Test164 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name + "  [" + detail + "]"); }
    }

    public static void main(String[] args) throws Exception {
        // ---------- 1. stripColorCodes ----------
        String colored = "§e[更新检查] §f发现新版本：§a§l1.6.4 §7（当前 1.6.3）";
        String stripped = PluginFileLog.INSTANCE.stripColorCodes(colored);
        check("stripColorCodes 剥离全部 § 码",
                !stripped.contains("§") && stripped.contains("[更新检查]") && stripped.contains("1.6.4"),
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
        check("isNewer 方法存在（internal 后缀模块名）", newer != null, "-");
        newer.setAccessible(true);
        check("isNewer 1.6.4 > 1.6.3", Boolean.TRUE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.6.4", "1.6.3")), "-");
        check("isNewer 1.6.4 == 1.6.4 不提示", Boolean.FALSE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.6.4", "1.6.4")), "-");
        check("isNewer 1.6.3 > 1.6.4 不提示", Boolean.FALSE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.6.3", "1.6.4")), "-");
        check("isNewer 1.6.10 > 1.6.9（多位段）", Boolean.TRUE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.6.10", "1.6.9")), "-");
        check("isNewer 1.7 > 1.6.4（段数不足补 0）", Boolean.TRUE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.7", "1.6.4")), "-");

        // ---------- 3. BindLoginGate 加载与放行兜底 ----------
        Class<?> gate = Class.forName("cn.huohuas001.huhobotPenguin.neoforge.BindLoginGate");
        check("BindLoginGate 类可加载", gate != null, "-");

        Class<?> listenerInterface = Class.forName(
                "net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener");
        Object proxyListener = Proxy.newProxyInstance(
                gate.getClassLoader(), new Class<?>[]{listenerInterface}, (p, m, a) -> null);

        Method evaluate = gate.getDeclaredMethod("evaluateKick", listenerInterface);
        evaluate.setAccessible(true);
        Object gateInstance = gate.getDeclaredField("INSTANCE").get(null);
        Object verdict = evaluate.invoke(gateInstance, proxyListener);
        check("evaluateKick 服务器未就绪时放行（null）", verdict == null, String.valueOf(verdict));

        // ---------- 4. BindGateTask ----------
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

        // start() 的参数是发包 Consumer（原版 ConfigurationTask 约定）；
        // 代理监听器不是 ServerCommonPacketListenerImpl → cast 为 null 安全跳过
        Method startMethod = taskClass.getMethod("start", Class.forName("java.util.function.Consumer"));
        startMethod.setAccessible(true);
        startMethod.invoke(task, (java.util.function.Consumer<Object>) x -> { });
        check("BindGateTask.start() 代理监听器不抛异常", true, "-");

        System.out.println("结果：" + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }
}
