#!/usr/bin/env python3
"""1.7.5.5 冒烟运行器（独立 JVM，NeoForge 分发 JAR 类路径 + slf4j-nop）。

覆盖（详见 smoke1755/Smoke1755.java）：
1. encodeAscii 原版半块渲染（回归）
2. encodeCjk 全角方块渲染（■/U+3000，GBK 可编码）
3. encodePlain 纯 ASCII 渲染（##/空格对）
4. 三渲染器同一矩阵（逐格一致性）
5. JDK 编码语义（GBK/UTF-8 canEncode）
6. 渲染选择逻辑镜像（分级选择）
7. GBK 安全性端到端
8. 回归（静区/空内容异常）
"""
import subprocess
import sys
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
NEOFORGE_JAR = REPO / "build" / "dist" / "KERONGPenguin_NeoForge-1.20.4-1.7.5.5.jar"
SLF4J_API = REPO / "scripts" / "slf4j-api-2.0.13.jar"
SLF4J_NOP = REPO / "scripts" / "slf4j-nop-2.0.13.jar"
_TOOLING = Path("/home/z/my-project/tooling/jdk21/bin")
if (_TOOLING / "javac").is_file():
    JAVA = str(_TOOLING)
else:
    import shutil
    JAVA = str(Path(shutil.which("javac")).parent)
SRC = REPO / "scripts" / "smoke1755"
OUT = Path("/tmp/smoke1755")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cp = f"{NEOFORGE_JAR}:{SLF4J_API}"
    r = subprocess.run(
        [f"{JAVA}/javac", "-cp", cp, "-d", str(OUT),
         str(SRC / "Smoke1755.java")],
        capture_output=True, text=True, timeout=180)
    if r.returncode != 0:
        print("冒烟编译失败:\n", r.stderr[:3000]); sys.exit(1)
    r = subprocess.run(
        [f"{JAVA}/java", "-cp", f"{OUT}:{NEOFORGE_JAR}:{SLF4J_API}:{SLF4J_NOP}", "Smoke1755"],
        capture_output=True, text=True, timeout=300)
    print(r.stdout)
    if r.returncode != 0:
        print("STDERR:", r.stderr[:2000]); sys.exit(1)
    sys.stdout.flush()
    os_exit()


def os_exit():
    import os
    os._exit(0)


if __name__ == "__main__":
    main()
