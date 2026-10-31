"""按「生成时的标签」横向比较多个权重 —— 同一批留出图，同一份标签。

为什么要这个脚本：
  真机截图只能人工标几个框，换个框参数就换一套口径，跨版本比不出东西。
  而合成样本在生成那一刻就知道自己是正是负（文件名里的 kind），所以留一批不参与
  训练的样本、固定种子生成一次，之后所有版本都在**同一批图**上比召回/误报。

用法：
  # 1) 生成一份留出批（只需一次，之后复用）
  python tools/eval_gen.py --make --size 600 --out D:/tmp/gen_eval/e600.npz

  # 2) 比多个权重（同一批图）
  python tools/eval_gen.py --eval D:/tmp/gen_eval/e600.npz \
      D:/tmp/skipnet_glyph_m/skipnet.pt D:/tmp/skipnet_glyph_p/skipnet.pt \
      D:/tmp/skipnet_glyph_h/skipnet.pt

  # 3) 把误判样本导出成 PNG 看（96×32 输入还原，只用于人眼确认）
  python tools/eval_gen.py --eval ... --dump D:/tmp/gen_eval/miss D:/tmp/xxx/skipnet.pt
"""
import argparse
import os
import sys

import numpy as np
import torch
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import glyph_synth as G            # noqa: E402
import par_synth as PS             # noqa: E402
from train_skip_torch import SkipNet  # noqa: E402

POS_KINDS = ("pos", "p_real", "p_loose")
NEG_KINDS = ("neg", "rtneg", "bg", "status", "hard")


def build_model(pt, dev="cpu"):
    net = SkipNet(width=2, head="meanmax").to(dev)
    ck = torch.load(pt, map_location="cpu", weights_only=False)
    net.load_state_dict(ck["state_dict"] if "state_dict" in ck else ck)
    net.eval()
    return net


@torch.no_grad()
def probs(net, X, dev="cpu", bs=256):
    out = []
    for s in range(0, len(X), bs):
        t = torch.from_numpy(X[s:s + bs]).to(dev)
        out.append(torch.softmax(net(t), 1)[:, 1].cpu().numpy())
    return np.concatenate(out)


def make_eval(args):
    root = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(root, exist_ok=True)
    tmp = os.path.join(root, "_gen")
    pool = PS.GenPool(args.raw, args.extra, args.real_box, workers=args.workers,
                      seed=args.seed)
    from batch_train_glyphs import plan_eval, read_round, rm_tree
    rm_tree(tmp)
    os.makedirs(tmp, exist_ok=True)
    paths = pool.run(tmp, plan_eval(args.size), seed_base=args.eval_seed)
    X, y, kinds = read_round(paths, want_kinds=True)
    pool.close()
    rm_tree(tmp)
    np.savez_compressed(args.out, X=X, y=y, kinds=kinds)
    print(f"[make] {args.out}  {X.shape}  正 {int(y.sum())}/{len(y)}  种子={args.eval_seed}")
    for k in sorted(set(kinds.tolist())):
        m = kinds == k
        print(f"  {k:<8} n={int(m.sum()):>4}  label={int(y[m][0])}")


def load_eval(path):
    z = np.load(path, allow_pickle=True)
    return z["X"], z["y"], z["kinds"]


