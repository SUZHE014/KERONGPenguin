import cn.huohuas001.bot.HuHoBot;
import cn.huohuas001.bot.provider.ChatFormat;
import cn.huohuas001.bot.provider.HExecution;
import cn.huohuas001.bot.provider.Motd;
import cn.huohuas001.bot.tools.Cancelable;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 测试插件：JavaPlugin + HuHoBot 双身份（QqBindManager.getInstance 要求
 * BotShared.instance 是 JavaPlugin），供独立 JVM 存储 logic 测试使用。
 */
public class TestPlugin extends JavaPlugin implements HuHoBot {

    private File configFile;

    public void setDataFolder(File folder, File config) {
        try {
            java.lang.reflect.Field field = JavaPlugin.class.getDeclaredField("dataFolder");
            field.setAccessible(true);
            field.set(this, folder);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        this.configFile = config;
    }

    @Override
    public File getConfigFile() {
        return configFile;
    }

    @Override
    public String getBotAppId() {
        return "";
    }

    @Override
    public String getBotSecret() {
        return "";
    }

    @Override
    public ChatFormat getChatFormat() {
        return new ChatFormat("", "", false, "");
    }

    @Override
    public Motd getMotd() {
        return new Motd("", 0, "", false, false);
    }

    @Override
    public String getBotName() {
        return "test";
    }

    @Override
    public String getPlatform() {
        return "spigot";
    }

    @Override
    public String getPluginVersion() {
        return "test";
    }

    @Override
    public List<String> getOnlineList() {
        return Collections.emptyList();
    }

    @Override
    public HExecution createCommandExecutor() {
        return new HExecution() {
            @Override
            public String getRawString() {
                return "";
            }

            @Override
            public CompletableFuture<HExecution> execute(String command) {
                return CompletableFuture.completedFuture(this);
            }
        };
    }

    @Override
    public void reloadPluginConfig() {
    }

    @Override
    public void broadcastMessage(String msg) {
    }

    @Override
    public void log_info(String msg) {
        System.out.println("[INFO] " + msg);
    }

    @Override
    public void log_warning(String msg) {
        System.out.println("[WARN] " + msg);
    }

    @Override
    public void log_error(String msg) {
        System.out.println("[ERROR] " + msg);
    }

    @Override
    public Cancelable submit(Runnable task) {
        task.run();
        return new Cancelable() {
            @Override
            public void cancel() {
            }
        };
    }

    @Override
    public Cancelable submitLater(long delay, Runnable task) {
        task.run();
        return new Cancelable() {
            @Override
            public void cancel() {
            }
        };
    }

    @Override
    public Cancelable submitTimer(long delay, long period, Runnable task) {
        return new Cancelable() {
            @Override
            public void cancel() {
            }
        };
    }
}
