#!/usr/bin/env python3
"""1.7.4 第七轮覆盖版门禁验证。

本轮两项改动：
① 意外关闭默认文案颜文字修正：༽◺_轨迹༼ → ༽◺_◿༼（去掉误写的「轨迹」二字，
   第 4 字符 U+25FF；第六轮误写版加入收敛源，已装第六轮的配置升级自动纠正）
② 重启时间窗口删除：超过 restart-window 秒再启动不播报的机制整体移除——
   上次正常关闭后再启动一律发开启通知（不限间隔时长）；restart-window 键
   进废弃清单；config-version 38 → 39

验证范围（源码 + 双端 JAR 资源 / 字节码）：
A. 颜文字修正：三处代码 + 双端模板 + tutorial 新文案逐字一致（含码点校验）；
   「轨迹」字样只允许出现在两处收敛比较源（ConfigManager / ConfigFileRebuilder）
   与 README 详史说明里；模板 / tutorial / JAR 资源零出现
B. YAML 语义：\\n 转义解析为真实换行（PyYAML 逐字校验三条文案）
C. 升级收敛：旧默认 + 第六轮误写版 → 新文案（reload 1 until 39 / 重建器低于 39，
   自改值不动口径）
D. 重启时间窗口删除：源码与字节码零残留（getter / 窗口判定 / DEFAULT_VALUES /
   模板 / JAR），REMOVED_CONFIG_KEYS 收录，新判定 previousStopTime > 0
E. config-version 38 → 39（代码 + 双端模板 + 双端 JAR 资源）
F. HuHoBot 口径：config 模板 / 官网四件 / JAR 全部文本资源零出现；
   git 侧致意保留（用户口径「git 的不用删」）
G. 基线保持：版本 1.7.4 / 11870 条目 / 776 目录 / 第五轮（延时 30 秒 / 上限 3 /
   反查全员可见）/ 灰度 106 / 第六轮关机与开启文案不回退
"""
import subprocess
import sys
import zipfile
from pathlib import Path

import yaml

REPO = Path("/home/z/my-project/penguin-git")
DIST = REPO / "build" / "dist"
SPIGOT_JAR = DIST / "KERONGPenguin_Spigot-1.7.4.jar"
NF_JAR = DIST / "KERONGPenguin_NeoForge-1.20.4-1.7.4.jar"

passed = []
failed = []

NEW_SHUTDOWN = "[状态]\n服务器已正常关闭 (╯︵╰,)"
NEW_RESTART = "[状态]\n服务器已开启！！(≧∇≦)/"
NEW_CRASH = "[状态]\n服务器....༽◺_◿༼"
WRONG_CRASH = "[状态]\n服务器....༽◺_轨迹༼"   # 第六轮误写版（收敛源）
OLD_SHUTDOWN = "服务器已正常关闭"
OLD_RESTART = "服务器已重启完成"
OLD_CRASH = "服务器意外关闭（崩溃或被强制结束），请管理员检查控制台"


def check(name, ok, detail=""):
    (passed if ok else failed).append(name)
    print(f"  {'✓' if ok else '✗'} {name}" + (f" — {detail}" if detail and not ok else ""))


def src(rel):
    return (REPO / rel).read_text(encoding="utf-8")


def jar_names(jar):
    with zipfile.ZipFile(jar) as zf:
        return zf.namelist()


def jar_read(jar, name):
    with zipfile.ZipFile(jar) as zf:
        return zf.read(name).decode("utf-8", errors="replace")


def class_has(jar, cls, needle):
    with zipfile.ZipFile(jar) as zf:
        return needle.encode("utf-8") in zf.read(cls)


cm = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
cfr = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.kt")
cp = src("common-Bot/src/main/kotlin/cn/kerong/bot/provider/ConfigProvider.kt")
snm = src("common-Bot/src/main/kotlin/cn/kerong/bot/ServerNotifyManager.kt")
cfg_s = src("server-Spigot/src/main/resources/config.yml")
cfg_n = src("server-NeoForge/src/main/resources/config.yml")
tutorial = src("website/tutorial.html")
index = src("website/index.html")
version = src("website/version.json")
readme = src("README.md")

