#!/usr/bin/env python3
"""1.7.4.1 第二轮覆盖版门禁验证。

本轮三项（用户口径：播报文案去表情 + 180 秒重启判定可配置 + 重启文案）：
① 默认播报文案：shutdown 去 (╯︵╰,) → [状态]\n服务器已正常关闭；
   crash 去 ༽◺_◿༼ → [状态]\n服务器....
② 180 秒重启判定窗口（server-notify.restart-window 默认 180，可配置，
   0 = 不判定重启）：上次正常关闭后窗口内再开机判定为「重启」发
   [状态]\n服务器重启完毕，已正常运行(o‿∩)；超窗按普通开机发
   [状态]\n服务器已开启！！(≧∇≦)/（新增 server-notify.start 段）
③ 修复双端主类未接线 server-notify getter 的遗留问题（配置真正生效）

验证范围（源码 + 双端 JAR 资源 / 字节码）：
A. 新文案五处同步：ConfigProvider / ConfigManager（getter + DEFAULT_VALUES）/
   ConfigFileRebuilder 收敛源 / 双端 config.yml 模板
B. JAR 资源：双端 config.yml PyYAML 逐字解析（四条新文案 + restart-window 180 +
   start 段 + config-version 40）
C. 窗口逻辑：ServerNotifyManager 源码窗口分支 + JAR 字节码
   （serverNotifyRestartWindow 调用 + 重启日志字符串）
D. 接线修复：双端主类源码 override 委托 + JAR 字节码方法在位
E. 收敛与废弃键：全部历史默认（初始/第六轮/误写/第七轮）→ 新默认；
   restart-window 自废弃清单移除；JAR 内模板 restart-window 键在位
F. 版本号 1.7.4.1（源码五处 + JAR 三处）；第1轮 @修复回归（源码+字节码）
G. 基线保持：Spigot 11869 条目 / 776 目录、二维码延时 30s / 上限 3、
   副标题灰度 106、README 与官网四件新口径
"""
import subprocess
import sys
import zipfile
from pathlib import Path

import yaml

REPO = Path("/home/z/my-project/penguin-git")
DIST = REPO / "build" / "dist"
SPIGOT_JAR = DIST / "KERONGPenguin_Spigot-1.7.4.1.jar"
NF_JAR = DIST / "KERONGPenguin_NeoForge-1.20.4-1.7.4.1.jar"
JAVAP = "/home/z/my-project/tooling/jdk21/bin/javap"

passed = []
failed = []

# 本轮新默认文案（YAML 解析后为真实换行；用于 B 段 PyYAML 逐字比对）
N_SHUTDOWN = "[状态]\n服务器已正常关闭"
N_RESTART = "[状态]\n服务器重启完毕，已正常运行(o‿∩)"
N_START = "[状态]\n服务器已开启！！(≧∇≦)/"
N_CRASH = "[状态]\n服务器...."
# 源码级同款（Kotlin 源文件里是字面 \n 两字符；用于 A 段源码比对）
S_SHUTDOWN = N_SHUTDOWN.replace("\n", "\\n")
S_RESTART = N_RESTART.replace("\n", "\\n")
S_START = N_START.replace("\n", "\\n")
S_CRASH = N_CRASH.replace("\n", "\\n")
# 全部历史默认文案（收敛源，源码级字面 \n）
HIST_SHUTDOWN = ["服务器已正常关闭", "[状态]\\n服务器已正常关闭 (╯︵╰,)"]
HIST_RESTART = ["服务器已重启完成", "[状态]\\n服务器已开启！！(≧∇≦)/"]
HIST_CRASH = [
    "服务器意外关闭（崩溃或被强制结束），请管理员检查控制台",
    "[状态]\\n服务器....༽◺_轨迹༼",
    "[状态]\\n服务器....༽◺_◿༼",
]

def check(name, ok, detail=""):
    (passed if ok else failed).append(name)
    print(f"  {'✓' if ok else '✗'} {name}" + (f" — {detail}" if detail and not ok else ""))

def src(rel):
    return (REPO / rel).read_text(encoding="utf-8")

def jar_read(jar, name):
    with zipfile.ZipFile(jar) as zf:
        return zf.read(name).decode("utf-8", errors="replace")

def jar_names(jar):
    with zipfile.ZipFile(jar) as zf:
        return zf.namelist()

