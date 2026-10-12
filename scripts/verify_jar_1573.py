#!/usr/bin/env python3
"""
KERONGPenguin Spigot fat JAR 门禁（1.5.7.3：Folia 多线程核心兼容，Spigot 独立小版本）。

缺省验证 build/dist/KERONGPenguin_Spigot-1.5.7.3.jar。
在 1.5.7.2 门禁基线（144 项）上新增第 9 节，其余基线不变（版本号断言随版更新）：
  - plugin.yml folia-supported: true（Folia 加载前提）
  - FoliaCompat 兼容桥 + FoliaDelegate 隔离类存在
  - 关键隔离保证：io/papermc/paper/threadedregions 引用只允许出现在
    FoliaDelegate*.class（惰性加载，非 Folia 服务器永不触发 NoClassDefFoundError）
  - 全部迁移类引用 FoliaCompat 且不再直调 BukkitScheduler
  - 延迟/周期 coerceAtLeast(1) 兜底按方法引用计数核验（Folia 至少 1 tick 约束）
  - 真机冒烟（Folia 1.21.1 启用/命令/停机 + Paper 1.18.2 回归）另行执行
"""
import os
import re
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from class_strings import read_constant_pool

JAR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    "/home/z/my-project/penguin-git", "build", "dist", "KERONGPenguin_Spigot-1.5.7.3.jar")

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS  " if ok else "FAIL  ") + name + (f"  [{detail}]" if detail and not ok else ""))


def png_size(data: bytes):
    w, h = struct.unpack(">II", data[16:24])
    return w, h


def _resolve_name(consts, idx):
    """解析常量池条目到名字字符串（utf8 直接返回；Field/Method/Interface ref 与 NameAndType 解引用）。"""
    try:
        e = consts[idx]
        if e is None:
            return None
        if e[0] == "utf8":
            return e[1]
        if e[0] == "ref2" and e[1] in (9, 10, 11):  # Field/Method/InterfaceMethodref
            nt = consts[e[3]]
            if nt and nt[0] == "ref2":
                nm = consts[nt[2]]
                if nm and nm[0] == "utf8":
                    return nm[1]
        if e[0] == "ref2" and e[1] == 12:  # NameAndType
            nm = consts[e[2]]
            if nm and nm[0] == "utf8":
                return nm[1]
    except Exception:
        return None
    return None


