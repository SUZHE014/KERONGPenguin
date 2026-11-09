#!/usr/bin/env python3
"""1.7.4.1 第二轮部署后外网验证（在服务器侧经 https://kerong.xin 公网路径）"""
import subprocess
import sys

BASE = "https://kerong.xin/penguin"
passed, failed = [], []

def check(name, ok, detail=""):
    (passed if ok else failed).append(name)
    print(f"  {'✓' if ok else '✗'} {name}" + (f" — {detail}" if detail and not ok else ""))

def curl(url):
    r = subprocess.run(["curl", "-s", "--max-time", "30", url], capture_output=True, text=True)
    return r.returncode, r.stdout

def curl_bin(url):
    r = subprocess.run(["curl", "-s", "--max-time", "60", url], capture_output=True)
    import hashlib
    return r.returncode, hashlib.md5(r.stdout).hexdigest(), len(r.stdout)

print("== 1. 官网页面 ==")
rc, idx = curl(f"{BASE}/")
check("index.html 200 且含 1.7.4.1 新口径", rc == 0 and "180 秒重启判定" in idx and "server-notify.start" in idx and "服务器重启完毕，已正常运行(o‿∩)" in idx)
check("index.html 版本头 1.7.4.1", "版本 1.7.4.1" in idx)
check("index.html 下载链接指向 1.7.4.1 JAR", "KERONGPenguin_Spigot-1.7.4.1.jar" in idx and "KERONGPenguin_NeoForge-1.20.4-1.7.4.1.jar" in idx)
rc, ver = curl(f"{BASE}/version.json")
check("version.json 200 且 version=1.7.4.1", rc == 0 and '"version": "1.7.4.1"' in ver)
check("version.json changelog 含第二轮内容", "服务器重启完毕，已正常运行(o‿∩)" in ver and "restart-window" in ver and "39 → 40" in ver)
rc, tut = curl(f"{BASE}/tutorial.html")
check("tutorial 200 且 server-notify 新段", rc == 0 and "restart-window: 180" in tut and "服务器重启完毕，已正常运行(o‿∩)" in tut)
check("tutorial start 段与去表情文案", "start:" in tut and "服务器...." in tut and "(╯︵╰" not in tut and "༽◺" not in tut)

print("== 2. 下载产物 md5 / 字节数 ==")
for name, expect_md5, expect_size in [
    ("downloads/KERONGPenguin_Spigot-1.7.4.1.jar", "d58cae6234bb070c60a5ff9f04ed08f8", 19983199),
    ("downloads/KERONGPenguin_NeoForge-1.20.4-1.7.4.1.jar", "2c2bdfce5ffaf4bb84b7e61e998668cc", 19166001),
    ("downloads/KERONGPenguin-Source-v1.7.4.1.zip", "03d09c5b33a1a8a985170b4e7f2ffc47", 6612615),
]:
    rc, md5, size = curl_bin(f"{BASE}/{name}")
    check(f"{name} md5+大小", rc == 0 and md5 == expect_md5 and size == expect_size, f"{md5} {size}")

print("== 3. JAR 内容抽验（下载后解析 config.yml / plugin.yml） ==")
import zipfile, io, yaml
rc = 0
for jar_url, tag, plugin_entry in [
    (f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.4.1.jar", "Spigot", "plugin.yml"),
    (f"{BASE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.4.1.jar", "NeoForge", "META-INF/neoforge.mods.toml"),
]:
    r = subprocess.run(["curl", "-s", "--max-time", "90", jar_url], capture_output=True)
    if r.returncode != 0 or not r.stdout:
        check(f"{tag} JAR 下载失败", False)
        continue
    zf = zipfile.ZipFile(io.BytesIO(r.stdout))
    cfg = yaml.safe_load(zf.read("config.yml").decode("utf-8"))
    sn = cfg["server-notify"]
    check(f"{tag} JAR config-version 40", cfg["config-version"] == 40)
    check(f"{tag} JAR 四条新文案", sn["shutdown"]["message"] == "[状态]\n服务器已正常关闭"
          and sn["restart"]["message"] == "[状态]\n服务器重启完毕，已正常运行(o‿∩)"
          and sn["start"]["message"] == "[状态]\n服务器已开启！！(≧∇≦)/"
          and sn["crash"]["message"] == "[状态]\n服务器....")
    check(f"{tag} JAR restart-window 180", sn["restart-window"] == 180)
    check(f"{tag} JAR 版本 1.7.4.1", b"1.7.4.1" in zf.read(plugin_entry))
    names = zf.namelist()
    check(f"{tag} JAR ServerNotifyManager 在位", "cn/kerong/bot/ServerNotifyManager.class" in names)

print("== 4. 旧版本文件未受影响 ==")
rc, old_md5, old_size = curl_bin(f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.4.jar")
check("旧 1.7.4 Spigot JAR 仍在（md5 3a2c1097）", rc == 0 and old_md5.startswith("3a2c1097"), old_md5)
rc, v74_md5, v74_size = curl_bin(f"{BASE}/downloads/KERONGPenguin-Source-v1.7.4.zip")
check("旧 v1.7.4 源码包仍在（6,617,595 B 第七轮版）", rc == 0 and v74_size == 6617595, str(v74_size))

print()
print(f"合计：{len(passed)} 通过 / {len(failed)} 失败")
if failed:
    for f in failed:
        print("  ✗", f)
    sys.exit(1)
print("外网验证全部通过 ✓")
