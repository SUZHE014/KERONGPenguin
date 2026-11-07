#!/usr/bin/env python3
"""检查 Java 矩阵是否匹配参考库 8 个掩码中的某一个（区分『掩码选择不同』vs『编码错误』）"""
import numpy as np
import qrcode
from qrcode import constants
from qrcode.main import QRCode
from verify_qr import parse_dump

TEXTS = [
    "https://kerong.xin/penguin/tutorial.html",
    "HELLO",
    "https://kerong.xin/penguin/",
    "12",
    "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMN",
    "中文内容测试-二维码",
]

entries = parse_dump("/tmp/qr_matrix.txt")
mine = {t: m for t, s, m in entries}

for text in TEXTS:
    m = mine[text]
    matches = []
    for k in range(8):
        qr = QRCode(error_correction=constants.ERROR_CORRECT_M, border=0, mask_pattern=k)
        qr.add_data(text.encode("utf-8"))
        try:
            qr.make(fit=False)
        except Exception:
            # make(fit=False) 需要 version；改用 fit 后手动指定 mask 不行——
            # QRCode 的 mask_pattern 参数在 make(fit=True) 下也会生效
            continue
        ref = np.array(qr.get_matrix(), dtype=np.uint8)
        if ref.shape == m.shape and (ref == m).all():
            matches.append(k)
    # 用 fit=True + mask_pattern（构造后 make 前设置）
    matches2 = []
    for k in range(8):
        qr = QRCode(error_correction=constants.ERROR_CORRECT_M, border=0)
        qr.mask_pattern = k
        qr.add_data(text.encode("utf-8"))
        qr.make(fit=True)
        ref = np.array(qr.get_matrix(), dtype=np.uint8)
        if ref.shape == m.shape and (ref == m).all():
            matches2.append(k)
    print(f"[{text[:36]:<36}] 形状={m.shape[0]} 显式掩码匹配={matches2}")
