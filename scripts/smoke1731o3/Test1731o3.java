package scripts.smoke1731o3;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.imageio.ImageIO;

import cn.kerong.bot.PerformanceSnapshot;
import cn.kerong.bot.provider.BotShared;
import cn.kerong.penguin.spigot.render.PerformanceRenderer;
import cn.kerong.penguin.spigot.stats.MotdDisplayTracker;
import cn.kerong.penguin.spigot.stats.PerformanceService;
import scripts.smoke1731o.FakeRuntime;

/**
 * 1.7.3.1 第三次覆盖版独立 JVM 冒烟（跑在 NF 分发 JAR 的兼容层之上）。
 *
 * 用户需求（同版本号 1.7.3.1 直接覆盖）：修复「配置文件写的是 motd，
 * 可展示的人数是服务器真实人数」的问题——旧 motd 捕获机制（Spigot
 * ServerListPingEvent，模组端无事件恒真实值、只记最大人数）整体废弃，
 * 改本机 SLP 自查询（对 127.0.0.1:<端口> 发服务器列表 ping 解析
 * players.online/max），双端统一，人数 N 与最大 M 成对切换。
 *
 * 覆盖：
 *  SL 组 本机 SLP 自查询（假状态服务器端到端：666/1000 解析 / online=0
 *        合法 / 端口不通 null / 非法响应静默 / record-reset 语义 /
 *        refreshIfStale 60 秒节流与强制刷新 / 端口未注入 no-op）
 *  PD 组 性能卡片展示值覆盖渲染（真实 3/20 与展示 666/1000 双口径
 *        渲染可解码、字节不同、尺寸 1350 宽）
 *  RD 组 resolveDisplayCounts 无插件上下文回退真实值（real 语义）
 *
 * 用法（在仓库根目录）：
 *   export J=/home/z/my-project/tooling/jdk21/bin
 *   $J/javac -cp <NF JAR>:scripts/slf4j-api-2.0.13.jar -d scripts/smoke1731o3/out \
 *       scripts/smoke1731o/FakeRuntime.java scripts/smoke1731o3/Test1731o3.java
 *   $J/java -cp scripts/smoke1731o3/out:<NF JAR>:scripts/slf4j-api-2.0.13.jar:scripts/slf4j-nop-2.0.13.jar \
 *       scripts.smoke1731o3.Test1731o3
 */
public final class Test1731o3 {

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

    // ---------- 假状态服务器（实现 SLP 协议：读握手帧 + 状态请求帧 → 回 JSON 状态帧） ----------

    static final class FakeStatusServer implements Runnable {
        final ServerSocket serverSocket;
        volatile String responseJson;
        volatile int handshakes = 0;

