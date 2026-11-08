#!/usr/bin/env python3
"""1.7.4 第四轮覆盖版门禁验证。

验证范围（源码 + 双端 JAR 字节码 / 资源）：
A. 查信息反查：lookupByPlayerName 玩家名反查 + player-lookup 模板 + 分派逻辑
B. 多绑定全部显示：OpenId 反查与玩家名反查都不弹选择、全部列出
C. 玩家名片@对方多绑定弹选择按钮（既有行为保持）
D. 副标题灰度 106：常量/默认值/收敛逻辑（40/56/80→106）/双端模板/config-version 37
E. 配置注释简化：双端 config.yml 无「1.7.4」「覆盖版」版本字段
F. 基线保持：版本 1.7.4 / 776 目录条目 / 既有功能类在
"""
import re
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
DIST = REPO / "build" / "dist"
SPIGOT_JAR = DIST / "KERONGPenguin_Spigot-1.7.4.jar"
NF_JAR = DIST / "KERONGPenguin_NeoForge-1.20.4-1.7.4.jar"

passed = []
failed = []


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


def class_refs(jar, pattern):
    rx = re.compile(pattern.encode())
    with zipfile.ZipFile(jar) as zf:
        for name in zf.namelist():
            if name.endswith(".class"):
                if rx.search(zf.read(name)):
                    return True
    return False


pc = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/PublicCommands.kt")
cm = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
cfr = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.kt")
rs = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/RenderSettings.kt")
cfg_s = src("server-Spigot/src/main/resources/config.yml")
cfg_n = src("server-NeoForge/src/main/resources/config.yml")
spigot_names = jar_names(SPIGOT_JAR)
nf_names = jar_names(NF_JAR)

print("== A. 查信息反查（查信息 <玩家名>） ==")
check("lookupByPlayerName 方法在位", "private fun lookupByPlayerName(" in pc)
check("玩家名查找链（索引 + 扫盘兜底）", "getQuuid(playerName) ?: bindManager.findQuuidByPlayerName(playerName)" in pc)
check("queryInfo 分派：先玩家名后 OpenId", "if (lookupByPlayerName(plugin, event, arg)) return" in pc
      and "reverseLookupOpenId(plugin, event, arg)" in pc)
