#!/usr/bin/env python3
"""verify_jar_1752：1.7.5.2 门禁（菜单模板去强制更新 + 回应连接复用保活 +
按钮管理员实时判定 + 绑定文案/白名单检测/单行配置）。

双 JAR（Spigot / NeoForge）字节码级断言。"""
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
SPIGOT = REPO / "build" / "dist" / "KERONGPenguin_Spigot-1.7.5.2.jar"
NEOFORGE = REPO / "build" / "dist" / "KERONGPenguin_NeoForge-1.20.4-1.7.5.2.jar"

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


def entry_exists(jar, name):
    with zipfile.ZipFile(jar) as z:
        return name in z.namelist()


def scan_all_kerong(jar, s):
    """全部 cn/kerong 类字节码含指定字符串的类清单。"""
    hits = []
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if n.startswith("cn/kerong/") and n.endswith(".class") and s.encode() in z.read(n):
                hits.append(n)
    return hits


def main():
    print("== A. 产物与版本 ==")
    check("A1 双 JAR 存在", SPIGOT.is_file() and NEOFORGE.is_file())
    plugin_yml = class_bytes(SPIGOT, "plugin.yml").decode()
    check("A2 Spigot plugin.yml 版本 1.7.5.2", "version: '1.7.5.2'" in plugin_yml)
    mods_toml = class_bytes(NEOFORGE, "META-INF/mods.toml").decode()
    nf_toml = class_bytes(NEOFORGE, "META-INF/neoforge.mods.toml").decode()
    check("A3 NeoForge mods.toml 双声明版本 1.7.5.2",
          'version = "1.7.5.2"' in mods_toml and 'version = "1.7.5.2"' in nf_toml)
    check("A4 NeoForge MOD_VERSION 1.7.5.2",
          has_str(NEOFORGE, "cn/kerong/penguin/neoforge/KERONGNeoForge", "1.7.5.2"))

    print("== B. 菜单模板去强制更新（需求 1） ==")
    MT = "cn/kerong/penguin/spigot/qqbind/MessageTemplates"
    for tag, jar in (("Spigot", SPIGOT), ("NeoForge", NEOFORGE)):
        check(f"B1 {tag} resetSystemTemplates 已移除", not has_str(jar, MT, "resetSystemTemplates"))
        check(f"B2 {tag} SYSTEM_TEMPLATE_IDS 已移除", not has_str(jar, MT, "SYSTEM_TEMPLATE_IDS"))
        check(f"B3 {tag} .bak 备份路径已移除", not has_str(jar, MT, ".bak"))
        check(f"B4 {tag} menu 回升级表（新默认常量）",
              has_str(jar, MT, "### 指令菜单（{page}/{pages}）"))
        check(f"B5 {tag} menu 旧默认 1.7.5 形态收录（点击下方按钮即可执行）",
              has_str(jar, MT, "点击下方按钮即可执行"))
        check(f"B6 {tag} normalize 容忍 BOM（\\uFEFF）",
              b"\\uFEFF" in class_bytes(jar, f"{MT}.class") or "﻿".encode() in class_bytes(jar, f"{MT}.class"))
        check(f"B7 {tag} 其余升级条目保留（黑名单特征）",
              has_str(jar, MT, "黑名单列表（{count} 个）"))
        menu_md = class_bytes(jar, "Markdown/menu.md").decode()
        check(f"B8 {tag} menu.md 当前默认（标题+版本行）",
              "### 指令菜单（{page}/{pages}）" in menu_md
              and "**当前插件版本：v{version}**" in menu_md
              and "帮助" not in menu_md)

    print("== C. 回应连接复用 + 保活（需求 2） ==")
    for tag, jar in (("Spigot", SPIGOT), ("NeoForge", NEOFORGE)):
        check(f"C1 {tag} ConnectionWarmer 类存在", entry_exists(jar, "cn/kerong/bot/ConnectionWarmer.class"))
        cw = "cn/kerong/bot/ConnectionWarmer"
        check(f"C2 {tag} 保活线程名 qq-connection-warmer", has_str(jar, cw, "qq-connection-warmer"))
        check(f"C3 {tag} HEAD 保活方法", has_str(jar, cw, "HEAD"))
        check(f"C4 {tag} 保活失败日志文案", has_str(jar, cw, "[连接保活] 保活请求失败"))
        ack = "cn/kerong/bot/events/InteractionAcker"
        check(f"C5 {tag} InteractionAcker 响应体读尽方法 drainBody",
              has_str(jar, ack, "drainBody"))
        check(f"C6 {tag} QClient launchClient 启动保活接线",
              has_str(jar, "cn/kerong/bot/QClient", "ConnectionWarmer"))

    print("== D. 按钮管理员实时判定（需求 3+7） ==")
    for tag, jar in (("Spigot", SPIGOT), ("NeoForge", NEOFORGE)):
        check(f"D1 {tag} GroupMemberLookup 类存在", entry_exists(jar, "cn/kerong/bot/events/GroupMemberLookup.class"))
        gm = "cn/kerong/bot/events/GroupMemberLookup"
        check(f"D2 {tag} 官方群成员接口端点", has_str(jar, gm, "/v2/groups/")
              and has_str(jar, gm, "/members/"))
        check(f"D3 {tag} 11253 无权限提示文案", has_str(jar, gm, "11253")
              and has_str(jar, gm, "机器人无群成员信息查询权限"))
        check(f"D4 {tag} MemberRoleCache 已删除",
              not entry_exists(jar, "cn/kerong/bot/events/MemberRoleCache.class"))
        leftovers = scan_all_kerong(jar, "MemberRoleCache")
        check(f"D5 {tag} 全部类零 MemberRoleCache 残留引用", not leftovers, str(leftovers))
        check(f"D6 {tag} MenuButtons 走实时查询",
              has_str(jar, "cn/kerong/bot/events/MenuButtons", "GroupMemberLookup")
              and has_str(jar, "cn/kerong/bot/events/MenuButtons", "fetchRole"))
        check(f"D7 {tag} 分发日志保留角色字段", has_str(jar, "cn/kerong/bot/events/MenuButtons", "角色="))

    print("== E. 绑定文案 + 白名单检测 + 单行配置（需求 4/5/6） ==")
    QM = "cn/kerong/penguin/spigot/qqbind/QqBindManager"
    for tag, jar in (("Spigot", SPIGOT), ("NeoForge", NEOFORGE)):
        check(f"E1 {tag} 默认绑定码口径文案",
              has_str(jar, QM, "你的专属绑定码: &a&l{code}")
              and has_str(jar, QM, "请在 QQ 群发送: &f{code}")
              and has_str(jar, QM, "（绑定码 {expire} 分钟内有效）"))
        check(f"E2 {tag} 旧验证码默认文案已移除",
              not has_str(jar, QM, "可以直接发送纯数字验证码")
              and not has_str(jar, QM, "你的专属验证码"))
        check(f"E3 {tag} KERONG 白名单检测三方法",
              has_str(jar, "cn/kerong/bot/KERONG", "isNativeWhitelistEnabled")
              and has_str(jar, "cn/kerong/bot/KERONG", "disableNativeWhitelist")
              and has_str(jar, "cn/kerong/bot/KERONG", "enforceNoNativeWhitelist"))
        check(f"E4 {tag} 白名单控制台提醒文案",
              has_str(jar, "cn/kerong/bot/KERONG", "检测到服务器自带白名单"))
    # 平台专属
    check("E5 Spigot 白名单读写接线",
          has_str(SPIGOT, "cn/kerong/penguin/spigot/KERONGSpigot", "hasWhitelist")
          and has_str(SPIGOT, "cn/kerong/penguin/spigot/KERONGSpigot", "setWhitelist")
          and has_str(SPIGOT, "cn/kerong/penguin/spigot/KERONGSpigot", "enforceNoNativeWhitelist"))
    check("E6 NeoForge 白名单反射双名（1.20.4 / 1.20.6+）",
          has_str(NEOFORGE, "cn/kerong/penguin/neoforge/KERONGNeoForge", "isUsingWhiteList")
          and has_str(NEOFORGE, "cn/kerong/penguin/neoforge/KERONGNeoForge", "isUsingWhitelist")
          and has_str(NEOFORGE, "cn/kerong/penguin/neoforge/KERONGNeoForge", "enforceNoNativeWhitelist"))
    for tag, jar in (("Spigot", SPIGOT), ("NeoForge", NEOFORGE)):
        cfg = class_bytes(jar, "config.yml").decode()
        check(f"E7 {tag} config.yml 版本 44", "config-version: 44" in cfg)
        check(f"E8 {tag} config.yml 新默认 kick-message",
              '"&e你的专属绑定码: &a&l{code}"' in cfg
              and '"&7请在 QQ 群发送: &f{code}"' in cfg)
        check(f"E9 {tag} config.yml 单行写法注释",
              "两种写法" in cfg and "\\n" in cfg)
        check(f"E10 {tag} config.yml 旧验证码默认已移除",
              "可以直接发送纯数字验证码" not in cfg and "你的专属验证码" not in cfg)

    print("== F. 迁移与结构基线 ==")
    for tag, jar in (("Spigot", SPIGOT), ("NeoForge", NEOFORGE)):
        check(f"F1 {tag} ConfigManager kick-message 迁移（绑定码口径收敛）",
              has_str(jar, "cn/kerong/penguin/spigot/manager/ConfigManager", "绑定码口径")
              and has_str(jar, "cn/kerong/penguin/spigot/manager/ConfigManager", "qq-bind.kick-message"))
        check(f"F2 {tag} 重建器同款收敛",
              has_str(jar, "cn/kerong/penguin/spigot/manager/ConfigFileRebuilder", "qq-bind.kick-message"))
    with zipfile.ZipFile(SPIGOT) as z:
        dirs = sum(1 for n in z.namelist() if n.endswith("/"))
    check("F3 Spigot 目录条目保留（kloping 组件扫描）", dirs >= 700, f"dirs={dirs}")
    with zipfile.ZipFile(NEOFORGE) as z:
        names = z.namelist()
    check("F4 NeoForge 重定位在位", any(n.startswith("cn/kerong/shaded/kotlin/") for n in names))
    check("F5 NeoForge 无裸 kotlin 顶层类",
          not any(n.startswith("kotlin/") and n.endswith(".class") for n in names))

    failed = [name for name, ok in checks if not ok]
    print(f"\n共 {len(checks)} 项，通过 {len(checks) - len(failed)}，失败 {len(failed)}")
    if failed:
        print("失败项：")
        for name in failed:
            print("  -", name)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
