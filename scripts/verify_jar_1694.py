#!/usr/bin/env python3
"""verify_jar_1694 —— 1.6.9.4 产物门禁（背景样式 / 合并转发防刷屏 / reload 边界 / 异步化）。

背景（用户需求）：
  ① 图片渲染模板样式配置：默认原版灰色遮罩，新增模糊遮罩样式
     （参考 https://github.com/BaizhouziYou/easybot-image-templates/ 液态玻璃模板）；
  ② 修复 QQ 群转发消息（合并聊天记录）转发到游戏里变成大段文字刷屏；
  ③ 确认 reload 指令可用性，注释与教程明确写出哪些配置不能重载只能重启；
  ④ 检查插件能用异步就用异步（AI 对话出 WSS 线程 / reload 出主线程）。

断言组：
  BS 组  背景遮罩样式（render.background-style）
       BS1  RenderSettings$BackgroundStyle 枚举在位（双 JAR）
       BS2  配置键常量：render.background-style / gray-mask / blur-mask / glass
       BS3  InfoCardAssets 新方法五件套（applyBackgroundStyle / blurImage /
            boxBlur / boxBlurAxis / backgroundStyleTag，javap -p）
       BS4  模糊常量 BLUR_DOWNSCALE=8 / BLUR_BOX_RADIUS=3（javap 常量池）
       BS5  processedBackground 缓存键拼 backgroundStyleTag（javap -c 调用）
       BS6  processedBuiltinBackground 缓存键拼 backgroundStyleTag（javap -c）
       BS7  双端 config.yml（源 + JAR 内置）：background-style: "gray-mask"
       BS8  ConfigManager：版本 25 + DEFAULT_VALUES 新键
       BS9  源码：blur 分支（BLUR_MASK → blurImage → darkOverlay）
  MF 组  合并转发消息防刷屏
       MF1  QqText$MergedForward 数据类在位（双 JAR）
       MF2  detectMergedForward 方法 + message_type 102 判定（源码）
       MF3  模板标记常量（=== 消息 / [发送者] / [消息类型] 合并转发消息）
       MF4  GroupMessageHandler 调用 detectMergedForward（javap -c）
       MF5  [聊天记录 占位文案常量（UTF8 两形态）
       MF6  条数解析（=== 消息 N === / --- 第N条 --- 双正则，javap -p 字段）
  RL 组  reload 可用性边界说明
       RL1  双端 config（源 + JAR）头部「只有以下配置不能用 reload」说明
       RL2  update-check 段注释「需重启服务器才生效」
       RL3  commands 段注释「/kerong panel 手动重新同步」
       RL4  bot 凭据注释「必须重启服务器」
       RL5  KERONGCommand reload：异步 + 反馈含重启边界（源码）
       RL6  NeoForgeCommands reload：异步 + 反馈含重启边界（源码）
       RL7  双端 help 行「QQ 凭据与更新检查除外，需重启」
  AS 组  异步化
       AS1  handleAiChat 内 AiChat.chat 经 submitAsync（源码 + javap -c）
       AS2  KERONGCommand reload submitAsync（javap -c）
       AS3  NeoForgeCommands reload submitAsync（源码）
       AS4  源码反断言：reloadPluginConfig 不再直接同步执行（在 lambda 内）
  V 组  版本六处 1.6.9.4 + JAR 内 plugin.yml / 双 toml
  B 组  基线回归（目录条目 / shaded / 1.6.9.3 未获取成功 / max-players-source /
       darkOverlay 黑度 105 / 快捷面板 void 兼容桥）

用法：python3 scripts/verify_jar_1694.py [spigot_jar] [nf_jar]
"""
import pathlib
import re
import subprocess
import sys
import tempfile
import tomllib
import zipfile

SPIGOT_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_Spigot-1.6.9.4.jar"
NF_JAR = sys.argv[2] if len(sys.argv) > 2 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.9.4.jar"
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


