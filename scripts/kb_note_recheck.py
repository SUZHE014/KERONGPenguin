#!/usr/bin/env python3
"""kb_note_recheck：知识库补记 2026-11-12 会话恢复复核结论（只补两处一句话，不改其他内容）。

背景：用户指令「确保 1.7.5.2 的全部功能都包含在 1.7.5.3」。复核结论为确认包含：
- 1.7.5.2 覆盖版与 1.7.5.3 同平台 JAR 全量条目对比（11104 项）：仅面板联动
  相关类 + 版本文件差异，承载 1.7.5.2 七项修复的全部类两版字节一致；
- Fabric 1.7.5.3 与 Spigot 1.7.5.3 的 common-Bot 共享类零差异（Fa 版同样包含）；
- 线上五 JAR md5 / version.json / 1.7.5.1 下架 / KB 两处 30 项复核全绿
  （scripts/verify_online_1753_recheck.py）。
"""
import os
import sys

import paramiko

HOST = os.environ.get("PENGUIN_SSH_HOST", "156.233.228.248")
USER = os.environ.get("PENGUIN_SSH_USER", "root")
PASS = os.environ.get("PENGUIN_SSH_PASS", "")
if not PASS:
    raise SystemExit("请经环境变量 PENGUIN_SSH_PASS 提供服务器密码（凭据不入库）")

HEAD_APPEND = ("会话恢复复核（verify_online_1753_recheck.py，30 项全绿）：确认 **1.7.5.3 完整包含 1.7.5.2 覆盖版全部功能**——"
               "两版 JAR 全量 11104 条目对比仅面板联动类与版本文件差异，七项修复承载类字节一致；Fabric 1.7.5.3 与主线 common-Bot 类零差异（Fa 版同含）。")

S12_APPEND = ("会话恢复复核（2026-11-12，verify_online_1753_recheck.py 30 项全绿）：1.7.5.3 ⊇ 1.7.5.2 经字节级确认"
              "（两版 JAR 11104 条目仅面板联动类+版本文件差异，七项承载类字节一致；Fabric 与主线 common-Bot 类零差异）。")


def main():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, 22, USER, PASS, timeout=25)
    try:
        sftp = c.open_sftp()
        with sftp.open("/wwwroot/penguin/AI-KNOWLEDGE.txt", "r") as f:
            text = f.read().decode("utf-8")

        # 1) 头部「本轮」条目末尾追加复核句（该条目以 md5 句收尾）
        anchor_head = "GitHub 直连 push 本轮可用（令牌嵌 URL）无需 ghfast 代理。"
        assert anchor_head in text, "头部条目锚点未找到"
        assert HEAD_APPEND not in text, "头部复核句已存在"
        text = text.replace(anchor_head, anchor_head + HEAD_APPEND, 1)

        # 2) §12 本轮条目末尾追加复核句
        anchor_s12 = "git push main=9e43084。\n"
        assert anchor_s12 in text, "§12 条目锚点未找到"
        assert S12_APPEND not in text, "§12 复核句已存在"
        text = text.replace(anchor_s12, anchor_s12.rstrip("\n") + S12_APPEND + "\n", 1)

        with sftp.open("/wwwroot/penguin/AI-KNOWLEDGE.txt", "w") as f:
            f.write(text)
        with sftp.open("/SUZHE014/AI-KNOWLEDGE.txt", "w") as f:
            f.write(text)
        _, out, _ = c.exec_command(
            "chown www:www /wwwroot/penguin/AI-KNOWLEDGE.txt && "
            "grep -c '会话恢复复核' /wwwroot/penguin/AI-KNOWLEDGE.txt /SUZHE014/AI-KNOWLEDGE.txt",
            timeout=30)
        print(out.read().decode())
    finally:
        c.close()
    print("KB 复核句补记完成（两处）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
