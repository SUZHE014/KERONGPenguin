package scripts.smoke174;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import cn.kerong.bot.provider.BotShared;
import cn.kerong.bot.tools.QrCode;
import cn.kerong.penguin.spigot.qqbind.QqBindManager;
import cn.kerong.penguin.spigot.qqbind.QqBindManager.BoundPlayer;
import cn.kerong.penguin.spigot.render.OnlineListRenderer;
import cn.kerong.penguin.spigot.render.OnlineListRenderer.OnlineEntry;
import cn.kerong.penguin.spigot.render.OnlineListRenderer.OnlineListData;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;
import io.github.kloping.qqbot.entities.qqpd.v2.Contact;
import scripts.smoke1731o.FakeRuntime;

/**
 * 1.7.4 独立 JVM 冒烟（跑在 NF 分发 JAR 的兼容层之上）。
 *
 * 用户需求（主线 1.7.4）：多玩家绑定上限 + 数字选择流程；金币榜别名退役；
 * AI 面板按钮开关联动；QQ 绑定关闭时签到拦截；查在线多页每页重抽背景；
 * 控制台二维码配置引导。
 *
 * 覆盖：
 *  PS 组 PlayerSelection 静态格式判定（123 / 0 / ab / 空 → 不拦截）+
 *        会话生命周期端到端（假事件 + FakeRuntime：两玩家触发选择 → 数字
 *        确认执行 → 会话消费；越界序号提示保留会话；键隔离；过期放行）
 *  BR 组 BindResult 枚举四态在位（SUCCESS / INVALID / BLACKLISTED / LIMIT）
 *  BG 组 查在线多页背景端到端渲染（backgroundProvider 方案：130 人 3 页
 *        × 逐页红/绿/蓝提供器 → PNG 可解码、各页背景色调正确；对照组
 *        provider=null 整组共用背景三页同色 = 旧版行为）
 *  QR 组 二维码补充断言（encodeAscii 行宽一致 = size+2×静区；溢出 / 空
 *        内容拒绝）——矩阵级逐文本比对与 OpenCV 解码在 verify_qr.py
 *
 * 用法（在仓库根目录）：
 *   export J=/home/z/my-project/tooling/jdk21/bin
 *   $J/javac -cp build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.4.jar:scripts/slf4j-api-2.0.13.jar \
 *       -d scripts/smoke174/out scripts/smoke1731o/FakeRuntime.java scripts/smoke174/Test174.java
 *   $J/java -cp scripts/smoke174/out:build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.4.jar:scripts/slf4j-api-2.0.13.jar:scripts/slf4j-nop-2.0.13.jar \
 *       scripts.smoke174.Test174
 */
public final class Test174 {

    static int pass = 0;
    static int fail = 0;

