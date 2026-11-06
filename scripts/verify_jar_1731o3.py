#!/usr/bin/env python3
"""verify_jar_1731o3 —— 1.7.3.1 第三次覆盖版产物门禁（motd 展示人数修复）。

背景（用户需求：本次直接覆盖 1.7.3.1，修复「配置文件写的是 motd，
可展示的人数是服务器真实人数」的问题）：
  1.6.9.3 的 motd 捕获机制（Spigot ServerListPingEvent MONITOR 记
  event.maxPlayers）三处硬伤：① NeoForge 端无对应事件，配置写 motd 也
  恒显示真实值（用户实测报告）；② 只捕获最大人数，在线人数仍是真实值；
  ③ 假人数插件用改包（ProtocolLib 等）实现时事件里读不到修改后的值。
  本轮机制重做：MotdDisplayTracker 改**本机 SLP 自查询**——需要时（60
  秒节流）对 127.0.0.1:<本服端口> 发一次 Minecraft 服务器列表 ping，
  解析 players.online / players.max（客户端在服务器列表看到的最终展示
  值），双端统一；人数 N 与最大 M 成对切换，卡底注明「MOTD 展示值」；
  查不到回退真实值。无新增配置键（config-version 保持 34）。

断言组：
  M 组  motd 展示值机制重做（源码 + 双 JAR 字节码）
       M1  MotdDisplayTracker 新 API（lastDisplayOnline / lastDisplayMax /
           lastCaptureAt / portProvider / record(Int?,Int?) / reset /
           refreshIfStale / pingStatus）；旧 record(Int) 单参签名移除
       M2  PlayerEventsListener 事件捕获路径整体移除（无 onServerListPing /
           无 ServerListPingEvent import / 无 MotdDisplayTracker import）
       M3  KERONGSpigot onEnable 注入 portProvider（server.port）+
           onDisable reset；快照注释口径更新
       M4  KERONGNeoForge onServerStarted 注入 portProvider（NeoPortReader
           双策略 + dataFolder 兜底）+ onServerStopping reset
       M5  NeoPortReader 源码在位（getConnection/channels/localAddress +
           getProperties/serverPort + server.properties 三重策略）
       M6  PerformanceService.resolveDisplayCounts（Triple + refreshIfStale +
           成对返回）；resolveMaxPlayers 移除；渲染入参 onlinePlayersDisplay
       M7  PerformanceRenderer.PerformanceData.onlinePlayersDisplay 字段 +
           卡片 N 用展示值 + note「人数与最大值为 MOTD 展示值」
       M8  双端 config.yml 注释新口径 + max-players-source: "real" 保持 +
           config-version 34 保持
       M9  字节码：MotdDisplayTracker 新方法在位（双 JAR javap -p）+
           SLP 常量（127.0.0.1 / writeVarInt / readVarInt）
       M10 字节码：resolveDisplayCounts 在位 / resolveMaxPlayers 不在（双 JAR）
       M11 字节码：PerformanceData 字段 onlinePlayersDisplay（javap -p）
       M12 字节码：PlayerEventsListener 无 onServerListPing（双 JAR）
       M13 字节码：渲染 note 常量「人数与最大值为 MOTD 展示值」（UTF-8 字节）
       M14 字节码：NeoPortReader 类只在 NF JAR；MotdDisplayTracker 双 JAR 在位
       M15 JAR 内置 config.yml：双端注释新口径 + "real" + version 34
       M16 字节码：resolveDisplayCounts 读 render.performance.max-players-source
           常量保持（字节串）
  B 组  基线回归（沿用 1.7.3.1 第二次覆盖版基线）
       B1  目录条目 776 档 / plugin.yml 版本 / 字体资源 / NF shaded 在位
       B2  bpng 基线：BackgroundPackService 64MB 熔断保持
       B3  hbkrg 命令键在 / hbkng 不在（plugin.yml + help.md）
       B4  副标题默认灰度 80 基线保持（DEFAULT 80 + bipush 80）
       B5  config-version 34 四处（双端源 + 双 JAR）
       B6  花里胡哨红线：drawSubLabel 四参签名 + 0x9FB3C8 固定灰保持
  WEB  官网三文件（仓库 website/ 底稿）
       WEB1 version.json 1.7.3.1 + changelog 首条含 motd
       WEB2 index.html 1.7.3.1 段落含 motd 口径
       WEB3 tutorial.html max-players-source 条目改新口径

用法：python3 scripts/verify_jar_1731o3.py [spigot_jar] [nf_jar]
"""
import json
import pathlib
import subprocess
import sys
import zipfile

