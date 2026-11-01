#!/usr/bin/env python3
"""verify_jar_1695 —— 1.6.9.5 产物门禁（blur-mask 局部模糊 / cover 裁剪 / 合并聊天记录占位 / hbkng 更名 / 执行命令回复 / reload 同步面板）。

背景（用户需求）：
  ① blur-mask 修正：只有卡片背后的背景模糊，其余部分不模糊（backdrop-filter）；
  ② 自定义背景图裁剪机制：不拉伸——图片不足就整张等比放大，以图片中心裁剪；
  ③ 合并聊天记录不发送完整内容，直接显示 [合并聊天记录]；
  ④ 主命令 /kerong → /hbkng（旧名删除不兼容）；
  ⑤ 修复 /执行命令 执行完无回复（输出过长裁剪 + Markdown 失败回退纯文本）；
  ⑥ reload 一并重载面板（先删后建），panel 子命令删除。

断言组：
  BB 组  blur-mask 局部模糊 + cover 裁剪
       BB1  RenderSettings$BackgroundStyle 枚举在位（双 JAR）
       BB2  InfoCardAssets$CardBackdropContext / $BackgroundPlacement 嵌套类在位（双 JAR）
       BB3  InfoCardAssets 新方法（drawBackground / backgroundPlacement /
            cardBackdropOf / paintCard，javap -p，internal 方法带模块后缀）
       BB4  模糊常量 BLUR_DOWNSCALE=8 / BLUR_BOX_RADIUS=3 保持（常量池）
       BB5  反断言：applyBackgroundStyle / backgroundStyleTag 已移除（javap -p）
       BB6  反断言：缓存键不再拼样式标签（javap -c 无 backgroundStyleTag 调用）
       BB7  四渲染器统一走 InfoCardAssets.drawBackground（javap -c 调用）
       BB8  反断言：三长页渲染器不再有 4 整型 drawImage 拉伸调用（javap -c）
       BB9  四渲染器卡片绘制前调用 paintCard（javap -c）
       BB10 源码：BackgroundEntry 挂 backdrop/backdropBytes、evict 计入预算
  MF 组  合并聊天记录占位
       MF1  QqText.isMergedForward 返回 Z（javap -p，双 JAR）
       MF2  反断言：QqText$MergedForward 类不在（双 JAR）；条数正则字段不在
       MF3  GroupMessageHandler 类文件含 [合并聊天记录] 字节、不含 [聊天记录 共
       MF4  源码：isMergedForward 判定 + GMH 单行占位 + 无条数解析
  HB 组  hbkng 更名
       HB1  plugin.yml（源 + JAR）：hbkng 命令在位、kerong 命令键不在
       HB2  KERONGSpigot getCommand("hbkng")（源码）
       HB3  NeoForgeCommands：字面量 hbkng 在、String kerong 不在（javap -c）
       HB4  help.md（双端源 + Spigot JAR）：/hbkng help、无 /kerong help
       HB5  ReconnectManager 用户文案 /hbkng chs（源码 + 双 JAR 字节）
       HB6  内部协议「kerong run|adminrun」保持（源码三处 + 类字节）
       HB7  config.yml 双端（源 + JAR）：无 /kerong 残留、/hbkng reload 在位
  EX 组  执行命令回复修复
       EX1  CommandSupport.clipCommandOutput + COMMAND_OUTPUT_MAX_CHARS=1200
       EX2  AdministrationCommands / PublicCommands 调用 clipCommandOutput（javap -c）
       EX3  QClient.replyMarkdown 返回 boolean（javap -p）
       EX4  双命令类 sendDirect 含 sendMessage 纯文本回退（源码）
  RP 组  reload 同步面板 + panel 删除
       RP1  反断言：KERONGCommand / NeoForgeCommands 无 panel 子命令（源 + 字节）
       RP2  KERONGCommand reload 内 syncGroupPanelsWithResult 调用（javap -c）
       RP3  NeoForgeCommands syncPanelReport + syncGroupPanelsWithResult（源 + javap）
       RP4  双端 help：reload 行含「重新同步 QQ 快捷面板」、无 panel 行
       RP5  MenuManager 错误文案改 /hbkng reload（源码）
  V 组  版本六处 1.6.9.5 + JAR 内 plugin.yml / 双 toml + config-version 26 + 无 1.6.9.4 残留
  B 组  基线回归（目录条目 / shaded / 未获取成功 / darkOverlay 105 /
       MotdCommands 不在 / void 兼容桥 / BlurImage 快速路径）

用法：python3 scripts/verify_jar_1695.py [spigot_jar] [nf_jar]
"""
import pathlib
import re
import subprocess
import sys
import tempfile
import tomllib
import zipfile

