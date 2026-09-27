#!/usr/bin/env python3
"""
KERONGPenguin NeoForge 本地冒烟测试（1.5.4.3 起含 kotlin 模块共存场景）。

用法：
    python3 scripts/nf_smoke_test.py [jar] [--kotlin-sim <kotlin-stdlib.jar>]
    [--server-dir <dir>] [--keep]

流程：
  1. 准备本地 NeoForge server（tooling/nf-install/server）：eula、假凭据
     config.yml（bot.app-id=1234567890，走完整机器人启动路径）；
  2. mods/ 只保留目标 jar（可选加 --kotlin-sim 模拟整合包 KFF kotlin.stdlib
     命名模块——1.5.4.3 重定位验证场景）；
  3. 启动服务器，等待 Done；校验模组加载 / 兼容层 / 扫描兜底 / 组件装配 /
     群消息监听 / 鉴权自检（重定位后的 kotlin+okhttp 真实跑通机器人路径）；
  4. 控制台发 stop，校验进程在限时内以退出码 0 干净退出（1.5.4.2 关服修复回归），
     且 kloping 线程池回收日志出现；
  5. 全部通过输出 PASS 汇总，任一失败退出码 1。
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time

DEFAULT_JAR = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_NeoForge-1.21.1-1.5.4.3.jar"
DEFAULT_SERVER = "/home/z/my-project/tooling/nf-install/server"
KOTLIN_SIM_DEFAULT = "/home/z/my-project/tooling/fake-kff-mod.jar"
JAVA = "/usr/bin/java"
ARGS_FILE = "libraries/net/neoforged/neoforge/21.1.248/unix_args.txt"

FAKE_CONFIG = """config-version: 13
serverName: "SmokeTest"
bot:
  app-id: "1234567890"
  secret: "smoke-fake-secret-do-not-use"
  name: "SmokeBot"
  groups: []
chat-format:
  from-game: "[游戏] {message}"
  from-group: "[QQ] {name}: {message}"
