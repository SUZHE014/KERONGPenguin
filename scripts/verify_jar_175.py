#!/usr/bin/env python3
"""1.7.5 门禁验证：版本一致 / 注释清理 / 新旧命令 / 按钮与快速路径 / 配置迁移 / 官网同步 / JAR 内容。

用法：python3 scripts/verify_jar_175.py
全绿输出 VERIFY_175_OK。
"""
import re
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DIST = REPO / "build" / "dist"
SPIGOT_JAR = DIST / "KERONGPenguin_Spigot-1.7.5.jar"
NF_JAR = DIST / "KERONGPenguin_NeoForge-1.20.4-1.7.5.jar"

results = []

def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" —— {detail}" if detail and not ok else ""))

def read(p):
    return Path(p).read_text(encoding="utf-8")

# ============ A. 版本一致性（含 MOD_VERSION 根因修复） ============
print("== A. 版本一致性 ==")
a1 = "version: '1.7.5'" in read(REPO / "server-Spigot/src/main/resources/plugin.yml")
check("plugin.yml = 1.7.5", a1)
a2 = 'version = "1.7.5"' in read(REPO / "server-NeoForge/src/main/resources/META-INF/mods.toml")
check("mods.toml = 1.7.5", a2)
a3 = 'version = "1.7.5"' in read(REPO / "server-NeoForge/src/main/resources/META-INF/neoforge.mods.toml")
check("neoforge.mods.toml = 1.7.5", a3)
a4 = 'version = "1.7.5"' in read(REPO / "server-NeoForge/build.gradle.kts")
check("NF build.gradle.kts = 1.7.5", a4)
nf_main = read(REPO / "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt")
a5 = 'private const val MOD_VERSION = "1.7.5"' in nf_main
check("MOD_VERSION 常量 = 1.7.5（更新检查根因修复）", a5)

# ============ B. 注释清理（零覆盖版 / 零版本新增标注） ============
print("== B. 注释与官网字段清理 ==")
b_bad = []
for sub in ("common-Bot/src", "server-Spigot/src", "server-NeoForge/src"):
    for p in (REPO / sub).rglob("*"):
        if p.suffix in (".kt", ".yml", ".toml"):
            t = p.read_text(encoding="utf-8", errors="replace")
            if "覆盖版" in t or "覆盖更新" in t:
                b_bad.append(f"{p.name}:覆盖版")
            for m in re.finditer(r"（(?:0|1)\.(?:[0-8])(?:\.\d+){0,3}\s*新增）", t):
                b_bad.append(f"{p.name}:（X新增）")
check("源码零「覆盖版/（X新增）」", not b_bad, "; ".join(b_bad[:5]))
web_index = read(REPO / "website/index.html")
b2 = "覆盖版" not in web_index and "PlaceholderAPI 为变量插件" not in web_index
check("官网 index 零覆盖版 + PlaceholderAPI 字段已删", b2)
b3 = "模组版</b>（MC 1.20.4，1.20.6，1.21.x，Java 21）" in web_index
check("模组版括号新文案", b3)

# ============ C. 新旧命令（/菜单 替换 /帮助；/AI管理 合并两条） ============
print("== C. 命令替换 ==")
pub = read(REPO / "common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/PublicCommands.kt")
c1 = '@Commands("菜单")' in pub and 'fun menu(' in pub
check("PublicCommands 存在 /菜单", c1)
c2 = '@Commands("帮助")' not in pub and "@Commands(\"AI对话上下文\")" not in pub and '@Commands("清除当前上下文")' not in pub
check("旧命令（帮助/AI对话上下文/清除当前上下文）已删", c2)
c3 = '@Commands("AI管理")' in pub and "ai-manage-disabled" in pub
check("/AI管理 存在且接 AI 未开启提示", c3)
c4 = '"AI上下文开启"' in pub and '"AI上下文关闭"' in pub and '"清除AI上下文"' in pub and 'actionData = "/AI管理 开"' in pub
check("/AI管理 三按钮（指令型）", c4)
c5 = 'actionType = 2' in pub and 'enter = true' in pub and 'enter = false' in pub
check("/菜单 按钮分直发型与填框型", c5)
mm = read(REPO / "common-Bot/src/main/kotlin/cn/kerong/bot/MenuManager.kt")
c6 = 'PanelItem("菜单"' in mm and 'PanelItem("AI管理"' in mm and "AI对话上下文" not in mm and "清除当前上下文" not in mm
check("快捷面板：菜单 + AI管理 按钮、旧 AI 按钮删", c6)