def class_bytes_has(jar, cls, needle):
    with zipfile.ZipFile(jar) as zf:
        return needle.encode("utf-8") in zf.read(cls)

def javap_methods(jar, cls):
    out = subprocess.run([JAVAP, "-p", "-cp", str(jar), cls],
                         capture_output=True, text=True, check=True).stdout
    return out

def javap_code(jar, cls):
    return subprocess.run([JAVAP, "-p", "-c", "-cp", str(jar), cls],
                          capture_output=True, text=True, check=True).stdout

print("== A. 新文案五处同步（源码） ==")
cp = src("common-Bot/src/main/kotlin/cn/kerong/bot/provider/ConfigProvider.kt")
check("A1 ConfigProvider shutdown 新默认", f'fun serverNotifyShutdownMessage(): String = "{S_SHUTDOWN}"' in cp)
check("A2 ConfigProvider restart 重启完毕新默认", S_RESTART in cp)
check("A3 ConfigProvider start 新方法+默认", "fun serverNotifyStartMessage(): String = " in cp and S_START in cp)
check("A4 ConfigProvider restartWindow 默认 180", "fun serverNotifyRestartWindow(): Int = 180" in cp)

cm = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
check("A5 ConfigManager shutdown getter 新默认", f'getString("server-notify.shutdown.message", "{S_SHUTDOWN}")' in cm)
check("A6 ConfigManager restart getter 重启完毕", S_RESTART in cm)
check("A7 ConfigManager start getter 在位", 'getString("server-notify.start.message"' in cm)
check("A8 ConfigManager restartWindow getter 180", 'getInt("server-notify.restart-window", 180)' in cm and "coerceAtLeast(0)" in cm)
check("A9 DEFAULT_VALUES shutdown/restart/crash 新默认", f'"server-notify.shutdown.message" to "{S_SHUTDOWN}"' in cm and f'"server-notify.restart.message" to "{S_RESTART}"' in cm and f'"server-notify.crash.message" to "{S_CRASH}"' in cm)
check("A10 DEFAULT_VALUES start 两键 + window 180", '"server-notify.start.enabled" to true' in cm and f'"server-notify.start.message" to "{S_START}"' in cm and '"server-notify.restart-window" to 180' in cm)
check("A11 config-version 40", "CURRENT_CONFIG_VERSION = 40" in cm)

rb = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.kt")
check("A12 Rebuilder 收敛：全部历史 shutdown 默认", all(h in rb for h in HIST_SHUTDOWN) and f'disk.set("server-notify.shutdown.message", "{S_SHUTDOWN}")' in rb)
check("A13 Rebuilder 收敛：全部历史 restart 默认", all(h in rb for h in HIST_RESTART) and S_RESTART in rb)
check("A14 Rebuilder 收敛：全部历史 crash 默认（含误写版与第七轮版）", all(h in rb for h in HIST_CRASH) and f'disk.set("server-notify.crash.message", "{S_CRASH}")' in rb)

cm_conv_ok = all(h in cm for h in HIST_SHUTDOWN + HIST_RESTART + HIST_CRASH)
check("A15 ConfigManager reload 收敛源同款（重建失败兑底）", cm_conv_ok and "oldVersion in 1 until 40" in cm)

for rel in ["server-Spigot/src/main/resources/config.yml", "server-NeoForge/src/main/resources/config.yml"]:
    t = src(rel)
    seg = t[t.index("server-notify:"):t.index("server-notify:")+1200]
    check(f"A16 模板 {rel.split('/')[0]} 四段+窗口", all(k in seg for k in [
        'restart-window: 180', f'message: "{S_SHUTDOWN}"',
        f'message: "{S_RESTART}"',
        f'message: "{S_START}"', f'message: "{S_CRASH}"', "start:", "restart:", "crash:", "shutdown:"]))
    check(f"A17 模板 {rel.split('/')[0]} 无旧表情残留", "(╯︵╰" not in seg and "༽◺" not in seg)
    check(f"A18 模板 {rel.split('/')[0]} config-version 40（重建器核对用）", "config-version: 40" in t)

