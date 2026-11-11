#!/usr/bin/env python3
"""verify_online_1753：1.7.5.3 部署后外网验证。

- 五个在架 JAR（1.7.5.3 ×3 + 1.7.5.2 覆盖 ×2）公网可下且 md5 与本地一致
- 1.7.5.1 三 JAR 公网 404（已下架）
- version.json 公网可读、版本号 1.7.5.3、含面板联动更新日志
- 官网 index.html 含 1.7.5.3 下载区 / 历史表行 / 更新日志块，无 1.7.5.1 残留引用
"""
import hashlib
import json
import urllib.request

BASE = "https://kerong.xin/penguin"
LOCAL = "/home/z/my-project/penguin-git/build/dist"
LOCAL_SITE = "/home/z/my-project/penguin-git/website"

checks = []


def check(name, ok, detail=""):
    checks.append((name, ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if not ok and detail else ""))


def fetch(url, binary=False):
    req = urllib.request.Request(url, headers={"User-Agent": "verify-online-1753"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = resp.read()
    return data if binary else data.decode()


def md5(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def local_md5(path: str) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def http_status(url):
    try:
        req = urllib.request.Request(url, method="HEAD",
                                     headers={"User-Agent": "verify-online-1753"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception as e:  # noqa: BLE001
        return -1


def main():
    print("== A. 在架 JAR 公网 md5 往返 ==")
    jars = [
        ("Spigot-1.7.5.3", f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.5.3.jar",
         f"{LOCAL}/KERONGPenguin_Spigot-1.7.5.3.jar"),
        ("NeoForge-1.7.5.3", f"{BASE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.3.jar",
         f"{LOCAL}/KERONGPenguin_NeoForge-1.20.4-1.7.5.3.jar"),
        ("Fabric-1.7.5.3", f"{BASE}/downloads/KERONGPenguin_Fabric-1.20.4-1.7.5.3.jar",
         f"{LOCAL}/KERONGPenguin_Fabric-1.20.4-1.7.5.3.jar"),
        ("Spigot-1.7.5.2覆盖", f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.5.2.jar",
         f"{LOCAL}/KERONGPenguin_Spigot-1.7.5.2.jar"),
        ("NeoForge-1.7.5.2覆盖", f"{BASE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.2.jar",
         f"{LOCAL}/KERONGPenguin_NeoForge-1.20.4-1.7.5.2.jar"),
    ]
    for name, url, local in jars:
        try:
            remote = md5(fetch(url, binary=True))
            check(f"A {name} md5 往返一致", remote == local_md5(local),
                  f"remote={remote} local={local_md5(local)}")
        except Exception as e:  # noqa: BLE001
            check(f"A {name} 下载失败", False, str(e))

    print("== B. 1.7.5.1 下架 404 ==")
    for name, url in [
        ("Spigot-1.7.5.1", f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.5.1.jar"),
        ("NeoForge-1.7.5.1", f"{BASE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.1.jar"),
        ("Fabric-1.7.5.1", f"{BASE}/downloads/KERONGPenguin_Fabric-1.20.4-1.7.5.1.jar"),
    ]:
        check(f"B {name} 已 404", http_status(url) == 404, f"status={http_status(url)}")

    print("== C. version.json 公网口径 ==")
    try:
        vj = json.loads(fetch(f"{BASE}/version.json"))
        check("C1 版本号 1.7.5.3", vj["version"] == "1.7.5.3", vj.get("version", ""))
        changelog = "\n".join(vj["changelog"])
        check("C2 含面板联动更新日志", "快捷面板管理员联动" in changelog and
              "panel-admin-linkage" in changelog)
        check("C3 含 1.7.5.1 下架说明", "下架 1.7.5.1" in changelog)
        check("C4 无官方接口方案残留表述", "实时查询官方群成员接口" not in changelog)
    except Exception as e:  # noqa: BLE001
        check("C version.json 读取失败", False, str(e))

    print("== D. 官网页面口径 ==")
    try:
        index = fetch(f"{BASE}/index.html")
        local_index = open(f"{LOCAL_SITE}/index.html", encoding="utf-8").read()
        check("D1 下载区版本 1.7.5.3（含 Fabric 按钮）",
              "版本 1.7.5.3" in index and "KERONGPenguin_Fabric-1.20.4-1.7.5.3.jar" in index)
        check("D2 历史表含 1.7.5.3 三行",
              index.count("1.7.5.3") >= 6)
        check("D3 更新日志含 v1.7.5.3 块", "v1.7.5.3" in index and "QQ 快捷面板管理员联动" in index)
        check("D4 历史表无 1.7.5.1 下载行",
              "KERONGPenguin_Spigot-1.7.5.1.jar" not in index
              and "KERONGPenguin_NeoForge-1.20.4-1.7.5.1.jar" not in index
              and "KERONGPenguin_Fabric-1.20.4-1.7.5.1.jar" not in index)
        check("D5 更新日志无 v1.7.5.1 块", "<h3>v1.7.5.1</h3>" not in index)
        check("D6 1.7.5.2 ③ 项为平台门禁口径",
              "平台门禁" in index and "实时查询官方群成员接口" not in index)
        check("D7 线上与本地 index.html 一致", index.strip() == local_index.strip())
        tutorial = fetch(f"{BASE}/tutorial.html")
        local_tutorial = open(f"{LOCAL_SITE}/tutorial.html", encoding="utf-8").read()
        check("D8 tutorial 一致且无 1.7.5.1 引用",
              tutorial.strip() == local_tutorial.strip() and "（1.7.5.1 起）" not in tutorial)
    except Exception as e:  # noqa: BLE001
        check("D 官网页面读取失败", False, str(e))

    print()
    failed = [name for name, ok in checks if not ok]
    print(f"共 {len(checks)} 项，通过 {len(checks) - len(failed)}，失败 {len(failed)}")
    if failed:
        print("失败项：")
        for name in failed:
            print(f"  - {name}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
