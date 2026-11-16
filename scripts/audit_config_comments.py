#!/usr/bin/env python3
"""1.7.6.3 二次覆盖轮：三端 config.yml 全键注释审计。

规则（用户口径「确保每个配置都有注释，仔细检查」）：
- 每个「键: 值」行必须带注释：同行 # 注释，或紧邻上方（跨空行）最近
  的非空行是注释行（块注释）；否则记为缺注释。
- 只审计生效行：# 开头的注释行跳过；列表项（- 开头）里的 key 跳过。
- 段落键（无值）如 `crawler:`、`memes:` 同样审计（子键有自己的注释，
  段落键本身靠段落标题注释行覆盖——上一位非空行是注释即算过）。

输出：每个文件缺注释键清单（路径:行号:内容），全部通过则 PASS。
"""
import re
import sys

FILES = [
    "/home/z/my-project/repo/server-Spigot/src/main/resources/config.yml",
    "/home/z/my-project/repo/server-NeoForge/src/main/resources/config.yml",
    "/home/z/my-project/repo/server-Fabric/src/main/resources/config.yml",
]

KEY_RE = re.compile(r'^(\s*)([A-Za-z][\w.-]*|"[^"]+")\s*:(\s|$)')


def is_comment(line: str) -> bool:
    return line.lstrip().startswith("#")


def is_blank(line: str) -> bool:
    return line.strip() == ""


def is_list_item(line: str) -> bool:
    return line.lstrip().startswith("-")


def prev_nonblank(lines, idx):
    for j in range(idx - 1, -1, -1):
        if not is_blank(lines[j]):
            return lines[j]
    return None


def audit(path: str):
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    missing = []
    keys = 0
    for i, line in enumerate(lines):
        if is_comment(line) or is_list_item(line):
            continue
        if not KEY_RE.match(line):
            continue
        keys += 1
        inline = "#" in line.split(":", 1)[1]
        if inline:
            continue
        up = prev_nonblank(lines, i)
        if up is None or not is_comment(up):
            missing.append((i + 1, line.rstrip()))
    return keys, missing


def main():
    total_bad = 0
    for path in FILES:
        keys, missing = audit(path)
        tag = path.split("/main/resources/")[0].split("/")[-1]
        if missing:
            total_bad += len(missing)
            print(f"[{tag}] {keys} 键，缺注释 {len(missing)} 个：")
            for ln, text in missing:
                print(f"  {ln}: {text}")
        else:
            print(f"[{tag}] {keys} 键，全部有注释 PASS")
    print(f"===== 审计结果: {'PASS 全键有注释' if total_bad == 0 else f'FAIL {total_bad} 键缺注释'} =====")
    return 0 if total_bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
