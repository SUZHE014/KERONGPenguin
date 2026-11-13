#!/usr/bin/env python3
"""verify_jar_1755：1.7.5.5 门禁（Fa 阶段：二维码编码自适应 + Fabric 元数据上限 + 回归）。

三 JAR（Spigot / NeoForge / Fabric）字节码级断言：
- A 产物与版本（1.7.5.5 三端 + fabric.mod.json minecraft 上限 <1.22）
- B 二维码回退渲染（QrCode.encodeCjk/encodePlain 方法在位 + 0x25A0/0x3000 常量
  + "##"/空格对字面量 + QrLoginManager renderQrForConsole 分级选择 + PrintStream.charset 探测）
- C 1.7.5.4 主线回归（按钮验身标记 krg_btn_admin / MANUAL 分支 / GLOBAL_MEMBER /
  排版改版 / config-version 45）
- D 分发形态（目录条目数 fat-JAR 熔断线）
"""
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
SPIGOT = REPO / "build" / "dist" / "KERONGPenguin_Spigot-1.7.5.5.jar"
NEOFORGE = REPO / "build" / "dist" / "KERONGPenguin_NeoForge-1.20.4-1.7.5.5.jar"
FABRIC = REPO / "build" / "dist" / "KERONGPenguin_Fabric-1.20.4-1.7.5.5.jar"

JARS = [("Spigot", SPIGOT), ("NeoForge", NEOFORGE), ("Fabric", FABRIC)]

checks = []


def check(name, ok, detail=""):
    checks.append((name, ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if not ok and detail else ""))


def class_bytes(jar, name):
    with zipfile.ZipFile(jar) as z:
        return z.read(name)


def has_str(jar, cls, s):
    try:
        return s.encode() in class_bytes(jar, f"{cls}.class")
    except KeyError:
        return False


def res_bytes(jar, name):
    with zipfile.ZipFile(jar) as z:
        return z.read(name)


def entry_exists(jar, name):
    with zipfile.ZipFile(jar) as z:
        return name in z.namelist()


CS = "cn/kerong/bot/events/commands/CommandSupport"
MB = "cn/kerong/bot/events/MenuButtons"
PC = "cn/kerong/bot/events/commands/PublicCommands"
SSB = "cn/kerong/bot/events/ServerSelectButtons"
QR = "cn/kerong/bot/tools/QrCode"
QLM = "cn/kerong/bot/QrLoginManager"


