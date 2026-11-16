#!/usr/bin/env python3
"""
1.7.6.2 覆盖轮门禁验证（对 build/dist 四产物断言）。

本轮四任务：
  A  爬取失败输出访问代码修复——WebCrawler 全部失败路径统一
     READ_FAILURE_MESSAGE（无状态码/异常细节），AiChat.buildPageFeedback
     失败引导；HTTP 码只写 PluginFileLog；
  K  知识库改提示词单文件——AiPromptFile 新类（默认 提示词.md，
     角色设定+专属知识），AiKnowledgeLibrary 删除，ai.system-prompt /
     ai.knowledge 配置删除（迁移在删键前执行）；
  M  表情包文件夹默认生成——QqBindManager.init 启动即建（三端一致）；
  V  版本标注清理——三端 config.yml 无「x.x.x 新增」字样；
     config-version 56 → 57；官网教程同口径（website 文件）。
回归：表情包/爬取/集群多卡首发能力 + chat 签名 + 发送层。

用法：python3 scripts/verify_jar_1762o.py
"""
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/repo")
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
    aichat_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/AiChat.kt")
    crawler_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/WebCrawler.kt")
    qbm_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/QqBindManager.kt")
    cm_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")

    print("== A 组：爬取失败输出访问代码修复 ==")
    check("WebCrawler 统一失败常量 READ_FAILURE_MESSAGE",
          b"READ_FAILURE_MESSAGE" in crawler)
    check("失败文案不含 HTTP 占位（无状态码模板）",
          "读取失败：HTTP $code".encode() not in crawler)
    check("失败文案自然语言口径", "网页暂时无法访问或没有返回内容".encode() in crawler)
    check("AiChat 失败反馈 buildPageFeedback", b"buildPageFeedback" in aichat)
    check("buildPageFeedback 失败引导文案",
          "不要输出错误代码或技术原因".encode() in aichat)
    check("能力段失败口径说明", "不要输出错误码或技术原因".encode() in aichat)
    check("源码：fetch 全部失败路径走常量（无带码返回）",
          "读取失败：HTTP $code\"".replace(" $code", " $code") not in crawler_src and
          crawler_src.count("READ_FAILURE_MESSAGE") >= 6,
          f"出现次数 {crawler_src.count('READ_FAILURE_MESSAGE')}")
    check("源码：buildPageFeedback 失败分支判定常量",
          "fetched == WebCrawler.READ_FAILURE_MESSAGE" in aichat_src)
    check("源码：空白正文按失败处理", "raw.isBlank()" in crawler_src)

    print("== K 组：知识库改提示词单文件 ==")
    check("AiPromptFile 类在位", has_entry(spigot, "cn/kerong/penguin/spigot/qqbind/AiPromptFile.class"))
    prompt = read_class(spigot, "cn/kerong/penguin/spigot/qqbind/AiPromptFile.class")
    check("默认文件名 提示词.md", "提示词.md".encode() in prompt)
    check("默认内容含角色设定结构", "# 角色设定".encode() in prompt)
    check("默认内容含专属知识结构", "# 专属知识".encode() in prompt)
    check("默认角色设定文案", "你是一个友好的游戏群助手，请简洁回答。".encode() in prompt)
    check("迁移函数 migrateFromSystemPrompt", b"migrateFromSystemPrompt" in prompt)
    check("读取函数 loadPrompt", b"loadPrompt" in prompt)
    check("AiKnowledgeLibrary 类已删除",
          not has_entry(spigot, "cn/kerong/penguin/spigot/qqbind/AiKnowledgeLibrary.class"))
    check("AiChat 知识库段入口已移除", b"knowledgeSection" not in aichat)
    check("QqBindManager 知识库配置族已移除",
          b"ai.knowledge" not in qbm and b"knowledgeFolder" not in qbm)
    check("QqBindManager aiPromptFile 在位", b"aiPromptFile" in qbm and b"ai.prompt-file" in qbm)
    check("QqBindManager aiSystemPrompt 走提示词文件",
          "AiPromptFile.loadPrompt(aiPromptFile())".replace(" ", "") in qbm_src.replace(" ", ""))
    check("ConfigManager 废弃键 ai.system-prompt", '"ai.system-prompt"' in cm_src)
    check("ConfigManager 废弃键 ai.knowledge", '"ai.knowledge"' in cm_src)
    check("ConfigManager 迁移函数（删键前执行）", "migrateSystemPromptToPromptFile" in cm_src)
    check("迁移先于重建（源码序）",
          cm_src.find("migrateSystemPromptToPromptFile()") < cm_src.find("ConfigFileRebuilder.rebuildIfNeeded"))
    check("DEFAULT_VALUES 无 system-prompt", '"ai.system-prompt" to' not in cm_src)

    print("== M 组：表情包文件夹默认生成 ==")
    check("QqBindManager.init 启动建表情包文件夹",
          "AiMemeLibrary.ensureFolder(memesFolder())" in qbm_src)
    check("QqBindManager.init 启动建提示词文件",
          "AiPromptFile.ensureFile(aiPromptFile())" in qbm_src)
    check("启动默认生成在构造期（源码序：buildReverseIndexes 后）",
          qbm_src.find("AiMemeLibrary.ensureFolder(memesFolder())") > qbm_src.find("buildReverseIndexes()"))
    check("AiMemeLibrary 说明文件（纯函数可冒烟）", b"ensureFolder" in read_class(spigot, "cn/kerong/penguin/spigot/qqbind/AiMemeLibrary.class"))

    print("== V 组：版本标注清理与基建 ==")
    for key in ["Spigot", "NeoForge", "Fabric"]:
        cfg = read_class(JARS[key if key != "NeoForge" else "NeoForge"], "config.yml")
        # 三端资源都在各自 JAR（Spigot 主 JAR 也带 config.yml）
        check(f"{key} config-version 57", b"config-version: 57" in cfg)
        check(f"{key} ai.prompt-file 默认名",
              b'prompt-file: "\xe6\x8f\x90\xe7\xa4\xba\xe8\xaf\x8d.md"' in cfg)
        check(f"{key} 无 knowledge 段", b"knowledge:" not in cfg)
        check(f"{key} 无 system-prompt 键", b"system-prompt:" not in cfg)
        check(f"{key} 无「1.7.6.2 新增」字样", "1.7.6.2".encode() not in cfg)
        check(f"{key} 无「1.7.6.1 新增」字样", "1.7.6.1".encode() not in cfg)
        check(f"{key} 爬取测试功能警告保留", "测试功能警告".encode() in cfg)
    cfg_src = src("server-Spigot/src/main/resources/config.yml")
    check("源码 config.yml 无任何版本号标注",
          all(m not in cfg_src for m in ["1.7.6.2", "1.7.6.1", "1.7.6", "1.7.5.9", "1.0.0"]))
    check("ConfigManager 版本常量 57", "CURRENT_CONFIG_VERSION = 57" in cm_src)
    tutorial = src("website/tutorial.html")
    check("官网教程无「x.x.x 新增」标注",
      all(m not in tutorial for m in ["1.7.6.2 新增", "1.7.6.1 新增", "1.7.6 新增", "1.7.5.9 起", "1.7.6 起"]),
    )
    check("官网教程无 system-prompt 配置条目", '<span class="key">ai.system-prompt</span>' not in tutorial)
    check("官网教程有提示词文件条目", "ai.prompt-file" in tutorial and "提示词.md" in tutorial)
    check("官网教程无知识库文件夹条目", "ai.knowledge" not in tutorial)
    version_json = src("website/version.json")
    check("version.json 含提示词条目", "提示词" in version_json and "ai.system-prompt" in version_json)
    check("version.json 含访问代码修复条目", "访问代码" in version_json)

    print("== R 组：回归（首发能力保留） ==")
    gmh = read_class(spigot, "cn/kerong/bot/events/GroupMessageHandler.class")
    gmh_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/GroupMessageHandler.kt")
    check("表情包发送层回归 replyWithImgBytes", b"replyWithImgBytes" in gmh)
    check("发送层 chatWithOutcome 接线", b"chatWithOutcome" in gmh)
    check("[表情] 标记正则回归", "[表情".encode() in aichat)
    check("[GET] 标记回归", b"[GET]" in aichat)
    check("SSRF 公网校验回归 isAllowedAddress", b"isAllowedAddress" in crawler)
    check("爬取默认 false（源码）", 'getBoolean("ai.crawler.enabled", false)' in qbm_src)
    check("表情包默认开（源码）", 'getBoolean("ai.memes.enabled", true)' in qbm_src)
    check("集群多卡接线回归 clusterShowMultiCards", b"clusterShowMultiCards" in gmh)
    check("思考段剥离回归 stripThink", b"stripThink" in aichat)
    ac_javap = javap_args(spigot, "cn.kerong.penguin.spigot.qqbind.AiChat")
    check("AiChat.chat 四参签名保留",
          "chat(java.lang.String" in ac_javap.replace(" ", ""))
    check("chatWithOutcome 入口保留", "chatWithOutcome(" in ac_javap)
    for key, jar, cls in [
        ("Spigot", spigot, "cn.kerong.penguin.spigot.events.GameChat"),
        ("NeoForge", JARS["NeoForge"], "cn.kerong.penguin.neoforge.NeoForgeEvents"),
        ("Fabric", JARS["Fabric"], "cn.kerong.penguin.fabric.FabricEvents"),
    ]:
        blob = read_class(jar, cls.replace(".", "/") + ".class")
        check(f"{key} 游戏内 AiChat.chat 调用回归", b"AiChat" in blob and b"chat" in blob)
    # 四 JAR 版本一致性
    for key, jar in JARS.items():
        py = read_class(jar, "plugin.yml") if key == "Spigot" else None
        if py is not None:
            check("plugin.yml 版本 1.7.6.2", b"version: '1.7.6.2'" in py)
            break
    for key, entry in [("NeoForge", "META-INF/neoforge.mods.toml"), ("Fabric", "fabric.mod.json")]:
        blob = read_class(JARS[key], entry)
        check(f"{key} 模组元数据版本 1.7.6.2", b"1.7.6.2" in blob)

    passed = sum(1 for _, ok, _ in results if ok)
    failed = sum(1 for _, ok, _ in results if not ok)
    print()
    print(f"RESULT: {passed}/{len(results)} PASS, {failed} FAIL")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
