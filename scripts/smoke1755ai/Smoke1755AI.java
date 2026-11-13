import cn.kerong.penguin.spigot.qqbind.AiAt;
import cn.kerong.penguin.spigot.qqbind.GroupMsgSender;
import cn.kerong.penguin.spigot.qqbind.OpenIdDirectory;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;
import io.github.kloping.qqbot.entities.qqpd.User;
import io.github.kloping.qqbot.entities.qqpd.message.RawMessage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * 1.7.5.5 AI 覆盖轮冒烟（独立 JVM，Spigot 分发 JAR 类路径）——AI 艾特 + 回复引用：
 * 1. system prompt 附注（措辞含 [at: 用法与克制口径）
 * 2. 入向解析（mentions 数组：普通成员还原 @昵称 / 机器人与未知昵称剥掉 / 频道格式）
 * 3. 入向目录兜底（mentions 缺失时 OpenIdDirectory.directory 命中 → @昵称）
 * 4. 出向渲染一级（本条 mentions 命中 → <@openid>）
 * 5. 出向渲染二级（OpenIdDirectory.reverseDirectory 命中 / 忽略大小写）
 * 6. 出向降级（未知昵称 → 纯文本 @昵称不丢字；空标记剥掉；多标记混合）
 * 7. 反查 API 语义（null/空/未知/精确/小写）
 * 8. 接线形态（GroupMsgSender.sendReply 三参 boolean 形态存在）
 *
 * 事件构造：GroupMessageEvent 为接口 → JDK 动态代理（getRawMessage 返回真
 * RawMessage，其余默认值）；RawMessage/User 均有流式 setter，可组装真实
 * mentions 数组——不经任何桩，直接跑 AiAt 的正式逻辑。
 */
public class Smoke1755AI {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) { check(name, ok, null); }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name + (detail == null ? "" : "  >> " + detail)); }
    }

    /** 群事件代理：只供 getRawMessage() 真值，其余按类型给默认。 */
    static GroupMessageEvent eventOf(RawMessage raw) {
        InvocationHandler h = (proxy, method, args) -> {
            String n = method.getName();
            if ("getRawMessage".equals(n)) return raw;
            if ("equals".equals(n)) return proxy == args[0];
            if ("hashCode".equals(n)) return System.identityHashCode(proxy);
            if ("toString".equals(n)) return "ProxyGroupMessageEvent";
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) return Boolean.FALSE;
            if (rt == int.class) return 0;
            if (rt == long.class) return 0L;
            return null;
        };
        return (GroupMessageEvent) Proxy.newProxyInstance(
                Smoke1755AI.class.getClassLoader(), new Class<?>[]{GroupMessageEvent.class}, h);
    }

    @SuppressWarnings("unchecked")
    static <T> T sole(Object obj) { return (T) obj; }

    public static void main(String[] args) throws Exception {

        // 组装真实 mentions：张三（普通）/ 机器人（bot=true）/ 无名（username=null）
        User zhang = new User().setId("openidAAA").setUsername("张三");
        User botSelf = new User().setId("openidBOT").setUsername("机器人").setBot(true);
        User noName = new User().setId("openidXXX").setUsername(null);
        RawMessage raw = new RawMessage()
                .setContent("占位")
                .setMentions(new User[]{zhang, botSelf, noName});
        GroupMessageEvent event = eventOf(raw);

        // ---------- 1. system prompt 附注 ----------
        String suffix = AiAt.INSTANCE.systemPromptSuffix();
        check("1.1 systemPromptSuffix 含 [at: 用法", suffix.contains("[at:") && suffix.contains("]"), suffix);
        check("1.2 systemPromptSuffix 含克制措辞（确有需要）", suffix.contains("确有需要"));
        check("1.3 systemPromptSuffix 含昵称一致性提示", suffix.contains("一致"));

        // ---------- 2. 入向解析（mentions 数组） ----------
        String in1 = AiAt.INSTANCE.parseIncomingMentions("你好，<@openidAAA>，这人是谁？", event);
        check("2.1 普通成员艾特还原 @昵称", "你好，@张三，这人是谁？".equals(in1), in1);
        String in2 = AiAt.INSTANCE.parseIncomingMentions("看 <@openidBOT> 说的", event);
        check("2.2 机器人自身占位剥掉", "看  说的".equals(in2), in2);
        String in3 = AiAt.INSTANCE.parseIncomingMentions("问 <@openidXXX> 去哪", event);
        check("2.3 无昵称成员占位剥掉", "问  去哪".equals(in3), in3);
        String in4 = AiAt.INSTANCE.parseIncomingMentions("频道格式 <@!openidAAA> 收到", event);
        check("2.4 频道 <@!id> 形态同样还原", "频道格式 @张三 收到".equals(in4), in4);
        String in5 = AiAt.INSTANCE.parseIncomingMentions("陌生 <@openidZZZ> 占位", event);
        check("2.5 未收录 openid 占位剥掉", "陌生  占位".equals(in5), in5);
        String in6 = AiAt.INSTANCE.parseIncomingMentions("无占位正文 原样", event);
        check("2.6 无占位快速路径原样返回", "无占位正文 原样".equals(in6), in6);

        // ---------- 3. 入向目录兜底（mentions 缺失） ----------
        Class<?> entryClass = Class.forName("cn.kerong.penguin.spigot.qqbind.OpenIdDirectory$Entry");
        Constructor<?> entryCtor = entryClass.getDeclaredConstructor(String.class, long.class);
        entryCtor.setAccessible(true);
        Field directoryField = OpenIdDirectory.class.getDeclaredField("directory");
        directoryField.setAccessible(true);
        Map<String, Object> directory = sole(directoryField.get(null));
        Object entry = entryCtor.newInstance("李四", System.currentTimeMillis());
        directory.put("openidBBB", entry);
        String in7 = AiAt.INSTANCE.parseIncomingMentions("<@openidBBB> 在吗", eventOf(null));
        check("3.1 mentions 缺失时目录兜底 @昵称", "@李四 在吗".equals(in7), in7);

        // ---------- 4. 出向渲染一级（本条 mentions） ----------
        String out1 = AiAt.INSTANCE.renderOutgoing("[at:张三] 看一下这条", event);
        check("4.1 mentions 命中渲染 <@openid>", "<@openidAAA> 看一下这条".equals(out1), out1);
        String out2 = AiAt.INSTANCE.renderOutgoing("普通回复无标记", event);
        check("4.2 无标记快速路径原样返回", "普通回复无标记".equals(out2), out2);

        // ---------- 5. 出向渲染二级（反向目录 / 忽略大小写） ----------
        Field reverseField = OpenIdDirectory.class.getDeclaredField("reverseDirectory");
        reverseField.setAccessible(true);
        Map<String, String> reverse = sole(reverseField.get(null));
        reverse.put("李四", "openidCCC");
        reverse.put("李四".toLowerCase(), "openidCCC");
        reverse.put("AbC", "openidDDD");
        reverse.put("abc", "openidDDD");
        GroupMessageEvent noMention = eventOf(new RawMessage().setContent("空").setMentions(new User[0]));
        String out3 = AiAt.INSTANCE.renderOutgoing("[at:李四] 回复你", noMention);
        check("5.1 反向目录命中渲染 <@openid>", "<@openidCCC> 回复你".equals(out3), out3);
        String out4 = AiAt.INSTANCE.renderOutgoing("[at:abc] 大小写测试", noMention);
        check("5.2 小写键忽略大小写命中", "<@openidDDD> 大小写测试".equals(out4), out4);
        String out5 = AiAt.INSTANCE.renderOutgoing("[at:AbC] 原样键命中", noMention);
        check("5.3 原样键精确命中", "<@openidDDD> 原样键命中".equals(out5), out5);

        // ---------- 6. 出向降级 ----------
        String out6 = AiAt.INSTANCE.renderOutgoing("[at:陌生人] 你好吗", noMention);
        check("6.1 未知昵称降级纯文本不丢字", "@陌生人 你好吗".equals(out6), out6);
        String out7 = AiAt.INSTANCE.renderOutgoing("空 [at:] 标记", noMention);
        check("6.2 空昵称标记剥掉", "空  标记".equals(out7), out7);
        String out8 = AiAt.INSTANCE.renderOutgoing("[at:张三] 与 [at:陌生人] 混合", event);
        check("6.3 多标记混合（命中+降级）", "<@openidAAA> 与 @陌生人 混合".equals(out8), out8);

        // ---------- 7. 反查 API 语义 ----------
        check("7.1 null 入参返 null", OpenIdDirectory.INSTANCE.lookupOpenIdByNickname(null) == null);
        check("7.2 空串入参返 null", OpenIdDirectory.INSTANCE.lookupOpenIdByNickname("") == null);
        check("7.3 未知昵称返 null", OpenIdDirectory.INSTANCE.lookupOpenIdByNickname("不存在的人") == null);
        check("7.4 精确命中", "openidCCC".equals(OpenIdDirectory.INSTANCE.lookupOpenIdByNickname("李四")));
        check("7.5 小写命中", "openidDDD".equals(OpenIdDirectory.INSTANCE.lookupOpenIdByNickname("abc")));
        check("7.6 大写变体经小写键命中（双键设计）",
                "openidDDD".equals(OpenIdDirectory.INSTANCE.lookupOpenIdByNickname("ABC")));

        // ---------- 8. 接线形态 ----------
        boolean hasQuoteSignature = false;
        for (Method m : GroupMsgSender.class.getDeclaredMethods()) {
            if (!"sendReply".equals(m.getName()) || m.isSynthetic()) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length == 3 && pts[0] == GroupMessageEvent.class
                    && pts[1] == String.class && pts[2] == boolean.class) {
                hasQuoteSignature = true;
            }
        }
        check("8.1 GroupMsgSender.sendReply 三参 boolean 形态存在", hasQuoteSignature);
        boolean hasDefaultBridge = false;
        for (Method m : GroupMsgSender.class.getDeclaredMethods()) {
            if ("sendReply$default".equals(m.getName())) hasDefaultBridge = true;
        }
        check("8.2 sendReply 默认参桥存在（quote 缺省 true）", hasDefaultBridge);

        System.out.println("\n========== 冒烟汇总: PASS=" + passed + " FAIL=" + failed + " ==========");
        if (failed > 0) System.exit(1);
    }
}
