#!/usr/bin/env python3
"""提取 class 文件常量池中的 UTF8 字符串与结构信息（用于无 javap 环境下的反汇编辅助）。"""
import struct
import sys


def read_constant_pool(data: bytes):
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    minor, major = struct.unpack(">HH", data[4:8])
    count = struct.unpack(">H", data[8:10])[0]
    pos = 10
    consts = [None] * count
    utf8s = []
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:  # Utf8
            length = struct.unpack(">H", data[pos:pos + 2])[0]
            pos += 2
            raw = data[pos:pos + length]
            pos += length
            try:
                text = raw.decode("utf-8", "replace")
            except Exception:
                text = raw.decode("latin-1", "replace")
            consts[i] = ("utf8", text)
            utf8s.append((i, text))
        elif tag in (7, 8, 16, 19, 20):  # Class/String/Module etc -> ref utf8
            idx = struct.unpack(">H", data[pos:pos + 2])[0]
            pos += 2
            consts[i] = ("ref", tag, idx)
        elif tag == 15:  # MethodHandle
            pos += 3
            consts[i] = ("skip", tag)
        elif tag in (3, 4):  # Integer/Float
            pos += 4
            consts[i] = ("skip", tag)
        elif tag in (5, 6):  # Long/Double occupy 2 slots
            pos += 8
            consts[i] = ("skip", tag)
            consts[i + 1] = ("pad",)
            i += 2
            continue
        elif tag in (9, 10, 11, 12, 17, 18):  # Field/Method/Interface/NameType/etc
            a, b = struct.unpack(">HH", data[pos:pos + 4])
            pos += 4
            consts[i] = ("ref2", tag, a, b)
        else:
            raise ValueError(f"unknown tag {tag} at {pos}")
        i += 1
    return consts, pos, (major, minor)


def resolve(consts, idx):
    entry = consts[idx]
    if entry is None:
        return "?"
    if entry[0] == "utf8":
        return entry[1]
    if entry[0] == "ref" and entry[1] == 7:  # Class
        return resolve(consts, entry[2])
    return "?"


def main(path: str):
    with open(path, "rb") as f:
        data = f.read()
    consts, _, (major, minor) = read_constant_pool(data)
    print(f"== {path} (class file {major}.{minor}) ==")
    print("-- class refs --")
    seen = set()
    for i, entry in enumerate(consts):
        if entry and entry[0] == "ref" and entry[1] == 7:
            name = resolve(consts, entry[2])
            if name not in seen:
                seen.add(name)
                print(f"  {name}")
    print("-- utf8 strings (filtered: method-ish, log-ish) --")
    for i, entry in enumerate(consts):
        if entry and entry[0] == "utf8":
            t = entry[1]
            if len(t) > 3 and any(c in t for c in " ()<>§"):
                print(f"  #{i}: {t!r}")
    print("-- all short utf8 --")
    for i, entry in enumerate(consts):
        if entry and entry[0] == "utf8":
            print(f"  #{i}: {entry[1]!r}")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        main(p)
