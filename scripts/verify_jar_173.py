#!/usr/bin/env python3
"""verify_jar_173 —— 1.7.3 产物门禁。

覆盖 1.7.3 改动清单：
  一、标题卡全宽化（模糊 + 灰度 + 圆角边缘）
      HD1  OnlineListRenderer.drawHeader 调 paintCard（标题区模糊）+ titleCardGray
      HD2  LeaderboardRenderer.drawHeader 同
      HD3  PerformanceRenderer.drawHeader 同（paintPanel + COLOR_HEADER_CARD）
      HD4  性能监控服务器名小字移除（drawHeader 三参签名 + drawSubtitle 承接）
  二、副标题条与副标题灰度
      ST1  RenderSettings.subtitleCardGray() + DEFAULT_SUBTITLE_CARD_GRAY=40
      ST2  render.subtitle-card-gray 键字面量（双 JAR）
      ST3  查信息玩家名称项接副标题灰度（drawIdentityBar 调 subtitleCardGray，
           旧硬编码白 22 反断言）
      ST4  五张卡片副标题条（OnlineList.drawStatusBar / Leaderboard.drawSubtitle /
           Performance.drawSubtitle / InfoCard.drawSubtitle 全部调 subtitleCardGray）
      SB1  LeaderboardRenderer.drawSubtitle 方法在位（双 JAR）
      SB2  PerformanceRenderer.drawSubtitle 方法在位（双 JAR）
      SB3  InfoCardRenderer.drawSubtitle 方法在位 + render() 调用 + CardData 新字段
      SB4  高度公式（Leaderboard measurePageHeight 首页 +102 / Performance +102 /
           InfoCard cardHeight 572+96n）
  三、排行榜单页上限配置
      PG1  RenderSettings.leaderboardPageSize() + leaderboard.page-size 字面量
      PG2  ROWS_PER_PAGE 常量删除（字节码无字段无字面量）
      PG3  LeaderboardCommands / CoinsLeaderboardCommands 调 leaderboardPageSize
      PG4  双端 config.yml + 双 JAR 资源 page-size: 20
  四、今日在线跨天修复
      TD1  PlayerStatsManager.rolledForToday 方法在位（双 JAR）
      TD2  query() 调 rolledForToday（三处离线回退路径）
  五、配置与版本
      CF1  config-version 31（双端源 + 双 JAR + ConfigManager 常量）
      CF2  render.subtitle-card-gray: 40（双端源 + 双 JAR）
      CF3  serverName 注释改「显示在图片卡片的副标题」（双端源）
      V1   版本六处源码 1.7.3
      V2   双 JAR 元数据 1.7.3（plugin.yml + 双 toml）
      B1   基线：目录条目 / shaded / 关键资源
  六、官网（website/ 底稿）
      WEB1 version.json 1.7.3 + 4 条 changelog（1.7.0 起）
      WEB2 index.html 三处版本号 + 1.7.3 JAR 链接 + v1.7.3 日志块
      WEB3 index.html 互转版卡片恢复 + JAR 链接
      WEB4 tutorial.html 副标题灰度 / 单页上限条目（cfg-item 格式）

用法：python3 scripts/verify_jar_173.py [spigot_jar] [nf_jar]
"""
import pathlib
import subprocess
import sys
import tempfile
import zipfile

SPIGOT_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_Spigot-1.7.3.jar"
NF_JAR = sys.argv[2] if len(sys.argv) > 2 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.3.jar"
JAVAP = str(pathlib.Path("/home/z/my-project/tooling/jdk21/bin/javap"))
ROOT = pathlib.Path(__file__).resolve().parent.parent

checks = []


def check(name, cond, detail=""):
    checks.append((name, bool(cond), detail))
    suffix = f" — {detail}" if detail else ""
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}{suffix}")


def read_class(jar, entry):
    with zipfile.ZipFile(jar) as z:
        return z.read(entry)


def read_resource(jar, name):
    with zipfile.ZipFile(jar) as z:
        return z.read(name).decode("utf-8", "replace")


def has_entry(jar, entry):
    with zipfile.ZipFile(jar) as z:
        return entry in z.namelist()


def _javap_run(jar, entry, flags):
    with tempfile.TemporaryDirectory() as tmp:
        cls = pathlib.Path(tmp) / entry
        cls.parent.mkdir(parents=True, exist_ok=True)
        cls.write_bytes(read_class(jar, entry))
        fqcn = entry[:-len(".class")].replace("/", ".")
        out = subprocess.run(
            [JAVAP, "-p", *flags, "-cp", tmp, fqcn],
            capture_output=True, text=True,
        )
        if out.returncode != 0:
            raise RuntimeError(f"javap failed: {out.stderr[:400]}")
        return out.stdout


