#!/usr/bin/env python3
"""1.7.5.7 再覆盖轮部署：四 JAR + 官网两件 + 源码包上传，chown/chmod，md5 往返核对。

（tutorial.html 本轮无改动不上传；index.html 日志块补一条 + version.json 五条。）
"""
import hashlib
import os
import sys

sys.path.insert(0, "/home/z/my-project/scripts")
from ssh_cmd import run, sftp_upload  # noqa: E402

REPO = "/home/z/my-project/penguin-git"

UPLOADS = [
    (f"{REPO}/build/dist/KERONGPenguin_Spigot-1.7.5.7.jar", "/wwwroot/penguin/downloads/"),
    (f"{REPO}/build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.5.7.jar", "/wwwroot/penguin/downloads/"),
    (f"{REPO}/build/dist/KERONGPenguin_Fabric-1.20.4-1.7.5.7.jar", "/wwwroot/penguin/downloads/"),
    (f"{REPO}/build/dist/KERONGPenguin_Fabric-1.20.1-1.7.5.7.jar", "/wwwroot/penguin/downloads/"),
    (f"{REPO}/website/index.html", "/wwwroot/penguin/"),
    (f"{REPO}/website/version.json", "/wwwroot/penguin/"),
    (f"{REPO}/build/dist/KERONGPenguin_v1.7.5.7_source.zip", "/wwwroot/penguin-source/"),
]


def md5_file(p):
    return hashlib.md5(open(p, "rb").read()).hexdigest()


def remote_md5(remote):
    code, out, _ = run(f"md5sum '{remote}'", 60)
    return out.split()[0] if out else ""


def main():
    ok = 0
    for local, rdir in UPLOADS:
        name = local.rsplit("/", 1)[-1]
        remote = f"{rdir}/{name}"
        sftp_upload(local, remote)
        same = md5_file(local) == remote_md5(remote)
        print(f"[{'SAME' if same else 'DIFF!'}] {remote} ({os.path.getsize(local)} bytes, md5 {md5_file(local)[:8]})")
        ok += same
    code, out, _ = run(
        "cd /wwwroot/penguin && chown www:www index.html version.json downloads/*.jar && "
        "chmod 644 index.html version.json downloads/*.jar && "
        "chown www:www /wwwroot/penguin-source/KERONGPenguin_v1.7.5.7_source.zip && "
        "chmod 644 /wwwroot/penguin-source/KERONGPenguin_v1.7.5.7_source.zip && "
        "cd /wwwroot/penguin/downloads && ls -1 KERONGPenguin_Spigot-*.jar | sort -V | head -n -3 | xargs -r rm -f && "
        "cd /wwwroot/penguin-source && ls -1 KERONGPenguin_v*_source.zip | sort -V | head -n -3 | xargs -r rm -f && "
        "ls -la /wwwroot/penguin/downloads/ | grep 1.7.5.7; echo ---; ls -la /wwwroot/penguin-source/", 60)
    print(out)
    print(f"\n===== 部署结果: {ok}/{len(UPLOADS)} SAME =====")
    return 0 if ok == len(UPLOADS) else 1


if __name__ == "__main__":
    sys.exit(main())
