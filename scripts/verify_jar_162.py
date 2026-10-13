#!/usr/bin/env python3
"""
KERONGPenguin 1.6.2 门禁验证：Spigot + NeoForge 双 JAR。
本轮改动点逐项断言：
  1. 回复去重修复（ReplySeqGuard 类存在 + GroupMessageHandler 引用 + 基数逻辑常量）；
  2. 重连编排（ReconnectManager 类存在 + 第 N/5 提示 + 重连超时 + /hb chs 文案 + 退避轮次）；
  3. QClient：resumeConnection 存在 + stopForConflict 不再停机（委托 ReconnectManager）；
  4. WssDiagnostics：onOpen/onClosedAfter/healthy 集成 + 冲突提示醒目化；
  5. /hb chs 命令（Spigot HuHoBotCommand + plugin.yml usage + NeoForge 两处 literal）；
  6. 更新检查（UpdateChecker 类 + version.json URL + HuHoBot 挂载）；
  7. 财富榜更名（CoinsLeaderboardCommands 注解双名 + MenuManager 财富榜 + help.md 条目 +
     config.yml commands.财富榜 + 迁移逻辑 + COMMAND_ALIASES 金币榜→财富榜）；
  8. 头像修复（httpGetWithStatus + NEGATIVE_UUID_TTL + 限流不写负缓存逻辑）；
  9. 配置：config-version 18 + update-check 段 + bot.reconnect-notify；
  10. 版本 1.6.2 五处（plugin.yml / mods.toml / MOD_VERSION）；模板资源 67 个。
"""
import re
import zipfile

SPIGOT = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_Spigot-1.6.2.jar"
NEOFORGE = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_NeoForge-1.21.1-1.6.2.jar"

passed = 0
failed = []


def ok(name: str) -> None:
    global passed
    passed += 1
    print(f"  ✓ {name}")


def bad(name: str) -> None:
    failed.append(name)
    print(f"  ✗ {name}")


def utf8_consts(data: bytes) -> str:
    return data.decode("utf-8", errors="ignore")


def check_all(zf: zipfile.ZipFile, entry: str, needles: list, name: str) -> None:
    try:
        data = zf.read(entry)
    except KeyError:
        bad(f"{name}（缺少 {entry}）")
        return
    text = utf8_consts(data)
    missing = [n for n in needles if n not in text]
    if missing:
        bad(f"{name}（缺 {missing}）")
    else:
        ok(name)


def check_absent(zf: zipfile.ZipFile, entry: str, needles: list, name: str) -> None:
    try:
        data = zf.read(entry)
    except KeyError:
        bad(f"{name}（缺少 {entry}）")
        return
    text = utf8_consts(data)
    present = [n for n in needles if n in text]
    if present:
        bad(f"{name}（不应存在 {present}）")
    else:
        ok(name)


def check_config(zf: zipfile.ZipFile, entry: str, name: str) -> None:
    try:
        text = zf.read(entry).decode("utf-8")
    except KeyError:
        bad(f"{name}（缺少 {entry}）")
        return
    problems = []
    if "config-version: 18" not in text:
        problems.append("config-version != 18")
    if "update-check:" not in text or "update-check:\n  enabled: true" not in text:
        problems.append("update-check 段缺失")
    if "reconnect-notify: true" not in text:
        problems.append("bot.reconnect-notify 缺失")
    if "财富榜: true" not in text:
        problems.append("commands.财富榜 缺失")
    if "金币榜: true" in text:
        problems.append("commands.金币榜 仍存在（应为 财富榜）")
    if "命令更名：/金币榜 → /财富榜" not in text and "1.6.2 命令更名" not in text:
        problems.append("金币排行榜段更名说明缺失")
    if problems:
        bad(f"{name}（{problems}）")
    else:
        ok(name)


