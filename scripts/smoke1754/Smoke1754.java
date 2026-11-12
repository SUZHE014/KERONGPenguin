import cn.kerong.bot.provider.BotShared;
import cn.kerong.bot.tools.Keyboards;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1.7.5.4 冒烟（独立 JVM，NeoForge 分发 JAR 类路径 + slf4j-nop）：
 * 1. 合成事件验身标记透传（SDK 链路：author.krg_btn_admin → sender.meta）
 * 2. isAdmin 按钮验身短路（标记放行 / 无标记拒绝 / qq 模式 member_role 回归）
 * 3. layoutRows 排版改版（独占行置顶 + 短按钮一行两个 + AI管理特判 + 分页行数）
 * 4. 按钮门禁模式分流（qq 门禁 krg:acmd:+permissionType=1 / MANUAL actionType=2 填输入栏）
 * 5. menu 模板收敛（1.7.5.2 旧默认升级 / 新默认幂等 / 自定义不动）
 * 6. 全局按钮门禁（GLOBAL_MEMBER 常量 / onInteraction 四参签名 / 双路由常量）
 * 7. 回归（注册表标记 / 键盘权限序列化透传 / 前缀常量）
 */
public class Smoke1754 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) { check(name, ok, null); }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name + (detail == null ? "" : "  >> " + detail)); }
    }

    static final Class<?> JSON_OBJ = cn.kerong.shaded.com.alibaba.fastjson.JSONObject.class;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        File sharedFolder = Files.createTempDirectory("krg-1754").toFile();
        FakeKeronRuntime shared = new FakeKeronRuntime(sharedFolder);
        Files.write(sharedFolder.toPath().resolve("config.yml"),
                "admin:\n  mode: qq\n".getBytes(StandardCharsets.UTF_8));
        shared.reloadConfig();
        BotShared.INSTANCE.setInstance(shared);

        // ---------- 1. 合成事件验身标记透传（SDK 链路） ----------
        Object raw = new io.github.kloping.qqbot.entities.qqpd.message.RawMessage();
        raw.getClass().getMethod("setContent", String.class).invoke(raw, "/黑名单");
        Object metadata = JSON_OBJ.getDeclaredConstructor().newInstance();
        Method put = JSON_OBJ.getMethod("put", String.class, Object.class);
        put.invoke(metadata, "group_id", "g1");
        put.invoke(metadata, "group_openid", "g1");
        Object author = JSON_OBJ.getDeclaredConstructor().newInstance();
        put.invoke(author, "id", "u1");
        put.invoke(author, "member_openid", "u1");
        put.invoke(author, "member_role", "admin");
        put.invoke(author, "krg_btn_admin", "1");
        put.invoke(metadata, "author", author);

        Object verifiedEvent = io.github.kloping.qqbot.impl.message.v2.BaseGroupMessageEvent.class
                .getConstructor(io.github.kloping.qqbot.entities.qqpd.message.RawMessage.class,
                        JSON_OBJ, io.github.kloping.qqbot.entities.Bot.class)
                .newInstance(raw, metadata, null);
        Object verifiedSender = verifiedEvent.getClass().getMethod("getSender").invoke(verifiedEvent);
        Object verifiedMeta = verifiedSender.getClass().getMethod("getMeta").invoke(verifiedSender);
        String flagValue = (String) verifiedMeta.getClass().getMethod("getString", String.class)
                .invoke(verifiedMeta, "krg_btn_admin");
        check("1.1 SDK 透传：author.krg_btn_admin → sender.meta.getString(\"krg_btn_admin\")=1",
                "1".equals(flagValue), "实际=" + flagValue);

        // 对照：不带标记的合成事件（真实手打消息形态——平台 payload 无 krg_btn_admin）
        Object plainMeta = JSON_OBJ.getDeclaredConstructor().newInstance();
        put.invoke(plainMeta, "group_id", "g1");
        put.invoke(plainMeta, "group_openid", "g1");
        Object plainAuthor = JSON_OBJ.getDeclaredConstructor().newInstance();
        put.invoke(plainAuthor, "id", "u2");
        put.invoke(plainAuthor, "member_openid", "u2");
        put.invoke(plainMeta, "author", plainAuthor);
        Object plainEvent = io.github.kloping.qqbot.impl.message.v2.BaseGroupMessageEvent.class
                .getConstructor(io.github.kloping.qqbot.entities.qqpd.message.RawMessage.class,
                        JSON_OBJ, io.github.kloping.qqbot.entities.Bot.class)
                .newInstance(raw, plainMeta, null);
        Object plainSender = plainEvent.getClass().getMethod("getSender").invoke(plainEvent);
        Object plainSenderMeta = plainSender.getClass().getMethod("getMeta").invoke(plainSender);
        String plainFlag = (String) plainSenderMeta.getClass().getMethod("getString", String.class)
                .invoke(plainSenderMeta, "krg_btn_admin");
        check("1.2 真实消息形态不带标记（null，不可伪造）", plainFlag == null, "实际=" + plainFlag);

        // ---------- 2. isAdmin 按钮验身短路 ----------
        cn.kerong.bot.events.commands.PublicCommands commands =
                new cn.kerong.bot.events.commands.PublicCommands();
        Method isAdmin = cn.kerong.bot.events.commands.CommandSupport.class
                .getDeclaredMethod("isAdmin", cn.kerong.bot.KERONG.class,
                        io.github.kloping.qqbot.api.v2.GroupMessageEvent.class);
        isAdmin.setAccessible(true);

        // 2.1 MANUAL（config）模式 + 空名单 + 无角色：仅凭按钮验身标记放行（1.7.5.4 主修复点）
        shared.adminModeValue = "config";
        boolean manualVerified = (Boolean) isAdmin.invoke(commands, shared, verifiedEvent);
        boolean manualPlain = (Boolean) isAdmin.invoke(commands, shared, plainEvent);
        check("2.1 MANUAL 模式：带验身标记 → true；无标记无名单 → false",
                manualVerified && !manualPlain,
                "verified=" + manualVerified + " plain=" + manualPlain);

        // 2.2 qq 模式：member_role=admin 旧路径回归 + 标记路径同样放行
        shared.adminModeValue = "qq";
        Object roleOnlyMeta = JSON_OBJ.getDeclaredConstructor().newInstance();
        put.invoke(roleOnlyMeta, "group_id", "g1");
        put.invoke(roleOnlyMeta, "group_openid", "g1");
        Object roleOnlyAuthor = JSON_OBJ.getDeclaredConstructor().newInstance();
        put.invoke(roleOnlyAuthor, "id", "u3");
        put.invoke(roleOnlyAuthor, "member_openid", "u3");
        put.invoke(roleOnlyAuthor, "member_role", "admin");
        put.invoke(roleOnlyMeta, "author", roleOnlyAuthor);
        Object roleOnlyEvent = io.github.kloping.qqbot.impl.message.v2.BaseGroupMessageEvent.class
                .getConstructor(io.github.kloping.qqbot.entities.qqpd.message.RawMessage.class,
                        JSON_OBJ, io.github.kloping.qqbot.entities.Bot.class)
                .newInstance(raw, roleOnlyMeta, null);
        boolean qqRole = (Boolean) isAdmin.invoke(commands, shared, roleOnlyEvent);
        boolean qqVerified = (Boolean) isAdmin.invoke(commands, shared, verifiedEvent);
        boolean qqPlain = (Boolean) isAdmin.invoke(commands, shared, plainEvent);
        check("2.2 qq 模式：member_role=admin 回归 true / 验身标记 true / 普通成员 false",
                qqRole && qqVerified && !qqPlain,
                "role=" + qqRole + " verified=" + qqVerified + " plain=" + qqPlain);

        // ---------- 3 & 4. layoutRows 排版 + 门禁模式分流 ----------
        List<cn.kerong.shaded.kotlin.Pair<String, String>> menu = new ArrayList<>();
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("查在线", "/查在线"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("查性能", "/查性能"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("玩家名片", "/玩家名片"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("查信息", "/查信息"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("在线排行榜", "/在线排行榜"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("财富榜", "/财富榜"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("签到", "/签到"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("全量", "/全量"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("AI管理", "/AI管理"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("切换服务器", "/切换服务器"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("重新绑定", "/重新绑定"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("黑名单", "/黑名单"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("解除黑名单", "/解除黑名单"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("绑定", "/绑定"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("发信息", "/发信息"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("执行命令", "/执行命令"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("执行", "/执行"));
        menu.add(new cn.kerong.shaded.kotlin.Pair<>("管理员执行", "/管理员执行"));

        Method layoutRows = cn.kerong.bot.events.MenuButtons.class
                .getDeclaredMethod("layoutRows", List.class, boolean.class);
        layoutRows.setAccessible(true);
        Object menuButtons = cn.kerong.bot.events.MenuButtons.INSTANCE;

        List<List<Keyboards.Button>> gatedRows =
                (List<List<Keyboards.Button>>) layoutRows.invoke(menuButtons, menu, true);
        List<List<Keyboards.Button>> manualRows =
                (List<List<Keyboards.Button>>) layoutRows.invoke(menuButtons, menu, false);

        // 3.1 独占行按钮置顶（>3 字：玩家名片/在线排行榜/切换服务器/重新绑定/解除黑名单/执行命令/管理员执行）
        List<String> firstRowLabels = new ArrayList<>();
        for (Keyboards.Button b : gatedRows.get(0)) firstRowLabels.add(b.getLabel());
        check("3.1 独占行按钮置顶（首行 = 玩家名片 单独一行）",
                firstRowLabels.size() == 1 && "玩家名片".equals(firstRowLabels.get(0)),
                "首行=" + firstRowLabels);
        boolean exclusiveOk = true;
        int exclusiveCount = 0;
        for (List<Keyboards.Button> row : gatedRows) {
            String label = row.get(0).getLabel();
            if ("查在线".equals(label)) break; // 短按钮区开始
            if (label.length() > 3 && "AI管理".equals(label) == false) {
                exclusiveCount++;
                if (row.size() != 1) exclusiveOk = false;
            }
        }
        check("3.2 长按钮（>3 字）全部独占一行（7 个）", exclusiveOk && exclusiveCount == 7,
                "独占行=" + exclusiveCount + " 独占形态=" + exclusiveOk);

        // 3.3 短按钮一行两个（查在线+查性能 / AI管理 特判与黑名单同行）
        int shortRowIndex = -1;
        for (int i = 0; i < gatedRows.size(); i++) {
            if ("查在线".equals(gatedRows.get(i).get(0).getLabel())) { shortRowIndex = i; break; }
        }
        boolean pairOk = shortRowIndex >= 0 && gatedRows.get(shortRowIndex).size() == 2
                && "查性能".equals(gatedRows.get(shortRowIndex).get(1).getLabel());
        int aiRow = -1;
        for (int i = 0; i < gatedRows.size(); i++) {
            if ("AI管理".equals(gatedRows.get(i).get(0).getLabel())) { aiRow = i; break; }
        }
        boolean aiPairOk = aiRow >= 0 && gatedRows.get(aiRow).size() == 2
                && "黑名单".equals(gatedRows.get(aiRow).get(1).getLabel());
        check("3.3 短按钮一行两个（查在线+查性能）", pairOk);
        check("3.4 AI管理 按 3 字计与黑名单同行", aiPairOk);

        // 3.5 行数与分页口径：7 独占 + 11 短按钮（6 行）= 13 命令行
        check("3.5 总行数 = 13（7 独占 + 6 短行）", gatedRows.size() == 13,
                "实际=" + gatedRows.size());

        // 4.1 qq/both 门禁形态：黑名单 krg:acmd: + permissionType=1 + actionType=1
        Keyboards.Button gatedBlacklist = null, gatedOnline = null, gatedExecCmd = null;
        for (List<Keyboards.Button> row : gatedRows) {
            for (Keyboards.Button b : row) {
                if ("黑名单".equals(b.getLabel())) gatedBlacklist = b;
                if ("查在线".equals(b.getLabel())) gatedOnline = b;
                if ("执行命令".equals(b.getLabel())) gatedExecCmd = b;
            }
        }
        check("4.1 门禁形态：黑名单 krg:acmd:/黑名单 + permissionType=1 + actionType=1",
                gatedBlacklist != null && "krg:acmd:/黑名单".equals(gatedBlacklist.getActionData())
                        && gatedBlacklist.getPermissionType() == 1 && gatedBlacklist.getActionType() == 1,
                gatedBlacklist == null ? "null" : gatedBlacklist.getActionData());
        check("4.2 公开形态：查在线 krg:cmd:/查在线 + permissionType=2 + actionType=1",
                gatedOnline != null && "krg:cmd:/查在线".equals(gatedOnline.getActionData())
                        && gatedOnline.getPermissionType() == 2 && gatedOnline.getActionType() == 1,
                gatedOnline == null ? "null" : gatedOnline.getActionData());
        check("4.3 需补参命令（执行命令）actionType=2 + actionData=/执行命令",
                gatedExecCmd != null && gatedExecCmd.getActionType() == 2
                        && "/执行命令".equals(gatedExecCmd.getActionData())
                        && gatedExecCmd.getPermissionType() == 1,
                gatedExecCmd == null ? "null" : gatedExecCmd.getActionData());

        // 4.4 MANUAL 形态：黑名单 actionType=2 填输入栏（无前缀）+ permissionType=2
        Keyboards.Button manualBlacklist = null, manualOnline = null, manualFull = null;
        for (List<Keyboards.Button> row : manualRows) {
            for (Keyboards.Button b : row) {
                if ("黑名单".equals(b.getLabel())) manualBlacklist = b;
                if ("查在线".equals(b.getLabel())) manualOnline = b;
                if ("全量".equals(b.getLabel())) manualFull = b;
            }
        }
        check("4.4 MANUAL：黑名单 actionType=2 + actionData=/黑名单（无前缀）+ permissionType=2",
                manualBlacklist != null && manualBlacklist.getActionType() == 2
                        && "/黑名单".equals(manualBlacklist.getActionData())
                        && manualBlacklist.getPermissionType() == 2,
                manualBlacklist == null ? "null" : manualBlacklist.getActionData());
        check("4.5 MANUAL：全量 actionType=2 + actionData=/全量",
                manualFull != null && manualFull.getActionType() == 2
                        && "/全量".equals(manualFull.getActionData()),
                manualFull == null ? "null" : manualFull.getActionData());
        check("4.6 MANUAL：查在线保持回调（actionType=1 + krg:cmd: 前缀）",
                manualOnline != null && manualOnline.getActionType() == 1
                        && "krg:cmd:/查在线".equals(manualOnline.getActionData()),
                manualOnline == null ? "null" : manualOnline.getActionData());

        // 4.7 both 模式与 qq 同走平台门禁形态（adminGateFor 判定链）
        shared.adminModeValue = "both";
        boolean gateBoth = cn.kerong.bot.events.MenuButtons.INSTANCE
                .adminGateFor(shared, "smoke-group-both");
        List<List<Keyboards.Button>> bothRows =
                (List<List<Keyboards.Button>>) layoutRows.invoke(menuButtons, menu, gateBoth);
        Keyboards.Button bothBlacklist = null;
        for (List<Keyboards.Button> row : bothRows) {
            for (Keyboards.Button b : row) {
                if ("黑名单".equals(b.getLabel())) bothBlacklist = b;
            }
        }
        check("4.7 both 模式：门禁开启，黑名单按钮 krg:acmd: + permissionType=1",
                gateBoth && bothBlacklist != null
                        && "krg:acmd:/黑名单".equals(bothBlacklist.getActionData())
                        && bothBlacklist.getPermissionType() == 1,
                "gate=" + gateBoth + " data=" + (bothBlacklist == null ? "null" : bothBlacklist.getActionData()));
        shared.adminModeValue = "qq";

        // ---------- 5. menu 模板收敛 ----------
        Field upgradesField = cn.kerong.penguin.spigot.qqbind.MessageTemplates.class
                .getDeclaredField("DEFAULT_CONTENT_UPGRADES");
        upgradesField.setAccessible(true);
        Map<String, Object> upgrades =
                (Map<String, Object>) upgradesField.get(cn.kerong.penguin.spigot.qqbind.MessageTemplates.INSTANCE);
        Object menuSpec = upgrades.get("menu");
        Method shouldUpgrade = menuSpec.getClass().getDeclaredMethod("shouldUpgrade", String.class);
        shouldUpgrade.setAccessible(true);
        String oldDefault = "### 指令菜单（{page}/{pages}）\n\n**当前插件版本：v{version}**";
        String newDefault = "### 指令菜单（{page}/{pages}）\n\n---\nv{version}";
        String olderDefault = "### 指令菜单\n\n点击下方按钮即可执行；带参数的指令（绑定 / 发信息 / 执行命令等）点击后复制到输入框，补全内容再发送。\n\n---\n**当前插件版本：v{version}**";
        check("5.1 menu 收敛：1.7.5.2 版默认（版本号在标题下）→ 升级",
                (Boolean) shouldUpgrade.invoke(menuSpec, oldDefault));
        check("5.2 menu 收敛：1.7.5 更早默认 → 升级",
                (Boolean) shouldUpgrade.invoke(menuSpec, olderDefault));
        check("5.3 menu 收敛：新默认幂等（不再升级）",
                !(Boolean) shouldUpgrade.invoke(menuSpec, newDefault));
        check("5.4 menu 收敛：自定义内容不动",
                !(Boolean) shouldUpgrade.invoke(menuSpec, "### 我的服务器菜单\n自定义文案"));

        // ---------- 6. 全局按钮门禁 ----------
        check("6.1 GLOBAL_MEMBER 常量 = 全局",
                "全局".equals(cn.kerong.bot.events.ServerSelectButtons.GLOBAL_MEMBER));
        Method onInteraction = cn.kerong.bot.events.ServerSelectButtons.class
                .getMethod("onInteraction", String.class, String.class, String.class,
                        cn.kerong.shaded.kotlin.jvm.functions.Function1.class);
        check("6.2 onInteraction 四参签名（含 userOpenId）", onInteraction != null);
        check("6.3 ACMD 前缀常量", "krg:acmd:".equals(cn.kerong.bot.events.MenuButtons.ACMD_PREFIX));
        check("6.4 dispatchAsClicker 公开（ServerSelectButtons 可转发合成命令）",
                cn.kerong.bot.events.MenuButtons.class
                        .getMethod("dispatchAsClicker", String.class, String.class, String.class, boolean.class) != null);

        // ---------- 7. 回归 ----------
        cn.kerong.bot.events.commands.CommandAdminRegistry reg =
                cn.kerong.bot.events.commands.CommandAdminRegistry.INSTANCE;
        boolean adminOk = reg.isAdminCommand("执行命令") && reg.isAdminCommand("管理员执行")
                && reg.isAdminCommand("全量") && reg.isAdminCommand("AI管理")
                && reg.isAdminCommand("黑名单") && reg.isAdminCommand("解除黑名单")
                && reg.isAdminCommand("解除认证");
        check("7.1 注册表标记管理命令（七项）", adminOk);
        boolean publicOk = !reg.isAdminCommand("查在线") && !reg.isAdminCommand("签到")
                && !reg.isAdminCommand("切换服务器") && !reg.isAdminCommand("菜单");
        check("7.2 注册表不误标公共命令（含切换服务器）", publicOk);

        io.github.kloping.qqbot.entities.ex.Keyboard gatedKb = Keyboards.INSTANCE.build(
                java.util.Collections.singletonList(new Keyboards.Button(
                        "黑名单", "黑名单", "krg:acmd:/黑名单", 1, 1, null, 1)));
        String gatedJson = cn.kerong.shaded.com.alibaba.fastjson.JSON.toJSONString(gatedKb);
        boolean permOk = gatedJson.contains("\"type\":1") || gatedJson.contains("\"type\": 1");
        check("7.3 键盘权限序列化透传（permission.type=1）", permOk, gatedJson);

        System.out.println();
        System.out.println("共 " + (passed + failed) + " 项，通过 " + passed + "，失败 " + failed);
        if (failed > 0) System.exit(1);
    }
}