# ============ D. 按钮互动快速路径（第三方连接失败修复） ============
print("== D. 按钮互动快速路径 ==")
fp = REPO / "common-Bot/src/main/kotlin/cn/kerong/bot/events/InteractionFastPath.kt"
d1 = fp.is_file() and "interActionBase.response" in read(fp) and "INTERACTION_CREATE" in read(fp)
check("InteractionFastPath 存在且走互动回调接口", d1)
wd = read(REPO / "common-Bot/src/main/kotlin/cn/kerong/bot/WssDiagnostics.kt")
d2 = 'InteractionFastPath.tryHandle' in wd and "INTERACTION_CREATE" in wd
check("WssDiagnostics 帧层挂接快速路径", d2)
ub = read(REPO / "common-Bot/src/main/kotlin/cn/kerong/bot/events/UnblacklistButtons.kt")
d3 = "buildVertical" in ub and "style = 1" in ub
check("解除黑名单按钮竖排 + 蓝色", d3)
ps = read(REPO / "common-Bot/src/main/kotlin/cn/kerong/bot/events/PlayerSelection.kt")
d4 = "respond(4)" in ps and "respond(3)" in ps
check("回应码官方语义（4 没有权限 / 3 重复操作）", d4)
kb = read(REPO / "common-Bot/src/main/kotlin/cn/kerong/bot/tools/Keyboards.kt")
d5 = "buildVertical" in kb and "actionType" in kb and "setActionEnter" in kb
check("Keyboards 支持竖排 / 指令按钮 / enter", d5)

