#!/usr/bin/env python3
"""1.7.6.3 四次覆盖轮门禁（AI 搜索默认关 / 提示词技能引导条件生成 / 数据迁移残留修复）。

A 组  AI 搜索默认配置改关：
  A1  ConfigManager.DEFAULT_VALUES ai.crawler.enabled → false
  A2  QqBindManager.isAiCrawlerEnabled 运行时兜底默认 false（get + catch）
  A3  三端 config.yml crawler.enabled: false（源）
  A4  四端 JAR 内嵌 config.yml crawler.enabled: false
  A5  ConfigFileRebuilder 升 58 false→true 收敛块移除（源码反断言）
  A6  四端 JAR Rebuilder 字节码无 ai.crawler.enabled 引用（收敛块移除直接证据）
B 组  提示词技能引导条件生成：
  B1  defaultContent(role, crawlerEnabled) 双参签名（源码）
  B2  搜索引导行包在 if (crawlerEnabled) 内（源码顺序断言）
  B3  关闭时技能段仍保留发表情包 + 系统技能段为准两行
  B4  ensureFile(file, crawlerEnabled) / migrateFromSystemPrompt 三参（源码）
  B5  QqBindManager 调用点传 isAiCrawlerEnabled
  B6  ConfigManager 迁移调用点读磁盘旧配置 ai.crawler.enabled
  B7  capabilitySection 搜索条目仍仅 isAiCrawlerEnabled 时注入（回归）
  B8  四端 JAR AiPromptFile 字节码三方法新签名
  B9  四端 JAR QqBindManager 字节码 getBoolean(iconst_0)（默认 false 编译形态）
  B10 config 注释口径（默认关 + 关闭不带搜索引导）
C 组  数据迁移残留修复（DataLayout）：
  C1  moveWithFallback / resolveSameName 方法在位（源码）
  C2  旧版同名跳过形态反断言（if (dest.exists()) continue 不再出现）
  C3  renameTo 失败复制后删除兜底（copyRecursively + deleteRecursively）
  C4  同名冲突按修改时间取新（lastModified 比较）
  C5  迁移后残留控制台提示
  C6  四端 JAR DataLayout 字节码新方法在位 + 复制删除调用 + 残留提示字符串
D 组  回归（三次覆盖轮关键行为保持）：
  D1  技能段标题 / 先搜索引导常量在位（源码 + 四端字节码）
  D2  纯表情包三分支（textBlank / aiContent=null / 全失败补发）在位
  D3  动态技能段全关不注入（skills.isEmpty()）
  D4  版本号 1.7.6.3 与 config-version 60 保持（plugin.yml / mods.toml 双文件 /
      MOD_VERSION ldc / fabric.mod.json / build.gradle.kts）
  D5  官网三件（version.json ⑧⑨⑩ / index.html 三条 li / tutorial 默认关口径）
  D6  README 四次覆盖轮条目在位
"""
import json
import os
import re
import subprocess
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(REPO, "build", "dist")
JAVAP = "/home/z/my-project/tooling/jdk21/bin/javap"

JARS = {
    "Spigot": os.path.join(DIST, "KERONGPenguin_Spigot-1.7.6.3.jar"),
    "NeoForge": os.path.join(DIST, "KERONGPenguin_NeoForge-1.20.4-1.7.6.3.jar"),
    "Fabric-1204": os.path.join(DIST, "KERONGPenguin_Fabric-1.20.4-1.7.6.3.jar"),
    "Fabric-1201": os.path.join(DIST, "KERONGPenguin_Fabric-1.20.1-1.7.6.3.jar"),
}

pass_n = 0
fail_n = 0
fails = []


def check(name, ok):
    global pass_n, fail_n
    if ok:
        pass_n += 1
        print(f"  PASS {name}")
    else:
        fail_n += 1
        fails.append(name)
        print(f"  FAIL {name}")


def src(rel):
    with open(os.path.join(REPO, rel), encoding="utf-8") as f:
        return f.read()


def read_entry(jar_path, entry):
    with zipfile.ZipFile(jar_path) as zf:
        return zf.read(entry)


def javap_text(jar_path, cls, extra=("-p", "-c")):
    out = subprocess.run(
        [JAVAP, *extra, "-cp", jar_path, cls.replace("/", ".")],
        capture_output=True, text=True,
    )
    return out.stdout


