package scripts.smoke1731o4;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import cn.kerong.bot.provider.BotShared;
import cn.kerong.penguin.spigot.render.InfoCardAssets;
import cn.kerong.penguin.spigot.render.OnlineListRenderer;
import scripts.smoke1731o.FakeRuntime;

/**
 * 1.7.3.1 第四次覆盖版独立 JVM 冒烟（跑在 NF 分发 JAR 的兼容层之上）。
 *
 * 用户需求（同版本号直接覆盖）：「多页改为重新抽图片」——查在线多页
 * 卡片每页独立重新抽背景图（旧版整组页面共用同一张）。排行榜 / 财富榜
 * 本就每页各抽，本轮只改查在线：OnlineListData 新增 backgroundProvider，
 * renderSingle 每渲染一页按本页画布高度调用一次。
 *
 * 覆盖：
 *  PB 组 provider 每页取背景（确定性纯色，130 人 3 页）
 *        PB1 分页正确（3 页）+ provider 恰调用 3 次 + 各页拿到本页高度
 *            （首页高 ≠ 续页高，两续页等高；解码图高 = provider 高）
 *        PB2 每页背景像素 = 该页 provider 返回的纯色（页页不同色）
 *        PB3 provider 返回 null 回退 background 字段（旧共用行为保留）
 *        PB4 provider 与 background 双 null 仍正常出图
 *        PB5 单页（≤60 人）provider 只调一次
 *  RB 组 真实随机路径（resolveBackground + img/ 目录）
 *        RB1 img/ 两张纯色图 + 61 人 2 页 × 24 轮：每页背景必为两色
 *            之一，且两页颜色不同的轮次 ≥3（每页独立重抽的直接证据；
 *            若整组共用则恒同色，恒同色 24 轮概率 2^-24）
 *        RB2 img/ 空 + useBuiltin=false 自动回退内置背景，正常出图
 *
 * 用法（在仓库根目录）：
 *   export J=/home/z/my-project/tooling/jdk21/bin
 *   $J/javac -cp build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.3.1.jar:scripts/slf4j-api-2.0.13.jar \
 *       -d scripts/smoke1731o4/out scripts/smoke1731o/FakeRuntime.java scripts/smoke1731o4/Test1731o4.java
 *   $J/java -cp scripts/smoke1731o4/out:build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.3.1.jar:scripts/slf4j-api-2.0.13.jar:scripts/slf4j-nop-2.0.13.jar \
 *       scripts.smoke1731o4.Test1731o4
 */
public final class Test1731o4 {

    static int pass = 0;
    static int fail = 0;

