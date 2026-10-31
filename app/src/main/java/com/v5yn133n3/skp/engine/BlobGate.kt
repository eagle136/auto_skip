package com.v5yn133n3.skp.engine

import com.v5yn133n3.skp.priv.Capture
import kotlin.math.abs

/**
 * 第五级（最后一关）：实心块闸门。
 *
 * 为什么需要它：模型只吃「框」，判断不了框里是**文字**还是**图形的一截**。
 * 真机实测（2026-10-29 19:21 的聊天截图）：一个圆形头像被提案器切成 108×46 的横条，
 * 白脸 + 黑底，模型给 **0.987** 并点了下去 —— 头像不是按钮，这一下是纯误触。
 *
 * 判据来自同一批实测数据（整帧坐标，`ring median` 作背景）：
 *
 * | 样本 | 墨迹占比 | 连通域数 | 最大连通域占比 |
 * |---|---|---|---|
 * | 圆形头像 108×46 | **0.697** | **2** | **1.00** |
 * | 「1跳过」96×35 | 0.382 | 7 | 0.33 |
 * | 「2跳过」102×36 | 0.375 | 7 | 0.31 |
 *
 * 文字的本质特征就是「由很多互不相连的笔画组成」，而头像/图标/色块是一整块。
 * 三个条件同时成立才判为实心块：墨迹占比高、连通域极少、且其中一块几乎占满
 * ——单独任一条都会误杀（比如粗体白字墨迹也能到 0.5，或「✕」只有一个连通域但很细）。
 *
 * 为什么用「框边中位数」当背景而不是取最亮色：圆形**白底**按钮（白底 + 深色「跳过」字）
 * 的框边就是白底，取中位数后深色字变成前景，会数出 5–7 个连通域，天然不会误杀。
 * 而头像的框边是它周围的黑色背景，白脸才是前景，于是数出 1 大块。
 */
object BlobGate {

    /** 前景像素（与框边背景的亮度差）阈值。 */
    private const val DIFF = 40

    /** 墨迹占比下限：文字行实测 0.31–0.45，实心块 0.65+。 */
    private const val INK_MIN = 0.55f

    /** 最大连通域占全部墨迹的比例下限。文字行 0.31–0.33，实心块 1.00。 */
    private const val BIG_MIN = 0.85f

    /** 连通域个数上限。文字行 7 个，实心块 1–2 个。 */
    private const val COMPS_MAX = 3

    /** 采样点数上限，超过就隔行隔列取。4000 点足够判形状，BFS 成本可控。 */
    private const val MAX_SAMPLES = 6000

    /** 内部空洞下限：文字把实心块「挖空」的块数。白字「4|跳过」实测 9 个。 */
    private const val HOLE_MIN = 2

    /** 内部空洞面积占框面积下限。该按钮实测 0.075。 */
    private const val HOLE_FRAC = 0.06f

    /** 单个空洞的最小采样点数，滤掉压缩噪点。 */
    private const val HOLE_AREA_MIN = 6

