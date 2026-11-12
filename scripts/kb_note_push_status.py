#!/usr/bin/env python3
"""kb_note_push_status：知识库 git push 状态行更新为复核轮实况（一处替换，两处上传）。

本轮实况：github.com 直连 push 超时（两次）；fine-grained PAT github_pat_ 对该仓库
仍 403 Permission denied（与 2026-11-10 记录一致）；ghfast.top 代理 + 经典令牌
ghp_（/tmp/.ghaskpass umask 077 + GIT_ASKPASS_REQUIRE=force）push 成功；
api.github.com 直连可复核（远端 main = 67ca39a）。
"""
import os
import sys

import paramiko

HOST = os.environ.get("PENGUIN_SSH_HOST", "156.233.228.248")
USER = os.environ.get("PENGUIN_SSH_USER", "root")
PASS = os.environ.get("PENGUIN_SSH_PASS", "")
if not PASS:
    raise SystemExit("请经环境变量 PENGUIN_SSH_PASS 提供服务器密码（凭据不入库）")

OLD = ("**git push 状态**：✅ 已完成（2026-11-12 v1.7.5.2覆盖+1.7.5.3+fa 实测，远端 main = `9e43084`；"
       "本轮 GitHub 直连 push 可用（令牌嵌 URL 无需 ghfast 代理）。")
NEW = ("**git push 状态**：✅ 已完成（2026-11-12 复核轮实测，远端 main = `67ca39a`（verify_online_1753_recheck + "
       "kb_note_recheck 两脚本）；本轮 github.com 直连 push 两次超时不可用、fine-grained PAT github_pat_ 对该仓库仍 "
       "403 Permission denied（同 11-10 记录），**ghfast.top 代理 + 经典令牌 ghp_（/tmp/.ghaskpass umask 077 + "
       "GIT_ASKPASS_REQUIRE=force）push 可用**；api.github.com 直连可复核。")


def main():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, 22, USER, PASS, timeout=25)
    try:
        sftp = c.open_sftp()
        with sftp.open("/wwwroot/penguin/AI-KNOWLEDGE.txt", "r") as f:
            text = f.read().decode("utf-8")
        assert OLD in text, "git push 状态行锚点未找到"
        text = text.replace(OLD, NEW, 1)
        for path in ("/wwwroot/penguin/AI-KNOWLEDGE.txt", "/SUZHE014/AI-KNOWLEDGE.txt"):
            with sftp.open(path, "w") as f:
                f.write(text)
        _, out, _ = c.exec_command(
            "chown www:www /wwwroot/penguin/AI-KNOWLEDGE.txt && "
            "grep -c '67ca39a' /wwwroot/penguin/AI-KNOWLEDGE.txt /SUZHE014/AI-KNOWLEDGE.txt", timeout=30)
        print(out.read().decode())
    finally:
        c.close()
    print("git push 状态行已更新（两处）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
