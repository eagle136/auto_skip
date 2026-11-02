package com.v5yn133n3.skp.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.v5yn133n3.skp.data.RuleStore
import com.v5yn133n3.skp.priv.Capture
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.v5yn133n3.skp.Logs

/**
 * 字形匹配扫描：四角 → DoG 带通 → 剥线 → 连通域/滑窗 → 64×64 归一化 →
 * 与 [GlyphBank] 的位平面模板做 IoU → 「跳」左「过」右配对。
 *
 * 全部常量与 `R:\glyph_ocr\glyphmatch.py` 一一对应，改动任何一条都要两边同步；
 * 阈值是在 28 张真机截图上标定的，不是拍脑袋。
 *
 * 与 CNN 路（[SkipNet]）的分工：这条路只回答「这一帧四角有没有跳过、在哪」，
 * 不做通用文字识别，所以没有 90% 那类误报——它的负样本是「任何不是这两个字的
 * 形状」，用 IoU 直接卡死。
 */
object GlyphScan {

    private const val TAG = "SkipGlyph"

    // ---- 扫描区（只切四角：宽 40%W、高 25%H，中带实测无按钮）----
    private const val CELL_W_RATIO = 0.40f
    private const val CELL_H_RATIO = 0.25f

    // ---- 窗口 ----
    private const val WIN = 64
    private const val WIN_OVERLAP = 24
    private const val STRIDE = WIN - WIN_OVERLAP          // 40
    private const val REFINE_WIN = 80

    // ---- 阈值 ----
    /*
     * 判定线由首页滑条控制（K.GLYPH_GATE），这里只保留派生关系。
     *
     * 之前是 const，钉死在 0.62。问题是 TTF 字模和真机字体的 IoU 上限就在
     * 0.70–0.79，钉死等于「要么放过所有真按钮，要么全丢」，现场没法按误报
     * 情况找平衡。改成 getter 后，其它引用点一个字都不用改。
     */
    @Volatile private var gate = 0.62f

    /** 最近一次命中的 combo，首页滑条旁边显示，让人看着实际分数调。 */
    @Volatile var lastCombo = 0f
        private set

    /**
     * 最近一帧**没命中时**四角里最高的单字分。
     *
     * 为什么需要：`miss` 只说明「没触发」，分不清是「画面上压根没有这两个字」
     * 还是「有字但差一点没过线」。后者调阈值就能救，前者调阈值只会引入误报。
     * 真机「OCR 模式完全不触发」的排查全靠这个数字。
     */
    @Volatile var lastBest = 0f
        private set

    private val S_MIN: Float get() = gate
    private val S_MEAN: Float get() = min(0.99f, gate + 0.06f)
    private val EARLY_OK: Float get() = min(0.99f, gate + 0.08f)
    /** 复核触发线：比判定线低一档。但不得低于 0.50 —— 再低就全是噪声，白烧复核预算。 */
    private val REFINE_TRIGGER: Float get() = max(0.50f, gate - 0.07f)
    private val S_CHAR_MIN: Float get() = max(0.50f, gate - 0.07f)

    private const val REFINE_GOOD = 0.70f      // 复核时拿到这个分就早退
    private const val REFINE_BUDGET = 2        // 单块最多复核几个字（只复核最终配对的两字）
    private const val MAX_BOX_PER_VARIANT = 64 // 单遍最多打多少个候选框
    private const val QUICK_MIN = 0.30f

    /**
     * 「这块可能有字」的最低迹象分。
     *
     * 主力组合（scale(1,7) + t=14，共 2 遍）跑完还到不了这个分，说明这一角
     * 根本没有像样的字形 —— 剩下的 10 遍（另两个尺度 + t=24）不用跑了。
     *
     * 依据是消融实测（28 张真值图）：
     *   12 遍 → 28/28，2 遍 → 27/28，唯一的漏检那张最高只到 0.531。
     * 所以 0.45 这条线不会切掉任何一个真命中，却能把「正常界面（无跳过）」
     * 的遍数从 12 砍到 2 —— 那正是 6s 的来源。
     */
    private const val SIGNAL = 0.45f

    // ---- 形态学 ----
    private const val STRIP_LEN = 60
    private const val STRIP_THICK = 5
    private const val DIAG_LEN = 16
    private const val MIN_COMP_H = 9
    private const val MAX_COMP_H = 120

    private const val NORM = 64
    private const val NORM_INK = 52f
    private const val MAX_CAND = 400

    /**
     * 一帧的硬时间上限（ms）。
     *
     * 普通照片、小说阅读界面这类画面，每块能产出上千个候选（纹理/正文全都是
     * 连通域），12 遍跑满会到 15s —— 用户实测过。算法上没法保证「一定很快」，
     * 因为「没有跳过」这个结论本身就要求把四个角看完。
     *
     * 所以给硬上限：到点就用手上已有的结果结算，本帧剩下的角不再扫。
     * 配合 1s 节流，最坏也不会拖慢下一次尝试。
     */
    private const val FRAME_BUDGET_MS = 800L

    /** 连通域给的候选少于这个数，才动用滑窗兜底（滑窗很贵，只在必要时跑）。 */
    private const val SLIDE_MIN_BOXES = 8

    /*
     * 只保留两个尺度（原来是三个，多了 (3,15)）。
     *
     * 依据是 28 张真值图的消融：
     *   3 尺度 x 2 阈值 x 2 极性 = 12 遍 -> 28/28，Python 单图 90.1s
     *   2 尺度 x 2 阈值 x 2 极性 =  8 遍 -> 28/28，Python 单图 34.0s
     * (3,15) 这一档（大尺度，抓粗笔画）在这批样本上一次都没成为关键路径，
     * 却让每块多跑 4 遍全链路，是「普通照片 15s」的直接贡献者之一。
     */
    private val SCALES = arrayOf(intArrayOf(1, 7), intArrayOf(2, 11))
    private val POL_T = floatArrayOf(14f, 24f)

    /** 一帧的命中结果，坐标已换算成整帧坐标。 */
    class Hit(
        val cell: Int,
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val combo: Float,
        val tiao: Float,
        val guo: Float,
        val ms: Long,
        val pass: Int,
    ) {
        val textLeft: Int get() = x0
        val cx: Int get() = (x0 + x1) / 2
        val cy: Int get() = (y0 + y1) / 2
    }

