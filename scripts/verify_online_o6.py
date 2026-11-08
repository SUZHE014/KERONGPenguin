#!/usr/bin/env python3
"""1.7.4 第六轮覆盖版部署后外网验证。

外网（不经内网 IP）curl 验证：
1. 双 JAR 可下载且 md5 与本地构建一致
2. 源码包可下载且 md5 一致
3. tutorial.html 新文案在位 + 旧文案零出现 + 无 HuHoBot
4. index.html / version.json 未受影响（版本 1.7.4、无 HuHoBot、无覆盖字段）
5. 官网 JAR 内 config.yml 三条新默认文案 + config-version 38（下载后开包校验）
"""
import hashlib
import io
import subprocess
import sys
import zipfile

BASE = "https://kerong.xin/penguin"
LOCAL_SPIGOT = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_Spigot-1.7.4.jar"
LOCAL_NF = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.4.jar"
LOCAL_SRC = "/tmp/KERONGPenguin-Source-v1.7.4.zip"

NEW_SHUTDOWN = "[状态]\n服务器已正常关闭 (╯︵╰,)"
NEW_RESTART = "[状态]\n服务器已开启！！(≧∇≦)/"
NEW_CRASH = "[状态]\n服务器....༽◺_轨迹༼"

passed, failed = [], []


def check(name, ok, detail=""):
    (passed if ok else failed).append(name)
    print(f"  {'✓' if ok else '✗'} {name}" + (f" — {detail}" if detail and not ok else ""))


def fetch(url):
    r = subprocess.run(["curl", "-sL", "-m", "60", url], capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(f"curl failed: {url}")
    return r.stdout


def md5(data):
    return hashlib.md5(data).hexdigest()


def local_md5(path):
    with open(path, "rb") as f:
        return md5(f.read())


print("== 1. 双端 JAR 与源码包外网下载 ==")
spigot = fetch(f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.4.jar")
check("Spigot JAR md5 一致", md5(spigot) == local_md5(LOCAL_SPIGOT), md5(spigot))
nf = fetch(f"{BASE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.4.jar")
check("NeoForge JAR md5 一致", md5(nf) == local_md5(LOCAL_NF), md5(nf))
src = fetch(f"{BASE}/../penguin-source/KERONGPenguin-Source-v1.7.4.zip")
check("源码包 md5 一致", md5(src) == local_md5(LOCAL_SRC), md5(src))

print("== 2. 官网 JAR 内 config.yml ==")
for label, blob in [("Spigot", spigot), ("NeoForge", nf)]:
    zf = zipfile.ZipFile(io.BytesIO(blob))
    cfg = zf.read("config.yml").decode("utf-8")
    check(f"{label} JAR 关机新文案", 'message: "[状态]\\n服务器已正常关闭 (╯︵╰,)"' in cfg)
    check(f"{label} JAR 重启新文案", 'message: "[状态]\\n服务器已开启！！(≧∇≦)/"' in cfg)
    check(f"{label} JAR 意外关闭新文案", 'message: "[状态]\\n服务器....༽◺_轨迹༼"' in cfg)
    check(f"{label} JAR config-version 38", "config-version: 38" in cfg)
    check(f"{label} JAR 旧默认文案零出现", 'message: "服务器已正常关闭"' not in cfg
          and 'message: "服务器已重启完成"' not in cfg
          and 'message: "服务器意外关闭' not in cfg)
    check(f"{label} JAR 无 HuHoBot", "huhobot" not in cfg.lower())

print("== 3. tutorial.html ==")
tutorial = fetch(f"{BASE}/tutorial.html").decode("utf-8")
check("tutorial 关机新文案", "\\n服务器已正常关闭 (╯︵╰,)" in tutorial)
check("tutorial 重启新文案", "\\n服务器已开启！！(≧∇≦)/" in tutorial)
check("tutorial 意外关闭新文案", "\\n服务器....༽◺_轨迹༼" in tutorial)
check("tutorial 旧默认文案零出现", 'message: "服务器已正常关闭"' not in tutorial
      and 'message: "服务器已重启完成"' not in tutorial
      and "服务器意外关闭（崩溃或被强制结束）" not in tutorial)
check("tutorial 无 HuHoBot", "huhobot" not in tutorial.lower())
check("tutorial 占位符说明保持", "占位符：{server}、{platform}" in tutorial)
check("tutorial 无版本轮次字段", "覆盖更新" not in tutorial and "第六轮" not in tutorial)

print("== 4. index / version.json 未受影响 ==")
index = fetch(f"{BASE}/index.html").decode("utf-8")
version = fetch(f"{BASE}/version.json").decode("utf-8")
check("index 版本 1.7.4", "1.7.4" in index)
check("index 无 HuHoBot", "huhobot" not in index.lower())
check("version.json 版本 1.7.4", '"version": "1.7.4"' in version)
check("version.json 无 HuHoBot", "huhobot" not in version.lower())
check("version.json 无覆盖字段", "覆盖" not in version.split('"changelog"')[0])
check("style.css 可达", len(fetch(f"{BASE}/style.css")) > 1000)

print(f"\n===== 外网验证：{len(passed)} 通过 / {len(failed)} 失败 =====")
if failed:
    for name in failed:
        print("  ✗", name)
    sys.exit(1)
