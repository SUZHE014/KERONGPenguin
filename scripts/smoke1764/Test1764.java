import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.7.6.4 冒烟（独立 JVM，真实类）：
 *
 * K 组 提示词技能段随插件更新刷新（本轮新增，用户口径「修复提示词.md在
 *   安装新插件时没有更新技能字段的问题（仅插件更新替换时更新）」）——
 *   skillSection 同源、applySkillSection 纯函数（替换 / 追加 / 幂等 / 段尾形态）、
 *   syncSkillSection 端到端（全新安装零打扰 / 1.7.6.2 老文件升级追加 / 同版本
 *   重启不触发 / 记录落 globalconfig / 开关关按无搜索行刷新 / 空版本跳过 /
 *   1.7.6.3 文件升级内容一致零写入）；
 * G 组 爬取开关补行回归（1.7.6.4 首发轮机制，本轮不动，回归防退化）；
 * D 组 死亡播报出口（ConfigProvider 默认方法，Java 匿名实现直接继承）——
 *   {message} 自动拼玩家名 / 插件文案已含名不重复拼 / null 回退 / {killer}。
 *
 * 编译运行：javac/java -cp NeoForge 分发 JAR（DataLayout 签名引用 JavaPlugin，
 * 需自带 bukkit 兼容层的 NeoForge 分发包；见 classpath.txt）。
 */
public class Test1764 {
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

    // 1.7.6.2 及更早版本生成的提示词（无「技能」段）
    static final String LEGACY_1762 =
        "# 角色设定\n\n你是一个友好的游戏群助手，请简洁回答。\n\n"
        + "# 专属知识（服务器规则、玩法说明、常见问题等，写在这里 AI 对话时会参考）\n\n"
        + "（在这里补充内容，可删除本行）\n";

    // 1.7.6.3 爬取开启状态下生成的提示词（技能段与本版内置一致）
    static final String V1763_CRAWLER_ON =
        "# 角色设定\n\n你是一个友好的游戏群助手，请简洁回答。\n\n"
        + "# 技能（你拥有的技能，系统自动注入，不用手动维护）\n\n"
        + "- 发表情包：气氛合适时在回复末尾加 [表情] 文件名；\n"
        + "- 读网页与搜索：遇到不会、没把握或时效性的问题（新闻、新版本、价格等），先搜索再回答，不要凭记忆猜测，也不要直接说不知道；\n"
        + "- 技能是否可用以系统注入的「系统技能」段为准。\n"
        + "\n# 专属知识（服务器规则、玩法说明、常见问题等，写在这里 AI 对话时会参考）\n\n"
        + "（在这里补充内容，可删除本行）\n";

