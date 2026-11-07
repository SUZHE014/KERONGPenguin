#!/usr/bin/env python3
"""verify_jar_174 —— 1.7.4 版产物门禁。

背景（用户需求，主线 1.7.4）：
  ① 每个QQ账号可绑定玩家数上限可配置（qq-bind.max-players-per-qq，默认 1，
     只能 ≥1，配 0/负数按 1 处理）；名下多个玩家时查信息 / 查询open ID /
     重新绑定 / 签到 / 绑定（上限提示）等指令先发数字选择消息，用户直接
     发数字或 @机器人 发数字确认（@机器人+数字不得判为 AI 对话）。
  ② 金币排行榜指令（金币榜别名）移除——1.6.2 更名财富榜后别名保留至今，
     本轮官网字段与命令别名一并清理。
  ③ 修复未开启 AI 功能时快捷面板仍出现 AI 指令按钮的问题。
  ④ 修复 QQ 绑定关闭时签到命令仍可用的问题（命令 + 面板按钮双拦截）。
  ⑤ 查在线多页图片每页重新随机抽背景（img/ 自定义背景模式；内置背景
     观感不变）——线上官网已宣称（fa 会话先行更新文案），本轮随 1.7.4 落地。
  附加：启动未配置凭据 / /hbkrg qr 控制台打印 QQ 配置引导二维码（自包含
  QR 编码器 QrCode.kt，无新增依赖）。

断言组：
  MB  多玩家绑定（源码 + 双 JAR 字节码）
      MB1  QqBindManager：maxPlayersPerQq（默认 1，<1 归 1）+ BindResult 四态
           + listBoundPlayersByQq + unbindAllAndKickByQq + confirmBinding 返回
           BindResult + LIMIT 时绑定码保留 + openidToQuuid 条件移除
      MB2  PlayerSelection：会话 2 分钟 + 1-2 位纯数字 + tryConsume 最高优先
           （GroupMessageHandler 中位于 dispatchCommand / handleAiChat 之前）
           + requireBoundPlayer / selectOrRun / clearAll + reload 双端清空
      MB3  指令改造：查信息（含@成员）/ 查询open ID / 重新绑定 / 签到 /
           绑定 LIMIT 分支（/绑定 + 纯数字自动绑定）/ 黑名单多玩家解绑
      MB4  配置：config-version 35 + DEFAULT_VALUES max-players-per-qq=1 +
           双端模板 max-players-per-qq + 三模板 messages 条目 + Markdown 三件
      MB5  字节码：PlayerSelection 类双 JAR 在位 + BindResult 枚举 +
           listBoundPlayersByQq/unbindAllAndKickByQq 方法 + 常量池
           qq-bind.max-players-per-qq + JAR 内置 config 三处
  GL  金币榜退役
      GL1 源码 @Commands 单名（财富榜）+ COMMAND_ALIASES 无金币榜 +
           CoinsLeaderboardCommands 常量池无"金币榜"字节
      GL2 双端 config 注释无"别名 /金币榜" + help.md 无"可简写 /金币榜" +
           JAR 内同口径
  AI  AI 面板修复
      AI1 MenuManager AI_PANEL_NAMES + aiEnabled 联动（源码）
      AI2 字节码 AI_PANEL_NAMES 集合（UTF-8 字节）
  CK  签到绑定关闭修复
      CK1 CheckInCommands isEnabled 前置（源码 + 字节码 invokevirtual isEnabled）
      CK2 MenuManager 签到按钮 (!checkinEnabled || !qqBindEnabled)（源码）
  BG  查在线多页背景（实现为第四次覆盖的 backgroundProvider 方案，随 1.7.4 发布）
      BG1 OnlineListData.backgroundProvider 字段 + renderSingle 每页
          invoke(height)（源码 + 字节码 getBackgroundProvider / Function1.invoke）
      BG2 OnlineListService 传 provider lambda（源码）+ 无 pageBackgrounds 旧方案残留
  QR  二维码引导
      QR1 QrCode.kt encode/encodeAscii + KERONG.printQqSetupQr +
           双端 /hbkrg qr 子命令（源码 + 字节码 encodeAscii 方法）
  B   基线回归
      B1  目录条目 776 档 / plugin.yml 1.7.4 / mods.toml 1.7.4 / 字体 / shaded
      B2  bpng 64MB 熔断 / hbkrg 命令键
      B3  drawSubLabel 四参 + 0x9FB3C8 固定灰（花里胡哨红线）
      B4  config-version 35 四处（双端源 + 双 JAR 内置）

用法：python3 scripts/verify_jar_174.py [spigot_jar] [nf_jar]
"""
import pathlib
import subprocess
import sys
import zipfile

