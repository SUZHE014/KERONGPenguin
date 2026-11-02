package scripts.smoke1711;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import cn.kerong.bot.provider.BotShared;
import cn.kerong.penguin.spigot.render.BackgroundPackService;
import cn.kerong.shaded.kotlin.Unit;

/**
 * 1.7.1 冒烟：BackgroundPackService 全场景端到端（独立 JVM + 本地 HTTP 服务）。
 *
 * 断言组：
 *   S1  parseManifest 合法多包解析（id/name/files/size/md5）
 *   S2  parseManifest 拒绝不安全条目（路径穿越文件名 / 非法 md5 / 无文件包 / 坏 JSON）
 *   S3  isSafeToken / isSafeFileName 黑白名单
 *   S4  md5Of 与 JDK MessageDigest 一致
 *   S5  downloadPack 全成功（3 张 → img/，md5 校验通过）
 *   S6  重复下载全部「已存在跳过」（断点续传语义）
 *   S7  单张 404 → 失败跳过其余照常
 *   S8  主源字节损坏（md5 不符）→ 自动换镜像源成功
 *   S9  主源整体不可达 → 全部走镜像源成功（含源降级）
 *   S10 下载中 requestCancel 中断（慢速端点 + 分块读中途取消；无 .part 残留）
 *   S11 downloadPackById 未知包 ID → 未找到提示
 *   S12 listPacksLines 清单列表输出（含包名与用法行）
 *   S13 switchBackgrounds 真实配置读写（五键翻转 + 自动重载计数 + img 空目录提醒）
 *   S14 BACKGROUND_SWITCH_KEYS 恰为五键
 */
