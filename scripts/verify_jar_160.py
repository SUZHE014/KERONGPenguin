#!/usr/bin/env python3
"""
KERONGPenguin 1.6.0 门禁验证：Spigot + NeoForge 双 JAR。
检查项：版本号、新类、模板资源、配置结构、别名、自动绑定、面板加固、
Folia API 隔离（Spigot 侧）、兼容层桩（NeoForge 侧）。
"""
import re
import sys
import zipfile

SPIGOT = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_Spigot-1.6.0.jar"
NEOFORGE = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_NeoForge-1.21.1-1.6.0.jar"

passed = 0
failed = []


def check(jar_path: str, name: str) -> None:
    global passed
    passed += 1
    print(f"  ✓ {name}")


def check_contains(zf: zipfile.ZipFile, patterns: list, name: str) -> None:
    global passed, failed
    names = zf.namelist()
    for p in patterns:
        if p not in names:
            failed.append(f"{name}: 缺少 {p}")
            print(f"  ✗ {name}: 缺少 {p}")
            return
    passed += 1
    print(f"  ✓ {name}（{len(patterns)} 项全部存在）")


def check_text(zf: zipfile.ZipFile, entry: str, needles: list, name: str) -> None:
    global passed, failed
    try:
        data = zf.read(entry)
    except KeyError:
        failed.append(f"{name}: {entry} 不存在")
        print(f"  ✗ {name}: {entry} 不存在")
        return
    # class 文件按字节匹配（常量池字符串为 UTF-8 编码）；文本文件同法兼容
    for n in needles:
        if n.encode("utf-8") not in data:
            failed.append(f"{name}: {entry} 缺少 {n!r}")
            print(f"  ✗ {name}: {entry} 缺少 {n!r}")
            return
    passed += 1
    print(f"  ✓ {name}")


def check_not_text(zf: zipfile.ZipFile, entry: str, banned: list, name: str) -> None:
    global passed, failed
    data = zf.read(entry)
    for b in banned:
        if b.encode("utf-8") in data:
            failed.append(f"{name}: {entry} 含禁用串 {b!r}")
            print(f"  ✗ {name}: {entry} 含禁用串 {b!r}")
            return
    passed += 1
    print(f"  ✓ {name}")


# 模板默认内容抽查（与 1.5.7.3 硬编码文本一字不差）
TEMPLATE_CONTENT_SPOT = {
    "Markdown/bind-success.md": "✅ QQ 绑定成功！现在可以重新进入服务器了。",
    "Markdown/bind-invalid.md": "❌ 绑定码无效或已过期。\n请重新进入服务器获取新的绑定码后再试。",
    "Markdown/checkin-already.md": "{prefix}  ❌签到失败，💰你太贪了\n今日已经签到过了，请明天再试...",
    "Markdown/checkin-success.md": "{prefix}  ✔️签到成功！\n玩家 {player} 获得了{reward}金币，已连续签到{streak}天",
    "Markdown/query-too-frequent.md": "查询太频繁了，请稍后再试",
    "Markdown/ai-context-on.md": "✅ AI 对话上下文已全局开启，将保留最近对话历史。",
    "Markdown/rebind-success.md": "✅ 已解除你的 QQ 绑定。玩家 {player} 已被踢出服务器。\n请重新进入服务器获取新的绑定码。",
    "Markdown/blacklist-added-kicked.md": "✅ 已将 {name} 加入黑名单。\n已解绑并踢出玩家: {player}",
    "Markdown/command-disabled.md": "此命令已被管理员关闭",
    "Markdown/no-admin-permission.md": "你没有执行此命令的管理员权限",
    "Markdown/motd-status.md": "**MC 服务器状态查询**",
}