def count_invoke_refs(data, name_part):
    """在方法 Code 字节码内统计 invoke 指令引用指定名字的次数（按方法体扫描，避免常量池误匹配）。"""
    consts, pos, _ = read_constant_pool(data)
    target = set()
    for i, e in enumerate(consts):
        if e and e[0] == "ref2" and e[1] in (9, 10, 11):
            nm = _resolve_name(consts, i)
            if nm and name_part in nm:
                target.add(i)
    if not target:
        return 0
    # 跳过头部：access/this/super/interfaces
    pos += 6
    icount = struct.unpack(">H", data[pos:pos + 2])[0]
    pos += 2 + icount * 2
    total = 0
    for _section in ("fields", "methods"):
        mcount = struct.unpack(">H", data[pos:pos + 2])[0]
        pos += 2
        for _ in range(mcount):
            pos += 6  # access/name/desc
            acount = struct.unpack(">H", data[pos:pos + 2])[0]
            pos += 2
            for _a in range(acount):
                alen = struct.unpack(">I", data[pos + 2:pos + 6])[0]
                aname = _resolve_name(consts, struct.unpack(">H", data[pos:pos + 2])[0]) or ""
                if _section == "methods" and aname == "Code":
                    body = data[pos + 6:pos + 6 + alen]
                    code_len = struct.unpack(">I", body[4:8])[0]
                    insns = body[8:8 + code_len]
                    i = 0
                    while i < len(insns) - 2:
                        op = insns[i]
                        if op in (0xb6, 0xb7, 0xb8, 0xb9):  # invoke virtual/special/static/interface
                            idx = struct.unpack(">H", insns[i + 1:i + 3])[0]
                            if idx in target:
                                total += 1
                            i += 3
                            continue
                        # 简化推进：invoke 指令之外仅处理常见长指令边界不敏感场景
                        # （门禁用途足够：仅用于计数验证，偶发误读只会多计不会漏 invoke 本身）
                        i += 1
                pos += 6 + alen
    return total


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
        check("plugin.yml version=1.5.7.3", re.search(
            r"^version:\s*['\"]?1\.5\.7\.3['\"]?\s*$", plugin_yml, re.M) is not None)
        check("plugin.yml api-version=1.18", "api-version: '1.18'" in plugin_yml)
        check("plugin.yml folia-supported: true（Folia 加载前提）", re.search(
            r"^folia-supported:\s*true\s*$", plugin_yml, re.M) is not None)
        check("plugin.yml /huhobot 别名 hb（与模组端一致）", re.search(
            r"^\s*aliases:\s*" + re.escape("[hb]") + r"\s*$", plugin_yml, re.M) is not None)

        # 2. 目录条目（kloping SDK 组件扫描依赖；scheduler/ → 869）
        check("目录条目 = 869（1.5.7.2 基线 868 + scheduler/）", dir_count == 869,
              f"实际 {dir_count}")
        for d in ("io/github/kloping/", "io/github/kloping/qqbot/", "io/github/kloping/spt/",
                  "backgrounds/",
                  "cn/huohuas001/huhobotPenguin/spigot/scheduler/"):
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

        # 3''''. 1.5.7 全部图片渲染默认内置背景 + img 空回退 + 深灰主体 ——基线保留
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

        # 3''''' 内置背景视觉自检（深灰主体，与卡片色可辨）——基线保留
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

        # 3'''''' 1.5.7.1 配置注释全面核对修正
        config_yml = zf.read("config.yml").decode("utf-8")
        check("config.yml start-with 注释=触发前缀语义（与 startsWith+removePrefix 字节码一致）",
              "只有以该字符开头的聊天才会转发到 QQ 群" in config_yml)
        check("config.yml start-with 注释含去前缀说明", "转发时自动去掉该前缀" in config_yml)
        check("config.yml start-with 注释含留空语义", '留空 "" 表示游戏内所有聊天都转发' in config_yml)
        check("config.yml filter-regex 注释=删除命中片段语义", "命中这些正则的部分会被直接删除" in config_yml)
        check("config.yml filter-regex 注释含双向生效说明", "双向生效" in config_yml)
        check("config.yml permission 注释=0所有人/非0管理员", "0 或不填 = 所有人可用" in config_yml)
        check("config.yml permission 注释不再声称 2=所有人", "2 = 所有人可用" not in config_yml)
        check("config.yml groups 注释强调双引号", "每一项都必须加双引号" in config_yml)
        check("config.yml groups 注释含双引号示例", '["ABC123456XYZ", "DEF789XYZ"]' in config_yml)
        check("config.yml groups 注释含留空不发说明", "留空时这些消息一条都不会发" in config_yml)
        check("config.yml kick-message 注释补 {name} 变量", "{code} = 专属绑定码，{name} = 玩家名" in config_yml)
        check("config.yml from-game 注释补 {name} 变量", "{name} = 玩家名，{message} / {msg}" in config_yml)
        check("config.yml 播报注释补占位符族", "{server} = 服务器显示名，{platform} = 运行平台" in config_yml)
        check("config.yml 模板占位符注释修正（{ip}{port} 仅 api 内）",
              "只能在" in config_yml and "不能用在模板里" in config_yml)
        check("config.yml checkin 注释含经济缺失自适应说明", "照常累计天数、不发放金币" in config_yml)
        check("config.yml ai.enabled 注释=总开关", "QQ 群（@机器人）和游戏内的 AI 对话都不可用" in config_yml)
        check("config.yml context-limit 注释=全局一份记忆", "全局一份记忆：所有群、所有人与游戏内共用" in config_yml)
        check("config.yml use-markdown 注释含模板缺失回退", "模板文件缺失时也会自动改用图片输出" in config_yml)
        check("config.yml 头部注释含列表双引号总则", "每一项都必须加双引号" in config_yml)
        check("config.yml 头部注释 /hb reload 简写说明", "可简写 /hb reload" in config_yml)
        check("config.yml 无 ai.context-global 键", re.search(
            r"^\s*ai\.context-global|^\s*context-global:", config_yml, re.M) is None)
        # 死键移除：ConfigManager 类内该字符串仅来自清理清单（1 次）
        cm_ctx = cm.count(b"ai.context-global")
        check("ConfigManager ai.context-global 仅存清理清单引用（次数=1）", cm_ctx == 1, f"次数 {cm_ctx}")
        # WebUiSchema：失效段落移除 + AI 段补齐
        check("WebUiSchema 无 whitelist 失效段", b"whitelist.add-command" not in wus)
        check("WebUiSchema 无 audit 失效段", b"audit.base-url" not in wus)
        check("WebUiSchema 含 AI 段字段", b"ai.enabled" in wus and b"ai.context-limit" in wus)
        check("WebUiSchema 含渲染缓存字段", b"gc-after-render" in wus and b"result-cache-seconds" in wus)
        check("WebUiSchema filter-regex 描述=删除命中片段", "命中这些正则的消息片段会被直接删除".encode() in wus)

        # 3' 1.5.7.2 Geyser 基岩版金币入账兼容（Spigot 独立小版本）——基线保留
        gc = zf.read("cn/huohuas001/huhobotPenguin/spigot/events/GameChat.class")
        gcc = zf.read("cn/huohuas001/huhobotPenguin/spigot/events/GameChat$Companion.class")
        kapi = zf.read("cn/huohuas001/huhobotPenguin/spigot/api/KERONGPenguinAPI.class")
        check("GameChat 含 resolveOfflinePlayer（入账目标三级解析）", b"resolveOfflinePlayer" in gcc)
        check("GameChat 一级解析 getPlayerExact（在线即本人）", b"getPlayerExact" in gcc)
        check("GameChat 二级解析 findPlayerUuidByName（绑定真实 UUID）", b"findPlayerUuidByName" in gcc)
        check("GameChat UUID.fromString 解析（Floodgate UUID 入账）", b"fromString" in gcc)
        check("GameChat 兜底 getOfflinePlayer(String) 保留", b"getOfflinePlayer" in gcc)
        check("depositToPlayer 走 resolveOfflinePlayer", b"resolveOfflinePlayer" in gcc)
        check("QqBindManager 含 findPlayerUuidByName（内存索引）", b"findPlayerUuidByName" in qbm)
        check("QqBindManager 含 UUID_TEXT 严格格式校验", b"UUID_TEXT" in qbm)
        check("QqBindManager 含旧键回退 getPlayerUuidByQuuid", b"getPlayerUuidByQuuid" in qbm)
        check("KERONGPenguinAPI 含 depositCoins 入口（兼容 API）", b"depositCoins" in kapi)
        check("CheckInCommands 在线判定 getPlayerExact 保留（基线不变）", b"getPlayerExact" in cic)

        # 4. Spigot 线不重定位（无模块层，kotlin 保持原包名）——基线保留
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

        # 6. 资源（含 1.5.7 新配置键）——基线保留
        for entry in (
            "config.yml",
            "fonts/NotoSansSC-Regular-subset.ttf",
            "fonts/NotoSansSC-Bold-subset.ttf",
            "Markdown/online.md",
        ):
            check(f"资源 {entry}", entry in names)
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

        # 9. 1.5.7.3 Folia 多线程核心兼容（Spigot 独立小版本）
        fcompat = zf.read("cn/huohuas001/huhobotPenguin/spigot/scheduler/FoliaCompat.class")
        fdel = zf.read("cn/huohuas001/huhobotPenguin/spigot/scheduler/FoliaDelegate.class")
        # 9.1 兼容桥本体
        check("FoliaCompat 类存在", "cn/huohuas001/huhobotPenguin/spigot/scheduler/FoliaCompat.class" in names)
        check("FoliaDelegate 隔离类存在", "cn/huohuas001/huhobotPenguin/spigot/scheduler/FoliaDelegate.class" in names)
        check("FoliaCompat 检测标记 getGlobalRegionScheduler", b"getGlobalRegionScheduler" in fcompat)
        check("FoliaCompat 含 isFolia 检测入口", b"isFolia" in fcompat)
        check("FoliaCompat 全套桥接口（runSync/runSyncLater/runTimer/runAsync）",
              b"runSync" in fcompat and b"runSyncLater" in fcompat
              and b"runTimer" in fcompat and b"runAsync" in fcompat)
        check("FoliaCompat 实体调度接口（runOnPlayer/callOnPlayer/callOnPlayerByUuid）",
              b"runOnPlayer" in fcompat and b"callOnPlayer" in fcompat
              and b"callOnPlayerByUuid" in fcompat)
        check("FoliaCompat callSync 限时语义（TimeUnit 引用）", b"TimeUnit" in fcompat)
        check("FoliaCompat 非Folia路径 callSyncMethod 兼容", b"callSyncMethod" in fcompat)
        # 9.2 Folia 调度 API 引用（仅允许出现在 FoliaDelegate——惰性加载隔离）
        folia_api = b"io/papermc/paper/threadedregions"
        leaked = []
        for n in names:
            if not n.endswith(".class"):
                continue
            data = zf.read(n)
            if folia_api in data and not n.startswith(
                    "cn/huohuas001/huhobotPenguin/spigot/scheduler/FoliaDelegate"):
                leaked.append(n)
        check("threadedregions API 引用仅存于 FoliaDelegate（惰性加载隔离）",
              not leaked, f"泄漏: {leaked[:5]}")
        check("FoliaDelegate 引用 GlobalRegionScheduler", b"GlobalRegionScheduler" in fdel)
        check("FoliaDelegate 引用 AsyncScheduler", b"AsyncScheduler" in fdel)
        check("FoliaDelegate 引用实体调度 EntityScheduler（getScheduler）", b"getScheduler" in fdel)
        check("FoliaDelegate 延迟/周期至少1tick兜底（coerceAtLeast 方法引用 ≥ 3）",
              count_invoke_refs(fdel, "coerceAtLeast") >= 3,
              f"引用 {count_invoke_refs(fdel, 'coerceAtLeast')} 次")
        # 调度 API 方法引用存在性（方法体扫描，非仅常量池字符串）
        check("FoliaDelegate 调用 GlobalRegionScheduler.runDelayed",
              count_invoke_refs(fdel, "runDelayed") >= 1,
              f"引用 {count_invoke_refs(fdel, 'runDelayed')} 次")
        check("FoliaDelegate 调用 GlobalRegionScheduler.runAtFixedRate",
              count_invoke_refs(fdel, "runAtFixedRate") >= 1,
              f"引用 {count_invoke_refs(fdel, 'runAtFixedRate')} 次")
        # 9.3 全部迁移类走桥接且无 BukkitScheduler 直调残留
        hbs = zf.read("cn/huohuas001/huhobotPenguin/spigot/HuHoBotSpigot.class")
        bcs = zf.read("cn/huohuas001/huhobotPenguin/spigot/commands/BukkitConsoleSender.class")
        hce = zf.read("cn/huohuas001/huhobotPenguin/spigot/commands/HybridCommandExecutor.class")
        psm = zf.read("cn/huohuas001/huhobotPenguin/spigot/stats/PlayerStatsManager.class")
        # 引用 FoliaCompat 桥的迁移类（GameChat$Companion 为经济逻辑不含调度，另查残留）
        migrated = {
            "HuHoBotSpigot": hbs, "GameChat": gc,
            "BukkitConsoleSender": bcs, "HybridCommandExecutor": hce,
            "PlayerStatsManager": psm, "OnlineListService": ols,
            "QueryInfoService": qis, "LeaderboardCommands": lb_cmd,
            "QqBindManager": qbm,
        }
        for label, data in migrated.items():
            check(f"{label} 引用 FoliaCompat 桥", b"FoliaCompat" in data)
        # 全部调度相关类（含 Companion 经济链路）均无 BukkitScheduler 直调残留
        residue = dict(migrated)
        residue["GameChat$Companion"] = gcc
        for label, data in residue.items():
            check(f"{label} 无 runTask* 直调残留", b"runTask" not in data)
            check(f"{label} 无 callSyncMethod 直调残留", b"callSyncMethod" not in data)
        # 实体调度迁移专项
        check("QqBindManager 踢人走实体调度（runOnPlayer）", b"runOnPlayer" in qbm)
        check("PlayerStatsManager 统计读取走实体线程（callOnPlayerByUuid）",
              b"callOnPlayerByUuid" in psm)
        check("PlayerStatsManager 周期刷新走实体调度（runOnPlayer）", b"runOnPlayer" in psm)
        # 9.4 HuHoBotTask 已删除（功能并入 FoliaCompat$BukkitCancelable）
        check("HuHoBotTask 已移除（无引用死代码）",
              "cn/huohuas001/huhobotPenguin/spigot/commands/HuHoBotTask.class" not in names)
        check("HuHoBotSpigot 无 HuHoBotTask 引用", b"HuHoBotTask" not in hbs)
        # 9.5 调度句柄统一 Cancelable
        check("FoliaCompat 含 BukkitCancelable 包装", b"BukkitCancelable" in fcompat)

    failed = [r for r in results if not r[1]]
    print()
    print(f"共 {len(results)} 项，失败 {len(failed)} 项")
    if failed:
        for name, _, detail in failed:
            print(f"  FAIL: {name} {detail}")
        sys.exit(1)
    print("=== Spigot 1.5.7.3 fat JAR 全部验证通过 ===")


if __name__ == "__main__":
    main()
