"""在线合成「跳过」紧框样本：逐字定位，字距可拉到 2 倍字高，胶囊底/描边透明度任意。

为什么不用 `ImageDraw.text` 整串画：那样字距由字体 kerning 定死，没法模拟
「跳  过」「跳过    3」这种被拉开的排版。这里每个字单独出 alpha mask，再按
`gap = k × 字高` 拼起来，k 从 -0.05 到 2.0 都能量。

口径与 app 一致：
  * 输出 = 96×32（SkipNet.IN_W×IN_H），前处理 (x/255 − mean)/std 由训练器做；
  * 缩放走 align_corners=False 纯双线性（app 的 resampleInto 同口径），不用 PIL BILINEAR；
  * 源框 aspect 随机 2.0–5.5（提案器给的真实范围），字距拉开时框自然变宽。

负样本三类：别的文案 / 只有「跳过」的一个字 / 真机背景块。
"""
import os
import hashlib

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT_DIR = os.environ.get("SYSTEMROOT", r"C:\Windows") + os.sep + "Fonts"
# 用 ttf/ttc 直接渲染，字体池按权重采样。
# 只用黑体是上一轮「微信被判 1.000」的隐性成因之一：模型只见过一种字形，
# 换台设备换套字体就泛化不了。这里补上真机常见的几种：
#   黑体 ×3      —— 广告胶囊主力
#   雅黑Bold ×2  —— 电商/游戏投放最常用
#   Noto Sans SC ×2 —— Android 系统默认（思源黑体），GT8 Pro 上大量出现
#   等线 ×1 / 雅黑 ×1 / 楷体 ×1 —— 补字形多样性，防过拟合
FONTS = ["simhei.ttf", "simhei.ttf", "simhei.ttf",
         "msyhbd.ttc", "msyhbd.ttc",
         "NotoSansSC-VF.ttf", "NotoSansSC-VF.ttf",
         "Deng.ttf", "msyh.ttc", "simkai.ttf"]

IN_W, IN_H = 96, 32

SKIP = "跳过"
# 真实胶囊里常见的搭配：前面带倒计时、后面带秒数或箭头
LEAD = ["", "", "", "3 ", "5 ", "2 ", "10 ", "8s "]
TAIL = ["", "", "", " 3", " 5s", " >", " >|", " 2"]
NEG_TEXTS = ["关闭", "关闭广告", "了解详情", "点击查看", "立即下载", "点击安装",
             "广告", "领取", "抽奖", "更多", "查看详情", "继续", "以后再说",
             # 「跳转」系列必须单列：它们**含「跳」字**，是模型最容易放行的假阳性。
             # 实测把「委桃跳逗」「37 跳究」「忙街跳」全判 1.000 —— 模型只认到了「跳」。
             "跳转", "跳转应用", "跳转详情", "跳一跳", "跳完", "跳远", "跳板", "跳蚤",
             "详情",
             # 纯数字/倒计时也要能认成「不是跳过」：真机广告里满屏都是这类
             "3", "10", "5s", "06", "15s", "2",
             # 真机标题字：模型最容易把这类两字/三字中文词当成「跳过」
             # （第 28 轮实测「微信」判 1.000、「加速版」判 0.993）
             "微信", "加速版", "安装", "下载", "进入", "玩法", "福利", "礼包",
             "开服", "预约", "充值", "首充", "特权", "秒杀", "抢购", "红包",
             "提现", "攻略", "官方", "正版", "独家", "更新", "会员", "登录",
             "注册", "新人", "限时", "免费"]

# ---------------------------------------------------------------------------
# 随机负样本文案
#
# 固定词表（NEG_TEXTS 那 60 个）只是真机里「已经踩过的坑」的补丁，模型很容易
# 过拟合到「只要不是这 60 个词就算负」。随机文案能把边界撑开。
#
# 但纯随机有两个陷阱，必须挡住：
#   1. 随机串可能凑出「跳过」本身 —— 等于给负样本贴错标，模型会学到自相矛盾的
#      东西。生成后一律过滤。
#   2. 随机串的长度分布若和正样本不同（随机 3–6 字 vs 正样本固定 2 字），模型
#      就会改学「字数」而不是字形。这里按正样本的口径：2 字为主，1/3/4 字少量。
# ---------------------------------------------------------------------------
# 形近字：足字旁（跳的本字旁）与辶字底（过的本字底）。
# 这一组是最该让模型分辨的 —— 随机文案里若从不出现它们，模型只会检查
# 「框里有没有一个足字旁」，遇到「挑」「边」照样误点。
_NEG_RANDOM_P = 0.55        # 负样本里随机文案的占比（其余走固定词表）

# 字形可用性：字池里（尤其 U+4E00–U+9FA5 盲采的那部分）会混进 simhei 没有的字，
# Pillow 会把它画成「豆腐块」。豆腐块一旦进了训练集，模型就会把「框里有空心方块」
# 学成负样本特征 —— 真机上任何方框图标都可能踩雷。所以每个字先跟 .notdef 比对。
_glyph_ok_cache = {}
_tofu_sig = None


def _sig(font, ch):
    try:
        m = np.asarray(font.getmask(ch), np.uint8)
    except Exception:
        return None
    return (m.shape, m.tobytes())


def glyph_ok(ch):
    global _tofu_sig
    ok = _glyph_ok_cache.get(ch)
    if ok is not None:
        return ok
    f = get_font("simhei.ttf", 32)
    if _tofu_sig is None:
        _tofu_sig = _sig(f, "\ue000")          # 私用区必然无字形 ⇒ 豆腐块基准
    s = _sig(f, ch)
    ok = bool(s) and s != _tofu_sig
    _glyph_ok_cache[ch] = ok
    return ok


def _clean_pool(chars):
    return [c for c in dict.fromkeys(chars) if glyph_ok(c)]


_CONFUSABLE_SRC = "挑桃逃眺兆跳边达迈运近进还迅迎追送退选透逛遥返逗适"
_COMMON_SRC = (
    "的一是不了人我在有他这上们来到时大地为子中你说生国年着就那和要她出也得里后自"
    "以会家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作"
    "种开美总从无情己面最女但现前些所同日手又行意动方期它头经长儿回位分爱老因很给"
    "名法间斯知世什两次使身者被高已亲其进此话常与活正感见明问力理尔点文几定本公特"
    "做外孩相西果走将月十实向声车全信重三机工物气每并别真打太新比才便夫再书部水像"
    "眼等体却加电主界门利海受听表德少克代员许先口由死安写性马光白或住难望教命花结"
    "乐色更拉东神记处让母父应直字场平报友关放至张认接告入笑内英军候民岁往何度山觉"
    "路带万男风解叫任金快原吃妈变通师立象数四失满战远格士音轻目条呢病始深完今提求"
    "清王化空业思切怎非找片罗钱语元喜曾离飞科言干流欢约各即指合反题必该论交终林请"
    "医晚制球决传画保读及则房早院量苦火布品近坐产答星精视五连司巴奇管类未朋且婚台"
    "夜青北队久乎越观落尽形影红爸百令周吧识步希亚术留市半热送兴造谈容极随演收首根"
    "讲整式取照办强石古华拿计您装似足双妻转诉米称丽客南领节衣站黑刻统断福城故历惊"
    "脸包紧争另建维绝树系伤示愿持千史谁准联妇纪基买志静阿诗独复痛消社算义竟确酒需"
    "单治卡幸兰念举仅钟怕共毛句息功官待究跟穿室易游程号居考突皮哪费倒价图具刚脑永"
    "歌响商礼细专黄块脚味灵改据般破引食仍存众注笔甚某沉血备习校默务土微娘须试怀料"
    "调广苏显赛查密议底列富梦错座参八除跑亮假印设线温虽掉京初养香停际致阳纸李纳验"
    "助激够严证帝饭忘趣支春集丈木研班普导顿睡展获艺六波察群皇段急庭创区奥器谢弟店"
    "否害草排背止组州朝封睛板角况曲馆育忙质河续哥呼若推境遇雨标姐充围案伦护冷警贝"
    "雪索剧船险烟依斗值帮汉慢佛肯闻唱沙局伯族低玩资屋击速顾泪洲团圣旁堂兵七露园牛"
    "哭旅街劳型烈姑陈莫鱼异抱宝权鲁简态级票怪寻杀律胜份汽右洋范床舞秘午登楼贵吸责"
    "例追较职属渐左录丝牙党继托赶章智冲叶胡吉卖坚喝肉遗救修松临藏担戏善卫药悲敢靠"
    "伊村戴词森耳差短祖云规窗散迷油旧适乡架恩投弹铁博雷府压超负勒杂醒洗采毫嘴毕九"
    "冰既状乱景席珍童顶派素脱农疑练野按犯拍征坏骨余承置臂彩灯巨琴免环姆暗换技翻束"
    "增忍餐洛塞缺忆判欧层付阵批岛项狗休懂武革良恶恋委拥妙探营摇弄桌熟诺宣银势奖宫"
    "忽套康供优课鸟喊降困刘罪亡鞋健模败伴守挥鲜财孤枪禁恐伙杰迹妹玻弱监尾输宪")

_pool_cache = {}


def _pools():
    """字池延迟构建 —— glyph_ok 依赖 get_font，而 get_font 定义在文件后半段。"""
    if not _pool_cache:
        _pool_cache["conf"] = _clean_pool(_CONFUSABLE_SRC)
        _pool_cache["common"] = _clean_pool(_COMMON_SRC)
    return _pool_cache


def _pick_char(rng):
    u = rng.random()
    if u < 0.30:                            # 形近字优先：这一档最值钱
        p = _pools()["conf"]
        return p[int(rng.integers(0, len(p)))]
    if u < 0.82:                            # 常用字
        p = _pools()["common"]
        return p[int(rng.integers(0, len(p)))]
    # 盲采区：真随机汉字，用来撑开「什么样的字形都算不上跳过」。
    # 只取能正常出字形的，否则会退化成豆腐块样本。
    for _ in range(12):
        c = chr(0x4E00 + int(rng.integers(0, 0x9FA5 - 0x4E00)))
        if glyph_ok(c):
            return c
    return _pools()["common"][0]


def rand_neg_text(rng):
    """随机负样本文案。长度按正样本口径分布（2 字为主），并过滤出含「跳过」的串。"""
    u = rng.random()
    n = 1 if u < 0.10 else (2 if u < 0.63 else (3 if u < 0.88 else 4))
    for _ in range(6):
        text = "".join(_pick_char(rng) for _ in range(n))
        if SKIP in text or text == SKIP:
            continue
        break
    else:
        text = "关闭"

    # 少数样本带数字/单位，和正样本的「3 跳过 5s」同构：
    # 不这么做的话，带倒计时的负样本永远只有词表那几十种搭配。
    if rng.random() < 0.28:
        if rng.random() < 0.5:
            text = str(int(rng.integers(1, 100))) + " " + text
        else:
            text = text + str(rng.choice(["s", "秒", "折", "元", ">", "%", " 3", " 5s"]))
    return text


def neg_text(rng):
    """负样本文案的统一入口：固定词表 / 随机，按 _NEG_RANDOM_P 混合。"""
    if rng.random() < _NEG_RANDOM_P:
        return rand_neg_text(rng)
    return NEG_TEXTS[int(rng.integers(0, len(NEG_TEXTS)))]


