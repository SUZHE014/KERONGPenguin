#!/usr/bin/env python3
"""1.7.4 第六轮覆盖版门禁验证。

验证范围（源码 + 双端 JAR 资源 / 字节码）：
A. 服务器开关消息默认文案换新：双端模板 + ConfigManager（getter / DEFAULT_VALUES）
   + ConfigProvider 三处新文案；旧文案在模板 / tutorial 零残留
B. YAML 语义：\n 转义解析为真实换行（PyYAML 逐字校验三条文案）
C. 升级收敛：ConfigManager reload 收敛块 + ConfigFileRebuilder 收敛块
   （旧默认 → 新默认，1 until 38 / 低于 38，自改值不动口径）
D. config-version 37 → 38（代码 + 双端模板 + 双端 JAR 资源）
E. HuHoBot 口径：config 模板 / 官网四件 / JAR 全部文本资源零出现；
   git 侧（README 设计参考 / 源码 KDoc）致意保留（用户口径「git 的不用删」）
F. 基线保持：版本 1.7.4 / 776 目录条目 / 第五轮内容（延时 30 秒 / 上限 3 /
   反查全员可见 / 灰度 106）不回退
"""
import re
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
NEW_CRASH = "[状态]\n服务器....༽◺_轨迹༼"
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

print("== A. 播报默认文案换新（源码与模板） ==")
for label, text in [("关机", NEW_SHUTDOWN), ("重启/开启", NEW_RESTART), ("意外关闭", NEW_CRASH)]:
    lit = text.replace("\n", "\\n")
    check(f"ConfigManager getter 新文案·{label}", f'"{lit}"' in cm)
    check(f"ConfigManager DEFAULT_VALUES 新文案·{label}", f'to "{lit}"' in cm)
    check(f"ConfigProvider 默认文案·{label}", f'= "{lit}"' in cp)
    check(f"Spigot 模板新文案·{label}", f'message: "{lit}"' in cfg_s)
    check(f"NeoForge 模板新文案·{label}", f'message: "{lit}"' in cfg_n)
    check(f"tutorial 新文案·{label}", lit in tutorial)
check("README 详史段新文案", NEW_SHUTDOWN.split("\n")[1] in readme and NEW_RESTART.split("\n")[1] in readme)

print("== B. YAML 语义（\\n 转义 = 真实换行，PyYAML 逐字校验） ==")
for jar, label in [(SPIGOT_JAR, "Spigot"), (NF_JAR, "NeoForge")]:
    data = yaml.safe_load(jar_read(jar, "config.yml"))
    sn = data["server-notify"]
    check(f"{label} JAR 关机文案逐字一致", sn["shutdown"]["message"] == NEW_SHUTDOWN, repr(sn["shutdown"]["message"]))
    check(f"{label} JAR 重启文案逐字一致", sn["restart"]["message"] == NEW_RESTART, repr(sn["restart"]["message"]))
    check(f"{label} JAR 意外关闭文案逐字一致", sn["crash"]["message"] == NEW_CRASH, repr(sn["crash"]["message"]))
    check(f"{label} JAR 三键开关保持默认开", sn["enabled"] is True and sn["shutdown"]["enabled"] is True
          and sn["restart"]["enabled"] is True and sn["crash"]["enabled"] is True)
    check(f"{label} JAR restart-window 180", sn["restart-window"] == 180)
data = yaml.safe_load(cfg_s)
check("仓库模板（Spigot）三条逐字一致",
      data["server-notify"]["shutdown"]["message"] == NEW_SHUTDOWN
      and data["server-notify"]["restart"]["message"] == NEW_RESTART
      and data["server-notify"]["crash"]["message"] == NEW_CRASH)

print("== C. 升级收敛（历史默认自动收敛，自改值不动） ==")
check("reload 收敛块·关机 旧→新", '"server-notify.shutdown.message") == "服务器已正常关闭"' in cm
      and 'config.set("server-notify.shutdown.message", "[状态]\\n服务器已正常关闭 (╯︵╰,)")' in cm)
check("reload 收敛块·重启 旧→新", '"server-notify.restart.message") == "服务器已重启完成"' in cm
      and 'config.set("server-notify.restart.message", "[状态]\\n服务器已开启！！(≧∇≦)/")' in cm)
check("reload 收敛块·意外关闭 旧→新", '"server-notify.crash.message") == "服务器意外关闭（崩溃或被强制结束），请管理员检查控制台"' in cm
      and 'config.set("server-notify.crash.message", "[状态]\\n服务器....༽◺_轨迹༼")' in cm)
