#!/usr/bin/env python3
"""
KERONGPenguin 1.6.3 门禁验证：Spigot + NeoForge 双 JAR。
本轮改动点逐项断言：
  1. 消息级冲突检测（ConflictWatch 类 + 40054005/消息被去重 特征 + 阈值窗口 +
     ReconnectManager.onDedupConflictConfirmed 处置 + suspendConnection 挂起复用）；
  2. QClient 四个被动回复路径 + ret 拒收分支全部接入 ConflictWatch；
  3. MessageTemplates / GroupMsgSender 模板回复接入 ConflictWatch；
  4. WssDiagnostics：鉴权期被关连接计入互踢证据（trackAuthRejected /
     recordConflictEvidence / QqTokenHealth.lastCheckOk 门控）；
  5. NeoForge 控制台 JUL→SLF4J 桥（JavaPlugin$Slf4jForwardHandler +
     bridgeJulToSlf4j + useParentHandlers）；
  6. QqBindManager 构造期日志走 plugin.logger（已就绪行不再 println）；
  7. /motd 转发修复（PublicCommands 引用 MotdCommands，motd-* 模板恢复可达）；
  8. 死代码清理（CollectionUtils / HReflection / WebUiPassword / sendWithMention /
     executeCustomCommand / getCommandTrigger / NeoScheduledHandle / WhiteList 链 /
     messageHandler / log_debug 等不再存在于产物）；
  9. 第三方库瘦身（com/mchange、com/zaxxer、org/terracotta、webui/、schema/ 不在双 JAR）；
  10. 版本 1.6.3（plugin.yml / mods.toml / MOD_VERSION）；模板资源 67 个不变；
      ReplySeqGuard（1.6.2 修复）保留。
"""
import zipfile

SPIGOT = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_Spigot-1.6.3.jar"
NEOFORGE = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_NeoForge-1.21.1-1.6.3.jar"

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


