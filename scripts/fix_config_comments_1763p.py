#!/usr/bin/env python3
"""1.7.6.3 二次覆盖轮：三端 config.yml 全键注释补齐（锚定插入 + 自带复审）。

对 audit_config_comments.py 审出的 28 个缺注释键逐个补一行注释
（用户口径「确保每个配置都有注释，仔细检查」）；
锚点 = 前一行 + 本行，要求每个文件恰好命中一次，命中数 != 1 即报错退出。
跑完自动调用审计复审，三端必须全 PASS。
"""
import sys

sys.path.insert(0, "/home/z/my-project/scripts")
from audit_config_comments import FILES, audit  # noqa: E402

PAIRS = [
    ("update-check:\n  enabled: true",
     "update-check:\n  # 检查开关（false = 不联网检查）\n  enabled: true"),
    ("  app-id: \"\"\n  secret: \"\"",
     "  app-id: \"\"\n  # 密钥（QQ 开放平台获取，与 AppID 同段；⚠ 改后必须重启）\n  secret: \"\""),
    ("  cluster:\n    name: \"\"",
     "  cluster:\n    # 本服显示名（单服留空 = 用 serverName）\n    name: \"\""),
    ("  join:\n    enabled: true\n    format: \"[游戏] {name} 加入了服务器\"\n  quit:\n    enabled: true\n    format: \"[游戏] {name} 离开了服务器\"",
     "  join:\n    # 进服播报开关\n    enabled: true\n    # 进服播报文案\n    format: \"[游戏] {name} 加入了服务器\"\n  # 离服播报（与进服同结构）\n  quit:\n    # 离服播报开关\n    enabled: true\n    # 离服播报文案\n    format: \"[游戏] {name} 离开了服务器\""),
    ("  death:\n    enabled: false\n    format: \"[游戏] {message}\"",
     "  death:\n    # 死亡播报开关（默认关）\n    enabled: false\n    # 死亡播报文案（{message} 自动中文死亡描述）\n    format: \"[游戏] {message}\""),
    ("  shutdown:\n    enabled: true\n",
     "  # 正常关闭时发送\n  shutdown:\n    # 发送开关\n    enabled: true\n"),
    ("  restart:\n    enabled: true\n",
     "  # 判定为重启时发送\n  restart:\n    # 发送开关\n    enabled: true\n"),
    ("  start:\n    enabled: true\n",
     "  # 普通开机时发送\n  start:\n    # 发送开关\n    enabled: true\n"),
    ("  crash:\n    enabled: true\n",
     "  # 上次未正常关闭时发送\n  crash:\n    # 发送开关\n    enabled: true\n"),
    ("  server-ip: \"\"\n  server-port: 0",
     "  server-ip: \"\"\n  # 公网端口（与 server-ip 配套，供状态图 api 占位符替换）\n  server-port: 0"),
    ("  info-card-builtin-background: true\n  online-list-builtin-background: true",
     "  info-card-builtin-background: true\n  # 查在线列表卡片内置背景（false = 用 img/ 随机图）\n  online-list-builtin-background: true"),
    ("image-download:\n  enabled: true",
     "image-download:\n  # 图片下载总开关\n  enabled: true"),
    ("  code-expire-minutes: 10\n  code-length: 5",
     "  code-expire-minutes: 10\n  # 绑定码位数（4-8）\n  code-length: 5"),
    ("  checkin:\n    enabled: false\n    prefix: \"[签到]\"",
     "  checkin:\n    # 签到开关（默认关）\n    enabled: false\n    # 签到回复前缀\n    prefix: \"[签到]\""),
    ("admin:\n  mode: qq",
     "admin:\n  # 管理员判定模式（qq / both / config）\n  mode: qq"),
    ("  server-output-prefix: \"[AI]\"\n  qq-output-prefix: \"[AI]\"",
     "  server-output-prefix: \"[AI]\"\n  # QQ 群里回复的前缀\n  qq-output-prefix: \"[AI]\""),
    ("  memes:\n    enabled: true",
     "  memes:\n    # 表情包开关（关闭后 AI 不发表情）\n    enabled: true"),
    ("  crawler:\n    enabled: true",
     "  crawler:\n    # 网页读取开关\n    enabled: true"),
]


def main():
    failed = False
    for path in FILES:
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        for old, new in PAIRS:
            hits = text.count(old)
            if hits != 1:
                print(f"[FAIL] {path}: 锚点非唯一({hits}): {old.splitlines()[0]} ... {old.splitlines()[-1]}")
                failed = True
                continue
            text = text.replace(old, new, 1)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)
    if failed:
        return 1
    # 自带复审：三端必须全 PASS
    bad = 0
    for path in FILES:
        keys, missing = audit(path)
        tag = path.split("/main/resources/")[0].split("/")[-1]
        if missing:
            bad += len(missing)
            print(f"[{tag}] 仍有 {len(missing)} 键缺注释：")
            for ln, t in missing:
                print(f"  {ln}: {t}")
        else:
            print(f"[{tag}] {keys} 键全部有注释 PASS")
    print(f"===== 复审: {'PASS 全键有注释' if bad == 0 else f'FAIL {bad} 键缺注释'} =====")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