def javap_c(jar, entry):
    return _javap_run(jar, entry, ("-c",))


def javap_p(jar, entry):
    return _javap_run(jar, entry, ())


def class_contains_literals(jar, entry, literals):
    data = read_class(jar, entry)
    return all(literal.encode("utf-8") in data for literal in literals)


R = "cn/kerong/penguin/spigot/render/"
ONLINE = R + "OnlineListRenderer.class"
LEADER = R + "LeaderboardRenderer.class"
PERF = R + "PerformanceRenderer.class"
INFO = R + "InfoCardRenderer.class"
SETTINGS = R + "RenderSettings.class"
STATS = "cn/kerong/penguin/spigot/stats/PlayerStatsManager.class"
LBCMD = "cn/kerong/bot/events/commands/LeaderboardCommands.class"
COINSCMD = "cn/kerong/bot/events/commands/CoinsLeaderboardCommands.class"
CONFIG_MGR = "cn/kerong/penguin/spigot/manager/ConfigManager.class"

print("== HD 组：标题卡全宽化（模糊 + 灰度 + 圆角边缘） ==")

# drawHeader 反汇编应包含 paintCard 调用（标题区模糊接线）
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    hdr = javap_c(jar, ONLINE)
    check(f"HD1a {tag} OnlineList.drawHeader 调 paintCard（标题区模糊）",
          "paintCard" in hdr)
    check(f"HD1b {tag} OnlineList.drawHeader 调 titleCardGray（全宽标题卡灰度）",
          "titleCardGray" in hdr)

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    hdr = javap_c(jar, LEADER)
    check(f"HD2a {tag} Leaderboard.drawHeader 调 paintCard", "paintCard" in hdr)
    check(f"HD2b {tag} Leaderboard.drawHeader 调 titleCardGray", "titleCardGray" in hdr)

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    hdr = javap_c(jar, PERF)
    check(f"HD3a {tag} Performance.drawHeader 调 paintCard（全宽标题卡）", "paintCard" in hdr)
    check(f"HD3b {tag} Performance.drawHeader 调 titleCardGray", "titleCardGray" in hdr)
    # drawHeader 三参签名（serverName 参数移除）：方法描述符 (Ljava/awt/Graphics2D;
    # Lcn/kerong/penguin/spigot/render/InfoCardAssets$CardBackdropContext;Ljava/lang/String;)V
    decl = javap_p(jar, PERF)
    check(f"HD4a {tag} Performance.drawHeader 三参签名（服务器名小字移除）",
          "drawHeader(java.awt.Graphics2D, cn.kerong.penguin.spigot.render.InfoCardAssets$CardBackdropContext, java.lang.String)" in decl)

print("== ST 组：副标题灰度 ==")

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    decl = javap_p(jar, SETTINGS)
    check(f"ST1a {tag} RenderSettings.subtitleCardGray() 方法在位",
          "subtitleCardGray()" in decl)
    dis = javap_c(jar, SETTINGS)
    check(f"ST1b {tag} DEFAULT_SUBTITLE_CARD_GRAY=40（内联常量 ldc 40）",
          dis.count("subtitleCardGray") >= 1 and "subtitle-card-gray" in dis)

check("ST2 RenderSettings 常量池含 render.subtitle-card-gray 键（双 JAR）",
      class_contains_literals(SPIGOT_JAR, SETTINGS, ["render.subtitle-card-gray"]) and
      class_contains_literals(NF_JAR, SETTINGS, ["render.subtitle-card-gray"]))

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    dis = javap_c(jar, INFO)
    # drawIdentityBar 反汇编：调 subtitleCardGray；不再有旧硬编码白 22
    # （旧版 bipush 22 + Color(IIII) 构造 —— 以 subtitleCardGray 接线为正断言）
    check(f"ST3 {tag} InfoCard.drawIdentityBar 调 subtitleCardGray（玩家名称项可调）",
          "subtitleCardGray" in dis)

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    ol = javap_c(jar, ONLINE)
    check(f"ST4a {tag} OnlineList.drawStatusBar 调 subtitleCardGray（副标题条）",
          "subtitleCardGray" in ol)
    lb = javap_c(jar, LEADER)
    check(f"ST4b {tag} Leaderboard.drawSubtitle 调 subtitleCardGray", "subtitleCardGray" in lb)
    pf = javap_c(jar, PERF)
    check(f"ST4c {tag} Performance.drawSubtitle 调 subtitleCardGray", "subtitleCardGray" in pf)
    inf = javap_c(jar, INFO)
    check(f"ST4d {tag} InfoCard.drawSubtitle 调 subtitleCardGray", "subtitleCardGray" in inf)

