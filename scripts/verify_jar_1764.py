#!/usr/bin/env python3
"""1.7.6.4 门禁（六项改动 + 本轮新增技能段更新）。

A 组  提示词技能段随插件更新刷新（本轮新增，用户口径「修复提示词.md在
      安装新插件时没有更新技能字段的问题（仅插件更新替换时更新）」）：
  A1  AiPromptFile.skillSection 纯函数（defaultContent / applySkillSection /
      applyCrawlerGuidance 同源）
  A2  defaultContent 复用 skillSection（不再手抄技能段）
  A3  applySkillSection 纯函数 + Kotlin lines() 尾部空元素坑修复（trimEnd）
  A4  syncSkillSection + SKILL_VERSION_FILE（globalconfig/.prompt-skill-version）
  A5  QqBindManager 调用点与顺序（ensureFile → syncSkillSection → syncCrawlerGuidance）
  A6  同版本跳过（last == pluginVersion return，仅更新替换触发）
  A7  记录经 DataLayout.resolve 落 globalconfig/
  A8  四端 JAR AiPromptFile 字节码三方法 + .prompt-skill-version 常量
  A9  四端 JAR QqBindManager 字节码调用 syncSkillSection
  A10 三端 config 注释「插件更新时自动把技能段刷新」（源 + 四端 JAR）
  A11 version.json changelog 第⑦条 / tutorial 同口径
B 组  消息模板更新机制（默认全部替换为内置 + keep-files 白名单）：
  B1  keepFileNames 读 messages.keep-files（补 .md 后缀）
  B2  白名单命中 continue（不释放不替换）
  B3  名单外与内置不一致删除重建（applyReplacement + normalize 比对）
  B4  UpgradeSpec / fingerprint / 释放记录机制删除（源码反断言）
  B5  旧 .release-record.json 启动清理在位
  B6  三端 config keep-files 键 + 注释（源 + 四端 JAR）
  B7  ConfigManager DEFAULT_VALUES messages.keep-files + cv61
  B8  四端 JAR MessageTemplates 字节码 keepFileNames + 旧记录清理常量
C 组  globalconfig 补漏迁移（三个状态散文件）：
  C1  DataLayout.MOVE_FILES 含 cluster-selection / command-state(ini+properties)
      / server-notify-state
  C2  ClusterState.selectionFile 改接 DataLayout.resolve
  C3  HumanReadableStateFile（ini + 旧 properties）改接 resolve
  C4  ServerNotifyManager.stateFile 改接 resolve
  C5  四端 JAR 三类字节码 resolve 调用在位
  C6  教程目录树补三状态文件行
D 组  死亡播报（昵称修复 + 死因增强）：
  D1  ConfigProvider 出口拼玩家名（"$name $it" / "$name 死了"，已含名不重复拼）
  D2  Spigot customMessageOf / looksVanilla / VANILLA_DEATH_PHRASES
  D3  Spigot 补齐分支（FREEZE/CRAMMING/DRYOUT/DRAGON_BREATH/SONIC_BOOM/PROJECTILE…）
  D4  NeoForge / D5 Fabric：localizedFallback 回退 + 同步补齐分支
  D6  三端 config {message} 自动带玩家名注释
  D7  四端 JAR ConfigProvider 字节码 " 死了" 兜底串
V 组  版本与官网：
  V1  六处版本号 1.7.6.4；V2 cv61（三端源 + 四端 JAR）
  V3  version.json 7 条 changelog；V4 index 1.7.6.4 块 + 历史表已删 1.7.6 之前版本
  V5  README 覆盖轮条目
R 组  回归（1.7.6.3 四次覆盖轮关键行为 + 产物口径）：
  R1  AI 搜索默认关保持；R2 爬取开关补行机制保持（含 KERONG reload 钩子）
  R3  build/dist 无 1.7.6 之前版本产物
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
    "Spigot": os.path.join(DIST, "KERONGPenguin_Spigot-1.7.6.4.jar"),
    "NeoForge": os.path.join(DIST, "KERONGPenguin_NeoForge-1.20.4-1.7.6.4.jar"),
    "Fabric-1204": os.path.join(DIST, "KERONGPenguin_Fabric-1.20.4-1.7.6.4.jar"),
    "Fabric-1201": os.path.join(DIST, "KERONGPenguin_Fabric-1.20.1-1.7.6.4.jar"),
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
    prompt = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/AiPromptFile.kt")
    qbm = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/QqBindManager.kt")
    templates = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/MessageTemplates.kt")
    layout = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/manager/DataLayout.kt")
    cfgmgr = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
    cluster = src("common-Bot/src/main/kotlin/cn/kerong/bot/ClusterState.kt")
    hrstate = src("common-Bot/src/main/kotlin/cn/kerong/bot/state/HumanReadableStateFile.kt")
    notify = src("common-Bot/src/main/kotlin/cn/kerong/bot/ServerNotifyManager.kt")
    kerong = src("common-Bot/src/main/kotlin/cn/kerong/bot/KERONG.kt")
    cfgprov = src("common-Bot/src/main/kotlin/cn/kerong/bot/provider/ConfigProvider.kt")
    dm_spigot = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/events/DeathMessage.kt")
    dm_neo = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/DeathMessageNeo.kt")
    dm_fab = src("server-Fabric/src/main/kotlin/cn/kerong/penguin/fabric/DeathMessageFabric.kt")

    print("== A 组：提示词技能段随插件更新刷新 ==")
    check("A1 skillSection 纯函数在位",
          "fun skillSection(crawlerEnabled: Boolean): String" in prompt)
    check("A2 defaultContent 复用 skillSection（同源不手抄）",
          "append(skillSection(crawlerEnabled))" in prompt)
    check("A3 applySkillSection 纯函数 + lines() 尾部空元素坑修复",
          "fun applySkillSection(content: String, crawlerEnabled: Boolean): String" in prompt
          and "trimEnd('\\n').lines()" in prompt)
    check("A4 syncSkillSection + SKILL_VERSION_FILE",
          "fun syncSkillSection(file: File?, crawlerEnabled: Boolean, pluginVersion: String)" in prompt
          and 'const val SKILL_VERSION_FILE = ".prompt-skill-version"' in prompt)
    i_ensure = qbm.find("AiPromptFile.ensureFile(aiPromptFile(), isAiCrawlerEnabled)")
    i_sync = qbm.find("AiPromptFile.syncSkillSection(aiPromptFile(), isAiCrawlerEnabled, skillSyncVersion)")
    i_guide = qbm.find("AiPromptFile.syncCrawlerGuidance(aiPromptFile(), isAiCrawlerEnabled)")
    check("A5 QqBindManager 调用顺序 ensureFile → syncSkillSection → syncCrawlerGuidance",
          0 <= i_ensure < i_sync < i_guide)
    check("A6 同版本跳过（仅插件更新替换触发）",
          "if (last == pluginVersion) return" in prompt)
    check("A7 记录经 DataLayout.resolve 落 globalconfig/",
          "DataLayout.resolve(file.parentFile, SKILL_VERSION_FILE)" in prompt)
    for label, jar in JARS.items():
        text = javap_text(jar, "cn/kerong/penguin/spigot/qqbind/AiPromptFile")
        ok = ("skillSection" in text and "applySkillSection" in text
              and "syncSkillSection" in text and ".prompt-skill-version" in text)
        check(f"A8 {label} AiPromptFile 字节码三方法 + 记录文件名", ok)
    for label, jar in JARS.items():
        text = javap_text(jar, "cn/kerong/penguin/spigot/qqbind/QqBindManager")
        check(f"A9 {label} QqBindManager 字节码调用 syncSkillSection",
              "syncSkillSection" in text)
    for module in ("server-Spigot", "server-NeoForge", "server-Fabric"):
        text = src(f"{module}/src/main/resources/config.yml")
        check(f"A10 {module} config 注释「插件更新时自动把技能段刷新」",
              "插件更新时自动把「技能」段刷新" in text)
    for label, jar in JARS.items():
        cfg = read_entry(jar, "config.yml").decode("utf-8")
        check(f"A10b {label} JAR 内嵌 config 同注释",
              "插件更新时自动把「技能」段刷新" in cfg)
    version_json = json.loads(src("website/version.json"))
    check("A11 version.json changelog 第⑦条（技能段随插件更新刷新）",
          any("技能」段随插件更新自动刷新" in c or "技能段随插件更新" in c for c in version_json["changelog"]))
    check("A11b tutorial 提示词条目同口径",
          "插件更新时会自动把「技能」段刷新为本版内置内容" in src("website/tutorial.html"))

    print("== B 组：消息模板更新机制（内置替换 + 白名单） ==")
    check("B1 keepFileNames 读 messages.keep-files（补 .md）",
          'getConfigurationSection("messages")?.getStringList("keep-files")' in templates
          and 'endsWith(".md", ignoreCase = true)' in templates)
    check("B2 白名单命中 continue（不释放不替换）",
          "if (fileName in keep)" in templates and "continue" in templates)
    check("B3 名单外不一致删除重建",
          "normalize(current) != normalize(bundled)" in templates
          and "applyReplacement(dir, target, bundled)" in templates)
    check("B4 UpgradeSpec / fingerprint / 释放记录删除（反断言）",
          "UpgradeSpec" not in templates and "fingerprint" not in templates
          and "RELEASE_RECORD" not in templates.replace("LEGACY_RELEASE_RECORD_FILE", "")
          and "shouldUpgrade" not in templates)
    check("B5 旧 .release-record.json 启动清理",
          "LEGACY_RELEASE_RECORD_FILE" in templates and "$LEGACY_RELEASE_RECORD_FILE.tmp" in templates)
    for module in ("server-Spigot", "server-NeoForge", "server-Fabric"):
        text = src(f"{module}/src/main/resources/config.yml")
        check(f"B6 {module} config keep-files 键 + 注释",
              "keep-files: []" in text and "白名单" in text)
    for label, jar in JARS.items():
        cfg = read_entry(jar, "config.yml").decode("utf-8")
        check(f"B6b {label} JAR 内嵌 config keep-files", "keep-files: []" in cfg)
    check("B7 DEFAULT_VALUES messages.keep-files + cv61",
          'put("messages.keep-files", emptyList<Any>())' in cfgmgr
          and "private const val CURRENT_CONFIG_VERSION = 61" in cfgmgr)
    for label, jar in JARS.items():
        text = javap_text(jar, "cn/kerong/penguin/spigot/qqbind/MessageTemplates")
        check(f"B8 {label} MessageTemplates 字节码 keepFileNames + 清理常量",
              "keepFileNames" in text and ".release-record.json" in text)

    print("== C 组：globalconfig 补漏迁移 ==")
    check("C1 MOVE_FILES 含三个状态散文件",
          '"cluster-selection.yml"' in layout and '"command-state.ini"' in layout
          and '"command-state.properties"' in layout
          and '"server-notify-state.properties"' in layout)
    check("C2 ClusterState 改接 DataLayout.resolve",
          "DataLayout.resolve(configDir, SELECTION_FILE)" in cluster)
    check("C3 HumanReadableStateFile 改接 resolve（ini + 旧 properties）",
          "DataLayout.resolve(dataDirectory, \"command-state.ini\")" in hrstate
          and "DataLayout.resolve(dataDirectory, \"command-state.properties\")" in hrstate)
    check("C4 ServerNotifyManager 改接 resolve",
          "DataLayout.resolve(configDir, STATE_FILE_NAME)" in notify)
    for label, jar in JARS.items():
        t1 = javap_text(jar, "cn/kerong/bot/ClusterState")
        t2 = javap_text(jar, "cn/kerong/bot/state/HumanReadableStateFile")
        t3 = javap_text(jar, "cn/kerong/bot/ServerNotifyManager")
        check(f"C5 {label} 三类字节码 resolve 调用在位",
              "resolve" in t1 and "resolve" in t2 and "resolve" in t3)
    check("C6 教程目录树补三状态文件行",
          "cluster-selection.yml" in src("website/tutorial.html")
          and "server-notify-state.properties" in src("website/tutorial.html"))

    print("== D 组：死亡播报 ==")
    check("D1 出口拼玩家名（不含名拼上 / 已含名原样 / null 兜底）",
          'if (name in it) it else "$name $it"' in cfgprov
          and '?: "$name 死了"' in cfgprov)
    check("D2 Spigot 插件自定义文案优先（customMessageOf / looksVanilla）",
          "customMessageOf" in dm_spigot and "looksVanilla" in dm_spigot
          and "VANILLA_DEATH_PHRASES" in dm_spigot)
    check("D3 Spigot 补齐分支",
          all(k in dm_spigot for k in
              ("FREEZE", "CRAMMING", "DRYOUT", "DRAGON_BREATH", "SONIC_BOOM",
               "SUICIDE", "PROJECTILE", "KILL")))
    check("D4 NeoForge localizedFallback + 补齐分支",
          "localizedFallback" in dm_neo and "sweetBerryBush" in dm_neo
          and "fallingStalactite" in dm_neo and "stalagmite" in dm_neo)
    check("D5 Fabric 同步（localizedFallback + 补齐分支）",
          "localizedFallback" in dm_fab and "sweetBerryBush" in dm_fab
          and "fallingStalactite" in dm_fab and "stalagmite" in dm_fab)
    for module in ("server-Spigot", "server-NeoForge", "server-Fabric"):
        text = src(f"{module}/src/main/resources/config.yml")
        check(f"D6 {module} config {{message}} 自动带玩家名注释",
              "{message} = 玩家名 + 中文死亡描述" in text
              or "自动带上玩家" in text)
    for label, jar in JARS.items():
        text = javap_text(jar, "cn/kerong/bot/provider/ConfigProvider")
        # "$name $it" / "$name 死了" 编译为 makeConcatWithConstants（indy），
        # 字符串常量不进 ldc——断言两处拼接调用 + contains（已含名检查）+ 占位符
        ok = (text.count("makeConcatWithConstants") >= 2
              and "contains$default" in text
              and "{message}" in text and "{killer}" in text and "未知" in text)
        check(f"D7 {label} ConfigProvider 字节码出口拼名（两处 concat + contains）", ok)

    print("== V 组：版本与官网 ==")
    v_checks = [
        ("build.gradle.kts", 'version = "1.7.6.4"'),
        ("server-Spigot/src/main/resources/plugin.yml", "version: '1.7.6.4'"),
        ("server-NeoForge/src/main/resources/META-INF/mods.toml", "1.7.6.4"),
        ("server-NeoForge/src/main/resources/META-INF/neoforge.mods.toml", "1.7.6.4"),
        ("server-Fabric/src/main/resources/fabric.mod.json", '"version": "1.7.6.4"'),
        ("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt",
         'MOD_VERSION = "1.7.6.4"'),
    ]
    for rel, needle in v_checks:
        check(f"V1 {rel} 1.7.6.4", needle in src(rel))
    for module in ("server-Spigot", "server-NeoForge", "server-Fabric"):
        check(f"V2 {module} config-version 61",
              re.search(r"config-version:\s*61", src(f"{module}/src/main/resources/config.yml")) is not None)
    for label, jar in JARS.items():
        cfg = read_entry(jar, "config.yml").decode("utf-8")
        check(f"V2b {label} JAR config-version 61",
              re.search(r"config-version:\s*61", cfg) is not None)
    check("V3 version.json 1.7.6.4 / changelog 7 条",
          version_json["version"] == "1.7.6.4" and len(version_json["changelog"]) == 7)
    index_html = src("website/index.html")
    check("V4 index 1.7.6.4 块 + 历史表已删 1.7.6 之前版本",
          "版本 1.7.6.4" in index_html
          and "KERONGPenguin_Spigot-1.7.6.4.jar" in index_html
          and "1.7.5.9" not in index_html and "1.7.5.2" not in index_html
          and "1.7.4.1" not in index_html)
    readme = src("README.md")
    check("V5 README 覆盖轮条目", "1.7.6.4" in readme)

    print("== R 组：回归 ==")
    spigot_cfg = src("server-Spigot/src/main/resources/config.yml")
    m = re.search(r"crawler:\s*\n(?:\s*#[^\n]*\n)+\s*enabled:\s*(\w+)", spigot_cfg)
    check("R1 AI 搜索默认关保持", m is not None and m.group(1) == "false")
    check("R2 爬取开关补行机制保持（syncCrawlerGuidance + reload 钩子）",
          "fun syncCrawlerGuidance" in prompt and "syncCrawlerGuidance(" in kerong)
    dist_files = sorted(os.listdir(DIST))
    legacy = [f for f in dist_files if re.search(r"1\.7\.[0-5](\.\d+)?[-.]", f)]
    check("R3 build/dist 无 1.7.6 之前版本产物", legacy == [] and "KERONGPenguin_Spigot-1.7.6.4.jar" in dist_files)

    print()
    print(f"结果: {pass_n} 通过, {fail_n} 失败")
    if fails:
        print("失败项:")
        for f in fails:
            print("  - " + f)
        sys.exit(1)


if __name__ == "__main__":
    main()
