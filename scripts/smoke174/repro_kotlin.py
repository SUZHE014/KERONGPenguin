#!/usr/bin/env python3
"""复刻 Kotlin QrCode.kt 的矩阵构建逻辑，喂相同码字，与 Kotlin dump 对比。

目的：区分「Kotlin 实现了自认为的逻辑」（复刻一致）vs「逻辑本身有 bug」
（复刻与库不一致），并在 Python 侧快速二分到具体步骤。
"""
import sys
import numpy as np

sys.path.insert(0, "scripts/smoke174")
from verify_qr import parse_dump

TEXT = "HELLO"


def kotlin_blank_canvas(version):
    """复刻 Kotlin blankCanvas：定位×3 + 校正 + 时序（None=未填，True/False=已定）"""
    size = version * 4 + 17
    m = [[None] * size for _ in range(size)]

    def set_module(r, c, dark):
        if 0 <= r < size and 0 <= c < size:
            m[r][c] = dark

    def probe(row, col):
        for r in range(-1, 8):
            for c in range(-1, 8):
                dark = ((0 <= r <= 6 and c in (0, 6))
                        or (0 <= c <= 6 and r in (0, 6))
                        or (2 <= r <= 4 and 2 <= c <= 4))
                set_module(row + r, col + c, dark)

    probe(0, 0)
    probe(size - 7, 0)
    probe(0, size - 7)

    align = {1: [], 2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30],
             6: [6, 34], 7: [6, 22, 38]}[version]
    for r in align:
        for c in align:
            if m[r][c] is not None:
                continue
            for dr in range(-2, 3):
                for dc in range(-2, 3):
                    set_module(r + dr, c + dc,
                               dr in (-2, 2) or dc in (-2, 2) or (dr == 0 and dc == 0))

    for i in range(8, size - 8):
        if m[i][6] is None:
            m[i][6] = i % 2 == 0
        if m[6][i] is None:
            m[6][i] = i % 2 == 0
    return m


def kotlin_write_type_info(canvas, mask, test_mode):
    size = len(canvas)
    # bchTypeInfo 复刻（EC M = 0b00）
    G15 = 0b10100110111
    G15_MASK = 0b101010000010010

    def bch(data):
        d = data << 10
        while d.bit_length() - G15.bit_length() >= 0:
            d ^= G15 << (d.bit_length() - G15.bit_length())
        return ((data << 10) | d) ^ G15_MASK

    bits = bch((0b00 << 3) | mask)
    for i in range(15):
        mod = (not test_mode) and ((bits >> i) & 1) == 1
        if i < 6:
            canvas[i][8] = mod
        elif i < 8:
            canvas[i + 1][8] = mod
        else:
            canvas[size - 15 + i][8] = mod
    for i in range(15):
        mod = (not test_mode) and ((bits >> i) & 1) == 1
        if i < 8:
            canvas[8][size - i - 1] = mod
        elif i == 8:
            canvas[8][7] = mod
        else:
            canvas[8][15 - i - 1] = mod
    canvas[size - 8][8] = not test_mode


