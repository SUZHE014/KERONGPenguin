#!/usr/bin/env python3
"""verify_jar_1731o4 —— 1.7.3.1 第四次覆盖版产物门禁（查在线多页每页重新抽背景）。

背景（用户需求：同版本号直接覆盖 1.7.3.1，「多页改为重新抽图片」）：
  自定义图片背景（img/ 随机图）的抽取本来就是随机的（processedBackground
  每次调用 ThreadLocalRandom 在 img/ 全部候选里独立抽一张），在线排行榜 /
  财富榜的分页循环里每页各调一次 resolveBackground——每页独立抽。唯独
  查在线（OnlineListService）在渲染前只调一次 resolveBackground、整组
  页面共用同一张背景（历史设计「保持视觉统一」，代码注释原文）。
  本轮把查在线改为与排行榜一致：OnlineListData 新增 backgroundProvider
  （(height: Int) -> BufferedImage?），renderSingle 每渲染一页按本页画布
  高度调用一次、独立重新抽一张；provider 为 null 时回退旧 background
  字段（整组共用）——内置背景与单页场景观感零变化。
  无新增配置键（config-version 保持 34）；双端（Spigot / NeoForge）同源
  改动、同版本号覆盖发布。

断言组：
  P 组  查在线多页每页重新抽背景（源码 + 双 JAR 字节码）
       P1  OnlineListData 新增 backgroundProvider 字段（类型
           ((height: Int) -> BufferedImage?)?，默认 null）+ 旧 background
           字段保留（回退路径）
       P2  renderSingle 每页取背景：backgroundProvider?.invoke(height)
           ?: background → drawBackground(pageBackground)
       P3  旧直传路径移除（drawBackground(g, data.background, ...) 不在）
       P4  OnlineListService 改传 provider（resolveBackground 移入 lambda，
           useBuiltinBackground 提为局部值）
       P5  OnlineListService 单次预取背景移除（val background =
           resolveBackground(...) / measurePageHeight(entries.size, true)
           不在服务层）
       P6  注释口径：服务层「每页独立重新抽背景」在位，「整组页面共用
           同一张背景保持视觉统一」旧口径移除
       P7  渲染器 KDoc 标注第四次覆盖版（≥3 处）
       P8  回归：在线排行榜 / 财富榜仍每页各调 resolveBackground
           （分页循环内 val background = ...）
       P9  字节码：OnlineListData 有 backgroundProvider 字段（javap -p，
           双 JAR；Spigot 为 kotlin/jvm/functions/Function1、NF 为
           cn/kerong/shaded/kotlin/... 重定位形态）
       P10 字节码：renderSingle 内 Function1.invoke 调用（双 JAR）
       P11 字节码：OnlineListService 类内 provider lambda 方法（签名
           (File, boolean, int) -> BufferedImage 的私有静态方法）+
           invokedynamic invoke:(Ljava/io/File;Z)L...Function1;
           + 类常量池含 resolveBackground
       P12 字节码：OnlineListData 常量池含 backgroundProvider（双 JAR）
  B 组  基线回归（沿用 1.7.3.1 第二 / 三次覆盖版基线）
       B1  目录条目 / plugin.yml 版本 1.7.3.1 / 字体资源 / NF shaded 在位
       B2  bpng 64MB 熔断保持（源码常量）
       B3  hbkrg 命令键在 / hbkng 不在（plugin.yml + help.md）
       B4  副标题默认灰度 80 基线保持（DEFAULT_SUBTITLE_CARD_GRAY 80）
       B5  config-version 34 四处（双端源 + 双 JAR config.yml）
       B6  花里胡哨红线：drawSubLabel 四参签名 + 0x9FB3C8 固定灰保持
       B7  motd 第三次覆盖版机制保持（tracker 新 API / 无事件监听 /
           resolveDisplayCounts 在位 resolveMaxPlayers 不在 / note 常量 /
           max-players-source "real" / NeoPortReader 只在 NF）
  WEB  官网三文件（仓库 website/ 底稿，已同步线上 tutorial 基础设置完成节）
       WEB1 version.json 1.7.3.1 + changelog 首条含每页重抽背景口径
       WEB2 index.html v1.7.3.1 段含每页重抽背景口径
       WEB3 tutorial.html 线上同步版（基础设置完成节）+ 背景开关条目完好

用法：python3 scripts/verify_jar_1731o4.py [spigot_jar] [nf_jar]
"""
import json
import pathlib
import re
import subprocess
import sys
import zipfile

