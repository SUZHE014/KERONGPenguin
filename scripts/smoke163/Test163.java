import cn.huohuas001.bot.ConflictWatch;
import cn.huohuas001.bot.HuHoBot;
import cn.huohuas001.bot.ReconnectManager;
import cn.huohuas001.bot.ReplySeqGuard;
import cn.huohuas001.bot.WssDiagnostics;
import cn.huohuas001.bot.provider.BotShared;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 1.6.3 功能冒烟（独立 JVM，类路径 = NeoForge 分发 JAR + deps.jar[slf4j]）。
 *
 * 覆盖：
 *  1. ConflictWatch：普通异常不计数 / 40054005 与「消息被去重」识别（含 cause 链）/
 *     ret 文本路径 / 阈值 3 次判定 / 判定后幂等 / reset 复位 / 窗口过期重开；
 *  2. 确凿冲突处置：判定成立 → ReconnectManager 挂起 + 不可关闭指引文案
 *     （断开连接、不再自动重试、/hb chs 恢复）；manualReconnect 复位；
 *  3. WssDiagnostics：互踢证据统一计数（3 次短命会话 → 冲突判定一次性触发）；
 *     QqTokenHealth.lastCheckOk 属性存在且初始为 null；
 *  4. NeoForge JUL→SLF4J 桥：getLogger 后 useParentHandlers=false、
 *     转发 Handler 恰好 1 个（幂等）、JUL info/warning/severe 调用不抛异常；
 *  5. ReplySeqGuard 回归（1.6.2 修复保留）：基数分配不变；
 *  6. 类加载冒烟：本轮改动类全部可加载，死代码类不存在。
 */