def verify_common(z: zipfile.ZipFile, label: str) -> None:
    names = z.namelist()

    # 1. ReplySeqGuard（回复去重修复）
    if "cn/huohuas001/bot/ReplySeqGuard.class" in names:
        ok(f"{label}：ReplySeqGuard 类存在")
    else:
        bad(f"{label}：ReplySeqGuard 缺失")
    check_all(z, "cn/huohuas001/bot/ReplySeqGuard.class", [
        "allocateBase", "DeliveryCounter", "maybeCleanup", "BASE_STRIDE",
    ], f"{label}：ReplySeqGuard 基数分配逻辑（常量）")
    check_all(z, "cn/huohuas001/bot/events/GroupMessageHandler.class", [
        "ReplySeqGuard", "allocateBase", "BaseGroupMessageEvent",
    ], f"{label}：GroupMessageHandler 入口分配序号基数")

    # 2. ReconnectManager（重连编排）
    rm = "cn/huohuas001/bot/ReconnectManager.class"
    check_all(z, rm, [
        "ReconnectManager", "onOpen", "onHealthySession", "onConflictDetected",
        "manualReconnect", "isSuspended", "giveUpAutoReconnect",
        "重试连接", "重连成功", "重连超时", "/hb chs",
        "轮自动重试", "30 分钟退避结束", "qq-reconnect-backoff",
    ], f"{label}：ReconnectManager 重连编排（提示/超时/退避/手动重连）")
    check_all(z, "cn/huohuas001/bot/ReconnectManager.class", [
        "SMALL_RETRY_MAX", "BIG_ROUND_MAX", "HEALTHY_SESSION_MS",
    ], f"{label}：ReconnectManager 计数常量")
    check_all(z, "cn/huohuas001/bot/QClient.class", [
        "resumeConnection", "手动重连触发失败", "ReconnectManager",
    ], f"{label}：QClient.resumeConnection + 手动重连链路")
    # stopForConflict 不再停机：不再有 qq-conflict-stop 线程
    check_absent(z, "cn/huohuas001/bot/QClient.class", [
        "qq-conflict-stop", "本服务器的 QQ 机器人连接已停止",
    ], f"{label}：stopForConflict 停机路径已移除")

    # 3. WssDiagnostics 集成
    check_all(z, "cn/huohuas001/bot/WssDiagnostics.class", [
        "ReconnectManager", "onSessionReady", "onClosedAfter", "onHealthySession",
    ], f"{label}：WssDiagnostics 集成重连编排")
    check_all(z, "cn/huohuas001/bot/WssDiagnostics.class", [
        "重连过程见", "互相抢占轮流掉线",
    ], f"{label}：短命会话提示醒目化（含排查指引）")

    # 4. UpdateChecker
    if "cn/huohuas001/bot/UpdateChecker.class" in names:
        ok(f"{label}：UpdateChecker 类存在")
    else:
        bad(f"{label}：UpdateChecker 缺失")
    check_all(z, "cn/huohuas001/bot/UpdateChecker.class", [
        "version.json", "kerong.xin", "isNewer", "更新检测失败", "已是最新版本",
    ], f"{label}：UpdateChecker 版本比较 + 失败一句话提示")
    check_all(z, "cn/huohuas001/bot/HuHoBot.class", [
        "UpdateChecker", "scheduleUpdateCheck", "qq-update-check",
    ], f"{label}：HuHoBot 启动挂载更新检查")

    # 5. 财富榜更名
    check_all(z, "cn/huohuas001/bot/events/commands/CoinsLeaderboardCommands.class", [
        "财富榜", "金币榜", "财富榜] 渲染失败",
    ], f"{label}：CoinsLeaderboardCommands 双触发名 + 日志前缀更名")
    check_all(z, "cn/huohuas001/bot/MenuManager.class", [
        "财富榜", "金币数量排行",
    ], f"{label}：MenuManager 快捷按钮更名财富榜")
    # ConfigManager：迁移 + 别名
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/manager/ConfigManager.class", [
        "财富榜", "金币榜", "命令更名",
    ], f"{label}：ConfigManager 迁移（commands.金币榜→财富榜）+ 别名映射")

    # help.md 模板条目
    try:
        text = z.read("Markdown/help.md").decode("utf-8")
        if "/财富榜 —— 金币数量排行图片" in text and "/金币榜" in text:
            ok(f"{label}：help.md 财富榜条目（含旧名提示）")
        else:
            bad(f"{label}：help.md 财富榜条目缺失")
    except KeyError:
        bad(f"{label}：help.md 缺失")

    # 6. 头像修复（嵌套 object 编译为独立类 InfoCardAssets.class）
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/render/InfoCardAssets.class", [
        "httpGetWithStatus", "NEGATIVE_UUID_TTL",
    ], f"{label}：头像修复（状态码区分 + 负缓存 TTL）")

    check_config(z, "config.yml", f"{label}：config.yml（版本18/update-check/reconnect-notify/财富榜）")

    md = [n for n in names if n.startswith("Markdown/") and n.endswith(".md")]
    if len(md) >= 67:
        ok(f"{label}：消息模板资源 {len(md)} 个（≥67）")
    else:
        bad(f"{label}：消息模板资源仅 {len(md)} 个")


print("================================================================")
print("Spigot JAR 1.6.2 验证")
print("================================================================")
with zipfile.ZipFile(SPIGOT) as z:
    names = z.namelist()
    if "plugin.yml" in names and "version: '1.6.2'" in z.read("plugin.yml").decode("utf-8"):
        ok("plugin.yml 版本 1.6.2")
    else:
        bad("plugin.yml 版本 1.6.2")
    if "usage: \"/huhobot reload|info|panel|chs|help\"" in z.read("plugin.yml").decode("utf-8"):
        ok("plugin.yml usage 含 chs")
    else:
        bad("plugin.yml usage 不含 chs")
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/commands/HuHoBotCommand.class", [
        "chs", "立即重试连接",
    ], "HuHoBotCommand：/hb chs 子命令")
    verify_common(z, "Spigot")

print("================================================================")
print("NeoForge JAR 1.6.2 验证")
print("================================================================")
with zipfile.ZipFile(NEOFORGE) as z:
    names = z.namelist()
    if "META-INF/neoforge.mods.toml" in names and 'version = "1.6.2"' in z.read("META-INF/neoforge.mods.toml").decode("utf-8"):
        ok("mods.toml 版本 1.6.2")
    else:
        bad("mods.toml 版本 1.6.2")
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/HuHoBotNeoForge.class", [
        "1.6.2",
    ], "HuHoBotNeoForge：MOD_VERSION 1.6.2")
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/NeoForgeCommands.class", [
        "chs", "ReconnectManager",
    ], "NeoForgeCommands：/hb chs + /huhobot chs")
    verify_common(z, "NeoForge")

print("================================================================")
print(f"结果：{passed} 项通过，{len(failed)} 项失败")
if failed:
    for f in failed:
        print(f"  ✗ {f}")
    raise SystemExit(1)
print("全部通过")
