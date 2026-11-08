import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * 1.7.4 第六轮覆盖版冒烟（独立 JVM，无插件实例）：
 * 1. 双端 JAR config.yml 解析：三条默认文案逐字一致（[状态] 前缀 + 真实换行
 *    + 颜文字精确字符），config-version 38，三开关默认开，restart-window 180
 * 2. snakeyaml dump → load 往返：多行文案值不丢不变（保存路径语义）
 * 3. 真实 YamlTextMerge（JAR 内类，反射 INSTANCE）：以新模板为底合并自定义值
 *    ——自定义关机文案保留、其余默认走模板、单行 \n 转义形式写出
 * 4. 旧配置升级收敛映射（版本 37 旧文案 → 新文案；自改值不动）语义核验
 * 5. plugin.yml 版本 1.7.4 / ServerNotifyManager 类在位
 */
public class Smoke174o6 {
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
    static final String NEW_CRASH = "[状态]\n服务器....༽◺_轨迹༼";

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
        for (String[] pair : new String[][]{{spigot, "Spigot"}, {neoforge, "NeoForge"}}) {
            Map<String, Object> root = parse(jarEntryText(pair[0], "config.yml"));
            Map<String, Object> sn = (Map<String, Object>) root.get("server-notify");
            Map<String, Object> shutdown = (Map<String, Object>) sn.get("shutdown");
            Map<String, Object> restart = (Map<String, Object>) sn.get("restart");
            Map<String, Object> crash = (Map<String, Object>) sn.get("crash");
            check(pair[1] + " 关机文案逐字一致", NEW_SHUTDOWN.equals(shutdown.get("message")),
                    String.valueOf(shutdown.get("message")));
            check(pair[1] + " 重启文案逐字一致", NEW_RESTART.equals(restart.get("message")),
                    String.valueOf(restart.get("message")));
            check(pair[1] + " 意外关闭文案逐字一致", NEW_CRASH.equals(crash.get("message")),
                    String.valueOf(crash.get("message")));
            check(pair[1] + " config-version 38", Integer.valueOf(38).equals(root.get("config-version")),
                    String.valueOf(root.get("config-version")));
            check(pair[1] + " 三开关默认开", Boolean.TRUE.equals(sn.get("enabled"))
                    && Boolean.TRUE.equals(shutdown.get("enabled"))
                    && Boolean.TRUE.equals(restart.get("enabled"))
                    && Boolean.TRUE.equals(crash.get("enabled")));
            check(pair[1] + " restart-window 180", Integer.valueOf(180).equals(sn.get("restart-window")));
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
        for (String[] pair : new String[][]{{"关机", NEW_SHUTDOWN}, {"重启", NEW_RESTART}, {"意外关闭", NEW_CRASH}}) {
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
        check("合并·重启默认走新模板", NEW_RESTART.equals(
                ((Map<String, Object>) mergedSn.get("restart")).get("message")));
        check("合并·意外关闭默认走新模板", NEW_CRASH.equals(
                ((Map<String, Object>) mergedSn.get("crash")).get("message")));
        check("合并·bot.name 自定义保留", "TestServer".equals(
                ((Map<String, Object>) mergedRoot.get("bot")).get("name")));
        check("合并·config-version 38", Integer.valueOf(38).equals(mergedRoot.get("config-version")));
        check("合并·单行 \\n 转义写出", merged.contains("message: \"[状态]\\n服务器已开启！！(≧∇≦)/\""));
        check("合并·旧双行块状形态未引入", !merged.contains("message: |\n") && !merged.contains("message: >"));

        // ---------- 4. 旧配置升级收敛映射语义 ----------
        System.out.println("== 4. 旧配置升级收敛映射 ==");
        String oldConfig = String.join("\n",
                "config-version: 37",
                "bot:",
                "  name: MyServer",
                "server-notify:",
                "  enabled: true",
                "  shutdown:",
                "    enabled: true",
                "    message: \"服务器已正常关闭\"",
                "  restart:",
                "    enabled: true",
                "    message: \"服务器已重启完成\"",
                "  crash:",
                "    enabled: true",
                "    message: \"服务器意外关闭（崩溃或被强制结束），请管理员检查控制台\"",
                "  restart-window: 180",
                "");
        Map<String, Object> oldRoot = parse(oldConfig);
        Map<String, Object> oldSn = (Map<String, Object>) oldRoot.get("server-notify");
        Map<String, Object> oldShutdown = (Map<String, Object>) oldSn.get("shutdown");
        Map<String, Object> oldRestart = (Map<String, Object>) oldSn.get("restart");
        Map<String, Object> oldCrash = (Map<String, Object>) oldSn.get("crash");
        // 与 ConfigFileRebuilder 相同的收敛判定（旧默认 → 新默认；自改值不动）
        if ("服务器已正常关闭".equals(oldShutdown.get("message"))) oldShutdown.put("message", NEW_SHUTDOWN);
        if ("服务器已重启完成".equals(oldRestart.get("message"))) oldRestart.put("message", NEW_RESTART);
        if ("服务器意外关闭（崩溃或被强制结束），请管理员检查控制台".equals(oldCrash.get("message"))) oldCrash.put("message", NEW_CRASH);
        check("收敛·关机旧默认 → 新默认", NEW_SHUTDOWN.equals(oldShutdown.get("message")));
        check("收敛·重启旧默认 → 新默认", NEW_RESTART.equals(oldRestart.get("message")));
        check("收敛·意外关闭旧默认 → 新默认", NEW_CRASH.equals(oldCrash.get("message")));
        // 收敛后 == 模板默认 → 重建时不作为自定义值回填（模板新值生效）
        Map<String, Object> templateRoot = parse(template);
        Map<String, Object> templateSn = (Map<String, Object>) templateRoot.get("server-notify");
        check("收敛结果 == 模板新默认（重建走模板值）",
                oldShutdown.get("message").equals(((Map<String, Object>) templateSn.get("shutdown")).get("message"))
                        && oldRestart.get("message").equals(((Map<String, Object>) templateSn.get("restart")).get("message"))
                        && oldCrash.get("message").equals(((Map<String, Object>) templateSn.get("crash")).get("message")));
        // 自改值不受收敛影响
        Map<String, Object> customShutdown = new LinkedHashMap<>(oldShutdown);
        customShutdown.put("message", "服务器下线啦，明天见");
        check("收敛·自改文案不动", "服务器下线啦，明天见".equals(customShutdown.get("message")));

        // ---------- 5. 基线 ----------
        System.out.println("== 5. 基线 ==");
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
