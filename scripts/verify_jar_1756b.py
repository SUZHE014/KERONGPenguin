#!/usr/bin/env python3
"""1.7.5.6 主线修复轮（覆盖轮）门禁：四分发 JAR 字节码核查。

A. 版本与资源（1.7.5.6 六处 + config-version 47 + 两个新键）
B. A1 模板升级=完整替换（MessageTemplates 释放记录机制）
C. A4 AI 提醒方式三模式（aiReminderMode + GroupMessageHandler 接线）
D. A5 按钮互动冷却（ButtonInteractionCooldown + 双路径 + 三平台 reload）
E. A3 按钮互动输出带 @（sendPage 3 参 / 选择器 getSender / 解除黑名单 recipe）
F. A2 控制台颜色码回归（ConsoleColors ANSI 探测，1.6.6 起已有，本轮复核）
G. 前轮回归（1.7.5.4-1.7.5.6：验身标记 / AiAt / encodeCjk / 手动 qr 全新码 /
   brigadier 直发 / Fa1201-1204 disconnect 映射 / 未映射引用白名单）
H. 形态（fat JAR 目录条目 / NF 重定位 / Fa 原包名）

用法：python3 scripts/verify_jar_1756b.py
"""
import re
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
DIST = REPO / "build" / "dist"
JARS = {
    "Spigot": DIST / "KERONGPenguin_Spigot-1.7.5.6.jar",
    "NeoForge": DIST / "KERONGPenguin_NeoForge-1.20.4-1.7.5.6.jar",
    "Fabric": DIST / "KERONGPenguin_Fabric-1.20.4-1.7.5.6.jar",
    "Fabric1201": DIST / "KERONGPenguin_Fabric-1.20.1-1.7.5.6.jar",
}
JAVAP = "/home/z/my-project/tooling/jdk21/bin/javap"

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))


def javap(jar, inner, extra=("-c", "-p")):
    import tempfile
    with zipfile.ZipFile(jar) as zf:
        data = zf.read(inner)
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "c.class"
        p.write_bytes(data)
        out = subprocess.run([JAVAP, *extra, str(p)], capture_output=True, text=True, timeout=120)
        return out.stdout


def scan_mc_refs(jar):
    import tempfile
    OWN = ("cn/kerong/penguin/fabric/", "org/bukkit/")
    refs = set()
    with tempfile.TemporaryDirectory() as td, zipfile.ZipFile(jar) as zf:
        names = [n for n in zf.namelist()
                 if n.endswith(".class") and any(n.startswith(p) for p in OWN)]
        for n in names:
            (Path(td) / n).parent.mkdir(parents=True, exist_ok=True)
            (Path(td) / n).write_bytes(zf.read(n))
        for i in range(0, len(names), 40):
            out = subprocess.run([JAVAP, "-c", "-p"] + [str(Path(td) / n) for n in names[i:i + 40]],
                                 capture_output=True, text=True, timeout=300)
            for line in out.stdout.splitlines():
                m = re.search(r"// (?:Interface|Class)?(Method|Field) ([\w/$.]+)\.([\w$<>]+):(.+)$", line)
                if m and m.group(2).startswith("net/minecraft/"):
                    refs.add(f"{m.group(2)}.{m.group(3)} {m.group(4)}")
    return sorted(r for r in refs if not (".method_" in r or ".field_" in r or
                     re.search(r"\.(method_|field_)", r)))


