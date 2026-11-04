import java.io.File;
import java.nio.file.Files;

/**
 * 阶段 A：用「已发布的旧版 1.7.2（首发）JAR」真实复现受害用户。
 *
 * 旧版 NeoForge 兼容层 saveConfig 整树 dump 剥注释——模拟用户执行过
 * /hbkrg bpngsz 之类的写操作（五个背景开关 + 两个自定义值），
 * 落盘文件注释全丢、config-version 仍是 29。
 */
public class AffectedUserSim extends org.bukkit.plugin.java.JavaPlugin {

    private final File dataFolder;

    public AffectedUserSim(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    @Override
    public String getName() {
        return "KERONGPenguin";
    }

    @Override
    public File getDataFolder() {
        return dataFolder;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[1]);
        if (dir.exists()) {
            for (File f : dir.listFiles()) f.delete();
        }
        dir.mkdirs();
        File cfg = new File(dir, "config.yml");
        Files.copy(new File(args[0]).toPath(), cfg.toPath());

        int before = countComments(cfg);
        System.out.println("[受害用户] 旧版写操作前注释行 = " + before);

        AffectedUserSim plugin = new AffectedUserSim(dir);
        plugin.reloadConfig();
        // bpngsz no 的写入面：五个背景开关
        String[] keys = {
            "render.info-card-builtin-background",
            "render.online-list-builtin-background",
            "render.performance.builtin-background",
            "leaderboard.builtin-background",
            "coins-leaderboard.builtin-background",
        };
        for (String k : keys) {
            plugin.getConfig().set(k, false);
        }
        // 用户自己的自定义值
        plugin.getConfig().set("render.blur-strength", 77);
        plugin.getConfig().set("serverName", "MyCustomServer");
        plugin.saveConfig();

        int after = countComments(cfg);
        System.out.println("[受害用户] 旧版 saveConfig 后注释行 = " + after);
        System.out.println("[受害用户] 落盘 config-version = " + readVersion(cfg));
        System.out.println("[受害用户] 自定义值在文件里: "
                + (Files.readString(cfg.toPath()).contains("blur-strength: 77") ? "blur=77 " : "blur丢失 ")
                + (Files.readString(cfg.toPath()).contains("MyCustomServer") ? "serverName=MyCustomServer" : "serverName丢失"));
        if (after > 10) {
            System.out.println("[受害用户] 意外：注释未丢（classpath 上的 JAR 不是旧版？）");
            System.exit(2);
        }
        System.out.println("[受害用户] 复现成功：注释被剥光，这就是升级前用户手里的 config.yml");
        System.exit(0);
    }

    private static int countComments(File file) throws Exception {
        int n = 0;
        for (String line : Files.readAllLines(file.toPath())) {
            if (line.trim().startsWith("#")) n++;
        }
        return n;
    }

    private static String readVersion(File file) throws Exception {
        for (String line : Files.readAllLines(file.toPath())) {
            if (line.trim().startsWith("config-version:")) return line.trim();
        }
        return "?";
    }
}