public class Test163 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name + "  [" + detail + "]"); }
    }

    public static class TestPlugin extends org.bukkit.plugin.java.JavaPlugin {
        private final File folder;
        public TestPlugin(File folder) { this.folder = folder; }
        @Override public String getName() { return "KERONGPenguin"; }
        @Override public File getDataFolder() { return folder; }
    }

    static Object field(Object owner, String name) throws Exception {
        Class<?> c = owner instanceof Class<?> ? (Class<?>) owner : owner.getClass();
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return owner instanceof Class<?> ? f.get(null) : f.get(owner);
    }

    static void setField(Object owner, String name, Object value) throws Exception {
        Class<?> c = owner instanceof Class<?> ? (Class<?>) owner : owner.getClass();
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(owner instanceof Class<?> ? null : owner, value);
    }

    public static void main(String[] args) throws Exception {
        File folder = new File("build/smoke163-data");
        folder.mkdirs();

        // ---------- 桩插件（动态代理 HuHoBot：记录日志，返回类型默认值） ----------
        List<String> logs = new ArrayList<>();
        HuHoBot stub = (HuHoBot) Proxy.newProxyInstance(
            Test163.class.getClassLoader(),
            new Class<?>[]{HuHoBot.class},
            (proxy, method, argv) -> {
                String name = method.getName();
                if (name.equals("log_info") || name.equals("log_warning") || name.equals("log_error")) {
                    logs.add(name + ": " + (argv == null ? "" : argv[0]));
                    return null;
                }
                Class<?> rt = method.getReturnType();
                if (rt == boolean.class) return Boolean.FALSE;
                if (rt == int.class) return 0;
                if (rt == long.class) return 0L;
                if (rt == List.class) return new ArrayList<String>();
                return null;
            });
        BotShared.INSTANCE.setInstance(stub);

        System.out.println("== 1. ConflictWatch（消息级冲突检测） ==");
        ConflictWatch.INSTANCE.reset();
        ReconnectManager.INSTANCE.onLaunch();

        // 普通网络异常：不计数
        ConflictWatch.INSTANCE.onReplyRejected(new RuntimeException("connect timed out"));
        check("普通异常不计数", ((int) field(ConflictWatch.INSTANCE, "dedupCount")) == 0,
            "count=" + field(ConflictWatch.INSTANCE, "dedupCount"));
        check("未判定冲突", !((boolean) field(ConflictWatch.INSTANCE, "conflictDeclared")), "");

        // 40054005 异常路径
        ConflictWatch.INSTANCE.onReplyRejected(
            new RuntimeException("code is [40054005] ({\"message\":\"消息被去重，请检查请求msgseq\"})"));
        check("40054005 异常计数 1", ((int) field(ConflictWatch.INSTANCE, "dedupCount")) == 1, "");

        // cause 链路径（外层普通包装、内层才是去重）
        ConflictWatch.INSTANCE.onReplyRejected(
            new RuntimeException("send failed", new Exception("HTTP 400 code=40054005 消息被去重")));
        check("cause 链识别计数 2", ((int) field(ConflictWatch.INSTANCE, "dedupCount")) == 2, "");

        // ret 文本路径（正常返回但被拒收）
        ConflictWatch.INSTANCE.onReplyRejectedDetail("code is [40054005] msg=消息被去重");
        check("ret 文本路径计数 3 → 判定成立",
            ((boolean) field(ConflictWatch.INSTANCE, "conflictDeclared")), "");

        // 判定后的处置（ReconnectManager 挂起 + 指引文案）
        check("确凿冲突后挂起（suspended）", ReconnectManager.INSTANCE.isSuspended(), "");
        check("确凿冲突醒目提示（实锤描述）",
            logs.stream().anyMatch(l -> l.contains("已确认同一机器人被两台服务器同时使用")),
            "日志: " + logs);
        check("确凿冲突提示（去重证据描述）",
            logs.stream().anyMatch(l -> l.contains("消息被去重(40054005)")
                && l.contains("抢先回复了同一条群消息")), "");
        check("确凿冲突提示（不再自动重试）",
            logs.stream().anyMatch(l -> l.contains("不再自动重试")), "");
        check("确凿冲突提示（/hb chs 恢复指引）",
            logs.stream().anyMatch(l -> l.contains("/hb chs") && l.contains("手动恢复本服连接")), "");
        check("确凿冲突提示（处理方法：换机器人/关闭其一）",
            logs.stream().anyMatch(l -> l.contains("为每台服务器申请")), "");

        // 判定后幂等：再多的拒收不重复触发
        int logSize = logs.size();
        ConflictWatch.INSTANCE.onReplyRejected(new RuntimeException("code is [40054005]"));
        check("判定后拒收不再重复触发处置", logs.size() == logSize, "日志增长: " + (logs.size() - logSize));

        // reset 复位
        ConflictWatch.INSTANCE.reset();
        check("reset 后计数归零", ((int) field(ConflictWatch.INSTANCE, "dedupCount")) == 0, "");
        check("reset 后判定复位", !((boolean) field(ConflictWatch.INSTANCE, "conflictDeclared")), "");
        String manual = ReconnectManager.INSTANCE.manualReconnect();
        check("manualReconnect 复位挂起", !ReconnectManager.INSTANCE.isSuspended(), "");
        check("manualReconnect（客户端未启动）返回指引文案",
            manual.contains("QQ 客户端未启动"), "实际 " + manual);

        // 窗口过期：首次拒收时间挪到 11 分钟前 → 重新开窗
        ConflictWatch.INSTANCE.reset();
        ConflictWatch.INSTANCE.onReplyRejectedDetail("40054005");
        setField(ConflictWatch.INSTANCE, "firstDedupAt",
            System.currentTimeMillis() - 11 * 60_000L);
        ConflictWatch.INSTANCE.onReplyRejectedDetail("40054005");
        check("窗口过期后重新开窗（计数回到 1）",
            ((int) field(ConflictWatch.INSTANCE, "dedupCount")) == 1,
            "count=" + field(ConflictWatch.INSTANCE, "dedupCount"));
        ConflictWatch.INSTANCE.reset();

        System.out.println("== 2. WssDiagnostics（互踢证据统一计数） ==");
        // 复位重连编排（真实流程中 launchClient 会同时调用两者复位）
        ReconnectManager.INSTANCE.onLaunch();
        WssDiagnostics.INSTANCE.start();
        Method trackShort = WssDiagnostics.class.getDeclaredMethod("trackShortSession", long.class);
        trackShort.setAccessible(true);
        for (int i = 0; i < 3; i++) trackShort.invoke(WssDiagnostics.INSTANCE, 5000L);
        check("3 次短命会话 → 冲突一次性判定",
            ((boolean) field(WssDiagnostics.INSTANCE, "conflictStopped")), "");
        check("短命会话单次醒目提示（含排查指引）",
            logs.stream().anyMatch(l -> l.contains("仅存活 5 秒即被平台关闭")
                && l.contains("互相抢占轮流掉线")), "");
        check("互踢判定醒目提示（已有另一台服务器绑定）",
            logs.stream().anyMatch(l -> l.contains("已有另一台服务器绑定了同一个机器人")), "");
        // 鉴权期被关（独立入口，经统一计数）
        Method trackAuth = WssDiagnostics.class.getDeclaredMethod("trackAuthRejected");
        trackAuth.setAccessible(true);
        int before = (int) field(WssDiagnostics.INSTANCE, "shortSessionCount");
        trackAuth.invoke(WssDiagnostics.INSTANCE);
        check("trackAuthRejected 计入统一计数",
            ((int) field(WssDiagnostics.INSTANCE, "shortSessionCount")) == before + 1,
            "before=" + before + " after=" + field(WssDiagnostics.INSTANCE, "shortSessionCount"));
        check("鉴权期关闭提示（凭据自检已通过语义）",
            logs.stream().anyMatch(l -> l.contains("鉴权完成前即被平台关闭")), "");
        // 重置
        WssDiagnostics.INSTANCE.start();
        check("start() 重置互踢计数", ((int) field(WssDiagnostics.INSTANCE, "shortSessionCount")) == 0, "");

        System.out.println("== 3. QqTokenHealth（凭据自检结果记录） ==");
        Class<?> tokenHealth = Class.forName("cn.huohuas001.bot.QqTokenHealth");
        Method getLastCheckOk = tokenHealth.getMethod("getLastCheckOk");
        Object initial = getLastCheckOk.invoke(tokenHealth.getField("INSTANCE").get(null));
        check("lastCheckOk 初始为 null（未自检）", initial == null, "实际 " + initial);

        System.out.println("== 4. NeoForge JUL→SLF4J 桥 ==");
        TestPlugin tp = new TestPlugin(folder);
        Logger logger1 = tp.getLogger();
        Logger logger2 = tp.getLogger();
        check("getLogger 幂等（同一实例）", logger1 == logger2, "");
        check("JUL 父级输出已关闭（useParentHandlers=false）",
            !logger1.getUseParentHandlers(), "");
        int forwarders = 0;
        for (java.util.logging.Handler h : logger1.getHandlers()) {
            if (h.getClass().getName().endsWith("Slf4jForwardHandler")) forwarders++;
        }
        check("转发 Handler 恰好 1 个（幂等安装）", forwarders == 1, "实际 " + forwarders);
        boolean threw = false;
        try {
            logger1.info("JUL 桥冒烟 info");
            logger1.warning("JUL 桥冒烟 warning");
            logger1.severe("JUL 桥冒烟 severe");
        } catch (Throwable t) {
            threw = true;
        }
        check("JUL 三级别转发不抛异常（NOP 绑定）", !threw, "");
        // 二次实例（另一 JavaPlugin 子类）不再重复挂 Handler
        TestPlugin tp2 = new TestPlugin(folder);
        int forwarders2 = 0;
        for (java.util.logging.Handler h : tp2.getLogger().getHandlers()) {
            if (h.getClass().getName().endsWith("Slf4jForwardHandler")) forwarders2++;
        }
        check("全局 JUL 单例不重复挂载（仍是 1 个）", forwarders2 == 1, "实际 " + forwarders2);

        System.out.println("== 5. ReplySeqGuard 回归（1.6.2 修复保留） ==");
        int first = ReplySeqGuard.INSTANCE.allocateBase("smoke-msg-1");
        check("首次投递基数 = 1", first == 1, "实际 " + first);
        int second = ReplySeqGuard.INSTANCE.allocateBase("smoke-msg-1");
        check("重投递基数 = 100001", second == 100001, "实际 " + second);
        int other = ReplySeqGuard.INSTANCE.allocateBase("smoke-msg-2");
        check("msg_id 隔离", other == 1, "实际 " + other);
        check("null msgId 安全", ReplySeqGuard.INSTANCE.allocateBase(null) == 1, "");

        System.out.println("== 6. 类加载冒烟 ==");
        Class<?>[] classes = {
            cn.huohuas001.bot.ConflictWatch.class,
            cn.huohuas001.bot.WssDiagnostics.class,
            cn.huohuas001.bot.QClient.class,
            cn.huohuas001.bot.ReconnectManager.class,
            cn.huohuas001.bot.events.GroupMessageHandler.class,
            cn.huohuas001.bot.events.commands.PublicCommands.class,
            cn.huohuas001.bot.events.commands.MotdCommands.class,
            cn.huohuas001.huhobotPenguin.spigot.qqbind.MessageTemplates.class,
            cn.huohuas001.huhobotPenguin.spigot.qqbind.GroupMsgSender.class,
            cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager.class,
            cn.huohuas001.huhobotPenguin.spigot.manager.ConfigManager.class,
            org.bukkit.plugin.java.JavaPlugin.class,
        };
        for (Class<?> c : classes) {
            check("类加载 " + c.getSimpleName(), c != null, "");
        }
        // 死代码类不应存在
        String[] dead = {
            "cn.huohuas001.bot.tools.CollectionUtils",
            "cn.huohuas001.bot.web.WebUiPassword",
        };
        for (String name : dead) {
            boolean absent;
            try { Class.forName(name); absent = false; } catch (ClassNotFoundException e) { absent = true; }
            check("死类不存在 " + name.substring(name.lastIndexOf('.') + 1), absent, "");
        }
        WssDiagnostics.INSTANCE.stop();

        System.out.println("================================================================");
        System.out.println("结果：" + passed + " 通过，" + failed + " 失败");
        if (failed > 0) System.exit(1);
        System.out.println("全部通过");
    }
}
