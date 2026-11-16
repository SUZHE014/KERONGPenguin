import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 1.7.6.3 覆盖轮冒烟（独立 JVM，真实类，无 MC 依赖）：
 * L 组 DataLayout.migrate 端到端——旧结构（根级 QUUID/logs/Markdown/cache/
 *   散文件）→ globalconfig/ 迁移、幂等、目标已存在合并、全新安装无事；
 * M 组 AiMemeLibrary.shrinkForSend——静态大图缩到 ≤240px 且转 GIF
 *   （magic GIF89a）、小图转 GIF、GIF 字节原样、非法字节原样降级；
 * R 组 回归——三常量、[GET]/[表情] 标记、失败文案、提示词文件。
 *
 * 编译运行：javac/java -cp Spigot-1.7.6.3.jar（见 classpath.txt）。
 */
public class Test1763o {
    static int pass = 0, fail = 0;
    static final List<String> fails = new ArrayList<>();

    static void check(String name, boolean ok) { check(name, ok, null); }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS " + name); }
        else { fail++; fails.add(name + (detail == null ? "" : " -> " + detail));
               System.out.println("  FAIL " + name + (detail == null ? "" : " -> " + detail)); }
    }

    static void write(File f, String content) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        File work = Files.createTempDirectory("smoke1763o").toFile();
        cn.kerong.penguin.spigot.manager.DataLayout layout =
            cn.kerong.penguin.spigot.manager.DataLayout.INSTANCE;
        cn.kerong.penguin.spigot.qqbind.AiMemeLibrary memes =
            cn.kerong.penguin.spigot.qqbind.AiMemeLibrary.INSTANCE;

        System.out.println("== L 组：DataLayout 迁移端到端 ==");
        // L1 全新安装：空目录无事发生
        File fresh = new File(work, "fresh"); fresh.mkdirs();
        check("L1 全新安装无事发生", !layout.migrate(fresh) && fresh.list().length == 0);

        // L2 旧结构全量迁移
        File old1 = new File(work, "old1");
        write(new File(old1, "QUUID/ABC123.yml"), "qq: 10001");
        write(new File(old1, "QUUID/index.yml"), "index: {}");
        write(new File(old1, "logs/qq/qq-bind.log"), "log");
        write(new File(old1, "Markdown/menu.md"), "menu");
        write(new File(old1, "cache/avatars/a.png"), "png");
        write(new File(old1, "ai-context.yml"), "ctx");
        write(new File(old1, "fake-players.json"), "[]");
        write(new File(old1, "stats.yml"), "players: {}");
        write(new File(old1, "openids.yml"), "openids: {}");
        // 根目录应保留的四样不动
        write(new File(old1, "config.yml"), "config");
        write(new File(old1, "提示词.md"), "prompt");
        write(new File(old1, "guaipng/开心.png"), "meme");
        write(new File(old1, "img/bg.jpg"), "bg");
        boolean moved = layout.migrate(old1);
        check("L2 旧结构迁移返回 true", moved);
        check("L3 QUUID 迁入 globalconfig",
            new File(old1, "globalconfig/QUUID/ABC123.yml").isFile()
            && new File(old1, "globalconfig/QUUID/index.yml").isFile()
            && !new File(old1, "QUUID").exists());
        check("L4 logs/Markdown/cache 迁入",
            new File(old1, "globalconfig/logs/qq/qq-bind.log").isFile()
            && new File(old1, "globalconfig/Markdown/menu.md").isFile()
            && new File(old1, "globalconfig/cache/avatars/a.png").isFile());
        check("L5 散文件迁入（ai-context/fake-players/stats/openids）",
            new File(old1, "globalconfig/ai-context.yml").isFile()
            && new File(old1, "globalconfig/fake-players.json").isFile()
            && new File(old1, "globalconfig/stats.yml").isFile()
            && new File(old1, "globalconfig/openids.yml").isFile());
        check("L6 根目录四样保留（config/提示词/guaipng/img）",
            new File(old1, "config.yml").isFile() && new File(old1, "提示词.md").isFile()
            && new File(old1, "guaipng/开心.png").isFile() && new File(old1, "img/bg.jpg").isFile());
        check("L7 迁移后根目录只剩 globalconfig + 四样",
            old1.list().length == 5);
        // L8 幂等：再跑无事发生
        check("L8 幂等（二次迁移无事发生）", !layout.migrate(old1));
        check("L9 迁移后数据完好",
            new String(Files.readAllBytes(new File(old1, "globalconfig/QUUID/ABC123.yml").toPath()),
                StandardCharsets.UTF_8).equals("qq: 10001"));

        // L10 目标已存在：目录合并（同名跳过）+ 散文件保留新位置
        File old2 = new File(work, "old2");
        write(new File(old2, "QUUID/shared.yml"), "old");
        write(new File(old2, "QUUID/only-old.yml"), "old");
        write(new File(old2, "globalconfig/QUUID/shared.yml"), "new");
        write(new File(old2, "globalconfig/QUUID/only-new.yml"), "new");
        write(new File(old2, "ai-context.yml"), "oldctx");
        write(new File(old2, "globalconfig/ai-context.yml"), "newctx");
        layout.migrate(old2);
        check("L10 目录合并：同名保留 globalconfig 里的",
            new String(Files.readAllBytes(new File(old2, "globalconfig/QUUID/shared.yml").toPath()),
                StandardCharsets.UTF_8).equals("new"));
        check("L11 目录合并：旧独有文件挪入",
            new File(old2, "globalconfig/QUUID/only-old.yml").isFile()
            && new File(old2, "globalconfig/QUUID/only-new.yml").isFile());
        check("L12 散文件冲突：保留 globalconfig 里的",
            new String(Files.readAllBytes(new File(old2, "globalconfig/ai-context.yml").toPath()),
                StandardCharsets.UTF_8).equals("newctx")
            && !new File(old2, "ai-context.yml").exists());

        System.out.println("== M 组：表情包缩小 shrinkForSend ==");
        // 生成 800x600 静态 PNG（带透明与彩色方块）
        BufferedImage big = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = big.createGraphics();
        g.setColor(new Color(0xFF3366, true)); g.fillRect(0, 0, 800, 300);
        g.setColor(new Color(0x00FF66, true)); g.fillRect(0, 300, 800, 300);
        g.dispose();
        java.io.ByteArrayOutputStream pngOut = new java.io.ByteArrayOutputStream();
        ImageIO.write(big, "png", pngOut);
        byte[] pngBytes = pngOut.toByteArray();
        byte[] shrunk = memes.shrinkForSend(pngBytes);
        check("M1 大图缩小返回新字节", shrunk.length != pngBytes.length,
            "orig=" + pngBytes.length + " shrunk=" + shrunk.length);
        check("M2 缩小结果为 GIF（magic GIF8）",
            shrunk.length >= 6 && shrunk[0]=='G' && shrunk[1]=='I' && shrunk[2]=='F' && shrunk[3]=='8');
        BufferedImage outImg = ImageIO.read(new ByteArrayInputStream(shrunk));
        check("M3 缩放后最长边 ≤ 240",
            outImg != null && Math.max(outImg.getWidth(), outImg.getHeight()) <= 240,
            outImg == null ? "null" : outImg.getWidth() + "x" + outImg.getHeight());
        check("M4 保持宽高比（800:600 ≈ 4:3）",
            outImg != null && Math.abs(outImg.getWidth() * 600 - outImg.getHeight() * 800) <= 600,
            outImg.getWidth() + "x" + outImg.getHeight());
        // 小图（100x80）也转 GIF（统一表情渲染）但分辨率不变
        BufferedImage small = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = small.createGraphics();
        g2.setColor(Color.BLUE); g2.fillRect(0, 0, 100, 80);
        g2.dispose();
        java.io.ByteArrayOutputStream smallOut = new java.io.ByteArrayOutputStream();
        ImageIO.write(small, "png", smallOut);
        byte[] smallBytes = smallOut.toByteArray();
        byte[] smallResult = memes.shrinkForSend(smallBytes);
        check("M5 小图也转 GIF（QQ 表情渲染）",
            smallResult.length >= 4 && smallResult[0]=='G' && smallResult[1]=='I' && smallResult[2]=='F');
        BufferedImage smallImg = ImageIO.read(new ByteArrayInputStream(smallResult));
        check("M6 小图分辨率不变（100x80）",
            smallImg != null && smallImg.getWidth() == 100 && smallImg.getHeight() == 80,
            smallImg == null ? "null" : smallImg.getWidth() + "x" + smallImg.getHeight());
        // GIF 字节原样直传
        byte[] gifBytes = new byte[]{'G','I','F','8','9','a',1,2,3,4,5};
        check("M7 GIF 字节原样直传（含动画）",
            java.util.Arrays.equals(memes.shrinkForSend(gifBytes), gifBytes));
        // 非法字节降级原样
        byte[] junk = new byte[]{-1,-40,-2,5,6,7,8};
        check("M8 非图片字节原样降级",
            java.util.Arrays.equals(memes.shrinkForSend(junk), junk));
        check("M9 目标边长常量 240",
            cn.kerong.penguin.spigot.qqbind.AiMemeLibrary.SEND_MAX_SIDE == 240);

        System.out.println("== R 组：回归 ==");
        check("R1 表情包上限常量 1",
            cn.kerong.penguin.spigot.qqbind.AiChat.MEME_MAX_PER_REPLY == 1);
        check("R2 固定超时 10 / 正文 6000 常量",
            cn.kerong.penguin.spigot.qqbind.WebCrawler.DEFAULT_TIMEOUT_SECONDS == 10
            && cn.kerong.penguin.spigot.qqbind.WebCrawler.DEFAULT_MAX_CHARS == 6000);
        List<String> urls = cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.parseGetUrls(
            "[GET] https://www.bing.com/search?q=我的世界\n[GET] https://b.com/2");
        check("R3 [GET] 解析（含必应中文）", urls.size() == 2
            && urls.get(0).equals("https://www.bing.com/search?q=我的世界"));
        List<String> memeKeywords = cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.parseMemeKeywords(
            "回答 [表情] 开心", 3);
        check("R4 [表情] 标记回归", memeKeywords.size() == 1 && memeKeywords.get(0).equals("开心"));
        String failMsg = cn.kerong.penguin.spigot.qqbind.WebCrawler.READ_FAILURE_MESSAGE;
        check("R5 失败文案回归", failMsg.contains("网页暂时无法访问或没有返回内容"));
        String guided = cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE.buildPageFeedback(
            "https://example.com/x", failMsg);
        check("R6 失败引导回归", guided.contains("不要输出错误代码或技术原因"));
        File prompt = new File(work, "提示词.md");
        cn.kerong.penguin.spigot.qqbind.AiPromptFile.INSTANCE.ensureFile(prompt);
        String content = new String(Files.readAllBytes(prompt.toPath()), StandardCharsets.UTF_8);
        check("R7 提示词默认结构回归", content.contains("# 角色设定") && content.contains("# 专属知识"));
        String encoded = cn.kerong.penguin.spigot.qqbind.WebCrawler.INSTANCE.normalizeUrl(
            "https://www.bing.com/search?q=我的世界");
        check("R8 URL 编码回归", encoded.contains("%E6%88%91"));

        System.out.println();
        System.out.println("RESULT: " + pass + "/" + (pass + fail) + " PASS, " + fail + " FAIL");
        if (!fails.isEmpty()) { System.out.println("失败清单: " + fails); System.exit(1); }
    }
}
