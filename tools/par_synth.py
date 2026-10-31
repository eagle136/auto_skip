"""多进程样本合成：主进程排采样计划，worker 只负责「画图 + 缩放成模型输入」。

为什么拆成计划/执行两段：
  GPU 上这个 42k 参数的小模型一步只要几毫秒，而合成一个 96×32 样本要 1.7 ms（PIL 画字 +
  下采样 + 标准化）。串行时 bs=64 就是 110 ms/步，GPU 全程空转。拆开之后主进程只做
  「按配比决定这一格是正样本还是负样本」（微秒级），把具体绘制丢给进程池，并且**提前提交
  下一步**，让 CPU 合成与 GPU 前反向重叠。

为什么用进程而不是线程：
  ImageDraw.text 在 C 层持 GIL，线程池对这条链路几乎没加速；numpy 那部分虽然放 GIL，
  但整体只有 5–10% 能并行。进程池则是线性扩展（12 核机器上 8 个 worker 约 6–7 倍）。

内存：每个 worker 各自加载一份背景帧（20 帧 1080×2352 ≈ 150 MB），8 worker ≈ 1.2 GB。
帧不通过 pickle 传（那会每次复制 150 MB），只传 raw/extra 目录路径，worker 自己读。
"""
import os
import sys
import multiprocessing as mp
from concurrent.futures import ProcessPoolExecutor

import numpy as np

_HERE = os.path.dirname(os.path.abspath(__file__))
_W = {}          # worker 进程内的全局：{"G": glyph_synth, "frames": [...]}


def _worker_init(raw, extra):
    if _HERE not in sys.path:
        sys.path.insert(0, _HERE)
    import glyph_synth as G
    _W["G"] = G
    _W["frames"] = G.load_frames(raw, extra)


def _worker_run(job):
    """job = (seed, [spec...])，spec = (kind, gap, alpha)。返回 (X, y)。"""
    seed, specs = job
    G = _W["G"]
    frames = _W["frames"]
    rng = np.random.default_rng(seed)
    X = np.empty((len(specs), 3, G.IN_H, G.IN_W), np.float32)
    y = np.empty(len(specs), np.int64)
    for i, (kind, gap, alpha) in enumerate(specs):
        if kind == "pos":
            arr, _ = G.make_positive(rng, frames, gap_scale=gap, cap_alpha=alpha)
            y[i] = 1
        elif kind == "bg":
            # 纯真机背景块，标负。合成负样本再多也画不出壁纸/大标题那种纹理，
            # 不加这一路，真机帧上的 max_p 会从 0.000 爬到 0.86（离 0.9 只差 0.04）。
            arr = G.pick_background(rng, frames)
            y[i] = 0
        elif kind == "hard":
            arr, _ = G.make_hard_positive(rng, frames)
            y[i] = 0
        elif kind == "status":
            arr = G.make_status_neg(rng, frames)
            y[i] = 0
        elif kind == "p_alpha":
            arr, _ = G.make_translucent_positive(rng, frames)
            y[i] = 1
        elif kind == "p_loose_syn":
            arr, _ = G.make_loose_positive(rng, frames)
            y[i] = 1
        elif kind == "p_wall":
            arr, _ = G.make_wall_positive(rng, frames)
            y[i] = 1
        elif kind == "n_wall":
            arr, _ = G.make_wall_negative(rng, frames)
            y[i] = 0
        elif kind == "bg_wall":
            arr = G.make_wall_bg(rng)
            y[i] = 0
        else:
            arr, _ = G.make_negative(rng, frames)
            y[i] = 0
        X[i] = G.to_input(arr)
    return X, y


