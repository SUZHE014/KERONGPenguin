#!/usr/bin/env python3
"""verify_online_1753_recheck：线上状态复核（会话恢复后重跑，不动任何线上文件）。

核验清单（全部只读）：
1. /wwwroot/penguin/downloads/ 五个 JAR 的 md5 与本地 build/dist/ 一致
   （1.7.5.3 Spigot/NeoForge/Fabric + 1.7.5.2 覆盖版 Spigot/NeoForge）；
2. 线上 version.json 与本地 website/version.json 逐字一致；
3. 1.7.5.1 三个旧 JAR 确认不存在（下架状态保持）；
4. KB 两处（/wwwroot/penguin/AI-KNOWLEDGE.txt + /SUZHE014/AI-KNOWLEDGE.txt）：
   - 头部最新条目为 2026-11-12（1.7.5.2 覆盖版 + 1.7.5.3 + fa 1.7.5.3）；
   - 无未标注下架的 1.7.5.1 头部条目；
   - §0 当前版本 = 1.7.5.3；
   - KB 中 1.7.5.3 条目明确包含 1.7.5.2 全部六项能力（含按钮平台门禁）。
"""
import hashlib
import os
import sys

import paramiko

HOST = os.environ.get("PENGUIN_SSH_HOST", "156.233.228.248")
USER = os.environ.get("PENGUIN_SSH_USER", "root")
PASS = os.environ.get("PENGUIN_SSH_PASS", "")
if not PASS:
    raise SystemExit("请经环境变量 PENGUIN_SSH_PASS 提供服务器密码（凭据不入库）")

LOCAL = "/home/z/my-project/penguin-git"
REMOTE = "/wwwroot/penguin"

JARS = [
    "KERONGPenguin_Spigot-1.7.5.3.jar",
    "KERONGPenguin_NeoForge-1.20.4-1.7.5.3.jar",
    "KERONGPenguin_Fabric-1.20.4-1.7.5.3.jar",
    "KERONGPenguin_Spigot-1.7.5.2.jar",
    "KERONGPenguin_NeoForge-1.20.4-1.7.5.2.jar",
]
DELETED_1751 = [
    "KERONGPenguin_Spigot-1.7.5.1.jar",
    "KERONGPenguin_NeoForge-1.20.4-1.7.5.1.jar",
    "KERONGPenguin_Fabric-1.20.4-1.7.5.1.jar",
]


def md5_file(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, 22, USER, PASS, timeout=25)
    passed, failed = 0, 0

    def check(name, ok, detail=""):
        nonlocal passed, failed
        passed += ok
        failed += (not ok)
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if detail and not ok else ""))

    try:
        print("== 1. 线上五 JAR md5 对比 ==")
        for jar in JARS:
            local_md5 = md5_file(f"{LOCAL}/build/dist/{jar}")
            _, out, _ = c.exec_command(f"md5sum '{REMOTE}/downloads/{jar}'", timeout=60)
            text = out.read().decode().strip()
            remote_md5 = text.split()[0] if text else None
            check(f"{jar} md5={local_md5}", remote_md5 == local_md5, f"远端={remote_md5}")

        print("== 2. 线上 version.json ==")
        with open(f"{LOCAL}/website/version.json", encoding="utf-8") as f:
            local_vj = f.read()
        _, out, _ = c.exec_command(f"cat '{REMOTE}/version.json'", timeout=30)
        remote_vj = out.read().decode("utf-8", "replace")
        check("version.json 逐字一致", remote_vj == local_vj)
        check("version.json 版本号 1.7.5.3", '"version": "1.7.5.3"' in remote_vj)

        print("== 3. 1.7.5.1 下架状态 ==")
        for jar in DELETED_1751:
            _, out, _ = c.exec_command(f"ls '{REMOTE}/downloads/{jar}' 2>&1", timeout=30)
            check(f"{jar} 不存在", "No such file" in out.read().decode())

        print("== 4. KB 两处状态 ==")
        for kb_path in (f"{REMOTE}/AI-KNOWLEDGE.txt", "/SUZHE014/AI-KNOWLEDGE.txt"):
            _, out, _ = c.exec_command(f"cat '{kb_path}'", timeout=60)
            kb = out.read().decode("utf-8", "replace")
            head = kb[:6000]
            check(f"{kb_path} 最新头部条目=2026-11-12 覆盖版+1.7.5.3+fa",
                  "本轮 v1.7.5.2 覆盖版 + v1.7.5.3 + fa 1.7.5.3（2026-11-12" in head)
            check(f"{kb_path} 无未下架标注的 1.7.5.1 头部条目",
                  "最后更新：2026-11-11（**v1.7.5.1" not in kb)
            check(f"{kb_path} §0 当前版本 1.7.5.3",
                  "当前版本 **1.7.5.3（2026-11-12）**" in kb)
            # 1.7.5.2 六项能力关键特征是否都在 KB 的 1.7.5.2/1.7.5.3 叙述中
            feats = {
                "①菜单模板升级表": "菜单模板去强制更新",
                "②连接保活": "ConnectionWarmer",
                "③按钮平台门禁": "permission.type=1",
                "④绑定码默认": "绑定码口径",
                "⑤白名单自动关": "自动关服务器白名单",
                "⑥单行写法": "单行",
                "面板联动": "panel-admin-linkage",
            }
            for label, needle in feats.items():
                check(f"{kb_path} KB含{label}", needle in kb)
    finally:
        c.close()

    print(f"\n合计: PASS={passed} FAIL={failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
