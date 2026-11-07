#!/usr/bin/env python3
"""对比我的 RS 实现（低次在前）与 qrcode 库（高次在前）的 EC 输出"""
from qrcode.base import Polynomial, gexp, glog
from qrcode import constants
from qrcode.util import create_data, QRData, MODE_8BIT_BYTE
from qrcode.base import rs_blocks

TEXT = "HELLO"
VER = 1
EC = constants.ERROR_CORRECT_M

data = create_data(VER, EC, [QRData(TEXT.encode(), MODE_8BIT_BYTE)])
blocks = rs_blocks(VER, EC)
dc = blocks[0].data_count
ec_count = blocks[0].total_count - dc
print(f"data codewords ({len(data)}):", [hex(x) for x in data])

# ---- qrcode 的 EC 计算（高次在前） ----
dc_data = data[:dc]
rs_poly = Polynomial([1], 0)
for i in range(ec_count):
    rs_poly = rs_poly * Polynomial([1, gexp(i)], 0)
raw_poly = Polynomial(dc_data, len(rs_poly) - 1)
mod_poly = raw_poly % rs_poly
mod_offset = len(mod_poly) - ec_count
ref_ec = [(mod_poly[i + mod_offset] if i + mod_offset >= 0 else 0) for i in range(ec_count)]
print("参考 EC:", [hex(x) for x in ref_ec])

# ---- 我的 RS 实现（低次在前） ----
def my_rs(data_low_high, ec_count):
    # 生成多项式（低次在前）：∏(1 + α^i x)
    gen = [1]
    for i in range(ec_count):
        a, b = gen, [1, gexp(i)]
        out = [0] * (len(a) + len(b) - 1)
        for ii in range(len(a)):
            if a[ii] == 0:
                continue
            for jj in range(len(b)):
                out[ii + jj] ^= gexp(glog(a[ii]) + glog(b[jj]))
        gen = out
    # 除法（低次在前综合除法）
    rem = list(data_low_high) + [0] * ec_count
    for i in range(len(data_low_high)):
        factor = rem[i]
        if factor == 0:
            continue
        for j in range(1, len(gen)):
            rem[i + j] ^= gexp(glog(gen[j]) + glog(factor))
    return rem[len(data_low_high):]

my_ec = my_rs(dc_data, ec_count)
print("我的 EC:", [hex(x) for x in my_ec])
print("一致:", my_ec == ref_ec)

# 若不一致，试试反序数据（把输入当低次在前 = 参考的高次在前的反转）
# 参考把 dc_data 视为高次在前；我的低次在前实现若把同一数组当低次在前，
# 等价于反转多项式。反转多项式的余数 = 原余数的反转（当 gen 也反转时）。
# 试：data 反转后走我的实现
my_ec2 = my_rs(list(reversed(dc_data)), ec_count)
print("我的 EC(数据反转):", [hex(x) for x in my_ec2])
print("反转后一致:", list(reversed(my_ec2)) == ref_ec or my_ec2 == ref_ec)
