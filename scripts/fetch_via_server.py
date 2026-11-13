#!/usr/bin/env python3
"""经服务器中转下载文件:服务器 wget 校验完整后 sftp 拉回。用法:
PENGUIN_SSH_PASS=... python3 fetch_via_server.py <url> <本地路径> [最小字节数]"""
import os
import sys
import paramiko

HOST = os.environ.get("PENGUIN_SSH_HOST", "156.233.228.248")
USER = os.environ.get("PENGUIN_SSH_USER", "root")
PASS = os.environ.get("PENGUIN_SSH_PASS", "")


def main():
    if len(sys.argv) < 3:
        raise SystemExit("用法: fetch_via_server.py <url> <local_path> [min_bytes]")
    url, local = sys.argv[1], sys.argv[2]
    min_bytes = int(sys.argv[3]) if len(sys.argv) > 3 else 1_000_000
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, 22, USER, PASS, timeout=30)
    try:
        # 服务器端下载(最多 5 次重试, unzip -t 校验)
        cmd = (
            "cd /tmp && rm -f fa_dl.jar && "
            "for i in 1 2 3 4 5; do "
            "  wget -q --timeout=60 -O fa_dl.jar '" + url + "' && "
            "  unzip -t fa_dl.jar >/dev/null 2>&1 && sz=$(stat -c%s fa_dl.jar) && [ $sz -ge " + str(min_bytes) + " ] && break; "
            "  sleep 2; "
            "done; "
            "sz=$(stat -c%s fa_dl.jar 2>/dev/null || echo 0); echo SIZE=$sz; "
            "unzip -t fa_dl.jar >/dev/null 2>&1 && echo ZIP_OK || echo ZIP_BAD"
        )
        _, out, err = c.exec_command(cmd, timeout=420)
        result = out.read().decode().strip()
        print(result)
        if "ZIP_OK" not in result:
            raise SystemExit("服务器下载失败")
        sftp = c.open_sftp()
        sftp.get("/tmp/fa_dl.jar", local)
        sftp.close()
        sz = os.path.getsize(local)
        print(f"[OK] {local} {sz} bytes")
        # 清理服务器临时文件
        c.exec_command("rm -f /tmp/fa_dl.jar")
    finally:
        c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
