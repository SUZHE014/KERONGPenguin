#!/usr/bin/env python3
"""1.7.4.1 门禁验证。

本轮一项修复：
① @其他用户 + 指令不再触发机器人回复——群里「@张三 /查在线」是成员间
   对话，旧版把消息里的 @ 占位片段无差别剥掉后照样命中命令。现在按 @ 对象
   分流：@机器人（三通道）→ 行为不变；只 @ 其他人 → 保留 @ 片段不剥，
   命令匹配自然失败，数字自动绑定 / 多玩家选择确认同样不命中。

验证范围（源码 + 双端 JAR 字节码 / 资源）：
A. 修复源码：分流判定（mentionsBot / mentionBarred）、条件剥离、
   dispatchCommand 守卫、注释口径；isMentionedBot 三通道保持
B. 字节码（javap -p -c 双 JAR 的 GroupMessageHandler.onGroupMessage）：
   isMentionedBot 调用 → ldc "<@" → contains → 条件跳转 →
   stripLeadingMentionPlaceholders 条件调用；dispatchCommand 调用前
   ifne 守卫跳转；BaseCommand 命令匹配正则 "<@!?[^>]+>" 未变
C. 版本号 1.7.4.1：源码五处（root/NF build.gradle.kts、plugin.yml、
   mods.toml、neoforge.mods.toml）+ 双 JAR 资源三处
D. 基线保持：config-version 39、三条开关消息文案（含颜文字码点）、
   restart-window 零出现（废弃清单一处除外）、条目 11870 / 目录 776、
   二维码延时 30s / 上限 3、副标题灰度 106、README 与官网 1.7.4.1 口径
E. QqText 行为保持：行首剥除（放行路径）方法与正则在位
"""
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
DIST = REPO / "build" / "dist"
SPIGOT_JAR = DIST / "KERONGPenguin_Spigot-1.7.4.1.jar"
NF_JAR = DIST / "KERONGPenguin_NeoForge-1.20.4-1.7.4.1.jar"
JAVAP = "/home/z/my-project/tooling/jdk21/bin/javap"

passed = []
failed = []

NEW_SHUTDOWN = "[状态]\n服务器已正常关闭 (╯︵╰,)"
NEW_RESTART = "[状态]\n服务器已开启！！(≧∇≦)/"
NEW_CRASH = "[状态]\n服务器....༽◺_◿༼"


def check(name, ok, detail=""):
    (passed if ok else failed).append(name)
    print(f"  {'✓' if ok else '✗'} {name}" + (f" — {detail}" if detail and not ok else ""))


def src(rel):
    return (REPO / rel).read_text(encoding="utf-8")


def jar_names(jar):
    with zipfile.ZipFile(jar) as zf:
        return zf.namelist()


def jar_read(jar, name):
    with zipfile.ZipFile(jar) as zf:
        return zf.read(name).decode("utf-8", errors="replace")


def class_has(jar, cls, needle):
    with zipfile.ZipFile(jar) as zf:
        return needle.encode("utf-8") in zf.read(cls)


def javap_onGroupMessage(jar, cls):
    """提取 onGroupMessage 方法的反汇编文本（javap -p -c）。"""
    out = subprocess.run(
        [JAVAP, "-p", "-c", "-cp", str(jar), cls],
        capture_output=True, text=True, check=True).stdout
    lines = out.splitlines()
    start = None
    for i, line in enumerate(lines):
        if "void onGroupMessage(" in line:
            start = i
            break
    if start is None:
        return None
    end = len(lines)
    for i in range(start + 1, len(lines)):
        if lines[i].startswith("  ") and ("(" in lines[i] and lines[i].rstrip().endswith(");")
                                          and (" void " in lines[i] or " boolean " in lines[i]
                                               or " String " in lines[i])) and i > start + 1:
            end = i
            break
    return "\n".join(lines[start:end])


gmh = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/GroupMessageHandler.kt")
qtext = src("common-Bot/src/main/kotlin/cn/kerong/bot/tools/QqText.kt")
basecmd = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/BaseCommand.kt")
cfg_s = src("server-Spigot/src/main/resources/config.yml")
cfg_n = src("server-NeoForge/src/main/resources/config.yml")
tutorial = src("website/tutorial.html")
index = src("website/index.html")
version = src("website/version.json")
readme = src("README.md")

for jar in (SPIGOT_JAR, NF_JAR):
    if not jar.exists():
        print(f"缺少产物：{jar}")
        sys.exit(2)

spigot_names = jar_names(SPIGOT_JAR)
nf_names = jar_names(NF_JAR)
jar_cfg_s = jar_read(SPIGOT_JAR, "config.yml")
jar_cfg_n = jar_read(NF_JAR, "config.yml")

print("== A. 修复源码（分流判定 + 守卫 + 三通道） ==")
check("分流判定在位（mentionsBot / mentionBarred）",
      'val mentionsBot = isMentionedBot(event)' in gmh
      and 'val mentionBarred = !mentionsBot && rawContent.contains("<@")' in gmh)
