#!/usr/bin/env python3
"""1.7.6.7 覆盖轮部署（2026-11-22 晚：Arclight-forge 1.20.1 兼容修复）。

- 同号覆盖线上四端 JAR + 源码包 + 官网三件（version.json 最后传，避免提前
  触发更新提示）；全部 md5 往返核对；权限 www:www / 644
- 终态核验：version.json 1.7.6.7（changelog 3 条）/ 外网 URL 200 字节精确
"""
import hashlib
import os
import sys

sys.path.insert(0, "/home/z/my-project/scripts")
from ssh_helper import client  # noqa: E402

REPO = "/home/z/my-project/repo-main"

# (本地路径, 远程路径, 期望md5前8位)  顺序即上传顺序：JAR 先行，version.json 收尾
UPLOADS = [
    (f"{REPO}/build/dist/KERONGPenguin_Spigot-1.7.6.7.jar", "/wwwroot/penguin/downloads/KERONGPenguin_Spigot-1.7.6.7.jar", "ec6ad36d"),
    (f"{REPO}/build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.6.7.jar", "/wwwroot/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.6.7.jar", "bbe6e365"),
    (f"{REPO}/build/dist/KERONGPenguin_Fabric-1.20.4-1.7.6.7.jar", "/wwwroot/penguin/downloads/KERONGPenguin_Fabric-1.20.4-1.7.6.7.jar", "69684f89"),
    (f"{REPO}/build/dist/KERONGPenguin_Fabric-1.20.1-1.7.6.7.jar", "/wwwroot/penguin/downloads/KERONGPenguin_Fabric-1.20.1-1.7.6.7.jar", "a3cbf7ad"),
    (f"{REPO}/website/tutorial.html", "/wwwroot/penguin/tutorial.html", "aa00aba9"),
    (f"{REPO}/website/index.html", "/wwwroot/penguin/index.html", "54282a0b"),
    (f"{REPO}/build/dist/KERONGPenguin_v1.7.6.7_source.zip", "/wwwroot/penguin-source/KERONGPenguin_v1.7.6.7_source.zip", "550201d3"),
    (f"{REPO}/website/version.json", "/wwwroot/penguin/version.json", "489bc56c"),
]

REMOTE_MD5 = "md5sum"


def md5_file(p):
    h = hashlib.md5()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    # 本地 md5 预核对（与打包产物一致，防本地误传）
    for local, _, expect in UPLOADS:
        actual = md5_file(local)[:8]
        if actual != expect:
            print(f"本地 md5 不符: {local} 期望 {expect} 实际 {actual}")
            sys.exit(1)
    print(f"本地 md5 预核对 {len(UPLOADS)}/{len(UPLOADS)} 通过")

    c = client()
    try:
        ok = 0
        for local, remote, expect in UPLOADS:
            sftp = c.open_sftp()
            sftp.put(local, remote)
            sftp.close()
            c.exec_command(f"chown www:www '{remote}' && chmod 644 '{remote}'")[1].channel.recv_exit_status()
            _, stdout, _ = c.exec_command(f"{REMOTE_MD5} '{remote}'")
            got = stdout.read().decode().strip().split()[0][:8]
            if got == expect:
                ok += 1
                print(f"  SAME {os.path.basename(remote)}  {got}")
            else:
                print(f"  DIFF {os.path.basename(remote)}  期望 {expect} 实际 {got}")
                sys.exit(1)
        print(f"上传 + md5 往返核对 {ok}/{len(UPLOADS)} 全部 SAME")

        # 终态核验：version.json 版本 + changelog 条数 + 外网 URL
        _, stdout, _ = c.exec_command("head -c 200 /wwwroot/penguin/version.json")
        print("version.json 头:", stdout.read().decode().strip().splitlines()[:2])
        urls = [
            "https://kerong.xin/penguin/version.json",
            "https://kerong.xin/penguin/",
            "https://kerong.xin/penguin/tutorial.html",
            "https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.6.7.jar",
            "https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.6.7.jar",
            "https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.4-1.7.6.7.jar",
            "https://kerong.xin/penguin/downloads/KERONGPenguin_Fabric-1.20.1-1.7.6.7.jar",
        ]
        for u in urls:
            _, stdout, _ = c.exec_command(f"curl -s -o /dev/null -w '%{{http_code}} %{{size_download}}' '{u}'")
            print(f"  {stdout.read().decode().strip()}  {u}")
    finally:
        c.close()


if __name__ == "__main__":
    main()