# 形近字对：拆开「跳过」的两半各换一个同部件字 —— 挑/桃/逃/眺 全是足字旁，
# 边/达/近/进 全是辶字底。模型要想分对，只能真的看字形结构，靠笔画数、
# 靠「两个字 vs 三个字」这类捷径在这个集合上全部失效。
_SIM_TIAO = "挑桃逃眺兆跳"
_SIM_GUO = "边达迈运近进还迅迎追送退选透逛遥返逗适过"
# 第一个字**就是「跳」本身**、第二个字换成别的：真机上「跳转」「跳完」「跳一跳」
# 到处都是。模型如果只靠一个「跳」字下手，这一组会全线误报 ——
# 上一轮实测「37 跳究」「忙街跳」都判 1.000，就是缺这一档。
_TIAO_SECOND = "远离转到完开走舞蹈跃板蚤绳级"


def similar_skip(rng):
    """与「跳过」逐字形近的二字词（永远 != 「跳过」）。

    62% 保留「跳」字、只换第二个字：这是真机上最危险的假阳性形态，
    也是模型上一轮最明显的短板（见到「跳」就放行）。
    """
    if rng.random() < 0.45:
        for _ in range(24):
            t = "跳" + _TIAO_SECOND[int(rng.integers(0, len(_TIAO_SECOND)))]
            if glyph_ok(t[1]):
                return t
        return "跳转"
    for _ in range(24):
        t = _SIM_TIAO[int(rng.integers(0, len(_SIM_TIAO)))] + \
            _SIM_GUO[int(rng.integers(0, len(_SIM_GUO)))]
        if t != SKIP and glyph_ok(t[0]) and glyph_ok(t[1]):
            return t
    return "挑过"


_font_cache = {}


def get_font(name, px):
    key = (name, px)
    f = _font_cache.get(key)
    if f is None:
        path = os.path.join(FONT_DIR, name)
        try:
            f = ImageFont.truetype(path, px)
        except Exception:
            f = ImageFont.truetype(os.path.join(FONT_DIR, "simhei.ttf"), px)
        _font_cache[key] = f
    return f


def glyph_mask(font, ch):
    """单个字的 alpha mask（float32, 0..1）+ 前进宽度。"""
    bbox = font.getbbox(ch)
    x0, y0, x1, y1 = bbox
    w = max(1, x1 - x0)
    h = max(1, y1 - y0)
    im = Image.new("L", (w + 2, h + 2), 0)
    d = ImageDraw.Draw(im)
    d.text((1 - x0, 1 - y0), ch, font=font, fill=255)
    a = np.asarray(im, np.float32) / 255.0
    adv = float(font.getlength(ch))
    return a, adv


def compose_line(rng, chars, px, gap_scale):
    """把 chars 逐字拼成一行 alpha 图；gap = gap_scale × 字高。返回 (mask, 字高)。"""
    font = get_font(FONTS[rng.integers(0, len(FONTS))], px)
    masks, advs = [], []
    for ch in chars:
        m, adv = glyph_mask(font, ch)
        masks.append(m)
        advs.append(adv)
    gh = max(m.shape[0] for m in masks)
    # 用户定的硬约束：字距不超过一个字高（1.0 = 一个字）
    gap_px = float(np.clip(gap_scale * gh, -0.06 * gh, 1.0 * gh))
    # 每个字单独决定用 advance 还是 mask 宽，取大者，避免字被压扁
    widths = [max(m.shape[1], int(round(adv))) for m, adv in zip(masks, advs)]
    total_w = int(sum(widths) + gap_px * (len(chars) - 1))
    total_h = gh
    canvas = np.zeros((total_h, max(1, total_w)), np.float32)
    x = 0.0
    for m, w in zip(masks, widths):
        xi = int(round(x))
        mw = min(m.shape[1], canvas.shape[1] - xi)
        if mw > 0:
            my = (total_h - m.shape[0]) // 2
            y0 = max(0, my)
            y1 = min(canvas.shape[0], my + m.shape[0])
            canvas[y0:y1, xi:xi + mw] = np.maximum(
                canvas[y0:y1, xi:xi + mw],
                m[y0 - my:y1 - my, :mw],
            )
        x += w + gap_px
    return canvas, gh


