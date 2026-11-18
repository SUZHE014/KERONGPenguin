#!/usr/bin/env python3
"""1.7.6.5 同号覆盖轮部署(2026-11-18):
- 上传四端 JAR(重打)+ 源码包(重打)+ 官网三件(version.json 最后传
  避免提前触发更新提示);全部 md5 往返核对;权限 www:www / 644
- 用户口径「已删除的归档永久删除」:/root/penguin-archive/1.7.6.3-1.7.6.4/
  (上轮「移动不删除」的 10 件)整体 rm -rf 永久删除;归档父目录留
  pre-1.7.6(1.7.6 之前版本归档,另一口径,不动)
- 终态核验:version.json 13 条 / 外网 URL / 归档已删
"""
import hashlib
import os
import sys

sys.path.insert(0, "/home/z/my-project/scripts")
from ssh_helper import client  # noqa: E402

REPO = "/home/z/my-project/repo"

# (本地路径, 远程目录, 期望md5前8位)  顺序即上传顺序:JAR 先行,version.json 收尾
UPLOADS = [
    (f"{REPO}/build/dist/KERONGPenguin_Spigot-1.7.6.5.jar", "/wwwroot/penguin/downloads", "90b7ee71"),
    (f"{REPO}/build/dist/KERONGPenguin_NeoForge-1.20.4-1.7.6.5.jar", "/wwwroot/penguin/downloads", "f094f330"),
    (f"{REPO}/build/dist/KERONGPenguin_Fabric-1.20.4-1.7.6.5.jar", "/wwwroot/penguin/downloads", "259f33b5"),
    (f"{REPO}/build/dist/KERONGPenguin_Fabric-1.20.1-1.7.6.5.jar", "/wwwroot/penguin/downloads", "413253b3"),
    (f"{REPO}/website/tutorial.html", "/wwwroot/penguin", "d2fc6b8d"),
    (f"{REPO}/website/index.html", "/wwwroot/penguin", "37e5f2e7"),
    (f"{REPO}/build/dist/KERONGPenguin_v1.7.6.5_source.zip", "/wwwroot/penguin-source", "5e05959f"),
    (f"{REPO}/website/version.json", "/wwwroot/penguin", "fea7bc51"),
]

ARCHIVE_DIR = "/root/penguin-archive/1.7.6.3-1.7.6.4"


def md5_file(p):
    h = hashlib.md5()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run(c, cmd, timeout=120):
    _, out, err = c.exec_command(cmd, timeout=timeout)
    code = out.channel.recv_exit_status()
    return code, out.read().decode("utf-8", "replace"), err.read().decode("utf-8", "replace")