def _worker_init_gen(raw, extra, real_boxes):
    """生图池的 worker 初始化：除了背景帧，再各载一份真机胶囊整图（防遗忘回放用）。"""
    _worker_init(raw, extra)
    _W["G"].load_wallpapers()        # 彩色壁纸也在这里预载，别让第一次裁块付 IO 成本
    _W["G"].load_ui()                # 真机界面截图（负样本），5 张 1080×2352 ≈ 30 MB/进程
    from PIL import Image
    real = []
    for path, box in (real_boxes or ()):
        if not os.path.exists(path):
            continue
        try:
            im = Image.open(path).convert("RGB")
        except Exception:
            continue
        real.append((np.asarray(im, np.uint8), tuple(int(v) for v in box)))
    _W["real"] = real


def _worker_gen_files(job):
    """job = (seed, outdir, [(kind, idx), ...])；worker 自己落盘，只回传路径。

    不回传数组：一轮 500 张原图约 25 MB，走 pickle 回主进程再写盘等于多拷一遍，
    而且落盘本来就能在 worker 里并行（Pillow 的 PNG 编码在 C 层不持 GIL）。
    """
    seed, outdir, specs = job
    G = _W["G"]
    frames = _W["frames"]
    real = _W.get("real") or []
    rng = np.random.default_rng(seed)
    from PIL import Image
    out = []
    for kind, idx in specs:
        if kind == "pos":
            arr, _ = G.make_positive(rng, frames)
        elif kind == "p_real" and real:
            full, box = real[int(rng.integers(0, len(real)))]
            arr = G.make_real_positive(rng, full, box)
        elif kind == "p_loose" and real:
            # 链路级松框：每边多 2–13% 宽 / 6–26% 高，从整图裁（带上邻居内容）
            full, box = real[int(rng.integers(0, len(real)))]
            arr = G.make_real_loose(rng, full, box)
        elif kind == "p_alpha":
            # 胶囊底色全透明度谱：无底白字 / 描边 / 四档半透明到实心
            arr, _ = G.make_translucent_positive(rng, frames)
        elif kind == "p_loose_syn":
            # 合成松框：ttf 渲染 + 边距 10–40%/边（真机只有 3 枚胶囊，扩不出多样性）
            arr, _ = G.make_loose_positive(rng, frames)
        elif kind == "p_wall":
            # 彩色壁纸底 + 「跳过」：补「高饱和背景」这一整段分布（用户报的漏检场景）
            arr, _ = G.make_wall_positive(rng, frames)
        elif kind == "n_wall":
            # 同一套彩色底 + 错文案：不配这一路，模型会学成「底一彩色就答 1」
            arr, _ = G.make_wall_negative(rng, frames)
        elif kind == "bg_wall":
            arr = G.make_wall_bg(rng)                    # 纯彩色背景块，标负
        elif kind == "p_flat":
            # 纯色/近纯色底 + 「跳过」：真机广告最常见的底型，含亮底低对比档
            arr, _ = G.make_flat_positive(rng, frames)
        elif kind == "n_flat":
            arr, _ = G.make_flat_negative(rng, frames)
        elif kind == "bg_flat":
            arr = G.make_flat_bg(rng)                    # 纯色背景块，标负
        elif kind == "p_round":
            # 圆形按钮（框是正方、字只占中间一条）：用户 10-29 截图实测漏检
            arr, _ = G.make_round_positive(rng, frames)
        elif kind == "n_round":
            arr, _ = G.make_round_negative(rng, frames)
        elif kind == "n_loose":
            # 超松框 + 错文案：p_loose2 的对偶，防「框里字小 = 跳过」
            arr, _ = G.make_loose_negative(rng, frames)
        elif kind == "n_shape":
            # 无字几何图形：防「深色底 + 亮元素 = 跳过」在系统界面上乱点
            arr = G.make_shape_negative(rng, frames)
        elif kind == "n_blank":
            # 同形状抹字硬负样本：底和胶囊与正样本完全相同，只去掉「跳过」两个字
            arr, _ = G.make_blank_negative(rng, frames)
        elif kind == "n_ui":
            # 真机界面截图裁块：深色卡片 / 大号时钟 / 百分比，压「见深底就点」
            arr = G.make_ui_negative(rng)
        elif kind == "p_loose2":
            # 超松框：字占框高 28–50%，链路提案框就是这种
            arr, _ = G.make_loose2_positive(rng, frames)
        elif kind == "bg":
            arr = G.degrade(G.pick_background(rng, frames), rng)   # 纯背景块，标负
        elif kind == "rtneg":
            # 真机高纹理块：真机里的「微信」「加速版」「0.1折」这类标题就长这样。
            # 合成假样本画不出这种字，而模型最容易被它们骗（实测把「微信」判成 1.000）。
            arr = G.degrade(G.pick_background(rng, frames, busy=True), rng)
        elif kind == "status":
            # 状态栏条带（月亮 / 5G / 信号条）：两三个并排亮字形，最像胶囊的假阳性源
            arr = G.make_status_neg(rng, frames)
        elif kind == "hard":
            arr, _ = G.make_hard_positive(rng, frames)
        else:
            arr, _ = G.make_negative(rng, frames)
        tag = "p" if kind in ("pos", "p_real", "p_loose", "p_alpha", "p_loose_syn",
                              "p_wall", "p_flat", "p_round", "p_loose2") else "n"
        p = os.path.join(outdir, f"{tag}{idx:05d}_{kind}.png")
        Image.fromarray(np.ascontiguousarray(arr)).save(p)
        out.append(p)
    return out