    private static void check(String name, boolean cond, Object detail) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + name + (detail == null ? "" : " (" + detail + ")"));
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + (detail == null ? "" : " (" + detail + ")"));
        }
    }

    private static void check(String name, boolean cond) {
        check(name, cond, null);
    }

    // ---------- 假群消息事件（Proxy 实现 GroupMessageEvent；sendDirect / 日志全吞异常） ----------

    /** 假发送者（Contact 匿名子类：protected 构造器经匿名子类 super() 合法调用；
     * 补齐 Sender / SenderAndCidMidGetter 抽象方法）。 */
    static Contact fakeContact(String openid) {
        Contact contact = new Contact() {
            @Override public io.github.kloping.qqbot.http.data.Result send(String message) { return null; }
            @Override public io.github.kloping.qqbot.http.data.Result send(
                    String message, io.github.kloping.qqbot.entities.qqpd.message.RawMessage reply) { return null; }
            @Override public io.github.kloping.qqbot.http.data.Result send(io.github.kloping.qqbot.api.SendAble sendAble) { return null; }
            @Override public String getCid() { return null; }
            @Override public io.github.kloping.qqbot.entities.ex.enums.EnvType getEnvType() { return null; }
        };
        contact.setOpenid(openid);
        return contact;
    }

    /** 假群消息事件：groupOpenId / groupId / sender 按参数返回，其余方法安全默认。 */
    static GroupMessageEvent fakeEvent(String groupOpenId, String groupId, Contact sender) {
        return (GroupMessageEvent) Proxy.newProxyInstance(
                GroupMessageEvent.class.getClassLoader(),
                new Class<?>[]{GroupMessageEvent.class},
                (InvocationHandler) (proxy, method, outerName) -> {
                    String name = method.getName();
                    switch (name) {
                        case "getGroupOpenId":
                        case "getOpenId":
                            return groupOpenId;
                        case "getGroupId":
                            return groupId;
                        case "getSender":
                            return sender;
                        case "sendMessage":
                            return null;
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == long.class) return 0L;
                            return null;
                    }
                });
    }

    /** 取 PNG 页面左上角 (3,3) 像素 RGB（卡片绘制留有边距，该处为背景色）。 */
    static int cornerPixel(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        return image.getRGB(3, 3) & 0xFFFFFF;
    }

    /** 纯色背景图。 */
    private static BufferedImage solidBackground(Color color, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // FakeRuntime 注册（QqBindManager.getInstance 可用；数据目录 tmp）
        FakeRuntime runtime = new FakeRuntime();
        BotShared.INSTANCE.setInstance(runtime);
        QqBindManager manager = QqBindManager.Companion.getInstance();
        check("PS0 QqBindManager 单例可用（FakeRuntime 在位）", manager != null);

        System.out.println("== PS 组：PlayerSelection 选择流程 ==");
        cn.kerong.bot.events.PlayerSelection ps = cn.kerong.bot.events.PlayerSelection.INSTANCE;
        // —— 静态格式判定（格式不过直接 false，不触碰事件内部）——
        GroupMessageEvent fmtEvent = fakeEvent("G1", null, fakeContact("U1"));
        check("PS1 三位数字 123 不拦截", !ps.tryConsume(fmtEvent, "123"));
        check("PS2 非数字 ab 不拦截", !ps.tryConsume(fmtEvent, "ab"));
        check("PS3 空串不拦截", !ps.tryConsume(fmtEvent, ""));
        check("PS4 序号 0 不拦截不提示", !ps.tryConsume(fmtEvent, "0"));

        // —— 会话生命周期端到端 ——
        GroupMessageEvent event = fakeEvent("G1", null, fakeContact("U1"));
        List<BoundPlayer> players = new ArrayList<>();
        players.add(new BoundPlayer("Alice", "quuid-a"));
        players.add(new BoundPlayer("Bob", "quuid-b"));
        players.add(new BoundPlayer("Cara", "quuid-c"));

        // 两名以上玩家：登记会话（选择消息），action 暂不执行
        AtomicInteger executed = new AtomicInteger(-1);
        ps.selectOrRun(event, players, p -> {
            executed.set(indexOf(players, p));
            return null;
        });
        check("PS5 多玩家触发选择（action 未执行）", executed.get() == -1,
              "executed=" + executed.get());

        // 无效序号（三位数）放行、不消费会话
        check("PS6 会话期间 123 仍不拦截", !ps.tryConsume(event, "123"));

        // 越界序号：提示并保留会话（不执行 action，返回 true 消息已处理）
        executed.set(-1);
        boolean consumed = ps.tryConsume(event, "9");
        check("PS7 越界序号被消费（回复提示）", consumed);
        check("PS8 越界序号不执行 action", executed.get() == -1);

        // 有效序号 2 → 执行第二个玩家（Bob）并消费会话
        consumed = ps.tryConsume(event, "2");
        check("PS9 序号 2 消费成功", consumed);
        check("PS10 执行的是 Bob（index 1）", executed.get() == 1, "executed=" + executed.get());

        // 会话已消费：再发数字放行（走正常消息流）
        check("PS11 会话消费后数字放行", !ps.tryConsume(event, "1"));

        // 重新登记 → 覆盖会话；序号 1 执行第一个（Alice）
        executed.set(-1);
        ps.selectOrRun(event, players, p -> {
            executed.set(indexOf(players, p));
            return null;
        });
        check("PS12 重新登记（action 未执行）", executed.get() == -1);
        check("PS13 序号 1 执行 Alice", ps.tryConsume(event, "1") && executed.get() == 0);

        // 单玩家：直接执行（不登记会话）
        List<BoundPlayer> single = new ArrayList<>();
        single.add(new BoundPlayer("Solo", "quuid-s"));
        executed.set(-1);
        ps.selectOrRun(event, single, p -> {
            executed.set(0);
            return null;
        });
        check("PS14 单玩家直接执行（与旧版行为一致）", executed.get() == 0);

        // 键隔离：另一群同用户无会话 → 数字放行
        GroupMessageEvent otherGroup = fakeEvent("G2", null, fakeContact("U1"));
        check("PS15 异群无会话数字放行", !ps.tryConsume(otherGroup, "1"));
        // 同群另一用户无会话 → 放行
        GroupMessageEvent otherUser = fakeEvent("G1", null, fakeContact("U2"));
        check("PS16 同群异用户无会话数字放行", !ps.tryConsume(otherUser, "1"));

        // 过期会话：反射把 expireAt 改到过去 → 数字放行（会话作废）
        ps.selectOrRun(event, players, p -> null);
        Field sessionsField = cn.kerong.bot.events.PlayerSelection.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        ConcurrentHashMap<String, Object> sessions =
                (ConcurrentHashMap<String, Object>) sessionsField.get(null);
        Object pending = sessions.get("G1\nU1");
        check("PS17 会话已登记（反射可见）", pending != null);
        if (pending != null) {
            Field expireField = pending.getClass().getDeclaredField("expireAt");
            expireField.setAccessible(true);
            expireField.setLong(pending, System.currentTimeMillis() - 1L);
            check("PS18 过期会话数字放行", !ps.tryConsume(event, "1"));
            check("PS19 过期会话已清除", sessions.get("G1\nU1") == null);
        }

        // clearAll 清空全部会话
        ps.selectOrRun(event, players, p -> null);
        ps.clearAll();
        check("PS20 clearAll 清空会话", sessions.isEmpty());

        // 会话期间 @机器人 形态（行首 @ 片段已被入口剥离，到 tryConsume 与直接发一致）
        // —— 该剥离发生在 GroupMessageHandler（源码门禁 MB2d 已断言顺序），冒烟以等价文本验证
        ps.selectOrRun(event, players, p -> null);
        check("PS21 剥离后的等价数字文本可确认（@机器人+数字同路径）",
              ps.tryConsume(event, " 3 ") && sessions.get("G1\nU1") == null);

        System.out.println("== BR 组：BindResult 枚举四态 ==");
        Class<?> br = Class.forName("cn.kerong.penguin.spigot.qqbind.QqBindManager$BindResult");
        Object[] constants = br.getEnumConstants();
        StringBuilder names = new StringBuilder();
        if (constants != null) {
            for (Object c : constants) names.append(((Enum<?>) c).name()).append(',');
        }
        check("BR1 枚举四态（SUCCESS,INVALID,BLACKLISTED,LIMIT）",
              constants != null && constants.length == 4
                      && "SUCCESS,INVALID,BLACKLISTED,LIMIT,".contentEquals(names),
              names.toString());

        System.out.println("== BG 组：查在线多页背景端到端（backgroundProvider） ==");
        // 130 人 → 3 页（60 + 60 + 10）；provider 按各页高度返回独立纯色背景（红 / 绿 / 蓝）
        List<OnlineEntry> entries = new ArrayList<>();
        for (int i = 1; i <= 130; i++) {
            entries.add(new OnlineEntry("Player" + i, null));
        }
        BufferedImage red = solidBackground(new Color(200, 40, 40), 1350, 900);
        BufferedImage green = solidBackground(new Color(40, 200, 40), 1350, 900);
        BufferedImage blue = solidBackground(new Color(40, 40, 200), 1350, 900);
        // provider 调用计数（每页恰一次）
        final int[] providerCalls = {0};
        cn.kerong.shaded.kotlin.jvm.functions.Function1<? super Integer, ? extends java.awt.image.BufferedImage> provider =
                height -> {
                    providerCalls[0]++;
                    int page = Math.min(providerCalls[0] - 1, 2);
                    switch (page) {
                        case 0: return red;
                        case 1: return green;
                        default: return blue;
                    }
                };
        OnlineListData data = new OnlineListData(
                "冒烟服", "12:34", entries, red, provider);
        List<byte[]> pages = OnlineListRenderer.INSTANCE.renderPages(data);
        check("BG1 分页数 = 3（130 人 × 60/页）", pages.size() == 3, String.valueOf(pages.size()));
        check("BG2 provider 每页恰调一次（共 3 次）", providerCalls[0] == 3,
              String.valueOf(providerCalls[0]));
        if (pages.size() == 3) {
            BufferedImage p1 = ImageIO.read(new ByteArrayInputStream(pages.get(0)));
            BufferedImage p2 = ImageIO.read(new ByteArrayInputStream(pages.get(1)));
            BufferedImage p3 = ImageIO.read(new ByteArrayInputStream(pages.get(2)));
            check("BG3 页宽 1350", p1.getWidth() == 1350 && p2.getWidth() == 1350 && p3.getWidth() == 1350);
            check("BG4 首页高度 > 续页（含标题区）", p1.getHeight() > p2.getHeight(),
                  p1.getHeight() + " vs " + p2.getHeight());
            int c1 = cornerPixel(pages.get(0));
            int c2 = cornerPixel(pages.get(1));
            int c3 = cornerPixel(pages.get(2));
            check("BG5 首页背景 = 红", (c1 >> 16) > 0xA0 && (c1 & 0xFF) < 0x80, String.format("%06x", c1));
            check("BG6 续页1背景 = 绿", ((c2 >> 8) & 0xFF) > 0xA0 && (c2 & 0xFF) < 0x80, String.format("%06x", c2));
            check("BG7 续页2背景 = 蓝", (c3 & 0xFF) > 0xA0 && ((c3 >> 16) & 0xFF) < 0x80, String.format("%06x", c3));
            check("BG8 三页背景互不相同（逐页重抽生效）", c1 != c2 && c2 != c3 && c1 != c3);
        }
        // 对照组：provider = null → 整组共用 background（旧版行为，内置背景模式观感不变）
        OnlineListData shared = new OnlineListData("冒烟服", "12:34", entries, blue, null);
        List<byte[]> sharedPages = OnlineListRenderer.INSTANCE.renderPages(shared);
        check("BG9 对照组分页数 = 3", sharedPages.size() == 3, String.valueOf(sharedPages.size()));
        if (sharedPages.size() == 3) {
            int s1 = cornerPixel(sharedPages.get(0));
            int s2 = cornerPixel(sharedPages.get(1));
            int s3 = cornerPixel(sharedPages.get(2));
            check("BG10 对照组三页同色（共用背景 = 内置模式观感不变）", s1 == s2 && s2 == s3,
                  String.format("%06x %06x %06x", s1, s2, s3));
            check("BG11 对照组 = 蓝（背景字段回退生效）", (s1 & 0xFF) > 0xA0, String.format("%06x", s1));
        }

        System.out.println("== QR 组：二维码补充断言 ==");
        String url = "https://kerong.xin/penguin/tutorial.html";
        QrCode.Matrix matrix = QrCode.encode(url);
        String ascii = QrCode.encodeAscii(url, 2);
        String[] lines = ascii.split("\n", -1);
        int expectedWidth = matrix.getSize() + 2 * 2;
        boolean widthsOk = true;
        for (String line : lines) {
            if (line.length() != expectedWidth) {
                widthsOk = false;
                break;
            }
        }
        int total = matrix.getSize() + 2 * 2;
        int expectedRows = (total + 1) / 2;
        check("QR1 encodeAscii 行宽一致（size + 2×静区）", widthsOk,
              "width=" + expectedWidth);
        check("QR2 encodeAscii 行数 = ceil(total/2)", lines.length == expectedRows,
              lines.length + " vs " + expectedRows);
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 30; i++) sb.append("0123456789");
            QrCode.encode(sb.toString());
            check("QR3 超长内容拒绝（300 字节 > 版本 10 上限）", false, "未抛异常");
        } catch (IllegalArgumentException expected) {
            check("QR3 超长内容拒绝（300 字节 > 版本 10 上限）", true, expected.getMessage());
        }
        try {
            QrCode.encode("");
            check("QR4 空内容拒绝", false, "未抛异常");
        } catch (IllegalArgumentException expected) {
            check("QR4 空内容拒绝", true, expected.getMessage());
        }

        System.out.println();
        System.out.println("结果：" + pass + " 通过，" + fail + " 失败");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static int indexOf(List<BoundPlayer> list, BoundPlayer target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getPlayerName().equals(target.getPlayerName())) return i;
        }
        return -1;
    }
}