print("== B. JAR 资源：双端 config.yml PyYAML 逐字解析 ==")
for tag, jar in [("Spigot", SPIGOT_JAR), ("NeoForge", NF_JAR)]:
    cfg = yaml.safe_load(jar_read(jar, "config.yml"))
    sn = cfg["server-notify"]
    check(f"B1 {tag} config-version 40", cfg["config-version"] == 40)
    check(f"B2 {tag} shutdown.message 逐字", sn["shutdown"]["message"] == N_SHUTDOWN)
    check(f"B3 {tag} restart.message 逐字", sn["restart"]["message"] == N_RESTART)
    check(f"B4 {tag} start.message 逐字", sn["start"]["message"] == N_START)
    check(f"B5 {tag} crash.message 逐字", sn["crash"]["message"] == N_CRASH)
    check(f"B6 {tag} restart-window 180", sn["restart-window"] == 180)
    check(f"B7 {tag} 四段开关全 true", all(sn[k]["enabled"] is True for k in ["shutdown", "restart", "start", "crash"]) and sn["enabled"] is True)
    check(f"B8 {tag} 全文无旧表情", "(╯︵╰" not in jar_read(jar, "config.yml") and "༽◺" not in jar_read(jar, "config.yml"))

print("== C. 窗口逻辑：ServerNotifyManager ==")
snm = src("common-Bot/src/main/kotlin/cn/kerong/bot/ServerNotifyManager.kt")
check("C1 窗口判定分支在位", "plugin.serverNotifyRestartWindow() * 1000L" in snm and "elapsedMs <= windowMs" in snm)
check("C2 超窗走 start 开机通知", "plugin.isServerNotifyStartEnabled()" in snm and "plugin.serverNotifyStartMessage()" in snm)
check("C3 窗口 0 = 不判定重启", "windowMs > 0 && elapsedMs <= windowMs" in snm)
check("C4 重启日志口径", "判定为重启" in snm)
check("C5 KDoc 新口径（180 秒窗口 / 0 = 不判定）", "restart-window 秒（默认 180，可配置）判定为「重启」" in snm and "窗口配 0 = 不判定重启" in snm)

snm_cls = "cn/kerong/bot/ServerNotifyManager.class"
for tag, jar in [("Spigot", SPIGOT_JAR), ("NeoForge", NF_JAR)]:
    check(f"C6 {tag} JAR 字节码窗口 getter 调用", class_bytes_has(jar, snm_cls, "serverNotifyRestartWindow"))
    check(f"C7 {tag} JAR 字节码 start getter 调用", class_bytes_has(jar, snm_cls, "serverNotifyStartMessage"))
    check(f"C8 {tag} JAR 字节码重启日志字符串", class_bytes_has(jar, snm_cls, "判定为重启"))

print("== D. 接线修复：双端主类 override 委托 ==")
ks = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/KERONGSpigot.kt")
kn = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt")
GETTERS = ["isServerNotifyEnabled", "serverNotifyRestartWindow", "isServerNotifyShutdownEnabled",
           "serverNotifyShutdownMessage", "isServerNotifyRestartEnabled", "serverNotifyRestartMessage",
           "isServerNotifyStartEnabled", "serverNotifyStartMessage", "isServerNotifyCrashEnabled",
           "serverNotifyCrashMessage"]
check("D1 KERONGSpigot 十个 getter 全接线", all(f"override fun {g}(" in ks for g in GETTERS))
check("D2 KERONGNeoForge 十个 getter 全接线", all(f"override fun {g}(" in kn for g in GETTERS))
for tag, jar, cls in [("Spigot", SPIGOT_JAR, "cn.kerong.penguin.spigot.KERONGSpigot"),
                      ("NeoForge", NF_JAR, "cn.kerong.penguin.neoforge.KERONGNeoForge")]:
    m = javap_methods(jar, cls)
    check(f"D3 {tag} JAR 字节码方法在位（window/start/crash）",
          all(g in m for g in ["serverNotifyRestartWindow", "serverNotifyStartMessage", "serverNotifyCrashMessage", "serverNotifyShutdownMessage"]))

print("== E. 废弃键与收敛顺序 ==")
removed_block = cm[cm.index("REMOVED_CONFIG_KEYS = listOf("):cm.index(")", cm.index("REMOVED_CONFIG_KEYS = listOf("))]
check("E1 restart-window 已移出废弃清单", '"server-notify.restart-window",' not in removed_block and "已在 1.7.4.1 覆盖版恢复" in removed_block)
check("E2 源码「轨迹」仅收敛字面量+历史注释（cm 2 / rb 1）", cm.count("轨迹") == 2 and rb.count("轨迹") == 1)
for tag, jar in [("Spigot", SPIGOT_JAR), ("NeoForge", NF_JAR)]:
    # JAR 内模板含 restart-window 键（说明不再是废弃键）
    check(f"E3 {tag} JAR config.yml restart-window 键在位", "restart-window: 180" in jar_read(jar, "config.yml"))

