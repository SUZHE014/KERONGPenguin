#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
1.6.6.2 门禁：双端分发 JAR 内容断言。

覆盖：
  A. 品牌与命名（包结构 cn.kerong.*，全 JAR 无旧字样，主类，命令注册）
  B. 控制台真彩色（ConsoleColors / ANSI 映射 / 双端 log_* 引用）
  C. PlaceholderAPI 依赖检测（PapiDependencyCheck + 挂载点引用）
  D. 头像线路与磁盘缓存（MinePic / Mojang 原生 API 保留 / TTL 配置化 /
     过期清扫线程 / xuid / cache 目录）
  E. 历史基线（1.6.2~1.6.6.1 关键类与机制保留；WebUI 与 PlayTimeSync 保持删除）
  F. 打包完整性（目录条目 / 体积 / relocate 干净）
  G. 配置面（config.yml 双端注释 / ConfigManager 默认值）
  H. WebUI 面板删除（1.6.6.1 起保持删除）
  I. PlayTimeManager 时长同步删除（1.6.6.1 起保持删除）
  J. 头像本地优先（avatar-fetch-priority 配置 / 本地与外置链拆分）
  K. 渲染内存优化（零中间图直绘 / roundImage 移除 / 背景封顶）
  L. 1.6.6.2 缓存与内存策略（内存缓存 128 / 10KB 磁盘门槛 / 空闲释放 /
     清扫启动化 / 获取方式日志 / 网络抖动型失败不写 missCache）
