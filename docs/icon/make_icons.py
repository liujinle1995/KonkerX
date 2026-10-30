# -*- coding: utf-8 -*-
"""
把 ImageGen 产出的原始图处理成 Android 图标资源。

流程：
1. 裁掉右下角的生成水印（水印在画面最底部，方框主体离它很远）
2. 按亮度找出主体（霓虹描边）的包围盒，围绕它裁出正方形，主体占画布固定比例
3. 输出 legacy 图标（圆角）与 adaptive icon 背景层（108dp 各密度）

要求：Pillow。用系统 Python 3.14 跑（C:\\Users\\94591\\AppData\\Local\\Programs\\Python\\Python314\\python.exe）。
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance

SRC = r"D:\app\KonkerX\docs\icon\icon_src.png"
RES = r"D:\app\KonkerX\manager\src\main\res"

CANVAS = 1024
# 主体宽度占整图比例。adaptive 可见区是 72/108 ≈ 66.7%，
# 0.46 → 主体约占可见区的 69%，圆形遮罩下也不顶边
SUBJECT_RATIO = 0.40
CUT_BOTTOM = 0.12          # 从底部裁掉的比例（水印所在）
LUMA_THRESHOLD = 58        # 高于它的像素算主体

img = Image.open(SRC).convert("RGB")
W, H = img.size
img = img.crop((0, 0, W, int(H * (1 - CUT_BOTTOM))))
W, H = img.size

mask = img.convert("L").point(lambda v: 255 if v > LUMA_THRESHOLD else 0)
bbox = mask.getbbox()
if bbox is None:
    raise SystemExit("没找到主体，检查 LUMA_THRESHOLD")
bx0, by0, bx1, by1 = bbox
bw, bh = bx1 - bx0, by1 - by0
print(f"source={W}x{H} bbox={bbox} subject={bw}x{bh}")

# 围绕主体中心裁正方形：边长取主体长边 / 比例，尽量把背景里的辉光一起收进来
side = int(max(bw, bh) / SUBJECT_RATIO)
cx, cy = (bx0 + bx1) // 2, (by0 + by1) // 2
left, top = cx - side // 2, cy - side // 2
right, bottom = left + side, top + side

canvas = Image.new("RGB", (side, side), (4, 7, 14))
sx0, sy0 = max(0, left), max(0, top)
sx1, sy1 = min(W, right), min(H, bottom)
canvas.paste(img.crop((sx0, sy0, sx1, sy1)), (sx0 - left, sy0 - top))
master = canvas.resize((CANVAS, CANVAS), Image.LANCZOS)
master = master.filter(ImageFilter.UnsharpMask(radius=2.0, percent=52, threshold=2))
master = ImageEnhance.Contrast(master).enhance(1.05)
master.save(r"D:\app\KonkerX\docs\icon\icon_master.png")
print("master saved")


def rounded(im, radius_ratio=0.24):
    w, h = im.size
    r = int(w * radius_ratio)
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], radius=r, fill=255)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(im, (0, 0), m)
    return out


LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

for dpi, size in LEGACY.items():
    d = os.path.join(RES, f"mipmap-{dpi}")
    os.makedirs(d, exist_ok=True)
    small = master.resize((size, size), Image.LANCZOS)
    small = small.filter(ImageFilter.UnsharpMask(radius=0.8, percent=65, threshold=1))
    rounded(small).save(os.path.join(d, "ic_launcher.png"))
    print("legacy", dpi, size)

for dpi, size in ADAPTIVE.items():
    d = os.path.join(RES, f"mipmap-{dpi}")
    os.makedirs(d, exist_ok=True)
    master.resize((size, size), Image.LANCZOS).save(os.path.join(d, "ic_launcher_bg.png"))
    print("adaptive-bg", dpi, size)

# 核对图：圆脸 / 圆角方 / 小尺寸
prev = Image.new("RGBA", (512, 168), (255, 255, 255, 255))
circ_mask = Image.new("L", (144, 144), 0)
ImageDraw.Draw(circ_mask).ellipse([0, 0, 143, 143], fill=255)
circ = Image.new("RGBA", (144, 144), (0, 0, 0, 0))
vis = int(CANVAS * 72 / 108)
crop = master.crop((int(CANVAS / 2 - vis / 2), int(CANVAS / 2 - vis / 2),
                    int(CANVAS / 2 + vis / 2), int(CANVAS / 2 + vis / 2)))
circ.paste(crop.resize((144, 144), Image.LANCZOS), (0, 0), circ_mask)
prev.paste(circ, (24, 12), circ)
sq = rounded(master.resize((144, 144), Image.LANCZOS))
prev.paste(sq, (196, 12), sq)
small = rounded(master.resize((48, 48), Image.LANCZOS), 0.24)
prev.paste(small, (376, 60), small)
prev.save(r"D:\app\KonkerX\docs\icon\preview.png")
print("done")