# ============ E. 配置迁移（config-version 41） ============
print("== E. 配置迁移 ==")
cm = read(REPO / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
e1 = "CURRENT_CONFIG_VERSION = 41" in cm
check("config-version 40 → 41", e1)
e2 = ('"commands.帮助" to "commands.菜单"' in cm and
      '"commands.AI对话上下文" to "commands.AI管理"' in cm and
      '"messages.help" to "messages.menu"' in cm)
check("键迁移表（帮助→菜单 / AI→AI管理 / help→menu）", e2)
e3 = '"commands.清除当前上下文"' in cm
check("清除当前上下文进废弃键清单", e3)
e4 = '"菜单"' in cm.split("COMMAND_NAMES")[1].split(")")[0] and '"AI管理"' in cm and '"帮助"' not in cm.split("COMMAND_NAMES")[1].split(")")[0]
check("COMMAND_NAMES 更新（菜单/AI管理，无旧名）", e4)
for cfgp in ("server-Spigot/src/main/resources/config.yml", "server-NeoForge/src/main/resources/config.yml"):
    cfg = read(REPO / cfgp)
    ok = ("  菜单: true" in cfg and "  AI管理: true" in cfg and
          "commands.帮助" not in cfg and "AI对话上下文: true" not in cfg and
          'menu: "menu.md"' in cfg and 'ai-manage-disabled: "ai-manage-disabled.md"' in cfg and
          'help: "help.md"' not in cfg)
    check(f"config.yml（{cfgp.split('/')[0]}）命令与模板段", ok)

# ============ F. 模板资源 ============
print("== F. 模板资源 ==")
for d in ("common-Bot/src/main/resources/Markdown", "server-NeoForge/src/main/resources/Markdown"):
    base = REPO / d
    f1 = (base / "menu.md").is_file() and "{version}" in read(base / "menu.md")
    f2 = (base / "ai-manage-disabled.md").is_file()
    f3 = not (base / "help.md").exists()
    f4 = "可以按下对应的按钮，或者艾特对方发送 /解除黑名单 解除" in read(base / "unblacklist-list.md")
    f5 = "{status}" in read(base / "ai-context-usage.md") and "/AI管理" in read(base / "ai-context-usage.md")
    check(f"{d.split('/')[0]} 模板（menu/ai-manage/无help/解除黑名单文案/ai-context-usage）",
          f1 and f2 and f3 and f4 and f5)

# ============ G. 官网三件 ============
print("== G. 官网三件 ==")
g1 = "版本 1.7.5" in web_index and "v1.7.5" in web_index and "KERONGPenguin_Spigot-1.7.5.jar" in web_index
check("index.html v1.7.5 块与下载链接", g1)
g2 = web_index.count("NeoForge 1.7.5") >= 1 and "NeoForge 1.7.4.1" in web_index
check("index.html 历史版本表新增 1.7.5 且保留旧版", g2)
tut = read(REPO / "website/tutorial.html")
g3 = "<code>/菜单</code>" in tut and "<code>/AI管理 开|关|清除</code>" in tut
check("tutorial 命令表（/菜单 与 /AI管理）", g3)
g4 = "/帮助</code>" not in tut and "<code>/AI对话上下文" not in tut and "<code>/清除当前上下文" not in tut
check("tutorial 旧命令引用清零", g4)
vj = read(REPO / "website/version.json")
g5 = '"version": "1.7.5"' in vj and '"changelog"' in vj
check("version.json 1.7.5", g5)
g6 = not re.search(r'"(?:1\.7\.4(?:\.1)?|1\.7\.3(?:\.1)?|1\.7\.2|1\.7\.1|1\.7\.0)：', vj)
check("version.json changelog 无历史版本条目", g6)

# ============ H. JAR 内容 ============
print("== H. JAR 内容 ==")
check("Spigot JAR 存在", SPIGOT_JAR.is_file())
check("NeoForge JAR 存在", NF_JAR.is_file())
with zipfile.ZipFile(SPIGOT_JAR) as z:
    names = z.namelist()
    cfg = z.read("config.yml").decode("utf-8")
    h1 = re.search(r"^config-version: 41$", cfg, re.M) is not None
    check("Spigot JAR config-version 41", h1)
    h2 = "  菜单: true" in cfg and "  AI管理: true" in cfg and "AI对话上下文" not in cfg
    check("Spigot JAR commands 段新键", h2)
    h3 = 'menu: "menu.md"' in cfg and 'ai-manage-disabled: "ai-manage-disabled.md"' in cfg
    check("Spigot JAR messages 段新键", h3)
    h4 = "Markdown/menu.md" in names and "Markdown/ai-manage-disabled.md" in names and "Markdown/help.md" not in names
    check("Spigot JAR 模板（menu 在位 / help 不在）", h4)
    h5 = "cn/kerong/bot/events/InteractionFastPath.class" in names
    check("Spigot JAR 含 InteractionFastPath", h5)
    unban = z.read("Markdown/unblacklist-list.md").decode("utf-8")
    h6 = "可以按下对应的按钮，或者艾特对方发送 /解除黑名单 解除" in unban
    check("Spigot JAR 解除黑名单新文案", h6)
    # server-notify 回归（四段文案不受影响；YAML 解析核对）
    import yaml
    root = yaml.safe_load(cfg)
    sn = root.get("server-notify", {})
    h7 = (sn.get("restart-window") == 180 and
          sn.get("shutdown", {}).get("message") == "[状态]\n服务器已正常关闭" and
          sn.get("restart", {}).get("message") == "[状态]\n服务器重启完毕，已正常运行(o‿∩)" and
          sn.get("crash", {}).get("message") == "[状态]\n服务器....")
    check("Spigot JAR server-notify 回归不变", h7)
    dirs = sum(1 for n in names if n.endswith("/"))
    check("Spigot JAR 目录条目 776", dirs == 776, f"实际 {dirs}")
with zipfile.ZipFile(NF_JAR) as z:
    nn = z.namelist()
    ncfg = z.read("config.yml").decode("utf-8")
    i1 = re.search(r"^config-version: 41$", ncfg, re.M) is not None
    check("NF JAR config-version 41", i1)
    i2 = "cn/kerong/bot/events/InteractionFastPath.class" in nn
    check("NF JAR 含 InteractionFastPath", i2)
    nftoml = z.read("META-INF/neoforge.mods.toml").decode("utf-8")
    i3 = 'version = "1.7.5"' in nftoml
    check("NF JAR mods.toml 1.7.5", i3)

# MOD_VERSION 字节码核对（javap）
import subprocess
javap = "/home/z/my-project/tooling/jdk21/bin/javap"
r = subprocess.run([javap, "-cp", str(NF_JAR), "-p", "-constants",
                    "cn.kerong.penguin.neoforge.KERONGNeoForge"],
                   capture_output=True, text=True, timeout=60)
i4 = 'MOD_VERSION = "1.7.5"' in r.stdout
check("NF JAR 字节码 MOD_VERSION = 1.7.5（更新检查根因）", i4)

# ============ 汇总 ============
fails = [r for r in results if not r[1]]
print(f"\n共 {len(results)} 项，通过 {len(results) - len(fails)}，失败 {len(fails)}")
if fails:
    for name, _, detail in fails:
        print(f"  FAIL: {name} {detail}")
    sys.exit(1)
print("VERIFY_175_OK")
