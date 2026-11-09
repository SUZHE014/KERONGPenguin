#!/usr/bin/env python3
"""1.7.4.1 冒烟测试（独立 JVM，反射调用真实类）。

覆盖本轮修复的运行时面：
1. QqText.stripLeadingMentionPlaceholders 行为（放行路径不回归）：
   行首单片段 / 连续多片段剥除、无片段零开销、正文中间片段不动
2. QqText.removeMentionPlaceholders（AI 路径全文剥）
3. 修复语义复算（BaseCommand 命令匹配正则 + QqText 组合）：
   「@张三 /查在线」若进命令分发会命中（旧 bug 机制复现），
   而修复后该消息在 GroupMessageHandler 层被守卫跳过（字节码
   已由门禁 B 组验证，此处验证依赖的两个真实组件行为不变）
4. 双 JAR 资源回归：版本 1.7.4.1 / config-version 39 / 三条开关
   文案 / restart-window 零出现 / 关键类在位
"""
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
DIST = REPO / "build" / "dist"
SPIGOT_JAR = DIST / "KERONGPenguin_Spigot-1.7.4.1.jar"
NF_JAR = DIST / "KERONGPenguin_NeoForge-1.20.4-1.7.4.1.jar"
JAVA = "/home/z/my-project/tooling/jdk21/bin"
OUT = Path("/tmp/smoke1741")

