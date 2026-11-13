#!/usr/bin/env python3
"""1.7.5.5 AI 覆盖轮门禁：三端分发 JAR 字节码核查。

A. 版本与资源（plugin.yml / mods.toml 双文件 / fabric.mod.json / 三份 config.yml）
B. 新功能（AiAt 类 + 各接线点常量池字符串 + 方法引用）
C. 回归（1.7.5.4 主线特征：验身标记 / acmd 前缀 / 全局按钮 / 排版键）
D. 形态（fat JAR 目录条目 / NF 重定位 kotlin / 类计数）

用法：python3 scripts/verify_jar_1755ai.py
"""
import re
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
DIST = REPO / "build" / "dist"
SPIGOT = DIST / "KERONGPenguin_Spigot-1.7.5.5.jar"
NEOFORGE = DIST / "KERONGPenguin_NeoForge-1.20.4-1.7.5.5.jar"
FABRIC = DIST / "KERONGPenguin_Fabric-1.20.4-1.7.5.5.jar"

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))


def class_bytes(zf, inner):
    return zf.read(inner)


def main():
    jars = {"Spigot": SPIGOT, "NeoForge": NEOFORGE, "Fabric": FABRIC}
    for label, jar in jars.items():
        check(f"0.{label} 分发 JAR 存在", jar.is_file(), str(jar))
        if not jar.is_file():
            continue
        with zipfile.ZipFile(jar) as zf:
            names = zf.namelist()
            # ---------- A. 版本与资源 ----------
            if label == "Spigot":
                py = class_bytes(zf, "plugin.yml").decode("utf-8", "replace")
                check("A1 Spigot plugin.yml 版本 1.7.5.5",
                      re.search(r"version:\s*'?\"?1\.7\.5\.5", py) is not None)
            if label == "NeoForge":
                for mfile in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
                    if mfile in names:
                        mt = class_bytes(zf, mfile).decode("utf-8", "replace")
                        check(f"A2 NF {mfile} 含 1.7.5.5", "1.7.5.5" in mt)
                    else:
                        check(f"A2 NF {mfile} 缺失", False)
            if label == "Fabric":
                fm = class_bytes(zf, "fabric.mod.json").decode("utf-8", "replace")
                check("A3 Fabric fabric.mod.json 版本 1.7.5.5", '"version": "1.7.5.5"' in fm or
                      re.search(r'"version":\s*"1\.7\.5\.5"', fm) is not None)
            cfg = class_bytes(zf, "config.yml").decode("utf-8", "replace")
            check(f"A4 {label} config.yml 含 ai.at-enabled 默认开",
                  re.search(r"at-enabled:\s*true", cfg) is not None)
            check(f"A5 {label} config.yml 含 ai.reply-quote 默认开",
                  re.search(r"reply-quote:\s*true", cfg) is not None)
            check(f"A6 {label} config.yml config-version 46",
                  re.search(r"config-version:\s*46", cfg) is not None)
            check(f"A7 {label} config.yml 两键注释齐（艾特接口/引用）",
                  "AI 艾特接口" in cfg and "是否引用消息" in cfg)

            # ---------- B. 新功能 ----------
            ai_at = "cn/kerong/penguin/spigot/qqbind/AiAt.class"
            check(f"B1 {label} AiAt 类存在", ai_at in names)
            if ai_at in names:
                b = class_bytes(zf, ai_at)
                check(f"B2 {label} AiAt 含 [at: 标记正则", b"[at:" in b)
                check(f"B3 {label} AiAt 含 <@ 占位正则", b"<@" in b)
                check(f"B4 {label} AiAt 含接口附注中文（确有需要）", "确有需要".encode() in b)
                check(f"B5 {label} AiAt 含 lookupOpenIdByNickname 引用", b"lookupOpenIdByNickname" in b)
                check(f"B6 {label} AiAt 含 lookupNickname 引用", b"lookupNickname" in b)

            gmh = "cn/kerong/bot/events/GroupMessageHandler.class"
            check(f"B7 {label} GroupMessageHandler 存在", gmh in names)
            if gmh in names:
                b = class_bytes(zf, gmh)
                check(f"B8 {label} Handler 接线 isAiAtEnabled", b"isAiAtEnabled" in b)
                check(f"B9 {label} Handler 接线 isAiReplyQuote", b"isAiReplyQuote" in b)
                check(f"B10 {label} Handler 接线 parseIncomingMentions", b"parseIncomingMentions" in b)
                check(f"B11 {label} Handler 接线 renderOutgoing", b"renderOutgoing" in b)
                check(f"B12 {label} Handler 保留旧路径 removeMentionPlaceholders",
                      b"removeMentionPlaceholders" in b)

            aichat = "cn/kerong/penguin/spigot/qqbind/AiChat.class"
            check(f"B13 {label} AiChat 存在", aichat in names)
            if aichat in names:
                b = class_bytes(zf, aichat)
                check(f"B14 {label} AiChat 接线 isAiAtEnabled", b"isAiAtEnabled" in b)
                check(f"B15 {label} AiChat 接线 systemPromptSuffix", b"systemPromptSuffix" in b)

            qqm = "cn/kerong/penguin/spigot/qqbind/QqBindManager.class"
            if qqm in names:
                b = class_bytes(zf, qqm)
                check(f"B16 {label} QqBindManager 含 ai.at-enabled 键", b"ai.at-enabled" in b)
                check(f"B17 {label} QqBindManager 含 ai.reply-quote 键", b"ai.reply-quote" in b)
            else:
                check(f"B16/B17 {label} QqBindManager 缺失", False)

            odd = "cn/kerong/penguin/spigot/qqbind/OpenIdDirectory.class"
            if odd in names:
                b = class_bytes(zf, odd)
                check(f"B18 {label} OpenIdDirectory 含 lookupOpenIdByNickname", b"lookupOpenIdByNickname" in b)
                check(f"B19 {label} OpenIdDirectory 含 reverseDirectory 字段", b"reverseDirectory" in b)
            else:
                check(f"B18/B19 {label} OpenIdDirectory 缺失", False)

            gms = "cn/kerong/penguin/spigot/qqbind/GroupMsgSender.class"
            if gms in names:
                b = class_bytes(zf, gms)
                check(f"B20 {label} GroupMsgSender 含 replyMarkdownWithReference 引用",
                      b"replyMarkdownWithReference" in b)
                check(f"B21 {label} GroupMsgSender 含 replyMarkdown 引用", b"replyMarkdown" in b)
            else:
                check(f"B20/B21 {label} GroupMsgSender 缺失", False)

            cm = "cn/kerong/penguin/spigot/manager/ConfigManager.class"
            if cm in names:
                b = class_bytes(zf, cm)
                check(f"B22 {label} ConfigManager 含 ai.at-enabled 默认键", b"ai.at-enabled" in b)
                check(f"B23 {label} ConfigManager 含 ai.reply-quote 默认键", b"ai.reply-quote" in b)
            else:
                # NF/Fabric 端 ConfigManager 同包名，同应存在
                check(f"B22/B23 {label} ConfigManager 缺失", False)

            # ---------- C. 回归（1.7.5.4 主线） ----------
            cs = "cn/kerong/bot/events/commands/CommandSupport.class"
            if cs in names:
                b = class_bytes(zf, cs)
                check(f"C1 {label} CommandSupport 验身标记 krg_btn_admin", b"krg_btn_admin" in b)
            else:
                check(f"C1 {label} CommandSupport 缺失", False)
            mb = "cn/kerong/bot/events/MenuButtons.class"
            if mb in names:
                b = class_bytes(zf, mb)
                check(f"C2 {label} MenuButtons 含 krg:acmd: 门禁前缀", b"krg:acmd:" in b)
            else:
                check(f"C2 {label} MenuButtons 缺失", False)
            ssb = "cn/kerong/bot/events/ServerSelectButtons.class"
            if ssb in names:
                b = class_bytes(zf, ssb)
                check(f"C3 {label} ServerSelectButtons 全局按钮（全局）", "全局".encode() in b)
            else:
                check(f"C3 {label} ServerSelectButtons 缺失", False)
            # Fa 阶段回归：二维码渲染
            qr = "cn/kerong/bot/tools/QrCode.class"
            if qr in names:
                b = class_bytes(zf, qr)
                check(f"C4 {label} QrCode encodeCjk/encodePlain 回归",
                      b"encodeCjk" in b and b"encodePlain" in b)
            else:
                check(f"C4 {label} QrCode 缺失", False)

            # ---------- D. 形态 ----------
            dir_count = sum(1 for n in names if n.endswith("/"))
            if label == "Spigot":
                check(f"D1 {label} fat JAR 目录条目数 700+（实际 {dir_count}）", dir_count >= 700)
            else:
                check(f"D1 {label} fat JAR 目录条目数 650+（实际 {dir_count}）", dir_count >= 650)
            if label == "NeoForge":
                check("D2 NF 重定位 kotlin 包存在", any(n.startswith("cn/kerong/shaded/kotlin/") for n in names))
                check("D3 NF 重定位 fastjson 无裸包", not any(n.startswith("com/alibaba/fastjson/") for n in names))
            if label == "Fabric":
                check("D2 Fabric 不重定位（kotlin 原包名）", any(n.startswith("kotlin/") for n in names))

    # ---------- 汇总 ----------
    n_pass = sum(1 for _, ok, _ in results if ok)
    n_fail = len(results) - n_pass
    for name, ok, detail in results:
        if not ok:
            print(f"[FAIL] {name}  {detail}")
    print(f"\n========== 门禁汇总: PASS={n_pass} FAIL={n_fail} / {len(results)} ==========")
    if n_fail:
        sys.exit(1)
    print("全部通过")


if __name__ == "__main__":
    main()
