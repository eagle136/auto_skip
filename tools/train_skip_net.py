"""Train the corner-skip text recognizer with numpy only.

No torch/tf/sklearn on this box, and that is deliberate: a ~11k-parameter CNN
trained here exports as raw float32 arrays, which is exactly what the Kotlin
engine loads.  Adding a framework on the PC would not add one on the phone.

Corpus layout (written by tools/gen_skip_corpus.py):
  <corpus>/pos/*.png  <corpus>/neg/*.png  <corpus>/manifest.csv
  manifest columns: file,label,base,text,style,alpha,gap,group

Split is by `group` (= base word + rendered text), never by image, so two
augmentations of the same word cannot sit in train and val at once.  Because
the split key includes the word, every negative word in val is UNSEEN.

Usage:
  py tools/train_skip_net.py --corpus R:/skipnet_batch --epochs 30
"""

import argparse
import csv
import hashlib
import json
import os
import sys
import time

import numpy as np
from PIL import Image

IN_W, IN_H = 96, 32
CH = 3


# --------------------------------------------------------------- preprocessing

def standardize(a):
    """Per-image, per-channel zero-mean/unit-std on float32 [0,1] input.

    This is the structural half of colour invariance: it removes global
    brightness and per-channel gain, so the network never sees "how bright is
    this pixel" but only "how does this pixel differ from its own channel's
    neighbours".  It must be reproduced verbatim on device.
    """
    m = a.mean(axis=(1, 2), keepdims=True)
    s = a.std(axis=(1, 2), keepdims=True) + 1e-5
    return (a - m) / s


def load_corpus(corpus):
    man = os.path.join(corpus, "manifest.csv")
    if not os.path.exists(man):
        sys.exit(f"[fatal] no manifest at {man}")
    rows = list(csv.DictReader(open(man, encoding="ascii")))
    X = np.empty((len(rows), CH, IN_H, IN_W), np.float32)
    y = np.empty(len(rows), np.int64)
    meta = []
    for i, r in enumerate(rows):
        cls = int(r["label"])          # 0 = pos/ (the skip capsule), 1 = neg/
        p = os.path.join(corpus, "pos" if cls == 0 else "neg", r["file"])
        im = Image.open(p)
        if im.size != (IN_W, IN_H):
            sys.exit(f"[fatal] {p} is {im.size}, expected {(IN_W, IN_H)}")
        a = np.asarray(im.convert("RGB"), np.float32) / 255.0
        X[i] = standardize(a.transpose(2, 0, 1))
        y[i] = 1 - cls                 # model class 1 == "skip present"
        meta.append(r)
    return X, y, meta


def split_by_group(meta, val_frac=0.20, buckets=100):
    """Deterministic group split. Same group -> same side, always."""
    train, val = [], []
    for i, r in enumerate(meta):
        h = int(hashlib.md5(r["group"].encode()).hexdigest(), 16) % buckets
        (val if h < int(buckets * val_frac) else train).append(i)
    return np.array(train), np.array(val)


# ------------------------------------------------------------- numpy CNN core

def im2col(x, kh, kw, pad=1):
    N, C, H, W = x.shape
    xp = np.pad(x, ((0, 0), (0, 0), (pad, pad), (pad, pad)))
    OH, OW = H + 2 * pad - kh + 1, W + 2 * pad - kw + 1
    A = np.empty((N, kh, kw, C, OH, OW), x.dtype)
    for i in range(kh):
        for j in range(kw):
            A[:, i, j, :, :, :] = xp[:, :, i:i + OH, j:j + OW]
    return A.reshape(N, kh * kw * C, OH * OW), (OH, OW)


def col2im(B, N, C, H, W, kh, kw, pad, OH, OW):
    xp = np.zeros((N, C, H + 2 * pad, W + 2 * pad), B.dtype)
    Br = B.reshape(N, kh, kw, C, OH, OW)
    for i in range(kh):
        for j in range(kw):
            xp[:, :, i:i + OH, j:j + OW] += Br[:, i, j, :, :, :]
    return xp[:, :, pad:pad + H, pad:pad + W]


