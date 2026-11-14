import java.io.File;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import io.github.kloping.qqbot.api.v2.GroupMessageEvent;
import io.github.kloping.qqbot.entities.qqpd.message.RawMessage;
import io.github.kloping.qqbot.impl.message.v2.BaseGroupMessageEvent;

import cn.kerong.bot.provider.BotShared;
import cn.kerong.bot.tools.GroupSenderName;
import cn.kerong.penguin.spigot.qqbind.QqBindManager;

/**
 * 1.7.5.8 冒烟：群消息转发显示名来源 + QQ 绑定假人豁免（真实类 + 独立 JVM，
 * classpath = NeoForge 分发 JAR——含 org.bukkit 兼容层与重定位 fastjson）。
 *
 * 功能一（chat-format.group-sender-name）：
 * S1  group 模式 → 直接显示事件群昵称（现有行为成为默认）；
 * S2  player 模式 + 未绑定成员 → 回退群昵称；
 * S3  player 模式 + 已绑定成员（QUUID 档案 qq=openid）→ 显示绑定玩家名；
 * S4  group 模式 + 已绑定成员 → 仍显示群昵称（模式关闭时不动）；
 * S5  QqBindManager.getBoundPlayerNameByOpenid 未绑定 → null；
 * S6  resolve 异常路径兜底（管理器可用但 openid 为空）→ 群昵称。
 *
 * 功能二（QQ 绑定假人豁免，BindLoginGate.isLocalFakeConnection 全反射）：
 * F1  回环地址 127.0.0.5 → true（minecraft-fakeplayer 场景）；
 * F2  外网地址 → false（真实玩家照常拦截）；
 * F3  同进程内存连接（isMemoryConnection）→ true；
 * F4  监听器无 connection 字段 → false（保守：绑定拦截保持原行为）；
 * F5  null 监听器 → false；
 * F6  未解析 InetSocketAddress（address=null）→ false；
 * F7  远端地址为 null → false。
 */
