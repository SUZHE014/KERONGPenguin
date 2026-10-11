#!/usr/bin/env python3
"""
KERONGPenguin Spigot fat JAR 门禁（1.5.6：排行榜内置专属背景 + 单图 15 人）。

缺省验证 build/dist/KERONGPenguin_Spigot-1.5.6.1.jar。
在 1.5.5 门禁基线上新增第 3''' 节，其余基线不变：
  - 1.5.6 内置背景：backgrounds/leaderboard-default.png 资源存在且为合法 PNG；
    InfoCardAssets 含 processedBuiltinBackground / BUILTIN_BACKGROUND_RESOURCE；
    LeaderboardCommands 背景开关分支（leaderboardRandomBackground / processedBuiltinBackground）
  - 1.5.6 单图 15 人：QqBindManager.leaderboardRandomBackground；
    ConfigManager 配置版本 15 与 qq-bind.leaderboard.random-background 键；
    config.yml top: 15 / random-background: false / config-version: 15
  - 1.5.5 基线全保留（在线排行榜类族 / 金币自适应 / 分段发送 / OutboxQueue / 暗色遮罩）
  - Spigot 线不重定位；kloping SDK / 字体 / Markdown / config 资源齐备
  - 无 NeoForge 泄漏
"""
import os
import re
import sys
import zipfile

JAR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    "/home/z/my-project/penguin-git", "build", "dist", "KERONGPenguin_Spigot-1.5.6.1.jar")

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS  " if ok else "FAIL  ") + name + (f"  [{detail}]" if detail and not ok else ""))


