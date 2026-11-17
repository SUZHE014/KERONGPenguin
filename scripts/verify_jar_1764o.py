#!/usr/bin/env python3
"""1.7.6.4 覆盖轮门禁（五项：游戏内 AI 消息不吞 + 表情文案 + 死亡播报三项）。

A 组  游戏内 AI 触发消息不再被吞（用户口径「修复游戏内发送唤醒AI消息，
      玩家发送的消息会直接吞掉不在聊天栏显示的问题（AI正常回复）」）：
  A1  Spigot GameChat AI 分支不再取消事件（源码 isCancelled 反断言）
  A2  NeoForgeEvents 同（isCanceled 反断言）
  A3  Fabric handleAllowChat 恒放行（return true）+ serverAiTriggerContent 纯判定
  A4  Fabric CHAT_MESSAGE 阶段 AI 触发消息补拦（不转发 QQ 群）
  A5  Spigot JAR GameChat 字节码 setCancelled 反断言
  A6  NeoForge JAR 字节码 setCanceled 反断言
  A7  Fabric 双 JAR 字节码 serverAiTriggerContent
B 组  表情兜底文案（用户口径「把AI在游戏内发送不了表情的文案改为
      “只想发个表情，可奈何游戏不允许”」）：
  B1  AiChat.chat 源码新文案 + 旧文案反断言
  B2  四端 JAR 字节码字符串常量
  B3  群聊侧兜底文案不变（GroupMessageHandler「AI 想发个表情包，但图片发送失败了」）
C 组  死亡播报昵称与死因分开（用户口径「修改死亡播报玩家昵称和死亡原因
      分开（配置记得升级修改）」+「玩家昵称和死亡原因中间有一个空格」）：
  C1  PlayerEventFormat deathFormat 新默认 + deathCustomCauses 字段
  C2  ConfigProvider 默认 playerEventFormat 同步
  C3  deathCause 纯函数（剥开头名 / 缺失回退「死了」）
  C4  出口不再自动拼名（旧实现 "$name $it" 反断言）
  C5  ConfigManager 读键新默认（format / custom-causes）
  C6  三端 config.yml 新文案 + custom-causes 键 + 注释
  C7  DEFAULT_VALUES 死亡三键补入
  C8  升级收敛双保险（ConfigFileRebuilder + reload 同款收敛块）
  C9  config-version 62（ConfigManager + 三端源 + 四端 JAR 内嵌 config）
  C10 四端 JAR PlayerEventFormat 构造器含 boolean 第 7 参 + ConfigProvider deathCause
D 组  自定义死因开关（用户口径「死亡播报添加一个配置开关（默认开），当
      关闭时，死亡原因只展示插件内置的原版死亡原因，其他死亡原因统一
      显示“死了”」）：
  D1  Spigot describe(event, customCauses) @JvmOverloads + 关闭跳过 customMessageOf
  D2  NeoForge describe 同 + 关闭跳过 localizedFallback
  D3  Fabric describe 同
  D4  三端监听器接线传参（PlayerEventsListener(plugin) 注册 + 两模组端
      plugin.playerEventFormat().deathCustomCauses）
  D5  击杀者拼名分支两态保留（内置口径不受开关影响）
  D6  四端 JAR 字节码 describe 双参 / 三参签名
V 组  版本与官网：
  V1  version.json 1.7.6.4 / changelog 12 条（⑧~⑫ 本轮五条）
  V2  index.html v1.7.6.4 块覆盖轮五条 li
  V3  tutorial 死亡播报段新文案 + custom-causes
  V4  README 覆盖轮任务 3+5 / 任务 4 条目
  V5  版本号六处保持 1.7.6.4（覆盖轮不改版本号）
R 组  回归（首发轮关键机制 + 产物口径）：
  R1  keep-files 模板白名单保持；R2 状态文件迁移保持
  R3  技能段随插件更新刷新保持；R4 首发轮死因表与自定义文案优先保持
  R5  build/dist 无 1.7.6 之前版本产物
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
    gamechat = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/events/GameChat.kt")
    neoevents = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/NeoForgeEvents.kt")
    fabevents = src("server-Fabric/src/main/kotlin/cn/kerong/penguin/fabric/FabricEvents.kt")
    aichat = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/AiChat.kt")
    gmh = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/GroupMessageHandler.kt")
    cfgprov = src("common-Bot/src/main/kotlin/cn/kerong/bot/provider/ConfigProvider.kt")
    cfgmgr = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
    rebuilder = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.kt")
    dm_spigot = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/events/DeathMessage.kt")
    dm_neo = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/DeathMessageNeo.kt")
    dm_fab = src("server-Fabric/src/main/kotlin/cn/kerong/penguin/fabric/DeathMessageFabric.kt")
    pel = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/events/PlayerEventsListener.kt")
    kerong_spigot = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/KERONGSpigot.kt")

    print("== A 组：游戏内 AI 触发消息不再被吞 ==")
    # 非注释行里不得出现取消调用（注释里引用旧代码字符串属正常留档）
    active_cancel_spigot = [l for l in gamechat.splitlines()
                            if "isCancelled" in l and not l.strip().startswith("//")]
    check("A1 Spigot AI 分支不再取消事件（非注释行无 isCancelled）",
          not active_cancel_spigot and "不再取消聊天事件" in gamechat)
    active_cancel_neo = [l for l in neoevents.splitlines()
                         if "isCanceled" in l and not l.strip().startswith("//")]
    check("A2 NeoForge 同（非注释行无 isCanceled）",
          not active_cancel_neo and "不再取消聊天事件" in neoevents)
    check("A3 Fabric 恒放行 + serverAiTriggerContent 纯判定",
          "private fun serverAiTriggerContent(message: String): String?" in fabevents
          and "val content = serverAiTriggerContent(message) ?: return true" in fabevents
          and "return true" in fabevents.split("private fun handleAllowChat")[1].split("private fun serverAiTriggerContent")[0])
    check("A4 Fabric CHAT 阶段 AI 触发补拦（不转发 QQ 群）",
          "if (serverAiTriggerContent(message.signedContent()) == null)" in fabevents
          and "QClient.broadcastGameMessage" in fabevents)
    text = javap_text(JARS["Spigot"], "cn/kerong/penguin/spigot/events/GameChat")
    check("A5 Spigot JAR GameChat 字节码无 setCancelled", "setCancelled" not in text)
    text = javap_text(JARS["NeoForge"], "cn/kerong/penguin/neoforge/NeoForgeEvents")
    check("A6 NeoForge JAR 字节码无 setCanceled", "setCanceled" not in text)
    for key in ("Fabric-1204", "Fabric-1201"):
        text = javap_text(JARS[key], "cn/kerong/penguin/fabric/FabricEvents")
        check(f"A7 {key} JAR 字节码 serverAiTriggerContent", "serverAiTriggerContent" in text)

    print("== B 组：表情兜底文案 ==")
    check("B1 AiChat.chat 游戏内兜底文案（新文案在 / 旧文案删）",
          '.text.ifBlank { "（只想发个表情，可奈何游戏不允许）" }' in aichat
          and "AI 本次只想发个表情包，游戏内无法显示图片" not in aichat)
    for label, jar in JARS.items():
        text = javap_text(jar, "cn/kerong/penguin/spigot/qqbind/AiChat")
        check(f"B2 {label} JAR 字节码新文案常量", "只想发个表情，可奈何游戏不允许" in text)
    check("B3 群聊侧兜底文案不变",
          "AI 想发个表情包，但图片发送失败了" in gmh)

    print("== C 组：死亡播报昵称与死因分开 ==")
    check("C1 PlayerEventFormat 新默认 + deathCustomCauses 字段",
          'val deathFormat: String = "[游戏] {name} {message}"' in cfgprov
          and "val deathCustomCauses: Boolean = true" in cfgprov)
    check("C2 ConfigProvider 默认 playerEventFormat 同步",
          'deathFormat = "[游戏] {name} {message}"' in cfgprov
          and "deathCustomCauses = true" in cfgprov)
    check("C3 deathCause 纯函数（剥开头名 / 回退「死了」）",
          "private fun deathCause(name: String, deathMessage: String?): String" in cfgprov
          and "text.startsWith(name, ignoreCase = true)" in cfgprov
          and 'ifEmpty { "死了" }' in cfgprov
          and "if (deathMessage.isNullOrBlank()) return \"死了\"" in cfgprov)
    check("C4 出口不再自动拼名（旧实现反断言）",
          'if (name in it) it else "$name $it"' not in cfgprov
          and '"$name 死了"' not in cfgprov
          and "昵称与死亡原因**分开**" in cfgprov)
    check("C5 ConfigManager 读键新默认",
          '"[游戏] {name} {message}"' in cfgmgr
          and 'getBoolean("player-events.death.custom-causes", true)' in cfgmgr)
    for module in ("server-Spigot", "server-NeoForge", "server-Fabric"):
        text = src(f"{module}/src/main/resources/config.yml")
        check(f"C6 {module} config 新文案 + custom-causes 键 + 注释",
              'format: "[游戏] {name} {message}"' in text
              and "custom-causes: true" in text
              and "只展示插件内置的原版死亡原因" in text
              and "中间一个空格" in text)
    check("C7 DEFAULT_VALUES 死亡三键补入",
          '"player-events.death.enabled" to false' in cfgmgr
          and '"player-events.death.format" to "[游戏] {name} {message}"' in cfgmgr
          and '"player-events.death.custom-causes" to true' in cfgmgr)
    check("C8 升级收敛双保险（Rebuilder + reload）",
          'disk.getString("player-events.death.format") == "[游戏] {message}"' in rebuilder
          and '"[游戏] {message}" == config.getString("player-events.death.format")' in cfgmgr
          and "oldVersion in 1 until 62" in cfgmgr)
    check("C9a ConfigManager cv62", "private const val CURRENT_CONFIG_VERSION = 62" in cfgmgr)
    for module in ("server-Spigot", "server-NeoForge", "server-Fabric"):
        check(f"C9b {module} config-version 62",
              re.search(r"config-version:\s*62", src(f"{module}/src/main/resources/config.yml")) is not None)
    for label, jar in JARS.items():
        cfg = read_entry(jar, "config.yml").decode("utf-8")
        check(f"C9c {label} JAR 内嵌 config cv62 + 新文案",
              re.search(r"config-version:\s*62", cfg) is not None
              and 'format: "[游戏] {name} {message}"' in cfg
              and "custom-causes: true" in cfg)
    for label, jar in JARS.items():
        pef = javap_text(jar, "cn/kerong/bot/provider/PlayerEventFormat", extra=("-p",))
        cp = javap_text(jar, "cn/kerong/bot/provider/ConfigProvider")
        # 构造器末位 boolean（deathCustomCauses）+ deathCause 私有方法 + 「死了」常量
        ctor = re.search(r"PlayerEventFormat\(([^)]*)\)", pef)
        ok_ctor = ctor is not None and ctor.group(1).strip().endswith("boolean")
        check(f"C10 {label} JAR PlayerEventFormat 第 7 参 boolean + deathCause 字节码",
              ok_ctor and "deathCause" in cp and "死了" in cp)

    print("== D 组：自定义死因开关 ==")
    check("D1 Spigot describe(customCauses) + 关闭跳过 customMessageOf",
          "@JvmOverloads" in dm_spigot
          and "fun describe(event: PlayerDeathEvent, customCauses: Boolean = true): String" in dm_spigot
          and "if (customCauses) customMessageOf(event)?.let { return it }" in dm_spigot)
    check("D2 NeoForge describe(customCauses) + 关闭跳过 localizedFallback",
          "fun describe(event: LivingDeathEvent, customCauses: Boolean = true): String" in dm_neo
          and "if (customCauses) {" in dm_neo
          and "localizedFallback(event.source, event.entity)?.let { return it }" in dm_neo)
    check("D3 Fabric describe(customCauses) 同",
          "fun describe(entity: LivingEntity, source: DamageSource, customCauses: Boolean = true): String" in dm_fab
          and "localizedFallback(source, entity)?.let { return it }" in dm_fab
          and "customCauses: Boolean = true" in dm_fab)
    check("D4 三端监听器接线传参",
          "class PlayerEventsListener(private val plugin: KERONGSpigot) : Listener" in pel
          and "DeathMessage.describe(event, format.deathCustomCauses)" in pel
          and "PlayerEventsListener(this)" in kerong_spigot
          and "DeathMessageNeo.describe(event, plugin.playerEventFormat().deathCustomCauses)" in neoevents
          and "DeathMessageFabric.describe(entity, source, plugin.playerEventFormat().deathCustomCauses)" in fabevents)
    check("D5 击杀者拼名分支两态保留（内置口径）",
          'if (killer != null) return "被${killer}杀死了"' in dm_spigot
          and 'if (killer != null) return "被${killer}杀死了"' in dm_neo
          and 'if (killer != null) return "被${killer}杀死了"' in dm_fab)
    text = javap_text(JARS["Spigot"], "cn/kerong/penguin/spigot/events/DeathMessage", extra=("-p",))
    check("D6a Spigot JAR describe(PlayerDeathEvent, boolean) 签名",
          "describe(org.bukkit.event.entity.PlayerDeathEvent, boolean)" in text)
    text = javap_text(JARS["NeoForge"], "cn/kerong/penguin/neoforge/DeathMessageNeo", extra=("-p",))
    check("D6b NeoForge JAR describe(LivingDeathEvent, boolean) 签名",
          re.search(r"describe\(net\.neoforged[^(]*LivingDeathEvent, boolean\)", text) is not None)
    for key in ("Fabric-1204", "Fabric-1201"):
        text = javap_text(JARS[key], "cn/kerong/penguin/fabric/DeathMessageFabric", extra=("-p",))
        check(f"D6c {key} JAR describe(LivingEntity, DamageSource, boolean) 签名",
              re.search(r"describe\(net\.minecraft[^)]*, net\.minecraft[^)]*, boolean\)", text) is not None)

    print("== V 组：版本与官网 ==")
    version_json = json.loads(src("website/version.json"))
    check("V1 version.json 1.7.6.4 / changelog 12 条",
          version_json["version"] == "1.7.6.4" and len(version_json["changelog"]) == 12
          and any("玩家昵称与死亡原因分开" in c for c in version_json["changelog"])
          and any("custom-causes" in c for c in version_json["changelog"]))
    index_html = src("website/index.html")
    check("V2 index 覆盖轮五条 li",
          "玩家昵称与死亡原因分开" in index_html
          and "custom-causes" in index_html
          and "只想发个表情，可奈何游戏不允许" in index_html
          and "玩家消息不再被吞" in index_html
          and "61 → 62" in index_html)
    tutorial = src("website/tutorial.html")
    check("V3 tutorial 死亡播报段新文案",
          'format: "[游戏] {name} {message}"' in tutorial
          and "custom-causes: true" in tutorial
          and "1.7.6.4 覆盖轮起与玩家名分开" in tutorial)
    readme = src("README.md")
    check("V4 README 覆盖轮任务 3+5 / 任务 4 条目",
          "死亡播报昵称与死因分开" in readme
          and "deathCustomCauses" in readme
          and "player-events.death.custom-causes" in readme)
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
        check(f"V5 {rel} 1.7.6.4 保持", needle in src(rel))

    print("== R 组：回归 ==")
    spigot_cfg = src("server-Spigot/src/main/resources/config.yml")
    check("R1 keep-files 模板白名单保持",
          "keep-files: []" in spigot_cfg
          and 'put("messages.keep-files", emptyList<Any>())' in cfgmgr)
    layout = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/manager/DataLayout.kt")
    check("R2 状态文件迁移保持",
          '"cluster-selection.yml"' in layout and '"command-state.ini"' in layout
          and '"server-notify-state.properties"' in layout)
    qbm = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/QqBindManager.kt")
    prompt = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/AiPromptFile.kt")
    check("R3 技能段随插件更新刷新保持",
          "syncSkillSection" in qbm and "fun syncSkillSection" in prompt)
    check("R4 首发轮死因表与自定义文案优先保持",
          "VANILLA_DEATH_PHRASES" in dm_spigot and "customMessageOf" in dm_spigot
          and "sweetBerryBush" in dm_neo and "sweetBerryBush" in dm_fab
          and "stalagmite" in dm_neo and "stalagmite" in dm_fab)
    dist_files = sorted(os.listdir(DIST))
    legacy = [f for f in dist_files if re.search(r"1\.7\.[0-5](\.\d+)?[-.]", f)]
    check("R5 build/dist 无 1.7.6 之前版本产物",
          legacy == [] and "KERONGPenguin_Spigot-1.7.6.4.jar" in dist_files)

    print()
    print(f"结果: {pass_n} 通过, {fail_n} 失败")
    if fails:
        print("失败项:")
        for f in fails:
            print("  - " + f)
        sys.exit(1)


if __name__ == "__main__":
    main()
