#!/usr/bin/env python3
"""1.7.5.7 外网复核：公网拉取四 JAR md5 + 官网三件内容断言。"""
import hashlib
import json
import re
import subprocess
import sys

BASE = "https://kerong.xin/penguin"
EXPECT = {
    f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.5.7.jar": "e7817624ee15937c98429655e8b8fe5d",
    f"{BASE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.7.jar": "3f891a622b95c0238a6f123781b97847",
    f"{BASE}/downloads/KERONGPenguin_Fabric-1.20.4-1.7.5.7.jar": "f7097ba8fc3d9bc8b1cbd178d5dcf5ea",
    f"{BASE}/downloads/KERONGPenguin_Fabric-1.20.1-1.7.5.7.jar": "b8410a4d36c343af7045ef805eb7b845",
}


def curl(url):
    r = subprocess.run(["curl", "-fsSL", "--max-time", "60", url], capture_output=True, timeout=90)
    return r.returncode, r.stdout


def main():
    ok = 0
    total = 0
    for url, md5 in EXPECT.items():
        total += 1
        code, body = curl(url)
        got = hashlib.md5(body).hexdigest() if code == 0 else ""
        status = "PASS" if got == md5 else "FAIL"
        if got == md5:
            ok += 1
        print(f"[{status}] {url.rsplit('/', 1)[-1]}  {got[:8]}")

    for path, needles in [
        (f"{BASE}/version.json", ['"version": "1.7.5.7"', "艾特其他机器人"]),
        (f"{BASE}/index.html", ["版本 1.7.5.7", "KERONGPenguin_Spigot-1.7.5.7.jar",
                                 "v1.7.5.7</h3>", "误判为被艾特而插嘴"]),
        (f"{BASE}/tutorial.html", ["ai.reminder-mode", "button-cooldown-seconds"]),
    ]:
        total += 1
        code, body = curl(path)
        text = body.decode("utf-8", "replace") if code == 0 else ""
        hit = all(n in text for n in needles)
        if hit:
            ok += 1
        print(f"[{'PASS' if hit else 'FAIL'}] {path}  ({len(text)} bytes)")

    # 源码包
    total += 1
    code, body = curl(f"{BASE.replace('/penguin', '/penguin-source')}/KERONGPenguin_v1.7.5.7_source.zip")
    got = hashlib.md5(body).hexdigest() if code == 0 else ""
    if got == "ce19e5ae352af2c1c9369ed73a3837d8":
        ok += 1
        print(f"[PASS] 源码包  {got[:8]}")
    else:
        print(f"[FAIL] 源码包  {got[:8]}")

    print(f"\n===== 外网复核: {ok}/{total} =====")
    return 0 if ok == total else 1


if __name__ == "__main__":
    sys.exit(main())