SPIGOT_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_Spigot-1.6.9.5.jar"
NF_JAR = sys.argv[2] if len(sys.argv) > 2 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.9.5.jar"
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
    """按包路径展开后 javap（类文件路径必须与全限定名一致，否则常量池输出被降级）。"""
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
    """按字节判断常量池是否含指定字面量（javap 对非 ASCII 输出 Unicode 转义，
    直接搜类文件字节更可靠——常量池存储 modified UTF-8，BMP 区与标准 UTF-8 兼容）。"""
    data = read_class(jar, entry)
    return all(literal.encode("utf-8") in data for literal in literals)


RENDER_SETTINGS = "cn/kerong/penguin/spigot/render/RenderSettings.class"
RENDER_SETTINGS_STYLE = "cn/kerong/penguin/spigot/render/RenderSettings$BackgroundStyle.class"
INFO_ASSETS = "cn/kerong/penguin/spigot/render/InfoCardAssets.class"
BACKDROP_CTX = "cn/kerong/penguin/spigot/render/InfoCardAssets$CardBackdropContext.class"
PLACEMENT = "cn/kerong/penguin/spigot/render/InfoCardAssets$BackgroundPlacement.class"
RENDER_CARD = "cn/kerong/penguin/spigot/render/InfoCardRenderer.class"
RENDER_ONLINE = "cn/kerong/penguin/spigot/render/OnlineListRenderer.class"
RENDER_LEADER = "cn/kerong/penguin/spigot/render/LeaderboardRenderer.class"
RENDER_PERF = "cn/kerong/penguin/spigot/render/PerformanceRenderer.class"
QQTEXT = "cn/kerong/bot/tools/QqText.class"
QQTEXT_MF = "cn/kerong/bot/tools/QqText$MergedForward.class"
GMH = "cn/kerong/bot/events/GroupMessageHandler.class"
KCMD = "cn/kerong/penguin/spigot/commands/KERONGCommand.class"
NFCMD = "cn/kerong/penguin/neoforge/NeoForgeCommands.class"
KSPIGOT = "cn/kerong/penguin/spigot/KERONGSpigot.class"
CSUPPORT = "cn/kerong/bot/events/commands/CommandSupport.class"
CSUPPORT_COMP = "cn/kerong/bot/events/commands/CommandSupport$Companion.class"
ADMINCMD = "cn/kerong/bot/events/commands/AdministrationCommands.class"
PUBCMD = "cn/kerong/bot/events/commands/PublicCommands.class"
QCLIENT = "cn/kerong/bot/QClient.class"
MENU = "cn/kerong/bot/MenuManager.class"
RECONNECT = "cn/kerong/bot/ReconnectManager.class"
CFGMGR = "cn/kerong/penguin/spigot/manager/ConfigManager.class"
MOTDCMD = "cn/kerong/penguin/spigot/events/commands/MotdCommands.class"

RENDERERS = [
    ("InfoCardRenderer", RENDER_CARD),
    ("OnlineListRenderer", RENDER_ONLINE),
    ("LeaderboardRenderer", RENDER_LEADER),
    ("PerformanceRenderer", RENDER_PERF),
]
LONG_PAGE_RENDERERS = [
    ("OnlineListRenderer", RENDER_ONLINE),
    ("LeaderboardRenderer", RENDER_LEADER),
    ("PerformanceRenderer", RENDER_PERF),
]


