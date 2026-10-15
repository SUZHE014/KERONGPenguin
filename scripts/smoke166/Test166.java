import cn.kerong.bot.UpdateChecker;
import cn.kerong.bot.tools.PluginFileLog;
import cn.kerong.bot.tools.ConsoleColors;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

/**
 * 1.6.6 功能冒烟（独立 JVM，类路径 = NeoForge 1.6.6 分发 JAR + 依赖）。
 *
 * 覆盖：
 *  1. 历史回归：PluginFileLog.stripColorCodes（1.6.4）/ UpdateChecker.isNewer（1.6.2）；
 *  2. 控制台真彩色（1.6.6 核心）：ConsoleColors.toAnsi（§→ANSI 映射、组合码、
 *     行尾重置、未知码剔除）、hasColorCodes、decorateForConsole（无终端类时的
 *     兜底路径）、无码文本零开销路径；
 *  3. 基岩玩家 xuid 解析（1.6.6）：Floodgate UUID（msb=0 + lsb 高 16 位 0x0009）
 *     → xuid；离线 v3 / 正版 v4 不误判；
 *  4. 自定义命令协议（回归 + 1.6.6 收紧）：kerong run 前缀解析为自定义命令、
 *     旧前缀串不再按自定义命令协议处理（原样透传）；
 *  5. 改名后类路径完整性（关键类 Class.forName 无 NoClassDefFoundError）。
 */