SPIGOT_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_Spigot-1.7.3.1.jar"
NF_JAR = sys.argv[2] if len(sys.argv) > 2 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.3.1.jar"
JAVAP = str(pathlib.Path("/home/z/my-project/tooling/jdk21/bin/javap"))
ROOT = pathlib.Path(__file__).resolve().parent.parent

OLR = "cn.kerong.penguin.spigot.render.OnlineListRenderer"
OLR_DATA = "cn.kerong.penguin.spigot.render.OnlineListRenderer$OnlineListData"
OLR_DATA_ENTRY = "cn/kerong/penguin/spigot/render/OnlineListRenderer$OnlineListData.class"
OLS = "cn.kerong.penguin.spigot.stats.OnlineListService"
OLS_ENTRY = "cn/kerong/penguin/spigot/stats/OnlineListService.class"
PERF_SVC = "cn.kerong.penguin.spigot.stats.PerformanceService"
TRACKER = "cn.kerong.penguin.spigot.stats.MotdDisplayTracker"
TRACKER_ENTRY = "cn/kerong/penguin/spigot/stats/MotdDisplayTracker.class"
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


def jar_read_bytes(jar, entry):
    with zipfile.ZipFile(jar) as z:
        return z.read(entry)


def jar_read_text(jar, entry):
    return jar_read_bytes(jar, entry).decode("utf-8", "replace")


def class_has_bytes(jar, entry, needle):
    return needle in jar_read_bytes(jar, entry)


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

# ============ P 组：查在线多页每页重新抽背景 ============
print("== P 组：查在线多页每页重新抽背景 ==")

olr_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/OnlineListRenderer.kt")
ols_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/OnlineListService.kt")
lb_src = source_text("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/LeaderboardCommands.kt")
clb_src = source_text("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/CoinsLeaderboardCommands.kt")

check("P1a 源码 OnlineListData 新增 backgroundProvider 字段（默认 null lambda 类型）",
      "val backgroundProvider: ((height: Int) -> BufferedImage?)? = null," in olr_src)
check("P1b 源码 旧 background 字段保留（provider 为 null 的回退路径）",
      "val background: BufferedImage? = null," in olr_src)
check("P2a 源码 renderSingle 每页取背景（invoke(height) ?: data.background）",
      "val pageBackground = data.backgroundProvider?.invoke(height) ?: data.background" in olr_src)
check("P2b 源码 drawBackground 接 pageBackground",
      "InfoCardAssets.drawBackground(g, pageBackground, WIDTH, height)" in olr_src)
check("P3 源码 旧直传路径移除（drawBackground(g, data.background, ...) 不在）",
      "InfoCardAssets.drawBackground(g, data.background," not in olr_src)
check("P4a 源码 OnlineListService 改传 backgroundProvider（lambda 包 resolveBackground）",
      "backgroundProvider = { pageHeight ->" in ols_src and
      "InfoCardAssets.resolveBackground(" in ols_src and
      "useBuiltinBackground," in ols_src)
check("P4b 源码 OnlineListService useBuiltinBackground 提为局部值",
      "val useBuiltinBackground = bindManager?.onlineListBuiltinBackground ?: true" in ols_src)
check("P5a 源码 服务层单次预取背景移除（val background = resolveBackground 不在）",
      "val background = InfoCardAssets.resolveBackground(" not in ols_src)
