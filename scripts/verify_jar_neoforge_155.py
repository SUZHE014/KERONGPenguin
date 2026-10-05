#!/usr/bin/env python3
"""
KERONGPenguin NeoForge 分发模组 JAR 门禁验证（1.5.5：在线排行榜 + 签到金币自适应 + 图片分段，模组线同步发版）。

用法：python3 scripts/verify_jar_neoforge_155.py [jar] [--base 基底jar]
缺省验证 build/dist/KERONGPenguin_NeoForge-1.21.1-1.5.5.jar。
在 1.5.4.5 门禁（112 项基线）之上新增：
  - 1.5.5 排行榜：LeaderboardCommands / LeaderboardRenderer 类族存在，
    GroupMessageHandler 注册、MenuManager 面板按钮、PublicCommands 帮助条目、
    QqBindManager.listQuuids / leaderboardTop、ConfigManager 版本 14 与新配置键；
  - 1.5.5 签到金币自适应：CheckInCommands.isEconomyAvailable（反射检测 +
    Throwable 兑底，NeoForge 无 Vault 生态走不发不记分支）；
  - 1.5.5 图片分段：OnlineListService.renderPages / sendPages、
    OnlineListRenderer.measurePageHeight / MAX_PLAYERS_PER_PAGE；
  - FixupTool 字节修复零残留：无 com/destroystokyo 引用、无 font$common_Bot
    合成名、无 org/bukkit/profile 泄漏（皮肤链已映射到兼容层真实类型）；
  - 差异白名单：与 1.5.4.5 基底 JAR 逐条目比对，仅允许补丁携带的 21 改 8 增
    与 mods.toml / MANIFEST / config.yml 存在差异（其余逐字节一致——重定位库
    与全部模组类零改动，1.5.4.5 实测通过的运行时行为按构造保留）。
若本地存在已安装的 NeoForge server（tooling/nf-install/server），额外执行：
  - 平台包冲突扫描（split package 门禁，防 ResolutionException）；
  - 与 kotlin-stdlib jar 的包交集扫描（1.5.4.3 重定位门禁）。
"""
import argparse
import re
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "relocate"))
sys.path.insert(0, "/home/z/my-project/scripts")
from class_strings import read_constant_pool

_DEFAULT_JAR = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_NeoForge-1.21.1-1.5.5.jar"
# 基底 JAR（官网 1.5.4.5，存在则执行差异白名单校验）
BASE_JAR = "/home/z/my-project/nf-work/KERONGPenguin_NeoForge-1.21.1-1.5.4.5.jar"

# 本地安装的 NeoForge 21.1.248 server（可选，存在则做平台冲突扫描）
NF_LIBS = Path("/home/z/my-project/tooling/nf-install/server/libraries")
# kotlin-stdlib 复现 jar（可选，存在则做包交集扫描）
KOTLIN_REPRO = Path("/home/z/my-project/tooling/kotlin-stdlib-repro.jar")

# 1.5.4.3 起重定位的第三方库前缀（与 package_jar_neoforge.py 保持一致）
RELOC_PREFIXES = (
    "kotlin/", "kotlinx/", "okhttp3/", "okio/", "com/alibaba/",
    "org/yaml/", "org/bouncycastle/", "org/jsoup/", "org/java_websocket/",
    "org/jetbrains/", "org/intellij/",
)
SHADE = "cn/huohuas001/shaded/"

results = []


def check(name: str, ok: bool, detail: str = ""):
    results.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (f"  {detail}" if detail and not ok else ""))


