package scripts.smoke1700;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.bot.provider.AdminMode;
import cn.kerong.bot.provider.ChatFormat;
import cn.kerong.bot.provider.HExecution;
import cn.kerong.bot.provider.Motd;
import cn.kerong.bot.provider.PlayerEventFormat;

/**
 * 冒烟用假 KERONG 运行时（仅独立 JVM 冒烟）：
 * 同时继承 NF 充层 JavaPlugin（提供内存 config）与实现 common-Bot 的
 * KERONG 接口——注册进 BotShared 后，RenderSettings 这类走
 *「BotShared.instance as? JavaPlugin」读配置的组件在冒烟 JVM 里
 * 也能走真实配置通路（1.6.9.4 背景 blur-mask 样式的端到端验证依赖它）。
 */
public final class FakeKeronRuntime extends org.bukkit.plugin.java.JavaPlugin implements cn.kerong.bot.KERONG {

    @Override
    public String getName() {
        return "KERONGPenguin";
    }

    @Override
    public File getDataFolder() {
        return new File(System.getProperty("java.io.tmpdir"), "smoke1700-data");
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
        return "1.7.0";
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
