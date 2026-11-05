#!/usr/bin/env python3
"""
verify_jar_173_overwrite.py —— 1.7.3 覆盖版门禁（同版本号覆盖主线，2026-11-05）
覆盖范围（对照 AI-KNOWLEDGE #43-#51 + 官网四件套覆盖口径）：
  RC 组  覆盖版七项：
      RC1  RenderSettings.headerBadgeCardGray()（= titleCardGray()+36 派生）
      RC2  DEFAULT_SUBTITLE_CARD_GRAY=56（ldc 56）+ 源/JAR config subtitle-card-gray: 56
      RC3  custom-render-threads 三键（enabled/render-threads/avatar-threads）：
           源 ConfigManager + CardRenderPool 读取 + 双端 config.yml 三键 + ⚠ 警告注释
      RC4  drawRenderElapsed 在位（OnlineList/Leaderboard/Performance/InfoCard）
      RC5  buildOverallStatus（Performance，OverallStatus 三态）+ drawHeader 新签名
      RC6  padding 常量 72.0f/68.0f（眉题/主标题距卡左缘 28/24px）
      RC7  SUBTITLE_GAP=32（性能卡等距）
  CF 组  config-version 32（源 + 双 JAR config.yml）
  FT 组  字体子集含「渲」（双端四 ttf，fonttools 解析 cmap）
  B1 组  基线：目录条目 776±/ shaded / 版本 1.7.3 / 字体资源
  WEB 组 官网四件套覆盖口径（website/ 底稿）：
      WEB1 version.json 1.7.3（含覆盖版 changelog：渲染耗时/综合状态/56）
      WEB2 index.html 发布于 2026-11-05 + 1.7.3 JAR 链接
      WEB3 tutorial.html subtitle-card-gray: 56 + custom-render-threads 条目（cfg-item ≥52）
用法：python3 scripts/verify_jar_173_overwrite.py [spigot_jar] [nf_jar]
"""
import pathlib
import subprocess
import sys
import zipfile

SPIGOT_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_Spigot-1.7.3.jar"
NF_JAR = sys.argv[2] if len(sys.argv) > 2 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.3.jar"
JAVAP = str(pathlib.Path("/home/z/my-project/tooling/jdk21/bin/javap"))
ROOT = pathlib.Path(__file__).resolve().parent.parent


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
CONFIG_ENTRY = "cn/kerong/penguin/spigot/manager/ConfigManager.class"

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
        # NF 剔除 org/slf4j、com/google/gson（split package），条目规模小于 Spigot 实属正常
        check(f"B1a {tag} 目录条目 ≥600（fat JAR 装配安全）", len(dirs) >= 600)
        check(f"B1b {tag} 条目数 >10000", len(names) > 10000)
    check(f"B1d {tag} 字体资源在位", "fonts/NotoSansSC-Regular-subset.ttf" in names)
    if tag == "NF":
        check("B1e NF shaded 包在位", any(n.startswith("cn/kerong/shaded/kotlin/") for n in names))
        check("B1f NF 无顶层 slf4j", not any(n.startswith("org/slf4j/") for n in names))

# ============ RC1 徽标灰度 ============
print("== RC 组：覆盖版七项 ==")
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    decl = javap_p(jar, SETTINGS)
    check(f"RC1a {tag} RenderSettings.headerBadgeCardGray() 方法在位",
          "headerBadgeCardGray()" in decl)
    dis = javap_c(jar, SETTINGS)
    check(f"RC1b {tag} headerBadgeCardGray 体内 bipush 36（+36 派生）",
          "bipush        36" in dis.split("headerBadgeCardGray()")[1].split("public")[0]
          if "headerBadgeCardGray()" in dis else False)

# ============ RC2 副标题灰度 56 ============
cfg_spigot = source_text("server-Spigot/src/main/resources/config.yml")
cfg_nf = source_text("server-NeoForge/src/main/resources/config.yml")
check("RC2a Spigot 源 subtitle-card-gray: 56", "subtitle-card-gray: 56" in cfg_spigot)
check("RC2b NF 源 subtitle-card-gray: 56", "subtitle-card-gray: 56" in cfg_nf)
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    text = jar_read_text(jar, "config.yml")
    check(f"RC2c {tag} JAR config.yml subtitle-card-gray: 56", "subtitle-card-gray: 56" in text)
    dis = javap_c(jar, SETTINGS)
    # DEFAULT_SUBTITLE_CARD_GRAY 为 const val → 内联；方法体内读键后 ldc 56 兜底
    check(f"RC2d {tag} RenderSettings 副标题默认 56 在位",
          "subtitle-card-gray" in dis and ("ldc           56" in dis or "bipush        56" in dis))

