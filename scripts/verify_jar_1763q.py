#!/usr/bin/env python3
"""1.7.6.3 三次覆盖轮门禁（任务1 提示词技能段 / 任务2 纯表情包空白消息修复 / 附带错别字修复）。

A 组  提示词技能段（源码 + 字节码）：
  A1  AiPromptFile.defaultContent 含「# 技能」段
  A2  技能段含「遇到不会」+「先搜索再回答」引导
  A3  技能段列出发表情包 / 读网页与搜索两个技能
  A4  角色设定 / 专属知识段回归保留
B 组  动态技能段 capabilitySection：
  B1  新标题「## 系统技能（你拥有的技能如下」
  B2  爬虫段含「先搜索再回答，不要凭记忆猜测」+「不要直接说不知道」
  B3  旧标题「## 系统能力」不再出现
  B4  全关时不注入（skills.isEmpty() return ""）
C 组  纯表情包空白消息修复：
  C1  GroupMessageHandler 源码 textBlank 三分支（文本 / null 跳过 / 兜底说明）
  C2  发送块包在 if (aiContent != null) 内（文本不再无条件发送）
  C3  日志含「纯表情包跳过文本」
  C4  无图兜底文案「没有匹配到图片」
  C5  图片全失败补发文本（memeSentCount == 0 → 补发）
  C6  游戏内入口 AiChat.chat() ifBlank 兜底（游戏内无法显示图片）
  C7  三端 GameChat/NeoForgeEvents/FabricEvents 调用点回归（AiChat.chat(
D 组  错别字修复：
  D1  四端 JAR 全部插件类不再含「兑底」
  D2  源码 .kt 不再含「兑底」
E 组  config 注释：
  E1  三端 config.yml（源 + JAR 内嵌）提示词段含「遇到不会的可以搜索」
F 组  回归：
  F1  MEME_MARKER_REGEX / GET_URL_REGEX / stripReplyMarkers / parseMemeKeywords 常量与方法在位
  F2  exhaustedFeedback / buildPageFeedback 回归
  F3  版本号 1.7.6.3 与 config-version 60 保持
"""
import os
import re
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(REPO, "build", "dist")

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


def read_class(jar_path, entry):
    with zipfile.ZipFile(jar_path) as zf:
        return zf.read(entry)


def scan_own_bytes(jar_path, needle, prefix="cn/kerong/"):
    """单次打开 JAR，扫描全部插件类，返回含 needle 的条目列表。"""
    hits = []
    with zipfile.ZipFile(jar_path) as zf:
        for name in zf.namelist():
            if name.startswith(prefix) and name.endswith(".class"):
                if needle in zf.read(name):
                    hits.append(name)
    return hits


