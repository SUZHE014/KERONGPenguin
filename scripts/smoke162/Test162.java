import cn.huohuas001.bot.HuHoBot;
import cn.huohuas001.bot.ReconnectManager;
import cn.huohuas001.bot.ReplySeqGuard;
import cn.huohuas001.bot.UpdateChecker;
import cn.huohuas001.bot.provider.BotShared;
import cn.huohuas001.huhobotPenguin.spigot.manager.ConfigManager;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.6.2 功能冒烟（独立 JVM，类路径 = NeoForge 分发 JAR）。
 *
 * 覆盖：
 *  1. ReplySeqGuard：回复序号基数分配（首次=1、重投递跳开 100000、按 msg_id 隔离、
 *     null 安全）——修复 QQ 40054005 消息去重；
 *  2. ReconnectManager：计数常量（5 次/3 轮）、onLaunch 重置、onOpen 递增、
 *     健康会话清零、连续 5 次失败挂起（isSuspended）、手动重连重置
 *     （starter 未启动文案）——重连编排体系；
 *  3. UpdateChecker.isNewer 版本比较（点分段、多位、不等长）+ check 失败路径
 *     （version.json 未部署时仅一句话提示不抛异常）；
 *  4. 配置迁移 17→18：commands.金币榜 → commands.财富榜（自定义 false 值保留）、
 *     旧键清除、新键补齐（update-check.enabled / bot.reconnect-notify）、
 *     coins-leaderboard 段原值不动；
 *  5. 类加载冒烟：WssDiagnostics / QClient / GroupMessageHandler 等新改类加载。
 */
public class Test162 {

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

