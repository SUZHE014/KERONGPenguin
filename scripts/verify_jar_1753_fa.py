#!/usr/bin/env python3
"""verify_jar_1753_fa：fa 版 1.7.5.3 门禁（随主线 1.7.5.3：按钮平台门禁 +
快捷面板管理员联动 + 配置版本 45 + intermediary 重映射正确性）。"""
import json
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
FA = REPO / "build" / "dist" / "KERONGPenguin_Fabric-1.20.4-1.7.5.3.jar"
NF = REPO / "build" / "dist" / "KERONGPenguin_NeoForge-1.20.4-1.7.5.3.jar"

checks = []


def check(name, ok, detail=""):
    checks.append((name, ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if not ok and detail else ""))


def zn(z, name):
    return name in z.namelist()


def zread(z, name):
    return z.read(name)


def has_str(z, cls, s):
    try:
        return s.encode() in zread(z, f"{cls}.class")
    except KeyError:
        return False


def main():
    print("== A. 产物与元数据 ==")
    check("A1 fa JAR 存在", FA.is_file())
    z = zipfile.ZipFile(FA)

    fmj = json.loads(zread(z, "fabric.mod.json").decode())
    check("A2 fabric.mod.json 版本 1.7.5.3", fmj["version"] == "1.7.5.3")
    check("A3 fabric.mod.json 入口 KERONGFabric",
          fmj["entrypoints"]["server"] == ["cn.kerong.penguin.fabric.KERONGFabric"])
    check("A4 depends minecraft >=1.20.4 / fabricloader >=0.15.0 / fabric-api *",
          fmj["depends"].get("minecraft") == ">=1.20.4"
          and fmj["depends"].get("fabricloader") == ">=0.15.0"
          and fmj["depends"].get("fabric-api") == "*")

    print("== B. 随主线 1.7.5.3 内核对齐 ==")
    cfg = zread(z, "config.yml").decode()
    check("B1 config-version 45", "config-version: 45" in cfg)
    check("B2 新键 admin.panel-admin-linkage 默认开", "panel-admin-linkage: true" in cfg)
    check("B3 kick-message 绑定码口径默认",
          '"&e你的专属绑定码: &a&l{code}"' in cfg)
    check("B4 menu.md 模板就位",
          zn(z, "Markdown/menu.md") and "**当前插件版本：v{version}**" in zread(z, "Markdown/menu.md").decode())
    nf = zipfile.ZipFile(NF)
    nf_cfg = zread(nf, "config.yml").decode()
    check("B5 config.yml 与 NeoForge 版一致（除平台差异）", cfg == nf_cfg)

    print("== C. 按钮平台门禁 + 面板联动随行（1.7.5.2 覆盖 + 1.7.5.3） ==")
    MB = "cn/kerong/bot/events/MenuButtons"
    MM = "cn/kerong/bot/MenuManager"
    check("C1 平台门禁回调前缀 krg:acmd:", has_str(z, MB, "krg:acmd:"))
    check("C2 门禁模式判定 adminGateFor", has_str(z, MB, "adminGateFor"))
    check("C3 GroupMemberLookup 不存在（覆盖版去接口）",
          not zn(z, "cn/kerong/bot/events/GroupMemberLookup.class"))
    check("C4 MemberRoleCache 不存在", not zn(z, "cn/kerong/bot/events/MemberRoleCache.class"))
    check("C5 注册表 adminOnly 标记",
          has_str(z, "cn/kerong/bot/events/commands/BaseCommand", "CommandAdminRegistry"))
    check("C6 Button 权限类型字段 permissionType",
          has_str(z, "cn/kerong/bot/tools/Keyboards$Button", "permissionType"))
    check("C7 面板条目 only_admin 联动", has_str(z, MM, "only_admin"))
    check("C8 联动开关判定 adminLinkageItems", has_str(z, MM, "adminLinkageItems"))
    check("C9 配置接口 panelAdminLinkageEnabled",
          has_str(z, "cn/kerong/penguin/spigot/manager/ConfigManager", "panel-admin-linkage"))
    check("C10 ConnectionWarmer 保活随行", zn(z, "cn/kerong/bot/ConnectionWarmer.class"))

    print("== D. intermediary 重映射正确性 ==")
    import re as _re
    # 模组自身产物重映射为 intermediary；cn/kerong 插件类不重映射
    check("D1 插件类保持原包名（cn/kerong/penguin/fabric/KERONGFabric）",
          zn(z, "cn/kerong/penguin/fabric/KERONGFabric.class"))
    check("D2 无裸 mojang net/minecraft 类（已重映射或未内联）",
          not any(n.startswith("net/minecraft/server/") for n in z.namelist()))
    # 抽检 fabric 事件层引用：intermediary 名 net.minecraft.class_XXXX
    fe = zread(z, "cn/kerong/penguin/fabric/FabricEvents.class")
    refs = {r.decode() for r in _re.findall(rb"net/minecraft/[a-zA-Z0-9_$]+", fe)}
    check("D3 事件层引用 intermediary 名（class_ 系）",
          any(_re.fullmatch(r"net/minecraft/class_\d+", s) for s in refs)
          or any("server/MinecraftServer" in s for s in refs),
          str(sorted(refs)[:5]))
    check("D4 无 mojmap 专属名泄漏", "net/minecraft/server/level/ServerPlayer" not in refs)

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
