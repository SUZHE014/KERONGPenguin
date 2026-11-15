import cn.kerong.penguin.spigot.qqbind.AiChat;
import cn.kerong.penguin.spigot.qqbind.AiKnowledgeLibrary;
import cn.kerong.penguin.spigot.qqbind.AiMemeLibrary;
import cn.kerong.penguin.spigot.qqbind.WebCrawler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.7.6.2 冒烟（独立 JVM，纯函数，不依赖插件实例）。
 * 覆盖：表情包匹配 / 知识库分段检索 / 爬虫 SSRF 与文本提取 /
 * AiChat 标记协议（[表情] / [GET]）与思考剥离。
 * 编译运行 classpath：KERONGPenguin_Spigot-1.7.6.2.jar + paper-api。
 */
public class Test1762 {

    static int pass = 0, fail = 0;
    static final String THINK_OPEN = "<" + "think" + ">";
    static final String THINK_CLOSE = "</" + "think" + ">";

    static void check(String name, boolean ok) { check(name, ok, null); }
    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + (detail == null ? "" : "  -> " + detail)); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== M 组：表情包（纯匹配） ==");
        List<String> names = List.of("开心", "鼓掌", "笑哭", "问号", "OK手势");
        check("m1 精确匹配", "开心".equals(AiMemeLibrary.INSTANCE.findBest("开心", names)));
        check("m2 大小写分隔归一", "OK手势".equals(AiMemeLibrary.INSTANCE.findBest("o k 手 势", names)));
        check("m3 包含匹配", "笑哭".equals(AiMemeLibrary.INSTANCE.findBest("笑哭表情", names)));
        check("m4 反向包含", "问号".equals(AiMemeLibrary.INSTANCE.findBest("问", names)) || AiMemeLibrary.INSTANCE.findBest("问号在哪里", names) != null);
        check("m5 字符重合度匹配", AiMemeLibrary.INSTANCE.findBest("鼓掌庆祝", names) != null);
        check("m6 无匹配返回 null", AiMemeLibrary.INSTANCE.findBest("完全无关的内容", names) == null);
        check("m7 空查询 null", AiMemeLibrary.INSTANCE.findBest("  ", names) == null);
        check("m8 扩展名含 gif（动图支持）", AiMemeLibrary.INSTANCE.getIMAGE_EXTENSIONS().contains("gif"));
        check("m9 stripExtension", "笑哭".equals(AiMemeLibrary.INSTANCE.stripExtension("笑哭.gif")));

        System.out.println("== M2 组：表情包（目录端到端，含 GIF 文件名） ==");
        File memeDir = Files.createTempDirectory("meme1762").toFile();
        Files.write(new File(memeDir, "开心.png").toPath(), new byte[]{1, 2, 3});
        Files.write(new File(memeDir, "笑哭.gif").toPath(), new byte[]{4, 5});
        Files.write(new File(memeDir, "ignore.txt").toPath(), new byte[]{9});
        File hit = AiMemeLibrary.INSTANCE.resolve(memeDir, "笑哭");
        check("m10 GIF 文件名解析命中动图文件", hit != null && hit.getName().equals("笑哭.gif"));
        File hit2 = AiMemeLibrary.INSTANCE.resolve(memeDir, "开心.png");
        check("m11 全名解析", hit2 != null && hit2.getName().equals("开心.png"));
        check("m12 非图片不索引", AiMemeLibrary.INSTANCE.listFiles(memeDir).size() == 2);
        check("m13 提示词段包含文件名", AiMemeLibrary.INSTANCE.renderPromptSection(memeDir).contains("开心"));
        check("m14 提示词段提及动图", AiMemeLibrary.INSTANCE.renderPromptSection(memeDir).contains("含动图"));

        System.out.println("== K 组：知识库 ==");
        File knowDir = Files.createTempDirectory("know1762").toFile();
        Files.write(new File(knowDir, "服务器规则.md").toPath(), (
            "入服需要先在 QQ 群绑定游戏账号。\n\n" +
            "每周五晚 8 点举行建筑比赛，冠军奖励 500 金币。\n\n" +
            "商店在主城东侧，出售木材和石头。"
        ).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(knowDir, "常见问题.txt").toPath(), (
            "问：怎么绑定？\n答：群里发送 /绑定 按提示操作。\n\n" +
            "问：建筑比赛什么时候？\n答：每周五晚 8 点。"
        ).getBytes(StandardCharsets.UTF_8));
        List<AiKnowledgeLibrary.Segment> segments = AiKnowledgeLibrary.INSTANCE.indexSegments(knowDir);
        check("k1 分段计数（3 + 2）", segments.size() == 5, "size=" + segments.size());
        boolean hasRule = false, hasFaq = false;
        for (AiKnowledgeLibrary.Segment s : segments) {
            if (s.getSource().equals("服务器规则")) hasRule = true;
            if (s.getSource().equals("常见问题")) hasFaq = true;
        }
        check("k2 两个来源文件都在位", hasRule && hasFaq);
        List<String> terms = AiKnowledgeLibrary.INSTANCE.tokenize("建筑比赛什么时候开始");
        check("k3 分词非空", !terms.isEmpty() && terms.contains("建筑"));
        List<AiKnowledgeLibrary.Segment> topSegs = new ArrayList<>();
        for (Object[] ignored : new Object[0][0]) {}
        List<Object[]> scoredRaw = new ArrayList<>();
        // scoreSegments 返回 List<Pair<Segment, Double>>——Java 侧视作 List
        List<?> scored = AiKnowledgeLibrary.INSTANCE.scoreSegments(terms, segments);
        check("k4 打分非空", !scored.isEmpty());
        Object first = scored.get(0);
        java.lang.reflect.Method getText = first.getClass().getMethod("getFirst");
        java.lang.reflect.Method getScore = first.getClass().getMethod("getSecond");
        Object topSeg = getText.invoke(first);
        String topText = (String) topSeg.getClass().getMethod("getText").invoke(topSeg);
        check("k5 Top1 命中建筑比赛段", topText.contains("建筑比赛"), "top1=" + topText);
        String section = AiKnowledgeLibrary.INSTANCE.renderPromptSection(knowDir, "建筑比赛什么时候开始", 2000);
        check("k6 注入段含「知识库资料」", section.contains("知识库资料"));
        check("k7 注入段含命中正文", section.contains("周五晚 8 点"));
        check("k8 无命中零注入", AiKnowledgeLibrary.INSTANCE.renderPromptSection(knowDir, "量子纠缠的傅里叶变换", 2000).isEmpty());
        check("k9 空目录零注入", AiKnowledgeLibrary.INSTANCE.renderPromptSection(Files.createTempDirectory("empty1762").toFile(), "建筑", 2000).isEmpty());
        check("k10 tokenize 中文滑窗", AiKnowledgeLibrary.INSTANCE.tokenize("服务器规则").contains("服务"));
        check("k11 tokenize 英文整词", AiKnowledgeLibrary.INSTANCE.tokenize("DeepSeek API Key").contains("deepseek"));

        System.out.println("== C 组：爬虫 SSRF 与文本 ==");
        check("c2 私网 10.x 拒绝", !WebCrawler.INSTANCE.isAllowedAddress(java.net.InetAddress.getByName("10.1.2.3")));
        check("c3 私网 192.168 拒绝", !WebCrawler.INSTANCE.isAllowedAddress(java.net.InetAddress.getByName("192.168.1.1")));
        check("c4 回环拒绝", !WebCrawler.INSTANCE.isAllowedAddress(java.net.InetAddress.getByName("127.0.0.1")));
        check("c5 链路本地拒绝", !WebCrawler.INSTANCE.isAllowedAddress(java.net.InetAddress.getByName("169.254.1.1")));
        check("c6 IPv6 ULA 拒绝", !WebCrawler.INSTANCE.isAllowedAddress(java.net.InetAddress.getByName("fd00::1")));
        check("c7 组播拒绝", !WebCrawler.INSTANCE.isAllowedAddress(java.net.InetAddress.getByName("224.0.0.1")));
        check("c8 公网地址放行", WebCrawler.INSTANCE.isAllowedAddress(java.net.InetAddress.getByName("8.8.8.8")));
        String html = "<html><head><script>var x=1;</script><style>.a{}</style></head>"
            + "<body><nav>菜单</nav><h1>标题</h1><p>正文内容第一段。</p><footer>页脚</footer></body></html>";
        String text = WebCrawler.INSTANCE.extractText(html, 6000);
        check("c9 正文提取含正文", text.contains("正文内容第一段"));
        check("c10 正文提取去 script/style/nav/footer", !text.contains("var x") && !text.contains("菜单") && !text.contains("页脚"));

        System.out.println("== A 组：AiChat 标记协议 ==");
        List<String> urls = AiChat.INSTANCE.parseGetUrls(
            "我需要查一下资料 [GET] https://example.com/a?x=1\n再来一个 [GET] http://b.com/page。");
        check("a1 [GET] 解析两条", urls.size() == 2, "urls=" + urls);
        check("a2 [GET] 去重保序", AiChat.INSTANCE.parseGetUrls("[GET] https://x.com/1 [GET] https://x.com/1").size() == 1);
        check("a3 [GET] 尾标点剥离", AiChat.INSTANCE.parseGetUrls("[GET] https://x.com/1.").get(0).equals("https://x.com/1"));
        check("a4 stripGetMarkers", AiChat.INSTANCE.stripGetMarkers("第一行\n[GET] https://x.com/1\n第二行").equals("第一行\n第二行"));
        List<String> memes = AiChat.INSTANCE.parseMemeKeywords("回答内容。\n[表情] 开心\n[表情:鼓掌]", 2);
        check("a5 [表情] 解析两条", memes.size() == 2 && memes.get(0).equals("开心") && memes.get(1).equals("鼓掌"), "memes=" + memes);
        check("a6 [表情] 限量", AiChat.INSTANCE.parseMemeKeywords("[表情] a [表情] b [表情] c", 2).size() == 2);
        check("a7 无标记空表", AiChat.INSTANCE.parseMemeKeywords("普通回复", 1).isEmpty());
        String cleaned = AiChat.INSTANCE.stripReplyMarkers("回答。\n[表情] 开心\n[表情:鼓掌]");
        check("a8 标记剥离", cleaned.equals("回答。"));
        check("a9 无标记保结构", AiChat.INSTANCE.stripReplyMarkers("第一段\n\n第二段").contains("第一段"));
        check("a10 思考剥离", AiChat.INSTANCE.stripThink(THINK_OPEN + "推理" + THINK_CLOSE + "正式回答").equals("正式回答"));
        check("a11 思考未闭合", AiChat.INSTANCE.stripThink("回答" + THINK_OPEN + "没说完").equals("回答"));
        check("a12 全思考给提示", AiChat.INSTANCE.stripThink(THINK_OPEN + "全部是思考" + THINK_CLOSE).contains("重新提问"));
        check("a13 capReply 截断", AiChat.INSTANCE.capReply("x".repeat(2500)).length() < 2500);
        check("a14 stripGetMarkers 空串安全", AiChat.INSTANCE.stripGetMarkers("").isEmpty());

        System.out.println();
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