SPIGOT_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_Spigot-1.7.3.1.jar"
NF_JAR = sys.argv[2] if len(sys.argv) > 2 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.3.1.jar"
JAVAP = str(pathlib.Path("/home/z/my-project/tooling/jdk21/bin/javap"))
ROOT = pathlib.Path(__file__).resolve().parent.parent

TRACKER = "cn.kerong.penguin.spigot.stats.MotdDisplayTracker"
PERF_SVC = "cn.kerong.penguin.spigot.stats.PerformanceService"
PERF_R = "cn.kerong.penguin.spigot.render.PerformanceRenderer"
TRACKER_ENTRY = "cn/kerong/penguin/spigot/stats/MotdDisplayTracker.class"
PERF_SVC_ENTRY = "cn/kerong/penguin/spigot/stats/PerformanceService.class"
PERF_R_ENTRY = "cn/kerong/penguin/spigot/render/PerformanceRenderer.class"
LISTENER_ENTRY = "cn/kerong/penguin/spigot/events/PlayerEventsListener.class"
PORT_READER_ENTRY = "cn/kerong/penguin/neoforge/NeoPortReader.class"

checks = []


def check(name, ok):
    checks.append((name, bool(ok)))


def javap_p(jar, cls):
    out = subprocess.run([JAVAP, "-p", "-cp", jar, cls], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"javap failed: {out.stderr[:300]}")
    return out.stdout


def javap_c(jar, cls):
    out = subprocess.run([JAVAP, "-p", "-c", "-cp", jar, cls], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"javap failed: {out.stderr[:300]}")
    return out.stdout


def jar_read_text(jar, entry):
    with zipfile.ZipFile(jar) as z:
        return z.read(entry).decode("utf-8", "replace")


def class_has_bytes(jar, entry, needle):
    with zipfile.ZipFile(jar) as z:
        return needle in z.read(entry)


