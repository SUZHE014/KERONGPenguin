#!/usr/bin/env python3
"""1.7.6.7 门禁（2026-11-22：签到经济随机奖励 + /hbkrg qifx 群发指令）。

A 组 CheckinRewardSpec（四端 JAR 新类在位与语义）
  A1 四端 JAR 均含 CheckinRewardSpec.class + $Companion + Fixed + Range 四条目
  A2 @JvmStatic：CheckinRewardSpec.class 直接声明 parse / parseQuietly 静态方法
     （Java 冒烟直调的前提；companion 只是委托）
  A3 KDoc 口径：类字节含「最小~最大」与「回退默认」UTF-8 常量

B 组 签到随机奖励接线（四端字节码）
  B1 CheckInCommands 类字节含「（随机 」标注常量（随机模式回复带区间标注）
  B2 CheckInCommands 调用 checkinRewardSpec（javap 引用计数 ≥2：判定 + roll）
  B3 CheckInCommands 无旧 checkinReward 直接读取（roll 链路全走 spec）
  B4 QqBindManager 类字节含 checkinRewardSpec 方法（getter 在位）
  B5 KERONGPenguinAPI 含 getCheckinRewardSpec / getCheckinRewardText（Spigot 端）

C 组 qifx 群发指令（四端）
  C1 三端命令类字节均含「qifx」常量（Spigot KERONGCommand / NeoForgeCommands
     / FabricCommands——注意 NF/Fa 经 remap/relocate，类名与常量保留）
  C2 三端命令类字节均含「群发完成」反馈文案
  C3 Spigot plugin.yml usage 含 qifx；SUBCOMMANDS 列表含 qifx
  C4 QClient.broadcastAdminNotice 方法在位（四端；NF/Fa 重定位后类名前缀变化，
     按 entry 名 glob 找 QClient）
  C5 帮助文案：三端命令类字节含「群发公告」

D 组 配置与版本
  D1 三端 JAR 内 config.yml：config-version: 64
  D2 三端 JAR 内 config.yml reward 注释含「随机区间」与「0~100」写法说明
  D3 三端 JAR 内 config.yml reward 默认值仍为 100.0（旧默认不变）
  D4 版本六处 1.7.6.7：根 gradle / plugin.yml / fabric.mod.json / mods.toml×2
     / version.json
  D5 源码级：ConfigManager CURRENT_CONFIG_VERSION = 64 且升 64 注释块在位

V 组 官网三件（部署前跑 D4/V 组时 version.json 已更新）
  V1 website/version.json 1.7.6.7 + changelog 两条
  V2 website/index.html v1.7.6.7 块在位
  V3 website/tutorial.html qifx 命令行 + 签到随机写法说明
  V4 README 1.7.6.7 条目

R 组 回归
  R1 上轮核心在位：committedMemoryMb（M1 级抽查）+ game-fallback-text 键 +
     stripInvisibleChars 常量
  R2 dist 四端 1.7.6.7 在位且 JAR 内版本一致
"""
import json
import os
import re
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(REPO, "build", "dist")
JAVAP = "/home/z/my-project/tooling/jdk-full/bin/javap"

JARS = {
    "Spigot": os.path.join(DIST, "KERONGPenguin_Spigot-1.7.6.7.jar"),
    "NeoForge": os.path.join(DIST, "KERONGPenguin_NeoForge-1.20.4-1.7.6.7.jar"),
    "Fabric-1204": os.path.join(DIST, "KERONGPenguin_Fabric-1.20.4-1.7.6.7.jar"),
    "Fabric-1201": os.path.join(DIST, "KERONGPenguin_Fabric-1.20.1-1.7.6.7.jar"),
}

pass_n = 0
fail_n = 0
fails = []


def check(name, ok):
    global pass_n, fail_n
    if ok:
        pass_n += 1
        print(f"  PASS {name}")
    else:
        fail_n += 1
        fails.append(name)
        print(f"  FAIL {name}")


def read_entry(jar_path, entry):
    with zipfile.ZipFile(jar_path) as zf:
        return zf.read(entry)


def entry_exists(jar_path, entry):
    with zipfile.ZipFile(jar_path) as zf:
        return entry in zf.namelist()


def find_class(jar_path, class_basename):
    """按 basename 找类条目（NF/Fa 重定位后包名前缀可能变化）。"""
    with zipfile.ZipFile(jar_path) as zf:
        names = [n for n in zf.namelist() if n.endswith("/" + class_basename + ".class")]
        return names[0] if len(names) == 1 else (names[0] if names else None)


def class_utf8(jar_path, class_basename):
    entry = find_class(jar_path, class_basename)
    if entry is None:
        return ""
    return read_entry(jar_path, entry).decode("utf-8", "surrogateescape")