public class Test166 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name + "  [" + detail + "]"); }
    }

    public static void main(String[] args) throws Exception {
        // ---------- 1. 历史回归 ----------
        String colored = "§e[更新检查] §f发现新版本：§a§l1.6.6 §7（当前 1.6.5）";
        String stripped = PluginFileLog.INSTANCE.stripColorCodes(colored);
        check("stripColorCodes 剥离全部 § 码",
                !stripped.contains("§") && stripped.contains("[更新检查]") && stripped.contains("1.6.6"),
                stripped);

        Method newer = null;
        for (Method m : UpdateChecker.class.getDeclaredMethods()) {
            if (m.getName().startsWith("isNewer") && m.getParameterCount() == 2) { newer = m; break; }
        }
        check("isNewer 方法存在", newer != null, "-");
        newer.setAccessible(true);
        check("isNewer 1.6.6 > 1.6.5", Boolean.TRUE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.6.6", "1.6.5")), "-");
        check("isNewer 1.6.10 > 1.6.9（多位段）", Boolean.TRUE.equals(newer.invoke(UpdateChecker.INSTANCE, "1.6.10", "1.6.9")), "-");

        // ---------- 2. ConsoleColors（1.6.6 核心） ----------
        String ansi = ConsoleColors.INSTANCE.toAnsi("§e黄色文本");
        check("toAnsi §e → ANSI 亮黄（[93m）",
                ansi.contains("\u001B[93m") && ansi.contains("黄色文本"), ansi.replace("\u001B", "ESC"));

        String combo = ConsoleColors.INSTANCE.toAnsi("§a§l绿粗§r后普通");
        check("toAnsi §a§l 组合 + §r 重置",
                combo.contains("\u001B[92m") && combo.contains("\u001B[1m") && combo.contains("\u001B[0m"),
                combo.replace("\u001B", "ESC"));

        String tail = ConsoleColors.INSTANCE.toAnsi("§b开头蓝但行尾无重置");
        check("toAnsi 行尾自动补重置码",
                tail.endsWith("\u001B[0m") && tail.contains("\u001B[96m"), "-");

        String unknown = ConsoleColors.INSTANCE.toAnsi("§k混淆§e黄");
        check("toAnsi 未知码（§k）剔除不乱码",
                !unknown.contains("§k") && unknown.contains("\u001B[93m") && unknown.contains("黄"), "-");

        check("toAnsi 无码文本原样（零开销路径）",
                "普通文本".equals(ConsoleColors.INSTANCE.toAnsi("普通文本")), "-");

        check("hasColorCodes 正负判定",
                ConsoleColors.INSTANCE.hasColorCodes("§a有码") && !ConsoleColors.INSTANCE.hasColorCodes("无码文本"), "-");

        String decorated = ConsoleColors.INSTANCE.decorateForConsole("§e检查");
        check("decorateForConsole 无终端类环境转 ANSI",
                decorated.contains("\u001B[93m"), decorated.replace("\u001B", "ESC"));
        check("decorateForConsole 无码文本原样",
                "纯文本".equals(ConsoleColors.INSTANCE.decorateForConsole("纯文本")), "-");

        // ---------- 3. floodgateXuid（反射，1.6.6） ----------
        Class<?> assets = Class.forName("cn.kerong.penguin.spigot.render.InfoCardAssets");
        Object assetsInst = assets.getDeclaredField("INSTANCE").get(null);
        Method floodgateXuid = null;
        for (Method m : assets.getDeclaredMethods()) {
            if (m.getName().equals("floodgateXuid")) { floodgateXuid = m; break; }
        }
        check("floodgateXuid 方法存在", floodgateXuid != null, "-");
        floodgateXuid.setAccessible(true);

        // Floodgate 示例：00000000-0000-0000-0009-01f64f65c7c1
        UUID floodgate = new UUID(0L, 0x000901f64f65c7c1L);
        Object xuid = floodgateXuid.invoke(assetsInst, floodgate);
        check("Floodgate UUID → xuid 解析正确",
                xuid instanceof Long && (Long) xuid == 0x01f64f65c7c1L, String.valueOf(xuid));

        UUID notFloodgate = new UUID(0L, 0x000801f64f65c7c1L);
        check("非 Floodgate 标志段（0x0008）不误判",
                floodgateXuid.invoke(assetsInst, notFloodgate) == null, "-");

        UUID offline = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes());
        check("离线 v3 UUID 不误判",
                floodgateXuid.invoke(assetsInst, offline) == null, "-");

        UUID premium = UUID.randomUUID();
        check("正版 v4 UUID 不误判",
                floodgateXuid.invoke(assetsInst, premium) == null, "-");

        UUID floodgate2 = new UUID(0L, 0x0009FFFFFFFFFFFFL);
        Object xuid2 = floodgateXuid.invoke(assetsInst, floodgate2);
        check("Floodgate 最大 xuid（48 位全 1）",
                xuid2 instanceof Long && (Long) xuid2 == 0xFFFFFFFFFFFFL, String.valueOf(xuid2));

        // ---------- 4. 自定义命令协议（回归 + 1.6.6 收紧） ----------
        Class<?> ccr = Class.forName("cn.kerong.bot.events.commands.CustomCommandRegistry");
        Object ccrInst = ccr.getDeclaredField("INSTANCE").get(null);
        Method replace = null, resolve = null;
        for (Method m : ccr.getDeclaredMethods()) {
            if (m.getName().equals("replace") && m.getParameterCount() == 1) replace = m;
            if (m.getName().equals("resolve") && m.getParameterCount() == 1) resolve = m;
        }
        check("CustomCommandRegistry replace/resolve 存在", replace != null && resolve != null, "-");
        resolve.setAccessible(true);
        replace.setAccessible(true);

        Class<?> detailCls = Class.forName("cn.kerong.bot.provider.CustomCommandDetail");
        Object detail = detailCls.getDeclaredConstructor(String.class, String.class, int.class)
                .newInstance("测试键", "say {user} 触发了 {params}", Integer.valueOf(0));
        replace.invoke(ccrInst, Collections.singletonList(detail));

        Object resolved = resolve.invoke(ccrInst, "kerong run 12345 67890 测试键 参数A");
        String resolvedText = String.valueOf(resolved);
        check("kerong run 协议正常解析",
                resolvedText.contains("67890") && resolvedText.contains("参数A"), resolvedText);

        Object passthrough = resolve.invoke(ccrInst, "huhobot run 12345 67890 测试键 参数A");
        String ptText = String.valueOf(passthrough);
        check("旧前缀串不再按自定义命令协议处理（原样透传）",
                ptText.contains("huhobot") && !ptText.contains("67890 触发"), ptText);

        // ---------- 5. 改名后类路径完整性 ----------
        for (String cls : new String[]{
                "cn.kerong.bot.KERONG",
                "cn.kerong.penguin.spigot.stats.PapiDependencyCheck",
                "cn.kerong.penguin.spigot.stats.PlayTimeSync",
                "cn.kerong.bot.events.commands.CustomCommandRegistry",
                "cn.kerong.penguin.spigot.render.InfoCardAssets",
                "cn.kerong.bot.UpdateChecker",
        }) {
            boolean loaded = false;
            try { Class.forName(cls); loaded = true; } catch (Throwable t) { }
            check("类加载 " + cls.substring(cls.lastIndexOf('.') + 1), loaded, cls);
        }

        System.out.println("\n===== 冒烟结果：" + passed + "/" + (passed + failed) + " 通过，" + failed + " 失败 =====");
        System.exit(failed == 0 ? 0 : 1);
    }
}