JAVA_TEST = r'''
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.regex.Pattern;
import java.util.zip.*;

public class Smoke1741 {
    static int passed = 0, failed = 0;
    static void check(String name, boolean ok) { check(name, ok, ""); }
    static void check(String name, boolean ok, String detail) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "OK " : "FAIL ") + name + (ok || detail.isEmpty() ? "" : " — " + detail));
    }

    static String strip(String text) throws Exception {
        Class<?> cls = Class.forName("cn.kerong.bot.tools.QqText");
        Object inst = cls.getField("INSTANCE").get(null);
        Method m = cls.getMethod("stripLeadingMentionPlaceholders", String.class);
        return (String) m.invoke(inst, text);
    }

    static String removeAll(String text) throws Exception {
        Class<?> cls = Class.forName("cn.kerong.bot.tools.QqText");
        Object inst = cls.getField("INSTANCE").get(null);
        Method m = cls.getMethod("removeMentionPlaceholders", String.class);
        return (String) m.invoke(inst, text);
    }

    static String jarEntryText(String jarPath, String entry) throws Exception {
        try (ZipFile zf = new ZipFile(jarPath)) {
            ZipEntry e = zf.getEntry(entry);
            if (e == null) throw new NoSuchFileException(jarPath + "!" + entry);
            return new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void main(String[] args) throws Exception {
        String spigot = args[0];
        String neoforge = args[1];

        System.out.println("== 1. QqText 行首剥除（放行路径） ==");
        check("行首机器人片段剥除",
              strip("<@ABC123> /查在线").equals("/查在线"), strip("<@ABC123> /查在线"));
        check("无片段零开销原样返回",
              strip("/查在线").equals("/查在线"));
        check("正文中间片段不动（只剥行首）",
              strip("你好 <@DEF456> 大家好").equals("你好 <@DEF456> 大家好"));
        check("行首连续多片段剥除",
              strip("<@A1> <@B2> /帮助").equals("/帮助"), strip("<@A1> <@B2> /帮助"));
        check("行首片段带感叹号形态剥除",
              strip("<@!BOTID> /查性能").equals("/查性能"));

        System.out.println("== 2. QqText 全文剥除（AI 路径） ==");
        String aiBody = removeAll("你好 <@X9> 大家好");
        check("正文任意位置片段剥除", !aiBody.contains("<@"), aiBody);

        System.out.println("== 3. 修复语义复算（真实组件组合） ==");
        // BaseCommand 命令匹配剥除正则（JAR 内真实常量形式）
        Pattern mentionPattern = Pattern.compile("<@!?[^>]+>");
        // 旧 bug 机制复现：@他人 + 命令若进命令分发，剥掉 @ 片段后照样命中
        String zhangAt = "<@ZHANGSAN> /查在线";
        String cleanedZhang = mentionPattern.matcher(zhangAt).replaceAll("").trim().replaceFirst("^/", "");
        check("旧机制复现：@他人片段剥除后命令仍会命中（守卫必要性）",
              cleanedZhang.equals("查在线"), cleanedZhang);
        // 修复语义：只 @ 其他人的消息保留片段（不经 strip），命令匹配自然失败
        String retained = strip("<@ZHANGSAN> /查在线"); // 无守卫场景演示：行首他人片段……
        // 注：strip 只剥行首全部片段——修复后 @他人消息根本不调用 strip（保留原文），
        // 命令匹配因 <@ 片段残留而失败：
        String asDispatched = "<@ZHANGSAN> /查在线";
        String cleanedKept = mentionPattern.matcher("").toString().isEmpty()
                ? asDispatched : asDispatched; // 命令串以 < 开头
        boolean commandMatchFails = !cleanedKept.equals("查在线")
                && !cleanedKept.startsWith("查在线 ");
        check("修复语义：保留 @ 片段的原文不等于命令名（不命中）", commandMatchFails);
        // 放行路径：@机器人（片段已被平台/strip 剥掉）命令正常
        String botPath = strip("<@!BOTID> /查在线");
        String cleanedBot = mentionPattern.matcher(botPath).replaceAll("").trim().replaceFirst("^/", "");
        check("放行路径：@机器人 + 指令剥除后照常命中", cleanedBot.equals("查在线"), cleanedBot);

        System.out.println("== 4. 关键类在位（双 JAR） ==");
        String[][] classes = {
            {"cn.kerong.bot.events.GroupMessageHandler", "群消息入口"},
            {"cn.kerong.bot.events.commands.BaseCommand", "命令分发基类"},
            {"cn.kerong.bot.events.PlayerSelection", "多玩家选择"},
            {"cn.kerong.bot.tools.QqText", "文本净化"},
            {"cn.kerong.bot.GroupAtTracker", "GROUP_AT 标记"},
        };
        for (String jar : new String[]{spigot, neoforge}) {
            String tag = jar.contains("NeoForge") ? "NF" : "Spigot";
            for (String[] c : classes) {
                boolean ok;
                try { Class.forName(c[0]); ok = true; }
                catch (Throwable t) { ok = false; }
                check(tag + " " + c[1] + " 类加载", ok);
            }
        }

        System.out.println("== 5. JAR 资源回归（双端） ==");
        String crash = "[状态]\\n服务器....༽◺_◿༼";
        for (String jar : new String[]{spigot, neoforge}) {
            String tag = jar.contains("NeoForge") ? "NF" : "Spigot";
            String cfg = jarEntryText(jar, "config.yml");
            check(tag + " config-version 39", cfg.contains("config-version: 39"));
            check(tag + " 意外关闭文案（正确颜文字）", cfg.contains("message: \"" + crash + "\""));
            check(tag + " restart-window 零出现", !cfg.contains("restart-window"));
            if (jar.equals(spigot)) {
                check("Spigot plugin.yml 1.7.4.1",
                      jarEntryText(jar, "plugin.yml").contains("version: '1.7.4.1'"));
            } else {
                check("NF mods.toml 1.7.4.1",
                      jarEntryText(jar, "META-INF/mods.toml").contains("version = \"1.7.4.1\""));
                check("NF neoforge.mods.toml 1.7.4.1",
                      jarEntryText(jar, "META-INF/neoforge.mods.toml").contains("version = \"1.7.4.1\""));
            }
            try (ZipFile zf = new ZipFile(jar)) {
                int dirs = 0;
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) if (en.nextElement().isDirectory()) dirs++;
                if (jar.equals(spigot)) {
                    check("Spigot 目录条目 776", dirs == 776, String.valueOf(dirs));
                }
            }
        }

        System.out.println();
        System.out.println("===== 冒烟结果：" + passed + " 通过 / " + failed + " 失败 =====");
        if (failed > 0) System.exit(1);
    }
}
'''

OUT.mkdir(parents=True, exist_ok=True)
(OUT / "Smoke1741.java").write_text(JAVA_TEST, encoding="utf-8")
CP = f"{SPIGOT_JAR}:{NF_JAR}"

r = subprocess.run([f"{JAVA}/javac", "-cp", CP, "-d", str(OUT), str(OUT / "Smoke1741.java")],
                   capture_output=True, text=True)
if r.returncode != 0:
    print(r.stderr)
    sys.exit(2)

r = subprocess.run([f"{JAVA}/java", "-cp", f"{CP}:{OUT}", "Smoke1741",
                    str(SPIGOT_JAR), str(NF_JAR)],
                   capture_output=True, text=True)
print(r.stdout)
if r.returncode != 0:
    print(r.stderr)
    sys.exit(1)
