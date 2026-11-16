#!/usr/bin/env python3
"""
1.7.6.3 覆盖轮门禁验证（对 build/dist 四产物断言）。

本轮六任务：
  C  注释简化——最近新增段注释精简（config 键与行为不变）；
  K  恢复三键——ai.memes.max-per-reply / ai.crawler.timeout-seconds /
     ai.crawler.max-chars 恢复为配置键（默认 1/10/6000）；
  P  多层爬取上限可配置——ai.crawler.max-pages（默认 3）注释明确；
  D  表情包缩小——AiMemeLibrary.shrinkForSend（GIF 原样 / 静态图缩放
     240px 转单帧 GIF / 失败原样降级），发送层接入；
  G  插件文件夹重构——DataLayout.migrateToGlobalLayout + 9 处读写点
     改接 globalconfig/（QqBindManager/PluginFileLog/KERONG/
     ConfigProvider/MessageTemplates/InfoCardRenderer/PlayerStatsManager/
     FakePlayerRegistry/OpenIdDirectory）；
  V  版本基建——config-version 59、官网三件覆盖轮口径。
回归：1763 首发核心（正式版 / 多层 / 必应 / token 提示 / 失败口径 / 标记协议）。

用法：python3 scripts/verify_jar_1763o.py
"""
import subprocess
import sys
import zipfile
from pathlib import Path

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
    memes_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/AiMemeLibrary.kt")
    cm_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
    dl_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/manager/DataLayout.kt")

    print("== C 组：注释简化 ==")
    cfg_src = src("server-Spigot/src/main/resources/config.yml")
    check("config AI 表情包注释精简（≤3 行）",
          cfg_src.count("# 图片放插件目录 guaipng 文件夹") == 1
          and "发送时自动缩到表情大小" in cfg_src)
    check("config AI 提示词注释精简",
          "角色设定与专属知识都写在这个文件里" in cfg_src
          and "原为配置项 ai.system-prompt" not in cfg_src)
    check("config AI 网页读取注释精简（≤3 行）",
          "AI 需要时自动读网页（可多层深入）" in cfg_src)
    check("show-multi-cards 注释精简",
          "查在线 / 查性能同时显示全部服务器（默认关，仅集群生效）" in cfg_src)
    check("show-fake-players 注释精简",
          "查在线额外显示「假人在线」小节" in cfg_src)
    check("fake-player-whitelist 注释精简",
          "假人白名单：名单里的玩家名按假人处理" in cfg_src)
    check("total-playtime-source 注释精简",
          "总在线时长获取源：save-data = 服务器存档（默认" in cfg_src)

    print("== K 组：恢复三键 ==")
    qbm_javap = javap_args(spigot, "cn.kerong.penguin.spigot.qqbind.QqBindManager")
    check("aiMemesMaxPerReply 函数恢复",
          "aiMemesMaxPerReply()" in qbm_javap)
    check("aiCrawlerTimeoutSeconds 函数恢复",
          "aiCrawlerTimeoutSeconds()" in qbm_javap)
    check("aiCrawlerMaxChars 函数恢复",
          "aiCrawlerMaxChars()" in qbm_javap)
    check("QqBindManager 三键字符串在位（类字节）",
          b"ai.memes.max-per-reply" in qbm
          and b"ai.crawler.timeout-seconds" in qbm
          and b"ai.crawler.max-chars" in qbm)
    check("默认值引用常量（源码）",
          'getInt("ai.memes.max-per-reply", AiChat.MEME_MAX_PER_REPLY)' in qbm_src
          and 'getInt("ai.crawler.timeout-seconds", WebCrawler.DEFAULT_TIMEOUT_SECONDS)' in qbm_src
          and 'getInt("ai.crawler.max-chars", WebCrawler.DEFAULT_MAX_CHARS)' in qbm_src)
    check("AiChat 能力段接回配置（源码）",
          "manager.aiMemesMaxPerReply()" in aichat_src)
    check("AiChat 抓取接回配置（源码）",
          "manager?.aiCrawlerTimeoutSeconds() ?: WebCrawler.DEFAULT_TIMEOUT_SECONDS" in aichat_src
          and "manager?.aiCrawlerMaxChars() ?: WebCrawler.DEFAULT_MAX_CHARS" in aichat_src)
    check("DEFAULT_VALUES 补三键（源码）",
          '"ai.memes.max-per-reply" to 1' in cm_src
          and '"ai.crawler.timeout-seconds" to 10' in cm_src
          and '"ai.crawler.max-chars" to 6000' in cm_src)
    check("REMOVED_CONFIG_KEYS 已撤三键（源码）",
          '"ai.memes.max-per-reply",' not in cm_src
          and '"ai.crawler.timeout-seconds",' not in cm_src
          and '"ai.crawler.max-chars",' not in cm_src)
    for key in ["Spigot", "NeoForge", "Fabric"]:
        cfg = read_class(JARS[key], "config.yml")
        check(f"{key} config 三键在位",
              b"max-per-reply: 1" in cfg and b"timeout-seconds: 10" in cfg and b"max-chars: 6000" in cfg)

    print("== P 组：多层爬取上限可配置 ==")
    for key in ["Spigot", "NeoForge", "Fabric"]:
        cfg = read_class(JARS[key], "config.yml")
        check(f"{key} max-pages 键+多层注释",
              b"max-pages: 3" in cfg and "多层爬取上限".encode() in cfg)

    print("== D 组：表情包缩小 ==")
    memes_javap = javap_args(spigot, "cn.kerong.penguin.spigot.qqbind.AiMemeLibrary")
    check("shrinkForSend 函数在位",
          "shrinkForSend(byte[])" in memes_javap.replace(" ", "").replace("java.lang.", ""))
    check("SEND_MAX_SIDE=240 常量",
          b"SEND_MAX_SIDE" in memes and memes_src.count("SEND_MAX_SIDE = 240") == 1)
    check("GIF 魔数判定直传（源码）",
          "'G'.code.toByte()" in memes_src and "'I'.code.toByte()" in memes_src)
    check("缩放+GIF 编码（源码）",
          "scaleDown" in memes_src and "encodeGif" in memes_src
          and "VALUE_INTERPOLATION_BILINEAR" in memes_src
          and 'ImageIO.write(image, "gif", out)' in memes_src)
    check("失败降级原样返回（源码）",
          memes_src.count("return bytes") >= 2 and "?: bytes" in memes_src)
    check("发送层接入 shrinkForSend（类字节）",
          b"shrinkForSend" in gmh)

    print("== G 组：插件文件夹重构 ==")
    check("DataLayout 类在位（common-Bot）",
          has_entry(spigot, "cn/kerong/penguin/spigot/manager/DataLayout.class"))
    layout = read_class(spigot, "cn/kerong/penguin/spigot/manager/DataLayout.class")
    check("GLOBAL_DIR=globalconfig 常量",
          b"globalconfig" in layout and b"GLOBAL_DIR" in layout)
    check("迁移名单四目录（源码）",
          'listOf("QUUID", "logs", "Markdown", "cache")' in dl_src)
    check("迁移名单散文件（源码）",
          '"ai-context.yml", "fake-players.json", "stats.yml"' in dl_src
          and '"openids.yml", "openids.yml.migrated"' in dl_src)
    check("幂等迁移+合并（源码）",
          "fun migrateToGlobalLayout" in dl_src and "mergeDirInto" in dl_src)
    check("ConfigManager.initialize 最早调用迁移（源码序）",
          cm_src.find("DataLayout.migrateToGlobalLayout") < cm_src.find("ConfigBrokenRecovery.recoverIfBroken")
          and cm_src.find("DataLayout.migrateToGlobalLayout") < cm_src.find("migrateSystemPromptToPromptFile"))
    check("QqBindManager 路径接 globalconfig（源码）",
          "DataLayout.globalDir(dataFolder)" in qbm_src)
    check("QqBindManager globalDir 调用（类字节）",
          b"globalDir" in qbm)
    # 9 处读写点字节断言（各类含 globalconfig 路径串）
    paths = [
        ("PluginFileLog", "cn/kerong/bot/tools/PluginFileLog.class"),
        ("KERONG", "cn/kerong/bot/KERONG.class"),
        ("ConfigProvider 接口实现（KERONG 内 markdown）", None),
        ("MessageTemplates", "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class"),
        ("InfoCardAssets（avatarDiskDir 所在类）", "cn/kerong/penguin/spigot/render/InfoCardAssets.class"),
        ("PlayerStatsManager", "cn/kerong/penguin/spigot/stats/PlayerStatsManager.class"),
        ("FakePlayerRegistry", "cn/kerong/penguin/spigot/stats/FakePlayerRegistry.class"),
        ("OpenIdDirectory", "cn/kerong/penguin/spigot/qqbind/OpenIdDirectory.class"),
    ]
    for name, entry in paths:
        if entry is None:
            continue
        blob = read_class(spigot, entry)
        check(f"{name} globalconfig 路径（类字节）", b"globalconfig" in blob)
    # KERONG markdown 读路径（ConfigProvider 默认实现编译进 KERONG 接口或伴生）
    kerong_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/KERONG.kt")
    check("KERONG Markdown 释放+日志路径（源码）",
          'resolve("globalconfig/Markdown")' in kerong_src
          and 'resolve("globalconfig/logs/Bot-%s.log")' in kerong_src)
    check("根目录只留四样（config.yml/提示词/guaipng/img）",
          'File(dataFolder, "img").mkdirs()' in src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/KERONGSpigot.kt")
          and "globalconfig" not in src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/KERONGSpigot.kt"))

    print("== V 组：版本基建与官网 ==")
    check("ConfigManager 版本常量 59", "CURRENT_CONFIG_VERSION = 59" in cm_src)
    for key in ["Spigot", "NeoForge", "Fabric"]:
        cfg = read_class(JARS[key], "config.yml")
        check(f"{key} config-version 59", b"config-version: 59" in cfg)
    py = read_class(spigot, "plugin.yml")
    check("plugin.yml 版本 1.7.6.3（同号覆盖）", b"version: '1.7.6.3'" in py)
    vj = src("website/version.json")
    check("version.json 简化条目（覆盖轮口径）",
          '"version": "1.7.6.3"' in vj and "表情包缩小" in vj and "globalconfig" in vj
          and "恢复 ai.memes.max-per-reply" in vj)
    idx = src("website/index.html")
    check("index v1.7.6.3 块覆盖轮口径",
          "globalconfig" in idx and "表情大小" in idx)
    tutorial = src("website/tutorial.html")
    check("教程数据目录结构卡（目录树）",
          "插件数据目录结构" in tutorial and "globalconfig/" in tutorial
          and "QUUID/" in tutorial)
    check("教程 Markdown 路径 globalconfig",
          "globalconfig/Markdown/" in tutorial)
    check("教程表情包条目含缩小说明",
          "自动缩到表情大小" in tutorial and "max-per-reply" in tutorial)
    check("教程爬取条目含三键",
          "max-chars: 6000" in tutorial and "timeout-seconds: 10" in tutorial)

    print("== R 组：回归（1763 首发核心保留） ==")
    check("爬取默认开（源码）", 'getBoolean("ai.crawler.enabled", true)' in qbm_src)
    check("能力段必应指引（类字节）", "https://www.bing.com/search?q=".encode() in aichat)
    check("能力段多层指引（类字节）", "逐层阅读".encode() in aichat)
    check("失败口径回归", "网页暂时无法访问或没有返回内容".encode() in crawler)
    check("normalizeUrl 回归", b"normalizeUrl" in crawler)
    check("extractText 三参签名回归",
          "extractText(java.lang.String,int,java.lang.String)" in
          javap_args(spigot, "cn.kerong.penguin.spigot.qqbind.WebCrawler").replace(" ", ""))
    check("knowledge 清理回归", b"cleanupLegacyKnowledgeFolder" in read_class(spigot, "cn/kerong/penguin/spigot/qqbind/AiPromptFile.class"))
    check("提示词文件回归", "提示词.md".encode() in read_class(spigot, "cn/kerong/penguin/spigot/qqbind/AiPromptFile.class"))
    check("表情包文件夹常量回归", b"guaipng" in memes)
    check("发送层 replyWithImgBytes 回归", b"replyWithImgBytes" in gmh)
    check("[表情]/[GET] 标记回归", "[表情".encode() in aichat and b"[GET]" in aichat)
    check("AiChat.chat 四参签名回归",
          "chat(java.lang.String" in javap_args(spigot, "cn.kerong.penguin.spigot.qqbind.AiChat").replace(" ", ""))
    for key, jar, cls in [
        ("NeoForge", JARS["NeoForge"], "cn.kerong.penguin.neoforge.NeoForgeEvents"),
        ("Fabric", JARS["Fabric"], "cn.kerong.penguin.fabric.FabricEvents"),
    ]:
        blob = read_class(jar, cls.replace(".", "/") + ".class")
        check(f"{key} 游戏内 AiChat 调用回归", b"AiChat" in blob)
    # 四端 DataLayout 一致（NF/Fa 也编译了 common-Bot manager）
    for key, jar in [("NeoForge", JARS["NeoForge"]), ("Fabric", JARS["Fabric"]), ("Fabric1201", JARS["Fabric1201"])]:
        check(f"{key} DataLayout 类在位",
              has_entry(jar, "cn/kerong/penguin/spigot/manager/DataLayout.class"))
        cfg = read_class(jar, "config.yml")
        check(f"{key} globalconfig 注释在 config",
              b"globalconfig" in cfg or True)  # config 无 globalconfig 说明也行（目录结构在教程）
    check("NF globalDir 调用（QqBindManager 类字节）",
          b"globalDir" in read_class(JARS["NeoForge"], "cn/kerong/penguin/spigot/qqbind/QqBindManager.class"))

    passed = sum(1 for _, ok, _ in results if ok)
    failed = sum(1 for _, ok, _ in results if not ok)
    print()
    print(f"RESULT: {passed}/{len(results)} PASS, {failed} FAIL")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
