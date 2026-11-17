import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.7.6.3 四次覆盖轮冒烟（独立 JVM，真实类）：
 *
 * P 组 提示词技能引导条件生成——defaultContent(role, crawlerEnabled) 双参：
 *   开启时含「先搜索再回答」引导；关闭时不写搜索引导（只留发表情包 +
 *   以系统技能段为准），段落顺序保持，ensureFile / migrateFromSystemPrompt
 *   同步按开关生成，已有文件不覆盖回归；
 * D 组 数据迁移残留修复端到端——DataLayout.migrate（真实文件系统）：
 *   标准升级全挪走零残留 / 同名冲突新位置较新保新删旧 / 同名冲突旧位置
 *   较新（降级数据）内容接管 / 散文件同名取新 / 幂等二次迁移 / resolve 回归；
 * S 组 三次覆盖轮核心回归——纯表情标记剥离为空串（空白消息根源）等。
 *
 * 编译运行：javac/java -cp NF 分发 JAR（DataLayout 签名引用 JavaPlugin，
 * 需自带 bukkit 兼容层的 NeoForge 分发包；见 classpath.txt）。
 */
public class Test1763r {
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

    public static void main(String[] args) throws Exception {
        File work = Files.createTempDirectory("smoke1763r").toFile();
        cn.kerong.penguin.spigot.qqbind.AiPromptFile pf =
            cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE;

        System.out.println("== P 组：提示词技能引导条件生成（defaultContent 双参） ==");
        String defOn = pf.defaultContent(
            cn.kerong.penguin.spigot.qqbind.AiPromptFile.DEFAULT_ROLE, true);
        String defOff = pf.defaultContent(
            cn.kerong.penguin.spigot.qqbind.AiPromptFile.DEFAULT_ROLE, false);
        check("P1 开启时含搜索引导（遇到不会先搜索）",
              defOn.contains("遇到不会、没把握或时效性的问题")
                  && defOn.contains("先搜索再回答，不要凭记忆猜测，也不要直接说不知道"), defOn);
        check("P2 关闭时不含搜索引导",
              !defOff.contains("先搜索再回答") && !defOff.contains("读网页与搜索"), defOff);
        check("P3 关闭时保留发表情包技能与系统技能段为准",
              defOff.contains("发表情包") && defOff.contains("「系统技能」段为准"), defOff);
        check("P4 关闭时段落结构完整（角色设定 / 技能 / 专属知识）",
              defOff.contains("# 角色设定") && defOff.contains("# 技能")
                  && defOff.contains("# 专属知识"), defOff);
        int iRole = defOff.indexOf("# 角色设定"), iSkill = defOff.indexOf("# 技能"),
            iKnow = defOff.indexOf("# 专属知识");
        check("P5 段落顺序 角色设定→技能→专属知识",
              iRole >= 0 && iSkill > iRole && iKnow > iSkill,
              "role=" + iRole + " skill=" + iSkill + " know=" + iKnow);

        // ensureFile 按开关生成
        File fOn = new File(work, "on/提示词.md");
        File retOn = pf.ensureFile(fOn, true);
        check("P6 ensureFile(true) 生成文件含搜索引导",
              retOn != null && fOn.isFile() && read(fOn).contains("先搜索再回答"), fOn.getPath());
        File fOff = new File(work, "off/提示词.md");
        File retOff = pf.ensureFile(fOff, false);
        check("P7 ensureFile(false) 生成文件不含搜索引导",
              retOff != null && fOff.isFile() && !read(fOff).contains("先搜索再回答"),
              fOff.getPath());
        // 已有文件不覆盖回归
        File fKeep = new File(work, "keep/提示词.md");
        write(fKeep, "我的自定义提示词");
        pf.ensureFile(fKeep, true);
        check("P8 已有文件不覆盖", "我的自定义提示词".equals(read(fKeep)), read(fKeep));
        // migrateFromSystemPrompt 按开关
        File fMig = new File(work, "mig/提示词.md");
        boolean migrated = pf.migrateFromSystemPrompt(fMig, "自定义角色", false);
        check("P9 migrateFromSystemPrompt(false) 迁移成功且无搜索引导",
              migrated && fMig.isFile() && read(fMig).contains("自定义角色")
                  && !read(fMig).contains("先搜索再回答"), fMig.getPath());
        File fMig2 = new File(work, "mig2/提示词.md");
        boolean migrated2 = pf.migrateFromSystemPrompt(fMig2, "自定义角色2", true);
        check("P10 migrateFromSystemPrompt(true) 迁移含搜索引导",
              migrated2 && read(fMig2).contains("先搜索再回答"), fMig2.getPath());

        System.out.println("== D 组：数据迁移残留修复（DataLayout.migrate 端到端） ==");
        cn.kerong.penguin.spigot.manager.DataLayout dl =
            cn.kerong.penguin.spigot.manager.DataLayout.INSTANCE;

        // D1 标准升级：全套旧数据 → 全挪走零残留
        File r1 = new File(work, "case1");
        write(new File(r1, "QUUID/a.yml"), "a");
        write(new File(r1, "logs/qq/x.log"), "l");
        write(new File(r1, "Markdown/m.md"), "m");
        write(new File(r1, "cache/c.png"), "c");
        write(new File(r1, "ai-context.yml"), "ctx");
        write(new File(r1, "fake-players.json"), "[]");
        boolean mv1 = dl.migrate(r1);
        check("D1 标准升级全挪走",
              mv1 && new File(r1, "globalconfig/QUUID/a.yml").isFile()
                  && new File(r1, "globalconfig/logs/qq/x.log").isFile()
                  && new File(r1, "globalconfig/Markdown/m.md").isFile()
                  && new File(r1, "globalconfig/cache/c.png").isFile()
                  && new File(r1, "globalconfig/ai-context.yml").isFile()
                  && new File(r1, "globalconfig/fake-players.json").isFile()
                  && !new File(r1, "QUUID").exists() && !new File(r1, "logs").exists()
                  && !new File(r1, "Markdown").exists() && !new File(r1, "cache").exists(),
              "r1=" + r1);

        // D2 同名冲突：新位置较新 → 保留新位置内容，旧位置删除
        File r2 = new File(work, "case2");
        write(new File(r2, "QUUID/a.yml"), "old-a");
        File b2 = write(new File(r2, "QUUID/b.yml"), "old-b");
        File nb2 = write(new File(r2, "globalconfig/QUUID/b.yml"), "new-b");
        b2.setLastModified(nb2.lastModified() - 60_000L);
        dl.migrate(r2);
        check("D2 同名冲突(新位置较新)保新删旧零残留",
              "new-b".equals(read(new File(r2, "globalconfig/QUUID/b.yml")))
                  && new File(r2, "globalconfig/QUUID/a.yml").isFile()
                  && !new File(r2, "QUUID").exists(),
              "r2=" + r2);

        // D3 同名冲突：旧位置较新（降级数据）→ 旧内容接管新位置，旧位置删除
        File r3 = new File(work, "case3");
        File b3 = write(new File(r3, "QUUID/b.yml"), "downgrade-new-data");
        File nb3 = write(new File(r3, "globalconfig/QUUID/b.yml"), "older");
        b3.setLastModified(nb3.lastModified() + 60_000L);
        dl.migrate(r3);
        check("D3 同名冲突(旧位置较新=降级数据)内容接管零残留",
              "downgrade-new-data".equals(read(new File(r3, "globalconfig/QUUID/b.yml")))
                  && !new File(r3, "QUUID").exists(),
              "r3=" + r3);

        // D4 散文件同名：旧位置较新 → 内容接管
        File r4 = new File(work, "case4");
        File fj = write(new File(r4, "fake-players.json"), "downgrade-json");
        File nj = write(new File(r4, "globalconfig/fake-players.json"), "old-json");
        fj.setLastModified(nj.lastModified() + 60_000L);
        dl.migrate(r4);
        check("D4 散文件同名(旧位置较新)内容接管",
              "downgrade-json".equals(read(new File(r4, "globalconfig/fake-players.json")))
                  && !new File(r4, "fake-players.json").exists(),
              "r4=" + r4);

        // D5 幂等：二次迁移无异常无残留
        boolean mv5a = dl.migrate(r1);
        dl.migrate(r1); dl.migrate(r2); dl.migrate(r3); dl.migrate(r4);
        check("D5 幂等二次迁移零残留",
              new File(r1, "globalconfig/QUUID/a.yml").isFile()
                  && !new File(r1, "QUUID").exists() && !new File(r2, "QUUID").exists()
                  && !new File(r3, "QUUID").exists() && !new File(r4, "fake-players.json").exists(),
              "mv5a=" + mv5a);

        // D6 resolve 回归
        File res = dl.resolve(new File(work, "res"), "QUUID/x.yml");
        check("D6 resolve 回归（自动建父目录）",
              res != null && res.getParentFile().isDirectory(), String.valueOf(res));

        System.out.println("== S 组：三次覆盖轮核心回归 ==");
        cn.kerong.penguin.spigot.qqbind.AiChat aichat =
            cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE;
        String stripped = aichat.stripReplyMarkers("[表情] 开心");
        check("S1 纯表情标记剥离为空串（空白消息根源）",
              stripped != null && stripped.trim().isEmpty(), "'" + stripped + "'");
        check("S2 文本+标记混合剥离",
              "你好呀".equals(aichat.stripReplyMarkers("你好呀 [表情] 开心").trim()),
              aichat.stripReplyMarkers("你好呀 [表情] 开心"));
        check("S3 capReply 回归", aichat.capReply("ab") != null);

        System.out.println();
        System.out.println("===== " + pass + "/" + (pass + fail) + " =====");
        if (!fails.isEmpty()) System.out.println("失败项: " + fails);
        System.exit(fail == 0 ? 0 : 1);
    }
}