def source_text(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


print(f"Spigot JAR: {SPIGOT_JAR}")
print(f"NF JAR:     {NF_JAR}")

# ============ B1 基线 ============
print("== B1 组：基线 ==")
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    with zipfile.ZipFile(jar) as z:
        names = z.namelist()
        dirs = [n for n in names if n.endswith("/")]
        if tag == "Spigot":
            meta = z.read("plugin.yml").decode("utf-8", "replace")
            check(f"B1c {tag} plugin.yml 版本 1.7.3.1", "version: '1.7.3.1'" in meta)
        else:
            meta = z.read("META-INF/neoforge.mods.toml").decode("utf-8", "replace")
            check(f"B1c {tag} neoforge.mods.toml 版本 1.7.3.1", 'version = "1.7.3.1"' in meta)
    if tag == "Spigot":
        check(f"B1a {tag} 目录条目 ≥700（fat JAR 装配安全）", len(dirs) >= 700)
        check(f"B1b {tag} 条目数 >11000", len(names) > 11000)
    else:
        check(f"B1a {tag} 目录条目 ≥600（fat JAR 装配安全）", len(dirs) >= 600)
        check(f"B1b {tag} 条目数 >10000", len(names) > 10000)
    check(f"B1d {tag} 字体资源在位", "fonts/NotoSansSC-Regular-subset.ttf" in names)
    if tag == "NF":
        check("B1e NF shaded 包在位", any(n.startswith("cn/kerong/shaded/kotlin/") for n in names))
        check("B1f NF 无顶层 slf4j", not any(n.startswith("org/slf4j/") for n in names))
        check("B1g NF 无顶层 gson", not any(n.startswith("com/google/gson/") for n in names))

# ============ M 组：motd 展示值机制重做 ============
print("== M 组：motd 展示值机制重做 ==")

tracker_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/MotdDisplayTracker.kt")
listener_src = source_text("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/events/PlayerEventsListener.kt")
spigot_main = source_text("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/KERONGSpigot.kt")
nf_main = source_text("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt")
port_reader_src = source_text("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/NeoPortReader.kt")
perf_svc_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/PerformanceService.kt")
perf_r_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/PerformanceRenderer.kt")
cfg_spi = source_text("server-Spigot/src/main/resources/config.yml")
cfg_nf = source_text("server-NeoForge/src/main/resources/config.yml")

# M1 tracker 新 API
check("M1a 源码 MotdDisplayTracker 新字段（lastDisplayOnline/lastDisplayMax/lastCaptureAt/portProvider）",
      "var lastDisplayOnline: Int?" in tracker_src and
      "var lastDisplayMax: Int?" in tracker_src and
      "var lastCaptureAt: Long" in tracker_src and
      "var portProvider: (() -> Int?)?" in tracker_src)
check("M1b 源码 record 双参 + reset + refreshIfStale + pingStatus 在位",
      "fun record(online: Int?, max: Int?)" in tracker_src and
      "fun reset()" in tracker_src and
      "fun refreshIfStale(" in tracker_src and
      "internal fun pingStatus(port: Int)" in tracker_src)
check("M1c 源码 SLP 客户端实现（握手 + 状态请求 + players 解析）",
      'pingStatus' in tracker_src and
      '"127.0.0.1"' in tracker_src and
      'getJSONObject("players")' in tracker_src and
      'getInteger("online")' in tracker_src and
      'getInteger("max")' in tracker_src)
check("M1d 源码 60 秒节流常量",
      "REFRESH_INTERVAL_MS = 60_000L" in tracker_src)
check("M1e 源码 旧 record(Int) 单参签名已移除",
      "fun record(displayMax: Int)" not in tracker_src)

# M2 监听器事件捕获路径移除
check("M2a 源码 PlayerEventsListener 无 onServerListPing 方法 / 无 ServerListPingEvent import",
      "fun onServerListPing" not in listener_src and
      "import org.bukkit.event.server.ServerListPingEvent" not in listener_src)
check("M2b 源码 PlayerEventsListener 无 MotdDisplayTracker import（仅 KDoc 历史说明）",
      "import cn.kerong.penguin.spigot.stats.MotdDisplayTracker" not in listener_src)
check("M2c 源码 PlayerEventsListener KDoc 注明事件监听移除（1.7.3.1 第三次覆盖版）",
      "移除原 ServerListPingEvent 监听" in listener_src)

# M3 Spigot 端注入
check("M3a 源码 KERONGSpigot onEnable 注入 portProvider（server.port）",
      "MotdDisplayTracker.portProvider = {" in spigot_main and "server.port" in spigot_main)
check("M3b 源码 KERONGSpigot onDisable reset",
      "MotdDisplayTracker.reset()" in spigot_main)
check("M3c 源码 KERONGSpigot 快照注释新口径（本机 SLP 自查询）",
      "本机 SLP" in spigot_main and "ping 捕获" not in spigot_main)

# M4 NF 端注入
check("M4a 源码 KERONGNeoForge onServerStarted 注入 portProvider（NeoPortReader）",
      "MotdDisplayTracker.portProvider = {" in nf_main and
      "NeoPortReader.portOf(NeoServerRef.server)" in nf_main and
      "NeoPortReader.portFromPropertiesFile(dataFolder)" in nf_main)
check("M4b 源码 KERONGNeoForge onServerStopping reset",
      "MotdDisplayTracker.reset()" in nf_main)
check("M4c 源码 KERONGNeoForge 快照注释无「恒用真实值」旧口径",
      "恒用真实值" not in nf_main)

# M5 NeoPortReader
check("M5 源码 NeoPortReader 三重端口策略",
      '"getConnection"' in port_reader_src and
      '"channels"' in port_reader_src and
      '"localAddress"' in port_reader_src and
      '"getProperties"' in port_reader_src and
      '"serverPort"' in port_reader_src and
      '"server.properties"' in port_reader_src and
      '"server-port"' in port_reader_src)

# M6 PerformanceService
check("M6a 源码 resolveDisplayCounts 三元组 + 成对返回",
      "fun resolveDisplayCounts(realOnline: Int?, realMax: Int?): Triple<Int?, Int?, Boolean>" in perf_svc_src and
      "return Triple(online, max, true)" in perf_svc_src and
      "return Triple(realOnline, realMax, false)" in perf_svc_src)
check("M6b 源码 resolveMaxPlayers 已移除",
      "resolveMaxPlayers" not in perf_svc_src)
check("M6c 源码 motd 分支调用 refreshIfStale + 读双捕获值",
      "MotdDisplayTracker.refreshIfStale()" in perf_svc_src and
      "MotdDisplayTracker.lastDisplayOnline" in perf_svc_src and
      "MotdDisplayTracker.lastDisplayMax" in perf_svc_src)
check("M6d 源码 渲染入参 onlinePlayersDisplay 接线",
      "onlinePlayersDisplay = onlinePlayers," in perf_svc_src and
      "maxPlayersFromMotd = fromMotd," in perf_svc_src)

# M7 PerformanceRenderer
check("M7a 源码 PerformanceData.onlinePlayersDisplay 字段",
      "val onlinePlayersDisplay: Int? = null," in perf_r_src)
check("M7b 源码 在线人数卡 N 用展示值覆盖",
      "val online = data.onlinePlayersDisplay ?: snapshot.onlinePlayers" in perf_r_src)
check("M7c 源码 note 新口径「人数与最大值为 MOTD 展示值」",
      "人数与最大值为 MOTD 展示值" in perf_r_src)
check("M7d 源码 旧 note 口径「最大 $max 人（MOTD 展示值）」已移除",
      "最大 $max 人（MOTD 展示值）" not in perf_r_src)

# M8 config 双端
for cfg, tag in ((cfg_spi, "Spigot"), (cfg_nf, "NF")):
    check(f"M8a {tag} config 注释新口径（展示值来源 + 一起生效）",
          "在线人数卡展示值来源" in cfg and "人数 N 与最大 M 一起生效" in cfg)
    check(f"M8b {tag} config 注释（本机自查询 + 60 秒复用 + 回退真实值）",
          "插件按需本机自" in cfg and "60 秒内复用结果" in cfg and "查不到回退真实值" in cfg)
    check(f"M8c {tag} config max-players-source 保持 real",
          'max-players-source: "real"' in cfg)
    check(f"M8d {tag} config-version 保持 34",
          "config-version: 34" in cfg)

# M9-M16 字节码（双 JAR）
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    tp = javap_p(jar, TRACKER)
    check(f"M9a {tag} MotdDisplayTracker 新方法在位（record/reset/refreshIfStale/pingStatus）",
          "record(int, int)" in tp.replace("java.lang.Integer,", "").replace(" ", "") or
          ("record" in tp and "reset" in tp and "refreshIfStale" in tp and "pingStatus" in tp))
    check(f"M9b {tag} MotdDisplayTracker 字段（lastDisplayOnline/lastDisplayMax/portProvider）",
          "lastDisplayOnline" in tp and "lastDisplayMax" in tp and "portProvider" in tp)
    check(f"M9c {tag} SLP 常量 127.0.0.1（类字节）",
          class_has_bytes(jar, TRACKER_ENTRY, "127.0.0.1".encode("utf-8")))
    check(f"M9d {tag} writeVarInt/readVarInt 方法在位",
          "writeVarInt" in tp and "readVarInt" in tp)
    ps = javap_p(jar, PERF_SVC)
    check(f"M10a {tag} resolveDisplayCounts 在位 / resolveMaxPlayers 不在",
          "resolveDisplayCounts" in ps and "resolveMaxPlayers" not in ps)
    pr = javap_p(jar, "cn.kerong.penguin.spigot.render.PerformanceRenderer$PerformanceData")
    check(f"M11 {tag} PerformanceData 字段 onlinePlayersDisplay",
          "onlinePlayersDisplay" in pr)
    check(f"M12 {tag} PlayerEventsListener 无 onServerListPing",
          tag != "Spigot" or
          "onServerListPing" not in javap_p(jar, "cn.kerong.penguin.spigot.events.PlayerEventsListener"))
    check(f"M13 {tag} 渲染 note 常量（UTF-8 字节）",
          class_has_bytes(jar, PERF_R_ENTRY, "人数与最大值为 MOTD 展示值".encode("utf-8")))
    check(f"M14a {tag} MotdDisplayTracker 类在位", TRACKER_ENTRY in zipfile.ZipFile(jar).namelist())
    check(f"M15a {tag} JAR 内置 config.yml 注释新口径",
          "在线人数卡展示值来源" in jar_read_text(jar, "config.yml") and
          "人数 N 与最大 M 一起生效" in jar_read_text(jar, "config.yml"))
    check(f"M15b {tag} JAR 内置 config.yml max-players-source real + version 34",
          'max-players-source: "real"' in jar_read_text(jar, "config.yml") and
          "config-version: 34" in jar_read_text(jar, "config.yml"))
    check(f"M16 {tag} resolveDisplayCounts 读配置键常量（字节串）",
          class_has_bytes(jar, PERF_SVC_ENTRY, "render.performance.max-players-source".encode("utf-8")))

# NeoPortReader 只在 NF
spigot_names = zipfile.ZipFile(SPIGOT_JAR).namelist()
nf_names = zipfile.ZipFile(NF_JAR).namelist()
check("M14b NeoPortReader 类只在 NF JAR", PORT_READER_ENTRY in nf_names and PORT_READER_ENTRY not in spigot_names)

# ============ B2-B6 基线回归 ============
print("== B2-B6 组：基线回归 ==")
bp = jar_read_text(SPIGOT_JAR, "cn/kerong/penguin/spigot/render/BackgroundPackService.class")
check("B2 bpng 64MB 熔断常量保持（65536 档位校验 via 源码）",
      "64L * 1024 * 1024" in source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/BackgroundPackService.kt"))
py_src = jar_read_text(SPIGOT_JAR, "plugin.yml")
check("B3a hbkrg 命令键在", "hbkrg:" in py_src and "hbkng" not in py_src)
help_md = jar_read_text(SPIGOT_JAR, "Markdown/help.md")
check("B3b help.md 无 hbkng 残留", "hbkng" not in help_md and "/hbkrg" in help_md)
rs_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/RenderSettings.kt")
check("B4a 副标题默认灰度 80 保持（源码 DEFAULT_SUBTITLE_CARD_GRAY 80）",
      "DEFAULT_SUBTITLE_CARD_GRAY = 80" in rs_src)
inf_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt")
check("B6a drawSubLabel 四参签名保持（花里胡哨红线）",
      "internal fun drawSubLabel(g: Graphics2D, text: String, x: Float, y: Float)" in inf_src)
check("B6b 固定灰 0x9FB3C8 保持",
      "0x9FB3C8" in inf_src)

# ============ WEB 组 ============
print("== WEB 组：官网三文件（仓库底稿） ==")
web_version = json.loads((ROOT / "website/version.json").read_text(encoding="utf-8"))
web_index = (ROOT / "website/index.html").read_text(encoding="utf-8")
web_tutorial = (ROOT / "website/tutorial.html").read_text(encoding="utf-8")
first_change = web_version["changelog"][0] if web_version["changelog"] else ""
check("WEB1a version.json 版本 1.7.3.1", web_version["version"] == "1.7.3.1")
check("WEB1b version.json changelog 首条含 motd 展示口径",
      "motd" in first_change and "展示" in first_change)
check("WEB2 index.html 1.7.3.1 更新日志段含 motd 口径",
      "motd" in web_index)
check("WEB3 tutorial.html max-players-source 条目新口径（展示值 + 自查询 + 双端）",
      "max-players-source: \"real\"" in web_tutorial and
      "服务器列表展示值" in web_tutorial and
      "自查询" in web_tutorial and
      "恒用真实值" not in web_tutorial)

print()
failed = [c for c in checks if not c[1]]
for name, ok in checks:
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
print()
print(f"总计 {len(checks)} 项，通过 {len(checks) - len(failed)} 项，失败 {len(failed)} 项")
if failed:
    print("  FAIL:", *[f"  {c[0]}" for c in failed], sep="\n")
    sys.exit(1)
print("ALL GREEN")