    private class Cand(val x0: Int, val y0: Int, val x1: Int, val y1: Int,
                       val tiao: Float, val guo: Float, val ok: Boolean = false,
                       /** 配对结果里左/右候选的下标；-1 表示这不是配对出来的。 */
                       val idxL: Int = -1, val idxR: Int = -1) {
        val h: Int get() = y1 - y0
        val combo: Float get() = 0.6f * min(tiao, guo) + 0.4f * (tiao + guo) / 2f
    }

    /**
     * 每线程复用的临时缓冲。
     *
     * 为什么必须有：一个角要跑 12–36 遍「二值化 → 剥线 → 闭运算 → 连通域」，
     * 每遍都 new 25 万格的 BooleanArray/IntArray 的话，光分配就几百 MB，
     * GC 会把帧时间直接拉到秒级（真机 1200–11000ms 的主要来源之一）。
     *
     * 用 ThreadLocal 而不是全局字段：cornerPool 是 2 线程，共享会串数据。
     */
    private class Scratch {
        var bitA = LongArray(0)
        var bitB = LongArray(0)
        var bitC = LongArray(0)
        var seen = BooleanArray(0)
        var stack = IntArray(0)
        var inkIdx = IntArray(0)
        var integ = IntArray(0)
        var meanA = IntArray(0)
        var meanB = IntArray(0)
        var dog = IntArray(0)

        /** 本块最近一次 scanBlock 里见过的最好单字分，用来判断要不要补跑剩余组合。 */
        var lastBest = 0f

        fun bitsA(n: Int): LongArray { if (bitA.size < n) bitA = LongArray(n); return bitA }
        fun bitsB(n: Int): LongArray { if (bitB.size < n) bitB = LongArray(n); return bitB }
        fun bitsC(n: Int): LongArray { if (bitC.size < n) bitC = LongArray(n); return bitC }

        fun seenOf(n: Int): BooleanArray {
            if (seen.size < n) seen = BooleanArray(n)
            java.util.Arrays.fill(seen, 0, n, false)
            return seen
        }

        fun stackOf(n: Int): IntArray { if (stack.size < n) stack = IntArray(n); return stack }
        fun inkOf(n: Int): IntArray { if (inkIdx.size < n) inkIdx = IntArray(n); return inkIdx }
        fun integOf(n: Int): IntArray { if (integ.size < n) integ = IntArray(n); return integ }
        fun meanAOf(n: Int): IntArray { if (meanA.size < n) meanA = IntArray(n); return meanA }
        fun meanBOf(n: Int): IntArray { if (meanB.size < n) meanB = IntArray(n); return meanB }
        fun dogOf(n: Int): IntArray { if (dog.size < n) dog = IntArray(n); return dog }
    }

    /** 每线程一份，容量按历史最大值保留，不随帧缩回去。 */
    private val scratch = object : ThreadLocal<Scratch>() {
        override fun initialValue() = Scratch()
    }

    /**
     * 扫一帧。返回 null 表示四角都没有「跳过」。
     *
     * 每个角两轮：第一轮原区，没命中就把该角往画面中心方向扩 50% 再扫——
     * 后者是 y≈0.67 那种「跳过不在上/下 25% 内」的唯一保证，别删。
     */
    fun scan(ctx: Context, frame: Capture.Frame): Hit? {
        if (!GlyphBank.load(ctx)) return null
        gate = RuleStore.glyphGate
        val tAll = SystemClock.uptimeMillis()
        val deadline = tAll + FRAME_BUDGET_MS
        val sc = scratch.get()
        val w = frame.width
        val h = frame.height
        val cw = (w * CELL_W_RATIO).roundToInt()
        val ch = (h * CELL_H_RATIO).roundToInt()
        // id, x, y, w, h —— 编号沿用 3×3 的 1/3/7/9。
        // 顺序按实测命中率排：右上 22/28、左上 3/28、下两角 3/28。预算有限时
        // 先扫最可能的位置，右上没中再看别的，别让左下角先吃掉 200ms。
        val cells = arrayOf(
            intArrayOf(3, w - cw, 0, cw, ch),
            intArrayOf(1, 0, 0, cw, ch),
            intArrayOf(9, w - cw, h - ch, cw, ch),
            intArrayOf(7, 0, h - ch, cw, ch),
        )
        // 每个角在 pass0 里的「迹象分」。pass1（外扩 50%）只为兜住「跳过不在
        // 上/下 25% 内」的版式，但如果这一角 pass0 连一个像样字形都没见过，
        // 扩出去更不可能有 —— 直接跳过，正常界面因此再省一半。
        val signal = FloatArray(cells.size)
        var bestAll = 0f
        for (pass in 0..1) {
            for (ci in cells.indices) {
                val c = cells[ci]
                if (pass == 1 && signal[ci] < SIGNAL) continue
                var x = c[1]
                var y = c[2]
                var bw = c[3]
                var bh = c[4]
                if (pass == 1) {
                    // 往中心方向扩 50%，贴边侧不动
                    val eh = bh / 2
                    val ew = bw / 2
                    if (y > 0) { y = max(0, y - eh); bh += eh }
                    if (y + bh < h) bh += eh
                    if (x > 0) { x = max(0, x - ew); bw += ew }
                    if (x + bw < w) bw += ew
                    bh = min(bh, h - y)
                    bw = min(bw, w - x)
                }
                if (SystemClock.uptimeMillis() > deadline) {
                    Logs.i(TAG, "budget exhausted pass=$pass cell=" + c[0])
                    break
                }
                val gray = Capture.luminance(frame, x, y, bw, bh)
                // 外扩轮（pass=1）先把低对比度块拉一遍：白字压浅色云图/浅灰广告图时
                // 字和背景只差 15–20 级，DoG 差分刚在 POL_T=14 边上，笔画被切断。
                if (pass == 1) stretchContrast(gray, bw * bh)
                val best = scanBlock(gray, bw, bh, pass == 1, sc, deadline)
                signal[ci] = sc.lastBest
                if (sc.lastBest > bestAll) bestAll = sc.lastBest
                if (best != null) {
                    val hit = Hit(
                        c[0], best.x0 + x, best.y0 + y, best.x1 + x, best.y1 + y,
                        combo = 0.6f * min(best.tiao, best.guo) +
                            0.4f * (best.tiao + best.guo) / 2f,
                        tiao = best.tiao, guo = best.guo,
                        ms = SystemClock.uptimeMillis() - tAll, pass = pass,
                    )
                    if (best.ok) {
                        lastCombo = hit.combo
                        // 命中帧也要写 lastBest，否则 idle 汇总会显示 best=0.00，
                        // 看起来像「一个字都没认出来」——2026-11-02 真机就是这么被误导的。
                        lastBest = max(bestAll, hit.combo)
                        Logs.i(TAG, "hit cell=${hit.cell} pass=$pass box=${hit.x0},${hit.y0}," +
                            "${hit.x1},${hit.y1} combo=${fmt(hit.combo)} " +
                            "跳=${fmt(hit.tiao)} 过=${fmt(hit.guo)} ms=${hit.ms}")
                        return hit
                    }
                }
            }
        }
        // 无命中也要留一行：best 是四角最高的单字分，用来区分「没字」和「差点过线」
        lastBest = bestAll
        Logs.i(TAG, "miss best=${fmt(bestAll)} gate=${fmt(gate)} ms=${SystemClock.uptimeMillis() - tAll}")
        return null
    }

