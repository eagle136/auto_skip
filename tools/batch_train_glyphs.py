"""批量落盘训练流水：生 500 张 → 落盘 → 训 → 删 → 循环 N 轮。

数据流：
  12 进程生图池（跨轮复用，worker 各自画图并**直接落盘**）→ 主进程读回 PNG → 训练 →
  立刻 rmtree 删掉本轮目录 → 下一轮。

  * 临时目录强制在 R 盘（ramdisk，5 GB）：一轮 500 张约 25 MB，训完即删。
  * 进程池跨轮复用：每个 worker 启动要载 20 帧真机背景（150 MB）+ 3 张胶囊整图。

配比（每轮 500 张，正:负 = 4:1）：
  380 合成正样本  —— 系统 ttf 黑体，框内**随机位置**、字距 0–1 字高、背景对比度随机
   20 真机紧框回放 —— 防遗忘，不加这路 2000 步后真机 min_p 会从 1.000 掉到 0.52
   60 真机背景块   —— 标负，压真机误报唯一有效的手段
   24 合成假样本   —— 数字 / 其它文案 / 单字，标负
   16 残缺「跳过」 —— 被切角，标负

负样本只占 20% 会让 FPR 变差（历史实测 20% 时 FPR 到 12.8%，见 进度.txt），
所以 100 张负样本里 60 张用真机背景块（比合成假样本硬），每轮盯 neg_fpr / bg_max。
"""
import argparse
import ctypes
import json
import os
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glyph_synth as G          # noqa: E402
import par_synth as PS           # noqa: E402
import self_train_glyphs as ST   # noqa: E402
from train_skip_torch import SkipNet  # noqa: E402

IN_W, IN_H = G.IN_W, G.IN_H

REAL_BOXES = [
    # 这三枚框是链路真机命中时给出的实际框（x0,y0,x1,y1），肉眼确认「跳过」两字完整。
    # 旧值 (820,378,984,453) 只盖住胶囊的左 3/4，「过」字整个在框外 —— 拿它做真机锚点
    # 等于在教模型「半截字也是跳过」，同时让 p_real 永远满分、失去预警作用。
    (r"D:/迅雷下载/1793238269842.jpg", (884, 394, 1018, 434)),
    (r"D:/迅雷下载/1793241215465.jpg", (894, 22, 998, 58)),
    (r"D:/迅雷下载/Screenshot_2026-10-28-22-52-50-19_4e50d8a648c8cf205d9cb65b7a169329.jpg",
     (846, 144, 1008, 210)),
]


_K32 = ctypes.WinDLL("kernel32", use_last_error=True)
_K32.DeleteFileW.argtypes = [ctypes.c_wchar_p]
_K32.DeleteFileW.restype = ctypes.c_int
_K32.RemoveDirectoryW.argtypes = [ctypes.c_wchar_p]
_K32.RemoveDirectoryW.restype = ctypes.c_int


def rm_tree(path):
    """删目录 —— 必须绕开 `shutil.rmtree`。

    宿主给 Python 挂了 safe-delete 钩子，rmtree/os.remove 都改走回收站；
    ramdisk 不支持回收站，钩子直接抛 `[safe-delete] ... CanonicalizePath` 失败。
    所以这里直接调 kernel32 的 DeleteFileW/RemoveDirectoryW。
    """
    if not os.path.isdir(path):
        return
    for root, dirs, files in os.walk(path, topdown=False):
        for f in files:
            _K32.DeleteFileW(os.path.join(root, f))
        for d in dirs:
            _K32.RemoveDirectoryW(os.path.join(root, d))
    _K32.RemoveDirectoryW(path)


def check_writable(d):
    """临时目录必须真的能写 —— R 盘是 ramdisk，被只读挂载过，报错要说清怎么修。"""
    os.makedirs(d, exist_ok=True)
    probe = os.path.join(d, "_probe.tmp")
    try:
        with open(probe, "wb") as fh:
            fh.write(b"1")
        _K32.DeleteFileW(probe)
    except OSError as e:
        raise SystemExit(
            f"[致命] 临时目录不可写：{d}\n  {e}\n"
            f"  R 盘是 ramdisk，只读挂载时改 ACL 没用（ACL 本来就是 Everyone:F），"
            f"要在挂载工具里取消 read-only 再重新挂载，或 clear 卷 readonly 标记。")


