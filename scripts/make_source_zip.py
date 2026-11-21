#!/usr/bin/env python3
"""按当前 HEAD 重打官网源码包（只含 git 跟踪文件，zip 注释 = 提交哈希）。
用法：python3 scripts/make_source_zip.py [输出路径]
仿照五轮会话产出的 KERONGPenguin_v1.7.3_source.zip 结构（759 文件口径）。"""
import os
import pathlib
import subprocess
import sys
import zipfile

# 仓库根 = 脚本所在目录的上一级（旧版硬编码 /home/z/my-project/penguin-git，
# 沙盒重置后仓库目录名可能变化，改为跟随脚本位置自适应）
REPO = str(pathlib.Path(__file__).resolve().parent.parent)
NAME = sys.argv[1] if len(sys.argv) > 1 else \
    os.path.join(REPO, "build", "dist", "KERONGPenguin_v1.7.6.7_source.zip")


def excluded(rel):
    """源码包排除（1.7.5.4 轮口径：git add -A 时代起仓库含全部构建产物，
    git ls-files 全量打包 331MB+ 不可用；排除后 814~822 文件 / 约 9MB，
    与官网历史源码包口径一致）：
    build / .gradle / scripts/deps-work / 任意层 build 目录 / 二进制件。"""
    parts = rel.split("/")
    if parts[0] in ("build", ".gradle"):
        return True
    if rel.startswith("scripts/deps-work"):
        return True
    # 冒烟测试编译与运行产物不入包（1.7.5.9 轮口径：out/ 的 .class 排除；
    # 1.7.6 覆盖轮：smoke176o2 的 out/ 编译产物与 workdir/ 运行数据一并排除，
    # smoke 目录入包口径 = Java 源 + classpath.txt；
    # 1.7.6 二次覆盖轮：smoke176o3 同口径；三次覆盖轮：smoke176o4 同口径；
    # 1.7.6.1：smoke1761 同口径）
    if rel.startswith("scripts/smoke1759/out"):
        return True
    if rel.startswith("scripts/smoke176o2/out") or rel.startswith("scripts/smoke176o2/workdir"):
        return True
    if rel.startswith("scripts/smoke176o3/out") or rel.startswith("scripts/smoke176o3/workdir"):
        return True
    if rel.startswith("scripts/smoke176o4/out") or rel.startswith("scripts/smoke176o4/workdir"):
        return True
    if rel.startswith("scripts/smoke1761/out") or rel.startswith("scripts/smoke1761/workdir"):
        return True
    if rel.startswith("scripts/smoke1762/out") or rel.startswith("scripts/smoke1762/workdir"):
        return True
    if rel.startswith("scripts/smoke1762o/out") or rel.startswith("scripts/smoke1762o/workdir"):
        return True
    # 1.7.6.3：smoke1763 的 .class 编译产物直接在目录根（无 out/ 子目录），
    # 排除 .class；FetchBing.java 为真实网络验证留档随包；
    # 1.7.6.3 覆盖轮：smoke1763o 同口径（注意 startswith 需含尾部斜杠，
    # 否则 smoke1763/ 不会匹配 smoke1763o/——两目录都要各自列出）
    if rel.startswith("scripts/smoke1763/") and rel.endswith(".class"):
        return True
    if rel.startswith("scripts/smoke1763o/") and rel.endswith(".class"):
        return True
    # 1.7.6.3 二次覆盖轮：smoke1763p 同口径（.class 在目录根，排除；
    # Test1763p.java + classpath.txt 随包）
    if rel.startswith("scripts/smoke1763p/") and rel.endswith(".class"):
        return True
    # 1.7.6.3 四次覆盖轮：smoke1763r 同口径（.class 在目录根，排除；
    # Test1763r.java + classpath.txt 随包）
    if rel.startswith("scripts/smoke1763r/") and rel.endswith(".class"):
        return True
    # 1.7.6.4：smoke1764 同口径（.class 在目录根，排除；
    # Test1764.java + classpath.txt 随包）
    if rel.startswith("scripts/smoke1764/") and rel.endswith(".class"):
        return True
    # 1.7.6.4 覆盖轮：smoke1764o 同口径（.class 在目录根，排除；
    # Test1764o.java + classpath.txt 随包）
    if rel.startswith("scripts/smoke1764o/") and rel.endswith(".class"):
        return True
    # 1.7.6.5：smoke1765 同口径（.class 在目录根，排除；
    # Test1765.java + classpath.txt 随包）
    if rel.startswith("scripts/smoke1765/") and rel.endswith(".class"):
        return True
    # 1.7.6.7：smoke1767 同口径（.class 在目录根，排除；
    # Test1767.java 随包）
    if rel.startswith("scripts/smoke1767/") and rel.endswith(".class"):
        return True
    if rel.startswith("scripts/smoke176/") and (rel.endswith(".class") or "/out/" in rel):
        return True
    if "build" in parts[:-1]:
        return True
    if rel.endswith((".jar", ".bin", ".exe", ".dll", ".so")):
        return True
    return False


def main():
    head = subprocess.run(["git", "-C", REPO, "rev-parse", "HEAD"],
                          capture_output=True, text=True, check=True
                          ).stdout.strip()
    files = [f for f in subprocess.run(["git", "-C", REPO, "ls-files"],
                           capture_output=True, text=True, check=True
                           ).stdout.splitlines() if not excluded(f)]
    os.makedirs(os.path.dirname(NAME), exist_ok=True)
    with zipfile.ZipFile(NAME, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        dirs = set()
        for rel in files:
            src = os.path.join(REPO, rel)
            if os.path.isfile(src):
                z.write(src, rel)
                parts = rel.split("/")
                for i in range(1, len(parts)):
                    dirs.add("/".join(parts[:i]) + "/")
        for d in sorted(dirs):
            zi = zipfile.ZipInfo(d)
            zi.external_attr = 0o40755 << 16
            z.writestr(zi, b"")
        z.comment = head.encode()
    size = os.path.getsize(NAME)
    print(f"源码包完成：{NAME}（{size} B，{len(files)} 文件 + {len(dirs)} 目录，"
          f"HEAD {head[:7]}）")


main()
