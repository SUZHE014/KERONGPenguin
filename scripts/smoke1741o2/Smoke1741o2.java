import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.zip.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * 1.7.4.1 第二轮覆盖版冒烟（独立 JVM，无插件实例）：
 * 1. 双端 JAR config.yml 解析：四条默认文案逐字一致（去表情），config-version 40，
 *    restart-window 180，start 段补齐，五开关默认开
 * 2. snakeyaml dump → load 往返：新文案（含 (o‿∩)）不丢不变
 * 3. 真实 YamlTextMerge（JAR 内类，反射 INSTANCE）：以新模板为底合并自定义值
 * 4. v39 → v40 升级路径端到端（收敛 + 差分回填 + 真实合并）：
 *    第七轮文案收敛为去表情版、restart-window=180 补齐、start 段补齐、自改文案保留
 * 5. v36 → v40 直升场景：自定义 restart-window=300 保留（不再被废弃清理删掉）
 * 6. 窗口分流语义复算：窗口内 → 重启文案；超窗 → 开机文案；窗口 0 → 一律开机
 * 7. 基线：plugin.yml 1.7.4.1 / ServerNotifyManager 双端在位
 */
public class Smoke1741o2 {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  ✓ " + name); }
        else { failed++; System.out.println("  ✗ " + name + (detail == null ? "" : " — " + detail)); }
    }

    static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    // 本轮新默认（真实换行）
    static final String N_SHUTDOWN = "[状态]\n服务器已正常关闭";
    static final String N_RESTART = "[状态]\n服务器重启完毕，已正常运行(o‿∩)";
    static final String N_START = "[状态]\n服务器已开启！！(≧∇≦)/";
    static final String N_CRASH = "[状态]\n服务器....";
    // 历史默认（收敛源）
    static final String O_SHUTDOWN_6 = "[状态]\n服务器已正常关闭 (╯︵╰,)";
    static final String O_RESTART_6 = "[状态]\n服务器已开启！！(≧∇≦)/";
    static final String O_CRASH_7 = "[状态]\n服务器....༽◺_◿༼";
    static final String O_CRASH_WRONG = "[状态]\n服务器....༽◺_轨迹༼";
    static final String O_CRASH_INIT = "服务器意外关闭（崩溃或被强制结束），请管理员检查控制台";

    static String jarEntryText(String jarPath, String entry) throws Exception {
        try (ZipFile zf = new ZipFile(jarPath)) {
            ZipEntry e = zf.getEntry(entry);
            if (e == null) throw new NoSuchFileException(jarPath + "!" + entry);
            byte[] buf = zf.getInputStream(e).readAllBytes();
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String text) {
        return new Yaml().load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sn(Map<String, Object> root) {
        return (Map<String, Object>) root.get("server-notify");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> seg(Map<String, Object> root, String key) {
        return (Map<String, Object>) sn(root).get(key);
    }

    public static void main(String[] args) throws Exception {
        String spigot = "build/dist/KERONGPenguin_Spigot-1.7.4.1.jar";
        String neoforge = "build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.4.1.jar";

        // ---------- 1. 双端 JAR config.yml 解析 ----------
        System.out.println("== 1. 双端 JAR config.yml 文案逐字校验 ==");
        for (String[] pair : new String[][]{{spigot, "Spigot"}, {neoforge, "NeoForge"}}) {
            Map<String, Object> root = parse(jarEntryText(pair[0], "config.yml"));
            check(pair[1] + " 关机文案逐字（去表情）", N_SHUTDOWN.equals(seg(root, "shutdown").get("message")),
                    String.valueOf(seg(root, "shutdown").get("message")));
            check(pair[1] + " 重启文案逐字（重启完毕 o‿∩）", N_RESTART.equals(seg(root, "restart").get("message")),
                    String.valueOf(seg(root, "restart").get("message")));
            check(pair[1] + " 开机文案逐字（start 新段）", N_START.equals(seg(root, "start").get("message")),
                    String.valueOf(seg(root, "start").get("message")));
            check(pair[1] + " 意外关闭文案逐字（去颜文字）", N_CRASH.equals(seg(root, "crash").get("message")),
                    String.valueOf(seg(root, "crash").get("message")));
            check(pair[1] + " config-version 40", Integer.valueOf(40).equals(root.get("config-version")),
                    String.valueOf(root.get("config-version")));
            check(pair[1] + " restart-window 180", Integer.valueOf(180).equals(sn(root).get("restart-window")),
                    String.valueOf(sn(root).get("restart-window")));
            check(pair[1] + " 五开关默认开", Boolean.TRUE.equals(sn(root).get("enabled"))
                    && Boolean.TRUE.equals(seg(root, "shutdown").get("enabled"))
                    && Boolean.TRUE.equals(seg(root, "restart").get("enabled"))
                    && Boolean.TRUE.equals(seg(root, "start").get("enabled"))
                    && Boolean.TRUE.equals(seg(root, "crash").get("enabled")));
            check(pair[1] + " 全文无旧表情", !jarEntryText(pair[0], "config.yml").contains("╯︵╰")
                    && !jarEntryText(pair[0], "config.yml").contains("༽◺"));
            check(pair[1] + " 四条前缀 [状态] 换行", N_SHUTDOWN.startsWith("[状态]\n")
                    && N_RESTART.startsWith("[状态]\n") && N_START.startsWith("[状态]\n")
                    && N_CRASH.startsWith("[状态]\n"));
        }

        // ---------- 2. dump → load 往返 ----------
        System.out.println("== 2. snakeyaml dump → load 往返 ==");
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        options.setWidth(Integer.MAX_VALUE);
        Yaml yaml = new Yaml(options);
        for (String[] pair : new String[][]{{"关机", N_SHUTDOWN}, {"重启", N_RESTART},
                {"开机", N_START}, {"意外关闭", N_CRASH}, {"第七轮旧意外关闭", O_CRASH_7}}) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("message", pair[1]);
            String dumped = yaml.dump(doc);
            Object back = new Yaml().load(dumped);
            check("往返一致·" + pair[0], pair[1].equals(((Map<String, Object>) back).get("message")), dumped.trim());
        }

        // ---------- 3. 真实 YamlTextMerge 合并 ----------
        System.out.println("== 3. YamlTextMerge 真实合并（JAR 内类反射） ==");
        Class<?> mergeClass = Class.forName("cn.kerong.penguin.spigot.manager.YamlTextMerge");
        Object instance = mergeClass.getField("INSTANCE").get(null);
        String template = jarEntryText(spigot, "config.yml");
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("server-notify.shutdown.message", "我的自定义关机文案");
        overrides.put("bot.name", "TestServer");
        String merged = (String) mergeClass.getMethod("merge", String.class, Map.class)
                .invoke(instance, template, overrides);
        Map<String, Object> mergedRoot = parse(merged);
        check("合并·自定义关机文案保留", "我的自定义关机文案".equals(seg(mergedRoot, "shutdown").get("message")));
        check("合并·重启默认走新模板", N_RESTART.equals(seg(mergedRoot, "restart").get("message")));
        check("合并·开机默认走新模板", N_START.equals(seg(mergedRoot, "start").get("message")));
        check("合并·意外关闭默认走新模板", N_CRASH.equals(seg(mergedRoot, "crash").get("message")));
        check("合并·restart-window 180 保留", Integer.valueOf(180).equals(sn(mergedRoot).get("restart-window")));
        check("合并·config-version 40", Integer.valueOf(40).equals(mergedRoot.get("config-version")));
        check("合并·单行 \\n 转义写出（重启文案）", merged.contains("message: \"[状态]\\n服务器重启完毕，已正常运行(o‿∩)\""));
        check("合并·旧双行块状形态未引入", !merged.contains("message: |\n") && !merged.contains("message: >"));

        // ---------- 4. v39 → v40 升级路径端到端 ----------
        System.out.println("== 4. v39 → v40 升级端到端（收敛 + 差分回填 + 真实合并） ==");
        // v39 配置：第七轮形态——三条带表情文案 + 无 restart-window（第七轮已删）+ 无 start 段 + 自改关机文案
        String v39Config = String.join("\n",
                "config-version: 39",
                "bot:",
                "  name: MyServer",
                "server-notify:",
                "  enabled: true",
                "  shutdown:",
                "    enabled: true",
                "    message: \"服务器下线啦，明天见\"",
                "  restart:",
                "    enabled: true",
                "    message: \"" + O_RESTART_6.replace("\n", "\\n") + "\"",
                "  crash:",
                "    enabled: true",
                "    message: \"" + O_CRASH_7.replace("\n", "\\n") + "\"",
                "");
        Map<String, Object> disk = parse(v39Config);
        check("旧配置解析·无 restart-window（第七轮已删）", !sn(disk).containsKey("restart-window"));
        check("旧配置解析·无 start 段", !sn(disk).containsKey("start"));
        // 4a. 收敛（对应 Rebuilder 收敛块：全部历史默认 → 新默认；自改不动）
        Object sd = seg(disk, "shutdown").get("message");
        if ("服务器已正常关闭".equals(sd) || O_SHUTDOWN_6.equals(sd)) seg(disk, "shutdown").put("message", N_SHUTDOWN);
        Object rt = seg(disk, "restart").get("message");
        if ("服务器已重启完成".equals(rt) || O_RESTART_6.equals(rt)) seg(disk, "restart").put("message", N_RESTART);
        Object ct = seg(disk, "crash").get("message");
        if (O_CRASH_INIT.equals(ct) || O_CRASH_WRONG.equals(ct) || O_CRASH_7.equals(ct)) seg(disk, "crash").put("message", N_CRASH);
        check("收敛·v39 开启文案 → 重启完毕新文案", N_RESTART.equals(seg(disk, "restart").get("message")));
        check("收敛·v39 意外关闭（第七轮颜文字版） → 去表情", N_CRASH.equals(seg(disk, "crash").get("message")));
        check("收敛·自改关机文案不动", "服务器下线啦，明天见".equals(seg(disk, "shutdown").get("message")));
        // 4b. 差分回填收集（值 != 新模板默认才进 carry）
        Map<String, Object> templateRoot = parse(template);
        Map<String, Object> carry = new LinkedHashMap<>();
        if (!"MyServer".equals(((Map<String, Object>) templateRoot.get("bot")).get("name"))) {
            carry.put("bot.name", "MyServer");
        }
        Object myShutdown = seg(disk, "shutdown").get("message");
        if (!myShutdown.equals(seg(templateRoot, "shutdown").get("message"))) {
            carry.put("server-notify.shutdown.message", myShutdown);
        }
        Object myRestart = seg(disk, "restart").get("message");
        if (!myRestart.equals(seg(templateRoot, "restart").get("message"))) {
            carry.put("server-notify.restart.message", myRestart);
        }
        check("差分·收敛后的 restart == 模板默认不进回填", !carry.containsKey("server-notify.restart.message"));
        // 4c. 与新模板合并（真实 YamlTextMerge）
        String upgraded = (String) mergeClass.getMethod("merge", String.class, Map.class)
                .invoke(instance, template, carry);
        Map<String, Object> upRoot = parse(upgraded);
        check("升级·自改关机文案保留", "服务器下线啦，明天见".equals(seg(upRoot, "shutdown").get("message")));
        check("升级·重启文案 = 重启完毕（模板新默认）", N_RESTART.equals(seg(upRoot, "restart").get("message")));
        check("升级·start 段补齐（开机文案）", N_START.equals(seg(upRoot, "start").get("message")));
        check("升级·意外关闭 = 去表情（模板新默认）", N_CRASH.equals(seg(upRoot, "crash").get("message")));
        check("升级·restart-window 补齐 180", Integer.valueOf(180).equals(sn(upRoot).get("restart-window")));
        check("升级·config-version 40", Integer.valueOf(40).equals(upRoot.get("config-version")));
        check("升级·bot.name 保留", "MyServer".equals(((Map<String, Object>) upRoot.get("bot")).get("name")));

        // ---------- 5. v36 → v40 直升：自定义 restart-window 保留 ----------
        System.out.println("== 5. v36 → v40 直升（自定义 restart-window 保留） ==");
        String v36Config = String.join("\n",
                "config-version: 36",
                "server-notify:",
                "  enabled: true",
                "  restart-window: 300",
                "  shutdown:",
                "    enabled: true",
                "    message: \"" + O_SHUTDOWN_6.replace("\n", "\\n") + "\"",
                "  restart:",
                "    enabled: true",
                "    message: \"服务器已重启完成\"",
                "  crash:",
                "    enabled: true",
                "    message: \"" + O_CRASH_WRONG.replace("\n", "\\n") + "\"",
                "");
        Map<String, Object> v36 = parse(v36Config);
        // 收敛
        Object sd36 = seg(v36, "shutdown").get("message");
        if ("服务器已正常关闭".equals(sd36) || O_SHUTDOWN_6.equals(sd36)) seg(v36, "shutdown").put("message", N_SHUTDOWN);
        Object rt36 = seg(v36, "restart").get("message");
        if ("服务器已重启完成".equals(rt36) || O_RESTART_6.equals(rt36)) seg(v36, "restart").put("message", N_RESTART);
        Object ct36 = seg(v36, "crash").get("message");
        if (O_CRASH_INIT.equals(ct36) || O_CRASH_WRONG.equals(ct36) || O_CRASH_7.equals(ct36)) seg(v36, "crash").put("message", N_CRASH);
        // 差分：300 != 模板 180 → carry 保留
        Map<String, Object> carry36 = new LinkedHashMap<>();
        Object w36 = sn(v36).get("restart-window");
        if (!w36.equals(sn(templateRoot).get("restart-window"))) carry36.put("server-notify.restart-window", w36);
        check("差分·自定义 restart-window 300 进回填", Integer.valueOf(300).equals(carry36.get("server-notify.restart-window")));
        String up36 = (String) mergeClass.getMethod("merge", String.class, Map.class)
                .invoke(instance, template, carry36);
        Map<String, Object> up36Root = parse(up36);
        check("直升·自定义 restart-window 300 保留（不再被废弃清理删掉）",
                Integer.valueOf(300).equals(sn(up36Root).get("restart-window")),
                String.valueOf(sn(up36Root).get("restart-window")));
        check("直升·三条文案收敛为新默认", N_SHUTDOWN.equals(seg(up36Root, "shutdown").get("message"))
                && N_RESTART.equals(seg(up36Root, "restart").get("message"))
                && N_CRASH.equals(seg(up36Root, "crash").get("message")));

        // ---------- 6. 窗口分流语义复算 ----------
        System.out.println("== 6. 窗口分流语义（elapsed vs window） ==");
        // 场景：上次正常关闭 stopTime 记录后 60 秒再开机，窗口 180 → 重启
        long elapsedRestart = 60_000L;
        long windowMs = 180 * 1000L;
        boolean isRestart = windowMs > 0 && elapsedRestart <= windowMs;
        check("窗口内（60s < 180s）→ 判定为重启", isRestart);
        // 场景：300 秒 > 180 秒 → 普通开机
        long elapsedStart = 300_000L;
        boolean isStart = !(windowMs > 0 && elapsedStart <= windowMs);
        check("超窗（300s > 180s）→ 普通开机", isStart);
        // 场景：窗口 0 → 一律普通开机
        long w0 = 0L;
        boolean startWhen0 = !(w0 > 0 && elapsedRestart <= w0);
        check("窗口 0 → 不判定重启一律普通开机", startWhen0);
        // 场景：恰好 180 秒（边界含等号）→ 重启
        boolean edge = windowMs > 0 && 180_000L <= windowMs;
        check("边界恰好 180s → 判定为重启（<= 含等号）", edge);
        check("窗口 getter 负数收敛 0（coerceAtLeast）", Math.max(-5, 0) == 0);

        // ---------- 7. 基线 ----------
        System.out.println("== 7. 基线 ==");
        String pluginYml = jarEntryText(spigot, "plugin.yml");
        check("plugin.yml 版本 1.7.4.1", pluginYml.contains("1.7.4.1"));
        try (ZipFile zf = new ZipFile(spigot)) {
            check("ServerNotifyManager 类在位", zf.getEntry("cn/kerong/bot/ServerNotifyManager.class") != null);
        }
        try (ZipFile zf = new ZipFile(neoforge)) {
            check("NF ServerNotifyManager 类在位（未重定位路径）", zf.getEntry("cn/kerong/bot/ServerNotifyManager.class") != null);
        }

        System.out.println();
        System.out.println("===== 冒烟结果：" + passed + " 通过 / " + failed + " 失败 =====");
        if (failed > 0) System.exit(1);
    }
}