def packages_of(zf, names=None):
    names = names or zf.namelist()
    pkgs = set()
    for n in names:
        if n.endswith(".class") and not n.startswith("META-INF/"):
            parts = n.split("/")[:-1]
            if parts:
                pkgs.add("/".join(parts))
    return pkgs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jar", nargs="?", default=_DEFAULT_JAR)
    ap.add_argument("--base", default=BASE_JAR)
    args = ap.parse_args()
    JAR = args.jar
    with zipfile.ZipFile(JAR) as zf:
        names = set(zf.namelist())
        dir_count = sum(1 for n in names if n.endswith("/"))

        # zip 完整性
        bad = zf.testzip()
        check("zip 完整性（testzip）", bad is None, str(bad))

        # 1. 模组元数据
        toml = zf.read("META-INF/neoforge.mods.toml").decode("utf-8") if "META-INF/neoforge.mods.toml" in names else ""
        check("neoforge.mods.toml 存在", bool(toml))
        check("mods.toml modId=kerongpenguin", 'modId = "kerongpenguin"' in toml)
        check("mods.toml version=1.5.5", re.search(r'^version = "1\.5\.5"', toml, re.M) is not None)
        check("mods.toml modLoader=javafml", 'modLoader = "javafml"' in toml)
        check("mods.toml 依赖 neoforge 21.1+", "versionRange = \"[21.1.0,)\"" in toml)
        check("mods.toml 依赖 minecraft 1.21.1", "versionRange = \"[1.21.1,1.22)\"" in toml)

        # 2. 模组入口与兼容层
        must = [
            "cn/huohuas001/huhobotPenguin/neoforge/HuHoBotNeoForge.class",
            "cn/huohuas001/huhobotPenguin/neoforge/NeoForgeEvents.class",
            "cn/huohuas001/huhobotPenguin/neoforge/NeoForgeCommands.class",
            "cn/huohuas001/huhobotPenguin/neoforge/NeoForgeConsoleExecutor.class",
            "cn/huohuas001/huhobotPenguin/neoforge/LegacyText.class",
            "cn/huohuas001/huhobotPenguin/neoforge/NeoServerRef.class",
            "cn/huohuas001/bot/compat/SptCompat.class",
            "org/bukkit/Bukkit.class",
            "org/bukkit/OfflinePlayer.class",
            "org/bukkit/ChatColor.class",
            "org/bukkit/plugin/PluginManager.class",
            "org/bukkit/plugin/java/JavaPlugin.class",
            "org/bukkit/scheduler/BukkitScheduler.class",
            "org/bukkit/entity/Player.class",
            "org/bukkit/entity/NeoPlayer.class",
            "org/bukkit/entity/PlayerProfile.class",
            "org/bukkit/entity/EntityType.class",
            "org/bukkit/Material.class",
            "org/bukkit/Statistic.class",
            "org/bukkit/World.class",
            "org/bukkit/configuration/file/YamlConfiguration.class",
            "org/bukkit/configuration/file/MemorySection.class",
        ]
        for entry in must:
            check(f"存在 {entry.split('/')[-1]}", entry in names)

        # 3. common-Bot 同源业务类（含 1.5.4 乱码修复）
        must_business = [
            "cn/huohuas001/bot/QClient.class",
            "cn/huohuas001/bot/HuHoBot.class",
            "cn/huohuas001/bot/events/GroupMessageHandler.class",
            "cn/huohuas001/bot/tools/QqText.class",
            "cn/huohuas001/huhobotPenguin/spigot/qqbind/QqBindManager.class",
            "cn/huohuas001/huhobotPenguin/spigot/qqbind/BeijingTimeUtil.class",
            "cn/huohuas001/huhobotPenguin/spigot/stats/PlayerStatsManager.class",
            "cn/huohuas001/huhobotPenguin/spigot/stats/QueryInfoService.class",
            "cn/huohuas001/huhobotPenguin/spigot/stats/OnlineListService.class",
            "cn/huohuas001/huhobotPenguin/spigot/render/InfoCardRenderer.class",
            "cn/huohuas001/huhobotPenguin/spigot/render/OnlineListRenderer.class",
            "cn/huohuas001/huhobotPenguin/spigot/render/CardRenderPool.class",
            "cn/huohuas001/huhobotPenguin/spigot/manager/ConfigManager.class",
            "cn/huohuas001/huhobotPenguin/spigot/commands/CommandOutputAppender.class",
            "cn/huohuas001/bot/web/WebUiServer.class",
        ]
        for entry in must_business:
            check(f"存在 {entry.split('/')[-1]}（共用源码）", entry in names)

        # 3'. 1.5.5 排行榜 / 签到金币自适应 / 图片分段（模组线同步）
        lb_classes = [
            "cn/huohuas001/bot/events/commands/LeaderboardCommands.class",
            "cn/huohuas001/bot/events/commands/LeaderboardCommands$Companion.class",
            "cn/huohuas001/bot/events/commands/LeaderboardCommands$RawEntry.class",
            "cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer.class",
            "cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer$LeaderEntry.class",
            "cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer$LeaderboardData.class",
        ]
        for entry in lb_classes:
            check(f"存在 {entry.split('/')[-1]}", entry in names)
        lbc = zf.read("cn/huohuas001/bot/events/commands/LeaderboardCommands.class")
        check("LeaderboardCommands 命令名 在线排行榜", "在线排行榜".encode() in lbc)
        check("LeaderboardCommands 引用 listQuuids（全量扫描）", b"listQuuids" in lbc)
        check("LeaderboardCommands 引用 getLeaderboardTop（人数配置）", b"getLeaderboardTop" in lbc)
        check("LeaderboardCommands 头像预热线程（PenguinAvatarFetch）", b"PenguinAvatarFetch" in lbc)
        check("LeaderboardCommands 皮肤快照（callSyncMethod）", b"callSyncMethod" in lbc)
        lbr = zf.read("cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer.class")
        check("LeaderboardRenderer 分页渲染（renderPage/measurePageHeight）",
              b"renderPage" in lbr and b"measurePageHeight" in lbr)
        check("LeaderboardRenderer 无 bukkit 引用（纯 Java2D）", b"org/bukkit/" not in lbr)
        gmh = zf.read("cn/huohuas001/bot/events/GroupMessageHandler.class")
        check("GroupMessageHandler 注册 LeaderboardCommands", b"LeaderboardCommands" in gmh)
        mm = zf.read("cn/huohuas001/bot/MenuManager.class")
        check("MenuManager 面板含 在线排行榜 按钮", "在线排行榜".encode() in mm)
        pc = zf.read("cn/huohuas001/bot/events/commands/PublicCommands.class")
        check("PublicCommands 帮助含 在线排行榜 条目", "在线排行榜".encode() in pc)
        cic = zf.read("cn/huohuas001/bot/events/commands/CheckInCommands.class")
        check("CheckInCommands 金币插件检测 isEconomyAvailable", b"isEconomyAvailable" in cic)
        check("CheckInCommands 经济检测反射链（getServicesManager）", b"getServicesManager" in cic)
        qbm = zf.read("cn/huohuas001/huhobotPenguin/spigot/qqbind/QqBindManager.class")
        check("QqBindManager.listQuuids（全量扫描）", b"listQuuids" in qbm)
        check("QqBindManager.leaderboardTop（配置夹逼 1..100）", b"getLeaderboardTop" in qbm)
        ols = zf.read("cn/huohuas001/huhobotPenguin/spigot/stats/OnlineListService.class")
        check("OnlineListService 分段发送 renderPages/sendPages",
              b"renderPages" in ols and b"sendPages" in ols)
        olr = zf.read("cn/huohuas001/huhobotPenguin/spigot/render/OnlineListRenderer.class")
        check("OnlineListRenderer 分页 measurePageHeight/MAX_PLAYERS_PER_PAGE",
              b"measurePageHeight" in olr and b"MAX_PLAYERS_PER_PAGE" in olr)
        cm = zf.read("cn/huohuas001/huhobotPenguin/spigot/manager/ConfigManager.class")
        check("ConfigManager 新配置键 qq-bind.leaderboard.top", b"qq-bind.leaderboard.top" in cm)
        check("ConfigManager 命令名单含 在线排行榜", "在线排行榜".encode() in cm)
        wus = zf.read("cn/huohuas001/bot/web/WebUiSchema.class")
        check("WebUiSchema 含 leaderboard.top 字段", b"leaderboard.top" in wus)

        # 3'.2 FixupTool 字节修复零残留（皮肤链已映射到兼容层真实类型）
        for n in ("cn/huohuas001/bot/events/commands/LeaderboardCommands.class",
                  "cn/huohuas001/huhobotPenguin/spigot/stats/OnlineListService.class"):
            data = zf.read(n)
            check(f"{n.split('/')[-1]} 无 destroystokyo 残留",
                  b"com/destroystokyo/" not in data)
            check(f"{n.split('/')[-1]} 无 org/bukkit/profile 残留",
                  b"org/bukkit/profile/" not in data)
        check("产物无 org/bukkit/profile 泄漏类", not any(
            n.startswith("org/bukkit/profile/") for n in names))
        for n in ("cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer.class",
                  "cn/huohuas001/huhobotPenguin/spigot/render/OnlineListRenderer.class"):
            check(f"{n.split('/')[-1]} 无 font$common_Bot 残留",
                  b"font$common_Bot" not in zf.read(n))

        # 3'.3 1.5.4.5 延迟修复基线（继承）
        check("OutboxQueue 类存在", "cn/huohuas001/bot/OutboxQueue.class" in names)
        oq = zf.read("cn/huohuas001/bot/OutboxQueue.class") if "cn/huohuas001/bot/OutboxQueue.class" in names else b""
        check("OutboxQueue 单线程队列（newSingleThreadExecutor）", b"newSingleThreadExecutor" in oq)
        check("OutboxQueue daemon 线程（不阻止 JVM 退出）", b"setDaemon" in oq)
        check("OutboxQueue 线程命名（KERONGPenguin-QQ-Outbox）", b"KERONGPenguin-QQ-Outbox" in oq)
        check("OutboxQueue 停机清空（awaitTermination）", b"awaitTermination" in oq)
        check("OutboxQueue 引用 shaded kotlin（自身已重定位）", (SHADE + "kotlin/").encode() in oq)
        qc = zf.read("cn/huohuas001/bot/QClient.class")
        check("QClient 引用 OutboxQueue（发送入队）", b"OutboxQueue" in qc)
        check("QClient 同步内核 sendPayloadToGroupsSync", b"sendPayloadToGroupsSync" in qc)
        check("QClient 同步内核 sendTextToGroupsSync", b"sendTextToGroupsSync" in qc)
        gmh = zf.read("cn/huohuas001/bot/events/GroupMessageHandler.class")
        check("GroupMessageHandler 审核走 submitAsync（不阻塞 WSS 线程）", b"submitAsync" in gmh)

        # 3''. 差异白名单（与 1.5.4.5 基底逐条目比对；构造上其余零改动）
        import os as _os
        if _os.path.isfile(args.base):
            allow = {
                # 1.5.5 替换的 21 个类（8 新增不计入 changed）
                "cn/huohuas001/bot/MenuManager.class",
                "cn/huohuas001/bot/MenuManager$PanelItem.class",
                "cn/huohuas001/bot/events/GroupMessageHandler.class",
                "cn/huohuas001/bot/events/commands/CheckInCommands.class",
                "cn/huohuas001/bot/events/commands/PublicCommands.class",
                "cn/huohuas001/bot/web/WebUiSchema.class",
                "cn/huohuas001/huhobotPenguin/spigot/qqbind/QqBindManager.class",
                "cn/huohuas001/huhobotPenguin/spigot/qqbind/QqBindManager$ChatMessage.class",
                "cn/huohuas001/huhobotPenguin/spigot/qqbind/QqBindManager$Companion.class",
                "cn/huohuas001/huhobotPenguin/spigot/qqbind/QqBindManager$PendingBind.class",
                "cn/huohuas001/huhobotPenguin/spigot/render/OnlineListRenderer.class",
                "cn/huohuas001/huhobotPenguin/spigot/render/OnlineListRenderer$OnlineEntry.class",
                "cn/huohuas001/huhobotPenguin/spigot/render/OnlineListRenderer$OnlineListData.class",
                "cn/huohuas001/huhobotPenguin/spigot/stats/OnlineListService.class",
                "cn/huohuas001/huhobotPenguin/spigot/stats/OnlineListService$CachedList.class",
                "cn/huohuas001/huhobotPenguin/spigot/stats/OnlineListService$Snapshot.class",
                "cn/huohuas001/huhobotPenguin/spigot/manager/ConfigManager.class",
                "cn/huohuas001/huhobotPenguin/spigot/manager/ConfigManager$Companion.class",
                "META-INF/neoforge.mods.toml",
                "META-INF/MANIFEST.MF",
                "config.yml",
            }
            expected_added = [
                "cn/huohuas001/bot/events/commands/LeaderboardCommands$Companion.class",
                "cn/huohuas001/bot/events/commands/LeaderboardCommands$RawEntry.class",
                "cn/huohuas001/bot/events/commands/LeaderboardCommands$collectEntries$$inlined$compareByDescending$1.class",
                "cn/huohuas001/bot/events/commands/LeaderboardCommands$collectEntries$$inlined$thenBy$1.class",
                "cn/huohuas001/bot/events/commands/LeaderboardCommands.class",
                "cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer$LeaderEntry.class",
                "cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer$LeaderboardData.class",
                "cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer.class",
            ]
            with zipfile.ZipFile(args.base) as bz:
                bn = set(bz.namelist())
                added = sorted(names_set - bn) if (names_set := set(names)) else []
                removed = sorted(bn - names_set)
                check("相对基底新增恰为 8 个排行榜类", added == expected_added, str(added))
                check("相对基底零删除", not removed, str(removed[:5]))
                changed = [n for n in sorted(bn & names_set) if bz.read(n) != zf.read(n)]
                unexpected = [n for n in changed if n not in allow]
                check("相对基底差异全部在白名单内", not unexpected, str(unexpected[:5]))
        else:
            print("SKIP  未找到基底 JAR，跳过差异白名单校验")

        # 4. 乱码修复关键串（GroupMessageHandler 引用 QqText）
        gm = zf.read("cn/huohuas001/bot/events/GroupMessageHandler.class")
        check("GroupMessageHandler 含 QqText 引用", b"QqText" in gm)

        # 4a. 1.5.4.4 暗色遮罩（背景不再模糊）：方法名 darkOverlay 存在于
        #     InfoCardAssets（同文件顶级 object，类名无 $ 嵌套），
        #     旧模糊路径（blurAndDarken / resample / BLUR_SCALE_DIVISOR）全部移除
        icr = zf.read("cn/huohuas001/huhobotPenguin/spigot/render/InfoCardAssets.class")
        check("InfoCardAssets 含 darkOverlay 方法", b"darkOverlay" in icr)
        check("InfoCardAssets 无 blurAndDarken 残留", b"blurAndDarken" not in icr)
        check("InfoCardAssets 无 resample 残留", b"resample" not in icr)
        check("InfoCardAssets 无 BLUR_SCALE_DIVISOR 残留", b"BLUR_SCALE_DIVISOR" not in icr)

        # 4b. help 子命令修复（/huhobot help 与 /hb help 字面量注册）
        nc = zf.read("cn/huohuas001/huhobotPenguin/neoforge/NeoForgeCommands.class")
        check("NeoForgeCommands 含 help 子命令引用（≥2 处）", nc.count(b"help") >= 2,
              f"实际 {nc.count(b'help')} 处")

        # 5. 运行时依赖并入（1.5.4.3 起：kotlin/okhttp/okio/fastjson/snakeyaml/BC 已重定位）
        deps = [
            ("kloping SDK（原位不重定位）", "io/github/kloping/qqbot/Starter.class"),
            ("shaded kotlin-stdlib", SHADE + "kotlin/jvm/internal/Intrinsics.class"),
            ("shaded kotlin.collections", SHADE + "kotlin/collections/CollectionsKt.class"),
            ("shaded okhttp", SHADE + "okhttp3/OkHttpClient.class"),
            ("shaded okio", SHADE + "okio/Buffer.class"),
            ("shaded fastjson", SHADE + "com/alibaba/fastjson/JSON.class"),
            ("shaded snakeyaml", SHADE + "org/yaml/snakeyaml/Yaml.class"),
            ("shaded BouncyCastle", SHADE + "org/bouncycastle/jce/provider/BouncyCastleProvider.class"),
            ("shaded Java-WebSocket", SHADE + "org/java_websocket/client/WebSocketClient.class"),
        ]
        for label, entry in deps:
            check(f"依赖并入 {label}", entry in names)

        # 5b. ⚠️ 重定位硬门禁：重定位前缀下不得残留任何 .class
        for p in RELOC_PREFIXES:
            left = [n for n in names if n.startswith(p) and n.endswith(".class")]
            check(f"无 {p}*.class（已重定位）", not left, f"残留 {left[:3]}")
        check("无 META-INF/versions 条目", not any(n.startswith("META-INF/versions/") for n in names))
        check("services java.security.Provider 行已重写",
              all(
                  line.strip().startswith(SHADE.replace("/", "."))
                  for line in zf.read("META-INF/services/java.security.Provider").decode().splitlines()
                  if line.strip() and not line.startswith("#")
              ) if "META-INF/services/java.security.Provider" in names else True,
              "存在未重写的 provider 行")

        # 5c. ⚠️ 常量池结构性残留扫描（Class/NameAndType 引用必须全部重定位；
        # @Metadata d2 反射元数据字符串不判失败——仅 kotlin-reflect 读取，本包未携带）
        struct_hits = 0
        for n in names:
            if not n.endswith(".class"):
                continue
            try:
                consts, _, _ = read_constant_pool(zf.read(n))
            except Exception:
                struct_hits += 1
                continue
            for entry in consts:
                if not entry:
                    continue
                if entry[0] == "ref" and entry[1] == 7:
                    name_entry = consts[entry[2]]
                    cname = name_entry[1] if name_entry and name_entry[0] == "utf8" else ""
                    if any(cname.startswith(p) for p in RELOC_PREFIXES):
                        struct_hits += 1
                elif entry[0] == "ref2" and entry[1] == 12:
                    desc_entry = consts[entry[3]]
                    desc = desc_entry[1] if desc_entry and desc_entry[0] == "utf8" else ""
                    if any(f"L{p}" in desc for p in RELOC_PREFIXES):
                        struct_hits += 1
        check("常量池结构性引用零残留", struct_hits == 0, f"残留 {struct_hits} 处")

        # 5d. 业务类引用 shaded kotlin（证明自身字节码已同步重写）
        qclient = zf.read("cn/huohuas001/bot/QClient.class")
        check("QClient 引用 shaded kotlin", (SHADE + "kotlin/").encode() in qclient)
        compat = zf.read("cn/huohuas001/bot/compat/SptCompat.class")
        check("SptCompat 引用 shaded WebSocketClient", (SHADE + "org/java_websocket/").encode() in compat)

        # 6. 模组资源
        resources = [
            "config.yml",
            "fonts/NotoSansSC-Regular-subset.ttf",
            "fonts/NotoSansSC-Bold-subset.ttf",
            "Markdown/online.md",
        ]
        for entry in resources:
            check(f"资源 {entry}", entry in names)
        cfg = zf.read("config.yml").decode("utf-8")
        check("config.yml 含 leaderboard 配置段", "leaderboard:" in cfg)
        check("config.yml 含 在线排行榜 命令开关", "在线排行榜: true" in cfg)
        check("config.yml 兼容内置版本 13（运行时自动升 14）", "config-version: 13" in cfg)

        # 7. 生态安全：无泄漏 / 无模块描述 / 无签名
        check("无 net/minecraft 类泄漏", not any(n.startswith("net/minecraft/") for n in names))
        check("无 net/neoforged 类泄漏", not any(n.startswith("net/neoforged/") for n in names))
        check("无 module-info.class", not any(n == "module-info.class" or n.endswith("/module-info.class") for n in names))
        check("无签名文件 (.SF/.RSA/.DSA)", not any(
            n.startswith("META-INF/") and n.rsplit(".", 1)[-1] in {"SF", "RSA", "DSA"} for n in names))
        check("无 plugin.yml（Spigot 标记必须缺失）", "plugin.yml" not in names)
        check("无 server-Spigot 字节码泄漏", not any(
            n.startswith("cn/huohuas001/huhobotPenguin/spigot/events/") or
            n.startswith("cn/huohuas001/huhobotPenguin/spigot/commands/QqCommand") or
            n.startswith("cn/huohuas001/huhobotPenguin/spigot/commands/HuHoBotCommand") or
            n.startswith("cn/huohuas001/huhobotPenguin/spigot/commands/BukkitConsoleSender") or
            n.startswith("cn/huohuas001/huhobotPenguin/spigot/commands/HybridCommand") or
            n.startswith("cn/huohuas001/huhobotPenguin/spigot/HuHoBotSpigot")
            for n in names))
        check("无旧 kotlin_module 残留", "META-INF/common-Bot.kotlin_module" not in names and
              "META-INF/server-Spigot.kotlin_module" not in names)
        check("含模组 kotlin_module", "META-INF/server-NeoForge.kotlin_module" in names)

        # 8. 目录条目（SDK 组件扫描；1.5.4.3 丢弃 META-INF/versions 后约 748 个，
        # 阈值调整为 >700，另验 kloping 包目录条目存在——扫描兜底的关键依赖）
        check("目录条目 > 700", dir_count > 700, f"实际 {dir_count}")
        for d in ("io/github/kloping/", "io/github/kloping/qqbot/", "io/github/kloping/spt/"):
            check(f"kloping 目录条目存在 {d}", d in names)

        # 8b. ⚠️ 平台重复包硬门禁（split package → 模块解析 ResolutionException）
        check("无 org/slf4j 字节码（平台 slf4j-api 提供）", not any(
            n.startswith("org/slf4j/") and n.endswith(".class") for n in names))
        check("无 com/google/gson 字节码（平台 gson 提供）", not any(
            n.startswith("com/google/gson/") and n.endswith(".class") for n in names))
        check("无 slf4j services 声明残留", not any(
            n.startswith("META-INF/services/org.slf4j") for n in names))
        check("无 slf4j/gson maven 元数据残留", not any(
            n.startswith("META-INF/maven/org.slf4j/") or
            n.startswith("META-INF/maven/com.google.code.gson/") for n in names))

        # 8c. 与本地已安装 NeoForge 21.1.248 server libraries 全量包交集（深度门禁）
        if NF_LIBS.is_dir():
            platform_pkgs = set()
            for lib in NF_LIBS.rglob("*.jar"):
                with zipfile.ZipFile(lib) as lz:
                    ln = lz.namelist()
                    for n in ln:
                        if n.endswith(".class") and not n.startswith("META-INF/"):
                            parts = n.split("/")[:-1]
                            if parts:
                                platform_pkgs.add("/".join(parts))
            mod_pkgs = packages_of(zf, names)
            clash = sorted(mod_pkgs & platform_pkgs)
            check("与 NeoForge 21.1.248 平台包零交集", not clash, str(clash[:8]))
        else:
            print("SKIP  未找到本地 NeoForge server libraries，跳过平台交集扫描")

        # 8d. 与 kotlin-stdlib jar 包交集（1.5.4.3 重定位门禁：模拟 KFF kotlin.stdlib 共存）
        if KOTLIN_REPRO.is_file():
            with zipfile.ZipFile(KOTLIN_REPRO) as kz:
                kpkgs = packages_of(kz)
            mod_pkgs = packages_of(zf, names)
            clash = sorted(mod_pkgs & kpkgs)
            check("与 kotlin-stdlib 包零交集（KFF 共存）", not clash, str(clash[:8]))
        else:
            print("SKIP  未找到 kotlin-stdlib 复现 jar，跳过 KFF 共存交集扫描")

        # 9. MANIFEST
        manifest = zf.read("META-INF/MANIFEST.MF").decode("utf-8")
        check("MANIFEST 含 Implementation-Title", "KERONGPenguin-NeoForge" in manifest)
        check("MANIFEST Automatic-Module-Name=kerongpenguin", "Automatic-Module-Name: kerongpenguin" in manifest)

    failed = [r for r in results if not r[1]]
    print()
    print(f"共 {len(results)} 项，失败 {len(failed)} 项")
    if failed:
        for name, _, detail in failed:
            print(f"  FAIL: {name} {detail}")
        sys.exit(1)
    print("=== NeoForge 模组 JAR 全部验证通过 ===")


if __name__ == "__main__":
    main()
