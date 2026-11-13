import cn.kerong.bot.tools.QrCode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;

/**
 * 1.7.5.5 冒烟（独立 JVM，NeoForge 分发 JAR 类路径）——二维码编码自适应：
 * 1. encodeAscii 原版半块渲染（▀▄█ 行对压缩，宽 n+2q）
 * 2. encodeCjk 全角渲染（■/U+3000，每模块一行，GBK 可编码）
 * 3. encodePlain 纯 ASCII 渲染（##/空格对，任何编码可表示）
 * 4. 三渲染器同一矩阵（encodePlain 解析回网格与 encode().modules 逐格一致）
 * 5. JDK 编码语义（GBK：▀▄█ 不可编码 / ■ 与 U+3000 可编码；UTF-8 全可）
 * 6. 渲染选择逻辑镜像（GBK PrintStream → CJK 渲染；UTF-8 → 原版渲染）
 * 7. GBK 安全性端到端（encodeCjk 输出整体可被 GBK 编码器编码；encodeAscii 不可）
 * 8. 回归（授权 URL 长文本渲染无异常 / 每行宽度一致）
 */
public class Smoke1755 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) { check(name, ok, null); }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name + (detail == null ? "" : "  >> " + detail)); }
    }

    static final String URL =
            "https://q.qq.com/qqbot/openclaw/connect.html?task_id=5cbd362a-63af-4b5f-a17a-7f85cc34728e&source=openclaw&_wv=2";

    public static void main(String[] args) {
        QrCode.Matrix matrix = QrCode.encode(URL);
        int n = matrix.getSize();
        int q = 2;

        // ---------- 1. encodeAscii 原版半块 ----------
        String ascii = QrCode.encodeAscii(URL, 2);
        String[] asciiLines = ascii.split("\n", -1);
        int expectAsciiLines = (n + 2 * q + 1) / 2;
        boolean hasHalf = ascii.contains("▀") || ascii.contains("▄") || ascii.contains("█");
        check("1.1 encodeAscii 行数 = ceil((n+2q)/2) = " + expectAsciiLines,
                asciiLines.length == expectAsciiLines, "实际 " + asciiLines.length);
        check("1.2 encodeAscii 含半块字符", hasHalf);
        check("1.3 encodeAscii 每行宽度 = n+2q = " + (n + 2 * q),
                allWidth(asciiLines, n + 2 * q));

        // ---------- 2. encodeCjk 全角方块 ----------
        String cjk = QrCode.encodeCjk(URL, 2);
        String[] cjkLines = cjk.split("\n", -1);
        check("2.1 encodeCjk 行数 = n+2q = " + (n + 2 * q),
                cjkLines.length == n + 2 * q, "实际 " + cjkLines.length);
        check("2.2 encodeCjk 每行宽度 = n+2q（全角 1 模块/字符）",
                allWidth(cjkLines, n + 2 * q));
        boolean cjkCharsOnly = true;
        outerCjk:
        for (String line : cjkLines) {
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c != '■' && c != '\u3000') { cjkCharsOnly = false; break outerCjk; }
            }
        }
        check("2.3 encodeCjk 字符仅 ■ 与 U+3000", cjkCharsOnly);
        check("2.4 encodeCjk 含深色 ■", cjk.contains("■"));

        // ---------- 3. encodePlain 纯 ASCII ----------
        String plain = QrCode.encodePlain(URL, 2);
        String[] plainLines = plain.split("\n", -1);
        check("3.1 encodePlain 行数 = n+2q", plainLines.length == n + 2 * q,
                "实际 " + plainLines.length);
        check("3.2 encodePlain 每行宽度 = 2*(n+2q)（##/空格对）",
                allWidth(plainLines, 2 * (n + 2 * q)));
        boolean plainAsciiOnly = plain.chars().allMatch(c -> c == '#' || c == ' ' || c == '\n');
        check("3.3 encodePlain 纯 7 位 ASCII", plainAsciiOnly);
        check("3.4 encodePlain 含深色 ##", plain.contains("##"));

        // ---------- 4. 三渲染器同一矩阵 ----------
        boolean gridMatch = true;
        outerPlain:
        for (int r = 0; r < n + 2 * q; r++) {
            String pl = plainLines[r];
            for (int c = 0; c < n + 2 * q; c++) {
                boolean rendered = pl.charAt(2 * c) == '#';
                boolean expected = (r >= q && r < q + n && c >= q && c < q + n)
                        ? matrix.getModules()[r - q][c - q] : false;
                if (rendered != expected) { gridMatch = false; break outerPlain; }
            }
        }
        check("4.1 encodePlain 逐格 == encode().modules（含静区 false）", gridMatch);
        boolean cjkMatch = true;
        outerCjk2:
        for (int r = 0; r < n + 2 * q; r++) {
            for (int c = 0; c < n + 2 * q; c++) {
                boolean rendered = cjkLines[r].charAt(c) == '■';
                boolean expected = (r >= q && r < q + n && c >= q && c < q + n)
                        ? matrix.getModules()[r - q][c - q] : false;
                if (rendered != expected) { cjkMatch = false; break outerCjk2; }
            }
        }
        check("4.2 encodeCjk 逐格 == encode().modules", cjkMatch);

        // ---------- 5. JDK 编码语义 ----------
        // JDK GBK 表含 ▄█（U+2581-2588，A8 区）但不含 ▀（U+2580 上半块）——
        // 真机 GBK 输出即「█▄ 幸存 + ▀ 变 ?」的混合损坏形态；▀ 即回退触发字符。
        Charset gbk = Charset.forName("GBK");
        CharsetEncoder gbkEnc = gbk.newEncoder();
        check("5.1 GBK 不可编码 ▀（回退触发字符；▄█ 虽可编码但三字符 AND 判定仍触发）",
                !gbkEnc.canEncode('▀'));
        check("5.2 GBK 可编码 ■ 与 U+3000（回退字符成立）",
                gbkEnc.canEncode('■') && gbkEnc.canEncode('\u3000'));
        Charset utf8 = Charset.forName("UTF-8");
        CharsetEncoder utf8Enc = utf8.newEncoder();
        check("5.3 UTF-8 全部可编码（原版渲染成立）",
                utf8Enc.canEncode('▀') && utf8Enc.canEncode('▄') && utf8Enc.canEncode('█')
                        && utf8Enc.canEncode('■') && utf8Enc.canEncode('\u3000'));

        // ---------- 6. 渲染选择逻辑镜像（与 QrLoginManager.renderQrForConsole 同式） ----------
        check("6.1 GBK PrintStream.charset() 反映 GBK",
                printStreamCharset("GBK").name().toUpperCase().contains("GBK"));
        String chosenGbk = chooseRenderer(printStreamCharset("GBK"), URL);
        String chosenUtf8 = chooseRenderer(printStreamCharset("UTF-8"), URL);
        check("6.2 GBK 控制台 → CJK 渲染（■ 在场 / 无半块字符）",
                chosenGbk.contains("■") && !chosenGbk.contains("▀") && !chosenGbk.contains("▄"));
        check("6.3 UTF-8 控制台 → 原版半块渲染",
                chosenUtf8.contains("▀") || chosenUtf8.contains("▄") || chosenUtf8.contains("█"));
        Charset asciiCs = Charset.forName("US-ASCII");
        String chosenAscii = chooseRenderer(asciiCs, URL);
        check("6.4 ASCII 控制台 → 纯 ASCII 渲染（## 在场）",
                chosenAscii.contains("##") && !chosenAscii.contains("■"));

        // ---------- 7. GBK 安全性端到端 ----------
        boolean cjkGbkSafe = gbkEnc.canEncode(cjk.replace("\n", ""));
        check("7.1 encodeCjk 输出可被 GBK 编码（不再出现问号替换）", cjkGbkSafe);
        boolean asciiGbkUnsafe = !gbkEnc.canEncode(ascii.replace("\n", ""));
        check("7.2 encodeAscii 输出不可被 GBK 编码（回退必要性证明）", asciiGbkUnsafe);

        // ---------- 8. 回归 ----------
        String asciiQ4 = QrCode.encodeAscii(URL, 4);
        check("8.1 静区 4（规范默认）渲染行数 = ceil((n+8)/2)",
                asciiQ4.split("\n", -1).length == (n + 8 + 1) / 2);
        boolean threw = false;
        try {
            QrCode.encodeAscii("", 2);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("8.2 空内容 IllegalArgumentException（行为保持）", threw);
        System.out.println("----");
        System.out.println("共 " + (passed + failed) + " 项，通过 " + passed + "，失败 " + failed);
        if (failed > 0) System.exit(1);
    }

    static boolean allWidth(String[] lines, int w) {
        for (String line : lines) if (line.length() != w) return false;
        return true;
    }

    /** 构造指定编码的 PrintStream 并取 charset（Java 17+ API）。 */
    static Charset printStreamCharset(String cs) {
        try {
            PrintStream ps = new PrintStream(new ByteArrayOutputStream(), true, cs);
            return ps.charset();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 与 QrLoginManager.renderQrForConsole 相同的分级选择（JDK 语义镜像）。 */
    static String chooseRenderer(Charset cs, String url) {
        CharsetEncoder enc = cs.newEncoder();
        if (enc.canEncode('▀') && enc.canEncode('▄') && enc.canEncode('█')) {
            return QrCode.encodeAscii(url, 2);
        }
        if (enc.canEncode('■') && enc.canEncode('\u3000')) {
            return QrCode.encodeCjk(url, 2);
        }
        return QrCode.encodePlain(url, 2);
    }
}
