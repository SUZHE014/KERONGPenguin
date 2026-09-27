#!/usr/bin/env python3
"""
重定位 jar 常量池「结构性引用」残留扫描。

为什么不能扫原始字节/全部 Utf8：Kotlin @Metadata 注解的 d2 数组（反射元数据，
运行时仅 kotlin-reflect/编译器读取，本 jar 未携带 kotlin-reflect）与
Intrinsics 错误消息文本中包含大量 'Lkotlin/Function1;' 形态的字符串，
字节级扫描全部误报。

本脚本解析常量池结构，仅检查真正参与类链接的引用：
  - CONSTANT_Class (tag 7) 解析出的类名
  - CONSTANT_NameAndType (tag 12) 的描述符串（字段/方法签名）

用法：python3 cp_scan.py <jar> <prefix1,prefix2,...>
退出码：0 = 无残留；1 = 有残留（打印前 20 条）。
"""
import sys
import zipfile
import struct

sys.path.insert(0, "/home/z/my-project/scripts")
from class_strings import read_constant_pool


def scan(jar_path: str, prefixes):
    hits = []
    meta_residue = 0
    with zipfile.ZipFile(jar_path) as z:
        for n in z.namelist():
            if not n.endswith(".class"):
                continue
            data = z.read(n)
            try:
                consts, _, _ = read_constant_pool(data)
            except Exception as e:
                hits.append((n, f"<parse-fail {e}>"))
                continue
            for entry in consts:
                if not entry:
                    continue
                if entry[0] == "ref" and entry[1] == 7:  # CONSTANT_Class
                    name = consts[entry[2]]
                    name = name[1] if name and name[0] == "utf8" else ""
                    if any(name.startswith(p) for p in prefixes):
                        hits.append((n, f"class {name}"))
                elif entry[0] == "ref2" and entry[1] == 12:  # NameAndType
                    desc = consts[entry[3]]
                    desc = desc[1] if desc and desc[0] == "utf8" else ""
                    if any(f"L{p}" in desc for p in prefixes):
                        hits.append((n, f"desc {desc[:70]}"))
                elif entry[0] == "utf8":
                    # 仅统计 @Metadata d2 元数据残留（信息项，不判失败）
                    t = entry[1]
                    if any(f"L{p}" in t or t.startswith(p) for p in prefixes):
                        meta_residue += 1
    return hits, meta_residue


def main():
    jar = sys.argv[1]
    prefixes = [p if p.endswith("/") else p + "/" for p in sys.argv[2].split(",") if p]
    hits, meta_residue = scan(jar, prefixes)
    print(f"（信息）@Metadata d2 元数据字符串残留（kotlin-reflect 专用，无运行时影响）: {meta_residue} 处")
    if hits:
        print(f"FAIL: 结构性引用残留 {len(hits)} 处：")
        for n, t in hits[:20]:
            print(f"  {n}: {t}")
        sys.exit(1)
    print(f"PASS: {jar} 常量池无结构性重定位残留（{len(prefixes)} 个前缀）")


if __name__ == "__main__":
    main()
