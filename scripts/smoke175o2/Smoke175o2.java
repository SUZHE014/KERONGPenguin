import cn.kerong.bot.events.MenuButtons;
import cn.kerong.bot.events.InteractionAcker;
import cn.kerong.bot.tools.Keyboards;
import cn.kerong.penguin.spigot.qqbind.MessageTemplates;
import com.alibaba.fastjson.JSON;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 冒烟：1.7.5 覆盖第二轮（菜单回调按钮 / 回应 PUT 直发 / 模板收敛 / 布局序列化）。
 */
public class Smoke175o2 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) { check(name, ok, null); }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name + (detail == null ? "" : "  " + detail)); }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // ---------- 1. 键盘序列化：一行一个 + 回调类型 + 无 enter ----------
        List<Keyboards.Button> pageCommands = Arrays.asList(
                new Keyboards.Button("查在线", "查在线", "krg:cmd:/查在线", 1, 1, null),
                new Keyboards.Button("查性能", "查性能", "krg:cmd:/查性能", 1, 1, null),
                new Keyboards.Button("玩家名片", "玩家名片", "krg:cmd:/玩家名片", 1, 1, null),
                new Keyboards.Button("查信息", "查信息", "krg:cmd:/查信息", 1, 1, null));
        List<Keyboards.Button> nav = Arrays.asList(
                new Keyboards.Button("← 上一页", "← 上一页", "krg:menu:page:0", 0, 1, null),
                new Keyboards.Button("下一页 →", "下一页 →", "krg:menu:page:2", 1, 1, null));
        Object keyboard = Keyboards.INSTANCE.buildRows(Arrays.asList(
                Arrays.asList(pageCommands.get(0)),
                Arrays.asList(pageCommands.get(1)),
                Arrays.asList(pageCommands.get(2)),
                Arrays.asList(pageCommands.get(3)),
                nav));
        String json = JSON.toJSONString(keyboard);
        check("S1 布局 5 行（4 命令各一行 + 翻页行）", json.contains("\"rows\":[") && countRows(json) == 5, json);
        check("S2 菜单命令按钮 type=1（回调）", json.contains("\"type\":1"));
        check("S3 菜单按钮无 enter:true（SDK 默认序列化 enter:false，QQ 对回调按钮忽略该字段）", !json.contains("\"enter\":true"));
        check("S4 按钮带 unsupport_tips 与权限 2", json.contains("unsupport_tips") && json.contains("\"type\":2"));
        check("S5 菜单按钮 data 带 krg:cmd: 前缀", json.contains("krg:cmd:/查在线"));

        // 旧型指令按钮（type=2 + enter）序列化保持通用能力（当前无调用方）
        Object cmd2 = Keyboards.INSTANCE.build(Arrays.asList(
                new Keyboards.Button("填框", "填框", "/绑定 ", 1, 2, true)));
        String json2 = JSON.toJSONString(cmd2);
        check("S6 指令按钮（未用形态）enter 序列化保持", json2.contains("\"type\":2") && json2.contains("\"enter\":true"));

        // 竖排：一行一个
        Object vertical = Keyboards.INSTANCE.buildVertical(Arrays.asList(
                new Keyboards.Button("开启上下文", "开启上下文", "krg:cmd:/AI管理 开", 1, 1, null),
                new Keyboards.Button("关闭上下文", "关闭上下文", "krg:cmd:/AI管理 关", 1, 1, null),
                new Keyboards.Button("清除上下文", "清除上下文", "krg:cmd:/AI管理 清除", 1, 1, null)));
        String jsonV = JSON.toJSONString(vertical);
        check("S7 竖排 3 行（AI 管理按钮）", countRows(jsonV) == 3, jsonV);
        check("S8 AI 按钮回调 data", jsonV.contains("krg:cmd:/AI管理 开") && jsonV.contains("krg:cmd:/AI管理 清除"));

        // 解除黑名单按钮（默认回调）无 enter
        Object unban = Keyboards.INSTANCE.buildVertical(Arrays.asList(
                new Keyboards.Button("解除 张三", "已解除 张三", "krg:unban:o123", 1, 1, null)));
        String jsonU = JSON.toJSONString(unban);
        check("S9 解除按钮 type=1 无 enter:true", jsonU.contains("\"type\":1") && !jsonU.contains("\"enter\":true"));

        // ---------- 2. MenuButtons.onInteraction 行为 ----------
        final AtomicInteger code = new AtomicInteger(-1);
        kotlin.jvm.functions.Function1<Integer, kotlin.Unit> respond = c -> {
            code.set(c);
            return kotlin.Unit.INSTANCE;
        };

        Object r1 = MenuButtons.INSTANCE.onInteraction("g1", "u1", "krg:menu:page:1", respond);
        check("S10 翻页按钮接管且回应 0", Boolean.TRUE.equals(r1) && code.get() == 0, "code=" + code.get());

        code.set(-1);
        Object r2 = MenuButtons.INSTANCE.onInteraction("g1", "u1", "krg:cmd:/查在线", respond);
        check("S11 命令按钮接管且回应 0（分发在会话就绪后进行）", Boolean.TRUE.equals(r2) && code.get() == 0, "code=" + code.get());

        code.set(-1);
        Object r3 = MenuButtons.INSTANCE.onInteraction("g1", "", "krg:cmd:/黑名单", respond);
        check("S12 无点击者身份回应 4（没有权限）", Boolean.TRUE.equals(r3) && code.get() == 4, "code=" + code.get());

        code.set(-1);
        Object r4 = MenuButtons.INSTANCE.onInteraction("g1", "u1", "krg:other:x", respond);
        check("S13 非本流程前缀返回 null", r4 == null, "r4=" + r4);

        code.set(-1);
        Object r5 = MenuButtons.INSTANCE.onInteraction("g1", "u1", "krg:cmd:", respond);
        check("S14 空命令回应 1", Boolean.TRUE.equals(r5) && code.get() == 1, "code=" + code.get());

        // ---------- 3. 模板收敛端到端 ----------
        Object templates = MessageTemplates.INSTANCE;
        Field upgradesField = MessageTemplates.class.getDeclaredField("DEFAULT_CONTENT_UPGRADES");
        upgradesField.setAccessible(true);
        Map<String, ?> upgrades = (Map<String, ?>) upgradesField.get(templates);
        check("S15 收敛表 4 键", upgrades.size() == 4, "keys=" + upgrades.keySet());
        check("S16 收敛表含 menu/ai/unblacklist/openid",
                upgrades.containsKey("menu") && upgrades.containsKey("ai-context-usage")
                        && upgrades.containsKey("unblacklist-list") && upgrades.containsKey("openid-lookup"));

        Path dir = Files.createTempDirectory("krg-smoke-md");
        // 旧默认 → 应升级
        write(dir, "menu.md", "### 指令菜单\n\n点击下方按钮即可执行；带参数的指令（绑定 / 发信息 / 执行命令等）点击后复制到输入框，补全内容再发送。\n\n---\n**当前插件版本：v{version}**");
        write(dir, "ai-context-usage.md", "用法: /AI对话上下文 开|关\n当前状态: {status}");
        write(dir, "unblacklist-list.md", "黑名单列表（{count} 个）：\n{list}\n\n解除请发送: /解除黑名单 @QQ");
        // 用户自定义 → 不动
        write(dir, "blacklist-list.md", "我的自定义黑名单 {count}");
        Method upgradeMethod = MessageTemplates.class.getDeclaredMethod("upgradedDefaultContents", File.class);
        upgradeMethod.setAccessible(true);
        upgradeMethod.invoke(templates, dir.toFile());
        check("S17 旧默认 menu.md 升级到分页标题",
                read(dir, "menu.md").trim().equals("### 指令菜单（{page}/{pages}）"), read(dir, "menu.md"));
        check("S18 旧命令名 ai-context-usage.md 升级",
                read(dir, "ai-context-usage.md").trim().equals("当前 AI 上下文状态：{status}"), read(dir, "ai-context-usage.md"));
        check("S19 旧默认 unblacklist-list.md 升级带按钮提示",
                read(dir, "unblacklist-list.md").contains("可以按下对应的按钮"), read(dir, "unblacklist-list.md"));
        check("S20 自定义模板不动", read(dir, "blacklist-list.md").equals("我的自定义黑名单 {count}"));
        // 幂等：再跑一次不变
        upgradeMethod.invoke(templates, dir.toFile());
        check("S21 收敛幂等", read(dir, "menu.md").trim().equals("### 指令菜单（{page}/{pages}）"));

        // ---------- 4. InteractionAcker 防御 ----------
        check("S22 空 interactionId 直接 false", !InteractionAcker.INSTANCE.respond("", 0));
        check("S23 会话未就绪 false（无网络调用）", !InteractionAcker.INSTANCE.respond("abc-123", 0));

        System.out.println();
        System.out.println("===== 冒烟 smoke175o2：" + (passed + failed) + " 项，通过 " + passed + "，失败 " + failed + " =====");
        if (failed > 0) System.exit(1);
    }

    static int countRows(String json) {
        int count = 0, idx = 0;
        String marker = "\"buttons\":[";
        while ((idx = json.indexOf(marker, idx)) >= 0) { count++; idx += marker.length(); }
        return count;
    }

    static void write(Path dir, String name, String content) throws Exception {
        Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    static String read(Path dir, String name) throws Exception {
        return new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8);
    }
}
