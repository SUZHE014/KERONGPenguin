#!/usr/bin/env python3
"""verify_online_1754：1.7.5.4 外网验证（本地直接 curl kerong.xin 公网）。

- version.json 首字段 = 1.7.5.4 + changelog 四条
- index.html 三处版本号（主下载框 / 历史表首行 / 更新日志首段）+ 三个新 JAR 链接
- 三 JAR URL 200 且 md5 与本地一致
- tutorial.html 命令卡片新口径
- 1.7.5.3 历史产物仍可下载（保留历史版本）
"""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
BASE = "https://kerong.xin/penguin"

checks = []


def check(name, ok, detail=""):
    checks.append((name, ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if not ok and detail else ""))


def fetch(url):
    r = subprocess.run(["curl", "-sk", "--max-time", "60", url],
                       capture_output=True, timeout=90)
    return r.returncode, r.stdout


def md5_bytes(data):
    return hashlib.md5(data).hexdigest()


def main():
    print("== A. version.json ==")
    code, raw = fetch(f"{BASE}/version.json")
    check("A1 version.json 200", code == 0 and len(raw) > 10)
    data = json.loads(raw.decode())
    check("A2 首字段版本 1.7.5.4", data.get("version") == "1.7.5.4")
    check("A3 changelog 四条", len(data.get("changelog", [])) == 4)
    check("A4 changelog 首条含按钮管理员判定收敛",
          "按钮管理员判定收敛" in data["changelog"][0])

    print("== B. index.html ==")
    code, raw = fetch(f"{BASE}/index.html")
    html = raw.decode()
    check("B1 index 200", code == 0 and len(html) > 1000)
    check("B2 主下载框版本 1.7.5.4", "版本 1.7.5.4" in html)
    check("B3 历史表三行 1.7.5.4",
          html.count("1.7.5.4.jar") >= 6 and "NeoForge 1.7.5.4" in html
          and "Spigot 1.7.5.4" in html and "Fabric 1.7.5.4" in html)
    check("B4 更新日志首段 v1.7.5.4",
          '<h3>v1.7.5.4</h3>' in html and "按钮管理员判定收敛" in html)
    check("B5 Fabric 卡片 1.7.5.4", "Fabric 模组版 1.7.5.4" in html)

    print("== C. 三 JAR 下载与 md5 ==")
    jars = [
        ("Spigot", "KERONGPenguin_Spigot-1.7.5.4.jar",
         REPO / "build" / "dist" / "KERONGPenguin_Spigot-1.7.5.4.jar"),
        ("NeoForge", "KERONGPenguin_NeoForge-1.20.4-1.7.5.4.jar",
         REPO / "build" / "dist" / "KERONGPenguin_NeoForge-1.20.4-1.7.5.4.jar"),
        ("Fabric", "KERONGPenguin_Fabric-1.20.4-1.7.5.4.jar",
         REPO / "build" / "dist" / "KERONGPenguin_Fabric-1.20.4-1.7.5.4.jar"),
    ]
    for tag, name, local in jars:
        code, raw = fetch(f"{BASE}/downloads/{name}")
        local_md5 = md5_bytes(local.read_bytes()) if local.is_file() else "?"
        remote_md5 = md5_bytes(raw) if raw else "?"
        check(f"C-{tag} {name} 200 + md5 往返一致",
              code == 0 and len(raw) == local.stat().st_size and remote_md5 == local_md5,
              f"remote={remote_md5[:12]} local={local_md5[:12]} size={len(raw)}/{local.stat().st_size if local.is_file() else '?'}")

    print("== D. tutorial.html ==")
    code, raw = fetch(f"{BASE}/tutorial.html")
    tut = raw.decode()
    check("D1 tutorial 200", code == 0 and len(tut) > 1000)
    check("D2 /菜单 行新排版口径", "短按钮（不超过 3 字，含「AI管理」）一行两个" in tut)
    check("D3 /切换服务器 行带参说明", "带服务器名发送可直接切换" in tut)

    print("== E. 历史版本保留 ==")
    for name in ("KERONGPenguin_Spigot-1.7.5.3.jar",
                 "KERONGPenguin_NeoForge-1.20.4-1.7.5.3.jar",
                 "KERONGPenguin_Fabric-1.20.4-1.7.5.3.jar"):
        code, raw = fetch(f"{BASE}/downloads/{name}")
        check(f"E {name} 仍可下载", code == 0 and len(raw) > 1000000)

    failed = [name for name, ok in checks if not ok]
    print()
    print(f"共 {len(checks)} 项，通过 {len(checks) - len(failed)}，失败 {len(failed)}")
    if failed:
        for name in failed:
            print(f"  - {name}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
