#!/usr/bin/env python3
"""
外网验证：1.7.5 覆盖第三轮部署后（verify_online_o11）。
官网三件新口径（group_id 真根因 / 按钮分流 / 全量总开关 / 40→43）/ 双 JAR 与源码包两处 md5 /
JAR 内 RecentGroupMessages + config 43 + 新模板 / kb 已更新 / GitHub main=45cfbcd / 旧产物未受影响。
"""
import hashlib
import io
import json
import sys
import urllib.request
import zipfile

BASE = "https://kerong.xin/penguin"
SPIGOT_MD5 = "90c7bdea80f612bc34bd31a29bca4356"
NF_MD5 = "8373751ff5b302ebb2160dbc74769f03"
SRC_MD5 = "9c9ec63a573f2ddaf107964f0bc2d0d2"
COMMIT = "45cfbcd"

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
    check("W1 index v1.7.5 回调无反应真根因条目", "显示「操作成功」但机器人没有反应" in index and "group_id" in index)
    check("W2 index 按钮分流口径（无参点击即执行+带参复制）",
          "点击后复制到输入框" in index and "无参数命令" in index)
    check("W3 index 全量总开关条目", "全量转发改为总开关" in index and "同步隐藏" in index)
    check("W4 index 配置版本 40 → 43", "40 → 43" in index)
    check("W5 index 无覆盖版字样", "覆盖版" not in index)

    tutorial = fetch(f"{BASE}/tutorial.html")
    check("W6 tutorial 菜单带参命令复制到输入框", "点击复制到输入框" in tutorial)
    check("W7 tutorial 解除黑名单命令在前", "发送 /解除黑名单 @对应的人 解除" in tutorial)
    check("W8 tutorial 全量总开关口径", "全量转发总开关" in tutorial or ("总开关" in tutorial and "统一生效" in tutorial))
    check("W9 tutorial features.full-amount 总开关说明", "不再按群独立记忆" in tutorial)
    check("W10 tutorial commands 全量注释写回说明", "写回配置文件" in tutorial)
    check("W11 tutorial 无覆盖版字样", "覆盖版" not in tutorial)

    vj = fetch(f"{BASE}/version.json")
    data = json.loads(vj)
    check("W12 version.json 版本 1.7.5", data.get("version") == "1.7.5")
    check("W13 changelog 含 group_id 真根因", any("group_id" in c for c in data["changelog"]))
    check("W14 changelog 含全量总开关", any("总开关" in c and "统一生效" in c for c in data["changelog"]))
    check("W15 changelog 含 40 → 43", any("40 → 43" in c for c in data["changelog"]))
    check("W16 changelog 带参命令复制口径", any("复制到输入框" in c for c in data["changelog"]))

    # ---------- 产物 md5 ----------
    spigot = fetch(f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.5.jar", binary=True, timeout=90)
    check("W17 Spigot JAR md5 一致", md5(spigot) == SPIGOT_MD5, md5(spigot))
    nf = fetch(f"{BASE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.jar", binary=True, timeout=90)
    check("W18 NeoForge JAR md5 一致", md5(nf) == NF_MD5, md5(nf))
    src1 = fetch("https://kerong.xin/penguin-source/KERONGPenguin-Source-v1.7.5.zip", binary=True, timeout=90)
    check("W19 源码包(penguin-source) md5 一致", md5(src1) == SRC_MD5, md5(src1))
    src2 = fetch(f"{BASE}/downloads/KERONGPenguin-Source-v1.7.5.zip", binary=True, timeout=90)
    check("W20 源码包(downloads) md5 一致且两处相同", md5(src2) == SRC_MD5 and md5(src1) == md5(src2))

    # ---------- JAR 内容（第三轮核心） ----------
    zs = zipfile.ZipFile(io.BytesIO(spigot))
    zn = zipfile.ZipFile(io.BytesIO(nf))
    check("W21 Spigot JAR 内 RecentGroupMessages", "cn/kerong/bot/events/RecentGroupMessages.class" in zs.namelist())
    check("W22 NeoForge JAR 内 RecentGroupMessages", "cn/kerong/bot/events/RecentGroupMessages.class" in zn.namelist())
    menu_s = zs.read("cn/kerong/bot/events/MenuButtons.class")
    menu_n = zn.read("cn/kerong/bot/events/MenuButtons.class")
    check("W23 双 JAR MenuButtons 含 group_id（真根因修复）", b"group_id" in menu_s and b"group_id" in menu_n)
    check("W24 MenuButtons 含 INPUT_COMMANDS 命令（绑定/管理员执行）",
          "管理员执行".encode() in menu_s and "管理员执行".encode() in menu_n)
    cfg = zs.read("config.yml").decode("utf-8")
    cfg_n = zn.read("config.yml").decode("utf-8")
    check("W25 双 JAR config-version 43", "config-version: 43" in cfg and "config-version: 43" in cfg_n)
    check("W26 config 全量总开关注释（双端）",
          "总开关，对下方 bot.groups 里全部群统一生效" in cfg and "总开关，对下方 bot.groups 里全部群统一生效" in cfg_n)
    menu_md = zs.read("Markdown/menu.md").decode("utf-8")
    check("W27 menu.md 标题+版本行",
          menu_md.strip() == "### 指令菜单（{page}/{pages}）\n\n**当前插件版本：v{version}**", repr(menu_md))
    unb_md = zs.read("Markdown/unblacklist-list.md").decode("utf-8")
    check("W28 unblacklist-list 命令在前", unb_md.strip().endswith("发送 /解除黑名单 @对应的人 解除；也可以点击下方对应的按钮解除"))
    fat_md = zs.read("Markdown/full-amount-toggled.md").decode("utf-8")
    check("W29 full-amount-toggled 总开关口径", "总开关，全部配置群统一生效{writeResult}" in fat_md)
    gsr = zs.read("cn/kerong/bot/state/CommandRepositories.class")
    check("W30 群级开关零残留（无 setFullForwarding）", b"setFullForwarding" not in gsr)

    # ---------- 知识库 ----------
    kb = fetch(f"{BASE}/AI-KNOWLEDGE.txt")
    check("W31 kb 头部 v1.7.5 覆盖第三轮", "v1.7.5 覆盖第三轮" in kb)
    check("W32 kb 记录提交 45cfbcd 与产物 md5", "45cfbcd" in kb and "90c7bdea" in kb)
    check("W33 kb 补记 1.7.4/1.7.4.1/1.7.5 三版归档",
          "1.7.5（2026-11-10，覆盖三轮链" in kb and "1.7.4.1（2026-11-09，二轮）" in kb and "1.7.4（2026-11-08~09，七轮覆盖链）" in kb)

    # ---------- GitHub ----------
    gh = json.loads(fetch("https://api.github.com/repos/SUZHE014/KERONGPenguin/commits/main"))
    check("W34 GitHub main = 45cfbcd", gh["sha"].startswith(COMMIT), gh["sha"][:7])

    # ---------- 旧产物未受影响 ----------
    fabric_head = fetch(f"{BASE}/downloads/KERONGPenguin_Fabric-1.20.4-1.7.4.1.jar", binary=True, timeout=60)[:4]
    check("W35 Fabric JAR 未受影响（zip 头）", fabric_head[:2] == b"PK")
    old_sp = fetch(f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.4.1.jar", binary=True, timeout=60)
    check("W36 旧 1.7.4.1 Spigot JAR 在位可下", len(old_sp) > 1000000)

    failed = [r for r in results if not r[1]]
    print(f"\n===== 外网 verify_online_o11：{len(results)} 项，通过 {len(results) - len(failed)}，失败 {len(failed)} =====")
    for name, ok, detail in results:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  ({detail})" if detail and not ok else ""))
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