# 全部模板 ID（67 个 + online.md）
ALL_TEMPLATE_IDS = [
    "command-disabled", "no-admin-permission", "query-too-frequent", "server-busy",
    "image-send-failed", "manager-not-ready", "manager-not-ready-bind",
    "manager-not-ready-retry", "manager-not-ready-bind-retry", "qq-unknown", "server-status",
    "bind-usage", "bind-usage-fallback", "bind-disabled", "bind-error", "bind-success", "bind-invalid",
    "checkin-disabled", "checkin-not-bound", "checkin-data-error", "checkin-time-error",
    "checkin-already", "checkin-success-no-economy", "checkin-reward-invalid",
    "checkin-success", "checkin-pay-error",
    "info-bind-disabled", "info-closed", "info-not-bound", "info-target-not-bound", "info-render-failed",
    "online-data-unavailable", "online-render-failed",
    "leaderboard-no-data", "leaderboard-render-failed",
    "coins-leaderboard-no-economy", "coins-leaderboard-no-data", "coins-leaderboard-render-failed",
    "openid-self", "openid-lookup", "forward-disabled",
    "ai-context-usage", "ai-context-on", "ai-context-off", "ai-context-cleared",
    "ai-chat-empty", "ai-chat-error",
    "blacklist-empty", "blacklist-list", "blacklist-added-kicked", "blacklist-added",
    "blacklist-removed", "unblacklist-empty", "unblacklist-list",
    "rebind-success", "rebind-not-bound", "help",
    "invalid-params", "bridge-error", "sent-request", "execute-failed", "full-amount-toggled",
    "motd-usage", "motd-query-failed", "motd-offline", "motd-error", "motd-status",
]

NEW_CLASSES = [
    "cn/huohuas001/huhobotPenguin/spigot/qqbind/MessageTemplates.class",
    "cn/huohuas001/huhobotPenguin/spigot/qqbind/EconomyName.class",
    "cn/huohuas001/huhobotPenguin/spigot/render/RenderSettings.class",
    "cn/huohuas001/bot/events/commands/CoinsLeaderboardCommands.class",
]

print("=" * 64)
print("Spigot JAR 验证")
print("=" * 64)
with zipfile.ZipFile(SPIGOT) as zf:
    names = zf.namelist()
    check_text(zf, "plugin.yml", ["version: '1.6.0'", "folia-supported: true"], "plugin.yml 版本与 Folia 声明")
    check_contains(zf, NEW_CLASSES, "1.6.0 新类")
    check_contains(zf, [f"Markdown/{i}.md" for i in ALL_TEMPLATE_IDS], "全部 67 个消息模板资源")
    for entry, content in TEMPLATE_CONTENT_SPOT.items():
        check_text(zf, entry, [content], f"模板内容 {entry}")
    check_text(zf, "config.yml", [
        "config-version: 17",
        "render:",
        "command-cooldown-seconds: 5",
        "leaderboard:",
        "coins-leaderboard:",
        "economy-alias: \"\"",
        "messages:",
        "金币榜: true",
        "render.info-card-builtin-background",
        "coins-leaderboard.builtin-background",
    ], "config.yml 1.6.0 结构")
    check_not_text(zf, "config.yml", [
        "qq-bind.render.",
        "qq-bind.leaderboard.",
    ], "config.yml 无旧 qq-bind 路径残留")
    check_text(zf, "cn/huohuas001/huhobotPenguin/spigot/manager/ConfigManager.class", [
        "render.command-cooldown-seconds",
        "coins-leaderboard.top",
        "economy-alias",
        "messages.",
        "金币榜",
    ], "ConfigManager 新键注册（messages. 模板串 / 金币榜命令）")
    check_text(zf, "cn/huohuas001/huhobotPenguin/spigot/qqbind/QqBindManager.class", [
        "leaderboard.top",
        "render.info-card-builtin-background",
        "coins-leaderboard.top",
        "coins-leaderboard.builtin-background",
    ], "QqBindManager 新配置读取路径")
    check_not_text(zf, "cn/huohuas001/huhobotPenguin/spigot/qqbind/QqBindManager.class", [
        "qq-bind.render.info-card-builtin-background",
        "qq-bind.leaderboard.top",
    ], "QqBindManager 无旧路径残留")
    # Folia API 隔离：threadedregions 引用仅存于 FoliaDelegate*.class
    offenders = []
    for n in names:
        if n.endswith(".class") and not re.match(r"cn/huohuas001/huhobotPenguin/spigot/scheduler/FoliaDelegate(\$.*)?\.class$", n):
            data = zf.read(n)
            if b"io/papermc/paper/threadedregions" in data:
                offenders.append(n)
    if offenders:
        failed.append(f"threadedregions 引用泄漏: {offenders}")
        print(f"  ✗ threadedregions 引用泄漏: {offenders}")
    else:
        check(zf, "threadedregions API 仅存 FoliaDelegate*.class（字节码隔离）")
    # 自动绑定与面板加固
    check_text(zf, "cn/huohuas001/bot/events/GroupMessageHandler.class", [
        "tryAutoBindByDigits",
        "CoinsLeaderboardCommands",
    ], "自动绑定 + 金币榜注册")
    check_text(zf, "cn/huohuas001/bot/MenuManager.class", [
        "/v2/panels",
        "updatePanel",
        "fetchPanelDetail",
        "displayUnits",
        "金币榜",
    ], "面板加固（PUT 更新 / 详情查询 / 长度校验 / 金币榜按钮）")
    menu_data = zf.read("cn/huohuas001/bot/MenuManager.class")
    import struct
    if struct.pack(">i", 40030009) in menu_data:
        check(zf, "面板 40030009 冲突重试常量")
    else:
        failed.append("MenuManager 缺少 40030009 整型常量")
        print("  ✗ MenuManager 缺少 40030009 整型常量")
    # 渲染器泛化
    check_text(zf, "cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer$LeaderboardData.class", [
        "在线排行榜",
        "总在线时长",
    ], "排行榜渲染器默认标题（title/listHeader，旧版渲染不变）")
    check_text(zf, "cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer$LeaderEntry.class", [
        "valueText",
    ], "排行榜条目 valueText 自定义数值字段")
    # 查在线别名
    check_text(zf, "cn/huohuas001/bot/events/commands/PublicCommands.class", [
        "查在线",
        "在线",
    ], "查在线别名 /在线")

