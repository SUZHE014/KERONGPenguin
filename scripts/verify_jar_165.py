#!/usr/bin/env python3
"""
KERONGPenguin 1.6.5 门禁验证：Spigot + NeoForge 双 JAR。
本轮改动点逐项断言：
  1. 启动消息「HuHoBot Penguin 已加载」→「KERONG Penguin 已加载」；
  2. 主命令 /huhobot → /kerong（plugin.yml + NeoForgeCommands 三注册名）；
  3. 内部协议 huhobot run|adminrun → kerong run|adminrun（解析端兼容旧前缀）；
  4. 品牌清理：BotShared 错误文案 / 状态文件头 / mods.toml 描述 / 环境变量名；
  5. 重连成功提示提前到 READY/RESUMED（hadAnyClose / lastSessionShort 防抖字段），
     退避新一轮清零计数（不再「第 6/5 次」）；
  6. 渲染内存与性能：CardRenderPool 不再有 System.gc / gc-after-render；
     InfoCardAssets 保留缓存与 Crafatar 回退（1.6.4 基线）；
  7. OpenId 反查管理员可见：openidPart 变量 + 模板 {openidPart} +
     MessageTemplates 旧默认内容自动升级（upgradedDefaultContents）+ isAdmin；
  8. from-game 默认值 [游戏] <{name}> {message} + config-version 19 +
     gc-after-render 配置删除 + 注释精简（无「有新版本 →」/「留空 []」段）；
  9. 1.6.2~1.6.4 基线保持（ReplySeqGuard / ConflictWatch / 重连体系 /
     双端拦截 / PlayTimeSync / 死代码不复活 / 依赖瘦身 / 模板 ≥67）。
"""
import zipfile