def main():
    for local, _, exp in UPLOADS:
        if not os.path.isfile(local):
            print(f"[MISS] 本地文件不存在: {local}")
            return 1
        m = md5_file(local)
        if not m.startswith(exp):
            print(f"[MD5-LOCAL-MISMATCH] {local}: {m} != 期望前缀 {exp}")
            return 1
    print(f"[OK] 本地 {len(UPLOADS)} 个文件 md5 预检全部通过")

    c = client()
    ok = 0
    try:
        # 1. 上传 + md5 往返核对(mv 用完整目标路径)
        sftp = c.open_sftp()
        for local, rdir, exp in UPLOADS:
            name = local.rsplit("/", 1)[-1]
            remote = f"{rdir}/{name}"
            print(f"[PUT] {name} ({os.path.getsize(local) / 1e6:.1f} MB) ...", flush=True)
            sftp.put(local, f"/tmp/upload_{name}")
            run(c, f"mv /tmp/upload_{name} {remote}")
            _, out, _ = run(c, f"md5sum '{remote}'", 60)
            rmd5 = out.split()[0] if out else ""
            same = rmd5 == md5_file(local)
            print(f"[{'SAME' if same else 'DIFF!'}] {remote} (md5 {rmd5[:8]})")
            ok += same
        sftp.close()
        print(f"----- 上传核对: {ok}/{len(UPLOADS)} SAME -----")
        if ok != len(UPLOADS):
            return 1

        # 2. 归档永久删除(先记录,后 rm,再确认;幂等:已不存在则跳过)
        _, exists, _ = run(c, f"if [ -d {ARCHIVE_DIR} ]; then echo EXISTS; else echo GONE; fi", 30)
        if "GONE" in exists:
            print(f"----- 归档已不存在(幂等跳过)")
        else:
            _, before, _ = run(c, f"ls {ARCHIVE_DIR}/ 2>/dev/null | sort; echo ---; du -sh {ARCHIVE_DIR} 2>/dev/null", 30)
            print(f"----- 归档删除前清单 ({ARCHIVE_DIR}):\n{before.strip()}")
            run(c, f"rm -rf {ARCHIVE_DIR}", 30)
        _, after, _ = run(c, f"ls {ARCHIVE_DIR}/ 2>&1 | head -2; echo ---; ls /root/penguin-archive/", 30)
        print(f"----- 归档删除后: {after.strip()}")
        if "1.7.6.3-1.7.6.4" in after.split("---")[-1]:
            print("[ERR] 归档目录仍存在!")
            return 1

        # 3. 权限
        _, out, _ = run(
            c,
            "cd /wwwroot/penguin && chown www:www index.html version.json tutorial.html && "
            "chmod 644 index.html version.json tutorial.html && "
            "chown www:www downloads/*.jar && chmod 644 downloads/*.jar && "
            "chown www:www /wwwroot/penguin-source/*.zip && chmod 644 /wwwroot/penguin-source/*.zip && echo PERM-OK",
            60)
        print(out.strip())

        # 4. 终态核验
        checks = [
            ("version.json 首字段", "head -c 40 /wwwroot/penguin/version.json"),
            ("version.json changelog 条数(应 13)", "grep -c '^[ ]*\"' /wwwroot/penguin/version.json"),
            ("index.html ⑬ 行与 11-18 日期(应 3+0)",
             "grep -c '⑬ 查性能内存占用' /wwwroot/penguin/index.html; grep -c '>发布于 2026-11-18<' /wwwroot/penguin/index.html; grep -cE '发布于 2026-11-17|1\\.7\\.6\\.5</td><td>2026-11-17' /wwwroot/penguin/index.html || true"),
            ("1.7.6.5 四 JAR 字节数", "stat -c '%n %s' /wwwroot/penguin/downloads/*1.7.6.5*.jar"),
            ("1.7.6.5 四 JAR md5(线上)", "md5sum /wwwroot/penguin/downloads/*1.7.6.5*.jar /wwwroot/penguin-source/KERONGPenguin_v1.7.6.5_source.zip"),
            ("归档目录(应无 1.7.6.3-1.7.6.4)", "ls /root/penguin-archive/"),
            ("downloads 残留 upload_(应 0)",
             "ls /wwwroot/penguin/downloads/ /wwwroot/penguin-source/ /wwwroot/penguin/ | grep -c upload_ || true"),
            ("penguin-source 现存", "ls /wwwroot/penguin-source/"),
            ("外网 version.json ⑬", "curl -s --max-time 20 https://kerong.xin/penguin/version.json | grep -c '含预留'"),
            ("外网 Spigot JAR 状态码+字节",
             "curl -s -o /dev/null -w '%{http_code} %{size_download}' --max-time 30 https://kerong.xin/penguin/downloads/KERONGPenguin_Spigot-1.7.6.5.jar"),
            ("外网 index.html ⑬", "curl -s --max-time 20 https://kerong.xin/penguin/index.html | grep -c '⑬ 查性能内存占用'"),
        ]
        for title, cmd in checks:
            _, out, _ = run(c, cmd, 60)
            print(f"--- {title} ---\n{out.strip()}")
    finally:
        c.close()

    print("\n===== 1.7.6.5 覆盖轮部署完成 =====")
    return 0


if __name__ == "__main__":
    sys.exit(main())
