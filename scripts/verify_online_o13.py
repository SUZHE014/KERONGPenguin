#!/usr/bin/env python3
"""verify_online_o13：1.7.5.1 覆盖版部署后外网验证（双 JAR md5 往返 + 官网折行生效 + o12 口径保持）。"""
import hashlib
import subprocess
import tempfile


def fetch(url, binary=False, timeout=60):
    r = subprocess.run(["curl", "-s", "-m", str(timeout), url], capture_output=True)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def code(url):
    r = subprocess.run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-m", "30", url],
                       capture_output=True, text=True)
    return r.stdout.strip()


def md5(data):
    return hashlib.md5(data).hexdigest()


# 本地期望值（构建机产物）
LOCAL = {
    "spigot": ("c12cbb1aecc88d20f860a2810c54e683", "KERONGPenguin_Spigot-1.7.5.1.jar"),
    "neoforge": ("1ede29181aff4c8f2a53f3f56977679b", "KERONGPenguin_NeoForge-1.20.4-1.7.5.1.jar"),
}

checks = []


def c(name, ok):
    checks.append((name, ok))


idx = fetch("https://kerong.xin/penguin/index.html")
css = fetch("https://kerong.xin/penguin/style.css")

# ---- 1. 双 JAR 往返 md5 ----
for key, (expect, name) in LOCAL.items():
    with tempfile.NamedTemporaryFile() as f:
        data = fetch(f"https://kerong.xin/penguin/downloads/{name}", binary=True)
        c(f"O1 {key} JAR 可下且 md5 往返一致（{expect[:8]}）", bool(data) and md5(data) == expect)

# ---- 2. 官网折行修复上线 ----
c("O2 NeoForge 芯片 core-name/core-note 结构上线",
  '<span class="core-name">NeoForge</span>' in idx
  and '<span class="core-note">（MC 1.20.4，1.20.6，1.21.x，Java 21）</span>' in idx)
c("O3 style.css flex-wrap 折行上线",
  "flex-wrap:wrap" in css and ".core .core-note{white-space:normal;overflow-wrap:anywhere" in css)
c("O4 旧 nowrap 整体锁死样式已下线",
  ".core{display:inline-flex;align-items:center;white-space:nowrap" not in css)
c("O5 括号版本文本完整（未丢内容）",
  "（MC 1.20.4，1.20.6，1.21.x，Java 21）" in idx)

# ---- 3. o12 口径保持 ----
c("O6 更新日志最老为 v1.7.3",
  idx.count('class="version"') == 6 and "v1.7.3<" in idx and "v1.7.2" not in idx)
c("O7 无源码包字段/链接", "独立源码包" not in idx and "Source-v" not in idx)
c("O8 Fabric 主系已测试版本口径",
  "主系已测试版本</b>：1.20.4，1.21，1.21.6" in idx and "支持版本</b>：1.20.4 及以上" in idx)
c("O9 fa 1.7.4.1 冻结在架",
  code("https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.4-1.7.4.1.jar") == "200")
c("O10 旧 1.7.5 JAR 仍在架（历史表可达）",
  code("https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.5.jar") == "200")
c("O11 旧 1.7.2 JAR 仍 404",
  code("https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.2.jar") == "404")

# ---- 4. version.json 与新 JAR 对应 ----
vj = fetch("https://kerong.xin/penguin/version.json")
c("O12 version.json 仍为 1.7.5.1", '"version": "1.7.5.1"' in vj)

failed = [n for n, ok in checks if not ok]
for n, ok in checks:
    print(f"  [{'PASS' if ok else 'FAIL'}] {n}")
print(f"\n共 {len(checks)} 项，通过 {len(checks) - len(failed)}，失败 {len(failed)}")
raise SystemExit(1 if failed else 0)