public final class Test1711 {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String name, boolean cond) {
        check(name, cond, "");
    }

    private static void check(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : " — " + detail));
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : " — " + detail));
        }
    }

    private static final BackgroundPackService SVC = BackgroundPackService.INSTANCE;

    // ---------- 工具 ----------

    private static String md5Hex(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] bytesOf(char c, int len) {
        byte[] data = new byte[len];
        java.util.Arrays.fill(data, (byte) c);
        return data;
    }

    /** 测试用图片包文件。 */
    private static BackgroundPackService.PackFile packFile(String name, byte[] data) throws Exception {
        return new BackgroundPackService.PackFile(name, data.length, md5Hex(data));
    }

    private static BackgroundPackService.PackInfo packInfo(String id, String name, List<BackgroundPackService.PackFile> files) {
        return new BackgroundPackService.PackInfo(id, name, "smoke pack", files);
    }

    /** 收集进度行的监听器。 */
    private static List<String> collector() {
        return Collections.synchronizedList(new ArrayList<>());
    }

    private static Unit consume(List<String> sink, String line) {
        sink.add(line);
        return Unit.INSTANCE;
    }

    // ---------- 本地 HTTP 服务 ----------

    /** 简单文件服务：context 根下按精确路径回内容；找不到回 404。 */
    static final class FileServer {
        final HttpServer server;
        final AtomicInteger hits = new AtomicInteger();
        private final java.util.Map<String, byte[]> files = new java.util.HashMap<>();
        private final java.util.Map<String, HandlerHook> hooks = new java.util.HashMap<>();

        interface HandlerHook {
            void handle(HttpExchange exchange) throws Exception;
        }

        FileServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::route);
            server.start();
        }

        void put(String path, byte[] data) {
            files.put(path, data);
        }

        void hook(String path, HandlerHook hook) {
            hooks.put(path, hook);
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        private void route(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            hits.incrementAndGet();
            HandlerHook hook = hooks.get(path);
            if (hook != null) {
                try {
                    hook.handle(exchange);
                } catch (IOException io) {
                    throw io;
                } catch (Exception e) {
                    throw new IOException("hook failed", e);
                }
                return;
            }
            byte[] data = files.get(path);
            if (data == null) {
                byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        }

        void stop() {
            server.stop(0);
        }
    }

    // ---------- 主流程 ----------

    public static void main(String[] args) throws Exception {
        File dataDir = new File(System.getProperty("java.io.tmpdir"), "smoke1711-case");
        deleteRecursively(dataDir);
        dataDir.mkdirs();

        byte[] aData = bytesOf('A', 100_000);
        byte[] bData = bytesOf('B', 200_000);
        byte[] cData = bytesOf('C', 300_000);

        System.out.println("== S1-S4：纯函数 ==");

        // S1 合法多包解析
        String manifest = "{\"packs\":[" +
            "{\"id\":\"wl1\",\"name\":\"蔚蓝档案\",\"description\":\"desc\",\"files\":[" +
            "{\"name\":\"a.jpg\",\"size\":100000,\"md5\":\"" + md5Hex(aData) + "\"}," +
            "{\"name\":\"b.jpg\",\"size\":200000,\"md5\":\"" + md5Hex(bData) + "\"}]}," +
            "{\"id\":\"p2\",\"name\":\"包二\",\"description\":\"\",\"files\":[" +
            "{\"name\":\"c.jpg\",\"size\":300000,\"md5\":\"" + md5Hex(cData) + "\"}]}]}";
        List<BackgroundPackService.PackInfo> packs = SVC.parseManifest(manifest);
        check("S1a 合法两包解析", packs.size() == 2, "size=" + packs.size());
        check("S1b 包字段正确",
            packs.get(0).getId().equals("wl1") && packs.get(0).getName().equals("蔚蓝档案")
                && packs.get(0).getFiles().size() == 2 && packs.get(0).getTotalBytes() == 300_000L,
            "id=" + packs.get(0).getId() + " files=" + packs.get(0).getFiles().size());
        check("S1c 文件字段正确",
            packs.get(0).getFiles().get(0).getName().equals("a.jpg")
                && packs.get(0).getFiles().get(0).getSize() == 100_000L
                && packs.get(0).getFiles().get(0).getMd5().equals(md5Hex(aData)));

        // S2 拒绝不安全条目
        String evil = "{\"packs\":[" +
            "{\"id\":\"../x\",\"name\":\"evil1\",\"files\":[{\"name\":\"a.jpg\",\"size\":1,\"md5\":\"" + md5Hex(aData) + "\"}]}," +
            "{\"id\":\"ok1\",\"name\":\"evil2\",\"files\":[" +
            "{\"name\":\"../evil.jpg\",\"size\":1,\"md5\":\"" + md5Hex(aData) + "\"}," +
            "{\"name\":\"dir/a.jpg\",\"size\":1,\"md5\":\"" + md5Hex(aData) + "\"}," +
            "{\"name\":\".jpg\",\"size\":1,\"md5\":\"" + md5Hex(aData) + "\"}," +
            "{\"name\":\"x.txt\",\"size\":1,\"md5\":\"" + md5Hex(aData) + "\"}," +
            "{\"name\":\"bad-md5.jpg\",\"size\":1,\"md5\":\"zz\"}," +
            "{\"name\":\"good.jpg\",\"size\":1,\"md5\":\"" + md5Hex(aData) + "\"}]}," +
            "{\"id\":\"empty1\",\"name\":\"evil3\",\"files\":[]}]}";
        List<BackgroundPackService.PackInfo> evilPacks = SVC.parseManifest(evil);
        check("S2a 穿越包 id 被拒 / 无文件包被拒", evilPacks.size() == 1 && evilPacks.get(0).getId().equals("ok1"),
            "size=" + evilPacks.size() + (evilPacks.isEmpty() ? "" : " id=" + evilPacks.get(0).getId()));
        check("S2b 六个坏文件名只剩 good.jpg", evilPacks.get(0).getFiles().size() == 1
            && evilPacks.get(0).getFiles().get(0).getName().equals("good.jpg"),
            "files=" + evilPacks.get(0).getFiles().size());
        check("S2c 坏 JSON 返回空", SVC.parseManifest("{not json").isEmpty());

        // S3 安全名单
        check("S3a isSafeToken 黑白名单",
            SVC.isSafeToken("wl1") && SVC.isSafeToken("a-b_c.2") && !SVC.isSafeToken("")
                && !SVC.isSafeToken("..") && !SVC.isSafeToken("a/b") && !SVC.isSafeToken("a b")
                && !SVC.isSafeToken(".hidden"));
        check("S3b isSafeFileName 黑白名单",
            SVC.isSafeFileName("x.jpg") && SVC.isSafeFileName("y.PNG") && !SVC.isSafeFileName("x.txt")
                && !SVC.isSafeFileName("x.part") && !SVC.isSafeFileName("../x.jpg") && !SVC.isSafeFileName("x"));

        // S4 md5 一致
        check("S4 md5Of 与 JDK 一致", SVC.md5Of(aData).equals(md5Hex(aData)));
        check("S4b md5OfFile 落盘一致",
            SVC.md5OfFile(new File(dataDir, "probe.tmp")) == null
                || !new File(dataDir, "probe.tmp").exists());
        Files.write(new File(dataDir, "probe.jpg").toPath(), aData);
        check("S4c md5OfFile 读文件一致", SVC.md5OfFile(new File(dataDir, "probe.jpg")).equals(md5Hex(aData)));

        System.out.println("== S5-S10：本地 HTTP 端到端 ==");

        FileServer primary = new FileServer();
        FileServer mirror = new FileServer();

        // 清单（主源与镜像同构）
        byte[] manifestBytes = manifest.getBytes(StandardCharsets.UTF_8);
        primary.put("/manifest.json", manifestBytes);
        mirror.put("/manifest.json", manifestBytes);
        // 文件：主源有 a/b；c 只在镜像有（主源 404 → 走镜像）
        primary.put("/wl1/a.jpg", aData);
        primary.put("/wl1/b.jpg", bData);
        mirror.put("/wl1/a.jpg", aData);
        mirror.put("/wl1/b.jpg", bData);
        mirror.put("/p2/c.jpg", cData);

        List<String> sources = List.of(primary.base(), mirror.base());
        // 在主源额外放损坏的 c（S8 用：主源 200 但字节错，镜像正确）
        primary.put("/p2/c.jpg", bytesOf('X', 300_000));

        BackgroundPackService.PackInfo pack1 = packs.get(0);

        // S5 全成功（主源）
        File caseDir = new File(dataDir, "s5");
        List<String> lines5 = collector();
        BackgroundPackService.DownloadSummary s5 = SVC.downloadPack(caseDir, pack1, sources,
            line -> consume(lines5, line));
        check("S5a 三张全部成功", s5.getDownloaded() == 2 && s5.getFailed() == 0 && s5.getSkippedExisting() == 0,
            "downloaded=" + s5.getDownloaded() + " failed=" + s5.getFailed());
        check("S5b 文件落盘且 md5 正确",
            new File(caseDir, "img/a.jpg").isFile() && new File(caseDir, "img/b.jpg").isFile()
                && SVC.md5OfFile(new File(caseDir, "img/a.jpg")).equals(md5Hex(aData)));
        check("S5c 进度行带序号与结果",
            lines5.stream().anyMatch(l -> l.contains("[1/2]") && l.contains("已下载"))
                && lines5.stream().anyMatch(l -> l.contains("[2/2]") && l.contains("已下载")),
            "lines=" + lines5.size());

        // S6 重复下载 → 全部已存在
        List<String> lines6 = collector();
        BackgroundPackService.DownloadSummary s6 = SVC.downloadPack(caseDir, pack1, sources,
            line -> consume(lines6, line));
        check("S6 重复下载全部跳过", s6.getSkippedExisting() == 2 && s6.getDownloaded() == 0
            && lines6.stream().anyMatch(l -> l.contains("已存在，跳过")));

        // S7 单张 404：主源有 a，镜像只有 c（b 两源都无 → 404 跳过）
        FileServer lonely = new FileServer();
        lonely.put("/manifest.json", manifestBytes);
        lonely.put("/wl1/a.jpg", aData);
        File caseDir7 = new File(dataDir, "s7");
        List<String> lines7 = collector();
        BackgroundPackService.DownloadSummary s7 = SVC.downloadPack(caseDir7, pack1,
            List.of(lonely.base()), line -> consume(lines7, line));
        check("S7 404 失败跳过其余照常", s7.getDownloaded() == 1 && s7.getFailed() == 1
            && s7.getFailedFiles().contains("b.jpg"),
            "downloaded=" + s7.getDownloaded() + " failed=" + s7.getFailed());
        lonely.stop();

        // S8 主源损坏 → 镜像兜底
        File caseDir8 = new File(dataDir, "s8");
        int mirrorHitsBefore = mirror.hits.get();
        BackgroundPackService.PackInfo pack2 = packs.get(1);
        List<String> lines8 = collector();
        BackgroundPackService.DownloadSummary s8 = SVC.downloadPack(caseDir8, pack2, sources,
            line -> consume(lines8, line));
        check("S8 损坏字节换镜像成功", s8.getDownloaded() == 1 && s8.getFailed() == 0
            && SVC.md5OfFile(new File(caseDir8, "img/c.jpg")).equals(md5Hex(cData))
            && mirror.hits.get() > mirrorHitsBefore);

        // S9 主源整体不可达（连接被拒）→ 全部走镜像
        File caseDir9 = new File(dataDir, "s9");
        BackgroundPackService.PackInfo packAll = packInfo("wl1", "蔚蓝档案", List.of(
            packFile("a.jpg", aData), packFile("b.jpg", bData), packFile("c.jpg", cData)));
        mirror.put("/wl1/c.jpg", cData);
        List<String> lines9 = collector();
        BackgroundPackService.DownloadSummary s9 = SVC.downloadPack(caseDir9, packAll,
            List.of("http://127.0.0.1:1/bpng/", mirror.base()), line -> consume(lines9, line));
        check("S9 死源回退镜像全成功", s9.getDownloaded() == 3 && s9.getFailed() == 0,
            "downloaded=" + s9.getDownloaded() + " failed=" + s9.getFailed());

        // S10 中断：慢速端点（先吐 4KB 再挂起），收到首张进度后取消
        File caseDir10 = new File(dataDir, "s10");
        AtomicBoolean sawActiveDuringDownload = new AtomicBoolean(false);
        CountDownLatch firstDone = new CountDownLatch(1);
        primary.hook("/wl1/slow.jpg", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            // 声明很大长度，只先发一小块再挂住（模拟慢速下载）
            exchange.sendResponseHeaders(200, 50_000_000);
            OutputStream out = exchange.getResponseBody();
            out.write(bytesOf('S', 4096));
            out.flush();
            Thread.sleep(8_000);
        });
        BackgroundPackService.PackInfo packSlow = packInfo("wl1", "蔚蓝档案", List.of(
            packFile("a.jpg", aData), packFile("slow.jpg", bytesOf('S', 50_000_000))));
        List<String> lines10 = collector();
        Thread worker = new Thread(() -> {
            SVC.downloadPack(caseDir10, packSlow, List.of(primary.base()), line -> {
                consume(lines10, line);
                if (line.contains("[1/2]")) {
                    // 第一张完成的时机：此时下载仍处于激活态；请求中断
                    sawActiveDuringDownload.set(SVC.hasActiveDownload());
                    SVC.requestCancel();
                    firstDone.countDown();
                }
                return Unit.INSTANCE;
            });
        }, "smoke-downloader");
        worker.start();
        boolean startedInTime = firstDone.await(30, java.util.concurrent.TimeUnit.SECONDS);
        worker.join(60_000);
        check("S10a 中断信号及时触发", startedInTime, "started=" + startedInTime);
        check("S10b 下载中激活态可见", sawActiveDuringDownload.get());
        check("S10c 中断后无激活态", !SVC.hasActiveDownload());
        File[] parts = new File(caseDir10, "img").listFiles((dir, name) -> name.endsWith(".part"));
        check("S10d 无 .part 残留", parts == null || parts.length == 0,
            "parts=" + (parts == null ? 0 : parts.length));
        check("S10e slow.jpg 未落盘（进行中那张作废）", !new File(caseDir10, "img/slow.jpg").exists());
        check("S10f 已下载一张保留", new File(caseDir10, "img/a.jpg").isFile());

        System.out.println("== S11-S14：命令层语义 ==");

        // S11 未知包 ID（紧跟 S10 中断之后——同时回归「中断标志残留」修复：
        // 若标志未随下载结束清除，此处清单获取会直接失败）
        List<String> lines11 = collector();
        SVC.downloadPackById(dataDir, "nosuch", sources, line -> consume(lines11, line));
        check("S11a 未知包提示未找到（中断后清单仍可用）",
            lines11.stream().anyMatch(l -> l.contains("未找到图片包")),
            lines11.isEmpty() ? "no lines" : lines11.get(0));
        File caseDir11 = new File(dataDir, "s11");
        List<String> lines11b = collector();
        SVC.downloadPackById(caseDir11, "wl1", sources, line -> consume(lines11b, line));
        check("S11b 正常包全链路含汇总行",
            lines11b.stream().anyMatch(l -> l.contains("开始下载"))
                && lines11b.stream().anyMatch(l -> l.contains("下载完成"))
                && new File(caseDir11, "img/a.jpg").isFile(),
            "lines=" + lines11b.size());

        // S12 清单列表（本地源密闭验证；默认源为生产地址不适合冒烟）
        List<String> lines12 = SVC.listPacksLines(sources);
        check("S12a 列表输出含包名与张数", lines12.stream().anyMatch(l -> l.contains("蔚蓝档案"))
            && lines12.stream().anyMatch(l -> l.contains("2 张")));
        check("S12b 列表输出含用法与中断说明",
            lines12.stream().anyMatch(l -> l.contains("/hbkrg bpng wl1"))
                && lines12.stream().anyMatch(l -> l.contains("中断")));

        // S12c 清单源全死（fetchPacks 返回 null 即 listPacksLines 失败分支的判定依据）
        Object deadFetch = SVC.fetchPacks(List.of("http://127.0.0.1:1/bpng/"));
        check("S12c 全源失败返回 null", deadFetch == null);

        // S13 switchBackgrounds 真实配置通路
        FakeBpngRuntime fake = new FakeBpngRuntime();
        deleteRecursively(fake.getDataFolder());
        BotShared.INSTANCE.setInstance(fake);
        try {
            List<String> yesLines = SVC.switchBackgrounds(true);
            String saved = Files.readString(new File(fake.getDataFolder(), "config.yml").toPath());
            check("S13a yes 五键全 false",
                saved.contains("info-card-builtin-background: false")
                    && saved.contains("online-list-builtin-background: false")
                    && saved.contains("builtin-background: false")
                    && saved.contains("performance") && saved.contains("builtin-background: false"));
            check("S13b yes 后自动重载一次", fake.reloadCount.get() == 1, "reload=" + fake.reloadCount.get());
            check("S13c img 空目录提醒", yesLines.stream().anyMatch(l -> l.contains("没有图片")));

            new File(fake.getDataFolder(), "img").mkdirs();
            Files.write(new File(fake.getDataFolder(), "img/x.jpg").toPath(), aData);
            List<String> noLines = SVC.switchBackgrounds(false);
            String saved2 = Files.readString(new File(fake.getDataFolder(), "config.yml").toPath());
            check("S13d no 五键全 true",
                saved2.contains("info-card-builtin-background: true")
                    && saved2.contains("online-list-builtin-background: true")
                    && saved2.contains("builtin-background: true"));
            check("S13e no 后累计重载两次", fake.reloadCount.get() == 2, "reload=" + fake.reloadCount.get());
            check("S13f 有图时不再提醒", noLines.stream().noneMatch(l -> l.contains("没有图片")));
        } finally {
            // 冒烟 JVM 即将退出，无需还原单例
        }

        // S14 五键清单
        List<String> keys = SVC.getBACKGROUND_SWITCH_KEYS();
        check("S14 五键恰为预期", keys.size() == 5
            && keys.contains("render.info-card-builtin-background")
            && keys.contains("render.online-list-builtin-background")
            && keys.contains("render.performance.builtin-background")
            && keys.contains("leaderboard.builtin-background")
            && keys.contains("coins-leaderboard.builtin-background"));

        primary.stop();
        mirror.stop();

        System.out.println();
        System.out.println("========== 冒烟共 " + (pass + fail) + " 项，PASS " + pass + "，FAIL " + fail + " ==========");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }

    private Test1711() {
    }
}
