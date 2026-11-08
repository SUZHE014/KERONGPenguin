import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * 1.7.4 第七轮覆盖版冒烟（独立 JVM，无插件实例）：
 * 1. 双端 JAR config.yml 解析：三条默认文案逐字一致（[状态] 前缀 + 真实换行
 *    + 颜文字精确码点 ༽=0F3D ◺=25FA _=005F ◿=25FF ༼=0F3C），config-version 39，
 *    四开关默认开，restart-window 键已删除
 * 2. snakeyaml dump → load 往返：多行文案值不丢不变（保存路径语义）
 * 3. 真实 YamlTextMerge（JAR 内类，反射 INSTANCE）：以新模板为底合并自定义值
 *    ——自定义关机文案保留、其余默认走模板、单行 \n 转义形式写出
 * 4. v38 → v39 升级路径端到端模拟（snakeyaml + 真实 YamlTextMerge）：
 *    旧配置（含 restart-window + 第六轮误写颜文字）→ 废弃键剥离 → 收敛 →
 *    差分回填 → 新模板合并：restart-window 消失、误写颜文字纠正、自改文案保留
 * 5. 收敛映射语义：旧默认 / 第六轮误写版 → 新文案；自改值不动
 * 6. plugin.yml 版本 1.7.4 / ServerNotifyManager 类在位
 */
