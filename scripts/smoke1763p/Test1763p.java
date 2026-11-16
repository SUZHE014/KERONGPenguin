import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 1.7.6.3 二次覆盖轮冒烟（独立 JVM，真实类，无 MC 依赖）：
 * N 组 AiChat.exhaustedFeedback + 标记解析回归——达上限反馈文案参数化
 *   （页数/层数插值、不再输出 [GET]、基于已读内容引导）；
 * A 组 JAR 内嵌 config.yml 全键注释审计（Java 版同规则：同行 # 或
 *   紧邻上方非空行是注释；0 缺失才过，对齐 scripts/audit_config_comments.py）；
 * M 组 shrinkForSend 回归（静态大图 → ≤240px GIF）；
 * L 组 DataLayout.migrate 回归（旧结构迁移 + 幂等）。
 *
 * 编译运行：javac/java -cp Spigot-1.7.6.3.jar（见 classpath.txt）。
 */
public class Test1763p {
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

    // ---------- A 组：全键注释审计（与 audit_config_comments.py 同规则） ----------
    static boolean isComment(String line) { return line.trim().startsWith("#"); }
    static boolean isBlank(String line) { return line.trim().isEmpty(); }
    static boolean isListItem(String line) { return line.trim().startsWith("-"); }
    static boolean isKeyLine(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("-")) return false;
        int i = 0;
        if (t.charAt(0) == '"') {
            int close = t.indexOf('"', 1);
            if (close < 0) return false;
            return t.length() > close + 1 && t.charAt(close + 1) == ':';
        }
        char c0 = t.charAt(0);
        if (!(Character.isLetter(c0))) return false;
        while (i < t.length() && (Character.isLetterOrDigit(t.charAt(i)) || t.charAt(i) == '_' || t.charAt(i) == '-' || t.charAt(i) == '.')) i++;
        return i > 0 && i < t.length() && t.charAt(i) == ':';
    }
    static String prevNonblank(String[] lines, int idx) {
        for (int j = idx - 1; j >= 0; j--) if (!isBlank(lines[j])) return lines[j];
        return null;
    }

    static List<String> auditMissing(String text) {
        String[] lines = text.split("\n", -1);
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (isListItem(line) || !isKeyLine(line)) continue;
            String after = line.trim();
            int colon = after.indexOf(':');
            if (after.indexOf('#', colon) >= 0) continue; // 同行 #
            String up = prevNonblank(lines, i);
            if (up == null || !isComment(up)) missing.add((i + 1) + ": " + line.trim());
        }
        return missing;
    }

    // ---------- M 组工具：构造静态大图 ----------
    static byte[] makePng(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(java.awt.Color.RED);
        g.fillRect(0, 0, w, h);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        File work = Files.createTempDirectory("smoke1763p").toFile();
        cn.kerong.penguin.spigot.qqbind.AiChat aichat =
            cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE;
        cn.kerong.penguin.spigot.qqbind.AiMemeLibrary memes =
            cn.kerong.penguin.spigot.qqbind.AiMemeLibrary.INSTANCE;
        cn.kerong.penguin.spigot.manager.DataLayout layout =
            cn.kerong.penguin.spigot.manager.DataLayout.INSTANCE;

        System.out.println("== N 组：exhaustedFeedback + 标记解析回归 ==");
        String fb84 = aichat.exhaustedFeedback(8, 4);
        check("N1 反馈含页数插值（8 页）", fb84.contains("8 页"), fb84);
        check("N2 反馈含层数插值（4 层）", fb84.contains("4 层"), fb84);
        check("N3 反馈禁止再输出 [GET]", fb84.contains("请不要再输出 [GET]"), fb84);
        check("N4 反馈引导基于已读内容作答", fb84.contains("基于已读到的网页内容回答用户"), fb84);
        String fb52 = aichat.exhaustedFeedback(5, 2);
        check("N5 参数化独立（5/2 不串 8/4）", fb52.contains("5 页") && fb52.contains("2 层")
              && !fb52.contains("8 页"), fb52);
        check("N6 反馈含兜底说明（信息不足时说明）", fb84.contains("可能不够准确"), fb84);
        // 标记解析回归
        List<String> urls = aichat.parseGetUrls("[GET] https://a.com/x\n[GET] https://b.com/y\n重复 [GET] https://a.com/x");
        check("N7 parseGetUrls 去重保序回归", urls.size() == 2 && urls.get(0).equals("https://a.com/x") && urls.get(1).equals("https://b.com/y"));
        String stripped = aichat.stripGetMarkers("前言\n[GET] https://a.com/x\n[GET] https://b.com/y\n结语");
        check("N8 stripGetMarkers 回归", stripped.equals("前言\n结语"), stripped);
        String failMsg = cn.kerong.penguin.spigot.qqbind.WebCrawler.READ_FAILURE_MESSAGE;
        String failFb = aichat.buildPageFeedback("https://a.com", failMsg);
        check("N9 失败反馈口径回归", failFb.contains("没读取成功") && failFb.contains("不要输出错误代码"));
        String okFb = aichat.buildPageFeedback("https://a.com", "正文");
        check("N10 成功反馈格式回归", okFb.equals("[网页内容] https://a.com\n正文"), okFb);

        System.out.println("== A 组：JAR 内嵌 config.yml 全键注释审计 ==");
        InputStream cfgIn = Test1763p.class.getClassLoader().getResourceAsStream("config.yml");
        String cfgText = cfgIn == null ? "" : new String(cfgIn.readAllBytes(), StandardCharsets.UTF_8);
        check("A1 config.yml 资源可读", !cfgText.isEmpty());
        List<String> missing = auditMissing(cfgText);
        check("A2 全键注释 0 缺失（共 " + cfgText.split("\n", -1).length + " 行）",
              missing.isEmpty(), String.join("; ", missing.subList(0, Math.min(5, missing.size()))));
        check("A3 新键 max-layers: 4 在 config", cfgText.contains("max-layers: 4"));
        check("A4 max-pages: 8 + 单次口径注释", cfgText.contains("max-pages: 8")
              && cfgText.contains("单次回答最多读取的网页总数"));
        check("A5 config-version: 60", cfgText.contains("config-version: 60"));

        System.out.println("== M 组：shrinkForSend 回归（o 轮核心） ==");
        byte[] bigPng = makePng(800, 600);
        byte[] shrunk = memes.shrinkForSend(bigPng);
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(shrunk));
        check("M1 800x600 缩到 ≤240px", back != null && Math.max(back.getWidth(), back.getHeight()) <= 240,
              back == null ? "null" : back.getWidth() + "x" + back.getHeight());
        check("M2 缩放产物是 GIF（魔数）", shrunk.length > 6 && shrunk[0] == 'G' && shrunk[1] == 'I' && shrunk[2] == 'F');
        byte[] gifIn = new byte[]{(byte) 'G', (byte) 'I', (byte) 'F', (byte) '8', (byte) '9', (byte) 'a', 1, 2, 3};
        check("M3 GIF 字节原样直传", java.util.Arrays.equals(memes.shrinkForSend(gifIn), gifIn));
        byte[] junk = {1, 2, 3, 4};
        check("M4 非法字节原样降级", java.util.Arrays.equals(memes.shrinkForSend(junk), junk));

        System.out.println("== L 组：DataLayout.migrate 回归（o 轮核心） ==");
        File old1 = new File(work, "old1");
        write(new File(old1, "QUUID/ABC123.yml"), "qq: 10001");
        write(new File(old1, "logs/qq/qq-bind.log"), "log");
        write(new File(old1, "ai-context.yml"), "ctx");
        check("L1 旧结构迁移发生", layout.migrate(old1));
        check("L2 迁入 globalconfig 子目录", new File(old1, "globalconfig/QUUID/ABC123.yml").isFile()
              && new File(old1, "globalconfig/logs/qq/qq-bind.log").isFile()
              && new File(old1, "globalconfig/ai-context.yml").isFile());
        check("L3 幂等（二次迁移无事）", !layout.migrate(old1));

        System.out.println();
        System.out.println("RESULT: " + pass + "/" + (pass + fail) + " PASS, " + fail + " FAIL");
        if (!fails.isEmpty()) { System.out.println("失败清单："); for (String f : fails) System.out.println("  - " + f); }
        System.exit(fail == 0 ? 0 : 1);
    }
}