spigot_names = jar_names(SPIGOT_JAR)
nf_names = jar_names(NF_JAR)
jar_cfg_s = jar_read(SPIGOT_JAR, "config.yml")
jar_cfg_n = jar_read(NF_JAR, "config.yml")

print("== A. 颜文字修正（新文案逐字一致 + 码点校验） ==")
# 码点校验：用户指定的 ༽◺_◿༼ = U+0F3D U+25FA U+005F U+25FF U+0F3C
target = "༽◺_◿༼"
check("新颜文字码点 = 0F3D 25FA 005F 25FF 0F3C",
      [hex(ord(c)) for c in target] == ["0xf3d", "0x25fa", "0x5f", "0x25ff", "0xf3c"])
for label, text in [("关机", NEW_SHUTDOWN), ("开启", NEW_RESTART), ("意外关闭", NEW_CRASH)]:
    lit = text.replace("\n", "\\n")
    check(f"ConfigManager getter 新文案·{label}", f'"{lit}"' in cm)
    check(f"ConfigManager DEFAULT_VALUES 新文案·{label}", f'to "{lit}"' in cm)
    check(f"ConfigProvider 默认文案·{label}", f'= "{lit}"' in cp)
    check(f"Spigot 模板新文案·{label}", f'message: "{lit}"' in cfg_s)
    check(f"NeoForge 模板新文案·{label}", f'message: "{lit}"' in cfg_n)
check("tutorial 意外关闭新文案", NEW_CRASH.replace("\n", "\\n") in tutorial)
check("README 摘要段与详史段新文案", "༽◺_◿༼" in readme)
# 「轨迹」出现位置策略
check("源码「轨迹」只在 ConfigManager 收敛源/注释", cm.count("轨迹") >= 1
      and 'crashText == "[状态]\\n服务器....༽◺_轨迹༼"' in cm)
check("源码「轨迹」只在 ConfigFileRebuilder 收敛源/注释", cfr.count("轨迹") >= 1
      and 'crashText == "[状态]\\n服务器....༽◺_轨迹༼"' in cfr)
for label, text in [("Spigot 模板", cfg_s), ("NeoForge 模板", cfg_n), ("tutorial", tutorial),
                    ("index", index), ("version.json", version)]:
    check(f"{label} 零「轨迹」", "轨迹" not in text)
for label, text in [("Spigot JAR config", jar_cfg_s), ("NeoForge JAR config", jar_cfg_n)]:
    check(f"{label} 零「轨迹」", "轨迹" not in text)

print("== B. YAML 语义（\\n 转义 = 真实换行，PyYAML 逐字校验） ==")
for jar, label in [(SPIGOT_JAR, "Spigot"), (NF_JAR, "NeoForge")]:
    data = yaml.safe_load(jar_read(jar, "config.yml"))
    sn = data["server-notify"]
    check(f"{label} JAR 关机文案逐字一致", sn["shutdown"]["message"] == NEW_SHUTDOWN, repr(sn["shutdown"]["message"]))
    check(f"{label} JAR 开启文案逐字一致", sn["restart"]["message"] == NEW_RESTART, repr(sn["restart"]["message"]))
    check(f"{label} JAR 意外关闭文案逐字一致", sn["crash"]["message"] == NEW_CRASH, repr(sn["crash"]["message"]))
    check(f"{label} JAR 四键开关保持默认开", sn["enabled"] is True and sn["shutdown"]["enabled"] is True
          and sn["restart"]["enabled"] is True and sn["crash"]["enabled"] is True)
    check(f"{label} JAR restart-window 键已删除", "restart-window" not in sn)
data = yaml.safe_load(cfg_s)
check("仓库模板（Spigot）三条逐字一致",
      data["server-notify"]["shutdown"]["message"] == NEW_SHUTDOWN
      and data["server-notify"]["restart"]["message"] == NEW_RESTART
      and data["server-notify"]["crash"]["message"] == NEW_CRASH)

