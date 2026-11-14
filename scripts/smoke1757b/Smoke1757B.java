import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;

import io.github.kloping.qqbot.api.v2.GroupMessageEvent;
import io.github.kloping.qqbot.entities.qqpd.User;
import io.github.kloping.qqbot.entities.qqpd.message.RawMessage;
import io.github.kloping.qqbot.impl.message.v2.BaseGroupMessageEvent;

import cn.kerong.bot.GroupAtTracker;
import cn.kerong.bot.events.GroupMessageHandler;
import cn.kerong.penguin.spigot.qqbind.QqBindManager;

/**
 * 1.7.5.7 覆盖轮冒烟：全量群艾特机器人无法唤起 AI + 删旧键（真实类 + 独立 JVM）。
 *
 * 根因（用户报告「艾特机器人也无法唤起 AI 回复」）：
 *  - 群消息 mentions 里被艾特成员的 id 是群内 openid 加密串，与机器人全局 id
 *    （bot.id / AppID）不是同一 ID 体系——主轮的精确 id 匹配对群消息永远不成立；
 *  - 机器人开启「接收所有群消息」后 @ 消息只以 GROUP_MESSAGE_CREATE 推送，
 *    GROUP_AT 帧不再到达——通道一 GroupAtTracker 永远空。
 *  三通道全灭 → 艾特机器人唤不起 AI。本轮通道二新增昵称匹配。
 *
 * N1-N10 昵称通道判定 / M1-M7 aiReminderMode 无旧键回退（删键后行为）。
 */