def verify_common(z: zipfile.ZipFile, label: str) -> None:
    names = z.namelist()

    # 1. ConflictWatch（消息级冲突检测）
    if "cn/huohuas001/bot/ConflictWatch.class" in names:
        ok(f"{label}：ConflictWatch 类存在")
    else:
        bad(f"{label}：ConflictWatch 缺失")
    check_all(z, "cn/huohuas001/bot/ConflictWatch.class", [
        "onReplyRejected", "onReplyRejectedDetail", "looksLikeDedupRejection",
        "recordRejection", "DEDUP_THRESHOLD", "DEDUP_WINDOW_MS",
        "40054005", "消息被去重", "ReconnectManager",
    ], f"{label}：ConflictWatch 去重拒收识别 + 计数窗口 + 处置委托")

    # 2. ReconnectManager：确凿冲突处置 + 挂起复用
    check_all(z, "cn/huohuas001/bot/ReconnectManager.class", [
        "onDedupConflictConfirmed", "suspendConnection", "ConflictWatch",
        "消息被去重(40054005)", "抢先回复了同一条群消息", "不再自动重试",
    ], f"{label}：ReconnectManager 确凿冲突处置（断开+指引+不自动轮次）")
    check_all(z, "cn/huohuas001/bot/ReconnectManager.class", [
        "giveUpAutoReconnect", "scheduleBackoffWake", "manualReconnect",
        "重试连接", "重连超时", "/hb chs",
    ], f"{label}：ReconnectManager 1.6.2 重连体系保持不变")

    # 3. QClient 挂钩（四个回复方法 + ret 分支）
    check_all(z, "cn/huohuas001/bot/QClient.class", [
        "ConflictWatch", "onReplyRejected", "onReplyRejectedDetail",
        "replyMarkdown", "replyWithImg", "replyWithImgBytes",
        "回复图片消息失败", "回复 Markdown 失败",
    ], f"{label}：QClient 被动回复路径全部接入冲突检测")
    # 死代码：异步包装层已删（字节码中 sendTextToGroups/sendPayloadToGroups
    # 仅作为 …Sync 后缀出现，计数应与 Sync 计数一致）
    try:
        qc_text = utf8_consts(z.read("cn/huohuas001/bot/QClient.class"))
        if qc_text.count("sendTextToGroups") == qc_text.count("sendTextToGroupsSync") and \
                qc_text.count("sendPayloadToGroups") == qc_text.count("sendPayloadToGroupsSync"):
            ok(f"{label}：QClient 死代码已移除（异步包装层）")
        else:
            bad(f"{label}：QClient 异步包装层死代码残留")
    except KeyError:
        bad(f"{label}：QClient.class 缺失")

    # 4. MessageTemplates / GroupMsgSender 挂钩
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/qqbind/MessageTemplates.class", [
        "ConflictWatch", "onReplyRejected", "sendDirect", "sendPlain",
    ], f"{label}：MessageTemplates 模板回复接入冲突检测")
    check_absent(z, "cn/huohuas001/huhobotPenguin/spigot/qqbind/GroupMsgSender.class", [
        "sendWithMention",
    ], f"{label}：GroupMsgSender.sendWithMention 死代码已移除")

    # 5. WssDiagnostics：鉴权期被关证据 + 凭据门控
    # （lastCheckOk 为 Kotlin 属性，字节码方法名为 getLastCheckOk）
    check_all(z, "cn/huohuas001/bot/WssDiagnostics.class", [
        "trackAuthRejected", "recordConflictEvidence", "QqTokenHealth", "getLastCheckOk",
        "鉴权完成前即被平台关闭", "trackShortSession",
    ], f"{label}：WssDiagnostics 鉴权期关闭计入互踢证据（token 门控）")

    # 6. /motd 转发修复（转发调用以 MotdCommands 引用为证）
    check_all(z, "cn/huohuas001/bot/events/commands/PublicCommands.class", [
        "MotdCommands",
    ], f"{label}：PublicCommands.motd 带参数转发 MotdCommands")
    check_all(z, "cn/huohuas001/bot/events/commands/MotdCommands.class", [
        "motd", "motd.minebbs.com",
    ], f"{label}：MotdCommands 远程状态查询类保留")

    # 7. ReplySeqGuard（1.6.2 修复保留）
    if "cn/huohuas001/bot/ReplySeqGuard.class" in names:
        ok(f"{label}：ReplySeqGuard 类保留（1.6.2 去重基数修复）")
    else:
        bad(f"{label}：ReplySeqGuard 缺失（1.6.2 修复不应删除）")
    check_all(z, "cn/huohuas001/bot/events/GroupMessageHandler.class", [
        "ReplySeqGuard", "allocateBase",
    ], f"{label}：GroupMessageHandler 入口分配序号基数保持")

    # 8. 死代码清理断言（类不存在 / 符号不存在）
    dead_classes = [
        "cn/huohuas001/bot/tools/CollectionUtils.class",
        "cn/huohuas001/bot/web/WebUiPassword.class",
        "cn/huohuas001/huhobotPenguin/spigot/render/InfoCardAssets$HReflection.class",
    ]
    for cls in dead_classes:
        if cls in names:
            bad(f"{label}：死类仍存在 {cls}")
        else:
            ok(f"{label}：死类已移除 {cls.rsplit('/', 1)[-1]}")
    check_absent(z, "cn/huohuas001/bot/events/commands/CommandSupport.class", [
        "executeCustomCommand", "replyWithImg",
    ], f"{label}：CommandSupport 死方法已移除")
    check_absent(z, "cn/huohuas001/bot/MenuManager.class", [
        "getCommandTrigger",
    ], f"{label}：MenuManager.getCommandTrigger 已移除")
    check_absent(z, "cn/huohuas001/bot/state/CommandRepositories.class", [
        "setAdministratorMode",
    ], f"{label}：CommandRepositories.setAdministratorMode 已移除")
    check_absent(z, "cn/huohuas001/bot/provider/ConfigProvider.class", [
        "WhiteList", "whiteList",
    ], f"{label}：ConfigProvider WhiteList 死链已移除")
    check_absent(z, "cn/huohuas001/huhobotPenguin/spigot/manager/ConfigManager.class", [
        "whiteList",
    ], f"{label}：ConfigManager whiteList 覆写已移除")

    # 9. 第三方库瘦身
    for prefix in ("com/mchange/", "com/zaxxer/", "org/terracotta/", "webui/", "schema/"):
        hit = [n for n in names if n.startswith(prefix)]
        if hit:
            bad(f"{label}：瘦身失败，残留 {prefix}*（{len(hit)} 条）")
        else:
            ok(f"{label}：瘦身 {prefix} 已剔除")

    # 10. 资源与配置基线
    md = [n for n in names if n.startswith("Markdown/") and n.endswith(".md")]
    if len(md) >= 67:
        ok(f"{label}：消息模板资源 {len(md)} 个（≥67）")
    else:
        bad(f"{label}：消息模板资源仅 {len(md)} 个")
    check_all(z, "config.yml", [
        "config-version: 18", "update-check:", "reconnect-notify: true", "财富榜: true",
    ], f"{label}：config.yml 基线（版本18/update-check/reconnect-notify/财富榜）")
    for bg in ("backgrounds/card-default.png", "backgrounds/leaderboard-default.png"):
        if bg in names:
            ok(f"{label}：内置背景 {bg}")
        else:
            bad(f"{label}：内置背景缺失 {bg}")


