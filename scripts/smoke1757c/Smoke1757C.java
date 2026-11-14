import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;

import io.github.kloping.qqbot.Starter;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;
import io.github.kloping.qqbot.entities.Bot;
import io.github.kloping.qqbot.entities.qqpd.User;
import io.github.kloping.qqbot.entities.qqpd.message.RawMessage;
import io.github.kloping.qqbot.impl.message.v2.BaseGroupMessageEvent;

import cn.kerong.bot.GroupAtTracker;
import cn.kerong.bot.QClient;
import cn.kerong.bot.events.GroupMessageHandler;
import cn.kerong.penguin.spigot.qqbind.QqBindManager;

/**
 * 1.7.5.7 再覆盖轮冒烟：昵称匹配去 bot.name 源（真实类 + 独立 JVM）。
 *
 * 用户问题：bot.name 只是显示名、经常被随意填写（撞上群里其他机器人
 * 的昵称就会把「艾特其他机器人」误判成艾特本机器人），不严谨；官方
 * 接口 /users/@me 是基础接口（无需申请任何权限）能正常获取昵称。
 *
 * 修复：loadBotNicknames 唯一源 = /users/@me 的 username
 * （Bot.getInfo 懒加载缓存），不再读配置 bot.name。
 *
 * 官方源注入：Unsafe.allocateInstance 绕过 Starter 构造器（spt 扫描
 * 副作用）→ 反射注 contextManager=FakeContextManager → starter.bot
 * 返回预设 Bot（user 字段预设后 getInfo 直接返回、不碰 HTTP）→
 * 反射写 QClient.starter（private set）。
 *
 * X1-X2  bot.name 撞名场景（本轮核心）/ N1-N8 昵称通道（官方源真路径）
 * R1-R5b 通道回归（精确 id 两源真路径 / GROUP_AT / 文本片段 / 端到端）
 * M1-M7  aiReminderMode 删旧键后行为回归。
 */
public final class Smoke1757C {

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

    /** 预设的官方机器人信息（/users/@me 的返回形态：id=全局 id，username=昵称）。 */
    private static User officialSelf;

    /**
     * 注入官方源：QClient.starter.bot.getInfo() 返回预设官方昵称。
     * 全程不联网——Bot.user 字段预设后 getInfo() 直接返回。
     */
    private static void injectOfficialBot(String officialId, String officialName) throws Exception {
        officialSelf = user(officialId, officialName, true);
        Bot bot = new Bot(); // 无参构造只初始化 guildMap，无副作用
        Field uf = Bot.class.getDeclaredField("user");
        uf.setAccessible(true);
        uf.set(bot, officialSelf);

        Field uField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uField.get(null);
        Starter starter = (Starter) unsafe.allocateInstance(Starter.class); // 绕过 spt 扫描
        Field cmf = Starter.class.getDeclaredField("contextManager");
        cmf.setAccessible(true);
        cmf.set(starter, new FakeContextManager(bot));

        Field qf = QClient.class.getDeclaredField("starter"); // private set → 反射写
        qf.setAccessible(true);
        qf.set(QClient.INSTANCE, starter);
    }

