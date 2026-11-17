import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.7.6.4 覆盖轮冒烟（独立 JVM，真实类）：
 *
 * D 组 死亡播报出口（ConfigProvider 默认方法，Java 匿名实现直接继承）——
 *   昵称与死因分开：默认模板「[游戏] {name} {message}」（中间一个空格）；
 *   {message} 纯死因不再自动拼名；插件/模组自定义文案开头的死者名剥除
 *   （大小写不敏感 / 全角空格 / 剥完为空回退「死了」）；名字在句中的保留；
 *   null / 空白回退「死了」；自定义模板 {name}/{message}/{killer} 全占位符。
 * F 组 PlayerEventFormat 新字段 deathCustomCauses（第 7 参）读写。
 * U 组 ConfigFileRebuilder 升级收敛端到端（真实 fake JavaPlugin + JAR 内置
 *   模板）：cv61 旧默认 format 收敛为新默认「[游戏] {name} {message}」+
 *   custom-causes 新键带注释补入；自定义 format / 自定义 custom-causes 值
 *   原样保留；cv62 文件不触发重建。
 * W 组 ConfigManager.playerEventFormat 读键：custom-causes 缺键默认 true、
 *   显式 false 读出 false、format 新默认。
 *
 * 编译运行：javac/java -cp NeoForge 分发 JAR（+ slf4j-api / slf4j-nop，
 *   ConfigFileRebuilder 成功路径打日志需 slf4j；见 classpath.txt）。
 */
public class Test1764o {
    static int pass = 0, fail = 0;
    static final List<String> fails = new ArrayList<>();

