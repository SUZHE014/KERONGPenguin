#!/usr/bin/env python3
"""deploy_1753：1.7.5.3 全量部署（三 JAR 新版 + 1.7.5.2 覆盖版双 JAR + 官网三件 +
1.7.5.1 旧 JAR 下架 + md5 往返验证）。

上传清单：
- KERONGPenguin_Spigot-1.7.5.3.jar / NeoForge-1.20.4-1.7.5.3.jar /
  Fabric-1.20.4-1.7.5.3.jar（新版上架，下载区 + 历史表）
- KERONGPenguin_Spigot-1.7.5.2.jar / NeoForge-1.20.4-1.7.5.2.jar
 （覆盖版：替换昨天带官方接口方案的同号 JAR）
- website/version.json / index.html / tutorial.html
下架清单（1.7.5.1 三 JAR 删除，历史表与更新日志已从官网移除）。

上传走 SFTP；慢网络截断（md5 不一致）时回退 exec 通道 cat 断点续传。
"""
import hashlib
import os
import sys
import time

import paramiko

HOST = os.environ.get("PENGUIN_SSH_HOST", "156.233.228.248")
USER = os.environ.get("PENGUIN_SSH_USER", "root")
PASS = os.environ.get("PENGUIN_SSH_PASS", "")
if not PASS:
    raise SystemExit("请经环境变量 PENGUIN_SSH_PASS 提供服务器密码（凭据不入库）")
LOCAL = "/home/z/my-project/penguin-git"
REMOTE = "/wwwroot/penguin"

UPLOADS = [
    (f"{LOCAL}/build/dist/KERONGPenguin_Spigot-1.7.5.3.jar",
     f"{REMOTE}/downloads/KERONGPenguin_Spigot-1.7.5.3.jar"),
    (f"{LOCAL}/build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.5.3.jar",
     f"{REMOTE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.3.jar"),
    (f"{LOCAL}/build/dist/KERONGPenguin_Fabric-1.20.4-1.7.5.3.jar",
     f"{REMOTE}/downloads/KERONGPenguin_Fabric-1.20.4-1.7.5.3.jar"),
    (f"{LOCAL}/build/dist/KERONGPenguin_Spigot-1.7.5.2.jar",
     f"{REMOTE}/downloads/KERONGPenguin_Spigot-1.7.5.2.jar"),
    (f"{LOCAL}/build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.5.2.jar",
     f"{REMOTE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.2.jar"),
    (f"{LOCAL}/website/version.json", f"{REMOTE}/version.json"),
    (f"{LOCAL}/website/index.html", f"{REMOTE}/index.html"),
    (f"{LOCAL}/website/tutorial.html", f"{REMOTE}/tutorial.html"),
]

# 1.7.5.1 下架（官网历史表 / 更新日志已删，旧 JAR 一并移除）
DELETES = [
    f"{REMOTE}/downloads/KERONGPenguin_Spigot-1.7.5.1.jar",
    f"{REMOTE}/downloads/KERONGPenguin_NeoForge-1.20.4-1.7.5.1.jar",
    f"{REMOTE}/downloads/KERONGPenguin_Fabric-1.20.4-1.7.5.1.jar",
]


def md5_file(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def remote_md5(c, path):
    _, out, _ = c.exec_command(f"md5sum '{path}'", timeout=60)
    text = out.read().decode().strip()
    return text.split()[0] if text else None


def sftp_upload_with_verify(c, sftp, local, remote, attempts=3):
    local_md5 = md5_file(local)
    for attempt in range(1, attempts + 1):
        try:
            sftp.put(local, remote)
        except Exception as e:  # noqa: BLE001
            print(f"    SFTP 异常（{e}），重试 {attempt}/{attempts}")
        if remote_md5(c, remote) == local_md5:
            return True
        print(f"    md5 不一致，重试 {attempt}/{attempts}")
        time.sleep(2)
    return False


def cat_resume_upload(c, local, remote):
    """exec 通道 cat 断点续传（SFTP 慢网络截断兜底）。"""
    local_md5 = md5_file(local)
    size = os.path.getsize(local)
    remote_size = 0
    _, out, _ = c.exec_command(f"stat -c%s '{remote}' 2>/dev/null || echo 0", timeout=30)
    remote_size = int(out.read().decode().strip() or "0")
    while remote_size < size:
        with open(local, "rb") as f:
            f.seek(remote_size)
            chunk = f.read(8 << 20)
        if not chunk:
            break
        cmd = (f"cat >> '{remote}'")
        stdin, _, _ = c.exec_command(cmd, timeout=120)
        stdin.write(chunk)
        stdin.channel.shutdown_write()
        stdin.channel.recv_exit_status()
        remote_size += len(chunk)
        print(f"    续传至 {remote_size}/{size}")
        _, out, _ = c.exec_command(f"stat -c%s '{remote}'", timeout=30)
        remote_size = int(out.read().decode().strip() or "0")
        if remote_md5(c, remote) == local_md5:
            return True
    return remote_md5(c, remote) == local_md5


def main():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, 22, USER, PASS, timeout=25)
    ok = True
    try:
        sftp = c.open_sftp()
        print("== 上传产物（8 件） ==")
        for local, remote in UPLOADS:
            name = remote.split("/")[-1]
            local_md5 = md5_file(local)
            good = sftp_upload_with_verify(c, sftp, local, remote)
            if not good:
                print(f"  [SFTP 截断] {name} 改走 exec 断点续传")
                good = cat_resume_upload(c, local, remote)
            ok &= good
            print(f"  [{'OK' if good else 'MISMATCH'}] {name}  md5={local_md5}")

        print("== 下架 1.7.5.1 旧 JAR ==")
        for remote in DELETES:
            _, out, _ = c.exec_command(f"rm -f '{remote}' && ls '{remote}' 2>&1", timeout=30)
            result = out.read().decode().strip()
            gone = "No such file" in result
            print(f"  [{'OK' if gone else 'WARN'}] {remote.split('/')[-1]} 已删除" if gone
                  else f"  [WARN] {remote} 删除后仍存在: {result}")

        # 归属与权限对齐 www
        _, out, _ = c.exec_command(
            "chown www:www /wwwroot/penguin/downloads/*.jar /wwwroot/penguin/version.json "
            "/wwwroot/penguin/index.html /wwwroot/penguin/tutorial.html && "
            "ls -la /wwwroot/penguin/downloads/*1.7.5.3* /wwwroot/penguin/downloads/*1.7.5.2* "
            "/wwwroot/penguin/version.json", timeout=60)
        print(out.read().decode())
    finally:
        c.close()
    if not ok:
        print("上传 md5 校验失败")
        return 1
    print("部署完成（公网验证见 verify_online_1753）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