    public static void main(String[] args) throws Exception {
        File tmp = Files.createTempDirectory("smoke1757c").toFile();
        FakeKeronRuntime.APP_ID = "APPID1";
        FakeKeronRuntime plugin = new FakeKeronRuntime(tmp);
        // bot.name 被用户随手写成「米娅」（撞上群里另一个机器人的昵称）
        FakeKeronRuntime.BOT_NAME = "米娅";
        cn.kerong.bot.provider.BotShared.INSTANCE.setInstance(plugin);
        // 官方接口返回的真实昵称是 KERONGPenguin（全局 id BOTID_OFFICIAL）
        injectOfficialBot("BOTID_OFFICIAL", "KERONGPenguin");
        GroupMessageHandler handler = new GroupMessageHandler(plugin);

        Method isMentionedBot = GroupMessageHandler.class
                .getDeclaredMethod("isMentionedBot", GroupMessageEvent.class);
        isMentionedBot.setAccessible(true);

        // ================= 1. bot.name 撞名场景（本轮核心） =================
        // X1 用户担心的场景：bot.name 被乱写成「米娅」，群里艾特昵称为
        // 「米娅」的其他机器人 → 必须不触发（上轮版本此处必 true：
        // botName 源撞名误判，主轮修复口径的复活通道）
        GroupMessageEvent x1 = event("x1", "<@OTHERBOT> 帮我写个脚本",
                user("OTHERBOT", "米娅", true));
        check("X1 bot.name 撞其他机器人昵称（艾特「米娅」机器人）→ false（上轮版必 true）",
                !(Boolean) isMentionedBot.invoke(handler, x1));

        // X2 同一条消息同时艾特本机器人（官方昵称）与「米娅」机器人 → true
        //（本机器人确实被艾特，照常响应；艾特了谁以外的机器人不影响）
        GroupMessageEvent x2 = event("x2", "<@OTHERBOT> <@OPENID_GROUP_BOTID> 一起聊聊",
                user("OTHERBOT", "米娅", true),
                user("OPENID_GROUP_BOTID", "KERONGPenguin", true));
        check("X2 同时艾特本机器人（官方昵称）+ 撞名其他机器人 → true",
                (Boolean) isMentionedBot.invoke(handler, x2));

        // ================= 2. 昵称通道（官方源真路径） =================
        // N1 核心场景：全量群（无 GROUP_AT 帧），艾特本机器人——mentions
        // 条目 id 是群内 openid（≠ AppID ≠ bot.id），username = 官方昵称
        // → true（本轮由官方源 getInfo 命中；上轮版本此处靠 botName 源）
        GroupMessageEvent n1 = event("n1", "<@OPENID_GROUP_BOTID> 在吗",
                user("OPENID_GROUP_BOTID", "KERONGPenguin", true));
        check("N1 全量群艾特本机器人（openid 形态+官方昵称命中）→ true",
                (Boolean) isMentionedBot.invoke(handler, n1));

        // N2 艾特其他机器人（昵称非本机器人官方昵称）→ false
        GroupMessageEvent n2 = event("n2", "<@OTHERBOT2> 查天气",
                user("OTHERBOT2", "小助手", true));
        check("N2 艾特其他机器人（昵称不命中）→ false",
                !(Boolean) isMentionedBot.invoke(handler, n2));

        // N3 bot 收窄：普通成员昵称恰与官方昵称相同（bot=false）→ 不经昵称通道
        GroupMessageEvent n3 = event("n3", "<@ZHANG> 看看",
                user("ZHANG", "KERONGPenguin", false));
        check("N3 普通成员同名昵称（bot=false）→ false（bot 收窄防误判）",
                !(Boolean) isMentionedBot.invoke(handler, n3));

        // N4 混合场：同时艾特其他机器人 + 本机器人（官方昵称）→ true
        GroupMessageEvent n4 = event("n4", "<@OTHERBOT2> <@OPENID_GROUP_BOTID> 一起聊聊",
                user("OTHERBOT2", "小助手", true),
                user("OPENID_GROUP_BOTID", "KERONGPenguin", true));
        check("N4 同时艾特本机器人（官方昵称）与其他机器人 → true",
                (Boolean) isMentionedBot.invoke(handler, n4));

        // N5 官方昵称首尾空格容差（trim 后匹配）
        GroupMessageEvent n5 = event("n5", "<@OPENID_GROUP_BOTID> 在吗",
                user("OPENID_GROUP_BOTID", "  KERONGPenguin  ", true));
        check("N5 昵称首尾空格容差 → true",
                (Boolean) isMentionedBot.invoke(handler, n5));

        // N6 机器人条目昵称为空 → 不命中（空昵称跳过）
        GroupMessageEvent n6 = event("n6", "<@OPENID_X> 在吗",
                user("OPENID_X", "", true));
        check("N6 机器人条目空昵称 → false",
                !(Boolean) isMentionedBot.invoke(handler, n6));

        // N7 bot 字段 null 的条目 + 官方昵称命中 → 不经昵称通道（只认 bot=true）
        GroupMessageEvent n7 = event("n7", "<@OPENID_Y> 在吗",
                user("OPENID_Y", "KERONGPenguin", null));
        check("N7 bot=null 条目昵称命中 → false（只认 bot=true 收窄）",
                !(Boolean) isMentionedBot.invoke(handler, n7));

        // N8 官方接口昵称取不到（getInfo 返回 username 为空）→ 昵称通道静默，
        // 不再回退 bot.name——即使配置里恰好填了正确昵称也不读
        //（上轮版本此处必 true：官方源空时 botName 源兜底命中）
        officialSelf.setUsername("");
        try {
            GroupMessageEvent n8 = event("n8", "<@OPENID_Z> 在吗",
                    user("OPENID_Z", "KERONGPenguin", true));
            check("N8 官方昵称取不到 → 不回退 bot.name（艾特本机器人 → false）",
                    !(Boolean) isMentionedBot.invoke(handler, n8));
        } finally {
            officialSelf.setUsername("KERONGPenguin"); // 恢复供后续用例
        }

        // ================= 3. 通道回归（主轮与更早，含真路径） =================
        // R1 精确 id 匹配 botAppId（配置源）→ true
        GroupMessageEvent r1 = event("r1", "在吗", user("APPID1", "任意", true));
        check("R1 精确 id 匹配（botAppId）→ true（回归）",
                (Boolean) isMentionedBot.invoke(handler, r1));

        // R1b 精确 id 匹配 botSelfId（starter.bot.id 真路径——上轮冒烟 starter
        // 为 null 从未跑过此源，本轮注入后走真路径）
        GroupMessageEvent r1b = event("r1b", "在吗", user("BOTID_OFFICIAL", "任意", true));
        check("R1b 精确 id 匹配（botSelfId=starter.bot.id 真路径）→ true（回归）",
                (Boolean) isMentionedBot.invoke(handler, r1b));

        GroupAtTracker.INSTANCE.recordAtBot("r2");
        GroupMessageEvent r2 = event("r2", "在吗", (User[]) null);
        check("R2 GroupAtTracker 标记（未开全量群主信号）→ true（回归）",
                (Boolean) isMentionedBot.invoke(handler, r2));

        GroupMessageEvent r3 = event("r3", "普通聊天", (User[]) null);
        check("R3 无任何艾特 → false（回归）",
                !(Boolean) isMentionedBot.invoke(handler, r3));

        GroupMessageEvent r4 = event("r4", "你好 <@APPID1>", user("OTHERBOT2", "小助手", true));
        check("R4 文本 <@本机器人id> → true（频道形态回归）",
                (Boolean) isMentionedBot.invoke(handler, r4));

        GroupMessageEvent r4b = event("r4b", "你好 <@BOTID_OFFICIAL>", (User[]) null);
        check("R4b 文本 <@botSelfId> → true（真路径回归）",
                (Boolean) isMentionedBot.invoke(handler, r4b));

        // R5 用户场景端到端（判定链）：全量群艾特机器人发问（官方昵称命中）
        // → 判定 true 意味着 handleAiChat 分支可达（AI 唤起）
        GroupMessageEvent r5 = event("r5", "帮我查一下服务器在线人数",
                user("OPENID_GROUP_BOTID2", "KERONGPenguin", true));
        check("R5 用户场景：艾特机器人提问 → 判定 true（AI 唤起可达）",
                (Boolean) isMentionedBot.invoke(handler, r5));

        // ================= 4. aiReminderMode 删旧键后行为（回归） =================
        QqBindManager manager = QqBindManager.Companion.getInstance();
        plugin.getConfig().set("ai.reminder-mode", null);
        plugin.getConfig().set("ai.reply-quote", null);
        check("M1 双键缺省 → quote（默认引用）",
                "quote".equals(manager.aiReminderMode()), manager.aiReminderMode());

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