def kotlin_mask_applies(mask, row, col):
    return [
        (row + col) % 2 == 0,
        row % 2 == 0,
        col % 3 == 0,
        (row + col) % 3 == 0,
        (row // 2 + col // 3) % 2 == 0,
        (row * col) % 2 + (row * col) % 3 == 0,
        ((row * col) % 2 + (row * col) % 3) % 2 == 0,
        ((row + col) % 2 + (row * col) % 3) % 2 == 0,
    ][mask]


def kotlin_map_data(canvas, data, mask):
    """复刻 Kotlin mapData（列迭代器与列值分开版）"""
    size = len(canvas)
    inc = -1
    row = size - 1
    bit_index = 7
    byte_index = 0
    col_iter = size - 1
    while col_iter > 0:
        col = col_iter
        if col <= 6:
            col -= 1
        col_pair = col
        while True:
            for c in (col_pair, col_pair - 1):
                if canvas[row][c] is None:
                    dark = False
                    if byte_index < len(data):
                        dark = ((data[byte_index] >> bit_index) & 1) == 1
                    if kotlin_mask_applies(mask, row, c):
                        dark = not dark
                    canvas[row][c] = dark
                    bit_index -= 1
                    if bit_index == -1:
                        byte_index += 1
                        bit_index = 7
            row += inc
            if row < 0 or row >= size:
                row -= inc
                inc = -inc
                break
        col_iter -= 2


def finder_like(m, scan, fixed, horizontal):
    def at(s):
        return m[fixed][s] if horizontal else m[s][fixed]
    b = [at(scan + i) for i in range(11)]
    return (not b[1] and b[4] and not b[5] and b[6] and not b[9]
            and ((b[0] and b[2] and b[3] and not b[7] and not b[8] and not b[10])
                 or (not b[0] and not b[2] and not b[3] and b[7] and b[8] and b[10])))


def kotlin_lost_point(canvas):
    size = len(canvas)
    m = [[canvas[r][c] is True for c in range(size)] for r in range(size)]
    lost = 0
    run_count = [0] * (size + 1)
    for r in range(size):
        length = 1
        for c in range(1, size):
            if m[r][c] == m[r][c - 1]:
                length += 1
            else:
                if length >= 5:
                    run_count[length] += 1
                length = 1
        if length >= 5:
            run_count[length] += 1
    for c in range(size):
        length = 1
        for r in range(1, size):
            if m[r][c] == m[r - 1][c]:
                length += 1
            else:
                if length >= 5:
                    run_count[length] += 1
                length = 1
        if length >= 5:
            run_count[length] += 1
    for length in range(5, size + 1):
        lost += run_count[length] * (length - 2)
    for r in range(size - 1):
        for c in range(size - 1):
            if m[r][c] == m[r][c + 1] == m[r + 1][c] == m[r + 1][c + 1]:
                lost += 3
    for r in range(size):
        for c in range(size - 10):
            if finder_like(m, c, r, True):
                lost += 40
    for c in range(size):
        for r in range(size - 10):
            if finder_like(m, r, c, False):
                lost += 40
    dark = sum(row.count(True) for row in m)
    percent = dark / (size * size)
    lost += int(abs(percent * 100 - 50) / 5) * 10
    return lost


def kotlin_build(data, version, mask):
    canvas = kotlin_blank_canvas(version)
    kotlin_write_type_info(canvas, mask, test_mode=False)
    kotlin_map_data(canvas, data, mask)
    return canvas


def main():
    # 参考码字（与 debug_rs.py 同源：HELLO v1-M）
    from qrcode.util import create_data, QRData
    from qrcode.util import MODE_8BIT_BYTE
    from qrcode import constants
    data = create_data(1, constants.ERROR_CORRECT_M, [QRData(TEXT.encode(), MODE_8BIT_BYTE)])

    entries = parse_dump("/tmp/qr_dump.txt")
    kotlin_matrix = None
    for text, size, matrix in entries:
        if text == TEXT:
            kotlin_matrix = matrix  # 0/1，1=深
    assert kotlin_matrix is not None

    # 复刻：选定掩码与 Kotlin 相同怎么知道？Kotlin encodeDebug 有 mask——
    # 此处对 8 个掩码分别构建，找与 Kotlin dump 匹配的那个
    print("各掩码复刻矩阵 vs Kotlin dump 差异数：")
    for mask in range(8):
        canvas = kotlin_build(data, 1, mask)
        diff = 0
        for r in range(21):
            for c in range(21):
                if (canvas[r][c] is True) != (kotlin_matrix[r][c] == 1):
                    diff += 1
        print(f"  mask {mask}: diff={diff}")

    # 与参考库（自动掩码）对比
    import qrcode
    qr = qrcode.QRCode(error_correction=constants.ERROR_CORRECT_M, border=0)
    qr.add_data(TEXT)
    qr.make(fit=True)
    ref = np.array(qr.get_matrix())
    print("\n参考库掩码:", qr.mask_pattern)
    for mask in range(8):
        canvas = kotlin_build(data, 1, mask)
        diff = sum(1 for r in range(21) for c in range(21)
                   if (canvas[r][c] is True) != bool(ref[r][c]))
        print(f"  复刻 mask {mask} vs 参考: diff={diff}")


if __name__ == "__main__":
    main()
