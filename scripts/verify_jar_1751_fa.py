#!/usr/bin/env python3
"""verify_jar_1751_fa：fa 版 1.7.5.1 门禁（内核对齐主线 + 绑定不拦截 + /hbkrg qbd
+ 更新检查 + 移主线构建 + intermediary 重映射正确性 + 结构与 1.7.4.1 一致性）。"""
import json
import re
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
FA = REPO / "build" / "dist" / "KERONGPenguin_Fabric-1.20.4-1.7.5.1.jar"
NF = REPO / "build" / "dist" / "KERONGPenguin_NeoForge-1.20.4-1.7.5.1.jar"
OLD_FA = Path("/tmp/fa1741.jar")

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
    check("A2 fabric.mod.json 版本 1.7.5.1", fmj["version"] == "1.7.5.1")
    check("A3 fabric.mod.json 入口 KERONGFabric",
          fmj["entrypoints"]["server"] == ["cn.kerong.penguin.fabric.KERONGFabric"])
    check("A4 depends minecraft >=1.20.4 / fabricloader >=0.15.0 / fabric-api *",
          fmj["depends"].get("minecraft") == ">=1.20.4"
          and fmj["depends"].get("fabricloader") == ">=0.15.0"
          and fmj["depends"].get("fabric-api") == "*")
    check("A5 描述去掉「测试版」定位", "测试版" not in fmj["description"])

    print("== B. 内核对齐主线 1.7.5.1 ==")
    cfg = zread(z, "config.yml").decode()
    check("B1 config-version 43", "config-version: 43" in cfg)
    check("B2 commands.菜单（/帮助 键不存在）", "菜单: true" in cfg and "帮助: true" not in cfg)
    check("B3 commands.AI管理（旧 AI 两键不存在）",
          "AI管理: true" in cfg and "AI对话上下文: true" not in cfg and "清除当前上下文: true" not in cfg)
    check("B4 messages.menu 新键（help 键不存在）",
          'menu: "menu.md"' in cfg and 'help: "help.md"' not in cfg)
    check("B5 features.full-amount 总开关段", "full-amount" in cfg)
    check("B6 update-check 段", "update-check:" in cfg and "enabled: true" in cfg)
    check("B7 menu.md 模板就位（标题+版本行）",
          zn(z, "Markdown/menu.md") and "点击下方按钮" not in zread(z, "Markdown/menu.md").decode()
          and "**当前插件版本：v{version}**" in zread(z, "Markdown/menu.md").decode())
    check("B8 help.md 已删除", not zn(z, "Markdown/help.md"))
    nf = zipfile.ZipFile(NF)
    nf_cfg = zread(nf, "config.yml").decode()
    check("B9 config.yml 与 NeoForge 版一致（除平台差异）", cfg == nf_cfg)
    nf_md = set(n for n in nf.namelist() if n.startswith("Markdown/"))
    fa_md = set(n for n in z.namelist() if n.startswith("Markdown/"))
    check("B10 Markdown 模板目录与 NeoForge 完全一致", nf_md == fa_md,
          f"diff={nf_md ^ fa_md}")

    print("== C. QQ 绑定拦截移除 + /hbkrg qbd ==")
    check("C1 enforceLoginBinding 踢出路径已删（FabricEvents 无此方法）",
          not has_str(z, "cn/kerong/penguin/fabric/FabricEvents", "enforceLoginBinding"))
    check("C2 进服仅建档（registerPlayerProfile）",
          has_str(z, "cn/kerong/penguin/fabric/FabricEvents", "registerPlayerProfile"))
    check("C3 踢出消息 formatKickMessage 不再被 fa 引用",
          not has_str(z, "cn/kerong/penguin/fabric/FabricEvents", "formatKickMessage")
          and not has_str(z, "cn/kerong/penguin/fabric/FabricCommands", "formatKickMessage"))
    fc = zread(z, "cn/kerong/penguin/fabric/FabricCommands.class")
    check("C4 /hbkrg qbd 子命令注册（handleQbd）",
          has_str(z, "cn/kerong/penguin/fabric/FabricCommands", "handleQbd") and b"qbd" in fc)
    check("C5 qbd 输出引导 QQ 群 /绑定", "在 QQ 群发送".encode() in fc or "/绑定 ".encode() in fc)
    check("C6 已绑定玩家提示无需取码", "无需获取验证码".encode() in fc)
    check("C7 根节点不再整体锁 OP（requires 仅在管理子命令）",
          has_str(z, "cn/kerong/penguin/fabric/FabricCommands", "qbd"))
    check("C8 /qq skip / unskip 移除（handleSkip 不存在）",
          not has_str(z, "cn/kerong/penguin/fabric/FabricCommands", "handleSkip"))
    check("C9 /qq rebind / qxqq 保留",
          has_str(z, "cn/kerong/penguin/fabric/FabricCommands", "handleSelfRebind")
          and has_str(z, "cn/kerong/penguin/fabric/FabricCommands", "handleForceRebind"))
    check("C10 rebind 消息指向 /hbkrg qbd（不再「下次登录获取」）",
          "下次登录将重新获取绑定码".encode() not in fc and "hbkrg qbd".encode() in fc)

    print("== D. 更新检查（UpdateChecker 共用源码接线） ==")
    check("D1 UpdateChecker 类在", zn(z, "cn/kerong/bot/UpdateChecker.class"))
    check("D2 UpdateChecker 版本文件地址常量",
          has_str(z, "cn/kerong/bot/UpdateChecker", "https://kerong.xin/penguin/version.json"))
    check("D3 KERONG 调度更新检查（scheduleUpdateCheck）",
          has_str(z, "cn/kerong/bot/KERONG", "scheduleUpdateCheck"))
    check("D4 更新检查开关读取（update-check.enabled）",
          has_str(z, "cn/kerong/bot/UpdateChecker", "update-check.enabled"))

    print("== E. 主线 1.7.5.1 修复随行（共用源码） ==")
    check("E1 setFullAmountEnabled 已接线",
          has_str(z, "cn/kerong/penguin/fabric/KERONGFabric", "setFullAmountEnabled"))
    check("E2 menu 系统模板强制重置（resetSystemTemplates）",
          has_str(z, "cn/kerong/penguin/spigot/qqbind/MessageTemplates", "resetSystemTemplates"))
    check("E3 RecentGroupMessages 轮转（40034128）", zn(z, "cn/kerong/bot/events/RecentGroupMessages.class"))
    check("E4 MemberRoleCache（按钮管理员权限）", zn(z, "cn/kerong/bot/events/MemberRoleCache.class"))
    check("E5 MenuButtons 按钮菜单", zn(z, "cn/kerong/bot/events/MenuButtons.class"))

    print("== F. intermediary 重映射正确性（Fabric 运行时类名） ==")
    fe = zread(z, "cn/kerong/penguin/fabric/FabricEvents.class")
    refs = set(re.findall(rb"net/minecraft/[a-zA-Z0-9_$]+", fe))
    refs_str = [r.decode() for r in refs]
    check("F1 net.minecraft 引用为 intermediary 名（class_XXXX）",
          any(re.fullmatch(r"net/minecraft/class_\d+", s) for s in refs_str)
          or any("server/MinecraftServer" in s for s in refs_str),
          str(refs_str[:5]))
    check("F2 无 mojmap 专属名泄漏（server/level/ServerPlayer 不存在）",
          "net/minecraft/server/level/ServerPlayer" not in refs_str)
    check("F3 org.bukkit 兼容层类在（38 类与 1.7.4.1 一致）",
          sum(1 for n in z.namelist() if n.startswith("org/bukkit/") and n.endswith(".class")) == 38)
    if OLD_FA.is_file():
        zo = zipfile.ZipFile(OLD_FA)
        old_refs = set()
        for n in zo.namelist():
            if n.startswith("cn/kerong/penguin/fabric/") and n.endswith(".class"):
                old_refs |= set(re.findall(rb"net/minecraft/[a-zA-Z0-9_$]+", zo.read(n)))
        old_named = {r.decode() for r in old_refs}
        check("F4 与 1.7.4.1 同类引用形态一致（intermediary 集合无 mojmap 混入）",
              all(not re.search(r"/(level|network/chat|server/dedicated)/", s) for s in refs_str))

    print("== G. 结构与依赖合并 ==")
    names = set(z.namelist())
    top = {n.split("/")[0] for n in names if not n.endswith("/")}
    check("G1 第三方依赖并入（kotlin / okhttp3 / okio / com.alibaba）",
          "kotlin" in top and "okhttp3" in top and "okio" in top)
    check("G2 org/yaml（snakeyaml 运行时依赖）在", any(n.startswith("org/yaml/") for n in names))
    check("G3 plugin.yml（Spigot 专属）不在", "plugin.yml" not in names)
    check("G4 死库剔除（mchange / zaxxer 不在）",
          not any(n.startswith("com/mchange/") or n.startswith("com/zaxxer/") for n in names))
    check("G5 目录条目保留（SDK 组件扫描）", sum(1 for n in names if n.endswith("/")) > 500)
    check("G6 module-info / 签名文件剔除",
          not any(n == "module-info.class" or n.endswith("/module-info.class")
                  or re.match(r"META-INF/.*\.(SF|RSA|DSA)$", n) for n in names))
    check("G7 无重定位（kotlin 保持原包名，与 fa 1.7.4.1 一致）",
          any(n == "kotlin/jvm/internal/Intrinsics.class" for n in names)
          and not any(n.startswith("cn/kerong/shaded/kotlin/") for n in names))
    check("G8 背景与字体资源在",
          zn(z, "backgrounds/card-default.png") and zn(z, "fonts/NotoSansSC-Regular-subset.ttf"))

    failed = [n for n, ok in checks if not ok]
    print(f"\n共 {len(checks)} 项，通过 {len(checks) - len(failed)}，失败 {len(failed)}")
    for n in failed:
        print("  FAIL:", n)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
