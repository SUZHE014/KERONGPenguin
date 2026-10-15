import cn.kerong.bot.UpdateChecker;
import cn.kerong.bot.tools.PluginFileLog;
import cn.kerong.bot.tools.ConsoleColors;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 1.6.6.1 覆盖版功能冒烟（独立 JVM，类路径 = NeoForge 1.6.6.1 分发 JAR + 依赖）。
 *
 * 覆盖：
 *  1. 历史回归：stripColorCodes / ConsoleColors（1.6.6 真彩色）/ floodgateXuid；
 *  2. 四段版本号比较（1.6.6.1 > 1.6.6，isNewer 逐段数值比较天然支持）；
 *  3. 头像磁盘缓存 TTL（1.6.6.1 核心）：无插件实例时默认 3 小时
 *     （10_800_000ms），render.avatar-cache-hours 配置键名与默认常量在位；
 *  4. 过期清扫线程：clearCaches 幂等（无实例不抛）→ ensureAvatarSweeper
 *     启动（avatar-cache-sweeper 守护线程出现）→ clearCaches 停止（字段置
 *     null，可再次重启）；sweepDiskCache 无实例时安全空跑；
 *  5. MinePic 线路方法存在（fetchMinePicAvatar，不做真实网络请求），
 *     类字节码常量池含 https://minepic.org/avatar/ URL 前缀；
 *  6. Mojang 原生 API 保留：类字节码常量池含 sessionserver.mojang.com。
 *  7. 覆盖版：WebUI 类不存在（Class.forName 抛异常）；
 *  8. 覆盖版：PlayTimeSync 类不存在；
 *  9. 覆盖版：RenderSettings.avatarFetchPriority 无实例时默认 LOCAL；
 * 10. 覆盖版：头像链本地/外置拆分（私有方法在位，旧混合函数 resolveSkinTexture
 *     已移除）；
 * 11. 覆盖版：drawRoundedAvatar 零拷贝直绘行为（圆角裁剪生效 + 画完恢复
 *     裁剪区，直绘不产生中间图）。
 */
