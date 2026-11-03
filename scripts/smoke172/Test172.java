package scripts.smoke172;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import cn.kerong.bot.provider.BotShared;
import cn.kerong.bot.tools.ConsoleNoiseFilter;
import cn.kerong.penguin.spigot.qqbind.EconomyRewardService;
import cn.kerong.penguin.spigot.render.BackgroundPackService;
import cn.kerong.penguin.spigot.render.RenderSettings;

/**
 * 1.7.2 冒烟（独立 JVM，classpath = NF 分发 JAR + slf4j-api + slf4j-nop）。
 *
 * 覆盖：
 *  S 组  manifest 动态字段（count / bytes 声明值优先，旧清单回退 files 统计）
 *  H 组  本地 HttpServer 端到端：listPacksLines 渲染声明张数与体积；单源下载
 *  ID 组 image-download.enabled 开关（默认开 / 关闭后 false + 拦截文案）
 *  GY 组 灰度配置（默认 142 / 106；配置覆盖生效）
 *  EC 组 经济检测优雅跳过（无插件 JVM 全部 false 不抛异常）+ points-reward 配置读取
 *  NZ 组 ConsoleNoiseFilter 黑名单（MediaSource / Not Secure / 正常行放行）
 */
public final class Test172 {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean cond) { check(name, cond, ""); } 
    static void check(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : " — " + detail));
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : " — " + detail));
        }
    }

    static String md5(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("MD5").digest(data)) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 生成一张最小合法 PNG（1×1 灰像素，手动拼字节保证可解码）。 */
    static byte[] tinyPng() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==");
    }

    public static void main(String[] args) throws Exception {
        File dataDir = new File(System.getProperty("java.io.tmpdir"), "smoke172-work");
        dataDir.mkdirs();

        System.out.println("== S 组：manifest 动态字段 ==");

        // 声明 count/bytes 与 files 实际统计不一致 → 展示取声明值
        String manifestDeclared = "{\"packs\":[{\"id\":\"t1\",\"name\":\"测试包A\",\"description\":\"desc\","
                + "\"count\":29,\"bytes\":60926352,"
                + "\"files\":[{\"name\":\"a.png\",\"size\":100,\"md5\":\"" + "0".repeat(32) + "\"}]}]}";
        List<?> packsDeclared = BackgroundPackService.INSTANCE.parseManifest(manifestDeclared);
        check("S1 声明 count/bytes 优先（29 张 / 60926352 字节）",
                packsDeclared.size() == 1 && packsDeclared.get(0).toString().contains("declaredCount=29")
                        && packsDeclared.get(0).toString().contains("declaredBytes=60926352"),
                String.valueOf(packsDeclared.get(0)));

        // 旧清单（无 count/bytes）→ 回退按 files 统计
        String manifestOld = "{\"packs\":[{\"id\":\"t2\",\"name\":\"测试包B\","
                + "\"files\":[{\"name\":\"a.png\",\"size\":1048576,\"md5\":\"" + "1".repeat(32) + "\"},"
                + "{\"name\":\"b.png\",\"size\":1048576,\"md5\":\"" + "2".repeat(32) + "\"}]}]}";
        List<?> packsOld = BackgroundPackService.INSTANCE.parseManifest(manifestOld);
        Object packOld = packsOld.get(0);
        java.lang.reflect.Method mCount = packOld.getClass().getMethod("getCount");
        java.lang.reflect.Method mBytes = packOld.getClass().getMethod("getTotalBytes");
        check("S2 旧清单回退 files 统计（2 张 / 2MB）",
                packsOld.size() == 1 && (Integer) mCount.invoke(packOld) == 2
                        && (Long) mBytes.invoke(packOld) == 2097152L);

        // 下载源唯一（默认常量）
        List<String> sources = BackgroundPackService.INSTANCE.getDOWNLOAD_SOURCES();
        check("S3 默认下载源只有官网一条", sources.size() == 1 && sources.get(0).endsWith("kerong.xin/penguin/bpng/"),
                sources.toString());

        System.out.println("== H 组：HTTP 端到端 ==");

        byte[] img1 = tinyPng();
        byte[] img2 = tinyPng();
        String mds1 = md5(img1);
        String mds2 = md5(img2);
        String manifestHttp = "{\"packs\":[{\"id\":\"hp\",\"name\":\"端到端包\",\"description\":\"\","
                + "\"count\":7,\"bytes\":73400320,"
                + "\"files\":[{\"name\":\"one.png\",\"size\":" + img1.length + ",\"md5\":\"" + mds1 + "\"},"
                + "{\"name\":\"two.png\",\"size\":" + img2.length + ",\"md5\":\"" + mds2 + "\"}]}]}";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> hits = new ArrayList<>();
        server.createContext("/", (HttpExchange ex) -> {
            String path = ex.getRequestURI().getPath();
            hits.add(path);
            byte[] body;
            if (path.endsWith("manifest.json")) {
                body = manifestHttp.getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith("one.png")) {
                body = img1;
            } else if (path.endsWith("two.png")) {
                body = img2;
            } else {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        List<String> srcList = List.of(base);

        List<String> lines = BackgroundPackService.INSTANCE.listPacksLines(srcList);
        boolean hasDeclared = lines.stream().anyMatch(l -> l.contains("（7 张") && l.contains("70.0 MB"));
        check("H1 列表行渲染声明的张数与体积（7 张 / 70.0 MB）", hasDeclared,
                lines.stream().filter(l -> l.contains("张")).findFirst().orElse(""));

        // 单一源下载（下载 + md5 校验 + 落盘）
        List<String> progress = new ArrayList<>();
        BackgroundPackService.INSTANCE.downloadPackById(dataDir, "hp", srcList, s -> {
            progress.add(s);
            return cn.kerong.shaded.kotlin.Unit.INSTANCE;
        });
        File imgDir = new File(dataDir, "img");
        boolean bothFiles = new File(imgDir, "one.png").isFile() && new File(imgDir, "two.png").isFile();
        check("H2 单源下载完成（两张落盘 + 汇总行）", bothFiles
                        && progress.stream().anyMatch(l -> l.contains("下载完成")),
                progress.size() + " 行进度");

        // 拦截：未知包 ID
        progress.clear();
        BackgroundPackService.INSTANCE.downloadPackById(dataDir, "nope", srcList, s -> {
            progress.add(s);
            return cn.kerong.shaded.kotlin.Unit.INSTANCE;
        });
        check("H3 未知包 ID 如实提示", progress.stream().anyMatch(l -> l.contains("未找到图片包")));
        server.stop(0);

        System.out.println("== ID 组：图片下载开关 ==");

        FakeBpngRuntime fake = new FakeBpngRuntime();
        BotShared.INSTANCE.setInstance(fake);
        check("ID1 默认开启", BackgroundPackService.INSTANCE.isImageDownloadEnabled());
        fake.getConfig().set("image-download.enabled", false);
        check("ID2 配置关闭后 false", !BackgroundPackService.INSTANCE.isImageDownloadEnabled());
        boolean hasText = BackgroundPackService.INSTANCE.imageDownloadDisabledLines().stream()
                .anyMatch(l -> l.contains("管理员已关闭图片下载功能"));
        check("ID3 拦截文案在位", hasText);
        fake.getConfig().set("image-download.enabled", true);

        System.out.println("== GY 组：灰度配置 ==");

        check("GY1 默认 title 142 / item 106",
                RenderSettings.INSTANCE.titleCardGray() == 142 && RenderSettings.INSTANCE.itemCardGray() == 106,
                RenderSettings.INSTANCE.titleCardGray() + "/" + RenderSettings.INSTANCE.itemCardGray());
        fake.getConfig().set("render.title-card-gray", 200);
        fake.getConfig().set("render.item-card-gray", 30);
        check("GY2 配置覆盖生效（200 / 30）",
                RenderSettings.INSTANCE.titleCardGray() == 200 && RenderSettings.INSTANCE.itemCardGray() == 30,
                RenderSettings.INSTANCE.titleCardGray() + "/" + RenderSettings.INSTANCE.itemCardGray());
        fake.getConfig().set("render.title-card-gray", 999);
        fake.getConfig().set("render.item-card-gray", -5);
        check("GY3 越界收敛 0-255（999→255 / -5→0）",
                RenderSettings.INSTANCE.titleCardGray() == 255 && RenderSettings.INSTANCE.itemCardGray() == 0,
                RenderSettings.INSTANCE.titleCardGray() + "/" + RenderSettings.INSTANCE.itemCardGray());
        fake.getConfig().set("render.title-card-gray", null);
        fake.getConfig().set("render.item-card-gray", null);

        System.out.println("== EC 组：经济检测优雅跳过 ==");

        boolean allFalse;
        try {
            allFalse = !EconomyRewardService.INSTANCE.isEconomyAvailable()
                    && !EconomyRewardService.INSTANCE.isPointsAvailable()
                    && !EconomyRewardService.INSTANCE.isCoinsEngineAvailable();
        } catch (Throwable t) {
            allFalse = false;
        }
        check("EC1 无插件 JVM 全部检测 false 且不抛异常", allFalse);
        check("EC2 points-reward 默认 0", EconomyRewardService.INSTANCE.checkinPointsReward() == 0);
        fake.getConfig().set("qq-bind.checkin.points-reward", 50);
        check("EC3 points-reward 配置读取（50）", EconomyRewardService.INSTANCE.checkinPointsReward() == 50);
        fake.getConfig().set("qq-bind.checkin.points-reward", 0);

        System.out.println("== NZ 组：噪音黑名单 ==");

        check("NZ1 [MediaSource] 前缀命中",
                ConsoleNoiseFilter.INSTANCE.isNoise("net.mymod.Logger", "[MediaSource] 布谷鸟时钟 BlockPos{x=3094} 媒体=(空) mode=FIT"));
        check("NZ2 [Not Secure] 前缀命中",
                ConsoleNoiseFilter.INSTANCE.isNoise(null, "[Not Secure] [Server] 1"));
        check("NZ3 logger 名关键字命中（消息不带前缀）",
                ConsoleNoiseFilter.INSTANCE.isNoise("com.xxx.MediaSource", "布谷鸟时钟 媒体=(空)"));
        check("NZ4 正常命令输出放行",
                !ConsoleNoiseFilter.INSTANCE.isNoise("org.bukkit.craftbukkit.CommandDispatcher", "Set the time to 1000"));
        check("NZ5 null 消息放行", !ConsoleNoiseFilter.INSTANCE.isNoise("any.Logger", null));

        System.out.println();
        System.out.println("smoke172 总计 " + (pass + fail) + " 项，通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("smoke172 全部通过");
    }
}