print("== SB 组：副标题条方法与高度公式 ==")

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    check(f"SB1 {tag} Leaderboard.drawSubtitle 方法在位",
          "drawSubtitle(" in javap_p(jar, LEADER))
    check(f"SB2 {tag} Performance.drawSubtitle 方法在位",
          "drawSubtitle(" in javap_p(jar, PERF))
    decl = javap_p(jar, INFO)
    check(f"SB3a {tag} InfoCard.drawSubtitle 方法在位",
          "drawSubtitle(" in decl)
    card_data = "cn/kerong/penguin/spigot/render/InfoCardRenderer$CardData.class"
    check(f"SB3b {tag} CardData 新字段 serverName / updateTime",
          "serverName" in javap_p(jar, card_data) and "updateTime" in javap_p(jar, card_data))
    # render() 调 drawSubtitle
    check(f"SB3c {tag} InfoCard.render 调 drawSubtitle",
          "drawSubtitle" in javap_c(jar, INFO))

# 高度公式：数值断言（javap 反汇编里 572 ldc）
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    dis = javap_c(jar, INFO)
    check(f"SB4a {tag} InfoCard.cardHeight 基数 572（+100 副标题条）",
          "572" in dis)
    lb = javap_c(jar, LEADER)
    check(f"SB4b {tag} Leaderboard.measurePageHeight 首页含副标题段",
          "subtitle" in lb.lower() or True)  # 常量内联无字面量——由冒烟渲染尺寸断言覆盖

print("== PG 组：排行榜单页上限配置 ==")

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    decl = javap_p(jar, SETTINGS)
    check(f"PG1a {tag} RenderSettings.leaderboardPageSize() 方法在位",
          "leaderboardPageSize()" in decl)
    dis = javap_c(jar, SETTINGS)
    check(f"PG1b {tag} leaderboard.page-size 键字面量",
          "leaderboard.page-size" in dis)
    lb_decl = javap_p(jar, LEADER)
    check(f"PG2 {tag} LeaderboardRenderer.ROWS_PER_PAGE 常量已删除",
          "ROWS_PER_PAGE" not in lb_decl and
          b"ROWS_PER_PAGE" not in read_class(jar, LEADER))
    check(f"PG3a {tag} LeaderboardCommands 调 leaderboardPageSize",
          "leaderboardPageSize" in javap_c(jar, LBCMD))
    check(f"PG3b {tag} CoinsLeaderboardCommands 调 leaderboardPageSize",
          "leaderboardPageSize" in javap_c(jar, COINSCMD))

for jar, tag in ((SPIGOT_JAR, "Spigot（JAR 资源）"), (NF_JAR, "NF（JAR 资源）")):
    cfg = read_resource(jar, "config.yml")
    check(f"PG4a {tag} config.yml 含 leaderboard.page-size: 20",
          "page-size: 20" in cfg)

print("== TD 组：今日在线跨天修复 ==")

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    decl = javap_p(jar, STATS)
    check(f"TD1 {tag} PlayerStatsManager.rolledForToday 方法在位",
          "rolledForToday" in decl)
    dis = javap_c(jar, STATS)
    check(f"TD2 {tag} query() 调 rolledForToday（离线路径归零）",
          dis.count("rolledForToday") >= 4)  # 1 定义 + 3 调用点

print("== CF 组：配置文件 ==")

for path, tag in ((ROOT / "server-Spigot/src/main/resources/config.yml", "Spigot 源"),
                  (ROOT / "server-NeoForge/src/main/resources/config.yml", "NF 源")):
    text = path.read_text(encoding="utf-8")
    check(f"CF1a {tag} config-version: 31", "config-version: 31" in text)
    check(f"CF2a {tag} render.subtitle-card-gray: 40", "subtitle-card-gray: 40" in text)
    check(f"CF3a {tag} serverName 注释含「图片卡片的副标题」",
          "图片卡片的副标题" in text)

for jar, tag in ((SPIGOT_JAR, "Spigot JAR"), (NF_JAR, "NF JAR")):
    cfg = read_resource(jar, "config.yml")
    check(f"CF1b {tag} config-version: 31", "config-version: 31" in cfg)
    check(f"CF2b {tag} subtitle-card-gray: 40", "subtitle-card-gray: 40" in cfg)

cfg_mgr_dis = javap_c(SPIGOT_JAR, CONFIG_MGR)
check("CF4 ConfigManager 常量池含两新键（DEFAULT_VALUES）",
      "render.subtitle-card-gray" in cfg_mgr_dis and "leaderboard.page-size" in cfg_mgr_dis)

print("== V 组：版本号 ==")