check("条件剥离在位（@他人保留片段不剥）",
      "if (mentionBarred) rawContent" in gmh
      and "else QqText.stripLeadingMentionPlaceholders(rawContent)" in gmh)
check("命令分发守卫在位（mentionBarred 跳过 dispatchCommand）",
      "if (!mentionBarred) {" in gmh and "if (dispatchCommand(event)) return" in gmh)
check("修复注释口径在位（1.7.4.1）",
      "1.7.4.1 修复：@其他用户的消息不触发命令" in gmh)
check("isMentionedBot 三通道保持（GROUP_AT 标记 / mentions bot / 文本片段）",
      "GroupAtTracker.isAtBot" in gmh and "member.bot == true" in gmh
      and 'text.contains("<@!$botSelfId>")' in gmh)
check("1.7.4 @机器人+指令特性口径保持",
      "1.7.4：@机器人 + 指令同样在此命中" in gmh)
check("BaseCommand 命令匹配剥除正则未变",
      'Regex("<@!?[^>]+>").replace(content, "").trim().trimStart(\'/\')' in basecmd)

print("== B. 字节码（双 JAR GroupMessageHandler 指令流） ==")
GMH_CLS = "cn.kerong.bot.events.GroupMessageHandler"
for label, jar in [("Spigot", SPIGOT_JAR), ("NeoForge", NF_JAR)]:
    code = javap_onGroupMessage(jar, GMH_CLS)
    check(f"{label} onGroupMessage 反汇编可提取", code is not None)
    if code is None:
        continue
    lines = [l.strip() for l in code.splitlines() if l.strip()]
    # 1) isMentionedBot 调用
    idx_mention = next((i for i, l in enumerate(lines)
                        if "Method isMentionedBot:" in l), None)
    check(f"{label} isMentionedBot 调用在位", idx_mention is not None)
    # 2) ldc "<@" 常量 + contains 调用（在 isMentionedBot 之后）
    idx_ldc = next((i for i, l in enumerate(lines)
                    if 'String <@' in l), None)
    idx_contains = next((i for i, l in enumerate(lines)
                         if "StringsKt.contains$default" in l), None)
    check(f"{label} ldc <@ 常量 + contains 在 isMentionedBot 之后",
          idx_ldc is not None and idx_contains is not None
          and idx_mention is not None and idx_mention < idx_ldc <= idx_contains)
    # 3) stripLeadingMentionPlaceholders 条件调用（contains 之后）
    idx_strip = next((i for i, l in enumerate(lines)
                      if "QqText.stripLeadingMentionPlaceholders" in l), None)
    check(f"{label} stripLeadingMentionPlaceholders 条件调用在位",
          idx_strip is not None and idx_strip > idx_contains)
    # 4) contains 与 strip 之间有条件跳转（分流）
    guard_between = any(("ifeq" in l or "ifne" in l) for l in lines[idx_contains:idx_strip])
    check(f"{label} contains → strip 之间存在条件跳转", guard_between)
    # 5) dispatchCommand 调用前有 ifne 守卫（跳过命令分发）
    idx_dispatch = next((i for i, l in enumerate(lines)
                         if "Method dispatchCommand:" in l), None)
    check(f"{label} dispatchCommand 调用在位", idx_dispatch is not None)
    if idx_dispatch is not None:
        window = lines[max(0, idx_dispatch - 3):idx_dispatch]
        check(f"{label} dispatchCommand 前有守卫跳转（ifne）",
              any("ifne" in l or "ifeq" in l for l in window))
    # 6) 字节级："<@" 字符串常量真实存在
    check(f"{label} GroupMessageHandler.class 字节含 <@ 常量",
          class_has(jar, "cn/kerong/bot/events/GroupMessageHandler.class", "<@"))
    check(f"{label} BaseCommand.class 字节含命令匹配正则常量",
          class_has(jar, "cn/kerong/bot/events/commands/BaseCommand.class", "<@!?[^>]+>"))

print("== C. 版本号 1.7.4.1（源码 + 双 JAR） ==")
check("root build.gradle.kts 1.7.4.1", 'version = "1.7.4.1"' in src("build.gradle.kts"))
check("NF build.gradle.kts 1.7.4.1", 'version = "1.7.4.1"' in src("server-NeoForge/build.gradle.kts"))
check("plugin.yml 1.7.4.1", "version: '1.7.4.1'" in src("server-Spigot/src/main/resources/plugin.yml"))
check("mods.toml 1.7.4.1", 'version = "1.7.4.1"' in src("server-NeoForge/src/main/resources/META-INF/mods.toml"))
check("neoforge.mods.toml 1.7.4.1", 'version = "1.7.4.1"' in src("server-NeoForge/src/main/resources/META-INF/neoforge.mods.toml"))
check("Spigot JAR plugin.yml 1.7.4.1", "version: '1.7.4.1'" in jar_read(SPIGOT_JAR, "plugin.yml"))
check("NeoForge JAR mods.toml 双声明 1.7.4.1",
      'version = "1.7.4.1"' in jar_read(NF_JAR, "META-INF/mods.toml")
      and 'version = "1.7.4.1"' in jar_read(NF_JAR, "META-INF/neoforge.mods.toml"))