SPIGOT = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_Spigot-1.6.5.jar"
NEOFORGE = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_NeoForge-1.21.1-1.6.5.jar"

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

    # ---- 1.6.5 品牌统一 ----
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/render/InfoCardAssets.class", [
        "crafatar.com", "mojangInCooldown", "fetchCrafatarAvatar", "clearCaches",
    ], f"{label}：InfoCardAssets 头像链路与缓存清理保持（1.6.4 基线）")
    check_all(z, "cn/huohuas001/bot/provider/BotSharedKt.class", [
        "KERONG 插件实例尚未初始化",
    ], f"{label}：BotShared 错误文案 KERONG 化")
    check_absent(z, "cn/huohuas001/bot/provider/BotSharedKt.class", [
        "HuHoBot 插件实例尚未初始化",
    ], f"{label}：BotShared 旧文案已删除")
    check_all(z, "cn/huohuas001/bot/state/HumanReadableStateFile.class", [
        "# KERONG 群命令运行状态",
    ], f"{label}：状态文件头 KERONG 化")
    check_all(z, "cn/huohuas001/bot/provider/ConfigProvider.class", [
        "KERONG_AUDIT_BASE_URL", "KERONG_AUDIT_API_KEY", "KERONG_AUDIT_MODEL",
    ], f"{label}：审核环境变量 KERONG_* 新名")

    # ---- 1.6.5 协议前缀 kerong ----
    check_all(z, "cn/huohuas001/bot/events/commands/CustomCommandRegistry.class", [
        "kerong", "huhobot", "adminrun",
    ], f"{label}：自定义命令协议 kerong 前缀（兼容旧 huhobot）")
    check_all(z, "cn/huohuas001/bot/events/commands/PublicCommands.class", [
        "kerong run", "openidPart",
    ], f"{label}：PublicCommands 协议前缀 + openidPart 变量")
    check_all(z, "cn/huohuas001/bot/events/commands/AdministrationCommands.class", [
        "kerong adminrun", "kerong run",
    ], f"{label}：AdministrationCommands 协议前缀")
    check_all(z, "cn/huohuas001/bot/events/commands/CommandSupport.class", [
        "isAdmin", "no-admin-permission",
    ], f"{label}：CommandSupport 静默管理员判定 isAdmin")

    # ---- 1.6.5 模板升级机制 ----
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/qqbind/MessageTemplates.class", [
        "upgradedDefaultContents", "DEFAULT_CONTENT_UPGRADES", "openid-lookup",
    ], f"{label}：MessageTemplates 旧默认内容自动升级")
    check_all(z, "Markdown/openid-lookup.md", [
        "{openidPart}", "{nickname}", "{lastSeenPart}", "{player}",
    ], f"{label}：openid-lookup.md 新占位符模板")
    check_absent(z, "Markdown/openid-lookup.md", [
        "OpenId: {openid}",
    ], f"{label}：openid-lookup.md 旧 OpenId 行已移除")

    # ---- 1.6.5 重连成功提示 ----
    check_all(z, "cn/huohuas001/bot/ReconnectManager.class", [
        "hadAnyClose", "lastSessionShort", "消息收发已恢复", "onSessionReady",
    ], f"{label}：重连成功提前到 READY（防抖字段 + 提示文案）")

    # ---- 1.6.5 渲染：去 System.gc ----
    check_absent(z, "cn/huohuas001/huhobotPenguin/spigot/render/CardRenderPool.class", [
        "gc-after-render", "gcAfterRenderIfDue",
    ], f"{label}：CardRenderPool GC 提示机制已移除")
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/render/CardRenderPool.class", [
        "PenguinCardRender", "MAX_THREADS",
    ], f"{label}：CardRenderPool 渲染池保持（线程上限硬编码）")

    # ---- config.yml（1.6.5） ----
    check_all(z, "config.yml", [
        "config-version: 19", "from-game: \"[游戏] <{name}> {message}\"",
        "{openidPart}", "reconnect-notify: true", "财富榜: true",
    ], f"{label}：config.yml 基线（版本19 + from-game 新默认 + openidPart 文档）")
    check_absent(z, "config.yml", [
        "gc-after-render", "有新版本 → 控制台输出最新版本号", "留空 [] 表示不限制",
        "新增） ----------------", "起独立，不再挂在",
    ], f"{label}：config.yml 噪声注释已删除（含版本分隔条）")

    # ---- 1.6.4 基线保持 ----
    check_all(z, "cn/huohuas001/bot/UpdateChecker.class", [
        "§e[更新检查] §f发现新版本：§a§l", "kerong.xin/penguin/version.json",
    ], f"{label}：UpdateChecker 彩色输出保持（1.6.4 基线）")
    if "cn/huohuas001/bot/ReplySeqGuard.class" in names:
        ok(f"{label}：ReplySeqGuard 类保留（1.6.2 基线）")
    else:
        bad(f"{label}：ReplySeqGuard 缺失")
    check_all(z, "cn/huohuas001/bot/ConflictWatch.class", [
        "onReplyRejected", "40054005", "消息被去重",
    ], f"{label}：ConflictWatch 冲突实锤检测保持（1.6.3 基线）")
    check_all(z, "cn/huohuas001/bot/ReconnectManager.class", [
        "onDedupConflictConfirmed", "giveUpAutoReconnect", "/hb chs", "连接与自动重试照常继续",
    ], f"{label}：ReconnectManager 冲突处置与手动恢复保持（1.6.4 基线）")
    check_absent(z, "cn/huohuas001/bot/WssDiagnostics.class", [
        "仅存活", "则多为网络抖动",
    ], f"{label}：WssDiagnostics 单次短命提示保持删除")
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/stats/PlayTimeSync.class", [
        "PlayTimeManager", "PTM_playtime_s_",
    ], f"{label}：PlayTimeSync 保持（1.6.4 基线）")
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/stats/QueryInfoService.class", [
        "playerTitle_use",
    ], f"{label}：PlayerTitle 称号适配保持（1.6.4 基线）")

    # ---- 死代码不复活 + 瘦身 ----
    for cls in (
        "cn/huohuas001/bot/tools/CollectionUtils.class",
        "cn/huohuas001/bot/web/WebUiPassword.class",
        "cn/huohuas001/huhobotPenguin/spigot/render/InfoCardAssets$HReflection.class",
    ):
        if cls in names:
            bad(f"{label}：死类复活 {cls}")
        else:
            ok(f"{label}：死类保持移除 {cls.rsplit('/', 1)[-1]}")
    for prefix in ("com/mchange/", "com/zaxxer/", "org/terracotta/", "webui/", "schema/"):
        hit = [n for n in names if n.startswith(prefix)]
        if hit:
            bad(f"{label}：瘦身残留 {prefix}*（{len(hit)} 条）")
        else:
            ok(f"{label}：瘦身 {prefix} 保持剔除")

    # ---- 资源基线 ----
    md = [n for n in names if n.startswith("Markdown/") and n.endswith(".md")]
    if len(md) >= 67:
        ok(f"{label}：消息模板资源 {len(md)} 个（≥67）")
    else:
        bad(f"{label}：消息模板资源仅 {len(md)} 个")
    for bg in ("backgrounds/card-default.png", "backgrounds/leaderboard-default.png"):
        if bg in names:
            ok(f"{label}：内置背景 {bg}")
        else:
            bad(f"{label}：内置背景缺失 {bg}")


