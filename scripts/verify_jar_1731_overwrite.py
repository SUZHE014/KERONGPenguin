#!/usr/bin/env python3
"""
verify_jar_1731_overwrite.py —— 1.7.3.1 覆盖版门禁（2026-11-06）
用户两项（同版本号 1.7.3.1 直接覆盖）：
① 副标题灰色文字换回以前版本的（参考 1.7.0 查在线状态栏）——固定灰
  0x9FB3C8 + 22f 常规字重，五轮自适应双色 / 1.7.3.1 初版连续对比度配色
  （subtitleBaseLum / subLabelColorFor / canvasLuminance / wcagLinear /
  wcagSrgb / SUB_LABEL_* / COLOR_SUB_LABEL[_DARK]）整体移除；
  查信息身份条「玩家名称」标签同步回固定色（1.7.3.1 初版曾接自适应）。
② 副标题框默认灰度换成和项目框一样——render.subtitle-card-gray
  默认 56 → 106（= item-card-gray），历史默认 40 / 56 升级自动收敛，
  config-version 32 → 33（无新增键）。

  F1 组  本轮源码（回退 + 灰度对齐）：
      F1a InfoCard drawSubLabel 新签名（无 baseLum）+ 固定灰 COLOR_LABEL
      F1b drawSubLabel 22f 常规字重（1.7.0 同款）
      F1c COLOR_LABEL = 0x9FB3C8（1.7.0 查在线状态栏同款）
      F1d 连续配色入口整体移除（subLabelColorFor/subtitleBaseLum/canvasLuminance/
          wcagLinear/wcagSrgb/SUB_LABEL_*/COLOR_SUB_LABEL*）
      F1e drawIdentityBar 回固定色（无 image 参数 / 无采样）
      F1f InfoCard drawSubtitle 无采样 / render() 调用点新签名
      F1g 三列表卡副标题方法新签名（无 image 参数）+ drawSubLabel 无 baseLum
      F1h RenderSettings.DEFAULT_SUBTITLE_CARD_GRAY = 106
      F1i ConfigManager DEFAULT_VALUES 106 + CURRENT_CONFIG_VERSION = 33
      F1j ConfigManager reload 兜底收敛（40/56 → 106，1 until 33）
      F1k ConfigFileRebuilder 收敛（40/56 → 106）
      F1l 双端 config.yml subtitle-card-gray: 106 + config-version: 33
  F2 组  本轮字节码：
      F2a drawSubLabel 新签名（Graphics2D, String, float, float）
      F2b drawSubLabel 体内 22.0f + getstatic COLOR_LABEL
      F2c 连续配色五方法移除（后缀无关）+ COLOR_SUB_LABEL 字段移除
      F2d drawIdentityBar 旧签名（无 BufferedImage）
      F2e drawIdentityBar 体内不调 subLabelColorFor、直接 getstatic COLOR_LABEL
      F2f 三列表卡副标题方法不调 subtitleBaseLum、仍调 drawSubLabel
      F2g RenderSettings subtitleCardGray() 默认 106
      F2h ConfigManager 常量池含 106 与收敛日志「与项目卡相同」
  R45 组 TOP x / 共享入口沿用
  R3 组  三轮几何零改动（HEADER 182 / 副标题条顶 240 / 条高 84 / 眉题 64·136）
  R2 组  二轮沿用（展示玩家格 / Leaderboard 计时移除 / 其余三处保留）
  RC 组  一轮仍有效项（徽标 +36 / 线程三键 / padding / GAP32 / 综合状态）
  CF 组  config-version 33（源 + 双 JAR config.yml）
  FT 组  字体子集「渲」+「展示玩家」
  B1 组  基线：目录条目 / shaded / 版本 1.7.3.1
  WEB 组 官网四件套覆盖版口径（回退 + 106 + config-version 33）
用法：python3 scripts/verify_jar_1731_overwrite.py [spigot_jar] [nf_jar]
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
CFG_MGR = "cn.kerong.penguin.spigot.manager.ConfigManager"
LB_ENTRY = "cn/kerong/penguin/spigot/render/LeaderboardRenderer.class"
INF_ENTRY = "cn/kerong/penguin/spigot/render/InfoCardRenderer.class"
CFG_ENTRY = "cn/kerong/penguin/spigot/manager/ConfigManager.class"

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


def javap_v(jar, cls):
    out = subprocess.run([JAVAP, "-p", "-v", "-cp", jar, cls], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"javap failed: {out.stderr[:300]}")
    return out.stdout


def jar_read_text(jar, entry):
    with zipfile.ZipFile(jar) as z:
        return z.read(entry).decode("utf-8", "replace")


def class_contains_bytes(jar, entry, needle: bytes):
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

# ============ F1 本轮源码 ============
print("== F1 组：1.7.3.1 覆盖版源码（回退 1.7.0 固定灰 + 灰度对齐） ==")
info_src = source_text(INFO_REL)
check("F1a InfoCard drawSubLabel 新签名（无 baseLum）",
      "internal fun drawSubLabel(g: Graphics2D, text: String, x: Float, y: Float)" in info_src)
sub_seg = info_src.split("internal fun drawSubLabel")[1].split("private fun")[0] if "internal fun drawSubLabel" in info_src else ""
check("F1a2 drawSubLabel 固定灰 COLOR_LABEL + 22f 常规字重（1.7.0 同款）",
      "g.font = font(22f, false)" in sub_seg and "g.color = COLOR_LABEL" in sub_seg)
check("F1c InfoCard COLOR_LABEL = 0x9FB3C8（1.7.0 查在线状态栏同款）",
      "private val COLOR_LABEL = Color(0x9F, 0xB3, 0xC8)" in info_src)
check("F1d 连续配色入口整体移除（五方法 + 全部常量）",
      all(tok not in info_src for tok in (
          "subLabelColorFor", "subtitleBaseLum", "canvasLuminance",
          "wcagLinear", "wcagSrgb", "SUB_LABEL_", "COLOR_SUB_LABEL")))
id_seg = info_src.split("private fun drawIdentityBar")[1].split("private fun")[0] if "private fun drawIdentityBar" in info_src else ""
check("F1e drawIdentityBar 回固定色（无 image 参数 / 无采样 / COLOR_LABEL）",
      "image: BufferedImage" not in info_src.split("private fun drawIdentityBar")[0].split("fun ")[-1] and
      "g.color = COLOR_LABEL" in id_seg and "玩家名称" in id_seg)
check("F1e2 drawIdentityBar 签名无 BufferedImage",
      "private fun drawIdentityBar(g: Graphics2D, playerName: String, backdrop: InfoCardAssets.CardBackdropContext?)" in info_src)
check("F1f InfoCard drawSubtitle 无采样",
      "private fun drawSubtitle(g: Graphics2D, serverName: String, updateTime: String, backdrop: InfoCardAssets.CardBackdropContext?)" in info_src
      and info_src.count("drawSubtitle(g, data.serverName") >= 1)
check("F1f2 render() 调用点新签名（identity/subtitle 无 image）",
      "drawIdentityBar(g, data.playerName, backdrop)" in info_src and
      "drawSubtitle(g, data.serverName, data.updateTime, backdrop)" in info_src)
for name, rel in BIG:
    src = source_text(rel)
    anchor = "drawStatusBar" if name == "OnlineList" else "drawSubtitle"
    check(f"F1g {name}.{anchor} 新签名（无 image 参数）+ drawSubLabel 无 baseLum",
          "drawSubLabel(g, label, x + 26f, top + 34f)" in src and
          f"private fun {anchor}(g: Graphics2D, backdrop:" in src and
          "subtitleBaseLum" not in src and "subLabelColorFor" not in src)

settings_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/RenderSettings.kt")
check("F1h RenderSettings.DEFAULT_SUBTITLE_CARD_GRAY = 106",
      "const val DEFAULT_SUBTITLE_CARD_GRAY = 106" in settings_src)

cfg_mgr = source_text("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
check("F1i ConfigManager DEFAULT_VALUES 106 + CURRENT_CONFIG_VERSION = 33",
      '"render.subtitle-card-gray" to 106,' in cfg_mgr and
      "private const val CURRENT_CONFIG_VERSION = 33" in cfg_mgr)
conv_seg = cfg_mgr.split("1.7.3.1 覆盖版：副标题灰度对齐项目卡")[1].split("// 清理已废弃")[0] if "1.7.3.1 覆盖版：副标题灰度对齐项目卡" in cfg_mgr else ""
check("F1j ConfigManager reload 兜底收敛（40/56 → 106，1 until 33）",
      "oldVersion in 1 until 33" in conv_seg and "subGray == 40 || subGray == 56" in conv_seg and
      'config.set("render.subtitle-card-gray", 106)' in conv_seg)
rebuild_src = source_text("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.kt")
check("F1k ConfigFileRebuilder 收敛（40/56 → 106）",
      "subGrayValue == 40 || subGrayValue == 56" in rebuild_src and
      'disk.set("render.subtitle-card-gray", 106)' in rebuild_src)
cfg_spigot = source_text("server-Spigot/src/main/resources/config.yml")
cfg_nf = source_text("server-NeoForge/src/main/resources/config.yml")
check("F1l 双端 config.yml subtitle-card-gray: 106 + config-version: 33",
      "subtitle-card-gray: 106" in cfg_spigot and "subtitle-card-gray: 106" in cfg_nf and
      "config-version: 33" in cfg_spigot and "config-version: 33" in cfg_nf)

# ============ F2 本轮字节码 ============
print("== F2 组：1.7.3.1 覆盖版字节码 ==")
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    inf_decl = javap_p(jar, INF)
    # internal 后缀 Spigot=$common_Bot / NF=$server_NeoForge，用后缀无关形式匹配
    check(f"F2a {tag} drawSubLabel 新签名（三参 float，无 baseLum）",
          "void drawSubLabel" in inf_decl and
          "(java.awt.Graphics2D, java.lang.String, float, float)" in inf_decl and
          "baseLum" not in inf_decl)
    check(f"F2c {tag} 连续配色五方法移除（后缀无关）",
          all(m not in inf_decl for m in ("subLabelColorFor", "subtitleBaseLum",
                                           "canvasLuminance", "wcagLinear", "wcagSrgb")))
    check(f"F2c2 {tag} COLOR_SUB_LABEL / COLOR_SUB_LABEL_DARK 字段移除", "COLOR_SUB_LABEL" not in inf_decl)
    check(f"F2d {tag} drawIdentityBar 旧签名（无 BufferedImage）",
          "drawIdentityBar(java.awt.Graphics2D, java.lang.String," in inf_decl and
          "drawIdentityBar(java.awt.Graphics2D, java.awt.image.BufferedImage" not in inf_decl)
    inf_dis = javap_c(jar, INF)
    sub_body = inf_dis.split("void drawSubLabel")[1].split("public final")[0] if "void drawSubLabel" in inf_dis else ""
    check(f"F2b {tag} drawSubLabel 体内 22.0f 字号 + getstatic COLOR_LABEL",
          "22.0f" in sub_body and "COLOR_LABEL" in sub_body and "subLabelColorFor" not in sub_body)
    id_seg2 = inf_dis.split("void drawIdentityBar")[1].split("private final")[0] if "void drawIdentityBar" in inf_dis else ""
    check(f"F2e {tag} drawIdentityBar 体内 getstatic COLOR_LABEL（不调配色函数）",
          "COLOR_LABEL" in id_seg2 and "subLabelColorFor" not in id_seg2 and "subtitleBaseLum" not in id_seg2)
    check(f"F2e2 {tag} InfoCard 字节码无 0x5C7288 基准色（92/114/136 构造清零）",
          not class_contains_bytes(jar, INF_ENTRY, bytes([92, 114, 136, 92, 114, 136])))
    for cls, nm, anc in ((OL, "OnlineList", "drawStatusBar"), (LB, "Leaderboard", "drawSubtitle"),
                         (PERF, "Performance", "drawSubtitle")):
        dis = javap_c(jar, cls)
        seg2 = dis.split(f"void {anc}")[1].split("void draw")[0] if f"void {anc}" in dis else ""
        check(f"F2f {tag} {nm}.{anc} 不调 subtitleBaseLum、仍调 drawSubLabel",
              "subtitleBaseLum" not in seg2 and "drawSubLabel" in seg2)
        check(f"F2f2 {tag} {nm}.{anc} 签名无 BufferedImage（javap 声明）",
              "java.awt.image.BufferedImage" not in javap_p(jar, cls).split(f"void {anc}")[0].split("drawHeader")[-1])
    set_dis = javap_c(jar, SETTINGS)
    sub_get = set_dis.split("int subtitleCardGray()")[1].split("public")[0] if "int subtitleCardGray()" in set_dis else ""
    check(f"F2g {tag} RenderSettings.subtitleCardGray 默认 106（bipush/sipush 106）",
          "bipush        106" in sub_get or "sipush        106" in sub_get)
    set_v = javap_v(jar, SETTINGS)
    gray_field_seg = set_v.split("public static final int DEFAULT_SUBTITLE_CARD_GRAY;")[1][:120] if "public static final int DEFAULT_SUBTITLE_CARD_GRAY;" in set_v else ""
    check(f"F2g2 {tag} RenderSettings DEFAULT_SUBTITLE_CARD_GRAY 常量 106",
          "ConstantValue: int 106" in gray_field_seg)
    check(f"F2h {tag} ConfigManager 常量池含收敛日志「与项目卡相同」",
          class_contains_bytes(jar, CFG_ENTRY, "与项目卡相同，可自行改回".encode("utf-8")))
    check(f"F2n {tag} Leaderboard 常量池含 makeConcat recipe「TOP \\u0001」",
          class_contains_bytes(jar, LB_ENTRY, b"TOP \x01"))

# ============ R45 四五轮仍有效项 ============
print("== R45 组：四五轮仍有效项（TOP x + 共享入口） ==")
lb_src = source_text(BIG[1][1])
check("R45a Leaderboard 源码 TOP x 格式", '"TOP $displayedTop"' in lb_src)
check("R45b 三列表卡不自带副标签常量（旧静态方案仍移除）",
      all("COLOR_SUB_LABEL" not in source_text(rel) for _, rel in BIG))
check("R45c drawSubLabel 共享入口仍在（四渲染器接线）",
      all("drawSubLabel(" in source_text(rel) for _, rel in BIG))

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

for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    text = jar_read_text(jar, "config.yml")
    check(f"RC2c {tag} JAR config.yml subtitle-card-gray: 106", "subtitle-card-gray: 106" in text)

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

# ============ CF config-version 33 ============
print("== CF 组：config-version 33 ==")
check("CF1 ConfigManager 源 config-version 33",
      any("33" in ln and "CURRENT_CONFIG_VERSION" in ln for ln in cfg_mgr.splitlines()))
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    text = jar_read_text(jar, "config.yml")
    check(f"CF2 {tag} JAR config.yml config-version 33", "config-version: 33" in text)
    check(f"CF3 {tag} JAR config.yml 注释口径「默认与项目卡片相同」",
          "副标题条（服务器 / 更新时间 / 渲染耗时）用，默认与项目卡片相同" in text)

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

# ============ WEB 官网四件套覆盖版口径 ============
print("== WEB 组：官网四件套（website/ 底稿，1.7.3.1 覆盖版口径） ==")
web_version = json.loads((ROOT / "website/version.json").read_text(encoding="utf-8"))
web_index = (ROOT / "website/index.html").read_text(encoding="utf-8")
web_tutorial = (ROOT / "website/tutorial.html").read_text(encoding="utf-8")
first_change = web_version["changelog"][0] if web_version["changelog"] else ""
check("WEB1 version.json 1.7.3.1 且 changelog 首条含回退 + 106 + 配置版本 33",
      web_version["version"] == "1.7.3.1" and "回退 1.7.0" in first_change
      and "56 → 106" in first_change and "32 → 33" in first_change)
check("WEB2 index.html 发布于 2026-11-06 + 双 JAR 链接 + 更新日志首段 v1.7.3.1 回退口径",
      "发布于 2026-11-06" in web_index and
      "KERONGPenguin_Spigot-1.7.3.1.jar" in web_index and
      "KERONGPenguin_NeoForge-1.20.4-1.7.3.1.jar" in web_index and
      "回退 1.7.0 查在线同款固定灰" in web_index)
check("WEB3 index.html 历史表首行 NeoForge 1.7.3.1（NF 在前 Spigot 在后）",
      web_index.find("NeoForge 1.7.3.1") < web_index.find("Spigot 1.7.3.1") <
      web_index.find("NeoForge 1.7.3</td>"))
check("WEB4 tutorial.html 副标题灰度条目同步（106 + 与项目卡片相同 + cfg-item ≥52）",
      "subtitle-card-gray: 106" in web_tutorial and
      "默认 106 与项目卡片相同" in web_tutorial and
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
