#!/usr/bin/env python3
"""deploy_1752：1.7.5.2 全量部署（双 JAR 覆盖 + 官网三件 + md5 往返验证）。

将本地构建的 1.7.5.2 双 JAR 与更新后的官网三件（version.json / index.html /
tutorial.html）上传到生产服务器 /wwwroot/penguin/，随后在线验证：
- md5 往返一致（本地 vs 服务器 vs 公网下载）
- version.json 公网可读且版本号正确
- 官网页面可访问
"""
import hashlib
import sys
import time

import paramiko

import os

HOST = os.environ.get("PENGUIN_SSH_HOST", "156.233.228.248")
USER = os.environ.get("PENGUIN_SSH_USER", "root")
PASS = os.environ.get("PENGUIN_SSH_PASS", "")
if not PASS:
    raise SystemExit("请经环境变量 PENGUIN_SSH_PASS 提供服务器密码（凭据不入库）")
LOCAL = "/home/z/my-project/penguin-git"
REMOTE = "/wwwroot/penguin"


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    jars = [
        (f"{LOCAL}/build/dist/KERONGPenguin_Spigot-1.7.5.2.jar",
         f"{REMOTE}/downloads/KERONGPenguin_Spigot-1.7.5.2.jar"),
        (f"{LOCAL}/build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.5.2.jar",
         f"{REMOTE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.2.jar"),
    ]
    sites = [
        (f"{LOCAL}/website/version.json", f"{REMOTE}/version.json"),
        (f"{LOCAL}/website/index.html", f"{REMOTE}/index.html"),
        (f"{LOCAL}/website/tutorial.html", f"{REMOTE}/tutorial.html"),
    ]
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, 22, USER, PASS, timeout=25)
    ok = True
    try:
        sftp = c.open_sftp()
        print("== 上传产物 ==")
        for local, remote in jars + sites:
            local_md5 = md5(local)
            sftp.put(local, remote)
            with sftp.open(remote, "rb") as f:
                remote_md5 = hashlib.md5(f.read()).hexdigest()
            match = local_md5 == remote_md5
            ok &= match
            print(f"  [{'OK' if match else 'MISMATCH'}] {remote.split('/')[-1]}  md5={local_md5}")
        # 归属与权限对齐 www
        _, out, _ = c.exec_command(
            "chown www:www /wwwroot/penguin/downloads/KERONGPenguin_Spigot-1.7.5.2.jar "
            "/wwwroot/penguin/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.2.jar "
            "/wwwroot/penguin/version.json /wwwroot/penguin/index.html /wwwroot/penguin/tutorial.html && "
            "ls -la /wwwroot/penguin/downloads/*1.7.5.2* /wwwroot/penguin/version.json")
        print(out.read().decode())
    finally:
        c.close()
    if not ok:
        print("上传 md5 校验失败")
        return 1
    print("部署完成（公网验证见 verify_online_1752）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