        FakeStatusServer(int port, String json) throws IOException {
            serverSocket = new ServerSocket(port, 8, java.net.InetAddress.getByName("127.0.0.1"));
            responseJson = json;
            Thread t = new Thread(this, "fake-status-server");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void run() {
            while (!serverSocket.isClosed()) {
                try (Socket sock = serverSocket.accept()) {
                    sock.setSoTimeout(3000);
                    DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
                    int frameLen = readVarInt(in);
                    byte[] frame = new byte[frameLen];
                    in.readFully(frame);
                    int reqLen = readVarInt(in);
                    byte[] req = new byte[reqLen];
                    in.readFully(req);
                    handshakes++;
                    if (responseJson.startsWith("RAW:")) {
                        out.write(responseJson.substring(4).getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        continue;
                    }
                    byte[] json = responseJson.getBytes(StandardCharsets.UTF_8);
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    body.write(0x00);
                    writeVarInt(body, json.length);
                    body.write(json);
                    byte[] bodyBytes = body.toByteArray();
                    writeVarInt(out, bodyBytes.length);
                    out.write(bodyBytes);
                    out.flush();
                } catch (Exception ignored) {
                    // 单连接异常不影响继续 accept
                }
            }
        }

        static int readVarInt(InputStream in) throws IOException {
            int result = 0;
            for (int i = 0; i < 5; i++) {
                int b = in.read();
                if (b < 0) {
                    throw new EOFException("varint eof");
                }
                result |= (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) {
                    return result;
                }
            }
            throw new IOException("varint too long");
        }

        static void writeVarInt(OutputStream out, int value) throws IOException {
            while (true) {
                if ((value & ~0x7F) == 0) {
                    out.write(value);
                    return;
                }
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
        }
    }

    private static Integer pairFirst(Object pair) throws Exception {
        return (Integer) pair.getClass().getMethod("getFirst").invoke(pair);
    }

    private static Integer pairSecond(Object pair) throws Exception {
        return (Integer) pair.getClass().getMethod("getSecond").invoke(pair);
    }

    private static boolean trackerIs(int online, int max) {
        return Integer.valueOf(online).equals(MotdDisplayTracker.INSTANCE.getLastDisplayOnline())
                && Integer.valueOf(max).equals(MotdDisplayTracker.INSTANCE.getLastDisplayMax());
    }

    public static void main(String[] args) throws Exception {
        FakeRuntime fake = new FakeRuntime();
        BotShared.INSTANCE.setInstance(fake);

        System.out.println("== SL 组：本机 SLP 自查询（motd 展示值机制重做） ==");

        int port = 21000 + (int) (Math.random() * 2000);
        FakeStatusServer fakeServer = new FakeStatusServer(port,
                "{\"players\":{\"online\":666,\"max\":1000},\"version\":{\"name\":\"1.20.4\"},"
                        + "\"description\":{\"text\":\"fake\"}}");
        Thread.sleep(200);

        // internal 方法名带模块后缀混淆（NF JAR 内 pingStatus$server_NeoForge），按前缀定位
        Method ping = null;
        for (Method m : MotdDisplayTracker.class.getDeclaredMethods()) {
            if (m.getName().startsWith("pingStatus") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == int.class) {
                ping = m;
                break;
            }
        }
        check("SL0 pingStatus 方法在位（internal 混淆名）", ping != null,
                ping == null ? "-" : ping.getName());
        ping.setAccessible(true);

        Object pair1 = ping.invoke(MotdDisplayTracker.INSTANCE, port);
        check("SL1a pingStatus 端到端解析展示在线 666",
                pair1 != null && pairFirst(pair1) == 666, String.valueOf(pair1));
        check("SL1b pingStatus 端到端解析展示最大 1000",
                pair1 != null && pairSecond(pair1) == 1000, String.valueOf(pair1));

        fakeServer.responseJson = "{\"players\":{\"online\":0,\"max\":50}}";
        Object pair0 = ping.invoke(MotdDisplayTracker.INSTANCE, port);
        check("SL2 空服展示 online=0 合法（0/50）",
                pair0 != null && pairFirst(pair0) == 0 && pairSecond(pair0) == 50, String.valueOf(pair0));

        boolean deadRefused = false;
        try {
            ping.invoke(MotdDisplayTracker.INSTANCE, port + 7);
        } catch (java.lang.reflect.InvocationTargetException e) {
            deadRefused = e.getCause() instanceof java.net.ConnectException;
        }
        check("SL3 端口不通抛连接拒绝（refreshIfStale 捕获后回退真实值）", deadRefused);

        MotdDisplayTracker.INSTANCE.reset();
        check("SL4a reset 清空捕获值（online/max null、时间 0）",
                MotdDisplayTracker.INSTANCE.getLastDisplayOnline() == null
                        && MotdDisplayTracker.INSTANCE.getLastDisplayMax() == null
                        && MotdDisplayTracker.INSTANCE.getLastCaptureAt() == 0L);
        MotdDisplayTracker.INSTANCE.record(5, 100);
        check("SL4b record(5,100) 成对入位",
                trackerIs(5, 100) && MotdDisplayTracker.INSTANCE.getLastCaptureAt() > 0L);
        MotdDisplayTracker.INSTANCE.record(-1, 0);
        MotdDisplayTracker.INSTANCE.record(3, 0);
        check("SL4c 非法值忽略（online<0 / max<=0 不覆盖）", trackerIs(5, 100));
        MotdDisplayTracker.INSTANCE.record(0, 50);
        check("SL4d record(0,50) 合法（online=0 允许）", trackerIs(0, 50));

        MotdDisplayTracker.INSTANCE.reset();
        fakeServer.responseJson = "{\"players\":{\"online\":12,\"max\":34}}";
        int before = fakeServer.handshakes;
        MotdDisplayTracker.INSTANCE.setPortProvider(() -> port);
        MotdDisplayTracker.INSTANCE.refreshIfStale(60_000L);
        check("SL5a 空捕获触发自查询（12/34 入位）",
                fakeServer.handshakes == before + 1 && trackerIs(12, 34),
                "handshakes=" + fakeServer.handshakes);

        fakeServer.responseJson = "{\"players\":{\"online\":99,\"max\":99}}";
        MotdDisplayTracker.INSTANCE.refreshIfStale(60_000L);
        check("SL5b 60 秒窗口内复用不重查（值保持 12/34）",
                fakeServer.handshakes == before + 1 && trackerIs(12, 34));

        MotdDisplayTracker.INSTANCE.refreshIfStale(0L);
        check("SL5c maxAge=0 强制刷新取新值（99/99）",
                fakeServer.handshakes == before + 2 && trackerIs(99, 99));

        MotdDisplayTracker.INSTANCE.reset();
        MotdDisplayTracker.INSTANCE.setPortProvider(null);
        MotdDisplayTracker.INSTANCE.refreshIfStale(0L);
        check("SL6 端口未注入自查询 no-op（捕获值保持空）",
                MotdDisplayTracker.INSTANCE.getLastDisplayOnline() == null
                        && MotdDisplayTracker.INSTANCE.getLastDisplayMax() == null);

        fakeServer.responseJson = "not-json";
        MotdDisplayTracker.INSTANCE.setPortProvider(() -> port);
        boolean silent = true;
        try {
            MotdDisplayTracker.INSTANCE.refreshIfStale(0L);
        } catch (Throwable t) {
            silent = false;
        }
        check("SL7a 非法 JSON 静默失败（不抛异常）",
                silent && MotdDisplayTracker.INSTANCE.getLastDisplayOnline() == null);

        fakeServer.responseJson = "RAW:garbage-bytes";
        boolean silent2 = true;
        try {
            MotdDisplayTracker.INSTANCE.refreshIfStale(0L);
        } catch (Throwable t) {
            silent2 = false;
        }
        check("SL7b 垃圾协议响应静默失败（不抛异常、捕获值保持空）",
                silent2 && MotdDisplayTracker.INSTANCE.getLastDisplayOnline() == null);

        System.out.println("== PD 组：性能卡片展示值覆盖渲染 ==");

        PerformanceSnapshot snapshot = new PerformanceSnapshot(19.9, 5.0, 1024L, 2048L, 0.1, 3, 20);
        byte[] real = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "测试服", "12:00", snapshot, null, null, 20, false, null));
        byte[] display = PerformanceRenderer.INSTANCE.render(new PerformanceRenderer.PerformanceData(
                "测试服", "12:00", snapshot, null, 666, 1000, true, null));

        BufferedImage realImg = ImageIO.read(new ByteArrayInputStream(real));
        BufferedImage displayImg = ImageIO.read(new ByteArrayInputStream(display));
        check("PD1 真实值口径渲染可解码 PNG（1350 宽）",
                realImg != null && realImg.getWidth() == 1350,
                realImg == null ? "null" : realImg.getWidth() + "x" + realImg.getHeight());
        check("PD2 展示值口径渲染可解码 PNG（1350 宽）",
                displayImg != null && displayImg.getWidth() == 1350,
                displayImg == null ? "null" : displayImg.getWidth() + "x" + displayImg.getHeight());
        check("PD3 两种口径字节不同（覆盖确实改变画面）",
                !Arrays.equals(real, display),
                real.length + " vs " + display.length);

        System.out.println("== RD 组：resolveDisplayCounts 回退语义 ==");

        Method rd = PerformanceService.class.getDeclaredMethod(
                "resolveDisplayCounts", Integer.class, Integer.class);
        rd.setAccessible(true);
        Object result = rd.invoke(PerformanceService.INSTANCE, 3, 20);
        Object rOnline = result.getClass().getMethod("getFirst").invoke(result);
        Object rMax = result.getClass().getMethod("getSecond").invoke(result);
        Object rFromMotd = result.getClass().getMethod("getThird").invoke(result);
        check("RD1 无插件上下文回退真实值（3 / 20 / false）",
                Integer.valueOf(3).equals(rOnline) && Integer.valueOf(20).equals(rMax)
                        && Boolean.FALSE.equals(rFromMotd),
                rOnline + "/" + rMax + "/" + rFromMotd);

        System.out.println();
        System.out.println("总计 " + (pass + fail) + " 项，通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
        System.out.println("ALL GREEN");
    }
}
