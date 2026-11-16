#!/usr/bin/env python3
"""
1.7.6.3 门禁验证（对 build/dist 四产物断言）。

本轮五任务：
  A  爬取转正式版——ai.crawler.enabled 默认关→开（Rebuilder 升 58 收敛），
     能力段去测试警告 + 必应搜索指引；max-pages 2→3；
  M  多次多层爬取——extractText 带 baseUrl（abs:href 链接保留「文字（网址）」），
     normalizeUrl 非 ASCII 百分号编码；固定常量 DEFAULT_TIMEOUT_SECONDS /
     DEFAULT_MAX_CHARS / MEME_MAX_PER_REPLY；
  K  旧知识库文件夹清理——AiPromptFile.cleanupLegacyKnowledgeFolder +
     ConfigManager.cleanupLegacyFolders（删键前执行）；
  S  配置简化——删 4 调优键（ai.memes.folder / ai.memes.max-per-reply /
     ai.crawler.timeout-seconds / ai.crawler.max-chars），QqBindManager
     读取函数删除，memesFolder 固定 guaipng；
  V  版本基建——config-version 58、六处版本号 1.7.6.3、官网三件口径。
回归：1762o 全量核心（失败口径 / 提示词文件 / 标记协议 / 发送层）。

用法：python3 scripts/verify_jar_1763.py
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
    prompt = read_class(spigot, "cn/kerong/penguin/spigot/qqbind/AiPromptFile.class")
    aichat_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/AiChat.kt")
    crawler_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/WebCrawler.kt")
    qbm_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/QqBindManager.kt")
    cm_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
    rb_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.kt")

    print("== A 组：爬取转正式版 ==")
    check("isAiCrawlerEnabled 默认开（源码）",
          'getBoolean("ai.crawler.enabled", true)' in qbm_src)
    check("旧默认 false 分支已清除（源码）",
          'getBoolean("ai.crawler.enabled", false)' not in qbm_src)
    check("ai.crawler.enabled 键仍在（可关闭）",
          b"ai.crawler.enabled" in qbm)
    check("Rebuilder 升级收敛 false→true（源码）",
          'disk.set("ai.crawler.enabled", true)' in rb_src)
    check("能力段去「测试功能」字样（类字节）",
          "测试功能".encode() not in aichat)
    check("能力段必应搜索指引（类字节）",
          "https://www.bing.com/search?q=".encode() in aichat)
    check("能力段多层链接指引（类字节）",
          "逐层阅读".encode() in aichat)
    check("能力段多次读取指引（类字节）",
          "多行读多个网页".encode() in aichat)
    check("max-pages 默认 3（源码）",
          'getInt("ai.crawler.max-pages", 3)' in qbm_src)
    check("失败口径回归（类字节）",
          "不要输出错误码或技术原因".encode() in aichat)
    for key in ["Spigot", "NeoForge", "Fabric"]:
        cfg = read_class(JARS[key], "config.yml")
        check(f"{key} config crawler.enabled 默认开",
              b"crawler:\n    enabled: true" in cfg)
        check(f"{key} config max-pages 3", b"max-pages: 3" in cfg)
        check(f"{key} config 无测试功能字样", "测试功能".encode() not in cfg)
        check(f"{key} config token 消耗提示", "消耗额外 token".encode() in cfg)
        check(f"{key} config 必应说明", "必应".encode() in cfg)

    print("== M 组：多次多层爬取 ==")
    wc_javap = javap_args(spigot, "cn.kerong.penguin.spigot.qqbind.WebCrawler")
    check("extractText 三参签名（baseUrl）",
          "extractText(java.lang.String,int,java.lang.String)" in wc_javap.replace(" ", ""))
    check("normalizeUrl 纯函数在位",
          "normalizeUrl(java.lang.String)" in wc_javap.replace(" ", ""))
    check("链接保留 abs:href（类字节）", b"abs:href" in crawler)
    check("链接拼接文案（类字节）", "（".encode() in crawler and b"setBaseUri" in crawler)
    check("normalizeUrl 走 URLEncoder（类字节）",
          b"java/net/URLEncoder" in crawler or b"URLEncoder" in crawler)
    check("固定超时常量 DEFAULT_TIMEOUT_SECONDS", b"DEFAULT_TIMEOUT_SECONDS" in crawler)
    check("固定正文上限 DEFAULT_MAX_CHARS", b"DEFAULT_MAX_CHARS" in crawler)
    check("抓取调用走固定常量（源码）",
          "WebCrawler.DEFAULT_TIMEOUT_SECONDS" in aichat_src and "WebCrawler.DEFAULT_MAX_CHARS" in aichat_src)
    check("AiChat 表情包上限常量 MEME_MAX_PER_REPLY", b"MEME_MAX_PER_REPLY" in aichat)
    check("AiMemeLibrary 文件夹常量 FOLDER_NAME",
          b"FOLDER_NAME" in read_class(spigot, "cn/kerong/penguin/spigot/qqbind/AiMemeLibrary.class")
          and b"guaipng" in read_class(spigot, "cn/kerong/penguin/spigot/qqbind/AiMemeLibrary.class"))
    check("memesFolder 固定 guaipng（源码）",
          "AiMemeLibrary.FOLDER_NAME" in qbm_src)
    check("UA 版本 1.7.6.3（类字节）", b"KERONGPenguin-Bot/1.7.6.3" in crawler)
    check("URL 编码在 parseAndValidate 入口（源码）",
          "URL(normalizeUrl(urlText.trim()))" in crawler_src.replace("  ", " "))

    print("== K 组：旧知识库文件夹清理 ==")
    check("cleanupLegacyKnowledgeFolder 在位",
          b"cleanupLegacyKnowledgeFolder" in prompt)
    check("旧说明文件名常量（类字节）",
          "说明-把知识文件放进这里.txt".encode() in prompt)
    check("清理结果枚举（类字节）",
          has_entry(spigot, "cn/kerong/penguin/spigot/qqbind/AiPromptFile$CleanupResult.class"))
    check("ConfigManager.cleanupLegacyFolders（删键前执行，源码）",
          "cleanupLegacyFolders()" in cm_src
          and cm_src.find("cleanupLegacyFolders()") < cm_src.find("ConfigFileRebuilder.rebuildIfNeeded"))
    check("读旧配置 knowledge.folder 自定义名（源码）",
          'getString("ai.knowledge.folder")' in cm_src)
    check("读旧配置 memes.folder 迁移提示（源码）",
          'getString("ai.memes.folder")' in cm_src)
    check("表情包迁移提示文案（类字节）",
          "移入".encode() in read_class(spigot, "cn/kerong/penguin/spigot/manager/ConfigManager.class"))

    print("== S 组：配置简化删键 ==")
    qbm_javap = javap_args(spigot, "cn.kerong.penguin.spigot.qqbind.QqBindManager")
    check("aiMemesMaxPerReply 函数已删",
          "aiMemesMaxPerReply" not in qbm_javap)
    check("aiCrawlerTimeoutSeconds 函数已删",
          "aiCrawlerTimeoutSeconds" not in qbm_javap)
    check("aiCrawlerMaxChars 函数已删",
          "aiCrawlerMaxChars" not in qbm_javap)
    check("QqBindManager 无 memes.folder 键字符串（类字节）",
          b"ai.memes.folder" not in qbm)
    check("QqBindManager 无 max-per-reply 键字符串（类字节）",
          b"max-per-reply" not in qbm)
    check("QqBindManager 无 crawler.max-chars 键字符串（类字节）",
          b"crawler.max-chars" not in qbm and b"ai.crawler.max-chars" not in qbm)
    check("ConfigManager 删键清单 4 键（源码）",
          all(k in cm_src for k in ['"ai.memes.folder"', '"ai.memes.max-per-reply"',
                                    '"ai.crawler.timeout-seconds"', '"ai.crawler.max-chars"']))
    for key in ["Spigot", "NeoForge", "Fabric"]:
        cfg = read_class(JARS[key], "config.yml")
        check(f"{key} config 无 folder 键", b'folder: "guaipng"' not in cfg)
        check(f"{key} config 无 max-per-reply 键", b"max-per-reply" not in cfg)
        check(f"{key} config 无 max-chars 键", b"max-chars" not in cfg)
        check(f"{key} config 无 timeout-seconds 键（AI 段）", b"    timeout-seconds: 10" not in cfg)

    print("== V 组：版本基建与官网 ==")
    cm_ver = "CURRENT_CONFIG_VERSION = 58" in cm_src
    check("ConfigManager 版本常量 58", cm_ver)
    for key in ["Spigot", "NeoForge", "Fabric"]:
        cfg = read_class(JARS[key], "config.yml")
        check(f"{key} config-version 58", b"config-version: 58" in cfg)
        check(f"{key} ai.prompt-file 默认名",
              'prompt-file: "提示词.md"'.encode() in cfg)
        check(f"{key} 无 knowledge 段", b"knowledge:" not in cfg)
        check(f"{key} 无 system-prompt 键", b"system-prompt:" not in cfg)
    check("源码 config.yml 无版本号标注",
          all(m not in src("server-Spigot/src/main/resources/config.yml")
              for m in ["1.7.6.3", "1.7.6.2", "1.7.6.1", "1.7.6", "1.0.0"]))
    py = read_class(spigot, "plugin.yml")
    check("plugin.yml 版本 1.7.6.3", b"version: '1.7.6.3'" in py)
    check("NeoForge 模组元数据版本 1.7.6.3",
          b"1.7.6.3" in read_class(JARS["NeoForge"], "META-INF/neoforge.mods.toml"))
    check("Fabric 模组元数据版本 1.7.6.3",
          b"1.7.6.3" in read_class(JARS["Fabric"], "fabric.mod.json"))
    check("Fabric1201 模组元数据版本 1.7.6.3",
          b"1.7.6.3" in read_class(JARS["Fabric1201"], "fabric.mod.json"))
    nf_src = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt")
    check("KERONGNeoForge MOD_VERSION 1.7.6.3（补上轮漏更）",
          'MOD_VERSION = "1.7.6.3"' in nf_src)
    tutorial = src("website/tutorial.html")
    check("教程爬取条目正式功能口径",
          "AI 网页读取（正式功能）" in tutorial and "测试功能" not in tutorial)
    check("教程 token 消耗提示", "消耗额外 token" in tutorial)
    check("教程必应指引", "bing.com" in tutorial)
    check("教程表情包条目无 folder 键", "folder:" not in tutorial)
    vj = src("website/version.json")
    check("version.json 1.7.6.3", '"version": "1.7.6.3"' in vj)
    check("version.json 含正式版+简化条目",
          "正式功能" in vj and "简化 AI 配置" in vj and "知识库文件夹" in vj)
    idx = src("website/index.html")
    check("index v1.7.6.3 块在位", "v1.7.6.3" in idx and "版本 1.7.6.3" in idx)
    check("index 四按钮 1.7.6.3",
          idx.count("1.7.6.3.jar") == 8)

    print("== R 组：回归（1762o 核心保留） ==")
    gmh = read_class(spigot, "cn/kerong/bot/events/GroupMessageHandler.class")
    check("统一失败文案常量回归", b"READ_FAILURE_MESSAGE" in crawler)
    check("失败文案自然语言口径回归", "网页暂时无法访问或没有返回内容".encode() in crawler)
    check("buildPageFeedback 失败引导回归", "不要输出错误代码或技术原因".encode() in aichat)
    check("AiPromptFile 默认文件名回归", "提示词.md".encode() in prompt)
    check("默认内容角色设定结构回归", "# 角色设定".encode() in prompt)
    check("迁移函数回归", b"migrateFromSystemPrompt" in prompt)
    check("AiKnowledgeLibrary 仍已删除",
          not has_entry(spigot, "cn/kerong/penguin/spigot/qqbind/AiKnowledgeLibrary.class"))
    check("表情包发送层回归 replyWithImgBytes", b"replyWithImgBytes" in gmh)
    check("chatWithOutcome 接线回归", b"chatWithOutcome" in gmh)
    check("[表情] 标记回归", "[表情".encode() in aichat)
    check("[GET] 标记回归", b"[GET]" in aichat)
    check("表情包默认开回归", 'getBoolean("ai.memes.enabled", true)' in qbm_src)
    check("提示词文件读取回归",
          "AiPromptFile.loadPrompt(aiPromptFile())".replace(" ", "") in qbm_src.replace(" ", ""))
    check("启动即建表情包文件夹+提示词文件回归",
          "AiMemeLibrary.ensureFolder(memesFolder())" in qbm_src
          and "AiPromptFile.ensureFile(aiPromptFile())" in qbm_src)
    ac_javap = javap_args(spigot, "cn.kerong.penguin.spigot.qqbind.AiChat")
    check("AiChat.chat 四参签名保留", "chat(java.lang.String" in ac_javap.replace(" ", ""))
    check("chatWithOutcome 入口保留", "chatWithOutcome(" in ac_javap)
    for key, jar, cls in [
        ("Spigot", spigot, "cn.kerong.penguin.spigot.events.GameChat"),
        ("NeoForge", JARS["NeoForge"], "cn.kerong.penguin.neoforge.NeoForgeEvents"),
        ("Fabric", JARS["Fabric"], "cn.kerong.penguin.fabric.FabricEvents"),
    ]:
        blob = read_class(jar, cls.replace(".", "/") + ".class")
        check(f"{key} 游戏内 AiChat.chat 调用回归", b"AiChat" in blob and b"chat" in blob)

    passed = sum(1 for _, ok, _ in results if ok)
    failed = sum(1 for _, ok, _ in results if not ok)
    print()
    print(f"RESULT: {passed}/{len(results)} PASS, {failed} FAIL")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
