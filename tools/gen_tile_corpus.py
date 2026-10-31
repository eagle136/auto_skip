"""瓦片级语料：160×100 瓦片 → 96×64，阳性 = 瓦片内完整含一个「跳过」胶囊。

和旧语料（96×32 紧框）的区别只在一件事：模型不再判断「这块像素像不像跳过」，
而是判断「这块瓦片里有没有跳过按钮」。所以阳性必须把胶囊**放在瓦片内的随机位置**，
周围带真实背景——这正是扫带时模型看到的样子。

负样本分三档：
  1. 纯背景瓦片（真机帧随机位置切）；
  2. 胶囊外形但写别的字（关闭 / 了解详情 / 立即下载 / 广告 / 只有数字）；
  3. 真机帧里挖出来的负瓦片（无胶囊）。

缩放必须用 align_corners=False 的纯双线性（与 app 里 SkipNet.resampleInto 同口径），
不能用 PIL 的 BILINEAR——后者缩小时带抗锯齿，等于给模型喂了另一种图。
"""
import argparse
import csv
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT_DIR = os.environ.get("SYSTEMROOT", r"C:\Windows") + os.sep + "Fonts"
FONT_FILES = ["msyh.ttc", "msyhbd.ttc", "simhei.ttf", "Deng.ttf", "simsun.ttc", "simkai.ttf"]

IN_W, IN_H = 96, 64
TILE_W, TILE_H = 160, 100

SKIP_TEXTS = ["跳过", "跳過", "跳过", "跳过", "skip", "SKIP"]
COUNT_PREFIX = ["", "", "5 | ", "3|", "10 | ", "2 | ", "8 | "]
NEG_TEXTS = ["关闭", "关闭广告", "了解详情", "点击查看", "立即下载", "点击安装",
             "广告", "领取", "抽奖", "更多", "查看详情", "点击跳过详情"]


def resize_exact(im, ow=IN_W, oh=IN_H):
    """与 Kotlin 侧 resampleInto 一致：align_corners=False 双线性，无抗锯齿。"""
    a = np.asarray(im.convert("RGB"), np.float32)
    h, w = a.shape[:2]
    xs = np.clip((np.arange(ow) + 0.5) * w / ow - 0.5, 0, w - 1)
    ys = np.clip((np.arange(oh) + 0.5) * h / oh - 0.5, 0, h - 1)
    x0 = np.floor(xs).astype(int); x1 = np.minimum(x0 + 1, w - 1); fx = (xs - x0)[None, :, None]
    y0 = np.floor(ys).astype(int); y1 = np.minimum(y0 + 1, h - 1); fy = (ys - y0)[:, None, None]
    top = a[y0][:, x0] * (1 - fx) + a[y0][:, x1] * fx
    bot = a[y1][:, x0] * (1 - fx) + a[y1][:, x1] * fx
    return Image.fromarray(np.clip(top * (1 - fy) + bot * fy, 0, 255).astype(np.uint8), "RGB")