def rounded_alpha(w, h, radius, value=1.0, outline=0):
    w, h = max(3, int(w)), max(3, int(h))
    radius = int(max(0, min(int(radius), min(w, h) // 2)))
    im = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(im)
    if outline:
        # 边宽必须 < 半高，否则矩形退化、Pillow 直接抛 "x1 must be >= x0"
        ow = max(1, min(int(outline), max(1, min(w, h) // 2 - 1)))
        d.rounded_rectangle((1, 1, w - 2, h - 2), radius=radius,
                            outline=255, width=ow)
    else:
        d.rounded_rectangle((0, 0, w - 1, h - 1), radius=radius, fill=255)
    return np.asarray(im, np.float32) / 255.0 * value


def blend(dst, alpha, color):
    """alpha 层（HxW）以整块不透明度叠加到 RGB 背景 dst（HxWx3）。"""
    a = alpha[..., None]
    return dst * (1.0 - a) + np.asarray(color, np.float32) * a


def resize_exact(im, ow=IN_W, oh=IN_H):
    """与 app 的 resampleInto 一致：align_corners=False 双线性。"""
    a = np.asarray(im.convert("RGB"), np.float32)
    h, w = a.shape[:2]
    xs = np.clip((np.arange(ow) + 0.5) * w / ow - 0.5, 0, w - 1)
    ys = np.clip((np.arange(oh) + 0.5) * h / oh - 0.5, 0, h - 1)
    x0 = np.floor(xs).astype(int); x1 = np.minimum(x0 + 1, w - 1); fx = (xs - x0)[None, :, None]
    y0 = np.floor(ys).astype(int); y1 = np.minimum(y0 + 1, h - 1); fy = (ys - y0)[:, None, None]
    top = a[y0][:, x0] * (1 - fx) + a[y0][:, x1] * fx
    bot = a[y1][:, x0] * (1 - fx) + a[y1][:, x1] * fx
    return Image.fromarray(np.clip(top * (1 - fy) + bot * fy, 0, 255).astype(np.uint8), "RGB")


# ---------------------------------------------------------------------------
# 彩色壁纸背景
#
# 为什么单独开一路：此前所有合成背景都来自 `load_frames` 的真机广告帧，
# 那些帧的胶囊区几乎全是白/浅灰/黑的实心广告版式。模型于是把「背景是中性色」
# 当成了判据的一部分 —— 一旦底图换成高饱和的彩色壁纸（真机桌面、插屏广告的
# 满屏插画），同一枚「跳过」就认不出来。这不是字形问题，是底色分布没覆盖。
#
# 这里用真壁纸做背景源：随机缩放裁块 + 随机翻转，同一张壁纸能出无穷多样的
# 局部色块（大面积渐变、树叶、天空、霓虹），饱和度分布正好补上缺的那一段。
# ---------------------------------------------------------------------------
WALL_DIR = os.environ.get("SKIP_WALL_DIR",
                          os.path.join(os.path.expanduser("~"), "Pictures", "夸克精选壁纸"))
_WALL = None
_WALL_SCALE = 0.5        # 壁纸先缩到一半：20 张 1080×1920 → 每张 1.5 MB，12 worker 才 360 MB

# 真机界面截图（桌面 / 锁屏 / 设置 / 充电页）：里面全是「深色底 + 圆角卡片 + 一段标题字」，
# 正是模型最容易误点的构图。合成负样本永远比真实渲染「太干净」，所以直接拿真图裁块。
UI_DIR = os.environ.get("SKIP_UI_DIR", r"D:/tmp/ui_neg")
_UI = None


def load_ui():
    """懒加载界面截图（原分辨率，裁块用）。"""
    global _UI
    if _UI is not None:
        return _UI
    out = []
    if os.path.isdir(UI_DIR):
        for name in sorted(os.listdir(UI_DIR)):
            if not name.lower().endswith((".png", ".jpg", ".jpeg", ".webp")):
                continue
            try:
                im = Image.open(os.path.join(UI_DIR, name)).convert("RGB")
            except Exception:
                continue
            if im.width < 200 or im.height < 200:
                continue                       # 黑屏/损坏的截图
            if float(np.asarray(im, np.float32).mean()) < 8.0:
                continue                       # 纯黑
            out.append(np.asarray(im, np.uint8))
    _UI = out
    return _UI



def load_wallpapers():
    """懒加载壁纸（缩到 1/2），worker 进程内缓存。

    裁出来的一块最后要缩到 96×32 的框里，源像素密度早就过剩了；
    缩一半能把 12 进程的内存从 2.5 GB 压到 360 MB，而视觉上毫无差别。
    """
    global _WALL
    if _WALL is not None:
        return _WALL
    out = []
    if os.path.isdir(WALL_DIR):
        for name in sorted(os.listdir(WALL_DIR)):
            if not name.lower().endswith((".jpg", ".jpeg", ".png", ".webp")):
                continue
            try:
                im = Image.open(os.path.join(WALL_DIR, name)).convert("RGB")
            except Exception:
                continue
            w, h = im.size
            im = im.resize((max(8, int(w * _WALL_SCALE)), max(8, int(h * _WALL_SCALE))),
                           Image.BILINEAR)
            out.append(np.asarray(im, np.uint8))
    _WALL = out
    return _WALL


def _wall_patch(rng, bw, bh, vivid_p=0.70, busy=False):
    """从壁纸随机裁一块 (bw×bh) 的彩色背景。

    `vivid_p` 比例的样本会试 6 次、挑**饱和度最高**的那块 —— 用户报的就是
    「背景是彩色的就认不出」，闷头随机裁会有大量灰天空段，等于没补。
    余下 30% 保持纯随机，别把模型训成「见不到灰底就不认」。

    `busy=True` 反过来挑**边缘密度最高**的块：瓷砖格、格纹、小花都是密集重复图案，
    实测模型会把它们当成一行字（bg_wall 里 4/84 判到 0.93–1.00）。这类块必须进负样本。
    """
    walls = load_wallpapers()
    if not walls:
        raise RuntimeError(f"壁纸目录为空：{WALL_DIR}")
    trials = 6 if (busy or rng.random() < vivid_p) else 1
    best = None
    for _ in range(trials):
        f = walls[int(rng.integers(0, len(walls)))]
        H, W = f.shape[:2]
        # 裁窗相对目标尺寸的倍率：0.9 是几乎原样，3.0 是把一小块色斑放大 ——
        # 后者能造出「整框一片纯色」的极端情形（真机半透明胶囊压纯色广告底）。
        z = float(rng.uniform(0.9, 3.0))
        cw = int(np.clip(bw * z, 8, W - 1))
        ch = int(np.clip(bh * z, 6, H - 1))
        y = int(rng.integers(0, max(1, H - ch)))
        x = int(rng.integers(0, max(1, W - cw)))
        patch = f[y:y + ch, x:x + cw]
        if trials == 1:
            best = (0.0, patch.copy())
            break
        p32 = patch.astype(np.float32)
        if busy:
            g = 0.299 * p32[..., 0] + 0.587 * p32[..., 1] + 0.114 * p32[..., 2]
            score = float(np.abs(np.diff(g, axis=1)).mean() + np.abs(np.diff(g, axis=0)).mean())
        else:
            score = float((p32.max(axis=2) - p32.min(axis=2)).mean())
        if best is None or score > best[0]:
            best = (score, patch.copy())
    p = best[1]
    im = Image.fromarray(np.ascontiguousarray(p), "RGB")
    if rng.random() < 0.5:
        im = im.transpose(Image.FLIP_LEFT_RIGHT)
    im = im.resize((int(bw), int(bh)), Image.BILINEAR)
    return np.asarray(im, np.float32)


# 纯色/近纯色底：真机广告最常见的底型 —— 品牌主色铺满、上下渐变、色块分栏。
# 这一组必须覆盖到「浅黄/黄/橙/浅绿」这类亮底：促销广告的「跳过」常是白字压在上面，
# 字与底亮度差只有 10–30，人眼靠字形轮廓认，模型也得能认。
FLAT_BASES = [
    (255, 255, 255), (246, 246, 248), (232, 232, 234), (210, 210, 214),
    (252, 244, 214), (253, 216, 53), (251, 196, 60), (251, 140, 0), (244, 102, 27),
    (229, 57, 53), (216, 27, 96), (142, 36, 170), (103, 58, 183), (30, 136, 229),
    (13, 71, 161), (0, 172, 193), (67, 160, 71), (38, 166, 154), (120, 144, 156),
    (48, 48, 50), (24, 24, 26), (10, 10, 10),
]


def _flat_patch(rng, bw, bh):
    """造一块纯色 / 近纯色背景（真机广告底板）。

    为什么不能只用 `_wall_patch`：壁纸总有纹理和色斑，`vivid_p` 还会优先挑高饱和块，
    结果「整框一片均匀色」这种真机最常见的底型在训练集里几乎不存在。用户报的
    「纯色识别不出来」就是这片空白 —— 模型在纯净底上找不到它依赖的纹理线索。

    四档形态，都保留轻微的传感器噪声（真机截图不会是完全平坦的 0 方差，
    否则 per-image 标准化会把噪声放大成完全不同的统计量）：
      55% 单色铺满      20% 同色系线性渐变      15% 柔和径向光斑      10% 色块横带
    """
    base = np.array(FLAT_BASES[int(rng.integers(0, len(FLAT_BASES)))], np.float32)
    b3 = base.reshape(1, 1, 3)          # 必须带满三维，否则和 (bh,1,1) 广播会丢宽度
    r = float(rng.random())
    if r < 0.55:
        arr = np.broadcast_to(b3, (bh, bw, 3)).copy()
    elif r < 0.75:
        other = np.clip(base * float(rng.uniform(0.55, 1.35)), 0, 255).reshape(1, 1, 3)
        if rng.random() < 0.5:
            tv = np.linspace(0.0, 1.0, bw, dtype=np.float32)[None, :, None]
        else:
            tv = np.linspace(0.0, 1.0, bh, dtype=np.float32)[:, None, None]
        # 必须显式广播到 (bh, bw, 1)：numpy 右对齐，(1,bw,1)*(1,1,3) 会得到 (1,bw,3)，
        # 高度维直接消失 —— 拿到的"背景"是一行像素，后续切片必然对不上。
        t = np.broadcast_to(tv, (bh, bw, 1))
        arr = b3 * (1.0 - t) + other * t
    elif r < 0.90:
        yy, xx = np.mgrid[0:bh, 0:bw].astype(np.float32)
        cx, cy = float(rng.uniform(0, bw)), float(rng.uniform(0, bh))
        rad = float(max(bw, bh)) * float(rng.uniform(0.5, 1.2))
        d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / max(1.0, rad)
        t = np.clip(1.0 - d, 0.0, 1.0)[..., None]
        other = (np.clip(base + 40.0, 0, 255) if base.mean() < 128
                 else np.clip(base - 30.0, 0, 255)).reshape(1, 1, 3)
        arr = b3 * (1.0 - t) + other * t
    else:
        arr = np.broadcast_to(b3, (bh, bw, 3)).copy()
        band_h = int(rng.integers(max(4, bh // 5), max(6, bh // 2)))
        y0 = int(rng.integers(0, max(1, bh - band_h)))
        arr[y0:y0 + band_h] = np.clip(base * float(rng.uniform(0.7, 1.3)), 0, 255)
    arr += rng.normal(0, float(rng.uniform(0.8, 3.0)), arr.shape).astype(np.float32)
    return np.clip(arr, 0, 255)


def pick_background(rng, frames, size=None, quiet=0.8, busy=False, src=None):
    """随机切一块背景；`quiet` 比例下优先挑低纹理块。

    真机广告帧里到处是大标题、强图案，直接随机切会让合成的「跳过」被背景字压住，
    模型学到的是「框里最显眼的那块」。真实胶囊底下要么是实心底、要么是纯色区，
    所以这里按局部标准差挑干净一点的块，但保留一部分杂乱背景防过拟合。

    `busy=True` 反过来挑**纹理最重**的块 —— 真机里「微信」「加速版」「0.1折」这类
    标题字就在这里，拿它们当负样本比任何合成假样本都硬（实测模型会把「微信」判 1.000）。

    `src="wall"` 走彩色壁纸（见 `_wall_patch`），`src="flat"` 走纯色/近纯色（见
    `_flat_patch`），与真机帧完全分开。
    """
    if src is None and (frames is None or len(frames) == 0):
        src = "wall"           # 没有真机帧时退到壁纸（预览/单测路径）
    if src in ("wall", "flat"):
        if size is None:
            bh0 = int(rng.integers(30, 92))
            aspect = float(rng.uniform(2.0, 5.5))
            bw0 = int(np.clip(bh0 * aspect, 60, 340))
        else:
            bw0, bh0 = int(size[0]), int(size[1])
        bw0, bh0 = max(40, bw0), max(24, bh0)
        if src == "flat":
            return _flat_patch(rng, bw0, bh0)
        return _wall_patch(rng, bw0, bh0, busy=busy)

    f = frames[rng.integers(0, len(frames))]
    H, W = f.shape[:2]
    if size is None:
        bh = int(rng.integers(30, 92))
        aspect = float(rng.uniform(2.0, 5.5))
        bw = int(np.clip(bh * aspect, 60, min(340, W - 1)))
    else:
        bw, bh = int(size[0]), int(size[1])
        bw = int(np.clip(bw, 40, W - 1)); bh = int(np.clip(bh, 24, H - 1))
    trials = 6 if (rng.random() < quiet or busy) else 1
    best = None
    for _ in range(trials):
        y = int(rng.integers(0, max(1, H - bh)))
        x = int(rng.integers(0, max(1, W - bw)))
        patch = f[y:y + bh, x:x + bw]
        sd = float(patch[::2, ::2].std())
        if best is None or (sd > best[0] if busy else sd < best[0]):
            best = (sd, patch.copy())
    return best[1]


def deform_glyphs(line, rng, p=0.60):
    """字形畸变：旋转 / 错切 / 纵向压扁 / 弹性形变 / 笔画粗细。

    为什么必须加：这个模型只有 42k 参数。同一款字若在训练集里永远以同一姿态出现，
    它会记住像素模板（"第三列有个竖钩"）而不是字形结构；真机上字体、字号、渲染引擎、
    缩放比全都不一样，模板记忆必然失效 —— 用户看到的"换个广告就不认"多半是这种。

    只做**保持画布尺寸**的畸变，所以调用方的 lw/lh 不用重算。
    """
    if rng.random() > p:
        return line
    h, w = line.shape
    if h < 8 or w < 8:
        return line
    im = Image.fromarray(np.clip(line * 255, 0, 255).astype(np.uint8), "L")
    k = float(rng.random())
    if k < 0.30:                                   # 轻微旋转 ±5°
        im = im.rotate(float(rng.uniform(-5.0, 5.0)), resample=Image.BICUBIC)
    elif k < 0.52:                                 # 错切（仿斜体/透视）
        sh = float(rng.uniform(-0.16, 0.16))
        im = im.transform((w, h), Image.AFFINE, (1, sh, -sh * h / 2, 0, 1, 0),
                          resample=Image.BICUBIC)
    elif k < 0.74:                                 # 行高压缩/拉伸
        sy = float(rng.uniform(0.82, 1.20))
        nh = max(6, int(round(h * sy)))
        im = im.resize((w, nh), Image.BICUBIC).resize((w, h), Image.BICUBIC)
    elif k < 0.90:                                 # 弹性形变（低频正弦位移场）
        a = float(rng.uniform(0.5, 2.0))
        f0 = float(rng.uniform(0.7, 2.2))
        ph = float(rng.uniform(0.0, 6.283))
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        dx = a * np.sin(2 * np.pi * f0 * yy / max(1, h) + ph)
        dy = a * np.sin(2 * np.pi * f0 * xx / max(1, w) + ph * 1.7)
        sx = np.clip(np.rint(xx + dx), 0, w - 1).astype(np.int32)
        sy = np.clip(np.rint(yy + dy), 0, h - 1).astype(np.int32)
        return line[sy, sx]
    else:                                          # 笔画粗细（膨胀/腐蚀）
        im = im.filter(ImageFilter.MaxFilter(3) if rng.random() < 0.5
                       else ImageFilter.MinFilter(3))
    return np.asarray(im, np.float32) / 255.0


def _overlap(a, b):
    return not (a[2] <= b[0] or a[0] >= b[2] or a[3] <= b[1] or a[1] >= b[3])


def add_clutter(arr, rng, protect=None, p=0.38):
    """背景随机干扰：色块 / 细线 / 圆点。

    真机广告的候选框里从来不是干净底色 —— 货架、箭头、装饰线、半透明蒙层都在。
    只喂干净背景，模型会把「框里除了字什么都没有」当成判据的一部分。

    `protect=(x0,y0,x1,y1)` 时不往这块画（正样本必须保住「跳过」可读）。
    """
    if rng.random() > p:
        return arr
    h, w = arr.shape[:2]
    im = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    lay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    taken = 0
    for _ in range(int(rng.integers(3, 9))):
        col = [int(c) for c in rng.choice([[255, 255, 255], [0, 0, 0], [255, 214, 0],
                                           [255, 90, 80], [60, 120, 255], [245, 245, 245]])]
        a = int(rng.uniform(22, 105))
        k = float(rng.random())
        if k < 0.50:
            bw2 = max(3, int(rng.uniform(0.08, 0.35) * w))
            bh2 = max(3, int(rng.uniform(0.08, 0.35) * h))
            x0 = int(rng.uniform(0, max(1, w - bw2)))
            y0 = int(rng.uniform(0, max(1, h - bh2)))
            box = (x0, y0, x0 + bw2, y0 + bh2)
            if protect and _overlap(box, protect):
                continue
            d.rectangle(box, fill=tuple(col) + (a,))
            taken += 1
        elif k < 0.80:
            y = int(rng.uniform(0, max(1, h)))
            d.line((0, y, w, int(np.clip(y + rng.uniform(-6, 6), 0, h - 1))),
                   fill=tuple(col) + (a,), width=int(rng.integers(1, 3)))
            taken += 1
        else:
            r0 = int(rng.uniform(2, max(3, min(h, w) // 3)))
            cx = int(rng.uniform(r0, max(r0 + 1, w - r0)))
            cy = int(rng.uniform(r0, max(r0 + 1, h - r0)))
            if protect and _overlap((cx - r0, cy - r0, cx + r0, cy + r0), protect):
                continue
            d.ellipse((cx - r0, cy - r0, cx + r0, cy + r0), fill=tuple(col) + (a,))
            taken += 1
    if not taken:
        return arr
    out = Image.alpha_composite(im, lay).convert("RGB")
    return np.asarray(out, np.float32)


def outline_ring(layer, px=5):
    """由字 alpha 层膨胀出一圈描边（只在字外沿，不含字心）。"""
    im = Image.fromarray((np.clip(layer, 0, 1) * 255).astype(np.uint8), "L")
    dil = np.asarray(im.filter(ImageFilter.MaxFilter(px)), np.float32) / 255.0
    return np.clip(dil - np.clip(layer, 0, 1), 0.0, 1.0)


def paste_alpha(dst, line, ox, oy, gain=1.0):
    """把 alpha 行贴到 dst（HxW）的 (ox,oy)，自动裁掉越界部分。"""
    lh, lw = line.shape
    h, w = dst.shape
    x0 = max(0, ox); y0 = max(0, oy)
    x1 = min(w, ox + lw); y1 = min(h, oy + lh)
    if x1 <= x0 or y1 <= y0:
        return
    dst[y0:y1, x0:x1] = np.maximum(
        dst[y0:y1, x0:x1],
        line[y0 - oy:y1 - oy, x0 - ox:x1 - ox] * gain,
    )


def render_text_sample(rng, frames, text, gap_scale=0.0, cap_alpha=None, extra_text=False,
                       force_style=None, bg_busy=False, force_pad_ratio=None,
                       force_pad_px=None, bg_src=None, ink_mode=None, keep_lowcon=False,
                       px_range=None, blank=False):
    """按整套随机几何渲染一行文案（字体 / 框内位置 / 边距 / 胶囊 / 对比度全随机）。

    正负样本**共用**这个函数，唯一差别是 `text`：正样本传「跳过」，负样本传「微信」
    「加速版」这类真机常见词。必须共用 —— 早先负样本是固定窄边距、居中、单一黑体，
    模型学到的是「框里有一组中文字就是跳过」，于是「微信」判 1.000、「加速版」判 0.993。

    尺寸顺序是 **先字后框**：先生成文字行，再按行的宽高加内边距去切背景块。
    反过来（先随机框再塞字）在字距被拉开时会整行溢出框、字被裁掉——第一版就是栽在这。

    `ink_mode="low"`：字色取「与底色同色相、亮度差 10–60」的低对比色。真机促销广告
    的「白字压浅黄底」就是这一档，之前的合成器只会按区域均值挑反色，等于把这一整段
    分布从训练集里删了 —— 实测这类图在 t14 上判 0.04，人眼却清清楚楚。
    `keep_lowcon=True`：跳过「对比度不够就换反色」的护栏（`ink_mode="low"` 必须配它，
    否则刚画好的低对比又被改回高对比）；描边兜底只在一半样本上保留。
    `px_range`：字号区间，纯色档用小字 + 大边距才能造出「字占比 35%」的松框。
    """
    px = int(round(float(rng.uniform(*(px_range or (28, 64))))))
    # 框边距必须覆盖「紧框 → 松框」整段，不能全用紧框。
    # 实测：真机提案器给的框比胶囊大 8px/边时，纯紧框训出来的模型 p 从 1.000 掉到 0.779
    # （图2 那个 162×66 vs 胶囊 146×50），直接掉到阈值 0.9 以下 ⇒ 链路漏检。
    # 30% 沿用旧紧框分布（保住「框必须套住字」这条），70% 按字高比例放宽到 8–34%。
    pad_ratio = (float(force_pad_ratio) if force_pad_ratio is not None
                 else (None if rng.random() < 0.30 else float(rng.uniform(0.08, 0.34))))
    line = None
    for _ in range(5):
        line, gh = compose_line(rng, text, px, gap_scale)
        line = deform_glyphs(line, rng)          # 字形畸变：抗「记住像素模板」
        pad = (int(force_pad_px) if force_pad_px is not None
               else (int(rng.integers(2, 11)) if pad_ratio is None
                     else max(2, int(round(pad_ratio * gh)))))
        bw = line.shape[1] + 2 * pad
        bh = line.shape[0] + 2 * pad
        if bw / max(1, bh) <= 5.4 and bw <= 340:
            break
        gap_scale *= 0.68
        px = max(11, int(px * 0.9))
    lh, lw = line.shape
    bw = int(min(340, lw + 2 * pad))
    bh = int(min(96, lh + 2 * pad))
    bg = pick_background(rng, frames, size=(bw, bh), busy=bg_busy,
                         src=bg_src).astype(np.float32)
    bh, bw = bg.shape[:2]

    # 字在框内**随机位置**：真机框常偏（提案器按亮度投的），且用户要求位置随机分布。
    # 只在没有余量时才退回 0（此时必然居中）。
    mx, my = max(0, bw - lw), max(0, bh - lh)
    ox = int(rng.integers(0, mx + 1))
    oy = int(rng.integers(0, my + 1))

    style = rng.random()
    # 强制某一种胶囊形态：真机「跳过」按钮三种都有 —— 实心半透明底、只有描边、完全无底
    # （纯白字压在广告画面上）。随机混着采会漏档，尤其「无底」在随机 20% 里是稀有事件。
    if force_style == "solid":
        style = 0.30
    elif force_style == "outline":
        style = 0.70
    elif force_style == "none":
        style = 0.95
    cap_box = None
    if style < 0.62:
        # 实心胶囊：黑/白/深蓝/深灰，alpha 0.15–0.95（半透明是常态）
        col = [int(c) for c in rng.choice([[0, 0, 0], [255, 255, 255], [16, 22, 40],
                                           [40, 40, 44], [8, 8, 8]])]
        a = (float(cap_alpha) if cap_alpha is not None
             else (float(rng.uniform(0.35, 0.95)) if rng.random() < 0.75
                   else float(rng.uniform(0.18, 0.5))))
        pad = int(rng.integers(2, max(3, min(bw - lw, bh - lh) // 2 + 3)))
        cw = max(3, min(bw, lw + 2 * pad))
        ch = max(3, min(bh, lh + 2 * pad))
        cx = int(np.clip(ox + lw // 2 - cw // 2, 0, bw - cw))
        cy = int(np.clip(oy + lh // 2 - ch // 2, 0, bh - ch))
        cap = rounded_alpha(cw, ch, int(ch * float(rng.uniform(0.35, 0.5))), value=a)
        patch = np.zeros((bh, bw), np.float32)
        patch[cy:cy + cap.shape[0], cx:cx + cap.shape[1]] = cap
        bg = blend(bg, patch, col)
        cap_box = (cx, cy, cw, ch)
    elif style < 0.80:
        # 描边胶囊：边框 alpha 也随机（0.25–0.95）
        col = [int(c) for c in rng.choice([[255, 255, 255], [0, 0, 0], [230, 230, 230]])]
        a = float(cap_alpha) if cap_alpha is not None else float(rng.uniform(0.25, 0.95))
        pad = int(rng.integers(2, 6))
        cw = max(3, min(bw, lw + 2 * pad))
        ch = max(3, min(bh, lh + 2 * pad))
        cx = int(np.clip(ox + lw // 2 - cw // 2, 0, bw - cw))
        cy = int(np.clip(oy + lh // 2 - ch // 2, 0, bh - ch))
        ring = rounded_alpha(cw, ch, int(ch * 0.45), value=a,
                             outline=max(1, int(round(ch * rng.uniform(0.04, 0.09)))))
        patch = np.zeros((bh, bw), np.float32)
        patch[cy:cy + ring.shape[0], cx:cx + ring.shape[1]] = ring
        bg = blend(bg, patch, col)
        cap_box = (cx, cy, cw, ch)

    # 字色跟着底色走：底色亮就用深字，底色暗就用浅字。
    # 纯随机选会造出「白胶囊 + 白字」这种整行看不见的废样本（第一版就有）。
    if cap_box is not None:                     # 有胶囊就以胶囊内部为准
        cx, cy, cw, ch = cap_box
        region = bg[cy:cy + ch, cx:cx + cw]
    else:
        region = bg[oy:oy + lh, ox:ox + lw]
    bg_lum = float(region.mean()) if region.size else 128.0
    if ink_mode == "low":
        # 低对比档：字色沿「底色 → 白/黑」方向插值到目标亮度差 10–60。
        # 真机促销广告的「白字压浅黄底」就是这一档 —— 人眼靠字形轮廓读得出来，
        # 但按区域均值挑反色的老逻辑永远造不出它（那套只会选白/黑）。
        b = (region.reshape(-1, 3).mean(axis=0).astype(np.float32) if region.size
             else np.array([128.0, 128.0, 128.0], np.float32))
        lum = float(0.299 * b[0] + 0.587 * b[1] + 0.114 * b[2])
        d = float(rng.uniform(10, 60)) * (1.0 if rng.random() < 0.5 else -1.0)
        target = float(np.clip(lum + d, 0.0, 255.0))
        if target >= lum:
            k = (target - lum) / max(1.0, 255.0 - lum)
            ink = b + (255.0 - b) * k
        else:
            k = (lum - target) / max(1.0, lum)
            ink = b * (1.0 - k)
        ink = np.clip(ink, 0, 255)
    elif rng.random() < 0.85:
        if bg_lum > 145:
            ink = [20, 20, 20] if rng.random() < 0.75 else [40, 44, 60]
        else:
            ink = [255, 255, 255] if rng.random() < 0.8 else [240, 240, 235]
    else:
        ink = rng.choice([[255, 255, 255], [240, 240, 240], [20, 20, 20], [255, 250, 205]])
    ink_a = 0.0 if blank else (
        float(rng.uniform(0.75, 1.0)) if rng.random() < 0.45 else 1.0)
    ink_layer = np.zeros((bh, bw), np.float32)
    paste_alpha(ink_layer, line, ox, oy, ink_a)
    bg_pre = bg
    ink = [int(c) for c in ink]

    # 对比度护栏：无底/低透明度时，「按区域均值选字色」遇到杂乱背景会选出灰底灰字 ——
    # 人眼都读不出「跳过」的样本，模型更不可能。实测这一档有 15% 掉到阈值以下。
    # 判据用「字像素 vs 非字像素的亮度差」，差太小就换反色重画。
    #
    # `keep_lowcon=True` 时**跳过这条**：这条护栏会把低对比样本强行改成高对比，
    # 于是训练集里「白字压亮黄底」整段消失 —— t14 在这种真机图上判 0.04，
    # 人眼却一清二楚。低对比是真实分布，必须留一部分给模型学。
    m = ink_layer > 0.45
    if not keep_lowcon and int(m.sum()) > 20 and (~m).any():
        lo = float(bg_pre[m].mean())
        hi = float(bg_pre[~m].mean())
        if abs(hi - lo) < 42.0:
            inv = [20, 20, 20] if lo > 127 else [255, 255, 255]
            if inv != ink:
                ink = inv

    # 局部对比兜底（彩色背景专用）：上面那条只看**整块均值**，可彩色底最常见的失败是
    # 「平均够、局部融进高光里」—— 白字压亮橙火焰、白字压雪地天空，均值差 100+ 但字
    # 有一小半和底同亮度，人眼已经读不出来。这里改用逐像素感知亮度取 20 分位，
    # 低于 35 就描一圈反色细边再落字（真机白字压花画面本来也常带阴影）。
    # 低对比档只在一半样本上描边：真机既有带阴影的，也有纯低对比什么修饰都没有的。
    if int(m.sum()) > 20 and (not keep_lowcon or rng.random() < 0.5):
        Ll = 0.299 * bg_pre[..., 0] + 0.587 * bg_pre[..., 1] + 0.114 * bg_pre[..., 2]
        ink_l = 0.299 * ink[0] + 0.587 * ink[1] + 0.114 * ink[2]
        if float(np.percentile(np.abs(ink_l - Ll[m]), 20)) < 35.0:
            ring = outline_ring(ink_layer, 5)
            oc = [0, 0, 0] if ink_l > 127 else [255, 255, 255]
            bg = blend(bg_pre, np.clip(ring * 0.92, 0.0, 1.0), oc)
            bg = blend(bg, ink_layer, ink)
        else:
            bg = blend(bg_pre, ink_layer, ink)
    else:
        bg = blend(bg_pre, ink_layer, ink)

    # 干扰文字块：真实广告的候选框里常常不止一块字（「加速版」框里还压着「0.1折」）。
    # 合成样本里从来没有这种构图，于是模型把「加速版」框判成 0.945（第 28 轮实测）。
    # 只给负样本加，不加正样本 —— 代价是「跳过旁边有大字」时可能漏，但漏点比误点安全。
    if extra_text and rng.random() < 0.55:
        other = neg_text(rng)
        px2 = max(9, int(round(gh * float(rng.uniform(0.28, 0.5)))))
        l2, gh2 = compose_line(rng, other, px2, 0.0)
        h2, w2 = l2.shape
        if w2 < bw - 4 and h2 < bh - 4:
            ox2 = int(rng.integers(0, bw - w2))
            oy2 = int(rng.integers(0, 4)) if rng.random() < 0.5 else int(bh - h2 - rng.integers(0, 4))
            oy2 = int(np.clip(oy2, 0, max(0, bh - h2)))
            lay2 = np.zeros((bh, bw), np.float32)
            paste_alpha(lay2, l2, ox2, oy2, float(rng.uniform(0.55, 1.0)))
            bg = blend(bg, lay2, [255, 255, 255] if bg.mean() < 128 else [20, 20, 20])

    # 背景随机干扰：真机框里常有装饰块/细线/色斑。避开字区，只压背景。
    prot = (max(0, ox - 4), max(0, oy - 4), min(bw, ox + lw + 4), min(bh, oy + lh + 4))
    bg = add_clutter(bg, rng, protect=prot, p=0.38)

    im = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8), "RGB")
    r = float(rng.uniform(0.0, 1.1))
    if r > 0.35:
        im = im.filter(ImageFilter.GaussianBlur(r))
    arr = np.asarray(im, np.float32)
    arr = tonal_jitter(arr, rng)
    if rng.random() < 0.3:                      # 传感器噪声
        arr = np.clip(arr + rng.normal(0, float(rng.uniform(2, 9)), arr.shape), 0, 255)
    arr = degrade(arr.astype(np.uint8), rng)
    return arr


def make_positive(rng, frames, gap_scale=None, cap_alpha=None):
    """一个「跳过」样本（源分辨率 numpy RGB）。"""
    if gap_scale is None:
        # 字距 0–1.0 字高（用户约束：不能超过一个字）
        gap_scale = float(rng.choice([0.0, 0.0, 0.0, 0.1, 0.25, 0.5, 0.75, 1.0]))
    # 字距拉开时不带前后缀，否则一行能拉出 6 倍宽，现实里没有这种胶囊
    if gap_scale > 0.3:
        text = SKIP
    else:
        text = LEAD[rng.integers(0, len(LEAD))] + SKIP + TAIL[rng.integers(0, len(TAIL))]
    arr = render_text_sample(rng, frames, text, gap_scale, cap_alpha)
    return arr, dict(text=text, gap=gap_scale, style="skip")


def make_loose_positive(rng, frames):
    """「跳过」松框样本（用户指定：用松框生成 5000 张）。

    和 `make_real_loose` 的区别：这条是 ttf 合成，框松但字仍然多样（8 种字体、
    任意框内位置、任意字距、任意胶囊）。真机只有 3 枚锚点胶囊，把它扩到 5000 张
    等于让模型反复看同样 3 个字形 —— 那是过拟合，不是数据。

    边距按**字高比例**给，但保留 30% 的「像素级紧框」（2–10 px）——
    链路的「内缩 8% 复测」会把 134×40 的框再收到 112×33，等于比任何紧框都紧；
    训练集若只有 10–40% 的松框，这一档就成了分布外，复测会把真胶囊自己判掉
    （v 版实测：参考图 raw 1.000、内缩后 0.281，整条链路漏检）。
    """
    gap = float(rng.choice([0.0, 0.0, 0.0, 0.0, 0.1, 0.25, 0.5]))
    text = SKIP if gap > 0.3 else LEAD[rng.integers(0, len(LEAD))] + SKIP + TAIL[rng.integers(0, len(TAIL))]
    if rng.random() < 0.30:
        pad_px, pad = int(rng.integers(2, 11)), None
    else:
        pad_px, pad = None, float(rng.uniform(0.05, 0.40))
    arr = render_text_sample(rng, frames, text, gap, force_pad_ratio=pad,
                             force_pad_px=pad_px)
    return arr, dict(text=text, gap=gap, style="loose",
                     pad=(pad_px if pad_px is not None else pad))


def make_translucent_positive(rng, frames):
    """「跳过」胶囊底色的**全透明度谱**样本（用户指定：背景不同透明度）。

    真机上这个按钮的底色差距极大：有的几乎全透明只剩白字，有的是 30% 黑底，
    有的是 90% 实心白底。随机混采时 alpha 会被「0.35–0.95 占 75%」这条分布吃掉，
    低透明度一档几乎采样不到，模型于是把「有底」当成判据 —— 真机遇到无底白字就漏。

    这里按档位**显式**采 alpha，并把三种形态都覆盖：
      none    18%  无底，字直接压画面（alpha 无意义）
      outline 12%  只有描边，边框 alpha 0.25–0.95
      solid   70%  实心底，alpha 按四档抽：0.05–0.22 / 0.22–0.45 / 0.45–0.72 / 0.72–1.00
    """
    r = float(rng.random())
    if r < 0.18:
        style, alpha = "none", None
    elif r < 0.30:
        style, alpha = "outline", float(rng.uniform(0.25, 0.95))
    else:
        style = "solid"
        lo, hi = [(0.05, 0.22), (0.22, 0.45), (0.45, 0.72), (0.72, 1.00)][int(rng.integers(0, 4))]
        alpha = float(rng.uniform(lo, hi))
    gap = float(rng.choice([0.0, 0.0, 0.0, 0.0, 0.1, 0.25, 0.5]))
    text = SKIP if gap > 0.3 else LEAD[rng.integers(0, len(LEAD))] + SKIP + TAIL[rng.integers(0, len(TAIL))]
    # 55% 用高纹理背景：半透明底的意义就在于「透过底还能看见背景」。底色若压在
    # 纯色块上，alpha 从 0.1 到 0.9 视觉上几乎没差，模型学不到 alpha 无关性。
    arr = render_text_sample(rng, frames, text, gap, alpha, force_style=style,
                             bg_busy=bool(rng.random() < 0.55))
    return arr, dict(text=text, gap=gap, style="alpha", alpha=alpha, cap=style)


def make_wall_cap_style(rng):
    """彩色壁纸样本的胶囊形态 + 底色不透明度（用户指定 10%–85%）。

    为什么把上限卡在 0.85 而不是 1.0：用户报的场景是「背景是彩色就认不出」 ——
    真机上彩色底图多半配**半透明**胶囊（全实心等于把背景遮死，反而好认）。
    0.10–0.85 正好是「背景色会透上来、字还得站得住」的整段，最吃字形判别力。
    """
    r = float(rng.random())
    if r < 0.12:
        return "none", None                      # 无底：字直接压彩色画面
    a = float(rng.uniform(0.10, 0.85))
    return ("outline", a) if r < 0.30 else ("solid", a)


def make_wall_positive(rng, frames=None):
    """彩色壁纸底 + 「跳过」—— 本次专项正样本。

    与 `make_translucent_positive` 的差别只在背景源：那边是真机广告帧（中性色为主），
    这里是高饱和壁纸。字体 / 框内位置 / 边距 / 字距 / 胶囊形态全部沿用同一套分布，
    否则模型会改学「背景彩色 = 负样本」而不是继续认字。
    """
    style, alpha = make_wall_cap_style(rng)
    gap = float(rng.choice([0.0, 0.0, 0.0, 0.0, 0.1, 0.25, 0.5]))
    text = SKIP if (gap > 0.3 or rng.random() < 0.60) else (
        LEAD[rng.integers(0, len(LEAD))] + SKIP + TAIL[rng.integers(0, len(TAIL))])
    if rng.random() < 0.35:
        pad_px, pad = int(rng.integers(2, 11)), None      # 紧框档，防「只认松框」
    else:
        pad_px, pad = None, float(rng.uniform(0.05, 0.38))
    arr = render_text_sample(rng, frames, text, gap, alpha, force_style=style,
                             bg_src="wall", force_pad_ratio=pad, force_pad_px=pad_px)
    return arr, dict(text=text, gap=gap, style="wall", alpha=alpha, cap=style)


def make_wall_negative(rng, frames=None):
    """彩色壁纸底 + **不是**「跳过」的文案 —— 用户要的「错例」。

    这一路是整个方案的关键。只在彩色底上堆正样本，模型会学成「背景一彩色就答 1」
    （反正训练集里彩色底清一色是正）。必须让同一张彩色背景上同时出现正、负两种
    文案，模型才会被逼着回到字形本身。

    三档：
      62% 别的文案 —— 固定词表 + 随机形近字，几何分布与 `make_wall_positive` 完全一致
      20% 形近字对 —— 挑过 / 桃过 / 跳边 这类，同字体同框位同胶囊，只换两个字里的一个
      18% 残缺「跳过」 —— 被切掉三到五成，标负（真机里常被框边切掉）

    为什么不用「单字」档：单字渲染出来的框天然又窄又方（一个字 + 小边距），
    模型会直接学「框不够宽 = 负」，在真机上遇到窄胶囊照样误判。单字负样本由
    `make_negative` 在真机帧背景上提供，那边是衬在随机宽框里的。
    """
    style, alpha = make_wall_cap_style(rng)
    r = float(rng.random())
    if r < 0.62:
        text = neg_text(rng)
        gap = 0.0 if len(text) <= 2 else float(rng.choice([0.0, 0.0, 0.1, 0.25]))
        arr = render_text_sample(rng, frames, text, gap, alpha, force_style=style,
                                 bg_src="wall", extra_text=True)
        return arr, dict(text=text, gap=gap, style="wall_neg", alpha=alpha, cap=style)

    if r < 0.82:
        text = similar_skip(rng)
        arr = render_text_sample(rng, frames, text, 0.0, alpha, force_style=style,
                                 bg_src="wall")
        return arr, dict(text=text, gap=0.0, style="wall_sim", alpha=alpha, cap=style)

    return make_wall_hard(rng, style, alpha)


def make_wall_hard(rng, style=None, alpha=None, src="wall"):
    """彩色/纯色底 + 被切角的「跳过」——标负。"""
    px = int(round(float(rng.uniform(30, 52))))
    line, _ = compose_line(rng, SKIP, px, 0.0)
    lh, lw = line.shape
    bg = pick_background(rng, None, size=(lw + int(rng.integers(6, 26)),
                                          int(lh * rng.uniform(1.3, 1.9))),
                         src=src).astype(np.float32)
    bh, bw = bg.shape[:2]
    cut = int(lw * float(rng.uniform(0.3, 0.55)))
    line = line[:, cut:] if rng.integers(0, 2) == 0 else line[:, :lw - cut]
    lh, lw = line.shape
    ox = int(np.clip((bw - lw) // 2, 0, max(0, bw - lw)))
    oy = int(np.clip((bh - lh) // 2, 0, max(0, bh - lh)))
    layer = np.zeros((bh, bw), np.float32)
    paste_alpha(layer, line, ox, oy)
    ink = [255, 255, 255] if float(bg.mean()) < 150 else [20, 20, 20]
    bg = blend(bg, layer, ink)
    im = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8), "RGB")
    arr = tonal_jitter(np.asarray(im, np.float32), rng, p=0.5)
    return degrade(arr.astype(np.uint8), rng), dict(text="cut", gap=0.0,
                                                   style=("flat_cut" if src == "flat"
                                                          else "wall_cut"))


def make_flat_cap_style(rng):
    """纯色底样本的胶囊形态：无底比例比壁纸档高（纯色底上「不画胶囊」很常见）。"""
    r = float(rng.random())
    if r < 0.22:
        return "none", None
    a = float(rng.uniform(0.10, 0.85))
    return ("outline", a) if r < 0.42 else ("solid", a)


def make_flat_positive(rng, frames=None):
    """纯色 / 近纯色底 + 「跳过」。

    与 `make_wall_positive` 的分工：壁纸档补「高饱和 + 高纹理」，这一档补
    「均匀底色 + 任意明度」，其中 42% 走 `ink_mode="low"`（字与底亮度差 10–60）。
    真机上这两种底是同一批广告里混着出现的，只补一种等于留一半洞。
    """
    style, alpha = make_flat_cap_style(rng)
    lowcon = rng.random() < 0.42
    gap = float(rng.choice([0.0, 0.0, 0.0, 0.0, 0.1, 0.25, 0.5]))
    # 用户要求「让模型学会看跳过这两个字」：58% 只画「跳过」，其余才带前后缀。
    # 前后缀的作用是防「框里必须是两个字」的捷径，但占比过高会把正样本的主形态冲淡。
    text = SKIP if (gap > 0.3 or rng.random() < 0.58) else (
        LEAD[rng.integers(0, len(LEAD))] + SKIP + TAIL[rng.integers(0, len(TAIL))])
    if rng.random() < 0.32:
        pad_px, pad = int(rng.integers(2, 9)), None          # 紧框档
    else:
        pad_px, pad = None, float(rng.uniform(0.08, 0.90))
    arr = render_text_sample(rng, frames, text, gap, alpha, force_style=style,
                             bg_src="flat", force_pad_ratio=pad, force_pad_px=pad_px,
                             ink_mode=("low" if lowcon else None),
                             keep_lowcon=lowcon, px_range=(20, 46))
    return arr, dict(text=text, gap=gap, style="flat", alpha=alpha,
                     cap=style, lowcon=lowcon)


def make_flat_negative(rng, frames=None):
    """纯色底 + 非「跳过」文案，标负（与 `make_wall_negative` 同三档）。"""
    style, alpha = make_flat_cap_style(rng)
    lowcon = rng.random() < 0.35
    r = float(rng.random())
    kw = dict(force_style=style, bg_src="flat",
              ink_mode=("low" if lowcon else None), keep_lowcon=lowcon)
    if r < 0.60:
        text = neg_text(rng)
        gap = 0.0 if len(text) <= 2 else float(rng.choice([0.0, 0.0, 0.1, 0.25]))
        arr = render_text_sample(rng, frames, text, gap, alpha, extra_text=True, **kw)
        return arr, dict(text=text, gap=gap, style="flat_neg", alpha=alpha, cap=style)
    if r < 0.88:
        text = similar_skip(rng)
        arr = render_text_sample(rng, frames, text, 0.0, alpha, **kw)
        return arr, dict(text=text, gap=0.0, style="flat_sim", alpha=alpha, cap=style)
    return make_wall_hard(rng, style, alpha, src="flat")


def make_wall_bg(rng):
    """纯彩色背景块（标负）：壁纸碎块本身最像「胶囊」的假阳性源。

    50% 走 `busy=True` 挑密集重复纹理（瓷砖格、格纹、花丛）—— 上一版训练里
    完全漏了这一路，结果 84 张留出块里有 4 张被判到 0.93–1.00。
    """
    return degrade(np.clip(pick_background(rng, None, src="wall",
                                           busy=bool(rng.random() < 0.5)),
                           0, 255).astype(np.uint8), rng)


def make_flat_bg(rng):
    """纯色背景块（标负）：t14 训练里 p_flat 一加，模型就可能学会「纯色底 = 跳过」。"""
    return degrade(np.clip(pick_background(rng, None, src="flat"), 0, 255).astype(np.uint8), rng)


# ── 圆形跳过按钮 ────────────────────────────────────────────────────────────
# 真机实测（2026-10-29 用户截图，1080×2352）：圆直径 103 px，内嵌横排「跳过」，
# 字高仅约 25 px（占圆直径 24%），外面还套了一圈更浅的半透明边。
# 这类样本此前一条都没有 —— 框是**正方**的，字只占中间一条，缩到 96×32 时
# 高度被压 3 倍，字形和所有「横长胶囊」样本都不在一个分布。
ROUND_OUTER = [[255, 255, 255], [236, 236, 238], [212, 216, 222]]
ROUND_INNER = [[44, 44, 48], [16, 16, 18], [96, 96, 100], [70, 74, 82], [130, 132, 136]]


def _round_cap(bg, rng):
    """在 bg 中央画一枚圆形按钮底（外圈浅 + 内圈深），返回新的 bg。

    先在一张 D×D 的 alpha 画布上画好整枚按钮，再一次性贴回去 —— 直接在 bg 上
    逐层切片赋值时，`bw != bh` 或 D 取整偏差会让切片短一行（实测踩过一次）。
    """
    bh, bw = bg.shape[:2]
    D = int(min(bw, bh))
    if D < 8:
        return bg
    canvas = np.zeros((D, D), np.float32)
    if rng.random() < 0.72:                      # 双层圆：截图里就是这种
        canvas = rounded_alpha(D, D, D // 2, value=float(rng.uniform(0.20, 0.55)))
        oc = ROUND_OUTER[int(rng.integers(0, len(ROUND_OUTER)))]
        lay = np.zeros((bh, bw), np.float32)
        y0, x0 = (bh - D) // 2, (bw - D) // 2
        lay[y0:y0 + D, x0:x0 + D] = canvas
        bg = blend(bg, lay, oc)
        iw = int(np.clip(int(D * float(rng.uniform(0.78, 0.90))), 8, D))
        canvas = np.zeros((D, D), np.float32)
        off = (D - iw) // 2
        canvas[off:off + iw, off:off + iw] = rounded_alpha(
            iw, iw, iw // 2, value=float(rng.uniform(0.35, 0.80)))
    else:
        canvas = rounded_alpha(D, D, D // 2, value=float(rng.uniform(0.35, 0.80)))
    ic = ROUND_INNER[int(rng.integers(0, len(ROUND_INNER)))]
    lay2 = np.zeros((bh, bw), np.float32)
    y0, x0 = (bh - D) // 2, (bw - D) // 2
    lay2[y0:y0 + D, x0:x0 + D] = canvas
    return blend(bg, lay2, ic)


def _round_sample(rng, frames, text, cut_frac=0.0, blank=False):
    """圆形按钮上的一行文案（正负共用，唯一差别是 text / 是否切角）。"""
    r = float(rng.random())
    bg_src = "flat" if r < 0.50 else ("wall" if r < 0.85 else None)
    px = int(round(float(rng.uniform(18, 34))))
    gap = 0.0 if rng.random() < 0.85 else float(rng.uniform(0.05, 0.22))
    line, _ = compose_line(rng, text, px, gap)
    if cut_frac > 0.0:                            # 被框边切掉一段，标负
        keep = max(4, int(line.shape[1] * (1.0 - cut_frac)))
        line = line[:, :keep] if rng.integers(0, 2) == 0 else line[:, -keep:]
    line = deform_glyphs(line, rng, p=0.55)
    lh, lw = line.shape
    # 圆的直径相对字宽 1.45–2.20 倍：真机实测 103/63 = 1.63。框再比圆大 0–20%，
    # 合起来字高只占框高 20–30% —— 这正是「正方框里一条小字」的极端框型。
    D = int(max(lw * float(rng.uniform(1.45, 2.20)), lh * 2.8))
    pad = int(D * float(rng.uniform(0.0, 0.20)))
    side = min(280, D + 2 * pad)                 # 圆形按钮的框是正方形
    bw = bh = side
    bg = pick_background(rng, frames, size=(bw, bh), src=bg_src).astype(np.float32)
    bh, bw = bg.shape[:2]
    bg = _round_cap(bg, rng)

    ink = [255, 255, 255] if rng.random() < 0.88 else [238, 238, 238]
    if rng.random() < 0.10:                       # 浅色圆配深字
        ink = [24, 24, 24]
    ox = int(np.clip((bw - lw) // 2 + int(rng.integers(-2, 3)), 0, max(0, bw - lw)))
    oy = int(np.clip((bh - lh) // 2 + int(rng.integers(-2, 3)), 0, max(0, bh - lh)))
    lay = np.zeros((bh, bw), np.float32)
    paste_alpha(lay, line, ox, oy, 0.0 if blank else float(rng.uniform(0.80, 1.0)))
    bg = blend(bg, lay, ink)

    prot = (max(0, ox - 4), max(0, oy - 4), min(bw, ox + lw + 4), min(bh, oy + lh + 4))
    bg = add_clutter(bg, rng, protect=prot, p=0.30)

    im = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8), "RGB")
    br = float(rng.uniform(0.0, 0.9))
    if br > 0.4:
        im = im.filter(ImageFilter.GaussianBlur(br))
    arr = tonal_jitter(np.asarray(im, np.float32), rng)
    if rng.random() < 0.3:
        arr = np.clip(arr + rng.normal(0, float(rng.uniform(2, 8)), arr.shape), 0, 255)
    return degrade(arr.astype(np.uint8), rng), dict(gap=gap, px=px, D=D,
                                                   bg=bg_src or "frame")


def make_loose_negative(rng, frames=None):
    """超松框 + 错文案 —— `make_loose2_positive` 的负样本对偶。

    没有这一路，模型会把「框里字很小、四周大片空白」直接当成「跳过」，
    因为它只在正样本里见过这种几何。桌面截图上的深色卡片（2.1W、充电加速中）
    正好是同一个形状，实测被判 1.000。
    """
    r = float(rng.random())
    bg_src = "flat" if r < 0.45 else ("wall" if r < 0.80 else None)
    sr = float(rng.random())
    style = "none" if sr < 0.25 else ("outline" if sr < 0.45 else "solid")
    alpha = None if style == "none" else float(rng.uniform(0.10, 0.85))
    text = neg_text(rng) if rng.random() < 0.75 else similar_skip(rng)
    if len(text) > 3:
        text = text[:3]
    arr = render_text_sample(rng, frames, text, 0.0, alpha, force_style=style,
                             bg_src=bg_src, extra_text=True,
                             force_pad_ratio=float(rng.uniform(0.45, 1.55)),
                             px_range=(16, 34))
    return arr, dict(text=text, gap=0.0, style="loose2_neg", alpha=alpha, cap=style)


def make_shape_negative(rng, frames=None):
    """**没有文字**的几何图形负样本：圆角块 / 线条 / 圆点。

    这是「见亮就点」的正面靶子。a16 桌面截图上的深色卡片、进度条、百分比、
    图标块，都只是「深色底 + 一行亮元素」；模型若把这套当成判据，就会在
    系统界面上乱点（实测一版在桌面 282 个窗口里 83 个判到 0.9 以上）。
    """
    r = float(rng.random())
    bg_src = "flat" if r < 0.60 else ("wall" if r < 0.85 else None)
    bw = int(rng.integers(60, 260))
    bh = int(rng.integers(28, 96))
    bg = pick_background(rng, frames, size=(bw, bh), src=bg_src).astype(np.float32)
    bh, bw = bg.shape[:2]
    im = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    lay = Image.new("RGBA", (bw, bh), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    col = [int(c) for c in rng.choice([[255, 255, 255], [0, 0, 0], [80, 220, 120],
                                       [60, 160, 255], [255, 200, 60], [240, 240, 240]])]
    a = int(rng.uniform(60, 225))
    for _ in range(int(rng.integers(1, 4))):
        k = float(rng.random())
        if k < 0.40:
            # 细描边圆角卡片、**内部透空** —— a16 充电页/设置页的卡片就是这种。
            # 这是实测最难压的假阳性：深色底 + 一圈 1–2 px 灰边，模型判 1.000。
            # 之前的「填充圆角块」跟它不是一回事：内部透空意味着框里几乎什么都没有。
            w2 = max(10, int(rng.uniform(0.30, 0.95) * bw))
            h2 = max(10, int(rng.uniform(0.40, 0.95) * bh))
            x0 = int(rng.uniform(0, max(1, bw - w2)))
            y0 = int(rng.uniform(0, max(1, bh - h2)))
            d.rounded_rectangle((x0, y0, x0 + w2, y0 + h2),
                                radius=max(3, int(h2 * 0.35)),
                                outline=tuple(col) + (a,), width=int(rng.integers(1, 3)))
        elif k < 0.62:                                 # 填充圆角块（按钮/卡片）
            w2 = max(4, int(rng.uniform(0.10, 0.50) * bw))
            h2 = max(4, int(rng.uniform(0.20, 0.80) * bh))
            x0 = int(rng.uniform(0, max(1, bw - w2)))
            y0 = int(rng.uniform(0, max(1, bh - h2)))
            d.rounded_rectangle((x0, y0, x0 + w2, y0 + h2),
                                radius=max(2, int(h2 * 0.3)), fill=tuple(col) + (a,))
        elif k < 0.82:                                 # 线条（进度条/分隔线）
            y = int(rng.uniform(0, max(1, bh - 1)))
            th = int(rng.integers(1, max(3, bh // 6)))
            x0 = int(rng.uniform(0, bw * 0.5))
            x1 = int(rng.uniform(min(x0 + 4, bw - 1), bw))
            d.rectangle((x0, y, x1, min(bh - 1, y + th)), fill=tuple(col) + (a,))
        else:                                          # 圆 / 圆点
            r0 = max(2, int(rng.uniform(2, max(3, min(bh, bw) // 4))))
            cx = int(rng.uniform(r0, max(r0 + 1, bw - r0)))
            cy = int(rng.uniform(r0, max(r0 + 1, bh - r0)))
            d.ellipse((cx - r0, cy - r0, cx + r0, cy + r0), fill=tuple(col) + (a,))
    out = Image.alpha_composite(im, lay).convert("RGB")
    arr = tonal_jitter(np.asarray(out, np.float32), rng)
    return degrade(arr.astype(np.uint8), rng)


def make_ui_negative(rng):
    """真机界面截图随机裁块，标负。

    覆盖「深色底 + 圆角卡片 + 标题字 / 大号时钟 / 百分比 / 图标」这一整类构图 ——
    合成样本画不出 ColorOS 的渲染细节（阴影、圆角半径、字重的灰度层次），
    实测纯合成负样本压不住这类误报。
    """
    ui = load_ui()
    if not ui:
        return make_shape_negative(rng, None)
    f = ui[int(rng.integers(0, len(ui)))]
    H, W = f.shape[:2]
    bw = int(rng.integers(60, max(61, min(320, W - 2))))
    bh = int(rng.integers(28, max(29, min(96, H - 2))))
    y = int(rng.integers(0, max(1, H - bh)))
    x = int(rng.integers(0, max(1, W - bw)))
    arr = f[y:y + bh, x:x + bw].astype(np.float32)
    if rng.random() < 0.35:
        arr = arr[:, ::-1].copy()
    arr = tonal_jitter(arr, rng, p=0.30)
    return degrade(arr.astype(np.uint8), rng)


def make_blank_negative(rng, frames=None):
    """同形状、**没有字**的硬负样本 —— 专门击穿「形状捷径」。

    实测 t15 在 a16 充电页上 282 个窗口里 83 个判 ≥0.9；把判分最高的窗口 dump 出来，
    几乎全是「深色底 + 圆角矩形边框 + 一个孤零零的字符」—— 模型把「深底 + 圆角块」
    本身当成了判据，字有没有压根不看。

    这一路把正样本的生成参数**原样复用**，只把墨迹透明度置 0：底、胶囊形状、边距、
    降质全部相同，唯一的差别就是「跳过」这两个字。合成负样本里没有比这更硬的。

    （真机界面截图也能当负样本，但要依赖设备停在哪一屏；形状抹字这条路不依赖任何外部状态。）
    """
    if rng.random() < 0.5:
        arr, meta = _round_sample(rng, frames, SKIP, 0.0, blank=True)
        meta.update(text="", style="blank_round")
        return arr, meta
    sr = float(rng.random())
    style = "none" if sr < 0.15 else ("outline" if sr < 0.45 else "solid")
    alpha = None if style == "none" else float(rng.uniform(0.10, 0.85))
    if rng.random() < 0.35:
        pad_px, pad = int(rng.integers(2, 9)), None
    else:
        pad_px, pad = None, float(rng.uniform(0.08, 0.90))
    arr = render_text_sample(rng, frames, SKIP, 0.0, alpha, force_style=style,
                             bg_src=("flat" if rng.random() < 0.6 else "wall"),
                             force_pad_ratio=pad, force_pad_px=pad_px,
                             px_range=(20, 46), blank=True)
    return arr, dict(text="", gap=0.0, style="blank_flat", alpha=alpha, cap=style)


def make_round_positive(rng, frames=None):
    """圆形按钮 + 「跳过」（真机最常见的一种极限框型，见 `_round_sample`）。"""
    arr, meta = _round_sample(rng, frames, SKIP, 0.0)
    meta["text"] = SKIP
    meta["style"] = "round"
    return arr, meta


def make_round_negative(rng, frames=None):
    """圆形按钮 + 非「跳过」文案，标负。

    没有这一路，模型会学成「圆形深底 = 跳过」：圆形按钮在真机上还常写「广告」
    「关闭」「跳过广告」，以及被框边切掉一半的「跳过」。
    """
    r = float(rng.random())
    if r < 0.55:
        text = neg_text(rng)
        arr, meta = _round_sample(rng, frames, text, 0.0)
        meta.update(text=text, style="round_neg")
        return arr, meta
    if r < 0.85:
        text = similar_skip(rng)
        arr, meta = _round_sample(rng, frames, text, 0.0)
        meta.update(text=text, style="round_sim")
        return arr, meta
    arr, meta = _round_sample(rng, frames, SKIP, float(rng.uniform(0.30, 0.55)))
    meta.update(text="cut", style="round_cut")
    return arr, meta


def make_loose2_positive(rng, frames=None):
    """超松框（字只占框高 28–50%）—— 链路提案器给的就是这种框。

    `make_loose_positive` 的边距上限只到 0.38×字高（字占比 ≥55%）。可圆形按钮
    的框上下各空半圆，字占比只有 24%；实测把 180×64 的紧框 pad 到 16 px，
    t14 从 1.000 直接掉到 0.000。这一档专门补 0.45–1.15×字高 的空白。
    """
    r = float(rng.random())
    bg_src = "flat" if r < 0.45 else ("wall" if r < 0.80 else None)
    sr = float(rng.random())
    style = "none" if sr < 0.25 else ("outline" if sr < 0.45 else "solid")
    alpha = None if style == "none" else float(rng.uniform(0.10, 0.85))
    gap = 0.0 if rng.random() < 0.85 else float(rng.uniform(0.05, 0.30))
    text = SKIP if (gap > 0.2 or rng.random() < 0.60) else (
        LEAD[rng.integers(0, len(LEAD))] + SKIP + TAIL[rng.integers(0, len(TAIL))])
    arr = render_text_sample(rng, frames, text, gap, alpha, force_style=style,
                             bg_src=bg_src,
                             force_pad_ratio=float(rng.uniform(0.45, 1.55)),
                             px_range=(16, 34))
    return arr, dict(text=text, gap=gap, style="loose2", alpha=alpha, cap=style)


def make_real_positive(rng, full, box):
    """从真机整图裁一枚已知胶囊，做轻度缩放/色调抖动 —— 防遗忘用。

    只做轻度扰动：这一路的目的是「别忘了真机像素长什么样」，抹太狠就失去锚点意义。
    """
    x0, y0, x1, y1 = [int(v) for v in box]
    im = Image.fromarray(full[y0:y1, x0:x1])
    # 0.82–1.10：下界要覆盖链路侧的「内缩 8% 复测」（两轴同时收 8% ≈ 0.846 倍），
    # 上界覆盖提案框偏松。只收不放的尺度分布会让模型把「比紧框再紧一点」当成另一个东西。
    s = float(rng.uniform(0.82, 1.10))
    w, h = im.size
    im = im.resize((max(8, int(w * s)), max(6, int(h * s))), Image.BILINEAR)
    a = tonal_jitter(np.asarray(im, np.float32), rng, p=0.5)
    return np.clip(a, 0, 255).astype(np.uint8)


def make_real_loose(rng, full, box):
    """真机胶囊 + 链路级松框 —— 这是真机上模型真正看到的框。

    链路提案器在 1/2 缩略图上按亮度投影给框，回到原分辨率每边普遍多 6–12 px。
    实测同一枚「跳过」：紧框 p=1.000，松 8px/边 p=0.779。`make_real_positive` 只放
    0.94–1.06 的缩放，永远碰不到这个区间，所以模型能在这类验证里满分、到真机链路漏检。

    从**整图**裁（不是裁紧框再 pad），这样框边带进来的是真实邻居内容 ——
    真机框里确实会压着旁边的字和图标。
    """
    x0, y0, x1, y1 = [int(v) for v in box]
    w, h = max(4, x1 - x0), max(4, y1 - y0)
    H, W = full.shape[:2]
    # 每边独立采样，允许左右/上下不对称（提案框本来就偏）。
    # 上限按链路实测收：图2 那枚胶囊 146×50，链路给 162×66 ⇒ 宽 5.5%/边、高 16%/边。
    px = [int(round(w * float(rng.uniform(0.02, 0.10)))),
          int(round(w * float(rng.uniform(0.02, 0.10))))]
    py = [int(round(h * float(rng.uniform(0.05, 0.20)))),
          int(round(h * float(rng.uniform(0.05, 0.20))))]
    nx0, ny0 = max(0, x0 - px[0]), max(0, y0 - py[0])
    nx1, ny1 = min(W, x1 + px[1]), min(H, y1 + py[1])
    im = Image.fromarray(full[ny0:ny1, nx0:nx1])
    a = tonal_jitter(np.asarray(im, np.float32), rng, p=0.5)
    return np.clip(a, 0, 255).astype(np.uint8)


def pick_status_crop(rng, frames):
    """状态栏条带：真机帧最上方 6% 那一条，直接切出来当负样本。

    为什么要单列这一类：状态栏里「月亮图标 + 5G + 信号条」正好是两三个并排的亮字形，
    提案器会把它当成胶囊候选。第 30 轮实测这类框被判到 0.715（阈值 0.9），
    而状态栏**每一帧屏幕都在**，一旦跨过阈值就是无限次乱点。
    合成负样本画不出图标行（字池里没有图标），只能拿真机帧切。
    """
    f = frames[int(rng.integers(0, len(frames)))]
    H, W = f.shape[:2]
    band = max(40, int(H * 0.06))
    bh = int(rng.integers(26, max(28, min(56, band))))
    bw = int(np.clip(bh * float(rng.uniform(1.6, 4.2)), 60, min(320, W - 1)))
    y = int(rng.integers(0, max(1, band - bh)))
    x = int(rng.integers(0, max(1, W - bw)))   # 时间在左、图标在右，两端都要覆盖
    return f[y:y + bh, x:x + bw].copy()


def make_status_neg(rng, frames):
    return degrade(pick_status_crop(rng, frames), rng)


def make_hard_positive(rng, frames):
    """「跳过」但当帧被切掉一角 —— 教模型别把残缺的也认成跳过（标负）。"""
    bg = pick_background(rng, frames).astype(np.float32)
    bh, bw = bg.shape[:2]
    px = max(9, int(bh * float(rng.uniform(0.5, 0.8))))
    line, _ = compose_line(rng, SKIP, px, 0.0)
    lh, lw = line.shape
    cut = int(lw * float(rng.uniform(0.3, 0.55)))
    side = rng.integers(0, 2)
    if side == 0:
        line = line[:, cut:]                    # 右边被切
    else:
        line = line[:, :lw - cut]               # 左边被切
    lh, lw = line.shape
    ox = int(np.clip((bw - lw) // 2, 0, max(0, bw - lw)))
    oy = int(np.clip((bh - lh) // 2, 0, max(0, bh - lh)))
    layer = np.zeros((bh, bw), np.float32)
    paste_alpha(layer, line, ox, oy)
    bg = blend(bg, layer, [255, 255, 255])
    im = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8), "RGB")
    return degrade(np.asarray(im, np.uint8), rng), dict(text="cut", gap=0.0, style="cut")


def make_negative(rng, frames):
    """三类负样本：别的文案 / 真机纯背景 / 只有一个字。

    「别的文案」走 `render_text_sample`，与正样本共用同一套字体 / 框内位置 / 边距 /
    胶囊 / 对比度分布，只换文案。分布不一致会让模型靠排版而不是字形判别 —— 第 28 轮
    实测：负样本用旧的固定窄边距居中渲染时，「微信」判 1.000、「加速版」判 0.993，
    装机就是乱点。
    """
    kind = rng.random()
    if kind < 0.62:
        # 固定词表只兜「真机踩过的坑」，其余走随机文案 —— 词表只有 60 个词，
        # 全靠它模型会过拟合到「不是这 60 个词就是负」。
        text = neg_text(rng)
        gap = 0.0 if len(text) <= 2 else float(rng.choice([0.0, 0.0, 0.1, 0.25]))
        return render_text_sample(rng, frames, text, gap, extra_text=True), dict(
            text=text, gap=gap, style="neg")

    bg = pick_background(rng, frames).astype(np.float32)
    bh, bw = bg.shape[:2]
    if kind >= 0.82:
        # 只有一个「跳」或「过」：单字绝不能判成跳过
        ch1 = SKIP[rng.integers(0, 2)]
        px = max(9, int(bh * float(rng.uniform(0.45, 0.8))))
        line, _ = compose_line(rng, ch1, px, 0.0)
        lh, lw = line.shape
        ox = int(np.clip((bw - lw) // 2, 0, max(0, bw - lw)))
        oy = int(np.clip((bh - lh) // 2, 0, max(0, bh - lh)))
        layer = np.zeros((bh, bw), np.float32)
        paste_alpha(layer, line, ox, oy)
        bg = blend(bg, layer, [255, 255, 255])

    im = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8), "RGB")
    if rng.random() < 0.6:
        im = im.filter(ImageFilter.GaussianBlur(float(rng.uniform(0, 1.0))))
    arr = np.asarray(im, np.float32)
    arr = tonal_jitter(arr, rng, p=0.5)
    arr = degrade(arr.astype(np.uint8), rng)
    return arr, dict(text="neg", gap=0.0, style="neg")


def tonal_jitter(arr, rng, p=0.55):
    """对比度 / 亮度 / 伽马随机抖动 —— 用户要的「背景对比度随机」。

    只做整体乘法是亮度抖动，不是对比度：围绕中点拉伸 `(x-128)*c+128` 才改对比度。
    真机广告底色从纯白到近黑都有，字与底的对比度从 1.1:1（灰字灰底）到 21:1；
    配合伽马还能造出「暗部压死、字边发灰」这类难样本。
    """
    if rng.random() >= p:
        return arr
    c = float(rng.uniform(0.45, 1.85))
    arr = (arr - 128.0) * c + 128.0 + float(rng.uniform(-22, 22))
    if rng.random() < 0.35:
        g = float(rng.uniform(0.7, 1.45))
        arr = 255.0 * np.power(np.clip(arr, 0, 255) / 255.0, g)
    return np.clip(arr, 0, 255)


def degrade(arr, rng):
    """按 1/s 下采样再放大，模拟「整帧被压过」的框质量。

    真实链路里提案器在压缩图上工作、模型回原分辨率裁框，所以模型原本看到的是清晰像素。
    但设备分辨率差异（1080/1440/2K）和截图被二次压缩时，框里的字确实会更糊。
    这里 s∈{1,1,2,3}：1/3 那档对应「一个 160×65 的胶囊只剩 54×22 像素」的情况。
    """
    s = int(rng.choice([1, 1, 1, 2, 3]))
    if s == 1:
        return arr
    im = Image.fromarray(arr, "RGB")
    w, h = im.size
    if w // s < 12 or h // s < 8:
        return arr
    small = im.resize((max(6, w // s), max(4, h // s)), Image.BILINEAR)
    return np.asarray(small.resize((w, h), Image.BILINEAR), np.uint8)


def to_input(arr, ow=IN_W, oh=IN_H):
    """源帧 → 96×32 → per-image per-channel 标准化（与 app 前处理一致）。

    ⚠️ 归一化轴必须是 (0, 1)：数组是 HWC，减的是「整幅图每通道」的均值/方差，
    对应 SkipNet.kt 里 `resampleInto` 累计的 `sr/sg/sb`（除以 plane=96×32）。
    写成 (1, 2) 就变成「逐行」标准化 —— 那是按每行 96 像素重新拉伸，行与行之间的
    亮度关系全被抹掉，而 app 侧从不这么做。这个错误一直存在：模型是在逐行口径下
    训的，却在逐通道口径下被调用，实测同一张松框图两种口径能差 1.000 vs 0.438。
    凡是重新训练都必须走这一版，否则权重与 app 前处理对不上。
    """
    im = resize_exact(Image.fromarray(arr, "RGB"), ow, oh)
    a = np.asarray(im, np.float32) / 255.0
    m = a.mean(axis=(0, 1), keepdims=True)
    s = a.std(axis=(0, 1), keepdims=True) + 1e-5
    return ((a - m) / s).transpose(2, 0, 1)


def load_frames(raw_dir, extra_dir):
    """真机裸帧（16B 头 + BGRA）+ PNG，全分辨率保留（框要贴原尺寸）。"""
    out = []
    if raw_dir and os.path.isdir(raw_dir):
        for name in sorted(os.listdir(raw_dir)):
            if not name.endswith(".raw"):
                continue
            with open(os.path.join(raw_dir, name), "rb") as f:
                head = f.read(16)
                w, h, fmt, _ = np.frombuffer(head, "<i4")
                if fmt != 1 or w < 100:
                    continue
                buf = np.frombuffer(f.read(), np.uint8)
            if buf.size < 4 * w * h:
                continue
            bgra = buf[:4 * w * h].reshape(h, w, 4)
            out.append(bgra[:, :, [2, 1, 0]].astype(np.uint8))
    if extra_dir and os.path.isdir(extra_dir):
        for name in sorted(os.listdir(extra_dir)):
            if name.endswith(".png"):
                im = Image.open(os.path.join(extra_dir, name)).convert("RGB")
                out.append(np.asarray(im, np.uint8))
    return out


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", default=r"D:/tmp/skip_probe/byfen_live2")
    ap.add_argument("--extra", default=r"D:/tmp/skip_probe/byfen_live")
    ap.add_argument("--out", default=r"D:/tmp/glyph_preview")
    ap.add_argument("--n", type=int, default=12)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    rng = np.random.default_rng(7)
    frames = load_frames(a.raw, a.extra)
    print("frames", len(frames), frames[0].shape)
    rows = []
    for i in range(a.n):
        g = [0.0, 0.3, 0.8, 1.5, 2.0, 0.0]
        arr, meta = make_positive(rng, frames, gap_scale=g[i % len(g)])
        rows.append((arr, f"{meta['text']} gap={g[i % len(g)]}"))
    for i in range(a.n // 2):
        arr, meta = make_negative(rng, frames)
        rows.append((arr, meta["text"]))
    # 等比展示：每格固定宽 320，高按原比例缩放，行高取该行最大值
    cells = [Image.fromarray(a) for a, _ in rows]
    scaled = [c.resize((320, max(24, int(c.height * 320 / c.width))), Image.BILINEAR) for c in cells]
    rh = 0
    sheet = Image.new("RGB", (320 * 3, sum(max(s.height for s in scaled[i:i + 3]) for i in range(0, len(scaled), 3)) + 8), (25, 25, 25))
    y = 0
    for i in range(0, len(scaled), 3):
        row = scaled[i:i + 3]
        rh = max(s.height for s in row)
        for j, s2 in enumerate(row):
            sheet.paste(s2, (320 * j, y))
        y += rh + 2
    p = os.path.join(a.out, "sheet.png")
    sheet.save(p)
    print("saved", p, sheet.size)
