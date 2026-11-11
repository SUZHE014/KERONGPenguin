#!/usr/bin/env python3
"""verify_online_o14：fa 版 1.7.5.1 上线后外网验证（fa JAR 往返 + 官网主线化口径
+ 更新检查源 + 既有产物不回归）。"""
import hashlib
import json
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


EXPECT = {
    "fa": ("35654f3491905d3647f6ad5624083e97", "KERONGPenguin_Fabric-1.20.4-1.7.5.1.jar"),
    "spigot": ("c12cbb1aecc88d20f860a2810c54e683", "KERONGPenguin_Spigot-1.7.5.1.jar"),
    "neoforge": ("1ede29181aff4c8f2a53f3f56977679b", "KERONGPenguin_NeoForge-1.20.4-1.7.5.1.jar"),
}

checks = []


def c(name, ok):
    checks.append((name, ok))


idx = fetch("https://kerong.xin/penguin/index.html")
tut = fetch("https://kerong.xin/penguin/tutorial.html")

# ---- 1. 三端 JAR 往返 md5 ----
for key, (expect, name) in EXPECT.items():
    data = fetch(f"https://kerong.xin/penguin/downloads/{name}", binary=True)
    c(f"O1 {key} JAR 可下且 md5 往返一致（{expect[:8]}）", bool(data) and md5(data) == expect)

# ---- 2. fa 主线化口径（官网） ----
c("O2 下载区含 Fabric 模组版按钮（链接 1.7.5.1）",
  "KERONGPenguin_Fabric-1.20.4-1.7.5.1.jar" in idx and ">⬇ Fabric 模组版</a>" in idx)
c("O3 Fabric 卡片为 1.7.5.1 主线定位",
  "Fabric 模组版 1.7.5.1（MC 1.20.4，Java 21）" in idx
  and "Fabric 测试版 1.7.4.1（MC 1.20.4，Java 21）" not in idx
  and ">⬇ 下载 Fabric 测试版</a>" not in idx)
c("O4 Fabric 卡片绑定口径（/hbkrg qbd 不拦截进服）",
  "QQ 绑定不拦截进服" in idx and "/hbkrg qbd" in idx)
c("O5 Fabric 卡片更新检查说明", "更新检查" in idx and "update-check.enabled" in idx)
c("O6 历史版本表含 Fabric 1.7.5.1 与 1.7.4.1 两行",
  "Fabric 1.7.5.1" in idx and "Fabric 1.7.4.1" in idx)
c("O7 更新日志 v1.7.5.1 含 menu 强制收敛与 fa 主线化",
  "旧帮助文段" in idx and "Fabric 版升入主线发版" in idx)
c("O8 模组版核心行含 Fabric 芯片（MC 1.20.4 及以上）",
  '<span class="core-name">Fabric</span>' in idx and "（MC 1.20.4 及以上，Java 21）" in idx)
c("O9 前置插件表补模组版自动降级说明", "模组版（NeoForge / Fabric）没有这些前置插件" in idx)
c("O10 教程含 fa 绑定差异说明",
  "Fabric 模组版的绑定不拦截进服" in tut and "/hbkrg qbd" in tut)

# ---- 3. version.json（更新检查源，fa 1.7.4.1 升级提示内容） ----
vj = json.loads(fetch("https://kerong.xin/penguin/version.json"))
c("O11 version.json 1.7.5.1 + menu 收敛 + fa 主线化条目",
  vj.get("version") == "1.7.5.1"
  and any("帮助文段" in x for x in vj.get("changelog", []))
  and any("Fabric" in x for x in vj.get("changelog", [])))

# ---- 4. 既有产物与口径不回归 ----
c("O12 fa 1.7.4.1 仍在架（历史表可达）",
  code("https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.4-1.7.4.1.jar") == "200")
c("O13 NeoForge 括号折行样式仍在线（core-note）",
  '<span class="core-note">（MC 1.20.4，1.20.6，1.21.x，Java 21）</span>' in idx)
c("O14 旧 1.7.5 JAR 仍在架", code("https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.5.jar") == "200")
c("O15 旧 1.7.2 JAR 仍 404", code("https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.2.jar") == "404")
c("O16 无源码包链接", "Source-v" not in idx)

failed = [n for n, ok in checks if not ok]
for n, ok in checks:
    print(f"  [{'PASS' if ok else 'FAIL'}] {n}")
print(f"\n共 {len(checks)} 项，通过 {len(checks) - len(failed)}，失败 {len(failed)}")
raise SystemExit(1 if failed else 0)
