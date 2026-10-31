"""Score the torch checkpoint on the 72 labelled real corner crops.

Also answers the question the on-device port depends on: how much does the
resampler used to reach 96x32 matter?  The corpus was rendered at 2x and
LANCZOS-downscaled, so LANCZOS is the training-matched choice; Android's
Bitmap.createScaledBitmap is bilinear, and an area average is the cheap
alternative.  If the probabilities agree across all three, the port can use
whichever is fastest.

Writes `vectors.bin` (inputs in float32 CHW + expected logits) next to the
checkpoint so the JVM/Kotlin port can be diffed numerically.
"""

import argparse
import csv
import os
import sys

import numpy as np
import torch
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from train_skip_torch import IN_H, IN_W, SkipNet  # noqa: E402

RESAMPLERS = {
    "lanczos": Image.LANCZOS,
    "bilinear": Image.BILINEAR,
    "box": Image.BOX,
}


def build_net(ckpt):
    blob = torch.load(ckpt, map_location="cpu", weights_only=True)
    net = SkipNet(width=blob["width"], head=blob["head"])
    net.load_state_dict(blob["state_dict"])
    net.eval()
    return net


def standardize(im, resample):
    if im.size != (IN_W, IN_H):
        im = im.resize((IN_W, IN_H), resample)
    a = np.asarray(im.convert("RGB"), np.float32) / 255.0
    m = a.mean(axis=(1, 2), keepdims=True)
    s = a.std(axis=(1, 2), keepdims=True) + 1e-5
    return ((a - m) / s).transpose(2, 0, 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--crops", default=r"R:\skipnet_real\crops")
    ap.add_argument("--ckpt", default=r"R:\skipnet_model_torchv5w2\skipnet.pt")
    ap.add_argument("--threshold", type=float, default=0.9)
    ap.add_argument("--vectors", default=r"R:\skipnet_model_torchv5w2\vectors.bin")
    args = ap.parse_args()

    net = build_net(args.ckpt)
    rows = list(csv.DictReader(open(os.path.join(args.crops, "labels.csv"),
                                    encoding="ascii")))
    files = [os.path.join(args.crops, r["file"]) for r in rows]
    y = np.array([int(r["label"]) for r in rows])

    probs = {}
    for name, res in RESAMPLERS.items():
        x = np.stack([standardize(Image.open(p), res) for p in files])
        with torch.no_grad():
            logits = net(torch.from_numpy(x)).numpy()
        p = np.exp(logits[:, 1]) / np.exp(logits).sum(1)
        probs[name] = p

    for name, p in probs.items():
        hit = p >= args.threshold
        rec = hit[y == 1].mean() if (y == 1).any() else float("nan")
        fpr = hit[y == 0].mean()
        print(f"[{name:8s}] recall@{args.threshold}={rec:.3f} "
              f"fpr={fpr:.3f} pos={int(hit[y == 1].sum())}/{int((y == 1).sum())} "
              f"neg={int(hit[y == 0].sum())}/{int((y == 0).sum())}")

    base = probs["lanczos"]
    for name in ("bilinear", "box"):
        d = np.abs(probs[name] - base)
        flip = ((probs[name] >= args.threshold) != (base >= args.threshold)).sum()
        print(f"[delta {name:8s}] max={d.max():.4f} mean={d.mean():.5f} "
              f"threshold-flips={int(flip)}")

    # rows where the false positives live: they are what the tap would act on
    for i in np.where((base >= args.threshold) & (y == 0))[0]:
        print(f"[fp] #{rows[i]['idx']:>3s} {rows[i]['frame'][-24:]} "
              f"{rows[i]['corner']} p={base[i]:.3f} {rows[i]['file']}")
    for i in np.where((base < args.threshold) & (y == 1))[0]:
        print(f"[miss] #{rows[i]['idx']:>3s} {rows[i]['frame'][-24:]} "
              f"{rows[i]['corner']} p={base[i]:.3f} {rows[i]['file']}")

    vel = np.stack([standardize(Image.open(p), RESAMPLERS["lanczos"]) for p in files])
    with torch.no_grad():
        logits = net(torch.from_numpy(vel)).numpy().astype("<f4")
    with open(args.vectors, "wb") as fh:
        fh.write(np.int32(len(files)).tobytes())
        fh.write(np.int32(IN_W).tobytes())
        fh.write(np.int32(IN_H).tobytes())
        fh.write(vel.astype("<f4").tobytes())
        fh.write(logits.tobytes())
    print(f"[vectors] {args.vectors} n={len(files)} "
          f"bytes={os.path.getsize(args.vectors)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
