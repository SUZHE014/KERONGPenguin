import cn.huohuas001.bot.provider.BotShared;
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

/**
 * 1.5.7.2 Geyser/Floodgate 基岩版兼容验证（独立 JVM，走真实 QqBindManager）：
 *
 * 场景：Geyser-Spigot + Floodgate 服务器，基岩玩家 UUID 为 Floodgate UUID
 *（00000000-0000-0000-0009-…，XUID 派生，与名字无关），名字可能带前缀（.Steve）。
 *
 *  G1 新键命中：getOrCreateQuuidByUuid(".Steve", floodgateUuid) 后
 *     findPlayerUuidByName(".Steve") 返回 floodgateUuid（金币入账目标正确）
 *  G2 大小写不敏感：".sTeVe" 同样命中
 *  G3 未绑定玩家：返回 null（调用方退回按名字解析）
 *  G4 Java 与基岩“同名”（Steve 与 .Steve 各自记录）：互不干扰
 *  G5 同名双记录（同名不同 UUID 的两个人）：返回其一（两者均有效，不崩溃）
 *  G6 旧版纯名字键：读主文件 playerUuid 字段
 *  G7 严格 UUID 格式校验：索引键被玩家名括号“污染”时（bob[x][junk]）
 *     查 bob / bob[x] 均返回 null 而不是误截取（不崩溃）
 */
public class GeyserCheck {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean cond, String name) {
        if (cond) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> nameIndex(QqBindManager manager) throws Exception {
        Field field = QqBindManager.class.getDeclaredField("nameToQuuid");
        field.setAccessible(true);
        return (Map<String, String>) field.get(manager);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> clazz) throws Exception {
        sun.misc.Unsafe unsafe;
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = (sun.misc.Unsafe) f.get(null);
        return (T) unsafe.allocateInstance(clazz);
    }

    public static void main(String[] args) throws Exception {
        File base = Files.createTempDirectory("penguin-geyser-test").toFile();
        File dataFolder = new File(base, "KERONGPenguin");
        dataFolder.mkdirs();
        File configFile = new File(dataFolder, "config.yml");
        configFile.createNewFile();

        TestPlugin plugin = allocate(TestPlugin.class);
        plugin.setDataFolder(dataFolder, configFile);
        BotShared.INSTANCE.setInstance(plugin);

        System.out.println("== G. Geyser/Floodgate 基岩版兼容（findPlayerUuidByName）==");
        QqBindManager manager = QqBindManager.Companion.getInstance();

        // 模拟基岩玩家（Floodgate UUID：00000000-0000-0000-0009-xxxx，名字带点前缀）
        String bedrockUuid = "00000000-0000-0000-0009-01f3f7f9a1b2";
        String bedrockName = ".Steve";
        String quuidBedrock = manager.getOrCreateQuuidByUuid(bedrockName, bedrockUuid);
        check(quuidBedrock != null && !quuidBedrock.isEmpty(), "G0 基岩玩家 QUUID 已建立（名字含前缀合法）");

        // G1 新键命中
        String resolved = manager.findPlayerUuidByName(bedrockName);
        check(bedrockUuid.equals(resolved), "G1 基岩玩家按名取到 Floodgate UUID（入账目标正确）");

        // G2 大小写不敏感
        String resolvedCase = manager.findPlayerUuidByName(".sTeVe");
        check(bedrockUuid.equals(resolvedCase), "G2 大小写不敏感命中");

        // G3 未绑定玩家
        check(manager.findPlayerUuidByName("Nobody") == null, "G3 未绑定玩家返回 null");
        check(manager.findPlayerUuidByName(null) == null, "G3 null 名字返回 null");
        check(manager.findPlayerUuidByName("") == null, "G3 空名字返回 null");

        // G4 Java 玩家同名（无前缀）互不干扰
        String javaUuid = "55555555-5555-5555-5555-555555555555";
        manager.getOrCreateQuuidByUuid("Steve", javaUuid);
        check(javaUuid.equals(manager.findPlayerUuidByName("Steve")), "G4 Java 玩家取到自己的 UUID");
        check(bedrockUuid.equals(manager.findPlayerUuidByName(".Steve")), "G4 基岩玩家仍取到 Floodgate UUID（互不串）");

        // G5 同名双记录（同名不同 UUID = 两个人）：返回其一且格式合法
        String twinA = "aaaaaaaa-1111-1111-1111-111111111111";
        String twinB = "aaaaaaaa-2222-2222-2222-222222222222";
        manager.getOrCreateQuuidByUuid("Twin", twinA);
        manager.getOrCreateQuuidByUuid("Twin", twinB);
        String twinResolved = manager.findPlayerUuidByName("Twin");
        check(twinResolved != null && UUID.fromString(twinResolved) != null
                        && (twinResolved.equals(twinA) || twinResolved.equals(twinB)),
                "G5 同名双记录返回其一（两者均有效，不崩溃）");

        // G6 旧版纯名字键 → 主文件 playerUuid 字段
        String legacyUuid = "bbbbbbbb-3333-3333-3333-333333333333";
        String quuidLegacy = manager.getOrCreateQuuidByUuid("LegacyGuy", legacyUuid);
        Map<String, String> index = nameIndex(manager);
        index.remove("legacyguy[" + legacyUuid + "]");
        index.put("legacyguy", quuidLegacy); // 人为构造 1.5.3.1 之前的旧键形态
        String legacyResolved = manager.findPlayerUuidByName("LegacyGuy");
        check(legacyUuid.equals(legacyResolved), "G6 旧版纯名字键读主文件 playerUuid 命中");

        // G7 严格 UUID 格式（防玩家名含括号误截取）
        index.put("bob[x][junk]", quuidLegacy);
        check(manager.findPlayerUuidByName("bob") == null, "G7 名字括号污染：查 bob 不误截取");
        check(manager.findPlayerUuidByName("bob[x]") == null, "G7 名字括号污染：查 bob[x] 不误截取");
        check(manager.findPlayerUuidByName("Bob[X]") == null, "G7 大小写混合同样不误截取");

        // 主文件字段真实存在（G6 的前置 + 基岩玩家落盘）
        File record = new File(new File(dataFolder, "QUUID"), quuidBedrock + ".yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(record);
        check(bedrockUuid.equals(yaml.getString("playerUuid")), "G8 基岩玩家主文件 playerUuid 已落盘");
        check(bedrockName.equals(yaml.getString("playerName")), "G8 基岩玩家主文件 playerName 带前缀原样保存");

        System.out.println();
        System.out.println("结果: " + passed + " 通过, " + failed + " 失败");
        System.exit(failed == 0 ? 0 : 1);
    }
}