    public static void main(String[] args) throws Exception {
        // ---------- 桩插件（动态代理 HuHoBot：记录日志，返回类型默认值） ----------
        List<String> logs = new ArrayList<>();
        HuHoBot stub = (HuHoBot) Proxy.newProxyInstance(
            Test162.class.getClassLoader(),
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

        System.out.println("== 1. ReplySeqGuard（回复去重基数） ==");
        int first = ReplySeqGuard.INSTANCE.allocateBase("msg-1");
        check("首次投递基数 = 1", first == 1, "实际 " + first);
        int second = ReplySeqGuard.INSTANCE.allocateBase("msg-1");
        check("重投递基数 = 100001（跳开）", second == 100001, "实际 " + second);
        int third = ReplySeqGuard.INSTANCE.allocateBase("msg-1");
        check("第三次基数 = 200001", third == 200001, "实际 " + third);
        int other = ReplySeqGuard.INSTANCE.allocateBase("msg-2");
        check("不同 msg_id 隔离（msg-2 首次 = 1）", other == 1, "实际 " + other);
        int nullId = ReplySeqGuard.INSTANCE.allocateBase(null);
        check("null msgId 安全返回 1", nullId == 1, "实际 " + nullId);
        int emptyId = ReplySeqGuard.INSTANCE.allocateBase("");
        check("空 msgId 安全返回 1", emptyId == 1, "实际 " + emptyId);
        check("重投递基数互不重叠（间隔 ≥ 100000）",
            second - first >= 100_000 && third - second >= 100_000, "间隔不足");

        System.out.println("== 2. ReconnectManager（重连编排） ==");
        Field small = ReconnectManager.class.getDeclaredField("SMALL_RETRY_MAX");
        small.setAccessible(true);
        Field big = ReconnectManager.class.getDeclaredField("BIG_ROUND_MAX");
        big.setAccessible(true);
        check("每轮重试上限 = 5", small.getInt(null) == 5, "实际 " + small.getInt(null));
        check("大轮次上限 = 3", big.getInt(null) == 3, "实际 " + big.getInt(null));

        ReconnectManager.INSTANCE.onLaunch();
        check("onLaunch 重置：attempt = 0", ReconnectManager.INSTANCE.currentAttempt() == 0, "");
        check("onLaunch 重置：未挂起", !ReconnectManager.INSTANCE.isSuspended(), "");

        ReconnectManager.INSTANCE.onOpen();
        ReconnectManager.INSTANCE.onOpen();
        check("onOpen 两次 → 第 2 次重试", ReconnectManager.INSTANCE.currentAttempt() == 2,
            "实际 " + ReconnectManager.INSTANCE.currentAttempt());
        check("重连提示文案已输出（含 2/5）",
            logs.stream().anyMatch(l -> l.contains("第 2/5 次重试连接")), "日志: " + logs);

        ReconnectManager.INSTANCE.onHealthySession();
        check("健康会话清零计数", ReconnectManager.INSTANCE.currentAttempt() == 0, "");
        check("重连成功文案已输出",
            logs.stream().anyMatch(l -> l.contains("重连成功")), "无成功提示");

        // 连续 5 次失败 → 挂起
        for (int i = 0; i < 5; i++) ReconnectManager.INSTANCE.onOpen();
        boolean gaveUp = ReconnectManager.INSTANCE.onClosedAfter(0L, false);
        check("第 5 次失败触发挂起", gaveUp && ReconnectManager.INSTANCE.isSuspended(),
            "gaveUp=" + gaveUp + " suspended=" + ReconnectManager.INSTANCE.isSuspended());
        check("重连超时提示（含 /hb chs，不可关闭）",
            logs.stream().anyMatch(l -> l.contains("重连超时") && l.contains("/hb chs")),
            "缺少超时指引");
        check("退避轮次提示（30 分钟 + 第 1/3 轮）",
            logs.stream().anyMatch(l -> l.contains("30 分钟") && l.contains("1/3")),
            "缺少退避说明");

        String manual = ReconnectManager.INSTANCE.manualReconnect();
        check("手动重连（客户端未启动）返回指引文案",
            manual.contains("QQ 客户端未启动"), "实际 " + manual);
        check("手动重连重置挂起状态", !ReconnectManager.INSTANCE.isSuspended(), "");
        check("手动重连重置计数", ReconnectManager.INSTANCE.currentAttempt() == 0, "");

        System.out.println("== 3. UpdateChecker（更新检查） ==");
        // Kotlin internal fun 有名字修饰（isNewer$...），按前缀反射获取
        Method isNewer = null;
        for (Method m : UpdateChecker.class.getDeclaredMethods()) {
            if (m.getName().startsWith("isNewer")) { isNewer = m; break; }
        }
        check("isNewer 方法存在", isNewer != null, "未找到");
        if (isNewer != null) {
            isNewer.setAccessible(true);
            check("isNewer 1.6.2 > 1.6.1", (Boolean) isNewer.invoke(UpdateChecker.INSTANCE, "1.6.2", "1.6.1"), "");
            check("isNewer 1.6.1 < 1.6.2（不误报）", !(Boolean) isNewer.invoke(UpdateChecker.INSTANCE, "1.6.1", "1.6.2"), "");
            check("isNewer 相等版本（不误报）", !(Boolean) isNewer.invoke(UpdateChecker.INSTANCE, "1.6.2", "1.6.2"), "");
            check("isNewer 1.6.10 > 1.6.9（两位数字段）", (Boolean) isNewer.invoke(UpdateChecker.INSTANCE, "1.6.10", "1.6.9"), "");
            check("isNewer 1.7 > 1.6.9（段数不足补 0）", (Boolean) isNewer.invoke(UpdateChecker.INSTANCE, "1.7", "1.6.9"), "");
            check("isNewer 1.6.9 < 1.6.10", !(Boolean) isNewer.invoke(UpdateChecker.INSTANCE, "1.6.9", "1.6.10"), "");
        }
        check("update-check 默认开启（无 JavaPlugin 桩）", UpdateChecker.INSTANCE.enabled(), "");

        try {
            UpdateChecker.INSTANCE.check("1.6.2");
            check("check 执行不抛异常（版本文件未部署走失败路径）", true, "");
            check("失败路径仅一句话提示 + 细节进文件日志",
                logs.stream().anyMatch(l -> l.contains("更新检测失败")),
                "无失败提示");
        } catch (Throwable t) {
            check("check 执行不抛异常", false, t.toString());
        }

        System.out.println("== 4. 配置迁移 17→18（金币榜 → 财富榜） ==");
        File folder = Files.createTempDirectory("penguin-162").toFile();
        File cfg = new File(folder, "config.yml");
        String old17 = String.join("\n",
            "config-version: 17",
            "bot:",
            "  app-id: \"\"",
            "  secret: \"\"",
            "  name: \"T\"",
            "  groups: []",
            "coins-leaderboard:",
            "  top: 20",
            "  builtin-background: false",
            "commands:",
            "  金币榜: false",
            "  查在线: true",
            "");
        Files.write(cfg.toPath(), old17.getBytes(StandardCharsets.UTF_8));
        TestPlugin tp = new TestPlugin(folder);
        ConfigManager mgr = new ConfigManager(tp);
        mgr.reload();
        String after = new String(Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8);
        check("config-version 升至 18", after.contains("config-version: 18"), "");
        check("commands.金币榜 迁移为 commands.财富榜（值 false 保留）",
            after.contains("财富榜: false") && !after.contains("金币榜: false"), "");
        check("金币榜 旧键已清除（迁移后无该键）",
            !after.lines().anyMatch(l -> l.trim().startsWith("金币榜:")), "");
        check("新键 update-check.enabled 补齐（默认 true）",
            after.contains("enabled: true") && after.contains("update-check"), "");
        check("新键 bot.reconnect-notify 补齐（默认 true）",
            after.contains("reconnect-notify: true"), "");
        check("coins-leaderboard.top 自定义值 20 保留",
            after.contains("top: 20"), "");
        check("coins-leaderboard.builtin-background 自定义值 false 保留",
            after.lines().anyMatch(l -> l.trim().equals("builtin-background: false")), "");
        check("命令开关映射读取：财富榜=false",
            mgr.commandSwitches().get("财富榜") == Boolean.FALSE, "实际 " + mgr.commandSwitches().get("财富榜"));
        check("别名开关映射读取：金币榜=false（与财富榜共用）",
            mgr.commandSwitches().get("金币榜") == Boolean.FALSE, "实际 " + mgr.commandSwitches().get("金币榜"));
        check("别名开关映射读取：在线=true（与查在线共用）",
            mgr.commandSwitches().get("在线") == Boolean.TRUE, "实际 " + mgr.commandSwitches().get("在线"));

        System.out.println("== 5. 类加载冒烟 ==");
        Class<?>[] classes = {
            cn.huohuas001.bot.WssDiagnostics.class,
            cn.huohuas001.bot.QClient.class,
            cn.huohuas001.bot.events.GroupMessageHandler.class,
            cn.huohuas001.bot.events.commands.CoinsLeaderboardCommands.class,
            cn.huohuas001.bot.MenuManager.class,
            cn.huohuas001.huhobotPenguin.spigot.render.InfoCardAssets.class,
            cn.huohuas001.bot.web.WebUiSchema.class,
        };
        for (Class<?> c : classes) {
            check("类加载 " + c.getSimpleName(), c != null, "");
        }
        String schema = cn.huohuas001.bot.web.WebUiSchema.INSTANCE.toJson();
        check("WebUiSchema 含 update-check 分区", schema.contains("update-check"), "");
        check("WebUiSchema 含 reconnect-notify 字段", schema.contains("bot.reconnect-notify"), "");
        check("WebUiSchema 含财富榜描述", schema.contains("财富榜"), "");

        System.out.println("================================================================");
        System.out.println("结果：" + passed + " 通过，" + failed + " 失败");
        if (failed > 0) System.exit(1);
        System.out.println("全部通过");
    }
}