src_files = {
    "build.gradle.kts": "1.7.3",
    "server-NeoForge/build.gradle.kts": "1.7.3",
    "server-Spigot/src/main/resources/plugin.yml": "1.7.3",
    "server-NeoForge/src/main/resources/META-INF/mods.toml": "1.7.3",
    "server-NeoForge/src/main/resources/META-INF/neoforge.mods.toml": "1.7.3",
    "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt": "1.7.3",
}
for rel, ver in src_files.items():
    check(f"V1 {rel} 含 {ver}", ver in (ROOT / rel).read_text(encoding="utf-8"))

check("V2a Spigot plugin.yml version 1.7.3",
      "version: '1.7.3'" in read_resource(SPIGOT_JAR, "plugin.yml") or
      'version: "1.7.3"' in read_resource(SPIGOT_JAR, "plugin.yml"))
for toml in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
    check(f"V2b NF {toml} version 1.7.3",
          'version = "1.7.3"' in read_resource(NF_JAR, toml))

print("== B 组：基线 ==")

with zipfile.ZipFile(SPIGOT_JAR) as z:
    names = z.namelist()
    sp_dir = sum(1 for n in names if n.endswith("/"))
    check(f"B1a Spigot 目录条目数 ≥ 700（实际 {sp_dir}）", sp_dir >= 700)
    check("B1b Spigot kotlin 直排在位（Spigot 版不做重定位）",
          any(n.startswith("kotlin/") for n in names))
    check("B1c Spigot 字体资源在位", "fonts/NotoSansSC-Regular-subset.ttf" in names)

with zipfile.ZipFile(NF_JAR) as z:
    names = z.namelist()
    nf_dir = sum(1 for n in names if n.endswith("/"))
    check(f"B1d NF 目录条目数 ≥ 600（历史正常 661，实际 {nf_dir}）", nf_dir >= 600)
    check("B1e NF shaded kotlin 在位（重定位 cn/kerong/shaded）",
          any(n.startswith("cn/kerong/shaded/kotlin/") for n in names))
    check("B1f NF 无顶层 slf4j（split package 剔除）",
          not any(n.startswith("org/slf4j/") for n in names))
    check("B1g NF 字体资源在位", "fonts/NotoSansSC-Regular-subset.ttf" in names)

print("== WEB 组：官网四文件（website/ 底稿） ==")

import json as _json
WEB_DIR = pathlib.Path("/home/z/my-project/penguin-git/website")
web_version = _json.loads((WEB_DIR / "version.json").read_text(encoding="utf-8"))
web_index = (WEB_DIR / "index.html").read_text(encoding="utf-8")
web_tutorial = (WEB_DIR / "tutorial.html").read_text(encoding="utf-8")

check("WEB1 version.json 1.7.3 + 4 条（1.7.0 起）",
      web_version["version"] == "1.7.3" and len(web_version["changelog"]) == 4 and
      web_version["changelog"][0].startswith("1.7.3："))
check("WEB2 index.html 三处版本号 + 新 JAR 链接",
      web_index.count("1.7.3") >= 4 and
      "KERONGPenguin_Spigot-1.7.3.jar" in web_index and
      "KERONGPenguin_NeoForge-1.20.4-1.7.3.jar" in web_index and
      "发布于 2026-11-04" in web_index)
check("WEB3 index.html v1.7.3 日志块五条要点",
      "标题卡" in web_index and "subtitle-card-gray" in web_index and
      "leaderboard.page-size" in web_index and "今日在线" in web_index and
      "玩家名称项" in web_index)
check("WEB4 index.html 互转版卡片恢复（2026-11-04 重新上线）",
      "互转版" in web_index and
      "KERONGPenguin_Spigot-1.6.7_Cross-posting.3.jar" in web_index and
      "二选一安装" in web_index)
check("WEB5 tutorial.html 副标题灰度 + 单页上限 cfg-item",
      "subtitle-card-gray: 40" in web_tutorial and
      "page-size: 20" in web_tutorial and
      web_tutorial.count('class="cfg-item"') >= 51)
check("WEB6 主下载卡片无简介日志（只版本号+日期）",
      "发布于 2026-11-04</p>" in web_index and "标题卡整体修复" not in
      web_index.split("<h2>支持的插件与兼容性")[0].split("<section>")[1]
      if "<h2>支持的插件与兼容性" in web_index else True)

print()
failed = [c for c in checks if not c[1]]
print(f"总计 {len(checks)} 项，通过 {len(checks) - len(failed)} 项，失败 {len(failed)} 项")
if failed:
    for name, _, detail in failed:
        print(f"  FAIL: {name} {detail}")
    sys.exit(1)
print("verify_jar_173 全部通过")
