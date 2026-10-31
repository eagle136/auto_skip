"""GPU trainer for the corner-skip text recognizer (same net as train_skip_net.py).

The numpy implementation in tools/train_skip_net.py is the reference: its
backward pass was verified against float64 finite differences to 6e-7.  This
script builds the identical architecture (conv 3->16 | mp | conv 16->24 | mp |
conv 24->32 | mp | GAP | fc 32->2, 10,938 params) with the identical
preprocessing (per-image, per-channel standardize) and the identical
group-based split, so the two are comparable and the export stays honest.

Two things fixed relative to the first numpy run:
  * the classifier head is zero-initialised, so training starts at ln(2)=0.69
    instead of 2.52 (the random head produced mean logit -1.4 at init);
  * the reported decision is a threshold sweep, because the app cares about
    "recall at a bounded false-positive rate", not accuracy.

Run:
  D:/path/torch/env/Scripts/python.exe tools/train_skip_torch.py \
      --corpus R:/skipnet_batch --epochs 200
"""

import argparse
import csv
import hashlib
import json
import os
import sys
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from PIL import Image

IN_W, IN_H, CH = 96, 32, 3


def load_corpus(corpus):
    man = os.path.join(corpus, "manifest.csv")
    if not os.path.exists(man):
        sys.exit(f"[fatal] no manifest at {man}")
    rows = list(csv.DictReader(open(man, encoding="ascii")))
    X = np.empty((len(rows), CH, IN_H, IN_W), np.float32)
    y = np.empty(len(rows), np.int64)
    for i, r in enumerate(rows):
        cls = int(r["label"])                    # 0 = pos/ = the skip capsule
        p = os.path.join(corpus, "pos" if cls == 0 else "neg", r["file"])
        im = Image.open(p)
        if im.size != (IN_W, IN_H):
            sys.exit(f"[fatal] {p} is {im.size}, expected {(IN_W, IN_H)}")
        a = np.asarray(im.convert("RGB"), np.float32) / 255.0
        m = a.mean(axis=(1, 2), keepdims=True)
        s = a.std(axis=(1, 2), keepdims=True) + 1e-5
        X[i] = ((a - m) / s).transpose(2, 0, 1)
        y[i] = 1 - cls                           # model class 1 == skip
    return X, y, rows


def split_by_group(rows, val_frac=0.20, buckets=100):
    train, val = [], []
    for i, r in enumerate(rows):
        h = int(hashlib.md5(r["group"].encode()).hexdigest(), 16) % buckets
        (val if h < int(buckets * val_frac) else train).append(i)
    return np.array(train), np.array(val)


class SkipNet(nn.Module):
    """width=1 reproduces the numpy reference (10,938 params, mean-pool head).

    head='meanmax' concatenates global mean and global max of the last map:
    global average pooling alone throws away "where in the crop is this
    pattern", which is exactly the information that separates 跳过 from 路过.
    """

    def __init__(self, width=1, head="mean"):
        super().__init__()
        c = [max(1, int(w * width)) for w in (16, 24, 32)]
        self.c = nn.ModuleList([nn.Conv2d(3, c[0], 3, padding=1),
                                nn.Conv2d(c[0], c[1], 3, padding=1),
                                nn.Conv2d(c[1], c[2], 3, padding=1)])
        self.head = head
        fin = c[2] * (2 if head == "meanmax" else 1)
        self.f = nn.Linear(fin, 2)
        nn.init.zeros_(self.f.weight)          # start the loss at ln(2)
        nn.init.zeros_(self.f.bias)

    def forward(self, x):
        x = F.max_pool2d(F.relu(self.c[0](x)), 2)
        x = F.max_pool2d(F.relu(self.c[1](x)), 2)
        x = F.relu(self.c[2](x))
        if self.head == "mean":
            x = x.mean(dim=(2, 3))
        elif self.head == "max":
            x = x.amax(dim=(2, 3))
        else:
            x = torch.cat([x.mean(dim=(2, 3)), x.amax(dim=(2, 3))], 1)
        return self.f(x)


def sweep(prob, y, ths=(0.5, 0.7, 0.9, 0.95, 0.99)):
    pos = y == 1
    neg = y == 0
    out = []
    for t in ths:
        p = prob >= t
        out.append((t, float(p[pos].sum()) / max(1, int(pos.sum())),
                    float(p[neg].sum()) / max(1, int(neg.sum()))))
    return out