def load_frames(raw_dir):
    """真机裸帧：16B 头 + BGRA，压一半成 RGB（与 app 的 halveLuma 同尺度）。"""
    out = []
    for name in sorted(os.listdir(raw_dir)):
        if not name.endswith(".raw"):
            continue
        p = os.path.join(raw_dir, name)
        with open(p, "rb") as f:
            head = f.read(16)
            w, h, fmt, _ = np.frombuffer(head, "<i4")
            if fmt != 1:
                continue
            buf = np.frombuffer(f.read(), np.uint8)
        if buf.size < 4 * w * h:
            continue
        bgra = buf[:4 * w * h].reshape(h, w, 4)
        rgb = bgra[:, :, [2, 1, 0]].astype(np.float32)
        rgb = rgb.reshape(h // 2, 2, w // 2, 2, 3).mean(axis=(1, 3))
        out.append(np.clip(rgb, 0, 255).astype(np.uint8))
    return out


def pick_font(rng, px):
    for _ in range(12):
        name = FONT_FILES[rng.integers(0, len(FONT_FILES))]
        p = os.path.join(FONT_DIR, name)
        if not os.path.exists(p):
            continue
        try:
            return ImageFont.truetype(p, px)
        except Exception:
            continue
    return ImageFont.truetype(os.path.join(FONT_DIR, "simhei.ttf"), px)


def render_label(rng, text, w, h, positive):
    """画一个胶囊贴片（RGBA）。返回 (贴片, 文字外接框相对偏移)。"""
    spr = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    dr = ImageDraw.Draw(spr)
    style = rng.random()
    if style < 0.68:                       # 实心胶囊（真机最常见：深灰底白字）
        fill = int(rng.integers(15, 75)) if rng.random() < 0.75 else int(rng.integers(200, 250))
        alpha = int(rng.integers(180, 240))
        ink = 255 if fill < 128 else 20
        dr.rounded_rectangle((0, 0, w - 1, h - 1), radius=int(h * rng.uniform(0.35, 0.5)),
                             fill=(fill, fill, fill, alpha))
    elif style < 0.86:                     # 描边胶囊
        ink = 255 if rng.random() < 0.6 else 20
        c = 255 if ink == 20 else int(rng.integers(180, 240))
        dr.rounded_rectangle((1, 1, w - 2, h - 2), radius=int(h * rng.uniform(0.35, 0.5)),
                             outline=(c, c, c, int(rng.integers(170, 230))),
                             width=max(1, int(rng.integers(1, 3))))
    else:                                  # 裸字
        ink = 255 if rng.random() < 0.7 else 20
    # 字号：字高占胶囊高的 0.5–0.75
    px = max(8, int(h * rng.uniform(0.50, 0.75)))
    font = pick_font(rng, px)
    tw = dr.textlength(text, font=font)
    if tw > w - 4:                          # 太宽就缩字号，别让字被切掉
        px = max(6, int(px * (w - 4) / tw))
        font = pick_font(rng, px)
        tw = dr.textlength(text, font=font)
    bbox = font.getbbox(text)
    th = bbox[3] - bbox[1]
    tx = (w - tw) / 2.0
    ty = (h - th) / 2.0 - bbox[1]
    dr.text((tx, ty), text, font=font, fill=(ink, ink, ink, 255))
    return spr, ("filled" if style < 0.68 else "outline" if style < 0.86 else "bare")


def composite(rng, bg, sprite, x, y):
    """sprite 贴到 bg（numpy RGB）的 (x,y)，alpha 混合。"""
    h, w = sprite.size[1], sprite.size[0]
    a = np.asarray(sprite, np.float32) / 255.0
    region = bg[y:y + h, x:x + w].astype(np.float32)
    out = region * (1 - a[..., 3:4]) + (a[..., :3] * 255.0) * a[..., 3:4]
    bg[y:y + h, x:x + w] = np.clip(out, 0, 255).astype(np.uint8)
    return bg


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", default=r"D:/tmp/skip_probe/byfen_live2")
    ap.add_argument("--extra", default=r"D:/tmp/skip_probe/byfen_live")
    ap.add_argument("--out", default=r"D:/tmp/tile_corpus")
    ap.add_argument("--pos", type=int, default=6000)
    ap.add_argument("--neg", type=int, default=12000)
    ap.add_argument("--seed", type=int, default=20261029)
    a = ap.parse_args()

    rng = np.random.default_rng(a.seed)
    frames = load_frames(a.raw)
    # 真机 PNG 帧也并进背景池
    for name in sorted(os.listdir(a.extra)):
        if name.endswith(".png"):
            im = Image.open(os.path.join(a.extra, name)).convert("RGB")
            frames.append(np.asarray(im.resize((im.width // 2, im.height // 2), Image.BILINEAR)))
    if not frames:
        sys.exit("没有可用背景帧")
    print(f"[bg] {len(frames)} 帧 {frames[0].shape}")

    os.makedirs(os.path.join(a.out, "pos"), exist_ok=True)
    os.makedirs(os.path.join(a.out, "neg"), exist_ok=True)
    rows = []

    def write(img, label, group, idx, text, style):
        sub = "pos" if label == 0 else "neg"
        name = f"{'p' if label == 0 else 'n'}_{idx:06d}.png"
        resize_exact(img).save(os.path.join(a.out, sub, name))
        # manifest 是 ASCII：中文文案按 unicode_escape 存，训练器那边原样读
        base = text.encode("unicode_escape").decode("ascii") if text else "none"
        rows.append({"label": label, "file": name, "group": group,
                     "base": base, "style": style})

    for i in range(a.pos):
        bg = frames[rng.integers(0, len(frames))]
        H, W = bg.shape[:2]
        x = int(rng.integers(0, W - TILE_W)); y = int(rng.integers(0, H - TILE_H))
        patch = bg[y:y + TILE_H, x:x + TILE_W].copy()
        text = COUNT_PREFIX[rng.integers(0, len(COUNT_PREFIX))] + SKIP_TEXTS[rng.integers(0, len(SKIP_TEXTS))]
        cw = int(rng.integers(58, 118)); ch = int(rng.integers(26, 46))
        spr, style = render_label(rng, text, cw, ch, True)
        px = int(rng.integers(2, TILE_W - cw - 2)); py = int(rng.integers(2, TILE_H - ch - 2))
        patch = composite(rng, patch, spr, px, py)
        if rng.random() < 0.35:            # 亮度抖动
            patch = np.clip(patch.astype(np.float32) * rng.uniform(0.75, 1.25), 0, 255).astype(np.uint8)
        write(Image.fromarray(patch, "RGB"), 0, f"pos{i // 20}", i, text, style)

    for i in range(a.neg):
        bg = frames[rng.integers(0, len(frames))]
        H, W = bg.shape[:2]
        x = int(rng.integers(0, W - TILE_W)); y = int(rng.integers(0, H - TILE_H))
        patch = bg[y:y + TILE_H, x:x + TILE_W].copy()
        text, style = "", "plain"
        if rng.random() < 0.55:            # 硬负样本：胶囊形状 + 非跳过文案
            text = NEG_TEXTS[rng.integers(0, len(NEG_TEXTS))]
            cw = int(rng.integers(50, 130)); ch = int(rng.integers(24, 50))
            spr, style = render_label(rng, text, cw, ch, False)
            px = int(rng.integers(2, TILE_W - cw - 2)); py = int(rng.integers(2, TILE_H - ch - 2))
            patch = composite(rng, patch, spr, px, py)
        if rng.random() < 0.35:
            patch = np.clip(patch.astype(np.float32) * rng.uniform(0.75, 1.25), 0, 255).astype(np.uint8)
        write(Image.fromarray(patch, "RGB"), 1, f"neg{i // 20}", i, text, style)

    with open(os.path.join(a.out, "manifest.csv"), "w", newline="", encoding="ascii") as fh:
        wr = csv.DictWriter(fh, fieldnames=["label", "file", "group", "base", "style"])
        wr.writeheader()
        wr.writerows(rows)
    print(f"[done] pos={a.pos} neg={a.neg} -> {a.out}")


if __name__ == "__main__":
    main()