public final class Smoke1757B {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name + (detail == null ? "" : "  (" + detail + ")")); }
    }

    private static User user(String id, String name, Boolean bot) {
        User u = new User();
        u.setId(id);
        u.setUsername(name);
        u.setBot(bot);
        return u;
    }

    /** 构造群消息事件（SDK 构造器要求 metadata 含 id/author；fastjson 已重定位）。 */
    private static GroupMessageEvent event(String msgId, String content, User... mentions) {
        RawMessage raw = new RawMessage();
        raw.setId(msgId);
        raw.setContent(content);
        raw.setMentions(mentions == null ? null : (mentions.length == 0 ? null : mentions));
        try {
            Class<?> jo = Class.forName("cn.kerong.shaded.com.alibaba.fastjson.JSONObject");
            Object author = jo.getDeclaredConstructor().newInstance();
            jo.getMethod("put", String.class, Object.class).invoke(author, "id", "SENDER1");
            jo.getMethod("put", String.class, Object.class).invoke(author, "member_openid", "SENDER1");
            jo.getMethod("put", String.class, Object.class).invoke(author, "username", "发送者");
            Object meta = jo.getDeclaredConstructor().newInstance();
            jo.getMethod("put", String.class, Object.class).invoke(meta, "id", msgId);
            jo.getMethod("put", String.class, Object.class).invoke(meta, "author", author);
            return (GroupMessageEvent) newInstance(BaseGroupMessageEvent.class, raw, meta, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object newInstance(Class<?> type, Object... args) {
        try {
            for (java.lang.reflect.Constructor<?> c : type.getConstructors()) {
                if (c.getParameterCount() == args.length) {
                    return c.newInstance(args);
                }
            }
            throw new IllegalStateException("no ctor: " + type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        File tmp = Files.createTempDirectory("smoke1757b").toFile();
        FakeKeronRuntime.APP_ID = "APPID1";
        FakeKeronRuntime plugin = new FakeKeronRuntime(tmp);
        cn.kerong.bot.provider.BotShared.INSTANCE.setInstance(plugin);
        GroupMessageHandler handler = new GroupMessageHandler(plugin);

        Method isMentionedBot = GroupMessageHandler.class
                .getDeclaredMethod("isMentionedBot", GroupMessageEvent.class);
        isMentionedBot.setAccessible(true);

        // ================= 1. 昵称通道（本轮核心修复） =================
        // N1 核心修复场景：全量群（无 GROUP_AT 帧），艾特本机器人——mentions 条目
        // id 是群内 openid（≠ AppID ≠ bot.id），username = 本机器人昵称（botName 源）
        // → 必须判定 true（主轮版本此处必 false，即用户报告的 bug）
        GroupMessageEvent n1 = event("n1", "<@OPENID_GROUP_BOTID> 在吗",
                user("OPENID_GROUP_BOTID", "KERONGPenguin", true));
        check("N1 全量群艾特本机器人（openid 形态+昵称命中）→ true（主轮版必 false）",
                (Boolean) isMentionedBot.invoke(handler, n1));

        // N2 主轮修复保持：艾特其他机器人（bot=true 但昵称非本机器人）→ false
        GroupMessageEvent n2 = event("n2", "<@OTHERBOT> 帮我写个脚本",
                user("OTHERBOT", "米娅", true));
        check("N2 艾特其他机器人（昵称不命中）→ false（主轮修复保持）",
                !(Boolean) isMentionedBot.invoke(handler, n2));

        // N3 bot 收窄：普通成员昵称恰与本机器人相同（bot=false）→ 不经昵称通道
        GroupMessageEvent n3 = event("n3", "<@ZHANG> 看看",
                user("ZHANG", "KERONGPenguin", false));
        check("N3 普通成员同名昵称（bot=false）→ false（bot 收窄防误判）",
                !(Boolean) isMentionedBot.invoke(handler, n3));

        // N4 混合场：同时艾特本机器人（昵称）+ 其他机器人 → true
        GroupMessageEvent n4 = event("n4", "<@OTHERBOT> <@OPENID_GROUP_BOTID> 一起聊聊",
                user("OTHERBOT", "米娅", true),
                user("OPENID_GROUP_BOTID", "KERONGPenguin", true));
        check("N4 同时艾特本机器人（昵称）与其他机器人 → true",
                (Boolean) isMentionedBot.invoke(handler, n4));

        // N5 昵称首尾空格容差（trim 后匹配）
        GroupMessageEvent n5 = event("n5", "<@OPENID_GROUP_BOTID> 在吗",
                user("OPENID_GROUP_BOTID", "  KERONGPenguin  ", true));
        check("N5 昵称首尾空格容差 → true",
                (Boolean) isMentionedBot.invoke(handler, n5));

        // N6 机器人条目昵称为空 → 不命中（空昵称跳过）
        GroupMessageEvent n6 = event("n6", "<@OPENID_X> 在吗",
                user("OPENID_X", "", true));
        check("N6 机器人条目空昵称 → false",
                !(Boolean) isMentionedBot.invoke(handler, n6));

        // N7 bot 字段 null 的条目（部分平台不下发）+ 昵称命中 → 不经昵称通道
        GroupMessageEvent n7 = event("n7", "<@OPENID_Y> 在吗",
                user("OPENID_Y", "KERONGPenguin", null));
        check("N7 bot=null 条目昵称命中 → false（只认 bot=true 收窄）",
                !(Boolean) isMentionedBot.invoke(handler, n7));

        // ================= 2. 通道回归（主轮与更早） =================
        GroupMessageEvent r1 = event("r1", "在吗", user("APPID1", "企鹅", true));
        check("R1 精确 id 匹配（botAppId）→ true（回归）",
                (Boolean) isMentionedBot.invoke(handler, r1));

        GroupAtTracker.INSTANCE.recordAtBot("r2");
        GroupMessageEvent r2 = event("r2", "在吗", (User[]) null);
        check("R2 GroupAtTracker 标记（未开全量群主信号）→ true（回归）",
                (Boolean) isMentionedBot.invoke(handler, r2));

        GroupMessageEvent r3 = event("r3", "普通聊天", (User[]) null);
        check("R3 无任何艾特 → false（回归）",
                !(Boolean) isMentionedBot.invoke(handler, r3));

        GroupMessageEvent r4 = event("r4", "你好 <@APPID1>", user("OTHERBOT", "米娅", true));
        check("R4 文本 <@本机器人id> → true（频道形态回归）",
                (Boolean) isMentionedBot.invoke(handler, r4));

        // R5 用户 bug 场景端到端（判定链）：全量群艾特机器人发问（昵称命中）
        // → 判定 true 意味着 handleAiChat 分支可达（AI 唤起）
        GroupMessageEvent r5 = event("r5", "帮我查一下服务器在线人数",
                user("OPENID_GROUP_BOTID2", "KERONGPenguin", true));
        check("R5 用户场景：艾特机器人提问 → 判定 true（AI 唤起可达）",
                (Boolean) isMentionedBot.invoke(handler, r5));

        // ================= 3. aiReminderMode 删旧键后行为 =================
        QqBindManager manager = QqBindManager.Companion.getInstance();
        plugin.getConfig().set("ai.reminder-mode", null);
        plugin.getConfig().set("ai.reply-quote", null);
        check("M1 双键缺省 → quote（默认引用）",
                "quote".equals(manager.aiReminderMode()), manager.aiReminderMode());

        // M2 本轮变化：旧键残留 false + 新键空 → 直接 quote（不再回退读旧键；
        // 旧键值已由配置迁移 47→48 归一，运行时不再看旧键）
        plugin.getConfig().set("ai.reminder-mode", null);
        plugin.getConfig().set("ai.reply-quote", false);
        check("M2 旧键残留 false + 新键空 → quote（旧键回退已删）",
                "quote".equals(manager.aiReminderMode()), manager.aiReminderMode());

        plugin.getConfig().set("ai.reminder-mode", "at");
        check("M3 at → at", "at".equals(manager.aiReminderMode()), manager.aiReminderMode());

        plugin.getConfig().set("ai.reminder-mode", "艾特");
        check("M4 中文 艾特 → at", "at".equals(manager.aiReminderMode()), manager.aiReminderMode());

        plugin.getConfig().set("ai.reminder-mode", "无");
        check("M5 中文 无 → none", "none".equals(manager.aiReminderMode()), manager.aiReminderMode());

        plugin.getConfig().set("ai.reminder-mode", "garbage");
        check("M6 非法取值 → quote 兜底", "quote".equals(manager.aiReminderMode()), manager.aiReminderMode());

        plugin.getConfig().set("ai.reminder-mode", "");
        check("M7 空串 → quote（与缺省一致）",
                "quote".equals(manager.aiReminderMode()), manager.aiReminderMode());

        System.out.println();
        System.out.println("===== 冒烟结果: " + pass + "/" + (pass + fail) + " =====");
        if (fail > 0) { System.out.println(fail + " 项失败"); System.exit(1); }
        System.out.println("全部通过");
    }
}