def main():
    print("== A. 产物与版本 ==")
    check("A1 三 JAR 存在", all(j.is_file() for _, j in JARS),
          "; ".join(str(j) for _, j in JARS if not j.is_file()))
    if not SPIGOT.is_file():
        return finish()
    plugin_yml = res_bytes(SPIGOT, "plugin.yml").decode()
    check("A2 Spigot plugin.yml 版本 1.7.5.5", "version: '1.7.5.5'" in plugin_yml)
    mods_toml = res_bytes(NEOFORGE, "META-INF/mods.toml").decode()
    nf_toml = res_bytes(NEOFORGE, "META-INF/neoforge.mods.toml").decode()
    check("A3 NeoForge mods.toml 双声明版本 1.7.5.5",
          'version = "1.7.5.5"' in mods_toml and 'version = "1.7.5.5"' in nf_toml)
    check("A4 NeoForge MOD_VERSION 1.7.5.5",
          has_str(NEOFORGE, "cn/kerong/penguin/neoforge/KERONGNeoForge", "1.7.5.5"))
    fabric_mod = res_bytes(FABRIC, "fabric.mod.json").decode()
    check("A5 Fabric fabric.mod.json 版本 1.7.5.5", '"version": "1.7.5.5"' in fabric_mod)
    check("A6 Fabric minecraft 上限（>=1.20.4 <1.22）",
          '"minecraft": ">=1.20.4 <1.22"' in fabric_mod)
    check("A7 Fabric fabricloader 声明保持 >=0.15.0",
          '"fabricloader": ">=0.15.0"' in fabric_mod)
    check("A8 无旧版本号残留（1.7.5.4 不再出现在三端元数据）",
          "1.7.5.4" not in plugin_yml and "1.7.5.4" not in mods_toml
          and "1.7.5.4" not in fabric_mod)

    print("== B. 二维码编码自适应（encodeCjk / encodePlain / 分级探测） ==")
    for tag, jar in JARS:
        check(f"B1 {tag} QrCode.encodeCjk 方法在位", has_method(jar, QR, "encodeCjk"))
        check(f"B2 {tag} QrCode.encodePlain 方法在位", has_method(jar, QR, "encodePlain"))
        check(f"B3 {tag} QrCode 半块渲染保持（encodeAscii）", has_method(jar, QR, "encodeAscii"))
        check(f"B4 {tag} QrCode 全角常量 ■(0x25A0)/U+3000（sipush 字节码）",
              has_sipush(jar, QR, 0x25A0) and has_sipush(jar, QR, 0x3000))
        check(f"B5 {tag} QrCode 纯 ASCII 字面量 ##（encodePlain）",
              has_str(jar, QR, "##"))
        check(f"B6 {tag} QrLoginManager renderQrForConsole 在位",
              has_method(jar, QLM, "renderQrForConsole"))
        check(f"B7 {tag} PrintStream.charset 探测（字节码引用）",
              b"java/io/PrintStream" in class_bytes(jar, f"{QLM}.class")
              and b"charset" in class_bytes(jar, f"{QLM}.class")
              and b"newEncoder" in class_bytes(jar, f"{QLM}.class"))
        check(f"B8 {tag} 半块/全角探测常量（sipush 0x2580/0x2584/0x2588/0x25A0/0x3000）",
              has_sipush(jar, QLM, 0x2580) and has_sipush(jar, QLM, 0x2584)
              and has_sipush(jar, QLM, 0x2588) and has_sipush(jar, QLM, 0x25A0)
              and has_sipush(jar, QLM, 0x3000))

    print("== C. 1.7.5.4 主线回归 ==")
    for tag, jar in JARS:
        check(f"C1 {tag} CommandSupport 常量 krg_btn_admin", has_str(jar, CS, "krg_btn_admin"))
        check(f"C2 {tag} MenuButtons isManualAdminCommand 分支",
              has_str(jar, MB, "isManualAdminCommand"))
        check(f"C3 {tag} ServerSelectButtons GLOBAL_MEMBER=全局",
              has_str(jar, SSB, "全局"))
        check(f"C4 {tag} 排版改版 SHORT_LABEL_EXCEPTIONS（AI管理特判）",
              has_str(jar, MB, "AI管理"))
    for tag, jar in [("Spigot", SPIGOT), ("NeoForge", NEOFORGE)]:
        check(f"C5 {tag} config.yml config-version 45",
              b"config-version: 45" in res_bytes(jar, "config.yml"))
    check("C5f Fabric config.yml config-version 45",
          b"config-version: 45" in res_bytes(FABRIC, "config.yml"))

    print("== D. 分发形态 ==")
    # 目录条目基线（与 1.7.5.4 分发版实测一致：Spigot 776 / NF 661 / Fabric 680）
    for tag, jar in JARS:
        with zipfile.ZipFile(jar) as z:
            names = z.namelist()
            dirs = sum(1 for n in names if n.endswith("/"))
            floor = 700 if tag == "Spigot" else 600
            check(f"D1 {tag} 目录条目数 ≥ {floor}（fat-JAR 形态，实际 {dirs}）", dirs >= floor)
            check(f"D2 {tag} QrCode/QrLoginManager 类条目在位",
                  f"{QR}.class" in names and f"{QLM}.class" in names)
    finish()


def has_method(jar, cls, name):
    """类文件常量池/方法名区包含方法名（UTF-8 字节）。"""
    return has_str(jar, cls, name)


def has_sipush(jar, cls, value):
    """字节码 sipush 常量（0x25A0/0x3000 超出 bipush 范围编译为 sipush）。"""
    data = class_bytes(jar, f"{cls}.class")
    b1, b2 = (value >> 8) & 0xFF, value & 0xFF
    return bytes([0x11, b1, b2]) in data


def finish():
    total = len(checks)
    bad = [c for c in checks if not c[1]]
    print(f"\n共 {total} 项，通过 {total - len(bad)}，失败 {len(bad)}")
    if bad:
        print("失败项：")
        for name, _ in bad:
            print("  -", name)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