public final class Smoke1758 {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name + (detail == null ? "" : "  (" + detail + ")")); }
    }

    /** 构造群消息事件（SDK 构造器要求 metadata 含 id/author；fastjson 已重定位）。 */
    private static GroupMessageEvent event(String msgId, String senderOpenid, String senderName) {
        try {
            RawMessage raw = new RawMessage();
            raw.setId(msgId);
            raw.setContent("内容-" + msgId);
            Class<?> jo = Class.forName("cn.kerong.shaded.com.alibaba.fastjson.JSONObject");
            Object author = jo.getDeclaredConstructor().newInstance();
            jo.getMethod("put", String.class, Object.class).invoke(author, "id", senderOpenid);
            jo.getMethod("put", String.class, Object.class).invoke(author, "member_openid", senderOpenid);
            jo.getMethod("put", String.class, Object.class).invoke(author, "username", senderName);
            Object meta = jo.getDeclaredConstructor().newInstance();
            jo.getMethod("put", String.class, Object.class).invoke(meta, "id", msgId);
            jo.getMethod("put", String.class, Object.class).invoke(meta, "author", author);
            return (GroupMessageEvent) BaseGroupMessageEvent.class
                    .getDeclaredConstructor(RawMessage.class, jo, io.github.kloping.qqbot.entities.Bot.class)
                    .newInstance(raw, meta, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- 假人豁免的假监听器 / 假连接（BindLoginGate 全反射兼容） ----------

    /** 假 Connection：isMemoryConnection() / getRemoteAddress() 与原版签名一致。 */
    public static final class FakeConnection {
        private final boolean memory;
        private final java.net.SocketAddress remote;
        public FakeConnection(boolean memory, java.net.SocketAddress remote) {
            this.memory = memory;
            this.remote = remote;
        }
        public boolean isMemoryConnection() { return memory; }
        public java.net.SocketAddress getRemoteAddress() { return remote; }
    }

    /** 假配置监听器：持有 connection 字段（ServerCommonPacketListenerImpl 形态）。 */
    public static final class FakeListener {
        public final FakeConnection connection;
        public FakeListener(FakeConnection connection) { this.connection = connection; }
    }

    /** 无 connection 字段的监听器（反射失败场景）。 */
    public static final class NoFieldListener { }

    public static void main(String[] args) throws Exception {
        File tmp = Files.createTempDirectory("smoke1758").toFile();
        FakeKeronRuntime plugin = new FakeKeronRuntime(tmp);
        BotShared.INSTANCE.setInstance(plugin);

        // ================= 功能一：显示名来源 =================
        Method resolve = GroupSenderName.class.getDeclaredMethod(
                "resolve", cn.kerong.bot.KERONG.class, GroupMessageEvent.class, String.class);
        resolve.setAccessible(true);

        // S1 group 模式（默认）→ 事件群昵称直出
        FakeKeronRuntime.SENDER_NAME_MODE = "group";
        GroupMessageEvent e1 = event("s1", "OPEN_A", "群昵称小A");
        check("S1 group 模式显示群昵称（默认行为）",
                "群昵称小A".equals(resolve.invoke(GroupSenderName.INSTANCE, plugin, e1, "群昵称小A")));

        // 绑定档案：OPENID_BOUND → 玩家 Steve（QUUID 主文件 qq + playerName）
        QqBindManager manager = QqBindManager.Companion.getInstance();
        File quuidDir = new File(tmp, "QUUID");
        quuidDir.mkdirs();
        Files.write(new File(quuidDir, "smoke-bound-1.yml").toPath(),
                ("qq: OPENID_BOUND\nplayerName: Steve\nplayerUuid: 00000000-0000-0000-0000-000000000001\n")
                        .getBytes(StandardCharsets.UTF_8));

        // S2 player 模式 + 未绑定 → 回退群昵称
        FakeKeronRuntime.SENDER_NAME_MODE = "player";
        GroupMessageEvent e2 = event("s2", "OPEN_UNBOUND", "路人乙");
        check("S2 player 模式未绑定成员回退群昵称",
                "路人乙".equals(resolve.invoke(GroupSenderName.INSTANCE, plugin, e2, "路人乙")));

        // S3 player 模式 + 已绑定 → 显示绑定玩家名
        GroupMessageEvent e3 = event("s3", "OPENID_BOUND", "群昵称小B");
        check("S3 player 模式已绑定成员显示玩家名 Steve",
                "Steve".equals(resolve.invoke(GroupSenderName.INSTANCE, plugin, e3, "群昵称小B")));

        // S4 group 模式 + 已绑定 → 仍显示群昵称（模式关闭不动）
        FakeKeronRuntime.SENDER_NAME_MODE = "group";
        check("S4 group 模式已绑定成员仍显示群昵称",
                "群昵称小B".equals(resolve.invoke(GroupSenderName.INSTANCE, plugin, e3, "群昵称小B")));

        // S5 getBoundPlayerNameByOpenid 未绑定 → null
        check("S5 getBoundPlayerNameByOpenid 未绑定返回 null",
                manager.getBoundPlayerNameByOpenid("OPEN_NOPE") == null);
        check("S5b getBoundPlayerNameByOpenid 已绑定返回 Steve",
                "Steve".equals(manager.getBoundPlayerNameByOpenid("OPENID_BOUND")));
        check("S5c getBoundPlayerNameByOpenid 空 openid 返回 null",
                manager.getBoundPlayerNameByOpenid(null) == null
                        && manager.getBoundPlayerNameByOpenid("") == null);

        // S6 player 模式 + openid 为空的发送者（author 无 id 键）→ 回退群昵称
        FakeKeronRuntime.SENDER_NAME_MODE = "player";
        GroupMessageEvent e6 = event("s6", "", "无名氏");
        check("S6 player 模式空 openid 回退群昵称",
                "无名氏".equals(resolve.invoke(GroupSenderName.INSTANCE, plugin, e6, "无名氏")));

        // ================= 功能二：假人豁免（NF 闸门） =================
        Class<?> gate = Class.forName("cn.kerong.penguin.neoforge.BindLoginGate");
        Object gateInstance = gate.getField("INSTANCE").get(null);
        Method fakeConn = null;
        for (Method m : gate.getMethods()) {
            // internal 方法带 $server_NeoForge 后缀（坑表：后缀无关断言）
            if (m.getName().startsWith("isLocalFakeConnection") && m.getParameterCount() == 1) {
                fakeConn = m;
                break;
            }
        }
        check("F0 BindLoginGate.isLocalFakeConnection 方法存在", fakeConn != null);
        if (fakeConn != null) {
            check("F1 回环地址 127.0.0.5 → 假人放行（minecraft-fakeplayer 场景）",
                    (Boolean) fakeConn.invoke(gateInstance, new FakeListener(
                            new FakeConnection(false, new InetSocketAddress("127.0.0.5", 25565)))));
            check("F2 外网地址 → 非假人（真实玩家照常拦截）",
                    !(Boolean) fakeConn.invoke(gateInstance, new FakeListener(
                            new FakeConnection(false, new InetSocketAddress("203.0.113.7", 51000)))));
            check("F3 同进程内存连接 → 假人放行",
                    (Boolean) fakeConn.invoke(gateInstance, new FakeListener(
                            new FakeConnection(true, null))));
            check("F4 无 connection 字段 → 保守非假人",
                    !(Boolean) fakeConn.invoke(gateInstance, new NoFieldListener()));
            check("F5 null 监听器 → 非假人",
                    !(Boolean) fakeConn.invoke(gateInstance, (Object) null));
            check("F6 未解析地址（hostname 型）→ 非假人（保守）",
                    !(Boolean) fakeConn.invoke(gateInstance, new FakeListener(
                            new FakeConnection(false, InetSocketAddress.createUnresolved("proxy.lan", 25565)))));
            check("F7 远端地址为 null 且非内存连接 → 非假人（保守）",
                    !(Boolean) fakeConn.invoke(gateInstance, new FakeListener(
                            new FakeConnection(false, null))));
        }

        System.out.println();
        System.out.println("===== 冒烟结果: " + pass + "/" + (pass + fail) + " =====");
        if (fail > 0) {
            System.out.println("FAIL 项: " + fail);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