check("管理员才显示 OpenId 行（玩家反查）", 'if (isAdmin(plugin, event)) "QQ(OpenId): $openId\\n" else ""' in pc)
check("TemplateIDs 登记 player-lookup", '"player-lookup"' in
      src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/MessageTemplates.kt"))
for rel in ["common-Bot/src/main/resources/Markdown/player-lookup.md",
            "server-NeoForge/src/main/resources/Markdown/player-lookup.md"]:
    check(f"模板文件 {Path(rel).parent.parent.name}", "{player}" in src(rel) and "{players}" in src(rel) and "{openidPart}" in src(rel))
check("Spigot JAR 含 player-lookup.md", "Markdown/player-lookup.md" in spigot_names)
check("NF JAR 含 player-lookup.md", "Markdown/player-lookup.md" in nf_names)
for label, cfg in [("Spigot", cfg_s), ("NeoForge", cfg_n)]:
    check(f"{label} config.yml messages.player-lookup", 'player-lookup: "player-lookup.md"' in cfg)
for rel in ["common-Bot/src/main/resources/Markdown/help.md", "server-NeoForge/src/main/resources/Markdown/help.md"]:
    check(f"{Path(rel).parent.parent.name} help.md 玩家名反查条目", "/查信息 <玩家名>" in src(rel))
check("JAR 字节码引用 player-lookup", class_refs(SPIGOT_JAR, "player-lookup"))
check("JAR 字节码引用 QQ(OpenId) 行", class_refs(SPIGOT_JAR, r"QQ\(OpenId\)"))

print("== B. 多绑定全部显示（反查不弹选择） ==")
rl_section = pc.split("private fun reverseLookupOpenId")[1] if "private fun reverseLookupOpenId" in pc else ""
check("OpenId 反查多绑定全列出", "joinToString(\"、\")" in rl_section)
check("OpenId 反查不再走 PlayerSelection", "PlayerSelection.selectOrRun" not in rl_section)
lp_section = pc.split("private fun lookupByPlayerName")[1].split("private fun")[0] if "private fun lookupByPlayerName" in pc else ""
check("玩家名反查显示该 QQ 全部绑定玩家", "listBoundPlayersByQq(openId)" in lp_section and "joinToString(\"、\")" in lp_section)

print("== C. 玩家名片@对方多绑定弹选择按钮（保持） ==")
info_section = pc.split('fun queryPersonalInfo')[1].split("fun queryInfo")[0] if "fun queryPersonalInfo" in pc else ""
check("@路径走 requireBoundPlayer（info-target-not-bound）", 'notBoundTemplate = "info-target-not-bound"' in info_section)
ps = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/PlayerSelection.kt")
check("选择消息带按钮键盘", "Keyboards.build" in ps and 'actionData = "$ACTION_PREFIX${player.playerName}"' in ps)
check("数字兜底保留", "fun tryConsume(" in ps)
check("玩家名片自身路径同样先选择", 'notBoundTemplate = "info-not-bound"' in info_section)

print("== D. 副标题灰度 106 ==")
check("RenderSettings 常量 106", "DEFAULT_SUBTITLE_CARD_GRAY = 106" in rs)
check("ConfigManager DEFAULT_VALUES 106", '"render.subtitle-card-gray" to 106' in cm)
check("ConfigManager 收敛 40/56/80 → 106", "subGray == 40 || subGray == 56 || subGray == 80" in cm
      and 'config.set("render.subtitle-card-gray", 106)' in cm)
check("ConfigManager 收敛条件 until 37", "oldVersion in 1 until 37" in cm)
check("ConfigFileRebuilder 收敛 40/56/80 → 106", "subGrayValue == 40 || subGrayValue == 56 || subGrayValue == 80" in cfr
      and 'disk.set("render.subtitle-card-gray", 106)' in cfr)
check("config-version 37（代码）", "CURRENT_CONFIG_VERSION = 37" in cm)
for label, cfg in [("Spigot", cfg_s), ("NeoForge", cfg_n)]:
    check(f"{label} config.yml subtitle-card-gray: 106", "subtitle-card-gray: 106" in cfg)
    check(f"{label} config.yml config-version: 37", "config-version: 37" in cfg)
check("JAR 内置 config 副标题 106（Spigot）", "subtitle-card-gray: 106" in jar_read(SPIGOT_JAR, "config.yml"))
check("JAR 内置 config 副标题 106（NF）", "subtitle-card-gray: 106" in jar_read(NF_JAR, "config.yml"))
check("JAR 内置 config-version 37（双端）", "config-version: 37" in jar_read(SPIGOT_JAR, "config.yml")
      and "config-version: 37" in jar_read(NF_JAR, "config.yml"))
check("JAR 字节码副标题收敛日志", class_refs(SPIGOT_JAR, r"106（render.subtitle-card-gray"))

print("== E. 配置注释简化（无版本字段） ==")
for label, cfg in [("Spigot", cfg_s), ("NeoForge", cfg_n)]:
    check(f"{label} config.yml 无 1.7.4", "1.7.4" not in cfg)
    check(f"{label} config.yml 无「覆盖版」", "覆盖版" not in cfg)
    check(f"{label} config.yml 无「第三轮」", "第三轮" not in cfg)
    check(f"{label} config.yml 查信息注释为反查口径", "查 OpenId（加玩家名可反查其绑定的 QQ）" in cfg)
    check(f"{label} config.yml 无「四位数群绑定码」", "四位数群绑定码" not in cfg)
    check(f"{label} config.yml cluster 注释简化（无 HuHoBot 字样）", "HuHoBot" not in cfg)

print("== F. 基线保持 ==")
check("plugin.yml 版本 1.7.4", "version: '1.7.4'" in src("server-Spigot/src/main/resources/plugin.yml")
      or "version: 1.7.4" in src("server-Spigot/src/main/resources/plugin.yml"))
check("Spigot JAR 目录条目 776 基线", len([n for n in spigot_names if n.endswith("/")]) == 776,
      str(len([n for n in spigot_names if n.endswith("/")])))
for cls in ["cn/kerong/bot/QrLoginManager.class", "cn/kerong/bot/ClusterState.class",
            "cn/kerong/bot/ServerNotifyManager.class", "cn/kerong/bot/events/AutoGroupBinder.class",
            "cn/kerong/bot/events/PlayerSelection.class", "cn/kerong/bot/events/ServerSelectButtons.class"]:
    check(f"既有功能类保留 {Path(cls).name}", cls in spigot_names)
check("扫码登录保留（connect 链接）", "connect.html?task_id=" in
      src("common-Bot/src/main/kotlin/cn/kerong/bot/QrLoginManager.kt"))
check("旧名兜底仍下线（@Commands 无查询open）",
      not re.search(r'@Commands\([^)]*查询', pc))
check("server-notify 段保留", "server-notify:" in cfg_s and "server-notify:" in cfg_n)
check("help.md 模板双端一致",
      src("common-Bot/src/main/resources/Markdown/help.md") == src("server-NeoForge/src/main/resources/Markdown/help.md"))
check("player-lookup.md 模板双端一致",
      src("common-Bot/src/main/resources/Markdown/player-lookup.md") == src("server-NeoForge/src/main/resources/Markdown/player-lookup.md"))
check("docs.huhobot 链接零出现",
      len([l for l in subprocess.run(["grep", "-rn", "docs.huhobot.dpdns.org",
          "--include=*.kt", "--include=*.yml", "--include=*.md", "--include=*.html", "--include=*.json", "."],
          cwd=REPO, capture_output=True, text=True).stdout.splitlines() if "/build/" not in l]) == 0)

print(f"\n===== 结果：{len(passed)} 通过 / {len(failed)} 失败 =====")
if failed:
    print("失败项：")
    for name in failed:
        print("  ✗", name)
    sys.exit(1)
