import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;

import io.github.kloping.qqbot.api.v2.GroupMessageEvent;
import io.github.kloping.qqbot.entities.qqpd.User;
import io.github.kloping.qqbot.entities.qqpd.message.RawMessage;
import io.github.kloping.qqbot.impl.message.v2.BaseGroupMessageEvent;

import cn.kerong.bot.GroupAtTracker;
import cn.kerong.bot.events.GroupMessageHandler;
import cn.kerong.penguin.spigot.qqbind.AiAt;
import cn.kerong.penguin.spigot.qqbind.OpenIdDirectory;

/**
 * 1.7.5.7 修复轮冒烟：艾特其他机器人误判（真实类 + 独立 JVM）。
 *
 * 场景（用户报告）：群里艾特其他机器人（如另一个 AI 机器人）时，旧版
 * isMentionedBot 因 mentions 带 bot=true 条目误判为「艾特了本机器人」→
 * AI 误触发插嘴 + 回复艾特发送人。本轮三处修复的行为级验证：
 *  1. isMentionedBot 删除 bot 泛匹配（只认 botSelfId/botAppId 精确 id）
 *  2. AiAt.renderOutgoing 一级反查排除 bot（防机器人互踩）
 *  3. OpenIdDirectory.recordEvent mentions 不再收录机器人
 */
public final class Smoke1757 {

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
        File tmp = Files.createTempDirectory("smoke1757").toFile();
        FakeKeronRuntime.APP_ID = "APPID1";
        FakeKeronRuntime plugin = new FakeKeronRuntime(tmp);
        GroupMessageHandler handler = new GroupMessageHandler(plugin);

        Method isMentionedBot = GroupMessageHandler.class
                .getDeclaredMethod("isMentionedBot", GroupMessageEvent.class);
        isMentionedBot.setAccessible(true);

        // ---------- S1-S5：isMentionedBot 判定行为 ----------
        // S1 核心修复：艾特其他机器人（bot=true 但 id 非本机器人）→ 不再误判
        GroupMessageEvent e1 = event("m1", "<@OTHERBOT> 帮我写个脚本", user("OTHERBOT", "米娅", true));
        check("S1 艾特其他机器人(bot=true) 不误判（旧版 true）",
                !(Boolean) isMentionedBot.invoke(handler, e1));

        // S1b 用户报告场景端到端：正文艾特其他机器人 + 无本机器人
        GroupMessageEvent e1b = event("m1b", "<@OTHERBOT> 你好", user("OTHERBOT", "米娅", true));
        check("S1b 用户场景：只艾特其他机器人 → 不触发 AI（false）",
                !(Boolean) isMentionedBot.invoke(handler, e1b));

        // S2 通道二精确命中：mentions 含本机器人 AppId → true
        GroupMessageEvent e2 = event("m2", "在吗", user("APPID1", "企鹅", true));
        check("S2 mentions 含本机器人 id（botAppId）→ true",
                (Boolean) isMentionedBot.invoke(handler, e2));

        // S3 通道三文本：content 含 <@APPID1> → true（频道形态回归）
        GroupMessageEvent e3 = event("m3", "你好 <@APPID1>", user("OTHERBOT", "米娅", true));
        check("S3 文本 <@本机器人id> → true（其他机器人混在场不影响）",
                (Boolean) isMentionedBot.invoke(handler, e3));

        // S4 通道一：GROUP_AT 帧标记 → true（主判定通道回归）
        GroupAtTracker.INSTANCE.recordAtBot("m4");
        GroupMessageEvent e4 = event("m4", "在吗", (User[]) null);
        check("S4 GroupAtTracker 标记（GROUP_AT 事件）→ true",
                (Boolean) isMentionedBot.invoke(handler, e4));

        // S5 无艾特：mentions 空 + 文本无片段 → false
        GroupMessageEvent e5 = event("m5", "普通聊天", (User[]) null);
        check("S5 无任何艾特 → false",
                !(Boolean) isMentionedBot.invoke(handler, e5));

        // S6 混合艾特：本机器人 + 其他机器人同场 → true（精确命中即可）
        GroupMessageEvent e6 = event("m6", "<@OTHERBOT> <@APPID1> 一起聊聊",
                user("OTHERBOT", "米娅", true), user("APPID1", "企鹅", true));
        check("S6 同时艾特本机器人与其他机器人 → true",
                (Boolean) isMentionedBot.invoke(handler, e6));

        // ---------- S7-S10：AiAt.renderOutgoing 出向防互踩 ----------
        // S7 机器人昵称降级纯文本（不渲染 <@机器人openid>）
        GroupMessageEvent e7 = event("m7", "艾特一下米娅", user("MIYA", "米娅", true));
        String r7 = AiAt.INSTANCE.renderOutgoing("[at:米娅] 你好", e7);
        check("S7 AI 艾特机器人降级纯文本", "@米娅 你好".equals(r7), "got=" + r7);

        // S8 普通成员正常渲染官方艾特
        GroupMessageEvent e8 = event("m8", "艾特张三", user("ZHANG", "张三", false));
        String r8 = AiAt.INSTANCE.renderOutgoing("[at:张三] 你好", e8);
        check("S8 AI 艾特普通成员渲染 <@openid>", "<@ZHANG> 你好".equals(r8), "got=" + r8);

        // S9 混合：机器人降级 + 普通成员渲染
        GroupMessageEvent e9 = event("m9", "都艾特",
                user("MIYA", "米娅", true), user("ZHANG", "张三", false));
        String r9 = AiAt.INSTANCE.renderOutgoing("[at:米娅] 和 [at:张三]", e9);
        check("S9 混合场机器人降级+成员渲染", "@米娅 和 <@ZHANG>".equals(r9), "got=" + r9);

        // S10 parseIncomingMentions 回归：入向 bot 占位剥掉
        String r10 = AiAt.INSTANCE.parseIncomingMentions("<@MIYA> 在吗", e7);
        check("S10 入向机器人占位剥掉（回归）", "在吗".equals(r10.trim()), "got=" + r10);

        // ---------- S11-S13：OpenIdDirectory 不收录机器人 ----------
        // S11 mentions 含机器人 → 不入库（二级反查防线）
        OpenIdDirectory.INSTANCE.recordEvent(event("m11", "<@MIYA>", user("MIYA", "米娅", true)));
        check("S11 目录不收录机器人昵称",
                OpenIdDirectory.INSTANCE.lookupOpenIdByNickname("米娅") == null);

        // S12 mentions 普通成员 → 正常入库
        OpenIdDirectory.INSTANCE.recordEvent(event("m12", "<@ZHANG>", user("ZHANG", "张三", false)));
        check("S12 目录收录普通成员（openid 反查）",
                "ZHANG".equals(OpenIdDirectory.INSTANCE.lookupOpenIdByNickname("张三")),
                "got=" + OpenIdDirectory.INSTANCE.lookupOpenIdByNickname("张三"));

        // S13 机器人不入库不影响同名普通成员后续入库（先机器人后成员同名场景）
        OpenIdDirectory.INSTANCE.recordEvent(event("m13", "<@LI>", user("LI", "小李", true)));
        OpenIdDirectory.INSTANCE.recordEvent(event("m13b", "<@LI2>", user("LI2", "小李", false)));
        check("S13 机器人占位不挡同名真人入库",
                "LI2".equals(OpenIdDirectory.INSTANCE.lookupOpenIdByNickname("小李")));

        System.out.println("\n===== 冒烟结果: " + pass + "/" + (pass + fail) + " =====");
        if (fail > 0) System.exit(1);
    }
}
