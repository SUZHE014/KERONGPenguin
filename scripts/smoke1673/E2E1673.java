import cn.kerong.penguin.spigot.manager.YamlTextMerge;
import cn.kerong.shaded.org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 端到端：真实 JAR config.yml 模板 + 1.6.7.2 覆盖版用户磁盘形态（块状 groups）
 * → 模拟重建 carry → merge → 断言行内格式 + 解析合法。
 */
public class E2E1673 {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name); }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // 1. 读 JAR 内真实模板全文
        String template;
        try (InputStream in = E2E1673.class.getResourceAsStream("/config.yml")) {
            if (in == null) { System.out.println("FAIL  未找到 /config.yml（classpath 需含分发 JAR）"); System.exit(1); }
            template = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        check("E1 JAR 模板读取（含 config-version: 22）", template.contains("config-version: 22"));

        // 2. 模拟 1.6.7.2 覆盖版用户磁盘文件（块状 groups + 自定义值），
        //    用 snakeyaml 解析成树（近似 Bukkit get() 语义）
        String userDisk = String.join("\n",
            "config-version: 21",
            "serverName: \"MyServer\"",
            "bot:",
            "  app-id: \"1905475723\"",
            "  secret: \"my-secret\"",
            "  name: \"KERONGPenguin\"",
            "  reconnect-notify: true",
            "  groups:",
            "    - 2532FC1C7629849FCE6491F3B72A06D2",
            "chat-format:",
            "  from-game: \"[游戏] <{name}> {message}\"",
            "  from-group: \"[§bQQ§r] {name}: {message}\"",
            "  post-chat: true",
            "  start-with: \"#\"",
            "admin:",
            "  mode: \"qq\"",
            "  openids: []",
            "filter-regex: []",
            "custom-commands: []",
            "render:",
            "  global-opacity: 0.85",
            "  performance:",
            "    cache-seconds: 90",
            "commands:",
            "  查性能: true",
            "config-version-dup-guard: x"
        ) + "\n";
        Map<String, Object> disk = new Yaml().load(userDisk);

        // 3. 模拟 ConfigFileRebuilder 的 carry 收集：与 JAR 默认不同的项
        Map<String, Object> jarDefaults = new Yaml().load(template);
        Map<String, Object> carry = new LinkedHashMap<>();
        collect(disk, "", jarDefaults, carry);
        carry.remove("config-version");
        carry.remove("config-version-dup-guard"); // 测试专用键，JAR 无 → 会进 carry，此处移除模拟 removedKeys

        // 4. merge + 断言
        String merged = YamlTextMerge.INSTANCE.merge(template, carry);
        check("E2 单群 groups 回填为行内双引号",
              merged.contains("  groups: [\"2532FC1C7629849FCE6491F3B72A06D2\"]"));
        check("E3 自定义字符串回填双引号（app-id）",
              merged.contains("app-id: \"1905475723\""));
        check("E4 数字缓存值原样（cache-seconds: 90）",
              merged.contains("cache-seconds: 90"));
        check("E5 透明度数值原样（global-opacity: 0.85）",
              merged.contains("global-opacity: 0.85"));
        check("E6 命令开关布尔原样（查性能: true）",
              merged.contains("查性能: true"));

        // 5. 解析合法性 + 语义
        Map<String, Object> result = new Yaml().load(merged);
        Map<String, Object> bot = (Map<String, Object>) result.get("bot");
        check("E7 重建结果解析合法且单群值保留",
              bot != null && Arrays.asList("2532FC1C7629849FCE6491F3B72A06D2").equals(bot.get("groups")));
        check("E8 secret 语义保留", bot != null && "my-secret".equals(bot.get("secret")));
        Map<String, Object> render = (Map<String, Object>) result.get("render");
        Map<String, Object> perf = render == null ? null : (Map<String, Object>) render.get("performance");
        check("E9 自定义缓存秒数保留", perf != null && Integer.valueOf(90).equals(perf.get("cache-seconds")));
        check("E10 版本号已升 22", merged.contains("config-version: 22"));
        System.out.println("---- passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    /** 递归收集叶子（近似 ConfigFileRebuilder.collectLeaves + 等值比较）。 */
    @SuppressWarnings("unchecked")
    static void collect(Map<String, Object> node, String prefix,
                        Map<String, Object> jar, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : node.entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object value = e.getValue();
            if (value instanceof Map) {
                collect((Map<String, Object>) value, path, jar, out);
            } else {
                Object jarValue = resolve(jar, path);
                if (jarValue == null || !value.equals(jarValue)) out.put(path, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Object resolve(Map<String, Object> root, String path) {
        Object node = root;
        for (String seg : path.split("\\.", -1)) {
            if (!(node instanceof Map)) return null;
            node = ((Map<String, Object>) node).get(seg);
        }
        return node;
    }
}