print("== C. 升级收敛（旧默认 + 第六轮误写版 → 新文案，自改值不动） ==")
check("reload 收敛块·关机 旧→新", '"server-notify.shutdown.message") == "服务器已正常关闭"' in cm
      and 'config.set("server-notify.shutdown.message", "[状态]\\n服务器已正常关闭 (╯︵╰,)")' in cm)
check("reload 收敛块·开启 旧→新", '"server-notify.restart.message") == "服务器已重启完成"' in cm
      and 'config.set("server-notify.restart.message", "[状态]\\n服务器已开启！！(≧∇≦)/")' in cm)
check("reload 收敛块·意外关闭 旧默认→新", '"服务器意外关闭（崩溃或被强制结束），请管理员检查控制台"' in cm
      and 'config.set("server-notify.crash.message", "[状态]\\n服务器....༽◺_◿༼")' in cm)
check("reload 收敛块·第六轮误写版→新（crashText 双源）",
      'val crashText = config.getString("server-notify.crash.message")' in cm
      and 'crashText == "[状态]\\n服务器....༽◺_轨迹༼"' in cm)
check("reload 收敛版本门槛 1 until 39", "oldVersion in 1 until 39" in cm)
check("reload 收敛提示日志保持", "服务器开关消息默认文案已换新" in cm)
check("重建器收敛块·关机/开启同款", 'disk.getString("server-notify.shutdown.message") == "服务器已正常关闭"' in cfr
      and 'disk.getString("server-notify.restart.message") == "服务器已重启完成"' in cfr)
check("重建器收敛块·意外关闭旧默认→新", '"服务器意外关闭（崩溃或被强制结束），请管理员检查控制台"' in cfr
      and 'disk.set("server-notify.crash.message", "[状态]\\n服务器....༽◺_◿༼")' in cfr)
check("重建器收敛块·第六轮误写版→新（crashText 双源）",
      'val crashText = disk.getString("server-notify.crash.message")' in cfr
      and 'crashText == "[状态]\\n服务器....༽◺_轨迹༼"' in cfr)
check("重建器收敛注释·低于 39 触发", "低于 39 时触发" in cfr)
check("收敛自改值不动口径（reload 注释）", "自行改过文案的配置不受影响" in cm)

print("== D. 重启时间窗口删除（源码与字节码零残留） ==")
check("ConfigManager getter 已删", "serverNotifyRestartWindow" not in cm)
check("ConfigProvider 接口方法已删", "serverNotifyRestartWindow" not in cp)
check("ServerNotifyManager 调用已删", "serverNotifyRestartWindow" not in snm)
check("窗口判定表达式已删", "restartWindowMs" not in snm and "0..restartWindowMs" not in snm)
check("新判定 previousStopTime > 0（不限间隔）", "previousStopTime > 0 -> {" in snm)
check("新开启通知日志", "检测到上次正常运行后重新启动" in snm)
check("KDoc 不限间隔口径", "不限间隔时长" in snm)
check("REMOVED_CONFIG_KEYS 收录 restart-window", '"server-notify.restart-window",' in cm)
check("DEFAULT_VALUES 无 restart-window", '"server-notify.restart-window" to 180' not in cm)
check("Spigot 模板无 restart-window", "restart-window" not in cfg_s)
check("NeoForge 模板无 restart-window", "restart-window" not in cfg_n)
check("模板段落注释新口径", "不限间隔时长" in cfg_s and "首次安装不发送" in cfg_s
      and "不限间隔时长" in cfg_n and "首次安装不发送" in cfg_n)
check("tutorial 无 restart-window", "restart-window" not in tutorial)
check("tutorial 描述新口径", "不限间隔时长" in tutorial and "首次安装不发送" in tutorial
      and "普通开机不发送" not in tutorial)
# 字节码：getter 方法名零出现；restart-window 字符串仅存于废弃键清单字面量
cm_cls = "cn/kerong/penguin/spigot/manager/ConfigManager.class"
check("ConfigManager 字节码无 getter 方法名", not class_has(SPIGOT_JAR, cm_cls, "serverNotifyRestartWindow"))
check("ConfigManager 字节码 restart-window 仅废弃清单一处",
      jar_read(SPIGOT_JAR, cm_cls).count("server-notify.restart-window") == 1)
