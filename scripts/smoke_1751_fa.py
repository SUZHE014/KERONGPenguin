#!/usr/bin/env python3
"""fa 版冒烟运行器：对 Fabric 分发 JAR 跑与 Spigot 同源的模板/菜单行为冒烟
（Smoke1751o2 复用），验证 fa 打包（重映射 + 底座合并）后共享类可加载、
menu 强制重置行为与 Spigot 版一致。"""
import subprocess
import sys
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
FA_JAR = REPO / "build" / "dist" / "KERONGPenguin_Fabric-1.20.4-1.7.5.1.jar"
JAVA = "/home/z/my-project/tooling/jdk-21.0.13+9/bin"
SRC = REPO / "scripts" / "smoke1751o2"
OUT = Path("/tmp/smoke1751fa")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    r = subprocess.run(
        [f"{JAVA}/javac", "-d", str(OUT), str(SRC / "JavaPlugin.java")],
        capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        print("桩编译失败:\n", r.stderr[:1500]); sys.exit(1)
    r = subprocess.run(
        [f"{JAVA}/javac", "-cp", f"{OUT}:{FA_JAR}", "-d", str(OUT), str(SRC / "Smoke1751o2.java")],
        capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        print("fa 冒烟编译失败:\n", r.stderr[:3000]); sys.exit(1)
    r = subprocess.run(
        [f"{JAVA}/java", "-cp", f"{OUT}:{FA_JAR}", "Smoke1751o2"],
        capture_output=True, text=True, timeout=180)
    print(r.stdout)
    if r.returncode != 0:
        print("STDERR:", r.stderr[:1500]); sys.exit(1)


if __name__ == "__main__":
    main()