def maxpool2(x):
    N, C, H, W = x.shape
    p = x.reshape(N, C, H // 2, 2, W // 2, 2).transpose(0, 1, 2, 4, 3, 5)
    out = p.reshape(N, C, H // 2, W // 2, 4).max(-1)
    arg = p.reshape(N, C, H // 2, W // 2, 4).argmax(-1)
    return out, arg


def maxpool2_back(dout, arg, shape):
    N, C, H, W = shape
    oh, ow = H // 2, W // 2
    d = np.zeros((N, C, oh, ow, 4), dout.dtype)
    np.put_along_axis(d, arg[..., None], dout[..., None], axis=4)
    # slot s = a*2+b came from x[:, :, 2i+a, 2j+b]; undo the forward permutation
    return d.reshape(N, C, oh, ow, 2, 2).transpose(0, 1, 2, 4, 3, 5).reshape(N, C, H, W)


class Net:
    """conv(3->16)-relu-mp  conv(16->24)-relu-mp  conv(24->32)-relu-GAP  fc(32->2)"""

    def __init__(self, seed=0, lr=3e-3, dtype=np.float32):
        r = np.random.default_rng(seed)
        he = lambda fi, fo: np.sqrt(2.0 / (9.0 * fi))
        self.p = {
            "w1": r.normal(0, he(3, 16), (16, 3, 3, 3)).astype(dtype),
            "b1": np.zeros(16, dtype),
            "w2": r.normal(0, he(16, 24), (24, 16, 3, 3)).astype(dtype),
            "b2": np.zeros(24, dtype),
            "w3": r.normal(0, he(24, 32), (32, 24, 3, 3)).astype(dtype),
            "b3": np.zeros(32, dtype),
            "wf": r.normal(0, np.sqrt(2.0 / 32), (2, 32)).astype(dtype),
            "bf": np.zeros(2, dtype),
        }
        self.m = {k: np.zeros_like(v) for k, v in self.p.items()}
        self.v = {k: np.zeros_like(v) for k, v in self.p.items()}
        self.t = 0
        self.lr = lr

    def n_params(self):
        return sum(v.size for v in self.p.values())

    def _conv(self, x, w, b, cache):
        C_in = w.shape[1]
        A, (OH, OW) = im2col(x, 3, 3, 1)
        # w is (O, C_in, 3, 3); im2col flattens K as ((i*kw + j)*C_in + c),
        # so the weight matrix must be transposed to (O, i, j, c) first.
        wm = w.transpose(0, 2, 3, 1).reshape(w.shape[0], -1)
        # strided batched gemm -- einsum here does not reach BLAS and costs 4x
        out = np.matmul(wm[None, :, :], A) + b[None, :, None]
        out = out.reshape(A.shape[0], w.shape[0], OH, OW)
        cache.append(("conv", x.shape, A, wm, OH, OW, w.shape[0], C_in))
        return out

    def forward(self, x, training=False):
        cache = []
        h = self._conv(x, self.p["w1"], self.p["b1"], cache)
        a1 = np.maximum(h, 0)
        cache.append(("relu", h))
        p1, arg1 = maxpool2(a1)
        cache.append(("mp", arg1, a1.shape))

        h = self._conv(p1, self.p["w2"], self.p["b2"], cache)
        a2 = np.maximum(h, 0)
        cache.append(("relu", h))
        p2, arg2 = maxpool2(a2)
        cache.append(("mp", arg2, a2.shape))

        h = self._conv(p2, self.p["w3"], self.p["b3"], cache)
        a3 = np.maximum(h, 0)
        cache.append(("relu", h))
        g = a3.mean(axis=(2, 3))
        cache.append(("gap", a3.shape))

        logits = g @ self.p["wf"].T + self.p["bf"]
        cache.append(("fc", g))
        if training:
            return logits, cache
        return logits

    def backward(self, dlogits, cache):
        g = cache[-1][1]
        gp = {"wf": dlogits.T @ g, "bf": dlogits.sum(0)}
        dg = dlogits @ self.p["wf"]
        a3_shape = cache[-2][1]
        N, C, H, W = a3_shape
        da3 = np.repeat(dg[:, :, None, None], H * W, axis=3).reshape(N, C, H, W) / (H * W)
        i = len(cache) - 3
        dh = da3 * (cache[i][1] > 0)
        i -= 1
        dh, dwp, dbp = self._conv_back(cache, i, dh)
        i -= 1
        dh = maxpool2_back(dh, cache[i][1], cache[i][2])
        i -= 1
        dh = dh * (cache[i][1] > 0)
        i -= 1
        dh, dw2, db2 = self._conv_back(cache, i, dh)
        i -= 1
        dh = maxpool2_back(dh, cache[i][1], cache[i][2])
        i -= 1
        dh = dh * (cache[i][1] > 0)
        i -= 1
        dh, dw1, db1 = self._conv_back(cache, i, dh)
        gp.update({"w3": dwp, "b3": dbp, "w2": dw2, "b2": db2, "w1": dw1, "b1": db1})
        return gp

    def _conv_back(self, cache, i, dout):
        _, xshape, A, wm, OH, OW, O, C_in = cache[i]
        N, C, H, W = xshape
        # dout: (N, O, OH, OW) -> (N, P, O) with P = OH*OW
        dout2 = dout.transpose(0, 2, 3, 1).reshape(N, OH * OW, O)
        dA = np.matmul(wm.T[None, :, :], dout2.transpose(0, 2, 1))   # (N, K, P)
        dx = col2im(dA, N, C_in, H, W, 3, 3, 1, OH, OW)
        Dm = dout2.transpose(2, 0, 1).reshape(O, N * OH * OW)         # (O, N*P)
        Ak = A.transpose(1, 0, 2).reshape(A.shape[1], N * OH * OW)    # (K, N*P)
        dw = Dm @ Ak.T                                                 # (O, K)
        db = dout.sum(axis=(0, 2, 3))
        dw = dw.reshape(O, 3, 3, C_in).transpose(0, 3, 1, 2)
        # keep the working dtype: the gradcheck runs the same code in float64
        return dx, dw.astype(dout.dtype, copy=False), db.astype(dout.dtype, copy=False)

    def step(self, grads):
        self.t += 1
        b1, b2, eps = 0.9, 0.999, 1e-8
        for k, g in grads.items():
            self.m[k] = b1 * self.m[k] + (1 - b1) * g
            self.v[k] = b2 * self.v[k] + (1 - b2) * (g * g)
            mh = self.m[k] / (1 - b1 ** self.t)
            vh = self.v[k] / (1 - b2 ** self.t)
            self.p[k] -= self.lr * mh / (np.sqrt(vh) + eps)


def softmax_ce(logits, y):
    z = logits - logits.max(1, keepdims=True)
    e = np.exp(z)
    p = e / e.sum(1, keepdims=True)
    N = logits.shape[0]
    loss = -np.log(p[np.arange(N), y] + 1e-12).mean()
    d = p.copy()
    d[np.arange(N), y] -= 1.0
    return loss, d / N


def probs(net, X, bs=256):
    out = []
    for i in range(0, len(X), bs):
        z = net.forward(X[i:i + bs])
        e = np.exp(z - z.max(1, keepdims=True))
        out.append((e / e.sum(1, keepdims=True))[:, 1])
    return np.concatenate(out)


def report(name, p, y):
    """p = P(skip).  y = 1 for a real skip capsule, 0 for everything else."""
    pred = (p >= 0.5).astype(np.int64)
    pos = y == 1
    neg = y == 0
    rec = float(pred[pos].sum()) / max(1, int(pos.sum()))
    fpr = float(pred[neg].sum()) / max(1, int(neg.sum()))
    acc = float((pred == y).mean())
    print(f"[{name}] n={len(y)} acc={acc:.4f}  pos_recall={rec:.4f} "
          f"({int(pos.sum())})  neg_FPR={fpr:.4f} ({int(neg.sum())})")
    return acc, rec, fpr


def breakdown(p, y, meta, key):
    groups = {}
    for i, r in enumerate(meta):
        g = r[key]
        s = groups.setdefault(g, [0, 0])
        s[1] += 1
        s[0] += int(((p[i] >= 0.5).astype(np.int64)) == y[i])
    print(f"[{key}] val per-group accuracy (worst first):")
    for g, (ok, n) in sorted(groups.items(), key=lambda kv: kv[1][0] / kv[1][1])[:12]:
        print(f"    {ok:4d}/{n:<4d} {100.0 * ok / max(1, n):6.1f}%  {g}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", default="R:/skipnet_batch")
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--bs", type=int, default=64)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", default="R:/skipnet_model")
    args = ap.parse_args()

    t0 = time.time()
    X, y, meta = load_corpus(args.corpus)
    print(f"[data] {X.shape} {y.shape} in {time.time() - t0:.1f}s")
    tr, va = split_by_group(meta)
    print(f"[split] train={len(tr)} val={len(va)}  "
          f"(val skip={int((y[va] == 1).sum())} other={int((y[va] == 0).sum())})")
    vg = {meta[i]["group"] for i in va}
    tg = {meta[i]["group"] for i in tr}
    print(f"[split] groups in BOTH sides: {len(vg & tg)} (must be 0)")
    assert not (vg & tg), "group split leaked"

    os.makedirs(args.out, exist_ok=True)
    net = Net(seed=args.seed, lr=args.lr)
    print(f"[net] params={net.n_params()}  fp32={net.n_params() * 4 / 1024:.1f} KiB")

    rng = np.random.default_rng(7)
    for ep in range(args.epochs):
        order = rng.permutation(tr)
        tot = 0.0
        nb = 0
        for i in range(0, len(order) - args.bs + 1, args.bs):
            b = order[i:i + args.bs]
            cache = []
            logits, cache = net.forward(X[b], training=True)
            loss, d = softmax_ce(logits, y[b])
            net.step(net.backward(d, cache))
            tot += loss
            nb += 1
        pv = probs(net, X[va])
        vacc = float(((pv >= 0.5).astype(np.int64) == y[va]).mean())
        if ep % 2 == 0 or ep == args.epochs - 1:
            print(f"[ep {ep:02d}] loss={tot / max(1, nb):.4f} val_acc={vacc:.4f} "
                  f"({time.time() - t0:.0f}s)")

    pv = probs(net, X[va])
    report("val", pv, y[va])
    breakdown(pv, y[va], [meta[i] for i in va], "base")
    breakdown(pv, y[va], [meta[i] for i in va], "style")
    pt = probs(net, X[tr])
    report("train", pt, y[tr])

    np.savez(os.path.join(args.out, "skipnet.npz"), **net.p)
    spec = {"arch": "conv3x3-16-relu-mp|conv3x3-24-relu-mp|conv3x3-32-relu-GAP|fc32-2",
            "input": {"w": IN_W, "h": IN_H, "ch": CH,
                      "pre": "per-image per-channel (x/255 - mean)/std"},
            "classes": {"0": "not-skip", "1": "skip"},
            "params": int(net.n_params())}
    with open(os.path.join(args.out, "model.json"), "w", encoding="ascii") as fh:
        json.dump(spec, fh, indent=2)
    with open(os.path.join(args.out, "skipnet_f32.bin"), "wb") as fh:
        for k in ("w1", "b1", "w2", "b2", "w3", "b3", "wf", "bf"):
            fh.write(net.p[k].astype("<f4").tobytes())
    print(f"[export] {args.out}  bin={os.path.getsize(os.path.join(args.out, 'skipnet_f32.bin'))} B")
    print(f"[done] {time.time() - t0:.0f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
