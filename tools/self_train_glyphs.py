"""在线自训练：**生成一批 → 立刻前向/反向 → 再生成一批**，循环到验证指标收敛。

为什么不做成「先落盘几万张再训」：这一轮要教的是「字距被拉开、胶囊底透明度任意」这一类，
它和真实样本的分布差得太远，离线语料得反复重造。在线生成可以每一批都变，而且旧数据
不必占盘（20 帧真机背景随机裁，组合空间足够大）。

起点是现有的 v5w2 权重（width=2 / head=meanmax / 96×32 / 42,738 参数），只换数值不改结构，
这样 app 侧 SkipNet.kt 的加载顺序和缓冲大小一行都不用动。

采样配比（每批 bs）：
  55% 正样本，gap 分布偏向拉开的那几档；
  15% 残缺样本（「跳过」被切掉一个角）标负 —— 框没套住时不点，避免点到字边上；
  30% 负样本（含「跳过广告详情」这类带「跳过」二字的干扰文案 / 单字 / 真机背景块）。
  负样本占比给到 30% 是量出来的：20% 时 60 步就把 FPR 顶到 12.8%。

评估集是固定 seed 生成的，按 gap 与 alpha 分档看召回，再加真机帧的紧框。
"""
import argparse
import json
import os
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glyph_synth as G  # noqa: E402
import par_synth as PS  # noqa: E402
from train_skip_torch import SkipNet  # noqa: E402

IN_W, IN_H = G.IN_W, G.IN_H


GAPS = (0.0, 0.1, 0.25, 0.5, 0.75, 1.0)
GAP_W = (0.34, 0.16, 0.16, 0.14, 0.11, 0.09)


def make_plan(rng, bs, real=None):
    """按配比排一批采样计划。

    字距 0–1.0 字高（用户约束：不超过一个字）；5% 回放真机帧防止遗忘。
    返回 (specs, real_idx)：specs 里 None 表示「这一格用真机帧填」，由主进程负责，
    不进进程池（真机数组没必要 pickle 到 worker 再传回来）。
    """
    specs, real_idx = [], []
    for i in range(bs):
        r = rng.random()
        if real is not None and r < 0.05:
            # 真机回放：不加这个，2000 步后真机 min_p 从 1.000 掉到 0.523
            real_idx.append(i)
            specs.append(None)
        elif r < 0.55:
            specs.append(("pos", float(rng.choice(GAPS, p=GAP_W)), None))
        elif r < 0.68:
            specs.append(("hard", None, None))
        elif r < 0.86:
            specs.append(("neg", None, None))
        else:
            specs.append(("bg", None, None))
    return specs, real_idx


def assemble(X_part, y_part, specs, real, rng):
    """把进程池回来的合成样本与主进程填的真机帧按原计划顺序拼回一整批。"""
    bs = len(specs)
    X = np.empty((bs, 3, IN_H, IN_W), np.float32)
    y = np.zeros(bs, np.int64)
    k = 0
    for i, s in enumerate(specs):
        if s is None:
            X[i] = real[rng.integers(0, len(real))]
            y[i] = 1
        else:
            X[i] = X_part[k]
            y[i] = y_part[k]
            k += 1
    return X, y


