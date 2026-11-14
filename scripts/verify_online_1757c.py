#!/usr/bin/env python3
"""1.7.5.7 再覆盖轮外网复核：官网四 JAR md5 + 页面内容断言（昵称唯一源口径）。"""
import hashlib
import io
import json
import sys
import urllib.request

BASE = "https://kerong.xin/penguin"
SRC = "https://kerong.xin/penguin-source"

JARS = {
    "Spigot": (f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.5.7.jar", "523b913a"),
    "NeoForge": (f"{BASE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.7.jar", "c517a6b6"),
    "Fabric": (f"{BASE}/downloads/KERONGPenguin_Fabric-1.20.4-1.7.5.7.jar", "616ac17a"),
    "Fabric1201": (f"{BASE}/downloads/KERONGPenguin_Fabric-1.20.1-1.7.5.7.jar", "60a4c5c9"),
}

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))


def fetch(url, binary=False, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 verify"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = r.read()
    return data if binary else data.decode("utf-8", "replace")


def main():
    # 1. 四 JAR md5（外网拉全量）
    for label, (url, expect) in JARS.items():
        try:
            data = fetch(url, binary=True, timeout=180)
            md5 = hashlib.md5(data).hexdigest()
            check(f"J1 {label} 外网 md5 {expect}", md5.startswith(expect), md5)
        except Exception as e:
            check(f"J1 {label} 外网下载", False, str(e))

    # 2. index.html
    idx = fetch(f"{BASE}/index.html")
    check("I1 index 版本 1.7.5.7", "版本 1.7.5.7" in idx)
    check("I2 Fabric 按钮括号 1.20.4-1.21.x", "Fabric 模组版（1.20.4-1.21.x）" in idx)
    check("I3 Fabric 1.20.1 按钮保留", "Fabric 模组版（1.20.1）" in idx)
    check("I4 日志块含全量群艾特修复", "开启「接收所有群消息」的群里，艾特机器人无法唤起" in idx)
    check("I5 日志块含配置清理条目", "ai.reply-quote 已并入" in idx)
    check("I6 日志块新增昵称唯一源条目", "艾特识别的机器人昵称改为只从 QQ 官方接口自动获取" in idx)
    check("I6b 新条目含「无需申请任何权限」", "无需申请任何权限" in idx)
    check("I6c 新条目含「不再依赖配置 bot.name」", "不再依赖配置 bot.name" in idx)
    check("I7 历史表 Fabric 1.7.5.7 行保留", "Fabric 1.7.5.7" in idx)

    # 3. version.json
    vj = json.loads(fetch(f"{BASE}/version.json"))
    check("V1 version 1.7.5.7", vj["version"] == "1.7.5.7")
    check("V2 changelog 五条", len(vj["changelog"]) == 5, str(len(vj["changelog"])))
    check("V3 首条为全量群艾特修复", "无法唤起" in vj["changelog"][0])
    check("V4 末条为昵称唯一源", "不再依赖配置 bot.name" in vj["changelog"][4])

    # 4. tutorial.html（bot.name 段口径回归：仅用于显示）
    tut = fetch(f"{BASE}/tutorial.html")
    check("T1 bot.name 段「仅用于显示」口径", "仅用于显示" in tut)
    check("T2 bot.name 段「不影响功能」", "不影响功能" in tut)
    check("T3 扫码段通俗文案保持", "可能长得不一样" in tut)

    # 5. 源码包
    try:
        data = fetch(f"{SRC}/KERONGPenguin_v1.7.5.7_source.zip", binary=True, timeout=300)
        md5 = hashlib.md5(data).hexdigest()
        check("S1 源码包外网 md5 970a5406", md5.startswith("970a5406"), md5)
        zf = io.BytesIO(data)
        import zipfile
        with zipfile.ZipFile(zf) as z:
            gh = z.read("common-Bot/src/main/kotlin/cn/kerong/bot/events/GroupMessageHandler.kt").decode()
            check("S2 源码 loadBotNicknames 在位", "loadBotNicknames" in gh)
            check("S3 源码无 getBotName 读取（唯一源）", "plugin.botName" not in gh)
            check("S4 源码含官方唯一源 KDoc", "唯一来源" in gh)
    except Exception as e:
        check("S1 源码包外网下载", False, str(e))

    failed = [r for r in results if not r[1]]
    print(f"\n===== 外网复核: {len(results) - len(failed)}/{len(results)} =====")
    for name, ok, detail in results:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  | {detail}" if (detail and not ok) else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
