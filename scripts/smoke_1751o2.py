#!/usr/bin/env python3
"""1.7.5.1 覆盖版冒烟运行器（独立 JVM，Spigot 分发 JAR 类路径 + org.bukkit 桩）。

覆盖（详见 smoke1751o2/Smoke1751o2.java）：
1. resetSystemTemplates：未知历史形态 / 已知旧默认 / 幂等 / 缺文件 / 空文件
   （menu 系统模板强制收敛，替代特征匹配升级）
2. .bak 备份：重置前留一份、幂等路径不覆盖
3. 升级表 menu 条目移除 + 其余 4 条保留
4. 其他模板特征升级不受影响（unblacklist-list 回归）
5. render(menu) 出口文案（标题+版本行、无帮助文段）
"""
import subprocess
import sys
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
SPIGOT_JAR = REPO / "build" / "dist" / "KERONGPenguin_Spigot-1.7.5.1.jar"
JAVA = "/home/z/my-project/tooling/jdk-21.0.13+9/bin"
SRC = REPO / "scripts" / "smoke1751o2"
OUT = Path("/tmp/smoke1751o2")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    # 1) 编译 org.bukkit 桩
    r = subprocess.run(
        [f"{JAVA}/javac", "-d", str(OUT), str(SRC / "JavaPlugin.java")],
        capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        print("桩编译失败:\n", r.stderr[:1500]); sys.exit(1)
    # 2) 编译冒烟主类（桩类路径在前）
    r = subprocess.run(
        [f"{JAVA}/javac", "-cp", f"{OUT}:{SPIGOT_JAR}", "-d", str(OUT), str(SRC / "Smoke1751o2.java")],
        capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        print("冒烟编译失败:\n", r.stderr[:3000]); sys.exit(1)
    # 3) 运行
    r = subprocess.run(
        [f"{JAVA}/java", "-cp", f"{OUT}:{SPIGOT_JAR}", "Smoke1751o2"],
        capture_output=True, text=True, timeout=180)
    print(r.stdout)
    if r.returncode != 0:
        print("STDERR:", r.stderr[:1500]); sys.exit(1)


if __name__ == "__main__":
    main()
