import cn.kerong.bot.provider.BotShared;
import cn.kerong.penguin.spigot.manager.ConfigManager;
import cn.kerong.penguin.spigot.qqbind.MessageTemplates;
import cn.kerong.penguin.spigot.qqbind.QqBindManager;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 1.7.5.2 冒烟（独立 JVM，NeoForge 分发 JAR 类路径 + slf4j-nop）：
 * 1. kick-message 新默认渲染（绑定码口径 + 颜色符 + 占位符）
 * 2. kick-message 自定义列表 / 单行字符串写法 / 键缺失回退
 * 3. ConfigManager v43 → v44 迁移（旧默认收敛 / 自定义不动 / 单行不动）
 * 4. menu 升级表收敛（1.7.5 旧默认 / BOM 容忍 / 幂等 / 无特征自定义不动）
 * 5. KERONG.enforceNoNativeWhitelist 默认方法判定链（关闭 + 提醒 / 两个零操作分支）
 */
public class Smoke1752 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) { check(name, ok, null); }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name + (detail == null ? "" : "  >> " + detail)); }
    }

    static Path writeConfig(File folder, String content) throws Exception {
        folder.mkdirs();
        Path cfg = folder.toPath().resolve("config.yml");
        Files.write(cfg, content.getBytes(StandardCharsets.UTF_8));
        return cfg;
    }

    static String read(File folder) throws Exception {
        return new String(Files.readAllBytes(folder.toPath().resolve("config.yml")), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // 共享一个 fake（QqBindManager 单例绑定首个插件，换配置用 reloadConfig）
        File sharedFolder = Files.createTempDirectory("krg-1752-shared").toFile();
        FakeKeronRuntime shared = new FakeKeronRuntime(sharedFolder);
        writeConfig(sharedFolder, "qq-bind:\n  enabled: true\n");
        shared.reloadConfig();
        BotShared.INSTANCE.setInstance(shared);
        QqBindManager manager = QqBindManager.Companion.getInstance();

        // ---------- 1. kick-message 默认渲染（新绑定码口径） ----------
        writeConfig(sharedFolder, "qq-bind:\n  enabled: true\n  kick-message:\n" +
                "    - \"&c===== QQ 绑定验证 =====\"\n" +
                "    - \"&f你还未绑定 QQ，无法进入服务器\"\n" +
                "    - \"\"\n" +
                "    - \"&e你的专属绑定码: &a&l{code}\"\n" +
                "    - \"&7请在 QQ 群发送: &f{code}\"\n" +
                "    - \"&7（绑定码 {expire} 分钟内有效）\"\n" +
                "  code-expire-minutes: 10\n");
        shared.reloadConfig();
        String rendered = manager.formatKickMessage("56526", "Steve");
        check("T1a 默认渲染：标题与绑定码口径",
                rendered.contains("§c===== QQ 绑定验证 =====")
                        && rendered.contains("你的专属绑定码: §a§l56526")
                        && rendered.contains("请在 QQ 群发送: §f56526")
                        && rendered.contains("（绑定码 10 分钟内有效）"),
                rendered);
        check("T1b 默认渲染：旧验证码口径不再出现",
                !rendered.contains("验证码") && !rendered.contains("可以直接发送纯数字"),
                rendered);

        // ---------- 2a. 单行字符串写法 ----------
        writeConfig(sharedFolder, "qq-bind:\n  enabled: true\n  kick-message: \"&c单行测试\\n&f第二行 {code}\"\n  code-expire-minutes: 10\n");
        shared.reloadConfig();
        String single = manager.formatKickMessage("88888", "Bob");
        check("T2 单行字符串写法按 \\n 分行",
                single.contains("§c单行测试") && single.contains("§f第二行 88888")
                        && single.indexOf("§c单行测试") < single.indexOf("§f第二行"),
                single);

        // ---------- 2b. 自定义列表 ----------
        writeConfig(sharedFolder, "qq-bind:\n  enabled: true\n  kick-message:\n" +
                "    - \"&b欢迎 {name}\"\n    - \"&a你的码 {code}\"\n  code-expire-minutes: 5\n");
        shared.reloadConfig();
        String custom = manager.formatKickMessage("11111", "Cara");
        check("T3 自定义列表完全生效",
                custom.equals("§b欢迎 Cara\n§a你的码 11111"), custom);

        // ---------- 2c. 键缺失 → 内置默认（绑定码口径） ----------
        writeConfig(sharedFolder, "qq-bind:\n  enabled: true\n  code-expire-minutes: 10\n");
        shared.reloadConfig();
        String fallback = manager.formatKickMessage("56526", "Dave");
        check("T4 键缺失回退内置默认（绑定码口径）",
                fallback.contains("你的专属绑定码: §a§l56526")
                        && fallback.contains("请在 QQ 群发送: §f56526")
                        && fallback.contains("（绑定码 10 分钟内有效）"),
                fallback);

        // ---------- 3. ConfigManager v43 → v44 迁移 ----------
        String oldKick172 = "  kick-message:\n" +
                "    - \"&c===== QQ 绑定验证 =====\"\n" +
                "    - \"&f你还未绑定 QQ，无法进入服务器。\"\n" +
                "    - \"\"\n" +
                "    - \"&e你的专属验证码: &a&l{code}\"\n" +
                "    - \"&7可以直接发送纯数字验证码: &f{code}\"\n" +
                "    - \"&7（也可发送 /绑定 {code}；验证码 {expire} 分钟内有效）\"\n";
        String base43 = "config-version: 43\nbot:\n  name: \"MyCustomBot\"\nqq-bind:\n  enabled: true\n" + oldKick172;

        // 3a. 旧默认（1.7.2 验证码版）→ 收敛为新默认
        File m1 = Files.createTempDirectory("krg-1752-m1").toFile();
        writeConfig(m1, base43);
        new ConfigManager(new FakeKeronRuntime(m1)).initialize();
        String after1 = read(m1);
        check("M1a v43 → v44（config-version 升 44）", after1.contains("config-version: 44"), after1.substring(0, Math.min(80, after1.length())));
        check("M1b 旧验证码默认收敛为绑定码新默认",
                after1.contains("\"&e你的专属绑定码: &a&l{code}\"")
                        && after1.contains("\"&7请在 QQ 群发送: &f{code}\"")
                        && !after1.contains("可以直接发送纯数字验证码"),
                after1);
        check("M1c 用户自定义值保留（bot.name）", after1.contains("MyCustomBot"));

        // 3b. 旧默认（v0.1.5.0 绑定码版）→ 同样收敛
        File m2 = Files.createTempDirectory("krg-1752-m2").toFile();
        writeConfig(m2, "config-version: 43\nqq-bind:\n  enabled: true\n  kick-message:\n" +
                "    - \"&c===== QQ 绑定验证 =====\"\n" +
                "    - \"&f你还未绑定 QQ，无法进入服务器。\"\n" +
                "    - \"\"\n" +
                "    - \"&e你的专属绑定码: &a&l{code}\"\n" +
                "    - \"&7请在 QQ 群发送: &f/绑定 {code}\"\n" +
                "    - \"&7（绑定码 {expire} 分钟内有效）\"\n");
        new ConfigManager(new FakeKeronRuntime(m2)).initialize();
        String after2 = read(m2);
        check("M2 v0.1.5.0 绑定码旧默认也收敛为新默认",
                after2.contains("config-version: 44")
                        && after2.contains("\"&7请在 QQ 群发送: &f{code}\"")
                        && !after2.contains("/绑定 {code}"),
                after2);

        // 3c. 自定义 kick-message → 不动
        File m3 = Files.createTempDirectory("krg-1752-m3").toFile();
        writeConfig(m3, "config-version: 43\nqq-bind:\n  enabled: true\n  kick-message:\n" +
                "    - \"&b自定义文案 {code}\"\n    - \"&d第二行\"\n");
        new ConfigManager(new FakeKeronRuntime(m3)).initialize();
        String after3 = read(m3);
        check("M3 自定义 kick-message 不被迁移",
                after3.contains("&b自定义文案 {code}") && after3.contains("&d第二行")
                        && after3.contains("config-version: 44"),
                after3);

        // 3d. 单行字符串 kick-message → 不动（保持字符串形态）
        File m4 = Files.createTempDirectory("krg-1752-m4").toFile();
        writeConfig(m4, "config-version: 43\nqq-bind:\n  enabled: true\n  kick-message: \"&c我的单行\\n&f文案 {code}\"\n");
        new ConfigManager(new FakeKeronRuntime(m4)).initialize();
        String after4 = read(m4);
        check("M4 单行字符串 kick-message 不被迁移",
                after4.contains("我的单行") && after4.contains("config-version: 44")
                        && !after4.contains("你的专属绑定码"),
                after4);

        // ---------- 4. menu 升级表收敛 ----------
        Object templates = MessageTemplates.INSTANCE;
        Method bundled = MessageTemplates.class.getDeclaredMethod("bundledResource", String.class);
        bundled.setAccessible(true);
        String menuDefault = (String) bundled.invoke(templates, "menu");
        Method upgradeMethod = MessageTemplates.class.getDeclaredMethod("upgradedDefaultContents", File.class);
        upgradeMethod.setAccessible(true);

        Path tdir = Files.createTempDirectory("krg-1752-menu");

        // 4a. 1.7.5 旧默认（帮助文段形态）→ 收敛
        Path d1 = Files.createTempDirectory("krg-1752-menu-a");
        Files.write(d1.resolve("menu.md"), ("### 指令菜单\n\n点击下方按钮即可执行；带参数的指令（绑定 / 发信息 / 执行命令等）点击后复制到输入框，补全内容再发送。\n\n---\n**当前插件版本：v{version}**").getBytes(StandardCharsets.UTF_8));
        upgradeMethod.invoke(templates, d1.toFile());
        String menuA = new String(Files.readAllBytes(d1.resolve("menu.md")), StandardCharsets.UTF_8);
        check("S1 1.7.5 旧默认 menu.md 收敛为新默认", menuA.trim().equals(menuDefault.trim()), menuA);

        // 4b. 更旧形态 + BOM → 收敛（BOM 容忍）
        Path d2 = Files.createTempDirectory("krg-1752-menu-b");
        Files.write(d2.resolve("menu.md"), ("﻿### 指令菜单（{page}/{pages}）").getBytes(StandardCharsets.UTF_8));
        upgradeMethod.invoke(templates, d2.toFile());
        String menuB = new String(Files.readAllBytes(d2.resolve("menu.md")), StandardCharsets.UTF_8);
        check("S2 带 BOM 旧形态收敛（normalize 容忍）", menuB.trim().equals(menuDefault.trim()), menuB);

        // 4c. 已是新默认 → 幂等不动
        Path d3 = Files.createTempDirectory("krg-1752-menu-c");
        Files.write(d3.resolve("menu.md"), menuDefault.getBytes(StandardCharsets.UTF_8));
        upgradeMethod.invoke(templates, d3.toFile());
        String menuC = new String(Files.readAllBytes(d3.resolve("menu.md")), StandardCharsets.UTF_8);
        check("S3 已是新默认保持不动（幂等）", menuC.trim().equals(menuDefault.trim()));

        // 4d. 无默认特征的自定义 → 不动
        Path d4 = Files.createTempDirectory("krg-1752-menu-d");
        Files.write(d4.resolve("menu.md"), "## 我的自定义菜单\n\n只有我自己知道的内容".getBytes(StandardCharsets.UTF_8));
        upgradeMethod.invoke(templates, d4.toFile());
        String menuD = new String(Files.readAllBytes(d4.resolve("menu.md")), StandardCharsets.UTF_8);
        check("S4 无默认特征的自定义菜单不动", menuD.equals("## 我的自定义菜单\n\n只有我自己知道的内容"), menuD);

        // 4e. 升级表含 menu 共 5 键
        Field upgradesField = MessageTemplates.class.getDeclaredField("DEFAULT_CONTENT_UPGRADES");
        upgradesField.setAccessible(true);
        Map<String, ?> upgrades = (Map<String, ?>) upgradesField.get(templates);
        check("S5 升级表 5 键且 menu 回表",
                upgrades.size() == 5 && upgrades.containsKey("menu"), "keys=" + upgrades.keySet());

        // ---------- 5. enforceNoNativeWhitelist 默认方法判定链 ----------
        // 绑定开启 + 白名单开着 → 关闭 + 控制台提醒
        writeConfig(sharedFolder, "qq-bind:\n  enabled: true\n");
        shared.reloadConfig();
        shared.nativeWhitelistOn = true;
        shared.disableCalled = false;
        shared.warnings.clear();
        shared.enforceNoNativeWhitelist();
        check("W1 绑定开启且白名单开着 → 自动关闭", shared.disableCalled);
        check("W2 控制台输出提醒文案",
                !shared.warnings.isEmpty() && shared.warnings.get(0).contains("检测到服务器自带白名单")
                        && shared.warnings.get(0).contains("已自动关闭"),
                String.valueOf(shared.warnings));

        // 白名单本就关闭 → 零操作零输出
        shared.nativeWhitelistOn = false;
        shared.disableCalled = false;
        shared.warnings.clear();
        shared.enforceNoNativeWhitelist();
        check("W3 白名单本就关闭 → 零操作零输出", !shared.disableCalled && shared.warnings.isEmpty());

        // 绑定未开启 → 零操作零输出（同一共享 fake，单例实时读它的新配置）
        writeConfig(sharedFolder, "qq-bind:\n  enabled: false\n");
        shared.reloadConfig();
        shared.nativeWhitelistOn = true;
        shared.disableCalled = false;
        shared.warnings.clear();
        shared.enforceNoNativeWhitelist();
        check("W4 绑定未开启 → 零操作零输出", !shared.disableCalled && shared.warnings.isEmpty());

        // ---------- 6. 按钮管理员平台门禁（覆盖版：注册表标记 + 门禁判定 + 权限透传） ----------
        cn.kerong.bot.events.commands.CommandAdminRegistry reg =
                cn.kerong.bot.events.commands.CommandAdminRegistry.INSTANCE;
        // 6.1 注册表自动标记（@Commands adminOnly 单一事实来源）
        boolean adminOk = reg.isAdminCommand("执行命令") && reg.isAdminCommand("管理员执行")
                && reg.isAdminCommand("全量") && reg.isAdminCommand("AI管理")
                && reg.isAdminCommand("黑名单") && reg.isAdminCommand("解除黑名单")
                && reg.isAdminCommand("解除认证");
        check("6.1 注册表标记管理命令（执行命令/管理员执行/全量/AI管理/黑名单/解除黑名单/解除认证）", adminOk);
        boolean publicOk = !reg.isAdminCommand("查在线") && !reg.isAdminCommand("查性能")
                && !reg.isAdminCommand("签到") && !reg.isAdminCommand("绑定")
                && !reg.isAdminCommand("执行") && !reg.isAdminCommand("重新绑定")
                && !reg.isAdminCommand("发信息") && !reg.isAdminCommand("菜单")
                && !reg.isAdminCommand("玩家名片") && !reg.isAdminCommand("认证");
        check("6.2 注册表不误标公共命令（查在线/查性能/签到/绑定/执行/重新绑定/发信息/菜单/玩家名片/认证）", publicOk);

        // 6.3 门禁模式判定（qq/both 开门禁，config 关门禁走名单）
        cn.kerong.bot.events.MenuButtons mb = cn.kerong.bot.events.MenuButtons.INSTANCE;
        shared.adminModeValue = "qq";
        boolean gateQq = mb.adminGateFor(shared, "smoke-group-1");
        shared.adminModeValue = "both";
        boolean gateBoth = mb.adminGateFor(shared, "smoke-group-1");
        shared.adminModeValue = "config";
        boolean gateConfig = mb.adminGateFor(shared, "smoke-group-1");
        check("6.3 门禁判定：qq/both 开门禁、config 关门禁",
                gateQq && gateBoth && !gateConfig,
                "qq=" + gateQq + " both=" + gateBoth + " config=" + gateConfig);

        // 6.4 键盘权限字段透传：permissionType=1 序列化后 permission.type=1（平台门禁）
        //（NeoForge 分发 JAR 内 fastjson 已重定位到 cn.kerong.shaded.com.alibaba.fastjson）
        io.github.kloping.qqbot.entities.ex.Keyboard gatedKb = cn.kerong.bot.tools.Keyboards.INSTANCE.build(
                java.util.Collections.singletonList(new cn.kerong.bot.tools.Keyboards.Button(
                        "黑名单", "黑名单", "krg:acmd:/黑名单", 1, 1, null, 1)));
        String gatedJson = cn.kerong.shaded.com.alibaba.fastjson.JSON.toJSONString(gatedKb);
        io.github.kloping.qqbot.entities.ex.Keyboard openKb = cn.kerong.bot.tools.Keyboards.INSTANCE.build(
                java.util.Collections.singletonList(new cn.kerong.bot.tools.Keyboards.Button(
                        "查在线", "查在线", "krg:cmd:/查在线", 1, 1, null, 2)));
        String openJson = cn.kerong.shaded.com.alibaba.fastjson.JSON.toJSONString(openKb);
        boolean gatedHasPerm1 = gatedJson.contains("\"type\":1") || gatedJson.contains("\"type\": 1");
        boolean openHasPerm2 = openJson.contains("\"type\":2") || openJson.contains("\"type\": 2");
        check("6.4 键盘权限透传：门禁按钮 permission.type=1 / 公开按钮 permission.type=2",
                gatedHasPerm1 && openHasPerm2, "gated=" + gatedJson + " open=" + openJson);

        // 6.5 门禁回调前缀与普通前缀并存（路由分流）
        check("6.5 回调前缀：krg:acmd:（门禁）与 krg:cmd:（公开）",
                cn.kerong.bot.events.MenuButtons.ACMD_PREFIX.equals("krg:acmd:")
                        && cn.kerong.bot.events.MenuButtons.ACTION_PREFIX.equals("krg:cmd:"));

        System.out.println();
        System.out.println("共 " + (passed + failed) + " 项，通过 " + passed + "，失败 " + failed);
        if (failed > 0) System.exit(1);
    }
}
