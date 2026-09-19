#!/usr/bin/env python3
from PIL import Image, ImageDraw
import os

SRC = "/storage/emulated/0/Download/Operit/cleanOnExit/attachment_6347191058598383667.jpg"
BASE = "/data/user/0/com.ai.assistance.operit/files/workspace/1e8622e9-13ae-47bb-a797-18061c53b403/app/src/main/res"

# 各密度 launcher 尺寸
sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

src = Image.open(SRC).convert("RGBA")
# 居中裁剪为正方形
w, h = src.size
side = min(w, h)
left = (w - side) // 2
top = (h - side) // 2
square = src.crop((left, top, left + side, top + side))

def make_round(img, size):
    img = img.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse((0, 0, size, size), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out

def make_square(img, size):
    return img.resize((size, size), Image.LANCZOS)

for folder, size in sizes.items():
    d = os.path.join(BASE, folder)
    os.makedirs(d, exist_ok=True)
    sq = make_square(square, size)
    sq.save(os.path.join(d, "ic_launcher.png"))
    rd = make_round(square, size)
    rd.save(os.path.join(d, "ic_launcher_round.png"))
    print(folder, size, "done")

print("ALL DONE")
