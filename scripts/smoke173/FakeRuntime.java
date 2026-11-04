package scripts.smoke173;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.bot.provider.ChatFormat;
import cn.kerong.bot.provider.HExecution;
import cn.kerong.bot.provider.Motd;

/**
 * 冒烟用假 KERONG 运行时（仅独立 JVM 冒烟，1.7.3）：
 * 继承 NF 兼容层 JavaPlugin（提供内存 config + saveConfig 落盘）并实现
 * KERONG 接口——注册进 BotShared 后，BackgroundPackService 里走
 *「BotShared.instance as? JavaPlugin」的 switchBackgrounds 在冒烟 JVM
 * 里也能走真实配置读写通路；reloadPluginConfig 计数用于断言自动重载。
 */
public final class FakeRuntime extends org.bukkit.plugin.java.JavaPlugin implements cn.kerong.bot.KERONG {

    /** reloadPluginConfig 被调用次数（断言「改完自动重载」）。 */
    public final AtomicInteger reloadCount = new AtomicInteger();

    private final File dataFolder;

    public FakeRuntime() {
        this.dataFolder = new File(System.getProperty("java.io.tmpdir"), "smoke173-data");
        dataFolder.mkdirs();
    }

    @Override
    public String getName() {
        return "KERONGPenguin";
    }

    @Override
    public File getDataFolder() {
        return dataFolder;
    }

    // ---------- LoggerProvider ----------

    @Override
    public void log_info(String msg) {
        System.out.println("[fake-info] " + msg);
    }

    @Override
    public void log_warning(String msg) {
        System.out.println("[fake-warn] " + msg);
    }

    @Override
    public void log_error(String msg) {
        System.out.println("[fake-error] " + msg);
    }

    // ---------- ConfigProvider ----------

    @Override
    public ChatFormat getChatFormat() {
        return new ChatFormat("[游戏] <{name}> {message}", "[QQ] {name}: {message}", true, "");
    }

    @Override
    public Motd getMotd() {
        return new Motd("", 0, "", true, false);
    }

    @Override
    public String getBotName() {
        return "KERONGPenguin";
    }

    @Override
    public String getPlatform() {
        return "smoke";
    }

    @Override
    public String getPluginVersion() {
        return "1.7.3";
    }

    @Override
    public String getServerName() {
        return "SmokeServer";
    }

    @Override
    public String getServerVersion() {
        return "smoke-1.0";
    }

    // ---------- SchedulerProvider（submitAsync 有默认实现） ----------

    @Override
    public cn.kerong.bot.tools.Cancelable submit(Runnable task) {
        task.run();
        return () -> { };
    }

    @Override
    public cn.kerong.bot.tools.Cancelable submitLater(long delay, Runnable task) {
        task.run();
        return () -> { };
    }

    @Override
    public cn.kerong.bot.tools.Cancelable submitTimer(long delay, long period, Runnable task) {
        task.run();
        return () -> { };
    }

    // ---------- CommandProvider ----------

    @Override
    public CompletableFuture<HExecution> dispatchCommand(String command) {
        return CompletableFuture.completedFuture(null);
    }

    // ---------- MessageProvider ----------

    @Override
    public void broadcastMessage(String msg) {
    }

    @Override
    public void sendMarkdown(String markdownContent, io.github.kloping.qqbot.entities.ex.Keyboard keyboard) {
    }

    @Override
    public void sendMarkdownToGroup(String groupOpenId, String markdownContent, io.github.kloping.qqbot.entities.ex.Keyboard keyboard) {
    }

    @Override
    public void replyMarkdown(io.github.kloping.qqbot.api.v2.GroupMessageEvent event, String content, io.github.kloping.qqbot.entities.ex.Keyboard keyboard) {
    }

    @Override
    public void replyWithImg(io.github.kloping.qqbot.api.v2.GroupMessageEvent event, String text, String imgUrl) {
    }

    // ---------- KERONG 本体 ----------

    @Override
    public String getBotAppId() {
        return "";
    }

    @Override
    public String getBotSecret() {
        return "";
    }

    @Override
    public HExecution createCommandExecutor() {
        return null;
    }

    @Override
    public void reloadPluginConfig() {
        reloadCount.incrementAndGet();
    }

    @Override
    public List<String> getOnlineList() {
        return List.of();
    }

    @Override
    public PerformanceSnapshot performanceSnapshot() {
        return null;
    }
}