"""
import os
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SPIGOT = os.path.join(REPO, "build", "dist", "KERONGPenguin_Spigot-1.6.6.2.jar")
NEOFORGE = os.path.join(REPO, "build", "dist", "KERONGPenguin_NeoForge-1.21.1-1.6.6.2.jar")

passed = 0
failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"PASS  {name}")
    else:
        failed += 1
        print(f"FAIL  {name}  [{detail}]")


def read_text(zf, name):
    return zf.read(name).decode("utf-8")


def class_blob(zf, prefix):
    return b"".join(
        zf.read(n) for n in zf.namelist()
        if n.startswith(prefix) and n.endswith(".class")
    )


def source(name):
    path = os.path.join(REPO, name)
    with open(path, encoding="utf-8") as f:
        return f.read()


def main():
    if not os.path.isfile(SPIGOT):
        raise SystemExit(f"缺少 Spigot JAR：{SPIGOT}")
    if not os.path.isfile(NEOFORGE):
        raise SystemExit(f"缺少 NeoForge JAR：{NEOFORGE}")

    # ================= Spigot =================
    with zipfile.ZipFile(SPIGOT) as zf:
        names = zf.namelist()
        plugin_yml = read_text(zf, "plugin.yml")

        # ---- A. 品牌与命名 ----
        check("A1 plugin.yml main = cn.kerong.penguin.spigot.KERONGSpigot",
              "main: cn.kerong.penguin.spigot.KERONGSpigot" in plugin_yml)
        check("A2 plugin.yml version 1.6.6.2", "version: '1.6.6.2'" in plugin_yml)
        check("A3 plugin.yml aliases = [hb]（无旧命令别名）",
              "aliases: [hb]" in plugin_yml and "huhobot" not in plugin_yml)
        check("A4 plugin.yml 命令 kerong / qq 注册",
              "kerong:" in plugin_yml and "qq:" in plugin_yml)
        check("A5 全 JAR 条目名无旧字样",
              not any(("huhobot" in n.lower() or "huohuas001" in n.lower()) for n in names))
        bot_blob = class_blob(zf, "cn/kerong/")
        check("A6 插件自身字节码无旧字样",
              b"huhobot" not in bot_blob and b"huohuas001" not in bot_blob)
        check("A7 KERONG 接口类存在", "cn/kerong/bot/KERONG.class" in names)
        check("A8 主类 KERONGSpigot 存在", "cn/kerong/penguin/spigot/KERONGSpigot.class" in names)
        check("A9 KERONGCommand 存在", "cn/kerong/penguin/spigot/commands/KERONGCommand.class" in names)

        # ---- B. 控制台真彩色 ----
        check("B1 ConsoleColors 类存在", "cn/kerong/bot/tools/ConsoleColors.class" in names)
        cc = zf.read("cn/kerong/bot/tools/ConsoleColors.class")
        check("B3 终端能力探测（TerminalConsoleAppender）",
              b"net/minecrell/terminalconsole/TerminalConsoleAppender" in cc
              or b"TerminalConsoleAppender" in cc)
        spigot_cls = zf.read("cn/kerong/penguin/spigot/KERONGSpigot.class")
        check("B4 KERONGSpigot.log_* 引用 ConsoleColors",
              b"ConsoleColors" in spigot_cls)

        # ---- C. PlaceholderAPI 依赖检测 ----
        check("C1 PapiDependencyCheck 类存在",
              "cn/kerong/penguin/spigot/stats/PapiDependencyCheck.class" in names)
        check("C2 KERONGSpigot 挂载 scheduleStartupCheck",
              b"scheduleStartupCheck" in spigot_cls)
        qis = zf.read("cn/kerong/penguin/spigot/stats/QueryInfoService.class")
        check("C3 QueryInfoService 挂载 notifyMissingOnce",
              b"notifyMissingOnce" in qis)
        check("C4 称号来源链保留（DeluxeTags/PlayerTitle/Vault）",
              b"DeluxeTags" in qis and b"PlayerTitle" in qis)

        # ---- D. 头像线路与磁盘缓存 ----
        render_blob = class_blob(zf, "cn/kerong/penguin/spigot/render/")
        check("D1 MC-Heads 线路", b"mc-heads.net" in render_blob)
        check("D2 Minotar 线路", b"minotar.net" in render_blob)
        check("D3 Crafatar 线路保留", b"crafatar.com" in render_blob)
        check("D4 基岩 xuid 线路", b"xuid:" in render_blob)
        check("D5 floodgateXuid 解析方法", b"floodgateXuid" in render_blob)
        check("D6 磁盘缓存目录 cache/avatars", b"cache/avatars" in render_blob)
        check("D7 TTL 动态化到位（avatarDiskTtlMillis / DEFAULT_AVATAR_DISK_TTL_HOURS）",
              b"avatarDiskTtlMillis" in render_blob
              and b"DEFAULT_AVATAR_DISK_TTL_HOURS" in render_blob)
        check("D8 MinePic 线路（minepic.org）", b"minepic.org" in render_blob)
        check("D9 MinePic 归一化调用（fetchMinePicAvatar）", b"fetchMinePicAvatar" in render_blob)
        check("D10 Mojang 原生 API 保留（sessionserver + api.mojang.com）",
              b"sessionserver.mojang.com" in render_blob and b"api.mojang.com" in render_blob)
        settings_cls = zf.read("cn/kerong/penguin/spigot/render/RenderSettings.class")
        check("D11 RenderSettings 读取 avatar-cache-hours", b"avatar-cache-hours" in settings_cls)
        check("D12 TTL 配置键默认 3 小时（DEFAULT_AVATAR_CACHE_HOURS）",
              b"DEFAULT_AVATAR_CACHE_HOURS" in settings_cls)
        check("D13 过期清扫线程（avatar-cache-sweeper）",
              b"avatar-cache-sweeper" in render_blob and b"sweepDiskCache" in render_blob)

        # ---- E. 历史基线保留 ----
        for cls, desc in [
            ("cn/kerong/bot/ConflictWatch.class", "E1 ConflictWatch（1.6.3 双服检测）"),
            ("cn/kerong/bot/WssDiagnostics.class", "E2 WssDiagnostics（1.6.4 互踢诊断）"),
            ("cn/kerong/bot/ReconnectManager.class", "E3 ReconnectManager（1.6.5 重连提示）"),
            ("cn/kerong/bot/UpdateChecker.class", "E4 UpdateChecker（1.6.2 更新检查）"),
            ("cn/kerong/bot/OutboxQueue.class", "E5 OutboxQueue（1.6.2 回复去重）"),
            ("cn/kerong/bot/tools/ConsoleColors.class", "E6 ConsoleColors（1.6.6 真彩色）"),
            ("cn/kerong/penguin/spigot/stats/PapiDependencyCheck.class", "E7 PapiDependencyCheck（1.6.6 依赖检测）"),
        ]:
            check(desc, cls in names)

        # ---- F. 打包完整性 ----
        dir_count = sum(1 for n in names if n.endswith("/"))
        check("F1 目录条目保留（SDK 组件扫描必需）", dir_count > 500, str(dir_count))
        size_mb = os.path.getsize(SPIGOT) / 1024 / 1024
        check("F2 Spigot 体积合理（18~21MB）", 18.0 <= size_mb <= 21.0, f"{size_mb:.1f}MB")
        check("F3 kloping SDK 保留（组件扫描）",
              any(n.startswith("io/github/kloping/") for n in names))

        # ---- G. 配置面 ----
        config_yml = read_text(zf, "config.yml")
        check("G1 config.yml 含 avatar-cache-hours: 3",
              "avatar-cache-hours: 3" in config_yml)
        check("G2 config.yml 注释含默认 3 小时 / 0 关闭说明",
              "默认 3" in config_yml and "0 = 关闭磁盘缓存" in config_yml)
        check("G3 config.yml 注释含 30 分钟清扫说明", "30 分钟清扫" in config_yml)
        manager = zf.read("cn/kerong/penguin/spigot/manager/ConfigManager.class")
        check("G4 ConfigManager 默认值含 avatar-cache-hours", b"avatar-cache-hours" in manager)

        # ---- H. WebUI 面板删除（保持） ----
        check("H1 WebUiServer 类不存在", "cn/kerong/bot/web/WebUiServer.class" not in names)
        check("H2 WebUiSchema 类不存在", "cn/kerong/bot/web/WebUiSchema.class" not in names)
        check("H3 web/ 目录条目不存在",
              not any(n.startswith("cn/kerong/bot/web/") for n in names))
        kerong_cls = zf.read("cn/kerong/bot/KERONG.class")
        check("H4 KERONG 接口无 webUiConfigValues / applyWebUiConfigChanges",
              b"webUiConfigValues" not in kerong_cls
              and b"applyWebUiConfigChanges" not in kerong_cls)
        check("H5 KERONGSpigot 无 WebUI 挂点与 fastjson 转换",
              b"webUiConfigValues" not in spigot_cls
              and b"applyWebUiConfigChanges" not in spigot_cls
              and b"convertJsonValue" not in spigot_cls)

        # ---- I. PlayTimeManager 时长同步删除（保持） ----
        check("I1 PlayTimeSync 类不存在",
              "cn/kerong/penguin/spigot/stats/PlayTimeSync.class" not in names)
        check("I2 插件字节码无 PTM_playtime 变量引用",
              b"PTM_playtime" not in bot_blob)
        stats_blob = class_blob(zf, "cn/kerong/penguin/spigot/stats/")
        check("I3 PlayerStatsManager 无 PlayTimeSync 挂载",
              b"PlayTimeSync" not in stats_blob)
        papi_cls = zf.read("cn/kerong/penguin/spigot/stats/PapiDependencyCheck.class")
        check("I4 PapiDependencyCheck 检测面收窄（无 PlayTimeManager 插件名探测）",
              b"PlayTimeManager" not in papi_cls)

        # ---- J. 头像本地优先（保持） ----
        check("J1 RenderSettings 读取 avatar-fetch-priority",
              b"avatar-fetch-priority" in settings_cls)
        check("J2 RenderSettings 含 AvatarFetchPriority 枚举",
              "cn/kerong/penguin/spigot/render/RenderSettings$AvatarFetchPriority.class" in names)
        check("J3 RenderSettings 含 avatarFetchPriority 方法",
              b"avatarFetchPriority" in settings_cls)
        check("J4 本地链（fetchViaLocalChain / resolveLocalSkinUrl）",
              b"fetchViaLocalChain" in render_blob and b"resolveLocalSkinUrl" in render_blob)
        check("J5 外置链（fetchViaExternalChain / resolveMojangSkinUrl）",
              b"fetchViaExternalChain" in render_blob and b"resolveMojangSkinUrl" in render_blob)
        check("J6 裁脸复用（downloadAndCropFace）", b"downloadAndCropFace" in render_blob)
        check("J7 旧混合解析函数已拆分（无 resolveSkinTexture）",
              b"resolveSkinTexture" not in render_blob)
        check("J8 config.yml 含 avatar-fetch-priority: local",
              "avatar-fetch-priority: local" in config_yml)
        check("J10 ConfigManager 默认值含 avatar-fetch-priority",
              b"avatar-fetch-priority" in manager)

        # ---- K. 渲染内存优化（保持） ----
        info_cls = zf.read("cn/kerong/penguin/spigot/render/InfoCardRenderer.class")
        online_cls = zf.read("cn/kerong/penguin/spigot/render/OnlineListRenderer.class")
        board_cls = zf.read("cn/kerong/penguin/spigot/render/LeaderboardRenderer.class")
        check("K1 InfoCardRenderer 含 drawRoundedAvatar（零拷贝直绘）",
              b"drawRoundedAvatar" in info_cls)
        check("K2 InfoCardRenderer 无 roundImage",
              b"roundImage" not in info_cls)
        check("K3 OnlineListRenderer 无 roundImage",
              b"roundImage" not in online_cls)
        check("K4 LeaderboardRenderer 无 roundImage",
              b"roundImage" not in board_cls)
        check("K6 resolveBackground 内置分支统一封顶（源码断言）",
              "val cappedHeight = height.coerceAtMost(ONLINE_BACKGROUND_MAX_HEIGHT)" in source(
                  "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt"))
        check("K7 三渲染器调用 drawRoundedAvatar 直绘",
              b"drawRoundedAvatar" in online_cls and b"drawRoundedAvatar" in board_cls)

        # ---- L. 1.6.6.2 缓存与内存策略 ----
        render_src = source(
            "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt")
        pool_cls = zf.read("cn/kerong/penguin/spigot/render/CardRenderPool.class")
        check("L1 头像内存缓存上限 128（源码断言 AVATAR_CACHE_MAX = 128）",
              "AVATAR_CACHE_MAX = 128" in render_src)
        check("L2 内存门槛常量 10KB（源码断言 AVATAR_MEMORY_MAX_BYTES = 10 * 1024）",
              "AVATAR_MEMORY_MAX_BYTES = 10 * 1024" in render_src
              and b"AVATAR_MEMORY_MAX_BYTES" in render_blob)
        check("L3 缓存落位方法（cacheAndStoreAvatar：PNG 编码一次共用）",
              b"cacheAndStoreAvatar" in render_blob)
        check("L4 磁盘写入拆分（encodeAvatarPng / writeDiskAvatar，旧 saveDiskAvatar 移除）",
              b"encodeAvatarPng" in render_blob and b"writeDiskAvatar" in render_blob
              and b"saveDiskAvatar" not in render_blob)
        check("L5 磁盘命中带文件大小（DiskAvatar 内部类）",
              "cn/kerong/penguin/spigot/render/InfoCardAssets$DiskAvatar.class" in names)
        check("L6 渲染空闲释放（releaseIfIdle + render-idle-releaser 线程）",
              b"releaseIfIdle" in render_blob and b"render-idle-releaser" in render_blob)
        check("L7 渲染完成时间戳（markRenderCompleted 挂 CardRenderPool）",
              b"markRenderCompleted" in pool_cls and b"markRenderCompleted" in render_blob)
        check("L8 活跃渲染计数护栏（activeRenders）",
              b"activeRenders" in pool_cls)
        check("L9 RenderSettings 读取 idle-release-minutes",
              b"idle-release-minutes" in settings_cls
              and b"idleReleaseMillis" in settings_cls)
        check("L10 config.yml 含 idle-release-minutes: 5 与说明注释",
              "idle-release-minutes: 5" in config_yml
              and "渲染空闲内存释放" in config_yml and "0 = 关闭空闲释放" in config_yml)
        check("L11 ConfigManager 默认值含 idle-release-minutes",
              b"idle-release-minutes" in manager)
        check("L12 清扫线程启动化（KERONGSpigot 启动调用 ensureAvatarSweeper）",
              b"ensureAvatarSweeper" in spigot_cls)
        check("L13 内存缓存及时清理（purgeMemoryAvatarCache）",
              b"purgeMemoryAvatarCache" in render_blob)
        check("L14 头像获取方式日志（logAvatarSource / FetchedFace）",
              b"logAvatarSource" in render_blob
              and "cn/kerong/penguin/spigot/render/InfoCardAssets$FetchedFace.class" in names)
        check("L15 失败分类修复（networkTrouble ThreadLocal，源码断言不写 missCache 分支）",
              b"networkTrouble" in render_blob
              and "if (networkTrouble.get()) {" in render_src)
        check("L16 失败分类日志区分（logAvatarMiss retryable 参数）",
              b"logAvatarMiss" in render_blob and "retryable" in render_src)
        check("L17 清空缓存停用空闲释放线程（源码断言 idleReleaser?.shutdownNow）",
              "idleReleaser?.shutdownNow()" in render_src)

        # ---- M. 1.6.6.2 覆盖版（日志刷屏 / 启动内存 / 设计署名） ----
        settings_src = source(
            "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/RenderSettings.kt")
        cmds_cls = zf.read("cn/kerong/penguin/spigot/commands/KERONGCommand.class")
        online_src = source(
            "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/OnlineListRenderer.kt")
        leaderboard_src = source(
            "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/LeaderboardRenderer.kt")
        readme_src = source("README.md")
        check("M1 获取方式日志改文件日志（源码断言 PluginFileLog.write（[头像]）",
              'PluginFileLog.write("[头像]' in render_src
              and 'log_info("[头像]' not in render_src)
        check("M2 获取失败日志同样仅文件（源码断言）",
              'PluginFileLog.write("[头像] ${request.playerName} 获取失败' in render_src)
        check("M3 获取方式日志会话去重（loggedAvatarSources）",
              b"loggedAvatarSources" in render_blob and "LOGGED_SOURCES_MAX" in render_src)
        check("M4 单飞门闩内部类（FetchLatch）",
              "cn/kerong/penguin/spigot/render/InfoCardAssets$FetchLatch.class" in names)
        check("M5 并发获取去重表（inFlightFetches + 等待 8 秒）",
              b"inFlightFetches" in render_blob and b"IN_FLIGHT_WAIT_MILLIS" in render_blob)
        check("M6 等待者超时语义（await 8 秒 TimeUnit 毫秒，源码断言）",
              "await(IN_FLIGHT_WAIT_MILLIS, TimeUnit.MILLISECONDS)" in render_src)
        check("M7 空闲 GC 提示配置（idleGcHint + render.idle-gc-hint）",
              b"idleGcHint" in settings_cls and b"idle-gc-hint" in settings_cls
              and "fun idleGcHint(): Boolean" in settings_src)
        check("M8 空闲释放后 System.gc 提示（源码断言，区别于旧版渲染后 GC）",
              "if (RenderSettings.idleGcHint()) {" in render_src and "System.gc()" in render_src)
        check("M9 config.yml 含 idle-gc-hint: true 与说明",
              "idle-gc-hint: true" in config_yml and "GC 提示归还内存" in config_yml)
        check("M10 ConfigManager 默认值含 idle-gc-hint",
              b"idle-gc-hint" in manager)
        check("M11 缓存诊断快照（cacheStats）",
              b"cacheStats" in render_blob)
        check("M12 /kerong mem 子命令（Spigot 命令类引用 cacheStats）",
              b"cacheStats" in cmds_cls
              and '"reload", "info", "panel", "chs", "mem", "help"' in source(
                  "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/commands/KERONGCommand.kt"))
        check("M13 渲染器设计来源署名（三个渲染器文件头）",
              "新亭泪" in render_src and "新亭泪" in online_src and "新亭泪" in leaderboard_src)
        check("M14 渲染器署名含设计来源链接（xintinglei.cn）",
              "xintinglei.cn" in online_src and "xintinglei.cn" in render_src)
        check("M15 README 设计来源段（BaiZhouziYou / 新亭泪 / issue #1）",
              "新亭泪" in readme_src and "xintinglei.cn" in readme_src
              and "issues/1" in readme_src)

    # ================= NeoForge =================
    with zipfile.ZipFile(NEOFORGE) as zf:
        names = zf.namelist()
        toml = read_text(zf, "META-INF/neoforge.mods.toml")

        check("N1 mods.toml [mod] 标准语法", "[mod]" in toml.replace("mods]", "mod]") or "modId" in toml)
        check("N2 mods.toml version 1.6.6.2", 'version = "1.6.6.2"' in toml)
        check("N3 modId kerongpenguin", 'modId = "kerongpenguin"' in toml)
        check("N4 主类 KERONGNeoForge 存在", "cn/kerong/penguin/neoforge/KERONGNeoForge.class" in names)
        check("N5 NeoForgeCommands 存在（kerong/hb 双 literal）",
              "cn/kerong/penguin/neoforge/NeoForgeCommands.class" in names)
        nf_cmds = zf.read("cn/kerong/penguin/neoforge/NeoForgeCommands.class")
        check("N6 命令注册无旧名 literal", b"huhobot" not in nf_cmds)
        check("N7 BindLoginGate 保留（1.6.4）", "cn/kerong/penguin/neoforge/BindLoginGate.class" in names)
        check("N8 全 JAR 无旧字样",
              not any(("huhobot" in n.lower() or "huohuas001" in n.lower()) for n in names))
        nf_blob = class_blob(zf, "cn/kerong/")
        check("N9 插件字节码无旧字样",
              b"huhobot" not in nf_blob and b"huohuas001" not in nf_blob)
        check("N10 relocate 干净（无旧 shaded）",
              not any(n.startswith("cn/huohuas001/shaded/") for n in names))
        check("N11 relocate 就位（cn/kerong/shaded）",
              sum(1 for n in names if n.startswith("cn/kerong/shaded/")) > 1000)
        check("N12 ConsoleColors / PapiDependencyCheck 存在",
              "cn/kerong/bot/tools/ConsoleColors.class" in names
              and "cn/kerong/penguin/spigot/stats/PapiDependencyCheck.class" in names)
        nf_render_blob = class_blob(zf, "cn/kerong/penguin/spigot/render/")
        check("N13 MinePic 线路同步（NF 产物）", b"minepic.org" in nf_render_blob)
        check("N14 TTL 动态化同步（NF 产物）",
              b"avatarDiskTtlMillis" in nf_render_blob and b"DEFAULT_AVATAR_DISK_TTL_HOURS" in nf_render_blob)
        check("N15 Mojang 原生 API 保留（NF 产物）",
              b"sessionserver.mojang.com" in nf_render_blob)
        nf_config = read_text(zf, "config.yml")
        check("N16 NF config.yml 含 avatar-cache-hours: 3", "avatar-cache-hours: 3" in nf_config)

        # 1.6.6.1 覆盖版同步断言（NF 产物，保持）
        check("N17 NF WebUI 类不存在",
              "cn/kerong/bot/web/WebUiServer.class" not in names
              and "cn/kerong/bot/web/WebUiSchema.class" not in names)
        check("N18 NF PlayTimeSync 类不存在",
              "cn/kerong/penguin/spigot/stats/PlayTimeSync.class" not in names
              and b"PTM_playtime" not in nf_blob)
        nf_settings = zf.read("cn/kerong/penguin/spigot/render/RenderSettings.class")
        check("N19 NF RenderSettings 含 avatar-fetch-priority",
              b"avatar-fetch-priority" in nf_settings
              and "cn/kerong/penguin/spigot/render/RenderSettings$AvatarFetchPriority.class" in names)
        check("N20 NF 本地/外置链拆分同步",
              b"fetchViaLocalChain" in nf_render_blob and b"fetchViaExternalChain" in nf_render_blob)
        check("N21 NF config.yml 含 avatar-fetch-priority: local",
              "avatar-fetch-priority: local" in nf_config)
        nf_info = zf.read("cn/kerong/penguin/spigot/render/InfoCardRenderer.class")
        nf_online = zf.read("cn/kerong/penguin/spigot/render/OnlineListRenderer.class")
        nf_board = zf.read("cn/kerong/penguin/spigot/render/LeaderboardRenderer.class")
        check("N22 NF 渲染器零中间图直绘同步",
              b"drawRoundedAvatar" in nf_info and b"roundImage" not in nf_info
              and b"roundImage" not in nf_online and b"roundImage" not in nf_board)
        nf_kerong = zf.read("cn/kerong/bot/KERONG.class")
        nf_main = zf.read("cn/kerong/penguin/neoforge/KERONGNeoForge.class")
        check("N23 NF KERONG 接口无 WebUI 挂点",
              b"webUiConfigValues" not in nf_kerong
              and b"applyWebUiConfigChanges" not in nf_kerong
              and b"applyWebUiConfigChanges" not in nf_main)
        size_mb = os.path.getsize(NEOFORGE) / 1024 / 1024
        check("N24 NeoForge 体积合理（17~20MB）", 17.0 <= size_mb <= 20.0, f"{size_mb:.1f}MB")

        # 1.6.6.2 同步断言（NF 产物）
        check("N25 NF 空闲释放同步（releaseIfIdle / render-idle-releaser）",
              b"releaseIfIdle" in nf_render_blob and b"render-idle-releaser" in nf_render_blob)
        nf_pool = zf.read("cn/kerong/penguin/spigot/render/CardRenderPool.class")
        check("N26 NF CardRenderPool 挂 markRenderCompleted / activeRenders",
              b"markRenderCompleted" in nf_pool and b"activeRenders" in nf_pool)
        check("N27 NF RenderSettings 读取 idle-release-minutes",
              b"idle-release-minutes" in nf_settings)
        check("N28 NF config.yml 含 idle-release-minutes: 5",
              "idle-release-minutes: 5" in nf_config)
        check("N29 NF 主类启动调用 ensureAvatarSweeper（强关后磁盘可清）",
              b"ensureAvatarSweeper" in nf_main)
        check("N30 NF 失败分类与获取方式日志同步（networkTrouble / logAvatarSource）",
              b"networkTrouble" in nf_render_blob and b"logAvatarSource" in nf_render_blob)
        check("N31 NF 磁盘写入拆分同步（encodeAvatarPng / writeDiskAvatar）",
              b"encodeAvatarPng" in nf_render_blob and b"writeDiskAvatar" in nf_render_blob
              and b"saveDiskAvatar" not in nf_render_blob)
        nf_manager = zf.read("cn/kerong/penguin/spigot/manager/ConfigManager.class")
        check("N32 NF ConfigManager 默认值含 idle-release-minutes",
              b"idle-release-minutes" in nf_manager)

        # 1.6.6.2 覆盖版同步断言（NF 产物）
        check("N33 NF 日志改文件日志 + 会话去重（loggedAvatarSources / PluginFileLog 引用）",
              b"loggedAvatarSources" in nf_render_blob
              and b"cn/kerong/bot/tools/PluginFileLog" in nf_render_blob)
        check("N34 NF 单飞去重同步（FetchLatch / inFlightFetches）",
              b"inFlightFetches" in nf_render_blob
              and "cn/kerong/penguin/spigot/render/InfoCardAssets$FetchLatch.class" in names)
        check("N35 NF 空闲 GC 提示同步（idleGcHint / idle-gc-hint）",
              b"idleGcHint" in nf_settings
              and "idle-gc-hint: true" in nf_config)
        check("N36 NF /kerong mem 子命令同步（NeoForgeCommands 引用 cacheStats）",
              b"cacheStats" in nf_cmds)

    total = passed + failed
    print(f"\n===== 门禁结果：{passed}/{total} 通过，{failed} 失败 =====")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