class GenPool:
    """跨轮复用的生图池：每轮只提交配比计划，worker 各自画图 + 落盘。

    池必须跨轮复用 —— 每个 worker 启动要载 20 帧真机背景（150 MB）+ 3 张胶囊整图，
    一轮一建池等于每轮多花 1–2 s/worker 的预热，20 轮就是白扔半分钟。
    """

    def __init__(self, raw, extra, real_boxes, workers=12, seed=0):
        self.workers = max(1, int(workers))
        self.seed = int(seed)
        self._n = 0
        if self.workers == 1:
            _worker_init_gen(raw, extra, real_boxes)
            self.pool = None
        else:
            ctx = mp.get_context("spawn")
            self.pool = ProcessPoolExecutor(max_workers=self.workers, mp_context=ctx,
                                            initializer=_worker_init_gen,
                                            initargs=(raw, extra, real_boxes))

    def run(self, outdir, kinds, seed_base=None):
        """kinds = [(kind, count), ...]；返回本轮落盘的路径列表。

        `seed_base` 给定时用固定种子（评估集要跨版本可比：同一份图、同一份标签）。
        """
        specs, i = [], 0
        for kind, cnt in kinds:
            for _ in range(int(cnt)):
                specs.append((kind, i))
                i += 1
        if not specs:
            return []
        chunks = _split(specs, min(self.workers, len(specs)))
        self._n += 1
        base = self.seed + self._n * 1000 if seed_base is None else int(seed_base)
        jobs = [(base + j, outdir, c) for j, c in enumerate(chunks)]
        if self.pool is None:
            parts = [_worker_gen_files(j) for j in jobs]
        else:
            parts = list(self.pool.map(_worker_gen_files, jobs))
        return [p for part in parts for p in part]

    def close(self):
        if self.pool is not None:
            self.pool.shutdown(wait=True, cancel_futures=True)
            self.pool = None


def _split(specs, n):
    """把列表切成长度尽量均匀的 n 段。"""
    out, k, m = [], len(specs) // n, len(specs) % n
    i = 0
    for j in range(n):
        sz = k + (1 if j < m else 0)
        if sz:
            out.append(specs[i:i + sz])
        i += sz
    return out