print("== F. 版本号与第1轮回归 ==")
check("F1 根 build.gradle.kts 1.7.4.1", 'version = "1.7.4.1"' in src("build.gradle.kts"))
check("F2 NF build.gradle.kts 1.7.4.1", 'version = "1.7.4.1"' in src("server-NeoForge/build.gradle.kts"))
check("F3 plugin.yml 版本 1.7.4.1", "version: '1.7.4.1'" in src("server-Spigot/src/main/resources/plugin.yml"))
for tag, jar in [("Spigot", SPIGOT_JAR), ("NeoForge", NF_JAR)]:
    check(f"F4 {tag} JAR plugin 版本 1.7.4.1", "1.7.4.1" in jar_read(jar, "plugin.yml" if tag == "Spigot" else "META-INF/neoforge.mods.toml"))
# 第1轮 @修复回归（源码 + 双 JAR 字节码）
gmh = src("common-Bot/src/main/kotlin/cn/kerong/bot/handler/GroupMessageHandler.kt") if (REPO / "common-Bot/src/main/kotlin/cn/kerong/bot/handler/GroupMessageHandler.kt").exists() else None
gmh_found = None
for p in (REPO / "common-Bot/src").rglob("*.kt"):
    if "isMentionedBot" in p.read_text(encoding="utf-8"):
        gmh_found = p
        break
check("F5 第1轮 @分流逻辑源码在位", gmh_found is not None and "isMentionedBot" in gmh_found.read_text(encoding="utf-8"))
for tag, jar in [("Spigot", SPIGOT_JAR), ("NeoForge", NF_JAR)]:
    with zipfile.ZipFile(jar) as zf:
        gmh_cls = [n for n in zf.namelist() if "GroupMessageHandler" in n and n.endswith(".class") and "$" not in n]
    check(f"F6 {tag} JAR GroupMessageHandler 在位", len(gmh_cls) == 1)
    if gmh_cls:
        code = javap_code(jar, gmh_cls[0][:-6].replace("/", "."))
        check(f"F7 {tag} 字节码 isMentionedBot 调用在位", "isMentionedBot" in code)

print("== G. 基线保持 ==")
sn = jar_names(SPIGOT_JAR)
check("G1 Spigot 11870 条目（ZipFile 口径，与线上底座零增删）", len(sn) == 11870, f"实际 {len(sn)}")
check("G2 Spigot 776 目录", sum(1 for n in sn if n.endswith("/")) == 776)
qrl = src("common-Bot/src/main/kotlin/cn/kerong/bot/QrLoginManager.kt") if (REPO / "common-Bot/src/main/kotlin/cn/kerong/bot/QrLoginManager.kt").exists() else ""
qr_file = next((REPO / "common-Bot/src").rglob("QrLoginManager.kt"), None)
if qr_file:
    qrl = qr_file.read_text(encoding="utf-8")
check("G3 二维码延时 30s / 上限 3", "STARTUP_DELAY_MS" in qrl and "30_000L" in qrl and "MAX_REFRESH_COUNT = 3" in qrl)
check("G4 副标题灰度 106（DEFAULT_VALUES）", '"render.subtitle-card-gray" to 106' in cm)
check("G5 README 新口径", "1.7.4.1（两项）" in src("README.md") and "服务器重启完毕，已正常运行(o‿∩)" in src("README.md") and "39 → 40" in src("README.md"))
web_v = src("website/version.json")
check("G6 version.json 新条目", "服务器重启完毕，已正常运行(o‿∩)" in web_v and "restart-window" in web_v)
web_i = src("website/index.html")
check("G7 index.html v1.7.4.1 块更新", "180 秒重启判定" in web_i and "server-notify.start" in web_i)
web_t = src("website/tutorial.html")
check("G8 tutorial.html server-notify 新段", "restart-window: 180" in web_t and "服务器重启完毕" in web_t and "服务器...." in web_t)

print()
print(f"合计：{len(passed)} 通过 / {len(failed)} 失败")
if failed:
    print("失败项：")
    for f in failed:
        print("  ✗", f)
    sys.exit(1)
print("全部通过 ✓")
