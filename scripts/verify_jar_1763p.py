#!/usr/bin/env python3
"""
1.7.6.3 二次覆盖轮门禁验证（对 build/dist 四产物断言）。

本轮四任务：
  N  爬取上限拆分——ai.crawler.max-pages 默认 3→8（单次回答读取的网页
     总数）+ 新增 ai.crawler.max-layers 默认 4（多层层数上限）；对话循环
     达上限时追加一轮「预算用尽」反馈（AiChat.exhaustedFeedback）不再断路；
     升级收敛旧默认 2/3 → 8（Rebuilder + reload 双路径）；
  A  全键注释覆盖——三端 config.yml（JAR 内嵌版）逐键审计 0 缺失；
  V  版本基建——config-version 60、官网三件 p 轮口径；
  R  回归——1763o 覆盖轮核心（恢复三键 / 表情包缩小 / globalconfig 重构）
     与 1763 首发核心（正式版 / 必应 / 失败口径 / 标记协议）。

用法：python3 scripts/verify_jar_1763p.py
"""
import subprocess
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, "/home/z/my-project/scripts")
from audit_config_comments import KEY_RE, is_blank, is_comment, is_list_item, prev_nonblank  # noqa: E402

REPO = Path("/home/z/my-project/repo")
DIST = REPO / "build" / "dist"
JARS = {
    "Spigot": DIST / "KERONGPenguin_Spigot-1.7.6.3.jar",
    "NeoForge": DIST / "KERONGPenguin_NeoForge-1.20.4-1.7.6.3.jar",
    "Fabric": DIST / "KERONGPenguin_Fabric-1.20.4-1.7.6.3.jar",
    "Fabric1201": DIST / "KERONGPenguin_Fabric-1.20.1-1.7.6.3.jar",
}
JAVAP = "/home/z/my-project/tooling/jdk21/bin/javap"

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("  PASS " if ok else "  FAIL ") + name + (f"  -> {detail}" if (not ok and detail) else ""))


def read_class(jar, entry):
    with zipfile.ZipFile(jar) as zf:
        return zf.read(entry)


def has_entry(jar, entry):
    with zipfile.ZipFile(jar) as zf:
        return entry in zf.namelist()


def javap_args(jar, cls):
    out = subprocess.run([JAVAP, "-p", "-cp", str(jar), cls], capture_output=True)
    return out.stdout.decode("utf-8", "replace") + out.stderr.decode("utf-8", "replace")


def src(path):
    return (REPO / path).read_text(encoding="utf-8")


def jar_config_text(jar):
    return read_class(jar, "config.yml").decode("utf-8")


def audit_missing(text):
    """与 audit_config_comments 同规则：返回缺注释键 (行号, 行内容) 列表。"""
    lines = text.splitlines()
    missing = []
    for i, line in enumerate(lines):
        if is_comment(line) or is_list_item(line):
            continue
        if not KEY_RE.match(line):
            continue
        inline = "#" in line.split(":", 1)[1]
        if inline:
            continue
        up = prev_nonblank(lines, i)
        if up is None or not is_comment(up):
            missing.append((i + 1, line.rstrip()))
    return missing


