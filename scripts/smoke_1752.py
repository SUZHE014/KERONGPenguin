#!/usr/bin/env python3
"""1.7.5.2 冒烟运行器（独立 JVM，NeoForge 分发 JAR 类路径 + slf4j-nop）。

覆盖（详见 smoke1752/Smoke1752.java）：
1. kick-message 新默认渲染（绑定码口径 / 单行字符串写法 / 自定义列表 / 键缺失回退）
2. ConfigManager v43 → v44 迁移（两版旧默认收敛 / 自定义不动 / 单行不动 / 自定义值保留）
3. menu 升级表收敛（1.7.5 旧默认 / BOM 容忍 / 幂等 / 无特征自定义不动 / 5 键回表）
4. KERONG.enforceNoNativeWhitelist 默认方法判定链（关闭+提醒 / 两个零操作分支）
"""
import subprocess
import sys
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
NEOFORGE_JAR = REPO / "build" / "dist" / "KERONGPenguin_NeoForge-1.20.4-1.7.5.2.jar"
SLF4J_API = REPO / "scripts" / "slf4j-api-2.0.13.jar"
SLF4J_NOP = REPO / "scripts" / "slf4j-nop-2.0.13.jar"
# JDK：优先历史构建机 tooling 全量 JDK；不存在时回退系统 JDK（javac/java 同源）
_TOOLING = Path("/home/z/my-project/tooling/jdk21full/bin")
if (_TOOLING / "javac").is_file():
    JAVA = str(_TOOLING)
elif Path("/usr/lib/jvm/java-21-openjdk-amd64/bin/javac").is_file():
    JAVA = "/usr/lib/jvm/java-21-openjdk-amd64/bin"
else:
    import shutil
    JAVA = str(Path(shutil.which("javac")).parent)
SRC = REPO / "scripts" / "smoke1752"
OUT = Path("/tmp/smoke1752")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cp = f"{NEOFORGE_JAR}:{SLF4J_API}"
    r = subprocess.run(
        [f"{JAVA}/javac", "-cp", cp, "-d", str(OUT),
         str(SRC / "FakeKeronRuntime.java"), str(SRC / "Smoke1752.java")],
        capture_output=True, text=True, timeout=180)
    if r.returncode != 0:
        print("冒烟编译失败:\n", r.stderr[:3000]); sys.exit(1)
    r = subprocess.run(
        [f"{JAVA}/java", "-cp", f"{OUT}:{NEOFORGE_JAR}:{SLF4J_API}:{SLF4J_NOP}", "Smoke1752"],
        capture_output=True, text=True, timeout=300)
    print(r.stdout)
    if r.returncode != 0:
        print("STDERR:", r.stderr[:2000]); sys.exit(1)


if __name__ == "__main__":
    main()
