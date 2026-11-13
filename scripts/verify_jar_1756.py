#!/usr/bin/env python3
"""1.7.5.6 Fa 修复轮门禁:四分发 JAR 字节码核查。

A. 版本与资源(plugin.yml / mods.toml 双文件 / fabric.mod.json ×2 / config.yml)
B. 本轮改动(QrLoginManager 手动新会话 / FabricConsoleExecutor brigadier 直发)
C. 1.20.1 remap 正确性(disconnect→method_14367 / getDispatcher→method_9235 /
   performPrefixedCommand 移除 / 未映射引用白名单)
D. 回归(1.7.5.4 验身标记 + 1.7.5.5 AiAt/encodeCjk/ai 两键)
E. 形态(fat JAR 目录条目 / NF 重定位 / Fa 原包名)

用法:python3 scripts/verify_jar_1756.py
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
    """从 jar 内提取类并 javap,返回文本。"""
    import tempfile
    with zipfile.ZipFile(jar) as zf:
        data = zf.read(inner)
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "c.class"
        p.write_bytes(data)
        out = subprocess.run([JAVAP, *extra, str(p)], capture_output=True, text=True, timeout=120)
        return out.stdout


def scan_mc_refs(jar):
    """扫描 mod 自有类的全部 net/minecraft 引用,返回未映射(mojmap 名保留)清单。"""
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
            check(f"A4 {label} config.yml ai.at-enabled 默认开", re.search(r"at-enabled:\s*true", cfg) is not None)
            check(f"A5 {label} config.yml ai.reply-quote 默认开", re.search(r"reply-quote:\s*true", cfg) is not None)
            check(f"A6 {label} config.yml config-version 46", re.search(r"config-version:\s*46", cfg) is not None)

            # ---------- B. 本轮改动 ----------
            qrm = "cn/kerong/bot/QrLoginManager.class"
            if qrm in names:
                b = zf.read(qrm)
                check(f"B1 {label} QrLoginManager 含手动取消旧会话文案", "已取消此前的扫码会话".encode() in b)
                check(f"B2 {label} QrLoginManager 含全新二维码文案", "重新发起新的二维码".encode() in b)
            else:
                check(f"B1 {label} QrLoginManager 类存在", False)

            # ---------- D. 回归(全端) ----------
            mb = "cn/kerong/bot/menu/MenuButtons.class"
            if mb in names:
                check(f"D1 {label} 验身标记 krg_btn_admin 回归", b"krg_btn_admin" in zf.read(mb))
            ai_at = "cn/kerong/penguin/spigot/qqbind/AiAt.class"
            if ai_at in names:
                b = zf.read(ai_at)
                check(f"D2 {label} AiAt 类与 [at: 标记回归", b"[at:" in b)
            else:
                check(f"D2 {label} AiAt 类存在", ai_at in names)
            qr = "cn/kerong/bot/tools/QrCode.class"
            if qr in names:
                b = zf.read(qr)
                check(f"D3 {label} encodeCjk/encodePlain 回归", b"encodeCjk" in b and b"encodePlain" in b)
            dirs = sum(1 for n in names if n.endswith("/"))
            check(f"D4 {label} fat JAR 目录条目 > 100", dirs > 100, f"dirs={dirs}")

        # ---------- B/C. Fabric 端字节码细节 ----------
        if label in ("Fabric", "Fabric1201"):
            txt = javap(jar, "cn/kerong/penguin/fabric/FabricConsoleExecutor.class")
            check(f"B3 {label} ConsoleExecutor 引用 CommandSyntaxException",
                  "com/mojang/brigadier/exceptions/CommandSyntaxException" in txt)
            check(f"B4 {label} ConsoleExecutor brigadier execute 调用",
                  re.search(r"CommandDispatcher.*\.execute:\(Ljava/lang/String;Ljava/lang/Object;\)I", txt) is not None)
            check(f"B5 {label} ConsoleExecutor getDispatcher→method_9235",
                  "class_2170.method_9235" in txt)
            check(f"B6 {label} ConsoleExecutor 无 performPrefixedCommand 残留",
                  "performPrefixedCommand" not in txt)

        if label == "Fabric1201":
            txt = javap(jar, "org/bukkit/entity/FabricPlayer.class")
            check("C1 Fa1201 FabricPlayer 踢人 disconnect→method_14367",
                  "class_3244.method_14367" in txt)
        if label == "Fabric":
            txt = javap(jar, "org/bukkit/entity/FabricPlayer.class")
            check("C2 Fa1204 FabricPlayer 踢人 disconnect→method_52396",
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
            check(f"C3 {label} 未映射引用仅白名单 4 条",
                  not bad and len(unmapped) <= 4, "; ".join(unmapped[:8]))

        # ---------- E. 形态 ----------
        if label == "NeoForge":
            with zipfile.ZipFile(jar) as zf:
                names = zf.namelist()
                check("E1 NF kotlin 重定位(cn/kerong/shaded/kotlin)",
                      any(n.startswith("cn/kerong/shaded/kotlin/") for n in names))
                check("E2 NF 无裸 fastjson", not any(n.startswith("org/springframework/framework/boot/web/") for n in names))
        if label in ("Fabric", "Fabric1201"):
            with zipfile.ZipFile(jar) as zf:
                names = zf.namelist()
                check(f"E3 {label} kotlin 原包名(不重定位)", any(n.startswith("kotlin/") for n in names))

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