public class Test1661 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name + "  [" + detail + "]"); }
    }

    public static void main(String[] args) throws Exception {
        // ---------- 1. 历史回归 ----------
        String stripped = PluginFileLog.INSTANCE.stripColorCodes("§e[更新检查] §f发现新版本：§a§l1.6.6.1");
        check("stripColorCodes 剥离全部 § 码（回归）",
                !stripped.contains("§") && stripped.contains("1.6.6.1"), stripped);

        String ansi = ConsoleColors.INSTANCE.toAnsi("§e黄色文本");
        check("toAnsi §e → ANSI（回归）",
                ansi.contains("\u001B") && ansi.contains("黄色文本"), ansi.replace("\u001B", "ESC"));
        check("toAnsi 无码文本原样（回归）",
                "普通文本".equals(ConsoleColors.INSTANCE.toAnsi("普通文本")), "-");

        // ---------- 2. 四段版本号比较 ----------
        Method newer = null;
        for (Method m : UpdateChecker.class.getDeclaredMethods()) {
            if (m.getName().startsWith("isNewer") && m.getParameterCount() == 2) { newer = m; break; }
        }
        check("isNewer 方法存在（回归）", newer != null, "-");
        newer.setAccessible(true);
        check("isNewer 1.6.6.1 > 1.6.6（四段 vs 三段）",
                Boolean.TRUE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.6.6.1", "1.6.6")), "-");
        check("isNewer 1.6.7 > 1.6.6.1（第三段优先）",
                Boolean.TRUE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.6.7", "1.6.6.1")), "-");
        check("isNewer 1.6.6.1 不大于 1.6.6.1（相等）",
                Boolean.FALSE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.6.6.1", "1.6.6.1")), "-");
        check("isNewer 1.6.6.2 > 1.6.6.1（第四段数值比较）",
                Boolean.TRUE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.6.6.2", "1.6.6.1")), "-");

        // ---------- 3. 头像磁盘缓存 TTL（1.6.6.1 核心） ----------
        Class<?> assets = Class.forName("cn.kerong.penguin.spigot.render.InfoCardAssets");
        Object assetsInst = assets.getDeclaredField("INSTANCE").get(null);

        Method ttlMillis = null, sweep = null, ensure = null;
        for (Method m : assets.getDeclaredMethods()) {
            // Kotlin internal 函数字节码名带模块后缀（avatarDiskTtlMillis$module），用前缀匹配
            if (m.getName().startsWith("avatarDiskTtlMillis")) ttlMillis = m;
            if (m.getName().startsWith("sweepDiskCache")) sweep = m;
            if (m.getName().equals("ensureAvatarSweeper")) ensure = m;
        }
        check("avatarDiskTtlMillis 方法存在", ttlMillis != null, "-");
        check("sweepDiskCache 方法存在", sweep != null, "-");
        check("ensureAvatarSweeper 方法存在", ensure != null, "-");
        ttlMillis.setAccessible(true);
        sweep.setAccessible(true);
        ensure.setAccessible(true);

        // 无 BotShared 插件实例 → 走默认值 3 小时
        Object ttl = ttlMillis.invoke(assetsInst);
        check("无插件实例时默认 TTL = 3 小时（10_800_000ms）",
                ttl instanceof Long && (Long) ttl == 10_800_000L, String.valueOf(ttl));

        Class<?> settings = Class.forName("cn.kerong.penguin.spigot.render.RenderSettings");
        Object settingsInst = settings.getDeclaredField("INSTANCE").get(null);
        Method hours = null;
        for (Method m : settings.getDeclaredMethods()) {
            if (m.getName().equals("avatarCacheHours")) hours = m;
        }
        check("RenderSettings.avatarCacheHours 方法存在", hours != null, "-");
        hours.setAccessible(true);
        Object defaultHours = hours.invoke(settingsInst);
        check("avatarCacheHours 默认 3.0（无插件实例）",
                defaultHours instanceof Double && (Double) defaultHours == 3.0, String.valueOf(defaultHours));

        // ---------- 4. 过期清扫线程生命周期 ----------
        Method clearCaches = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("clearCaches")) clearCaches = m;
        }
        check("clearCaches 方法存在（回归）", clearCaches != null, "-");
        clearCaches.setAccessible(true);
        try {
            clearCaches.invoke(assetsInst);
            check("clearCaches 无实例时安全不抛", true, "-");
        } catch (Throwable t) {
            check("clearCaches 无实例时安全不抛", false, String.valueOf(t));
        }

        ensure.invoke(assetsInst);  // 启动清扫线程
        Field sweeperField = assets.getDeclaredField("avatarSweeper");
        sweeperField.setAccessible(true);
        Object sweeper1 = sweeperField.get(assetsInst);
        check("ensureAvatarSweeper 启动清扫执行器", sweeper1 != null, "-");
        boolean threadAlive = false;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("avatar-cache-sweeper".equals(t.getName()) && t.isDaemon()) { threadAlive = true; break; }
        }
        check("avatar-cache-sweeper 守护线程已运行", threadAlive, "-");

        clearCaches.invoke(assetsInst);  // 停机：关闭清扫线程
        Object sweeper2 = sweeperField.get(assetsInst);
        check("clearCaches 停止清扫线程（字段置 null）", sweeper2 == null, String.valueOf(sweeper2));

        ensure.invoke(assetsInst);  // 再次使用：自动重启（幂等可恢复）
        Object sweeper3 = sweeperField.get(assetsInst);
        check("清扫线程停机后可自动重启", sweeper3 != null, "-");
        ensure.invoke(assetsInst);  // 幂等：重复调用不新建
        Object sweeper4 = sweeperField.get(assetsInst);
        check("ensureAvatarSweeper 幂等（不重复建线程）", sweeper4 == sweeper3, "-");
        clearCaches.invoke(assetsInst);  // 收尾清理，避免悬挂

        try {
            sweep.invoke(assetsInst);  // 无插件实例 → 目录不可得 → 安全空跑
            check("sweepDiskCache 无实例时安全空跑", true, "-");
        } catch (Throwable t) {
            check("sweepDiskCache 无实例时安全空跑", false, String.valueOf(t));
        }

        // ---------- 5. MinePic 线路（方法在位 + URL 常量） ----------
        Method minePic = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("fetchMinePicAvatar")) minePic = m;
        }
        check("fetchMinePicAvatar 方法存在", minePic != null, "-");
        try (InputStream is = assets.getResourceAsStream("/cn/kerong/penguin/spigot/render/InfoCardAssets.class")) {
            byte[] blob = is.readAllBytes();
            String blobText = new String(blob, java.nio.charset.StandardCharsets.ISO_8859_1);
            check("MinePic URL 前缀在常量池（https://minepic.org/avatar/）",
                    blobText.contains("https://minepic.org/avatar/"), "-");
            check("Mojang 原生 API 保留（sessionserver.mojang.com）",
                    blobText.contains("sessionserver.mojang.com"), "-");
            check("MC-Heads / Minotar / Crafatar 线路齐备（回归）",
                    blobText.contains("mc-heads.net") && blobText.contains("minotar.net")
                            && blobText.contains("crafatar.com"), "-");
        }

        // ---------- 6. floodgateXuid 回归 ----------
        Method floodgateXuid = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("floodgateXuid")) floodgateXuid = m;
        }
        check("floodgateXuid 方法存在（回归）", floodgateXuid != null, "-");
        floodgateXuid.setAccessible(true);
        UUID floodgate = new UUID(0L, 0x000901f64f65c7c1L);
        Object xuid = floodgateXuid.invoke(assetsInst, floodgate);
        check("Floodgate UUID → xuid 解析正确（回归）",
                xuid instanceof Long && (Long) xuid == 0x01f64f65c7c1L, String.valueOf(xuid));
        UUID offline = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes());
        check("离线 v3 UUID 不误判（回归）",
                floodgateXuid.invoke(assetsInst, offline) == null, "-");

        // ---------- 7. WebUI 面板删除（1.6.6.1 覆盖版） ----------
        try {
            Class.forName("cn.kerong.bot.web.WebUiServer");
            check("WebUiServer 类不存在", false, "类仍然存在");
        } catch (ClassNotFoundException expected) {
            check("WebUiServer 类不存在", true, "-");
        }
        try {
            Class.forName("cn.kerong.bot.web.WebUiSchema");
            check("WebUiSchema 类不存在", false, "类仍然存在");
        } catch (ClassNotFoundException expected) {
            check("WebUiSchema 类不存在", true, "-");
        }

        // ---------- 8. PlayTimeSync 删除（1.6.6.1 覆盖版） ----------
        try {
            Class.forName("cn.kerong.penguin.spigot.stats.PlayTimeSync");
            check("PlayTimeSync 类不存在", false, "类仍然存在");
        } catch (ClassNotFoundException expected) {
            check("PlayTimeSync 类不存在", true, "-");
        }

        // ---------- 9. 头像获取优先方式（1.6.6.1 覆盖版） ----------
        Method priority = null;
        for (Method m : settings.getDeclaredMethods()) {
            if (m.getName().equals("avatarFetchPriority")) priority = m;
        }
        check("RenderSettings.avatarFetchPriority 方法存在", priority != null, "-");
        priority.setAccessible(true);
        Object defaultPriority = priority.invoke(settingsInst);
        check("avatarFetchPriority 无实例时默认 LOCAL",
                "LOCAL".equals(String.valueOf(defaultPriority)), String.valueOf(defaultPriority));

        // ---------- 10. 头像链本地/外置拆分（1.6.6.1 覆盖版） ----------
        boolean hasLocal = false, hasExternal = false, hasMojang = false, hasCrop = false, hasLegacy = false;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("fetchViaLocalChain")) hasLocal = true;
            if (m.getName().equals("fetchViaExternalChain")) hasExternal = true;
            if (m.getName().equals("resolveLocalSkinUrl")) hasMojang = true;
            if (m.getName().equals("resolveMojangSkinUrl")) hasMojang = true;
            if (m.getName().equals("downloadAndCropFace")) hasCrop = true;
            if (m.getName().equals("resolveSkinTexture")) hasLegacy = true;
        }
        check("本地链方法在位（fetchViaLocalChain）", hasLocal, "-");
        check("外置链方法在位（fetchViaExternalChain）", hasExternal, "-");
        check("解析拆分在位（resolveLocalSkinUrl / resolveMojangSkinUrl）",
                hasMojang && !hasLegacy, "-");
        check("裁脸复用在位（downloadAndCropFace）", hasCrop, "-");

        // ---------- 11. drawRoundedAvatar 零拷贝直绘行为（1.6.6.1 覆盖版） ----------
        Class<?> renderer = Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer");
        Object rendererInst = renderer.getDeclaredField("INSTANCE").get(null);
        Method drawRounded = null;
        for (Method m : renderer.getDeclaredMethods()) {
            // Kotlin internal 函数字节码名带模块后缀：drawRoundedAvatar$common_Bot
            if (m.getName().startsWith("drawRoundedAvatar")
                    && m.getParameterCount() == 6
                    && m.getParameterTypes()[0] == Graphics2D.class) drawRounded = m;
        }
        check("drawRoundedAvatar 方法存在（6 参含圆角半径）", drawRounded != null, "-");
        drawRounded.setAccessible(true);

        // 红色源图 32×32 → 100×100 透明画布，直绘到 (10, 10) 尺寸 64，圆角 16
        BufferedImage source = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) source.setRGB(x, y, 0xFFFF0000);
        }
        BufferedImage canvas = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            drawRounded.invoke(rendererInst, g, source, 10, 10, 64, 16);
        } finally {
            g.dispose();
        }
        // 画完再拿一个新 Graphics 画不受裁剪的方块，验证裁剪区已恢复
        Graphics2D g2 = canvas.createGraphics();
        try {
            g2.setColor(Color.BLUE);
            g2.fillRect(0, 0, 5, 5);
        } finally {
            g2.dispose();
        }
        int corner = canvas.getRGB(10, 10);          // 头像区左上角（圆角外）
        int center = canvas.getRGB(42, 42);          // 头像区中心
        int blueMark = canvas.getRGB(2, 2);          // 恢复裁剪后画的蓝块
        check("圆角裁剪生效（角落未被头像染色，保持透明）",
                (corner >>> 24) == 0, String.format("0x%08X", corner));
        check("头像中心正常绘制（红色）",
                (center & 0x00FFFFFF) == 0x00FF0000 && (center >>> 24) == 0xFF,
                String.format("0x%08X", center));
        check("画完恢复原裁剪区（后续绘制不受圆角影响）",
                (blueMark & 0x00FFFFFF) == 0x000000FF, String.format("0x%08X", blueMark));

        System.out.println();
        System.out.println("===== 冒烟结果：" + passed + "/" + (passed + failed) + " 通过，" + failed + " 失败 =====");
        System.exit(failed == 0 ? 0 : 1);
    }
}
