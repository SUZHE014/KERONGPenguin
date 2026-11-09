#!/usr/bin/env python3
"""1.7.5 部署后外网验证（verify_online_o9）。

从公网拉取官网与产物，核对：版本号三件套 / 更新日志只含当前版本 / 下载链接
可达且 md5 与本地一致 / 旧产物未受影响 / JAR 内 config-version 与新命令键 /
Fabric 卡片仍在「其他版本」区。
"""
import hashlib
import json
import subprocess
import sys
import zipfile
import io

BASE = "https://kerong.xin/penguin"
LOCAL = {
    "spigot": ("/home/z/my-project/penguin-git/build/dist/KERONGPenguin_Spigot-1.7.5.jar",
               "d52b520c4897b44d41fce2b1c054c514"),
    "neoforge": ("/home/z/my-project/penguin-git/build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.5.jar",
                 "29fcdf2efa7836125f683c0324ebb8d1"),
    "source": ("/home/z/my-project/penguin-git/build/dist/KERONGPenguin-Source-v1.7.5.zip",
               "053dd9fb79db23bbb6e9225bea2e85f3"),
}
results = []

def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" —— {detail}" if detail and not ok else ""))

def fetch(url, binary=False):
    r = subprocess.run(["curl", "-sS", "--max-time", "60", url], capture_output=True,
                       text=not binary, timeout=90)
    if r.returncode != 0:
        return None
    return r.stdout if not binary else r.stdout.encode("latin1", "ignore") if isinstance(r.stdout, str) else r.stdout

def fetch_bytes(url):
    r = subprocess.run(["curl", "-sS", "--max-time", "120", url], capture_output=True, timeout=150)
    return r.stdout if r.returncode == 0 else None

print("== A. 官网三件 ==")
index = fetch(f"{BASE}/index.html")
check("index.html 可达", bool(index))
for key in ["版本 1.7.5", "v1.7.5", "KERONGPenguin_Spigot-1.7.5.jar",
            "模组版</b>（MC 1.20.4，1.20.6，1.21.x，Java 21）",
            "Fabric 测试版 1.7.4.1", "NeoForge 1.7.5"]:
    check(f"index.html 含 {key[:28]}", index and key in index)
check("index.html 不含 PlaceholderAPI 说明字段", index and "PlaceholderAPI 为变量插件" not in index)
check("index.html 不含覆盖版字段", index and "覆盖版" not in index)

vj_text = fetch(f"{BASE}/version.json")
check("version.json 可达", bool(vj_text))
try:
    vj = json.loads(vj_text)
    check("version.json version=1.7.5", vj.get("version") == "1.7.5")
    cl = vj.get("changelog", [])
    check("changelog 只含当前版本条目（无历史版本号前缀）",
          all(not str(c).startswith(("1.7.4", "1.7.3", "1.7.2", "1.7.1", "1.7.0"))
              for c in cl) and len(cl) >= 5)
    check("changelog 含菜单/AI管理/第三方连接失败条目",
          any("/菜单" in str(c) for c in cl) and any("AI管理" in str(c) for c in cl) and
          any("第三方连接失败" in str(c) for c in cl))
except Exception as e:
    check("version.json 解析", False, str(e))

tut = fetch(f"{BASE}/tutorial.html")
check("tutorial.html 可达", bool(tut))
check("tutorial 含 /菜单 与 /AI管理", tut and "<code>/菜单</code>" in tut and "<code>/AI管理 开|关|清除</code>" in tut)
check("tutorial 旧命令引用清零",
      tut and "/帮助</code>" not in tut and "<code>/AI对话上下文" not in tut and "<code>/清除当前上下文" not in tut)

print("== B. 产物下载与 md5 ==")
for key, (path, md5) in LOCAL.items():
    url = f"{BASE}/downloads/{path.split('/')[-1]}"
    data = fetch_bytes(url)
    ok = data is not None and hashlib.md5(data).hexdigest() == md5
    check(f"{key} 下载 md5 一致", ok,
          f"remote={hashlib.md5(data).hexdigest() if data else 'N/A'} expect={md5}")
    if key == "source":
        url2 = f"https://kerong.xin/penguin-source/{path.split('/')[-1]}"
        data2 = fetch_bytes(url2)
        check("source 第二处（penguin-source/）md5 一致",
              data2 is not None and hashlib.md5(data2).hexdigest() == md5)

print("== C. JAR 内容（在线下载件） ==")
data = fetch_bytes(f"{BASE}/downloads/KERONGPenguin_Spigot-1.7.5.jar")
with zipfile.ZipFile(io.BytesIO(data)) as z:
    cfg = z.read("config.yml").decode("utf-8")
    check("Spigot JAR config-version 41", "config-version: 41" in cfg)
    check("Spigot JAR commands 含 菜单/AI管理", "  菜单: true" in cfg and "  AI管理: true" in cfg)
    check("Spigot JAR 旧命令键零出现", "帮助: true" not in cfg and "AI对话上下文" not in cfg and "清除当前上下文" not in cfg)
    names = z.namelist()
    check("JAR 含 Markdown/menu.md 与 ai-manage-disabled.md",
          "Markdown/menu.md" in names and "Markdown/ai-manage-disabled.md" in names)
    check("JAR 不含 Markdown/help.md", "Markdown/help.md" not in names)
    check("JAR 含 InteractionFastPath.class", "cn/kerong/bot/events/InteractionFastPath.class" in names)
    unban = z.read("Markdown/unblacklist-list.md").decode("utf-8")
    check("解除黑名单模板新末句", "可以按下对应的按钮，或者艾特对方发送 /解除黑名单 解除" in unban)

print("== D. 旧产物未受影响 ==")
for old in ["KERONGPenguin_Spigot-1.7.4.1.jar", "KERONGPenguin_NeoForge-1.20.4-1.7.4.1.jar",
            "KERONGPenguin_Fabric-1.20.4-1.7.4.1.jar"]:
    r = subprocess.run(["curl", "-sSI", "--max-time", "40", f"{BASE}/downloads/{old}"],
                       capture_output=True, text=True, timeout=60)
    check(f"旧产物 {old.split('-', 1)[1][:24]} 仍在", "200" in r.stdout.split("\n")[0] if r.stdout else False)

print("== E. GitHub 远端 ==")
r = subprocess.run(["curl", "-sS", "--max-time", "40",
                    "https://api.github.com/repos/SUZHE014/KERONGPenguin/branches/main"],
                   capture_output=True, text=True, timeout=60)
check("GitHub main 指向本轮提交", '"sha": "40d65348310c31f57bf09e96774fce87c5d1f78f"' in r.stdout)

fails = [r for r in results if not r[1]]
print(f"\n共 {len(results)} 项，通过 {len(results) - len(fails)}，失败 {len(fails)}")
if fails:
    for name, _ in fails:
        print(f"  FAIL: {name}")
    sys.exit(1)
print("VERIFY_ONLINE_O9_OK")