print("================================================================")
print("Spigot JAR 1.6.3 验证")
print("================================================================")
with zipfile.ZipFile(SPIGOT) as z:
    names = z.namelist()
    if "plugin.yml" in names and "version: '1.6.3'" in z.read("plugin.yml").decode("utf-8"):
        ok("plugin.yml 版本 1.6.3")
    else:
        bad("plugin.yml 版本 1.6.3")
    check_all(z, "plugin.yml", ["chs"], "plugin.yml usage 含 chs")
    # QqBindManager：构造期已就绪行改走 plugin.logger
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/qqbind/QqBindManager.class", [
        "已就绪",
    ], "QqBindManager 已就绪文案保留")
    check_absent(z, "cn/huohuas001/huhobotPenguin/spigot/qqbind/QqBindManager.class", [
        "listBlacklistNames", "currentLogFile",
    ], "QqBindManager 死方法已移除（listBlacklistNames/currentLogFile）")
    verify_common(z, "Spigot")

print("================================================================")
print("NeoForge JAR 1.6.3 验证")
print("================================================================")
with zipfile.ZipFile(NEOFORGE) as z:
    names = z.namelist()
    if "META-INF/neoforge.mods.toml" in names and 'version = "1.6.3"' in z.read("META-INF/neoforge.mods.toml").decode("utf-8"):
        ok("mods.toml 版本 1.6.3")
    else:
        bad("mods.toml 版本 1.6.3")
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/HuHoBotNeoForge.class", [
        "1.6.3",
    ], "HuHoBotNeoForge：MOD_VERSION 1.6.3")
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/NeoForgeCommands.class", [
        "chs", "ReconnectManager",
    ], "NeoForgeCommands：/hb chs + /huhobot chs")

    # NeoForge 专属：JUL→SLF4J 桥
    # （useParentHandlers 为 Java 字段 + setter，字节码引用 setUseParentHandlers）
    check_all(z, "org/bukkit/plugin/java/JavaPlugin.class", [
        "bridgeJulToSlf4j", "setUseParentHandlers",
    ], "NeoForge：JavaPlugin JUL→SLF4J 桥接方法")
    if "org/bukkit/plugin/java/JavaPlugin$Slf4jForwardHandler.class" in names:
        ok("NeoForge：JavaPlugin$Slf4jForwardHandler 类存在")
    else:
        bad("NeoForge：JavaPlugin$Slf4jForwardHandler 缺失")
    # NeoForge 专属：slf4j 平台提供（打包剔除）
    hit = [n for n in names if n.startswith("org/slf4j/")]
    if hit:
        bad("NeoForge：org/slf4j 应由平台提供（打包剔除）")
    else:
        ok("NeoForge：org/slf4j 平台提供（产物内无）")
    # NeoForge 专属：BukkitScheduler 死句柄移除
    if "org/bukkit/scheduler/NeoScheduledHandle.class" in names:
        bad("NeoForge：NeoScheduledHandle 死类仍存在")
    else:
        ok("NeoForge：NeoScheduledHandle 死类已移除")
    verify_common(z, "NeoForge")

print("================================================================")
print(f"结果：{passed} 项通过，{len(failed)} 项失败")
if failed:
    for f in failed:
        print(f"  ✗ {f}")
    raise SystemExit(1)
print("全部通过")
