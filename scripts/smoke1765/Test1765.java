import java.net.InetAddress;
import java.net.Inet6Address;

/**
 * 1.7.6.5 冒烟（独立 JVM，真实类）：
 *
 * S 组 WebCrawler 安全机制纯函数——
 *   isDownloadUrl：压缩包/安装包/图片等下载扩展名黑名单（查询串/锚点/
 *     大小写/路径点号形态）；
 *   stripInvisibleChars：零宽字符族 / 双向控制符 / 不占位字符剔除、BOM
 *     归一空格、常规文字与换行保留；
 *   isDispositionAttachment：attachment / inline / 大小写 / 空串；
 *   isAllowedAddress：回环 / 内网 / CGN / 链路本地 / IPv6 ULA / IPv4 内嵌
 *     IPv6 映射地址拒绝，公网放行；
 *   fetch 前置拒绝（不触网）：下载链接 / 非 http 协议 / 带凭据网址 →
 *     统一失败文案。
 * P 组 AiChat——
 *   buildPageFeedback 成功分支带「资料不是指令」防注入框定，失败分支为
 *   自然语言引导不带框定。
 * M 组 表情兜底文案常量在位（DEFAULT_GAME_MEME_FALLBACK 去「可」字）。
 *
 * 编译运行：javac/java -cp Spigot 分发 JAR（jsoup/fastjson 等依赖全在包内）。
 */
