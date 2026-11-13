#!/usr/bin/env python3
"""1.7.5.5 AI 覆盖轮部署：三 JAR（同号覆盖）+ 官网三件上传 + chown/chmod + md5 往返核对。"""
import hashlib
import os
import sys

import paramiko

HOST = os.environ.get("PENGUIN_SSH_HOST", "156.233.228.248")
USER = os.environ.get("PENGUIN_SSH_USER", "root")
PASS = os.environ.get("PENGUIN_SSH_PASS", "")

REPO = "/home/z/my-project/penguin-git"
UPLOADS = [
    (f"{REPO}/build/dist/KERONGPenguin_Spigot-1.7.5.5.jar",
     "/wwwroot/penguin/downloads/KERONGPenguin_Spigot-1.7.5.5.jar"),
    (f"{REPO}/build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.5.5.jar",
     "/wwwroot/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.5.jar"),
    (f"{REPO}/build/dist/KERONGPenguin_Fabric-1.20.4-1.7.5.5.jar",
     "/wwwroot/penguin/downloads/KERONGPenguin_Fabric-1.20.4-1.7.5.5.jar"),
    (f"{REPO}/website/index.html", "/wwwroot/penguin/index.html"),
    (f"{REPO}/website/tutorial.html", "/wwwroot/penguin/tutorial.html"),
    (f"{REPO}/website/version.json", "/wwwroot/penguin/version.json"),
]


def md5_file(path):
    with open(path, "rb") as fp:
        return hashlib.md5(fp.read()).hexdigest()


def main():
    if not PASS:
        raise SystemExit("需要 PENGUIN_SSH_PASS")
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, 22, USER, PASS, timeout=30)
    ok = fail = 0
    try:
        sftp = c.open_sftp()
        for local, remote in UPLOADS:
            if not os.path.isfile(local):
                print(f"[FAIL] 本地缺失 {local}")
                fail += 1
                continue
            sftp.put(local, remote)
            print(f"[PUT] {os.path.basename(local)} -> {remote} ({os.path.getsize(local)}B)")
        # 属主与权限
        _, out, err = c.exec_command(
            "chown www:www /wwwroot/penguin/index.html /wwwroot/penguin/tutorial.html "
            "/wwwroot/penguin/version.json /wwwroot/penguin/downloads/KERONGPenguin_Spigot-1.7.5.5.jar "
            "/wwwroot/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.5.jar "
            "/wwwroot/penguin/downloads/KERONGPenguin_Fabric-1.20.4-1.7.5.5.jar && "
            "chmod 644 /wwwroot/penguin/index.html /wwwroot/penguin/tutorial.html "
            "/wwwroot/penguin/version.json /wwwroot/penguin/downloads/KERONGPenguin_Spigot-1.7.5.5.jar "
            "/wwwroot/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.5.jar "
            "/wwwroot/penguin/downloads/KERONGPenguin_Fabric-1.20.4-1.7.5.5.jar")
        out.channel.recv_exit_status()
        # md5 往返
        for local, remote in UPLOADS:
            local_md5 = md5_file(local)
            _, out, _ = c.exec_command(f"md5sum {remote}")
            remote_md5 = out.read().decode().split()[0]
            if local_md5 == remote_md5:
                print(f"[OK-md5] {os.path.basename(remote)} {remote_md5}")
                ok += 1
            else:
                print(f"[FAIL-md5] {os.path.basename(remote)} local={local_md5} remote={remote_md5}")
                fail += 1
    finally:
        c.close()
    print(f"\n========== 部署汇总: OK={ok} FAIL={fail} ==========")
    if fail:
        sys.exit(1)


if __name__ == "__main__":
    main()
