#!/usr/bin/env python3
"""verify_jar_1700 —— 1.7.0 产物门禁（头像在线获取+外层修复 / 毛玻璃统一 / hbkrg 更名 / 执行命令真根因 / 配置精简）。

背景（用户需求）：
  ① 头像：本地获取链路删除（缓存保留），统一在线获取；修复只有内层外层纹理
     丢失（检查获取源——Minotar /avatar 与 MinePic /avatar 实测均只回内层）；
  ② 渲染：只保留毛玻璃样式（原版遮罩删除），透明度配置改为模糊度
     （render.blur-strength，默认 50），卡片灰色调浅，默认模糊调低；
  ③ 主命令 /hbkng → /hbkrg；
  ④ 修复 /执行命令 仍无输出（SDK 对平台拒收不抛异常 + 控制字符清洗 +
     NeoForge 执行器输出定格 bug + 异步回复）；
  ⑤ 配置注释精简（删版本标记）；旧默认值→新默认值升级机制删除。

断言组：
  AV 组  头像链路
       AV1  反断言：本地链路构件不在（fetchViaLocalChain / resolveLocalSkinUrl /
            extractNbtSkinUrl / walkRootCompound / querySkinInfo，javap -p）
       AV2  Minotar 改 helm 线路：minotar.net/helm/ 在、minotar.net/avatar/ 不在（双 JAR 字节）
       AV3  MinePic 改皮肤裁脸：minepic.org/skin/ 在、minepic.org/avatar/ 不在（双 JAR 字节）
       AV4  Crafatar overlay / MC-Heads 回归保持（双 JAR 字节）
       AV5  AvatarRequest 两参构造 (Ljava/util/UUID;Ljava/lang/String;)V（javap -p，双 JAR）
       AV6  RenderSettings 无 avatarFetchPriority；$AvatarFetchPriority 嵌套类不在
       AV7  源码：四处构造点无 skinUrl / playerDataDir；排行榜改 snapshotOnlineUuids
       AV8  config 双端（源 + JAR）：无 avatar-fetch-priority 键
  BL 组  毛玻璃统一与模糊度
       BL1  反断言：RenderSettings$BackgroundStyle / GlobalOpacity 类不在（双 JAR）
       BL2  RenderSettings.blurStrength 在（javap -p）+ render.blur-strength 字面量（字节）
       BL3  InfoCardAssets：blurDownscaleOf / blurBoxRadiusOf 在；无 BLUR_DOWNSCALE /
            BLUR_BOX_RADIUS 静态字段（javap -p）
       BL4  反断言：darkOverlay 方法不在（javap -p，双 JAR）
       BL5  BackgroundEntry 挂 backdropStrength 字段（javap -p，双 JAR）
       BL6  cardBackdropOf 读 RenderSettings.blurStrength（javap -c）
       BL7  卡片底色调浅：源码统计面板黑 76、容器黑 64（四渲染器）
       BL8  回归：drawBackground / paintCard / backgroundPlacement / cover 保持
  HB 组  hbkrg 更名
       HB1  plugin.yml（源 + JAR）：hbkrg 在、hbkng / kerong 命令键不在
       HB2  KERONGSpigot getCommand("hbkrg")（源码 + 字节）
       HB3  NeoForgeCommands：hbkrg 字面量在、hbkng 不在（字节）
       HB4  help.md（双端源 + Spigot JAR）：/hbkrg help、无 /hbkng
       HB5  ReconnectManager 用户文案 /hbkrg chs（源码 + 双 JAR 字节）
       HB6  内部协议「kerong run|adminrun」保持（源码三处 + 类字节）
       HB7  config.yml 双端（源 + JAR）：/hbkrg 在位、无 /hbkng
  EX 组  执行命令真根因
       EX1  CommandSupport：sanitizeConsoleText 在（javap -p）+ ansiEscapeRegex 字段
       EX2  源码：clipCommandOutput 先清洗再裁剪
       EX3  QClient.replyMarkdown：按 V2Result.id 判真实送达（源码 isNullOrEmpty +
            javap -c 含 id 调用）
       EX4  NeoForgeConsoleExecutor：capturedText 字段（javap -p）+ stopCapture 定格（源码）
       EX5  源码：双命令类 whenComplete 内 submitAsync（回产出主线程）
       EX6  源码：BukkitConsoleSender / HybridCommandExecutor 捕获 Throwable
       EX7  源码：MessageTemplates.sendDirect 按返回值回退纯文本
  CF 组  配置精简
       CF1  config-version: 27（双端源 + JAR 内置 + ConfigManager 常量池）
       CF2  源码：ConfigFileRebuilder 无旧默认升级常量（OLD_FROM_GROUP 等）
       CF3  双端 config（源 + JAR）：无「覆盖版 / 1.6.」版本标记；blur-strength: 50
       CF4  源码：REMOVED_CONFIG_KEYS 含三新键；DEFAULT_VALUES 无三旧键有 blur-strength
  V 组  版本六处 1.7.0 + JAR 内 plugin.yml / 双 toml + 无 1.6.9.5 残留
  B 组  基线回归（目录条目 / shaded / 未获取成功 / [合并聊天记录] /
       MotdCommands 不在 / 内部协议）

用法：python3 scripts/verify_jar_1700.py [spigot_jar] [nf_jar]
"""
import pathlib
import re
import subprocess
import sys
import tempfile
import tomllib
import zipfile

