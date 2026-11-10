import cn.kerong.penguin.spigot.qqbind.MessageTemplates;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 冒烟：1.7.5.1 覆盖版（menu 系统模板强制重置——用户服务器 menu.md 带历史
 * 帮助文段未被特征匹配收敛，改为无条件重置 + .bak 备份）。
 */
public class Smoke1751o2 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) { check(name, ok, null); }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name + (detail == null ? "" : "  " + detail)); }
    }

    static void write(Path dir, String name, String content) throws Exception {
        Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    static String read(Path dir, String name) throws Exception {
        return new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Object templates = MessageTemplates.INSTANCE;

        // 反演内置默认（resetSystemTemplates 的收敛目标）
        Method bundled = MessageTemplates.class.getDeclaredMethod("bundledResource", String.class);
        bundled.setAccessible(true);
        String menuDefault = (String) bundled.invoke(templates, "menu");
        check("S0 内置 menu 默认可读（标题+版本行）",
                menuDefault != null && menuDefault.contains("### 指令菜单（{page}/{pages}）")
                        && menuDefault.contains("**当前插件版本：v{version}**")
                        && !menuDefault.contains("点击下方按钮"),
                menuDefault);

        Method reset = MessageTemplates.class.getDeclaredMethod("resetSystemTemplates", File.class);
        reset.setAccessible(true);

        // ---------- 1. 未知历史形态（特征匹配覆盖不了的）也强制重置 ----------
        Path dir = Files.createTempDirectory("krg-smoke-1751o2");
        String unknownOld = "### 指令菜单\n\n/查在线 —— 查询在线玩家\n/帮助 —— 查看帮助\n/绑定 —— 绑定QQ\n\n**当前插件版本：v1.7.4**";
        write(dir, "menu.md", unknownOld);
        reset.invoke(templates, dir.toFile());
        check("S1 未知历史形态 menu.md 强制重置为默认",
                read(dir, "menu.md").equals(menuDefault), read(dir, "menu.md"));
        check("S2 旧内容备份为 menu.md.bak",
                read(dir, "menu.md.bak").equals(unknownOld));

        // ---------- 2. 已知旧默认（v1 一轮形态）同样重置 ----------
        write(dir, "menu.md", "### 指令菜单\n\n点击下方按钮即可执行；带参数的指令（绑定 / 发信息 / 执行命令等）点击后复制到输入框，补全内容再发送。\n\n---\n**当前插件版本：v{version}**");
        reset.invoke(templates, dir.toFile());
        check("S3 已知旧默认（v1）重置为默认", read(dir, "menu.md").equals(menuDefault));

        // ---------- 3. 幂等：已是默认 → 不再写、不动备份 ----------
        write(dir, "menu.md", menuDefault);
        write(dir, "menu.md.bak", "历史备份内容");
        reset.invoke(templates, dir.toFile());
        check("S4 已是默认保持不动（幂等）", read(dir, "menu.md").equals(menuDefault));
        check("S5 幂等路径不覆盖既有备份", read(dir, "menu.md.bak").equals("历史备份内容"));

        // ---------- 4. 缺文件 → 跳过（释放逻辑负责写入） ----------
        Files.delete(dir.resolve("menu.md"));
        reset.invoke(templates, dir.toFile());
        check("S6 缺文件时不创建 menu.md", !Files.exists(dir.resolve("menu.md")));

        // ---------- 5. 空文件形态也重置 ----------
        write(dir, "menu.md", "");
        reset.invoke(templates, dir.toFile());
        check("S7 空文件重置为默认", read(dir, "menu.md").equals(menuDefault));

        // ---------- 6. 升级表 menu 条目移除、其余保留 ----------
        Field upgradesField = MessageTemplates.class.getDeclaredField("DEFAULT_CONTENT_UPGRADES");
        upgradesField.setAccessible(true);
        Map<String, ?> upgrades = (Map<String, ?>) upgradesField.get(templates);
        check("S8 升级表 4 键且不含 menu",
                upgrades.size() == 4 && !upgrades.containsKey("menu"),
                "keys=" + upgrades.keySet());
        check("S9 unblacklist-list / openid-lookup / ai-context-usage / full-amount-toggled 保留",
                upgrades.containsKey("unblacklist-list") && upgrades.containsKey("openid-lookup")
                        && upgrades.containsKey("ai-context-usage") && upgrades.containsKey("full-amount-toggled"));

        // ---------- 7. 其他模板的特征升级不受影响（回归） ----------
        write(dir, "unblacklist-list.md", "黑名单列表（{count} 个）：\n{list}\n\n中间态默认：点按钮解除吧");
        Method upgradeMethod = MessageTemplates.class.getDeclaredMethod("upgradedDefaultContents", File.class);
        upgradeMethod.setAccessible(true);
        upgradeMethod.invoke(templates, dir.toFile());
        check("S10 unblacklist 特征升级仍生效",
                read(dir, "unblacklist-list.md").trim().equals(
                        "黑名单列表（{count} 个）：\n{list}\n\n发送 /解除黑名单 @对应的人 解除；也可以点击下方对应的按钮解除"),
                read(dir, "unblacklist-list.md"));

        // ---------- 8. render 出口（菜单路径整体文案） ----------
        String rendered = MessageTemplates.INSTANCE.render("menu", Map.of(
                "page", "1", "pages", "3", "version", "1.7.5.1"));
        check("S11 render(menu) 标题+版本、无帮助文段",
                rendered.contains("### 指令菜单（1/3）")
                        && rendered.contains("**当前插件版本：v1.7.5.1**")
                        && !rendered.contains("点击下方按钮") && !rendered.contains("帮助"),
                rendered);

        System.out.println("================================");
        System.out.println("共 " + (passed + failed) + " 项，通过 " + passed + "，失败 " + failed);
        if (failed > 0) System.exit(1);
    }
}