    /**
     * 这个框里是不是一整块实心图形（而非文字行）。
     *
     * @param x0/y0/w/h 整帧坐标（模型吃的那个框）。
     */
    fun isSolidBlob(frame: Capture.Frame, x0: Int, y0: Int, w: Int, h: Int): Boolean {
        val fw = frame.width
        val fh = frame.height
        val left = x0.coerceIn(0, fw)
        val top = y0.coerceIn(0, fh)
        val right = (x0 + w).coerceIn(0, fw)
        val bottom = (y0 + h).coerceIn(0, fh)
        val bw = right - left
        val bh = bottom - top
        if (bw < 8 || bh < 6) return false

        val step = if (bw * bh > MAX_SAMPLES) 2 else 1
        val sw = bw / step
        val sh = bh / step
        if (sw < 4 || sh < 4) return false

        val src = frame.bgra
        val stride = fw
        val lum = IntArray(sw * sh)
        var i = 0
        for (sy in 0 until sh) {
            val row = 4 * ((top + sy * step) * stride + left)
            for (sx in 0 until sw) {
                val p = row + 4 * (sx * step)
                val b = src[p].toInt() and 0xFF
                val g = src[p + 1].toInt() and 0xFF
                val r = src[p + 2].toInt() and 0xFF
                lum[i++] = (r * 299 + g * 587 + b * 114) / 1000
            }
        }

        // 框边一圈采样的中位数当背景：文字行是「背景 + 笔画」，实心块是「背景 + 一整块图形」
        val ring = IntArray(2 * (sw + sh))
        var ri = 0
        for (x in 0 until sw) {
            ring[ri++] = lum[x]
            ring[ri++] = lum[(sh - 1) * sw + x]
        }
        for (y in 1 until sh - 1) {
            ring[ri++] = lum[y * sw]
            ring[ri++] = lum[y * sw + sw - 1]
        }
        java.util.Arrays.sort(ring, 0, ri)
        val bg = ring[ri / 2]

        val mask = BooleanArray(sw * sh)
        var ink = 0
        for (k in 0 until sw * sh) {
            if (abs(lum[k] - bg) > DIFF) {
                mask[k] = true
                ink++
            }
        }
        if (ink == 0) return false
        val inkRatio = ink.toFloat() / (sw * sh)
        if (inkRatio < INK_MIN) return false

        // 4 邻域连通域。用 IntArray 当显式栈，避免递归和对象分配。
        val seen = BooleanArray(sw * sh)
        val stack = IntArray(ink)
        var comps = 0
        var biggest = 0
        for (start in 0 until sw * sh) {
            if (!mask[start] || seen[start]) continue
            comps++
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var area = 0
            while (sp > 0) {
                val k = stack[--sp]
                area++
                val y = k / sw
                val x = k - y * sw
                if (x > 0) {
                    val n = k - 1
                    if (mask[n] && !seen[n]) { seen[n] = true; stack[sp++] = n }
                }
                if (x < sw - 1) {
                    val n = k + 1
                    if (mask[n] && !seen[n]) { seen[n] = true; stack[sp++] = n }
                }
                if (y > 0) {
                    val n = k - sw
                    if (mask[n] && !seen[n]) { seen[n] = true; stack[sp++] = n }
                }
                if (y < sh - 1) {
                    val n = k + sw
                    if (mask[n] && !seen[n]) { seen[n] = true; stack[sp++] = n }
                }
            }
            if (area > biggest) biggest = area
        }

        val bigRatio = biggest.toFloat() / ink
        if (comps > COMPS_MAX || bigRatio < BIG_MIN) return false
        // 内部空洞豁免（2026-10-31）：上面三条只在「字是前景」时成立。碰到
        // **实心底 + 与背景同色的字** 会整块过阈 —— 百分网开屏的「4 | 跳过」正是：
        // 页面白底 250、半透明灰胶囊 140、白字 ≈250。灰胶囊成了前景（ink 0.82、
        // comps 3、big 0.99 → 判实心块），白字反倒成了「背景色」。模型给 1.000 也照样被丢。
        // 真文字会把实心块挖出多个洞：该按钮 9 个洞、占框 7.5%；头像/图标内部没有这种
        // 镂空（白脸本身就是前景），所以这一条不会把 10-29 那个 0.987 的头像放回来。
        return !hasInnerHoles(mask, sw, sh)
    }

    /**
     * 框内是否存在「被前景包围、不接触框边」的背景色区域。
     *
     * 只用连通域数 + 面积占比，不判字形：判字形要上模型，而这一步本来就是为了
     * 在模型之前用一毫秒挡掉误触。触边框边的反相区域是框外背景，直接排除。
     */
    private fun hasInnerHoles(mask: BooleanArray, sw: Int, sh: Int): Boolean {
        val n = sw * sh
        val seen = BooleanArray(n)
        val stack = IntArray(n)
        var holes = 0
        var holeArea = 0
        for (start in 0 until n) {
            if (mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var a = 0
            var touch = false
            while (sp > 0) {
                val k = stack[--sp]
                a++
                val y = k / sw
                val x = k - y * sw
                if (x == 0 || y == 0 || x == sw - 1 || y == sh - 1) touch = true
                if (x > 0) {
                    val q = k - 1
                    if (!mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                }
                if (x < sw - 1) {
                    val q = k + 1
                    if (!mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                }
                if (y > 0) {
                    val q = k - sw
                    if (!mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                }
                if (y < sh - 1) {
                    val q = k + sw
                    if (!mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                }
            }
            if (!touch && a >= HOLE_AREA_MIN) {
                holes++
                holeArea += a
            }
        }
        return holes >= HOLE_MIN && holeArea.toFloat() / n >= HOLE_FRAC
    }
}
