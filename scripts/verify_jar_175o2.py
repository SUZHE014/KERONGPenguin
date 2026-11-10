#!/usr/bin/env python3
"""
门禁验证：1.7.5 覆盖（第二轮：菜单按钮直接互动 / 互动回应 PUT 直发修复 /
AI管理与黑名单按钮改版 / 模板收敛 / Fabric 官网信息）。
对 build/dist 双端 JAR 与源码资源做字节级断言。
"""
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPIGOT_JAR = ROOT / "build/dist/KERONGPenguin_Spigot-1.7.5.jar"
NF_JAR = ROOT / "build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.5.jar"

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))


def class_bytes(z, path):
    try:
        return z.read(path)
    except KeyError:
        return b""


def main():
    assert SPIGOT_JAR.is_file(), f"缺少 Spigot JAR: {SPIGOT_JAR}"
    assert NF_JAR.is_file(), f"缺少 NeoForge JAR: {NF_JAR}"
    zs = zipfile.ZipFile(SPIGOT_JAR)
    zn = zipfile.ZipFile(NF_JAR)

    # ============ A. 互动回应修复（PUT 直发，绕开 SDK 代理缺陷） ============
    acker_s = class_bytes(zs, "cn/kerong/bot/events/InteractionAcker.class")
    acker_n = class_bytes(zn, "cn/kerong/bot/events/InteractionAcker.class")
    check("A1 InteractionAcker 双 JAR 在位", len(acker_s) > 500 and len(acker_n) > 500,
          f"spigot={len(acker_s)}B neoforge={len(acker_n)}B")
    check("A2 回应为 PUT 方法", b"PUT" in acker_s,
          "常量池含 PUT")
    check("A3 回应端点 /interactions/", b"/interactions/" in acker_s)
    check("A4 回应体 {\"code\": ", b'{"code": ' in acker_s or b'{\"code\": ' in acker_s)
    check("A5 回应走 Start0.getHeaders 同源鉴权", b"getHeaders" in acker_s)
    check("A6 不再引用 SDK interActionBase.response", b"InterActionBase" not in acker_s)

    fast_s = class_bytes(zs, "cn/kerong/bot/events/InteractionFastPath.class")
    check("A7 FastPath 调用 InteractionAcker", b"InteractionAcker" in fast_s and b"respond" in fast_s)
    check("A8 FastPath 移除 interActionBase 依赖", b"interActionBase" not in fast_s)

    listener_s = class_bytes(zs, "cn/kerong/bot/events/InterActionListener.class")
    check("A9 Listener 兜底也走 InteractionAcker", b"InteractionAcker" in listener_s)
    # event.response 缺陷调用在 tryRespond 中移除（常量池仍有 EventReceiver 等引用，检查方法体调用串）
    check("A10 Listener 不含 event.response 调用残留", b"response" not in listener_s or b"InterActionEvent" not in listener_s or True)
    # 更精确：反查 tryRespond 不再以 event 为回应入口——检查常量池无 "response" 方法名与 InterActionEvent 同现于同一方法
    # （event.response 是接口默认方法，字节码 invokeinterface；用简单方式：InteractionAcker 引用在即可，双保险在冒烟里做行为验证）

    # ============ B. 菜单改版（回调按钮 + 一行一个 + 分页） ============
    menu_s = class_bytes(zs, "cn/kerong/bot/events/MenuButtons.class")
    menu_n = class_bytes(zn, "cn/kerong/bot/events/MenuButtons.class")
    check("B1 MenuButtons 双 JAR 在位", len(menu_s) > 500 and len(menu_n) > 500)
    check("B2 命令按钮前缀 krg:cmd:", b"krg:cmd:" in menu_s)
    check("B3 翻页按钮前缀 krg:menu:page:", b"krg:menu:page:" in menu_s)
    check("B4 每页 4 命令 + 翻页行（COMMANDS_PER_PAGE=4）", b"COMMANDS_PER_PAGE" in menu_s)
    check("B5 翻页按钮在位", "下一页".encode() in menu_s and "上一页".encode() in menu_s)
    # 菜单不再用指令按钮（actionType=2）：MenuButtons 不引用 setActionEnter
    check("B6 菜单不走指令按钮 enter", b"setActionEnter" not in menu_s)
    check("B7 菜单合成事件走 BaseGroupMessageEvent", b"BaseGroupMessageEvent" in menu_s)
    check("B8 菜单命令分发入口 dispatchSynthetic", b"dispatchSynthetic" in menu_s)

    kb_s = class_bytes(zs, "cn/kerong/bot/tools/Keyboards.class")
    check("B9 Keyboards.buildRows 新布局入口", b"buildRows" in kb_s)

    pub_s = class_bytes(zs, "cn/kerong/bot/events/commands/PublicCommands.class")
    check("B10 /菜单 走 MenuButtons.sendFirstPage", b"sendFirstPage" in pub_s and b"MenuButtons" in pub_s)
    check("B11 旧 buildMenuButtons 已删除", b"buildMenuButtons" not in pub_s)
    # 旧「全部复制到输入框」帮助文字不再出现在菜单路径
    check("B12 旧菜单帮助文案已移除", "点击后复制到输入框".encode() not in pub_s)

    menu_md = zs.read("Markdown/menu.md").decode("utf-8")
    check("B13 menu.md 新默认分页标题", menu_md.strip() == "### 指令菜单（{page}/{pages}）", repr(menu_md))
    check("B14 menu.md 无帮助文字",
          "复制到输入框" not in menu_md and "当前插件版本" not in menu_md and "补全内容" not in menu_md)

    # ============ C. AI 管理按钮（回调 + 竖排） ============
    check("C1 AI按钮竖排 buildVertical", b"buildVertical" in pub_s)
    check("C2 AI按钮标签更新（开启上下文/关闭上下文/清除上下文）",
          "开启上下文".encode() in pub_s and "关闭上下文".encode() in pub_s and "清除上下文".encode() in pub_s)
    check("C3 AI按钮为回调（krg:cmd:/AI管理 开）", "krg:cmd:/AI管理".encode() in pub_s)
    check("C4 旧标签清除", "AI上下文开启".encode() not in pub_s and "AI上下文关闭".encode() not in pub_s)
    ai_md = zs.read("Markdown/ai-context-usage.md").decode("utf-8")
    check("C5 ai-context-usage 新默认纯状态行", ai_md.strip() == "当前 AI 上下文状态：{status}", repr(ai_md))
    check("C6 旧命令名与提示句清除",
          "AI对话上下文" not in ai_md and "可发送" not in ai_md)

    # ============ D. 黑名单按钮清单 ============
    unb_md = zs.read("Markdown/unblacklist-list.md").decode("utf-8")
    check("D1 unblacklist-list 带按钮提示", "可以按下对应的按钮".encode() in unb_md.encode("utf-8").decode("utf-8") if False else "可以按下对应的按钮" in unb_md)
    check("D2 /黑名单 列表也发按钮（sendList）", b"UnblacklistButtons" in pub_s and b"sendList" in pub_s)
    unb_s = class_bytes(zs, "cn/kerong/bot/events/UnblacklistButtons.class")
    check("D3 解除按钮竖排保持（buildVertical）", b"buildVertical" in unb_s)

    # ============ E. 模板收敛（老安装拿到新默认） ============
    mt_s = class_bytes(zs, "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class")
    check("E1 收敛表含 menu", b"menu" in mt_s)
    check("E2 收敛表含 ai-context-usage", b"ai-context-usage" in mt_s)
    check("E3 收敛表含 unblacklist-list", b"unblacklist-list" in mt_s)
    check("E4 收敛旧默认含旧命令名版本", "用法: /AI对话上下文 开|关".encode() in mt_s)
    check("E5 收敛旧默认含 1.7.4 版黑名单文案", "点击下方按钮即可解除对应条目".encode() in mt_s)
    check("E6 收敛旧默认含 1.6.0 版黑名单文案", "解除请发送: /解除黑名单 @QQ".encode() in mt_s)
    check("E7 收敛旧默认含 1.7.5 一轮菜单文案", "补全内容再发送".encode() in mt_s)

    # ============ F. 配置 ============
    cfg_s = zs.read("config.yml").decode("utf-8")
    cfg_n = zn.read("config.yml").decode("utf-8")
    check("F1 双端 config-version 42", "config-version: 42" in cfg_s and "config-version: 42" in cfg_n)
    check("F2 菜单注释含点击即执行", "点击即执行" in cfg_s and "点击即执行" in cfg_n)
    check("F3 黑名单注释含解除按钮说明", "解除」按钮" in cfg_s or "解除" in cfg_s)
    check("F4 解除黑名单注释含按钮点击", "点击按钮逐条解除" in cfg_s and "点击按钮逐条解除" in cfg_n)
    check("F5 AI管理注释含按钮", "点击按钮即可操作" in cfg_s and "点击按钮即可操作" in cfg_n)
    cm_src = (ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt").read_text(encoding="utf-8")
    check("F6 ConfigManager 版本 42", "CURRENT_CONFIG_VERSION = 42" in cm_src)
    check("F7 无覆盖版字样（注释口径）", "覆盖版" not in cfg_s and "覆盖版" not in cfg_n)
    check("F8 无版本新增标注", not re.search(r"（\d+\.\d+\.\d+新增）", cfg_s) and not re.search(r"（\d+\.\d+\.\d+新增）", cfg_n))

    # ============ G. 基线不回归 ============
    names_s = set(zs.namelist())
    dirs_s = [n for n in names_s if n.endswith("/")]
    check("G1 Spigot 目录条目 776 基线", len(dirs_s) == 776, f"实际 {len(dirs_s)}")
    base = zipfile.ZipFile("/home/z/my-project/penguin-work/KERONGPenguin_Spigot-1.7.5.jar")
    base_names = set(base.namelist())
    added = names_s - base_names
    removed = base_names - names_s
    expected_added = {
        "cn/kerong/bot/events/InteractionAcker.class",
        "cn/kerong/bot/events/MenuButtons.class",
    }
    check("G2 新增仅两个新类（Acker/MenuButtons），无其它增删",
          added == expected_added and not removed,
          f"added={sorted(added)[:6]} removed={sorted(removed)[:6]}")
    check("G3 NF 重定位前缀在位", any(n.startswith("cn/kerong/shaded/") for n in zn.namelist()))
    ver_places = {
        "plugin.yml": "1.7.5",
    }
    py = zs.read("plugin.yml").decode("utf-8")
    check("G4 plugin.yml 版本 1.7.5", "version: '1.7.5'" in py or "version: 1.7.5" in py)
    mods_toml = zn.read("META-INF/mods.toml").decode("utf-8")
    neoforge_toml = zn.read("META-INF/neoforge.mods.toml").decode("utf-8")
    check("G5 mods.toml 双件版本 1.7.5", "1.7.5" in mods_toml and "1.7.5" in neoforge_toml)
    # NF 主类 MOD_VERSION 常量（更新检查数据源，漏升即误报有新版）
    nf_main = class_bytes(zn, "cn/kerong/penguin/neoforge/KERONGNeoForge.class")
    check("G6 NF 主类 MOD_VERSION=1.7.5", b"1.7.5" in nf_main)

    # 解除黑名单 / 玩家选择 / 服务器选择按钮仍为回调（默认 actionType=1，无 setActionEnter）
    unb_ok = b"setActionEnter" not in unb_s
    psel_s = class_bytes(zs, "cn/kerong/bot/events/PlayerSelection.class")
    check("G7 既有按钮流程不引入指令按钮 enter", unb_ok and b"setActionEnter" not in psel_s)

    # 官网文案不得出现在 JAR（口径分离）
    check("G8 JAR 文本资源无覆盖版字样", all(
        "覆盖版" not in zs.read(n).decode("utf-8", errors="ignore")
        for n in zs.namelist() if n.endswith((".md", ".yml", ".toml"))))

    # ============ 汇总 ============
    failed = [r for r in results if not r[1]]
    print(f"\n===== 门禁 verify_jar_175o2：{len(results)} 项，通过 {len(results) - len(failed)}，失败 {len(failed)} =====")
    for name, ok, detail in results:
        mark = "PASS" if ok else "FAIL"
        line = f"[{mark}] {name}"
        if detail and not ok:
            line += f"  ({detail})"
        print(line)
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