    static void check(String name, boolean ok) { check(name, ok, null); }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS " + name); }
        else { fail++; fails.add(name + (detail == null ? "" : " -> " + detail));
               System.out.println("  FAIL " + name + (detail == null ? "" : " -> " + detail)); }
    }

    static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static File write(File f, String content) throws Exception {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /** 1.7.6.4 首发轮用户配置形态（cv61，死亡播报旧默认文案）。 */
    static final String CFG_V61_OLD_DEFAULT =
        "# 配置文件版本号（插件自动维护，勿改）\n"
        + "config-version: 61\n\n"
        + "player-events:\n"
        + "  join:\n"
        + "    enabled: true\n"
        + "    format: \"[游戏] {name} 加入了服务器\"\n"
        + "  quit:\n"
        + "    enabled: true\n"
        + "    format: \"[游戏] {name} 离开了服务器\"\n"
        + "  death:\n"
        + "    enabled: false\n"
        + "    format: \"[游戏] {message}\"\n";

    static org.bukkit.plugin.java.JavaPlugin fakePlugin(File dir) {
        return new org.bukkit.plugin.java.JavaPlugin() {
            @Override public String getName() { return "KERONGPenguin"; }
            @Override public File getDataFolder() { return dir; }
        };
    }

    public static void main(String[] args) throws Exception {
        File work = Files.createTempDirectory("smoke1764o").toFile();

        System.out.println("== D 组：死亡播报出口（昵称与死因分开） ==");
        cn.kerong.bot.provider.ConfigProvider cp =
            new cn.kerong.bot.provider.ConfigProvider() {
                public cn.kerong.bot.provider.ChatFormat getChatFormat() { return null; }
                public cn.kerong.bot.provider.Motd getMotd() { return null; }
                public String getBotName() { return "测试服"; }
                public String getPlatform() { return "test"; }
                public String getPluginVersion() { return "1.7.6.4"; }
            };
        String d1 = cp.formatPlayerDeathMessage("Steve", "从高处摔落而死", null);
        check("D1 内置描述不含名 → {name} + 空格 + 死因",
              d1.equals("[游戏] Steve 从高处摔落而死"), d1);
        String d2 = cp.formatPlayerDeathMessage("Steve", "Steve 在决斗中被 Alex 击败", "Alex");
        check("D2 插件文案开头带名 → 剥除不重复（killer 替换）",
              d2.equals("[游戏] Steve 在决斗中被 Alex 击败"), d2);
        String d3 = cp.formatPlayerDeathMessage("Steve", null, null);
        check("D3 deathMessage=null → 纯「死了」", d3.equals("[游戏] Steve 死了"), d3);
        String d4 = cp.formatPlayerDeathMessage("Steve", "steve 被陨石砸死了", null);
        check("D4 开头名大小写不敏感剥除",
              d4.equals("[游戏] Steve 被陨石砸死了"), d4);
        String d5 = cp.formatPlayerDeathMessage("Steve", "Alex 击败了 Steve", "Alex");
        check("D5 名字在句中 → 保留不动",
              d5.equals("[游戏] Steve Alex 击败了 Steve"), d5);
        String d6 = cp.formatPlayerDeathMessage("Steve", "Steve", null);
        check("D6 剥完为空 → 回退「死了」", d6.equals("[游戏] Steve 死了"), d6);
        String d7 = cp.formatPlayerDeathMessage("Steve", "Steve　在决斗中被 Alex 击败", "Alex");
        check("D7 全角空格随名剥除",
              d7.equals("[游戏] Steve 在决斗中被 Alex 击败"), d7);
        String d8 = cp.formatPlayerDeathMessage("Steve", "Steve was slain by Zombie", null);
        check("D8 英文原版消息剥开头名（出口不拼名）",
              d8.equals("[游戏] Steve was slain by Zombie"), d8);
        String d9 = cp.formatPlayerDeathMessage("Steve", "   ", null);
        check("D9 空白 message → 「死了」", d9.equals("[游戏] Steve 死了"), d9);
        // D10 自定义模板（{name}/{message}/{killer} 全占位符，昵称与死因分开）
        cn.kerong.bot.provider.PlayerEventFormat custom =
            new cn.kerong.bot.provider.PlayerEventFormat(
                true, "j", true, "q", true, "{name}|{message}|{killer}", false);
        cn.kerong.bot.provider.ConfigProvider cp2 =
            new cn.kerong.bot.provider.ConfigProvider() {
                public cn.kerong.bot.provider.ChatFormat getChatFormat() { return null; }
                public cn.kerong.bot.provider.Motd getMotd() { return null; }
                public String getBotName() { return "测试服"; }
                public String getPlatform() { return "test"; }
                public String getPluginVersion() { return "1.7.6.4"; }
                @Override
                public cn.kerong.bot.provider.PlayerEventFormat playerEventFormat() { return custom; }
            };
        String d10 = cp2.formatPlayerDeathMessage("Alex", "被僵尸杀死了", "Zombie");
        check("D10 自定义模板三占位符分开替换",
              d10.equals("Alex|被僵尸杀死了|Zombie"), d10);
        String d11 = cp2.formatPlayerDeathMessage("Bob", null, null);
        check("D11 自定义模板 null message → 死了 + killer 未知",
              d11.equals("Bob|死了|未知"), d11);

        System.out.println("== F 组：PlayerEventFormat deathCustomCauses 字段 ==");
        check("F1 第 7 参 false 读回 false",
              !custom.getDeathCustomCauses());
        cn.kerong.bot.provider.PlayerEventFormat on =
            new cn.kerong.bot.provider.PlayerEventFormat(
                true, "j", true, "q", true, "[游戏] {name} {message}", true);
        check("F2 第 7 参 true 读回 true", on.getDeathCustomCauses());
        check("F3 deathFormat 读回传入值",
              on.getDeathFormat().equals("[游戏] {name} {message}"));
        check("F4 deathEnabled 读回", on.getDeathEnabled());

        System.out.println("== U 组：ConfigFileRebuilder 升级收敛端到端 ==");
        Class<?> reb = Class.forName("cn.kerong.penguin.spigot.manager.ConfigFileRebuilder");
        Object rebInstance = reb.getField("INSTANCE").get(null);
        java.lang.reflect.Method m = reb.getMethod("rebuildIfNeeded",
            org.bukkit.plugin.java.JavaPlugin.class, int.class, List.class, List.class);
        // U1 cv61 + 旧默认 format → 收敛为新默认 + custom-causes 补入 + cv62
        File dir1 = new File(work, "u1/plugins/KERONGPenguin");
        write(new File(dir1, "config.yml"), CFG_V61_OLD_DEFAULT);
        Object r1 = m.invoke(rebInstance, fakePlugin(dir1), 62,
                             new ArrayList<Object>(), new ArrayList<Object>());
        String after1 = read(new File(dir1, "config.yml"));
        check("U1a 旧默认 format 收敛为 [游戏] {name} {message}",
              Boolean.TRUE.equals(r1)
                  && after1.contains("format: \"[游戏] {name} {message}\""), after1);
        check("U1b custom-causes 新键带注释补入",
              after1.contains("custom-causes: true")
                  && after1.contains("自定义死亡原因"), after1);
        check("U1c config-version 62", after1.contains("config-version: 62"));
        check("U1d join/quit 值原样保留",
              after1.contains("\"[游戏] {name} 加入了服务器\""));
        // U2 自定义 format → 原样保留
        File dir2 = new File(work, "u2/plugins/KERONGPenguin");
        write(new File(dir2, "config.yml"), CFG_V61_OLD_DEFAULT.replace(
            "format: \"[游戏] {message}\"", "format: \"[游戏] 死讯：{message}\""));
        Object r2 = m.invoke(rebInstance, fakePlugin(dir2), 62,
                             new ArrayList<Object>(), new ArrayList<Object>());
        String after2 = read(new File(dir2, "config.yml"));
        check("U2 自定义 format 不被收敛",
              Boolean.TRUE.equals(r2)
                  && after2.contains("format: \"[游戏] 死讯：{message}\"")
                  && !after2.contains("format: \"[游戏] {message}\""), after2);
        // U3 cv62 → 不触发重建
        File dir3 = new File(work, "u3/plugins/KERONGPenguin");
        write(new File(dir3, "config.yml"), after1);
        Object r3 = m.invoke(rebInstance, fakePlugin(dir3), 62,
                             new ArrayList<Object>(), new ArrayList<Object>());
        check("U3 cv62 文件不触发重建", Boolean.FALSE.equals(r3)
              && read(new File(dir3, "config.yml")).equals(after1));
        // U4 用户已写 custom-causes: false → 重建保留 false
        File dir4 = new File(work, "u4/plugins/KERONGPenguin");
        write(new File(dir4, "config.yml"), CFG_V61_OLD_DEFAULT.replace(
            "    format: \"[游戏] {message}\"",
            "    format: \"[游戏] {message}\"\n    custom-causes: false"));
        Object r4 = m.invoke(rebInstance, fakePlugin(dir4), 62,
                             new ArrayList<Object>(), new ArrayList<Object>());
        String after4 = read(new File(dir4, "config.yml"));
        check("U4 预写 custom-causes: false 保留",
              Boolean.TRUE.equals(r4) && after4.contains("custom-causes: false"), after4);

        System.out.println("== W 组：ConfigManager.playerEventFormat 读键 ==");
        // W1 显式 false
        File dir5 = new File(work, "w1/plugins/KERONGPenguin");
        write(new File(dir5, "config.yml"),
              "config-version: 62\nplayer-events:\n  death:\n"
              + "    enabled: true\n    custom-causes: false\n");
        org.bukkit.plugin.java.JavaPlugin p5 = fakePlugin(dir5);
        p5.reloadConfig();
        cn.kerong.penguin.spigot.manager.ConfigManager cm5 =
            new cn.kerong.penguin.spigot.manager.ConfigManager(p5);
        cn.kerong.bot.provider.PlayerEventFormat f5 = cm5.playerEventFormat();
        check("W1 custom-causes: false 读出 false",
              !f5.getDeathCustomCauses() && f5.getDeathEnabled());
        check("W2 format 键缺失 → 代码新默认",
              f5.getDeathFormat().equals("[游戏] {name} {message}"), f5.getDeathFormat());
        // W3 缺 custom-causes 键 → 默认 true
        File dir6 = new File(work, "w2/plugins/KERONGPenguin");
        write(new File(dir6, "config.yml"),
              "config-version: 62\nplayer-events:\n  death:\n"
              + "    enabled: true\n    format: \"[游戏] {name} {message}\"\n");
        org.bukkit.plugin.java.JavaPlugin p6 = fakePlugin(dir6);
        p6.reloadConfig();
        cn.kerong.penguin.spigot.manager.ConfigManager cm6 =
            new cn.kerong.penguin.spigot.manager.ConfigManager(p6);
        check("W3 custom-causes 缺键 → 默认 true",
              cm6.playerEventFormat().getDeathCustomCauses());

        System.out.println();
        System.out.println("结果: " + pass + " 通过, " + fail + " 失败");
        if (!fails.isEmpty()) {
            System.out.println("失败项:");
            for (String f : fails) System.out.println("  - " + f);
            System.exit(1);
        }
    }
}
