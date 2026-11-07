#!/usr/bin/env python3
"""调试：HELLO 矩阵差异定位（功能图形 / 格式信息 / 数据区分类对比）"""
import numpy as np
import qrcode
from qrcode import constants
from qrcode.main import QRCode

TEXT = "HELLO"

qr = QRCode(error_correction=constants.ERROR_CORRECT_M, border=0)
qr.add_data(TEXT)
qr.make(fit=True)
ref = np.array(qr.get_matrix(), dtype=np.uint8)
print("参考版本:", qr.version, "掩码:", qr.mask_pattern, "尺寸:", len(ref))

# 读 Java 矩阵（HELLO 是 dump 第二组）
import sys
sys.path.insert(0, ".")
from verify_qr import parse_dump  # noqa

entries = parse_dump("/tmp/qr_matrix.txt")
mine = None
for text, size, matrix in entries:
    if text == TEXT:
        mine = matrix
        break
print("Java 矩阵:", None if mine is None else mine.shape)

n = ref.shape[0]
diff = mine != ref
print(f"差异模块数: {diff.sum()} / {n * n}")

# 分类差异位置
def region(r, c):
    # 功能图形（粗略）：三个定位 7x7 + 分隔边 + 时序行列 6 + 格式信息区（8 行/列边缘）
    if (r < 9 and c < 9) or (r < 9 and c >= n - 8) or (r >= n - 8 and c < 9):
        # 进一步区分格式信息区
        if (r == 8 or c == 8) and not (r < 8 and c < 8) and not (r < 8 and c >= n - 7) and not (r >= n - 7 and c < 8):
            return "格式信息"
        if r < 8 and c < 8:
            return "定位TL"
        if r < 8 and c >= n - 8:
            return "定位TR"
        if r >= n - 8 and c < 8:
            return "定位BL"
        return "分隔/其他边缘"
    if r == 6 or c == 6:
        return "时序"
    if n >= 25 and (r in (18,) or c in (18,)) and abs(r - 18) <= 2 and abs(c - 18) <= 2:
        return "校正"
    return "数据区"

from collections import Counter
counter = Counter()
for r in range(n):
    for c in range(n):
        if diff[r][c]:
            counter[region(r, c)] += 1
print("差异分布:", dict(counter))

# 打印两个矩阵对比
print("\n参考 (R) vs Java (J)：差异位用 X 标记")
for r in range(n):
    line = ""
    for c in range(n):
        if diff[r][c]:
            line += "X"
        else:
            line += "." if ref[r][c] == 0 else "#"
    print(line)

# 顺带打印参考实现的 data codewords（对比 createCodewords）
from qrcode.util import create_data, QRData, MODE_8BIT_BYTE
data = create_data(qr.version, constants.ERROR_CORRECT_M, [QRData(TEXT.encode("utf-8"), MODE_8BIT_BYTE)])
print("\n参考数据码字:", [hex(x) for x in data[:20]])