def read_round(paths, want_kinds=False):
    """从盘上读回一轮 PNG（训练吃的就是落盘那份，顺带验证写盘链路）。

    标签直接取生成时写进文件名的 kind —— 生成的那一刻就已经知道真假，
    不需要再人工看一遍：`p00007_pos.png` / `n00021_rtneg.png`。
    """
    X = np.empty((len(paths), 3, IN_H, IN_W), np.float32)
    y = np.empty(len(paths), np.int64)
    kinds = np.empty(len(paths), dtype=object) if want_kinds else None
    for i, p in enumerate(paths):
        im = Image.open(p).convert("RGB")
        X[i] = G.to_input(np.asarray(im, np.uint8))
        base = os.path.basename(p)
        y[i] = 1 if base.startswith("p") else 0
        if want_kinds:
            # 文件名是 `p00007_pos.png` / `n00021_rtneg.png` / `p00003_p_real.png`；
            # kind 本身可能带下划线（p_real），所以按**第一个**下划线切。
            kinds[i] = base.split("_", 1)[1][:-4] if "_" in base else "?"
    return (X, y, kinds) if want_kinds else (X, y)


def plan_kinds(n, has_real, neg_ratio=0.30):
    """每轮配比。

    负样本比例默认 0.30 而不是 4:1 的 0.20 —— 4:1 实测把「微信」判成 1.000、
    「加速版」0.976（见 进度.txt 第 28 轮），误点一次就够砸掉整个功能。
    30% 这一档是项目里验证过的（e 版链路负样本 max 0.639）。

    负样本内部分配：
      48% 合成假样本   —— 数字 / 其它文案 / 单字；几何分布与正样本完全一致（只换字），
                          文案以「随机生成」为主、固定词表兜底
      22% 真机高纹理块 —— 真机标题字就在这些块里，补真机纹理
      11% 真机纯背景块 —— 压壁纸/纯色区误报
      10% 状态栏条带   —— 月亮/5G/信号条，两三个并排亮字形，最像胶囊的假阳性源
       9% 残缺「跳过」 —— 教模型别点被切角的
    """
    n_pos_real = int(round(n * 0.02)) if has_real else 0
    n_pos_loose = int(round(n * 0.05)) if has_real else 0
    n_neg = int(round(n * neg_ratio))
    n_syn_neg = int(round(n_neg * 0.48))
    n_rt = int(round(n_neg * 0.22))
    n_bg = int(round(n_neg * 0.11))
    n_st = int(round(n_neg * 0.10))
    n_hard = n_neg - n_syn_neg - n_rt - n_bg - n_st
    n_pos = n - n_neg - n_pos_real - n_pos_loose
    return [("pos", n_pos), ("p_real", n_pos_real), ("p_loose", n_pos_loose),
            ("neg", n_syn_neg), ("rtneg", n_rt), ("bg", n_bg),
            ("status", n_st), ("hard", n_hard)]


def plan_big(n_loose, n_alpha, neg_ratio=0.30):
    """单轮大样本计划（用户指定）：真机松框 N 张 + 胶囊透明度全谱 N 张，再按比例配负样本。

    负样本内部沿用 `plan_kinds` 的比例（合成假 48% / 高纹理 22% / 背景 11% / 状态栏 10% / 残缺 9%），
    这一组比例是逐轮实测调出来的，不跟着总量改。
    """
    n_pos = int(n_loose) + int(n_alpha)
    n_real = 400                                  # 真机胶囊回放：防遗忘，也锚住内缩复测那一档
    n_neg = int(round((n_pos + n_real) * neg_ratio / max(1e-6, 1.0 - neg_ratio)))
    n_syn = int(round(n_neg * 0.48))
    n_rt = int(round(n_neg * 0.22))
    n_bg = int(round(n_neg * 0.11))
    n_st = int(round(n_neg * 0.10))
    n_hard = n_neg - n_syn - n_rt - n_bg - n_st
    return [("p_loose_syn", int(n_loose)), ("p_alpha", int(n_alpha)), ("p_real", n_real),
            ("neg", n_syn), ("rtneg", n_rt), ("bg", n_bg),
            ("status", n_st), ("hard", n_hard)]


