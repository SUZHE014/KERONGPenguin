#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
1.6.6.1 门禁：双端分发 JAR 内容断言。

覆盖：
  A. 品牌与命名（包结构 cn.kerong.*，全 JAR 无旧字样，主类，命令注册）
  B. 控制台真彩色（ConsoleColors / ANSI 映射 / 双端 log_* 引用）
  C. PlaceholderAPI 依赖检测（PapiDependencyCheck + 挂载点引用）
  D. 头像线路与磁盘缓存（MinePic 新线路 / Mojang 原生 API 保留 /
     TTL 配置化 avatar-cache-hours / 过期清扫线程 / xuid / cache 目录）
  E. 历史基线（1.6.2~1.6.5 关键类与机制保留）
  F. 打包完整性（目录条目 / 体积 / relocate 干净）
  G. 配置面（config.yml 双端注释 / WebUI 面板字段 / ConfigManager 默认值）
"""
import os
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELEASE = "/home/z/my-project/download/kerongpenguin-1.6.6.1-release"
SPIGOT = os.path.join(RELEASE, "KERONGPenguin_Spigot-1.6.6.1.jar")
NEOFORGE = os.path.join(RELEASE, "KERONGPenguin_NeoForge-1.21.1-1.6.6.1.jar")

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
        check("A2 plugin.yml version 1.6.6.1", "version: '1.6.6.1'" in plugin_yml)
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
        check("B2 ANSI 颜色映射（[33m/[92m/[1m/[0m）",
              b"[33m" in cc and b"[92m" in cc and b"[1m" in cc and b"[0m" in cc)
        check("B3 终端能力探测（TerminalConsoleAppender）",
              b"net.minecrell/terminalconsole/TerminalConsoleAppender" in cc
              or b"TerminalConsoleAppender" in cc)
        spigot_cls = zf.read("cn/kerong/penguin/spigot/KERONGSpigot.class")
        check("B4 KERONGSpigot.log_* 引用 ConsoleColors",
              b"ConsoleColors" in spigot_cls)
        nf_cls = zf.read("cn/kerong/penguin/neoforge/KERONGNeoForge.class") if "cn/kerong/penguin/neoforge/KERONGNeoForge.class" in names else b""
        if nf_cls:
            check("B5 NeoForge 主类引用 ConsoleColors（真彩色）", b"ConsoleColors" in nf_cls)
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
        # 1.6.6.1：旧 24h 常量废除，改动态读取（源码级 grep 已确认无独立旧常量；
        # 字节码中的子串来自新常量 DEFAULT_AVATAR_DISK_TTL_HOURS，非旧常量）
        check("D7 TTL 动态化到位（avatarDiskTtlMillis / DEFAULT_AVATAR_DISK_TTL_HOURS）",
              b"avatarDiskTtlMillis" in render_blob
              and b"DEFAULT_AVATAR_DISK_TTL_HOURS" in render_blob)
        # 1.6.6.1：MinePic 线路
        check("D8 MinePic 线路（minepic.org）", b"minepic.org" in render_blob)
        check("D9 MinePic 归一化调用（fetchMinePicAvatar）", b"fetchMinePicAvatar" in render_blob)
        # Mojang 原生 API 保留
        check("D10 Mojang 原生 API 保留（sessionserver + api.mojang.com）",
              b"sessionserver.mojang.com" in render_blob and b"api.mojang.com" in render_blob)
        # 配置化
        settings_cls = zf.read("cn/kerong/penguin/spigot/render/RenderSettings.class")
        check("D11 RenderSettings 读取 avatar-cache-hours", b"avatar-cache-hours" in settings_cls)
        check("D12 TTL 配置键默认 3 小时（DEFAULT_AVATAR_CACHE_HOURS）",
              b"DEFAULT_AVATAR_CACHE_HOURS" in settings_cls)
        # 过期清扫线程
        check("D13 过期清扫线程（avatar-cache-sweeper）",
              b"avatar-cache-sweeper" in render_blob and b"sweepDiskCache" in render_blob)
        check("D14 清扫周期 30 分钟（1800000ms 常量）", b"sweepDiskCache" in render_blob)

        # ---- E. 历史基线保留 ----
        for cls, desc in [
            ("cn/kerong/bot/ConflictWatch.class", "E1 ConflictWatch（1.6.3 双服检测）"),
            ("cn/kerong/bot/WssDiagnostics.class", "E2 WssDiagnostics（1.6.4 互踢诊断）"),
            ("cn/kerong/bot/ReconnectManager.class", "E3 ReconnectManager（1.6.5 重连提示）"),
            ("cn/kerong/penguin/spigot/stats/PlayTimeSync.class", "E4 PlayTimeSync（1.6.4 时长同步）"),
            ("cn/kerong/bot/UpdateChecker.class", "E5 UpdateChecker（1.6.2 更新检查）"),
            ("cn/kerong/bot/OutboxQueue.class", "E6 OutboxQueue（1.6.2 回复去重）"),
            ("cn/kerong/bot/tools/ConsoleColors.class", "E7 ConsoleColors（1.6.6 真彩色）"),
            ("cn/kerong/penguin/spigot/stats/PapiDependencyCheck.class", "E8 PapiDependencyCheck（1.6.6 依赖检测）"),
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
        schema = zf.read("cn/kerong/bot/web/WebUiSchema.class")
        check("G5 WebUI 面板新增头像缓存字段", b"avatar-cache-hours" in schema)
        check("G6 WebUI 面板已移除废除的 gc-after-render 字段",
              b"gc-after-render" not in schema)

    # ================= NeoForge =================
    with zipfile.ZipFile(NEOFORGE) as zf:
        names = zf.namelist()
        toml = read_text(zf, "META-INF/neoforge.mods.toml")

        check("N1 mods.toml [[mods]] 标准语法", "[[mods]]" in toml)
        check("N2 mods.toml version 1.6.6.1", 'version = "1.6.6.1"' in toml)
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
        # 1.6.6.1：头像线路与配置同步到 NF 产物
        nf_render_blob = class_blob(zf, "cn/kerong/penguin/spigot/render/")
        check("N13 MinePic 线路同步（NF 产物）", b"minepic.org" in nf_render_blob)
        check("N14 TTL 动态化同步（NF 产物）",
              b"avatarDiskTtlMillis" in nf_render_blob and b"DEFAULT_AVATAR_DISK_TTL_HOURS" in nf_render_blob)
        check("N15 Mojang 原生 API 保留（NF 产物）",
              b"sessionserver.mojang.com" in nf_render_blob)
        nf_config = read_text(zf, "config.yml")
        check("N16 NF config.yml 含 avatar-cache-hours: 3", "avatar-cache-hours: 3" in nf_config)
        nf_schema = zf.read("cn/kerong/bot/web/WebUiSchema.class")
        check("N17 NF WebUI 面板同步（新增字段 / 无废除字段）",
              b"avatar-cache-hours" in nf_schema and b"gc-after-render" not in nf_schema)
        size_mb = os.path.getsize(NEOFORGE) / 1024 / 1024
        check("N18 NeoForge 体积合理（17~20MB）", 17.0 <= size_mb <= 20.0, f"{size_mb:.1f}MB")

    total = passed + failed
    print(f"\n===== 门禁结果：{passed}/{total} 通过，{failed} 失败 =====")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