class Sampler:
    """带一步预取的进程池采样器。

    用法：
        s = Sampler(raw, extra, workers=8, seed=0)
        s.submit(plan)          # 第一批
        for step in range(steps):
            X, y = s.take()     # 取当前批
            s.submit(next_plan) # 立刻把下一批压给 worker
            train(X, y)
        s.close()
    """

    def __init__(self, raw, extra, workers=8, seed=0):
        self.workers = max(1, int(workers))
        self.seed = int(seed)
        self._n = 0
        self._pending = None
        if self.workers == 1:
            _worker_init(raw, extra)          # 单进程：就地初始化，省掉池的开销
            self.pool = None
        else:
            ctx = mp.get_context("spawn")     # Windows 只有 spawn；显式写死不依赖默认值
            self.pool = ProcessPoolExecutor(max_workers=self.workers, mp_context=ctx,
                                            initializer=_worker_init,
                                            initargs=(raw, extra))

    def submit(self, specs):
        """提交一批采样计划（spec 列表），返回 None。"""
        chunks = _split(specs, self.workers)
        seeds = [self.seed + self._n * 1000 + i for i in range(len(chunks))]
        self._n += 1
        if self.pool is None:
            self._pending = ("local", [(s, c) for s, c in zip(seeds, chunks)])
        else:
            self._pending = ("pool", [self.pool.submit(_worker_run, (s, c))
                                      for s, c in zip(seeds, chunks)])

    def take(self):
        """等当前批全部就绪并按计划顺序拼回去。"""
        kind, jobs = self._pending
        self._pending = None
        if kind == "local":
            parts = [_worker_run(j) for j in jobs]
        else:
            parts = [f.result() for f in jobs]
        return np.concatenate([p[0] for p in parts]), np.concatenate([p[1] for p in parts])

    def map(self, spec_groups):
        """一次性跑完多组（验证集构建用），返回与输入等长的数组列表。"""
        flat, sizes = [], []
        for g in spec_groups:
            sizes.append(len(g))
            flat.extend(g)
        chunks = _split(flat, self.workers * 2)
        seeds = [self.seed + 777000 + i for i in range(len(chunks))]
        if self.pool is None:
            parts = [_worker_run(j) for j in zip(seeds, chunks)]
        else:
            parts = list(self.pool.map(_worker_run, list(zip(seeds, chunks))))
        X = np.concatenate([p[0] for p in parts])
        y = np.concatenate([p[1] for p in parts])
        out, i = [], 0
        for n in sizes:
            out.append((X[i:i + n], y[i:i + n]))
            i += n
        return out

    def close(self):
        if self.pool is not None:
            self.pool.shutdown(wait=True, cancel_futures=True)
            self.pool = None


def _selftest(n=400, workers=8):
    """基准：同样 400 个样本，串行 vs 进程池。"""
    import time
    raw = r"D:/tmp/skip_probe/byfen_live2"
    extra = r"D:/tmp/skip_probe/byfen_live"
    rng = np.random.default_rng(0)
    specs = []
    for _ in range(n):
        if rng.random() < 0.6:
            specs.append(("pos", float(rng.choice([0.0, 0.25, 0.5, 1.0])), None))
        else:
            specs.append(("neg" if rng.random() < 0.5 else "hard", None, None))

    t = time.time()
    _worker_init(raw, extra)
    for c in _split(specs, workers):
        _worker_run((1, c))
    serial = time.time() - t

    s = Sampler(raw, extra, workers=workers, seed=1)
    s.submit(specs)
    s.take()                                   # 预热（等 worker 起来 + 各自载帧）
    t = time.time()
    for _ in range(5):
        s.submit(specs)
        X, y = s.take()
    par = (time.time() - t) / 5
    s.close()
    print(f"串行 {serial / n * 1000:.2f} ms/样本 ({serial:.2f}s / {n})")
    print(f"池 x{workers} {par / n * 1000:.2f} ms/样本 ({par:.2f}s / {n})  "
          f"加速 {serial / par:.2f}x  X={X.shape} y_mean={y.mean():.2f}")


if __name__ == "__main__":
    _selftest(int(sys.argv[1]) if len(sys.argv) > 1 else 400,
              int(sys.argv[2]) if len(sys.argv) > 2 else 8)
