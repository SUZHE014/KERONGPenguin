#!/usr/bin/env python3
"""verify_jar_1751o2：1.7.5.1 覆盖版门禁（menu 系统模板强制重置 + 官网 NeoForge 括号手机端折行）。

双 JAR（Spigot / NeoForge）+ 官网文件级断言。"""
import re
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
SPIGOT = REPO / "build" / "dist" / "KERONGPenguin_Spigot-1.7.5.1.jar"
NEOFORGE = REPO / "build" / "dist" / "KERONGPenguin_NeoForge-1.20.4-1.7.5.1.jar"

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


def method_in(jar, cls, method):
    """class 常量池含方法名（粗断言：UTF8 常量出现）。"""
    return has_str(jar, cls, method)


def main():
    print("== A. 产物与版本 ==")
    check("A1 双 JAR 存在", SPIGOT.is_file() and NEOFORGE.is_file())
    plugin_yml = class_bytes(SPIGOT, "plugin.yml").decode()
    check("A2 Spigot plugin.yml 版本 1.7.5.1", "version: '1.7.5.1'" in plugin_yml)
    mods_toml = class_bytes(NEOFORGE, "META-INF/mods.toml").decode()
    nf_toml = class_bytes(NEOFORGE, "META-INF/neoforge.mods.toml").decode()
    check("A3 NeoForge mods.toml 版本 1.7.5.1",
          'version = "1.7.5.1"' in mods_toml and 'version = "1.7.5.1"' in nf_toml)

    print("== B. menu 系统模板强制重置（本轮核心） ==")
    MT = "cn/kerong/penguin/spigot/qqbind/MessageTemplates"
    for tag, jar in (("Spigot", SPIGOT), ("NeoForge", NEOFORGE)):
        check(f"B1 {tag} resetSystemTemplates 方法", method_in(jar, MT, "resetSystemTemplates"))
        check(f"B2 {tag} SYSTEM_TEMPLATE_IDS 字段", has_str(jar, MT, "SYSTEM_TEMPLATE_IDS"))
        check(f"B3 {tag} 升级表 menu 条目已移除（无「### 指令菜单」常量）",
              not has_str(jar, MT, "### 指令菜单"))
        check(f"B4 {tag} .bak 备份路径字符串", has_str(jar, MT, ".bak"))
        check(f"B5 {tag} bundledResource 仍用于读默认", method_in(jar, MT, "bundledResource"))
        check(f"B6 {tag} 其余升级条目保留（黑名单特征）",
              has_str(jar, MT, "黑名单列表（{count} 个）"))
        check(f"B7 {tag} normalize/features 机制保留",
              b"\\s" in class_bytes(jar, f"{MT}.class"))
        menu_md = class_bytes(jar, "Markdown/menu.md").decode()
        check(f"B8 {tag} menu.md 只剩标题+版本行",
              "### 指令菜单（{{page}}/{{pages}}）".replace("{{", "{").replace("}}", "}") in menu_md
              and "**当前插件版本：v{version}**" in menu_md
              and "点击下方按钮" not in menu_md and "帮助" not in menu_md)

    print("== C. 1.7.5.1 首轮修复不回归 ==")
    for tag, jar in (("Spigot", SPIGOT), ("NeoForge", NEOFORGE)):
        check(f"C1 {tag} RecentGroupMessages 环形缓冲（16）",
              has_str(jar, "cn/kerong/bot/events/RecentGroupMessages", "nextId")
              and class_bytes(jar, "cn/kerong/bot/events/RecentGroupMessages.class") is not None)
        check(f"C2 {tag} MemberRoleCache", has_str(jar, "cn/kerong/bot/events/MemberRoleCache", "roleOf"))
        check(f"C3 {tag} InteractionAcker PUT", b"PUT" in class_bytes(jar, "cn/kerong/bot/events/InteractionAcker.class"))
        check(f"C4 {tag} ReplySeqGuard allocateBase",
              has_str(jar, "cn/kerong/bot/ReplySeqGuard", "allocateBase"))
        check(f"C5 {tag} QClient 主动通道重发",
              has_str(jar, "cn/kerong/bot/QClient", "resendActively"))
        check(f"C6 {tag} MenuButtons 回调前缀",
              has_str(jar, "cn/kerong/bot/events/MenuButtons", "krg:cmd:")
              and has_str(jar, "cn/kerong/bot/events/MenuButtons", "krg:menu:page:"))
    cfg_s = class_bytes(SPIGOT, "config.yml").decode()
    cfg_n = class_bytes(NEOFORGE, "config.yml").decode()
    check("C7 双端 config-version 43", "config-version: 43" in cfg_s and "config-version: 43" in cfg_n)
    check("C8 双端 commands.菜单（无 帮助 键）",
          "菜单: true" in cfg_s and "帮助: true" not in cfg_s
          and "菜单: true" in cfg_n and "帮助: true" not in cfg_n)
    check("C9 双端 features.full-amount 总开关",
          "full-amount" in cfg_s and "full-amount" in cfg_n)

    print("== D. 官网 NeoForge 括号手机端折行（文件级） ==")
    idx = (REPO / "website" / "index.html").read_text(encoding="utf-8")
    css = (REPO / "website" / "style.css").read_text(encoding="utf-8")
    check("D1 NeoForge 芯片 core-name/core-note 结构",
          '<span class="core-name">NeoForge</span>' in idx
          and '<span class="core-note">（MC 1.20.4，1.20.6，1.21.x，Java 21）</span>' in idx)
    check("D2 括号版本文本完整保留", "NeoForge</span><span class=\"core-note\">（MC 1.20.4，1.20.6，1.21.x，Java 21）" in idx.replace("\n", ""))
    check("D3 .core 允许折行（flex-wrap）", "flex-wrap:wrap" in css and "max-width:100%" in css)
    check("D4 .core-note 可换行断词",
          ".core .core-note{white-space:normal;overflow-wrap:anywhere" in css)
    check("D5 nowrap 不再整体锁死芯片", ".core{display:inline-flex;align-items:center;white-space:nowrap" not in css)
    check("D6 其他芯片结构未动（Spigot/Paper/Purpur/Folia 芯片仍在）",
          all(f'>{n}</span>' in idx for n in ("Spigot", "Paper", "Purpur", "Folia")))

    failed = [n for n, ok in checks if not ok]
    print(f"\n共 {len(checks)} 项，通过 {len(checks) - len(failed)}，失败 {len(failed)}")
    for n in failed:
        print("  FAIL:", n)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
