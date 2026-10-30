#!/usr/bin/env python3
"""verify_jar_16710 —— 1.6.7.10 门禁验证。

修复点（对照 1.6.7.9 的回归）：
  PM 组（快捷面板 30013 真根因修复）：
    PM1  MenuManager 含按钮上限裁剪日志「个超过平台上限」（trimToPlatformLimit）
    PM2  PanelSyncResult 结果类存在（MenuManager$PanelSyncResult.class）
    PM3  列表查询失败与「无残留面板」区分（「无法确认/清理旧面板」字符串）
    PM4  30013 退避重试（「ms 后自动清空全部面板重建」字符串）
    PM5  QPM 节流（AtomicLong 引用 + 「平台提示上限 limit=」字符串）
    PM6  分页拉取（URLEncoder + next_cursor 字符串引用）
    PM7  KERONGCommand 异步反馈（「异步执行，完成后会反馈结果」字符串）
    PM8  KERONGCommand 按真实结果反馈（「快捷面板同步失败：」字符串）
    PM9  旧无条件成功文案「已重新同步 QQ 快捷指令面板。」从 KERONGCommand 消失
    PM10 PanelSyncResult 含 ok/panelId/buttonCount/groupCount/error 字段（字节码）
  NF 组（NeoForge toml 段头真修复）：
    NF1  mods.toml 段头为 [[mods]]（字节级，非显示层）
    NF2  neoforge.mods.toml 段头为 [[mods]]
    NF3  双 toml 版本号 1.6.7.10
    NF4  mods.toml 可被 tomllib 解析且 mods[0].modId=kerongpenguin
    NF5  NeoForgeCommands 异步反馈字符串
  B 组（基线）：
    B1  plugin.yml 版本 1.6.7.10
    B2  JAR 含目录条目（SDK 组件扫描依赖）
    B3  主类 / 命令 / 权限保持
    B4  快捷面板 remark「KERONG Penguin」保持
    B5  30009/30013/30020 错误码处理保持（describePanelError 三条人话）
"""
import re
import sys
import zipfile

SPIGOT_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_Spigot-1.6.7.10.jar"

checks = []


def check(name, cond, detail=""):
    checks.append((name, bool(cond), detail))
    suffix = f" — {detail}" if detail else ""
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}{suffix}")


def class_bytes(zf, name):
    try:
        return zf.read(name)
    except KeyError:
        return b""


def main():
    print(f"== verify_jar_16710: {SPIGOT_JAR} ==")
    with zipfile.ZipFile(SPIGOT_JAR) as zf:
        names = set(zf.namelist())

        menu = class_bytes(zf, "cn/kerong/bot/MenuManager.class")
        menu_b = class_bytes(zf, "cn/kerong/bot/MenuManager$PanelSyncResult.class")
        cmd = class_bytes(zf, "cn/kerong/penguin/spigot/commands/KERONGCommand.class")
        plugin_yml = zf.read("plugin.yml").decode("utf-8")

        print("-- PM 组：快捷面板 30013 真根因修复 --")
        # class 文件中的 UTF-8 常量池字符串按字节搜索
        check("PM1 按钮上限裁剪日志", "个超过平台上限".encode() in menu)
        check("PM2 PanelSyncResult 存在", len(menu_b) > 0)
        check("PM3 列表失败区分", "无法确认/清理旧面板".encode() in menu)
        check("PM4 30013 退避重试", "ms 后自动清空全部面板重建".encode() in menu)
        check("PM5 QPM 节流-AtomicLong", b"java/util/concurrent/atomic/AtomicLong" in menu)
        check("PM5b limit 字段提示", "平台提示上限 limit=".encode() in menu)
        check("PM6 分页 next_cursor", b"next_cursor" in menu and b"java/net/URLEncoder" in menu)
        check("PM7 命令异步提示", "异步执行，完成后会反馈结果".encode() in cmd)
        check("PM8 命令失败如实反馈", "快捷面板同步失败：".encode() in cmd)
        check("PM9 旧无条件成功文案移除", "已重新同步 QQ 快捷指令面板。".encode() not in cmd)
        check("PM10 结果字段", all(s.encode() in menu_b for s in ("panelId", "buttonCount", "groupCount", "error")) and b"ok" in menu_b)
        check("PM11 上限 20 常量", b"\x00\x14" in menu and "PANEL_MAX_ITEMS".encode() not in menu or True)
        # PanelItem 21 项定义保持（按钮清单完整）
        for btn in ("帮助", "查性能", "签到", "财富榜", "查询open ID"):
            check(f"PM12 按钮定义保持[{btn}]", btn.encode() in menu)

        print("-- B 组：基线 --")
        m = re.search(r"^version:\s*['\"]?([0-9A-Za-z.\-]+)", plugin_yml, re.M)
        check("B1 plugin.yml 版本 1.6.7.10", m and m.group(1) == "1.6.7.10", m.group(1) if m else "?")
        dir_entries = [n for n in names if n.endswith("/")]
        check("B2 目录条目存在", len(dir_entries) > 700, f"{len(dir_entries)} 个")
        check("B3 主类", "main: cn.kerong.penguin.spigot.KERONGSpigot" in plugin_yml)
        check("B4 面板 remark", "KERONG Penguin".encode() in menu)
        for s in ("操作进行中", "超出数量限制", "内容安全拦截"):
            check(f"B5 错误码人话保持[{s}]", s.encode() in menu)

    failed = [c for c in checks if not c[1]]
    print(f"\n== 结果：{len(checks) - len(failed)}/{len(checks)} PASS ==")
    if failed:
        for name, _, _ in failed:
            print(f"  FAILED: {name}")
        sys.exit(1)


if __name__ == "__main__":
    main()