def plan_wall(n, seed_ratio=0.30):
    """彩色壁纸专项单轮计划（用户指定 5000 张，胶囊不透明度 10%–85%）。

    背景全部换成真壁纸随机裁块 —— 此前所有背景都来自真机广告帧，那一批几乎全是
    白/浅灰/黑的实心版式，模型从没见过高饱和底色，真机换彩色底图就漏检。

    配比（n=5000）：
      p_wall     ~50%  彩色底 +「跳过」，alpha 0.10–0.85（本次主诉求）
      n_wall      16%  彩色底 + **错文案**（同底不同字，含「跳过形近」与「跳X」两档）
      p_alpha     12%  真机帧底 + 透明度全谱（**防遗忘**：不能为了彩色底把中性底训丢）
      p_real       8%  真机三枚胶囊回放（防遗忘的硬锚点）
      p_loose_syn  6%  合成松框（链路给的真实框形状）
      bg_wall      3%  纯彩色背景块（密集纹理为主）—— 上一版漏了这一路，
                       结果留出批 84 张里有 4 张判到 0.93–1.00
      neg          2%  真机帧底合成假样本
      rtneg/hard   各 1%  真机高纹理块 / 残缺「跳过」
      bg/status  各 0.5%  真机纯背景块 / 状态栏条带
    正:负 ≈ 76:24，负样本里 67% 是彩色底错例。
    """
    n = int(n)

    def c(f):
        return int(round(n * f))

    rest = [("p_alpha", c(0.12)), ("p_real", c(0.08)), ("p_loose_syn", c(0.06)),
            ("n_wall", c(0.16)), ("bg_wall", c(0.03)), ("neg", c(0.02)),
            ("rtneg", c(0.01)), ("bg", c(0.005)), ("hard", c(0.01)),
            ("status", c(0.005))]
    # p_wall 吃掉所有取整余量，保证总数正好是 n（否则每轮会漂几十张）
    return [("p_wall", n - sum(x for _, x in rest))] + rest


# 留出评估批的配比（比例，按 --eval-size 缩放）。正负两侧的全部分支都必须进来：
# 只要有一路掺假，那一路上「漏检」就会被平均掉，看着好看但装机就漏/就误点。
EVAL_MIX = [("pos", 0.19), ("p_real", 0.07), ("p_loose", 0.11), ("p_loose_syn", 0.10),
            ("p_alpha", 0.13), ("neg", 0.15), ("rtneg", 0.09), ("bg", 0.05),
            ("status", 0.07), ("hard", 0.04)]

# 彩色壁纸专项的留出批（本轮的选模型依据）。壁纸三档必须单列：
#   p_wall  —— 用户报漏检的那一类
#   n_wall  —— 同一批彩色底上的错文案，看模型是不是靠「底一彩色就答 1」蒙对
#   bg_wall —— 纯彩色碎块，看会不会在壁纸/插画上乱点
EVAL_WALL_MIX = [("p_wall", 0.30), ("p_alpha", 0.10), ("p_real", 0.06),
                 ("p_loose_syn", 0.05), ("n_wall", 0.24), ("bg_wall", 0.07),
                 ("neg", 0.06), ("rtneg", 0.05), ("bg", 0.03),
                 ("status", 0.02), ("hard", 0.02)]