# ============ RC3 自定义渲染线程 ============
cfg_mgr = source_text("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
check("RC3a ConfigManager 源三键（custom-render-threads 段）", cfg_mgr.count("custom-render-threads") >= 3)
check("RC3b ConfigManager enabled/render-threads/avatar-threads 键名",
      "custom-render-threads.enabled" in cfg_mgr and
      "custom-render-threads.render-threads" in cfg_mgr and
      "custom-render-threads.avatar-threads" in cfg_mgr)
pool_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/CardRenderPool.kt")
check("RC3c CardRenderPool 源读 custom-render-threads", "custom-render-threads" in pool_src)
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    text = jar_read_text(jar, "config.yml")
    check(f"RC3d {tag} JAR config.yml 三键", "custom-render-threads:" in text and
          "render-threads:" in text and "avatar-threads:" in text)
    check(f"RC3e {tag} JAR config.yml ⚠ 警告注释", ("调大渲染线程" in text or "争抢" in text))
    check(f"RC3f {tag} RenderSettings 字节码含键字面量（CardRenderPool 经其读取）",
          class_contains_literals(jar, SETTINGS_ENTRY, ["custom-render-threads"]))
    pool_dis = javap_c(jar, POOL)
    check(f"RC3g {tag} CardRenderPool 调 customRenderThreads()",
          "customRenderThreads:" in pool_dis)

# ============ RC4 渲染耗时 ============
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    for cls, name in ((OL, "OnlineList"), (LB, "Leaderboard"), (PERF, "Performance"), (INF, "InfoCard")):
        decl = javap_p(jar, cls)
        check(f"RC4 {tag} {name}.drawRenderElapsed 在位", "drawRenderElapsed(" in decl)

# ============ RC5 综合状态 ============
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    decl = javap_p(jar, PERF)
    check(f"RC5a {tag} Performance.buildOverallStatus 在位", "buildOverallStatus(" in decl)
    check(f"RC5b {tag} OverallStatus 类型引用", "OverallStatus" in decl)
    check(f"RC5c {tag} drawHeader 新签名（OverallStatus 参数）",
          "drawHeader(java.awt.Graphics2D, cn.kerong.penguin.spigot.render.InfoCardAssets$CardBackdropContext, cn.kerong.penguin.spigot.render.PerformanceRenderer$OverallStatus)" in decl)
    dis = javap_c(jar, PERF)
    check(f"RC5d {tag} 综合状态文案（运行良好/轻度负载/负载过高）",
          all(s in dis for s in ("运行良好", "轻度负载", "负载过高")))

# ============ RC6 标题卡内边距 ============
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    for cls, name in ((OL, "OnlineList"), (LB, "Leaderboard"), (PERF, "Performance")):
        dis = javap_c(jar, cls)
        body = dis.split("void drawHeader")[1].split("void draw")[0] if "void drawHeader" in dis else ""
        check(f"RC6 {tag} {name}.drawHeader padding 72.0f/68.0f",
              "72.0f" in body and "68.0f" in body)

# ============ RC7 性能卡等距 ============
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
        check(f"FT1 源字体含「渲」({f.split('/')[0]})", has_char(ROOT / f, "渲"))
    import tempfile

    for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
        with zipfile.ZipFile(jar) as z:
            for entry in ("fonts/NotoSansSC-Regular-subset.ttf", "fonts/NotoSansSC-Bold-subset.ttf"):
                with tempfile.NamedTemporaryFile(suffix=".ttf", delete=False) as tf:
                    tf.write(z.read(entry))
                    tf.flush()
                    check(f"FT2 {tag} JAR 字体含「渲」({entry.split('/')[1][:20]})",
                          has_char(tf.name, "渲"))
except ImportError:
    check("FT 字体组（fonttools 缺失，跳过）", False)

# ============ WEB 官网四件套覆盖口径 ============
print("== WEB 组：官网四件套（website/ 底稿，覆盖版口径） ==")
import json  # noqa: E402

web_version = json.loads((ROOT / "website/version.json").read_text(encoding="utf-8"))
web_index = (ROOT / "website/index.html").read_text(encoding="utf-8")
web_tutorial = (ROOT / "website/tutorial.html").read_text(encoding="utf-8")
check("WEB1 version.json 1.7.3 且 changelog 首条含覆盖要点",
      web_version["version"] == "1.7.3" and
      any("渲染耗时" in c for c in web_version["changelog"][:1]) and
      any("综合状态" in c for c in web_version["changelog"][:1]))
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
