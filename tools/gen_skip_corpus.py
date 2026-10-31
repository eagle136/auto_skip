# -*- coding: utf-8 -*-
"""Synthetic corpus + domain-gap probe for the 2-class "skip capsule" detector.

Why a corpus at all: the plan (plan/四角OCR跳过方案.md §7-T2) says the model is only
worth training if synthetic data can reach a real on-device capsule. This script makes
that check possible before any training code exists:

  1. locate the real capsule in a reference screenshot by colour (low saturation,
     mid luminance) -- no hand-measured coordinates, so the crop is reproducible;
  2. render N positives ("skip" in Chinese) and negatives (confusable glyph pairs,
     latin words, empty capsules) with heavy augmentation at the model input size;
  3. score the real crop against the synthetic positives with two independent,
     training-free proxies:
       a) nearest-neighbour normalised cross-correlation (does a synthetic twin exist?)
       b) percentile rank of the real crop's ink features inside the synthetic
          distribution (is the real sample even in-distribution?)
  4. emit montages so a human can eyeball the same evidence.

Nothing here trains or writes to the app tree. Output goes to a scratch dir only.

Usage:
  python tools/gen_skip_corpus.py --out R:/skipnet --pos 4000 --neg 4000 \
      --ref /d/some/dir/screenshot.jpg
"""
import argparse
import csv
import io
import math
import os
import shutil
import sys
import zlib

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# Model input. The engine taps the capsule centre, so the crop only has to be
# big enough to hold one corner capsule; 96x32 matches the measured real one
# (~150x65 device px) after a 1.5x downscale, which also acts as anti-aliasing.
IN_W, IN_H = 96, 32
RENDER_SCALE = 2  # draw at 2x, downscale with LANCZOS
_LUM_C = np.array([0.299, 0.587, 0.114], np.float32)   # Rec.601 luma weights

# Traditional and space-separated spellings of the same button: the product decision
# (2026-10-29) is that a 跳過 / 跳 过 control is still the thing to tap, so they are
# positives with the same countdown layouts, not hard negatives.
POS_BASE = ["跳过", "跳過", "跳 过"]
# Negatives are chosen to be *structurally* confusable, not merely different words.
# The 2026-10-28 sweep showed the failure is concentrated in the confusable families
# (距过 0/5, 跳過 0/6, 路过 1/10, 跟过 2/12) while unrelated words (广告, 点击查看)
# were already correct -- so the negative vocabulary, not the parameter count, is
# the lever.  Families: 足-radical look-alikes of 跳, 辶-radical look-alikes of 过,
# traditional/variant forms, and the corner text actually harvested from the 18
# real frames (R:\skipnet_real\corners_*.png).
NEG_BASE = [
    # 足 radical confusion for 跳
    "跟过", "跃过", "路过", "跨过", "跳边", "跟跃", "距过", "跳跟", "蹋过",
    "蹲过", "趴过", "路跃", "距跃", "跟距",
    "跌过", "跋过", "跑过", "蹄过", "趟过", "越过", "超过",
    # 辶 radical confusion for 过
    "运过", "近过", "远过", "适过", "迎过", "透过", "赶过", "趋过", "逢过",
    "迹过", "通过", "达过", "选过", "迷过",
    # traditional / variant / spacing forms of the label itself are NOT here: they
    # are positives now (see POS_BASE).
    # NOTE: nothing here may render to the same string as a positive template --
    # "跳过广告" and bare "跳过" are positives via {t}广告 / {t}, so they must not
    # appear in this list even though they are "different words" in principle.
    # real corner text harvested from device screenshots
    "点击按钮了解更多", "更多精彩", "向上滑动", "上滑或点击查看详情",
    "了解详情", "点击查看", "关闭广告", "广告", "推荐", "首页",
    "爆火推荐", "立即打开", "了解更多", "热门", "新游上线", "领取",
    "中国移动", "心级服务", "百分网",
    "SKIP", "Skip", "skip", "SK", "GO",
]

# Templates observed in the 18 real frames dropped in R:\skipnet_real\frames
# (2026-10-28): "5 | 跳过", "4 | 跳过", "跳过", "跳过 6", "跳过 | 4",
# "跳过广告 5", "跳过 3", "3 跳过". The countdown number appears on both sides of
# the label and sometimes with no separator at all.
# The number is part of the *layout*, not the class signal -- so it is applied to
# negatives too. If only positives carried it, a model could pass by detecting
# "| <digit>" and never look at the glyphs.
COUNT_TEMPLATES = [
    "{t}", "{d} | {t}", "{d}|{t}", "{t} {d}", "{t}({d})", "{d}s {t}", "{t} | {d}",
    "{d} {t}", "{d}{t}", "{t}广告 {d}", "{t}广告",
]

NEG_EMPTY = 3  # capsule with no glyph at all -> the pure "shape" false positive

# Negatives that differ from the label by one glyph: a model that has learned to
# read only 跳 passes every one of them.  They are weighted up in the sampler (see
# main) because at an even spread over NEG_BASE each of these got ~116 samples
# against 16,800 of the real label, and the trained model did exactly that:
# 跳跟 0/16 correct, 跳边 1/24 -- the whole 5.6% FPR.  (跳過 and 跳 过 were in this
# list until the 2026-10-29 product decision made them positives.)
HARD_NEG = ("跳跟", "跳边")
HARD_NEG_SHARE = 0.5   # share of the negative draws these four should get together

# ---- legibility / visibility floors -----------------------------------------
# "random" here means random *inside the range a human can still read*.  The audit
# on the 2026-10-28 corpus found the old drawing code ignored all of it: glyphs
# were taller than the pill in 83.8% of samples and clipped by the crop border in
# 72.6%, 20.3% had a text-vs-body luminance gap under 40, and a radius-2 blur on
# its own pushed 21% of samples to no measurable stroke contrast at all.
# Measured on the 30 capsule crops in R:\skipnet_real\crops (18 frames, 4 corners):
# a real pill is 77-116 x 42-51 device px at aspect ~1.8-2.0, and the crop is
# resized to 32 px tall -- so the glyphs the model has to read are 13-26 px there.
GLYPH_H_1X = (13.0, 26.0)   # glyph height the squeeze should land at, model px
DRAW_PX_2X = (28.0, 56.0)   # drawn ink height at 2x, i.e. the random size draw
MAX_CANVAS_W = 1400         # canvas width cap (2x px) for very long labels
MIN_INK_H_1X = 13    # floor: a sample below this is flagged tight=1 and counted
# |lum(ink) - lum(body)| enforced at colour level, 0..255.  105, not the 45 this
# started at: JPEG q35 + sigma-9 noise eat 30-60 of it on the way to the PNG, so
# the measured gap -- not the nominal one -- is what the floors are about.
# Measured min gap on a 2000-image run: 45 -> 10, 60 -> 22, 90 -> 49, 105 -> 54.
# At 90 a full 24k corpus still leaked 3 rows into the 15-23 band (all translucent
# pills with 13-15px thin strokes), so the gate is kept at zero tolerance and the
# target moved up.  Cost is 6 points of colour diversity: 24.7% -> 30.9% of ink
# draws fall back to white or black because their hue cannot reach the target.
MIN_GAP = 105
MIN_GAP_SEEN = 25    # ...and what must still be there in the pixels, after
                     # antialiasing, JPEG, noise and the LANCZOS downscale