def build_val(sampler, rng, n_gap=140, n_alpha=140, n_neg=600):
    """固定验证集：gap 分档 / alpha 分档 / 负样本 / 真机紧框。

    合成部分整批丢给进程池（2140 张，串行要 4 s，池化后 <1 s）。
    """
    out = {}
    groups, keys = [], []
    for g in GAPS:
        groups.append([("pos", g, None)] * n_gap)
        keys.append(f"gap{g}")
    for a in (0.15, 0.3, 0.5, 0.7, 0.9):
        groups.append([("pos", 0.0, a)] * n_alpha)
        keys.append(f"alpha{a}")
    groups.append([("neg", None, None)] * n_neg)
    keys.append("neg")
    groups.append([("bg", None, None)] * 400)      # 真机背景块：单独一档看误报
    keys.append("bg")
    groups.append([("rtneg", None, None)] * 400)   # 真机高纹理块：微信/加速版这类标题
    keys.append("rtneg")
    for k, (X, _) in zip(keys, sampler.map(groups)):
        out[k] = X
    # 真机帧：三张已知胶囊框（原分辨率紧框）
    from PIL import Image
    real = [
        # 链路真机命中时的实际框，肉眼确认「跳过」完整（旧框把「过」裁在框外）
        (r"D:/迅雷下载/1793238269842.jpg", (884, 394, 1018, 434)),
        (r"D:/迅雷下载/1793241215465.jpg", (894, 22, 998, 58)),
        (r"D:/迅雷下载/Screenshot_2026-10-28-22-52-50-19_4e50d8a648c8cf205d9cb65b7a169329.jpg",
         (846, 144, 1008, 210)),
        # 2026-10-31：百分网开屏「4 | 跳过」。半透明灰胶囊 + 白字，提案器框出的是
        # **整颗胶囊 200×76**（不是字行）—— 白字亮度≈页面背景，只有灰底过阈值。
        # 老训练集全是「深底白字、框字行」的口径，这类样本必须显式进集。
        (r"D:/tmp/bf/f3.png", (850, 374, 1050, 450)),
    ]
    xs, raws, loose = [], [], []
    for path, box in real:
        if not os.path.exists(path):
            continue
        full = Image.open(path).convert("RGB")
        a = np.asarray(full.crop(box), np.uint8)
        raws.append(a)
        xs.append(G.to_input(a))
        # 松框档：每边外扩 10% 边长，和链路 cands 实际给出的框同口径。
        # 紧框档全是 1.000 也没用 —— 真机上提案框偏大 8px/边就把 p 打到 0.78（实测）。
        x0, y0, x1, y1 = box
        ex, ey = max(1, int((x1 - x0) * 0.10)), max(1, int((y1 - y0) * 0.10))
        lb = (max(0, x0 - ex), max(0, y0 - ey),
              min(full.width, x1 + ex), min(full.height, y1 + ey))
        loose.append(G.to_input(np.asarray(full.crop(lb), np.uint8)))
    if xs:
        out["real"] = np.stack(xs)
    if loose:
        out["real_loose"] = np.stack(loose)
    # 低分辨率设备/二次压缩：**从原图裁框**再压到 1/3 再放大。
    # 不能拿上面已经缩放成 96×32 的输入去压，那是「压两遍」，字早就没了。
    if raws:
        low = []
        for a in raws:
            low.append(G.to_input(G.degrade(a, _ForceScale(3))))
        out["real_lo3"] = np.stack(low)

    # 百分网那颗按钮的多尺度档：链路里提案框会随瓦片位置在原框基础上晃 ±8%~24%，
    # 而 SCALES/RESCUE 复测本身就在这四个尺度上取 max —— 单看原框 1.000 说明不了稳定性。
    bf = r"D:/tmp/bf/f3.png"
    if os.path.exists(bf):
        full = Image.open(bf).convert("RGB")
        box = (850, 374, 1050, 450)                 # x0,y0,x1,y1
        bx, by = (box[0] + box[2]) // 2, (box[1] + box[3]) // 2
        arrs = []
        for k in (0.0, -0.08, 0.08, 0.16, 0.24):
            hw, hh = int((box[2] - box[0]) * (1 + k) / 2), int((box[3] - box[1]) * (1 + k) / 2)
            b = (max(0, bx - hw), max(0, by - hh),
                 min(full.width, bx + hw), min(full.height, by + hh))
            arrs.append(G.to_input(np.asarray(full.crop(b), np.uint8)))
        out["real_bf"] = np.stack(arrs)
    return out


class _ForceScale:
    """给 degrade 用的固定倍数 rng（只需要 .choice）。"""
    def __init__(self, s):
        self.s = s

    def choice(self, arr):
        return self.s


@torch.no_grad()
def evaluate(net, val, dev, thr=0.9):
    net.eval()
    rep = {}
    for k, X in val.items():
        t = torch.from_numpy(X).to(dev)
        p = torch.softmax(net(t), 1)[:, 1].cpu().numpy()
        if k in ("neg", "bg", "rtneg"):
            rep[k] = dict(max=float(p.max()), fpr=float((p >= thr).mean()))
        else:
            rep[k] = dict(min=float(p.min()), mean=float(p.mean()),
                          recall=float((p >= thr).mean()))
    net.train()
    return rep


