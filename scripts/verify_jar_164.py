#!/usr/bin/env python3
"""
KERONGPenguin 1.6.4 门禁验证：Spigot + NeoForge 双 JAR。
本轮改动点逐项断言：
  1. 更新检查更新日志改为颜色文字（UpdateChecker 输出带 § 颜色码）；
  2. PluginFileLog.stripColorCodes 公开剥离方法（NeoForge 控制台适配用）；
  3. NeoForge 适配器 log_info/log_warning/log_error 输出前剥离 § 码；
  4. QQ 绑定拦截策略双端改造：
     - Spigot GameChat：onPlayerLogin 老玩家判定分流（hasPlayedBefore +
       playerdata/<uuid>.dat 文件判定）+ 新玩家 onPlayerJoin 建档后踢出
       （enforceJoinBinding）；
     - NeoForge BindLoginGate：配置阶段闸门（RegisterConfigurationTasksEvent +
       BindGateTask + kerongpenguin:bind_gate + PLAYER_DATA_DIR 老玩家判定）；
     - HuHoBotNeoForge.registerBindLoginGate 挂载 mod 总线；
     - NeoForgeEvents.enforceLoginBinding 保留（新玩家 + 兜底）。
  5. 版本 1.6.4（plugin.yml / mods.toml / MOD_VERSION）。
  6. 1.6.2/1.6.3 基线保持：ReplySeqGuard / ConflictWatch / 重连体系 /
     JUL→SLF4J 桥 / 死代码不复活 / 依赖瘦身 / config-version 18 / 模板 67 个。
"""
import zipfile

SPIGOT = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_Spigot-1.6.4.jar"
NEOFORGE = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_NeoForge-1.21.1-1.6.4.jar"

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

    # ---- 1. 更新检查彩色输出 ----
    check_all(z, "cn/huohuas001/bot/UpdateChecker.class", [
        "§e[更新检查] §f发现新版本：§a§l", "§e[更新检查] §f下载页面：§b",
        "§e[更新检查] §7- §f", "§e[更新检查] §7更新内容：",
    ], f"{label}：更新检查更新日志颜色文字（§ 前缀块）")
    check_all(z, "cn/huohuas001/bot/UpdateChecker.class", [
        "isNewer", "VERSION_URL", "kerong.xin/penguin/version.json",
    ], f"{label}：UpdateChecker 版本比较与官网地址保持")

    # ---- 2. PluginFileLog.stripColorCodes ----
    check_all(z, "cn/huohuas001/bot/tools/PluginFileLog.class", [
        "stripColorCodes",
    ], f"{label}：PluginFileLog.stripColorCodes 公开剥离方法")
    check_all(z, "cn/huohuas001/bot/tools/PluginFileLog.class", [
        "write", "infoAndKeep", "warnAndKeep", "errorAndKeep",
    ], f"{label}：PluginFileLog 原有 API 保持")

    # ---- 4a. Spigot 拦截策略（GameChat 仅 Spigot JAR 有，单独断言）----
    # （NeoForge JAR 无 GameChat 类，跳过）

    # ---- 6. 基线：ReplySeqGuard / ConflictWatch / 重连体系 ----
    if "cn/huohuas001/bot/ReplySeqGuard.class" in names:
        ok(f"{label}：ReplySeqGuard 类保留（1.6.2 基线）")
    else:
        bad(f"{label}：ReplySeqGuard 缺失")
    check_all(z, "cn/huohuas001/bot/events/GroupMessageHandler.class", [
        "ReplySeqGuard", "allocateBase",
    ], f"{label}：GroupMessageHandler 序号基数保持（1.6.2 基线）")
    check_all(z, "cn/huohuas001/bot/ConflictWatch.class", [
        "onReplyRejected", "looksLikeDedupRejection", "40054005", "消息被去重",
    ], f"{label}：ConflictWatch 冲突实锤检测保持（1.6.3 基线）")
    check_all(z, "cn/huohuas001/bot/ReconnectManager.class", [
        "onDedupConflictConfirmed", "giveUpAutoReconnect", "/hb chs",
    ], f"{label}：ReconnectManager 挂起与手动恢复保持（1.6.3 基线）")
    check_all(z, "cn/huohuas001/bot/QClient.class", [
        "ConflictWatch", "replyMarkdown", "replyWithImgBytes",
    ], f"{label}：QClient 冲突检测挂钩保持（1.6.3 基线）")

    # ---- 6. 基线：死代码不复活 ----
    dead_classes = [
        "cn/huohuas001/bot/tools/CollectionUtils.class",
        "cn/huohuas001/bot/web/WebUiPassword.class",
        "cn/huohuas001/huhobotPenguin/spigot/render/InfoCardAssets$HReflection.class",
    ]
    for cls in dead_classes:
        if cls in names:
            bad(f"{label}：死类复活 {cls}")
        else:
            ok(f"{label}：死类保持移除 {cls.rsplit('/', 1)[-1]}")

    # ---- 6. 基线：依赖瘦身 ----
    for prefix in ("com/mchange/", "com/zaxxer/", "org/terracotta/", "webui/", "schema/"):
        hit = [n for n in names if n.startswith(prefix)]
        if hit:
            bad(f"{label}：瘦身残留 {prefix}*（{len(hit)} 条）")
        else:
            ok(f"{label}：瘦身 {prefix} 保持剔除")

    # ---- 6. 基线：资源与配置 ----
    md = [n for n in names if n.startswith("Markdown/") and n.endswith(".md")]
    if len(md) >= 67:
        ok(f"{label}：消息模板资源 {len(md)} 个（≥67）")
    else:
        bad(f"{label}：消息模板资源仅 {len(md)} 个")
    check_all(z, "config.yml", [
        "config-version: 18", "update-check:", "reconnect-notify: true", "财富榜: true",
    ], f"{label}：config.yml 基线（版本18）")
    for bg in ("backgrounds/card-default.png", "backgrounds/leaderboard-default.png"):
        if bg in names:
            ok(f"{label}：内置背景 {bg}")
        else:
            bad(f"{label}：内置背景缺失 {bg}")