def utf8_constants(jar, entry):
    out = _javap_run(jar, entry, ("-v",))
    strings = set()
    for line in out.splitlines():
        m = re.match(r"\s*#\d+ = Utf8\s+(.*)$", line)
        if m:
            strings.add(m.group(1))
        elif "// String " in line:
            strings.add(line.split("// String ", 1)[1])
    return strings


def class_contains_literals(jar, entry, literals):
    """按字节判断常量池是否含指定字面量（javap 对非 ASCII 输出 Unicode 转义，
    直接搜类文件字节更可靠——常量池存储 modified UTF-8，BMP 区与标准 UTF-8 兼容）。"""
    data = read_class(jar, entry)
    return all(literal.encode("utf-8") in data for literal in literals)


RENDER_SETTINGS = "cn/kerong/penguin/spigot/render/RenderSettings.class"
RENDER_SETTINGS_STYLE = "cn/kerong/penguin/spigot/render/RenderSettings$BackgroundStyle.class"
INFO_CLASS = "cn/kerong/penguin/spigot/render/InfoCardAssets.class"
QQTEXT = "cn/kerong/bot/tools/QqText.class"
QQTEXT_MF = "cn/kerong/bot/tools/QqText$MergedForward.class"
GMH = "cn/kerong/bot/events/GroupMessageHandler.class"
KCMD = "cn/kerong/penguin/spigot/commands/KERONGCommand.class"
NFCMD = "cn/kerong/penguin/neoforge/NeoForgeCommands.class"


