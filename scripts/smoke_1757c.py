#!/usr/bin/env python3
"""1.7.5.7 再覆盖轮冒烟运行器（独立 JVM，NeoForge 分发 JAR 类路径 + slf4j）。

覆盖（详见 smoke1757c/Smoke1757C.java）：
X1-X2  bot.name 撞名场景（本轮核心：bot.name 写成群里其他机器人昵称，
       艾特那个机器人不再误触发；同场艾特本机器人照常响应）
N1-N8  昵称通道官方源真路径（注入 starter.bot.getInfo 预设官方昵称：
       openid 形态命中 / 其他机器人不命中 / bot 收窄 / 空格容差 / 空昵称 /
       bot=null / 官方昵称取不到不回退 bot.name）
R1-R5b 通道回归（精确 id 两源真路径（含注入的 botSelfId）/ GROUP_AT /
       无艾特 / 文本片段两形态 / 用户场景端到端）
M1-M7  aiReminderMode 删旧键后行为（回归）
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
SRC = REPO / "scripts" / "smoke1757c"
OUT = Path("/tmp/smoke1757c")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cp = f"{NEOFORGE_JAR}:{SLF4J_API}"
    r = subprocess.run(
        [f"{JAVA}/javac", "-encoding", "UTF-8", "-cp", cp, "-d", str(OUT),
         str(SRC / "FakeKeronRuntime.java"), str(SRC / "FakeContextManager.java"),
         str(SRC / "Smoke1757C.java")],
        capture_output=True, text=True, timeout=180)
    if r.returncode != 0:
        print("冒烟编译失败:\n", r.stderr[:3000]); sys.exit(1)

    run = subprocess.run(
        [f"{JAVA}/java", "--add-opens", "jdk.unsupported/sun.misc=ALL-UNNAMED",
         "-cp", f"{OUT}:{NEOFORGE_JAR}:{SLF4J_API}:{SLF4J_NOP}",
         "Smoke1757C"],
        capture_output=True, text=True, timeout=300)
    print(run.stdout[-6000:])
    if run.returncode != 0:
        print("冒烟运行失败:\n", run.stderr[-3000:])
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
