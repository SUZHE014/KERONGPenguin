import cn.kerong.bot.KERONG;
import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.bot.provider.BotShared;
import cn.kerong.bot.provider.ChatFormat;
import cn.kerong.bot.provider.HExecution;
import cn.kerong.bot.provider.Motd;
import cn.kerong.penguin.spigot.render.OnlineListRenderer;
import cn.kerong.penguin.spigot.render.RenderSettings;
import cn.kerong.penguin.spigot.stats.FakePlayerRegistry;
import cn.kerong.bot.tools.Cancelable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 1.7.6.1 冒烟：假人登记表 + 查在线假人小节 + 新配置键。
 * 纯 JVM（classpath = NeoForge 分发 JAR，自带 org.bukkit 兼容层）：
 *   R. FakePlayerRegistry（本轮核心）：
 *      假人连接登记落盘（fake-players.json）→ 持久排除（离线假人历史时长）
 *      → 大小写不敏感名字判定 → 重载往返 → 真实连接自愈移除
 *      → 白名单命中（config 整树替换前后）→ liveIsFake 名单/检测器两路
 *      → 在线假人集合路径 → 白名单并入登记
 *   S. RenderSettings.onlineListShowFakePlayers（本轮核心）：读配置 true /
 *      键缺失默认 false
 *   O. OnlineListRenderer 假人小节（本轮核心）：
 *      高度公式（差值 = 18+56+14+84）/ 单页渲染含小节 / 小节标题文字像素
 *      在位 + 反例（无假人同区域为背景）/ 空主列表 + 假人 / 多页时小节
 *      只在末页 / measureHeight 兼容入口回归
 *   假桩：SmokePlugin extends 兼容层 JavaPlugin implements KERONG——
 *      FakePlayerRegistry / RenderSettings 均经 BotShared.instance 读
 *      dataFolder 与 config，必须给到真实形状的实例。
 */
public class Smoke1761 {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    /** 兼容层 Player 最小实现（liveIsFake 判定用）。 */
    public static class FakePlayer implements org.bukkit.entity.Player {
        private final String name;
        private final UUID uuid;
        public FakePlayer(String name, UUID uuid) { this.name = name; this.uuid = uuid; }
        @Override public String getName() { return name; }
        @Override public UUID getUniqueId() { return uuid; }
        @Override public boolean isOnline() { return true; }
        @Override public void sendMessage(String message) { }
        @Override public void kickPlayer(String reason) { }
        @Override public int getStatistic(org.bukkit.Statistic statistic) { return 0; }
        @Override public org.bukkit.entity.PlayerProfile getPlayerProfile() { return null; }
    }

    /** 假桩插件：兼容层 JavaPlugin（dataFolder/config）+ KERONG 全接口（默认值桩）。 */
    public static class SmokePlugin extends org.bukkit.plugin.java.JavaPlugin implements KERONG {
        private final File folder;
        public SmokePlugin(File folder) { this.folder = folder; }
        @Override public String getName() { return "KERONGPenguin"; }
        @Override public File getDataFolder() { return folder; }
        // ---- KERONG ----
        @Override public String getBotAppId() { return ""; }
        @Override public String getBotSecret() { return ""; }
        @Override public HExecution createCommandExecutor() { return null; }
        @Override public void reloadPluginConfig() { }
        @Override public List<String> getOnlineList() { return Collections.emptyList(); }
        @Override public PerformanceSnapshot performanceSnapshot() { return null; }
        // ---- LoggerProvider ----
        @Override public void log_info(String msg) { System.out.println("[info] " + msg); }
        @Override public void log_warning(String msg) { System.out.println("[warn] " + msg); }
        @Override public void log_error(String msg) { System.out.println("[error] " + msg); }
        // ---- SchedulerProvider ----
        @Override public Cancelable submit(Runnable task) { return null; }
        @Override public Cancelable submitLater(long delay, Runnable task) { return null; }
        @Override public Cancelable submitTimer(long delay, long period, Runnable task) { return null; }
        // ---- CommandProvider ----
        @Override public java.util.concurrent.CompletableFuture<HExecution> dispatchCommand(String command) { return null; }
        // ---- MessageProvider（KERONG 接口对后四个给了默认实现，这里全部给桩） ----
        @Override public void broadcastMessage(String msg) { }
        @Override public void sendMarkdown(String content, io.github.kloping.qqbot.entities.ex.Keyboard keyboard) { }
        @Override public void sendMarkdownToGroup(String groupOpenId, String content, io.github.kloping.qqbot.entities.ex.Keyboard keyboard) { }
        @Override public void replyMarkdown(io.github.kloping.qqbot.api.v2.GroupMessageEvent event, String content, io.github.kloping.qqbot.entities.ex.Keyboard keyboard) { }
        @Override public void replyWithImg(io.github.kloping.qqbot.api.v2.GroupMessageEvent event, String text, String imgUrl) { }
        // ---- ConfigProvider ----
        @Override public ChatFormat getChatFormat() { return new ChatFormat("[游戏] <{name}> {message}", "[QQ] {name}: {message}", false, "#"); }
        @Override public Motd getMotd() { return new Motd("", 0, "", false, false); }
        @Override public String getBotName() { return "smoke"; }
        @Override public String getPlatform() { return "smoke"; }
        @Override public String getPluginVersion() { return "1.7.6.1"; }
        @Override public File getConfigFile() { return new File(folder, "config.yml"); }
        @Override public String getServerName() { return "SmokeSrv"; }
        @Override public String getServerVersion() { return "smoke"; }
        @Override public String markdown(String key) { return null; }
        @Override public boolean getFullAmount() { return false; }
    }

