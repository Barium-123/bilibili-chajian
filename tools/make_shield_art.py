#!/usr/bin/env python3
"""把用户提供的插画做成遮挡框资源。

源图：tools/source/shield_source.png（从用户提供的截图里裁出来的 2233 插画，
      版权归哔哩哔哩所有，仅用于本机自用，不要再分发）

输出：
    app/src/main/res/drawable-nodpi/shield_art_light.png   白底直接用
    app/src/main/res/drawable-nodpi/shield_art_dark.png    深色背景：白色圆角卡片托住插画

Run:  python tools/make_shield_art.py
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "tools", "source", "shield_source.png")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")

TARGET_W = 700  # 3x 屏上 220dp ≈ 660px，取 700 保证不糊


def load_art():
    art = Image.open(SRC).convert("RGB")
    height = round(TARGET_W * art.height / art.width)
    return art.resize((TARGET_W, height), Image.LANCZOS)


def make_light(art):
    canvas = Image.new("RGB", art.size, (255, 255, 255))
    canvas.paste(art, (0, 0))
    return canvas


def make_dark(art):
    """深色背景适配：插画本身是白底线稿，直接放黑底会糊成一块，
    所以托一张白色圆角卡片，四周透明，圆角外能看到遮罩底色。"""
    pad = 24
    w = art.width + pad * 2
    h = art.height + pad * 2
    radius = 46

    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=255)

    card = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(card)
    d.rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=(255, 255, 255, 255))
    card.paste(art, (pad, pad))

    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(card, (0, 0), mask)
    return out


def main():
    art = load_art()
    os.makedirs(OUT, exist_ok=True)

    light = make_light(art)
    light.save(os.path.join(OUT, "shield_art_light.png"))
    print("wrote shield_art_light.png", light.size)

    dark = make_dark(art)
    dark.save(os.path.join(OUT, "shield_art_dark.png"))
    print("wrote shield_art_dark.png", dark.size)


if __name__ == "__main__":
    main()