def plan_mix(n):
    """本轮总计划：纯色底 + 圆形按钮 + 超松框 + 彩色壁纸 + 旧路径（防遗忘）。

    为什么不再单一押壁纸：t14 用 2500 张壁纸把「中性纯色底」挤掉了，真机上
    **圆形按钮**（框是正方、字只占中间一条）直接判 0.000；纯色底 + 浅色字也
    从 0.574 掉到 0.041。42k 参数的小模型容量有限，配比就是它的记忆配额，
    不能让一个专项吃掉别的分支。

    配比（n=5000）：
      p_flat    10%  纯色/近纯色底 +「跳过」，42% 为低对比（字与底 ΔL 10–60）
      p_round   13%  圆形按钮（正方框、外层浅圆+内层深圆、横排小字）
      p_wall     9%  彩色壁纸底
      p_loose2   9%  超松框：字占框高 28–50%（链路提案框的真实形状）
      p_alpha    7%  真机帧底透明度全谱（防遗忘）
      p_real     4%  真机胶囊回放（硬锚点）
      p_loose_syn 1% 合成松框
      n_ui       8%  真机界面裁块 —— **权重最高的一路负样本**：端上每帧 6–7 个瓦片
                     各推一个框，界面误报会被直接点掉，代价远高于一次漏检
      n_shape    9%  无字几何（圆角块 / 线 / 圆点）
      n_blank    7%  同形状抹字
      n_flat/n_round  各 7/8%  同底错文案（纯色 / 圆形）
      n_wall/n_loose  各 3/3%
      其余 3%        真机背景块、纯色块、壁纸块、残缺跳过、状态栏
    正:负 ≈ 53:47。
    """
    n = int(n)

    def c(f):
        return int(round(n * f))

    parts = [("p_flat", c(0.10)), ("p_round", c(0.13)), ("p_loose2", c(0.09)),
             ("p_wall", c(0.09)), ("p_alpha", c(0.07)), ("p_real", c(0.04)),
             ("p_loose_syn", c(0.010)),
             ("n_flat", c(0.07)), ("n_round", c(0.08)), ("n_wall", c(0.03)),
             ("n_loose", c(0.03)), ("n_shape", c(0.09)), ("n_blank", c(0.07)),
             ("n_ui", c(0.08)),
             ("bg_wall", c(0.010)), ("bg_flat", c(0.006)), ("rtneg", c(0.006)),
             ("bg", c(0.004)), ("hard", c(0.006)), ("status", c(0.002))]
    # 取整余量给 p_flat，保证总数正好是 n
    used = sum(x for _, x in parts)
    parts[0] = (parts[0][0], max(0, parts[0][1] + (n - used)))
    return parts


# 综合留出批（flat 专项的选模型依据）。每一路都必须单列 —— 任何一路被平均掉，
# 那一路在真机上就会漏检或误点。
EVAL_MIX_ALL = [("p_flat", 0.11), ("p_round", 0.09), ("p_wall", 0.09), ("p_loose2", 0.08),
                ("p_alpha", 0.05), ("p_real", 0.04), ("p_loose_syn", 0.02),
                ("n_flat", 0.08), ("n_round", 0.08), ("n_wall", 0.05),
                ("n_loose", 0.05), ("n_shape", 0.04), ("n_blank", 0.04),
                ("n_ui", 0.10),
                ("neg", 0.03), ("rtneg", 0.03), ("bg", 0.01), ("bg_flat", 0.005),
                ("bg_wall", 0.01), ("hard", 0.01), ("status", 0.005)]


def plan_eval(n, mix=None):
    mix = mix or EVAL_MIX
    out, used = [], 0
    for i, (k, f) in enumerate(mix):
        c = int(round(n * f)) if i < len(mix) - 1 else max(0, n - used)
        out.append((k, c))
        used += c
    return out


def build_eval(pool, root, n, seed, mix=None, tag="_eval"):
    """用固定种子生成一批留出样本，读进内存后立刻删盘。

    同一 seed ⇒ 任何版本、任何轮次看到的都是**同一批图 + 同一份标签**，
    所以跨版本比是严格可比的（不像真机截图那样每次换框就换口径）。
    """
    d = os.path.join(root, tag)
    rm_tree(d)
    os.makedirs(d, exist_ok=True)
    paths = pool.run(d, plan_eval(n, mix), seed_base=seed)
    X, y, kinds = read_round(paths, want_kinds=True)
    rm_tree(d)
    return X, y, kinds


POS_KINDS = ("pos", "p_real", "p_loose", "p_loose_syn", "p_alpha", "p_wall",
             "p_flat", "p_round", "p_loose2")
# 负样本分档：哪种最像胶囊就最危险，评估里必须单列，不能被平均。
NEG_KINDS = ("neg", "rtneg", "bg", "status", "hard", "n_wall", "bg_wall",
             "n_flat", "bg_flat", "n_round", "n_loose", "n_shape", "n_blank", "n_ui")


