import cn.kerong.bot.UpdateChecker;
import cn.kerong.bot.tools.PluginFileLog;
import cn.kerong.bot.tools.ConsoleColors;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 1.6.6.4 功能冒烟（独立 JVM，类路径 = NeoForge 1.6.6.4 分发 JAR + 依赖）。
 *
 * 1.6.6.4 改动：移除空闲 GC 提示（idleGcHint 全链路删除，防 Full GC
 * 全停顿卡顿；单飞与空闲释放保留），第 9.4 节反转断言；
 * 1.6.6.3 重连降噪 / 双服检测移除基线经 class 加载与常量池间接覆盖。
 *
 * 覆盖：
 *  1. 历史回归：stripColorCodes / ConsoleColors / isNewer 四段比较 /
 *     floodgateXuid / WebUI 与 PlayTimeSync 类不存在 / avatarFetchPriority
 *     默认 LOCAL / 本地外置链拆分 / drawRoundedAvatar 直绘 / TTL 默认 3 小时；
 *  2. 头像内存缓存上限 128（AVATAR_CACHE_MAX 反射断言）；
 *  3. 单张 10KB 内存门槛（AVATAR_MEMORY_MAX_BYTES = 10240 反射断言 +
 *     cacheAndStoreAvatar 行为测试：纯色小图进内存 / 噪声大图不进内存）；
 *  4. 渲染空闲内存释放（idleReleaseMillis 默认 5 分钟 + render-idle-releaser
 *     线程随 markRenderCompleted 启动 + clearCaches 停止 + releaseIfIdle
 *     行为：未超时不释放 / 超时清空图像缓存并重置计时 / 未渲染安全空跑）；
 *  5. 内存缓存及时清理（sweepDiskCache 顺带清内存过期条目：过期清除、
 *     新鲜保留）；
 *  6. 失败分类（修复「清除缓存后极少玩家头像获取失败」：官方链路冷却
 *     期内（网络抖动型）失败不写 missCache；无冷却的确定性失败写
 *     missCache；全程零真实网络请求）；
 *  7. 磁盘缓存生命周期回归（ensureAvatarSweeper 幂等启动 / clearCaches
 *     停止 / 守护线程在位）；
 *  8. 链路标签载体（DiskAvatar / FetchedFace / LocalSkinRef 内部类 +
 *     常量池线路名与日志方法）。
 */
