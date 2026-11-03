#!/usr/bin/env python3
"""make_bpng_manifest.py —— 生成 bpng 图片包清单 manifest.json。

用法：
    python3 make_bpng_manifest.py <图片目录> <包ID> <包名> [输出路径]

清单格式（官网 /wwwroot/penguin/bpng/manifest.json；1.7.2 起每包新增
count / bytes 两个展示字段，插件按清单动态渲染张数与体积）：
    {
      "packs": [
        {
          "id": "wl1",
          "name": "蔚蓝档案",
          "description": "……",
          "files": [ {"name": "xxx.jpg", "size": 123, "md5": "..."} ]
        }
      ]
    }
"""
import hashlib
import json
import os
import sys

ALLOWED_EXT = {".png", ".jpg", ".jpeg", ".bmp", ".gif"}


def main() -> int:
    src = sys.argv[1]
    pack_id = sys.argv[2]
    pack_name = sys.argv[3]
    out = sys.argv[4] if len(sys.argv) > 4 else os.path.join(src, "manifest.json")
    desc = sys.argv[5] if len(sys.argv) > 5 else f"{pack_name}主题背景图片包"

    files = []
    for name in sorted(os.listdir(src)):
        path = os.path.join(src, name)
        if not os.path.isfile(path):
            continue
        if os.path.splitext(name)[1].lower() not in ALLOWED_EXT:
            continue
        data = open(path, "rb").read()
        files.append({
            "name": name,
            "size": len(data),
            "md5": hashlib.md5(data).hexdigest(),
        })
    if not files:
        print("no image files found")
        return 1

    manifest = {
        "packs": [{
            "id": pack_id,
            "name": pack_name,
            "description": desc,
            "count": len(files),
            "bytes": sum(f["size"] for f in files),
            "files": files,
        }]
    }
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=1)
    total = sum(f["size"] for f in files)
    print(f"manifest written: {out}")
    print(f"pack {pack_id} ({pack_name}): {len(files)} files, {total/1024/1024:.2f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