    public static void main(String[] args) throws Exception {
        File work = Files.createTempDirectory("smoke1764").toFile();
        cn.kerong.penguin.spigot.qqbind.AiPromptFile pf =
            cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE;

        System.out.println("== K 组：技能段随插件更新刷新 ==");
        String secOn = pf.skillSection(true);
        String secOff = pf.skillSection(false);
        check("K1 skillSection(true) 四件套（标题/表情包/搜索引导/系统技能为准）",
              secOn.contains("# 技能（你拥有的技能，系统自动注入，不用手动维护）")
                  && secOn.contains("- 发表情包：气氛合适时在回复末尾加 [表情] 文件名；")
                  && secOn.contains("- 读网页与搜索：") && secOn.contains("「系统技能」段为准。"), secOn);
        check("K2 skillSection(false) 无搜索行、其余三件套在",
              !secOff.contains("读网页与搜索") && secOff.contains("发表情包")
                  && secOff.contains("# 技能") && secOff.contains("「系统技能」段为准。"), secOff);
        String defOn = pf.defaultContent(pf.DEFAULT_ROLE, true);
        String defOff = pf.defaultContent(pf.DEFAULT_ROLE, false);
        check("K3 defaultContent 与 skillSection 同源（开/关都完整包含对应技能段）",
              defOn.contains(secOn) && defOff.contains(secOff)
                  && !defOff.contains(secOn), defOn);

        // K4 1.7.6.2 老文件（无技能段）→ 末尾追加，前两段原样
        String k4 = pf.applySkillSection(LEGACY_1762, true);
        check("K4 applySkillSection 无技能段文件末尾追加（角色/专属知识原样）",
              k4.contains("你是一个友好的游戏群助手，请简洁回答。")
                  && k4.contains("（在这里补充内容，可删除本行）")
                  && k4.contains(pf.skillSection(true))
                  && k4.indexOf("# 角色设定") < k4.indexOf("# 专属知识")
                  && k4.indexOf("# 专属知识") < k4.indexOf("# 技能"), k4);

        // K5 旧技能段（手改成两行旧内容）→ 整段替换，段前后不动
        String oldSkillFile =
            "# 角色设定\n\n自定义角色\n\n# 技能（旧标题）\n\n- 旧技能行A\n- 旧技能行B\n\n# 专属知识（…）\n\n自定义知识内容\n";
        String k5 = pf.applySkillSection(oldSkillFile, true);
        check("K5 旧技能段整段替换（角色/专属知识原样，旧技能行清除）",
              k5.contains("自定义角色") && k5.contains("自定义知识内容")
                  && !k5.contains("旧技能行A") && !k5.contains("旧技能行B")
                  && !k5.contains("旧标题") && k5.contains(pf.skillSection(true)), k5);

        // K6 用户自加的技能行同样被替换（技能段由系统维护口径）
        String customSkillFile =
            "# 角色设定\n\n角色\n\n# 技能（你拥有的技能，系统自动注入，不用手动维护）\n\n- 讲笑话\n- 发表情包：气氛合适时在回复末尾加 [表情] 文件名；\n\n# 专属知识（…）\n\n知识\n";
        String k6 = pf.applySkillSection(customSkillFile, false);
        check("K6 自加技能行被替换（整段按当前开关重建）",
              !k6.contains("讲笑话") && k6.contains(pf.skillSection(false)), k6);

        // K7 幂等
        String once = pf.applySkillSection(oldSkillFile, true);
        String twice = pf.applySkillSection(once, true);
        check("K7 applySkillSection 幂等（重复应用零变化）", once.equals(twice));

        // K8 空内容 → 纯技能段
        String k8 = pf.applySkillSection("", true);
        check("K8 空内容 → 纯技能段", k8.equals(pf.skillSection(true)), k8);

        // K9 全新安装：ensureFile 生成默认 → syncSkillSection 只写记录不动文件
        File dir9 = new File(work, "k9/plugins/KERONGPenguin");
        File file9 = new File(dir9, "提示词.md");
        pf.ensureFile(file9, true);
        String before9 = read(file9);
        pf.syncSkillSection(file9, true, "1.7.6.4");
        check("K9 全新安装零打扰（文件一字不变，仅写版本记录）",
              read(file9).equals(before9)
                  && new File(dir9, "globalconfig/.prompt-skill-version").isFile()
                  && read(new File(dir9, "globalconfig/.prompt-skill-version")).trim().equals("1.7.6.4"));

        // K10 插件更新：1.7.6.2 老文件升级 → 追加技能段 + 记录写入
        File dir10 = new File(work, "k10/plugins/KERONGPenguin");
        File file10 = write(new File(dir10, "提示词.md"), LEGACY_1762);
        pf.syncSkillSection(file10, true, "1.7.6.4");
        String after10 = read(file10);
        check("K10 插件更新老文件补技能段（1.7.6.2 形态 → 追加 + 记录版本）",
              after10.contains(pf.skillSection(true)) && after10.contains("（在这里补充内容，可删除本行）")
                  && read(new File(dir10, "globalconfig/.prompt-skill-version")).trim().equals("1.7.6.4"), after10);

        // K11 同版本重启：手改技能行不被恢复（版本相同直接跳过）
        File dir11 = new File(work, "k11/plugins/KERONGPenguin");
        File file11 = write(new File(dir11, "提示词.md"), V1763_CRAWLER_ON);
        pf.syncSkillSection(file11, true, "1.7.6.4"); // 首次：写记录（内容一致零写入）
        String handEdited = read(file11).replace("- 发表情包", "- 我自己改过的技能行；\n- 发表情包");
        write(file11, handEdited);
        pf.syncSkillSection(file11, true, "1.7.6.4"); // 同版本：跳过
        check("K11 同版本重启不触发（手改技能行保留原样）",
              read(file11).equals(handEdited) && read(file11).contains("我自己改过的技能行"));

        // K12 记录位置在 globalconfig/ 下（根目录无散文件）
        check("K12 版本记录位于 globalconfig/ 子目录（根目录不散落）",
              new File(dir10, "globalconfig/.prompt-skill-version").isFile()
                  && !new File(dir10, ".prompt-skill-version").exists());

        // K13 插件更新 + 开关关：技能段刷新为无搜索行版本（搜索行随开关清除）
        File dir13 = new File(work, "k13/plugins/KERONGPenguin");
        File file13 = write(new File(dir13, "提示词.md"), V1763_CRAWLER_ON);
        pf.syncSkillSection(file13, false, "1.7.6.4");
        String after13 = read(file13);
        check("K13 更新+开关关：技能段按当前开关重建（搜索行清除，角色/知识保留）",
              !after13.contains("读网页与搜索") && after13.contains("发表情包")
                  && after13.contains("「系统技能」段为准。")
                  && after13.contains("你是一个友好的游戏群助手"), after13);

        // K14 版本空白（取不到插件版本）：直接跳过，不产生记录
        File dir14 = new File(work, "k14/plugins/KERONGPenguin");
        File file14 = write(new File(dir14, "提示词.md"), LEGACY_1762);
        pf.syncSkillSection(file14, true, "");
        pf.syncSkillSection(file14, true, "  ");
        check("K14 版本空白跳过（不产生记录文件，文件不动）",
              !new File(dir14, "globalconfig/.prompt-skill-version").exists()
                  && read(file14).equals(LEGACY_1762));

        // K15 1.7.6.3 文件升级到 1.7.6.4：技能段内容一致 → 零写入（只补记录）
        File dir15 = new File(work, "k15/plugins/KERONGPenguin");
        File file15 = write(new File(dir15, "提示词.md"), V1763_CRAWLER_ON);
        pf.syncSkillSection(file15, true, "1.7.6.4");
        check("K15 1.7.6.3 升级零打扰（内容一致不写文件，仅写记录）",
              read(file15).equals(V1763_CRAWLER_ON)
                  && read(new File(dir15, "globalconfig/.prompt-skill-version")).trim().equals("1.7.6.4"));

        // K16 技能段在文件末尾（applyCrawlerGuidance 追加形态）→ 定位替换正常
        String tailSkillFile =
            "# 角色设定\n\n角色\n\n# 专属知识（…）\n\n知识\n\n# 技能（你拥有的技能，系统自动注入，不用手动维护）\n\n- 旧技能行\n";
        String k16 = pf.applySkillSection(tailSkillFile, true);
        check("K16 技能段在文件末尾（追加形态）替换正常",
              k16.contains("知识") && !k16.contains("旧技能行")
                  && k16.replaceAll("\\s+$", "").endsWith("「系统技能」段为准。"), k16);

        System.out.println("== G 组：爬取开关补行回归 ==");
        String g1 = pf.applyCrawlerGuidance(V1763_CRAWLER_ON);
        check("G1 已有引导原样返回", g1.equals(V1763_CRAWLER_ON));
        String g2 = pf.applyCrawlerGuidance(LEGACY_1762);
        check("G2 无技能段文件末尾追加完整段（与 K4 同口径）",
              g2.contains("读网页与搜索") && g2.indexOf("# 专属知识") < g2.indexOf("# 技能"));
        File dirG3 = new File(work, "g3/plugins/KERONGPenguin");
        File fileG3 = write(new File(dirG3, "提示词.md"), LEGACY_1762);
        pf.syncCrawlerGuidance(fileG3, false);
        check("G3 开关关闭不动文件", read(fileG3).equals(LEGACY_1762));

        System.out.println("== D 组：死亡播报出口（{message} 自动带玩家名） ==");
        cn.kerong.bot.provider.ConfigProvider cp =
            new cn.kerong.bot.provider.ConfigProvider() {
                public cn.kerong.bot.provider.ChatFormat getChatFormat() { return null; }
                public cn.kerong.bot.provider.Motd getMotd() { return null; }
                public String getBotName() { return "测试服"; }
                public String getPlatform() { return "test"; }
                public String getPluginVersion() { return "1.7.6.4"; }
            };
        String d1 = cp.formatPlayerDeathMessage("Steve", "从高处摔落而死", null);
        check("D1 描述不含名 → 自动拼玩家名", d1.equals("[游戏] Steve 从高处摔落而死"), d1);
        String d2 = cp.formatPlayerDeathMessage("Steve", "Steve 在决斗中被 Alex 击败", "Alex");
        check("D2 插件文案已含名 → 原样不重复拼",
              d2.equals("[游戏] Steve 在决斗中被 Alex 击败"), d2);
        String d3 = cp.formatPlayerDeathMessage("Steve", null, null);
        check("D3 deathMessage=null → 名字+死了", d3.equals("[游戏] Steve 死了"), d3);
        String d4 = cp.formatPlayerDeathMessage("Steve", "被苦力怕杀死了", null);
        check("D4 环境死亡 killer=未知", d4.equals("[游戏] Steve 被苦力怕杀死了"), d4);
        // D5 自定义 format（{name} 与 {message} 并用）——覆写 playerEventFormat
        cn.kerong.bot.provider.PlayerEventFormat custom =
            new cn.kerong.bot.provider.PlayerEventFormat(true, "j", true, "q", true, "{name} {message}（击杀者：{killer}）");
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
        String d5 = cp2.formatPlayerDeathMessage("Alex", "被僵尸杀死了", "Zombie");
        check("D5 自定义文案 {name}+{message}+{killer} 全占位符",
              d5.equals("Alex Alex 被僵尸杀死了（击杀者：Zombie）"), d5);

        System.out.println();
        System.out.println("结果: " + pass + " 通过, " + fail + " 失败");
        if (!fails.isEmpty()) {
            System.out.println("失败项:");
            for (String f : fails) System.out.println("  - " + f);
            System.exit(1);
        }
    }
}