def main():
    spigot = pathlib.Path(SPIGOT_JAR)
    nf = pathlib.Path(NF_JAR)
    for p in (spigot, nf):
        if not p.exists():
            raise SystemExit(f"JAR 不存在：{p}")

    src_render = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/RenderSettings.kt").read_text(encoding="utf-8")
    src_info = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt").read_text(encoding="utf-8")
    src_qqtext = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/tools/QqText.kt").read_text(encoding="utf-8")
    src_gmh = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/events/GroupMessageHandler.kt").read_text(encoding="utf-8")
    src_kcmd = (ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/commands/KERONGCommand.kt").read_text(encoding="utf-8")
    src_nfcmd = (ROOT / "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/NeoForgeCommands.kt").read_text(encoding="utf-8")
    src_cfgmgr = (ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt").read_text(encoding="utf-8")
    cfg_spigot = (ROOT / "server-Spigot/src/main/resources/config.yml").read_text(encoding="utf-8")
    cfg_nf = (ROOT / "server-NeoForge/src/main/resources/config.yml").read_text(encoding="utf-8")

    print("== BS 组：背景遮罩样式 ==")
    check("BS1 RenderSettings$BackgroundStyle 枚举（双 JAR）",
          has_entry(spigot, RENDER_SETTINGS_STYLE) and has_entry(nf, RENDER_SETTINGS_STYLE))

    rs_consts_spigot = utf8_constants(spigot, RENDER_SETTINGS)
    rs_consts_nf = utf8_constants(nf, RENDER_SETTINGS)
    for label, consts in (("Spigot", rs_consts_spigot), ("NeoForge", rs_consts_nf)):
        check(f"BS2 RenderStyles 配置键常量（{label}）",
              all(s in consts for s in ("render.background-style", "gray-mask", "blur-mask", "glass")) and
              "backgroundStyle" in "".join(consts))

    assets_spigot = javap_p(spigot, INFO_CLASS)
    assets_nf = javap_p(nf, INFO_CLASS)
    for label, sig in (("Spigot", assets_spigot), ("NeoForge", assets_nf)):
        check(f"BS3 InfoCardAssets 样式方法五件套（{label}）",
              all(m in sig for m in ("applyBackgroundStyle", "blurImage", "boxBlur(", "boxBlurAxis", "backgroundStyleTag")))

    assets_v = _javap_run(spigot, INFO_CLASS, ("-v",))
    check("BS4 模糊常量 BLUR_DOWNSCALE=8 / BLUR_BOX_RADIUS=3（常量池 ConstantValue）",
          re.search(r"BLUR_DOWNSCALE.*?ConstantValue.*?int\s+8", assets_v, re.S) is not None and
          re.search(r"BLUR_BOX_RADIUS.*?ConstantValue.*?int\s+3", assets_v, re.S) is not None)

    pb_body = javap_c(spigot, INFO_CLASS)
    check("BS5 processedBackground 缓存键拼 backgroundStyleTag",
          pb_body.count("backgroundStyleTag") >= 2)

    check("BS6 processedBuiltinBackground 缓存键拼 backgroundStyleTag",
          pb_body.count("backgroundStyleTag") >= 3,
          f"调用 {pb_body.count('backgroundStyleTag')} 处（random+随机键+builtin 键 ≥3）")

    for label, cfg_text, jar in (("Spigot源", cfg_spigot, spigot), ("NeoForge源", cfg_nf, nf),
                                 ("SpigotJAR", None, spigot), ("NeoForgeJAR", None, nf)):
        text = cfg_text if cfg_text is not None else read_resource(jar, "config.yml")
        check(f"BS7 config background-style: \"gray-mask\"（{label}）",
              'background-style: "gray-mask"' in text and "blur-mask" in text and "gray-mask" in text)

    check("BS8 ConfigManager 版本 25 + DEFAULT_VALUES 新键",
          "CURRENT_CONFIG_VERSION = 25" in src_cfgmgr and
          '"render.background-style" to "gray-mask"' in src_cfgmgr)

    check("BS9 源码 blur 分支：BLUR_MASK → blurImage → darkOverlay",
          "RenderSettings.BackgroundStyle.BLUR_MASK" in src_info and
          "blurImage(cover)" in src_info and "darkOverlay(blurred)" in src_info and
          "builtin = true" in src_info)

    print("== MF 组：合并转发防刷屏 ==")
    check("MF1 QqText$MergedForward 数据类（双 JAR）",
          has_entry(spigot, QQTEXT_MF) and has_entry(nf, QQTEXT_MF))

    check("MF2 detectMergedForward：message_type 102 判定 + 双正则（源码）",
          "fun detectMergedForward" in src_qqtext and
          'getIntValue("message_type") == 102' in src_qqtext and
          "FORWARD_MSG_HEADER" in src_qqtext and "FORWARD_NESTED_ITEM" in src_qqtext)

    qqtext_bytes_spigot = read_class(spigot, QQTEXT)
    qqtext_bytes_nf = read_class(nf, QQTEXT)
    mf_literals = ("=== 消息 ", "[发送者]", "[消息类型] 合并转发消息")
    for label, data in (("Spigot", qqtext_bytes_spigot), ("NeoForge", qqtext_bytes_nf)):
        check(f"MF3 模板标记常量（{label}，类文件字节）",
              all(literal.encode("utf-8") in data for literal in mf_literals))

    gmh_body = javap_c(spigot, GMH)
    check("MF4 GroupMessageHandler 调用 detectMergedForward",
          "detectMergedForward" in gmh_body)

    gmh_consts = utf8_constants(spigot, GMH)
    gmh_nf_consts = utf8_constants(nf, GMH)
    gmh_bytes_spigot = read_class(spigot, GMH)
    gmh_bytes_nf = read_class(nf, GMH)
    for label, data in (("Spigot", gmh_bytes_spigot), ("NeoForge", gmh_bytes_nf)):
        check(f"MF5 [聊天记录 占位文案（{label}，类文件字节）",
              "[聊天记录 共".encode("utf-8") in data and "[聊天记录]".encode("utf-8") in data)

    check("MF6 条数解析双正则字段（javap -p）",
          "FORWARD_MSG_HEADER" in javap_p(spigot, QQTEXT) and "FORWARD_NESTED_ITEM" in javap_p(spigot, QQTEXT))

    print("== RL 组：reload 可用性边界 ==")
    head_note = "只有以下配置不能用 reload"
    check("RL1 config 头部 reload 边界总说明（双端源 + 双 JAR）",
          head_note in cfg_spigot and head_note in cfg_nf and
          head_note in read_resource(spigot, "config.yml") and head_note in read_resource(nf, "config.yml"))

    check("RL2 update-check 段「需重启服务器才生效」（双端源）",
          "需重启服务器才生效" in cfg_spigot and "需重启服务器才生效" in cfg_nf)

    check("RL3 commands 段「/kerong panel 手动重新同步」（双端源）",
          "/kerong panel 手动重新同步" in cfg_spigot and "/kerong panel 手动重新同步" in cfg_nf)

    check("RL4 bot 凭据注释「必须重启服务器」（双端源 + 双 JAR）",
          "必须重启服务器" in cfg_spigot and "必须重启服务器" in cfg_nf and
          "必须重启服务器" in read_resource(spigot, "config.yml") and
          "必须重启服务器" in read_resource(nf, "config.yml"))

    check("RL5 KERONGCommand reload：异步 + 重启边界反馈",
          "submitAsync" in src_kcmd and "只能重启服务器" in src_kcmd and
          "bot.app-id / bot.secret" in src_kcmd and "update-check" in src_kcmd and
          "/kerong panel 手动同步" in src_kcmd)

    check("RL6 NeoForgeCommands reload：异步 + 重启边界反馈",
          "submitAsync" in src_nfcmd and "只能重启服务器" in src_nfcmd and
          "bot.app-id / bot.secret" in src_nfcmd and "update-check" in src_nfcmd and
          "/kerong panel 手动同步" in src_nfcmd)

    check("RL7 help 行「QQ 凭据与更新检查除外，需重启」（双端）",
          "QQ 凭据与更新检查除外，需重启" in src_kcmd and "QQ 凭据与更新检查除外，需重启" in src_nfcmd)

    print("== AS 组：异步化 ==")
    check("AS1 handleAiChat：AiChat.chat 经 submitAsync（源码）",
          "plugin.submitAsync {" in src_gmh and "AiChat.chat(preparedBody" in src_gmh and
          "AiChat.chat(body" not in src_gmh)

    check("AS1b handleAiChat submitAsync 字节级（Spigot JAR）",
          "submitAsync" in gmh_body)

    kcmd_body = javap_c(spigot, KCMD)
    check("AS2 KERONGCommand reload submitAsync（javap -c）",
          "submitAsync" in kcmd_body and "reloadPluginConfig" in kcmd_body)

    check("AS3 NeoForgeCommands reload submitAsync（源码 + javap）",
          "submitAsync" in src_nfcmd and "submitAsync" in javap_c(nf, NFCMD))

    # reload 分支里 reloadPluginConfig 必须出现在 submitAsync 之后（lambda 内异步执行）。
    # javap -c 的字符串字面量以「// String reload」注释形式出现。
    kcmd_body = javap_c(spigot, KCMD)
    reload_section = kcmd_body[kcmd_body.find("// String reload") if "// String reload" in kcmd_body else 0:]
    pos_async = reload_section.find("submitAsync")
    pos_reload = reload_section.find("reloadPluginConfig")
    check("AS4 reload 执行体在异步 lambda 内（submitAsync 先于 reloadPluginConfig）",
          0 <= pos_async < pos_reload, f"async@{pos_async} reload@{pos_reload}")

    print("== V 组：版本号 ==")
    gradle_root = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    plugin_yml_src = (ROOT / "server-Spigot/src/main/resources/plugin.yml").read_text(encoding="utf-8")
    nf_gradle = (ROOT / "server-NeoForge/build.gradle.kts").read_text(encoding="utf-8")
    toml_a = (ROOT / "server-NeoForge/src/main/resources/META-INF/mods.toml").read_text(encoding="utf-8")
    toml_b = (ROOT / "server-NeoForge/src/main/resources/META-INF/neoforge.mods.toml").read_text(encoding="utf-8")
    nf_main = (ROOT / "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt").read_text(encoding="utf-8")
    check("V1 版本六处 1.6.9.4（源）",
          'version = "1.6.9.4"' in gradle_root and "version: '1.6.9.4'" in plugin_yml_src and
          'version = "1.6.9.4"' in nf_gradle and 'version = "1.6.9.4"' in toml_a and
          'version = "1.6.9.4"' in toml_b and 'MOD_VERSION = "1.6.9.4"' in nf_main)
    jar_plugin_yml = read_resource(spigot, "plugin.yml")
    jar_toml_a = tomllib.loads(read_resource(nf, "META-INF/mods.toml"))
    jar_toml_b = tomllib.loads(read_resource(nf, "META-INF/neoforge.mods.toml"))
    check("V2 JAR 内版本（plugin.yml + 双 toml）",
          "version: '1.6.9.4'" in jar_plugin_yml and
          jar_toml_a["mods"][0]["version"] == "1.6.9.4" and
          jar_toml_b["mods"][0]["version"] == "1.6.9.4")
    check("V3 双 toml 段头字节级 [[mods]]（非注释行）",
          "[[mods]]" in read_resource(nf, "META-INF/mods.toml") and
          "[[mods]]" in read_resource(nf, "META-INF/neoforge.mods.toml"))
    check("V4 无 1.6.9.3 残留（源六处）",
          "1.6.9.3" not in gradle_root and "1.6.9.3" not in plugin_yml_src and
          "1.6.9.3" not in nf_gradle and "1.6.9.3" not in toml_a and "1.6.9.3" not in toml_b and
          '"1.6.9.3"' not in nf_main)

    print("== B 组：基线回归 ==")
    with zipfile.ZipFile(spigot) as z:
        entries = z.namelist()
    dir_entries = sum(1 for e in entries if e.endswith("/"))
    check("B1 Spigot 目录条目基线（≥776）", dir_entries >= 776, f"实际 {dir_entries}")
    check("B2 NF shaded 在位 + kotlin 不裸露",
          has_entry(nf, "cn/kerong/shaded/kotlin/jvm/internal/Intrinsics.class") and
          not has_entry(nf, "kotlin/jvm/internal/Intrinsics.class"))

    src_query = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/QueryInfoService.kt").read_text(encoding="utf-8")
    check("B3 1.6.9.3 回归：未获取成功常量保持",
          "未获取成功" in src_query and "FETCH_FAILED_TEXT" in
          (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/PlaceholderVariableService.kt").read_text(encoding="utf-8"))
    check("B4 1.6.9.3 回归：max-players-source 键保持（双端源 + JAR）",
          'max-players-source: "real"' in cfg_spigot and 'max-players-source: "real"' in cfg_nf and
          'max-players-source: "real"' in read_resource(spigot, "config.yml"))
    check("B5 darkOverlay 黑度常量 105 保持（源码）",
          "Color(0x00, 0x00, 0x00, 105)" in src_info)
    check("B6 MotdDisplayTracker / PlaceholderVariableService$Context 保持（双 JAR）",
          has_entry(spigot, "cn/kerong/penguin/spigot/stats/MotdDisplayTracker.class") and
          has_entry(nf, "cn/kerong/penguin/spigot/stats/MotdDisplayTracker.class") and
          has_entry(spigot, "cn/kerong/penguin/spigot/stats/PlaceholderVariableService$Context.class"))
    check("B7 快捷面板 void 兼容桥保持（源码）",
          "fun syncGroupPanels(" in (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/MenuManager.kt").read_text(encoding="utf-8") and
          "syncGroupPanelsWithResult" in src_kcmd)
    check("B8 命令删除回归：MotdCommands 不在（双 JAR）",
          not has_entry(spigot, "cn/kerong/bot/events/commands/MotdCommands.class") and
          not has_entry(nf, "cn/kerong/bot/events/commands/MotdCommands.class"))

    failed = [c for c in checks if not c[1]]
    print(f"\n共 {len(checks)} 项，通过 {len(checks) - len(failed)}，失败 {len(failed)}")
    if failed:
        for name, _, detail in failed:
            print(f"  FAIL: {name} {detail}")
        sys.exit(1)
    print("verify_jar_1694 全部通过")


if __name__ == "__main__":
    main()