print("================================================================")
print("Spigot JAR 1.6.5 验证")
print("================================================================")
with zipfile.ZipFile(SPIGOT) as z:
    names = z.namelist()
    py = z.read("plugin.yml").decode("utf-8")
    if "version: '1.6.5'" in py:
        ok("plugin.yml 版本 1.6.5")
    else:
        bad("plugin.yml 版本 1.6.5")
    if "\n  kerong:" in py and "kerong.command" in py and "[hb, huhobot]" in py:
        ok("plugin.yml 主命令 /kerong（别名 hb / huhobot，权限 kerong.command）")
    else:
        bad("plugin.yml 主命令 kerong 注册异常")
    desc = next((ln for ln in py.splitlines() if ln.startswith("description:")), "")
    if "HuHoBot" not in desc:
        ok("plugin.yml 描述无 HuHoBot 品牌字样（main 类路径除外）")
    else:
        bad("plugin.yml 描述仍含 HuHoBot")

    # 启动消息修复
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/HuHoBotSpigot.class", [
        "KERONG Penguin 已加载",
    ], "Spigot：启动消息「KERONG Penguin 已加载」")
    check_absent(z, "cn/huohuas001/huhobotPenguin/spigot/HuHoBotSpigot.class", [
        "HuHoBot Penguin 已加载",
    ], "Spigot：旧启动消息已删除")
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/HuHoBotSpigot.class", [
        "kerong",
    ], "Spigot：getCommand(\"kerong\") 注册")

    # 1.6.4 基线：绑定拦截 + 预热
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/events/GameChat.class", [
        "enforceJoinBinding", "hasServerPlayerData",
    ], "Spigot：GameChat 双段拦截保持（1.6.4 基线）")
    check_all(z, "cn/huohuas001/huhobotPenguin/spigot/HuHoBotSpigot.class", [
        "getInstance",
    ], "Spigot：onEnable 预热 CommandOutputAppender 保持（1.6.4 基线）")
    verify_common(z, "Spigot")

print("================================================================")
print("NeoForge JAR 1.6.5 验证")
print("================================================================")
with zipfile.ZipFile(NEOFORGE) as z:
    names = z.namelist()
    toml = z.read("META-INF/neoforge.mods.toml").decode("utf-8")
    if 'version = "1.6.5"' in toml:
        ok("mods.toml 版本 1.6.5")
    else:
        bad("mods.toml 版本 1.6.5")
    if "KERONG QQ bot integration" in toml and "HuHoBot" not in toml:
        ok("mods.toml 描述 KERONG 化（无 HuHoBot）")
    else:
        bad("mods.toml 描述仍含 HuHoBot")
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/NeoForgeCommands.class", [
        "kerong", "huhobot", "hb", "MAIN_COMMAND_LABELS",
    ], "NeoForge：主命令三注册名（kerong / hb / huhobot）")
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/HuHoBotNeoForge.class", [
        "KERONGPenguin NeoForge 已加载",
    ], "NeoForge：启动消息保持 KERONG 品牌")
    verify_common(z, "NeoForge")

print("================================================================")
print(f"结果：{passed} 项通过，{len(failed)} 项失败")
if failed:
    print("失败项：")
    for f in failed:
        print(f"  - {f}")
    raise SystemExit(1)
print("全部通过")
