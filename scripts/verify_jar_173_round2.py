#!/usr/bin/env python3
"""
verify_jar_173_round2.py —— 1.7.3 覆盖版二轮门禁（同版本号覆盖，2026-11-05）
用户三项 + 覆盖口径：
  R2 组  本轮三项：
      R2a 源码 HEADER_HEIGHT = 164（三列表卡，150 → 164）
      R2b 源码 cardInset = 14 四周间距（上/右/下，旧 8/8/0）
      R2c 字节码 drawHeader 眉题基线 bipush 46 / 主标题 bipush 118
      R2d 字节码 drawHeader ldc 164.0f（标题卡高度）
      R2e LeaderboardRenderer「展示玩家」字面量 + drawSubtitle 新签名（…,long）
      R2f LeaderboardRenderer drawRenderElapsed 已移除
      R2g OnlineList / Performance / InfoCard drawRenderElapsed 仍在位
      R2h 三列表卡 drawSubtitle/drawStatusBar 副标题条顶 222.0f（40+164+18）
  RC 组  一轮覆盖版仍有效项：
      RC1 headerBadgeCardGray（+36 派生）
      RC2 subtitle-card-gray 56
      RC3 custom-render-threads 三键
      RC5 buildOverallStatus + drawHeader 新签名
      RC6 padding 72.0f/68.0f（左缘内边距不变）
      RC7 SUBTITLE_GAP=32
  CF 组  config-version 32
  FT 组  字体子集含「渲」与「展示玩家」四字
  B1 组  基线：目录条目 776±/ shaded / 版本 1.7.3 / 字体资源
  WEB 组 官网四件套二轮覆盖口径（website/ 底稿）
用法：python3 scripts/verify_jar_173_round2.py [spigot_jar] [nf_jar]
"""
import pathlib
import subprocess
import sys
import zipfile

SPIGOT_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_Spigot-1.7.3.jar"
NF_JAR = sys.argv[2] if len(sys.argv) > 2 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.3.jar"
JAVAP = str(pathlib.Path("/home/z/my-project/tooling/jdk21/bin/javap"))
ROOT = pathlib.Path(__file__).resolve().parent.parent

BIG = [("OnlineList", "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/OnlineListRenderer.kt"),
       ("Leaderboard", "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/LeaderboardRenderer.kt"),
       ("Performance", "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/PerformanceRenderer.kt")]


def class_contains_literals(jar, entry, literals):
    """类文件字节级字面量检查（UTF-8 常量池字节直搜，规避 javap 转义坑）。"""
    with zipfile.ZipFile(jar) as z:
        data = z.read(entry)
    return all(literal.encode("utf-8") in data for literal in literals)


SETTINGS = "cn.kerong.penguin.spigot.render.RenderSettings"
POOL = "cn.kerong.penguin.spigot.render.CardRenderPool"
PERF = "cn.kerong.penguin.spigot.render.PerformanceRenderer"
OL = "cn.kerong.penguin.spigot.render.OnlineListRenderer"
LB = "cn.kerong.penguin.spigot.render.LeaderboardRenderer"
INF = "cn.kerong.penguin.spigot.render.InfoCardRenderer"
CONFIG = "cn.kerong.penguin.spigot.manager.ConfigManager"
SETTINGS_ENTRY = "cn/kerong/penguin/spigot/render/RenderSettings.class"
LB_ENTRY = "cn/kerong/penguin/spigot/render/LeaderboardRenderer.class"

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
            check(f"B1c {tag} plugin.yml 版本 1.7.3", "version: '1.7.3'" in meta)
        else:
            meta = z.read("META-INF/neoforge.mods.toml").decode("utf-8", "replace")
            check(f"B1c {tag} neoforge.mods.toml 版本 1.7.3", "1.7.3" in meta)
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

