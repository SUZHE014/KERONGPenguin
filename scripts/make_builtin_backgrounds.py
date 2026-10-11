#!/usr/bin/env python3
"""
生成随插件 / 模组 JAR 分发的内置背景图（1.5.7：主体黑色改深灰色）。

产出两张同风格图（径向渐变：中心深灰 → 四周渐入深绿，非上下纵向渐变）：
  - leaderboard-default.png  1200×1800（在线排行榜竖图）
  - card-default.png         1800×1200（查信息卡片 / 查在线列表共用横图）

视觉规格：
  - 主体深灰  (54, 56, 60)，中央内容区轻微压暗 (49, 51, 55) 保文字可读；
    深灰与半透明卡片叠加色（黑 α142 / 黑 α120 / 白 α34）保持 ΔL ≥ 20，
    方框卡片边界清晰可辨（1.5.7 用户要求：不要与卡片同色）。
  - 边缘深绿  (10, 72, 44)，径向 t^1.8 渐入（中心大部分区域保持深灰）。
  - 绿色装饰：四角辉光 + 左右缘光带 + 顶部微光 + 对角线纹理（64px 间隔）
    + 点阵 + 同心圆弧 + 内缩 keyline；微噪点 ±3 防色带。

自检六项（全过才写出文件）：
  暗部占比 / 内容区深灰区间 / 内容区非黑 / 边缘绿显著 / 卡片对比度 / 体积上限。

用法：python3 scripts/make_builtin_backgrounds.py
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIRS = (
    os.path.join(REPO, "server-Spigot", "src", "main", "resources", "backgrounds"),
    os.path.join(REPO, "server-NeoForge", "src", "main", "resources", "backgrounds"),
)

# ---- 配色（1.5.7：主体黑 → 深灰） ----
CENTER_GRAY = np.array([49, 51, 55], dtype=np.float64)    # 中央内容区（深灰、微暗）
BASE_GRAY = np.array([54, 56, 60], dtype=np.float64)      # 主体深灰
EDGE_GREEN = np.array([10, 72, 44], dtype=np.float64)     # 边缘深绿
DECOR_GREEN = np.array([46, 168, 104], dtype=np.float64)  # 装饰绿（辉光 / 光带 / 描边）

# 卡片叠加色（与三个渲染器实测一致），自检用：
#   查信息卡 Color(0,0,0,142) / 查在线行卡 Color(0,0,0,120) / 排行榜行卡 Color(255,255,255,34)
CARD_OVERLAYS = [
    ("查信息卡", (0, 0, 0), 142),
    ("查在线行卡", (0, 0, 0), 120),
    ("排行榜行卡", (255, 255, 255), 34),
]


def radial_t(w: int, h: int) -> np.ndarray:
    """归一化径向距离（中心 0 → 最远角 1），用于主体渐变。"""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
    dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    return dist / dist.max()


def base_layer(w: int, h: int) -> np.ndarray:
    """径向渐变主体：中心深灰 → 四周渐入深绿（t^1.8，中心大范围保持深灰）。"""
    t = radial_t(w, h)
    mix = np.clip(t, 0, 1) ** 1.8
    # 中心 45% 内向 CENTRAL_GRAY 混一点（内容区微暗，保白字可读）
    center_mix = np.clip(1.0 - t / 0.45, 0, 1) * 0.35
    img = BASE_GRAY[None, None, :] + (EDGE_GREEN - BASE_GRAY)[None, None, :] * mix[..., None]
    img = img * (1 - center_mix[..., None]) + CENTER_GRAY[None, None, :] * center_mix[..., None]
    return img


def add_corner_glows(img: np.ndarray, w: int, h: int) -> None:
    """四角辉光（1.5.6.1 起 4 角全亮）：径向亮绿，峰值随离角距离衰减。"""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    radius = 0.42 * min(w, h)
    glow = np.zeros((h, w), dtype=np.float64)
    for cx, cy in ((0, 0), (w, 0), (0, h), (w, h)):
        d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
        glow += np.clip(1.0 - d / radius, 0, 1) ** 2.2 * 46.0
    glow = np.minimum(glow, 64.0)
    img += (DECOR_GREEN - img) * (glow / 255.0)[..., None]


def add_edge_bands(img: np.ndarray, w: int, h: int) -> None:
    """左右双缘光带（边缘 6% 宽向内渐弱）。"""
    band = 0.06 * w
    xx = np.tile(np.arange(w, dtype=np.float64), (h, 1))
    strength = np.maximum(1.0 - xx / band, 1.0 - (w - 1 - xx) / band)
    strength = np.clip(strength, 0, 1) ** 1.6 * 20.0
    img += (DECOR_GREEN - img) * (strength / 255.0)[..., None]


def add_top_light(img: np.ndarray, w: int, h: int) -> None:
    """顶部微光（前 14% 高度渐弱的绿色横晕，仅装饰层次）。"""
    yy = np.tile(np.arange(h, dtype=np.float64)[:, None], (1, w))
    strength = np.clip(1.0 - yy / (0.14 * h), 0, 1) ** 1.5 * 16.0
    img += (DECOR_GREEN - img) * (strength / 255.0)[..., None]


def add_diagonal_texture(img: np.ndarray, w: int, h: int) -> None:
    """对角线纹理（45° 细线，64px 间隔，绿低透明度）。"""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    phase = ((xx + yy) % 64.0)
    line = np.clip((phase < 2.0) | (phase > 62.0), 0, 1).astype(np.float64) * 14.0
    img += (DECOR_GREEN - img) * (line / 255.0)[..., None]


def add_dot_matrix(img: np.ndarray, w: int, h: int) -> None:
    """点阵（64px 网格 2px 圆点；四角 25% 半径内加亮）。"""
    t = radial_t(w, h)
    yy, xx = np.mgrid[0:h, 0:w]
    gx = (xx % 64) - 32
    gy = (yy % 64) - 32
    dot = ((gx ** 2 + gy ** 2) <= 2.0).astype(np.float64)
    alpha = 18.0 + np.clip(1.0 - t / 0.55, 0, 1) * 22.0  # 四角附近更亮
    strength = dot * alpha
    img += (DECOR_GREEN - img) * (strength / 255.0)[..., None]


def add_concentric_arcs(img: np.ndarray, w: int, h: int) -> None:
    """同心圆弧装饰（中心 30% / 42% / 55% 对角半径三圈，虚弧）。"""
    t = radial_t(w, h)
    for radius, alpha in ((0.30, 20.0), (0.42, 15.0), (0.55, 11.0)):
        ring = np.abs(t - radius)
        arc = (np.abs(ring) < 0.0022).astype(np.float64) * alpha
        img += (DECOR_GREEN - img) * (arc / 255.0)[..., None]


def add_keyline(img: np.ndarray, w: int, h: int, inset: int = 24, alpha: float = 52.0) -> None:
    """内缩 keyline：圆角矩形描边（PIL 直接画，保证线条平滑）。"""
    # 该层单独用 PIL 画在 overlay 上再合成（numpy 换算麻烦）
    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    green = tuple(int(v) for v in DECOR_GREEN)
    draw.rounded_rectangle(
        [inset, inset, w - inset, h - inset], radius=28,
        outline=green + (int(alpha),), width=2,
    )
    arr = np.asarray(overlay).astype(np.float64)
    a = arr[..., 3:4] / 255.0
    img *= (1 - a)
    img += arr[..., :3] * a


def add_noise(img: np.ndarray, seed: int) -> None:
    """Bayer 4×4 有序抖动（固定重复模式）：防色带的同时保持 PNG 高压缩率
    （随机噪声会使体积膨胀到 2MB+，1.5.6.1 版实测有序抖动可压到 400KB 内）。
    抖动与量化步长配套（见 [quantize_step]），色带边界被抖开成不可见的花纹。"""
    bayer = np.array([
        [0, 8, 2, 10],
        [12, 4, 14, 6],
        [3, 11, 1, 9],
        [15, 7, 13, 5],
    ], dtype=np.float64) / 16.0 - 0.5
    h, w = img.shape[:2]
    pattern = np.tile(bayer, (h // 4 + 1, w // 4 + 1))[:h, :w]
    img += pattern[..., None] * QUANT_STEP


# 量化步长：4（2 阶左右差异在深色图上不可见；配合有序抖动消除色带）
QUANT_STEP = 4.0


def quantize_step(img: np.ndarray) -> np.ndarray:
    """量化到 QUANT_STEP 的倍数：相邻像素更容易一致，PNG 行间压缩率大幅提升。"""
    return np.round(img / QUANT_STEP) * QUANT_STEP


def generate(w: int, h: int, seed: int) -> Image.Image:
    img = base_layer(w, h)
    add_corner_glows(img, w, h)
    add_edge_bands(img, w, h)
    add_top_light(img, w, h)
    add_diagonal_texture(img, w, h)
    add_dot_matrix(img, w, h)
    add_concentric_arcs(img, w, h)
    add_keyline(img, w, h)
    add_noise(img, seed)
    out = np.clip(quantize_step(img), 0, 255).astype(np.uint8)
    return Image.fromarray(out)


# ---------------- 自检 ----------------

def luminance(arr: np.ndarray) -> np.ndarray:
    return arr[..., 0] * 0.299 + arr[..., 1] * 0.587 + arr[..., 2] * 0.114


def selfcheck(name: str, image: Image.Image) -> list:
    arr = np.asarray(image).astype(np.float64)
    h, w = arr.shape[:2]
    lum = luminance(arr)
    failures = []

    def req(cond, title, detail):
        print(f"  [{'PASS' if cond else 'FAIL'}] {title}: {detail}")
        if not cond:
            failures.append(title)

    # 1. 暗部占比（整体保持深色调不干扰文字）
    dark = (lum < 96).mean() * 100
    req(dark >= 90.0, "暗部占比 ≥ 90%", f"{dark:.1f}%")

    # 2/3. 内容区（中心 40% 矩形）平均亮度 ∈ [40, 72]：深灰而非黑 / 中灰
    cy0, cy1 = int(h * 0.30), int(h * 0.70)
    cx0, cx1 = int(w * 0.30), int(w * 0.70)
    content = lum[cy0:cy1, cx0:cx1]
    cl = content.mean()
    req(40.0 <= cl <= 72.0, "内容区深灰区间 [40,72]", f"{cl:.1f}")
    req(cl > 36.0, "内容区非黑 (>36)", f"{cl:.1f}")

    # 4. 边缘绿色显著（外圈 18% 环 G-R 均值）
    eh = int(h * 0.18)
    ew = int(w * 0.18)
    edge = np.concatenate([
        arr[:eh].reshape(-1, 3), arr[h - eh:].reshape(-1, 3),
        arr[:, :ew].reshape(-1, 3), arr[:, w - ew:].reshape(-1, 3),
    ])
    gr = (edge[:, 1] - edge[:, 0]).mean()
    req(gr >= 12.0, "边缘绿色显著 G-R ≥ 12", f"{gr:.1f}")

    # 5. 卡片对比度：三种半透明卡片叠加后与背景 ΔL ≥ 20
    content_rgb = arr[cy0:cy1, cx0:cx1].reshape(-1, 3)
    bg_mean = content_rgb.mean(axis=0)
    bg_l = bg_mean[0] * 0.299 + bg_mean[1] * 0.587 + bg_mean[2] * 0.114
    for label, (r, g, b), a in CARD_OVERLAYS:
        f = a / 255.0
        mixed = bg_mean * (1 - f) + np.array([r, g, b], dtype=np.float64) * f
        ml = mixed[0] * 0.299 + mixed[1] * 0.587 + mixed[2] * 0.114
        delta = abs(ml - bg_l)
        req(delta >= 20.0, f"卡片对比 {label} ΔL ≥ 20", f"ΔL={delta:.1f}")

    # 6. 体积上限（JAR 内资源体积控制）
    return failures


def main() -> None:
    specs = [("leaderboard-default.png", 1200, 1800, 20260711),
             ("card-default.png", 1800, 1200, 20260712)]
    all_failures = {}
    for name, w, h, seed in specs:
        print(f"生成 {name}（{w}×{h}）：")
        image = generate(w, h, seed)
        print(f"  自检：")
        failures = selfcheck(name, image)
        # keyline 层是 generate 内部的 PIL 合成，自检跑完才写文件
        if failures:
            all_failures[name] = failures
            continue
        for out_dir in OUT_DIRS:
            os.makedirs(out_dir, exist_ok=True)
            path = os.path.join(out_dir, name)
            image.save(path, "PNG", optimize=True, compress_level=9)
            size_kb = os.path.getsize(path) / 1024
            if size_kb > 400:
                all_failures.setdefault(name, []).append(f"体积 {size_kb:.0f}KB > 400KB")
                print(f"  [FAIL] 体积上限：{size_kb:.0f}KB")
            else:
                print(f"  [PASS] 体积上限 ≤ 400KB：{size_kb:.0f}KB")
            print(f"  写出 {path}")
    if all_failures:
        for name, failures in all_failures.items():
            for f in failures:
                print(f"FAIL {name}: {f}")
        sys.exit(1)
    print("=== 内置背景生成完成（两张图自检全过） ===")


if __name__ == "__main__":
    main()