SPIGOT_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_Spigot-1.7.4.jar"
NF_JAR = sys.argv[2] if len(sys.argv) > 2 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.4.jar"
JAVAP = str(pathlib.Path("/home/z/my-project/tooling/jdk21/bin/javap"))
ROOT = pathlib.Path(__file__).resolve().parent.parent

QBM = "cn.kerong.penguin.spigot.qqbind.QqBindManager"
PS = "cn.kerong.bot.events.PlayerSelection"
OLR = "cn.kerong.penguin.spigot.render.OnlineListRenderer"
CLC = "cn.kerong.bot.events.commands.CoinsLeaderboardCommands"
CIC = "cn.kerong.bot.events.commands.CheckInCommands"

checks = []


def check(name, ok):
    checks.append((name, bool(ok)))


def javap_p(jar, cls):
    out = subprocess.run([JAVAP, "-p", "-cp", jar, cls], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"javap failed: {out.stderr[:300]}")
    return out.stdout


def jar_read_text(jar, entry):
    with zipfile.ZipFile(jar) as z:
        return z.read(entry).decode("utf-8", "replace")


def jar_has(jar, entry):
    with zipfile.ZipFile(jar) as z:
        return entry in z.namelist()


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
            check(f"B1c {tag} plugin.yml 版本 1.7.4", "version: '1.7.4'" in meta)
        else:
            meta = z.read("META-INF/neoforge.mods.toml").decode("utf-8", "replace")
            check(f"B1c {tag} neoforge.mods.toml 版本 1.7.4", 'version = "1.7.4"' in meta)
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

# ============ MB 组：多玩家绑定 ============
print("== MB 组：多玩家绑定 ==")

