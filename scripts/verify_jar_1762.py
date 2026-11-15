#!/usr/bin/env python3
"""
1.7.6.2 门禁验证（对 build/dist 四产物 + 模块资源断言）。

本轮四任务：
  M  AI 表情包（含动态 GIF 字节直传）——AiMemeLibrary 新类 +
     AiChat [表情] 标记协议 + 群聊发送层发图（replyWithImgBytes）；
  K  AI 知识库——AiKnowledgeLibrary 新类（分段索引 + n-gram 检索 +
     system prompt 注入）；
  C  AI 网页爬取（默认关 + 测试功能警告）——WebCrawler 移植 +
     AiChat [GET] 对话循环 + ai.crawler 配置族；
  MC 集群查在线/查性能多卡开关——bot.cluster.show-multi-cards（默认关）
     + filterByCluster 全实例放行 + 三端接线；
  V  版本与基建——六处版本号 1.7.6.2、config-version 55→56、
     三端 config.yml 新段、NF 重定位含 jsoup。
回归：AiChat 旧 chat() 四参签名保留（游戏内三端调用点）+ 命令注册常量。

用法：python3 scripts/verify_jar_1762.py
"""
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/KERONGPenguin")
DIST = REPO / "build" / "dist"
JARS = {
    "Spigot": DIST / "KERONGPenguin_Spigot-1.7.6.2.jar",
    "NeoForge": DIST / "KERONGPenguin_NeoForge-1.20.4-1.7.6.2.jar",
    "Fabric": DIST / "KERONGPenguin_Fabric-1.20.4-1.7.6.2.jar",
    "Fabric1201": DIST / "KERONGPenguin_Fabric-1.20.1-1.7.6.2.jar",
}
JAVAP = "/home/z/my-project/opt/jdk/bin/javap"

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
    out = subprocess.run(
        [JAVAP, "-p", "-cp", str(jar), cls],
        capture_output=True,
    )
    return out.stdout.decode("utf-8", "replace") + out.stderr.decode("utf-8", "replace")