MIN_PILL_GAP = 18    # |lum(pill) - lum(artwork)| so a filled pill is not ghosted
MIN_ALPHA = 120      # pill body alpha; below this the capsule is invisible
INK_FILL_H = (0.50, 0.80)   # glyph height as a fraction of the pill height
INK_FILL_W = (0.55, 0.92)   # glyph width as a fraction of the pill width
WARP_PROB = 0.75     # share of glyphs that get an individual micro-deform
WARP_SHEAR = 0.07    # up to 4 degrees of slant, plus +-6% scale, per glyph


def variants(base, rng):
    tmpl = COUNT_TEMPLATES[int(rng.integers(0, len(COUNT_TEMPLATES)))]
    digit = int(rng.integers(1, 10))
    return tmpl.format(t=base, d=digit)

FONT_DIR = os.environ.get("SYSTEMROOT", r"C:\Windows") + os.sep + "Fonts"
# 跳 vs 路: they differ only in the left radical, so a face that substitutes a
# generic .notdef box draws *exactly* the same bitmap for both.  Comparing the
# two is a real "does this file contain Han glyphs" test; loading alone is not.
PROBE_CHARS = ("\u8df3", "\u8def")
MIN_CJK_INK = 30


def ascii_stdout():
    """This box runs Python under a GBK console; keep the transcript pure ASCII."""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="ascii", errors="replace")
        except Exception:
            pass


def _glyph_bits(font, ch, side=90):
    im = Image.new("L", (side, side), 0)
    ImageDraw.Draw(im).text((5, 5), ch, fill=255, font=font)
    return np.asarray(im)


def usable_faces(font_dir=FONT_DIR, verbose=True):
    """Every (path, index, tag) in the system fonts dir that really draws Han chars.

    Faces are deduplicated by what they render, not by file name: .ttc subfaces
    share a file, and index>0 on a plain .ttf hands back the same face again.
    """
    seen, faces, rejected = {}, [], 0
    for name in sorted(os.listdir(font_dir)):
        if not name.lower().endswith((".ttf", ".ttc", ".otf")):
            continue
        path = os.path.join(font_dir, name)
        for index in range(8):
            try:
                f = ImageFont.truetype(path, 30 * RENDER_SCALE, index=index)
            except Exception:
                break  # PIL raises here for an out-of-range .ttc subface
            try:
                a = _glyph_bits(f, PROBE_CHARS[0])
                b = _glyph_bits(f, PROBE_CHARS[1])
            except Exception:
                # loads fine but cannot draw at all ("invalid reference" on this
                # box) -- the face is useless, keep scanning the rest
                rejected += 1
                continue
            key = (a.tobytes(), b.tobytes())
            if int((a > 0).sum()) < MIN_CJK_INK or key[0] == key[1]:
                rejected += 1
            elif key in seen:
                pass  # duplicate subface -- the first one already represents it
            else:
                seen[key] = len(faces)
                faces.append((path, index, f"{name[:-4]}#{index}"))
    if verbose:
        print(f"[fonts] {len(faces)} usable CJK faces, {rejected} rejected "
              f"(no Han ink / .notdef substitution) under {font_dir}")
    return faces


def load_fonts(faces=None):
    """[(path, index, tag)] -- one entry per usable face.

    Deliberately *not* ImageFont objects: the drawn size comes from GLYPH_H_1X per
    sample (see `_fit_size`), so a face is picked and then sized; FreeType faces
    are cached by (path, index, px) instead of being rebuilt for every image.
    """
    faces = usable_faces() if faces is None else faces
    return [(path, index, tag) for path, index, tag in faces]


_FONT_CACHE = {}


def _font_for(path, index, px):
    px = max(6, int(round(px)))
    key = (path, index, px)
    f = _FONT_CACHE.get(key)
    if f is None:
        f = ImageFont.truetype(path, px, index=index)
        _FONT_CACHE[key] = f
    return f