# ============ R2 本轮三项 ============
print("== R2 组：覆盖版二轮三项 ==")
for name, rel in BIG:
    src = source_text(rel)
    check(f"R2a {name} 源码 HEADER_HEIGHT = 164",
          "private const val HEADER_HEIGHT = 164" in src)
    check(f"R2b {name} 源码 cardInset = 14 四周间距",
          "val cardInset = 14" in src and "HEADER_HEIGHT - 2 * cardInset" in src)

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    for cls, name in ((OL, "OnlineList"), (LB, "Leaderboard"), (PERF, "Performance")):
        dis = javap_c(jar, cls)
        body = dis.split("void drawHeader")[1].split("void draw")[0] if "void drawHeader" in dis else ""
        check(f"R2c {tag} {name}.drawHeader 眉题 46 / 主标题 118",
              "bipush        46" in body and "bipush        118" in body)
        check(f"R2d {tag} {name}.drawHeader 标题卡高 164.0f",
              "164.0f" in body)
    # 副标题条顶 222.0f（40+164+18 常量折叠）
    for cls, name, anchor in ((OL, "OnlineList", "drawStatusBar"), (LB, "Leaderboard", "drawSubtitle"),
                              (PERF, "Performance", "drawSubtitle")):
        dis = javap_c(jar, cls)
        seg = dis.split(f"void {anchor}")[1].split("void draw")[0] if f"void {anchor}" in dis else ""
        check(f"R2h {tag} {name}.{anchor} 副标题条顶 222.0f", "222.0f" in seg)
    # 排行榜：展示玩家 + 移除渲染耗时
    check(f"R2e {tag} Leaderboard「展示玩家」字面量（makeConcat xTOP）",
          class_contains_literals(jar, LB_ENTRY, ["展示玩家", "TOP"]))
    decl = javap_p(jar, LB)
    check(f"R2e2 {tag} Leaderboard.drawSubtitle 新签名（long 参数）",
          "drawSubtitle(java.awt.Graphics2D, cn.kerong.penguin.spigot.render.InfoCardAssets$CardBackdropContext, java.lang.String, java.lang.String, long)" in decl)
    check(f"R2f {tag} Leaderboard.drawRenderElapsed 已移除",
          "drawRenderElapsed(" not in decl)
    for cls, name in ((OL, "OnlineList"), (PERF, "Performance"), (INF, "InfoCard")):
        d = javap_p(jar, cls)
        check(f"R2g {tag} {name}.drawRenderElapsed 仍在位", "drawRenderElapsed(" in d)

# ============ RC 一轮仍有效项 ============
print("== RC 组：一轮覆盖版仍有效项 ==")
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    decl = javap_p(jar, SETTINGS)
    check(f"RC1a {tag} RenderSettings.headerBadgeCardGray() 方法在位",
          "headerBadgeCardGray()" in decl)
    dis = javap_c(jar, SETTINGS)
    check(f"RC1b {tag} headerBadgeCardGray 体内 bipush 36（+36 派生）",
          "bipush        36" in dis.split("headerBadgeCardGray()")[1].split("public")[0]
          if "headerBadgeCardGray()" in dis else False)

cfg_spigot = source_text("server-Spigot/src/main/resources/config.yml")
cfg_nf = source_text("server-NeoForge/src/main/resources/config.yml")
check("RC2a Spigot 源 subtitle-card-gray: 56", "subtitle-card-gray: 56" in cfg_spigot)
check("RC2b NF 源 subtitle-card-gray: 56", "subtitle-card-gray: 56" in cfg_nf)
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    text = jar_read_text(jar, "config.yml")
    check(f"RC2c {tag} JAR config.yml subtitle-card-gray: 56", "subtitle-card-gray: 56" in text)
    dis = javap_c(jar, SETTINGS)
    check(f"RC2d {tag} RenderSettings 副标题默认 56 在位",
          "subtitle-card-gray" in dis and ("ldc           56" in dis or "bipush        56" in dis))