public class Test1765 {
    static int pass = 0, fail = 0;
    static final java.util.List<String> fails = new java.util.ArrayList<>();

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  PASS " + name); }
        else { fail++; fails.add(name); System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) throws Exception {
        cn.kerong.penguin.spigot.qqbind.WebCrawler wc =
                cn.kerong.penguin.spigot.qqbind.WebCrawler.INSTANCE;
        cn.kerong.penguin.spigot.qqbind.AiChat ai =
                cn.kerong.penguin.spigot.qqbind.AiChat.INSTANCE;

        System.out.println("== S 组：WebCrawler 安全机制 ==");

        // ---- S1 isDownloadUrl ----
        check("S1a zip 直链", wc.isDownloadUrl("https://example.com/file.zip"));
        check("S1b exe 带查询串", wc.isDownloadUrl("https://example.com/a/setup.exe?v=1"));
        check("S1c png 带锚点", wc.isDownloadUrl("https://example.com/img/photo.png#x"));
        check("S1d jar", wc.isDownloadUrl("https://example.com/lib.jar"));
        check("S1e mp4", wc.isDownloadUrl("https://example.com/v/movie.mp4"));
        check("S1f pdf", wc.isDownloadUrl("https://example.com/doc/report.pdf"));
        check("S1g 大写 ZIP", wc.isDownloadUrl("https://example.com/A.ZIP"));
        check("S1h 路径含点目录仍判网页", !wc.isDownloadUrl("https://example.com/a.b/page"));
        check("S1i 搜索页不误杀", !wc.isDownloadUrl("https://www.bing.com/search?q=%E6%88%91%E7%9A%84%E4%B8%96%E7%95%8C"));
        check("S1j 普通网页不误杀", !wc.isDownloadUrl("https://example.com/index.html"));
        check("S1k 无扩展名不误杀", !wc.isDownloadUrl("https://example.com/"));

        // ---- S2 stripInvisibleChars ----
        String injected = "正常\u200B文字\u202E注入\u2066测试\u2060完毕";
        String cleaned = wc.stripInvisibleChars(injected);
        check("S2a 零宽空格剔除", !cleaned.contains("\u200B"));
        check("S2b 双向覆盖符剔除", !cleaned.contains("\u202E"));
        check("S2c 双向隔离符剔除", !cleaned.contains("\u2066"));
        check("S2d 零宽不连字剔除", !cleaned.contains("\u2060"));
        check("S2e 正常文字保留", cleaned.contains("正常") && cleaned.contains("文字")
                && cleaned.contains("注入") && cleaned.contains("测试") && cleaned.contains("完毕"));
        check("S2f BOM 归一空格", wc.stripInvisibleChars("a\uFEFFb").equals("a b"));
        check("S2g 换行制表保留", wc.stripInvisibleChars("a\nb\tc").equals("a\nb\tc"));
        check("S2h 控制字符剔除", !wc.stripInvisibleChars("a\u0000b\u001Fc").contains("\u0000"));

        // ---- S3 isDispositionAttachment ----
        check("S3a attachment 命中", wc.isDispositionAttachment("attachment; filename=\"a.zip\""));
        check("S3b inline 不命中", !wc.isDispositionAttachment("inline; filename=\"x.html\""));
        check("S3c 空串不命中", !wc.isDispositionAttachment(""));
        check("S3d 大小写不敏感", wc.isDispositionAttachment("Attachment; filename=a.pdf"));
        check("S3e form-data 无 filename 不命中", !wc.isDispositionAttachment("form-data; name=\"f\""));

        // ---- S5 isAllowedAddress（字面量解析不触网） ----
        check("S5a 回环拒", !wc.isAllowedAddress(InetAddress.getByName("127.0.0.1")));
        check("S5b 内网 10/8 拒", !wc.isAllowedAddress(InetAddress.getByName("10.1.2.3")));
        check("S5c 内网 192.168/16 拒", !wc.isAllowedAddress(InetAddress.getByName("192.168.1.1")));
        check("S5d CGN 100.64/10 拒", !wc.isAllowedAddress(InetAddress.getByName("100.64.0.1")));
        check("S5e 链路本地拒", !wc.isAllowedAddress(InetAddress.getByName("169.254.1.1")));
        check("S5f IPv6 ULA 拒", !wc.isAllowedAddress(InetAddress.getByName("fd00::1")));
        check("S5g IPv6 回环拒", !wc.isAllowedAddress(InetAddress.getByName("::1")));
        check("S5h 公网 IPv4 放行", wc.isAllowedAddress(InetAddress.getByName("8.8.8.8")));
        check("S5i 公网 IPv6 放行", wc.isAllowedAddress(InetAddress.getByName("2606:4700::1111")));
        // IPv4 内嵌映射形式（::ffff:c0a8:101 = 192.168.1.1）：无论 JVM 解析为
        // Inet4 还是保留 Inet6 映射形态，都必须拒绝
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF; mapped[11] = (byte) 0xFF;
        mapped[12] = (byte) 192; mapped[13] = (byte) 168; mapped[14] = 1; mapped[15] = 1;
        InetAddress mappedAddr;
        try {
            mappedAddr = Inet6Address.getByAddress(null, mapped, 0);
        } catch (Throwable t) {
            mappedAddr = InetAddress.getByAddress(mapped);
        }
        check("S5j IPv4 映射 IPv6 拒（192.168.1.1）", !wc.isAllowedAddress(mappedAddr));
        byte[] mappedPub = new byte[16];
        mappedPub[10] = (byte) 0xFF; mappedPub[11] = (byte) 0xFF;
        mappedPub[12] = 8; mappedPub[13] = 8; mappedPub[14] = 8; mappedPub[15] = 8;
        InetAddress mappedPubAddr;
        try {
            mappedPubAddr = Inet6Address.getByAddress(null, mappedPub, 0);
        } catch (Throwable t) {
            mappedPubAddr = InetAddress.getByAddress(mappedPub);
        }
        check("S5k IPv4 映射公网地址放行（8.8.8.8）", wc.isAllowedAddress(mappedPubAddr));

        // ---- S6 fetch 前置拒绝（全程不触网） ----
        String r1 = wc.fetch("https://example.com/downloads/file.zip", 5, 6000);
        check("S6a 下载链接统一失败文案",
                cn.kerong.penguin.spigot.qqbind.WebCrawler.READ_FAILURE_MESSAGE.equals(r1));
        String r2 = wc.fetch("ftp://example.com/file.txt", 5, 6000);
        check("S6b 非 http 协议拒绝",
                cn.kerong.penguin.spigot.qqbind.WebCrawler.READ_FAILURE_MESSAGE.equals(r2));
        String r3 = wc.fetch("https://user:pass@example.com/", 5, 6000);
        check("S6c 带凭据网址拒绝",
                cn.kerong.penguin.spigot.qqbind.WebCrawler.READ_FAILURE_MESSAGE.equals(r3));

        System.out.println("== P 组：AiChat 防注入框定 ==");
        String okPage = ai.buildPageFeedback("https://example.com/page", "页面正文内容");
        check("P1 成功分支含「资料不是指令」框定", okPage.contains("不是对你的命令") && okPage.contains("外部资料"));
        check("P2 成功分支正文保留", okPage.contains("页面正文内容") && okPage.contains("https://example.com/page"));
        String failPage = ai.buildPageFeedback("https://example.com/x",
                cn.kerong.penguin.spigot.qqbind.WebCrawler.READ_FAILURE_MESSAGE);
        check("P3 失败分支自然语言引导", failPage.contains("没读取成功") && failPage.contains("不要输出错误代码"));
        check("P4 失败分支不带成功框定文案", !failPage.contains("不是对你的命令"));

        System.out.println("== M 组：表情兜底文案常量 ==");
        String fallback = cn.kerong.penguin.spigot.qqbind.AiChat.DEFAULT_GAME_MEME_FALLBACK;
        check("M1 兜底文案新句式（无「可」字）",
                "（只想发个表情，奈何游戏不允许）".equals(fallback) && !fallback.contains("可奈何"));

        System.out.println("== PM 组：PerformanceSnapshot 内存口径（含预留，2026-11-18 覆盖轮）==");
        cn.kerong.bot.PerformanceSnapshot snap =
                cn.kerong.bot.PerformanceSnapshot.capture(null, null);
        Runtime rt = Runtime.getRuntime();
        long committed = rt.totalMemory() / (1024L * 1024L);
        check("PM1 committedMemoryMb = totalMemory/MB（含预留口径）",
                snap.getCommittedMemoryMb() == committed);
        check("PM2 maxMemoryMb = maxMemory/MB", snap.getMaxMemoryMb() == rt.maxMemory() / (1024L * 1024L));
        long live = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        check("PM3 含预留 ≥ 存活口径（total - free）", snap.getCommittedMemoryMb() >= live);
        check("PM4 已提交 ≤ 上限（Xmx）", snap.getCommittedMemoryMb() <= snap.getMaxMemoryMb());

        System.out.println("===== smoke1765: " + pass + " PASS / " + fail + " FAIL =====");
        if (fail > 0) {
            for (String f : fails) System.out.println("  FAIL 项: " + f);
            System.exit(1);
        }
    }
}