SPIGOT_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_Spigot-1.7.0.jar"
NF_JAR = sys.argv[2] if len(sys.argv) > 2 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.0.jar"
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


def javap_v(jar, entry):
    return _javap_run(jar, entry, ("-v",))


def class_contains_literals(jar, entry, literals):
    data = read_class(jar, entry)
    return all(literal.encode("utf-8") in data for literal in literals)


RENDER_SETTINGS = "cn/kerong/penguin/spigot/render/RenderSettings.class"
INFO_ASSETS = "cn/kerong/penguin/spigot/render/InfoCardAssets.class"
BACKDROP_CTX = "cn/kerong/penguin/spigot/render/InfoCardAssets$CardBackdropContext.class"
PLACEMENT = "cn/kerong/penguin/spigot/render/InfoCardAssets$BackgroundPlacement.class"
BACKDROP_ENTRY = "cn/kerong/penguin/spigot/render/InfoCardAssets$BackgroundEntry.class"
RENDER_CARD = "cn/kerong/penguin/spigot/render/InfoCardRenderer.class"
RENDER_ONLINE = "cn/kerong/penguin/spigot/render/OnlineListRenderer.class"
RENDER_LEADER = "cn/kerong/penguin/spigot/render/LeaderboardRenderer.class"
RENDER_PERF = "cn/kerong/penguin/spigot/render/PerformanceRenderer.class"
GMH = "cn/kerong/bot/events/GroupMessageHandler.class"
NFCMD = "cn/kerong/penguin/neoforge/NeoForgeCommands.class"
KSPIGOT = "cn/kerong/penguin/spigot/KERONGSpigot.class"
CSUPPORT = "cn/kerong/bot/events/commands/CommandSupport.class"
ADMINCMD = "cn/kerong/bot/events/commands/AdministrationCommands.class"
PUBCMD = "cn/kerong/bot/events/commands/PublicCommands.class"
QCLIENT = "cn/kerong/bot/QClient.class"
RECONNECT = "cn/kerong/bot/ReconnectManager.class"
CFGMGR = "cn/kerong/penguin/spigot/manager/ConfigManager.class"
NFEXEC = "cn/kerong/penguin/neoforge/NeoForgeConsoleExecutor.class"
MTEMPLATES = "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class"
BUKSENDER = "cn/kerong/penguin/spigot/commands/BukkitConsoleSender.class"
HYBEXEC = "cn/kerong/penguin/spigot/commands/HybridCommandExecutor.class"
QUERYINFO = "cn/kerong/penguin/spigot/stats/QueryInfoService.class"
ONLINELIST = "cn/kerong/penguin/spigot/stats/OnlineListService.class"
MOTDCMD = "cn/kerong/penguin/spigot/events/commands/MotdCommands.class"

JARS = [("Spigot", SPIGOT_JAR), ("NF", NF_JAR)]