check("ConfigProvider 字节码无 getter 方法名",
      not class_has(SPIGOT_JAR, "cn/kerong/bot/provider/ConfigProvider.class", "serverNotifyRestartWindow"))
snm_cls = "cn/kerong/bot/ServerNotifyManager.class"
check("ServerNotifyManager 字节码无 getter 方法名", not class_has(SPIGOT_JAR, snm_cls, "serverNotifyRestartWindow"))
check("ServerNotifyManager 字节码含新开启日志", class_has(SPIGOT_JAR, snm_cls, "检测到上次正常运行后重新启动"))
check("ServerNotifyManager 字节码含新判定（读停止时间键）", class_has(SPIGOT_JAR, snm_cls, "stop-time"))
check("NeoForge JAR 同款（ConfigManager 无 getter）",
      not class_has(NF_JAR, cm_cls, "serverNotifyRestartWindow"))
check("NeoForge JAR 同款（ServerNotifyManager 新日志）",
      class_has(NF_JAR, snm_cls, "检测到上次正常运行后重新启动"))

print("== E. config-version 38 → 39 ==")
check("代码 CURRENT_CONFIG_VERSION = 39", "private const val CURRENT_CONFIG_VERSION = 39" in cm)
check("版本历史注释补 39 条目", "第七轮：升 39" in cm)
check("双端模板 config-version: 39", "config-version: 39" in cfg_s and "config-version: 39" in cfg_n)
check("双端 JAR config-version: 39", "config-version: 39" in jar_cfg_s and "config-version: 39" in jar_cfg_n)
check("无 38 残留（模板）", "config-version: 38" not in cfg_s and "config-version: 38" not in cfg_n)

print("== F. HuHoBot 口径（配置与官网零出现，git 致意保留） ==")
check("Spigot 模板无 HuHoBot", "huhobot" not in cfg_s.lower())
check("NeoForge 模板无 HuHoBot", "huhobot" not in cfg_n.lower())
for label, jar in [("Spigot", SPIGOT_JAR), ("NeoForge", NF_JAR)]:
    hits = []
    with zipfile.ZipFile(jar) as zf:
        for n in zf.namelist():
            if n.endswith((".yml", ".md", ".txt", ".json", ".toml")):
                if b"huhobot" in zf.read(n).lower():
                    hits.append(n)
    check(f"{label} JAR 文本资源无 HuHoBot", not hits, str(hits))
for label, text in [("tutorial", tutorial), ("index", index), ("version.json", version)]:
    check(f"官网 {label} 无 HuHoBot", "huhobot" not in text.lower())
check("README 设计参考保留 HuHoBot（git 不删）", "HuHoBot（github.com/HuHoBot/PenguinAgent）" in readme)
check("源码 KDoc 致意保留（ServerNotifyManager）", "参考 HuHoBot" in snm)
check("源码 KDoc 致意保留（ConfigManager DEFAULT_VALUES 注释）", "配置口径与 HuHoBot 的" in cm)
check("docs.huhobot 链接零出现（源码与文档）",
      len([l for l in subprocess.run(["grep", "-rn", "docs.huhobot.dpdns.org",
          "--include=*.kt", "--include=*.yml", "--include=*.md", "--include=*.html", "--include=*.json", "."],
          cwd=REPO, capture_output=True, text=True).stdout.splitlines() if "/build/" not in l]) == 0)

print("== G. 基线保持（既有内容不回退） ==")
check("plugin.yml 版本 1.7.4", "version: '1.7.4'" in src("server-Spigot/src/main/resources/plugin.yml")
      or "version: 1.7.4" in src("server-Spigot/src/main/resources/plugin.yml"))
check("Spigot JAR 目录条目 776 基线", len([n for n in spigot_names if n.endswith("/")]) == 776,
      str(len([n for n in spigot_names if n.endswith("/")])))
