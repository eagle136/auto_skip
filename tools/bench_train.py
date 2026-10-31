"""吞吐/显存基准：固定训练通路，只变 worker 数与 batch size。

GPU 侧这个模型只有 42,738 参数，单步算力开销可以忽略（kernel launch 主导），
所以瓶颈永远是 CPU 合成。测三件事：
  ① workers 从 8 往上还给不给加速（超订阅有没有意义）；
  ② bs 变大能不能把「一步要等一批」的流水线气泡摊薄；
  ③ 显存峰值（用户上限 15 GB）。
"""
import argparse
import os
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from train_skip_torch import SkipNet  # noqa: E402
import par_synth as PS  # noqa: E402
import self_train_glyphs as T  # noqa: E402


def run(workers, bs, steps, raw, extra, dev="cuda"):
    rng = np.random.default_rng(7)
    sampler = PS.Sampler(raw, extra, workers=workers, seed=7)
    net = SkipNet(width=2, head="meanmax").to(dev)
    opt = torch.optim.AdamW(net.parameters(), lr=1e-3)

    def one(step):
        specs, _ = T.make_plan(rng, bs, None)
        sampler.submit([s for s in specs if s is not None])
        X, y = sampler.take()
        xb = torch.from_numpy(X).to(dev, non_blocking=True)
        yb = torch.from_numpy(y).to(dev, non_blocking=True)
        opt.zero_grad(set_to_none=True)
        F.cross_entropy(net(xb), yb).backward()
        opt.step()

    for _ in range(5):                 # 预热：等 worker 起进程、各自载帧
        one(0)
    if dev == "cuda":
        torch.cuda.synchronize()
        torch.cuda.reset_peak_memory_stats()

    t = time.time()
    for i in range(steps):
        one(i)
    if dev == "cuda":
        torch.cuda.synchronize()
    dt = time.time() - t
    peak = torch.cuda.max_memory_allocated() / 2 ** 30 if dev == "cuda" else 0.0
    sampler.close()
    return steps * bs / dt, peak, dt / steps * 1000


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", default=r"D:/tmp/skip_probe/byfen_live2")
    ap.add_argument("--extra", default=r"D:/tmp/skip_probe/byfen_live")
    ap.add_argument("--steps", type=int, default=80)
    ap.add_argument("--cpu-threads", type=int, default=2)
    a = ap.parse_args()
    torch.set_num_threads(a.cpu_threads)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    name = torch.cuda.get_device_name(0) if dev == "cuda" else "cpu"
    total = torch.cuda.get_device_properties(0).total_memory / 2 ** 30 if dev == "cuda" else 0
    print(f"[gpu] {name} {total:.1f} GB  可用核数 {os.cpu_count()}", flush=True)
    print(f"{'workers':>8} {'bs':>6} {'样本/s':>9} {'ms/步':>8} {'peak GB':>8}", flush=True)
    for workers, bs in [(8, 64), (12, 64), (12, 256), (24, 256), (12, 1024),
                        (24, 1024), (48, 1024)]:
        try:
            sps, peak, ms = run(workers, bs, a.steps, a.raw, a.extra, dev)
            print(f"{workers:>8} {bs:>6} {sps:>9.0f} {ms:>8.1f} {peak:>8.2f}", flush=True)
        except Exception as e:                       # 超订阅撞到资源上限就跳过
            print(f"{workers:>8} {bs:>6}   failed: {type(e).__name__}: {e}", flush=True)


if __name__ == "__main__":
    main()