def main():
    cfgmgr = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
    rebuilder = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.kt")
    qbm = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/QqBindManager.kt")
    aichat = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/AiChat.kt")
    prompt = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/AiPromptFile.kt")
    gmh = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/GroupMessageHandler.kt")
    layout = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/manager/DataLayout.kt")

    print("== A 组：AI 搜索默认配置改关 ==")
    check("A1 DEFAULT_VALUES ai.crawler.enabled to false",
          '"ai.crawler.enabled" to false' in cfgmgr)
    check("A2 isAiCrawlerEnabled 兜底默认 false",
          'plugin.config.getBoolean("ai.crawler.enabled", false)' in qbm
          and re.search(r'getBoolean\("ai\.crawler\.enabled", false\)', qbm) is not None)
    for module in ("server-Spigot", "server-NeoForge", "server-Fabric"):
        text = src(f"{module}/src/main/resources/config.yml")
        m = re.search(r"crawler:\s*\n\s*#[^\n]*\n\s*enabled:\s*(\w+)", text)
        check(f"A3 {module} config crawler.enabled: false", m is not None and m.group(1) == "false")
    for label, jar in JARS.items():
        cfg = read_entry(jar, "config.yml").decode("utf-8")
        m = re.search(r"crawler:\s*\n\s*#[^\n]*\n\s*enabled:\s*(\w+)", cfg)
        check(f"A4 {label} JAR 内嵌 config enabled: false", m is not None and m.group(1) == "false")
    check("A5 Rebuilder 升58收敛块移除（源码反断言）",
          'disk.set("ai.crawler.enabled", true)' not in rebuilder
          and 'disk.getBoolean("ai.crawler.enabled", true)' not in rebuilder)
    for label, jar in JARS.items():
        text = javap_text(jar, "cn/kerong/penguin/spigot/manager/ConfigFileRebuilder")
        check(f"A6 {label} Rebuilder 字节码无 ai.crawler.enabled",
              "ai.crawler.enabled" not in text)

    print("== B 组：提示词技能引导条件生成 ==")
    check("B1 defaultContent 双参签名",
          "fun defaultContent(role: String, crawlerEnabled: Boolean): String" in prompt)
    m = re.search(r"fun defaultContent.*?buildString \{", prompt, re.S)
    body = prompt[m.start():m.start() + 1400] if m else ""
    check("B2 搜索引导包在 if (crawlerEnabled) 内",
          "if (crawlerEnabled) {" in body
          and body.find("if (crawlerEnabled) {") < body.find("- 读网页与搜索")
          and body.find("- 读网页与搜索") < body.find("- 技能是否可用以系统注入"))
    check("B3 关闭时保留发表情包 + 系统技能段为准",
          'append("- 发表情包：气氛合适时在回复末尾加 [表情] 文件名；\\n")' in prompt
          and 'append("- 技能是否可用以系统注入的「系统技能」段为准。\\n")' in prompt)
    check("B4 ensureFile / migrateFromSystemPrompt 新签名",
          "fun ensureFile(file: File?, crawlerEnabled: Boolean): File?" in prompt
          and "fun migrateFromSystemPrompt(file: File?, oldRole: String?, crawlerEnabled: Boolean): Boolean" in prompt)
    check("B5 QqBindManager 调用点传 isAiCrawlerEnabled",
          "AiPromptFile.ensureFile(aiPromptFile(), isAiCrawlerEnabled)" in qbm)
    check("B6 ConfigManager 迁移读磁盘旧配置",
          'disk.getBoolean("ai.crawler.enabled", false)' in cfgmgr
          and "AiPromptFile.migrateFromSystemPrompt(promptFile, oldRole, crawlerEnabled)" in cfgmgr)
    check("B7 capabilitySection 搜索条目仍条件注入",
          re.search(r"// 读网页与搜索（默认关[^\n]*\n\s*if \(manager\.isAiCrawlerEnabled\)", aichat) is not None)
    pf_cls = "cn/kerong/penguin/spigot/qqbind/AiPromptFile"
    for label, jar in JARS.items():
        text = javap_text(jar, pf_cls)
        check(f"B8 {label} AiPromptFile 三方法新签名",
              "java.lang.String defaultContent(java.lang.String, boolean)" in text
              and "java.io.File ensureFile(java.io.File, boolean)" in text
              and "boolean migrateFromSystemPrompt(java.io.File, java.lang.String, boolean)" in text)
    qbm_cls = "cn/kerong/penguin/spigot/qqbind/QqBindManager"
    for label, jar in JARS.items():
        text = javap_text(jar, qbm_cls)
        m = re.search(r"public final boolean isAiCrawlerEnabled\(\);.*?Code:\s*\n(.*?)\n\s*\n", text, re.S)
        seg = m.group(1) if m else ""
        check(f"B9 {label} isAiCrawlerEnabled 编译形态 iconst_0 默认",
              "ai.crawler.enabled" in seg and "iconst_0" in seg)
    spigot_cfg = src("server-Spigot/src/main/resources/config.yml")
    check("B10 config 注释口径（默认关 + 关闭不带搜索引导）",
          "AI 网页读取（默认关）" in spigot_cfg
          and "关闭时提示词不带搜索引导" in spigot_cfg)

    print("== C 组：数据迁移残留修复 ==")
    check("C1 moveWithFallback / resolveSameName 在位",
          "private fun moveWithFallback(old: File, target: File): Boolean" in layout
          and "private fun resolveSameName(old: File, target: File): Boolean" in layout)
    check("C2 旧版同名跳过形态反断言",
          "if (dest.exists()) continue" not in layout
          and "if (dest.exists()) {" not in layout)
    check("C3 rename 失败复制后删除兜底",
          "old.renameTo(target)) return true" in layout
          and "copyRecursively(target, overwrite = false)" in layout
          and "old.deleteRecursively()" in layout)
    check("C4 同名冲突按修改时间取新",
          "old.lastModified() > target.lastModified()" in layout
          and "copyTo(target, overwrite = true)" in layout)
    check("C5 迁移后残留控制台提示",
          "数据目录迁移后有残留" in layout)
    dl_cls = "cn/kerong/penguin/spigot/manager/DataLayout"
    for label, jar in JARS.items():
        text = javap_text(jar, dl_cls)
        data = read_entry(jar, dl_cls + ".class")
        check(f"C6 {label} DataLayout 新方法 + 兜底调用 + 残留提示",
              "moveWithFallback" in text and "resolveSameName" in text
              and text.count("copyRecursively") + text.count("deleteRecursively") >= 4
              and "数据目录迁移后有残留".encode() in data)

    print("== D 组：回归 ==")
    check("D1 技能段标题 / 先搜索引导在位（源码）",
          "# 技能（你拥有的技能，系统自动注入，不用手动维护）" in prompt
          and "先搜索再回答，不要凭记忆猜测，也不要直接说不知道" in aichat)
    check("D2 纯表情包三分支在位",
          "val textBlank = reply.isBlank()" in gmh
          and "outcome.memeFiles.isNotEmpty() -> null" in gmh
          and "memeSentCount == 0" in gmh
          and ".text.ifBlank { " in aichat)
    check("D3 动态技能段全关不注入",
          'if (skills.isEmpty()) return ""' in aichat)
    check("D4a plugin.yml 1.7.6.3",
          re.search(r"version: '??1\.7\.6\.3", read_entry(JARS["Spigot"], "plugin.yml").decode()) is not None)
    nf_jar = JARS["NeoForge"]
    for entry in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
        try:
            t = read_entry(nf_jar, entry).decode("utf-8")
            check(f"D4b NF {entry} 1.7.6.3", "1.7.6.3" in t)
        except KeyError:
            check(f"D4b NF {entry} 存在", False)
    nf_main = javap_text(nf_jar, "cn/kerong/penguin/neoforge/KERONGNeoForge", extra=("-p", "-c", "-v"))
    check("D4c KERONGNeoForge MOD_VERSION ldc 1.7.6.3", "1.7.6.3" in nf_main)
    for entry, jlabel in (("fabric.mod.json", "Fabric-1204"), ("fabric.mod.json", "Fabric-1201")):
        t = read_entry(JARS[jlabel], entry).decode("utf-8")
        check(f"D4d {jlabel} fabric.mod.json 1.7.6.3", '"1.7.6.3"' in t)
    check("D4e build.gradle.kts 1.7.6.3",
          re.search(r'version\s*=\s*"1\.7\.6\.3"', src("build.gradle.kts")) is not None)
    m = re.search(r"config-version:\s*(\d+)", read_entry(JARS["Spigot"], "config.yml").decode())
    check("D4f config-version 60 保持", m is not None and m.group(1) == "60")
    vj = json.load(open(os.path.join(REPO, "website/version.json"), encoding="utf-8"))
    check("D5a version.json ⑧⑨⑩ 三条",
          any("默认改为关" in c for c in vj["changelog"])
          and any("仅在 AI 网页读取开启时才生成" in c for c in vj["changelog"])
          and any("globalconfig/ 后旧文件残留" in c for c in vj["changelog"])
          and all(isinstance(c, str) for c in vj["changelog"]))
    idx = src("website/index.html")
    check("D5b index.html 三条新 li",
          "<b>AI 网页读取（搜索）默认改为关</b>" in idx
          and "技能引导仅在 AI 网页读取开启时才生成</b>" in idx
          and "globalconfig/ 后旧文件残留</b>" in idx)
    tut = src("website/tutorial.html")
    check("D5c tutorial 默认关口径",
          "<b>默认关闭</b>（此前版本默认开启" in tut
          and "enabled: false          # 默认关；改 true 开启" in tut)
    readme = src("README.md")
    check("D6 README 四次覆盖轮条目",
          "> 1.7.6.3 四次覆盖轮：**AI 搜索默认改关 + 提示词技能引导条件生成**" in readme
          and "### 1.7.6.3 四次覆盖轮" in readme
          and "**修复数据迁移后旧文件残留**" in readme)

    print(f"\n===== {pass_n}/{pass_n + fail_n} =====")
    if fails:
        print("失败项:", fails)
    return 0 if fail_n == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
