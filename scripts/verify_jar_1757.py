#!/usr/bin/env python3
"""1.7.5.7 修复轮门禁：四分发 JAR 字节码核查。

本轮修复（B 段）：
  艾特其他机器人误判——isMentionedBot 通道 2 删除 User.getBot 泛匹配，
  只认 botSelfId/botAppId 精确 id；AiAt.renderOutgoing 一级反查排除 bot
  成员；OpenIdDirectory.recordEvent mentions 不再收录机器人。

A. 版本与资源（1.7.5.7 + config-version 47 + 1.7.5.6 两新键回归）
B. 本轮修复（isMentionedBot 无 getBot / 通道一保留 / renderOutgoing·
   recordEvent 有 getBot 排除）
C. 前轮回归（1.7.5.4-1.7.5.6：释放记录 / aiReminderMode / 按钮冷却 /
   sendPage 三参 / ConsoleColors / 手动 qr / 验身标记 / AiAt / brigadier
   直发 / Fa1201-1204 disconnect 映射 / 未映射白名单）
H. 形态（fat JAR 目录 / NF 重定位 / Fa 原包名）

用法：python3 scripts/verify_jar_1757.py
"""
import re
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
DIST = REPO / "build" / "dist"
JARS = {
    "Spigot": DIST / "KERONGPenguin_Spigot-1.7.5.7.jar",
    "NeoForge": DIST / "KERONGPenguin_NeoForge-1.20.4-1.7.5.7.jar",
    "Fabric": DIST / "KERONGPenguin_Fabric-1.20.4-1.7.5.7.jar",
    "Fabric1201": DIST / "KERONGPenguin_Fabric-1.20.1-1.7.5.7.jar",
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


def method_body(javap_txt, name):
    """抽取指定方法名的字节码方法体（javap -c 分段）。"""
    out, inside = [], False
    decl = re.compile(r"^  \S[^(]*\(")
    for line in javap_txt.splitlines():
        if decl.match(line):
            if inside:
                break
            inside = re.search(rf"\b{re.escape(name)}\(", line) is not None
        if inside:
            out.append(line)
    return "\n".join(out)


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
                check("A1 Spigot plugin.yml 版本 1.7.5.7",
                      re.search(r"version:\s*'?\"?1\.7\.5\.7", py) is not None)
            if label == "NeoForge":
                for mfile in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
                    mt = zf.read(mfile).decode("utf-8", "replace") if mfile in names else ""
                    check(f"A2 NF {mfile} 含 1.7.5.7", "1.7.5.7" in mt)
                kt = javap(jar, "cn/kerong/penguin/neoforge/KERONGNeoForge.class", ("-p",))
                check("A2b NF MOD_VERSION 1.7.5.7（ldc 常量池）",
                      "1.7.5.7" in javap(jar, "cn/kerong/penguin/neoforge/KERONGNeoForge.class", ("-c", "-p")))
            if label in ("Fabric", "Fabric1201"):
                fm = zf.read("fabric.mod.json").decode("utf-8", "replace")
                check(f"A3 {label} fabric.mod.json 版本 1.7.5.7",
                      re.search(r'"version":\s*"1\.7\.5\.7"', fm) is not None)
                if label == "Fabric":
                    check("A3b Fabric(1.20.4) minecraft 声明 >=1.20.4 <1.22",
                          re.search(r'"minecraft":\s*">=1\.20\.4 <1\.22"', fm) is not None, fm[:200])
                else:
                    check("A3b Fabric1201 minecraft 声明精确 1.20.1",
                          re.search(r'"minecraft":\s*"1\.20\.1"', fm) is not None, fm[:200])
            cfg = zf.read("config.yml").decode("utf-8", "replace")
            check(f"A4 {label} config-version 47（本轮无新键）", re.search(r"config-version:\s*47", cfg) is not None, cfg[:120])
            check(f"A5 {label} 1.7.5.6 键 ai.reminder-mode 回归",
                  re.search(r"reminder-mode:\s*\"\"", cfg) is not None)
            check(f"A6 {label} 1.7.5.6 键 bot.button-cooldown-seconds 回归",
                  re.search(r"button-cooldown-seconds:\s*5", cfg) is not None)
            check(f"A6b {label} 旧键 ai.reply-quote / ai.at-enabled 回归",
                  re.search(r"reply-quote:\s*true", cfg) is not None and
                  re.search(r"at-enabled:\s*true", cfg) is not None)

            # ---------- B. 本轮修复：艾特其他机器人误判 ----------
            gmr = "cn/kerong/bot/events/GroupMessageHandler.class"
            if gmr in names:
                txt = javap(jar, gmr)
                body = method_body(txt, "isMentionedBot")
                check(f"B1 {label} isMentionedBot 方法体已抽取", "isMentionedBot" in body and "Code:" in body,
                      body[:120] if body else "EMPTY")
                check(f"B2 {label} isMentionedBot 不再调用 User.getBot（删泛匹配）",
                      "User.getBot" not in body)
                check(f"B3 {label} 通道一 GroupAtTracker.isAtBot 保留",
                      "GroupAtTracker.isAtBot" in body)
                check(f"B4 {label} 通道二精确 id 匹配保留（Bot.getId botSelfId）",
                      "Bot.getId" in body)
                check(f"B5 {label} 通道二 botAppId 匹配保留（getId 比较双源）",
                      body.count("getId") >= 2, f"getId x{body.count('getId')}")
            else:
                check(f"B1 {label} GroupMessageHandler 类存在", False)
            ai_at = "cn/kerong/penguin/spigot/qqbind/AiAt.class"
            if ai_at in names:
                txt = javap(jar, ai_at)
                ro = method_body(txt, "renderOutgoing")
                check(f"B6 {label} renderOutgoing 一级反查排除 bot（getBot）",
                      "User.getBot" in ro)
                pi = method_body(txt, "parseIncomingMentions")
                check(f"B7 {label} parseIncomingMentions bot 剥除回归（getBot）",
                      "User.getBot" in pi)
                # Regex.replace 的 lambda 未内联，两级反查落在合成方法 renderOutgoing$lambda$N 内
                lambdas = re.findall(r"renderOutgoing\$lambda\$\d+", txt)
                lam_bodies = "\n".join(method_body(txt, l) for l in set(lambdas))
                check(f"B8 {label} renderOutgoing 两级反查（合成方法内 lookupOpenIdByNickname）",
                      "lookupOpenIdByNickname" in lam_bodies,
                      f"lambdas={sorted(set(lambdas))}")
            else:
                check(f"B6 {label} AiAt 类存在", False)
            oid = "cn/kerong/penguin/spigot/qqbind/OpenIdDirectory.class"
            if oid in names:
                txt = javap(jar, oid)
                re_body = method_body(txt, "recordEvent")
                check(f"B9 {label} recordEvent mentions 排除 bot（getBot）",
                      "User.getBot" in re_body)
                # 同类 record 调用 javap 不带类前缀（invokespecial Method record:）；
                # 发送者 + mentions 各一次，共 ≥2 处
                check(f"B10 {label} recordEvent 发送者+mentions 均入库（record 调用 ≥2）",
                      re_body.count("Method record:") >= 2,
                      f"record x{re_body.count('Method record:')}")
            else:
                check(f"B9 {label} OpenIdDirectory 类存在", False)

            # ---------- C. 前轮回归（1.7.5.6 B-E/F 段 + 更早 G 段） ----------
            mtc = "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class"
            if mtc in names:
                b = zf.read(mtc)
                check(f"C1 {label} 释放记录机制回归", b".release-record.json" in b and b"SHA-256" in b)
            qqm = "cn/kerong/penguin/spigot/qqbind/QqBindManager.class"
            if qqm in names:
                b = zf.read(qqm)
                check(f"C2 {label} aiReminderMode 三模式回归",
                      b"ai.reminder-mode" in b and "艾特".encode() in b and "引用".encode() in b)
            txt = javap(jar, gmr) if gmr in names else ""
            check(f"C3 {label} GroupMessageHandler 接线 aiReminderMode 回归",
                  "aiReminderMode" in txt)
            bic = "cn/kerong/bot/events/ButtonInteractionCooldown.class"
            if bic in names:
                txt2 = javap(jar, bic)
                check(f"C4 {label} 按钮冷却回归（tryAcquire/clearAll）",
                      "tryAcquire" in txt2 and "clearAll" in txt2)
            ifp = "cn/kerong/bot/events/InteractionFastPath.class"
            if ifp in names:
                check(f"C5 {label} FastPath 冷却门禁回归",
                      "ButtonInteractionCooldown.tryAcquire" in javap(jar, ifp))
            mbc = "cn/kerong/bot/events/MenuButtons.class"
            if mbc in names:
                txt3 = javap(jar, mbc)
                check(f"C6 {label} sendPage 三参签名回归",
                      re.search(r"sendPage\(java\.lang\.String, java\.lang\.String, int\)", txt3) is not None)
                check(f"C7 {label} 验身标记 krg_btn_admin 回归", b"krg_btn_admin" in zf.read(mbc))
            cc = "cn/kerong/bot/tools/ConsoleColors.class"
            if cc in names:
                b = zf.read(cc)
                check(f"C8 {label} ConsoleColors 回归",
                      b"TerminalConsoleAppender" in b and b"isAnsiSupported" in b)
            qrm = "cn/kerong/bot/QrLoginManager.class"
            if qrm in names:
                check(f"C9 {label} 手动 qr 全新码文案回归",
                      "已取消此前的扫码会话".encode() in zf.read(qrm))
            if ai_at in names:
                check(f"C10 {label} AiAt [at: 标记回归", b"[at:" in zf.read(ai_at))
            qr = "cn/kerong/bot/tools/QrCode.class"
            if qr in names:
                b = zf.read(qr)
                check(f"C11 {label} encodeCjk/encodePlain 回归", b"encodeCjk" in b and b"encodePlain" in b)
            dirs = sum(1 for n in names if n.endswith("/"))
            check(f"C12 {label} fat JAR 目录条目 > 100", dirs > 100, f"dirs={dirs}")

        # ---------- C(Fabric 端字节码细节) ----------
        if label in ("Fabric", "Fabric1201"):
            txt = javap(jar, "cn/kerong/penguin/fabric/FabricConsoleExecutor.class")
            check(f"C13 {label} ConsoleExecutor brigadier 直发回归",
                  "com/mojang/brigadier/exceptions/CommandSyntaxException" in txt and
                  re.search(r"CommandDispatcher.*\.execute:\(Ljava/lang/String;Ljava/lang/Object;\)I", txt) is not None)
        if label == "Fabric1201":
            txt = javap(jar, "org/bukkit/entity/FabricPlayer.class")
            check("C14 Fa1201 disconnect→method_14367", "class_3244.method_14367" in txt)
        if label == "Fabric":
            txt = javap(jar, "org/bukkit/entity/FabricPlayer.class")
            check("C15 Fa1204 disconnect→method_52396", "class_3244.method_52396" in txt)
        if label in ("Fabric", "Fabric1201"):
            unmapped = scan_mc_refs(jar)
            whitelist = {
                "net/minecraft/class_124.ordinal ()I",
                "net/minecraft/class_124.values ()[Lnet/minecraft/class_124;",
                "net/minecraft/class_2561.getString ()Ljava/lang/String;",
                "net/minecraft/server/MinecraftServer.execute (Ljava/lang/Runnable;)V",
            }
            bad = [r for r in unmapped if r not in whitelist]
            check(f"C16 {label} 未映射引用仅白名单 4 条",
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