def main():
    global IN_H
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", default="R:/skipnet_batch")
    ap.add_argument("--epochs", type=int, default=200)
    ap.add_argument("--bs", type=int, default=256)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--out", default="R:/skipnet_model_torch")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--width", type=int, default=1)
    ap.add_argument("--head", default="mean", choices=("mean", "max", "meanmax"))
    ap.add_argument("--tag", default="", help="suffix for --out, for sweeps")
    ap.add_argument("--in-h", type=int, default=IN_H,
                    help="input height; tile-level corpus uses 64 (160x100 瓦片压进去)")
    args = ap.parse_args()
    args.out = args.out + args.tag
    IN_H = args.in_h

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    torch.manual_seed(args.seed)
    t0 = time.time()
    Xn, yn, rows = load_corpus(args.corpus)
    tr, va = split_by_group(rows)
    tg = {rows[i]["group"] for i in tr}
    vg = {rows[i]["group"] for i in va}
    assert not (tg & vg), "group split leaked"
    print(f"[data] {Xn.shape} train={len(tr)} val={len(va)} "
          f"(val skip={int((yn[va] == 1).sum())} other={int((yn[va] == 0).sum())}) "
          f"in {time.time() - t0:.1f}s")

    X = torch.from_numpy(Xn).to(dev)
    y = torch.from_numpy(yn).to(dev)
    tr_t = torch.from_numpy(tr).to(dev)
    va_t = torch.from_numpy(va).to(dev)

    net = SkipNet(width=args.width, head=args.head).to(dev)
    n = sum(p.numel() for p in net.parameters())
    print(f"[net] params={n} width={args.width} head={args.head} device={dev} "
          f"gpu={torch.cuda.get_device_name(0) if dev == 'cuda' else '-'}")
    opt = torch.optim.AdamW(net.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs)

    best = (-1.0, None, -1)
    for ep in range(args.epochs):
        perm = tr_t[torch.randperm(len(tr_t), device=dev)]
        net.train()
        tot = 0.0
        for i in range(0, len(perm) - args.bs + 1, args.bs):
            b = perm[i:i + args.bs]
            opt.zero_grad(set_to_none=True)
            loss = F.cross_entropy(net(X[b]), y[b])
            loss.backward()
            opt.step()
            tot += float(loss)
        sched.step()
        net.eval()
        with torch.no_grad():
            pv = torch.softmax(net(X[va_t]), 1)[:, 1].cpu().numpy()
        yv = yn[va]
        vacc = float(((pv >= 0.5).astype(np.int64) == yv).mean())
        if ep % 20 == 0 or ep == args.epochs - 1:
            sw = sweep(pv, yv)
            print(f"[ep {ep:03d}] loss={tot / max(1, (len(perm) // args.bs)):.4f} "
                  f"val_acc={vacc:.4f} "
                  f"recall@0.9={sw[2][1]:.3f} fpr@0.9={sw[2][2]:.3f} "
                  f"({time.time() - t0:.0f}s)")
        if vacc > best[0]:
            best = (vacc, pv.copy(), ep)

    pv = best[1]
    yv = yn[va]
    print(f"\n[val] best-epoch acc={best[0]:.4f}")
    print("[threshold sweep on val]  threshold  skip_recall  other_FPR")
    for t, rec, fpr in sweep(pv, yv):
        print(f"    {t:>5.2f}   {rec:9.4f}  {fpr:9.4f}")
    # per-word and per-style breakdown at the operating point the app would use
    for key in ("base", "style"):
        d = {}
        for j, i in enumerate(va):
            g = rows[i][key]
            s = d.setdefault(g, [0, 0, 0])
            s[2] += 1
            s[0] += int(((pv[j] >= 0.5).astype(np.int64)) == yv[j])
            s[1] += int(pv[j] >= 0.5)
        print(f"[{key}] val groups (acc, flagged-skip):")
        for g, (ok, flagged, cnt) in sorted(d.items(), key=lambda kv: kv[1][0] / kv[1][2]):
            print(f"    {ok:4d}/{cnt:<4d} {100.0 * ok / cnt:6.1f}%  flagged={flagged:<4d} {g}")

    # the number that decides whether the net reads glyphs at all: can it fit
    # its own training negatives?  train FPR ~ val FPR means under-fitting.
    with torch.no_grad():
        pt = torch.softmax(net(X[tr_t]), 1)[:, 1].cpu().numpy()
    yt = yn[tr]
    print("\n[train]  threshold  skip_recall  other_FPR")
    for t, rec, fpr in sweep(pt, yt, (0.5, 0.9)):
        print(f"    {t:>5.2f}   {rec:9.4f}  {fpr:9.4f}")

    sw_tr = sweep(pt, yt, (0.5, 0.9))
    sw_va = sweep(pv, yv, (0.5, 0.9))
    print(f"[SUMMARY] w={args.width} head={args.head} params={n} "
          f"ep={best[2]} "
          f"train@.5={sw_tr[0][1]:.3f}/{sw_tr[0][2]:.3f} "
          f"val@.5={sw_va[0][1]:.3f}/{sw_va[0][2]:.3f} "
          f"val@.9={sw_va[1][1]:.3f}/{sw_va[1][2]:.3f} "
          f"(recall/fpr)")

    os.makedirs(args.out, exist_ok=True)
    sd = {k: v.detach().cpu() for k, v in net.state_dict().items()}
    torch.save({"state_dict": sd, "params": n, "width": args.width, "head": args.head,
                "arch": f"conv3x3-{16*args.width}-mp|conv3x3-{24*args.width}-mp"
                        f"|conv3x3-{32*args.width}-{args.head}|fc-{32*args.width}-2",
                "input": {"w": IN_W, "h": IN_H, "ch": CH,
                          "pre": "per-image per-channel (x/255-mean)/std"}},
               os.path.join(args.out, "skipnet.pt"))
    order = list(sd.keys())          # registration order, deterministic
    with open(os.path.join(args.out, "skipnet_f32.bin"), "wb") as fh:
        for k in order:
            fh.write(sd[k].numpy().astype("<f4").tobytes())
    with open(os.path.join(args.out, "model.json"), "w", encoding="ascii") as fh:
        json.dump({"params": int(n), "order": order, "head": args.head,
                   "width": args.width,
                   "input": {"w": IN_W, "h": IN_H, "ch": CH},
                   "classes": {"1": "skip"}}, fh, indent=2)
    print(f"\n[export] {args.out} bin="
          f"{os.path.getsize(os.path.join(args.out, 'skipnet_f32.bin'))} B")
    print(f"[done] {time.time() - t0:.0f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
