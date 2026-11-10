import cn.kerong.bot.ReplySeqGuard;
import cn.kerong.bot.events.MenuButtons;
import cn.kerong.bot.events.MemberRoleCache;
import cn.kerong.bot.events.RecentGroupMessages;
import cn.kerong.bot.tools.Keyboards;
import cn.kerong.penguin.spigot.qqbind.MessageTemplates;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冒烟：1.7.5.1（40034128 轮转 / 模板升级放宽 / 按钮管理员角色缓存 /
 * 按钮分流不回归 / 序号守卫不回归）。
 */
public class Smoke1751 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) { check(name, ok, null); }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name + (detail == null ? "" : "  " + detail)); }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // ---------- 1. RecentGroupMessages：轮转 / 容量 / 去重 / TTL ----------
        RecentGroupMessages rgm = RecentGroupMessages.INSTANCE;
        rgm.clear();
        check("R1 未登记群 nextId null", rgm.nextId("g-none") == null);
        rgm.record("g1", "m1");
        check("R2 登记后可读", "m1".equals(rgm.nextId("g1")));
        rgm.record("g1", null);
        rgm.record(null, "mx");
        rgm.record("g1", "");
        check("R3 空值忽略（值不变）", "m1".equals(rgm.nextId("g1")));
        rgm.record("g2", "m2");
        check("R4 群间隔离", "m2".equals(rgm.nextId("g2")) && "m1".equals(rgm.nextId("g1")));

        // 轮转：m1..m3 依次取，第 4 次回绕 m1（round-robin 分摊 40034128 配额）
        rgm.clear();
        rgm.record("gr", "m1");
        rgm.record("gr", "m2");
        rgm.record("gr", "m3");
        String r1 = rgm.nextId("gr"), r2 = rgm.nextId("gr"), r3 = rgm.nextId("gr"), r4 = rgm.nextId("gr");
        check("R5 轮转取用 m1→m2→m3",
                "m1".equals(r1) && "m2".equals(r2) && "m3".equals(r3),
                r1 + "," + r2 + "," + r3);
        check("R6 第 4 次回绕到 m1", "m1".equals(r4), r4);

        // 容量 16：登记 20 条，只保留最后 16 条（m1..m4 淘汰）
        rgm.clear();
        for (int i = 1; i <= 20; i++) rgm.record("gc", "m" + i);
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 16; i++) seen.add(rgm.nextId("gc"));
        boolean onlyLast16 = !seen.contains("m1") && !seen.contains("m4")
                && seen.contains("m5") && seen.contains("m20");
        boolean distinct = seen.stream().distinct().count() == 16;
        check("R7 环形缓冲容量 16（m1..m4 淘汰，m5..m20 保留）", onlyLast16, seen.toString());
        check("R8 一圈 16 次取用互不重复", distinct, seen.toString());

        // 重复投递同一条消息不重复登记
        rgm.clear();
        rgm.record("gd", "mx");
        rgm.record("gd", "mx");
        List<String> seen2 = new ArrayList<>();
        for (int i = 0; i < 4; i++) seen2.add(rgm.nextId("gd"));
        check("R9 同消息重复投递只登记一次", seen2.stream().distinct().count() == 1, seen2.toString());

        // TTL 过期：私造 5 分钟前的 Entry 塞进缓冲（不等待真实时间）
        Field groupsField = RecentGroupMessages.class.getDeclaredField("groups");
        groupsField.setAccessible(true);
        ConcurrentHashMap<String, Object> groups =
                (ConcurrentHashMap<String, Object>) groupsField.get(rgm);
        Class<?> entryCls = Class.forName("cn.kerong.bot.events.RecentGroupMessages$Entry");
        Constructor<?> entryCtor = entryCls.getDeclaredConstructor(String.class, long.class);
        entryCtor.setAccessible(true);
        rgm.clear();
        rgm.record("g-stale", "m-now");
        Object state = groups.get("g-stale");
        Method getBuffer = state.getClass().getDeclaredMethod("getBuffer");
        getBuffer.setAccessible(true);
        List<Object> buffer = (List<Object>) getBuffer.invoke(state);
        buffer.clear();
        buffer.add(entryCtor.newInstance("m-stale", System.currentTimeMillis() - 5L * 60L * 1000L));
        check("R10 过期条目 nextId null", rgm.nextId("g-stale") == null);
        check("R11 过期条目惰性清理（读后缓冲空）", buffer.isEmpty());
        rgm.clear();
        check("R12 clear 清空", rgm.nextId("gc") == null && groups.isEmpty());

        // ---------- 2. MemberRoleCache：按钮管理员角色缓存 ----------
        MemberRoleCache mrc = MemberRoleCache.INSTANCE;
        mrc.clear();
        check("M1 未登记 roleOf null", mrc.roleOf("g1", "u1") == null);
        mrc.record("g1", "u1", "owner");
        check("M2 登记后可查", "owner".equals(mrc.roleOf("g1", "u1")));
        mrc.record("g1", null, "admin");
        mrc.record(null, "u2", "admin");
        mrc.record("g1", "u2", "");
        mrc.record("g1", "u2", null);
        check("M3 空值忽略", mrc.roleOf("g1", "u2") == null);
        // 同一成员不同群角色不同（composite key）
        mrc.record("g2", "u1", "member");
        check("M4 composite key 群间隔离",
                "owner".equals(mrc.roleOf("g1", "u1")) && "member".equals(mrc.roleOf("g2", "u1")));
        // 角色随新消息刷新（降权后下一条消息即更新）
        mrc.record("g1", "u1", "member");
        check("M5 角色随消息刷新", "member".equals(mrc.roleOf("g1", "u1")));
        // TTL：私造 31 分钟前条目
        Field rolesField = MemberRoleCache.class.getDeclaredField("roles");
        rolesField.setAccessible(true);
        ConcurrentHashMap<String, Object> roles =
                (ConcurrentHashMap<String, Object>) rolesField.get(mrc);
        Class<?> mEntryCls = Class.forName("cn.kerong.bot.events.MemberRoleCache$Entry");
        Constructor<?> mEntryCtor = mEntryCls.getDeclaredConstructor(String.class, long.class);
        mEntryCtor.setAccessible(true);
        roles.put("gTTL\nuTTL", mEntryCtor.newInstance("admin", System.currentTimeMillis() - 31L * 60L * 1000L));
        check("M6 过期条目 roleOf null", mrc.roleOf("gTTL", "uTTL") == null);
        check("M7 过期条目惰性清理", !roles.containsKey("gTTL\nuTTL"));
        mrc.clear();
        check("M8 clear 清空", mrc.roleOf("g1", "u1") == null && roles.isEmpty());

        // ---------- 3. 模板升级放宽（normalize + 特征兜底）端到端 ----------
        Object templates = MessageTemplates.INSTANCE;
        Field upgradesField = MessageTemplates.class.getDeclaredField("DEFAULT_CONTENT_UPGRADES");
        upgradesField.setAccessible(true);
        Map<String, ?> upgrades = (Map<String, ?>) upgradesField.get(templates);
        check("T1 升级表仍 5 键", upgrades.size() == 5, "keys=" + upgrades.keySet());
        Method upgradeMethod = MessageTemplates.class.getDeclaredMethod("upgradedDefaultContents", File.class);
        upgradeMethod.setAccessible(true);
        Path dir = Files.createTempDirectory("krg-smoke-1751");
        String expectedMenu = "### 指令菜单（{page}/{pages}）\n\n**当前插件版本：v{version}**";
        // 空白差异版旧默认（二轮纯标题 + CRLF + 行尾空格）——旧逐字比对
        // （trim 后整串相等）永不命中，normalize 命中
        write(dir, "menu.md", "### 指令菜单（{page}/{pages}）\r\n \r\n");
        // 收敛表未收录的中间态默认（带特征开头）——特征兜底命中
        write(dir, "unblacklist-list.md", "黑名单列表（{count} 个）：\n{list}\n\n中间态默认：点按钮解除吧");
        // 自定义（不含特征）→ 不动
        write(dir, "full-amount-toggled.md", "我的自定义全量提示 {state}");
        write(dir, "custom-menu.md", "自定义菜单 {page}");
        upgradeMethod.invoke(templates, dir.toFile());
        check("T2 空白差异旧默认 menu 升级（normalize 容忍）",
                read(dir, "menu.md").trim().equals(expectedMenu), read(dir, "menu.md"));
        check("T3 未收录中间态默认（特征兜底）升级到命令在前口径",
                read(dir, "unblacklist-list.md").trim().equals(
                        "黑名单列表（{count} 个）：\n{list}\n\n发送 /解除黑名单 @对应的人 解除；也可以点击下方对应的按钮解除"),
                read(dir, "unblacklist-list.md"));
        check("T4 自定义 full-amount 不动", read(dir, "full-amount-toggled.md").equals("我的自定义全量提示 {state}"));
        check("T5 自定义 menu 不动", read(dir, "custom-menu.md").equals("自定义菜单 {page}"));
        // 幂等
        upgradeMethod.invoke(templates, dir.toFile());
        check("T6 升级幂等", read(dir, "menu.md").trim().equals(expectedMenu));
        // 已是新默认 → 不再写（内容不变）
        write(dir, "full-amount-toggled.md", "全量转发已{state}（总开关，全部配置群统一生效{writeResult}）");
        upgradeMethod.invoke(templates, dir.toFile());
        check("T7 新默认保持不动",
                read(dir, "full-amount-toggled.md").trim()
                        .equals("全量转发已{state}（总开关，全部配置群统一生效{writeResult}）"));

        // ---------- 4. pageKeyboard 按钮分流不回归（本轮改过 MenuButtons） ----------
        Method pageKeyboard = MenuButtons.class.getDeclaredMethod("pageKeyboard", List.class, int.class);
        pageKeyboard.setAccessible(true);
        List<kotlin.Pair<String, String>> page = Arrays.asList(
                new kotlin.Pair<>("查在线", "/查在线"),
                new kotlin.Pair<>("绑定", "/绑定"),
                new kotlin.Pair<>("执行命令", "/执行命令"),
                new kotlin.Pair<>("查性能", "/查性能"));
        Object kb = pageKeyboard.invoke(MenuButtons.INSTANCE, page, 0);
        String jk = JSON.toJSONString(kb);
        check("P1 无参命令 type=1 回调带前缀",
                btnAction(jk, "查在线").getIntValue("type") == 1
                        && "krg:cmd:/查在线".equals(btnAction(jk, "查在线").getString("data")));
        check("P2 需补参命令 type=2 裸命令",
                btnAction(jk, "绑定").getIntValue("type") == 2
                        && "/绑定".equals(btnAction(jk, "绑定").getString("data"))
                        && btnAction(jk, "执行命令").getIntValue("type") == 2);
        check("P3 无 enter:true", !jk.contains("\"enter\":true"));
        // Keyboards 指令按钮序列化保持
        Object kbInput = Keyboards.INSTANCE.build(Arrays.asList(
                new Keyboards.Button("发信息", "发信息", "/发信息 ", 1, 2, null)));
        String jki = JSON.toJSONString(kbInput);
        check("P4 Keyboards 指令按钮 type=2 保持", jki.contains("\"type\":2") && !jki.contains("\"enter\":true"));

        // ---------- 5. ReplySeqGuard 序号守卫不回归 ----------
        check("Q1 首次投递基数 1", ReplySeqGuard.INSTANCE.allocateBase("q1") == 1);
        check("Q2 重投递基数 100001", ReplySeqGuard.INSTANCE.allocateBase("q1") == 100001);
        check("Q3 空 id 基数 1", ReplySeqGuard.INSTANCE.allocateBase(null) == 1
                && ReplySeqGuard.INSTANCE.allocateBase("") == 1);

        System.out.println("================================");
        System.out.println("共 " + (passed + failed) + " 项，通过 " + passed + "，失败 " + failed);
        if (failed > 0) System.exit(1);
    }

    /** 按按钮文案取其 action 对象（render_data.label 定位，action 含 type/data/enter）。 */
    static JSONObject btnAction(String json, String label) {
        JSONObject root = JSON.parseObject(json);
        JSONArray rows = root.getJSONObject("content").getJSONArray("rows");
        for (int i = 0; i < rows.size(); i++) {
            JSONArray buttons = rows.getJSONObject(i).getJSONArray("buttons");
            for (int j = 0; j < buttons.size(); j++) {
                JSONObject btn = buttons.getJSONObject(j);
                if (label.equals(btn.getJSONObject("render_data").getString("label"))) {
                    return btn.getJSONObject("action");
                }
            }
        }
        return new JSONObject();
    }

    static void write(Path dir, String name, String content) throws Exception {
        Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    static String read(Path dir, String name) throws Exception {
        return new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8);
    }
}