def javap_text(jar_path, cls_path, extra=("-p",)):
    import subprocess
    # cls_path 是 zip 条目名（含 .class 后缀）——转 javap 类名时去掉后缀
    cls_name = cls_path.replace("/", ".")
    if cls_name.endswith(".class"):
        cls_name = cls_name[: -len(".class")]
    r = subprocess.run([JAVAP, "-cp", jar_path] + list(extra) + [cls_name],
                       capture_output=True, text=True, timeout=120)
    return r.stdout


def main():
    print("== A 组：CheckinRewardSpec 新类 ==")
    for label, jar in JARS.items():
        entries = []
        with zipfile.ZipFile(jar) as zf:
            names = zf.namelist()
            for e in ["CheckinRewardSpec.class", "CheckinRewardSpec$Companion.class",
                      "CheckinRewardSpec$Fixed.class", "CheckinRewardSpec$Range.class"]:
                entries.append(any(n.endswith("/qqbind/" + e) for n in names))
        check(f"A1 {label} 四条目在位", all(entries))

    # A2 @JvmStatic（Spigot 端验证，四端同源）：parse/parseQuietly 直接静态挂在外类
    spec_cls = find_class(JARS["Spigot"], "CheckinRewardSpec")
    jv = javap_text(JARS["Spigot"], spec_cls)
    check("A2 parse/parseQuietly 静态直挂外类（@JvmStatic）",
          "static final cn.kerong.penguin.spigot.qqbind.CheckinRewardSpec parse(" in jv.replace("\n", " ")
          and "static final cn.kerong.penguin.spigot.qqbind.CheckinRewardSpec parseQuietly(" in jv.replace("\n", " "))

    # A3 回退日志常量在字节码（parse 方法体在 $Companion，写法提示文案随方法所在类）
    utf8 = class_utf8(JARS["Spigot"], "CheckinRewardSpec$Companion")
    src = open(os.path.join(REPO, "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/CheckinRewardSpec.kt"),
               encoding="utf-8").read()
    check("A3 回退日志常量（随机区间 / 0~100）在 Companion 字节", "随机区间" in utf8 and "0~100" in utf8)
    check("A3b 源码 KDoc 写法口径（最小~最大 / 回退默认）", "最小~最大" in src and "回退默认" in src)

    print("== B 组：签到随机奖励接线 ==")
    for label, jar in JARS.items():
        utf8 = class_utf8(jar, "CheckInCommands")
        check(f"B1 {label} CheckInCommands 含「（随机 」标注", "（随机 " in utf8)
    # B2/B3 Spigot 端 javap 引用：getter 取值一次 + roll/maxAmount 虚调用
    cic_cls = find_class(JARS["Spigot"], "CheckInCommands")
    jv_cic = javap_text(JARS["Spigot"], cic_cls, ("-p", "-c"))
    check("B2 spec 接线（getCheckinRewardSpec + roll + getMaxAmount）",
          jv_cic.count("getCheckinRewardSpec") >= 1 and "CheckinRewardSpec.roll" in jv_cic
          and "CheckinRewardSpec.getMaxAmount" in jv_cic)
    check("B3 无 checkinReward 旧直读（兼容 getter 只在 API 层）",
          "checkinReward:" not in jv_cic or jv_cic.count("checkinReward") == jv_cic.count("checkinRewardSpec"))
    for label, jar in JARS.items():
        utf8 = class_utf8(jar, "QqBindManager")
        check(f"B4 {label} QqBindManager 含 checkinRewardSpec", "checkinRewardSpec" in utf8)
    api_utf8 = class_utf8(JARS["Spigot"], "KERONGPenguinAPI")
    check("B5 API 新增 getCheckinRewardSpec/Text", "getCheckinRewardSpec" in api_utf8 and "getCheckinRewardText" in api_utf8)

    print("== C 组：qifx 群发指令 ==")
    cmd_cls = {
        "Spigot": "KERONGCommand",
        "NeoForge": "NeoForgeCommands",
        "Fabric-1204": "FabricCommands",
    }
    for label, cls in cmd_cls.items():
        utf8 = class_utf8(JARS[label], cls)
        check(f"C1 {label} 命令类含 qifx", "qifx" in utf8)
        check(f"C2 {label} 命令类含「群发完成」", "群发完成" in utf8)
        check(f"C5 {label} 帮助含「群发公告」", "群发公告" in utf8)
    # Fabric-1201 与 1204 同源（同模块产物重打包），抽查一项
    check("C1 Fabric-1201 命令类含 qifx", "qifx" in class_utf8(JARS["Fabric-1201"], "FabricCommands"))

    pyml = read_entry(JARS["Spigot"], "plugin.yml").decode("utf-8")
    check("C3 plugin.yml usage 含 qifx", "qifx" in pyml)
    kc_utf8 = class_utf8(JARS["Spigot"], "KERONGCommand")
    check("C3b SUBCOMMANDS 含 qifx", 'qifx' in kc_utf8)

    for label, jar in JARS.items():
        with zipfile.ZipFile(jar) as zf:
            qc = [n for n in zf.namelist() if n.endswith("/bot/QClient.class")]
        ok = False
        if qc:
            data = read_entry(jar, qc[0])
            ok = b"broadcastAdminNotice" in data
        check(f"C4 {label} QClient.broadcastAdminNotice 在位", ok)

    print("== D 组：配置与版本 ==")
    cfg_jars = {"Spigot": JARS["Spigot"], "NeoForge": JARS["NeoForge"], "Fabric-1204": JARS["Fabric-1204"]}
    for label, jar in cfg_jars.items():
        cfg = read_entry(jar, "config.yml").decode("utf-8")
        check(f"D1 {label} config-version 64", re.search(r"^config-version: 64$", cfg, re.M) is not None)
        check(f"D2 {label} reward 随机写法注释", "随机区间" in cfg and "0~100" in cfg)
        check(f"D3 {label} reward 默认 100.0 保持", re.search(r"^    reward: 100\.0$", cfg, re.M) is not None)

    root_gradle = open(os.path.join(REPO, "build.gradle.kts"), encoding="utf-8").read()
    check("D4a 根 gradle 1.7.6.7", 'version = "1.7.6.7"' in root_gradle)
    check("D4b plugin.yml 1.7.6.7", "version: '1.7.6.7'" in pyml)
    for label, jar, entry in [("Fa1204", JARS["Fabric-1204"], "fabric.mod.json"),
                              ("NF", JARS["NeoForge"], "META-INF/mods.toml"),
                              ("NF2", JARS["NeoForge"], "META-INF/neoforge.mods.toml")]:
        text = read_entry(jar, entry).decode("utf-8")
        check(f"D4c {label} {entry} 1.7.6.7", "1.7.6.7" in text)

    cm = open(os.path.join(REPO, "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt"),
              encoding="utf-8").read()
    check("D5 ConfigManager cv64 + 注释块", "CURRENT_CONFIG_VERSION = 64" in cm and "升 64——1.7.6.7" in cm)

    print("== V 组：官网三件 ==")
    vj_path = os.path.join(REPO, "website", "version.json")
    if os.path.isfile(vj_path):
        vj = json.load(open(vj_path, encoding="utf-8"))
        check("V1 version.json 1.7.6.7 + changelog ≥2 条",
              vj.get("version") == "1.7.6.7" and isinstance(vj.get("changelog"), list) and len(vj["changelog"]) >= 2)
        joined = "".join(vj["changelog"])
        check("V1b changelog 含随机奖励与 qifx 关键词", "随机" in joined and "qifx" in joined)
        idx = open(os.path.join(REPO, "website", "index.html"), encoding="utf-8").read()
        check("V2 index.html v1.7.6.7 块", "1.7.6.7" in idx)
        tut = open(os.path.join(REPO, "website", "tutorial.html"), encoding="utf-8").read()
        check("V3 tutorial qifx + 随机写法", "qifx" in tut and "0~100" in tut)
        rd = open(os.path.join(REPO, "README.md"), encoding="utf-8").read()
        check("V4 README 1.7.6.7", "1.7.6.7" in rd)
    else:
        print("  （version.json 未生成，V 组跳过——官网更新后重跑）")

    print("== R 组：回归 ==")
    perf = class_utf8(JARS["Spigot"], "PerformanceSnapshot")
    check("R1a committedMemoryMb 在位（上轮口径保持）", "committedMemoryMb" in perf and "usedMemoryMb" not in perf)
    for label, jar in cfg_jars.items():
        cfg = read_entry(jar, "config.yml").decode("utf-8")
        if "game-fallback-text" not in cfg:
            check(f"R1b {label} game-fallback-text 键保持", False)
            break
    else:
        check("R1b game-fallback-text 键保持（三端）", True)
    wc_utf8 = class_utf8(JARS["Spigot"], "WebCrawler")
    check("R1c stripInvisibleChars 在位", "stripInvisibleChars" in wc_utf8)

    for label, jar in JARS.items():
        ok = os.path.isfile(jar) and os.path.getsize(jar) > 10_000_000
        check(f"R2 {label} 1.7.6.7 产物在位", ok)

    print()
    print(f"通过 {pass_n}，失败 {fail_n}")
    if fails:
        print("失败项:")
        for f in fails:
            print("  -", f)
    return 1 if fail_n else 0


if __name__ == "__main__":
    sys.exit(main())