    private static void check(String name, boolean cond, Object detail) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + name + (detail == null ? "" : " (" + detail + ")"));
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + (detail == null ? "" : " (" + detail + ")"));
        }
    }

    private static void check(String name, boolean cond) {
        check(name, cond, null);
    }

    /** 画一张纯色图（1200x900，供 provider 逐页返回）。 */
    private static BufferedImage solid(int rgb) {
        BufferedImage img = new BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(rgb));
            g.fillRect(0, 0, 1200, 900);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** 生成 n 个在线玩家条目（无头像 → 占位块）。 */
    private static List<OnlineListRenderer.OnlineEntry> players(int n) {
        List<OnlineListRenderer.OnlineEntry> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(new OnlineListRenderer.OnlineEntry(String.format("P%03d", i), null));
        }
        return list;
    }

    /** 解码 PNG。 */
    private static BufferedImage decode(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    /** 取页边背景像素（x=8,y=8 落在 40px 页边距内，必为纯背景）。 */
    private static int margin(BufferedImage img) {
        return img.getRGB(8, 8) & 0xFFFFFF;
    }

    public static void main(String[] args) throws Exception {
        FakeRuntime fake = new FakeRuntime();
        BotShared.INSTANCE.setInstance(fake);

        final int RED = 0x441100;
        final int GREEN = 0x004411;
        final int BLUE = 0x001144;
        final int MAGENTA = 0x660066;

        // ============ PB 组：provider 每页取背景 ============
        System.out.println("== PB 组：provider 每页取背景（确定性） ==");

        List<BufferedImage> solids = List.of(solid(RED), solid(GREEN), solid(BLUE));
        AtomicInteger calls = new AtomicInteger();
        List<Integer> heights = new ArrayList<>();
        List<byte[]> pages = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData(
                        "SmokeServer", "12:34", players(130), null,
                        h -> {
                            heights.add(h);
                            return solids.get(calls.getAndIncrement() % solids.size());
                        }));
        check("PB1a 130 人切 3 页", pages.size() == 3, "pages=" + pages.size());
        check("PB1b provider 恰调用 3 次（每页一次）", calls.get() == 3, "calls=" + calls.get());
        int h1 = OnlineListRenderer.INSTANCE.measurePageHeight(60, true);
        int h2 = OnlineListRenderer.INSTANCE.measurePageHeight(60, false);
        int h3 = OnlineListRenderer.INSTANCE.measurePageHeight(10, false);
        check("PB1c 各页拿到本页高度（首页 / 续页 60 / 续页 10）",
              heights.size() == 3 && heights.get(0) == h1 && heights.get(1) == h2 && heights.get(2) == h3,
              "heights=" + heights + " expect=" + List.of(h1, h2, h3));
        check("PB1d 首页高 ≠ 续页高且三页高度互异（provider 各页尺寸相关）",
              h1 != h2 && h1 != h3 && h2 != h3, h1 + "/" + h2 + "/" + h3);
        BufferedImage p1 = decode(pages.get(0));
        BufferedImage p2 = decode(pages.get(1));
        BufferedImage p3 = decode(pages.get(2));
        check("PB1e 解码图高 = provider 收到的高度",
              p1.getHeight() == h1 && p2.getHeight() == h2 && p3.getHeight() == h3,
              p1.getHeight() + "/" + p2.getHeight() + "/" + p3.getHeight());
        check("PB1f 画布宽 1350", p1.getWidth() == 1350 && p2.getWidth() == 1350 && p3.getWidth() == 1350);

        check("PB2a 第 1 页背景 = 纯色 A", margin(p1) == RED, Integer.toHexString(margin(p1)));
        check("PB2b 第 2 页背景 = 纯色 B（与第 1 页不同图）", margin(p2) == GREEN, Integer.toHexString(margin(p2)));
        check("PB2c 第 3 页背景 = 纯色 C（与前后页不同图）", margin(p3) == BLUE, Integer.toHexString(margin(p3)));
        check("PB2d 三页背景互不相同", margin(p1) != margin(p2) && margin(p2) != margin(p3) && margin(p1) != margin(p3));

        // PB3 provider 返回 null → 回退 background 字段（旧共用行为保留）
        List<byte[]> fb = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData(
                        "SmokeServer", "12:34", players(130), solid(MAGENTA),
                        h -> null));
        check("PB3a provider null 回退路径同样 3 页", fb.size() == 3, "pages=" + fb.size());
        BufferedImage f1 = decode(fb.get(0));
        BufferedImage f2 = decode(fb.get(1));
        check("PB3b 回退背景两页同色（旧整组共用行为保留）",
              margin(f1) == MAGENTA && margin(f2) == MAGENTA,
              Integer.toHexString(margin(f1)) + "/" + Integer.toHexString(margin(f2)));

        // PB4 双 null 仍正常出图
        List<byte[]> nn = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData(
                        "SmokeServer", "12:34", players(130), null, null));
        check("PB4 双 null 出 3 页且可解码",
              nn.size() == 3 && decode(nn.get(0)) != null && decode(nn.get(2)) != null, "pages=" + nn.size());

        // PB5 单页只调一次
        AtomicInteger single = new AtomicInteger();
        List<byte[]> one = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData(
                        "SmokeServer", "12:34", players(30), null,
                        h -> {
                            single.incrementAndGet();
                            return solid(BLUE);
                        }));
        check("PB5 单页（30 人）provider 只调一次", one.size() == 1 && single.get() == 1,
              "pages=" + one.size() + " calls=" + single.get());

        // ============ RB 组：真实随机路径 ============
        System.out.println("== RB 组：真实随机路径（resolveBackground + img/） ==");

        final int R_RED = 0x441111;
        final int R_GREEN = 0x114411;
        File imgDir = Files.createTempDirectory("smoke1731o4-img").toFile();
        File dirA = new File(imgDir, "img");
        check("RB0 img/ 目录创建", dirA.mkdirs());
        ImageIO.write(solid(R_RED), "png", new File(dirA, "a.png"));
        ImageIO.write(solid(R_GREEN), "png", new File(dirA, "b.png"));

        List<OnlineListRenderer.OnlineEntry> many = players(61);
        int rounds = 24;
        int diffRounds = 0;
        int badRounds = 0;
        for (int i = 0; i < rounds; i++) {
            List<byte[]> rp = OnlineListRenderer.INSTANCE.renderPages(
                    new OnlineListRenderer.OnlineListData(
                            "SmokeServer", "12:34", many, null,
                            h -> InfoCardAssets.INSTANCE.resolveBackground(imgDir, OnlineListRenderer.WIDTH, h, false)));
            if (rp.size() != 2) {
                badRounds++;
                continue;
            }
            int c1 = margin(decode(rp.get(0)));
            int c2 = margin(decode(rp.get(1)));
            boolean okColors = (c1 == R_RED || c1 == R_GREEN) && (c2 == R_RED || c2 == R_GREEN);
            if (!okColors) {
                badRounds++;
                continue;
            }
            if (c1 != c2) {
                diffRounds++;
            }
        }
        check("RB1a 每轮 2 页且两页背景都来自 img/ 两张图", badRounds == 0, "bad=" + badRounds + "/" + rounds);
        check("RB1b 两页背景不同的轮次 ≥3（每页独立重抽的证据；恒同色概率 2^-24）",
              diffRounds >= 3, "diff=" + diffRounds + "/" + rounds);

        // RB2 img/ 空 + useBuiltin=false → 自动回退内置背景
        File emptyDir = Files.createTempDirectory("smoke1731o4-empty").toFile();
        check("RB0b 空 img/ 目录创建", new File(emptyDir, "img").mkdirs());
        List<byte[]> builtin = OnlineListRenderer.INSTANCE.renderPages(
                new OnlineListRenderer.OnlineListData(
                        "SmokeServer", "12:34", many, null,
                        h -> InfoCardAssets.INSTANCE.resolveBackground(emptyDir, OnlineListRenderer.WIDTH, h, false)));
        check("RB2 img/ 空回退内置背景正常出图（2 页可解码）",
              builtin.size() == 2 && decode(builtin.get(0)) != null && decode(builtin.get(1)) != null,
              "pages=" + builtin.size());

        System.out.println();
        System.out.println("总计 " + (pass + fail) + " 项，通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("ALL GREEN");
    }
}