public class Test1664 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name + "  [" + detail + "]"); }
    }

    static Object assetsInst, settingsInst, poolInst;
    static Class<?> assets, settings, pool;

    public static void main(String[] args) throws Exception {
        assets = Class.forName("cn.kerong.penguin.spigot.render.InfoCardAssets");
        assetsInst = assets.getDeclaredField("INSTANCE").get(null);
        settings = Class.forName("cn.kerong.penguin.spigot.render.RenderSettings");
        settingsInst = settings.getDeclaredField("INSTANCE").get(null);
        pool = Class.forName("cn.kerong.penguin.spigot.render.CardRenderPool");
        poolInst = pool.getDeclaredField("INSTANCE").get(null);

        // ---------- 1. 历史回归 ----------
        String stripped = PluginFileLog.INSTANCE.stripColorCodes("§e[更新检查] §f发现新版本：§a§l1.6.6.4");
        check("stripColorCodes 剥离全部 § 码（回归）",
                !stripped.contains("§") && stripped.contains("1.6.6.4"), stripped);

        String ansi = ConsoleColors.INSTANCE.toAnsi("§e黄色文本");
        check("toAnsi §e → ANSI（回归）",
                ansi.contains("\u001B") && ansi.contains("黄色文本"), ansi.replace("\u001B", "ESC"));

        Method newer = null;
        for (Method m : UpdateChecker.class.getDeclaredMethods()) {
            if (m.getName().startsWith("isNewer") && m.getParameterCount() == 2) { newer = m; break; }
        }
        check("isNewer 方法存在（回归）", newer != null, "-");
        newer.setAccessible(true);
        check("isNewer 1.6.6.4 > 1.6.6.3（第四段数值比较）",
                Boolean.TRUE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.6.6.4", "1.6.6.3")), "-");
        check("isNewer 1.6.6.4 不大于 1.6.6.4（相等）",
                Boolean.FALSE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.6.6.4", "1.6.6.4")), "-");

        Method floodgateXuid = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("floodgateXuid")) floodgateXuid = m;
        }
        floodgateXuid.setAccessible(true);
        UUID floodgate = new UUID(0L, 0x000901f64f65c7c1L);
        Object xuid = floodgateXuid.invoke(assetsInst, floodgate);
        check("Floodgate UUID → xuid 解析正确（回归）",
                xuid instanceof Long && (Long) xuid == 0x01f64f65c7c1L, String.valueOf(xuid));
        UUID offline = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes());
        check("离线 v3 UUID 不误判（回归）",
                floodgateXuid.invoke(assetsInst, offline) == null, "-");

        try {
            Class.forName("cn.kerong.bot.web.WebUiServer");
            check("WebUiServer 类不存在（回归）", false, "类仍然存在");
        } catch (ClassNotFoundException expected) {
            check("WebUiServer 类不存在（回归）", true, "-");
        }
        try {
            Class.forName("cn.kerong.penguin.spigot.stats.PlayTimeSync");
            check("PlayTimeSync 类不存在（回归）", false, "类仍然存在");
        } catch (ClassNotFoundException expected) {
            check("PlayTimeSync 类不存在（回归）", true, "-");
        }

        Method priority = null;
        for (Method m : settings.getDeclaredMethods()) {
            if (m.getName().equals("avatarFetchPriority")) priority = m;
        }
        priority.setAccessible(true);
        check("avatarFetchPriority 无实例时默认 LOCAL（回归）",
                "LOCAL".equals(String.valueOf(priority.invoke(settingsInst))), "-");

        boolean hasLocal = false, hasExternal = false, hasCrop = false, hasLegacy = false;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("fetchViaLocalChain")) hasLocal = true;
            if (m.getName().equals("fetchViaExternalChain")) hasExternal = true;
            if (m.getName().equals("downloadAndCropFace")) hasCrop = true;
            if (m.getName().equals("resolveSkinTexture")) hasLegacy = true;
        }
        check("头像链本地/外置拆分在位（回归）", hasLocal && hasExternal && hasCrop && !hasLegacy, "-");

        Method drawRounded = null;
        for (Method m : Class.forName("cn.kerong.penguin.spigot.render.InfoCardRenderer")
                .getDeclaredMethods()) {
            if (m.getName().startsWith("drawRoundedAvatar")) drawRounded = m;
        }
        check("drawRoundedAvatar 方法存在（回归）", drawRounded != null, "-");

        Method hours = null;
        for (Method m : settings.getDeclaredMethods()) {
            if (m.getName().equals("avatarCacheHours")) hours = m;
        }
        hours.setAccessible(true);
        check("avatarCacheHours 默认 3.0（回归）",
                hours.invoke(settingsInst).equals(3.0), String.valueOf(hours.invoke(settingsInst)));

        // ---------- 2. 头像内存缓存上限 128（1.6.6.2） ----------
        Field maxField = assets.getDeclaredField("AVATAR_CACHE_MAX");
        maxField.setAccessible(true);
        int cacheMax = maxField.getInt(null);
        check("AVATAR_CACHE_MAX = 128（1.6.6.2：64 → 128）", cacheMax == 128, String.valueOf(cacheMax));

        // ---------- 3. 单张 10KB 内存门槛（1.6.6.2） ----------
        Field thresholdField = assets.getDeclaredField("AVATAR_MEMORY_MAX_BYTES");
        thresholdField.setAccessible(true);
        int threshold = thresholdField.getInt(null);
        check("AVATAR_MEMORY_MAX_BYTES = 10240（10KB）", threshold == 10 * 1024, String.valueOf(threshold));

        Method cacheAndStore = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("cacheAndStoreAvatar")) cacheAndStore = m;
        }
        check("cacheAndStoreAvatar 方法存在", cacheAndStore != null, "-");
        cacheAndStore.setAccessible(true);
        Method encodePng = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("encodeAvatarPng")) encodePng = m;
        }
        check("encodeAvatarPng 方法存在", encodePng != null, "-");
        encodePng.setAccessible(true);

        Field avatarCacheField = assets.getDeclaredField("avatarCache");
        avatarCacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> avatarCache = (Map<String, Object>) avatarCacheField.get(assetsInst);

        // 纯色小图：PNG 压缩后远小于 10KB → 应进内存缓存
        BufferedImage flat = new BufferedImage(216, 216, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = flat.createGraphics();
        try { g.setColor(new java.awt.Color(0x33CC66FF, true)); g.fillRect(0, 0, 216, 216); }
        finally { g.dispose(); }
        byte[] flatPng = (byte[]) encodePng.invoke(assetsInst, flat);
        check("纯色头像 PNG 编码成功且为 PNG 魔数",
                flatPng != null && flatPng.length >= 8
                        && (flatPng[0] & 0xFF) == 0x89 && flatPng[1] == 0x50
                        && flatPng[2] == 0x4E && flatPng[3] == 0x47,
                flatPng == null ? "null" : String.valueOf(flatPng.length));
        check("纯色头像 PNG 小于 10KB", flatPng != null && flatPng.length <= threshold,
                String.valueOf(flatPng == null ? -1 : flatPng.length));

        avatarCache.clear();
        long now = System.currentTimeMillis();
        cacheAndStore.invoke(assetsInst, "smoke_flat", flat, now);
        check("纯色小图（≤10KB）进内存缓存", avatarCache.containsKey("smoke_flat"),
                "keys=" + avatarCache.keySet());

        // 噪声大图：随机像素 PNG 压不动，必然远超 10KB → 不进内存缓存（只走磁盘）
        BufferedImage noise = new BufferedImage(216, 216, BufferedImage.TYPE_INT_ARGB);
        Random rnd = new Random(42L);
        for (int y = 0; y < 216; y++) {
            for (int x = 0; x < 216; x++) {
                noise.setRGB(x, y, rnd.nextInt());
            }
        }
        byte[] noisePng = (byte[]) encodePng.invoke(assetsInst, noise);
        check("噪声头像 PNG 超过 10KB", noisePng != null && noisePng.length > threshold,
                String.valueOf(noisePng == null ? -1 : noisePng.length));
        cacheAndStore.invoke(assetsInst, "smoke_noise", noise, now);
        check("噪声大图（>10KB）不进内存缓存（只走磁盘）",
                !avatarCache.containsKey("smoke_noise"), "keys=" + avatarCache.keySet());

        // ---------- 4. 渲染空闲内存释放（1.6.6.2） ----------
        Method idleMillis = null;
        for (Method m : settings.getDeclaredMethods()) {
            if (m.getName().equals("idleReleaseMillis")) idleMillis = m;
        }
        check("RenderSettings.idleReleaseMillis 方法存在", idleMillis != null, "-");
        idleMillis.setAccessible(true);
        Object idleDefault = idleMillis.invoke(settingsInst);
        check("idleReleaseMillis 默认 300000（5 分钟，无插件实例）",
                idleDefault instanceof Long && (Long) idleDefault == 300_000L, String.valueOf(idleDefault));

        Method markRender = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("markRenderCompleted")) markRender = m;
        }
        check("markRenderCompleted 方法存在", markRender != null, "-");
        markRender.setAccessible(true);
        markRender.invoke(assetsInst);
        Field idleReleaserField = assets.getDeclaredField("idleReleaser");
        idleReleaserField.setAccessible(true);
        check("markRenderCompleted 启动 render-idle-releaser 执行器",
                idleReleaserField.get(assetsInst) != null, "-");
        boolean idleThreadAlive = false;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("render-idle-releaser".equals(t.getName()) && t.isDaemon()) { idleThreadAlive = true; break; }
        }
        check("render-idle-releaser 守护线程已运行", idleThreadAlive, "-");

        Method activeRenders = null;
        for (Method m : pool.getDeclaredMethods()) {
            if (m.getName().equals("activeRenders")) activeRenders = m;
        }
        check("CardRenderPool.activeRenders 方法存在", activeRenders != null, "-");
        activeRenders.setAccessible(true);
        check("activeRenders 无渲染时为 0",
                Integer.valueOf(0).equals(activeRenders.invoke(poolInst)), "-");

        // releaseIfIdle 行为：缓存非空 + 距上次渲染超过阈值 → 清空图像缓存并重置计时
        Method releaseIfIdle = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().startsWith("releaseIfIdle")) releaseIfIdle = m;
        }
        check("releaseIfIdle 方法存在", releaseIfIdle != null, "-");
        releaseIfIdle.setAccessible(true);

        Field lastRenderField = assets.getDeclaredField("lastRenderCompletedAt");
        lastRenderField.setAccessible(true);
        // 场景 A：刚渲染完（未超时）→ 不释放
        avatarCache.put("smoke_hold", newCacheEntry(flat, now));
        markRender.invoke(assetsInst);
        releaseIfIdle.invoke(assetsInst);
        check("渲染后未超空闲阈值不释放", avatarCache.containsKey("smoke_hold"), "-");
        // 场景 B：超时 + 无进行中渲染 → 释放全部图像缓存 + 重置计时
        lastRenderField.setLong(assetsInst, now - 400_000L);  // 约 6.7 分钟前
        releaseIfIdle.invoke(assetsInst);
        check("超空闲阈值释放头像缓存", !avatarCache.containsKey("smoke_hold"), "-");
        check("释放后重置渲染计时基准", lastRenderField.getLong(assetsInst) == 0L,
                String.valueOf(lastRenderField.getLong(assetsInst)));
        // 场景 C：计时未启动（0）→ 空跑安全
        try {
            releaseIfIdle.invoke(assetsInst);
            check("未渲染过（计时 0）时空闲检查安全空跑", true, "-");
        } catch (Throwable t) {
            check("未渲染过（计时 0）时空闲检查安全空跑", false, String.valueOf(t));
        }

        // ---------- 5. 内存缓存及时清理（sweepDiskCache 顺带清内存） ----------
        avatarCache.clear();
        avatarCache.put("smoke_stale", newCacheEntry(flat, now - 11 * 60_000L));  // 超 10 分钟 TTL
        avatarCache.put("smoke_fresh", newCacheEntry(flat, now));
        Method sweep = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().startsWith("sweepDiskCache") && m.getParameterCount() == 0) sweep = m;
        }
        check("sweepDiskCache 方法存在（回归）", sweep != null, "-");
        sweep.setAccessible(true);
        sweep.invoke(assetsInst);  // 无插件实例：磁盘段空跑，内存段照常执行
        check("清扫顺带清掉内存过期条目（>10 分钟）", !avatarCache.containsKey("smoke_stale"),
                "keys=" + avatarCache.keySet());
        check("清扫保留内存新鲜条目", avatarCache.containsKey("smoke_fresh"), "-");

        // ---------- 6. 失败分类（1.6.6.2 修复：网络抖动型失败不写 missCache） ----------
        Field missCacheField = assets.getDeclaredField("missCache");
        missCacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> missCache = (Map<String, Object>) missCacheField.get(assetsInst);
        Field cooldownField = assets.getDeclaredField("mojangNetworkFailureAt");
        cooldownField.setAccessible(true);
        Method markUnreachable = null, inCooldown = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("markMojangUnreachable")) markUnreachable = m;
            if (m.getName().equals("mojangInCooldown")) inCooldown = m;
        }
        check("markMojangUnreachable / mojangInCooldown 方法存在",
                markUnreachable != null && inCooldown != null, "-");
        markUnreachable.setAccessible(true);
        inCooldown.setAccessible(true);

        Method fetch = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("fetchAvatar")) fetch = m;
        }
        Class<?> reqClass = Class.forName("cn.kerong.penguin.spigot.render.InfoCardAssets$AvatarRequest");
        Constructor<?> reqCtor = reqClass.getDeclaredConstructor(UUID.class, String.class, String.class, java.io.File.class);
        reqCtor.setAccessible(true);
        Object blankReq = reqCtor.newInstance(null, "", null, null);  // 空名：全链路零网络请求

        missCache.clear();
        cooldownField.setLong(assetsInst, 0L);
        // 场景 A：官方链路冷却期内（模拟网络抖动风暴）→ 失败不写 missCache
        markUnreachable.invoke(assetsInst);
        check("markMojangUnreachable 后处于冷却期", Boolean.TRUE.equals(inCooldown.invoke(assetsInst)), "-");
        Object r1 = fetch.invoke(assetsInst, blankReq);
        check("冷却期内获取失败返回 null", r1 == null, String.valueOf(r1));
        check("网络抖动型失败不写 missCache（下次查询立即重试）",
                !missCache.containsKey("name:"), "miss=" + missCache.keySet());
        // 场景 B：无冷却的确定性失败 → 写 missCache
        cooldownField.setLong(assetsInst, 0L);
        Object r2 = fetch.invoke(assetsInst, blankReq);
        check("无冷却时确定性失败返回 null", r2 == null, String.valueOf(r2));
        check("确定性失败写 missCache（60 秒冷却）",
                missCache.containsKey("name:"), "miss=" + missCache.keySet());
        missCache.clear();

        // ---------- 7. 磁盘缓存生命周期回归 ----------
        Method clearCaches = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("clearCaches")) clearCaches = m;
        }
        clearCaches.setAccessible(true);
        clearCaches.invoke(assetsInst);
        check("clearCaches 停止空闲释放线程（字段置 null）",
                idleReleaserField.get(assetsInst) == null, "-");
        Field sweeperField = assets.getDeclaredField("avatarSweeper");
        sweeperField.setAccessible(true);
        Method ensure = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("ensureAvatarSweeper")) ensure = m;
        }
        check("ensureAvatarSweeper 公开可调（双端启动即清扫）",
                ensure != null && java.lang.reflect.Modifier.isPublic(ensure.getModifiers()), "-");
        ensure.setAccessible(true);
        ensure.invoke(assetsInst);
        Object sweeper1 = sweeperField.get(assetsInst);
        check("ensureAvatarSweeper 启动清扫执行器（回归）", sweeper1 != null, "-");
        ensure.invoke(assetsInst);
        check("ensureAvatarSweeper 幂等（不重复建线程）",
                sweeperField.get(assetsInst) == sweeper1, "-");
        boolean sweepThreadAlive = false;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("avatar-cache-sweeper".equals(t.getName()) && t.isDaemon()) { sweepThreadAlive = true; break; }
        }
        check("avatar-cache-sweeper 守护线程已运行（回归）", sweepThreadAlive, "-");
        clearCaches.invoke(assetsInst);
        check("clearCaches 停止清扫线程（字段置 null，回归）",
                sweeperField.get(assetsInst) == null, "-");

        // ---------- 8. 链路标签载体（获取方式日志的数据基础） ----------
        try (InputStream is = assets.getResourceAsStream("/cn/kerong/penguin/spigot/render/InfoCardAssets.class")) {
            byte[] blob = is.readAllBytes();
            String blobText = new String(blob, java.nio.charset.StandardCharsets.UTF_8);
            check("获取方式日志方法在常量池（logAvatarSource / logAvatarMiss）",
                    blobText.contains("logAvatarSource") && blobText.contains("logAvatarMiss"), "-");
            check("线路名标签在常量池（Mojang官方API / 本地磁盘缓存）",
                    blobText.contains("Mojang官方API") && blobText.contains("本地磁盘缓存"), "-");
            check("空闲释放日志措辞在常量池（分钟无渲染）",
                    blobText.contains("分钟无渲染"), "-");
            check("多线路 URL 齐备（回归）",
                    blobText.contains("https://mc-heads.net/avatar/")
                            && blobText.contains("https://minotar.net/avatar/")
                            && blobText.contains("https://minepic.org/avatar/")
                            && blobText.contains("https://crafatar.com/avatars/"), "-");
        }
        try {
            Class.forName("cn.kerong.penguin.spigot.render.InfoCardAssets$DiskAvatar");
            Class.forName("cn.kerong.penguin.spigot.render.InfoCardAssets$FetchedFace");
            Class.forName("cn.kerong.penguin.spigot.render.InfoCardAssets$LocalSkinRef");
            check("DiskAvatar / FetchedFace / LocalSkinRef 内部类在位", true, "-");
        } catch (ClassNotFoundException t) {
            check("DiskAvatar / FetchedFace / LocalSkinRef 内部类在位", false, String.valueOf(t));
        }

        // ---------- 9. 1.6.6.2 覆盖版（日志刷屏 / 启动内存风暴 / 诊断） ----------
        // 9.1 获取方式日志：文件日志 + 会话内按玩家去重
        Field loggedField = assets.getDeclaredField("loggedAvatarSources");
        loggedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> loggedSources = (Map<String, String>) loggedField.get(assetsInst);
        loggedSources.clear();
        Method logSource = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("logAvatarSource")) logSource = m;
        }
        check("logAvatarSource 方法存在", logSource != null, "-");
        logSource.setAccessible(true);
        Object probeReq = reqCtor.newInstance(null, "DedupProbe", null, null);
        logSource.invoke(assetsInst, probeReq, "方式A");
        logSource.invoke(assetsInst, probeReq, "方式A");
        check("获取方式日志会话去重（同方式第二次不新增）",
                loggedSources.size() == 1 && "方式A".equals(loggedSources.get("name:dedupprobe")),
                "map=" + loggedSources);
        logSource.invoke(assetsInst, probeReq, "方式B");
        check("获取方式日志换线路更新记录（线路切换可观测）",
                "方式B".equals(loggedSources.get("name:dedupprobe")),
                "map=" + loggedSources);
        // 日志方法体必须引用 PluginFileLog（文件日志）而不是 log_info（控制台）
        try (InputStream is = assets.getResourceAsStream("/cn/kerong/penguin/spigot/render/InfoCardAssets.class")) {
            byte[] blob = is.readAllBytes();
            String blobText = new String(blob, java.nio.charset.StandardCharsets.UTF_8);
            check("获取方式日志走文件日志（常量池引用 PluginFileLog）",
                    blobText.contains("PluginFileLog"), "-");
            check("日志去重容量上限常量在位（LOGGED_SOURCES_MAX）",
                    blobText.contains("LOGGED_SOURCES_MAX"), "-");
        }

        // 9.2 头像获取 single-flight（并发去重：等待者共享执行者结果）
        Class<?> latchClass = Class.forName("cn.kerong.penguin.spigot.render.InfoCardAssets$FetchLatch");
        Constructor<?> latchCtor = latchClass.getDeclaredConstructor();
        latchCtor.setAccessible(true);
        Field inFlightField = assets.getDeclaredField("inFlightFetches");
        inFlightField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> inFlight = (Map<String, Object>) inFlightField.get(assetsInst);
        inFlight.clear();
        Object latch = latchCtor.newInstance();
        Field imageField = latchClass.getDeclaredField("image");
        imageField.setAccessible(true);
        java.util.concurrent.CountDownLatch done;
        {
            Field doneField = latchClass.getDeclaredField("done");
            doneField.setAccessible(true);
            done = (java.util.concurrent.CountDownLatch) doneField.get(latch);
        }
        inFlight.put("name:waiterprobe", latch);
        Object waiterReq = reqCtor.newInstance(null, "WaiterProbe", null, null);
        final Method fetchFinal = fetch;
        java.util.concurrent.atomic.AtomicReference<Object> waiterResult = new java.util.concurrent.atomic.AtomicReference<>("未返回");
        Thread waiter = new Thread(() -> {
            try {
                waiterResult.set(fetchFinal.invoke(assetsInst, waiterReq));
            } catch (Throwable t) {
                waiterResult.set("异常:" + t);
            }
        }, "smoke-waiter");
        waiter.start();
        Thread.sleep(400);
        check("等待者阻塞等待执行者（未完成前不返回）", waiter.isAlive(), "-");
        BufferedImage sharedFace = new BufferedImage(216, 216, BufferedImage.TYPE_INT_ARGB);
        imageField.set(latch, sharedFace);
        done.countDown();
        waiter.join(3000);
        check("等待者取到执行者结果（同一图像实例，零重复网络获取）",
                waiterResult.get() == sharedFace, "result=" + waiterResult.get());
        check("等待者不改写去重表（仍持有测试门闩）", inFlight.get("name:waiterprobe") == latch, "-");
        inFlight.remove("name:waiterprobe");

        // 9.3 执行者路径收尾清理（finally 移除门闩，防止去重表泄漏）
        // 空名请求：全链路零网络请求（与第 6 节同一不变量）
        inFlight.clear();
        missCache.clear();
        Object execReq = reqCtor.newInstance(null, "", null, null);
        Object execResult = fetch.invoke(assetsInst, execReq);
        check("执行者路径正常完成（空请求零网络，失败返回 null）",
                execResult == null, String.valueOf(execResult));
        check("执行者收尾移除门闩（去重表无残留）",
                !inFlight.containsKey("name:"), "inFlight=" + inFlight.keySet());

        // 9.4 空闲 GC 提示已移除（1.6.6.4：idleGcHint 方法与配置全链路删除）
        boolean gcHintMethodExists = false;
        for (Method m : settings.getDeclaredMethods()) {
            if (m.getName().equals("idleGcHint")) gcHintMethodExists = true;
        }
        check("idleGcHint 方法已删除（防 Full GC 全停顿卡顿）",
                !gcHintMethodExists, "idleGcHint 仍存在");

        // 9.5 缓存诊断快照（/kerong mem 输出源）
        Method statsMethod = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("cacheStats")) statsMethod = m;
        }
        check("cacheStats 方法存在", statsMethod != null, "-");
        if (statsMethod != null) {
            statsMethod.setAccessible(true);
            String stats = String.valueOf(statsMethod.invoke(assetsInst));
            check("cacheStats 输出 JVM 堆与缓存条目",
                    stats.contains("JVM 堆") && stats.contains("头像内存缓存")
                            && stats.contains("进行中的头像获取"), stats);
        }

        // 9.6 clearCaches 清空去重表（停机重置，防止长会话陈旧记录）
        loggedSources.put("name:stale", "旧方式");
        clearCaches.invoke(assetsInst);
        check("clearCaches 清空获取方式去重表与并发获取表",
                loggedSources.isEmpty() && inFlight.isEmpty(), "-");

        System.out.println("\n===== 冒烟结果：" + passed + "/" + (passed + failed) + " 通过，" + failed + " 失败 =====");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** 反射构造私有内部类 CacheEntry(image, createdAt)。 */
    static Object newCacheEntry(BufferedImage image, long createdAt) throws Exception {
        Class<?> cls = Class.forName("cn.kerong.penguin.spigot.render.InfoCardAssets$CacheEntry");
        Constructor<?> ctor = cls.getDeclaredConstructor(BufferedImage.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(image, createdAt);
    }
}
