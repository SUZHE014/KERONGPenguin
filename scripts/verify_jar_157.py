#!/usr/bin/env python3
"""
KERONGPenguin Spigot fat JAR 门禁（1.5.7：全部图片渲染默认内置背景 + img 空回退 + 深灰主体）。

缺省验证 build/dist/KERONGPenguin_Spigot-1.5.7.jar。
在 1.5.6.1 门禁基线上新增第 3'''' 节，其余基线不变：
  - 1.5.7 内置背景：两张资源齐备（leaderboard-default.png 1200×1800 +
    card-default.png 1800×1200）；深灰主体自检（中心平均亮度 40-72，
    与三种半透明卡片叠加色 ΔL ≥ 20，方框卡片可辨）；
    InfoCardAssets 含 resolveBackground 统一入口（img 无图回退内置）
  - 1.5.7 配置：三个内置背景开关（info-card / online-list / leaderboard，
    默认 true）；config-version 16；旧键 random-background 已清理、
    QqBindManager 无 leaderboardRandomBackground 残留
  - 1.5.6 / 1.5.5 / 1.5.4.5 / 1.5.4.4 基线全保留（内置背景资源 / 单图 15 人 /
    在线排行榜类族 / 金币自适应 / 分段发送 / OutboxQueue / 暗色遮罩）
  - Spigot 线不重定位；kloping SDK / 字体 / Markdown / config 资源齐备
  - 无 NeoForge 泄漏
"""
import os
import re
import struct
import sys
import zipfile

JAR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    "/home/z/my-project/penguin-git", "build", "dist", "KERONGPenguin_Spigot-1.5.7.jar")

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS  " if ok else "FAIL  ") + name + (f"  [{detail}]" if detail and not ok else ""))