check("P5b 源码 服务层不再固定首页高度取背景（measurePageHeight(entries.size, true) 不在）",
      "OnlineListRenderer.measurePageHeight(entries.size, true)" not in ols_src)
check("P6a 源码 注释口径：服务层「每页独立重新抽背景」在位",
      "每页独立重新抽背景" in ols_src)
check("P6b 源码 注释口径：「整组页面共用同一张背景保持视觉统一」旧口径移除",
      "整组页面共用同一张背景保持视觉统一" not in ols_src)
check("P7 源码 渲染器 KDoc 标注第四次覆盖版（≥3 处）",
      olr_src.count("第四次覆盖版") >= 3)
check("P8 回归：在线排行榜 / 财富榜仍每页各调 resolveBackground（分页循环内）",
      "val background = InfoCardAssets.resolveBackground(" in lb_src and
      "val background = InfoCardAssets.resolveBackground(" in clb_src)

# P9-P12 字节码（双 JAR）
lambda_sig = re.compile(
    r"private static final java\.awt\.image\.BufferedImage \w+\$lambda\$[0-9]+\$lambda\$[0-9]+\(java\.io\.File, boolean, int\)")
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    data_p = javap_p(jar, OLR_DATA)
    check(f"P9a {tag} OnlineListData 字段 backgroundProvider（javap -p）",
          "backgroundProvider" in data_p)
    check(f"P9b {tag} OnlineListData 构造器含 Function1 形参（NF 为重定位形态）",
          "Function1" in data_p)
    olr_c = javap_c(jar, OLR)
    check(f"P10a {tag} renderSingle 内 Function1.invoke 调用（每页取背景）",
          "Function1.invoke" in olr_c)
    check(f"P10b {tag} renderSingle 内 getBackgroundProvider 调用",
          "getBackgroundProvider" in olr_c)
    check(f"P10c {tag} renderSingle 内 drawBackground 接 pageBackground 结果",
          "InfoCardAssets.drawBackground" in olr_c)
    ols_c = javap_c(jar, OLS)
    check(f"P11a {tag} 服务层 provider lambda 方法（(File, boolean, int) -> BufferedImage）",
          lambda_sig.search(ols_c) is not None)
    check(f"P11b {tag} 服务层 invokedynamic 构造 provider（invoke:(Ljava/io/File;Z)L...Function1）",
          "invoke:(Ljava/io/File;Z)L" in ols_c)
    check(f"P11c {tag} 服务类常量池含 resolveBackground（字节串）",
          class_has_bytes(jar, OLS_ENTRY, b"resolveBackground"))
    check(f"P12 {tag} OnlineListData 常量池含 backgroundProvider（字节串）",
          class_has_bytes(jar, OLR_DATA_ENTRY, b"backgroundProvider"))

