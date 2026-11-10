#!/usr/bin/env python3
"""
门禁验证：1.7.5.1（六项修复：① 40034128 被动回复超限——RecentGroupMessages
环形缓冲轮转 + QClient 被动失败主动兜底；② 模板升级匹配放宽 normalize +
特征兜底；③ QQ 第三方连接失败概率——InteractionAcker 超时收紧 + 快速重试；
④ 全量切换后面板/配置即时重载；⑤ 按钮管理员权限——MemberRoleCache 补
member_role；⑥ 版本 1.7.5.1）。
对 build/dist 双端 JAR 与源码资源做字节级断言；基线为线上 1.7.5 第三轮版
（tooling/online-1.7.5.jar，无 MemberRoleCache / resendActively）。
"""
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPIGOT_JAR = ROOT / "build/dist/KERONGPenguin_Spigot-1.7.5.1.jar"
NF_JAR = ROOT / "build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.5.1.jar"
BASE_JAR = Path("/home/z/my-project/tooling/online-1.7.5.jar")
JAVAP = "/home/z/my-project/tooling/jdk21/bin/javap"

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))


def class_bytes(z, path):
    try:
        return z.read(path)
    except KeyError:
        return b""


def disasm(z, path, tag="v1751"):
    """提取 class 到临时目录并 javap -p -c 反汇编（失败返回空文本）。"""
    out_dir = Path(f"/tmp/verify-{tag}")
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / path.replace("/", "_")
    target.write_bytes(class_bytes(z, path))
    r = subprocess.run([JAVAP, "-p", "-c", str(target)], capture_output=True, text=True)
    return r.stdout


def has_str(z, path, s):
    """class 常量池含指定字符串（中文 BMP 字符 modified-UTF8 与标准 UTF-8 同码）。"""
    return s.encode("utf-8") in class_bytes(z, path)


