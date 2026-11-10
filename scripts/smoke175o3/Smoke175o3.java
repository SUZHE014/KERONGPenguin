import cn.kerong.bot.ReplySeqGuard;
import cn.kerong.bot.events.MenuButtons;
import cn.kerong.bot.events.RecentGroupMessages;
import cn.kerong.bot.state.HumanReadableStateFile;
import cn.kerong.bot.datapack.StoredCommandSettings;
import cn.kerong.bot.tools.Keyboards;
import cn.kerong.penguin.spigot.qqbind.MessageTemplates;
import com.alibaba.fastjson.JSON;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 冒烟：1.7.5 覆盖第三轮（回调真根因 group_id 相关运行时部件 /
 * 按钮按需补参分流 / 模板收敛 / 群级全量开关删除 / 序号守卫）。
 */
public class Smoke175o3 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) { check(name, ok, null); }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name + (detail == null ? "" : "  " + detail)); }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // ---------- 1. RecentGroupMessages：登记 / 读取 / TTL / 边界 ----------
        RecentGroupMessages rgm = RecentGroupMessages.INSTANCE;
        rgm.clear();
        check("R1 未登记群返回 null", rgm.latestId("g-none") == null);
        rgm.record("g1", "m1");
        check("R2 登记后可读", "m1".equals(rgm.latestId("g1")));
        rgm.record("g1", null);
        rgm.record(null, "mx");
        rgm.record("g1", "");
        check("R3 空 id/空群忽略（值不变）", "m1".equals(rgm.latestId("g1")));
        rgm.record("g2", "m2");
        check("R4 群间隔离", "m2".equals(rgm.latestId("g2")) && "m1".equals(rgm.latestId("g1")));
        // TTL 过期：私造一条 5 分钟前的 Entry 塞进登记表（不等待真实时间）
        Field latestField = RecentGroupMessages.class.getDeclaredField("latest");
        latestField.setAccessible(true);
        ConcurrentHashMap<String, Object> latest = (ConcurrentHashMap<String, Object>) latestField.get(rgm);
        Class<?> entryCls = Class.forName("cn.kerong.bot.events.RecentGroupMessages$Entry");
        Constructor<?> entryCtor = entryCls.getDeclaredConstructor(String.class, long.class);
        entryCtor.setAccessible(true);
        Object stale = entryCtor.newInstance("m-stale", System.currentTimeMillis() - 5L * 60L * 1000L);
        latest.put("g-stale", stale);
        check("R5 过期条目读为 null", rgm.latestId("g-stale") == null);
        check("R6 过期条目惰性清理（读后移除）", !latest.containsKey("g-stale"));
        rgm.clear();
        check("R7 clear 清空", rgm.latestId("g1") == null && latest.isEmpty());

        // ---------- 2. pageKeyboard：需补参命令 actionType=2 复制进输入栏 ----------
        Method pageKeyboard = MenuButtons.class.getDeclaredMethod("pageKeyboard", List.class, int.class);
        pageKeyboard.setAccessible(true);
        List<kotlin.Pair<String, String>> page1 = Arrays.asList(
                new kotlin.Pair<>("查在线", "/查在线"),
                new kotlin.Pair<>("绑定", "/绑定"),
                new kotlin.Pair<>("执行命令", "/执行命令"),
                new kotlin.Pair<>("查性能", "/查性能"));
        Object kb1 = pageKeyboard.invoke(MenuButtons.INSTANCE, page1, 0);
        String j1 = JSON.toJSONString(kb1);
        check("P1 单页 4 行无翻页行", countRows(j1) == 4, j1);
        check("P2 无参命令 type=1 回调", btnAction(j1, "查在线").getIntValue("type") == 1, btnAction(j1, "查在线").toJSONString());
        check("P3 无参命令 data 带 krg:cmd: 前缀",
                "krg:cmd:/查在线".equals(btnAction(j1, "查在线").getString("data")));
        check("P4 绑定 type=2 指令按钮", btnAction(j1, "绑定").getIntValue("type") == 2, btnAction(j1, "绑定").toJSONString());
        check("P5 绑定 data 为裸命令（复制进输入栏，无前缀）",
                "/绑定".equals(btnAction(j1, "绑定").getString("data")), btnAction(j1, "绑定").toJSONString());
        check("P6 执行命令 type=2 裸命令",
                btnAction(j1, "执行命令").getIntValue("type") == 2
                        && "/执行命令".equals(btnAction(j1, "执行命令").getString("data")));
        check("P7 查性能 type=1 回调带前缀",
                btnAction(j1, "查性能").getIntValue("type") == 1
                        && "krg:cmd:/查性能".equals(btnAction(j1, "查性能").getString("data")));
        check("P8 全键盘无 enter:true", !j1.contains("\"enter\":true"), j1);
        // 多页翻页
        List<kotlin.Pair<String, String>> page2 = Arrays.asList(
                new kotlin.Pair<>("查在线", "/查在线"),
                new kotlin.Pair<>("绑定", "/绑定"),
                new kotlin.Pair<>("执行命令", "/执行命令"),
                new kotlin.Pair<>("查性能", "/查性能"),
                new kotlin.Pair<>("发信息", "/发信息"));
        Object kb2a = pageKeyboard.invoke(MenuButtons.INSTANCE, page2, 0);
        String j2a = JSON.toJSONString(kb2a);
        check("P9 多页首页 4 命令 + 翻页行", countRows(j2a) == 5, j2a);
        check("P10 首页翻页 data=krg:menu:page:1", j2a.contains("krg:menu:page:1"));
        Object kb2b = pageKeyboard.invoke(MenuButtons.INSTANCE, page2, 1);
        String j2b = JSON.toJSONString(kb2b);
        check("P11 末页 1 命令 + 上一页行", countRows(j2b) == 2, j2b);
        check("P12 末页发信息 type=2 裸命令",
                btnAction(j2b, "发信息").getIntValue("type") == 2
                        && "/发信息".equals(btnAction(j2b, "发信息").getString("data")), j2b);
        check("P13 末页上一页 data=krg:menu:page:0", j2b.contains("krg:menu:page:0"));

        // ---------- 3. Keyboards 指令按钮序列化（type=2 无 enter） ----------
        Object kbInput = Keyboards.INSTANCE.build(Arrays.asList(
                new Keyboards.Button("绑定", "绑定", "/绑定 ", 1, 2, null)));
        String jk = JSON.toJSONString(kbInput);
        check("K1 指令按钮 type=2 序列化", jk.contains("\"type\":2"), jk);
        check("K2 enter=null 不序列化 enter:true", !jk.contains("\"enter\":true"), jk);

        // ---------- 4. 模板收敛端到端（第三轮新口径） ----------
        Object templates = MessageTemplates.INSTANCE;
        Field upgradesField = MessageTemplates.class.getDeclaredField("DEFAULT_CONTENT_UPGRADES");
        upgradesField.setAccessible(true);
        Map<String, ?> upgrades = (Map<String, ?>) upgradesField.get(templates);
        check("T1 收敛表 5 键（新增 full-amount-toggled）", upgrades.size() == 5,
                "keys=" + upgrades.keySet());
        check("T2 收敛表含 full-amount-toggled", upgrades.containsKey("full-amount-toggled"));

        Path dir = Files.createTempDirectory("krg-smoke-o3");
        // 二轮纯标题旧默认 menu → 升级到 标题+版本行
        write(dir, "menu.md", "### 指令菜单（{page}/{pages}）");
        // 二轮旧默认 unblacklist-list
        write(dir, "unblacklist-list.md", "黑名单列表（{count} 个）：\n{list}\n\n可以按下对应的按钮，或者艾特对方发送 /解除黑名单 解除");
        // 群级旧默认 full-amount-toggled
        write(dir, "full-amount-toggled.md", "本群全量转发已{state}");
        // 用户自定义 → 不动
        write(dir, "blacklist-list.md", "我的自定义黑名单 {count}");
        write(dir, "custom-menu.md", "自定义菜单 {page}");
        Method upgradeMethod = MessageTemplates.class.getDeclaredMethod("upgradedDefaultContents", File.class);
        upgradeMethod.setAccessible(true);
        upgradeMethod.invoke(templates, dir.toFile());
        String expectedMenu = "### 指令菜单（{page}/{pages}）\n\n**当前插件版本：v{version}**";
        check("T3 二轮旧默认 menu 升级到标题+版本行",
                read(dir, "menu.md").trim().equals(expectedMenu), read(dir, "menu.md"));
        String expectedUnb = "黑名单列表（{count} 个）：\n{list}\n\n发送 /解除黑名单 @对应的人 解除；也可以点击下方对应的按钮解除";
        check("T4 二轮旧默认 unblacklist 升级到命令在前口径",
                read(dir, "unblacklist-list.md").trim().equals(expectedUnb), read(dir, "unblacklist-list.md"));
        check("T5 群级旧默认 full-amount 升级到总开关口径",
                read(dir, "full-amount-toggled.md").trim()
                        .equals("全量转发已{state}（总开关，全部配置群统一生效{writeResult}）"),
                read(dir, "full-amount-toggled.md"));
        check("T6 自定义模板不动",
                read(dir, "blacklist-list.md").equals("我的自定义黑名单 {count}")
                        && read(dir, "custom-menu.md").equals("自定义菜单 {page}"));
        // 一轮讲解版旧默认 → 同样升级（收敛表含多代旧默认）
        write(dir, "menu.md", "### 指令菜单\n\n点击下方按钮即可执行；带参数的指令（绑定 / 发信息 / 执行命令等）点击后复制到输入框，补全内容再发送。\n\n---\n**当前插件版本：v{version}**");
        write(dir, "unblacklist-list.md", "黑名单列表（{count} 个）：\n{list}\n\n点击下方按钮即可解除对应条目（也可发送 /解除黑名单 @QQ）");
        upgradeMethod.invoke(templates, dir.toFile());
        check("T7 一轮讲解版 menu 同样升级",
                read(dir, "menu.md").trim().equals(expectedMenu), read(dir, "menu.md"));
        check("T8 1.7.4 版 unblacklist 同样升级",
                read(dir, "unblacklist-list.md").trim().equals(expectedUnb), read(dir, "unblacklist-list.md"));
        // 幂等
        upgradeMethod.invoke(templates, dir.toFile());
        check("T9 收敛幂等", read(dir, "menu.md").trim().equals(expectedMenu)
                && read(dir, "full-amount-toggled.md").trim()
                        .equals("全量转发已{state}（总开关，全部配置群统一生效{writeResult}）"));

        // ---------- 5. HumanReadableStateFile：[full-forwarding] 段淘汰 ----------
        Path stateDir = Files.createTempDirectory("krg-smoke-state");
        write(stateDir, "command-state.ini", String.join("\n",
                "# KERONG 群命令运行状态",
                "",
                "[administrators]",
                "g1 = u1,u2",
                "",
                "[authenticated-users]",
                "g1 = u3",
                "",
                "[administrator-modes]",
                "g1 = QQ",
                "",
                "[full-forwarding]",
                "g1 = true",
                "g2 = false",
                ""));
        HumanReadableStateFile stateFile = new HumanReadableStateFile();
        StoredCommandSettings snapshot = stateFile.initialize(stateDir.toFile());
        check("H1 旧文件管理员段照常解析", snapshot.getAdministrators().get("g1").contains("u1"));
        check("H2 管理员模式照常解析", "QQ".equals(String.valueOf(snapshot.getAdministratorModes().get("g1"))));
        boolean hasFullForwardingField = false;
        for (Field f : StoredCommandSettings.class.getDeclaredFields()) {
            if (f.getName().equals("fullForwarding")) hasFullForwardingField = true;
        }
        check("H3 StoredCommandSettings 无 fullForwarding 字段", !hasFullForwardingField);
        stateFile.save(snapshot);
        String saved = read(stateDir, "command-state.ini");
        check("H4 保存后无 [full-forwarding] 段", !saved.contains("full-forwarding"), saved);
        check("H5 保存后管理员段保留", saved.contains("[administrators]") && saved.contains("[administrator-modes]"), saved);

        // ---------- 6. onInteraction 回应码保持（快检） ----------
        final AtomicInteger code = new AtomicInteger(-1);
        kotlin.jvm.functions.Function1<Integer, kotlin.Unit> respond = c -> {
            code.set(c);
            return kotlin.Unit.INSTANCE;
        };
        Object r1 = MenuButtons.INSTANCE.onInteraction("g1", "u1", "krg:menu:page:1", respond);
        check("O1 翻页按钮接管回应 0", Boolean.TRUE.equals(r1) && code.get() == 0, "code=" + code.get());
        code.set(-1);
        Object r2 = MenuButtons.INSTANCE.onInteraction("g1", "u1", "krg:cmd:", respond);
        check("O2 空命令回应 1", Boolean.TRUE.equals(r2) && code.get() == 1, "code=" + code.get());

        // ---------- 7. ReplySeqGuard：序号基数错开（防被动回复去重拒收） ----------
        int b1 = ReplySeqGuard.INSTANCE.allocateBase("smoke-msg-a");
        int b2 = ReplySeqGuard.INSTANCE.allocateBase("smoke-msg-a");
        int b3 = ReplySeqGuard.INSTANCE.allocateBase("smoke-msg-a");
        check("Q1 同一 msgId 基数按 100000 递进", b1 == 1 && b2 == 100001 && b3 == 200001,
                b1 + "/" + b2 + "/" + b3);
        int c1 = ReplySeqGuard.INSTANCE.allocateBase("smoke-msg-b");
        check("Q2 不同 msgId 计数隔离", c1 == 1, "c1=" + c1);
        int n1 = ReplySeqGuard.INSTANCE.allocateBase(null);
        int e1 = ReplySeqGuard.INSTANCE.allocateBase("");
        check("Q3 空 msgId 固定基数 1", n1 == 1 && e1 == 1);

        System.out.println();
        System.out.println("===== 冒烟 smoke175o3：" + (passed + failed) + " 项，通过 " + passed + "，失败 " + failed + " =====");
        if (failed > 0) System.exit(1);
    }

    /** 数 JSON 里 "buttons":[ 出现次数（=行数）。 */
    static int countRows(String json) {
        int count = 0, idx = 0;
        String marker = "\"buttons\":[";
        while ((idx = json.indexOf(marker, idx)) >= 0) { count++; idx += marker.length(); }
        return count;
    }

    /** 按按钮文案取其 action 对象（render_data.label 定位，action 含 type/data/enter）。 */
    static com.alibaba.fastjson.JSONObject btnAction(String json, String label) {
        com.alibaba.fastjson.JSONObject root = JSON.parseObject(json);
        com.alibaba.fastjson.JSONArray rows = root.getJSONObject("content").getJSONArray("rows");
        for (int i = 0; i < rows.size(); i++) {
            com.alibaba.fastjson.JSONArray buttons = rows.getJSONObject(i).getJSONArray("buttons");
            for (int j = 0; j < buttons.size(); j++) {
                com.alibaba.fastjson.JSONObject btn = buttons.getJSONObject(j);
                if (label.equals(btn.getJSONObject("render_data").getString("label"))) {
                    return btn.getJSONObject("action");
                }
            }
        }
        return new com.alibaba.fastjson.JSONObject();
    }

    static void write(Path dir, String name, String content) throws Exception {
        Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    static String read(Path dir, String name) throws Exception {
        return new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8);
    }
}