check("JAR 资源无 1.7.4 裸版本残留（插件元数据）",
      "1.7.4'" not in jar_read(SPIGOT_JAR, "plugin.yml").replace("1.7.4.1'", ""))

print("== D. 基线保持（第七轮成果不回退） ==")
check("config-version 39（源码双端模板）",
      "config-version: 39" in cfg_s and "config-version: 39" in cfg_n)
check("config-version 39（双 JAR 资源）",
      "config-version: 39" in jar_cfg_s and "config-version: 39" in jar_cfg_n)
target = "༽◺_◿༼"
check("颜文字码点 = 0F3D 25FA 005F 25FF 0F3C",
      [hex(ord(c)) for c in target] == ["0xf3d", "0x25fa", "0x5f", "0x25ff", "0xf3c"])
check("意外关闭文案保持（双 JAR 模板）",
      f'message: "{NEW_CRASH.replace(chr(10), chr(92) + "n")}"' in jar_cfg_s
      and f'message: "{NEW_CRASH.replace(chr(10), chr(92) + "n")}"' in jar_cfg_n)
cm_cls = "cn/kerong/penguin/spigot/manager/ConfigManager.class"
check("ConfigManager 字节码三条文案保持",
      class_has(SPIGOT_JAR, cm_cls, NEW_SHUTDOWN)
      and class_has(SPIGOT_JAR, cm_cls, NEW_RESTART)
      and class_has(SPIGOT_JAR, cm_cls, NEW_CRASH))
cm_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
check("restart-window 仅版本历史注释 + 废弃清单两处（源码）",
      cm_src.count("restart-window") == 2 and "REMOVED_CONFIG_KEYS" in cm_src
      and "server-notify.restart-window" in cm_src)
for label, jar in [("Spigot", SPIGOT_JAR), ("NeoForge", NF_JAR)]:
    hits = []
    with zipfile.ZipFile(jar) as zf:
        for n in zf.namelist():
            if n.endswith((".yml", ".toml", ".md")):
                if b"restart-window" in zf.read(n):
                    hits.append(n)
    check(f"{label} JAR restart-window 零出现", not hits, str(hits))
check("Spigot JAR 条目 11870（与 1.7.4 零增删）", len(spigot_names) == 11870,
      str(len(spigot_names)))
check("Spigot JAR 目录条目 776 基线",
      len([n for n in spigot_names if n.endswith("/")]) == 776,
      str(len([n for n in spigot_names if n.endswith("/")])))
qrm = src("common-Bot/src/main/kotlin/cn/kerong/bot/QrLoginManager.kt")
check("二维码延时 30 秒 / 上限 3 保持",
      "STARTUP_DELAY_MS = 30_000L" in qrm and "MAX_REFRESH_COUNT = 3" in qrm)
rs = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/RenderSettings.kt")
check("副标题灰度 106 保持", "DEFAULT_SUBTITLE_CARD_GRAY = 106" in rs)

print("== E. README 与官网口径（1.7.4.1 条目） ==")
check("README 当前版本 1.7.4.1", "当前版本：**1.7.4.1**" in readme)
check("README 1.7.4.1 摘要在位", "1.7.4.1：修复群里「@QQ用户 /查在线」" in readme)
check("README 1.7.4.1 详史段在位", "### v1.7.4.1 更新日志" in readme
      and "### v1.7.4 更新日志" in readme)
check("version.json 1.7.4.1", '"version": "1.7.4.1"' in version)
check("version.json changelog 置顶 1.7.4.1",
      '"1.7.4.1：' in version.split('"changelog"')[1][:400])
check("index 主下载框 1.7.4.1", "版本 1.7.4.1</p>" in index and "发布于 2026-11-09" in index)
check("index 新 JAR 链接两处",
      "KERONGPenguin_Spigot-1.7.4.1.jar" in index
      and "KERONGPenguin_NeoForge-1.20.4-1.7.4.1.jar" in index)
check("index 历史表 1.7.4.1 两行置顶",
      index.count("1.7.4.1</td><td>2026-11-09") == 2)
check("index 更新日志 v1.7.4.1 段在位", "<h3>v1.7.4.1</h3>" in index)
check("tutorial 未改动（本轮无命令用法变化）",
      "查在线" in tutorial and "重启时间窗口" not in tutorial)

print("== F. QqText 行为保持（放行路径） ==")
check("行首剥除方法在位", "fun stripLeadingMentionPlaceholders" in qtext
      and 'Regex("""^(?:\\s*<@!?[A-Za-z0-9_-]+>)+\\s*""")' in qtext)
check("全文剥除方法在位（AI 路径）", "fun removeMentionPlaceholders" in qtext)
check("QqText.class 双 JAR 在位",
      "cn/kerong/bot/tools/QqText.class" in spigot_names
      and "cn/kerong/bot/tools/QqText.class" in nf_names)

print(f"\n===== 结果：{len(passed)} 通过 / {len(failed)} 失败 =====")
if failed:
    print("失败项：")
    for name in failed:
        print("  ✗", name)
    sys.exit(1)
