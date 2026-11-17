#!/usr/bin/env python3
"""verify_jar_1753：1.7.5.3 门禁（QQ 快捷面板管理员联动 + 配置版本 45）。

双 JAR（Spigot / NeoForge）字节码级断言。"""
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
SPIGOT = REPO / "build" / "dist" / "KERONGPenguin_Spigot-1.7.5.3.jar"
NEOFORGE = REPO / "build" / "dist" / "KERONGPenguin_NeoForge-1.20.4-1.7.5.3.jar"

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


def main():
    print("== A. 产物与版本 ==")
    check("A1 双 JAR 存在", SPIGOT.is_file() and NEOFORGE.is_file())
    plugin_yml = class_bytes(SPIGOT, "plugin.yml").decode()
    check("A2 Spigot plugin.yml 版本 1.7.5.3", "version: '1.7.5.3'" in plugin_yml)
    mods_toml = class_bytes(NEOFORGE, "META-INF/mods.toml").decode()
    nf_toml = class_bytes(NEOFORGE, "META-INF/neoforge.mods.toml").decode()
    check("A3 NeoForge mods.toml 双声明版本 1.7.5.3",
          'version = "1.7.5.3"' in mods_toml and 'version = "1.7.5.3"' in nf_toml)
    check("A4 NeoForge MOD_VERSION 1.7.5.3",
          has_str(NEOFORGE, "cn/kerong/penguin/neoforge/KERONGNeoForge", "1.7.5.3"))

    print("== B. 快捷面板管理员联动（1.7.5.3 本体） ==")
    MM = "cn/kerong/bot/MenuManager"
    for tag, jar in (("Spigot", SPIGOT), ("NeoForge", NEOFORGE)):
        check(f"B1 {tag} 面板条目 only_admin 字段下发", has_str(jar, MM, "only_admin"))
        check(f"B2 {tag} 联动开关判定（adminLinkageItems）", has_str(jar, MM, "adminLinkageItems"))
        check(f"B3 {tag} 联动生效日志文案", has_str(jar, MM, "管理员联动已生效"))
        check(f"B4 {tag} 配置接口 panelAdminLinkageEnabled",
              has_str(jar, "cn/kerong/bot/provider/ConfigProvider", "panelAdminLinkageEnabled")
              and has_str(jar, "cn/kerong/penguin/spigot/manager/ConfigManager", "panel-admin-linkage"))
        check(f"B5 {tag} 面板联动走注册表标记",
              has_str(jar, MM, "CommandAdminRegistry"))
        cfg = class_bytes(jar, "config.yml").decode()
        check(f"B6 {tag} config.yml 新键 admin.panel-admin-linkage 默认开",
              "panel-admin-linkage: true" in cfg)
        check(f"B7 {tag} config.yml 配置版本 45", "config-version: 45" in cfg)
        check(f"B8 {tag} ConfigManager CURRENT_CONFIG_VERSION 45",
              has_str(jar, "cn/kerong/penguin/spigot/manager/ConfigManager", "45"))

    print("== C. 1.7.5.2 覆盖版门禁随行（回归抽检） ==")
    MB = "cn/kerong/bot/events/MenuButtons"
    for tag, jar in (("Spigot", SPIGOT), ("NeoForge", NEOFORGE)):
        check(f"C1 {tag} 平台门禁回调前缀 krg:acmd:", has_str(jar, MB, "krg:acmd:"))
        check(f"C2 {tag} 门禁模式判定 adminGateFor", has_str(jar, MB, "adminGateFor"))
        check(f"C3 {tag} GroupMemberLookup 保持已删除",
              not entry_exists(jar, "cn/kerong/bot/events/GroupMemberLookup.class"))
        check(f"C4 {tag} MemberRoleCache 保持已删除",
              not entry_exists(jar, "cn/kerong/bot/events/MemberRoleCache.class"))
        check(f"C5 {tag} 注册表 adminOnly 标记",
              has_str(jar, "cn/kerong/bot/events/commands/BaseCommand", "CommandAdminRegistry"))
        check(f"C6 {tag} Button 权限类型字段",
              has_str(jar, "cn/kerong/bot/tools/Keyboards$Button", "permissionType"))

    print("== D. 门禁脚本与配置迁移说明 ==")
    check("D1 门禁脚本 verify_jar_1752 D 段已改覆盖版口径",
          "平台门禁" in (REPO / "scripts" / "verify_jar_1752.py").read_text(encoding="utf-8"))

    print()
    failed = [name for name, ok in checks if not ok]
    print(f"共 {len(checks)} 项，通过 {len(checks) - len(failed)}，失败 {len(failed)}")
    if failed:
        print("失败项：")
        for name in failed:
            print(f"  - {name}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