check("reload 收敛版本门槛 1 until 38", "oldVersion in 1 until 38" in cm)
check("reload 收敛提示日志", "服务器开关消息默认文案已换新" in cm)
check("重建器收敛块·三条同款", 'disk.getString("server-notify.shutdown.message") == "服务器已正常关闭"' in cfr
      and 'disk.getString("server-notify.restart.message") == "服务器已重启完成"' in cfr
      and 'disk.getString("server-notify.crash.message") == "服务器意外关闭（崩溃或被强制结束），请管理员检查控制台"' in cfr)
check("重建器收敛注释·低于 38 触发", "低于 38 时触发" in cfr)
check("收敛自改值不动口径（reload 注释）", "自行改过文案的配置不受影响" in cm)

print("== D. config-version 37 → 38 ==")
check("代码 CURRENT_CONFIG_VERSION = 38", "private const val CURRENT_CONFIG_VERSION = 38" in cm)
check("版本历史注释补 38 条目", "升 38——服务器开关消息三条默认文案换新" in cm)
check("双端模板 config-version: 38", "config-version: 38" in cfg_s and "config-version: 38" in cfg_n)
check("双端 JAR config-version: 38", "config-version: 38" in jar_cfg_s and "config-version: 38" in jar_cfg_n)
check("无 37 残留（模板）", "config-version: 37" not in cfg_s and "config-version: 37" not in cfg_n)

print("== E. HuHoBot 口径（配置与官网零出现，git 致意保留） ==")
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
check("源码 KDoc 致意保留（git 不删）", "口径与 HuHoBot 一致" in cm)
check("docs.huhobot 链接零出现（源码与文档）",
      len([l for l in subprocess.run(["grep", "-rn", "docs.huhobot.dpdns.org",
          "--include=*.kt", "--include=*.yml", "--include=*.md", "--include=*.html", "--include=*.json", "."],
          cwd=REPO, capture_output=True, text=True).stdout.splitlines() if "/build/" not in l]) == 0)

print("== F. 基线保持（既有内容不回退） ==")
check("plugin.yml 版本 1.7.4", "version: '1.7.4'" in src("server-Spigot/src/main/resources/plugin.yml")
      or "version: 1.7.4" in src("server-Spigot/src/main/resources/plugin.yml"))
check("Spigot JAR 目录条目 776 基线", len([n for n in spigot_names if n.endswith("/")]) == 776,
      str(len([n for n in spigot_names if n.endswith("/")])))
check("Spigot JAR 条目数 11870（与线上底座一致）", len(spigot_names) == 11870, str(len(spigot_names)))
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
      class_has(SPIGOT_JAR, "cn/kerong/penguin/spigot/manager/ConfigManager.class", NEW_SHUTDOWN)
      and class_has(SPIGOT_JAR, "cn/kerong/penguin/spigot/manager/ConfigManager.class", NEW_RESTART)
      and class_has(SPIGOT_JAR, "cn/kerong/penguin/spigot/manager/ConfigManager.class", NEW_CRASH))
check("ConfigFileRebuilder 字节码含收敛旧值常量",
      class_has(SPIGOT_JAR, "cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.class", OLD_RESTART))
check("ConfigProvider 字节码含新文案常量",
      class_has(SPIGOT_JAR, "cn/kerong/bot/provider/ConfigProvider.class", NEW_CRASH))
check("模板无旧默认文案残留（Spigot）",
      f'message: "{OLD_SHUTDOWN}"' not in cfg_s and f'message: "{OLD_RESTART}"' not in cfg_s
      and f'message: "{OLD_CRASH}"' not in cfg_s)
check("模板无旧默认文案残留（NeoForge）",
      f'message: "{OLD_SHUTDOWN}"' not in cfg_n and f'message: "{OLD_RESTART}"' not in cfg_n
      and f'message: "{OLD_CRASH}"' not in cfg_n)
check("JAR 模板无旧默认文案（双端）",
      f'message: "{OLD_SHUTDOWN}"' not in jar_cfg_s and f'message: "{OLD_RESTART}"' not in jar_cfg_s
      and f'message: "{OLD_CRASH}"' not in jar_cfg_s and f'message: "{OLD_SHUTDOWN}"' not in jar_cfg_n
      and f'message: "{OLD_RESTART}"' not in jar_cfg_n and f'message: "{OLD_CRASH}"' not in jar_cfg_n)
check("教程 server-notify 段占位符说明保持", "占位符：{server}、{platform}" in tutorial)

print(f"\n===== 结果：{len(passed)} 通过 / {len(failed)} 失败 =====")
if failed:
    print("失败项：")
    for name in failed:
        print("  ✗", name)
    sys.exit(1)