qbm_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/QqBindManager.kt")
ps_src = source_text("common-Bot/src/main/kotlin/cn/kerong/bot/events/PlayerSelection.kt")
gmh_src = source_text("common-Bot/src/main/kotlin/cn/kerong/bot/events/GroupMessageHandler.kt")
pub_src = source_text("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/PublicCommands.kt")
bind_src = source_text("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/BindCommands.kt")
cic_src = source_text("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/CheckInCommands.kt")
qi_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/QueryInfoService.kt")
mm_src = source_text("common-Bot/src/main/kotlin/cn/kerong/bot/MenuManager.kt")
cfgm_src = source_text("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
cfg_spi = source_text("server-Spigot/src/main/resources/config.yml")
cfg_nf = source_text("server-NeoForge/src/main/resources/config.yml")
spigot_main = source_text("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/KERONGSpigot.kt")
nf_main = source_text("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt")
help_md = source_text("common-Bot/src/main/resources/Markdown/help.md")
help_md_nf = source_text("server-NeoForge/src/main/resources/Markdown/help.md")

# MB1 QqBindManager
check("MB1a maxPlayersPerQq 默认 1 且 <1 归 1",
      'getInt("qq-bind.max-players-per-qq", 1)' in qbm_src and
      "if (value < 1) 1 else value" in qbm_src)
check("MB1b BindResult 四态（SUCCESS/INVALID/BLACKLISTED/LIMIT）",
      "enum class BindResult" in qbm_src and
      "SUCCESS," in qbm_src and "INVALID," in qbm_src and
      "BLACKLISTED," in qbm_src and "LIMIT," in qbm_src)
check("MB1c listBoundPlayersByQq + BoundPlayer 数据类",
      "fun listBoundPlayersByQq(qq: String?): List<BoundPlayer>" in qbm_src and
      "data class BoundPlayer(val playerName: String, val quuuid: String)" in qbm_src)
check("MB1d unbindAllAndKickByQq（黑名单多玩家解绑）",
      "fun unbindAllAndKickByQq(qq: String?): List<String>" in qbm_src)
check("MB1e confirmBinding 返回 BindResult + LIMIT 分支绑定码保留",
      "fun confirmBinding(code: String?, qq: String?): BindResult" in qbm_src and
      "绑定码保留，解绑后可重试" in qbm_src)
check("MB1f openidToQuuid 反查缓存条件移除（多玩家保留其他绑定）",
      "if (openidToQuuid[currentQq] == quuid)" in qbm_src)

# MB2 PlayerSelection
check("MB2a 会话有效期 2 分钟", "SESSION_EXPIRE_MILLIS = 120_000L" in ps_src)
check("MB2b 仅 1-2 位纯数字视为选择尝试（0 不提示）",
      "if (text.length !in 1..2) return false" in ps_src and
      'if (text == "0") return false' in ps_src)
check("MB2c requireBoundPlayer / selectOrRun / tryConsume / clearAll 在位",
      "fun requireBoundPlayer(" in ps_src and
      "fun selectOrRun(" in ps_src and
      "fun tryConsume(event:" in ps_src and
      "fun clearAll()" in ps_src)
# tryConsume 在 GroupMessageHandler 中位于命令分发与 AI 对话之前
tc_pos = gmh_src.find("PlayerSelection.tryConsume(event, content)")
dc_pos = gmh_src.find("if (dispatchCommand(event)) return")
ai_pos = gmh_src.find("handleAiChat(event, content, groupId)")
ab_pos = gmh_src.find("if (tryAutoBindByDigits(event, content)) return")
check("MB2d tryConsume 最高优先（自动绑定 / 命令分发 / AI 对话之前）",
      tc_pos != -1 and dc_pos != -1 and ai_pos != -1 and ab_pos != -1 and
      tc_pos < ab_pos < dc_pos < ai_pos)
check("MB2e 双端 reload 清空选择会话",
      "PlayerSelection.clearAll()" in spigot_main and
      "PlayerSelection.clearAll()" in nf_main)

# MB3 指令改造
check("MB3a 查信息走选择流程（含@成员目标）",
      "PlayerSelection.requireBoundPlayer(" in pub_src and
      "info-target-not-bound" in pub_src)
check("MB3b 查询open ID 多玩家先选择", "PlayerSelection.selectOrRun(event, players)" in pub_src)
check("MB3c 重新绑定按选中解绑", "unbindByQuuid(selected.quuuid, selected.playerName)" in pub_src)
check("MB3d 签到走选择流程", "PlayerSelection.requireBoundPlayer(" in cic_src and
      "performCheckin(" in cic_src)
check("MB3e /绑定 LIMIT 分支", "QqBindManager.BindResult.LIMIT ->" in bind_src and
      '"bind-limit"' in bind_src)
check("MB3f 纯数字自动绑定 LIMIT 分支（码保留如实提示）",
      "QqBindManager.BindResult.LIMIT ->" in gmh_src)
check("MB3g 黑名单多玩家解绑", "unbindAllAndKickByQq(mentionedId)" in pub_src)
check("MB3h 查信息选择确认跳过冷却（同一次查询延续）",
      "selectedPlayer: cn.kerong.penguin.spigot.qqbind.QqBindManager.BoundPlayer? = null" in qi_src and
      "if (selectedPlayer == null)" in qi_src)

# MB4 配置与模板
check("MB4a ConfigManager config-version 35", "CURRENT_CONFIG_VERSION = 35" in cfgm_src)
check("MB4b DEFAULT_VALUES max-players-per-qq=1",
      '"qq-bind.max-players-per-qq" to 1,' in cfgm_src)
check("MB4c 模板 ID 三新增（bind-limit/select-player/select-invalid）",
      '"bind-limit", "select-player", "select-invalid",' in cfgm_src)
for tag, cfg in (("Spigot", cfg_spi), ("NF", cfg_nf)):
    check(f"MB4d {tag} 模板 config-version 35", "config-version: 35" in cfg)
    check(f"MB4e {tag} 模板 max-players-per-qq: 1", "max-players-per-qq: 1" in cfg)
    check(f"MB4f {tag} 模板三 messages 条目",
          'bind-limit: "bind-limit.md"' in cfg and
          'select-player: "select-player.md"' in cfg and
          'select-invalid: "select-invalid.md"' in cfg)
    check(f"MB4g {tag} Markdown 模板三件在位（jar 内）",
          jar_has(SPIGOT_JAR if tag == "Spigot" else NF_JAR, "Markdown/select-player.md") and
          jar_has(SPIGOT_JAR if tag == "Spigot" else NF_JAR, "Markdown/select-invalid.md") and
          jar_has(SPIGOT_JAR if tag == "Spigot" else NF_JAR, "Markdown/bind-limit.md"))
check("MB4h select-player 模板文案（数字选择 + 2 分钟）",
      "请回复序号" in source_text("common-Bot/src/main/resources/Markdown/select-player.md") and
      "2 分钟内有效" in source_text("common-Bot/src/main/resources/Markdown/select-player.md"))
check("MB4i NF Markdown 副本同步（select-player 一致）",
      source_text("common-Bot/src/main/resources/Markdown/select-player.md") ==
      source_text("server-NeoForge/src/main/resources/Markdown/select-player.md"))

# MB5 字节码
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    ps_cls = ps_src and javap_p(jar, PS)
    check(f"MB5a {tag} PlayerSelection 类在位 + tryConsume/requireBoundPlayer",
          "tryConsume" in ps_cls and "requireBoundPlayer" in ps_cls and "selectOrRun" in ps_cls)
    qbm_cls = javap_p(jar, QBM)
    check(f"MB5b {tag} QqBindManager BindResult 内部类",
          f"class {QBM}$BindResult" in javap_p(jar, f"{QBM}$BindResult"))
    check(f"MB5c {tag} maxPlayersPerQq / listBoundPlayersByQq / unbindAllAndKickByQq 方法",
          "getMaxPlayersPerQq" in qbm_cls and
          "listBoundPlayersByQq" in qbm_cls and
          "unbindAllAndKickByQq" in qbm_cls)
    check(f"MB5d {tag} 常量池 qq-bind.max-players-per-qq",
          class_has_bytes(jar, "cn/kerong/penguin/spigot/qqbind/QqBindManager.class",
                          b"qq-bind.max-players-per-qq"))
    cfg_in = jar_read_text(jar, "config.yml")
    check(f"MB5e {tag} JAR 内置 config：version 35 + max-players-per-qq + 三模板",
          "config-version: 35" in cfg_in and "max-players-per-qq: 1" in cfg_in and
          'select-player: "select-player.md"' in cfg_in)

# ============ GL 组：金币榜退役 ============
print("== GL 组：金币榜退役 ==")
clc_src = source_text("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/CoinsLeaderboardCommands.kt")
check("GL1a @Commands 单名财富榜（无金币榜别名）", '@Commands("财富榜")' in clc_src)
check("GL1b COMMAND_ALIASES 无金币榜映射",
      '"金币榜" to "财富榜"' not in cfgm_src and '"在线" to "查在线",' in cfgm_src)
for tag, cfg in (("Spigot", cfg_spi), ("NF", cfg_nf)):
    check(f"GL2a {tag} config 注释无「别名 /金币榜」", "别名 /金币榜" not in cfg)
check("GL2b help.md 无「可简写 /金币榜」", "可简写 /金币榜" not in help_md and
      "可简写 /金币榜" not in help_md_nf)
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    check(f"GL2c {tag} 字节码 CoinsLeaderboardCommands 无「金币榜」",
          not class_has_bytes(jar, "cn/kerong/bot/events/commands/CoinsLeaderboardCommands.class",
                              "金币榜".encode("utf-8")))
    check(f"GL2d {tag} JAR 内 help.md 无金币榜简写",
          "可简写 /金币榜" not in jar_read_text(jar, "Markdown/help.md"))
    check(f"GL2e {tag} JAR 内 config 无「别名 /金币榜」",
          "别名 /金币榜" not in jar_read_text(jar, "config.yml"))

# ============ AI 组：AI 面板修复 ============
print("== AI 组：AI 面板修复 ==")
check("AI1a AI_PANEL_NAMES 两按钮", 'AI_PANEL_NAMES = setOf("AI对话上下文", "清除当前上下文")' in mm_src)
check("AI1b aiEnabled 读取 + 按钮联动",
      "aiEnabled = manager.isAiEnabled" in mm_src and
      "if (!aiEnabled && item.name in AI_PANEL_NAMES) return@filter false" in mm_src)
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    check(f"AI2 {tag} 字节码 AI_PANEL_NAMES 集合字面量",
          class_has_bytes(jar, "cn/kerong/bot/MenuManager.class", "AI对话上下文".encode("utf-8")))

# ============ CK 组：签到绑定关闭修复 ============
print("== CK 组：签到绑定关闭修复 ==")
check("CK1a 签到命令 isEnabled 前置（源码）",
      "if (!manager.isEnabled || !manager.isCheckinEnabled)" in cic_src)
check("CK2 MenuManager 签到按钮双开关联动（源码）",
      "if ((!checkinEnabled || !qqBindEnabled) && item.name in CHECKIN_PANEL_NAMES) return@filter false" in mm_src)
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    cic_cls = javap_p(jar, CIC)
    check(f"CK1b {tag} 字节码 checkin 方法在位", "checkin" in cic_cls)

# ============ BG 组：查在线多页背景 ============
print("== BG 组：查在线多页背景（backgroundProvider 方案）==")
olr_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/OnlineListRenderer.kt")
ols_src = source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/OnlineListService.kt")
check("BG1a OnlineListData.backgroundProvider 字段（默认 null lambda）",
      "val backgroundProvider: ((height: Int) -> BufferedImage?)? = null," in olr_src)
check("BG1b renderSingle 每页取背景（invoke(height) ?: background）",
      "val pageBackground = data.backgroundProvider?.invoke(height) ?: data.background" in olr_src)
check("BG1c 旧直传路径移除（drawBackground(g, data.background, ...) 不在）",
      "InfoCardAssets.drawBackground(g, data.background, WIDTH, height)" not in olr_src)
check("BG2a 服务层传 provider lambda（逐页 resolveBackground）",
      "backgroundProvider = { pageHeight ->" in ols_src)
check("BG2b 无 pageBackgrounds 旧方案残留（1.7.4 采用远端 backgroundProvider）",
      "pageBackgrounds" not in olr_src and "pageBackgrounds" not in ols_src)
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    data_p = javap_p(jar, f"{OLR}$OnlineListData")
    check(f"BG1d {tag} 字节码 OnlineListData.getBackgroundProvider",
          "getBackgroundProvider" in data_p)
    single_p = javap_p(jar, OLR)
    check(f"BG1e {tag} 字节码 renderSingle 在位", "renderSingle" in single_p)

# ============ QR 组：二维码引导 ============
print("== QR 组：二维码引导 ==")
qr_src = source_text("common-Bot/src/main/kotlin/cn/kerong/bot/tools/QrCode.kt")
kerong_src = source_text("common-Bot/src/main/kotlin/cn/kerong/bot/KERONG.kt")
check("QR1a QrCode encode/encodeAscii 在位",
      "fun encode(text: String): Matrix" in qr_src and
      "fun encodeAscii(text: String, quietZone: Int = 4): String" in qr_src)
check("QR1b KERONG.printQqSetupQr + 未配置时打印",
      "fun printQqSetupQr()" in kerong_src and
      "printQqSetupQr()" in kerong_src)
check("QR1c 教程地址常量", "https://kerong.xin/penguin/tutorial.html" in kerong_src)
kc_src = source_text("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/commands/KERONGCommand.kt")
nfc_src = source_text("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/NeoForgeCommands.kt")
check("QR1d Spigot /hbkrg qr 子命令", '"qr" -> {' in kc_src and
      '"reload", "info", "chs", "mem", "bpng", "bpngsz", "qr", "help"' in kc_src)
check("QR1e NF /hbkrg qr 子命令", 'Commands.literal("qr")' in nfc_src)
for jar, tag in ((SPIGOT_JAR, "Spigot"), (NF_JAR, "NF")):
    check(f"QR1f {tag} 字节码 QrCode.encodeAscii 方法",
          "encodeAscii" in javap_p(jar, "cn.kerong.bot.tools.QrCode"))

# ============ B2/B3/B4 组：基线沿用 ============
print("== B2-B4 组：基线沿用 ==")
check("B2a bpng 64MB 熔断保持",
      "MAX_FILE_BYTES" in source_text(
          "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/BackgroundPackService.kt"))
check("B2b hbkrg 命令键在 / hbkng 不在",
      "hbkrg" in source_text("server-Spigot/src/main/resources/plugin.yml") and
      "hbkng" not in source_text("server-Spigot/src/main/resources/plugin.yml"))
check("B3 drawSubLabel 四参固定灰红线（源码）",
      "fun drawSubLabel(g: Graphics2D, text: String, x: Float, y: Float" in
      source_text("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt"))

# 汇总
print()
failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(("PASS  " if ok else "FAIL  ") + name)
print(f"\n共 {len(checks)} 项，通过 {len(checks) - len(failed)}，失败 {len(failed)}")
if failed:
    print("\n失败项：")
    for name in failed:
        print("  - " + name)
    sys.exit(1)
print("全部通过")
