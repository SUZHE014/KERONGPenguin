#!/usr/bin/env python3
"""1.7.5.5 AI 覆盖轮冒烟运行器（独立 JVM，Spigot 分发 JAR 类路径 + slf4j）。

覆盖（详见 smoke1755ai/Smoke1755AI.java）：
1. system prompt 附注（[at: 用法 / 克制措辞 / 昵称一致性）
2. 入向解析（mentions 普通成员还原 / 机器人剥掉 / 无昵称剥掉 / 频道格式 / 未知剥掉 / 快速路径）
3. 入向目录兜底（mentions 缺失 → OpenIdDirectory.directory 命中）
4. 出向一级（本条 mentions → <@openid>；无标记快速路径）
5. 出向二级（反向目录 / 原样键 / 小写键）
6. 出向降级（未知昵称纯文本 / 空标记 / 多标记混合）
7. 反查 API（null/空/未知/精确/小写/大写边界）
8. 接线形态（sendReply 三参 boolean + $default 桥）
"""
import subprocess
import sys
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
SPIGOT_JAR = REPO / "build" / "dist" / "KERONGPenguin_Spigot-1.7.5.5.jar"
SLF4J_API = REPO / "scripts" / "slf4j-api-2.0.13.jar"
SLF4J_NOP = REPO / "scripts" / "slf4j-nop-2.0.13.jar"
_TOOLING = Path("/home/z/my-project/tooling/jdk21/bin")
if (_TOOLING / "javac").is_file():
    JAVA = str(_TOOLING)
else:
    import shutil
    JAVA = str(Path(shutil.which("javac")).parent)
SRC = REPO / "scripts" / "smoke1755ai"
OUT = Path("/tmp/smoke1755ai")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cp = f"{SPIGOT_JAR}:{SLF4J_API}"
    r = subprocess.run(
        [f"{JAVA}/javac", "-encoding", "UTF-8", "-cp", cp, "-d", str(OUT),
         str(SRC / "Smoke1755AI.java")],
        capture_output=True, text=True, timeout=180)
    if r.returncode != 0:
        print("冒烟编译失败:\n", r.stderr[:3000]); sys.exit(1)

    r = subprocess.run(
        [f"{JAVA}/java", "-cp", f"{OUT}:{cp}:{SLF4J_NOP}",
         "-Dfile.encoding=UTF-8", "Smoke1755AI"],
        capture_output=True, text=True, timeout=180)
    print(r.stdout)
    if r.returncode != 0:
        print("冒烟运行失败(Exit %s):\n" % r.returncode, r.stderr[:3000]); sys.exit(1)
    tail = [line for line in r.stdout.splitlines() if "冒烟汇总" in line]
    print("结果:", tail[-1] if tail else "(无汇总行)")
    n_pass = r.stdout.count("[PASS]")
    n_fail = r.stdout.count("[FAIL]")
    if n_fail > 0:
        sys.exit(1)
    print(f"冒烟运行器完成：{n_pass} 项通过 / {n_fail} 项失败")


if __name__ == "__main__":
    main()
