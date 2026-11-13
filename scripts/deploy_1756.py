#!/usr/bin/env python3
"""1.7.5.6 部署:四 JAR + 官网三件上传 /wwwroot/penguin/,chown/chmod,md5 往返核对。"""
import hashlib
import os
import sys
import paramiko

HOST = "156.233.228.248"
PASS = os.environ.get("PENGUIN_SSH_PASS", "")
REPO = "/home/z/my-project/penguin-git"

UPLOADS = [
    (f"{REPO}/build/dist/KERONGPenguin_Spigot-1.7.5.6.jar", "/wwwroot/penguin/downloads/"),
    (f"{REPO}/build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.5.6.jar", "/wwwroot/penguin/downloads/"),
    (f"{REPO}/build/dist/KERONGPenguin_Fabric-1.20.4-1.7.5.6.jar", "/wwwroot/penguin/downloads/"),
    (f"{REPO}/build/dist/KERONGPenguin_Fabric-1.20.1-1.7.5.6.jar", "/wwwroot/penguin/downloads/"),
    (f"{REPO}/website/index.html", "/wwwroot/penguin/"),
    (f"{REPO}/website/tutorial.html", "/wwwroot/penguin/"),
    (f"{REPO}/website/version.json", "/wwwroot/penguin/"),
]


def md5_file(p):
    return hashlib.md5(open(p, "rb").read()).hexdigest()


def main():
    if not PASS:
        raise SystemExit("需要 PENGUIN_SSH_PASS")
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, 22, "root", PASS, timeout=30)
    try:
        sftp = c.open_sftp()
        ok = 0
        for local, rdir in UPLOADS:
            name = local.rsplit("/", 1)[-1]
            remote = f"{rdir}/{name}"
            sftp.put(local, remote)
            with sftp.open(remote, "rb") as f:
                back = f.read()
            same = md5_file(local) == hashlib.md5(back).hexdigest()
            print(f"[{'SAME' if same else 'DIFF!'}] {remote} ({os.path.getsize(local)} bytes, md5 {md5_file(local)[:8]})")
            ok += same
        # 属主与权限
        _, out, _ = c.exec_command(
            "cd /wwwroot/penguin && chown www:www index.html tutorial.html version.json downloads/*.jar && "
            "chmod 644 index.html tutorial.html version.json downloads/*.jar && "
            "ls -la downloads/ | tail -8", timeout=60)
        print(out.read().decode())
        if ok != len(UPLOADS):
            raise SystemExit(f"md5 不一致 {len(UPLOADS)-ok} 件!")
        print(f"部署完成: {ok}/{len(UPLOADS)} SAME")
    finally:
        c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