def main():
    aichat = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/AiChat.kt")
    prompt = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/AiPromptFile.kt")
    gmh = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/GroupMessageHandler.kt")

    print("== A 组：提示词技能段 ==")
    check("A1 defaultContent 含「# 技能（你拥有的技能」段标题",
          'append("\\n\\n# 技能（你拥有的技能，系统自动注入，不用手动维护）\\n\\n")' in prompt)
    check("A2 技能段含遇到不会先搜索引导",
          "遇到不会、没把握或时效性的问题" in prompt and "先搜索再回答" in prompt)
    check("A3 技能段列出两个技能（发表情包 / 读网页与搜索）",
          "[表情] 文件名" in prompt and "读网页与搜索" in prompt)
    check("A4 角色设定 / 专属知识段回归",
          "# 角色设定" in prompt and "# 专属知识" in prompt
          and "你是一个友好的游戏群助手，请简洁回答。" in prompt)

    print("== B 组：动态技能段 capabilitySection ==")
    check("B1 新标题「## 系统技能（你拥有的技能如下」",
          'append("\\n\\n## 系统技能（你拥有的技能如下，按需使用，不用刻意使用；")' in aichat)
    check("B2 爬虫段先搜索引导（不猜测 / 不说不知道）",
          "先搜索再回答，不要凭记忆猜测，也不要直接说不知道" in aichat)
    check("B3 旧标题「## 系统能力」不再出现", "## 系统能力" not in aichat)
    check("B4 全关不注入空段",
          'if (skills.isEmpty()) return ""' in aichat)
    check("B5 爬虫段保留原有协议说明（必应 / 多层 / 上限）",
          "bing.com/search?q=关键词" in aichat and "逐层阅读" in aichat
          and "不要再输出标记" in aichat)

    print("== C 组：纯表情包空白消息修复 ==")
    check("C1 textBlank 三分支判定",
          "val textBlank = reply.isBlank()" in gmh
          and "outcome.memeFiles.isNotEmpty() -> null" in gmh
          and "没有匹配到图片" in gmh)
    check("C2 文本发送包在 if (aiContent != null) 内",
          "if (aiContent != null) {" in gmh
          and gmh.find("if (aiContent != null) {") < gmh.find("GroupMsgSender.sendReply(event, outbound"))
    check("C3 日志含纯表情包跳过文本", "纯表情包跳过文本" in gmh)
    check("C4 无图兜底文案", "AI 想发个表情包，但没有匹配到图片（可检查表情包文件夹）" in gmh)
    check("C5 图片全失败补发文本",
          "var memeSentCount = 0" in gmh and "memeSentCount == 0" in gmh
          and "AI 想发个表情包，但图片发送失败了" in gmh)
    check("C6 游戏内入口 ifBlank 兜底",
          '.text.ifBlank { "（AI 本次只想发个表情包，游戏内无法显示图片）" }' in aichat)
    for rel, label in [
        ("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/events/GameChat.kt", "Spigot"),
        ("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/NeoForgeEvents.kt", "NeoForge"),
        ("server-Fabric/src/main/kotlin/cn/kerong/penguin/fabric/FabricEvents.kt", "Fabric"),
    ]:
        check(f"C7 游戏内调用点回归（{label}）", "AiChat.chat(content, manager" in src(rel))

    print("== D 组：错别字修复（兑底→兜底） ==")
    kt_bad = []
    for root, dirs, files in os.walk(REPO):
        if any(p in ("build", ".git", "scripts", "website") for p in root.split(os.sep)):
            continue
        for name in files:
            if name.endswith(".kt"):
                p = os.path.join(root, name)
                with open(p, encoding="utf-8") as f:
                    if "兑底" in f.read():
                        kt_bad.append(os.path.relpath(p, REPO))
    check("D2 源码 .kt 不再含「兑底」", not kt_bad, )
    if kt_bad:
        print("    残留:", kt_bad)
    for label, jar in JARS.items():
        bad = scan_own_bytes(jar, "兑底".encode())
        check(f"D1 {label} JAR 插件类无「兑底」", not bad)
        if bad:
            print("    残留:", bad[:8])

    print("== E 组：config 注释 ==")
    for module in ("server-Spigot", "server-NeoForge", "server-Fabric"):
        text = src(f"{module}/src/main/resources/config.yml")
        check(f"E1 {module} config 提示词注释含技能引导",
              "遇到不会的可以搜索" in text and "技能说明与专属知识" in text)
    for label, jar in JARS.items():
        cfg = read_class(jar, "config.yml").decode("utf-8")
        check(f"E1 {label} JAR 内嵌 config 含技能引导", "遇到不会的可以搜索" in cfg)

    print("== F 组：回归 ==")
    check("F1 标记协议常量与方法在位",
          "MEME_MARKER_REGEX" in aichat and "GET_URL_REGEX" in aichat
          and "fun stripReplyMarkers" in aichat and "fun parseMemeKeywords" in aichat
          and "fun resolveMemeFiles" in aichat)
    check("F2 exhaustedFeedback / buildPageFeedback 在位",
          "fun exhaustedFeedback" in aichat and "fun buildPageFeedback" in aichat)
    check("F3 版本号 1.7.6.3（plugin.yml）",
          "version: '1.7.6.3'" in read_class(JARS["Spigot"], "plugin.yml").decode("utf-8")
          or "version: 1.7.6.3" in read_class(JARS["Spigot"], "plugin.yml").decode("utf-8"))
    spigot_cfg = read_class(JARS["Spigot"], "config.yml").decode("utf-8")
    m = re.search(r"config-version:\s*(\d+)", spigot_cfg)
    check("F4 config-version 60 保持", m is not None and m.group(1) == "60",
          )

    # 字节码关键字符串（四端）
    aichat_entry = "cn/kerong/penguin/spigot/qqbind/AiChat.class"
    gmh_entry = "cn/kerong/bot/events/GroupMessageHandler.class"
    pf_entry = "cn/kerong/penguin/spigot/qqbind/AiPromptFile.class"
    for label, jar in JARS.items():
        ac = read_class(jar, aichat_entry)
        gc = read_class(jar, gmh_entry)
        pc = read_class(jar, pf_entry)
        check(f"Z1 {label} AiChat 字节含新技能标题",
              "## 系统技能（你拥有的技能如下".encode() in ac)
        check(f"Z2 {label} AiChat 字节含先搜索引导",
              "先搜索再回答，不要凭记忆猜测，也不要直接说不知道".encode() in ac)
        check(f"Z3 {label} AiChat 字节不含旧标题",
              "## 系统能力".encode() not in ac)
        check(f"Z4 {label} AiChat 字节含游戏内兜底",
              "游戏内无法显示图片".encode() in ac)
        check(f"Z5 {label} GMH 字节含纯表情包日志",
              "纯表情包跳过文本".encode() in gc)
        check(f"Z6 {label} GMH 字节含无图兜底",
              "没有匹配到图片".encode() in gc and "图片发送失败了".encode() in gc)
        check(f"Z7 {label} AiPromptFile 字节含技能段",
              "# 技能（你拥有的技能，系统自动注入，不用手动维护）".encode() in pc)

    print(f"\n===== {pass_n}/{pass_n + fail_n} =====")
    if fails:
        print("失败项:", fails)
    return 0 if fail_n == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