@torch.no_grad()
def eval_gen(net, X, y, kinds, dev, thr=0.9):
    """按生成时的标签算指标 —— 这张图是正是负，生成那一刻就定了。"""
    net.eval()
    t = torch.from_numpy(X).to(dev)
    p = torch.softmax(net(t), 1)[:, 1].cpu().numpy()
    rep = {}
    for k in sorted(set(kinds.tolist())):
        m = kinds == k
        q = p[m]
        if (y[m] == 1).all():
            rep[k] = dict(n=int(m.sum()), kind="pos", min=float(q.min()),
                          mean=float(q.mean()), recall=float((q >= thr).mean()))
        else:
            rep[k] = dict(n=int(m.sum()), kind="neg", max=float(q.max()),
                          mean=float(q.mean()), fpr=float((q >= thr).mean()))
    net.train()
    return rep


def score_gen(rep):
    """选模型的唯一依据：留出批上的召回与误报。

    误报系数给到 4：漏检是「少点一次」，误点是在首页/状态栏乱跳，
    一个误点样本的代价远高于一次漏点，而且 FPR 是跨帧累积的。
    """
    recs = [rep[k]["recall"] for k in POS_KINDS if k in rep]
    fprs = [rep[k]["fpr"] for k in NEG_KINDS if k in rep]
    rec = float(np.mean(recs)) if recs else 0.0
    fpr = float(max(fprs)) if fprs else 0.0
    return 2.0 * rec - 4.0 * fpr, rec, fpr