"""

results = []


def check(name: str, ok: bool, detail: str = ""):
    results.append((name, ok))
    print(("PASS " if ok else "FAIL ") + name + (f"  {detail}" if detail and not ok else ""))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("jar", nargs="?", default=DEFAULT_JAR)
    parser.add_argument("--kotlin-sim", nargs="?", const=KOTLIN_SIM_DEFAULT, default=None)
    parser.add_argument("--server-dir", default=DEFAULT_SERVER)
    parser.add_argument("--keep", action="store_true", help="保留 mods 与日志（默认清理旧日志）")
    args = parser.parse_args()

    server = args.server_dir
    jar_name = os.path.basename(args.jar)
    version = re.search(r"-(\d+\.\d+\.\d+(?:\.\d+)?)\.jar$", jar_name)
    version = version.group(1) if version else "?"
    log_path = os.path.join(server, "logs", "latest.log")
    stdout_log = "/home/z/my-project/build-logs/nf-smoke-stdout.log"

    # 1. 环境准备
    with open(os.path.join(server, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    os.makedirs(os.path.join(server, "kerongpenguin"), exist_ok=True)
    with open(os.path.join(server, "kerongpenguin", "config.yml"), "w") as f:
        f.write(FAKE_CONFIG)
    mods = os.path.join(server, "mods")
    os.makedirs(mods, exist_ok=True)
    for n in os.listdir(mods):
        os.remove(os.path.join(mods, n))
    shutil.copy(args.jar, os.path.join(mods, jar_name))
    if args.kotlin_sim:
        shutil.copy(args.kotlin_sim, os.path.join(mods, "kotlin-stdlib-kff-sim.jar"))
    if os.path.isfile(log_path):
        os.remove(log_path)

    print(f"冒烟目标：{jar_name}（版本 {version}），kotlin 模拟模块：{'有' if args.kotlin_sim else '无'}")

    # 2. 启动
    boot_start = time.time()
    proc = subprocess.Popen(
        [JAVA, "-Xmx1600M", f"@{os.path.join(server, ARGS_FILE)}", "nogui"],
        cwd=server,
        stdin=subprocess.PIPE,
        stdout=open(stdout_log, "w"),
        stderr=subprocess.STDOUT,
        text=True,
    )

    def read_log() -> str:
        try:
            with open(stdout_log, encoding="utf-8", errors="replace") as f:
                return f.read()
        except FileNotFoundError:
            return ""

    # 等待 Done（最多 5 分钟）
    done_at = None
    deadline = boot_start + 300
    while time.time() < deadline:
        if proc.poll() is not None:
            break
        log = read_log()
        if re.search(r"Done \([0-9.]+s\)", log):
            done_at = time.time()
            break
        time.sleep(2)
    log = read_log()
    check("服务器启动完成（Done）", done_at is not None or "Done (" in log,
          f"进程退出码 {proc.poll()}")
    check("无模块解析崩溃（ResolutionException）", "ResolutionException" not in log)
    check("无启动期 NPE", "NullPointerException" not in log)

    # 3. 机器人路径（Done 后再等 45 秒让异步链路走完）
    if done_at:
        time.sleep(45)
    log = read_log()
    qq_log = ""
    qq_dir = os.path.join(server, "logs", "qq")
    if os.path.isdir(qq_dir):
        for n in sorted(os.listdir(qq_dir), reverse=True):
            if n.startswith("qq-bind-"):
                with open(os.path.join(qq_dir, n), encoding="utf-8", errors="replace") as f:
                    qq_log = f.read()
                break
    full = log + "\n" + qq_log

    check(f"模组列表识别 kerongpenguin {version}", f"KERONGPenguin {version} (kerongpenguin)" in log)
    check("KERONGPenguin NeoForge 已加载", "KERONGPenguin NeoForge 已加载" in log)
    check("QqBindManager 就绪", "QqBindManager 已就绪" in full)
    check("[兼容] 模块化包扫描兼容层已挂载", "模块化包扫描兼容层已挂载" in full)
    check("包扫描兜底/原生扫描产出类（兼容层生效）",
          ("包扫描兜底生效" in full) or ("QQ 机器人组件装配完成" in full))
    check("QQ 机器人客户端初始化（假凭据）", "QQ 机器人客户端初始化（AppID: 1234567890" in full)
    # 假凭据下组件装配会因平台拒签而停止（鉴权失败 11201 / 机器人不存在 10004），
    # 属预期；关键回归信号是扫描器产出 197 类且 SDK 走到平台网关（真凭据才会装配完成）
    check("机器人启动链路完整（装配完成，或平台拒绝假凭据）",
          ("QQ 机器人组件装配完成" in full)
          or ("鉴权失败" in full) or ("机器人不存在" in full))
    check("无扫描器 NPE（classNames is null）", "classNames is null" not in full)
    check("群消息监听已注册", "群消息监听已注册" in full)
    check("鉴权自检已执行（重定位 okhttp 真实出网）", "鉴权自检" in full)
    check("无「QQ 机器人启动失败： null」", "QQ 机器人启动失败： null" not in full)

    # 4. 关服
    stop_start = time.time()
    if proc.poll() is None:
        try:
            proc.stdin.write("stop\n")
            proc.stdin.flush()
        except Exception as e:
            print(f"  （发送 stop 失败：{e}）")
    exit_code = None
    while time.time() - stop_start < 120:
        exit_code = proc.poll()
        if exit_code is not None:
            break
        time.sleep(2)
    if exit_code is None:
        proc.kill()
        check("stop 后 120 秒内进程退出", False, "超时，已强杀")
    else:
        check("stop 后 120 秒内进程退出", True)
    stop_secs = time.time() - stop_start
    log = read_log()
    check("进程退出码 0（干净关服，无挂死）", exit_code == 0, f"实际 {exit_code}，耗时 {stop_secs:.0f}s")
    check("kloping 线程池已回收（SptCompat 停机清扫）",
          "已回收 kloping 线程池" in log or "已回收 kloping 线程池" in read_log())
    # 关服兑底（ServerStopped 后 5 秒 halt）是 1.5.4.2 设计的兑底手段：
    # 世界已保存、资源已回收，halt(0) 与自然退出均属干净关服，只验证总耗时
    check("关服总耗时 < 30 秒", stop_secs < 30, f"实际 {stop_secs:.0f}s")

    failed = [r for r in results if not r[1]]
    print()
    print(f"共 {len(results)} 项，失败 {len(failed)} 项；关服耗时 {stop_secs:.0f} 秒")
    if failed:
        for name, _ in failed:
            print(f"  FAIL: {name}")
        sys.exit(1)
    print("=== NeoForge 冒烟测试全部通过 ===")


if __name__ == "__main__":
    main()
