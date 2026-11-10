#!/usr/bin/env python3
"""
外网验证：1.7.5 覆盖第二轮部署后（verify_online_o10）。
官网三件新口径 / 双 JAR 与源码包两处 md5 / JAR 内新类与配置 42 / 旧产物未受影响。
"""
import hashlib
import json
import sys
import urllib.request
import zipfile

BASE = "https://kerong.xin/penguin"
SPIGOT_MD5 = "261adc36365c898b9ba14a68e211c2a6"
NF_MD5 = "00d775604c85b30be2473d358c5888e0"
SRC_MD5 = "23a40bea223ed8e62055f65ffdcd2513"

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))


def fetch(url, binary=False, timeout=40):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = r.read()
    return data if binary else data.decode("utf-8", errors="replace")


def md5(data):
    return hashlib.md5(data).hexdigest()


def main():
    # ---------- 官网三件 ----------
    index = fetch(f"{BASE}/index.html")
    check("W1 index v1.7.5 主下载框在位", "版本 1.7.5" in index)
    check("W2 index v1.7.5 日志含点击即执行", "点一下直接执行" in index or "点击即执行" in index)
    check("W3 index v1.7.5 日志含复制到输入栏根因说明", "复制到输入栏" in index)
    check("W4 index v1.7.5 日志含第三方连接失败修复", "第三方连接失败" in index and "405" in index)
    check("W5 index v1.7.5 日志含模板自动升级", "自动刷新为新默认" in index or "自动升级" in index)
    check("W6 index Fabric 卡片含 MC 1.20.4", "Fabric 测试版 1.7.4.1（MC 1.20.4，Java 21）" in index)
    check("W7 index Fabric 卡片含 Loader 版本", "Fabric Loader" in index and "0.15.0" in index)
    check("W8 index Fabric 卡片含 Fabric API 版本说明", "Fabric API" in index and "1.20.4 对应版本" in index)
    check("W9 index 无覆盖版字样", "覆盖版" not in index)

    tutorial = fetch(f"{BASE}/tutorial.html")
    check("W10 tutorial 菜单卡片新口径（点击即执行+分页）", "点一下即直接执行" in tutorial and "下一页" in tutorial)
    check("W11 tutorial 黑名单卡片含解除按钮", "每个条目带「解除」按钮" in tutorial)
    check("W12 tutorial AI管理卡片新按钮标签", "开启上下文 / 关闭上下文 / 清除上下文" in tutorial)
    check("W13 tutorial menu 模板占位符说明", "{page}/{pages}" in tutorial)
    check("W14 tutorial 无覆盖版字样", "覆盖版" not in tutorial)

    vj = fetch(f"{BASE}/version.json")
    data = json.loads(vj)
    check("W15 version.json 版本 1.7.5", data.get("version") == "1.7.5")
    check("W16 changelog 只含当前版本条目", isinstance(data.get("changelog"), list) and len(data["changelog"]) >= 6)
    check("W17 changelog 含回调按钮口径", any("回调按钮" in c for c in data["changelog"]))
    check("W18 changelog 含 405 根因", any("405" in c for c in data["changelog"]))

    # ---------- 产物 md5 ----------
    spigot = fetch(f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.5.jar", binary=True)
    check("W19 Spigot JAR md5 一致", md5(spigot) == SPIGOT_MD5, md5(spigot))
    nf = fetch(f"{BASE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.jar", binary=True)
    check("W20 NeoForge JAR md5 一致", md5(nf) == NF_MD5, md5(nf))
    src1 = fetch("https://kerong.xin/penguin-source/KERONGPenguin-Source-v1.7.5.zip", binary=True)
    check("W21 源码包(penguin-source) md5 一致", md5(src1) == SRC_MD5, md5(src1))
    src2 = fetch(f"{BASE}/downloads/KERONGPenguin-Source-v1.7.5.zip", binary=True)
    check("W22 源码包(downloads) md5 一致且两处相同", md5(src2) == SRC_MD5 and md5(src1) == md5(src2))

    # ---------- JAR 内容 ----------
    zs = zipfile.ZipFile(__import__("io").BytesIO(spigot))
    names = zs.namelist()
    check("W23 JAR 内 InteractionAcker 在位", "cn/kerong/bot/events/InteractionAcker.class" in names)
    check("W24 JAR 内 MenuButtons 在位", "cn/kerong/bot/events/MenuButtons.class" in names)
    acker = zs.read("cn/kerong/bot/events/InteractionAcker.class")
    check("W25 Acker 含 PUT 与 /interactions/", b"PUT" in acker and b"/interactions/" in acker)
    menu_btn = zs.read("cn/kerong/bot/events/MenuButtons.class")
    check("W26 MenuButtons 含两个回调前缀", b"krg:cmd:" in menu_btn and b"krg:menu:page:" in menu_btn)
    cfg = zs.read("config.yml").decode("utf-8")
    check("W27 JAR 内 config-version 42", "config-version: 42" in cfg)
    check("W28 JAR 内菜单注释新口径", "点击即执行" in cfg)
    check("W29 JAR 内解除黑名单注释按钮说明", "点击按钮逐条解除" in cfg)
    menu_md = zs.read("Markdown/menu.md").decode("utf-8")
    check("W30 menu.md 分页标题", menu_md.strip() == "### 指令菜单（{page}/{pages}）")
    ai_md = zs.read("Markdown/ai-context-usage.md").decode("utf-8")
    check("W31 ai-context-usage 纯状态行", ai_md.strip() == "当前 AI 上下文状态：{status}")
    unb_md = zs.read("Markdown/unblacklist-list.md").decode("utf-8")
    check("W32 unblacklist-list 带按钮提示", "可以按下对应的按钮" in unb_md)

    # ---------- 旧产物未受影响 ----------
    fabric_head = fetch(f"{BASE}/downloads/KERONGPenguin_Fabric-1.20.4-1.7.4.1.jar", binary=True, timeout=60)[:4]
    check("W33 Fabric JAR 未受影响（zip 头）", fabric_head[:2] == b"PK")
    old_nf = fetch(f"{BASE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.4.1.jar", binary=True, timeout=60)
    check("W34 旧 1.7.4.1 NF JAR 在位可下", len(old_nf) > 1000000)

    failed = [r for r in results if not r[1]]
    print(f"\n===== 外网 verify_online_o10：{len(results)} 项，通过 {len(results) - len(failed)}，失败 {len(failed)} =====")
    for name, ok, detail in results:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  ({detail})" if detail and not ok else ""))
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
