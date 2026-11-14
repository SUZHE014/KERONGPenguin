#!/usr/bin/env python3
"""1.7.5.7 覆盖轮冒烟运行器（独立 JVM，NeoForge 分发 JAR 类路径 + slf4j）。

覆盖（详见 smoke1757b/Smoke1757B.java）：
N1-N7  昵称通道判定（openid 形态昵称命中 / 其他机器人不命中 / bot 收窄 /
       空格容差 / 空昵称 / bot=null）
R1-R5  通道回归（精确 id / GROUP_AT / 无艾特 / 文本片段 / 用户场景端到端）
M1-M7  aiReminderMode 删旧键后行为（默认 quote / 不再回退 / 中文三值 / 兜底）
"""
import subprocess
import sys
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
NEOFORGE_JAR = REPO / "build" / "dist" / "KERONGPenguin_NeoForge-1.20.4-1.7.5.7.jar"
SLF4J_API = REPO / "scripts" / "slf4j-api-2.0.13.jar"
SLF4J_NOP = REPO / "scripts" / "slf4j-nop-2.0.13.jar"
_TOOLING = Path("/home/z/my-project/tooling/jdk21/bin")
if (_TOOLING / "javac").is_file():
    JAVA = str(_TOOLING)
else:
    import shutil
    JAVA = str(Path(shutil.which("javac")).parent)
SRC = REPO / "scripts" / "smoke1757b"
OUT = Path("/tmp/smoke1757b")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cp = f"{NEOFORGE_JAR}:{SLF4J_API}"
    r = subprocess.run(
        [f"{JAVA}/javac", "-encoding", "UTF-8", "-cp", cp, "-d", str(OUT),
         str(SRC / "FakeKeronRuntime.java"), str(SRC / "Smoke1757B.java")],
        capture_output=True, text=True, timeout=180)
    if r.returncode != 0:
        print("冒烟编译失败:\n", r.stderr[:3000]); sys.exit(1)

    run = subprocess.run(
        [f"{JAVA}/java", "-cp", f"{OUT}:{NEOFORGE_JAR}:{SLF4J_API}:{SLF4J_NOP}",
         "Smoke1757B"],
        capture_output=True, text=True, timeout=300)
    print(run.stdout[-6000:])
    if run.returncode != 0:
        print("冒烟运行失败:\n", run.stderr[-3000:])
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
