#!/usr/bin/env python3
"""从官网分发的 Spigot JAR 重建 common-Bot/libs/deps.jar（编译期依赖底料）。

剔除插件自身内容（cn/kerong/**、Markdown/、backgrounds/、fonts/、
plugin.yml、config.yml），保留第三方依赖字节码（kloping SDK、fastjson、
kotlin-stdlib、okhttp 等）。保留目录条目（kloping SDK 组件扫描需要）。

用法：python3 scripts/rebuild_deps_jar.py <分发JAR路径>
"""
import os
import sys
import zipfile

EXCLUDE_PREFIXES = ("cn/kerong/", "cn/huohuas001/")
EXCLUDE_EXACT = {"plugin.yml", "config.yml", "META-INF/MANIFEST.MF"}
EXCLUDE_DIR_PREFIXES = ("Markdown/", "backgrounds/", "fonts/", "META-INF/kerong")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    src = sys.argv[1]
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                       "common-Bot", "libs", "deps.jar")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    kept = 0
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            name = item.filename
            if name.endswith("/"):
                if any(name.startswith(p) for p in EXCLUDE_PREFIXES + EXCLUDE_DIR_PREFIXES):
                    continue
                zout.writestr(item, b"")
                continue
            if name in EXCLUDE_EXACT:
                continue
            if any(name.startswith(p) for p in EXCLUDE_PREFIXES + EXCLUDE_DIR_PREFIXES):
                continue
            zout.writestr(item, zin.read(name))
            kept += 1
    size = os.path.getsize(out)
    print(f"deps.jar written: {out}")
    print(f"kept entries: {kept}, size: {size / 1024 / 1024:.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