def row_of(p, y, kinds, thr):
    rep = {}
    for k in sorted(set(kinds.tolist())):
        m = kinds == k
        q = p[m]
        rep[k] = (float(q.min()), float(q.max()), float(q.mean()),
                  float((q >= thr).mean()))
    return rep


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pts", nargs="*", help="要比较的 skipnet.pt（可多个）")
    ap.add_argument("--eval", default=r"D:/tmp/gen_eval/e600.npz")
    ap.add_argument("--make", action="store_true", help="只生成留出批")
    ap.add_argument("--size", type=int, default=600)
    ap.add_argument("--seed", type=int, default=20261029)
    ap.add_argument("--eval-seed", type=int, default=991733)
    ap.add_argument("--workers", type=int, default=12)
    ap.add_argument("--raw", default=r"R:/byfen_live2")
    ap.add_argument("--extra", default=r"R:/byfen_live")
    ap.add_argument("--out", default=r"D:/tmp/gen_eval/e600.npz")
    ap.add_argument("--thr", type=float, default=0.9)
    ap.add_argument("--dump", default="", help="误判样本导出目录（PNG，96×32 输入还原）")
    ap.add_argument("--device", default="cpu")
    a = ap.parse_args()
    from batch_train_glyphs import REAL_BOXES
    a.real_box = REAL_BOXES

    if a.make:
        return make_eval(a)

    if not os.path.exists(a.eval):
        raise SystemExit(f"没有留出批：{a.eval}\n先跑 --make 生成一次（固定种子，以后复用）。")
    X, y, kinds = load_eval(a.eval)
    print(f"[eval] {a.eval}  {X.shape}  正 {int(y.sum())}/{len(y)}  thr={a.thr}")

    hdr = (f"{'版本':<10}{'组':>5}{'召回':>7}{'误报':>7}{'genScore':>9}"
           + "".join(f"{k:>9}" for k in POS_KINDS)
           + "".join(f"{k:>9}" for k in NEG_KINDS))
    print(hdr)
    print("-" * len(hdr))
    cache = {}
    for pt in a.pts:
        name = os.path.basename(os.path.dirname(pt))
        name = name.replace("skipnet_glyph_", "").replace("skipnet_", "")[:10]
        net = build_model(pt, a.device)
        p = probs(net, X, a.device)
        cache[name] = p
        rep = row_of(p, y, kinds, a.thr)
        rec = np.mean([rep[k][3] for k in POS_KINDS if k in rep])
        fpr = max([rep[k][3] for k in NEG_KINDS if k in rep] or [0.0])
        sc = 2.0 * rec - 4.0 * fpr
        line = f"{name:<10}{len(p):>5}{rec:>7.3f}{fpr:>7.3f}{sc:>9.3f}"
        line += "".join(f"{rep[k][3]:>9.3f}" for k in POS_KINDS if k in rep)
        line += "".join(f"{rep[k][3]:>9.3f}" for k in NEG_KINDS if k in rep)
        print(line)
        if a.dump:
            _dump_miss(a.dump, name, X, y, kinds, p, a.thr)

    if len(cache) >= 2:
        names = list(cache)
        print("\n[分歧] 只在 A 判对、B 判错（阈值 %.2f）" % a.thr)
        for i in range(len(names)):
            for j in range(i + 1, len(names)):
                pa, pb = cache[names[i]], cache[names[j]]
                oka = (pa >= a.thr) == (y == 1)
                okb = (pb >= a.thr) == (y == 1)
                only_a = int((oka & ~okb).sum())
                only_b = int((okb & ~oka).sum())
                both_wrong = int((~oka & ~okb).sum())
                print(f"  {names[i]} vs {names[j]}: "
                      f"{names[i]}独有对={only_a} {names[j]}独有对={only_b} 都错={both_wrong}")
    return 0


def _dump_miss(outdir, name, X, y, kinds, p, thr, limit=40):
    """把判错的样本还原成 96×32 PNG。

    输入是 per-image per-channel 标准化后的结果，还原只能回到「视觉上可看」，
    不是原始像素 —— 用来人眼确认「这框到底是什么」，不做像素级比对。
    """
    os.makedirs(outdir, exist_ok=True)
    pred = (p >= thr).astype(int)
    idx = np.where(pred != y)[0]
    # 误点（负样本被判正）比漏检更危险，排前面
    order = sorted(idx, key=lambda i: (y[i] == 0, -p[i]))
    for n, i in enumerate(order[:limit]):
        a = X[i].transpose(1, 2, 0)
        a = (a - a.min()) / max(1e-6, a.max() - a.min())
        im = Image.fromarray((a * 255).astype(np.uint8))
        fn = f"{name}_{n:02d}_y{int(y[i])}_p{p[i]:.3f}_{kinds[i]}.png"
        im.resize((96 * 4, 32 * 4), Image.NEAREST).save(os.path.join(outdir, fn))
    if len(idx):
        print(f"    [dump] {name}: 判错 {len(idx)} 张 -> {outdir}")


if __name__ == "__main__":
    sys.exit(main())
