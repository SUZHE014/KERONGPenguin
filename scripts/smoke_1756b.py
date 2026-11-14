#!/usr/bin/env python3
"""1.7.5.6 主线修复轮（覆盖轮）冒烟运行器（独立 JVM，NeoForge 分发 JAR 类路径 + slf4j）。

覆盖（详见 smoke1756b/Smoke1756B.java）：
A1 模板升级=完整替换（释放记录端到端 / 表兜底 / 非表模板收敛 / 自改不动 /
   幂等 / BOM 容差）
A4 AI 提醒方式三模式（at/quote/none + 中文 + 非法兜底 + 空串回退旧键）
A5 按钮互动冷却（真实时钟 5 秒 / 键隔离 / 穿透 / clearAll / 配置关闭）
"""
import subprocess
import sys
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
NEOFORGE_JAR = REPO / "build" / "dist" / "KERONGPenguin_NeoForge-1.20.4-1.7.5.6.jar"
SLF4J_API = REPO / "scripts" / "slf4j-api-2.0.13.jar"
SLF4J_NOP = REPO / "scripts" / "slf4j-nop-2.0.13.jar"
_TOOLING = Path("/home/z/my-project/tooling/jdk21/bin")
if (_TOOLING / "javac").is_file():
    JAVA = str(_TOOLING)
else:
    import shutil
    JAVA = str(Path(shutil.which("javac")).parent)
SRC = REPO / "scripts" / "smoke1756b"
OUT = Path("/tmp/smoke1756b")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cp = f"{NEOFORGE_JAR}:{SLF4J_API}"
    r = subprocess.run(
        [f"{JAVA}/javac", "-encoding", "UTF-8", "-cp", cp, "-d", str(OUT),
         str(SRC / "FakeKeronRuntime.java"), str(SRC / "Smoke1756B.java")],
        capture_output=True, text=True, timeout=180)
    if r.returncode != 0:
        print("冒烟编译失败:\n", r.stderr[:3000]); sys.exit(1)

    run = subprocess.run(
        [f"{JAVA}/java", "-cp", f"{OUT}:{NEOFORGE_JAR}:{SLF4J_API}:{SLF4J_NOP}",
         "Smoke1756B"],
        capture_output=True, text=True, timeout=300)
    print(run.stdout[-6000:])
    if run.returncode != 0:
        print("冒烟运行失败:\n", run.stderr[-3000:])
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