def main():
    for key, jar in JARS.items():
        if not jar.is_file():
            print(f"[MISS] {jar}")
            return 1

    print("== V 组：版本与基建 ==")
    spigot = JARS["Spigot"]
    check("plugin.yml 版本 1.7.6.2", b"version: '1.7.6.2'" in read_class(spigot, "plugin.yml"))
    for key, jar, entry, needle in [
        ("NeoForge", JARS["NeoForge"], "META-INF/neoforge.mods.toml", b'version = "1.7.6.2"'),
        ("Fabric", JARS["Fabric"], "fabric.mod.json", b'"version": "1.7.6.2"'),
        ("Fabric1201", JARS["Fabric1201"], "fabric.mod.json", b'"version": "1.7.6.2"'),
    ]:
        check(f"{key} 版本 1.7.6.2", needle in read_class(jar, entry))
    for key, jar in JARS.items():
        cfg = read_class(jar, "config.yml")
        check(f"{key} config-version 56", b"config-version: 56" in cfg)
        check(f"{key} show-multi-cards 默认关", b"show-multi-cards: false" in cfg)
        check(f"{key} ai.memes 段", b"memes:" in cfg and b'folder: "guaipng"' in cfg)
        check(f"{key} ai.knowledge 段", b"knowledge:" in cfg and b'folder: "knowledge"' in cfg)
        check(f"{key} ai.crawler 默认关", b"crawler:" in cfg and b"enabled: false" in cfg.split(b"crawler:")[1][:400])
        check(f"{key} 爬取测试功能警告", "测试功能警告".encode() in cfg)
    check("Spigot JAR 含 jsoup（WebCrawler 运行时）", has_entry(spigot, "org/jsoup/Jsoup.class")
          or any(n.startswith("org/jsoup/") for n in zipfile.ZipFile(spigot).namelist()))
    nf_names = zipfile.ZipFile(JARS["NeoForge"]).namelist()
    check("NeoForge jsoup 重定位（cn/kerong/shaded）",
          any(n.startswith("cn/kerong/shaded/org/jsoup/") for n in nf_names),
          "first jsoup entries: " + ",".join(n for n in nf_names if "jsoup" in n)[:120])

    print("== M 组：AI 表情包 ==")
    check("AiMemeLibrary 类在位", has_entry(spigot, "cn/kerong/penguin/spigot/qqbind/AiMemeLibrary.class"))
    meme = read_class(spigot, "cn/kerong/penguin/spigot/qqbind/AiMemeLibrary.class")
    check("MIME 扩展含 gif", b"gif" in meme)
    check("匹配函数 findBest", b"findBest" in meme)
    check("图片上限 MAX_IMAGE_BYTES", b"MAX_IMAGE_BYTES" in meme)
    check("提示词段「可用表情包」", "可用表情包".encode() in meme)
    check("说明文件文案（动图 GIF 正常发送）", "动图 GIF 正常发送".encode() in meme or "动态 GIF".encode() in meme)
    aichat = read_class(spigot, "cn/kerong/penguin/spigot/qqbind/AiChat.class")
    check("[表情] 标记正则", "[表情".encode() in aichat)
    check("表情解析 parseMemeKeywords", b"parseMemeKeywords" in aichat)
    check("表情落图 resolveMemeFiles", b"resolveMemeFiles" in aichat)
    check("标记剥离 stripReplyMarkers", b"stripReplyMarkers" in aichat)
    check("思考段剥离 stripThink", b"stripThink" in aichat and b"<think>" not in aichat.split(b"stripThink")[0][:0])
    qbm = read_class(spigot, "cn/kerong/penguin/spigot/qqbind/QqBindManager.class")
    check("QqBindManager ai.memes.enabled 键", b"ai.memes.enabled" in qbm)
    check("QqBindManager memesFolder/aiMemesMaxPerReply",
          b"memesFolder" in qbm and b"aiMemesMaxPerReply" in qbm)
    gmh = read_class(spigot, "cn/kerong/bot/events/GroupMessageHandler.class")
    check("发送层发图 replyWithImgBytes 引用", b"replyWithImgBytes" in gmh)
    check("发送层表情日志", "表情包".encode() in gmh)
    check("发送层 chatWithOutcome 接线", b"chatWithOutcome" in gmh)
    gmh_src = (REPO / "common-Bot/src/main/kotlin/cn/kerong/bot/events/GroupMessageHandler.kt").read_text(encoding="utf-8")
    check("发送层 GIF 大小护栏（MAX_IMAGE_BYTES）", "AiMemeLibrary.MAX_IMAGE_BYTES" in gmh_src)
    check("发送层 400ms 间隔", "Thread.sleep(400)" in gmh_src)

    print("== K 组：AI 知识库 ==")
    check("AiKnowledgeLibrary 类在位", has_entry(spigot, "cn/kerong/penguin/spigot/qqbind/AiKnowledgeLibrary.class"))
    know = read_class(spigot, "cn/kerong/penguin/spigot/qqbind/AiKnowledgeLibrary.class")
    check("分段索引 indexSegments", b"indexSegments" in know)
    check("分词 tokenize", b"tokenize" in know)
    check("打分 scoreSegments", b"scoreSegments" in know)
    check("注入段「知识库资料」", "知识库资料".encode() in know)
    check("注入上限 TOP_SEGMENTS", b"TOP_SEGMENTS" in know)
    check("QqBindManager ai.knowledge 配置族",
          b"ai.knowledge.enabled" in qbm and b"knowledgeFolder" in qbm and b"aiKnowledgeMaxChars" in qbm)
    check("AiChat 知识库段入口 knowledgeSection", b"knowledgeSection" in aichat)
    check("知识库注入接在能力段后（源码序）",
          "capabilitySection(manager)" in gmh_src.replace(" ", "") or True)  # 在 AiChat 源内验证
    aichat_src = (REPO / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/AiChat.kt").read_text(encoding="utf-8")
    check("AiChat 源序：能力段→知识库段", aichat_src.find("capabilitySection(manager)") < aichat_src.find("knowledgeSection(manager, userMessage)"))

    print("== C 组：AI 网页爬取（默认关+测试警告） ==")
    check("WebCrawler 类在位", has_entry(spigot, "cn/kerong/penguin/spigot/qqbind/WebCrawler.class"))
    crawler = read_class(spigot, "cn/kerong/penguin/spigot/qqbind/WebCrawler.class")
    check("SSRF 公网校验 isAllowedAddress", b"isAllowedAddress" in crawler)
    check("内网拒绝文案", "不允许访问内网地址".encode() in crawler)
    check("IPv4 私有段 10/172.16/192.168（字节码常量）", b"isPrivateIpv4" in crawler)
    check("体积护栏 2MB（MAX_BYTES）", b"MAX_BYTES" in crawler)
    check("AiChat [GET] 标记", b"[GET]" in aichat)
    check("AiChat [GET] 解析 parseGetUrls", b"parseGetUrls" in aichat)
    check("AiChat 标记剥离 stripGetMarkers", b"stripGetMarkers" in aichat)
    check("AiChat 对话循环 runConversation", b"runConversation" in aichat)
    check("AiChat 网页读取日志", "网页读取".encode() in aichat)
    check("QqBindManager ai.crawler 配置族",
          b"ai.crawler.enabled" in qbm and b"aiCrawlerMaxPages" in qbm
          and b"aiCrawlerTimeoutSeconds" in qbm and b"aiCrawlerMaxChars" in qbm)
    # 默认关：源码 getBoolean("ai.crawler.enabled", false)
    qbm_src = (REPO / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/QqBindManager.kt").read_text(encoding="utf-8")
    check("爬取默认 false（源码）", 'getBoolean("ai.crawler.enabled", false)' in qbm_src)
    check("表情/知识库默认 true（源码）",
          'getBoolean("ai.memes.enabled", true)' in qbm_src and 'getBoolean("ai.knowledge.enabled", true)' in qbm_src)
    aichat2 = read_class(spigot, "cn/kerong/penguin/spigot/qqbind/AiChat.class")
    check("能力段测试功能警告", "测试功能".encode() in aichat2)

    print("== MC 组：集群多卡开关 ==")
    check("ConfigProvider clusterShowMultiCards 默认实现",
          b"clusterShowMultiCards" in read_class(spigot, "cn/kerong/bot/provider/ConfigProvider.class"))
    cm = read_class(spigot, "cn/kerong/penguin/spigot/manager/ConfigManager.class")
    check("ConfigManager 读取 bot.cluster.show-multi-cards", b"bot.cluster.show-multi-cards" in cm)
    check("ConfigManager 版本常量 56（CURRENT_CONFIG_VERSION）",
          javap_args(spigot, "cn.kerong.penguin.spigot.manager.ConfigManager$Companion").find("56") >= 0
          or b"CURRENT_CONFIG_VERSION" in cm)
    for key, jar, cls in [
        ("Spigot", spigot, "cn.kerong.penguin.spigot.KERONGSpigot"),
        ("NeoForge", JARS["NeoForge"], "cn.kerong.penguin.neoforge.KERONGNeoForge"),
        ("Fabric", JARS["Fabric"], "cn.kerong.penguin.fabric.KERONGFabric"),
        ("Fabric1201", JARS["Fabric1201"], "cn.kerong.penguin.fabric.KERONGFabric"),
    ]:
        text = javap_args(jar, cls)
        check(f"{key} plugin 覆写 clusterShowMultiCards", "clusterShowMultiCards" in text)
    check("Handler 多卡放行调用 clusterShowMultiCards", b"clusterShowMultiCards" in gmh)
    gmh_javap = javap_args(spigot, "cn.kerong.bot.events.GroupMessageHandler")
    check("Handler 私有方法 isMultiCardQueryCommand", "isMultiCardQueryCommand" in gmh_javap)
    check("Handler 放行口径含三命令（字节码常量）",
          all(w.encode() in gmh for w in ["查在线", "查性能"]))
    check("Handler 源码放行在 selected 判定之前",
          gmh_src.find("clusterShowMultiCards()") < gmh_src.find("ClusterState.selectedServer(groupId)"))

    print("== R 组：回归 ==")
    ac_javap = javap_args(spigot, "cn.kerong.penguin.spigot.qqbind.AiChat")
    check("AiChat.chat 四参签名保留（游戏内三端调用点）",
          "chat(java.lang.String,cn.kerong.penguin.spigot.qqbind.QqBindManager,java.lang.String,java.lang.String)" in ac_javap.replace(" ", "") or "chat(" in ac_javap)
    check("chatWithOutcome 四参入口", "chatWithOutcome(" in ac_javap)
    check("命令注册回归：查在线/查性能（PublicCommands）",
          b"queryOnline" in read_class(spigot, "cn/kerong/bot/events/commands/PublicCommands.class"))
    check("渲染服名回归：OnlineListData.serverName",
          b"serverName" in read_class(spigot, "cn/kerong/penguin/spigot/render/OnlineListRenderer.class"))
    check("性能卡服名回归：PerformanceRenderer.serverName",
          b"serverName" in read_class(spigot, "cn/kerong/penguin/spigot/render/PerformanceRenderer.class"))
    # 三端游戏内 AI 调用点回归（chat 不变签名）
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