def rand_bg(rng, w, h):
    """Ad artwork stand-in: gradient + blobs, plus a periodic texture half the time.

    The texture is not decoration.  On the hand-labelled real corners the strongest
    false positives were knit / fabric / woven surfaces with no glyphs in them at
    all: at 96x32 a 6-20px weave reads as a mass of strokes, and the corpus never
    contained one, so the net had no negative for "high frequency, no characters".
    """
    base = rng.integers(0, 255, size=3)
    xs = np.linspace(0, 1, w)[None, :]
    grad = (base[None, None, :] * (0.6 + 0.8 * xs)[:, :, None]).astype(np.float32)
    img = np.broadcast_to(grad, (h, w, 3)).copy()
    for _ in range(rng.integers(2, 6)):
        cx, cy = rng.integers(0, w), rng.integers(0, h)
        r = int(rng.integers(w // 8, w // 2))
        col = rng.integers(0, 255, size=3).astype(np.float32)
        yy, xx = np.mgrid[0:h, 0:w]
        mask = ((xx - cx) ** 2 + (yy - cy) ** 2) < r * r
        img[mask] = 0.5 * img[mask] + 0.5 * col
    if rng.random() < 0.55:
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        ang = float(rng.uniform(0, np.pi))
        u = xx * math.cos(ang) + yy * math.sin(ang)
        v = -xx * math.sin(ang) + yy * math.cos(ang)
        per = float(rng.uniform(3.0, 11.0))                    # 6-22px at 2x
        kind = int(rng.integers(0, 3))
        if kind == 0:                                          # stripes / ribbing
            wave = np.sin(2 * np.pi * u / per)
        elif kind == 1:                                        # weave
            wave = np.sin(2 * np.pi * u / per) * np.sin(2 * np.pi * v / per)
        else:                                                  # dots / knit loops
            wave = np.cos(2 * np.pi * np.hypot(u % per - per / 2,
                                               v % per - per / 2) / per)
        amp = float(rng.uniform(10.0, 34.0))
        img += (amp * wave)[:, :, None]
    img += rng.normal(0, 8, img.shape)
    return np.clip(img, 0, 255).astype(np.uint8)


# The measured real capsule (from the reference screenshot): grey pill, white text.
# Kept as an explicit anchor so random HSV sampling cannot drift away from the one
# configuration we have actually observed on the device.
MEASURED_STYLE = ((120, 122, 128), (255, 255, 255))


def _lum(rgb):
    return float(np.dot(_LUM_C, np.asarray(rgb, np.float32)))


def enforce_gap(col, ref, gap):
    """Nearest value-shift of `col` (hue and saturation kept) that clears `gap`
    luminance from `ref`.

    This is what turns "random colour" into "random *visible* colour": the hue
    draw stays uniform over the wheel, but a pairing that lands on equal
    luminance -- red on green, the case that also defeats a greyscale input --
    gets pushed apart instead of emitted.  Luminance is linear in HSV's V at
    fixed hue/saturation, so the two feasible targets are solved, not searched.
    """
    import colorsys
    if abs(_lum(col) - _lum(ref)) >= gap:
        return col
    h, s, v0 = colorsys.rgb_to_hsv(*(c / 255.0 for c in col))
    r1, g1, b1 = (c * 255.0 for c in colorsys.hsv_to_rgb(h, s, 1.0))
    slope = max(_lum((r1, g1, b1)) / 255.0, 1e-6)   # luminance reachable at V=1
    lref = _lum(ref)
    cands = []
    for want in (lref + gap, lref - gap):           # brighter / darker than body
        v = want / 255.0 / slope
        if 0.0 <= v <= 1.0:
            cands.append((abs(v - v0), v))
    if not cands:                                   # neither direction reaches
        cands = [(abs(1.0 - v0), 1.0), (abs(0.0 - v0), 0.0)]
    v = min(cands)[1]
    r, g, b = (int(round(c * 255)) for c in colorsys.hsv_to_rgb(h, s, v))
    out = (r, g, b)
    if abs(_lum(out) - lref) < gap:     # hue is too dark to ever clear this body
        out = (255, 255, 255) if lref + gap <= 255.0 else (0, 0, 0)
    return out


def _set_lum(col, want, ref):
    """`col` at luminance `want` (0..255), hue and saturation kept.

    Luminance is linear in HSV's V at fixed hue/saturation, so this is solved, not
    searched.  A hue that cannot reach that luminance at any V (deep blue cannot
    get bright) goes achromatic -- and to the extreme on the side of `ref` that
    `want` was aiming at, because "the hue cannot reach 205 above a 250 body" needs
    black, not the white it would saturate back into.
    """
    import colorsys
    h, s, v0 = colorsys.rgb_to_hsv(*(c / 255.0 for c in col))
    r1, g1, b1 = (c * 255.0 for c in colorsys.hsv_to_rgb(h, s, 1.0))
    slope = max(_lum((r1, g1, b1)) / 255.0, 1e-6)
    v = want / 255.0 / slope
    if 0.0 <= v <= 1.0:
        return tuple(int(round(c * 255)) for c in colorsys.hsv_to_rgb(h, s, v))
    return (255, 255, 255) if want > ref else (0, 0, 0)


def sample_pair(rng):
    """Random (capsule_fill, text_ink, alpha) -- colour is NOT a class feature.

    The old version drew from 6 hard-coded CAPSULE_STYLES. That is a memorised
    colour prior, and the user says the creative re-colours between launches, so
    a model trained on it could key on hue instead of glyph shape. Here the body
    colour is uniform over HSV, the ink hue is independent of it, and alpha spans
    translucent-to-solid. 15% of draws are pinned to MEASURED_STYLE so the
    configuration actually observed on the device stays well represented.

    Readability is *not* decided here: the pill is composited over random artwork
    at partial alpha, so what the eye sees is the blended colour.  draw_capsule()
    measures that and calls enforce_gap() on it.
    """
    import colorsys
    if rng.random() < 0.15:
        fill, ink = MEASURED_STYLE
        return fill, ink, int(rng.choice([255, 255, 255, 235, MIN_ALPHA]))
    h = float(rng.random())
    s = float(rng.uniform(0.0, 0.85))
    v = float(rng.uniform(0.25, 1.0))
    fill = tuple(int(round(c * 255)) for c in colorsys.hsv_to_rgb(h, s, v))
    dh = float(rng.random())                              # ink hue is independent
    ds = float(rng.uniform(0.0, 0.9))
    dv = float(rng.uniform(0.15, 1.0))
    ink = tuple(int(round(c * 255)) for c in colorsys.hsv_to_rgb(dh, ds, dv))
    alpha = int(rng.choice([255, 255, 255, 240, 220, 200, MIN_ALPHA + 40, MIN_ALPHA]))
    return fill, ink, alpha


def _micro_affine(rng, w, h):
    """AFFINE coefficients (output -> input) for one glyph's micro-deformation.

    +-4 deg tilt, +-6% anisotropic scale, up to WARP_SHEAR slant and a +-2px
    nudge.  Small enough that the stroke order still reads as the same character,
    large enough that 足/口/扌 do not collapse onto one pixel pattern.
    """
    ang = math.radians(float(rng.uniform(-4, 4)))
    sx = float(rng.uniform(0.94, 1.06))
    sy = float(rng.uniform(0.94, 1.06))
    shear = float(rng.uniform(-WARP_SHEAR, WARP_SHEAR))
    ca, sa = math.cos(ang), math.sin(ang)
    A = np.array([[1.0, shear], [0.0, 1.0]]) @ \
        np.array([[ca, -sa], [sa, ca]]) @ np.array([[sx, 0.0], [0.0, sy]])
    cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
    t = np.array([cx, cy]) - A @ np.array([cx, cy]) + \
        np.array([rng.uniform(-1, 1), rng.uniform(-1, 1)]) * RENDER_SCALE
    return (float(A[0, 0]), float(A[0, 1]), float(t[0]),
            float(A[1, 0]), float(A[1, 1]), float(t[1]))


def _glyph_strip(rng, font, text, ink, stroke, sfill):
    """Draw `text` one glyph at a time, each tile individually warped.

    Returns (RGBA strip, core-alpha array, coverage-alpha array).  Warping the
    assembled line would only tilt the word; warping per glyph perturbs stroke
    geometry, which is what the device fonts actually differ in and what the model
    keeps confusing.
    """
    adv = [font.getlength(ch) for ch in text]
    bb = font.getbbox(text)
    ink_h = max(1, bb[3] - bb[1])
    pad = stroke + 2 * RENDER_SCALE
    tile_h = ink_h + 2 * pad
    total_w = int(math.ceil(sum(adv))) + 2 * pad + 2
    core_a = np.zeros((tile_h, total_w), np.uint8)
    full_a = np.zeros((tile_h, total_w), np.uint8)
    cum = 0.0
    for ch, a in zip(text, adv):
        tw = int(math.ceil(a)) + 2 * pad
        tc = Image.new("L", (tw, tile_h), 0)
        ImageDraw.Draw(tc).text((tw / 2.0, tile_h / 2.0), ch, font=font,
                                fill=255, anchor="mm")
        tf = tc
        if stroke:
            tf = Image.new("L", (tw, tile_h), 0)
            ImageDraw.Draw(tf).text((tw / 2.0, tile_h / 2.0), ch, font=font,
                                     fill=255, anchor="mm", stroke_width=stroke)
        if rng.random() < WARP_PROB:
            coeffs = _micro_affine(rng, tw, tile_h)
            tc = tc.transform((tw, tile_h), Image.AFFINE, coeffs, resample=Image.BILINEAR)
            if tf is not tc:
                tf = tf.transform((tw, tile_h), Image.AFFINE, coeffs, resample=Image.BILINEAR)
        x0 = int(round(pad + cum + a / 2.0 - tw / 2.0))
        x0 = max(0, min(total_w - tw, x0))
        sl = (slice(None), slice(x0, x0 + tw))
        full_a[sl] = np.maximum(full_a[sl], np.asarray(tf))
        core_a[sl] = np.maximum(core_a[sl], np.asarray(tc))
        cum += a
    band = np.clip(full_a.astype(np.int16) - core_a.astype(np.int16), 0, 255) \
        .astype(np.uint8)
    alpha = np.maximum(core_a, band)
    rgb = np.where(core_a[..., None] > band[..., None],
                   np.asarray(ink, np.uint8), np.asarray(sfill, np.uint8))
    out = np.dstack([rgb, alpha[..., None]]).astype(np.uint8)
    return Image.fromarray(out, "RGBA"), core_a, alpha


def _fit_size(rng, path, index, text):
    """Pick the drawn size and measure the label.

    Returns (font, ink_h, ink_w, fill_h, fill_w).  The drawn size does not have to
    fit a fixed canvas any more: the canvas is built around the label and then
    resized to 96x32, which is exactly what the app does with a tight capsule bbox
    (a wide label arrives horizontally compressed, not tiny).  So the random size
    draw controls stroke weight, and (fill_h, margin) together decide how much of
    the 32px input the glyphs end up occupying -- that is where GLYPH_H_1X comes
    from, and it is why no label has to be dropped for being long.
    """
    px = float(rng.uniform(*DRAW_PX_2X))
    font = _font_for(path, index, px)
    bb = font.getbbox(text)
    ink_h = max(1, bb[3] - bb[1])
    ink_w = max(1.0, sum(font.getlength(ch) for ch in text))
    fh = float(rng.uniform(*INK_FILL_H))
    fw = float(rng.uniform(*INK_FILL_W))
    return font, ink_h, ink_w, fh, fw


def draw_capsule(rng, fonts, texts, empty_ok):
    """Render one label onto a rounded capsule over random artwork.

    The canvas is sized to the capsule (label + margin) at 2x and then resized to
    96x32, so what reaches the model is the same anisotropic squeeze the app
    applies to a tight bbox.  Long labels therefore arrive compressed, the way a
    real wide one does, instead of being drawn unreadably small to fit a fixed
    window -- which is what made 7% of the 2026-10-28 corpus illegible.

    Returns (image, meta).  `meta.style` is one of
      filled   -- solid pill behind the glyphs
      outline  -- pill drawn as a stroke only, body transparent (real frame #04)
      bare     -- no pill at all, glyphs + stroke (real ad creatives do this)

    `meta.gap` is measured on the composited pixels, not nominal: the pill is drawn
    at partial alpha over random artwork, so the number that matters is the
    luminance difference that survives compositing -- that is what enforce_gap()
    aims at and what the audit re-checks downstream of JPEG and noise.
    """
    empty = empty_ok and rng.random() < NEG_EMPTY / 10.0
    text = base = tag = ""
    font = None
    if empty:
        ink_h = float(rng.uniform(22.0, 34.0))
        ink_w = ink_h * float(rng.uniform(1.4, 4.0))
        # Half of the empties are full-bleed UI panels rather than capsules.  A real
        # corner region is mostly white blocks edged by a coloured band or a 1-3px
        # border, and a window that caught one scored 0.985 skip on a model trained
        # on rounded pills alone -- that alone is most of the 34/62 corner regions
        # that fired on the hand-labelled real set.
        panel = rng.random() < 0.5
        fh = fw = float(rng.uniform(0.88, 1.0)) if panel else 0.65
    else:
        panel = False
        base = texts[rng.integers(0, len(texts))]
        text = variants(base, rng)
        path, index, tag = fonts[rng.integers(0, len(fonts))]
        font, ink_h, ink_w, fh, fw = _fit_size(rng, path, index, text)

    fill, ink, alpha = sample_pair(rng)
    if panel and rng.random() < 0.6:
        fill = (int(rng.integers(200, 256)),) * 3     # real panels are white
    # Empties need to cover "texture and nothing else" too, not just "pill with no
    # glyph": that is what a 96x32 window on a knit or fabric surface actually is.
    bare_cut, out_cut = (0.40, 0.50) if empty else (0.12, 0.24)
    r = rng.random()
    style = "bare" if r < bare_cut else ("outline" if r < out_cut else "filled")
    # Decided before the canvas is sized, because it decides how tall the glyph
    # tile is: ink + 2*(stroke + 4).  An artwork margin thinner than that cuts the
    # tile off at the top and bottom, which clips real ink and crashed the contrast
    # measurement's mask assignment.
    stroke = (int(rng.integers(0, 2)) + (1 if style == "bare" else 0)) * RENDER_SCALE
    tile_pad = stroke + 2 * RENDER_SCALE

    pill_h = ink_h / fh
    pill_w = max(ink_w + 4 * RENDER_SCALE, ink_w / fw)
    mx = int(rng.integers(3, 9)) * RENDER_SCALE       # artwork margin, like a corner
    my = int(rng.integers(2, 7)) * RENDER_SCALE       # of a screenshot would show
    if not empty:
        my = max(my, tile_pad)
    W = int(min(MAX_CANVAS_W, pill_w + 2 * mx))
    H = int(pill_h + 2 * my)
    pill_w = min(pill_w, W - 2 * mx)
    bx0 = int((W - pill_w) // 2)
    by0 = int((H - pill_h) // 2)
    box = (bx0, by0, int(bx0 + pill_w), int(by0 + pill_h))
    pill_h = box[3] - box[1]
    pill_w = box[2] - box[0]
    radius = int(pill_h * (rng.uniform(0.0, 0.12) if panel
                           else rng.uniform(0.35, 0.5)))
    # glyph height once the canvas is squeezed into the 32px input
    mh1x = 32.0 * ink_h / H

    bg = rand_bg(rng, W, H).astype(np.float32)
    bg *= rng.uniform(0.80, 1.20)                        # artwork brightness
    bg = (bg - bg.mean()) * rng.uniform(0.85, 1.25) + bg.mean()
    bg += rng.normal(0, rng.uniform(2, 7), bg.shape)     # artwork / JPEG noise
    bg = np.clip(bg, 0, 255)
    img = Image.fromarray(bg.astype(np.uint8), "RGB").convert("RGBA")

    stroke_w = int(rng.integers(1, 4)) * RENDER_SCALE

    def _body_layer():
        """Background artwork with the pill composited on it, before any glyph."""
        lyr = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        dr = ImageDraw.Draw(lyr)
        if style == "filled":
            dr.rounded_rectangle(box, radius=radius, fill=(*fill, alpha))
        elif style == "outline":
            dr.rounded_rectangle(box, radius=radius, outline=(*fill, alpha),
                                 width=stroke_w)
        return Image.alpha_composite(img, lyr)

    art = tuple(float(c) for c in np.median(bg[by0:by0 + pill_h, bx0:bx0 + pill_w]
                                           .reshape(-1, 3), axis=0))
    if style == "filled":
        fill = enforce_gap(fill, art, MIN_PILL_GAP)   # the pill must not ghost out
    layer = _body_layer()

    # The luminance range the glyphs have to beat is the range of the pixels
    # *under them*, not the average colour of the pill: a translucent pill over a
    # gradient, or bare text over ad artwork, spans far more than its median.
    # Measured on the pill's inner band -- the corners of the bounding box are
    # outside a rounded rect, and counting their artwork would exaggerate the span.
    iy0 = by0 + max(1, pill_h // 4)
    iy1 = by0 + max(iy0 + 1, (3 * pill_h) // 4)
    ix0 = bx0 + min(radius, pill_w // 3)
    ix1 = bx0 + max(ix0 + 1, pill_w - min(radius, pill_w // 3))

    def _body_span(lay):
        lum = np.asarray(lay.convert("RGB"), np.float32) @ _LUM_C
        reg = lum[iy0:iy1, ix0:ix1]
        return (float(np.percentile(reg, 3)), float(np.percentile(reg, 97)),
                float(np.median(reg)))

    core_a = cov_a = None
    px0 = py0 = 0
    lo, hi, med = _body_span(layer)
    if not empty:
        # Which side can the ink actually clear, once luminance stops at 255?
        # The band is often blown out -- artwork x1.2 brightness on top of a light
        # pill puts p50 at 250+ -- and then "brighter than the band by MIN_GAP"
        # saturates at 254 and buys a few levels, which is how white-on-white
        # samples got emitted while the manifest recorded a gap.
        # Sides are scored by the clearance they still deliver against the band
        # EXTREME on their own side, not against the median: white text under a band
        # whose p97 is 237 wins a median contest (133) while clearing the brightest
        # pixels it sits next to by 17, and the audit measured exactly that as an
        # invisible sample.  A side that cannot clear its extreme at all is only used
        # when neither can -- a band spanning nearly the whole 0-255 range, where the
        # median is the only meaningful reference left.
        br, dk = min(254.0, hi + MIN_GAP), max(1.0, lo - MIN_GAP)
        sides = [(br - med, br - hi, br), (med - dk, lo - dk, dk)]
        ok = [s for s in sides if s[1] >= MIN_GAP_SEEN]
        want = max(ok or sides, key=(lambda s: (s[1], s[0])) if ok
                   else (lambda s: s[0]))[2]
        ink = _set_lum(ink, want, med)                    # random hue, guaranteed gap
        sfill = (0, 0, 0) if want >= med else (255, 255, 255)   # halo: opposite side
        strip, core_a, cov_a = _glyph_strip(rng, font, text, ink, stroke, sfill)
        px0 = max(0, (W - strip.width) // 2)
        py0 = max(0, (H - strip.height) // 2)
        layer.paste(strip, (px0, py0), strip)

    img = layer.convert("RGB")

    # A real detector -- and the app's own bbox -- can hand over a crop cut flush to
    # the glyphs.  #42 of the hand-labelled set is exactly that: white 跳过 on dark
    # artwork, no pill, every edge touching a stroke.  The net scored it 0.00 because
    # every synthetic sample carried a 6-18px artwork margin, so a quarter of the
    # glyph samples are cut to their ink box here (0-3px slack) and the squeeze is
    # then measured on that crop, which is what the audit records as inkh.
    if core_a is not None and rng.random() < 0.25:
        ys, xs = np.nonzero(cov_a > 8)
        q = int(rng.integers(0, 4))
        x0 = max(0, px0 + int(xs.min()) - q)
        y0 = max(0, py0 + int(ys.min()) - q)
        x1 = min(W, px0 + int(xs.max()) + 1 + q)
        y1 = min(H, py0 + int(ys.max()) + 1 + q)
        img, W, H = img.crop((x0, y0, x1, y1)), x1 - x0, y1 - y0
        px0, py0 = px0 - x0, py0 - y0
        iy0, iy1, ix0, ix1 = 0, H, 0, W      # on a tight crop the band is the crop
        mh1x = 32.0 * ink_h / H

    # Post-processing that real ad rendering actually applies -- gated on how
    # much legibility headroom the sample has left, measured in the size it will
    # occupy in the model input (mh1x), not in the drawing canvas.
    # No Gaussian blur and no whole-image rotation, deliberately: both are a second
    # low-pass resample stacked on top of the LANCZOS squeeze, which already does
    # the same job.  Measured on the same seed, they were the entire gap failure --
    # 53 rows under the floor, 32 without blur, 41 without rotate, 53 with JPEG or
    # posterise switched off.  And neither is a deployment case: the screen is
    # upright and the app crops an axis-aligned bbox.  The deformation the model
    # must tolerate comes from the per-glyph affine warp in _glyph_strip instead.
    small = mh1x < 14.0
    # q35 is fine when a pill carries the shape, but a bare caption has nothing else
    # to stand on: measured, an ink/body pairing of 131 luminance levels arrived in
    # the pixels at 14 because 8x8 block averaging had mixed the thin stroke into the
    # artwork around it.  Small glyphs get the same protection.
    q_lo = 55 if (small or style == "bare") else 35
    if rng.random() < (0.35 if small else 0.50):        # ad creative JPEG blocks
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=int(rng.integers(q_lo, 90)))
        buf.seek(0)
        img = Image.open(buf).convert("RGB")
    # Posterise models flat, banded ad artwork, so it only means something when the
    # background actually has bands: on a near-flat body (the two audit failures both
    # had a band spanning 13 luminance levels) a 12-40 colour median-cut has nothing
    # to spend its entries on but the glyph, and snaps the ink onto the body --
    # measured, a nominal 107-level pairing arrived in the pixels at 8.7.
    if rng.random() < (0.08 if small else 0.25) and (hi - lo) >= 60.0:
        img = img.quantize(colors=int(rng.integers(12, 40))).convert("RGB")  # posterise
    arr = np.asarray(img).astype(np.float32)
    if rng.random() < 0.45:                              # sensor / upscale noise
        arr += rng.normal(0, rng.uniform(2, 9), arr.shape)
    if rng.random() < 0.25:                              # salt and pepper
        sp = rng.random(arr.shape[:2])
        arr[sp < 0.002] = 255.0
        arr[sp > 0.998] = 0.0
    arr = np.clip(arr, 0, 255)

    # Contrast as it actually survives to the model input, measured on the 2x
    # pixels.  Ink side: strong-coverage core pixels only, because at 8-22px glyph
    # height a >127 mask is mostly anti-aliased rim whose median sits back on the
    # body.  Body side: pixels the glyph (core *or* halo) never touched --
    # otherwise the "body" sample is rim again, which is exactly the measurement
    # artefact that made 21% of samples look invisible while they were not.
    # The body region expands outward until it has pixels to average over: on a
    # tight capsule the label *is* the pill's interior, so the inner band can be
    # 100% painted and the comparison has nothing left to stand on.  A human falls
    # back on the pill's own margin, then on the artwork around it, so the same
    # order is used here; without it 3% of perfectly legible rows recorded gap 0.0.
    if core_a is None:
        gap = 0.0
    else:
        lum = arr @ _LUM_C
        strong = core_a > 200
        if strong.sum() < 5:
            strong = core_a > 127
        cover = np.zeros((H, W), bool)
        free = np.zeros((H, W), bool)
        # The strip is centred, and a tight crop starts at the ink box, so the paste
        # origin can sit left of / above the crop (and a label long enough to hit
        # MAX_CANVAS_W can run off the right).  Mirror what paste actually composited:
        # clip the strip on whichever side is outside.
        sx0, sy0 = max(0, -px0), max(0, -py0)
        px0, py0 = max(0, px0), max(0, py0)
        ch_, cw_ = strong.shape
        rh, rw = min(ch_ - sy0, H - py0), min(cw_ - sx0, W - px0)
        if rh > 0 and rw > 0:
            cover[py0:py0 + rh, px0:px0 + rw] = \
                strong[sy0:sy0 + rh, sx0:sx0 + rw]
            free[py0:py0 + rh, px0:px0 + rw] = \
                (cov_a > 8)[sy0:sy0 + rh, sx0:sx0 + rw]
        clean = ~(cover | free)
        body_pix = lum[clean]
        for r0, r1, c0, c1 in ((iy0, iy1, ix0, ix1), (by0, box[3], bx0, box[2])):
            sub = clean[r0:r1, c0:c1]
            if int(sub.sum()) >= 16:
                body_pix = lum[r0:r1, c0:c1][sub]
                break
        gap = float(abs(np.median(lum[cover]) - np.median(body_pix))) \
            if cover.any() and body_pix.size else 0.0

    img = Image.fromarray(arr.astype(np.uint8), "RGB")

    return img.resize((IN_W, IN_H), Image.LANCZOS), {
        "gap": gap, "alpha": alpha, "style": style, "text": text, "base": base,
        "font": tag, "empty": bool(empty),
        "inkh": int(round(mh1x)), "tight": 1 if mh1x < MIN_INK_H_1X else 0,
    }



def grey(img):
    a = np.asarray(img.convert("L"), dtype=np.float32)
    return a


def ink_features(a):
    """Training-free shape descriptors: Otsu ink ratio + connected component count."""
    hist, _ = np.histogram(a, bins=256, range=(0, 256))
    total = a.size
    best_t, best_var = 0, -1.0
    w0 = 0
    sum0 = 0.0
    sum_all = float(np.dot(np.arange(256), hist))
    for t in range(1, 255):
        w0 += hist[t]
        if w0 == 0:
            continue
        w1 = total - w0
        if w1 == 0:
            break
        sum0 += t * hist[t]
        m0 = sum0 / w0
        m1 = (sum_all - sum0) / w1
        var = w0 * w1 * (m0 - m1) ** 2
        if var > best_var:
            best_var, best_t = var, t
    light = a > best_t
    ink = light if light.mean() <= 0.5 else ~light  # ink = the minority class
    comps = count_components(ink)
    return dict(ink_ratio=float(ink.mean()), n_comp=comps,
                mean=float(a.mean()), std=float(a.std()))


def count_components(mask):
    """4-connected component count, iterative (images are 96x32, so this is cheap)."""
    m = mask.copy()
    n, h, w = 0, m.shape[0], m.shape[1]
    stack = []
    for y in range(h):
        for x in range(w):
            if not m[y, x]:
                continue
            n += 1
            m[y, x] = False
            stack.append((y, x))
            while stack:
                cy, cx = stack.pop()
                for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    ny, nx = cy + dy, cx + dx
                    if 0 <= ny < h and 0 <= nx < w and m[ny, nx]:
                        m[ny, nx] = False
                        stack.append((ny, nx))
    return n


def erode(mask, it=2):
    m = mask.copy()
    h, w = m.shape
    for _ in range(it):
        out = m.copy()
        out[1:, :] &= m[:-1, :]
        out[:-1, :] &= m[1:, :]
        out[:, 1:] &= m[:, :-1]
        out[:, :-1] &= m[:, 1:]
        m = out
    return m


def largest_blobs(mask, want=5):
    """Top-`want` 8-connected blobs as (size, (l,t,r,b)). Linear seed index, no rescan."""
    m = mask.copy()
    h, w = m.shape
    seeds = np.flatnonzero(m.reshape(-1))
    found = []
    for s in seeds:
        sy, sx = divmod(int(s), w)
        if not m[sy, sx]:
            continue
        m[sy, sx] = False
        stack = [(sy, sx)]
        pts = [(sy, sx)]
        while stack:
            cy, cx = stack.pop()
            for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, -1), (1, -1), (-1, 1)):
                ny, nx = cy + dy, cx + dx
                if 0 <= ny < h and 0 <= nx < w and m[ny, nx]:
                    m[ny, nx] = False
                    stack.append((ny, nx))
                    pts.append((ny, nx))
        ys = [p[0] for p in pts]
        xs = [p[1] for p in pts]
        found.append((len(pts), (min(xs), min(ys), max(xs), max(ys))))
    found.sort(reverse=True)
    return found[:want]


def find_real_capsule(ref_path, out_dir):
    """Locate the grey capsule by colour-distance-from-background, then crop it.

    Deliberately dumb and auditable: inside the top-right search window, the
    background is the median colour, the capsule is the largest compact blob that
    deviates from it. Binary closing (dilate then erode) is required -- the white
    glyph strokes sit *inside* the pill and would otherwise split it into confetti,
    which is exactly how the first version of this function lost the capsule.
    """
    im = Image.open(ref_path).convert("RGB")
    W, H = im.size
    a = np.asarray(im, dtype=np.float32)
    x0, x1 = int(W * 0.55), W
    y0, y1 = int(H * 0.04), int(H * 0.20)
    sub = a[y0:y1, x0:x1, :]
    if sub.size == 0:
        print("[real] search window empty")
        return None
    bg = np.median(sub.reshape(-1, 3), axis=0)
    dist = np.sqrt(((sub - bg) ** 2).sum(axis=2))
    raw = dist > 40.0
    mask = erode(dilate(raw, 3), 2)
    if mask.mean() < 0.001:
        print(f"[real] no deviating pixels: bg={bg.round(1)} "
              f"raw_ratio={raw.mean():.4f} window={sub.shape[1]}x{sub.shape[0]}")
        return None
    blobs = largest_blobs(mask, want=5)
    lum = (sub[..., 0] * 299 + sub[..., 1] * 587 + sub[..., 2] * 114) / 1000.0
    mx, mn = sub.max(axis=2), sub.min(axis=2)
    print(f"[real] window {sub.shape[1]}x{sub.shape[0]} bg={bg.round(1).tolist()} "
          f"mask_ratio={mask.mean():.4f}")
    pick = None
    for size, (l, t, r, b) in blobs:
        bw, bh = r - l + 1, b - t + 1
        aspect = bw / max(bh, 1)
        box_lum = float(lum[t:b + 1, l:r + 1].mean())
        box_sat = float((mx - mn)[t:b + 1, l:r + 1].mean())
        fill = size / float(bw * bh)
        print(f"  blob px={size:6d} box={bw}x{bh} aspect={aspect:4.2f} "
              f"lum={box_lum:5.1f} sat={box_sat:5.1f} fill={fill:.2f}")
        if (pick is None and 1.3 <= aspect <= 6.5 and fill > 0.35
                and bw <= 0.55 * (x1 - x0) and bh <= 0.35 * (y1 - y0)
                and box_sat < 40):
            pick = (l, t, r, b)
    if pick is None:
        print("[real] no blob passed the capsule gate -- domain-gap check will be skipped")
        return None
    l, t, r, b = pick
    bw, bh = r - l + 1, b - t + 1
    gx0, gy0 = x0 + l, y0 + t
    crop = im.crop((gx0, gy0, gx0 + bw, gy0 + bh))
    real = {"raw": crop.resize((IN_W, IN_H), Image.LANCZOS)}
    real["flip"] = real["raw"].transpose(Image.FLIP_LEFT_RIGHT)
    meta = dict(device_box=(gx0, gy0, gx0 + bw, gy0 + bh), aspect=round(bw / max(bh, 1), 2))
    return real, meta


def montage(imgs, path, cols=20):
    rows = (len(imgs) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * IN_W, rows * IN_H), (30, 30, 30))
    for i, im in enumerate(imgs):
        if isinstance(im, np.ndarray):  # grey float/uint8 -> RGB tile
            tile = Image.fromarray(np.clip(im, 0, 255).astype(np.uint8), "L").convert("RGB")
        else:
            tile = im
        sheet.paste(tile, ((i % cols) * IN_W, (i // cols) * IN_H))
    sheet.save(path)


def dilate(mask, it=2):
    """Binary dilation by Chebyshev radius `it`, via shifted ORs (no scipy here)."""
    m = mask.copy()
    h, w = m.shape
    for _ in range(it):
        out = m.copy()
        out[1:, :] |= m[:-1, :]
        out[:-1, :] |= m[1:, :]
        out[:, 1:] |= m[:, :-1]
        out[:, :-1] |= m[:, 1:]
        m = out
    return m


def dir_bytes(path):
    total = 0
    for root, _, files in os.walk(path):
        for f in files:
            total += os.path.getsize(os.path.join(root, f))
    return total


def audit(corpus, sample=400, seed=1):
    """Automated verification of the floors the generator promises.

    Two levels, because a floor that only holds on the 2x canvas is not a floor:
      nominal  -- what the manifest recorded (enforce_gap target, fitted height)
      measured -- what the PNG actually contains after JPEG / noise /
                  LANCZOS downscale to 96x32, i.e. the pixels the model sees
    """
    man = os.path.join(corpus, "manifest.csv")
    rows = list(csv.DictReader(open(man, encoding="ascii")))
    txt = [r for r in rows if r["text"]]
    gaps = np.array([float(r["gap"]) for r in txt], np.float32)
    inkh = np.array([int(r["inkh"]) for r in txt])
    print(f"\n[audit] rows={len(rows)}  with-glyphs={len(txt)}  "
          f"empty-capsules={len(rows) - len(txt)}")
    print(f"  recorded gap (strong-ink pixels vs their body, post JPEG/noise/downscale): "
          f"min {gaps.min():.0f} p05 {np.percentile(gaps, 5):.0f} "
          f"p50 {np.percentile(gaps, 50):.0f} max {gaps.max():.0f}   (floor {MIN_GAP_SEEN})")
    print(f"  rows under the seen-gap floor: {(gaps < MIN_GAP_SEEN).sum()}   "
          f"(colour-level enforcement floor is {MIN_GAP})")
    print(f"  glyph height in model px: min {inkh.min()} p05 {np.percentile(inkh, 5):.0f} "
          f"p50 {np.percentile(inkh, 50):.0f} max {inkh.max()}   (floor {MIN_INK_H_1X})")
    under_h = sum(1 for r in txt if int(r["inkh"]) < MIN_INK_H_1X)
    print(f"  rows under the height floor: {under_h}, of which flagged tight=1: "
          f"{sum(1 for r in txt if r['tight'] == '1')}")
    in_band = ((inkh >= GLYPH_H_1X[0]) & (inkh <= GLYPH_H_1X[1])).mean()
    print(f"  inside the measured real band {GLYPH_H_1X[0]:.0f}-{GLYPH_H_1X[1]:.0f} model px: "
          f"{100.0 * in_band:.1f}%")

    rng = np.random.default_rng(seed)
    pick = [txt[int(i)] for i in rng.choice(len(txt), size=min(sample, len(txt)),
                                             replace=False)]
    dyn, hf = [], []
    for r in pick:
        img = Image.open(os.path.join(corpus, "pos" if r["label"] == "0" else "neg",
                                      r["file"])).convert("L")
        a = np.asarray(img, np.float32)
        # p97-p03 of the luminance = the darkest-to-brightest span inside the crop
        dyn.append(float(np.percentile(a, 97) - np.percentile(a, 3)))
        # residual against a 3x3 median: ~0 on a clean render, >0 when noise survives
        med = np.asarray(img.filter(ImageFilter.MedianFilter(3)), np.float32)
        hf.append(float(np.abs(a - med).mean()))
    dyn = np.array(dyn); hf = np.array(hf)
    print(f"  MEASURED (n={len(pick)} PNGs) p97-p03 span: min {dyn.min():.0f} "
          f"p05 {np.percentile(dyn, 5):.0f} p50 {np.percentile(dyn, 50):.0f} | "
          f"under 25 (washed out): {(dyn < 25).sum()}")
    print(f"  MEASURED median-filter residual (noise proxy): p05 {np.percentile(hf, 5):.2f} "
          f"p50 {np.percentile(hf, 50):.2f} max {hf.max():.2f} | "
          f"flat (<0.35, i.e. no noise): {(hf < 0.35).sum()}")
    tight_rows = np.array([r["tight"] == "1" for r in txt])
    viol_h = int(((inkh < MIN_INK_H_1X) & ~tight_rows).sum())
    viol_gap = int((gaps < MIN_GAP_SEEN).sum())
    flat = int((hf < 0.35).sum())
    print(f"  violations: gap {viol_gap}, height {viol_h} unflagged "
          f"({int(tight_rows.sum())} tight=1 labels are allowed under the height floor), "
          f"noise-free {flat}")
    ok = viol_gap == 0 and viol_h == 0 and flat == 0
    print(f"[audit] floors held: {'YES' if ok else 'NO'}")
    return 0 if ok else 4


def main():
    ascii_stdout()
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="R:/skipnet")
    ap.add_argument("--n", type=int, default=1000,
                    help="images to emit in this batch (one file per image)")
    ap.add_argument("--neg-rate", type=float, default=0.30,
                    help="probability a given image is a negative")
    ap.add_argument("--ref", default=os.environ.get("SKIP_REF", ""))
    ap.add_argument("--seed", type=int, default=20261028)
    ap.add_argument("--budget-mb", type=int, default=1500)
    ap.add_argument("--probe", action="store_true",
                    help="also run the colour-invariance / NCC probes (slow, and "
                         "both were shown to be uninformative for real frames)")
    ap.add_argument("--audit-only", action="store_true",
                    help="re-check an existing corpus against the floors and exit")
    args = ap.parse_args()
    if args.audit_only:
        return audit(args.out)
    n_neg = int(round(args.n * args.neg_rate))
    n_pos = args.n - n_neg

    manifest = os.path.join(args.out, "manifest.csv")
    # Successive batches off the same --seed must not be clones of each other:
    # walk the rng stream on by however many rows already exist in this corpus.
    prior = 0
    if os.path.exists(manifest):
        with open(manifest, encoding="ascii") as fh:
            prior = sum(1 for _ in csv.DictReader(fh))
    rng = np.random.default_rng(args.seed + prior)
    fonts = load_fonts()
    if not fonts:
        print("[FATAL] no usable CJK font found -- refusing to emit a font-less corpus")
        return 2
    print(f"[fonts] {len(fonts)} usable faces loaded (sized per sample to GLYPH_H_1X)")

    for sub in ("pos", "neg", "real", "montage"):
        os.makedirs(os.path.join(args.out, sub), exist_ok=True)

    real = None
    if args.ref and os.path.exists(args.ref):
        work = os.path.join(args.out, "ref.jpg")
        shutil.copyfile(args.ref, work)  # avoid non-ASCII paths in the pipeline
        found = find_real_capsule(work, args.out)
        if found:
            real, meta = found
            print(f"[real] capsule at device box {meta['device_box']} "
                  f"aspect={meta['aspect']}")
            for name in ("raw", "flip"):
                p = os.path.join(args.out, "real", f"{name}.png")
                real[name].save(p)
                f = grey(real[name])
                print(f"[real] {name}: " + " ".join(
                    f"{k}={v:.3f}" if isinstance(v, float) else f"{k}={v}"
                    for k, v in ink_features(f).items()))
        else:
            print("[real] capsule NOT localised -- domain-gap check will be skipped")
    else:
        print("[real] no --ref given -- domain-gap check will be skipped")

    rows = []
    pos_imgs, neg_imgs = [], []
    gaps = []
    label = {0: "pos", 1: "neg"}
    # Batch accumulation: a second run continues the numbering instead of
    # overwriting the first 1000 files.
    start_idx = {c: 0 for c in (0, 1)}
    if os.path.exists(manifest):
        with open(manifest, encoding="ascii") as fh:
            for r in csv.DictReader(fh):
                start_idx[int(r["label"])] += 1
        print(f"[batch] continuing: existing pos={start_idx[0]} neg={start_idx[1]}")
    hdr = ["file", "label", "base", "text", "style", "alpha", "gap", "inkh",
           "tight", "font", "group"]
    # Repeating the hard negatives inside the sampling pool is the whole weighting:
    # draw_capsule() draws uniformly, and the manifest still records the real base.
    hard = [b for b in NEG_BASE if b in HARD_NEG]
    soft = [b for b in NEG_BASE if b not in HARD_NEG]
    rep = max(1, int(round(HARD_NEG_SHARE * len(soft) / (len(hard) * (1 - HARD_NEG_SHARE)))))
    neg_texts = soft + hard * rep
    for cls, n, texts, empty_ok in ((0, n_pos, POS_BASE, False),
                                    (1, n_neg, neg_texts, True)):
        for i in range(n):
            img, meta = draw_capsule(rng, fonts, texts, empty_ok)
            gaps.append(meta["gap"])
            idx = start_idx[cls] + i
            name = f"{label[cls]}_{idx:06d}.png"
            img.save(os.path.join(args.out, label[cls], name))
            (pos_imgs if cls == 0 else neg_imgs).append(grey(img))
            esc = ascii(meta["text"])[1:-1]
            rows.append((name, cls, ascii(meta["base"])[1:-1], esc,
                         meta["style"], meta["alpha"], round(meta["gap"], 1),
                         meta["inkh"], meta["tight"], meta["font"],
                         f"{ascii(meta['base'])[1:-1]}|{esc}"))
            if (i + 1) % 1000 == 0:
                used = dir_bytes(args.out) / 1e6
                print(f"[gen] {label[cls]} {i + 1}/{n}  dir={used:.0f} MB")
                if used > args.budget_mb:
                    print(f"[FATAL] exceeded --budget-mb={args.budget_mb}, aborting")
                    return 3

    gaps = np.asarray(gaps, dtype=np.float32)
    g_all = np.asarray(gaps, dtype=np.float32)
    g_txt = g_all[g_all > 0.0]
    print("\n[colour] text-vs-body luminance gap MEASURED on the composited pixels "
          "(n=%d with glyphs, %d empty capsules)" % (g_txt.size, g_all.size - g_txt.size))
    for thr in (MIN_GAP_SEEN, MIN_GAP, 80, 120):
        print(f"  gap<{thr:3d}: {100.0 * (g_txt < thr).mean():5.1f}%")
    print(f"  colour-level floor is MIN_GAP {MIN_GAP} (enforce_gap pushes equal-luminance")
    print(f"  pairings apart); what is recorded here is what survives rendering, so the")
    print(f"  audit floor is MIN_GAP_SEEN {MIN_GAP_SEEN} and hues too dark to clear their")
    print("  body fall back to white/black rather than to an unreadable sample.")

    new_file = not os.path.exists(manifest)
    with open(manifest, "a" if rows and not new_file else "w",
              newline="", encoding="ascii") as fh:
        wr = csv.writer(fh)
        if new_file:
            wr.writerow(hdr)
        wr.writerows(rows)
    print(f"[manifest] +{len(rows)} rows -> {manifest}")

    montage(pos_imgs[:200], os.path.join(args.out, "montage", "pos_200.png"))
    montage(neg_imgs[:200], os.path.join(args.out, "montage", "neg_200.png"))
    print(f"[montage] {os.path.join(args.out, 'montage')}")

    rc = audit(args.out)
    if not args.probe:
        return rc

    # ---- colour-invariance probe ---------------------------------------------
    # Same glyphs, all drawn with independent random colours/fonts/layouts; the
    # question is whether raw-pixel nearest neighbour still groups them by *text*.
    # If it does not, colour/layout noise dominates the pixel space, which is the
    # honest reason the earlier "real capsule matches negatives better" reading was
    # uninformative -- and a warning that a colour-blind input needs help.
    def render_forced(base, rng):
        img, _g = draw_capsule(rng, fonts, [base], False)
        return img

    groups, group_ids = [], []
    for gi, base in enumerate(POS_BASE + NEG_BASE):
        for _ in range(12):
            groups.append(grey(render_forced(base, rng)))
            group_ids.append(gi)
    G = np.stack(groups).astype(np.float32)
    Gz = G.reshape(G.shape[0], -1)
    Gz = (Gz - Gz.mean(1, keepdims=True)) / (Gz.std(1, keepdims=True) + 1e-6)
    ids = np.asarray(group_ids)
    same_mask = ids[:, None] == ids[None, :]
    sim = Gz @ Gz.T
    np.fill_diagonal(sim, -9.0)                       # never match yourself
    best_same = np.where(same_mask, sim, -9.0).max(1)
    best_any = sim.max(1)
    nn_same = int((best_same >= best_any - 1e-6).sum())
    print(f"\n[colour-invariance] raw-pixel kNN groups same text despite random colours: "
          f"{nn_same}/{len(groups)} = {100.0 * nn_same / len(groups):.1f}%")
    print("  (>=70% means text structure survives colour noise in pixel space;")
    print("   low means colour/layout dominates and the input channels must change.)")

    # ---- training-free domain-gap probes -------------------------------------
    P = np.stack(pos_imgs).astype(np.float32)
    N = np.stack(neg_imgs).astype(np.float32)

    def z(a):
        a = a - a.mean()
        s = a.std()
        return a / (s + 1e-6)

    Pz = np.stack([z(p).ravel() for p in P])
    Nz = np.stack([z(n).ravel() for n in N])
    pos_feats = [ink_features(p) for p in pos_imgs[:800]]
    neg_feats = [ink_features(n) for n in neg_imgs[:800]]

    print("\n[distribution] synthetic positives (n=%d) / negatives (n=%d)" % (len(P), len(N)))
    for key in ("mean", "std", "ink_ratio", "n_comp"):
        pv = np.array([f[key] for f in pos_feats], dtype=np.float32)
        nv = np.array([f[key] for f in neg_feats], dtype=np.float32)
        print(f"  {key:9s} pos p5/p50/p95 = {np.percentile(pv,5):8.2f}/"
              f"{np.percentile(pv,50):8.2f}/{np.percentile(pv,95):8.2f}   "
              f"neg = {np.percentile(nv,5):8.2f}/{np.percentile(nv,50):8.2f}/"
              f"{np.percentile(nv,95):8.2f}")

    if real is not None:
        rg = z(grey(real["raw"]).reshape(1, IN_H, IN_W)).ravel()
        rf = ink_features(grey(real["raw"]))
        sp = (Pz @ rg.reshape(-1, 1)).ravel() / (IN_W * IN_H)
        sn = (Nz @ rg.reshape(-1, 1)).ravel() / (IN_W * IN_H)
        print("\n[domain-gap] real capsule vs synthetic corpus")
        print(f"  NCC  best-vs-pos = {sp.max():.3f}   (median {np.median(sp):.3f})")
        print(f"  NCC  best-vs-neg = {sn.max():.3f}   (median {np.median(sn):.3f})")
        for key in ("mean", "std", "ink_ratio", "n_comp"):
            pv = np.array([f[key] for f in pos_feats], dtype=np.float32)
            pct = float((pv <= rf[key]).mean() * 100)
            flag = "IN " if 2 <= pct <= 98 else "OUT"
            print(f"  {key:9s} real={rf[key]:8.2f}  pos-percentile={pct:5.1f}%  [{flag}]")
        print("  NOTE: NCC on raw pixels is a weak proxy -- a low value does not by")
        print("        itself prove the model will fail. Read it together with")
        print("        montage/pos_200.png vs real/raw.png by eye.")

    # negative-control self-check: the harness must be able to say FAIL
    checks = ok = 0
    checks += 1
    ok += int(len(rows) == args.pos + args.neg)
    checks += 1
    ok += int(sum(1 for _, c in rows if c == 0) == args.pos)
    checks += 1
    ok += int(os.path.exists(os.path.join(args.out, "montage", "pos_200.png")))
    checks += 1
    ok += int(bool(P.std() > 1.0 and N.std() > 1.0))  # corpus is not a flat image
    print(f"\n[selfcheck] {ok}/{checks} passed   out={args.out} "
          f"size={dir_bytes(args.out)/1e6:.0f} MB")
    return 0 if ok == checks else 4


if __name__ == "__main__":
    sys.exit(main())