    static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }

    static int brightPixels(BufferedImage img, int x0, int x1, int y0, int y1) {
        int count = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) continue;
                int rgb = img.getRGB(x, y);
                int sum = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                if (sum > 540) count++;
            }
        }
        return count;
    }

    public static void main(String[] args) throws Exception {
        File root = new File(".").getAbsoluteFile();
        File data = new File(root, "kr-data");
        if (data.exists()) deleteRecursively(data);
        data.mkdirs();
        File cfgFile = new File(data, "config.yml");
        Files.write(cfgFile.toPath(), (
            "qq-bind:\n" +
            "  fake-player-whitelist:\n" +
            "    - BotAlpha\n" +
            "online-list:\n" +
            "  show-fake-players: true\n"
        ).getBytes(StandardCharsets.UTF_8));

        SmokePlugin plugin = new SmokePlugin(data);
        BotShared.INSTANCE.setInstance(plugin);
        // 配置装载进假桩（兼容层 YamlConfiguration 整树替换语义）
        ((org.bukkit.configuration.file.YamlConfiguration) plugin.getConfig()).load(cfgFile);

        // ===== R. FakePlayerRegistry =====
        System.out.println("== R. FakePlayerRegistry 假人登记表 ==");
        FakePlayerRegistry reg = FakePlayerRegistry.INSTANCE;
        reg.resetCache();
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        UUID u3 = UUID.randomUUID();

        check("R1 无登记无名单 → 不排除",
              !reg.isExcluded(u1, "Steve", Collections.emptySet()));
        reg.evaluateJoin(u1, "Steve", true);
        File registryFile = new File(data, "fake-players.json");
        check("R2 假人连接登记落盘（fake-players.json）", registryFile.isFile());
        String regText = new String(Files.readAllBytes(registryFile.toPath()), StandardCharsets.UTF_8);
        check("R3 登记内容含 UUID 与玩家名",
              regText.contains(u1.toString()) && regText.contains("Steve"), regText);
        check("R4 持久登记路径排除（离线假人历史时长）",
              reg.isExcluded(u1, "Steve", Collections.emptySet()));
        check("R5 名字判定大小写不敏感", reg.isFakeName("STEVE"));
        reg.resetCache();
        check("R6 重载后仍在登记表（持久化往返）",
              reg.isExcluded(u1, "Steve", Collections.emptySet()));
        reg.evaluateJoin(u1, "Steve", false);
        check("R7 真实连接进服自愈移除",
              !reg.isExcluded(u1, "Steve", Collections.emptySet()));
        check("R8 自愈后文件不含该 UUID",
              !new String(Files.readAllBytes(registryFile.toPath()), StandardCharsets.UTF_8).contains(u1.toString()));
        check("R9 名单命中（UUID 为空仅按名排除）",
              reg.isExcluded(null, "BotAlpha", Collections.emptySet()));
        check("R10 名单判定大小写不敏感", reg.isFakeName("botalpha"));
        FakePlayer steve = new FakePlayer("Steve", u1);
        FakePlayer bot = new FakePlayer("BotAlpha", u2);
        FakePlayer injected = new FakePlayer("Injected", u3);
        check("R11 liveIsFake 名单命中", reg.liveIsFake(bot));
        check("R12 liveIsFake 非名单无检测器 → 真人", !reg.liveIsFake(steve));
        reg.setConnectionDetector(p -> p.getName().equals("Injected"));
        check("R13 liveIsFake 检测器命中（本地注入连接）", reg.liveIsFake(injected));
        check("R14 liveIsFake 检测器未命中 → 真人", !reg.liveIsFake(steve));
        reg.setConnectionDetector(null);
        check("R15 检测器置空回退", !reg.liveIsFake(injected));
        check("R16 在线假人集合路径排除", reg.isExcluded(u2, "Alex", Set.of(u2)));
        check("R17 非在线未登记 → 不排除", !reg.isExcluded(u2, "Alex", Collections.emptySet()));
        reg.evaluateJoin(u3, "BotAlpha", false);
        check("R18 白名单并入登记（连接非假但名单命中仍登记）",
              reg.isExcluded(u3, "BotAlpha", Collections.emptySet()));

        // ===== S. RenderSettings 新键 =====
        System.out.println("== S. online-list.show-fake-players 配置读取 ==");
        check("S1 配置 true 读取为 true", RenderSettings.INSTANCE.onlineListShowFakePlayers());
        File cfgBare = new File(data, "config-bare.yml");
        Files.write(cfgBare.toPath(), "serverName: SmokeSrv\n".getBytes(StandardCharsets.UTF_8));
        ((org.bukkit.configuration.file.YamlConfiguration) plugin.getConfig()).load(cfgBare);
        check("S2 键缺失默认 false", !RenderSettings.INSTANCE.onlineListShowFakePlayers());
        check("S3 配置替换后名单失效（liveIsFake 走新配置）", !reg.liveIsFake(bot));

        // ===== O. OnlineListRenderer 假人小节 =====
        System.out.println("== O. OnlineListRenderer 假人小节 ==");
        OnlineListRenderer r = OnlineListRenderer.INSTANCE;
        int base9 = r.measurePageHeight(9, true, 0);
        check("O1 无假人 = 旧公式（measureHeight 兼容入口回归）",
              r.measureHeight(9) == base9, base9 + " vs " + r.measureHeight(9));
        // 9 人 = 3 行不触发 640 高度下限（6 人基础 632 会被 coerce 到 640，
        // 差值变成 164——实现正确，选不触底的行数断精确差值）
        int with3 = r.measurePageHeight(9, true, 3);
        check("O2 3 假人小节高度差 = 18+56+14+84", with3 - base9 == 172,
              String.valueOf(with3 - base9));

        List<OnlineListRenderer.OnlineEntry> real = Arrays.asList(
            new OnlineListRenderer.OnlineEntry("RealOne", null),
            new OnlineListRenderer.OnlineEntry("RealTwo", null));
        List<OnlineListRenderer.OnlineEntry> fakes = Arrays.asList(
            new OnlineListRenderer.OnlineEntry("BotA", null),
            new OnlineListRenderer.OnlineEntry("BotB", null));

        // 单页：2 真人 + 2 假人（背景 null → 纯黑底 + 渐变）
        List<byte[]> pages = r.renderPages(new OnlineListRenderer.OnlineListData(
            "SmokeSrv", "12:00", real, fakes, null, null));
        check("O3 单页输出 1 张", pages.size() == 1, String.valueOf(pages.size()));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pages.get(0)));
        check("O4 画布 1350 × 预期高度（2 人 + 2 假人小节）",
              img != null && img.getWidth() == 1350
              && img.getHeight() == r.measurePageHeight(2, true, 2),
              img == null ? "null" : img.getWidth() + "x" + img.getHeight());
        // 假人小节标题文字像素：gridTop=412，主网格底=496，sectionTop=514，
        // 「假人在线」34px 白字基线 554 → 采样带 y∈[524,560]，标题居左
        int bright = brightPixels(img, 44, 420, 524, 560);
        check("O5 假人小节标题文字在位（亮像素 > 80）", bright > 80, String.valueOf(bright));
        // 反例：无假人时同区域为纯背景
        BufferedImage imgNoFake = ImageIO.read(new ByteArrayInputStream(
            r.renderPages(new OnlineListRenderer.OnlineListData(
                "SmokeSrv", "12:00", real, Collections.emptyList(), null, null)).get(0)));
        int brightNoFake = brightPixels(imgNoFake, 44, 420, 524, 560);
        check("O6 无假人时同区域无文字（反例）", brightNoFake <= bright / 4,
              brightNoFake + " vs " + bright);

        // 空主列表 + 假人：主空态卡 + 假人小节照常
        List<byte[]> p3 = r.renderPages(new OnlineListRenderer.OnlineListData(
            "SmokeSrv", "12:00", Collections.emptyList(),
            Arrays.asList(new OnlineListRenderer.OnlineEntry("BotC", null)), null, null));
        BufferedImage img3 = ImageIO.read(new ByteArrayInputStream(p3.get(0)));
        check("O7 空主列表 + 假人照常出小节",
              p3.size() == 1 && img3.getHeight() == r.measurePageHeight(0, true, 1),
              img3.getHeight() + " vs " + r.measurePageHeight(0, true, 1));

        // 多页：61 主名单 + 2 假人 → 2 张，假人小节只在末页
        List<OnlineListRenderer.OnlineEntry> many = new ArrayList<>();
        for (int i = 1; i <= 61; i++) many.add(new OnlineListRenderer.OnlineEntry("P" + i, null));
        List<byte[]> p4 = r.renderPages(new OnlineListRenderer.OnlineListData(
            "SmokeSrv", "12:00", many, fakes, null, null));
        check("O8 61 人 + 2 假人 → 2 页", p4.size() == 2, String.valueOf(p4.size()));
        BufferedImage first = ImageIO.read(new ByteArrayInputStream(p4.get(0)));
        BufferedImage last = ImageIO.read(new ByteArrayInputStream(p4.get(1)));
        check("O9 假人小节只在末页（首页无小节 / 末页含小节）",
              first.getHeight() == r.measurePageHeight(60, true, 0)
              && last.getHeight() == r.measurePageHeight(1, false, 2),
              first.getHeight() + "/" + last.getHeight());
        check("O10 两页宽度一致 1350",
              first.getWidth() == 1350 && last.getWidth() == 1350);

        System.out.println();
        System.out.println("===== Smoke1761: " + pass + " 通过, " + fail + " 失败 =====");
        System.exit(fail == 0 ? 0 : 1);
    }
}
