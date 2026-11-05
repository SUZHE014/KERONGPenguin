#!/usr/bin/env python3
"""
verify_jar_1731.py —— 1.7.3.1 门禁（2026-11-06）
修复：副标题灰色小标签在图片稍大（画布更高 → cover 裁剪把背景更亮区域
铺到副标题条背后）时显示不清晰——五轮 150 阈值二值切换的模糊区
（卡底 110~200 亮度上两侧都只有 1.8~2.7:1）。1.7.3.1 改连续对比度配色：
分界下移 110，亮色卡深色标签按卡底亮度同色相等比压暗（WCAG ≥ 3:1），
纯白背景精确还原五轮 0x5C7288；查信息身份条「玩家名称」标签同接自适应。
无配置变更（config-version 32 不变）。

  F1 组  本轮源码（连续对比度配色）：
      F1a InfoCard 源码 subLabelColorFor（连续配色入口）
      F1b SUB_LABEL_LIGHT_MAX_LUM = 110f（分界 150 → 110 下移）
      F1c SUB_LABEL_CONTRAST_TARGET = 3.05f / F1d SUB_LABEL_MIN_SCALE = 0.30f
      F1e subtitleBaseLum 连续值（×(255-gray)/255，不再返回 Boolean）
      F1f subtitleCardBright 二值判定移除
      F1g wcagLinear / wcagSrgb 在位 / F1l 双基准色 0xB8C8D8 / 0x5C7288 不变
      F1h/F1i drawIdentityBar 接自适应（采样 + subLabelColorFor，固定浅色移除）
      F1j 四渲染器 subtitleBaseLum + drawSubLabel 接线
  F2 组  本轮字节码：
      F2a subLabelColorFor / F2b subtitleBaseLum 方法在位（后缀无关）
      F2c subtitleCardBright 移除 / F2d 110.0f + 3.05f + 109.0f + 0.3f + bipush 92
      F2e 旧 150.0f 阈值清零 / F2f Math.round 防通道舍入漂移
      F2g 四渲染器副标题方法调 subtitleBaseLum（不调 subtitleCardBright）
      F2h drawIdentityBar 新签名（BufferedImage 参数）+ 调 subLabelColorFor
      F2i drawSubLabel 新签名（float baseLum，不再 boolean）
  R45 组 四五轮仍有效项：双色常量 / 20f / canvasLuminance 三系数 / TOP x
  R3 组  三轮几何零改动（HEADER 182 / 副标题条顶 240 / 条高 84 / 眉题 64·136）
  R2 组  二轮仍有效项（展示玩家格 / 计时移除）
  RC 组  一轮仍有效项（徽标 +36 / subtitle 56 / 线程三键 / padding / GAP32）
  CF 组  config-version 32（1.7.3.1 无配置变更）
  FT 组  字体子集「渲」+「展示玩家」
  B1 组  基线：目录条目 / shaded / 版本 1.7.3.1
  WEB 组 官网四件套 1.7.3.1 口径
用法：python3 scripts/verify_jar_1731.py [spigot_jar] [nf_jar]
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

# ============ F1 本轮源码 ============
print("== F1 组：1.7.3.1 连续对比度配色（源码） ==")
info_src = source_text(INFO_REL)
check("F1a InfoCard 源码 subLabelColorFor（连续配色入口）",
      "internal fun subLabelColorFor" in info_src)
check("F1b InfoCard 源码 SUB_LABEL_LIGHT_MAX_LUM = 110f（分界 150 → 110）",
      "internal const val SUB_LABEL_LIGHT_MAX_LUM = 110f" in info_src)
check("F1c InfoCard 源码 SUB_LABEL_CONTRAST_TARGET = 3.05f",
      "private const val SUB_LABEL_CONTRAST_TARGET = 3.05f" in info_src)
check("F1d InfoCard 源码 SUB_LABEL_MIN_SCALE = 0.30f",
      "private const val SUB_LABEL_MIN_SCALE = 0.30f" in info_src)
check("F1e InfoCard 源码 subtitleBaseLum 连续值（卡底亮度预测）",
      "internal fun subtitleBaseLum" in info_src and
      "canvasLuminance(image, x, y, w, h) * (255 - gray) / 255f" in info_src)
check("F1f InfoCard 源码 subtitleCardBright 二值判定移除",
      "subtitleCardBright" not in info_src and ">= 150f" not in info_src)
check("F1g InfoCard 源码 wcagLinear / wcagSrgb 在位",
      "private fun wcagLinear" in info_src and "private fun wcagSrgb" in info_src)
check("F1l InfoCard 源码双基准色不变（0xB8C8D8 / 0x5C7288）",
      "COLOR_SUB_LABEL = Color(0xB8, 0xC8, 0xD8)" in info_src and
      "COLOR_SUB_LABEL_DARK = Color(0x5C, 0x72, 0x88)" in info_src)
id_seg = info_src.split("private fun drawIdentityBar")[1].split("private fun")[0] if "private fun drawIdentityBar" in info_src else ""
check("F1h InfoCard drawIdentityBar 接自适应（采样 + subLabelColorFor）",
      "subtitleBaseLum(image" in id_seg and "subLabelColorFor(baseLum)" in id_seg)
check("F1i drawIdentityBar 固定浅色标签移除（不再 g.color = COLOR_LABEL）",
      "g.color = COLOR_LABEL" not in id_seg and "玩家名称" in id_seg)
check("F1i2 drawSubLabel 新签名（baseLum: Float）",
      "internal fun drawSubLabel(g: Graphics2D, text: String, x: Float, y: Float, baseLum: Float)" in info_src)
for name, rel in BIG:
    src = source_text(rel)
    anchor = "drawStatusBar" if name == "OnlineList" else "drawSubtitle"
    check(f"F1j {name} {anchor} 接连续配色（subtitleBaseLum + drawSubLabel）",
          "subtitleBaseLum(" in src and "drawSubLabel(" in src and "subtitleCardBright" not in src)
check("F1j2 InfoCard drawSubtitle 接连续配色",
      info_src.count("subtitleBaseLum(image") >= 2 and "subtitleCardBright" not in info_src)

# ============ F2 本轮字节码 ============
print("== F2 组：1.7.3.1 连续对比度配色（字节码） ==")
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    inf_decl = javap_p(jar, INF)
    # internal 后缀 Spigot=$common_Bot / NF=$server_NeoForge，用后缀无关形式匹配
    check(f"F2a {tag} subLabelColorFor 方法在位", "Color subLabelColorFor" in inf_decl)
    check(f"F2b {tag} subtitleBaseLum 方法在位", "float subtitleBaseLum" in inf_decl)
    check(f"F2c {tag} subtitleCardBright 方法移除", "subtitleCardBright" not in inf_decl)
    check(f"F2i {tag} drawSubLabel 新签名（三 float 参数，不再 boolean）",
          "void drawSubLabel" in inf_decl and
          "(java.awt.Graphics2D, java.lang.String, float, float, float)" in inf_decl)
    check(f"F2p {tag} COLOR_SUB_LABEL / COLOR_SUB_LABEL_DARK 字段在位",
          "COLOR_SUB_LABEL_DARK" in inf_decl and "COLOR_SUB_LABEL;" in inf_decl)

    inf_dis = javap_c(jar, INF)
    seg = inf_dis.split("Color subLabelColorFor")[1].split("public final")[0] if "Color subLabelColorFor" in inf_dis else ""
    check(f"F2d {tag} subLabelColorFor 体内 110.0f 分界 + 3.05f 目标 + 109.0f 贴基准 + 0.3f 下限",
          "110.0f" in seg and "3.05f" in seg and "109.0f" in seg and "0.3f" in seg)
    check(f"F2d2 {tag} subLabelColorFor 体内 bipush 92（基准色 R 通道，色相基准）",
          "bipush        92" in seg)
    check(f"F2f {tag} Math.round 防通道舍入漂移", "Math.round" in seg)
    check(f"F2f2 {tag} subLabelColorFor 调 wcagLinear / wcagSrgb",
          "wcagLinear" in seg and "wcagSrgb" in seg)
    check(f"F2e {tag} InfoCard 字节码旧 150.0f 阈值清零", "150.0f" not in inf_dis)

    id_decl = javap_p(jar, INF)
    check(f"F2h {tag} drawIdentityBar 新签名（BufferedImage 参数）",
          "drawIdentityBar(java.awt.Graphics2D, java.awt.image.BufferedImage," in id_decl)
    id_dis = javap_c(jar, INF)
    id_seg2 = id_dis.split("void drawIdentityBar")[1].split("private final")[0] if "void drawIdentityBar" in id_dis else ""
    check(f"F2h2 {tag} drawIdentityBar 体内调 subtitleBaseLum + subLabelColorFor",
          "subtitleBaseLum" in id_seg2 and "subLabelColorFor" in id_seg2)

    sub_body = inf_dis.split("void drawSubLabel")[1].split("public final")[0] if "void drawSubLabel" in inf_dis else ""
    check(f"F2k {tag} drawSubLabel 体内 20.0f 字号 + 调 subLabelColorFor",
          "20.0f" in sub_body and "subLabelColorFor" in sub_body)
    lum_body = inf_dis.split("float canvasLuminance")[1].split("public final")[0] if "float canvasLuminance" in inf_dis else ""
    check(f"F2m {tag} canvasLuminance 体内 0.299f 加权（不变）", "0.299f" in lum_body)

    for cls, nm, anc in ((OL, "OnlineList", "drawStatusBar"), (LB, "Leaderboard", "drawSubtitle"),
                         (PERF, "Performance", "drawSubtitle")):
        dis = javap_c(jar, cls)
        seg2 = dis.split(f"void {anc}")[1].split("void draw")[0] if f"void {anc}" in dis else ""
        check(f"F2g {tag} {nm}.{anc} 调 subtitleBaseLum（不调 subtitleCardBright）",
              "subtitleBaseLum" in seg2 and "subtitleCardBright" not in seg2 and "drawSubLabel" in seg2)

    check(f"F2n {tag} Leaderboard 常量池含 makeConcat recipe「TOP \\u0001」",
          class_contains_bytes(jar, LB_ENTRY, b"TOP \x01"))

# ============ R45 四五轮仍有效项 ============
print("== R45 组：四五轮仍有效项（TOP x + 双基准色 + 共享入口） ==")
lb_src = source_text(BIG[1][1])
check("R45a Leaderboard 源码 TOP x 格式", '"TOP $displayedTop"' in lb_src)
check("R45b 三列表卡不自带 COLOR_SUB_LABEL（旧静态方案仍移除）",
      all("COLOR_SUB_LABEL" not in source_text(rel) for _, rel in BIG))
check("R45c InfoCard drawSubtitle 采样时机注释（卡体绘制前）",
      "先在卡体绘制前采样画布底色" in info_src)

# ============ R3 三轮几何零改动 ============
print("== R3 组：三轮几何零改动 ==")
for name, rel in BIG:
    src = source_text(rel)
    check(f"R3a {name} 源码 HEADER_HEIGHT = 182", "private const val HEADER_HEIGHT = 182" in src)
    check(f"R3b {name} 源码 cardInset = 14（四周间距不变）",
          "val cardInset = 14" in src and "HEADER_HEIGHT - 2 * cardInset" in src)

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    for cls, name in ((OL, "OnlineList"), (LB, "Leaderboard"), (PERF, "Performance")):
        dis = javap_c(jar, cls)
        body = dis.split("void drawHeader")[1].split("void draw")[0] if "void drawHeader" in dis else ""
        check(f"R3c {tag} {name}.drawHeader 眉题 64 / 主标题 136",
              "bipush        64" in body and "sipush        136" in body)
        check(f"R3d {tag} {name}.drawHeader 标题卡高 182.0f", "182.0f" in body)
    for cls, name, anchor in ((OL, "OnlineList", "drawStatusBar"), (LB, "Leaderboard", "drawSubtitle"),
                              (PERF, "Performance", "drawSubtitle")):
        dis = javap_c(jar, cls)
        seg = dis.split(f"void {anchor}")[1].split("void draw")[0] if f"void {anchor}" in dis else ""
        check(f"R3g {tag} {name}.{anchor} 副标题条顶 240.0f（40+182+18）", "240.0f" in seg)
        check(f"R3h {tag} {name}.{anchor} 副标题条高 84 不变", "84.0f" in seg)
    check(f"R2a {tag} Leaderboard「展示玩家」字面量",
          class_contains_bytes(jar, LB_ENTRY, "展示玩家".encode("utf-8")))
    decl = javap_p(jar, LB)
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

cfg_mgr = source_text("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
check("RC3a ConfigManager 源三键（custom-render-threads 段）", cfg_mgr.count("custom-render-threads") >= 3)
pool_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/CardRenderPool.kt")
check("RC3c CardRenderPool 源读 custom-render-threads", "custom-render-threads" in pool_src)
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    text = jar_read_text(jar, "config.yml")
    check(f"RC3d {tag} JAR config.yml 三键", "custom-render-threads:" in text and
          "render-threads:" in text and "avatar-threads:" in text)
    pool_dis = javap_c(jar, POOL)
    check(f"RC3g {tag} CardRenderPool 调 customRenderThreads()",
          "customRenderThreads:" in pool_dis)

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    decl = javap_p(jar, PERF)
    check(f"RC5a {tag} Performance.buildOverallStatus 在位", "buildOverallStatus(" in decl)
    dis = javap_c(jar, PERF)
    check(f"RC5d {tag} 综合状态文案（运行良好/轻度负载/负载过高）",
          all(s in dis for s in ("运行良好", "轻度负载", "负载过高")))
    check(f"RC7 {tag} SUBTITLE_GAP=32（bipush 32）", "bipush        32" in dis)
    for cls, name in ((OL, "OnlineList"), (LB, "Leaderboard"), (PERF, "Performance")):
        dis2 = javap_c(jar, cls)
        body = dis2.split("void drawHeader")[1].split("void draw")[0] if "void drawHeader" in dis2 else ""
        check(f"RC6 {tag} {name}.drawHeader 左缘 padding 72.0f/68.0f（不变）",
              "72.0f" in body and "68.0f" in body)

# ============ CF config-version 32（1.7.3.1 无配置变更） ============
print("== CF 组：config-version 32 ==")
check("CF1 ConfigManager 源 config-version 32",
      any("32" in ln and "CONFIG_VERSION" in ln for ln in cfg_mgr.splitlines()))
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    text = jar_read_text(jar, "config.yml")
    check(f"CF2 {tag} JAR config.yml config-version 32", "config-version: 32" in text)

# ============ FT 字体子集 ============
print("== FT 组：字体子集 ==")
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

# ============ WEB 官网四件套 1.7.3.1 口径 ============
print("== WEB 组：官网四件套（website/ 底稿，1.7.3.1 口径） ==")
web_version = json.loads((ROOT / "website/version.json").read_text(encoding="utf-8"))
web_index = (ROOT / "website/index.html").read_text(encoding="utf-8")
web_tutorial = (ROOT / "website/tutorial.html").read_text(encoding="utf-8")
first_change = web_version["changelog"][0] if web_version["changelog"] else ""
check("WEB1 version.json 1.7.3.1 且 changelog 首条含修复要点（连续对比度 / 玩家名称）",
      web_version["version"] == "1.7.3.1" and "连续对比度配色" in first_change
      and "玩家名称" in first_change)
check("WEB2 index.html 发布于 2026-11-06 + 双 JAR 新链接 + 更新日志首段 v1.7.3.1",
      "发布于 2026-11-06" in web_index and
      "KERONGPenguin_Spigot-1.7.3.1.jar" in web_index and
      "KERONGPenguin_NeoForge-1.20.4-1.7.3.1.jar" in web_index and
      "v1.7.3.1" in web_index)
check("WEB3 index.html 历史表首行 NeoForge 1.7.3.1（NF 在前 Spigot 在后）",
      web_index.find("NeoForge 1.7.3.1") < web_index.find("Spigot 1.7.3.1") <
      web_index.find("NeoForge 1.7.3</td>"))
check("WEB4 tutorial.html 未动（无配置变更，56 + 线程条目 + TOP x + cfg-item ≥52）",
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
