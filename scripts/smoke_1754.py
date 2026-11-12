#!/usr/bin/env python3
"""1.7.5.4 冒烟运行器（独立 JVM，NeoForge 分发 JAR 类路径 + slf4j-nop）。

覆盖（详见 smoke1754/Smoke1754.java）：
1. 合成事件验身标记透传（author.krg_btn_admin → sender.meta）
2. isAdmin 按钮验身短路（标记放行 / 无标记拒绝 / qq 模式 member_role 回归）
3. layoutRows 排版改版（独占行置顶 + 短按钮一行两个 + AI管理特判）
4. 按钮门禁模式分流（qq 门禁 / MANUAL actionType=2 填输入栏）
5. menu 模板收敛（旧默认升级 / 新默认幂等 / 自定义不动）
6. 全局按钮门禁（GLOBAL_MEMBER / onInteraction 四参 / dispatchAsClicker 公开）
7. 回归（注册表标记 / 键盘权限序列化透传）
"""
import subprocess
import sys
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
NEOFORGE_JAR = REPO / "build" / "dist" / "KERONGPenguin_NeoForge-1.20.4-1.7.5.4.jar"
SLF4J_API = REPO / "scripts" / "slf4j-api-2.0.13.jar"
SLF4J_NOP = REPO / "scripts" / "slf4j-nop-2.0.13.jar"
# JDK：优先 tooling 全量 JDK；不存在时回退系统 JDK
_TOOLING = Path("/home/z/my-project/tooling/jdk21/bin")
if (_TOOLING / "javac").is_file():
    JAVA = str(_TOOLING)
else:
    import shutil
    JAVA = str(Path(shutil.which("javac")).parent)
SRC = REPO / "scripts" / "smoke1754"
OUT = Path("/tmp/smoke1754")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cp = f"{NEOFORGE_JAR}:{SLF4J_API}"
    r = subprocess.run(
        [f"{JAVA}/javac", "-cp", cp, "-d", str(OUT),
         str(SRC / "FakeKeronRuntime.java"), str(SRC / "Smoke1754.java")],
        capture_output=True, text=True, timeout=180)
    if r.returncode != 0:
        print("冒烟编译失败:\n", r.stderr[:3000]); sys.exit(1)
    r = subprocess.run(
        [f"{JAVA}/java", "-cp", f"{OUT}:{NEOFORGE_JAR}:{SLF4J_API}:{SLF4J_NOP}", "Smoke1754"],
        capture_output=True, text=True, timeout=300)
    print(r.stdout)
    if r.returncode != 0:
        print("STDERR:", r.stderr[:2000]); sys.exit(1)


if __name__ == "__main__":
    main()
