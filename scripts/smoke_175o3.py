#!/usr/bin/env python3
"""1.7.5 覆盖第三轮冒烟运行器（独立 JVM，Spigot 分发 JAR 类路径 + org.bukkit 桩）。

覆盖（详见 smoke175o3/Smoke175o3.java）：
1. RecentGroupMessages：登记 / 读取 / 群间隔离 / 空值忽略 / TTL 过期惰性清理
2. pageKeyboard：需补参命令 actionType=2 裸命令复制进输入栏、无参命令
   actionType=1 回调带 krg:cmd: 前缀、多页翻页行
3. Keyboards 指令按钮 type=2 序列化（无 enter:true）
4. 模板收敛端到端：menu / unblacklist-list / full-amount-toggled 多代旧默认
   → 第三轮新口径，自定义不动，幂等
5. HumanReadableStateFile：旧 [full-forwarding] 段不再解析、保存后消失
6. MenuButtons.onInteraction 回应码
7. ReplySeqGuard.allocateBase 序号基数错开
"""
import subprocess
import sys
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
# Spigot 分发 JAR：fastjson / kotlin-stdlib 原包名（NF JAR 已重定位到
# cn.kerong.shaded.*，import com.alibaba 的测试源只能对 Spigot JAR 编译）；
# org.bukkit 桩由 smoke175o3/JavaPlugin.java 提供（MessageTemplates 签名）
SPIGOT_JAR = REPO / "build" / "dist" / "KERONGPenguin_Spigot-1.7.5.jar"
JAVA = "/home/z/my-project/tooling/jdk21/bin"
SRC = REPO / "scripts" / "smoke175o3"
OUT = Path("/tmp/smoke175o3")


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
        [f"{JAVA}/javac", "-cp", f"{OUT}:{SPIGOT_JAR}", "-d", str(OUT), str(SRC / "Smoke175o3.java")],
        capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        print("冒烟编译失败:\n", r.stderr[:3000]); sys.exit(1)
    # 3) 运行
    r = subprocess.run(
        [f"{JAVA}/java", "-cp", f"{OUT}:{SPIGOT_JAR}", "Smoke175o3"],
        capture_output=True, text=True, timeout=180)
    print(r.stdout)
    if r.returncode != 0:
        print("STDERR:", r.stderr[:1500]); sys.exit(1)


if __name__ == "__main__":
    main()