def score_of(rep):
    """选模型的目标：大间距召回 + 低误报，给真机留一点权重。"""
    hard = np.mean([rep[f"gap{g}"]["recall"] for g in (0.5, 0.75, 1.0)])
    soft = np.mean([rep[f"gap{g}"]["recall"] for g in (0.0, 0.1, 0.25)])
    fpr = max(rep["neg"]["fpr"], rep.get("bg", {}).get("fpr", 0.0),
              rep.get("rtneg", {}).get("fpr", 0.0))
    # 真机两个口径取最差：紧框容易满分，松框才是链路真实面对的框
    real = min(rep.get("real", {}).get("min", 1.0),
               rep.get("real_loose", {}).get("min", 1.0))
    # 松框权重要压过 FPR：0.4/6.0 的旧配比下，「松框 0.99→0.63」只丢 0.14 分，
    # 而「FPR 0.042→0.030」能赚 0.07 —— 第 30 轮就选出了一个松框只有 0.63 的轮次，
    # 链路三张图虽然都过，但那是这批框刚好紧，换台机器就漏检。漏检和误点一样致命，
    # 所以 real 提到 1.5、FPR 降到 4.0。
    return 2.0 * hard + 0.6 * soft + 1.5 * min(real, 1.0) - 4.0 * fpr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--init", default=r"D:/tmp/skip_probe/skipnet_model_torchv5w2/skipnet.pt",
                    help="warm start 权重；空字符串 = 从零训")
    ap.add_argument("--raw", default=r"D:/tmp/skip_probe/byfen_live2")
    ap.add_argument("--extra", default=r"D:/tmp/skip_probe/byfen_live")
    ap.add_argument("--out", default=r"D:/tmp/skipnet_glyph")
    ap.add_argument("--steps", type=int, default=3000)
    ap.add_argument("--bs", type=int, default=64)
    ap.add_argument("--lr", type=float, default=6e-4)
    ap.add_argument("--seed", type=int, default=20261029)
    ap.add_argument("--eval-every", type=int, default=250)
    ap.add_argument("--workers", type=int, default=8,
                    help="合成进程数；1 = 就地串行（调试用），0 = cpu-2")
    ap.add_argument("--cpu-threads", type=int, default=2,
                    help="留给 torch 的 CPU 线程数（进程池已经吃满核，别抢）")
    a = ap.parse_args()
    if a.workers <= 0:
        a.workers = max(1, min(8, (os.cpu_count() or 4) - 2))
    torch.set_num_threads(a.cpu_threads)

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    rng = np.random.default_rng(a.seed)
    # 全部用 spawn 之前先建池：worker 各自载帧（150 MB/个），这里不再在主进程留一份
    sampler = PS.Sampler(a.raw, a.extra, workers=a.workers, seed=a.seed)
    print(f"[pool] {a.workers} 进程", flush=True)

    net = SkipNet(width=2, head="meanmax").to(dev)
    n = sum(p.numel() for p in net.parameters())
    if a.init and os.path.exists(a.init):
        ck = torch.load(a.init, map_location="cpu", weights_only=False)
        sd = ck["state_dict"] if "state_dict" in ck else ck
        missing, unexpected = net.load_state_dict(sd, strict=True), None
        print(f"[init] warm start {a.init} params={n}", flush=True)
    else:
        print(f"[init] 从零 params={n}", flush=True)

    print("[val] 造固定验证集…", flush=True)
    t0 = time.time()
    val = build_val(sampler, rng)
    print(f"[val] {len(val)} 组，{time.time() - t0:.1f}s", flush=True)
    rep0 = evaluate(net, val, dev)
    print("[init] " + " ".join(
        f"{k}={'%.2f' % v.get('recall', v.get('fpr'))}" for k, v in rep0.items()
        if k.startswith("gap") or k == "neg"), flush=True)

    opt = torch.optim.AdamW(net.parameters(), lr=a.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=a.steps)

    best = (-1e9, None, -1)
    os.makedirs(a.out, exist_ok=True)
    real = val.get("real")
    # 预取：先把第 0 批压给进程池，循环里「取当前批 → 立刻提交下一批 → 训练」，
    # 这样 CPU 合成和 GPU 前反向是重叠的，不再一步一等。
    specs, _ = make_plan(rng, a.bs, real)
    sampler.submit([s for s in specs if s is not None])
    tTrain = time.time()
    for step in range(a.steps):
        Xp, yp = sampler.take()
        X, y = assemble(Xp, yp, specs, real, rng)
        specs, _ = make_plan(rng, a.bs, real)
        sampler.submit([s for s in specs if s is not None])
        xb = torch.from_numpy(X).to(dev, non_blocking=True)
        yb = torch.from_numpy(y).to(dev, non_blocking=True)
        opt.zero_grad(set_to_none=True)
        loss = F.cross_entropy(net(xb), yb)
        loss.backward()
        opt.step()
        sched.step()

        if (step + 1) % a.eval_every == 0 or step == a.steps - 1:
            rep = evaluate(net, val, dev)
            s = score_of(rep)
            hard = np.mean([rep[f"gap{g}"]["recall"] for g in (0.5, 0.75, 1.0)])
            print(f"[{step + 1:04d}] loss={float(loss):.4f} score={s:.3f} "
                  f"recall@0.9: gap0={rep['gap0.0']['recall']:.3f} "
                  f"gap0.5={rep['gap0.5']['recall']:.3f} gap1.0={rep['gap1.0']['recall']:.3f} "
                  f"hard={hard:.3f} "
                  f"neg_fpr={rep['neg']['fpr']:.4f} neg_max={rep['neg']['max']:.3f} "
                  f"bg_fpr={rep.get('bg', {}).get('fpr', -1):.4f} "
                  f"bg_max={rep.get('bg', {}).get('max', -1):.3f} "
                  f"real_min={rep.get('real', {}).get('min', -1):.3f} "
                  f"loose={rep.get('real_loose', {}).get('min', -1):.3f} "
                  f"lo3={rep.get('real_lo3', {}).get('min', -1):.3f} "
                  f"bf={rep.get('real_bf', {}).get('min', -1):.3f} "
                  f"({time.time() - t0:.0f}s, "
                  f"{(step + 1) * a.bs / max(1e-6, time.time() - tTrain):.0f} 样本/s)", flush=True)
            if s > best[0]:
                best = (s, {k: v.detach().cpu().clone() for k, v in net.state_dict().items()}, step + 1)

    print(f"\n[best] score={best[0]:.3f} at step {best[2]}", flush=True)
    net.load_state_dict(best[1])
    rep = evaluate(net, val, dev)

    print("\n[gap 分档 @0.9]  (recall / mean p)")
    for g in (0.0, 0.1, 0.25, 0.5, 0.75, 1.0):
        r = rep[f"gap{g}"]
        print(f"  gap={g:<5.2f} recall={r['recall']:.3f}  mean_p={r['mean']:.3f}  min_p={r['min']:.3f}")
    print("[胶囊底 alpha 分档 @0.9]")
    for al in (0.15, 0.3, 0.5, 0.7, 0.9):
        r = rep[f"alpha{al}"]
        print(f"  alpha={al:<4.2f} recall={r['recall']:.3f}  mean_p={r['mean']:.3f}")
    print(f"[负样本] FPR@0.9={rep['neg']['fpr']:.4f}  max_p={rep['neg']['max']:.3f}")
    if "bg" in rep:
        print(f"[真机背景块] FPR@0.9={rep['bg']['fpr']:.4f}  max_p={rep['bg']['max']:.3f}")
    if "real" in rep:
        print(f"[真机紧框] min_p={rep['real']['min']:.3f} mean_p={rep['real']['mean']:.3f}")
    if "real_loose" in rep:
        print(f"[真机松框 +10%/边] min_p={rep['real_loose']['min']:.3f} "
              f"mean_p={rep['real_loose']['mean']:.3f}")
    if "real_lo3" in rep:
        print(f"[真机紧框 1/3 压缩] min_p={rep['real_lo3']['min']:.3f} "
              f"mean_p={rep['real_lo3']['mean']:.3f}")
    if "real_bf" in rep:
        print(f"[百分网胶囊 ±尺度] min_p={rep['real_bf']['min']:.3f} "
              f"mean_p={rep['real_bf']['mean']:.3f} "
              f"recall@0.9={rep['real_bf']['recall']:.3f}")

    sd = {k: v.detach().cpu() for k, v in net.state_dict().items()}
    sampler.close()
    order = list(sd.keys())
    torch.save({"state_dict": sd, "params": n, "width": 2, "head": "meanmax",
                "arch": "conv3x3-32-mp|conv3x3-48-mp|conv3x3-64-meanmax|fc-128-2",
                "input": {"w": IN_W, "h": IN_H, "ch": 3,
                          "pre": "per-image per-channel (x/255-mean)/std"},
                "train": {"kind": "online-glyph-synth", "steps": a.steps, "bs": a.bs,
                          "seed": a.seed, "init": a.init}},
               os.path.join(a.out, "skipnet.pt"))
    with open(os.path.join(a.out, "skipnet_f32.bin"), "wb") as fh:
        for k in order:
            fh.write(sd[k].numpy().astype("<f4").tobytes())
    with open(os.path.join(a.out, "model.json"), "w", encoding="ascii") as fh:
        json.dump({"params": int(n), "order": order, "head": "meanmax", "width": 2,
                   "input": {"w": IN_W, "h": IN_H, "ch": 3},
                   "classes": {"1": "skip"},
                   "val": rep}, fh, indent=2)
    print(f"\n[export] {a.out} bin="
          f"{os.path.getsize(os.path.join(a.out, 'skipnet_f32.bin'))} B")
    return 0


if __name__ == "__main__":
    sys.exit(main())
