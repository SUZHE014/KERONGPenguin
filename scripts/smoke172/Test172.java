package scripts.smoke172;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import cn.kerong.penguin.spigot.render.InfoCardAssets;
import cn.kerong.penguin.spigot.render.InfoCardRenderer;
import cn.kerong.penguin.spigot.render.RenderSettings;

/**
 * 1.7.2 冒烟（独立 JVM，classpath = NF 分发 JAR + slf4j-api + slf4j-nop）。
 *
 * 覆盖：
 *  S 组  manifest 动态字段（count / bytes 声明值优先，旧清单回退 files 统计）
 *  H 组  本地 HttpServer 端到端：listPacksLines 渲染声明张数与体积；单源下载
 *  ID 组 image-download.enabled 开关（默认开 / 关闭后 false + 拦截文案）
 *  GY 组 灰度配置（默认 80 / 106；配置覆盖生效 + 标题框/统计面板像素级接线）
 *  AV 组 头像批量获取有界预算（fetchAvatarBatch：空列表秒回 / 超预算到点即回）
 *  EC 组 经济检测优雅跳过（无插件 JVM 全部 false 不抛异常）+ points-reward 配置读取
 *  NZ 组 ConsoleNoiseFilter 黑名单（MediaSource / Not Secure / [QQ 远程命令] / 正常行放行）
 *  CL 组 config.yml 注释保留保存（bpngsz 翻转 / 补键 / 删键 / 未装载跳过 / 损坏保护）
 *  CF 组 v29 → v30 重建（注释保留 + 标题框旧默认 96→80 收敛 + 自定义值保留 + 无备份文件）
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

    /** 统计文本里的注释行数。 */
    static long commentLines(String text) {
        return text.lines().filter(l -> l.trim().startsWith("#")).count();
    }

    /** 区域平均亮度（0-255，步长 4 采样）。 */
    static double avgBrightness(java.awt.image.BufferedImage img, int x0, int y0, int w, int h) {
        long sum = 0;
        long n = 0;
        for (int y = y0; y < y0 + h; y += 4) {
            for (int x = x0; x < x0 + w; x += 4) {
                int rgb = img.getRGB(x, y);
                sum += (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF)) / 3;
                n++;
            }
        }
        return n == 0 ? -1 : (double) sum / n;
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

        System.out.println("== AV 组：头像批量获取有界预算 ==");

        // AV1 空列表：立即返回空 Map（不触碰线程池）
        java.util.Map<String, java.awt.image.BufferedImage> emptyBatch =
                InfoCardAssets.INSTANCE.fetchAvatarBatch(java.util.Collections.emptyList(), 1000L);
        check("AV1 空列表返回空 Map", emptyBatch != null && emptyBatch.isEmpty());

        // AV2 100ms 预算 + 三个缓存必未命中的玩家：到点即回不等网络完成
        //（旧版 invokeAll 硬预算会中断在途下载；新版超预算立即收尾返回）
        long avT0 = System.currentTimeMillis();
        java.util.Map<String, java.awt.image.BufferedImage> bounded =
                InfoCardAssets.INSTANCE.fetchAvatarBatch(java.util.Arrays.asList(
                        new InfoCardAssets.AvatarRequest(null, "smoke_no_such_a1"),
                        new InfoCardAssets.AvatarRequest(null, "smoke_no_such_b2"),
                        new InfoCardAssets.AvatarRequest(null, "smoke_no_such_c3")), 100L);
        long avCost = System.currentTimeMillis() - avT0;
        check("AV2 100ms 预算到点即回（实测 " + avCost + "ms < 3000ms）", avCost < 3000);
        check("AV3 批量返回 Map 不为 null（允许空：占位块口径）", bounded != null);

        System.out.println("== GY 组：灰度配置（默认 80 / 106） ==");

        check("GY1 默认 title 80 / item 106",
                RenderSettings.INSTANCE.titleCardGray() == 80 && RenderSettings.INSTANCE.itemCardGray() == 106,
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
        check("NZ6 [QQ 远程命令] 转发行命中（控制台留档行不回混 QQ 回复）",
                ConsoleNoiseFilter.INSTANCE.isNoise(null, "[QQ 远程命令] Set the time to 1000"));

        System.out.println("== GY 像素组：标题框 / 统计面板接线 ==");

        // 白底背景 + 关模糊：标题框与统计面板的黑度变化不被背景干扰
        java.awt.image.BufferedImage whiteBg =
                new java.awt.image.BufferedImage(900, 760, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D wg = whiteBg.createGraphics();
        wg.setColor(java.awt.Color.WHITE);
        wg.fillRect(0, 0, 900, 760);
        wg.dispose();
        java.util.List<InfoCardRenderer.CardItem> items = new java.util.ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            items.add(new InfoCardRenderer.CardItem("项目" + i, String.valueOf(i)));
        }
        fake.getConfig().set("render.blur-strength", 0);
        fake.getConfig().set("render.title-card-gray", 255);
        fake.getConfig().set("render.item-card-gray", 0);
        byte[] pngA = InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Smoke", items, null, whiteBg));
        java.awt.image.BufferedImage imgA =
                javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(pngA));
        double heroDark = avgBrightness(imgA, 24, 24, 460, 196);
        double statsBright = avgBrightness(imgA, 24, 352, 850, 360);

        fake.getConfig().set("render.title-card-gray", 0);
        fake.getConfig().set("render.item-card-gray", 255);
        byte[] pngB = InfoCardRenderer.INSTANCE.render(
                new InfoCardRenderer.CardData("Smoke", items, null, whiteBg));
        java.awt.image.BufferedImage imgB =
                javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(pngB));
        double heroBright = avgBrightness(imgB, 24, 24, 460, 196);
        double statsDark = avgBrightness(imgB, 24, 352, 850, 360);
        fake.getConfig().set("render.title-card-gray", null);
        fake.getConfig().set("render.item-card-gray", null);
        fake.getConfig().set("render.blur-strength", null);

        check("GY4 标题框随 title-card-gray 变化（255 暗 " + String.format("%.0f", heroDark)
                        + " / 0 亮 " + String.format("%.0f", heroBright) + "）",
                heroDark < 80 && heroBright > 200);
        check("GY5 统计面板随 item-card-gray 变化（0 亮 " + String.format("%.0f", statsBright)
                        + " / 255 暗 " + String.format("%.0f", statsDark) + "）",
                statsBright > 180 && statsDark < 80);

        System.out.println("== CL 组：config.yml 注释保留保存 ==");

        File cfgFile = new File(fake.getDataFolder(), "config.yml");
        String template = new String(
                FakeBpngRuntime.class.getClassLoader().getResourceAsStream("config.yml").readAllBytes(),
                StandardCharsets.UTF_8);

        // CL1 bpngsz 场景：翻转五个背景开关后保存——注释与值都在
        Files.write(cfgFile.toPath(), template.getBytes(StandardCharsets.UTF_8));
        fake.reloadConfig();
        for (String key : new String[]{"render.info-card-builtin-background",
                "render.online-list-builtin-background", "render.performance.builtin-background",
                "leaderboard.builtin-background", "coins-leaderboard.builtin-background"}) {
            fake.getConfig().set(key, false);
        }
        fake.saveConfig();
        String after1 = Files.readString(cfgFile.toPath(), StandardCharsets.UTF_8);
        check("CL1 bpngsz 保存后注释保留（" + commentLines(after1) + " 行 > 100）",
                commentLines(after1) > 100);
        check("CL1b 五个背景开关全部写回（builtin-background: false × 5）",
                after1.split("builtin-background: false", -1).length - 1 == 5);
        fake.reloadConfig();
        check("CL1c 写回值可重新装载（roundtrip 一致）",
                !fake.getConfig().getBoolean("render.info-card-builtin-background", true)
                        && !fake.getConfig().getBoolean("coins-leaderboard.builtin-background", true)
                        && "30".equals(String.valueOf(fake.getConfig().getInt("config-version", 0))));

        // CL2 补键场景（fillMissing）：删掉 blur-strength 行，保存后补回
        String trimmed = template.replace("  blur-strength: 50\n", "");
        Files.write(cfgFile.toPath(), trimmed.getBytes(StandardCharsets.UTF_8));
        fake.reloadConfig();
        fake.getConfig().set("render.blur-strength", 77);
        fake.saveConfig();
        String after2 = Files.readString(cfgFile.toPath(), StandardCharsets.UTF_8);
        check("CL2 补键保存后注释保留 + 键写入（" + commentLines(after2) + " 行注释）",
                after2.contains("blur-strength: 77") && commentLines(after2) > 100);

        // CL3 删键场景（废弃键清理）：附加旧段后整段置 null
        String withAgent = template + "\nagent:\n  mode: \"on\"\n  # 旧段专属注释\n  key: \"v\"\n";
        Files.write(cfgFile.toPath(), withAgent.getBytes(StandardCharsets.UTF_8));
        fake.reloadConfig();
        fake.getConfig().set("agent", null);
        fake.saveConfig();
        String after3 = Files.readString(cfgFile.toPath(), StandardCharsets.UTF_8);
        check("CL3 删键保存后键与专属注释消失 + 其余注释保留",
                !after3.contains("mode: \"on\"") && !after3.contains("旧段专属注释")
                        && commentLines(after3) > 100);

        // CL4 未装载保护：新实例从未 reloadConfig，文件存在时保存跳过
        FakeBpngRuntime virgin = new FakeBpngRuntime();
        String before4 = Files.readString(cfgFile.toPath(), StandardCharsets.UTF_8);
        virgin.saveConfig();
        check("CL4 未装载时跳过保存（文件分毫未动）",
                Files.readString(cfgFile.toPath(), StandardCharsets.UTF_8).equals(before4));

        // CL5 损坏保护：装载失败后保存跳过，坏文件不被内存旧树覆盖
        Files.write(cfgFile.toPath(), "bot: \"unterminated\n".getBytes(StandardCharsets.UTF_8));
        boolean loadThrew = false;
        try {
            fake.reloadConfig();
        } catch (Throwable expected) {
            loadThrew = true;
        }
        fake.saveConfig();
        check("CL5 损坏文件装载抛出且保存跳过（原样保留）",
                loadThrew && Files.readString(cfgFile.toPath(), StandardCharsets.UTF_8).contains("unterminated"));

        System.out.println("== CF 组：v29 → v30 重建 ==");

        // 旧版 1.7.2 磁盘配置：v29 + 旧默认 96 + 用户自定义值
        String v29 = template.replace("config-version: 30", "config-version: 29")
                .replace("title-card-gray: 80", "title-card-gray: 96")
                .replace("item-card-gray: 106", "item-card-gray: 150")
                .replace("serverName: \"KERONGPenguin\"", "serverName: \"MyCustomServer\"");
        Files.write(cfgFile.toPath(), v29.getBytes(StandardCharsets.UTF_8));
        // 清掉历史运行可能残留的备份文件，保证「无备份生成」断言干净
        new File(fake.getDataFolder(), "config.yml.bak").delete();
        Class<?> rebuilderCls = Class.forName("cn.kerong.penguin.spigot.manager.ConfigFileRebuilder");
        Object rebuilder = rebuilderCls.getField("INSTANCE").get(null);
        boolean rebuilt = (Boolean) rebuilderCls
                .getMethod("rebuildIfNeeded", org.bukkit.plugin.java.JavaPlugin.class, int.class, List.class, List.class)
                .invoke(rebuilder, fake, 30, java.util.Collections.emptyList(), java.util.Collections.emptyList());
        check("CF1 v29→v30 重建执行成功", rebuilt);
        String out = Files.readString(cfgFile.toPath(), StandardCharsets.UTF_8);
        check("CF2 版本 30 + 注释完整（" + commentLines(out) + " 行 > 150）",
                out.contains("config-version: 30") && commentLines(out) > 150);
        check("CF3 标题框旧默认 96 自动收敛为 80", out.contains("title-card-gray: 80"));
        check("CF4 用户自定义值保留（serverName / item-card-gray 150）",
                out.contains("MyCustomServer") && out.contains("item-card-gray: 150"));
        check("CF5 不再生成 config.yml.bak 备份文件",
                !new File(fake.getDataFolder(), "config.yml.bak").exists());
        fake.reloadConfig();
        check("CF6 重建结果可装载（收敛值 80 可读）",
                fake.getConfig().getInt("render.title-card-gray", 0) == 80
                        && fake.getConfig().getInt("render.item-card-gray", 0) == 150);

        System.out.println();
        System.out.println("smoke172 总计 " + (pass + fail) + " 项，通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("smoke172 全部通过");
    }
}