check("Spigot JAR 条目数 11870（与线上底座零增删）", len(spigot_names) == 11870, str(len(spigot_names)))
qrm = src("common-Bot/src/main/kotlin/cn/kerong/bot/QrLoginManager.kt")
check("二维码延时 30 秒保持（第五轮修订）", "STARTUP_DELAY_MS = 30_000L" in qrm and "scheduleStartupStart" in qrm)
check("二维码自动刷新上限 3 保持", "MAX_REFRESH_COUNT = 3" in qrm)
pc = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/PublicCommands.kt")
check("玩家名反查保持（QQ 号全员可见基座）", "findQuuidByPlayerName(playerName)" in pc)
rs = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/RenderSettings.kt")
check("副标题灰度 106 保持", "DEFAULT_SUBTITLE_CARD_GRAY = 106" in rs)
for cls in ["cn/kerong/bot/ServerNotifyManager.class", "cn/kerong/bot/QrLoginManager.class",
            "cn/kerong/bot/events/commands/PublicCommands.class"]:
    check(f"既有功能类保留 {Path(cls).name}", cls in spigot_names and cls in nf_names)
check("ServerNotifyManager 机制保持（读 plugin 配置）",
      class_has(SPIGOT_JAR, "cn/kerong/bot/ServerNotifyManager.class", "serverNotifyShutdownMessage")
      and class_has(SPIGOT_JAR, "cn/kerong/bot/ServerNotifyManager.class", "formatMessage"))
check("ConfigManager 字节码含新文案常量（含真实换行字节）",
      class_has(SPIGOT_JAR, cm_cls, NEW_SHUTDOWN)
      and class_has(SPIGOT_JAR, cm_cls, NEW_RESTART)
      and class_has(SPIGOT_JAR, cm_cls, NEW_CRASH))
check("ConfigManager 字节码含第六轮误写版收敛源常量", class_has(SPIGOT_JAR, cm_cls, WRONG_CRASH))
check("ConfigFileRebuilder 字节码含收敛旧值常量",
      class_has(SPIGOT_JAR, "cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.class", OLD_RESTART)
      and class_has(SPIGOT_JAR, "cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.class", WRONG_CRASH))
check("ConfigProvider 字节码含新文案常量",
      class_has(SPIGOT_JAR, "cn/kerong/bot/provider/ConfigProvider.class", NEW_CRASH))
check("模板无旧默认文案残留（Spigot）",
      f'message: "{OLD_SHUTDOWN}"' not in cfg_s and f'message: "{OLD_RESTART}"' not in cfg_s
      and f'message: "{OLD_CRASH}"' not in cfg_s)
check("模板无旧默认文案残留（NeoForge）",
      f'message: "{OLD_SHUTDOWN}"' not in cfg_n and f'message: "{OLD_RESTART}"' not in cfg_n
      and f'message: "{OLD_CRASH}"' not in cfg_n)
check("JAR 模板无旧默认与误写文案（双端）",
      f'message: "{OLD_SHUTDOWN}"' not in jar_cfg_s and f'message: "{OLD_RESTART}"' not in jar_cfg_s
      and f'message: "{OLD_CRASH}"' not in jar_cfg_s and f'message: "{OLD_SHUTDOWN}"' not in jar_cfg_n
      and f'message: "{OLD_RESTART}"' not in jar_cfg_n and f'message: "{OLD_CRASH}"' not in jar_cfg_n
      and f'message: "{WRONG_CRASH}"' not in jar_cfg_s and f'message: "{WRONG_CRASH}"' not in jar_cfg_n)
check("教程 server-notify 段占位符说明保持", "占位符：{server}、{platform}" in tutorial)
check("README 第七轮详史块在位", "1.7.4 第七轮覆盖更新" in readme and "重启时间窗口删除" in readme)
check("README 摘要段口径更新", "配置版本 36 → 39" in readme)

print(f"\n===== 结果：{len(passed)} 通过 / {len(failed)} 失败 =====")
if failed:
    print("失败项：")
    for name in failed:
        print("  ✗", name)
    sys.exit(1)