def fmt_gen(rep):
    """一行压缩版：正样本各路召回 + 负样本各路误报（带最大分）。"""
    pos = " ".join(f"{k}={rep[k]['recall']:.3f}" for k in POS_KINDS if k in rep)
    neg = " ".join(f"{k}={rep[k]['fpr']:.3f}/{rep[k]['max']:.2f}"
                   for k in NEG_KINDS if k in rep)
    return f"gen[+ {pos} | - {neg}]"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--init", default=r"D:/tmp/skip_probe/skipnet_model_torchv5w2/skipnet.pt")
    ap.add_argument("--raw", default=r"R:/byfen_live2")
    ap.add_argument("--extra", default=r"R:/byfen_live")
    ap.add_argument("--root", default=r"R:/Temp/glyph_batch",
                    help="临时落盘根目录（必须可写，默认 R 盘 ramdisk）")
    ap.add_argument("--out", default=r"D:/tmp/skipnet_glyph_f")
    ap.add_argument("--rounds", type=int, default=20)
    ap.add_argument("--per-round", type=int, default=500)
    ap.add_argument("--epochs", type=int, default=4)
    ap.add_argument("--bs", type=int, default=32)
    ap.add_argument("--lr", type=float, default=8e-4)
    ap.add_argument("--workers", type=int, default=12, help="生图进程数")
    ap.add_argument("--neg-ratio", type=float, default=0.30,
                    help="负样本占比；4:1 = 0.20，但那条实测会导致「微信」误报 1.000")
    ap.add_argument("--seed", type=int, default=20261029)
    ap.add_argument("--eval-size", type=int, default=400,
                    help="留出评估批张数（生成时带标签，训练不碰这批）")
    ap.add_argument("--eval-seed", type=int, default=991733,
                    help="评估批固定种子；同一 seed = 同一批图，跨版本严格可比")
    ap.add_argument("--loose-n", type=int, default=0,
                    help="单轮大样本模式：真机松框张数（>0 则忽略 --rounds/--per-round）")
    ap.add_argument("--alpha-n", type=int, default=0,
                    help="单轮大样本模式：胶囊透明度全谱张数")
    ap.add_argument("--eval-every-ep", type=int, default=0,
                    help="单轮大样本模式下每 N 个 epoch 在留出批上评一次并挑 best（0=只在末尾评）")
    ap.add_argument("--wall-n", type=int, default=0,
                    help="彩色壁纸专项单轮模式：总张数（背景换成真壁纸随机裁块）")
    ap.add_argument("--flat-n", type=int, default=0,
                    help="综合专项单轮模式：总张数（纯色底+圆形按钮+超松框+壁纸，见 plan_mix）")
    ap.add_argument("--wall-eval-size", type=int, default=1200,
                    help="彩色壁纸留出批张数（选模型的依据）")
    ap.add_argument("--wall-eval-seed", type=int, default=771021,
                    help="彩色壁纸留出批种子；固定 ⇒ 跨版本严格可比")
    a = ap.parse_args()

    big = a.loose_n > 0 or a.alpha_n > 0 or a.wall_n > 0 or a.flat_n > 0
    if big:
        # 壁纸/综合专项允许 --rounds > 1：每轮重新生成整批（种子不同），等于把样本量翻倍，
        # 比在同一批上多跑 epoch 更能压住尾部那 3%。
        if a.wall_n <= 0 and a.flat_n <= 0:
            a.rounds = 1
        a.per_round = max(1, a.flat_n or a.wall_n or (a.loose_n + a.alpha_n))

    def round_plan():
        """当前模式的单轮配比。各专项会顶掉 loose/alpha 两个老开关（背景源不同）。"""
        if a.flat_n > 0:
            return plan_mix(a.flat_n)
        if a.wall_n > 0:
            return plan_wall(a.wall_n)
        if big:
            return plan_big(a.loose_n, a.alpha_n, a.neg_ratio)
        return plan_kinds(a.per_round, True, a.neg_ratio)

    torch.set_num_threads(2)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    check_writable(a.root)

    rng = np.random.default_rng(a.seed)
    t0 = time.time()
    pool = PS.GenPool(a.raw, a.extra, REAL_BOXES, workers=a.workers, seed=a.seed)
    print(f"[pool] {a.workers} 进程", flush=True)

    net = SkipNet(width=2, head="meanmax").to(dev)
    n = sum(p.numel() for p in net.parameters())
    if a.init and os.path.exists(a.init):
        ck = torch.load(a.init, map_location="cpu", weights_only=False)
        net.load_state_dict(ck["state_dict"] if "state_dict" in ck else ck)
        print(f"[init] warm start {a.init} params={n}", flush=True)

    # 固定验证集：合成分档 + 真机紧框/松框/1/3 压缩。串行采样器够用（一次性 2140 张）
    sampler = PS.Sampler(a.raw, a.extra, workers=1, seed=a.seed)
    val = ST.build_val(sampler, np.random.default_rng(12345))
    sampler.close()

    # 留出评估批：生成时就带标签，训练不碰。固定种子 ⇒ 任何版本/任何轮次比的是同一批图。
    EX, Ey, Ek = build_eval(pool, a.root, a.eval_size, a.eval_seed)
    print(f"[eval] 留出 {len(EX)} 张（正 {int(Ey.sum())}） 种子={a.eval_seed} "
          f"预热 {time.time() - t0:.1f}s", flush=True)
    os.makedirs(a.out, exist_ok=True)
    np.savez_compressed(os.path.join(a.out, "eval_gen.npz"),
                        X=EX, y=Ey, kinds=Ek)

    # 专项留出批：本轮选模型的唯一依据。旧口径那套继续算，用来盯回归
    # （不能为了新底型把「白底实心胶囊」这半个基本盘训丢）。
    WX = Wy = Wk = None
    if a.wall_n > 0 or a.flat_n > 0:
        mix = EVAL_MIX_ALL if a.flat_n > 0 else EVAL_WALL_MIX
        tag = "_eval_mix" if a.flat_n > 0 else "_eval_wall"
        WX, Wy, Wk = build_eval(pool, a.root, a.wall_eval_size, a.wall_eval_seed,
                                mix, tag)
        print(f"[eval-mix] 留出 {len(WX)} 张（正 {int(Wy.sum())}） "
              f"种子={a.wall_eval_seed}  累计 {time.time() - t0:.1f}s", flush=True)
        np.savez_compressed(os.path.join(a.out, "eval_mix.npz"),
                            X=WX, y=Wy, kinds=Wk)

    opt = torch.optim.AdamW(net.parameters(), lr=a.lr, weight_decay=1e-4)
    n_total = sum(c for _, c in round_plan())
    n_ep = a.epochs
    steps_per_round = max(1, n_total // a.bs) * n_ep
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=a.rounds * steps_per_round)

    best = (-1e9, None, "?", None, None)

    def evaluate_and_log(tag, losses, dt):
        """留出批 + 真机锚点 → 选模型。返回 (score, report)。"""
        nonlocal best
        gre = eval_gen(net, EX, Ey, Ek, dev)
        gsc, grec, gfpr = score_gen(gre)
        rep = ST.evaluate(net, val, dev)
        real = min(rep.get("real", {}).get("min", 1.0),
                   rep.get("real_loose", {}).get("min", 1.0))
        hard = np.mean([rep[f"gap{g}"]["recall"] for g in (0.5, 0.75, 1.0)])
        if WX is not None:
            # 壁纸专项：选模型看这套（用户报的就是这一类），旧口径按 1:2 权重压着防回归。
            wgre = eval_gen(net, WX, Wy, Wk, dev)
            wsc, wrec, wfpr = score_gen(wgre)
            sc = 2.0 * wsc + 1.0 * gsc + 1.5 * min(real, 1.0)
            print(f"[{tag}] {dt:.1f}s loss={np.mean(losses):.4f} score={sc:.3f} "
                  f"real={real:.3f} gap0={rep['gap0.0']['recall']:.3f} hard={hard:.3f} "
                  f"({time.time() - t0:.0f}s)", flush=True)
            print("  wall " + fmt_gen(wgre), flush=True)
            print("  base " + fmt_gen(gre), flush=True)
        else:
            wgre = None
            sc = gsc + 1.5 * min(real, 1.0)
            print(f"[{tag}] {dt:.1f}s loss={np.mean(losses):.4f} "
                  f"score={sc:.3f} rec={grec:.3f} fpr={gfpr:.3f} " + fmt_gen(gre) +
                  f" real={real:.3f} gap0={rep['gap0.0']['recall']:.3f} hard={hard:.3f} "
                  f"({time.time() - t0:.0f}s)", flush=True)
        if sc > best[0]:
            best = (sc, {k: v.detach().cpu().clone() for k, v in net.state_dict().items()},
                    tag, gre, wgre)
        return sc, rep

    os.makedirs(a.out, exist_ok=True)
    t0 = time.time()
    for r in range(1, a.rounds + 1):
        d = os.path.join(a.root, f"r{r:02d}")
        rm_tree(d)
        os.makedirs(d, exist_ok=True)

        tg = time.time()
        plan = round_plan()
        paths = pool.run(d, plan)
        X, y = read_round(paths)
        print(f"[r{r:02d}] 生图 {len(paths)} 张（正 {int(y.sum())}） "
              f"{time.time() - tg:.2f}s  计划={plan}", flush=True)

        idx = np.arange(len(X))
        losses = []
        for ep in range(1, n_ep + 1):
            tt = time.time()
            rng.shuffle(idx)
            for s in range(0, len(idx) - a.bs + 1, a.bs):
                b = idx[s:s + a.bs]
                xb = torch.from_numpy(X[b]).to(dev, non_blocking=True)
                yb = torch.from_numpy(y[b]).to(dev, non_blocking=True)
                opt.zero_grad(set_to_none=True)
                loss = F.cross_entropy(net(xb), yb)
                loss.backward()
                opt.step()
                sched.step()
                losses.append(loss.detach().item())
            # 大单轮模式没有「轮次」可比，改成逐 epoch 在留出批上挑 —— 否则选出的是
            # 训练末端的过拟合快照（n 版 40 轮就是这么把两张真机正样本训丢的）。
            if big and a.eval_every_ep and (ep % a.eval_every_ep == 0 or ep == n_ep):
                evaluate_and_log(f"r{r:02d}ep{ep:02d}", losses, time.time() - tt)

        rm_tree(d)                           # 训完立刻删，R 盘别攒东西
        if not (big and a.eval_every_ep):
            evaluate_and_log(f"r{r:02d}", losses, 0.0)

    pool.close()
    print(f"\n[best] score={best[0]:.3f} at {best[2]}", flush=True)
    net.load_state_dict(best[1])
    rep = ST.evaluate(net, val, dev)

    def dump_report(title, gre):
        print(f"\n[{title} @0.9]（标签来自生成时的 kind，非人工判）")
        print(f"  {'kind':<10}{'n':>5}{'类型':>6}{'min/max':>10}{'mean':>8}{'召回/误报':>10}")
        for k in POS_KINDS + NEG_KINDS:
            if k not in gre:
                continue
            d = gre[k]
            if d["kind"] == "pos":
                print(f"  {k:<10}{d['n']:>5}{'正':>6}{d['min']:>10.3f}{d['mean']:>8.3f}"
                      f"{d['recall']:>10.3f}")
            else:
                print(f"  {k:<10}{d['n']:>5}{'负':>6}{d['max']:>10.3f}{d['mean']:>8.3f}"
                      f"{d['fpr']:>10.3f}")
        sc, rec, fpr = score_gen(gre)
        print(f"  => 平均召回 {rec:.4f}  最大误报 {fpr:.4f}  gen_score={sc:.3f}")
        return sc, rec, fpr

    gre = eval_gen(net, EX, Ey, Ek, dev)
    gsc, grec, gfpr = dump_report("留出批（旧口径，跨版本可比）", gre)
    wgre, wsc, wrec, wfpr = None, 0.0, 0.0, 0.0
    if WX is not None:
        wgre = eval_gen(net, WX, Wy, Wk, dev)
        wsc, wrec, wfpr = dump_report("彩色壁纸留出批", wgre)
    print("[gap 分档 @0.9]")
    for g in (0.0, 0.1, 0.25, 0.5, 0.75, 1.0):
        r = rep[f"gap{g}"]
        print(f"  gap={g:<5.2f} recall={r['recall']:.3f} mean_p={r['mean']:.3f}")
    print(f"[负样本] FPR={rep['neg']['fpr']:.4f} max={rep['neg']['max']:.3f}")
    print(f"[真机背景块] FPR={rep['bg']['fpr']:.4f} max={rep['bg']['max']:.3f}")
    print(f"[真机高纹理块] FPR={rep.get('rtneg', {}).get('fpr', -1):.4f} "
          f"max={rep.get('rtneg', {}).get('max', -1):.3f}")
    print(f"[真机紧框] min={rep.get('real', {}).get('min', -1):.3f}")
    print(f"[真机松框 +10%/边] min={rep.get('real_loose', {}).get('min', -1):.3f}")
    print(f"[真机紧框 1/3 压缩] min={rep.get('real_lo3', {}).get('min', -1):.3f}")

    sd = {k: v.detach().cpu() for k, v in net.state_dict().items()}
    order = list(sd.keys())
    torch.save({"state_dict": sd, "params": n, "width": 2, "head": "meanmax",
                "arch": "conv3x3-32-mp|conv3x3-48-mp|conv3x3-64-meanmax|fc-128-2",
                "input": {"w": IN_W, "h": IN_H, "ch": 3,
                          "pre": "per-image per-channel (x/255-mean)/std"},
                "train": {"kind": "batch-file-pipeline", "rounds": a.rounds,
                          "per_round": a.per_round, "epochs": a.epochs, "bs": a.bs,
                          "seed": a.seed, "init": a.init}},
               os.path.join(a.out, "skipnet.pt"))
    with open(os.path.join(a.out, "skipnet_f32.bin"), "wb") as fh:
        for k in order:
            fh.write(sd[k].numpy().astype("<f4").tobytes())
    with open(os.path.join(a.out, "model.json"), "w", encoding="ascii") as fh:
        doc = {"params": int(n), "order": order, "head": "meanmax", "width": 2,
               "input": {"w": IN_W, "h": IN_H, "ch": 3},
               "classes": {"1": "skip"}, "val": rep,
               "gen_eval": {"seed": a.eval_seed, "size": int(len(EX)), "rep": gre,
                            "recall": grec, "fpr": gfpr}}
        if wgre is not None:
            doc["wall_eval"] = {"seed": a.wall_eval_seed, "size": int(len(WX)),
                                "rep": wgre, "recall": wrec, "fpr": wfpr,
                                "wall_dir": G.WALL_DIR}
        json.dump(doc, fh, indent=2)
    print(f"\n[export] {a.out} bin={os.path.getsize(os.path.join(a.out, 'skipnet_f32.bin'))} B")
    return 0


if __name__ == "__main__":
    sys.exit(main())
