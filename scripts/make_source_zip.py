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
    os.path.join(REPO, "build", "dist", "KERONGPenguin_v1.7.3.1_source.zip")


def main():
    head = subprocess.run(["git", "-C", REPO, "rev-parse", "HEAD"],
                          capture_output=True, text=True, check=True
                          ).stdout.strip()
    files = subprocess.run(["git", "-C", REPO, "ls-files"],
                           capture_output=True, text=True, check=True
                           ).stdout.splitlines()
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
