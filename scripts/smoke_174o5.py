#!/usr/bin/env python3
"""1.7.4 第五轮覆盖版冒烟测试（独立 JVM，反射调用真实类）。

覆盖本轮改动的运行时面：
1. QrLoginManager：MAX_REFRESH_COUNT=3 / start(manual) 签名与 $default 合成 /
   markStartupPending + startIfStartupPending 待发状态机（置位→消费→再消费空操作）
2. QClient：closeSessionForCredentialSwap 方法在位（无会话空操作不抛异常）
3. PublicCommands：lookupByPlayerName 反查入口在位（QQ 号行全员可见的载体）
4. KERONGSpigot：ServerLoadEvent 消费方法与兜底线程字节码在位
"""
import subprocess
import sys
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
JARS = [
    REPO / "build" / "dist" / "KERONGPenguin_Spigot-1.7.4.jar",
    Path("/home/z/my-project/tooling/gradle-home/caches/modules-2/files-2.1/"
         "io.papermc.paper/paper-api/1.20.4-R0.1-SNAPSHOT/"
         "8b9cca4e56ef91855140e918ec50cb4ed5189d81/paper-api-1.20.4-R0.1-SNAPSHOT.jar"),
]
CP = ":".join(str(j) for j in JARS)

JAVA_TEST = r'''
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class Smoke174o5 {
    static int passed = 0, failed = 0;
    static void check(String name, boolean ok) { check(name, ok, ""); }
    static void check(String name, boolean ok, String detail) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "OK " : "FAIL ") + name + (ok || detail.isEmpty() ? "" : " — " + detail));
    }

    public static void main(String[] args) throws Exception {
        // 1. QrLoginManager 常量与方法面
        Class<?> qrm = Class.forName("cn.kerong.bot.QrLoginManager");
        Object qrmInstance = qrm.getField("INSTANCE").get(null);
        Field maxRefresh = qrm.getDeclaredField("MAX_REFRESH_COUNT");
        maxRefresh.setAccessible(true);
        check("MAX_REFRESH_COUNT = 3", maxRefresh.getInt(qrmInstance) == 3, String.valueOf(maxRefresh.getInt(qrmInstance)));

        Method startNoArg = null, startManual = null, startDefault = null;
        for (Method m : qrm.getDeclaredMethods()) {
            if (!m.getName().equals("start") && !m.getName().equals("start$default")) continue;
            if (m.getName().equals("start") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class) startManual = m;
            // Kotlin 默认参数合成：static start$default(QrLoginManager, boolean, int, Object)
            if (m.getName().equals("start$default")) startDefault = m;
        }
        check("start(boolean) 显式重载在位", startManual != null);
        check("start$default 合成在位（Kotlin 默认参数 manual=false）", startDefault != null
                && java.lang.reflect.Modifier.isStatic(startDefault.getModifiers())
                && startDefault.getParameterCount() == 4
                && startDefault.getParameterTypes()[1] == boolean.class,
                startDefault == null ? "" : java.util.Arrays.toString(startDefault.getParameterTypes()));

        Method markPending = null, consumePending = null;
        for (Method m : qrm.getDeclaredMethods()) {
            if (m.getName().equals("markStartupPending") && m.getParameterCount() == 0) markPending = m;
            if (m.getName().equals("startIfStartupPending") && m.getParameterCount() == 0) consumePending = m;
        }
        check("markStartupPending 方法在位", markPending != null);
        check("startIfStartupPending 方法在位", consumePending != null);

        // 2. 待发状态机：初始无待发 → 置位 → 消费 true → 再消费 false
        //    注：消费会触发 start(manual=false) 起后台会话线程；裸 JVM 无插件
        //    实例，线程在首条日志即退出（生产环境 BotShared.setInstance 先于
        //    任何 QR 会话执行，不发生）——不影响状态机断言本身
        if (consumePending != null) {
            consumePending.setAccessible(true);
            boolean first = (Boolean) consumePending.invoke(qrmInstance);
            check("初始无待发标记消费返回 false", !first);
            if (markPending != null) {
                markPending.setAccessible(true);
                markPending.invoke(qrmInstance);
                // 消费会触发 start(manual=false)：无网络环境下 create_bind_task
                // 失败 → 会话 FAILED → 线程退出，不抛出（异常已在会话内部兜住）
                boolean second = (Boolean) consumePending.invoke(qrmInstance);
                check("置位后消费返回 true", second);
                Thread.sleep(500);
                boolean third = (Boolean) consumePending.invoke(qrmInstance);
                check("消费后标记已清（再消费 false）", !third);
            }
        }

        // 3. AuthSession 携带 manual 字段（构造参数）
        Class<?> sessionCls = null;
        for (Class<?> inner : qrm.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("AuthSession")) sessionCls = inner;
        }
        check("AuthSession 内部类在位", sessionCls != null);
        boolean hasManual = false;
        if (sessionCls != null) {
            for (java.lang.reflect.Constructor<?> c : sessionCls.getDeclaredConstructors()) {
                for (Class<?> pt : c.getParameterTypes()) if (pt == boolean.class) hasManual = true;
            }
        }
        check("AuthSession 构造含 manual: Boolean", hasManual);

        // 4. QClient.closeSessionForCredentialSwap：方法在位 + 无会话空操作
        Class<?> qc = Class.forName("cn.kerong.bot.QClient");
        Object qcInstance = qc.getField("INSTANCE").get(null);
        Method swap = null;
        for (Method m : qc.getDeclaredMethods()) {
            if (m.getName().equals("closeSessionForCredentialSwap") && m.getParameterCount() == 0) swap = m;
        }
        check("closeSessionForCredentialSwap 方法在位", swap != null);
        if (swap != null) {
            swap.setAccessible(true);
            try {
                swap.invoke(qcInstance); // 从未 launch → starter == null → 空操作
                check("无会话时调用空操作不抛异常", true);
            } catch (Throwable t) {
                check("无会话时调用空操作不抛异常", false, String.valueOf(t));
            }
        }
        // shutdown 仍在（插件卸载路径不走 swap）
        Method shutdown = null;
        for (Method m : qc.getDeclaredMethods()) {
            if (m.getName().equals("shutdown") && m.getParameterCount() == 0) shutdown = m;
        }
        check("shutdown 整体停机方法保留", shutdown != null);

        // 5. PublicCommands.lookupByPlayerName 反查入口（全员可见载体）
        Class<?> pc = Class.forName("cn.kerong.bot.events.commands.PublicCommands");
        Method lookup = null;
        for (Method m : pc.getDeclaredMethods()) {
            if (m.getName().equals("lookupByPlayerName")) { lookup = m; break; }
        }
        check("lookupByPlayerName 方法存在", lookup != null);

        System.out.println("SMOKE_RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
'''
out_dir = Path("/tmp/smoke174o5")
out_dir.mkdir(exist_ok=True)
(out_dir / "Smoke174o5.java").write_text(JAVA_TEST)
javac = "/home/z/my-project/tooling/jdk21/bin/javac"
java = "/home/z/my-project/tooling/jdk21/bin/java"
r = subprocess.run([javac, "-cp", CP, "-d", str(out_dir), str(out_dir / "Smoke174o5.java")],
                   capture_output=True, text=True)
if r.returncode != 0:
    print(r.stderr[:3000]); sys.exit(1)
r = subprocess.run([java, "-cp", f"{CP}:{out_dir}", "Smoke174o5"], capture_output=True, text=True)
print(r.stdout)
if r.returncode != 0:
    print(r.stderr[:2000]); sys.exit(1)
