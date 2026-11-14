#!/usr/bin/env python3
"""1.7.5.7 覆盖轮外网复核：官网四 JAR md5 + 三页内容断言。"""
import hashlib
import io
import json
import sys
import urllib.request

BASE = "https://kerong.xin/penguin"
SRC = "https://kerong.xin/penguin-source"

JARS = {
    "Spigot": (f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.5.7.jar", "d4756a76"),
    "NeoForge": (f"{BASE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.7.jar", "1861d084"),
    "Fabric": (f"{BASE}/downloads/KERONGPenguin_Fabric-1.20.4-1.7.5.7.jar", "f4aa4cf1"),
    "Fabric1201": (f"{BASE}/downloads/KERONGPenguin_Fabric-1.20.1-1.7.5.7.jar", "f2cfadcf"),
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
    check("I2b 旧括号（1.20.4）不再出现", "Fabric 模组版（1.20.4）" not in idx)
    check("I3 Fabric 1.20.1 按钮保留", "Fabric 模组版（1.20.1）" in idx)
    check("I4 兼容表「括号里的 1.20.4-1.21.x」", "下载按钮括号里的 1.20.4-1.21.x" in idx)
    check("I5 日志块含全量群艾特修复", "开启「接收所有群消息」的群里，艾特机器人无法唤起" in idx)
    check("I6 日志块含配置清理条目", "ai.reply-quote 已并入" in idx)
    check("I7 历史表 Fabric 1.7.5.7 行保留", "Fabric 1.7.5.7（1.20.4 通用包）" in idx)

    # 3. version.json
    vj = json.loads(fetch(f"{BASE}/version.json"))
    check("V1 version 1.7.5.7", vj["version"] == "1.7.5.7")
    check("V2 changelog 四条", len(vj["changelog"]) == 4, str(len(vj["changelog"])))
    check("V3 首条为全量群艾特修复", "无法唤起" in vj["changelog"][0])
    check("V4 末条为配置清理", "47 → 48" in vj["changelog"][3] or "47→48" in vj["changelog"][3])

    # 4. tutorial.html
    tut = fetch(f"{BASE}/tutorial.html")
    check("T1 扫码段通俗文案", "可能长得不一样" in tut and "扫一扫" in tut)
    check("T2 不再出现 GBK/渲染形态等术语", "GBK" not in tut and "渲染形态" not in tut and "控制台编码" not in tut)
    check("T3 reminder-mode 段无旧键", "跟随下方旧键" not in tut and 'reply-quote: true' not in tut)
    check("T4 reminder-mode 留空默认引用", "留空 = 默认引用" in tut or "留空默认引用" in tut)

    # 5. 源码包
    try:
        data = fetch(f"{SRC}/KERONGPenguin_v1.7.5.7_source.zip", binary=True, timeout=300)
        md5 = hashlib.md5(data).hexdigest()
        check("S1 源码包外网 md5 0b876916", md5.startswith("0b876916"), md5)
        zf = io.BytesIO(data)
        import zipfile
        with zipfile.ZipFile(zf) as z:
            gh = z.read("common-Bot/src/main/kotlin/cn/kerong/bot/events/GroupMessageHandler.kt").decode()
            check("S2 源码包含昵称通道", "loadBotNicknames" in gh)
            cfg = z.read("server-Spigot/src/main/resources/config.yml").decode()
            check("S3 源码包 config 48 无旧键", "config-version: 48" in cfg and "reply-quote" not in cfg)
    except Exception as e:
        check("S1 源码包外网下载", False, str(e))

    failed = [r for r in results if not r[1]]
    print(f"\n===== 外网复核: {len(results) - len(failed)}/{len(results)} =====")
    for name, ok, detail in results:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  | {detail}" if (detail and not ok) else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