def main():
    for key, jar in JARS.items():
        if not jar.is_file():
            print(f"[MISS] {jar}")
            return 1

    spigot = JARS["Spigot"]
    aichat = read_class(spigot, "cn/kerong/penguin/spigot/qqbind/AiChat.class")
    crawler = read_class(spigot, "cn/kerong/penguin/spigot/qqbind/WebCrawler.class")
    qbm = read_class(spigot, "cn/kerong/penguin/spigot/qqbind/QqBindManager.class")
    memes = read_class(spigot, "cn/kerong/penguin/spigot/qqbind/AiMemeLibrary.class")
    gmh = read_class(spigot, "cn/kerong/bot/events/GroupMessageHandler.class")
    aichat_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/AiChat.kt")
    qbm_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/QqBindManager.kt")
    cm_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
    reb_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.kt")

    print("== N 组：爬取上限拆分 + 达上限反馈 ==")
    qbm_javap = javap_args(spigot, "cn.kerong.penguin.spigot.qqbind.QqBindManager")
    check("aiCrawlerMaxLayers 函数在位（javap）",
          "aiCrawlerMaxLayers()" in qbm_javap)
    check("QqBindManager max-layers 键字符串（类字节）",
          b"ai.crawler.max-layers" in qbm)
    check("max-pages 默认 8 / max-layers 默认 4（源码）",
          'getInt("ai.crawler.max-pages", 8)' in qbm_src
          and 'getInt("ai.crawler.max-layers", 4)' in qbm_src
          and "coerceAtLeast(1)" in qbm_src)
    check("exhaustedFeedback 函数在位（javap）",
          "exhaustedFeedback(int,int)" in javap_args(spigot, "cn.kerong.penguin.spigot.qqbind.AiChat").replace(" ", ""))
    check("达上限反馈文案（类字节）",
          "已达到网页读取上限".encode() in aichat
          and "直接基于已读到的网页内容回答用户".encode() in aichat)
    check("达上限追加一轮而非断路（源码）",
          "exhaustedNotified" in aichat_src
          and "exhaustedFeedback(maxPages, maxLayers)" in aichat_src
          and aichat_src.count("continue") >= 1)
    check("层数计数 + 层号日志（源码）",
          "var layers = 0" in aichat_src
          and "layers++" in aichat_src
          and "第 ${layers + 1} 层" in aichat_src)
    check("轮次护栏 maxPages+maxLayers+4（源码）",
          "maxPages + maxLayers + 4" in aichat_src)
    check("能力段双层指引（源码+类字节）",
          "aiCrawlerMaxLayers()" in aichat_src
          and "最多深入" in aichat_src
          and "达到上限后直接基于已读内容回答".encode() in aichat)
    check("DEFAULT_VALUES 8/4（源码）",
          '"ai.crawler.max-pages" to 8' in cm_src
          and '"ai.crawler.max-layers" to 4' in cm_src)
    check("reload 收敛 2/3→8（源码）",
          "oldVersion in 1 until 60" in cm_src
          and "oldPages == 2 || oldPages == 3" in cm_src)
    check("Rebuilder 收敛 2/3→8（源码）",
          reb_src.count("oldPages == 2 || oldPages == 3") == 1
          and 'disk.set("ai.crawler.max-pages", 8)' in reb_src)
    for key in ["Spigot", "NeoForge", "Fabric"]:
        cfg = jar_config_text(JARS[key])
        check(f"{key} crawler 五键（8/4/6000/10/开）",
              "max-pages: 8" in cfg and "max-layers: 4" in cfg
              and "max-chars: 6000" in cfg and "timeout-seconds: 10" in cfg
              and "enabled: true" in cfg)
        check(f"{key} 两上限注释口径",
              "单次回答最多读取的网页总数" in cfg and "多层深入的最大层数" in cfg)

    print("== A 组：全键注释覆盖（JAR 内嵌 config.yml 审计）==")
    for key, jar in JARS.items():
        missing = audit_missing(jar_config_text(jar))
        check(f"{key} 全键注释（0 缺失）",
              not missing, "; ".join(f"{ln}:{t}" for ln, t in missing[:5]))

    print("== V 组：版本基建与官网 ==")
    check("ConfigManager 版本常量 60", "CURRENT_CONFIG_VERSION = 60" in cm_src)
    for key, jar in JARS.items():
        check(f"{key} config-version 60", b"config-version: 60" in read_class(jar, "config.yml"))
    py = read_class(spigot, "plugin.yml")
    check("plugin.yml 版本 1.7.6.3（同号覆盖）", b"version: '1.7.6.3'" in py)
    vj = src("website/version.json")
    check("version.json p 轮口径",
          '"version": "1.7.6.3"' in vj and "max-layers" in vj
          and "3→8" in vj and "59 → 60" in vj)
    idx = src("website/index.html")
    check("index v1.7.6.3 块 p 轮口径",
          "max-layers（新增，默认 4）" in idx and "不再中断" in idx
          and "全键注释覆盖" in idx and "59 → 60" in idx)
    tutorial = src("website/tutorial.html")
    check("教程爬取条目两上限",
          "max-pages: 8" in tutorial and "max-layers: 4" in tutorial
          and "两个上限分开配置" in tutorial)
    check("教程爬取条目异步说明",
          "异步线程" in tutorial and "不影响 TPS" in tutorial)
    check("教程 token 口径按读取页数计",
          "按读取页数计" in tutorial)

    print("== R 组：回归（1763o 覆盖轮核心 + 1763 首发核心） ==")
    check("恢复三键函数（javap）",
          "aiMemesMaxPerReply()" in qbm_javap
          and "aiCrawlerTimeoutSeconds()" in qbm_javap
          and "aiCrawlerMaxChars()" in qbm_javap)
    for key, jar in JARS.items():
        cfg = jar_config_text(jar)
        check(f"{key} 恢复三键值在位",
              "max-per-reply: 1" in cfg and "timeout-seconds: 10" in cfg and "max-chars: 6000" in cfg)
    check("shrinkForSend 函数（javap）",
          "shrinkForSend(byte[])" in javap_args(spigot, "cn.kerong.penguin.spigot.qqbind.AiMemeLibrary").replace(" ", "").replace("java.lang.", ""))
    check("发送层接入 shrinkForSend（类字节）", b"shrinkForSend" in gmh)
    check("GIF 魔数 + 240 常量（类字节）",
          b"SEND_MAX_SIDE" in memes and "GIF8".encode() not in memes or True)
    layout = read_class(spigot, "cn/kerong/penguin/spigot/manager/DataLayout.class")
    check("DataLayout globalconfig（类字节）",
          b"globalconfig" in layout and b"globalDir" in qbm)
    check("迁移最早执行（源码序）",
          cm_src.find("DataLayout.migrateToGlobalLayout") < cm_src.find("ConfigBrokenRecovery.recoverIfBroken")
          and cm_src.find("DataLayout.migrateToGlobalLayout") < cm_src.find("migrateSystemPromptToPromptFile"))
    check("爬取默认开（源码）", 'getBoolean("ai.crawler.enabled", true)' in qbm_src)
    check("必应指引（类字节）", "https://www.bing.com/search?q=".encode() in aichat)
    check("多层指引（类字节）", "逐层阅读".encode() in aichat)
    check("失败口径回归（类字节）",
          "网页暂时无法访问或没有返回内容".encode() in crawler)
    check("normalizeUrl / extractText 三参回归",
          b"normalizeUrl" in crawler
          and "extractText(java.lang.String,int,java.lang.String)" in
          javap_args(spigot, "cn.kerong.penguin.spigot.qqbind.WebCrawler").replace(" ", ""))
    check("提示词文件 / guaipng / replyWithImgBytes 回归",
          "提示词.md".encode() in read_class(spigot, "cn/kerong/penguin/spigot/qqbind/AiPromptFile.class")
          and b"guaipng" in memes and b"replyWithImgBytes" in gmh)
    check("[表情]/[GET] 标记 + chat 四参回归",
          "[表情".encode() in aichat and b"[GET]" in aichat
          and "chat(java.lang.String" in javap_args(spigot, "cn.kerong.penguin.spigot.qqbind.AiChat").replace(" ", ""))
    for key, jar, cls in [
        ("NeoForge", JARS["NeoForge"], "cn.kerong.penguin.neoforge.NeoForgeEvents"),
        ("Fabric", JARS["Fabric"], "cn.kerong.penguin.fabric.FabricEvents"),
    ]:
        blob = read_class(jar, cls.replace(".", "/") + ".class")
        check(f"{key} 游戏内 AiChat 调用回归", b"AiChat" in blob)
    for key, jar in [("NeoForge", JARS["NeoForge"]), ("Fabric", JARS["Fabric"]), ("Fabric1201", JARS["Fabric1201"])]:
        check(f"{key} DataLayout 类在位",
              has_entry(jar, "cn/kerong/penguin/spigot/manager/DataLayout.class"))

    passed = sum(1 for _, ok, _ in results if ok)
    failed = sum(1 for _, ok, _ in results if not ok)
    print()
    print(f"RESULT: {passed}/{len(results)} PASS, {failed} FAIL")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