print()
print("=" * 64)
print("NeoForge JAR 验证")
print("=" * 64)
with zipfile.ZipFile(NEOFORGE) as zf:
    check_text(zf, "META-INF/neoforge.mods.toml", ['version = "1.6.0"'], "mods.toml 版本")
    check_contains(zf, NEW_CLASSES, "1.6.0 新类（含 common-Bot 同源字节码）")
    check_contains(zf, [f"Markdown/{i}.md" for i in ALL_TEMPLATE_IDS], "全部 67 个消息模板资源")
    check_text(zf, "config.yml", [
        "config-version: 17",
        "render:",
        "coins-leaderboard:",
        "economy-alias: \"\"",
        "messages:",
        "金币榜: true",
    ], "config.yml 1.6.0 结构（模组版）")
    check_text(zf, "config.yml", ["kerongpenguin/Markdown/"], "模组版数据目录注释")
    # 兼容层桩
    check_contains(zf, [
        "io/papermc/paper/threadedregions/scheduler/GlobalRegionScheduler.class",
        "io/papermc/paper/threadedregions/scheduler/AsyncScheduler.class",
        "io/papermc/paper/threadedregions/scheduler/EntityScheduler.class",
        "io/papermc/paper/threadedregions/scheduler/ScheduledTask.class",
        "org/bukkit/scheduler/BukkitTask.class",
    ], "Folia 调度桩 + BukkitTask 兼容层")
    check_contains(zf, [
        "cn/huohuas001/huhobotPenguin/spigot/scheduler/FoliaCompat.class",
        "cn/huohuas001/huhobotPenguin/spigot/scheduler/FoliaDelegate.class",
    ], "FoliaCompat/FoliaDelegate 编译进模组（NeoForge 恒走非 Folia 路径）")
    check_text(zf, "cn/huohuas001/huhobotPenguin/spigot/manager/ConfigManager.class", [
        "金币榜",
        "render.command-cooldown-seconds",
    ], "模组侧共享 ConfigManager（金币榜命令 / 渲染冷却）")

print()
print("=" * 64)
print(f"结果：{passed} 项通过，{len(failed)} 项失败")
if failed:
    for f in failed:
        print(f"  ✗ {f}")
    sys.exit(1)
print("全部通过")