    /**
     * 只给一个框打分（CornerTask 用不到，留给 ImageTestActivity 做逐框回归）。
     * 框外扩 1.3 倍取像素，在框内重跑一遍链路；命中返回 combo，否则 0。
     */
    fun scoreBox(ctx: Context, frame: Capture.Frame, x0: Int, y0: Int, bw: Int, bh: Int): Float {
        if (!GlyphBank.load(ctx)) return 0f
        gate = RuleStore.glyphGate
        val padX = (bw * 0.15f).roundToInt()
        val padY = (bh * 0.15f).roundToInt()
        val x = max(0, x0 - padX)
        val y = max(0, y0 - padY)
        val w = min(frame.width - x, bw + padX * 2)
        val h = min(frame.height - y, bh + padY * 2)
        if (w < 16 || h < 16) return 0f
        val gray = Capture.luminance(frame, x, y, w, h)
        val best = scanBlock(
            gray, w, h, allowPartial = false, sc = scratch.get(),
            deadline = SystemClock.uptimeMillis() + FRAME_BUDGET_MS,
        ) ?: return 0f
        val combo = 0.6f * min(best.tiao, best.guo) + 0.4f * (best.tiao + best.guo) / 2f
        return if (combo >= S_MIN) combo else 0f
    }

    // ---------------------------------------------------------------- 块扫描

