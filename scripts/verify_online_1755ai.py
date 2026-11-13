#!/usr/bin/env python3
"""verify_online_1755ai：1.7.5.5 AI 覆盖轮外网验证（本地直接 curl kerong.xin 公网）。

- version.json version=1.7.5.5 + changelog 含 AI 艾特/引用/GBK 二维码三条
- index.html：主下载框 1.7.5.5、更新日志首段 v1.7.5.5 且含 AI 艾特句、
  超简口径（九版条目在位且单版条目不超过 3 条 li）、历史表 1.7.5.5 三行
- tutorial.html：ai.at-enabled / ai.reply-quote 两个 cfg-item 在位
- 三 JAR URL 200 且 md5 与本地一致；1.7.5.4 历史仍可下载
- config.yml 抽验（JAR 内）：三端 46 + 两键（复用本地门禁口径）
"""
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
BASE = "https://kerong.xin/penguin"
LOCAL = {
    "Spigot": REPO / "build" / "dist" / "KERONGPenguin_Spigot-1.7.5.5.jar",
    "NeoForge": REPO / "build" / "dist" / "KERONGPenguin_NeoForge-1.20.4-1.7.5.5.jar",
    "Fabric": REPO / "build" / "dist" / "KERONGPenguin_Fabric-1.20.4-1.7.5.5.jar",
}
REMOTE = {
    "Spigot": "downloads/KERONGPenguin_Spigot-1.7.5.5.jar",
    "NeoForge": "downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.5.jar",
    "Fabric": "downloads/KERONGPenguin_Fabric-1.20.4-1.7.5.5.jar",
}

checks = []


def check(name, ok, detail=""):
    checks.append((name, ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if detail else ""))


def fetch(url):
    r = subprocess.run(["curl", "-sk", "--max-time", "60", url],
                       capture_output=True, timeout=90)
    return r.returncode, r.stdout


def md5b(data):
    return hashlib.md5(data).hexdigest()


def main():
    # ---------- version.json ----------
    code, raw = fetch(f"{BASE}/version.json")
    vj = json.loads(raw.decode("utf-8", "replace")) if code == 0 else {}
    check("V1 version.json version=1.7.5.5", vj.get("version") == "1.7.5.5")
    cl = "\n".join(vj.get("changelog", []))
    check("V2 changelog 含 AI 艾特条", "AI 艾特" in cl and "at-enabled" in cl)
    check("V3 changelog 含回复引用条", "reply-quote" in cl and "引用消息" in cl)
    check("V4 changelog 含 GBK 二维码条", "控制台" in cl)

    # ---------- index.html ----------
    code, raw = fetch(f"{BASE}/index.html")
    idx = raw.decode("utf-8", "replace") if code == 0 else ""
    check("I1 index.html 200", code == 0 and len(idx) > 5000)
    check("I2 主下载框 版本 1.7.5.5", "版本 1.7.5.5" in idx)
    check("I3 更新日志首段 v1.7.5.5 含 AI 艾特句",
          re.search(r"<h3>v1\.7\.5\.5</h3>.*?AI 艾特", idx, re.S) is not None)
    check("I4 更新日志含 reply-quote 句", "reply-quote" in idx)
    versions = re.findall(r'<div class="version"><h3>(v[\d.]+)</h3>', idx)
    check("I5 九个版本条目在位", len(versions) == 9, str(versions))
    # 超简口径：每版 li 不超过 3 条
    blocks = re.findall(r'<div class="version"><h3>(v[\d.]+)</h3>.*?</ul></div>', idx, re.S)
    simple_ok = len(blocks) == 9 and all(
        len(re.findall(r"<li>", b2)) <= 3 for b2 in
        re.findall(r'(<div class="version"><h3>v[\d.]+</h3>.*?</ul></div>)', idx, re.S))
    check("I6 每版条目不超过 3 条 li（超级简化）", simple_ok)
    check("I7 历史表 1.7.5.5 三行",
          idx.count("<td>Spigot 1.7.5.5</td>") == 1
          and idx.count("<td>NeoForge 1.7.5.5</td>") == 1
          and idx.count("<td>Fabric 1.7.5.5</td>") == 1)
    check("I8 旧长文已删（无「真根因是以点击者身份」长句）",
          "真根因是以点击者身份" not in idx)

    # ---------- tutorial.html ----------
    code, raw = fetch(f"{BASE}/tutorial.html")
    tut = raw.decode("utf-8", "replace") if code == 0 else ""
    check("T1 tutorial.html 200", code == 0 and len(tut) > 30000)
    check("T2 cfg-item ai.at-enabled", 'ai.at-enabled</span>' in tut and "at-enabled: true" in tut)
    check("T3 cfg-item ai.reply-quote", 'ai.reply-quote</span>' in tut and "reply-quote: true" in tut)
    check("T4 AI 艾特用法说明（这人是谁）", "这人是谁" in tut)

    # ---------- 三 JAR ----------
    for label in ("Spigot", "NeoForge", "Fabric"):
        code, raw = fetch(f"{BASE}/{REMOTE[label]}")
        ok = code == 0 and len(raw) == LOCAL[label].stat().st_size and md5b(raw) == md5b(
            LOCAL[label].read_bytes())
        check(f"J-{label} 下载 200 且 md5 一致", ok,
              f"code={code} size={len(raw)}/{LOCAL[label].stat().st_size}")
    code, raw = fetch(f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.5.4.jar")
    check("J-历史 1.7.5.4 Spigot 仍可下载", code == 0 and len(raw) > 1000000)

    # ---------- JAR 内 config 抽验 ----------
    for label in ("Spigot",):
        with zipfile.ZipFile(LOCAL[label]) as zf:
            cfg = zf.read("config.yml").decode("utf-8", "replace")
            check("K1 Spigot JAR config-version 46", re.search(r"config-version:\s*46", cfg) is not None)
            check("K2 Spigot JAR at-enabled 默认开", re.search(r"at-enabled:\s*true", cfg) is not None)
            check("K3 Spigot JAR reply-quote 默认开", re.search(r"reply-quote:\s*true", cfg) is not None)

    n_pass = sum(1 for _, ok in checks if ok)
    n_fail = len(checks) - n_pass
    print(f"\n========== 外网复核汇总: PASS={n_pass} FAIL={n_fail} / {len(checks)} ==========")
    if n_fail:
        sys.exit(1)


if __name__ == "__main__":
    main()
