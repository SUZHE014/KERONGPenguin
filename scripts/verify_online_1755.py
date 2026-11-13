#!/usr/bin/env python3
"""verify_online_1755：1.7.5.5 外网验证（本地直接 curl kerong.xin 公网）。

- version.json 首字段 = 1.7.5.5 + changelog 四条
- index.html 三处版本号（主下载框 / 历史表首行 / 更新日志首段）+ 三个新 JAR 链接
- 三 JAR URL 200 且 md5 与本地一致
- Fabric 卡片新口径（已测试七版本 / Loader 0.15.1 / minecraft <1.22 / 1.20.4–1.21.x）
- tutorial.html 二维码渲染形态说明在位
- 1.7.5.4 历史产物仍可下载（保留历史版本）
"""
import hashlib
import json
import subprocess
import sys
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
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if not ok and detail else ""))


def fetch(url):
    r = subprocess.run(["curl", "-sk", "--max-time", "60", url],
                       capture_output=True, timeout=90)
    return r.returncode, r.stdout


def md5b(data):
    return hashlib.md5(data).hexdigest()


def md5_file(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    print("== A. version.json ==")
    code, raw = fetch(f"{BASE}/version.json")
    check("A1 version.json 200", code == 0 and bool(raw))
    vj = json.loads(raw)
    check("A2 首字段版本 1.7.5.5", vj.get("version") == "1.7.5.5")
    check("A3 changelog 四条", len(vj.get("changelog", [])) == 4)
    check("A4 changelog 含二维码编码切换（自动切换渲染）", "自动切换渲染" in vj["changelog"][0])
    check("A5 changelog 含 Fabric 上限", "上限" in vj["changelog"][1])

    print("== B. index.html 三处版本号与链接 ==")
    code, raw = fetch(f"{BASE}/index.html")
    html = raw.decode()
    check("B1 index.html 200", code == 0 and bool(raw))
    check("B2 主下载框版本 1.7.5.5 + 发布日期 2026-11-13",
          "版本 1.7.5.5" in html and "2026-11-13" in html)
    check("B3 历史表首行 1.7.5.5（NF/Spigot/Fabric 三行）",
          html.count("1.7.5.5</td>") >= 3 and "NeoForge 1.7.5.5" in html)
    check("B4 更新日志首段 v1.7.5.5", "<h3>v1.7.5.5</h3>" in html)
    check("B5 三个新 JAR 链接在位",
          REMOTE["Spigot"] in html and REMOTE["NeoForge"] in html and REMOTE["Fabric"] in html)

    print("== C. 三 JAR 外网下载与 md5 ==")
    for tag in ("Spigot", "NeoForge", "Fabric"):
        code, data = fetch(f"{BASE}/{REMOTE[tag]}")
        check(f"C1 {tag} JAR 200 且字节数一致（{LOCAL[tag].stat().st_size}）",
              code == 0 and len(data) == LOCAL[tag].stat().st_size,
              f"实际 {len(data) if data else 0}")
        check(f"C2 {tag} JAR md5 往返一致（{md5_file(LOCAL[tag])}）",
              md5b(data) == md5_file(LOCAL[tag]))

    print("== D. Fabric 卡片与 tutorial 新口径 ==")
    check("D1 Fabric 卡片标题 1.7.5.5（MC 1.20.4–1.21.x）",
          "Fabric 模组版 1.7.5.5（MC 1.20.4–1.21.x，Java 21）" in html)
    check("D2 Fabric 已测试七版本清单",
          "1.20.4，1.20.5，1.20.6，1.21，1.21.4，1.21.6，1.21.11" in html)
    check("D3 Fabric Loader 0.15.1 口径（0.15.0 已移除）",
          "0.15.1 及以上" in html and "0.15.0 及以上" not in html)
    check("D4 Fabric minecraft 上限口径（&lt;1.22）", "&lt;1.22" in html)
    check("D5 主线发版对齐行更新（1.7.5.5）", "与当前主线 1.7.5.5 完全对齐" in html)
    code, traw = fetch(f"{BASE}/tutorial.html")
    tutorial = traw.decode()
    check("D6 tutorial 200", code == 0 and bool(traw))
    check("D7 tutorial 二维码渲染形态说明（GBK ■ 正常现象）",
          "方块字符" in tutorial and "正常现象" in tutorial)

    print("== E. 历史版本保留 ==")
    for jar in ("KERONGPenguin_Spigot-1.7.5.4.jar",
                "KERONGPenguin_NeoForge-1.20.4-1.7.5.4.jar",
                "KERONGPenguin_Fabric-1.20.4-1.7.5.4.jar"):
        code, data = fetch(f"{BASE}/downloads/{jar}")
        check(f"E {jar} 仍可下载", code == 0 and len(data) > 10000000)

    total = len(checks)
    bad = [c for c in checks if not c[1]]
    print(f"\n共 {total} 项，通过 {total - len(bad)}，失败 {len(bad)}")
    for name, _ in bad:
        print("  -", name)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
