#!/usr/bin/env python3
"""1.7.5.1 冒烟运行器（独立 JVM，Spigot 分发 JAR 类路径 + org.bukkit 桩）。

覆盖（详见 smoke1751/Smoke1751.java）：
1. RecentGroupMessages：nextId 轮转 / 环形缓冲容量 16 / 重复投递去重 /
   TTL 过期惰性清理（40034128 分摊修复）
2. MemberRoleCache：登记查询 / composite key 群间隔离 / 角色随消息刷新 /
   TTL 过期（按钮管理员权限修复）
3. 模板升级放宽：normalize 空白容忍 + 特征兜底（未收录中间态默认）+
   自定义不动 + 幂等
4. pageKeyboard 按钮分流不回归（type1/2 + 无 enter）
5. ReplySeqGuard 序号守卫不回归
"""
import subprocess
import sys
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
# Spigot 分发 JAR：fastjson / kotlin-stdlib 原包名（NF JAR 已重定位到
# cn.kerong.shaded.*，import com.alibaba 的测试源只能对 Spigot JAR 编译）；
# org.bukkit 桩由 smoke1751/JavaPlugin.java 提供（MessageTemplates 签名）
SPIGOT_JAR = REPO / "build" / "dist" / "KERONGPenguin_Spigot-1.7.5.1.jar"
JAVA = "/home/z/my-project/tooling/jdk21/bin"
SRC = REPO / "scripts" / "smoke1751"
OUT = Path("/tmp/smoke1751")


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
        [f"{JAVA}/javac", "-cp", f"{OUT}:{SPIGOT_JAR}", "-d", str(OUT), str(SRC / "Smoke1751.java")],
        capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        print("冒烟编译失败:\n", r.stderr[:3000]); sys.exit(1)
    # 3) 运行
    r = subprocess.run(
        [f"{JAVA}/java", "-cp", f"{OUT}:{SPIGOT_JAR}", "Smoke1751"],
        capture_output=True, text=True, timeout=180)
    print(r.stdout)
    if r.returncode != 0:
        print("STDERR:", r.stderr[:1500]); sys.exit(1)


if __name__ == "__main__":
    main()
