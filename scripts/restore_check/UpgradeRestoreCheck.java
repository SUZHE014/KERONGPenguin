import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

/**
 * 阶段 B：受害用户换上新版（线上部署的）1.7.2 JAR 后首次启动——
 * ConfigManager.initialize() → ConfigFileRebuilder.rebuildIfNeeded(v30)。
 *
 * 验证「已丢失的注释是否会回来」：
 *  R1  重建执行成功（v29 < v30 触发）
 *  R2  注释回来（> 150 行；重建前受害文件为 0 行）
 *  R3  config-version 升到 30
 *  R4  用户设置保留（五个背景开关 false）
 *  R5  用户自定义值保留（blur-strength 77 / serverName MyCustomServer）
 *  R6  首发默认灰度 142 原样保留（收敛只针对第九轮默认 96）
 *  R7  不再生成 config.yml.bak
 *  R8  重建后再做运行期保存（新注释保留机制）注释不丢
 *  R9  变体：第九轮默认 96 的受害文件 → 重建后收敛为 80
 *  R10 96 变体重建后注释同样回来
 */
public class UpgradeRestoreCheck extends org.bukkit.plugin.java.JavaPlugin {

    private final File dataFolder;

    public UpgradeRestoreCheck(File dataFolder) {
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

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean cond) {
        check(name, cond, "");
    }

    static void check(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : " — " + detail));
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : " — " + detail));
        }
    }

    public static void main(String[] args) throws Exception {
        // ---- 场景一：首发版受害文件（title-card-gray 142） ----
        String victim = Files.readString(new File(args[0]).toPath(), StandardCharsets.UTF_8);
        File dir = new File(args[1]);
        if (dir.exists()) {
            for (File f : dir.listFiles()) f.delete();
        }
        dir.mkdirs();
        File cfg = new File(dir, "config.yml");
        Files.write(cfg.toPath(), victim.getBytes(StandardCharsets.UTF_8));

        int before = countComments(cfg);
        System.out.println("[升级后首次启动] 重建前（受害文件）注释行 = " + before);

        UpgradeRestoreCheck fake = new UpgradeRestoreCheck(dir);
        Class<?> reb = Class.forName("cn.kerong.penguin.spigot.manager.ConfigFileRebuilder");
        Object inst = reb.getField("INSTANCE").get(null);
        Boolean rebuilt = (Boolean) reb.getMethod(
                "rebuildIfNeeded",
                org.bukkit.plugin.java.JavaPlugin.class,
                int.class,
                java.util.List.class,
                java.util.List.class)
                .invoke(inst, fake, 30, Collections.emptyList(), Collections.emptyList());

        String out = Files.readString(cfg.toPath(), StandardCharsets.UTF_8);
        int after = countComments(cfg);
        check("R1 v29→v30 重建执行成功", Boolean.TRUE.equals(rebuilt));
        check("R2 已丢失的注释回来了", after > 150, "重建前 " + before + " 行 → 重建后 " + after + " 行");
        check("R3 config-version 升到 30", out.contains("config-version: 30"));
        check("R4 用户设置保留（五个背景开关 false）",
                out.split("builtin-background: false", -1).length - 1 == 5);
        check("R5 用户自定义值保留（blur-strength 77 / serverName MyCustomServer）",
                out.contains("blur-strength: 77") && out.contains("MyCustomServer"));
        check("R6 首发默认灰度 142 原样保留（仅 96 收敛）", out.contains("title-card-gray: 142"));
        check("R7 无备份文件生成", !new File(dir, "config.yml.bak").exists());

        // ---- 重建之后再做一次运行期保存：新注释保留机制不再剥注释 ----
        fake.reloadConfig();
        fake.getConfig().set("render.blur-strength", 66);
        fake.saveConfig();
        String out2 = Files.readString(cfg.toPath(), StandardCharsets.UTF_8);
        check("R8 重建后再保存（bpngsz 等场景）注释仍在 + 新值写入",
                countComments(cfg) > 150 && out2.contains("blur-strength: 66"),
                "注释 " + countComments(cfg) + " 行");

        // ---- 场景二：第九轮默认 96 的受害文件 → 收敛为 80 ----
        File dir96 = new File(args[1] + "-v96");
        if (dir96.exists()) {
            for (File f : dir96.listFiles()) f.delete();
        }
        dir96.mkdirs();
        File cfg96 = new File(dir96, "config.yml");
        String victim96 = victim.replace("title-card-gray: 142", "title-card-gray: 96");
        Files.write(cfg96.toPath(), victim96.getBytes(StandardCharsets.UTF_8));

        UpgradeRestoreCheck fake96 = new UpgradeRestoreCheck(dir96);
        Boolean rebuilt96 = (Boolean) reb.getMethod(
                "rebuildIfNeeded",
                org.bukkit.plugin.java.JavaPlugin.class,
                int.class,
                java.util.List.class,
                java.util.List.class)
                .invoke(inst, fake96, 30, Collections.emptyList(), Collections.emptyList());
        String out96 = Files.readString(cfg96.toPath(), StandardCharsets.UTF_8);
        check("R9 第九轮旧默认 96 → 重建后自动收敛为 80",
                Boolean.TRUE.equals(rebuilt96) && out96.contains("title-card-gray: 80"));
        check("R10 96 变体重建后注释同样回来", countComments(cfg96) > 150,
                "注释 " + countComments(cfg96) + " 行");

        System.out.println();
        System.out.println("重建头几行预览：");
        for (String line : out.split("\n", 8)) {
            System.out.println("    | " + line);
        }
        System.out.println();
        System.out.println("总计 " + (pass + fail) + " 项，通过 " + pass + " 项，失败 " + fail + " 项");
        System.exit(fail > 0 ? 1 : 0);
    }

    private static int countComments(File file) throws Exception {
        int n = 0;
        for (String line : Files.readAllLines(file.toPath())) {
            if (line.trim().startsWith("#")) n++;
        }
        return n;
    }
}