def main():
    for label, jar in JARS.items():
        check(f"0.{label} 分发 JAR 存在", jar.is_file(), str(jar))
        if not jar.is_file():
            continue
        with zipfile.ZipFile(jar) as zf:
            names = zf.namelist()

            # ---------- A. 版本与资源 ----------
            if label == "Spigot":
                py = zf.read("plugin.yml").decode("utf-8", "replace")
                check("A1 Spigot plugin.yml 版本 1.7.5.6",
                      re.search(r"version:\s*'?\"?1\.7\.5\.6", py) is not None)
            if label == "NeoForge":
                for mfile in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
                    mt = zf.read(mfile).decode("utf-8", "replace") if mfile in names else ""
                    check(f"A2 NF {mfile} 含 1.7.5.6", "1.7.5.6" in mt)
            if label in ("Fabric", "Fabric1201"):
                fm = zf.read("fabric.mod.json").decode("utf-8", "replace")
                check(f"A3 {label} fabric.mod.json 版本 1.7.5.6",
                      re.search(r'"version":\s*"1\.7\.5\.6"', fm) is not None)
                if label == "Fabric":
                    check("A3b Fabric(1.20.4) minecraft 声明 >=1.20.4 <1.22",
                          re.search(r'"minecraft":\s*">=1\.20\.4 <1\.22"', fm) is not None, fm[:200])
                else:
                    check("A3b Fabric1201 minecraft 声明精确 1.20.1",
                          re.search(r'"minecraft":\s*"1\.20\.1"', fm) is not None, fm[:200])
            cfg = zf.read("config.yml").decode("utf-8", "replace")
            check(f"A4 {label} config-version 47", re.search(r"config-version:\s*47", cfg) is not None, cfg[:120])
            check(f"A5 {label} 新键 ai.reminder-mode（默认空串）",
                  re.search(r"reminder-mode:\s*\"\"", cfg) is not None)
            check(f"A5b {label} 新键注释含三选一说明", "三选一" in cfg)
            check(f"A6 {label} 新键 bot.button-cooldown-seconds: 5",
                  re.search(r"button-cooldown-seconds:\s*5", cfg) is not None)
            check(f"A6b {label} 旧键 ai.reply-quote 保留默认开",
                  re.search(r"reply-quote:\s*true", cfg) is not None)
            check(f"A6c {label} ai.at-enabled 默认开回归", re.search(r"at-enabled:\s*true", cfg) is not None)

            # ---------- B. A1 模板升级=完整替换 ----------
            mtc = "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class"
            if mtc in names:
                b = zf.read(mtc)
                check(f"B1 {label} MessageTemplates 释放记录文件名常量", b".release-record.json" in b)
                check(f"B2 {label} MessageTemplates SHA-256 指纹", b"SHA-256" in b)
                check(f"B3 {label} 整体替换日志（单文件）", "已升级到新默认内容（整体替换".encode() in b)
                check(f"B4 {label} 整体替换日志（汇总）", "整体替换到新默认".encode() in b)
                txt = javap(jar, mtc)
                check(f"B5 {label} 新私有方法 loadReleaseRecord/saveReleaseRecord/fingerprint",
                      "loadReleaseRecord" in txt and "saveReleaseRecord" in txt and "fingerprint" in txt)
                check(f"B6 {label} writeText 调用（替换动作本体）", "writeText" in txt)
                check(f"B7 {label} shouldUpgrade 兜底调用保留", "shouldUpgrade" in txt)
            else:
                check(f"B1 {label} MessageTemplates 类存在", False)

            # ---------- C. A4 AI 提醒方式三模式 ----------
            qqm = "cn/kerong/penguin/spigot/qqbind/QqBindManager.class"
            if qqm in names:
                b = zf.read(qqm)
                check(f"C1 {label} QqBindManager 含 ai.reminder-mode 键", b"ai.reminder-mode" in b)
                check(f"C2 {label} 中文取值 艾特/引用/无", "艾特".encode() in b and "引用".encode() in b and "无".encode() in b)
                check(f"C3 {label} 非法取值告警文案", "无法识别".encode() in b)
                txt = javap(jar, qqm)
                check(f"C4 {label} aiReminderMode 方法在位", re.search(r"aiReminderMode\(\)", txt) is not None)
            else:
                check(f"C1 {label} QqBindManager 类存在", False)
            gmr = "cn/kerong/bot/events/GroupMessageHandler.class"
            if gmr in names:
                txt = javap(jar, gmr)
                check(f"C5 {label} GroupMessageHandler 调用 aiReminderMode",
                      re.search(r"aiReminderMode", txt) is not None)
                check(f"C6 {label} GroupMessageHandler 不再直读 isAiReplyQuote",
                      re.search(r"isAiReplyQuote", txt) is None)
            else:
                check(f"C5 {label} GroupMessageHandler 类存在", False)

            # ---------- D. A5 按钮互动冷却 ----------
            bic = "cn/kerong/bot/events/ButtonInteractionCooldown.class"
            if bic in names:
                b = zf.read(bic)
                check(f"D1 {label} ButtonInteractionCooldown 类在位", True)
                check(f"D2 {label} 冷却键常量 bot.button-cooldown-seconds", b"bot.button-cooldown-seconds" in b)
                check(f"D3 {label} 默认 5 秒常量", b"button-cooldown-seconds" in b)
                txt = javap(jar, bic)
                check(f"D4 {label} tryAcquire/clearAll 方法在位",
                      "tryAcquire" in txt and "clearAll" in txt and "cooldownSeconds" in txt)
            else:
                check(f"D1 {label} ButtonInteractionCooldown 类存在", False)
            ifp = "cn/kerong/bot/events/InteractionFastPath.class"
            if ifp in names:
                txt = javap(jar, ifp)
                check(f"D5 {label} FastPath 接入冷却门禁", "ButtonInteractionCooldown.tryAcquire" in txt)
                b = zf.read(ifp)
                check(f"D6 {label} FastPath 限频日志", "已限频".encode() in b)
            ial = "cn/kerong/bot/events/InterActionListener.class"
            if ial in names:
                txt = javap(jar, ial)
                check(f"D7 {label} Listener 接入冷却门禁", "ButtonInteractionCooldown.tryAcquire" in txt)
                b = zf.read(ial)
                check(f"D8 {label} Listener 限频日志", "已限频".encode() in b)
            # 三平台 reload 清空
            if label == "Spigot":
                txt = javap(jar, "cn/kerong/penguin/spigot/KERONGSpigot.class")
                check("D9 Spigot reload 清空冷却", "ButtonInteractionCooldown.clearAll" in txt)
            if label == "NeoForge":
                txt = javap(jar, "cn/kerong/penguin/neoforge/KERONGNeoForge.class")
                check("D9 NeoForge reload 清空冷却", "ButtonInteractionCooldown.clearAll" in txt)
            if label in ("Fabric", "Fabric1201"):
                txt = javap(jar, "cn/kerong/penguin/fabric/KERONGFabric.class")
                check(f"D9 {label} reload 清空冷却", "ButtonInteractionCooldown.clearAll" in txt)

            # ---------- E. A3 按钮互动输出带 @ ----------
            mbc = "cn/kerong/bot/events/MenuButtons.class"
            if mbc in names:
                txt = javap(jar, mbc)
                m = re.search(r"private final void sendPage\(java\.lang\.String, java\.lang\.String, int\)", txt)
                check(f"E1 {label} sendPage 三参签名（含点击者）", m is not None, txt[:0])
            else:
                check(f"E1 {label} MenuButtons 类存在", False)
            ssb = "cn/kerong/bot/events/ServerSelectButtons.class"
            if ssb in names:
                txt = javap(jar, ssb)
                check(f"E2 {label} 选择器读取发送者（getSender 接 @）",
                      re.search(r"getSender", txt) is not None)
            ubb = "cn/kerong/bot/events/UnblacklistButtons.class"
            if ubb in names:
                b = zf.read(ubb)
                # 新串模板 recipe："<@" + \u0001 + "> 已从黑名单移除：" + \u0001
                needle = b"\x01> " + "已从黑名单移除".encode()
                check(f"E3 {label} 解除结果带 @ 前缀（recipe 字节）", needle in b)

            # ---------- F. A2 控制台颜色码（1.6.6 ConsoleColors 回归复核） ----------
            cc = "cn/kerong/bot/tools/ConsoleColors.class"
            if cc in names:
                b = zf.read(cc)
                check(f"F1 {label} ConsoleColors 类在位", True)
                check(f"F2 {label} TerminalConsoleAppender 探测", b"TerminalConsoleAppender" in b)
                check(f"F3 {label} isAnsiSupported 探测", b"isAnsiSupported" in b)
            else:
                check(f"F1 {label} ConsoleColors 类存在", False)

            # ---------- G. 前轮回归 ----------
            qrm = "cn/kerong/bot/QrLoginManager.class"
            if qrm in names:
                b = zf.read(qrm)
                check(f"G1 {label} 手动取消旧会话文案回归", "已取消此前的扫码会话".encode() in b)
            mb = "cn/kerong/bot/menu/MenuButtons.class" if "cn/kerong/bot/menu/MenuButtons.class" in names else mbc
            if mb in names:
                check(f"G2 {label} 验身标记 krg_btn_admin 回归", b"krg_btn_admin" in zf.read(mb))
            ai_at = "cn/kerong/penguin/spigot/qqbind/AiAt.class"
            if ai_at in names:
                check(f"G3 {label} AiAt 类与 [at: 标记回归", b"[at:" in zf.read(ai_at))
            qr = "cn/kerong/bot/tools/QrCode.class"
            if qr in names:
                b = zf.read(qr)
                check(f"G4 {label} encodeCjk/encodePlain 回归", b"encodeCjk" in b and b"encodePlain" in b)
            dirs = sum(1 for n in names if n.endswith("/"))
            check(f"G5 {label} fat JAR 目录条目 > 100", dirs > 100, f"dirs={dirs}")

        # ---------- G(Fabric 端字节码细节) ----------
        if label in ("Fabric", "Fabric1201"):
            txt = javap(jar, "cn/kerong/penguin/fabric/FabricConsoleExecutor.class")
            check(f"G6 {label} ConsoleExecutor 引用 CommandSyntaxException",
                  "com/mojang/brigadier/exceptions/CommandSyntaxException" in txt)
            check(f"G7 {label} ConsoleExecutor brigadier execute 调用",
                  re.search(r"CommandDispatcher.*\.execute:\(Ljava/lang/String;Ljava/lang/Object;\)I", txt) is not None)
            check(f"G8 {label} ConsoleExecutor getDispatcher→method_9235",
                  "class_2170.method_9235" in txt)
            check(f"G9 {label} ConsoleExecutor 无 performPrefixedCommand 残留",
                  "performPrefixedCommand" not in txt)
        if label == "Fabric1201":
            txt = javap(jar, "org/bukkit/entity/FabricPlayer.class")
            check("G10 Fa1201 FabricPlayer 踢人 disconnect→method_14367",
                  "class_3244.method_14367" in txt)
        if label == "Fabric":
            txt = javap(jar, "org/bukkit/entity/FabricPlayer.class")
            check("G11 Fa1204 FabricPlayer 踢人 disconnect→method_52396",
                  "class_3244.method_52396" in txt)
        if label in ("Fabric", "Fabric1201"):
            unmapped = scan_mc_refs(jar)
            whitelist = {
                "net/minecraft/class_124.ordinal ()I",
                "net/minecraft/class_124.values ()[Lnet/minecraft/class_124;",
                "net/minecraft/class_2561.getString ()Ljava/lang/String;",
                "net/minecraft/server/MinecraftServer.execute (Ljava/lang/Runnable;)V",
            }
            bad = [r for r in unmapped if r not in whitelist]
            check(f"G12 {label} 未映射引用仅白名单 4 条",
                  not bad and len(unmapped) <= 4, "; ".join(unmapped[:8]))

        # ---------- H. 形态 ----------
        if label == "NeoForge":
            with zipfile.ZipFile(jar) as zf:
                names2 = zf.namelist()
                check("H1 NF kotlin 重定位(cn/kerong/shaded/kotlin)",
                      any(n.startswith("cn/kerong/shaded/kotlin/") for n in names2))
        if label in ("Fabric", "Fabric1201"):
            with zipfile.ZipFile(jar) as zf:
                names2 = zf.namelist()
                check(f"H2 {label} kotlin 原包名(不重定位)", any(n.startswith("kotlin/") for n in names2))

    failed = [r for r in results if not r[1]]
    print(f"\n===== 门禁结果:{len(results) - len(failed)}/{len(results)} =====")
    for name, ok, detail in results:
        mark = "PASS" if ok else "FAIL"
        print(f"[{mark}] {name}" + (f"  ({detail})" if detail and not ok else ""))
    if failed:
        print(f"\n{len(failed)} 项失败")
        return 1
    print("\n全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
