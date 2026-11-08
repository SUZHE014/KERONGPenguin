#!/usr/bin/env python3
"""1.7.4 第七轮覆盖版部署后外网验证（从沙盒直接 curl 线上）。

验证项：
1. 双 JAR 外网可下载且 md5 / 字节数与本地构建一致
2. 源码包两处（penguin-source/ 与 downloads/）外网 md5 一致且为 HEAD f732d83 版
3. JAR 内 config.yml：三条新文案（含正确颜文字 ༽◺_◿༼）、config-version 39、
   restart-window 零出现、误写版「轨迹」零出现、无 HuHoBot
4. tutorial.html：新文案 + restart-window 零出现 + 描述新口径；
   index / version.json / style.css 与部署前基线一致（未受影响）
5. 双 JAR 字节码：ServerNotifyManager 含新开启日志、无 serverNotifyRestartWindow
"""
import hashlib
import io
import json
import subprocess
import sys
import zipfile

BASE = "https://kerong.xin/penguin"
SPIGOT = f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.4.jar"
NF = f"{BASE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.4.jar"
SRC_A = f"{BASE}/../penguin-source/KERONGPenguin-Source-v1.7.4.zip"
SRC_B = f"{BASE}/downloads/KERONGPenguin-Source-v1.7.4.zip"

LOCAL = "/home/z/my-project/penguin-git/build/dist"
LOCAL_SPIGOT_MD5 = "3a2c1097113141e63063c18a935d2025"
LOCAL_NF_MD5 = "bd903165b83102a3a12da41bd5708ff6"
LOCAL_SRC_MD5 = "db2bc4710e77936084f3074d1e03925e"
LOCAL_SRC_SIZE = 6617595

# 部署前基线（8043e18 时线上四件中未动的三件）
INDEX_MD5 = "ccfa81dda4a09d0033935c300778dea7"
VERSION_MD5 = "c0578a6aab9782ce709a36fd22d0361b"
STYLE_MD5 = "6e36f8c21dfb3a25864be45d554896dc"

NEW_SHUTDOWN = "[状态]\n服务器已正常关闭 (╯︵╰,)"
NEW_RESTART = "[状态]\n服务器已开启！！(≧∇≦)/"
NEW_CRASH = "[状态]\n服务器....༽◺_◿༼"
WRONG_CRASH = "[状态]\n服务器....༽◺_轨迹༼"

passed = []
failed = []


def check(name, ok, detail=""):
    (passed if ok else failed).append(name)
    print(f"  {'✓' if ok else '✗'} {name}" + (f" — {detail}" if detail and not ok else ""))


