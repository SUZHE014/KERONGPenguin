#!/usr/bin/env python3
"""
verify_jar_173_round45.py —— 1.7.3 覆盖版四+五轮门禁（同版本号覆盖，2026-11-05）
四轮（TOP x）+ 五轮（小标签自适应配色）合计三项用户反馈：
  R4 组  本轮源码/字节码（四渲染器统一走共享入口）：
      R4a InfoCard 源码 COLOR_SUB_LABEL = 0xB8C8D8（深卡浅蓝灰变体，柔和）
      R4b InfoCard 源码 COLOR_SUB_LABEL_DARK = 0x5C7288（亮卡深蓝灰变体，可读）
      R4c InfoCard 源码 internal drawSubLabel（20f 常规字重共享入口，无描边）
      R4d InfoCard 源码 internal subtitleCardBright（×(255-gray)/255 ≥ 150f）
      R4e InfoCard 源码 internal canvasLuminance（0.299/0.587/0.114，8×4 网格）
      R4f 四渲染器 drawSubtitle/drawStatusBar 采样 + 接 drawSubLabel
      R4g Leaderboard 源码 "TOP $displayedTop"（四轮 TOP x 格式）
      R4h 三列表卡不再自带 COLOR_SUB_LABEL（旧静态方案移除）
      R4i-R4m 字节码：drawSubLabel/subtitleCardBright/canvasLuminance 方法
          + 20.0f + 150.0f + 0.299f + getstatic 双色字段 + "TOP \u0001" recipe
      R4n-R4o 四渲染器副标题方法新签名（BufferedImage 参数）
  R3 组  三轮仍有效项（几何零改动是五轮前置承诺）：
      HEADER_HEIGHT=182 / 副标题条顶 240.0f / 条高 84 / 眉题 64/136/51/109
  R2 组  二轮仍有效项：展示玩家格 + Leaderboard 计时移除 + cardInset 14
  RC 组  一轮仍有效项：徽标灰度 / subtitle 56 / 线程三键 / padding / GAP32
  CF 组  config-version 32（四五轮无配置变更）
  FT 组  字体子集「渲」+「展示玩家」
  B1 组  基线：目录条目 / shaded / 版本 1.7.3
  WEB 组 官网四件套四五轮覆盖口径（自适应 + TOP x）
用法：python3 scripts/verify_jar_173_round45.py [spigot_jar] [nf_jar]
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
INFO_REL = "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt"

SETTINGS = "cn.kerong.penguin.spigot.render.RenderSettings"
POOL = "cn.kerong.penguin.spigot.render.CardRenderPool"
PERF = "cn.kerong.penguin.spigot.render.PerformanceRenderer"
OL = "cn.kerong.penguin.spigot.render.OnlineListRenderer"
LB = "cn.kerong.penguin.spigot.render.LeaderboardRenderer"
INF = "cn.kerong.penguin.spigot.render.InfoCardRenderer"
LB_ENTRY = "cn/kerong/penguin/spigot/render/LeaderboardRenderer.class"
INF_ENTRY = "cn/kerong/penguin/spigot/render/InfoCardRenderer.class"

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


def class_contains_bytes(jar, entry, needle: bytes):
    with zipfile.ZipFile(jar) as z:
        data = z.read(entry)
    return needle in data


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

# ============ R4 四+五轮 ============
print("== R4 组：覆盖版四+五轮（TOP x + 小标签自适应配色） ==")
info_src = source_text(INFO_REL)
check("R4a InfoCard 源码 COLOR_SUB_LABEL = 0xB8C8D8（深卡浅蓝灰变体）",
      "COLOR_SUB_LABEL = Color(0xB8, 0xC8, 0xD8)" in info_src)
check("R4b InfoCard 源码 COLOR_SUB_LABEL_DARK = 0x5C7288（亮卡深蓝灰变体）",
      "COLOR_SUB_LABEL_DARK = Color(0x5C, 0x72, 0x88)" in info_src)
check("R4c InfoCard 源码 drawSubLabel 共享入口（20f 常规字重）",
      "internal fun drawSubLabel" in info_src and "font(20f, false)" in info_src)
check("R4d InfoCard 源码 subtitleCardBright 判亮（≥ 150f）",
      "internal fun subtitleCardBright" in info_src and ">= 150f" in info_src)
check("R4e InfoCard 源码 canvasLuminance（三系数 + 8×4 网格）",
      "internal fun canvasLuminance" in info_src and "0.299f" in info_src
      and "0.587f" in info_src and "0.114f" in info_src)
check("R4e2 InfoCard drawSubtitle 采样子卡体绘制前（注释承诺的采样时机）",
      "先在卡体绘制前采样画布底色" in info_src)
lb_src = source_text(BIG[1][1])
check("R4g Leaderboard 源码 TOP x 格式（四轮）", '"TOP $displayedTop"' in lb_src)

for name, rel in BIG:
    src = source_text(rel)
    anchor = "drawStatusBar" if name == "OnlineList" else "drawSubtitle"
    check(f"R4f {name} {anchor} 采样 + 接 drawSubLabel",
          "subtitleCardBright(" in src and "drawSubLabel(" in src)
    check(f"R4h {name} 旧静态标签方案移除（不再自带 COLOR_SUB_LABEL）",
          "COLOR_SUB_LABEL" not in src)
check("R4h2 InfoCard drawSubtitle 内直接赋旧色已移除（走 drawSubLabel）",
      "g.color = COLOR_SUB_LABEL" not in info_src and "drawSubLabel(" in info_src)

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    inf_decl = javap_p(jar, INF)
    # internal 后缀 Spigot=$common_Bot / NF=$server_NeoForge，用后缀无关形式匹配
    check(f"R4i {tag} drawSubLabel 方法在位",
          "void drawSubLabel" in inf_decl)
    check(f"R4i2 {tag} subtitleCardBright 方法在位",
          "boolean subtitleCardBright" in inf_decl)
    check(f"R4i3 {tag} canvasLuminance 方法在位",
          "float canvasLuminance" in inf_decl)
    check(f"R4p {tag} COLOR_SUB_LABEL_DARK 字段在位", "COLOR_SUB_LABEL_DARK" in inf_decl)

    inf_dis = javap_c(jar, INF)
    sub_body = inf_dis.split("void drawSubLabel")[1].split("public final")[0] if "void drawSubLabel" in inf_dis else ""
    check(f"R4k {tag} drawSubLabel 体内 20.0f 字号 + 双色字段分支",
          "20.0f" in sub_body and "COLOR_SUB_LABEL_DARK" in sub_body and "Field COLOR_SUB_LABEL:" in sub_body)
    bright_body = inf_dis.split("boolean subtitleCardBright")[1].split("public final")[0] if "boolean subtitleCardBright" in inf_dis else ""
    check(f"R4l {tag} subtitleCardBright 体内 150.0f 阈值 + 调 canvasLuminance",
          "150.0f" in bright_body and "canvasLuminance" in bright_body)
    lum_body = inf_dis.split("float canvasLuminance")[1].split("public final")[0] if "float canvasLuminance" in inf_dis else ""
    check(f"R4m {tag} canvasLuminance 体内 0.299f 加权", "0.299f" in lum_body)

    check(f"R4n {tag} Leaderboard 常量池含 makeConcat recipe「TOP \\u0001」",
          class_contains_bytes(jar, LB_ENTRY, b"TOP \x01"))

    # 四渲染器副标题方法新签名（BufferedImage 参数）
    for cls, nm, anc in ((OL, "OnlineList", "drawStatusBar"), (LB, "Leaderboard", "drawSubtitle"),
                         (PERF, "Performance", "drawSubtitle"), (INF, "InfoCard", "drawSubtitle")):
        d = javap_p(jar, cls)
        check(f"R4o {tag} {nm}.{anc} 新签名（BufferedImage 参数）",
              f"{anc}(java.awt.Graphics2D, java.awt.image.BufferedImage," in d or
              f"{anc}(java.awt.Graphics2D, java.awt.image.BufferedImage" in d)
        dis = javap_c(jar, cls)
        seg = dis.split(f"void {anc}")[1].split("void draw")[0] if f"void {anc}" in dis else ""
        check(f"R4f2 {tag} {nm}.{anc} 字节码调 drawSubLabel + subtitleCardBright",
              "drawSubLabel" in seg and "subtitleCardBright" in seg)

# ============ R3 三轮仍有效项（副标题框几何零改动） ============
print("== R3 组：三轮几何零改动（五轮前置承诺） ==")
for name, rel in BIG:
    src = source_text(rel)
    check(f"R3a {name} 源码 HEADER_HEIGHT = 182", "private const val HEADER_HEIGHT = 182" in src)
    check(f"R3b2 {name} 源码 cardInset = 14 四周间距（不变）",
          "val cardInset = 14" in src and "HEADER_HEIGHT - 2 * cardInset" in src)

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    for cls, name in ((OL, "OnlineList"), (LB, "Leaderboard"), (PERF, "Performance")):
        dis = javap_c(jar, cls)
        body = dis.split("void drawHeader")[1].split("void draw")[0] if "void drawHeader" in dis else ""
        check(f"R3c {tag} {name}.drawHeader 眉题 64 / 主标题 136",
              "bipush        64" in body and "sipush        136" in body)
        check(f"R3d {tag} {name}.drawHeader 标题卡高 182.0f", "182.0f" in body)
        check(f"R3e {tag} {name}.drawHeader 徽标标签 51 / 数值 109",
              "bipush        51" in body and "bipush        109" in body)
    for cls, name, anchor in ((OL, "OnlineList", "drawStatusBar"), (LB, "Leaderboard", "drawSubtitle"),
                              (PERF, "Performance", "drawSubtitle")):
        dis = javap_c(jar, cls)
        seg = dis.split(f"void {anchor}")[1].split("void draw")[0] if f"void {anchor}" in dis else ""
        check(f"R3g {tag} {name}.{anchor} 副标题条顶 240.0f（40+182+18）", "240.0f" in seg)
        check(f"R3h {tag} {name}.{anchor} 副标题条高 84 不变", "84.0f" in seg)
    # R2 组沿用
    check(f"R2a {tag} Leaderboard「展示玩家」字面量",
          class_contains_bytes(jar, LB_ENTRY, "展示玩家".encode("utf-8")))
    decl = javap_p(jar, LB)
    check(f"R2a2 {tag} Leaderboard.drawSubtitle 新签名（long 参数 + BufferedImage）",
          "drawSubtitle(java.awt.Graphics2D, java.awt.image.BufferedImage, cn.kerong.penguin.spigot.render.InfoCardAssets$CardBackdropContext, java.lang.String, java.lang.String, long)" in decl)
    check(f"R2b {tag} Leaderboard.drawRenderElapsed 已移除", "drawRenderElapsed(" not in decl)
    for cls, name in ((OL, "OnlineList"), (PERF, "Performance"), (INF, "InfoCard")):
        d = javap_p(jar, cls)
        check(f"R2b2 {tag} {name}.drawRenderElapsed 仍在位", "drawRenderElapsed(" in d)

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

# ============ CF config-version 32（四五轮无配置变更） ============
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

# ============ WEB 官网四件套四五轮覆盖口径 ============
print("== WEB 组：官网四件套（website/ 底稿，四+五轮覆盖口径） ==")
import json  # noqa: E402

web_version = json.loads((ROOT / "website/version.json").read_text(encoding="utf-8"))
web_index = (ROOT / "website/index.html").read_text(encoding="utf-8")
web_tutorial = (ROOT / "website/tutorial.html").read_text(encoding="utf-8")
first_change = web_version["changelog"][0] if web_version["changelog"] else ""
check("WEB1 version.json 1.7.3 且 changelog 首条含四+五轮要点（TOP x / 自适应）",
      web_version["version"] == "1.7.3" and "TOP x" in first_change and "自适应" in first_change)
check("WEB2 index.html 发布于 2026-11-05 + 双 JAR 链接 + 自适应条目",
      "发布于 2026-11-05" in web_index and
      "KERONGPenguin_Spigot-1.7.3.jar" in web_index and
      "KERONGPenguin_NeoForge-1.20.4-1.7.3.jar" in web_index and
      "自适应配色" in web_index)
check("WEB3 tutorial.html 覆盖口径（56 + 线程条目 + TOP x + cfg-item ≥52）",
      "subtitle-card-gray: 56" in web_tutorial and
      "custom-render-threads" in web_tutorial and
      "TOP x" in web_tutorial and
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