print("================================================================")
print("Spigot JAR 1.6.4 验证")
print("================================================================")
with zipfile.ZipFile(SPIGOT) as z:
    names = z.namelist()
    if "plugin.yml" in names and "version: '1.6.4'" in z.read("plugin.yml").decode("utf-8"):
        ok("plugin.yml 版本 1.6.4")
    else:
        bad("plugin.yml 版本 1.6.4")

    # 4a. Spigot 绑定拦截策略
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/events/GameChat.class", [
        "enforceJoinBinding", "hasServerPlayerData", "onPlayerJoin", "onPlayerLogin",
    ], "Spigot：GameChat 双段拦截方法（登录分流 + 加入后踢出）")
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/events/GameChat.class", [
        "hasPlayedBefore", "playerdata/", "getWorldFolder", "getUniqueId",
    ], "Spigot：老玩家判定（usercache + playerdata 文件）")
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/events/GameChat.class", [
        "disallow", "kickPlayer", "generateCode", "formatKickMessage",
    ], "Spigot：拒入与踢出调用绑定码渲染")
    # Kotlin 内部类名验证（enforceJoinBinding / hasServerPlayerData 编译为私有方法即可）
    verify_common(z, "Spigot")

print("================================================================")
print("NeoForge JAR 1.6.4 验证")
print("================================================================")
with zipfile.ZipFile(NEOFORGE) as z:
    names = z.namelist()
    if "META-INF/neoforge.mods.toml" in names and 'version = "1.6.4"' in z.read("META-INF/neoforge.mods.toml").decode("utf-8"):
        ok("mods.toml 版本 1.6.4")
    else:
        bad("mods.toml 版本 1.6.4")
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/HuHoBotNeoForge.class", [
        "1.6.4",
    ], "NeoForge：MOD_VERSION 1.6.4")

    # 3. NeoForge 适配器 § 剥离
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/HuHoBotNeoForge.class", [
        "stripColorCodes",
    ], "NeoForge：log_* 输出前剥离 § 颜色码")

    # 4b. 登录闸门
    if "cn/huohuas001/huhobotPenguin/neoforge/BindLoginGate.class" in names:
        ok("NeoForge：BindLoginGate 类存在")
    else:
        bad("NeoForge：BindLoginGate 缺失")
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/BindLoginGate.class", [
        "registerTo", "onRegisterConfigurationTasks", "evaluateKick", "profileOf",
    ], "NeoForge：BindLoginGate 事件处理与判定方法")
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/BindLoginGate$BindGateTask.class", [
        "ServerCommonPacketListenerImpl", "disconnect", "start", "type",
    ], "NeoForge：BindGateTask 配置阶段断开（cast + disconnect + 任务约定方法）")
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/BindLoginGate$BindGateTask.class", [
        "kerongpenguin:bind_gate", "TYPE",
    ], "NeoForge：BindGateTask 任务类型标识")
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/BindLoginGate.class", [
        "RegisterConfigurationTasksEvent", "PLAYER_DATA_DIR",
        "playerProfile", "gameProfile", "NeoServerRef",
    ], "NeoForge：BindLoginGate 外部类引用集（事件/存档/反射/服务器引用）")
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/BindLoginGate.class", [
        "BindGateTask", "getOrCreateQuuidByUuid", "isBoundByUuid", "isSkipped",
        "generateCode", "formatKickMessage", "LegacyText",
    ], "NeoForge：闸门复用共享绑定逻辑")

    # 4c. 挂载方法 + 兜底
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/HuHoBotNeoForge.class", [
        "registerBindLoginGate", "登录闸门已挂载", "FMLModContainer",
    ], "NeoForge：registerBindLoginGate 挂载 mod 总线")
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/NeoForgeEvents.class", [
        "enforceLoginBinding", "generateCode", "formatKickMessage",
    ], "NeoForge：enforceLoginBinding 兜底保持（新玩家建档后踢出）")

    # 基线：JUL→SLF4J 桥
    check_all(z, "org/bukkit/plugin/java/JavaPlugin.class", [
        "bridgeJulToSlf4j", "setUseParentHandlers",
    ], "NeoForge：JavaPlugin JUL→SLF4J 桥保持（1.6.3 基线）")
    if "org/bukkit/plugin/java/JavaPlugin$Slf4jForwardHandler.class" in names:
        ok("NeoForge：JavaPlugin$Slf4jForwardHandler 保持")
    else:
        bad("NeoForge：JavaPlugin$Slf4jForwardHandler 缺失")
    hit = [n for n in names if n.startswith("org/slf4j/")]
    if hit:
        bad("NeoForge：org/slf4j 应由平台提供")
    else:
        ok("NeoForge：org/slf4j 平台提供保持")
    if "org/bukkit/scheduler/NeoScheduledHandle.class" in names:
        bad("NeoForge：NeoScheduledHandle 死类复活")
    else:
        ok("NeoForge：NeoScheduledHandle 保持移除")
    verify_common(z, "NeoForge")

print("================================================================")
print(f"结果：{passed} 项通过，{len(failed)} 项失败")
if failed:
    for f in failed:
        print(f"  ✗ {f}")
    raise SystemExit(1)
print("全部通过")
