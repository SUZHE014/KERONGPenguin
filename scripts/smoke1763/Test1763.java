import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.7.6.3 冒烟（独立 JVM，真实类，无 MC 依赖）：
 * W 组 WebCrawler——normalizeUrl 百分号编码（中文/空格/%XX 保留/ASCII 直通）、
 *   extractText 三参（baseUrl 链接保留「文字（网址）」/ 相对链接补全 /
 *   script 剥离 / 截断兜底）；
 * C 组 AiPromptFile.cleanupLegacyKnowledgeFolder——不存在 ABSENT /
 *   只剩旧说明文件自动删 REMOVED / 有用户文件保留 KEPT；
 * R 组 回归——失败文案、buildPageFeedback、[GET] 多 URL 解析、
 *   提示词文件、表情包常量、标记协议。
 *
 * 编译运行：javac/java -cp Spigot-1.7.6.3.jar（见 classpath.txt）。
 */
public class Test1763 {
    static int pass = 0, fail = 0;
    static final List<String> fails = new ArrayList<>();

    static void check(String name, boolean ok) { check(name, ok, null); }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS " + name); }
        else { fail++; fails.add(name + (detail == null ? "" : " -> " + detail));
               System.out.println("  FAIL " + name + (detail == null ? "" : " -> " + detail)); }
    }

    public static void main(String[] args) throws Exception {
        File work = Files.createTempDirectory("smoke1763").toFile();
        cn.kerong.penguin.spigot.qqbind.WebCrawler wc =
            cn.kerong.penguin.spigot.qqbind.WebCrawler.INSTANCE;

        System.out.println("== W 组：normalizeUrl 与 extractText 多层爬取 ==");
        String n1 = wc.normalizeUrl("https://www.bing.com/search?q=我的世界 服务器");
        check("W1 中文+空格编码（%E6 开头 + %20）",
            n1.equals("https://www.bing.com/search?q=%E6%88%91%E7%9A%84%E4%B8%96%E7%95%8C%20%E6%9C%8D%E5%8A%A1%E5%99%A8"), n1);
        check("W2 编码后无空格无中文字符",
            !n1.matches(".*[\\u4e00-\\u9fff ].*"), n1);
        String n2 = wc.normalizeUrl("https://example.com/a%20b?x=%E6%88%91");
        check("W3 已编码 %XX 原样保留", n2.equals("https://example.com/a%20b?x=%E6%88%91"), n2);
        String n3 = wc.normalizeUrl("https://example.com/path?q=minecraft");
        check("W4 纯 ASCII 直通不变", n3.equals("https://example.com/path?q=minecraft"), n3);

        String html = "<html><head><script>var x=1;</script><style>.a{}</style></head><body>"
            + "<nav>导航噪音</nav>"
            + "<h1>标题文字</h1>"
            + "<p>请看 <a href=\"https://wiki.example.com/xxx\">详细教程</a> 或 <a href=\"/relative/page\">相对链接</a></p>"
            + "<footer>页脚噪音</footer></body></html>";
        String text = wc.extractText(html, 6000, "https://www.example.com/article");
        check("W5 链接保留为「文字（网址）」",
            text.contains("详细教程（https://wiki.example.com/xxx）"), text);
        check("W6 相对链接按 baseUrl 补全",
            text.contains("相对链接（https://www.example.com/relative/page）"), text);
        check("W7 script 内容剥离", !text.contains("var x=1"), text);
        check("W8 nav/footer 噪音剥离", !text.contains("导航噪音") && !text.contains("页脚噪音"), text);
        check("W9 正文文字保留", text.contains("标题文字"), text);
        String shortText = wc.extractText(html, 20, "https://www.example.com/article");
        check("W10 截断兜底（超长加省略说明）",
            shortText.length() < 40 && shortText.contains("……"), shortText);
        String noBase = wc.extractText(html, 6000, "");
        check("W11 无 baseUrl 不注入链接标注",
            !noBase.contains("（https://wiki.example.com/xxx）") && noBase.contains("详细教程"), noBase);

        System.out.println("== C 组：旧知识库文件夹清理 ==");
        File absent = new File(work, "no-such-knowledge");
        check("C1 文件夹不存在 → ABSENT",
            cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.cleanupLegacyKnowledgeFolder(absent)
                == cn.kerong.penguin.spigot.qqbind.AiPromptFile.CleanupResult.ABSENT);

        File legacy = new File(work, "knowledge");
        legacy.mkdirs();
        Files.write(new File(legacy, "说明-把知识文件放进这里.txt").toPath(),
            "旧说明".getBytes(StandardCharsets.UTF_8));
        check("C2 只剩旧说明文件 → REMOVED",
            cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.cleanupLegacyKnowledgeFolder(legacy)
                == cn.kerong.penguin.spigot.qqbind.AiPromptFile.CleanupResult.REMOVED);
        check("C3 REMOVED 后目录已删除", !legacy.exists());

        File legacy2 = new File(work, "knowledge2");
        legacy2.mkdirs();
        Files.write(new File(legacy2, "说明-把知识文件放进这里.txt").toPath(),
            "旧说明".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(legacy2, "服务器规则.md").toPath(),
            "用户自己的知识".getBytes(StandardCharsets.UTF_8));
        check("C4 有用户文件 → KEPT",
            cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.cleanupLegacyKnowledgeFolder(legacy2)
                == cn.kerong.penguin.spigot.qqbind.AiPromptFile.CleanupResult.KEPT);
        check("C5 KEPT 后用户文件原样保留",
            new File(legacy2, "服务器规则.md").isFile()
                && new String(Files.readAllBytes(new File(legacy2, "服务器规则.md").toPath()),
                    StandardCharsets.UTF_8).equals("用户自己的知识"));

        File empty = new File(work, "knowledge3");
        empty.mkdirs();
        check("C6 空文件夹 → REMOVED",
            cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.cleanupLegacyKnowledgeFolder(empty)
                == cn.kerong.penguin.spigot.qqbind.AiPromptFile.CleanupResult.REMOVED && !empty.exists());

        System.out.println("== R 组：回归 ==");
        String failMsg = cn.kerong.penguin.spigot.qqbind.WebCrawler.READ_FAILURE_MESSAGE;
        check("R1 统一失败文案在位", failMsg.contains("网页暂时无法访问或没有返回内容"), failMsg);
        String guided = cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.buildPageFeedback(
            "https://example.com/x", failMsg);
        check("R2 失败反馈自然语言引导", guided.contains("不要输出错误代码或技术原因"), guided);

        List<String> urls = cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.parseGetUrls(
            "[GET] https://a.com/1\n[GET] https://b.com/2\n[GET] https://a.com/1 重复");
        check("R3 [GET] 多 URL 去重保序", urls.size() == 2
            && urls.get(0).equals("https://a.com/1") && urls.get(1).equals("https://b.com/2"), urls.toString());
        String bing = cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.parseGetUrls(
            "需要搜索 [GET] https://www.bing.com/search?q=我的世界").get(0);
        check("R4 必应搜索 URL（含中文关键词）可解析",
            bing.equals("https://www.bing.com/search?q=我的世界"), bing);

        List<String> memes = cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.parseMemeKeywords(
            "回答内容 [表情] 开心 结尾 [表情:鼓掌]", 1);
        check("R5 [表情] 标记限量解析", memes.size() == 1 && memes.get(0).equals("开心"), memes.toString());
        check("R6 表情包上限常量 MEME_MAX_PER_REPLY=1",
            cn.kerong.penguin.spigot.qqbind.AiChat.MEME_MAX_PER_REPLY == 1);
        check("R7 表情包文件夹常量 FOLDER_NAME=guaipng",
            cn.kerong.penguin.spigot.qqbind.AiMemeLibrary.FOLDER_NAME.equals("guaipng"));
        check("R8 固定超时常量 10",
            cn.kerong.penguin.spigot.qqbind.WebCrawler.DEFAULT_TIMEOUT_SECONDS == 10);
        check("R9 固定正文上限 6000",
            cn.kerong.penguin.spigot.qqbind.WebCrawler.DEFAULT_MAX_CHARS == 6000);

        File prompt = new File(work, "提示词.md");
        cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.ensureFile(prompt);
        String content = new String(Files.readAllBytes(prompt.toPath()), StandardCharsets.UTF_8);
        check("R10 提示词默认内容结构", content.contains("# 角色设定") && content.contains("# 专属知识"), content);
        String loaded = cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.loadPrompt(prompt);
        check("R11 loadPrompt 读取文件内容", loaded.contains("# 角色设定"), loaded);

        String THINK_OPEN = "<" + "think" + ">";
        String THINK_CLOSE = "</" + "think" + ">";
        String stripped = cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.stripThink(
            THINK_OPEN + "思考" + THINK_CLOSE + "正式回答");
        check("R12 思考段剥离回归", stripped.equals("正式回答"), stripped);

        System.out.println();
        System.out.println("RESULT: " + pass + "/" + (pass + fail) + " PASS, " + fail + " FAIL");
        if (!fails.isEmpty()) { System.out.println("失败清单: " + fails); System.exit(1); }
    }
}
