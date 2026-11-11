import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.bot.provider.ChatFormat;
import cn.kerong.bot.provider.HExecution;
import cn.kerong.bot.provider.Motd;

/**
 * 冒烟专用假 KERONG 运行时（1.7.5.2）：
 * 继承 NF 兼容层 JavaPlugin（内存 config + dataFolder 可指定）并实现
 * KERONG 接口；白名单三方法带可翻转状态与调用记录（验证接口默认方法
 * enforceNoNativeWhitelist 的完整判定链）。
 */
public final class FakeKeronRuntime extends org.bukkit.plugin.java.JavaPlugin implements cn.kerong.bot.KERONG {

    private final File folder;
    public volatile boolean nativeWhitelistOn = false;
    public volatile boolean disableCalled = false;
    public final List<String> warnings = new ArrayList<>();

    public FakeKeronRuntime(File folder) {
        this.folder = folder;
    }

    @Override
    public String getName() {
        return "KERONGPenguin";
    }

    @Override
    public File getDataFolder() {
        return folder;
    }

    // ---------- LoggerProvider ----------

    @Override
    public void log_info(String msg) {
        System.out.println("[fake-info] " + msg);
    }

    @Override
    public void log_warning(String msg) {
        warnings.add(msg);
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
        return "1.7.5.2";
    }

    // ---------- SchedulerProvider ----------

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
    }

    @Override
    public List<String> getOnlineList() {
        return List.of();
    }

    @Override
    public PerformanceSnapshot performanceSnapshot() {
        return null;
    }

    // ---------- 白名单检测（1.7.5.2 需求 5） ----------

    @Override
    public boolean isNativeWhitelistEnabled() {
        return nativeWhitelistOn;
    }

    @Override
    public void disableNativeWhitelist() {
        disableCalled = true;
    }
}