def fetch(url, binary=True):
    r = subprocess.run(["curl", "-fsSL", "--max-time", "120", url], capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(f"curl 失败 {url}: {r.stderr.decode()[:200]}")
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def md5(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def main():
    print("== 1. 双 JAR 外网下载与 md5 ==")
    spigot = fetch(SPIGOT)
    nf = fetch(NF)
    check("Spigot JAR md5 一致", md5(spigot) == LOCAL_SPIGOT_MD5, md5(spigot))
    check("Spigot JAR 字节数 19981558", len(spigot) == 19981558, str(len(spigot)))
    check("NeoForge JAR md5 一致", md5(nf) == LOCAL_NF_MD5, md5(nf))
    check("NeoForge JAR 字节数 19164370", len(nf) == 19164370, str(len(nf)))

    print("== 2. 源码包两处外网一致（HEAD f732d83） ==")
    src_a = fetch(SRC_A)
    src_b = fetch(SRC_B)
    check("penguin-source 源码包 md5", md5(src_a) == LOCAL_SRC_MD5, md5(src_a))
    check("downloads 源码包 md5", md5(src_b) == LOCAL_SRC_MD5, md5(src_b))
    check("两处内容一致", src_a == src_b)
    check("源码包字节数", len(src_a) == LOCAL_SRC_SIZE, str(len(src_a)))
    with zipfile.ZipFile(io.BytesIO(src_a)) as zf:
        names = zf.namelist()
        check("源码包含第七轮脚本 verify_jar_174o7.py",
              any("verify_jar_174o7.py" in n for n in names))
        check("源码包含第六轮脚本 verify_online_o6.py（第六轮遗留补齐）",
              any("verify_online_o6.py" in n for n in names))
        check("源码包含本轮提交的 smoke174o7", any("smoke174o7/Smoke174o7.java" in n for n in names))

    print("== 3. JAR 内 config.yml（snakeyaml 逐字） ==")
    import yaml
    for label, blob in [("Spigot", spigot), ("NeoForge", nf)]:
        with zipfile.ZipFile(io.BytesIO(blob)) as zf:
            cfg = zf.read("config.yml").decode("utf-8")
        data = yaml.safe_load(cfg)
        sn = data["server-notify"]
        check(f"{label} 关机文案逐字", sn["shutdown"]["message"] == NEW_SHUTDOWN)
        check(f"{label} 开启文案逐字", sn["restart"]["message"] == NEW_RESTART)
        check(f"{label} 意外关闭文案（正确颜文字）", sn["crash"]["message"] == NEW_CRASH,
              repr(sn["crash"]["message"]))
        check(f"{label} config-version 39", data["config-version"] == 39)
        check(f"{label} restart-window 键零出现", "restart-window" not in sn)
        check(f"{label} config.yml 文本零 restart-window", "restart-window" not in cfg)
        check(f"{label} config.yml 零「轨迹」", "轨迹" not in cfg)
        check(f"{label} config.yml 无 HuHoBot", "huhobot" not in cfg.lower())

    print("== 4. 官网四件口径 ==")
    tutorial = fetch(f"{BASE}/tutorial.html", binary=False)
    index = fetch(f"{BASE}/index.html", binary=False)
    version_raw = fetch(f"{BASE}/version.json", binary=False)
    style = fetch(f"{BASE}/style.css", binary=False)
    check("tutorial 意外关闭新文案", NEW_CRASH.replace("\n", "\\n") in tutorial)
    check("tutorial 零 restart-window", "restart-window" not in tutorial)
    check("tutorial 新口径（不限间隔时长）", "不限间隔时长" in tutorial)
    check("tutorial 新口径（首次安装不发送）", "首次安装不发送" in tutorial)
    check("tutorial 旧口径（普通开机不发送）已删", "普通开机不发送" not in tutorial)
    check("tutorial 零「轨迹」", "轨迹" not in tutorial)
    check("tutorial 无 HuHoBot", "huhobot" not in tutorial.lower())
    check("index 未受影响（md5 基线）", md5(index.encode()) == INDEX_MD5, md5(index.encode()))
    check("version.json 未受影响（md5 基线）", md5(version_raw.encode()) == VERSION_MD5)
    check("style.css 未受影响（md5 基线）", md5(style.encode()) == STYLE_MD5)
    vj = json.loads(version_raw)
    check("version.json 版本仍 1.7.4", vj.get("version") == "1.7.4")

    print("== 5. JAR 字节码（新逻辑在位） ==")
    for label, blob in [("Spigot", spigot), ("NeoForge", nf)]:
        with zipfile.ZipFile(io.BytesIO(blob)) as zf:
            snm = zf.read("cn/kerong/bot/ServerNotifyManager.class")
            cm = zf.read("cn/kerong/penguin/spigot/manager/ConfigManager.class")
        check(f"{label} ServerNotifyManager 新开启日志",
              "检测到上次正常运行后重新启动".encode() in snm)
        check(f"{label} ServerNotifyManager 无窗口 getter",
              b"serverNotifyRestartWindow" not in snm)
        check(f"{label} ConfigManager 无窗口 getter",
              b"serverNotifyRestartWindow" not in cm)
        check(f"{label} ConfigManager restart-window 仅废弃清单",
              cm.count(b"server-notify.restart-window") == 1)
        check(f"{label} ConfigManager 含第六轮误写版收敛源",
              WRONG_CRASH.encode() in cm)
        check(f"{label} ConfigManager 含新文案常量", NEW_CRASH.encode() in cm)

    print(f"\n===== 外网验证：{len(passed)} 通过 / {len(failed)} 失败 =====")
    if failed:
        print("失败项：")
        for name in failed:
            print("  ✗", name)
        sys.exit(1)


if __name__ == "__main__":
    main()