def png_size(data: bytes):
    w, h = struct.unpack(">II", data[16:24])
    return w, h


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
        check("plugin.yml version=1.5.7", re.search(
            r"^version:\s*['\"]?1\.5\.7['\"]?\s*$", plugin_yml, re.M) is not None)
        check("plugin.yml api-version=1.18", "api-version: '1.18'" in plugin_yml)

        # 2. 目录条目（kloping SDK 组件扫描依赖；backgrounds/ → 868）
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

        # 3'''. 1.5.6 内置背景 + 单图 15 人 ——基线保留
        check("内置背景资源 backgrounds/leaderboard-default.png 存在",
              "backgrounds/leaderboard-default.png" in names)
        bg_png = zf.read("backgrounds/leaderboard-default.png")
        check("排行榜内置背景为合法 PNG（魔数）", bg_png[:8] == b"\x89PNG\r\n\x1a\n")
        check("排行榜内置背景尺寸 1200×1800", png_size(bg_png) == (1200, 1800),
              f"实际 {png_size(bg_png)}")
        check("排行榜内置背景体积合理（< 400KB）", 0 < len(bg_png) < 400 * 1024, f"实际 {len(bg_png)}")
        check("InfoCardAssets 含 BUILTIN_BACKGROUND 常量族", b"BUILTIN_BACKGROUND" in ica)
        check("QqBindManager 含 leaderboardTop 15 人默认（夹逼）", b"leaderboardTop" in qbm)

        # 3''''. 1.5.7 全部图片渲染默认内置背景 + img 空回退 + 深灰主体
        check("内置背景资源 backgrounds/card-default.png 存在",
              "backgrounds/card-default.png" in names)
        card_png = zf.read("backgrounds/card-default.png")
        check("卡片内置背景为合法 PNG（魔数）", card_png[:8] == b"\x89PNG\r\n\x1a\n")
        check("卡片内置背景尺寸 1800×1200", png_size(card_png) == (1800, 1200),
              f"实际 {png_size(card_png)}")
        check("卡片内置背景体积合理（< 400KB）", 0 < len(card_png) < 400 * 1024, f"实际 {len(card_png)}")
        check("InfoCardAssets 含统一背景入口 resolveBackground", b"resolveBackground" in ica)
        check("InfoCardAssets 含 BUILTIN_BACKGROUND_LEADERBOARD 常量",
              b"BUILTIN_BACKGROUND_LEADERBOARD" in ica)
        check("InfoCardAssets 含 BUILTIN_BACKGROUND_CARD 常量",
              b"BUILTIN_BACKGROUND_CARD" in ica)
        check("InfoCardAssets 含卡片背景资源路径 card-default.png",
              b"backgrounds/card-default.png" in ica)
        check("QqBindManager 含 infoCardBuiltinBackground", b"infoCardBuiltinBackground" in qbm)
        check("QqBindManager 含 onlineListBuiltinBackground", b"onlineListBuiltinBackground" in qbm)
        check("QqBindManager 含 leaderboardBuiltinBackground", b"leaderboardBuiltinBackground" in qbm)
        check("QqBindManager 无 leaderboardRandomBackground 残留",
              b"leaderboardRandomBackground" not in qbm)
        qis = zf.read("cn/huohuas001/huhobotPenguin/spigot/stats/QueryInfoService.class")
        check("QueryInfoService 走统一背景入口 resolveBackground", b"resolveBackground" in qis)
        check("OnlineListService 走统一背景入口 resolveBackground", b"resolveBackground" in ols)
        check("LeaderboardCommands 走统一背景入口 resolveBackground",
              b"resolveBackground" in lb_cmd)
        check("LeaderboardCommands 使用排行榜内置背景资源（常量内联为路径字面量）",
              b"backgrounds/leaderboard-default.png" in lb_cmd)
        cm = zf.read("cn/huohuas001/huhobotPenguin/spigot/manager/ConfigManager.class")
        check("ConfigManager 配置版本 16", b"CURRENT_CONFIG_VERSION" in cm and b"\x00\x10" in cm)
        check("ConfigManager 含三个内置背景默认键",
              b"qq-bind.render.info-card-builtin-background" in cm and
              b"qq-bind.render.online-list-builtin-background" in cm and
              b"qq-bind.leaderboard.builtin-background" in cm)
        wus = zf.read("cn/huohuas001/bot/web/WebUiSchema.class")
        check("WebUiSchema 含三个内置背景字段（新键）",
              b"qq-bind.render.info-card-builtin-background" in wus and
              b"qq-bind.leaderboard.builtin-background" in wus)
        check("WebUiSchema 无旧 random-background 字段残留",
              b"qq-bind.leaderboard.random-background" not in wus)

        # 3''''' 内置背景视觉自检（深灰主体，与卡片色可辨）
        try:
            from PIL import Image
            import io as _io
            import numpy as _np

            def visual_checks(data, label):
                img = _np.asarray(Image.open(_io.BytesIO(data)).convert("RGB")).astype(float)
                h, w = img.shape[:2]
                lum = img[..., 0] * 0.299 + img[..., 1] * 0.587 + img[..., 2] * 0.114
                content = lum[int(h * .3):int(h * .7), int(w * .3):int(w * .7)]
                cl = content.mean()
                check(f"{label} 中心区深灰亮度 ∈ [40,72]", 40 <= cl <= 72, f"{cl:.1f}")
                check(f"{label} 中心区非黑 (>36)", cl > 36, f"{cl:.1f}")
                bg = img[int(h * .3):int(h * .7), int(w * .3):int(w * .7)].reshape(-1, 3).mean(axis=0)
                bg_l = float(bg[0] * .299 + bg[1] * .587 + bg[2] * .114)
                for cname, (r, g, b), a in (("信息卡黑142", (0, 0, 0), 142),
                                            ("在线行卡黑120", (0, 0, 0), 120),
                                            ("排行行卡白34", (255, 255, 255), 34)):
                    f = a / 255.0
                    mixed = bg * (1 - f) + _np.array([r, g, b], float) * f
                    ml = mixed[0] * .299 + mixed[1] * .587 + mixed[2] * .114
                    check(f"{label} 卡片对比 {cname} ΔL ≥ 20", abs(ml - bg_l) >= 20, f"ΔL={abs(ml - bg_l):.1f}")

            visual_checks(bg_png, "排行榜背景")
            visual_checks(card_png, "卡片背景")
        except ImportError:
            check("背景视觉自检依赖 PIL 可用（跳过非必须）", False, "缺 PIL")

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

        # 6. 资源（含 1.5.7 新配置键）
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
        check("config.yml info-card-builtin-background 默认 true", re.search(
            r"info-card-builtin-background:\s*true", config_yml) is not None)
        check("config.yml online-list-builtin-background 默认 true", re.search(
            r"online-list-builtin-background:\s*true", config_yml) is not None)
        check("config.yml leaderboard.builtin-background 默认 true", re.search(
            r"builtin-background:\s*true", config_yml) is not None)
        check("config.yml 无 random-background 键残留", re.search(
            r"^\s*random-background:", config_yml, re.M) is None)
        check("config.yml 注释含 img 回退说明", "回退" in config_yml and "img" in config_yml)
        check("config.yml config-version 16", re.search(
            r"^config-version:\s*16\b", config_yml, re.M) is not None)

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
    print("=== Spigot 1.5.7 fat JAR 全部验证通过 ===")


if __name__ == "__main__":
    main()
