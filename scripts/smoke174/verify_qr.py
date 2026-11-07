#!/usr/bin/env python3
"""1.7.4 QR 编码器双重验证：
1. 矩阵比对：Kotlin 输出矩阵 vs Python qrcode 库（同文本 / M 级 / 无边框，
   **强制字节模式**——插件只实现字节模式；旧版对比基线用 add_data(text)
   自动选模式，纯数字 / 纯大写字母输入会被库压缩成数字 / 字母数字模式，
   码字天然不同造成假 FAIL，2026-11-07 已修正）
2. 解码验证：zbar（pyzbar）解码 Kotlin 矩阵 → 还原文本；OpenCV
   QRCodeDetector 仅作附加通道（对小版本 v1 / 高密度矩阵解码能力弱，
   失败不判 FAIL——矩阵与参考逐位一致 + zbar 还原已是正确性双重证明）

用法：python3 scripts/smoke174/verify_qr.py [dump文件]（默认 /tmp/qr_dump.txt）
"""
import sys
import numpy as np

import qrcode
from qrcode import constants
from qrcode.util import QRData, MODE_8BIT_BYTE

TEXTS = [
    "https://kerong.xin/penguin/tutorial.html",
    "HELLO",
    "https://kerong.xin/penguin/",
    "12",
    "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMN",
    "中文内容测试-二维码",
]


def ref_matrix(text):
    # 强制字节模式（与插件编码器对齐；自动模式会对可压缩输入选数字 /
    # 字母数字模式导致码字不同——那是库的合法选择而非插件错误）
    qr = qrcode.QRCode(error_correction=constants.ERROR_CORRECT_M, border=0)
    qr.add_data(QRData(text.encode("utf-8"), MODE_8BIT_BYTE))
    qr.make(fit=True)
    return qr.version, np.array(qr.get_matrix(), dtype=np.uint8)


def parse_dump(path):
    """解析 Java 冒烟输出的矩阵 dump 文件"""
    entries = []
    text = size = matrix = None
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("TEXT="):
            text = line[5:]
            size = int(lines[i + 1][5:])
            rows = []
            for r in range(size):
                rows.append([1 if ch == "#" else 0 for ch in lines[i + 2 + r]])
            matrix = np.array(rows, dtype=np.uint8)
            entries.append((text, size, matrix))
            i += 2 + size + 1  # END 行
        else:
            i += 1
    return entries


def main():
    dump_path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qr_dump.txt"
    entries = parse_dump(dump_path)
    print(f"解析到 {len(entries)} 组矩阵（期望 {len(TEXTS)}，dump = {dump_path}）")
    if len(entries) != len(TEXTS):
        print("FAIL: dump 组数不符")
        sys.exit(1)

    # 主解码器：zbar（pyzbar，能力器）；OpenCV 仅附加通道（弱矩阵不判 FAIL）
    zbar_decode = None
    try:
        from pyzbar import pyzbar
        from PIL import Image

        def zbar_decode(matrix):
            n = matrix.shape[0]
            scale = 8  # 放大提高小矩阵解码率
            img = np.full(((n + 8) * scale, (n + 8) * scale), 255, dtype=np.uint8)
            inner = np.where(matrix == 1, 0, 255).astype(np.uint8)
            inner = np.kron(inner, np.ones((scale, scale), dtype=np.uint8))
            img[4 * scale:4 * scale + n * scale, 4 * scale:4 * scale + n * scale] = inner
            for result in pyzbar.decode(Image.fromarray(img)):
                if result.type == "QRCODE":
                    return result.data.decode("utf-8", "replace")
            return None
    except ImportError:
        print("WARN: pyzbar 不可用，解码通道跳过（仅矩阵比对）")

    cv_detector = None
    try:
        import cv2
        cv_detector = cv2.QRCodeDetector()
    except ImportError:
        pass

    all_ok = True
    for text, size, matrix in entries:
        version, ref = ref_matrix(text)
        same_shape = matrix.shape == ref.shape
        same_content = same_shape and bool((matrix == ref).all())
        # 主解码通道：zbar 还原原文
        decoded_ok = None
        if zbar_decode is not None:
            decoded_ok = zbar_decode(matrix) == text
        # 附加通道：cv2（仅展示，不计入判定）
        cv_note = ""
        if cv_detector is not None:
            n = matrix.shape[0]
            padded = np.full((n + 8, n + 8), 255, dtype=np.uint8)
            padded[4:4 + n, 4:4 + n] = np.where(matrix == 1, 0, 255)
            cv_decoded, _, _ = cv_detector.detectAndDecode(padded)
            cv_note = "，cv2附加通道" + ("✓" if cv_decoded == text else "✗（cv2 能力限制，不计判定）")
        status = []
        if same_content:
            status.append("矩阵=参考 ✓")
        else:
            status.append(f"矩阵≠参考（形状一致={same_shape}）")
        if decoded_ok is None:
            status.append("解码=跳过")
        elif decoded_ok:
            status.append("zbar 解码还原 ✓")
        else:
            status.append(f"zbar 解码失败（{text[:20]}…）")
        line_ok = same_content and decoded_ok in (None, True)
        all_ok = all_ok and line_ok
        print(f"{'PASS' if line_ok else 'FAIL'} [{text[:36]:<36}] v{version} {size}x{size} {', '.join(status)}{cv_note}")

    print("\n结果:", "全部通过" if all_ok else "存在失败")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