def main() -> None:
    if not os.path.isfile(JAR):
        raise SystemExit(f"JAR 不存在: {JAR}")
    with zipfile.ZipFile(JAR) as zf:
        names = zf.namelist()
        dir_count = sum(1 for n in names if n.endswith("/"))
        check("zip 完整性（testzip）", zf.testzip() is None)

        # 1. 插件元数据
        plugin_yml = zf.read("plugin.yml").decode("utf-8")
        check("plugin.yml 存在", True)
        check("plugin.yml version=1.5.6.1", re.search(
            r"^version:\s*['\"]?1\.5\.6\.1['\"]?\s*$", plugin_yml, re.M) is not None)
        check("plugin.yml api-version=1.18", "api-version: '1.18'" in plugin_yml)

        # 2. 目录条目（kloping SDK 组件扫描依赖；1.5.6 新增 backgrounds/ → 868）
        check("目录条目 = 868（1.5.6 基线）", dir_count == 868, f"实际 {dir_count}")
        for d in ("io/github/kloping/", "io/github/kloping/qqbot/", "io/github/kloping/spt/",
                  "backgrounds/"):
            check(f"目录条目存在 {d}", d in names)

        # 3. 1.5.4.5 延迟修复 ——基线保留
        check("OutboxQueue 类存在", "cn/huohuas001/bot/OutboxQueue.class" in names)
        oq = zf.read("cn/huohuas001/bot/OutboxQueue.class") if "cn/huohuas001/bot/OutboxQueue.class" in names else b""
        check("OutboxQueue 单线程队列（newSingleThreadExecutor）", b"newSingleThreadExecutor" in oq)
        check("OutboxQueue daemon 线程（不阻止 JVM 退出）", b"setDaemon" in oq)
        check("OutboxQueue 线程命名（排查用）", b"KERONGPenguin-QQ-Outbox" in oq)
        qc = zf.read("cn/huohuas001/bot/QClient.class")
        check("QClient 引用 OutboxQueue（发送入队）", b"OutboxQueue" in qc)
        check("QClient 同步内核 sendPayloadToGroupsSync", b"sendPayloadToGroupsSync" in qc)
        check("QClient 同步内核 sendTextToGroupsSync", b"sendTextToGroupsSync" in qc)
        gmh = zf.read("cn/huohuas001/bot/events/GroupMessageHandler.class")
        check("GroupMessageHandler 审核走 submitAsync", b"submitAsync" in gmh)

        # 3'. 1.5.4.4 暗色遮罩 ——基线保留
        ica = zf.read("cn/huohuas001/huhobotPenguin/spigot/render/InfoCardAssets.class")
        check("InfoCardAssets 含 darkOverlay 方法", b"darkOverlay" in ica)
        check("InfoCardAssets 无 blurAndDarken 残留", b"blurAndDarken" not in ica)
        check("InfoCardAssets 无 resample 残留", b"resample" not in ica)
        check("InfoCardAssets 无 BLUR_SCALE_DIVISOR 残留", b"BLUR_SCALE_DIVISOR" not in ica)

        # 3''. 1.5.5 在线排行榜 + 签到金币自适应 + 分段发送 ——基线保留
        check("LeaderboardCommands 类存在", "cn/huohuas001/bot/events/commands/LeaderboardCommands.class" in names)
        check("LeaderboardRenderer 类存在", "cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer.class" in names)
        lb_cmd = zf.read("cn/huohuas001/bot/events/commands/LeaderboardCommands.class")
        check("LeaderboardCommands 注册命令名 在线排行榜", "在线排行榜".encode() in lb_cmd)
        check("LeaderboardCommands 多张发送间隔控制（sendPages）", b"sendPages" in lb_cmd)
        check("GroupMessageHandler 注册 LeaderboardCommands", b"LeaderboardCommands" in gmh)
        mm = zf.read("cn/huohuas001/bot/MenuManager.class")
        check("MenuManager 快捷面板含 在线排行榜 按钮", "在线排行榜".encode() in mm)
        pc = zf.read("cn/huohuas001/bot/events/commands/PublicCommands.class")
        check("PublicCommands 帮助含 在线排行榜 条目", "在线排行榜".encode() in pc)
        qbm = zf.read("cn/huohuas001/huhobotPenguin/spigot/qqbind/QqBindManager.class")
        check("QqBindManager 含 listQuuids（全量扫描）", b"listQuuids" in qbm)
        check("QqBindManager 含 leaderboardTop（显示人数）", b"leaderboardTop" in qbm)
        cic = zf.read("cn/huohuas001/bot/events/commands/CheckInCommands.class")
        check("CheckInCommands 含 isEconomyAvailable（金币插件检测）", b"isEconomyAvailable" in cic)
        check("CheckInCommands 金币可用分支文案保留", "获得了".encode() in cic)
        lbr = zf.read("cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer.class")
        check("LeaderboardRenderer 每页行数常量（ROWS_PER_PAGE）", b"ROWS_PER_PAGE" in lbr)
        check("LeaderboardRenderer 时长格式化（formatPlayTime）", b"formatPlayTime" in lbr)
        olr = zf.read("cn/huohuas001/huhobotPenguin/spigot/render/OnlineListRenderer.class")
        check("OnlineListRenderer 分段入口 renderPages", b"renderPages" in olr)
        check("OnlineListRenderer 分页高度 measurePageHeight", b"measurePageHeight" in olr)
        check("OnlineListRenderer 每页人数上限 MAX_PLAYERS_PER_PAGE", b"MAX_PLAYERS_PER_PAGE" in olr)
        check("OnlineListRenderer 旧长图入口 measureHeight 兼容保留", b"measureHeight" in olr)
        ols = zf.read("cn/huohuas001/huhobotPenguin/spigot/stats/OnlineListService.class")
        check("OnlineListService 多张发送 sendPages", b"sendPages" in ols)
        check("OnlineListService 整组缓存（pages 字段）", b"pages" in ols)

        # 3'''. 1.5.6 内置专属背景 + 单图 15 人
        check("内置背景资源 backgrounds/leaderboard-default.png 存在",
              "backgrounds/leaderboard-default.png" in names)
        bg_png = zf.read("backgrounds/leaderboard-default.png")
        check("内置背景为合法 PNG（PNG 魔数）", bg_png[:8] == b"\x89PNG\r\n\x1a\n")
        import struct
        w, h = struct.unpack(">II", bg_png[16:24])
        check("内置背景尺寸 1200×1800", (w, h) == (1200, 1800), f"实际 {w}×{h}")
        # 1.5.6.1 背景装饰加密（四角辉光/双缘光带/圆弧/点阵加密）后约 318KB，阈值放宽到 400KB
        check("内置背景体积合理（< 400KB）", 0 < len(bg_png) < 400 * 1024, f"实际 {len(bg_png)}")
        check("InfoCardAssets 含 processedBuiltinBackground", b"processedBuiltinBackground" in ica)
        check("InfoCardAssets 含 BUILTIN_BACKGROUND_RESOURCE 常量", b"BUILTIN_BACKGROUND_RESOURCE" in ica)
        check("InfoCardAssets 含资源路径 backgrounds/leaderboard-default.png",
              b"backgrounds/leaderboard-default.png" in ica)
        check("LeaderboardCommands 背景开关分支（getLeaderboardRandomBackground）",
              b"getLeaderboardRandomBackground" in lb_cmd)
        check("LeaderboardCommands 调用 processedBuiltinBackground",
              b"processedBuiltinBackground" in lb_cmd)
        check("QqBindManager 含 leaderboardRandomBackground（随机背景开关）",
              b"leaderboardRandomBackground" in qbm)
        check("QqBindManager 含配置键 qq-bind.leaderboard.random-background",
              b"qq-bind.leaderboard.random-background" in qbm)
        cm = zf.read("cn/huohuas001/huhobotPenguin/spigot/manager/ConfigManager.class")
        check("ConfigManager 配置版本 15", b"\x00\x0f" in cm and b"CURRENT_CONFIG_VERSION" in cm)
        check("ConfigManager 含 qq-bind.leaderboard.random-background 默认键",
              b"qq-bind.leaderboard.random-background" in cm)
        wus = zf.read("cn/huohuas001/bot/web/WebUiSchema.class")
        check("WebUiSchema 含 leaderboard.random-background 字段",
              b"qq-bind.leaderboard.random-background" in wus)

        # 4. Spigot 线不重定位（无模块层，kotlin 保持原包名）
        check("kotlin 原位未重定位", "kotlin/jvm/internal/Intrinsics.class" in names)
        check("okhttp3 原位未重定位", "okhttp3/OkHttpClient.class" in names)
        check("fastjson 原位未重定位", "com/alibaba/fastjson/JSON.class" in names)
        check("无 shaded 前缀字节码", not any(
            n.startswith("cn/huohuas001/shaded/") for n in names))

        # 5. 关键业务类
        for entry in (
            "cn/huohuas001/bot/QClient.class",
            "cn/huohuas001/bot/HuHoBot.class",
            "cn/huohuas001/huhobotPenguin/spigot/HuHoBotSpigot.class",
            "cn/huohuas001/huhobotPenguin/spigot/render/InfoCardRenderer.class",
            "cn/huohuas001/huhobotPenguin/spigot/render/CardRenderPool.class",
            "cn/huohuas001/huhobotPenguin/spigot/stats/QueryInfoService.class",
            "cn/huohuas001/huhobotPenguin/spigot/stats/OnlineListService.class",
            "cn/huohuas001/huhobotPenguin/spigot/manager/ConfigManager.class",
        ):
            check(f"存在 {entry.split('/')[-1]}", entry in names)

        # 6. 资源（含 1.5.6 新配置键与 15 人默认）
        for entry in (
            "config.yml",
            "fonts/NotoSansSC-Regular-subset.ttf",
            "fonts/NotoSansSC-Bold-subset.ttf",
            "Markdown/online.md",
        ):
            check(f"资源 {entry}", entry in names)
        config_yml = zf.read("config.yml").decode("utf-8")
        check("config.yml 含 commands.在线排行榜 开关", "在线排行榜: true" in config_yml)
        check("config.yml qq-bind.leaderboard.top 默认 15", re.search(
            r"leaderboard:\s*\n(?:\s*#[^\n]*\n)*\s*top:\s*15", config_yml) is not None)
        check("config.yml 含 random-background: false", re.search(
            r"random-background:\s*false", config_yml) is not None)
        check("config.yml 注释含 img 随机图说明（开启后）", "img" in config_yml and "随机" in config_yml)
        check("config.yml config-version 15", re.search(
            r"^config-version:\s*15\b", config_yml, re.M) is not None)

        # 7. 无 NeoForge 泄漏
        check("无 neoforge.mods.toml", "META-INF/neoforge.mods.toml" not in names)
        check("无 org/bukkit shim 泄漏（真 Spigot API 场景）", not any(
            n.startswith("org/bukkit/entity/NeoPlayer") for n in names))
        check("无 server-NeoForge kotlin_module", "META-INF/server-NeoForge.kotlin_module" not in names)

        # 8. 签名与模块描述
        check("无签名文件 (.SF/.RSA/.DSA)", not any(
            n.startswith("META-INF/") and n.rsplit(".", 1)[-1] in {"SF", "RSA", "DSA"} for n in names))
        check("无根级 module-info.class", not any(
            n == "module-info.class" or n.endswith("/module-info.class") and not n.startswith("META-INF/versions/") for n in names))

    failed = [r for r in results if not r[1]]
    print()
    print(f"共 {len(results)} 项，失败 {len(failed)} 项")
    if failed:
        for name, _, detail in failed:
            print(f"  FAIL: {name} {detail}")
        sys.exit(1)
    print("=== Spigot 1.5.6 fat JAR 全部验证通过 ===")


if __name__ == "__main__":
    main()