def src(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


def main():
    spigot = pathlib.Path(SPIGOT_JAR)
    nf = pathlib.Path(NF_JAR)
    for p in (spigot, nf):
        if not p.exists():
            raise SystemExit(f"JAR 不存在：{p}")

    # ================= AV 组：头像链路 =================
    print("== AV 组：头像统一在线获取 + 外层纹理修复 ==")
    for tag, jar in JARS:
        p_info = javap_p(jar, INFO_ASSETS)
        removed = ["fetchViaLocalChain", "resolveLocalSkinUrl", "extractNbtSkinUrl", "walkRootCompound"]
        check(f"AV1 {tag} 本地链路构件不在", all(m not in p_info for m in removed), ",".join(removed))
    for tag, jar in JARS:
        check(f"AV2 {tag} Minotar helm", class_contains_literals(jar, INFO_ASSETS, ["minotar.net/helm/"]))
        data = read_class(jar, INFO_ASSETS)
        check(f"AV2 {tag} Minotar /avatar 不在", b"minotar.net/avatar/" not in data)
    for tag, jar in JARS:
        check(f"AV3 {tag} MinePic skin 裁脸", class_contains_literals(jar, INFO_ASSETS, ["minepic.org/skin/"]))
        data = read_class(jar, INFO_ASSETS)
        check(f"AV3 {tag} MinePic /avatar 不在", b"minepic.org/avatar/" not in data)
    for tag, jar in JARS:
        check(
            f"AV4 {tag} Crafatar overlay / MC-Heads 保持",
            class_contains_literals(jar, INFO_ASSETS, ["crafatar.com/avatars/", "overlay", "mc-heads.net/avatar/"]),
        )
    for tag, jar in JARS:
        p_req = javap_p(jar, "cn/kerong/penguin/spigot/render/InfoCardAssets$AvatarRequest.class")
        check(
            f"AV5 {tag} AvatarRequest 两参构造",
            "(java.util.UUID, java.lang.String)" in p_req and "skinUrl" not in p_req and "playerDataDir" not in p_req,
        )
    for tag, jar in JARS:
        p_rs = javap_p(jar, RENDER_SETTINGS)
        check(f"AV6 {tag} 无 avatarFetchPriority", "avatarFetchPriority" not in p_rs)
        check(f"AV6 {tag} $AvatarFetchPriority 类不在", not has_entry(jar, "cn/kerong/penguin/spigot/render/RenderSettings$AvatarFetchPriority.class"))
    qsrc = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/QueryInfoService.kt")
    osrc = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/OnlineListService.kt")
    lsrc = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/LeaderboardCommands.kt")
    csrc = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/CoinsLeaderboardCommands.kt")
    check(
        "AV7 源码四构造点无本地参数",
        "skinUrl" not in qsrc and "playerDataDir" not in qsrc and "skinUrl" not in osrc
        and "playerDataDir" not in osrc and "skinUrl =" not in lsrc and "skinUrl =" not in csrc
        and "snapshotOnlineUuids" in lsrc and "snapshotOnlineUuids" in csrc and "querySkinInfo" not in qsrc,
    )
    for rel in ["server-Spigot/src/main/resources/config.yml", "server-NeoForge/src/main/resources/config.yml"]:
        check(f"AV8 {rel.split('/')[0]} config 无 avatar-fetch-priority", "avatar-fetch-priority" not in src(rel))
    for tag, jar in JARS:
        check(f"AV8 {tag} JAR config 无 avatar-fetch-priority", "avatar-fetch-priority" not in read_resource(jar, "config.yml"))

    # ================= BL 组：毛玻璃统一 =================
    print("== BL 组：毛玻璃统一 + 模糊度 ==")
    for tag, jar in JARS:
        check(f"BL1 {tag} BackgroundStyle 类不在", not has_entry(jar, "cn/kerong/penguin/spigot/render/RenderSettings$BackgroundStyle.class"))
        check(f"BL1 {tag} GlobalOpacity 类不在", not has_entry(jar, "cn/kerong/penguin/spigot/render/GlobalOpacity.class"))
        p_rs = javap_p(jar, RENDER_SETTINGS)
        check(f"BL2 {tag} blurStrength 方法在", "blurStrength" in p_rs and "globalOpacity" not in p_rs and "backgroundStyle" not in p_rs)
        check(f"BL2 {tag} render.blur-strength 字面量", class_contains_literals(jar, RENDER_SETTINGS, ["render.blur-strength"]))
        p_ia = javap_p(jar, INFO_ASSETS)
        check(
            f"BL3 {tag} 强度映射方法在 / 旧常量不在",
            "blurDownscaleOf" in p_ia and "blurBoxRadiusOf" in p_ia
            and "BLUR_DOWNSCALE" not in p_ia and "BLUR_BOX_RADIUS" not in p_ia,
        )
        check(f"BL4 {tag} darkOverlay 不在", "darkOverlay" not in p_ia)
        p_be = javap_p(jar, BACKDROP_ENTRY)
        check(f"BL5 {tag} BackgroundEntry.backdropStrength", "backdropStrength" in p_be)
        c_bd = javap_c(jar, INFO_ASSETS)
        check(f"BL6 {tag} cardBackdropOf 读 blurStrength", "blurStrength" in c_bd)
    icsrc = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt")
    olsrc = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/OnlineListRenderer.kt")
    ldsrc = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/LeaderboardRenderer.kt")
    pfsrc = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/PerformanceRenderer.kt")
    check(
        "BL7 卡片底色调浅（统计面板 76 / 容器 64）",
        "0x00, 0x00, 0x00, 76" in icsrc and "0x00, 0x00, 0x00, 142" not in icsrc
        and icsrc.count("0x00, 0x00, 0x00, 64") == 0
        and olsrc.count("0x00, 0x00, 0x00, 64") == 3 and "0x00, 0x00, 0x00, 120" not in olsrc
        and ldsrc.count("0x00, 0x00, 0x00, 64") == 2 and "0x00, 0x00, 0x00, 120" not in ldsrc
        and pfsrc.count("0x00, 0x00, 0x00, 64") == 1 and "0x00, 0x00, 0x00, 120" not in pfsrc,
    )
    for tag, jar in JARS:
        p_ia = javap_p(jar, INFO_ASSETS)
        check(
            f"BL8 {tag} 回归：drawBackground/paintCard/cover 保持",
            "drawBackground" in p_ia and "backgroundPlacement" in p_ia and "cardBackdropOf" in p_ia
            and has_entry(jar, BACKDROP_CTX) and has_entry(jar, PLACEMENT),
        )
        # 四渲染器仍走共享入口
        for name, entry in [("InfoCard", RENDER_CARD), ("OnlineList", RENDER_ONLINE), ("Leaderboard", RENDER_LEADER), ("Performance", RENDER_PERF)]:
            c_r = javap_c(jar, entry)
            check(f"BL8 {tag} {name} 走 drawBackground", "drawBackground" in c_r)
        # 四渲染器不再引用 GlobalOpacity
        for name, entry in [("InfoCard", RENDER_CARD), ("OnlineList", RENDER_ONLINE), ("Leaderboard", RENDER_LEADER), ("Performance", RENDER_PERF)]:
            c_r = javap_c(jar, entry)
            check(f"BL8 {tag} {name} 无 GlobalOpacity 调用", "GlobalOpacity" not in c_r)

    # ================= HB 组：hbkrg 更名 =================
    print("== HB 组：/hbkng → /hbkrg ==")
    pl_src = read_resource(spigot, "plugin.yml")
    pl_file = src("server-Spigot/src/main/resources/plugin.yml")
    check("HB1 plugin.yml hbkrg 在 / hbkng·kerong 键不在", "hbkrg:" in pl_src and "hbkng" not in pl_src and "kerong:" not in pl_src)
    check("HB1 源 plugin.yml 同步", "hbkrg:" in pl_file and "hbkng" not in pl_file)
    ksp_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/KERONGSpigot.kt")
    check("HB2 KERONGSpigot getCommand(hbkrg)", 'getCommand("hbkrg")' in ksp_src)
    check("HB2 KERONGSpigot 字节 hbkrg", class_contains_literals(spigot, KSPIGOT, ['"hbkrg"']) or b"hbkrg" in read_class(spigot, KSPIGOT))
    check("HB3 NF NeoForgeCommands hbkrg 在 / hbkng 不在", b"hbkrg" in read_class(nf, NFCMD) and b"hbkng" not in read_class(nf, NFCMD))
    for rel in ["common-Bot/src/main/resources/Markdown/help.md", "server-NeoForge/src/main/resources/Markdown/help.md"]:
        t = src(rel)
        check(f"HB4 {rel.split('/')[0]} help.md /hbkrg", "/hbkrg help" in t and "hbkng" not in t)
    check("HB4 Spigot JAR help.md /hbkrg", "/hbkrg help" in read_resource(spigot, "Markdown/help.md") and "hbkng" not in read_resource(spigot, "Markdown/help.md"))
    rc_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/ReconnectManager.kt")
    check("HB5 ReconnectManager /hbkrg chs", "/hbkrg chs" in rc_src and "hbkng" not in rc_src)
    for tag, jar in JARS:
        check(f"HB5 {tag} 字节 /hbkrg chs", class_contains_literals(jar, RECONNECT, ["/hbkrg chs"]))
    a_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/AdministrationCommands.kt")
    p_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/PublicCommands.kt")
    check(
        "HB6 内部协议 kerong run|adminrun 保持",
        '"kerong run ${groupId(event)}' in a_src and '"kerong adminrun ' in a_src
        and '"kerong run ${groupId(event)}' in p_src,
    )
    for tag, jar in JARS:
        check(f"HB6 {tag} 协议字节保持", b"kerong run" in read_class(jar, ADMINCMD) and b"kerong adminrun" in read_class(jar, ADMINCMD))
    for rel in ["server-Spigot/src/main/resources/config.yml", "server-NeoForge/src/main/resources/config.yml"]:
        t = src(rel)
        check(f"HB7 {rel.split('/')[0]} config /hbkrg 在 /hbkng 不在", "/hbkrg reload" in t and "hbkng" not in t)
    for tag, jar in JARS:
        t = read_resource(jar, "config.yml")
        check(f"HB7 {tag} JAR config /hbkrg", "/hbkrg reload" in t and "hbkng" not in t)

    # ================= EX 组：执行命令真根因 =================
    print("== EX 组：/执行命令 修复 ==")
    for tag, jar in JARS:
        p_cs = javap_p(jar, CSUPPORT)
        check(f"EX1 {tag} sanitizeConsoleText 在", "sanitizeConsoleText" in p_cs and "clipCommandOutput" in p_cs)
        check(f"EX1 {tag} ansiEscapeRegex 字段", "ansiEscapeRegex" in p_cs)
    cs_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/CommandSupport.kt")
    check("EX2 clipCommandOutput 先清洗", "sanitizeConsoleText(output).trim()" in cs_src and "COMMAND_OUTPUT_MAX_CHARS" in cs_src)
    qc_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/QClient.kt")
    check(
        "EX3 replyMarkdown 按消息 id 判送达",
        "!result.id.isNullOrEmpty()" in qc_src and "将回退纯文本" in qc_src,
    )
    for tag, jar in JARS:
        p_qc = javap_p(jar, QCLIENT)
        check(f"EX3 {tag} replyMarkdown 返回 boolean", "boolean replyMarkdown" in p_qc.replace("public final ", "").replace("public ", ""))
        c_qc = javap_c(jar, QCLIENT)
        check(f"EX3 {tag} 送达校验字节（V2Result.getId/getRet）", "V2Result.getId" in c_qc and "V2Result.getRet" in c_qc)
    ne_src = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/NeoForgeConsoleExecutor.kt")
    check(
        "EX4 NeoForge 执行器输出定格",
        "capturedText" in ne_src and "outputAppender.stopCapture().joinToString" in ne_src,
    )
    p_nfexec = javap_p(nf, NFEXEC)
    check("EX4 NF 字节 capturedText", "capturedText" in p_nfexec)
    check(
        "EX5 双命令类回退异步",
        "plugin.submitAsync {" in a_src and "plugin.submitAsync {" in p_src,
    )
    bs_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/commands/BukkitConsoleSender.kt")
    hy_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/commands/HybridCommandExecutor.kt")
    check("EX6 执行器捕获 Throwable", "catch (error: Throwable)" in bs_src and "catch (error: Throwable)" in hy_src)
    mt_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/MessageTemplates.kt")
    check("EX7 MessageTemplates 按返回值回退", "val sent = try {" in mt_src and "if (!sent) {" in mt_src)

    # ================= CF 组：配置精简 =================
    print("== CF 组：配置精简 ==")
    for rel in ["server-Spigot/src/main/resources/config.yml", "server-NeoForge/src/main/resources/config.yml"]:
        t = src(rel)
        check(f"CF1 {rel.split('/')[0]} config-version 27", re.search(r"^config-version: 27$", t, re.M) is not None)
    for tag, jar in JARS:
        t = read_resource(jar, "config.yml")
        check(f"CF1 {tag} JAR config v27", re.search(r"^config-version: 27$", t, re.M) is not None)
        v_cfg = javap_v(jar, CFGMGR)
        check(f"CF1 {tag} ConfigManager 常量池 27", "ConstantValue: int 27" in v_cfg or "= 27" in v_cfg)
    cb_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.kt")
    check(
        "CF2 旧默认升级机制删除",
        "OLD_FROM_GROUP_DEFAULT" not in cb_src and "OLD_IDLE_RELEASE_MINUTES" not in cb_src
        and "OLD_PERFORMANCE_CACHE_SECONDS" not in cb_src and "升级收敛已移除" in cb_src,
    )
    for rel in ["server-Spigot/src/main/resources/config.yml", "server-NeoForge/src/main/resources/config.yml"]:
        t = src(rel)
        check(
            f"CF3 {rel.split('/')[0]} 无版本标记 + blur-strength 50",
            "覆盖版" not in t and "1.6." not in t and re.search(r"^  blur-strength: 50$", t, re.M) is not None,
        )
    cm_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
    check(
        "CF4 REMOVED 三键 + DEFAULT_VALUES 更新",
        '"render.avatar-fetch-priority", "render.background-style", "render.global-opacity"' in cm_src
        and '"render.avatar-fetch-priority" to' not in cm_src and '"render.global-opacity" to' not in cm_src
        and '"render.background-style" to' not in cm_src and '"render.blur-strength" to 50' in cm_src,
    )

    # ================= V 组：版本 =================
    print("== V 组：版本 1.7.0 ==")
    version_files = {
        "根 build.gradle.kts": ("build.gradle.kts", 'version = "1.7.0"'),
        "plugin.yml": ("server-Spigot/src/main/resources/plugin.yml", "version: '1.7.0'"),
        "NF build.gradle.kts": ("server-NeoForge/build.gradle.kts", 'version = "1.7.0"'),
        "MOD_VERSION": ("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt", 'MOD_VERSION = "1.7.0"'),
    }
    for name, (rel, needle) in version_files.items():
        check(f"V1 {name} 1.7.0", needle in src(rel) and "1.6.9.5" not in src(rel))
    for toml_rel in ["server-NeoForge/src/main/resources/META-INF/mods.toml", "server-NeoForge/src/main/resources/META-INF/neoforge.mods.toml"]:
        with open(ROOT / toml_rel, "rb") as fh:
            tdata = tomllib.load(fh)
        ver = tdata["mods"][0]["version"]
        check(f"V1 {toml_rel.split('/')[-1]} 1.7.0", ver == "1.7.0", ver)
    check("V2 JAR plugin.yml 1.7.0", re.search(r"^version: '1\.7\.0'$", pl_src, re.M) is not None)
    for toml_name in ["META-INF/mods.toml", "META-INF/neoforge.mods.toml"]:
        with zipfile.ZipFile(nf) as z:
            import io
            tdata = tomllib.load(io.BytesIO(z.read(toml_name)))
        check(f"V2 NF JAR {toml_name} 1.7.0", tdata["mods"][0]["version"] == "1.7.0")
    check("V3 无 1.6.9.5 版本残留", "1.6.9.5" not in pl_src and "1.6.9.5" not in src("build.gradle.kts"))

    # ================= B 组：基线回归 =================
    print("== B 组：基线回归 ==")
    with zipfile.ZipFile(spigot) as z:
        names = z.namelist()
        dirs = [n for n in names if n.endswith("/")]
    check("B1 目录条目 776", len(dirs) == 776, f"实际 {len(dirs)}")
    with zipfile.ZipFile(nf) as z:
        nf_names = z.namelist()
    check("B2 NF shaded 在位", any(n.startswith("cn/kerong/shaded/") for n in nf_names))
    check("B2 NF kotlin 不裸露", not any(n.startswith("kotlin/") and n.endswith(".class") for n in nf_names))
    check("B3 NF 无 slf4j/gson 顶层", not any(n.startswith("org/slf4j/") for n in nf_names) and not any(n.startswith("com/google/gson/") for n in nf_names))
    check("B4 资源在位", "fonts/NotoSansSC-Regular-subset.ttf" in names and "backgrounds/card-default.png" in names and "Markdown/help.md" in names)
    check("B5 未获取成功回归", class_contains_literals(spigot, QUERYINFO, ["未获取成功"]))
    check("B5 [合并聊天记录] 回归", class_contains_literals(spigot, GMH, ["[合并聊天记录]"]))
    check("B6 MotdCommands 不在", not has_entry(spigot, MOTDCMD))

    fails = [c for c in checks if not c[1]]
    print(f"\n结果：{len(checks) - len(fails)}/{len(checks)} 通过")
    if fails:
        print("失败项：")
        for name, _, detail in fails:
            print(f"  FAIL {name} {detail}")
        raise SystemExit(1)
    print("全部通过 ✅")


if __name__ == "__main__":
    main()