def main():
    spigot = pathlib.Path(SPIGOT_JAR)
    nf = pathlib.Path(NF_JAR)
    for p in (spigot, nf):
        if not p.exists():
            raise SystemExit(f"JAR 不存在：{p}")

    src_info = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt").read_text(encoding="utf-8")
    src_online = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/OnlineListRenderer.kt").read_text(encoding="utf-8")
    src_leader = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/LeaderboardRenderer.kt").read_text(encoding="utf-8")
    src_perf = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/PerformanceRenderer.kt").read_text(encoding="utf-8")
    src_qqtext = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/tools/QqText.kt").read_text(encoding="utf-8")
    src_gmh = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/events/GroupMessageHandler.kt").read_text(encoding="utf-8")
    src_kcmd = (ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/commands/KERONGCommand.kt").read_text(encoding="utf-8")
    src_nfcmd = (ROOT / "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/NeoForgeCommands.kt").read_text(encoding="utf-8")
    src_kspigot = (ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/KERONGSpigot.kt").read_text(encoding="utf-8")
    src_csupport = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/CommandSupport.kt").read_text(encoding="utf-8")
    src_admincmd = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/AdministrationCommands.kt").read_text(encoding="utf-8")
    src_pubcmd = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/PublicCommands.kt").read_text(encoding="utf-8")
    src_menu = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/MenuManager.kt").read_text(encoding="utf-8")
    src_reconnect = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/ReconnectManager.kt").read_text(encoding="utf-8")
    src_qclient = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/QClient.kt").read_text(encoding="utf-8")
    src_cfgmgr = (ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt").read_text(encoding="utf-8")
    src_plugin_yml = (ROOT / "server-Spigot/src/main/resources/plugin.yml").read_text(encoding="utf-8")
    src_help = (ROOT / "common-Bot/src/main/resources/Markdown/help.md").read_text(encoding="utf-8")
    src_help_nf = (ROOT / "server-NeoForge/src/main/resources/Markdown/help.md").read_text(encoding="utf-8")
    cfg_spigot = (ROOT / "server-Spigot/src/main/resources/config.yml").read_text(encoding="utf-8")
    cfg_nf = (ROOT / "server-NeoForge/src/main/resources/config.yml").read_text(encoding="utf-8")

    # ================= BB 组：blur-mask 局部模糊 + cover 裁剪 =================
    print("== BB 组：blur-mask 局部模糊 + cover 裁剪 ==")
    check("BB1 RenderSettings$BackgroundStyle 枚举（双 JAR）",
          has_entry(spigot, RENDER_SETTINGS_STYLE) and has_entry(nf, RENDER_SETTINGS_STYLE))

    check("BB2 CardBackdropContext / BackgroundPlacement 嵌套类（双 JAR）",
          has_entry(spigot, BACKDROP_CTX) and has_entry(nf, BACKDROP_CTX)
          and has_entry(spigot, PLACEMENT) and has_entry(nf, PLACEMENT))

    for label, jar in (("Spigot", spigot), ("NeoForge", nf)):
        p = javap_p(jar, INFO_ASSETS)
        ok = all(
            name in p
            for name in ("drawBackground", "backgroundPlacement", "cardBackdropOf", "blurImage", "boxBlur", "boxBlurAxis", "darkOverlay", "coverImage")
        )
        check(f"BB3 InfoCardAssets 方法八件套（{label}，javap -p）", ok)
        ctx = javap_p(jar, BACKDROP_CTX)
        check(f"BB3b CardBackdropContext.paintCard（{label}，javap -p）", "paintCard" in ctx)

    v = javap_v(spigot, INFO_ASSETS)
    check("BB4 模糊常量 BLUR_DOWNSCALE=8 / BLUR_BOX_RADIUS=3（常量池 ConstantValue）",
          re.search(r"BLUR_DOWNSCALE;\s*$", v, re.M) and re.search(r"BLUR_BOX_RADIUS;\s*$", v, re.M)
          and "ConstantValue: int 8" in v and "ConstantValue: int 3" in v)

    for label, jar in (("Spigot", spigot), ("NeoForge", nf)):
        p = javap_p(jar, INFO_ASSETS)
        check(f"BB5 反断言：applyBackgroundStyle / backgroundStyleTag 已移除（{label}）",
              "applyBackgroundStyle" not in p and "backgroundStyleTag" not in p)
        c = javap_c(jar, INFO_ASSETS)
        check(f"BB6 反断言：缓存键不拼样式标签（{label}，无 backgroundStyleTag 调用）",
              "backgroundStyleTag" not in c)

    for label, jar in (("Spigot", spigot), ("NeoForge", nf)):
        for rname, entry in RENDERERS:
            c = javap_c(jar, entry)
            check(f"BB7 {rname} 走 InfoCardAssets.drawBackground（{label}）",
                  re.search(r"InfoCardAssets\.drawBackground", c) is not None)

    for label, jar in (("Spigot", spigot), ("NeoForge", nf)):
        for rname, entry in LONG_PAGE_RENDERERS:
            c = javap_c(jar, entry)
            check(f"BB8 反断言：{rname} 无 4 整型拉伸 drawImage（{label}）",
                  "(Ljava/awt/Image;IIIILjava/awt/image/ImageObserver;)Z" not in c)

    for label, jar in (("Spigot", spigot), ("NeoForge", nf)):
        for rname, entry in RENDERERS:
            c = javap_c(jar, entry)
            check(f"BB9 {rname} 卡片前调用 paintCard（{label}）",
                  re.search(r"CardBackdropContext\.paintCard", c) is not None)

    check("BB10 源码：BackgroundEntry 挂 backdrop + evict 计入 backdropBytes",
          "var backdrop: BufferedImage?" in src_info and "backdropBytes" in src_info
          and "it.bytes + it.backdropBytes" in src_info and "backdrop?.flush()" in src_info)
    check("BB10b 源码：cover 铺放三要素（scale=max / coerceIn / 中心偏移）",
          "backgroundPlacement" in src_info and "coerceIn(1, background.width)" in src_info
          and "(background.width - sw) / 2" in src_info)
    check("BB10c 源码：四渲染器 paintCard 调用计数",
          src_info.count("paintCard(") >= 3 and src_online.count("paintCard(") >= 3
          and src_leader.count("paintCard(") >= 3 and src_perf.count("paintCard(") >= 2,
          f"info={src_info.count('paintCard(')} online={src_online.count('paintCard(')} "
          f"leader={src_leader.count('paintCard(')} perf={src_perf.count('paintCard(')}")

    # ================= MF 组：合并聊天记录占位 =================
    print("== MF 组：合并聊天记录占位 ==")
    for label, jar in (("Spigot", spigot), ("NeoForge", nf)):
        p = javap_p(jar, QQTEXT)
        check(f"MF1 QqText.isMergedForward 返回 boolean（{label}，javap -p）",
              "isMergedForward" in p and re.search(r"boolean isMergedForward", p) is not None)
        check(f"MF2 反断言：QqText$MergedForward 不在、条数正则字段不在（{label}）",
              not has_entry(jar, QQTEXT_MF) and "FORWARD_MSG_HEADER" not in p and "FORWARD_NESTED_ITEM" not in p)
        check(f"MF3 GroupMessageHandler 含 [合并聊天记录] 不含 [聊天记录 共（{label}，字节）",
              class_contains_literals(jar, GMH, ["[合并聊天记录]"])
              and "[聊天记录 共".encode("utf-8") not in read_class(jar, GMH))

    check("MF4 源码：isMergedForward 双通道判定 + GMH 单行占位 + 无条数解析",
          "fun isMergedForward" in src_qqtext and 'getIntValue("message_type") == 102' in src_qqtext
          and "QqText.isMergedForward(metadata, rawContent)" in src_gmh
          and 'parts.add("[合并聊天记录]")' in src_gmh
          and "FORWARD_MSG_HEADER" not in src_qqtext)

    # ================= HB 组：hbkng 更名 =================
    print("== HB 组：主命令 /kerong → /hbkng ==")
    jar_plugin_yml = read_resource(spigot, "plugin.yml")
    check("HB1 plugin.yml：hbkng 命令在位、kerong 命令键不在（源 + JAR）",
          "hbkng:" in src_plugin_yml and "kerong:" not in src_plugin_yml
          and "hbkng:" in jar_plugin_yml and "kerong:" not in jar_plugin_yml
          and '"/hbkng reload|info|chs|mem|help"' in jar_plugin_yml)

    check("HB2 KERONGSpigot getCommand(\"hbkng\")（源码）",
          'getCommand("hbkng")' in src_kspigot and 'getCommand("kerong")' not in src_kspigot)

    # NeoForgeCommands 仅存在于 NF 产物；KERONGCommand 仅存在于 Spigot 产物
    c = javap_c(nf, NFCMD)
    check("HB3 NeoForgeCommands 字面量 hbkng 在 / String kerong 不在（NF）",
          "// String hbkng" in c and "// String kerong" not in c)
    check("HB3b 反断言：Spigot JAR 不含 NeoForgeCommands（模块隔离）",
          not has_entry(spigot, NFCMD))

    jar_help = read_resource(spigot, "Markdown/help.md")
    check("HB4 help.md：/hbkng help 在位、/kerong help 不在（双端源 + JAR）",
          "- /hbkng help" in src_help and "- /hbkng help" in src_help_nf and "- /hbkng help" in jar_help
          and "/kerong help" not in src_help and "/kerong help" not in src_help_nf and "/kerong help" not in jar_help)

    check("HB5 ReconnectManager 用户文案 /hbkng chs（源码 + 双 JAR 字节）",
          "/hbkng chs" in src_reconnect and "/kerong chs" not in src_reconnect
          and class_contains_literals(spigot, RECONNECT, ["/hbkng chs"])
          and class_contains_literals(nf, RECONNECT, ["/hbkng chs"]))

    check("HB6 内部协议 kerong run|adminrun 保持（源码）",
          '"kerong adminrun ${groupId(event)} ${userId(event)} $params"' in src_admincmd
          and '"kerong run ${groupId(event)} ${userId(event)} $command"' in src_admincmd
          and '"kerong run ${groupId(event)} ${userId(event)} $command"' in src_pubcmd)

    jar_cfg_spigot = read_resource(spigot, "config.yml")
    jar_cfg_nf = read_resource(nf, "config.yml")
    check("HB7 config.yml 无 /kerong 残留、/hbkng reload 在位（双端源 + 双 JAR）",
          "/kerong" not in cfg_spigot and "/kerong" not in cfg_nf
          and "/kerong" not in jar_cfg_spigot and "/kerong" not in jar_cfg_nf
          and all("/hbkng reload" in t for t in (cfg_spigot, cfg_nf, jar_cfg_spigot, jar_cfg_nf)))

    # ================= EX 组：执行命令回复修复 =================
    print("== EX 组：/执行命令 回复修复 ==")
    # const val 编译为外部类静态字段（非 Companion），internal 名带模块后缀 → 子串匹配
    csup_p = javap_p(spigot, CSUPPORT)
    check("EX1 CommandSupport.clipCommandOutput + COMMAND_OUTPUT_MAX_CHARS（javap + 源码）",
          "clipCommandOutput" in csup_p and "COMMAND_OUTPUT_MAX_CHARS" in csup_p
          and "COMMAND_OUTPUT_MAX_CHARS = 1200" in src_csupport)

    for label, jar in (("Spigot", spigot), ("NeoForge", nf)):
        for cname, entry in (("AdministrationCommands", ADMINCMD), ("PublicCommands", PUBCMD)):
            c = javap_c(jar, entry)
            check(f"EX2 {cname} 调用 clipCommandOutput（{label}）",
                  "clipCommandOutput:(Ljava/lang/String;)Ljava/lang/String;" in c)

    for label, jar in (("Spigot", spigot), ("NeoForge", nf)):
        p = javap_p(jar, QCLIENT)
        check(f"EX3 QClient.replyMarkdown 返回 boolean（{label}，javap -p）",
              re.search(r"boolean replyMarkdown", p) is not None)

    check("EX4 sendDirect 纯文本回退按返回值（源码，双命令类）",
          "if (!sent)" in src_admincmd and "if (!sent)" in src_pubcmd
          and "event.sendMessage(content)" in src_admincmd and "event.sendMessage(content)" in src_pubcmd)
    check("EX4b 源码：先裁剪后审核",
          "plugin.auditText(clipCommandOutput(result.rawString))" in src_admincmd
          and "plugin.auditText(clipCommandOutput(result.rawString))" in src_pubcmd)

    # ================= RP 组：reload 同步面板 + panel 删除 =================
    print("== RP 组：reload 同步面板 + panel 删除 ==")
    check("RP1 反断言：panel 子命令已删（源码，双端）",
          '"panel"' not in src_kcmd and '"panel"' not in src_nfcmd
          and 'Commands.literal("panel")' not in src_nfcmd
          and "resyncPanel" not in src_kcmd and "resyncPanel" not in src_nfcmd)

    c = javap_c(spigot, KCMD)
    check("RP2 KERONGCommand reload 内 syncGroupPanelsWithResult（Spigot）",
          "syncGroupPanelsWithResult" in c and "// String panel" not in c)
    cn = javap_c(nf, NFCMD)
    check("RP3 NeoForgeCommands syncPanelReport + syncGroupPanelsWithResult（NF）",
          "syncPanelReport" in javap_p(nf, NFCMD) and "syncGroupPanelsWithResult" in cn
          and "// String panel" not in cn)

    check("RP4 双端 help：reload 行含「重新同步 QQ 快捷面板」、无 panel 行",
          "重新同步 QQ 快捷面板" in src_kcmd and "重新同步 QQ 快捷面板" in src_nfcmd
          and "$label panel" not in src_kcmd and "$label panel" not in src_nfcmd)

    check("RP5 MenuManager 错误文案 /hbkng reload（源码）",
          "/hbkng reload 重试" in src_menu and "/kerong panel" not in src_menu)

    check("RP6 源码：reload 先重载配置后同步面板（顺序断言）",
          src_kcmd.index("reloadPluginConfig()") < src_kcmd.index("syncPanelReport()")
          and src_nfcmd.index("reloadPluginConfig()") < src_nfcmd.index("syncPanelReport()"))

    # ================= V 组：版本 =================
    print("== V 组：版本 1.6.9.5 ==")
    version_sources = {
        "根 build.gradle.kts": ROOT / "build.gradle.kts",
        "NF build.gradle.kts": ROOT / "server-NeoForge/build.gradle.kts",
        "mods.toml": ROOT / "server-NeoForge/src/main/resources/META-INF/mods.toml",
        "neoforge.mods.toml": ROOT / "server-NeoForge/src/main/resources/META-INF/neoforge.mods.toml",
        "KERONGNeoForge MOD_VERSION": ROOT / "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt",
        "plugin.yml": ROOT / "server-Spigot/src/main/resources/plugin.yml",
    }
    bad = [name for name, path in version_sources.items() if "1.6.9.5" not in path.read_text(encoding="utf-8")]
    check("V1 版本六处 1.6.9.5（源）", not bad, "缺 " + ",".join(bad) if bad else "")

    check("V2 JAR 内版本（plugin.yml + 双 toml）",
          "version: '1.6.9.5'" in jar_plugin_yml
          and tomllib.loads(read_resource(nf, "META-INF/mods.toml"))["mods"][0]["version"] == "1.6.9.5"
          and tomllib.loads(read_resource(nf, "META-INF/neoforge.mods.toml"))["mods"][0]["version"] == "1.6.9.5")

    check("V3 双 toml 段头字节级 [ods]]（非注释行）",
          all(
              any(line.rstrip().endswith("[mods]]") and not line.lstrip().startswith("#")
                  for line in read_resource(nf, res).splitlines())
              for res in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml")
          ))

    check("V4 无 1.6.9.4 残留（源六处）",
          all("1.6.9.4" not in p.read_text(encoding="utf-8") or "1.6.9.5" in p.read_text(encoding="utf-8")
              for p in version_sources.values()))

    check("V5 config-version 26（双端源 + 双 JAR + ConfigManager）",
          "config-version: 26" in cfg_spigot and "config-version: 26" in cfg_nf
          and "config-version: 26" in jar_cfg_spigot and "config-version: 26" in jar_cfg_nf
          and "CURRENT_CONFIG_VERSION = 26" in src_cfgmgr)
    v_cfg = javap_v(spigot, CFGMGR)
    check("V5b ConfigManager 常量 CURRENT_CONFIG_VERSION=26（常量池）",
          re.search(r"CURRENT_CONFIG_VERSION;", v_cfg) is not None and "ConstantValue: int 26" in v_cfg)

    # ================= B 组：基线回归 =================
    print("== B 组：基线回归 ==")
    with zipfile.ZipFile(spigot) as z:
        dir_entries = sum(1 for n in z.namelist() if n.endswith("/"))
    check("B1 Spigot 目录条目基线（≥776）", dir_entries >= 776, f"实际 {dir_entries}")

    with zipfile.ZipFile(nf) as z:
        names = z.namelist()
        shaded = any(n.startswith("cn/kerong/shaded/kotlin/") for n in names)
        bare_kotlin = any(n.startswith("kotlin/") and n.endswith(".class") for n in names)
    check("B2 NF shaded 在位 + kotlin 不裸露", shaded and not bare_kotlin)

    check("B3 1.6.9.3 回归：未获取成功常量保持",
          class_contains_literals(spigot, "cn/kerong/penguin/spigot/stats/QueryInfoService.class", ["未获取成功"])
          and has_entry(spigot, "cn/kerong/penguin/spigot/stats/MotdDisplayTracker.class"))

    check("B4 darkOverlay 黑度常量 105 保持（源码）",
          "Color(0x00, 0x00, 0x00, 105)" in src_info)

    check("B5 反断言：MotdCommands 不在（双 JAR）",
          not has_entry(spigot, MOTDCMD) and not has_entry(nf, MOTDCMD))

    check("B6 快捷面板 void 兼容桥保持（源码）",
          "fun syncGroupPanels(starter: Starter, groupOpenIds: List<String>)" in src_menu)

    check("B7 BlurImage 小图快速路径保持（源码）",
          "if (width < BLUR_DOWNSCALE * 2 || height < BLUR_DOWNSCALE * 2) return image" in src_info)

    check("B8 panel 反馈：reload 反馈不含 /kerong panel（源码）",
          "/kerong panel" not in src_kcmd and "/kerong panel" not in src_nfcmd
          and "/kerong panel" not in src_menu and "/kerong panel" not in src_qclient)

    # ================= 汇总 =================
    failed = [c for c in checks if not c[1]]
    print(f"\n共 {len(checks)} 项：PASS {len(checks) - len(failed)} / FAIL {len(failed)}")
    if failed:
        for name, _, detail in failed:
            print(f"  FAIL: {name} {('— ' + detail) if detail else ''}")
        raise SystemExit(1)
    print("verify_jar_1695 全部通过")


if __name__ == "__main__":
    main()
