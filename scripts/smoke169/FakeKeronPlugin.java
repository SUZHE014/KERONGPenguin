package scripts.smoke169;

import java.io.File;

/**
 * 冒烟用假 KERONGPenguin 插件（仅独立 JVM 冒烟）：
 * 经 NF 兼容层 JavaPlugin 提供 config，供 PlaceholderVariableService
 * 读取 variable-fetch 段（isEnabled / placeholderOf 的配置通路）。
 * dataFolder 指向临时目录（不落真实文件，config 为内存态）。
 */
public final class FakeKeronPlugin extends org.bukkit.plugin.java.JavaPlugin {

    @Override
    public String getName() {
        return "KERONGPenguin";
    }

    @Override
    public File getDataFolder() {
        return new File(System.getProperty("java.io.tmpdir"), "smoke169-data");
    }
}
