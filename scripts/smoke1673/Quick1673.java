import cn.kerong.penguin.spigot.manager.YamlTextMerge;
import org.yaml.snakeyaml.Yaml;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 1.6.7.3 快速验证：renderEntry 行内 flow 双引号格式（正式冒烟前置自检）。 */
public class Quick1673 {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name); }
    }

    public static void main(String[] args) {
        String template = String.join("\n",
            "bot:",
            "  app-id: \"\"",
            "  secret: \"\"",
            "  name: \"KERONGPenguin\"",
            "  reconnect-notify: true",
            "  # 示例：groups: [\"ABC123456XYZ\", \"DEF789XYZ\"]",
            "  groups: []",
            "chat-format:",
            "  from-game: \"[游戏] <{name}> {message}\"",
            "  from-group: \"[§bQQ§r] {name}: {message}\"",
            "admin:",
            "  mode: \"qq\"",
            "  openids: []",
            "filter-regex: []",
            "custom-commands: []",
            "config-version: 22"
        ) + "\n";

        // 1. 单元素列表（单群用户场景）→ 行内双引号
        Map<String, Object> single = new LinkedHashMap<>();
        single.put("bot.groups", Arrays.asList("2532FC1C7629849FCE6491F3B72A06D2"));
        String m1 = YamlTextMerge.INSTANCE.merge(template, single);
        check("1 单元素列表渲染为行内双引号", m1.contains("  groups: [\"2532FC1C7629849FCE6491F3B72A06D2\"]"));
        check("1 不再产出块状项行", !m1.contains("- 2532FC1C"));
        check("1 不产出非法行内列表", !m1.contains("groups: - "));
        Map<String, Object> root1 = new Yaml().load(m1);
        @SuppressWarnings("unchecked") Map<String, Object> bot1 = (Map<String, Object>) root1.get("bot");
        check("1 语义正确（解析回读单群 OpenId 保留）",
            bot1.get("groups").equals(Arrays.asList("2532FC1C7629849FCE6491F3B72A06D2")));

        // 2. 多元素列表 → 行内双引号逗号分隔
        Map<String, Object> multi = new LinkedHashMap<>();
        multi.put("bot.groups", Arrays.asList("ABC123456XYZ", "DEF789XYZ"));
        String m2 = YamlTextMerge.INSTANCE.merge(template, multi);
        check("2 多元素行内双引号", m2.contains("  groups: [\"ABC123456XYZ\", \"DEF789XYZ\"]"));
        Map<String, Object> root2 = new Yaml().load(m2);
        @SuppressWarnings("unchecked") Map<String, Object> bot2 = (Map<String, Object>) root2.get("bot");
        check("2 多元素语义正确", bot2.get("groups").equals(Arrays.asList("ABC123456XYZ", "DEF789XYZ")));

        // 3. 模板外自定义键 + 单元素列表插入（行内）
        Map<String, Object> insert = new LinkedHashMap<>();
        insert.put("bot.groups", Arrays.asList("G1OPENID"));
        String m3 = YamlTextMerge.INSTANCE.merge(template, insert);
        Map<String, Object> root3 = new Yaml().load(m3);
        @SuppressWarnings("unchecked") Map<String, Object> bot3 = (Map<String, Object>) root3.get("bot");
        check("3 插入场景语义正确", bot3.get("groups").equals(Arrays.asList("G1OPENID")));

        // 4. 空列表回填 → 保持 []
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("bot.groups", Arrays.asList());
        String m4 = YamlTextMerge.INSTANCE.merge(template, empty);
        check("4 空列表保持 []", m4.contains("  groups: []"));

        // 5. List<Map>（custom-commands 形态）→ flow 单行合法
        Map<String, Object> cmd = new LinkedHashMap<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", "菜单");
        item.put("command", "say 你好");
        item.put("permission", 0);
        cmd.put("custom-commands", Arrays.asList(item));
        String m5 = YamlTextMerge.INSTANCE.merge(template, cmd);
        check("5 custom-commands 单行 flow", m5.contains("custom-commands: [{"));
        Map<String, Object> root5 = new Yaml().load(m5);
        check("5 custom-commands 语义正确", root5.get("custom-commands") instanceof List
            && ((List<?>) root5.get("custom-commands")).size() == 1);

        // 6. 字符串标量 → 双引号（含特殊字符转义安全）
        Map<String, Object> str = new LinkedHashMap<>();
        str.put("bot.secret", "s3cr et\"quote");
        str.put("chat-format.from-group", "[§bQQ§r] {name}: {message}");
        String m6 = YamlTextMerge.INSTANCE.merge(template, str);
        check("6 字符串双引号转义（含引号）", m6.contains("  secret: \"s3cr et\\\"quote\""));
        Map<String, Object> root6 = new Yaml().load(m6);
        @SuppressWarnings("unchecked") Map<String, Object> bot6 = (Map<String, Object>) root6.get("bot");
        check("6 转义回读语义正确", "s3cr et\"quote".equals(bot6.get("secret")));

        // 7. 布尔标量原样（不套引号）
        Map<String, Object> num = new LinkedHashMap<>();
        num.put("bot.reconnect-notify", false);
        String m7 = YamlTextMerge.INSTANCE.merge(template, num);
        check("7 布尔原样不套引号", m7.contains("  reconnect-notify: false"));
        Map<String, Object> root7 = new Yaml().load(m7);
        @SuppressWarnings("unchecked") Map<String, Object> bot7 = (Map<String, Object>) root7.get("bot");
        check("7 布尔语义正确", Boolean.FALSE.equals(bot7.get("reconnect-notify")));

        // 8. 混合数字+字符串列表
        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("bot.groups", Arrays.asList("ABC", 123, true));
        String m8 = YamlTextMerge.INSTANCE.merge(template, mixed);
        check("8 混合标量行内", m8.contains("  groups: [\"ABC\", 123, true]"));
        Map<String, Object> root8 = new Yaml().load(m8);
        @SuppressWarnings("unchecked") Map<String, Object> bot8 = (Map<String, Object>) root8.get("bot");
        check("8 混合标量语义正确", bot8.get("groups").equals(Arrays.asList("ABC", 123, true)));

        // 9. 注释保留
        check("9 模板注释保留", m1.contains("# 示例：groups: [\"ABC123456XYZ\", \"DEF789XYZ\"]"));

        // 10. 与教程示例同风（用户手写格式 = 回填格式）
        check("10 与教程示例同风", m1.contains("  groups: [\"2532FC1C7629849FCE6491F3B72A06D2\"]"));

        System.out.println("---- passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
