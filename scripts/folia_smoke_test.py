#!/usr/bin/env python3
"""
Folia 兼容真机冒烟（1.5.7.3）：Folia 1.20.4 + Paper 1.18.2 双场景。

流程（每个场景）：
  下载服务器 JAR（fill.papermc.io，带本地缓存） → 布置插件 →
  单进程内完成 启动 → 等待 Done → /hb help → /qq help → stop →
  断言日志标记（加载/启用/命令响应/干净停用/零调度异常）。

用法：
    python3 scripts/folia_smoke_test.py [jar路径]
    缺省验证 build/dist/KERONGPenguin_Spigot-1.5.7.3.jar。

说明：
- 服务器启动参数：flat 世界 + 视距 4 + 无怪物（低配环境快速启动）；
- QQ 机器人无凭据时按 WARN 跳过（正常预期，不视为失败）；
- 运行产物写入 scripts/folia-smoke-work/<场景>/（已在 .gitignore）。
"""
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request

JAR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    "/home/z/my-project/penguin-git", "build", "dist", "KERONGPenguin_Spigot-1.5.7.3.jar")
WORK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "folia-smoke-work")

# (场景名, 下载 URL)——fill.papermc.io 对象直链（版本升级时更新 build 号即可）
SERVERS = {
    "folia-1.20.4": "https://fill-data.papermc.io/v1/objects/"
                    "b0d55be3ba19cb6e040f0e7fba400e5224a271fbc7db73a9683aef7468425af9/folia-1.20.4-31.jar",
    "paper-1.18.2": "https://fill-data.papermc.io/v1/objects/"
                    "0578f18f4d632b494b468ec56b3b414b5b56fea087ee7d39cf6dcdf4c9d01f05/paper-1.18.2-388.jar",
}

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS  " if ok else "FAIL  ") + name + (f"  [{detail}]" if detail and not ok else ""))


def prepare(scene: str, server_url: str) -> str:
    """布置冒烟目录，返回服务器 JAR 路径。"""
    d = os.path.join(WORK, scene)
    os.makedirs(os.path.join(d, "plugins"), exist_ok=True)
    server_jar = os.path.join(d, "server.jar")
    if not os.path.isfile(server_jar):
        print(f"下载 {scene} ...")
        urllib.request.urlretrieve(server_url, server_jar)
    with open(os.path.join(d, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    with open(os.path.join(d, "server.properties"), "w") as f:
        f.write("level-type=minecraft\\:flat\nonline-mode=false\nview-distance=4\n"
                "simulation-distance=4\nspawn-monsters=false\nmax-players=5\n")
    plugin_copy = os.path.join(d, "plugins", os.path.basename(JAR))
    shutil.copyfile(JAR, plugin_copy)
    log = os.path.join(d, "console.log")
    if os.path.isfile(log):
        os.remove(log)
    return server_jar


def run_scene(scene: str, server_jar: str, timeout_s: int = 560) -> str:
    """单进程完成 启动→命令→停机，返回日志文本。"""
    d = os.path.dirname(server_jar)
    log_path = os.path.join(d, "console.log")
    feeder = (
        "for i in $(seq 1 150); do grep -q 'Done (' console.log 2>/dev/null && break; "
        "sleep 3; done; sleep 3; echo 'hb help'; sleep 6; echo 'qq help'; sleep 6; echo 'stop'"
    )
    cmd = f"cd {d} && ( {feeder} ) | timeout {timeout_s} java -Xms512M -Xmx1400M -jar server.jar nogui > console.log 2>&1"
    subprocess.run(["bash", "-c", cmd], timeout=timeout_s + 60)
    with open(log_path, encoding="utf-8", errors="replace") as f:
        return f.read()


def verify(scene: str, log: str):
    """断言插件在该场景的关键日志标记。"""
    tag = f"[{scene}]"
    check(f"{tag} 插件被服务器加载（folia-supported 声明生效）",
          re.search(r"Loading( server plugin)? KERONGPenguin v", log) is not None)
    check(f"{tag} 插件启用成功", "Enabling KERONGPenguin" in log)
    check(f"{tag} onEnable 完成（HuHoBot Penguin 已加载）", "HuHoBot Penguin 已加载" in log)
    check(f"{tag} 服务器完整启动（Done）", re.search(r"Done \([\d.]+s\)", log) is not None)
    check(f"{tag} /hb help 命令响应", "===== /hb 命令帮助 =====" in log)
    check(f"{tag} /qq help 命令响应", "/qq help" in log and "查看此帮助" in log)
    check(f"{tag} 插件干净停用（Disabling）", "Disabling KERONGPenguin" in log)
    bad = [pat for pat in ("UnsupportedOperationException", "NoClassDefFoundError",
                           "ClassNotFoundException") if pat in log]
    check(f"{tag} 零调度/类加载异常", not bad, f"出现 {bad}")
    # 插件数据目录完整生成
    d = os.path.join(WORK, scene, "plugins", "KERONGPenguin")
    check(f"{tag} 插件数据目录生成（QUUID/img/Markdown）",
          all(os.path.isdir(os.path.join(d, x)) for x in ("QUUID", "img", "Markdown")))


def main() -> None:
    if not os.path.isfile(JAR):
        raise SystemExit(f"JAR 不存在: {JAR}")
    for scene, url in SERVERS.items():
        server_jar = prepare(scene, url)
        print(f"--- 场景 {scene} 运行中 ---")
        log = run_scene(scene, server_jar)
        verify(scene, log)
    failed = [r for r in results if not r[1]]
    print()
    print(f"共 {len(results)} 项，失败 {len(failed)} 项")
    if failed:
        for name, _, detail in failed:
            print(f"  FAIL: {name} {detail}")
        sys.exit(1)
    print("=== Folia + Paper 双场景真机冒烟全部通过 ===")


if __name__ == "__main__":
    main()