    /**
     * 低对比度块的线性拉伸（2%–98% 分位）。
     *
     * 为什么需要：白字压在浅色云图 / 浅灰广告图上时，字和局部背景只差 15–20 级，
     * DoG 出来的差分正好卡在 POL_T=14 边上，笔画被二值化切碎，单字分上不去、
     * 组合分永远差一点点。真机 2026-11-02 中国移动云盘就是这样：四秒五帧
     * best 稳定停在 0.695–0.698，判定线过不去。
     *
     * 为什么只在外扩轮（pass=1）用：pass=0 是主力判定，必须保持原图口径 ——
     * 已经能过的样本（0.685–0.700）不能因为换了预处理而改分数；pass=1 本来
     * 就是主力没命中时对「有字形迹象的角落」的补救轮，动它不牵连正常帧。
     *
     * 两个不拉的条件：span ≥ 128 说明对比本来就够（正常白底黑字）；
     * span < 24 说明块内几乎纯色，拉起来只会把压缩噪声放大成假笔画。
     */
    private fun stretchContrast(gray: IntArray, n: Int) {
        val hist = IntArray(256)
        for (i in 0 until n) hist[gray[i]]++
        val cut = max(1, (n * 0.02f).toInt())
        var acc = 0
        var lo = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= cut) { lo = v; break }
        }
        acc = 0
        var hi = 255
        for (v in 255 downTo 0) {
            acc += hist[v]
            if (acc >= cut) { hi = v; break }
        }
        val span = hi - lo
        if (span >= 128 || span < 24) return
        val k = 255f / span
        for (i in 0 until n) {
            val v = ((gray[i] - lo) * k).toInt()
            gray[i] = if (v < 0) 0 else if (v > 255) 255 else v
        }
        Logs.i(TAG, "stretch lo=$lo hi=$hi span=$span k=${"%.2f".format(k)}")
    }

    private fun scanBlock(gray: IntArray, w: Int, h: Int, allowPartial: Boolean,
                          sc: Scratch, deadline: Long): Cand? {
        val n = w * h
        val integ = integral(gray, w, h, sc.integOf((w + 1) * (h + 1)))
        val hits = HashMap<Long, Cand>(256)
        var refineLeft = REFINE_BUDGET
        sc.lastBest = 0f
        // si/ti 只为识别「主力组合跑完了」，不改变遍历顺序：
        // SCALES[0]=(1,7) + POL_T[0]=14 就是消融里保住 27/28 的那两个组合。
        block@ for (si in SCALES.indices) {
            val scale = SCALES[si]
            val small = boxMean(integ, w, h, scale[0], sc.meanAOf(n))
            val large = boxMean(integ, w, h, scale[1], sc.meanBOf(n))
            val dog = sc.dogOf(n)
            for (i in 0 until n) dog[i] = small[i] - large[i]
            for (ti in POL_T.indices) {
                val t = POL_T[ti]
                for (sign in 1..2) {
                    val s = if (sign == 1) 1 else -1
                    var raw = BooleanArray(n)
                    var cnt = 0
                    for (i in 0 until n) {
                        if (s * dog[i] > t) { raw[i] = true; cnt++ }
                    }
                    if (cnt < 60) continue
                    raw = stripThinLines(raw, w, h)
                    if (count(raw) < 60) continue

                    refineLeft -= scanVariant(
                        raw, gray, w, h, allowPartial, hits, sc, refineLeft, deadline,
                    )
                    quickPair(hits)?.let { return it }

                    // 斜线剥离只在「外扩块 + 原始掩膜没找到像样单字」时才跑。
                    // 光带把相邻两字粘成一块时原始掩膜给不出候选，这时才需要；
                    // 正常帧一律走不到这里，等于白省两遍全块形态学。
                    if (allowPartial && bestChar(hits) < REFINE_TRIGGER) {
                        for (ang in intArrayOf(1, -1)) {
                            val sd = stripDiag(raw, w, h, ang)
                            if (count(sd) <= count(raw) - 40) {
                                refineLeft -= scanVariant(
                                    sd, gray, w, h, allowPartial, hits, sc, refineLeft,
                                    deadline,
                                )
                                quickPair(hits)?.let { return it }
                            }
                        }
                    }
                }
                // ★ 主力组合（2 遍）跑完就结账：
                //   有配对 → 直接返回；连迹象都没有 → 这一角放弃，不跑剩下的 10 遍。
                //   正常界面（无跳过）永远停在这里，这是 6s → 数百 ms 的那一刀。
                // 块内也要看预算：一块 12 遍，候选爆炸时单块就能吃掉几秒
                if (SystemClock.uptimeMillis() > deadline) break@block
                if (si == 0 && ti == 0) {
                    quickPair(hits)?.let { sc.lastBest = bestChar(hits); return it }
                    val b = bestChar(hits)
                    sc.lastBest = b
                    if (b < SIGNAL) return null
                }
            }
        }
        sc.lastBest = if (hits.isEmpty()) 0f else bestChar(hits)
        if (hits.isEmpty()) return null
        val list = ArrayList(hits.values)
        if (list.size > MAX_CAND) {
            list.sortByDescending { max(it.tiao, it.guo) }
            while (list.size > MAX_CAND) list.removeAt(list.size - 1)
        }
        val pair = pickPair(list) ?: return null

        // ★ 复核只针对「这一块最终要返回的那一对字」。
        //
        // 复核的意义是「把要点的那个字重新框准、重新打分」，对没配成对的候选
        // 毫无价值：无跳过画面里那些 0.55–0.65 的候选复核完还是过不了线，
        // 纯粹白烧。改成配对后再复核，单块最多 2 次（原来 12 次），
        // 而命中帧的行为完全不变（真字本来就是 pickPair 选出的最佳配对）。
        if (pair.combo < REFINE_TRIGGER || pair.idxL < 0 || pair.idxR < 0) return pair
        val cl = list[pair.idxL]
        val cr = list[pair.idxR]
        val rl = refine80(gray, w, h, cl.x0, cl.y0, cl.x1, cl.y1, sc)
        val rr = refine80(gray, w, h, cr.x0, cr.y0, cr.x1, cr.y1, sc)
        if (rl == null && rr == null) return pair
        val tiao = if (rl != null) GlyphBank.score(rl.shape, rl.area, GlyphBank.CH_TIAO) else cl.tiao
        val guo = if (rr != null) GlyphBank.score(rr.shape, rr.area, GlyphBank.CH_GUO) else cr.guo
        val mean = (tiao + guo) / 2f
        val combo = 0.6f * min(tiao, guo) + 0.4f * mean
        // 复核后分数掉了也不能比没复核更差，否则宁可用原始分
        if (combo < pair.combo) return pair
        val nbL = if (rl != null) intArrayOf(rl.x0, rl.y0, rl.x1, rl.y1)
        else intArrayOf(cl.x0, cl.y0, cl.x1, cl.y1)
        val nbR = if (rr != null) intArrayOf(rr.x0, rr.y0, rr.x1, rr.y1)
        else intArrayOf(cr.x0, cr.y0, cr.x1, cr.y1)
        val ok = tiao >= S_MIN && guo >= S_MIN && mean >= S_MEAN
        return Cand(
            min(nbL[0], nbR[0]), min(nbL[1], nbR[1]),
            max(nbL[2], nbR[2]), max(nbL[3], nbR[3]),
            tiao, guo, ok,
        )
    }

    /** 块内单字最高分（不管左右配对），用来判断要不要再试别的掩膜变体。 */
    private fun bestChar(hits: HashMap<Long, Cand>): Float {
        var m = 0f
        for (c in hits.values) {
            val v = max(c.tiao, c.guo)
            if (v > m) m = v
        }
        return m
    }

    /**
     * 块内是否已经凑出一对「够分的跳过」。
     *
     * 命中帧绝大多数在第一个尺度、第一个阈值就出结果，早退能砍掉后面
     * 十几遍全块形态学；未命中帧它每次都返回 null，只多花一次 O(n) 扫描。
     */
    private fun quickPair(hits: HashMap<Long, Cand>): Cand? {
        if (hits.size < 2) return null
        var hasT = false
        var hasG = false
        for (c in hits.values) {
            if (c.tiao >= S_MIN) hasT = true
            if (c.guo >= S_MIN) hasG = true
            if (hasT && hasG) break
        }
        if (!hasT || !hasG) return null
        val p = pickPair(ArrayList(hits.values)) ?: return null
        return if (p.ok && p.combo >= EARLY_OK) p else null
    }

    /**
     * 一遍掩膜的全部候选。返回本次用掉的复核次数（供块级预算扣减）。
     *
     * 候选多的时候先按「字高接近 36px」截断：噪声块的字高是散的，真字的
     * 字高集中在 30–45，先砍掉远处的才不会把复核预算耗在背景纹理上。
     */
    private fun scanVariant(
        rv: BooleanArray, gray: IntArray, w: Int, h: Int,
        allowPartial: Boolean, hits: HashMap<Long, Cand>,
        sc: Scratch, refineBudget: Int, deadline: Long,
    ): Int {
        var refined = 0
        val mask = closeStrokes(rv, w, h, sc)
        val raw = ArrayList<IntArray>(64)
        for (c in labelComps(mask, w, h, sc)) raw.addAll(splitWide(mask, w, h, c))

        // 滑窗只是「连通域兜底」：它存在的意义是细笔画被切开、连通域给不出框的
        // 时候还留一条路。连通域已经给出足够候选（小说正文、照片纹理都是成百上
        // 千）时再跑 192 个窗口 x 每窗 4096 像素，纯属白烧 —— 这是普通照片跑到
        // 15s 的主因。
        if (raw.size < SLIDE_MIN_BOXES) {
            raw.addAll(slideBoxes(mask, w, h, allowPartial, MAX_BOX_PER_VARIANT - raw.size))
        }

        // 先按尺寸/宽高比筛掉大块背景，再截断。原来截断在前、过滤在后，等于把
        // 预算花在一堆注定被丢的框上（每个框都要归一化 + 12 模板打分）。
        val boxes = ArrayList<IntArray>(min(raw.size, MAX_BOX_PER_VARIANT))
        for (b in raw) {
            val bh = b[3] - b[1]
            val bw = b[2] - b[0]
            if (bh < 10 || bh > 80) continue
            if (bw < 0.42f * bh || bw > 2.0f * bh) continue
            boxes.add(b)
        }
        if (boxes.size > MAX_BOX_PER_VARIANT) {
            boxes.sortBy { abs((it[3] - it[1]) - 36) }
            while (boxes.size > MAX_BOX_PER_VARIANT) boxes.removeAt(boxes.size - 1)
        }
        var tick = 0
        for (bb in boxes) {
            // 每 16 个框看一眼表。单遍就要归一化+打分 64 个框，被上千连通域
            // 撑起来的画面光这一遍就能过百毫秒，只在遍间检查来不及。
            if ((++tick and 15) == 0 && SystemClock.uptimeMillis() > deadline) break
            var x0 = bb[0]; var y0 = bb[1]; var x1 = bb[2]; var y1 = bb[3]
            val bh = y1 - y0
            val bw = x1 - x0
            if (bh < 10 || bh > 80) continue
            if (bw < 0.42f * bh || bw > 2.0f * bh) continue
            var key = keyOf(x0, y0, x1, y1)
            if (hits.containsKey(key)) continue

            var shape = normShape(rv, w, h, x0, y0, x1, y1) ?: continue
            var area = area(shape)
            if (area < 40) continue
            if (GlyphBank.quick(shape, area) < QUICK_MIN) continue
            val st = GlyphBank.score(shape, area, GlyphBank.CH_TIAO)
            val sg = GlyphBank.score(shape, area, GlyphBank.CH_GUO)
            // 复核（refine80）不在这里做 —— 见 scanBlock 末尾。
            // 以前每个 ≥ REFINE_TRIGGER 的候选都复核一次，单块预算 12 次；
            // 而无跳过的界面每块都能凑出十几个 0.55+ 的候选，预算全烧在
            // 「复核一个根本不会点的字」上，8 块 × 12 次 × 36 遍就是 6s 的来源。
            hits[key] = Cand(x0, y0, x1, y1, st, sg)
        }
        return refined
    }

    private fun keyOf(x0: Int, y0: Int, x1: Int, y1: Int): Long =
        (x0 / 2).toLong() or ((y0 / 2).toLong() shl 12) or
            ((x1 / 2).toLong() shl 24) or ((y1 / 2).toLong() shl 36)

    /** 80×80 复核：窗内重做二值化+分割，取离原框最近的单字框重新归一化。 */
    private class Refined(val x0: Int, val y0: Int, val x1: Int, val y1: Int,
                          val shape: LongArray, val area: Int)

    private fun refine80(gray: IntArray, w: Int, h: Int,
                         bx0: Int, by0: Int, bx1: Int, by1: Int,
                         sc: Scratch): Refined? {
        val cx = (bx0 + bx1) / 2f
        val cy = (by0 + by1) / 2f
        var x0 = (cx - REFINE_WIN / 2f).roundToInt()
        var y0 = (cy - REFINE_WIN / 2f).roundToInt()
        x0 = max(0, min(max(0, w - REFINE_WIN), x0))
        y0 = max(0, min(max(0, h - REFINE_WIN), y0))
        val pw = min(REFINE_WIN, w - x0)
        val ph = min(REFINE_WIN, h - y0)
        if (pw < 24 || ph < 24) return null
        val patch = IntArray(pw * ph)
        for (yy in 0 until ph) {
            System.arraycopy(gray, (y0 + yy) * w + x0, patch, yy * pw, pw)
        }
        val oh = by1 - by0
        val ow = bx1 - bx0
        val pn = pw * ph
        val integ = integral(patch, pw, ph, sc.integOf((pw + 1) * (ph + 1)))
        var bestScore = 0f
        var best: Refined? = null
        for (scale in SCALES) {
            val small = boxMean(integ, pw, ph, scale[0], sc.meanAOf(pn))
            val large = boxMean(integ, pw, ph, scale[1], sc.meanBOf(pn))
            val dog = sc.dogOf(pn)
            for (i in 0 until pn) dog[i] = small[i] - large[i]
            for (t in POL_T) {
                for (sign in 1..2) {
                    val s = if (sign == 1) 1 else -1
                    var raw0 = BooleanArray(pn)
                    var cnt = 0
                    for (i in 0 until pn) {
                        if (s * dog[i] > t) { raw0[i] = true; cnt++ }
                    }
                    if (cnt < 60) continue
                    raw0 = stripThinLines(raw0, pw, ph)
                    if (count(raw0) < 60) continue

                    // 先原始掩膜；拿到 S_CHAR_MIN 以上就收工，不再剥斜线。
                    val r0 = refineOnMask(raw0, pw, ph, x0, y0, cx, cy, oh, ow, sc)
                    if (r0 != null && r0.first > bestScore) {
                        bestScore = r0.first
                        best = r0.second
                    }
                    if (bestScore >= REFINE_GOOD) return best

                    for (ang in intArrayOf(1, -1)) {
                        val sd = stripDiag(raw0, pw, ph, ang)
                        if (count(sd) > count(raw0) - 40) continue
                        val r = refineOnMask(sd, pw, ph, x0, y0, cx, cy, oh, ow, sc)
                        if (r != null && r.first > bestScore) {
                            bestScore = r.first
                            best = r.second
                        }
                        if (bestScore >= REFINE_GOOD) return best
                    }
                }
            }
        }
        if (bestScore < S_CHAR_MIN) return null
        return best
    }

    /** 在一片（80 窗内的）掩膜上找最像「跳/过」的单字，返回 (最高分, 框)。 */
    private fun refineOnMask(
        rv: BooleanArray, pw: Int, ph: Int, ox: Int, oy: Int,
        cx: Float, cy: Float, oh: Int, ow: Int, sc: Scratch,
    ): Pair<Float, Refined>? {
        val mask = closeStrokes(rv, pw, ph, sc)
        var bestScore = 0f
        var best: Refined? = null
        for (bb in labelComps(mask, pw, ph, sc)) {
            for (cell in splitWide(mask, pw, ph, bb)) {
                val chh = cell[3] - cell[1]
                val cww = cell[2] - cell[0]
                if (chh < 10 || chh > 80) continue
                if (cww < 0.42f * chh || cww > 2.0f * chh) continue
                val ccx = (cell[0] + cell[2]) / 2f + ox
                val ccy = (cell[1] + cell[3]) / 2f + oy
                val lim = 0.6f * max(oh, chh)
                if (abs(ccx - cx) > lim || abs(ccy - cy) > lim) continue
                if (cww < 0.5f * ow || cww > 1.6f * ow) continue
                val shape = normShape(rv, pw, ph, cell[0], cell[1], cell[2], cell[3])
                    ?: continue
                val ar = area(shape)
                if (ar < 40) continue
                val st = GlyphBank.score(shape, ar, GlyphBank.CH_TIAO)
                val sg = GlyphBank.score(shape, ar, GlyphBank.CH_GUO)
                val scv = max(st, sg)
                if (scv > bestScore) {
                    bestScore = scv
                    best = Refined(cell[0] + ox, cell[1] + oy,
                        cell[2] + ox, cell[3] + oy, shape, ar)
                    if (scv >= REFINE_GOOD) return Pair(scv, best)
                }
            }
        }
        return if (best != null) Pair(bestScore, best) else null
    }

    // ---------------------------------------------------------------- 配对

    private fun pickPair(list: List<Cand>): Cand? {
        var best: Cand? = null
        var bestCombo = -1f
        var bestOk = false
        for (i in list.indices) {
            val vi = list[i]
            val hi = vi.h
            if (hi <= 0) continue
            val cyi = (vi.y0 + vi.y1) / 2f
            for (j in list.indices) {
                if (i == j) continue
                val vj = list[j]
                if (vj.x0 <= vi.x0 + 2) continue
                val hj = vj.h
                if (hj <= 0) continue
                val ratio = hi.toFloat() / hj
                if (ratio < 0.75f || ratio > 1.33f) continue
                val cyj = (vj.y0 + vj.y1) / 2f
                if (abs(cyj - cyi) > 0.30f * max(hi, hj)) continue
                val gap = vj.x0 - vi.x1
                if (gap < -0.10f * hi || gap > 0.55f * hi) continue
                val mean = (vi.tiao + vj.guo) / 2f
                val combo = 0.6f * min(vi.tiao, vj.guo) + 0.4f * mean
                val ok = vi.tiao >= S_MIN && vj.guo >= S_MIN && mean >= S_MEAN
                if (combo > bestCombo || (combo == bestCombo && ok && !bestOk)) {
                    bestCombo = combo
                    bestOk = ok
                    best = Cand(
                        min(vi.x0, vj.x0), min(vi.y0, vj.y0),
                        max(vi.x1, vj.x1), max(vi.y1, vj.y1),
                        vi.tiao, vj.guo, ok, i, j,
                    )
                }
            }
        }
        return best
    }

    // ---------------------------------------------------------------- 基础算子

    private fun integral(src: IntArray, w: Int, h: Int, s: IntArray): IntArray {
        val iw = w + 1
        // 复用缓冲：第 0 行与第 0 列必须是 0，其余会被完整覆盖
        java.util.Arrays.fill(s, 0, iw, 0)
        for (y in 0 until h) s[(y + 1) * iw] = 0
        for (y in 0 until h) {
            var rowSum = 0
            val so = (y + 1) * iw
            val sp = y * iw
            val ro = y * w
            for (x in 0 until w) {
                rowSum += src[ro + x]
                s[so + x + 1] = s[sp + x + 1] + rowSum
            }
        }
        return s
    }

    /** 一次积分图算任意半径的盒均值（边界取实际面积，不做 padding）。 */
    private fun boxMean(integral: IntArray, w: Int, h: Int, r: Int, out: IntArray): IntArray {
        val iw = w + 1
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            val a = y0 * iw
            val b = (y1 + 1) * iw
            val rows = y1 - y0 + 1
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                val sum = integral[b + x1 + 1] - integral[a + x1 + 1] -
                    integral[b + x0] + integral[a + x0]
                out[y * w + x] = sum / (rows * (x1 - x0 + 1))
            }
        }
        return out
    }

    private fun count(a: BooleanArray): Int {
        var c = 0
        for (v in a) if (v) c++
        return c
    }

    /** 剥掉长度 ≥STRIP_LEN 的横/竖长段（胶囊描边、卡片边框），整段挖掉。 */
    private fun stripThinLines(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = src.copyOf()
        // 横
        for (y in 0 until h) {
            val o = y * w
            var run = 0
            for (x in 0 until w) {
                if (src[o + x]) run++ else {
                    if (run >= STRIP_LEN) {
                        val from = x - run
                        val half = STRIP_THICK / 2
                        for (yy in max(0, y - half)..min(h - 1, y + half)) {
                            val oo = yy * w
                            for (k in from until x) out[oo + k] = false
                        }
                    }
                    run = 0
                }
            }
            if (run >= STRIP_LEN) {
                val from = w - run
                val half = STRIP_THICK / 2
                for (yy in max(0, y - half)..min(h - 1, y + half)) {
                    val oo = yy * w
                    for (k in from until w) out[oo + k] = false
                }
            }
        }
        // 竖
        for (x in 0 until w) {
            var run = 0
            for (y in 0 until h) {
                if (src[y * w + x]) run++ else {
                    if (run >= STRIP_LEN) {
                        val from = y - run
                        val half = STRIP_THICK / 2
                        for (xx in max(0, x - half)..min(w - 1, x + half)) {
                            for (k in from until y) out[k * w + xx] = false
                        }
                    }
                    run = 0
                }
            }
            if (run >= STRIP_LEN) {
                val from = h - run
                val half = STRIP_THICK / 2
                for (xx in max(0, x - half)..min(w - 1, x + half)) {
                    for (k in from until h) out[k * w + xx] = false
                }
            }
        }
        return out
    }

    /**
     * 挖掉 45°(ang=+1, "/") 或 135°(ang=-1, "\") 方向长度 ≥DIAG_LEN 的斜段。
     *
     * 高亮光带 / 斜向水印压过字时，DoG 后只在光带边缘留一条 2–3px 的斜线，
     * 它会把相邻两字粘成一个高块，两个都切不出来。汉字没有这么长的纯斜笔画，
     * 所以剥完字还在（实测 01-24-33 那张：剥掉 34%，跳 0.772 / 过 0.772）。
     */
    private fun stripDiag(src: BooleanArray, w: Int, h: Int, ang: Int): BooleanArray {
        val out = src.copyOf()
        // ang=+1: x+y 恒定；ang=-1: x-y 恒定
        val n = w + h - 1
        for (k in 0 until n) {
            // ang=+1: x+y=k，从 y 最大的那一端开始；ang=-1: x-y=k-(h-1)
            val sx: Int
            val sy: Int
            if (ang > 0) {
                sx = max(0, k - (h - 1))
                sy = k - sx
            } else {
                val d = k - (h - 1)
                if (d >= 0) { sx = d; sy = 0 } else { sx = 0; sy = -d }
            }
            if (sx < 0 || sx >= w || sy < 0 || sy >= h) continue
            var run = 0
            var x: Int = sx
            var y: Int = sy
            var runX = 0
            var runY = 0
            while (x < w && y >= 0 && y < h) {
                if (src[y * w + x]) {
                    if (run == 0) { runX = x; runY = y }
                    run++
                } else {
                    if (run >= DIAG_LEN) clearDiag(out, w, h, runX, runY, run, ang)
                    run = 0
                }
                x++
                if (ang > 0) y-- else y++
            }
            if (run >= DIAG_LEN) clearDiag(out, w, h, runX, runY, run, ang)
        }
        return out
    }

    private fun clearDiag(out: BooleanArray, w: Int, h: Int,
                          x0: Int, y0: Int, len: Int, ang: Int) {
        var x = x0
        var y = y0
        for (k in 0 until len) {
            if (x in 0 until w && y in 0 until h) out[y * w + x] = false
            x++
            if (ang > 0) y-- else y++
        }
    }

    /**
     * 5×5 方形闭运算（两遍 3×3 膨胀 + 两遍 3×3 腐蚀）。粘 3px 以内的笔画断口。
     *
     * 走位平面：把 w×h 的布尔图压成每行 ceil(w/64) 个 Long，3×3 膨胀/腐蚀就变成
     * 「上下行 or/and + 行内左移右移 1 位」。原来每遍要 w·h·9 次数组访问
     * （432×588 的块 = 228 万次，一个角 12 遍就是 2700 万次），位平面版是
     * 4116 个 Long 的位运算 —— 这是真机 1.2–11s 降到目标区间的关键一招。
     *
     * 语义与原逐像素实现严格等价：越界一律按 0（所以腐蚀会把最外一圈清掉，
     * 和原实现 `if (ro < 0) { all = false }` 的行为一致）。
     */
    private fun closeStrokes(src: BooleanArray, w: Int, h: Int, sc: Scratch): BooleanArray {
        val words = (w + 63) ushr 6
        val n = words * h
        val a = sc.bitsA(n)
        val b = sc.bitsB(n)
        val c = sc.bitsC(n)
        packBits(src, a, w, h, words)
        morph3(a, b, c, words, h, w, dilate = true)
        morph3(b, a, c, words, h, w, dilate = true)
        morph3(a, b, c, words, h, w, dilate = false)
        morph3(b, a, c, words, h, w, dilate = false)
        return unpackBits(a, w, h, words)
    }

    /** src → tmp（垂直 3 行）→ dst（行内 3 列）。 */
    private fun morph3(src: LongArray, dst: LongArray, tmp: LongArray,
                       words: Int, h: Int, w: Int, dilate: Boolean) {
        for (r in 0 until h) {
            val o = r * words
            if (dilate) {
                val up = if (r > 0) (r - 1) * words else -1
                val dn = if (r < h - 1) (r + 1) * words else -1
                for (i in 0 until words) {
                    var v = src[o + i]
                    if (up >= 0) v = v or src[up + i]
                    if (dn >= 0) v = v or src[dn + i]
                    tmp[o + i] = v
                }
            } else if (r == 0 || r == h - 1) {
                // 上下越界 → 整行腐蚀掉（与原实现一致）
                for (i in 0 until words) tmp[o + i] = 0L
            } else {
                val up = (r - 1) * words
                val dn = (r + 1) * words
                for (i in 0 until words) {
                    tmp[o + i] = src[up + i] and src[o + i] and src[dn + i]
                }
            }
        }
        val tailBits = w - (words - 1) * 64
        val tailMask = if (tailBits >= 64) -1L else (1L shl tailBits) - 1L
        for (r in 0 until h) {
            val o = r * words
            for (i in 0 until words) {
                val v = tmp[o + i]
                // 整体左移/右移 1 位，跨 64 位字用相邻字的高/低位补
                val left = if (i == 0) (v shl 1) else (v shl 1) or (tmp[o + i - 1] ushr 63)
                val right = if (i == words - 1) (v ushr 1) else (v ushr 1) or (tmp[o + i + 1] shl 63)
                var out = if (dilate) (v or left or right) else (v and left and right)
                // 最后一字超出行宽的高位必须清零，否则膨胀会沿位平面越界扩散
                if (i == words - 1) out = out and tailMask
                dst[o + i] = out
            }
        }
    }

    private fun packBits(src: BooleanArray, dst: LongArray, w: Int, h: Int, words: Int) {
        java.util.Arrays.fill(dst, 0, words * h, 0L)
        for (y in 0 until h) {
            val ro = y * w
            val bo = y * words
            for (x in 0 until w) {
                if (src[ro + x]) {
                    val i = bo + (x ushr 6)
                    dst[i] = dst[i] or (1L shl (x and 63))
                }
            }
        }
    }

    private fun unpackBits(src: LongArray, w: Int, h: Int, words: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val ro = y * w
            val bo = y * words
            for (x in 0 until w) {
                out[ro + x] = ((src[bo + (x ushr 6)] ushr (x and 63)) and 1L) != 0L
            }
        }
        return out
    }

    /** 8 邻域连通域 → [x0,y0,x1,y1]。 */
    private fun labelComps(mask: BooleanArray, w: Int, h: Int, sc: Scratch): List<IntArray> {
        val out = ArrayList<IntArray>(16)
        val n = w * h
        val seen = sc.seenOf(n)
        val stack = sc.stackOf(n)
        val ink = sc.inkOf(n)
        var ni = 0
        for (i in 0 until n) if (mask[i]) ink[ni++] = i
        for (k in 0 until ni) {
            val seed = ink[k]
            if (seen[seed]) continue
            var sp = 0
            stack[sp++] = seed
            seen[seed] = true
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE
            var x1 = -1; var y1 = -1
            while (sp > 0) {
                val p = stack[--sp]
                val py = p / w
                val px = p - py * w
                if (px < x0) x0 = px
                if (px > x1) x1 = px
                if (py < y0) y0 = py
                if (py > y1) y1 = py
                val ylo = if (py > 0) py - 1 else 0
                val yhi = if (py < h - 1) py + 1 else h - 1
                val xlo = if (px > 0) px - 1 else 0
                val xhi = if (px < w - 1) px + 1 else w - 1
                for (ny in ylo..yhi) {
                    val ro = ny * w
                    for (nx in xlo..xhi) {
                        val q = ro + nx
                        if (mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                    }
                }
            }
            if (x1 < 0) continue
            val ch = y1 - y0 + 1
            if (ch < MIN_COMP_H || ch > MAX_COMP_H) continue
            out.add(intArrayOf(x0, y0, x1 + 1, y1 + 1))
        }
        return out
    }

    /** 超宽块按列墨量最低点切成单字（n = round(w/h)，在每个理想切点 ±0.18h 找最低列）。 */
    private fun splitWide(mask: BooleanArray, w: Int, h: Int, c: IntArray): List<IntArray> {
        val x0 = c[0]; val y0 = c[1]; val x1 = c[2]; val y1 = c[3]
        val cw = x1 - x0
        val chh = y1 - y0
        if (chh <= 0) return emptyList()
        val parts = (cw.toFloat() / chh).roundToInt()
        if (parts <= 1) return listOf(c)
        val col = IntArray(cw)
        for (x in 0 until cw) {
            var s = 0
            for (y in y0 until y1) if (mask[y * w + x0 + x]) s++
            col[x] = s
        }
        val bounds = ArrayList<Int>(parts + 1)
        bounds.add(x0)
        for (k in 1 until parts) {
            val ideal = x0 + (cw.toFloat() * k / parts).roundToInt()
            val lo = max(x0 + 1, (ideal - 0.18f * chh).roundToInt())
            val hi = min(x1 - 1, (ideal + 0.18f * chh).roundToInt())
            if (hi <= lo) { bounds.add(ideal); continue }
            var bi = lo
            var bv = Int.MAX_VALUE
            for (x in lo..hi) {
                val v = col[x - x0]
                if (v < bv) { bv = v; bi = x }
            }
            bounds.add(bi)
        }
        bounds.add(x1)
        val out = ArrayList<IntArray>(parts)
        for (k in 0 until bounds.size - 1) {
            val a = bounds[k]
            val b = bounds[k + 1]
            if (b - a < 0.2f * chh) continue
            var sy = -1
            var ey = -1
            for (y in y0 until y1) {
                var any = false
                for (x in a until b) if (mask[y * w + x]) { any = true; break }
                if (any) { if (sy < 0) sy = y; ey = y }
            }
            if (sy < 0) continue
            out.add(intArrayOf(a, sy, b, ey + 1))
        }
        return out
    }

    /** 64 窗口、步进 40 的滑窗；partial 表示框贴窗边（第一 pass 不要）。 */
    private fun slideBoxes(mask: BooleanArray, w: Int, h: Int, allowPartial: Boolean,
                           maxOut: Int = Int.MAX_VALUE): List<IntArray> {
        val out = ArrayList<IntArray>(32)
        if (maxOut <= 0) return out
        val xs = axis(w)
        val ys = axis(h)
        for (y in ys) {
            for (x in xs) {
                var bx0 = Int.MAX_VALUE; var by0 = Int.MAX_VALUE
                var bx1 = -1; var by1 = -1
                val yEnd = min(h, y + WIN)
                val xEnd = min(w, x + WIN)
                for (yy in y until yEnd) {
                    val ro = yy * w
                    for (xx in x until xEnd) {
                        if (!mask[ro + xx]) continue
                        if (xx < bx0) bx0 = xx
                        if (xx > bx1) bx1 = xx
                        if (yy < by0) by0 = yy
                        if (yy > by1) by1 = yy
                    }
                }
                if (bx1 < 0) continue
                val partial = bx0 <= x || by0 <= y || bx1 >= xEnd - 1 || by1 >= yEnd - 1
                if (partial && !allowPartial) continue
                out.add(intArrayOf(bx0, by0, bx1 + 1, by1 + 1))
                if (out.size >= maxOut) return out
            }
        }
        return out
    }

    private fun axis(len: Int): IntArray {
        val v = ArrayList<Int>(len / STRIDE + 2)
        var p = 0
        while (p + WIN <= len) { v.add(p); p += STRIDE }
        if (v.isEmpty() || v[v.size - 1] != len - WIN) v.add(max(0, len - WIN))
        return v.toIntArray()
    }

    /** 二值 ink 裁到 bbox → 等比缩放到最长边 52 → 居中贴 64×64 → 阈值 0.5。 */
    private fun normShape(mask: BooleanArray, w: Int, h: Int,
                          x0: Int, y0: Int, x1: Int, y1: Int): LongArray? {
        val bw = x1 - x0
        val bh = y1 - y0
        if (bw <= 0 || bh <= 0) return null
        // 先收紧到真实 ink bbox
        var ix0 = Int.MAX_VALUE; var iy0 = Int.MAX_VALUE
        var ix1 = -1; var iy1 = -1
        for (y in y0 until y1) {
            val ro = y * w
            for (x in x0 until x1) {
                if (!mask[ro + x]) continue
                if (x < ix0) ix0 = x
                if (x > ix1) ix1 = x
                if (y < iy0) iy0 = y
                if (y > iy1) iy1 = y
            }
        }
        if (ix1 < 0) return null
        val iw = ix1 - ix0 + 1
        val ih = iy1 - iy0 + 1
        val s = NORM_INK / max(iw, ih).toFloat()
        val nw = max(1, (iw * s).roundToInt())
        val nh = max(1, (ih * s).roundToInt())
        val ox = (NORM - nw) / 2
        val oy = (NORM - nh) / 2
        val out = LongArray(NORM)
        for (ty in 0 until nh) {
            val sy0 = iy0 + ty * ih / nh
            val sy1 = max(sy0 + 1, iy0 + (ty + 1) * ih / nh)
            val row = oy + ty
            if (row < 0 || row >= NORM) continue
            for (tx in 0 until nw) {
                val sx0 = ix0 + tx * iw / nw
                val sx1 = max(sx0 + 1, ix0 + (tx + 1) * iw / nw)
                var on = 0
                var total = 0
                for (yy in sy0 until min(sy1, iy1 + 1)) {
                    val ro = yy * w
                    for (xx in sx0 until min(sx1, ix1 + 1)) {
                        total++
                        if (mask[ro + xx]) on++
                    }
                }
                if (total > 0 && on * 2 >= total) {
                    val col = ox + tx
                    if (col in 0 until NORM) out[row] = out[row] or (1L shl col)
                }
            }
        }
        return out
    }

    private fun area(shape: LongArray): Int {
        var a = 0
        for (v in shape) a += java.lang.Long.bitCount(v)
        return a
    }

    private fun fmt(v: Float): String = String.format("%.3f", v)
}