cfg_mgr = source_text("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
check("RC3a ConfigManager 源三键（custom-render-threads 段）", cfg_mgr.count("custom-render-threads") >= 3)
pool_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/CardRenderPool.kt")
check("RC3c CardRenderPool 源读 custom-render-threads", "custom-render-threads" in pool_src)
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    text = jar_read_text(jar, "config.yml")
    check(f"RC3d {tag} JAR config.yml 三键", "custom-render-threads:" in text and
          "render-threads:" in text and "avatar-threads:" in text)
    check(f"RC3e {tag} JAR config.yml ⚠ 警告注释", ("调大渲染线程" in text or "争抢" in text))
    pool_dis = javap_c(jar, POOL)
    check(f"RC3g {tag} CardRenderPool 调 customRenderThreads()",
          "customRenderThreads:" in pool_dis)

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    decl = javap_p(jar, PERF)
    check(f"RC5a {tag} Performance.buildOverallStatus 在位", "buildOverallStatus(" in decl)
    check(f"RC5c {tag} drawHeader 新签名（OverallStatus 参数）",
          "drawHeader(java.awt.Graphics2D, cn.kerong.penguin.spigot.render.InfoCardAssets$CardBackdropContext, cn.kerong.penguin.spigot.render.PerformanceRenderer$OverallStatus)" in decl)
    dis = javap_c(jar, PERF)
    check(f"RC5d {tag} 综合状态文案（运行良好/轻度负载/负载过高）",
          all(s in dis for s in ("运行良好", "轻度负载", "负载过高")))

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    for cls, name in ((OL, "OnlineList"), (LB, "Leaderboard"), (PERF, "Performance")):
        dis = javap_c(jar, cls)
        body = dis.split("void drawHeader")[1].split("void draw")[0] if "void drawHeader" in dis else ""
        check(f"RC6 {tag} {name}.drawHeader 左缘 padding 72.0f/68.0f（不变）",
              "72.0f" in body and "68.0f" in body)

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    dis = javap_c(jar, PERF)
    check(f"RC7 {tag} SUBTITLE_GAP=32（bipush 32）", "bipush        32" in dis)

# ============ CF config-version 32 ============
check("CF1 ConfigManager 源 config-version 32",
      any("32" in ln and "CONFIG_VERSION" in ln for ln in cfg_mgr.splitlines()))
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    text = jar_read_text(jar, "config.yml")
    check(f"CF2 {tag} JAR config.yml config-version 32", "config-version: 32" in text)

# ============ FT 字体子集 ============
try:
    from fontTools.ttLib import TTFont

    def has_char(path, ch):
        font = TTFont(str(path), lazy=True)
        for table in font["cmap"].tables:
            if ord(ch) in table.cmap:
                return True
        return False

    fonts = [
        "server-Spigot/src/main/resources/fonts/NotoSansSC-Regular-subset.ttf",
        "server-Spigot/src/main/resources/fonts/NotoSansSC-Bold-subset.ttf",
        "server-NeoForge/src/main/resources/fonts/NotoSansSC-Regular-subset.ttf",
        "server-NeoForge/src/main/resources/fonts/NotoSansSC-Bold-subset.ttf",
    ]
    for f in fonts:
        check(f"FT1 源字体含「渲」+「展示玩家」({f.split('/')[0]})",
              all(has_char(ROOT / f, ch) for ch in "渲展示玩家"))
    import tempfile

    for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
        with zipfile.ZipFile(jar) as z:
            for entry in ("fonts/NotoSansSC-Regular-subset.ttf", "fonts/NotoSansSC-Bold-subset.ttf"):
                with tempfile.NamedTemporaryFile(suffix=".ttf", delete=False) as tf:
                    tf.write(z.read(entry))
                    tf.flush()
                    check(f"FT2 {tag} JAR 字体含「渲」+「展示玩家」({entry.split('/')[1][:20]})",
                          all(has_char(tf.name, ch) for ch in "渲展示玩家"))
except ImportError:
    check("FT 字体组（fonttools 缺失，跳过）", False)

# ============ WEB 官网四件套二轮覆盖口径 ============
print("== WEB 组：官网四件套（website/ 底稿，二轮覆盖口径） ==")
import json  # noqa: E402

web_version = json.loads((ROOT / "website/version.json").read_text(encoding="utf-8"))
web_index = (ROOT / "website/index.html").read_text(encoding="utf-8")
web_tutorial = (ROOT / "website/tutorial.html").read_text(encoding="utf-8")
check("WEB1 version.json 1.7.3 且 changelog 首条含二轮要点（展示玩家）",
      web_version["version"] == "1.7.3" and
      any("展示玩家" in c for c in web_version["changelog"][:1]))
check("WEB2 index.html 发布于 2026-11-05 + 双 JAR 链接",
      "发布于 2026-11-05" in web_index and
      "KERONGPenguin_Spigot-1.7.3.jar" in web_index and
      "KERONGPenguin_NeoForge-1.20.4-1.7.3.jar" in web_index)
check("WEB3 tutorial.html 覆盖口径（56 + 线程条目 + cfg-item ≥52）",
      "subtitle-card-gray: 56" in web_tutorial and
      "custom-render-threads" in web_tutorial and
      web_tutorial.count('class="cfg-item"') >= 52)

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
