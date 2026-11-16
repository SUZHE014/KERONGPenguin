import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.7.6.2 覆盖轮冒烟（独立 JVM，真实类，无 MC 依赖）：
 * A 组 WebCrawler 失败反馈——READ_FAILURE_MESSAGE 常量无数字码、
 *   AiChat.buildPageFeedback 失败引导与成功直传；
 * K 组 AiPromptFile——默认内容结构、loadPrompt 空文件回退默认、
 *   ensureFile 生成、迁移不覆盖已有文件；
 * M 组 AiMemeLibrary.ensureFolder 默认生成目录与说明文件；
 * R 组 AiChat 标记协议回归（[表情]/[GET]/思考剥离）。
 *
 * 编译：见 run_smoke.sh（javac -cp 四端可分发 JAR）。
 */
public class Test1762o {
    static int pass = 0, fail = 0;
    static final List<String> fails = new ArrayList<>();

    static void check(String name, boolean ok) { check(name, ok, null); }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS " + name); }
        else { fail++; fails.add(name + (detail == null ? "" : " -> " + detail));
               System.out.println("  FAIL " + name + (detail == null ? "" : " -> " + detail)); }
    }

    public static void main(String[] args) throws Exception {
        File work = Files.createTempDirectory("smoke1762o").toFile();
        String THINK_OPEN = "<" + "think" + ">";
        String THINK_CLOSE = "</" + "think" + ">";

        System.out.println("== A 组：爬取失败输出访问代码修复 ==");
        String failMsg = cn.kerong.penguin.spigot.qqbind.WebCrawler.READ_FAILURE_MESSAGE;
        check("A1 统一失败文案在位", failMsg.contains("网页暂时无法访问或没有返回内容"), failMsg);
        check("A2 失败文案无数字状态码", !failMsg.matches(".*[0-9]{3}.*"), failMsg);
        check("A3 失败文案无英文技术词", !failMsg.toLowerCase().matches(".*(http|exception|timeout|error).*"), failMsg);

        String guided = cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.buildPageFeedback(
            "https://example.com/x", failMsg);
        check("A4 失败反馈带自然语言引导", guided.contains("不要输出错误代码或技术原因"), guided);
        check("A5 失败反馈含网址上下文", guided.contains("https://example.com/x") && guided.contains("[网页内容]"));
        String okPage = cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.buildPageFeedback(
            "https://example.com/y", "这里是正文内容");
        check("A6 成功反馈直传正文无引导句", okPage.contains("这里是正文内容")
            && !okPage.contains("不要输出错误代码"), okPage);

        System.out.println("== K 组：AiPromptFile 提示词单文件 ==");
        String def = cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.defaultContent(
            cn.kerong.penguin.spigot.qqbind.AiPromptFile.DEFAULT_ROLE);
        check("K1 默认内容含角色设定段", def.contains("# 角色设定"), def);
        check("K2 默认内容含专属知识段", def.contains("# 专属知识"), def);
        check("K3 默认角色文案", def.contains("你是一个友好的游戏群助手，请简洁回答。"));

        File prompt = new File(work, "提示词.md");
        File ensured = cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.ensureFile(prompt);
        check("K4 ensureFile 生成文件", ensured != null && prompt.isFile());
        check("K5 生成内容为默认结构", readFile(prompt).contains("# 角色设定"));
        // 已有文件不覆盖
        Files.write(prompt.toPath(), "自定义内容".getBytes(StandardCharsets.UTF_8));
        cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.ensureFile(prompt);
        check("K6 已有文件不覆盖", readFile(prompt).equals("自定义内容"));
        check("K7 loadPrompt 读自定义内容",
            cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.loadPrompt(prompt).equals("自定义内容"));
        // 空文件回退默认
        Files.write(prompt.toPath(), "   \n".getBytes(StandardCharsets.UTF_8));
        check("K8 空白文件回退默认角色",
            cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.loadPrompt(prompt)
                .equals(cn.kerong.penguin.spigot.qqbind.AiPromptFile.DEFAULT_ROLE));
        // 缺失文件回退默认
        check("K9 缺失文件回退默认角色",
            cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.loadPrompt(new File(work, "不存在.md"))
                .equals(cn.kerong.penguin.spigot.qqbind.AiPromptFile.DEFAULT_ROLE));
        check("K10 loadPrompt(null) 回退默认",
            cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.loadPrompt(null)
                .equals(cn.kerong.penguin.spigot.qqbind.AiPromptFile.DEFAULT_ROLE));
        // 迁移：写入旧角色，不覆盖已有
        File mig = new File(work, "迁移.md");
        boolean moved = cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.migrateFromSystemPrompt(mig, "你是猫娘助手");
        check("K11 迁移生成文件", moved && mig.isFile());
        check("K12 迁移保留旧角色设定", readFile(mig).contains("你是猫娘助手"));
        Files.write(mig.toPath(), "用户已编辑".getBytes(StandardCharsets.UTF_8));
        check("K13 迁移不覆盖已有文件",
            !cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.migrateFromSystemPrompt(mig, "你是猫娘助手")
            && readFile(mig).equals("用户已编辑"));
        check("K14 迁移空值不动作",
            !cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.migrateFromSystemPrompt(new File(work, "x.md"), null));
        check("K15 迁移 null 文件不动作",
            !cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.migrateFromSystemPrompt(null, "任意"));

        System.out.println("== M 组：表情包文件夹默认生成 ==");
        File memes = new File(work, "guaipng");
        File mEnsured = cn.kerong.penguin.spigot.qqbind.AiMemeLibrary.INSTANCE.ensureFolder(memes);
        check("M1 ensureFolder 生成目录", mEnsured != null && memes.isDirectory());
        File readme = new File(memes, "说明-把表情包图片放进这里.txt");
        check("M2 附带说明文件", readme.isFile());
        // 已有目录不覆盖内容
        File img = new File(memes, "开心.png");
        Files.write(img.toPath(), new byte[]{1, 2, 3});
        cn.kerong.penguin.spigot.qqbind.AiMemeLibrary.INSTANCE.ensureFolder(memes);
        check("M3 已有目录再 ensure 幂等", img.isFile());
        check("M4 列目录含图片", cn.kerong.penguin.spigot.qqbind.AiMemeLibrary.INSTANCE.listNames(memes).contains("开心"));

        System.out.println("== R 组：AiChat 标记协议回归 ==");
        List<String> urls = cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.parseGetUrls(
            "我看看 [GET] https://a.com/x 和 [GET] https://b.com/y");
        check("R1 [GET] 解析两条", urls.size() == 2, urls.toString());
        check("R2 [GET] 去重保序", cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.parseGetUrls(
            "[GET] https://a.com/x [GET] https://a.com/x").size() == 1);
        check("R3 残留标记剥离", !cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.stripGetMarkers(
            "前文\n[GET] https://a.com/x\n后文").contains("[GET]"));
        List<String> memes3 = cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.parseMemeKeywords(
            "好耶 [表情] 开心 [表情:鼓掌]", 2);
        check("R4 [表情] 关键词解析", memes3.size() == 2 && memes3.contains("开心") && memes3.contains("鼓掌"), memes3.toString());
        String stripped = cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.stripReplyMarkers("文字\n[表情] 开心");
        check("R5 [表情] 标记剥离", !stripped.contains("[表情]") && stripped.contains("文字"));
        String think = cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.stripThink(
            THINK_OPEN + "推理" + THINK_CLOSE + "正式回答");
        check("R6 思考段剥离", think.equals("正式回答"), think);
        check("R7 未闭合思考取前段", cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.stripThink(
            "开头" + THINK_OPEN + "还在思考").equals("开头"));
        check("R8 超长截断", cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.capReply(
            "x".repeat(3000)).length() < 2100);

        System.out.println();
        System.out.println("RESULT: " + pass + "/" + (pass + fail) + " PASS, " + fail + " FAIL");
        if (!fails.isEmpty()) { fails.forEach(f -> System.out.println("  !! " + f)); System.exit(1); }
    }

    static String readFile(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