def main():
    assert SPIGOT_JAR.is_file(), f"缺少 Spigot JAR: {SPIGOT_JAR}"
    assert NF_JAR.is_file(), f"缺少 NeoForge JAR: {NF_JAR}"
    assert BASE_JAR.is_file(), f"缺少基线 JAR: {BASE_JAR}"
    zs = zipfile.ZipFile(SPIGOT_JAR)
    zn = zipfile.ZipFile(NF_JAR)

    # ============ A. 40034128 被动回复超限——消息 id 轮转 + 主动兜底 ============
    rgm_s = disasm(zs, "cn/kerong/bot/events/RecentGroupMessages.class")
    rgm_n = disasm(zn, "cn/kerong/bot/events/RecentGroupMessages.class")
    check("A1 RecentGroupMessages 双 JAR 在位", len(rgm_s) > 500 and len(rgm_n) > 500,
          f"spigot={len(rgm_s)}行 neoforge={len(rgm_n)}行")
    check("A2 nextId 轮转方法（latestId 已废）",
          "nextId" in rgm_s and "nextId" in rgm_n and "latestId" not in rgm_s and "latestId" not in rgm_n,
          "双端 nextId 存在且无 latestId")
    gs_s = disasm(zs, "cn/kerong/bot/events/RecentGroupMessages$GroupState.class")
    gs_n = disasm(zn, "cn/kerong/bot/events/RecentGroupMessages$GroupState.class")
    check("A3 环形缓冲容量 16（CAPACITY）",
          "bipush        16" in gs_s,
          "ArrayList 构造 bipush 16")
    check("A4 轮转游标 cursor 字段",
          "cursor" in gs_s and "cursor" in gs_n, "GroupState.cursor 双端")
    rgm_cls = class_bytes(zs, "cn/kerong/bot/events/RecentGroupMessages.class")
    check("A5 GroupState 嵌套类在位",
          "cn/kerong/bot/events/RecentGroupMessages$GroupState.class" in zs.namelist()
          and "cn/kerong/bot/events/RecentGroupMessages$GroupState.class" in zn.namelist(),
          "双端 GroupState 嵌套类")

    qc_s = disasm(zs, "cn/kerong/bot/QClient.class")
    qc_n = disasm(zn, "cn/kerong/bot/QClient.class")
    check("B1 QClient 被动失败主动兜底 resendActively",
          "resendActively" in qc_s and "resendActively" in qc_n, "双端 resendActively 方法")
    check("B2 兜底日志文案在位",
          has_str(zs, "cn/kerong/bot/QClient.class", "转主动消息通道重发")
          and has_str(zn, "cn/kerong/bot/QClient.class", "转主动消息通道重发"),
          "「转主动消息通道重发」常量双端")
    check("B3 replyMarkdown 异常路径也兜底",
          has_str(zs, "cn/kerong/bot/QClient.class", "回复 Markdown 失败")
          and has_str(zn, "cn/kerong/bot/QClient.class", "回复 Markdown 失败"),
          "异常文案常量在位")

    # ============ C. 模板升级匹配放宽（normalize + 特征兜底） ============
    mt_s = class_bytes(zs, "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class")
    mt_n = class_bytes(zn, "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class")
    mt_dis = disasm(zs, "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class")
    check("C1 UpgradeSpec 嵌套类在位",
          "cn/kerong/penguin/spigot/qqbind/MessageTemplates$UpgradeSpec.class" in zs.namelist()
          and "cn/kerong/penguin/spigot/qqbind/MessageTemplates$UpgradeSpec.class" in zn.namelist(),
          "双端 UpgradeSpec")
    check("C2 shouldUpgrade 方法（三态匹配）",
          "public boolean shouldUpgrade" in mt_dis or "shouldUpgrade" in mt_dis,
          "shouldUpgrade 在反汇编中")
    check("C3 特征兜底锚点（指令菜单标题）",
          has_str(zs, "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class", "### 指令菜单")
          and has_str(zn, "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class", "### 指令菜单"),
          "menu 特征常量双端")
    check("C4 黑名单特征锚点",
          has_str(zs, "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class", "黑名单列表（{count} 个）")
          and has_str(zn, "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class", "黑名单列表（{count} 个）"),
          "unblacklist 特征常量双端")
    check("C5 normalize 空白剥离正则",
          b"\\s" in class_bytes(zs, "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class"),
          r"\s 正则在常量池")

    # ============ D. InteractionAcker 超时收紧 + 快速重试 ============
    ia_s = disasm(zs, "cn/kerong/bot/events/InteractionAcker.class")
    ia_n = disasm(zn, "cn/kerong/bot/events/InteractionAcker.class")
    check("D1 sendOnce 三态单次发送", "sendOnce" in ia_s and "sendOnce" in ia_n, "双端 sendOnce")
    # 常量池直接查数值（javap 里 1500/2500/400 以 sipush/bipush 形态出现）
    ia_sipush = re.findall(r"sipush\s+(\d+)", ia_s)
    check("D2 超时收紧 1500/2500",
          "1500" in ia_sipush and "2500" in ia_sipush and "4000" not in ia_sipush,
          f"sipush 值集: {sorted(set(ia_sipush))[:12]}")
    check("D3 快速重试间隔 400ms", "long 400l" in ia_s and "long 400l" in ia_n,
          "ldc2_w 400l（Thread.sleep 间隔）")
    check("D4 重试日志文案",
          has_str(zs, "cn/kerong/bot/events/InteractionAcker.class", "快速重试")
          and has_str(zn, "cn/kerong/bot/events/InteractionAcker.class", "快速重试"),
          "「快速重试」常量双端")
    check("D5 双端一致", ia_s.count("sendOnce") == ia_n.count("sendOnce") > 0,
          f"spigot={ia_s.count('sendOnce')} neoforge={ia_n.count('sendOnce')}")

    # ============ E. 全量切换面板/配置即时重载 ============
    ac_s = disasm(zs, "cn/kerong/bot/events/commands/AdministrationCommands.class")
    ac_n = disasm(zn, "cn/kerong/bot/events/commands/AdministrationCommands.class")
    check("E1 refreshPanelsAsync 在位",
          "refreshPanelsAsync" in ac_s and "refreshPanelsAsync" in ac_n, "双端 refreshPanelsAsync")
    check("E2 调 syncGroupPanels 重同步",
          "syncGroupPanels" in ac_s and "syncGroupPanels" in ac_n, "双端接 MenuManager.syncGroupPanels")
    check("E3 异步守护线程", "qq-panel-refresh" in ac_s, "线程名 qq-panel-refresh")
    check("E4 失败留档文案",
          has_str(zs, "cn/kerong/bot/events/commands/AdministrationCommands.class", "切换后重同步快捷面板失败")
          and has_str(zn, "cn/kerong/bot/events/commands/AdministrationCommands.class", "切换后重同步快捷面板失败"),
          "失败日志常量双端")
    # setFullAmountEnabled 写回逻辑沿用第三轮（写回配置而非只切内存）
    check("E5 setFullAmountEnabled 写回保持", "setFullAmountEnabled" in ac_s, "总配置写回仍在")

    # ============ F. 按钮管理员权限——MemberRoleCache 补 member_role ============
    mrc_s = disasm(zs, "cn/kerong/bot/events/MemberRoleCache.class")
    mrc_n = disasm(zn, "cn/kerong/bot/events/MemberRoleCache.class")
    check("F1 MemberRoleCache 双 JAR 新类",
          len(mrc_s) > 300 and len(mrc_n) > 300, f"spigot={len(mrc_s)}行 neoforge={len(mrc_n)}行")
    check("F2 record / roleOf / clear 三方法",
          all(m in mrc_s for m in ("record", "roleOf", "clear"))
          and all(m in mrc_n for m in ("record", "roleOf", "clear")),
          "方法双端齐全")
    check("F3 保留 30 分钟（1800000ms）",
          "1800000" in re.findall(r"ldc2_w\s+#?\d+\s*\n?\s*//\s*(\d+)", mrc_s).__str__()
          or "1800000" in mrc_s,
          "RETAIN_MILLIS 常量")
    check("F4 composite key 换行分隔",
          b"\n" in class_bytes(zs, "cn/kerong/bot/events/MemberRoleCache.class"),
          "键含换行符（群+成员组合键）")

    gmh_s = disasm(zs, "cn/kerong/bot/events/GroupMessageHandler.class")
    gmh_n = disasm(zn, "cn/kerong/bot/events/GroupMessageHandler.class")
    check("F5 GroupMessageHandler 接线登记角色",
          "MemberRoleCache" in gmh_s and "MemberRoleCache" in gmh_n, "双端 record 调用在位")
    check("F6 接线读取 member_role 字段",
          has_str(zs, "cn/kerong/bot/events/GroupMessageHandler.class", "member_role")
          and has_str(zn, "cn/kerong/bot/events/GroupMessageHandler.class", "member_role"),
          "author.member_role 常量双端")

    menu_s2 = disasm(zs, "cn/kerong/bot/events/MenuButtons.class")
    menu_n2 = disasm(zn, "cn/kerong/bot/events/MenuButtons.class")
    check("F7 合成事件补 member_role",
          has_str(zs, "cn/kerong/bot/events/MenuButtons.class", "member_role")
          and has_str(zn, "cn/kerong/bot/events/MenuButtons.class", "member_role"),
          "author.member_role 写入双端")
    check("F8 点击者角色查询 roleOf",
          "roleOf" in menu_s2 and "roleOf" in menu_n2, "双端 MemberRoleCache.roleOf 调用")
    check("F9 分发日志带角色",
          has_str(zs, "cn/kerong/bot/events/MenuButtons.class", "角色=")
          and has_str(zn, "cn/kerong/bot/events/MenuButtons.class", "角色="),
          "「角色=」日志字段双端")

    # ============ G. 版本 1.7.5.1 全链路 ============
    plugin_yml = class_bytes(zs, "plugin.yml").decode("utf-8")
    mods_toml = class_bytes(zn, "META-INF/mods.toml").decode("utf-8")
    neoforge_toml = class_bytes(zn, "META-INF/neoforge.mods.toml").decode("utf-8")
    check("G3 NF MOD_VERSION 1.7.5.1",
          has_str(zn, "cn/kerong/penguin/neoforge/KERONGNeoForge.class", "1.7.5.1"),
          "KERONGNeoForge 常量")
    check("G1 plugin.yml 版本 1.7.5.1", "version: '1.7.5.1'" in plugin_yml, plugin_yml.splitlines()[1])
    check("G2 双 toml 版本 1.7.5.1",
          'version = "1.7.5.1"' in mods_toml and 'version = "1.7.5.1"' in neoforge_toml,
          "mods.toml + neoforge.mods.toml")
    config_s = class_bytes(zs, "config.yml").decode("utf-8")
    config_n = class_bytes(zn, "config.yml").decode("utf-8")
    check("G4 config-version 保持 43",
          "config-version: 43" in config_s and "config-version: 43" in config_n,
          "双端（1.7.5.1 无配置键变更）")
    check("G5 mods.toml 段头 [[mods]] 语法完好",
          any(l.strip() == "[[mods]]" for l in mods_toml.splitlines()),
          "FML 可解析（防历史 [ods]] 笔误回归）")

    # ============ H. 基线不回归（相对线上 1.7.5 第三轮版） ============
    base_names = set(zipfile.ZipFile(BASE_JAR).namelist())
    new_names = set(zs.namelist())
    added = sorted(n for n in new_names - base_names if not n.endswith("/"))
    removed = sorted(n for n in base_names - new_names if not n.endswith("/"))
    expected_new = {
        # 按钮管理员权限：角色缓存新类
        "cn/kerong/bot/events/MemberRoleCache.class",
        "cn/kerong/bot/events/MemberRoleCache$Entry.class",
        # 40034128 轮转：环形缓冲状态类
        "cn/kerong/bot/events/RecentGroupMessages$GroupState.class",
        # 模板升级放宽：升级规则类
        "cn/kerong/penguin/spigot/qqbind/MessageTemplates$UpgradeSpec.class",
    }
    unexpected_added = [n for n in added if n not in expected_new]
    check("H1 相对基线仅新增本轮预期四个类", set(added) == expected_new, f"added={added[:6]} unexpected={unexpected_added[:6]}")
    check("H2 无类移除", not removed, f"removed={removed[:6]}")
    # 菜单模板口径保持第三轮（本轮未动）
    menu_md = class_bytes(zs, "Markdown/menu.md").decode("utf-8")
    check("H3 menu.md 口径保持", "**当前插件版本：v{version}**" in menu_md and "点击下方按钮" not in menu_md,
          "只留标题+版本行")
    unban_md = class_bytes(zs, "Markdown/unblacklist-list.md").decode("utf-8")
    check("H4 unblacklist-list 口径保持",
          unban_md.startswith("黑名单列表") and "发送 /解除黑名单 @对应的人 解除" in unban_md, "命令在前")
    # InteractionAcker PUT 语义保持（第三方连接失败修复不回归）
    ia_put = b"PUT" in class_bytes(zs, "cn/kerong/bot/events/InteractionAcker.class")
    check("H5 InteractionAcker PUT 直发保持", ia_put, "回应仍为 PUT")
    # 40034128 相关：InteractionFastPath 仍读 group_member_openid
    check("H6 快速路径身份字段保持",
          has_str(zs, "cn/kerong/bot/events/InteractionFastPath.class", "group_member_openid")
          and has_str(zs, "cn/kerong/bot/events/InteractionFastPath.class", "group_openid"),
          "回调双字段读取不变")

    zs.close()
    zn.close()

    print(f"\n{'=' * 64}")
    failed = [r for r in results if not r[1]]
    for name, ok, detail in results:
        suffix = f" — {detail}" if detail else ""
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}{suffix}")
    print(f"{'=' * 64}")
    print(f"共 {len(results)} 项，通过 {len(results) - len(failed)}，失败 {len(failed)}")
    if failed:
        print("失败项：")
        for name, _, detail in failed:
            print(f"  - {name} {detail}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
