import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.7.6.3 三次覆盖轮冒烟（独立 JVM，真实类，无 MC 依赖）：
 *
 * P 组 提示词技能段端到端——defaultContent 含「# 技能」段（技能列表 +
 *   遇到不会先搜索引导 + 以系统技能段为准），段落顺序 角色设定 → 技能 →
 *   专属知识，ensureFile 生成文件含技能段，已有文件不覆盖回归；
 * S 组 纯表情包空白消息根源与标记解析回归——stripReplyMarkers 对纯表情
 *   标记输入返回空串（正是旧版先发空白消息的根源），文本+标记混合剥离，
 *   parseMemeKeywords 限量去重，stripThink / capReply 回归；
 * G 组 表情包解析链路——resolve 关键词命中文件 / 未命中返回 null
 *   （对应「无图兜底」分支的真实数据源）。
 *
 * 编译运行：javac/java -cp Spigot-1.7.6.3.jar（见 classpath.txt）。
 */
public class Test1763q {
    static int pass = 0, fail = 0;
    static final List<String> fails = new ArrayList<>();

    static void check(String name, boolean ok) { check(name, ok, null); }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS " + name); }
        else { fail++; fails.add(name + (detail == null ? "" : " -> " + detail));
               System.out.println("  FAIL " + name + (detail == null ? "" : " -> " + detail)); }
    }

    static String readFile(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        File work = Files.createTempDirectory("smoke1763q").toFile();
        cn.kerong.penguin.spigot.qqbind.AiChat aichat =
            cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE;
        cn.kerong.penguin.spigot.qqbind.AiMemeLibrary memes =
            cn.kerong.penguin.spigot.qqbind.AiMemeLibrary.INSTANCE;

        System.out.println("== P 组：提示词技能段（defaultContent 端到端） ==");
        String def = cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.defaultContent(
            cn.kerong.penguin.spigot.qqbind.AiPromptFile.DEFAULT_ROLE);
        check("P1 含「# 技能」段标题", def.contains("# 技能（你拥有的技能，系统自动注入，不用手动维护）"), def);
        check("P2 技能段含遇到不会先搜索引导",
              def.contains("遇到不会、没把握或时效性的问题") && def.contains("先搜索再回答"), def);
        check("P3 技能段含不要猜测 / 不要说不知道",
              def.contains("不要凭记忆猜测") && def.contains("不要直接说不知道"), def);
        check("P4 列出发表情包技能", def.contains("发表情包") && def.contains("[表情] 文件名"), def);
        check("P5 列出读网页与搜索技能", def.contains("读网页与搜索"), def);
        check("P6 以系统技能段为准说明", def.contains("「系统技能」段为准"), def);
        int iRole = def.indexOf("# 角色设定");
        int iSkill = def.indexOf("# 技能");
        int iKnow = def.indexOf("# 专属知识");
        check("P7 段落顺序 角色设定→技能→专属知识",
              iRole >= 0 && iSkill > iRole && iKnow > iSkill,
              "role=" + iRole + " skill=" + iSkill + " know=" + iKnow);
        check("P8 默认角色文案回归", def.contains("你是一个友好的游戏群助手，请简洁回答。"), def);
        File prompt = new File(work, "提示词.md");
        cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.ensureFile(prompt);
        String persisted = readFile(prompt);
        check("P9 ensureFile 生成文件含技能段", persisted.contains("# 技能") && persisted.contains("先搜索再回答"), persisted);
        check("P10 生成文件可被 loadPrompt 原样读回",
              cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.loadPrompt(prompt).equals(persisted.trim()));
        Files.write(prompt.toPath(), "自定义内容".getBytes(StandardCharsets.UTF_8));
        cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.ensureFile(prompt);
        check("P11 已有文件不覆盖（回归）", readFile(prompt).equals("自定义内容"));

        System.out.println("== S 组：纯表情包标记剥离（空白消息根源） ==");
        String onlyMeme = aichat.stripReplyMarkers("[表情] 开心");
        check("S1 纯表情标记剥离后为空串（空白消息根源）", onlyMeme != null && onlyMeme.isEmpty(), "[" + onlyMeme + "]");
        String onlyMemeColon = aichat.stripReplyMarkers("[表情:鼓掌]\n");
        check("S2 冒号形态纯表情剥离后为空串", onlyMemeColon != null && onlyMemeColon.isEmpty(), "[" + onlyMemeColon + "]");
        String mixed = aichat.stripReplyMarkers("哈哈，这挺好玩的[表情] 开心");
        check("S3 文本+标记混合只剥标记", mixed.equals("哈哈，这挺好玩的"), "[" + mixed + "]");
        String noMarker = aichat.stripReplyMarkers("  普通回复  ");
        check("S4 无标记仅首尾裁剪", noMarker.equals("普通回复"), "[" + noMarker + "]");
        List<String> kw = aichat.parseMemeKeywords("嗯[表情] 开心 然后又一个 [表情] 开心 [表情] 鼓掌", 1);
        check("S5 关键词限量去重（max=1）", kw.size() == 1 && kw.get(0).equals("开心"), String.valueOf(kw));
        List<String> kw2 = aichat.parseMemeKeywords("[表情:鼓掌] 与 [表情] 开心", 5);
        check("S6 冒号与括号两种形态都可解析", kw2.size() == 2 && kw2.contains("鼓掌") && kw2.contains("开心"), String.valueOf(kw2));
        check("S7 无标记返回空列表", aichat.parseMemeKeywords("普通文本", 3).isEmpty());
        String think = aichat.stripThink("<" + "think" + ">推理过程</" + "think" + ">正式回答");
        check("S8 思考段剥离回归", think.equals("正式回答"), "[" + think + "]");
        String capped = aichat.capReply("x".repeat(2500));
        check("S9 超长截断回归", capped.length() < 2100 && capped.contains("已截断"), "len=" + capped.length());

        System.out.println("== G 组：表情包解析链路（resolve 真实文件） ==");
        File dir = new File(work, "guaipng");
        cn.kerong.penguin.spigot.qqbind.AiMemeLibrary.INSTANCE.ensureFolder(dir);
        Files.write(new File(dir, "开心.png").toPath(), new byte[]{1, 2, 3});
        File hit = cn.kerong.penguin.spigot.qqbind.AiMemeLibrary.INSTANCE.resolve(dir, "开心");
        check("G1 关键词命中文件", hit != null && hit.isFile() && hit.getName().equals("开心.png"),
              hit == null ? "null" : hit.getName());
        File miss = cn.kerong.penguin.spigot.qqbind.AiMemeLibrary.INSTANCE.resolve(dir, "不存在的表情");
        check("G2 未命中返回 null（无图兜底分支数据源）", miss == null);

        System.out.println();
        System.out.println("===== 结果: " + pass + "/" + (pass + fail) + " =====");
        if (!fails.isEmpty()) System.out.println("失败项: " + fails);
        System.exit(fail == 0 ? 0 : 1);
    }
}
