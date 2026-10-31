"""Crop the four corner regions of local device screenshots and locate capsules.

Input is a folder of screenshots (no device access).  Output:
  crops/pos/*.png   a capsule-like blob was localised
  crops/neg/*.png   the corner region, no capsule-like blob
  sheet.png         every crop, numbered, for by-eye labelling
  crops.csv         file, corner, box, aspect, fill

The blob gates are the ones ported from tools/gen_skip_corpus.py and validated to
reproduce the reference capsule box exactly.  On the 18 real frames they gave
10/17 recall in the top-right band and one false positive on a decorative banner
(2026-10-28), so every crop here still needs an eye on it -- this script does not
claim labels, it only proposes boxes.
"""

import argparse
import csv
import glob
import io
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harvest_real_capsules import _dilate, _erode  # noqa: E402


def grow_largest(mask, step=2, max_rounds=400):
    """Largest 4-connected blob, by BFS-by-dilation (numpy, so it is fast).

    Growing `step` pixels per round and AND-ing back with the mask keeps the
    component exact: a neighbour is only ever claimed if it is set in the mask.
    Returns (area, box) or None.
    """
    if not mask.any():
        return None
    ys, xs = np.where(mask)
    blob = np.zeros_like(mask)
    blob[ys[0], xs[0]] = True
    prev = -1
    for _ in range(max_rounds):
        grown = _dilate(blob, step) & mask
        n = int(grown.sum())
        blob = grown
        if n == prev:
            break
        prev = n
    yy, xx = np.where(blob)
    return int(blob.sum()), (int(xx.min()), int(yy.min()),
                             int(xx.max()) + 1, int(yy.max()) + 1)


# corner name -> (x0frac, x1frac, y0frac, y1frac) of the search window
CORNERS = {
    "TL": (0.00, 0.45, 0.02, 0.22),
    "TR": (0.55, 1.00, 0.02, 0.22),
    "BL": (0.00, 0.45, 0.76, 0.95),
    "BR": (0.55, 1.00, 0.76, 0.95),
}


def blobs_in(im, fx0, fx1, fy0, fy1):
    """Best capsule-like blob in one corner window, or None."""
    W, H = im.size
    x0, x1 = int(fx0 * W), int(fx1 * W)
    y0, y1 = int(fy0 * H), int(fy1 * H)
    sub = np.asarray(im, np.int16)[y0:y1, x0:x1]
    bg = np.median(sub.reshape(-1, 3), axis=0)
    dist = np.abs(sub - bg).sum(axis=2)
    mask = _erode(_dilate(dist > 40, 3), 2)
    best = None
    work = mask.copy()
    for _ in range(4):                      # inspect the 4 biggest blobs
        got = grow_largest(work)
        if got is None:
            break
        area, (bx0, by0, bx1, by1) = got
        bw, bh = bx1 - bx0, by1 - by0
        aspect = bw / float(bh)
        fill = area / float(bw * bh)
        ok = (bw >= 60 and bh >= 25 and 1.3 <= aspect <= 6.5 and fill > 0.35
              and bw <= 0.55 * sub.shape[1] and bh <= 0.35 * sub.shape[0])
        if ok and (best is None or area > best[0]):
            best = (area, (x0 + bx0, y0 + by0, x0 + bx1, y0 + by1), aspect, fill)
        work[by0:by1, bx0:bx1] &= ~mask[by0:by1, bx0:bx1]
        work = work & ~np.zeros_like(work)
    return (best[1], best[2], best[3]) if best else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", default=r"R:\skipnet_real\frames")
    ap.add_argument("--out", default=r"R:\skipnet_real\crops")
    ap.add_argument("--pad", type=int, default=3)
    args = ap.parse_args()

    files = sorted(glob.glob(os.path.join(args.frames, "*.jpg"))
                   + glob.glob(os.path.join(args.frames, "*.png")))
    if not files:
        sys.exit(f"[fatal] no images in {args.frames}")
    for sub in ("pos", "neg"):
        os.makedirs(os.path.join(args.out, sub), exist_ok=True)

    rows = []
    cells = []
    for fi, f in enumerate(files):
        im = Image.open(f).convert("RGB")
        for name, fr in CORNERS.items():
            hit = blobs_in(im, *fr)
            if hit:
                box, asp, fill = hit
                pad = args.pad
                c = im.crop((box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad))
                fn = f"pos/{fi:02d}_{name}.png"
                c.save(os.path.join(args.out, fn))
                rows.append((os.path.basename(f), name, fn, "pos", box,
                             f"{asp:.2f}", f"{fill:.2f}"))
                cells.append((f"#{fi:02d} {name} POS", c))
            else:
                W, H = im.size
                x0, x1 = int(fr[0] * W), int(fr[1] * W)
                y0, y1 = int(fr[2] * H), int(fr[3] * H)
                c = im.crop((x0, y0, x1, y1))
                fn = f"neg/{fi:02d}_{name}.png"
                c.save(os.path.join(args.out, fn))
                rows.append((os.path.basename(f), name, fn, "neg", (x0, y0, x1, y1),
                             "", ""))
                cells.append((f"#{fi:02d} {name} ---", c))

    with open(os.path.join(args.out, "crops.csv"), "w", newline="",
              encoding="ascii") as fh:
        w = csv.writer(fh)
        w.writerow(["frame", "corner", "file", "kind", "box", "aspect", "fill"])
        w.writerows(rows)

    cw, chh = 300, 120
    ncol = 3
    nrow = (len(cells) + ncol - 1) // ncol
    sheet = Image.new("RGB", (ncol * (cw + 4), nrow * (chh + 18)), (28, 28, 28))
    d = ImageDraw.Draw(sheet)
    for i, (lab, c) in enumerate(cells):
        c = c.resize((cw, chh), Image.LANCZOS)
        x, y = (i % ncol) * (cw + 4), (i // ncol) * (chh + 18)
        sheet.paste(c, (x, y + 16))
        d.text((x + 2, y + 2), lab, fill=(255, 255, 0))
    sheet.save(os.path.join(args.out, "sheet.png"))
    pos = sum(1 for r in rows if r[3] == "pos")
    print(f"[frames] {len(files)}  corners scanned {len(rows)}  pos {pos}  neg {len(rows) - pos}")
    print(f"[out] {args.out}  sheet={os.path.join(args.out, 'sheet.png')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