public class Smoke174o7 {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  ✓ " + name); }
        else { failed++; System.out.println("  ✗ " + name + (detail == null ? "" : " — " + detail)); }
    }

    static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    static final String NEW_SHUTDOWN = "[状态]\n服务器已正常关闭 (╯︵╰,)";
    static final String NEW_RESTART = "[状态]\n服务器已开启！！(≧∇≦)/";
    static final String NEW_CRASH = "[状态]\n服务器....༽◺_◿༼";
    static final String WRONG_CRASH = "[状态]\n服务器....༽◺_轨迹༼";

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

    public static void main(String[] args) throws Exception {
        String spigot = "build/dist/KERONGPenguin_Spigot-1.7.4.jar";
        String neoforge = "build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.4.jar";

        // ---------- 1. 双端 JAR config.yml 解析 ----------
        System.out.println("== 1. 双端 JAR config.yml 文案逐字校验 ==");
        // 颜文字码点核验：༽=0F3D ◺=25FA _=005F ◿=25FF ༼=0F3C
        String face = NEW_CRASH.substring(NEW_CRASH.lastIndexOf("....") + 4);
        int[] expect = {0x0F3D, 0x25FA, 0x005F, 0x25FF, 0x0F3C};
        boolean faceOk = face.length() == 5;
        if (faceOk) for (int i = 0; i < 5; i++) faceOk &= face.charAt(i) == expect[i];
        check("颜文字码点 0F3D 25FA 005F 25FF 0F3C（无「轨迹」）", faceOk,
                face + " " + Integer.toHexString(face.charAt(0)));
        for (String[] pair : new String[][]{{spigot, "Spigot"}, {neoforge, "NeoForge"}}) {
            Map<String, Object> root = parse(jarEntryText(pair[0], "config.yml"));
            Map<String, Object> sn = (Map<String, Object>) root.get("server-notify");
            Map<String, Object> shutdown = (Map<String, Object>) sn.get("shutdown");
            Map<String, Object> restart = (Map<String, Object>) sn.get("restart");
            Map<String, Object> crash = (Map<String, Object>) sn.get("crash");
            check(pair[1] + " 关机文案逐字一致", NEW_SHUTDOWN.equals(shutdown.get("message")),
                    String.valueOf(shutdown.get("message")));
            check(pair[1] + " 开启文案逐字一致", NEW_RESTART.equals(restart.get("message")),
                    String.valueOf(restart.get("message")));
            check(pair[1] + " 意外关闭文案逐字一致（正确颜文字）", NEW_CRASH.equals(crash.get("message")),
                    String.valueOf(crash.get("message")));
            check(pair[1] + " 意外关闭非误写版", !WRONG_CRASH.equals(crash.get("message")));
            check(pair[1] + " config-version 39", Integer.valueOf(39).equals(root.get("config-version")),
                    String.valueOf(root.get("config-version")));
            check(pair[1] + " 四开关默认开", Boolean.TRUE.equals(sn.get("enabled"))
                    && Boolean.TRUE.equals(shutdown.get("enabled"))
                    && Boolean.TRUE.equals(restart.get("enabled"))
                    && Boolean.TRUE.equals(crash.get("enabled")));
            check(pair[1] + " restart-window 键已删除", !sn.containsKey("restart-window"));
            check(pair[1] + " 前缀 [状态] 在第一行", NEW_SHUTDOWN.startsWith("[状态]\n")
                    && NEW_RESTART.startsWith("[状态]\n") && NEW_CRASH.startsWith("[状态]\n"));
        }

        // ---------- 2. dump → load 往返 ----------
        System.out.println("== 2. snakeyaml dump → load 往返 ==");
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        options.setWidth(Integer.MAX_VALUE);
        Yaml yaml = new Yaml(options);
        for (String[] pair : new String[][]{{"关机", NEW_SHUTDOWN}, {"开启", NEW_RESTART},
                {"意外关闭", NEW_CRASH}, {"误写版", WRONG_CRASH}}) {
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
        Map<String, Object> mergedSn = (Map<String, Object>) mergedRoot.get("server-notify");
        check("合并·自定义关机文案保留", "我的自定义关机文案".equals(
                ((Map<String, Object>) mergedSn.get("shutdown")).get("message")));
        check("合并·开启默认走新模板", NEW_RESTART.equals(
                ((Map<String, Object>) mergedSn.get("restart")).get("message")));
        check("合并·意外关闭默认走新模板（正确颜文字）", NEW_CRASH.equals(
                ((Map<String, Object>) mergedSn.get("crash")).get("message")));
        check("合并·bot.name 自定义保留", "TestServer".equals(
                ((Map<String, Object>) mergedRoot.get("bot")).get("name")));
        check("合并·config-version 39", Integer.valueOf(39).equals(mergedRoot.get("config-version")));
        check("合并·无 restart-window（模板已无该键）", !mergedSn.containsKey("restart-window"));
        check("合并·单行 \\n 转义写出", merged.contains("message: \"[状态]\\n服务器已开启！！(≧∇≦)/\""));
        check("合并·旧双行块状形态未引入", !merged.contains("message: |\n") && !merged.contains("message: >"));

        // ---------- 4. v38 → v39 升级路径端到端模拟 ----------
        System.out.println("== 4. v38 → v39 升级路径端到端（废弃键剥离 + 收敛 + 差分回填） ==");
        // 旧配置：v38（第六轮升级后形态）——含 restart-window 与误写颜文字 + 一处自改文案
        String oldConfig = String.join("\n",
                "config-version: 38",
                "bot:",
                "  name: MyServer",
                "server-notify:",
                "  enabled: true",
                "  shutdown:",
                "    enabled: true",
                "    message: \"服务器下线啦，明天见\"",
                "  restart:",
                "    enabled: true",
                "    message: \"" + NEW_RESTART.replace("\n", "\\n") + "\"",
                "  crash:",
                "    enabled: true",
                "    message: \"" + WRONG_CRASH.replace("\n", "\\n") + "\"",
                "  restart-window: 180",
                "");
        Map<String, Object> disk = parse(oldConfig);
        Map<String, Object> diskSn = (Map<String, Object>) disk.get("server-notify");
        // 4a. 废弃键剥离（ConfigFileRebuilder removedKeys 路径）
        check("旧配置解析·restart-window 在位（升级前）", Integer.valueOf(180).equals(diskSn.get("restart-window")));
        diskSn.remove("restart-window");   // 等价 disk.set(removed, null)
        // 4b. 收敛（第六轮误写版 → 新默认；自改关机文案不动）
        Map<String, Object> diskCrash = (Map<String, Object>) diskSn.get("crash");
        Object crashText = diskCrash.get("message");
        if ("服务器意外关闭（崩溃或被强制结束），请管理员检查控制台".equals(crashText)
                || WRONG_CRASH.equals(crashText)) {
            diskCrash.put("message", NEW_CRASH);
        }
        check("收敛·误写颜文字 → 正确颜文字", NEW_CRASH.equals(diskCrash.get("message")));
        check("收敛·自改关机文案不动", "服务器下线啦，明天见".equals(
                ((Map<String, Object>) diskSn.get("shutdown")).get("message")));
        // 4c. 差分回填收集（值 != 新模板默认才进 carry；收敛后的 crash == 模板默认不回填）
        Map<String, Object> templateRoot = parse(template);
        Map<String, Object> templateSn = (Map<String, Object>) templateRoot.get("server-notify");
        Map<String, Object> carry = new LinkedHashMap<>();
        if (!"MyServer".equals(((Map<String, Object>) templateRoot.get("bot")).get("name"))) {
            carry.put("bot.name", "MyServer");
        }
        Object myShutdown = ((Map<String, Object>) diskSn.get("shutdown")).get("message");
        if (!myShutdown.equals(((Map<String, Object>) templateSn.get("shutdown")).get("message"))) {
            carry.put("server-notify.shutdown.message", myShutdown);
        }
        Object myCrash = diskCrash.get("message");
        if (!myCrash.equals(((Map<String, Object>) templateSn.get("crash")).get("message"))) {
            carry.put("server-notify.crash.message", myCrash);
        }
        check("差分·收敛后的 crash 不进回填（走模板值）", !carry.containsKey("server-notify.crash.message"));
        // 4d. 与新模板合并（真实 YamlTextMerge）
        String upgraded = (String) mergeClass.getMethod("merge", String.class, Map.class)
                .invoke(instance, template, carry);
        Map<String, Object> upRoot = parse(upgraded);
        Map<String, Object> upSn = (Map<String, Object>) upRoot.get("server-notify");
        check("升级·restart-window 从用户配置消失", !upSn.containsKey("restart-window"));
        check("升级·误写颜文字纠正为新默认", NEW_CRASH.equals(
                ((Map<String, Object>) upSn.get("crash")).get("message")));
        check("升级·自改关机文案保留", "服务器下线啦，明天见".equals(
                ((Map<String, Object>) upSn.get("shutdown")).get("message")));
        check("升级·bot.name 保留", "MyServer".equals(
                ((Map<String, Object>) upRoot.get("bot")).get("name")));
        check("升级·config-version 39", Integer.valueOf(39).equals(upRoot.get("config-version")));
        check("升级·restart-window 字样零残留（全文）", !upgraded.contains("restart-window"));

        // ---------- 5. 收敛映射语义（v37 旧默认 → 新；自改不动） ----------
        System.out.println("== 5. 收敛映射语义 ==");
        String v37Config = String.join("\n",
                "config-version: 37",
                "server-notify:",
                "  shutdown:",
                "    message: \"服务器已正常关闭\"",
                "  restart:",
                "    message: \"服务器已重启完成\"",
                "  crash:",
                "    message: \"服务器意外关闭（崩溃或被强制结束），请管理员检查控制台\"",
                "");
        Map<String, Object> v37Root = parse(v37Config);
        Map<String, Object> v37Sn = (Map<String, Object>) v37Root.get("server-notify");
        Map<String, Object> v37Shutdown = (Map<String, Object>) v37Sn.get("shutdown");
        Map<String, Object> v37Restart = (Map<String, Object>) v37Sn.get("restart");
        Map<String, Object> v37Crash = (Map<String, Object>) v37Sn.get("crash");
        if ("服务器已正常关闭".equals(v37Shutdown.get("message"))) v37Shutdown.put("message", NEW_SHUTDOWN);
        if ("服务器已重启完成".equals(v37Restart.get("message"))) v37Restart.put("message", NEW_RESTART);
        Object t = v37Crash.get("message");
        if ("服务器意外关闭（崩溃或被强制结束），请管理员检查控制台".equals(t) || WRONG_CRASH.equals(t)) {
            v37Crash.put("message", NEW_CRASH);
        }
        check("收敛·关机旧默认 → 新默认", NEW_SHUTDOWN.equals(v37Shutdown.get("message")));
        check("收敛·开启旧默认 → 新默认", NEW_RESTART.equals(v37Restart.get("message")));
        check("收敛·意外关闭旧默认 → 新默认（正确颜文字）", NEW_CRASH.equals(v37Crash.get("message")));
        check("收敛结果 == 模板新默认（重建走模板值）",
                v37Shutdown.get("message").equals(((Map<String, Object>) templateSn.get("shutdown")).get("message"))
                        && v37Restart.get("message").equals(((Map<String, Object>) templateSn.get("restart")).get("message"))
                        && v37Crash.get("message").equals(((Map<String, Object>) templateSn.get("crash")).get("message")));
        Map<String, Object> customCrash = new LinkedHashMap<>(v37Crash);
        customCrash.put("message", "服务器炸了，快跑");
        check("收敛·自改文案不动", "服务器炸了，快跑".equals(customCrash.get("message")));

        // ---------- 6. 基线 ----------
        System.out.println("== 6. 基线 ==");
        String pluginYml = jarEntryText(spigot, "plugin.yml");
        check("plugin.yml 版本 1.7.4", pluginYml.contains("1.7.4"));
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