# ============ B2-B7 基线回归 ============
print("== B2-B7 组：基线回归 ==")
check("B2 bpng 64MB 熔断常量保持（源码）",
      "64L * 1024 * 1024" in source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/BackgroundPackService.kt"))
py_src = jar_read_text(SPIGOT_JAR, "plugin.yml")
check("B3a hbkrg 命令键在", "hbkrg:" in py_src and "hbkng" not in py_src)
help_md = jar_read_text(SPIGOT_JAR, "Markdown/help.md")
check("B3b help.md 无 hbkng 残留", "hbkng" not in help_md and "/hbkrg" in help_md)
rs_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/RenderSettings.kt")
check("B4 副标题默认灰度 80 保持（源码 DEFAULT_SUBTITLE_CARD_GRAY 80）",
      "DEFAULT_SUBTITLE_CARD_GRAY = 80" in rs_src)
inf_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt")
check("B6a drawSubLabel 四参签名保持（花里胡哨红线）",
      "internal fun drawSubLabel(g: Graphics2D, text: String, x: Float, y: Float)" in inf_src)
check("B6b 固定灰 0x9FB3C8 保持", "0x9FB3C8" in inf_src)

tracker_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/MotdDisplayTracker.kt")
listener_src = source_text("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/events/PlayerEventsListener.kt")
perf_svc_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/PerformanceService.kt")
cfg_spi = source_text("server-Spigot/src/main/resources/config.yml")
cfg_nf = source_text("server-NeoForge/src/main/resources/config.yml")

check("B7a motd 机制保持：tracker 新 API（record 双参 / refreshIfStale）",
      "fun record(online: Int?, max: Int?)" in tracker_src and
      "fun refreshIfStale(" in tracker_src)
check("B7b motd 机制保持：PlayerEventsListener 无事件监听路径",
      "fun onServerListPing" not in listener_src)
check("B7c motd 机制保持：resolveDisplayCounts 在位 / resolveMaxPlayers 不在（源码）",
      "fun resolveDisplayCounts(" in perf_svc_src and "resolveMaxPlayers" not in perf_svc_src)

for cfg, tag in ((cfg_spi, "Spigot 源"), (cfg_nf, "NF 源")):
    check(f"B5a {tag} config-version 34", "config-version: 34" in cfg)
    check(f"B5b {tag} max-players-source 保持 real", 'max-players-source: "real"' in cfg)
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    jar_cfg = jar_read_text(jar, "config.yml")
    check(f"B5c {tag} JAR 内置 config.yml version 34", "config-version: 34" in jar_cfg)
    check(f"B5d {tag} JAR 内置 config.yml max-players-source real",
          'max-players-source: "real"' in jar_cfg)
    check(f"B7d {tag} 渲染 note 常量「人数与最大值为 MOTD 展示值」（UTF-8 字节）",
          class_has_bytes(jar, PERF_R_ENTRY, "人数与最大值为 MOTD 展示值".encode("utf-8")))
    ps = javap_p(jar, PERF_SVC)
    check(f"B7e {tag} resolveDisplayCounts 在位 / resolveMaxPlayers 不在（字节码）",
          "resolveDisplayCounts" in ps and "resolveMaxPlayers" not in ps)
    check(f"B7f {tag} MotdDisplayTracker 类在位", TRACKER_ENTRY in zipfile.ZipFile(jar).namelist())

spigot_names = zipfile.ZipFile(SPIGOT_JAR).namelist()
nf_names = zipfile.ZipFile(NF_JAR).namelist()
check("B7g NeoPortReader 类只在 NF JAR", PORT_READER_ENTRY in nf_names and PORT_READER_ENTRY not in spigot_names)
check("B7h Spigot JAR PlayerEventsListener 无 onServerListPing（字节码）",
      "onServerListPing" not in javap_p(SPIGOT_JAR, "cn.kerong.penguin.spigot.events.PlayerEventsListener"))

# ============ WEB 组 ============
print("== WEB 组：官网三文件（仓库底稿） ==")
web_version = json.loads((ROOT / "website/version.json").read_text(encoding="utf-8"))
web_index = (ROOT / "website/index.html").read_text(encoding="utf-8")
web_tutorial = (ROOT / "website/tutorial.html").read_text(encoding="utf-8")
first_change = web_version["changelog"][0] if web_version["changelog"] else ""
check("WEB1a version.json 版本 1.7.3.1", web_version["version"] == "1.7.3.1")
check("WEB1b version.json changelog 首条含每页重抽背景口径（每页 + 重新随机抽 + 背景）",
      "每页" in first_change and "重新随机抽" in first_change and "背景" in first_change)
check("WEB2 index.html v1.7.3.1 段含每页重抽背景口径",
      "每页重新抽背景" in web_index and "1.7.3.1" in web_index)
check("WEB3a tutorial.html 为线上同步版（基础设置完成节）",
      "基础设置完成" in web_tutorial)
check("WEB3b tutorial.html 背景开关条目完好（img/ 随机图口径）",
      "false = 用 <code>img/</code> 文件夹里的随机图" in web_tutorial)

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
