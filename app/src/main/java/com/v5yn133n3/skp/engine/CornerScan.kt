package com.v5yn133n3.skp.engine

import android.graphics.Rect

/**
 * 第一级：纯像素找候选框（不认字）。
 *
 * 判据来自 plan/四角OCR跳过方案.md §5：背景取块外环中位数，`|lum-bg|>45` 二值化，
 * 4-邻域连通域，面积 0.5%–8%、长宽比 1.6–6.0（横胶囊）或 0.7–1.4（方角标）、
 * 且必须完整落在块内（贴边 = 被切开的假候选）。
 *
 * 上面那套数字是**四角块**（466×470）的口径。扫带改成 160×100 瓦片（半分辨率）后，
 * 胶囊在块里占到 ~6%，8% 的面积上限虽然还兜得住，但高度 h/3 的判据会把 20 px 的行砍掉，
 * 所以判据参数化成 [Params]，瓦片用 [TILE]。
 */
object CornerScan {

    const val BG_GAP = 45

    /**
     * 严格阈值：只在**整块瓦片被一个贴边巨块吞掉**时补跑一遍。
     *
     * 中国移动云盘开屏广告（2026-10-31）：金棕色海报底 + 白字「跳过 | 3」。底色纹理很杂，
     * 按 45 阈值二值化后，整片金色 banner 全算前景，连成一块 7182 px（占瓦片 45%）的
     * 连通域，而且直通瓦片边界 —— `onBorder` 一刀砍掉，白字也被并进这块巨块一起消失，
     * 整块瓦片 0 候选。提到 90 之后只有白字留下，「跳」「过」「|」「3」按行合并成
     * 142×36，模型 **1.000**。
     *
     * 判据刻意收得很窄（见 [Scratch.giant]）：正常瓦片前景占比都在 20% 以下，
     * 撞不到「单个贴边连通域 > 25% 块面积」。真机四张图量下来，只有这张海报帧会触发
     * （44/115 块），补回 12 个候选；另外三张（澎湃 / 百分网 / 云盘开屏）合计 5 个、
     * 0 个、0 个。整带 5–6 帧才跑一次，多这十几次提案不心疼。
     */
    const val BG_GAP_STRICT = 90

    const val MAX_CANDIDATES = 3

    /*
     * 第二路提案（top-hat）的参数。只在热区瓦片上、且连通域那一路没给出可点候选时兜底。
     *
     * 为什么需要第二路：连通域靠「和块边中位数差 45」二值化。广告图里一旦有**比按钮还亮
     * 的大面积光效**（中国移动开屏那两张：金紫渐变海报上斜穿的白光带、亮蓝渐变上的
     * 光晕），白字会和光带黏成一块通到瓦片边界，`onBorder` 整块砍掉 —— 提阈值也没用，
     * 因为光带和白字一样亮。真机实测 gap 45/70/90/110/130/150/170 七档，这个瓦片永远 0 候选。
     *
     * top-hat 换的是「和**局部**背景比」：用 7×7 最小值滤波当背景，`lum - min7` 只剩
     * 「比周围 7px 内最暗处还亮 120 以上」的细节。宽过 7px 的光带内部 min 也是亮的，
     * 自己就被抹平了，白字则完整留下。然后再走行段/列段投影（不看连通性，绕开贴边问题），
     * 两张图都稳定给出 92×36 / 102×32，模型 p=1.000。
     *
     * 最小值滤波可分离（先横后纵），7 次比较/像素 × 2 遍 ≈ 224k 次，比 7×7 中位数快一个量级。
     */
    const val TOP_R = 3

    /**
     * 第一档阈值。中国移动那两张（金紫海报的白光带、亮蓝渐变上的光晕）需要 120 才能
     * 把光带彻底抹平；降到 100 以下，光带和白字会连成一条超过 60 的行段，整块被跳过。
     */
    const val TOP_GAP = 120

    /**
     * 第二档阈值。浅蓝渐变那张（「2 跳过」白字压 155 亮度的底，对比只有 ~90）在 120
     * 一档出不了任何行段，必须降到这里。两档都只在**热区瓦片**上跑，且第一档有结果就
     * 不再跑第二档 —— 实测正常广告第一档就能出框。
     */
    const val TOP_GAP_LOOSE = 80

    /** 行段里「至少这么多前景像素才算一行有字」。半分辨率一行小字通常 15–30 个。 */
    const val TOP_ROW_MIN = 6

    /** 列段容差：字与字之间最多断这么多列还算同一段。'跳''过' 之间实测 4–6 列。 */
    const val TOP_COL_GAP = 8

    data class Box(val x: Int, val y: Int, val w: Int, val h: Int, val score: Float) {
        val cx: Int get() = x + w / 2
        val cy: Int get() = y + h / 2
    }

    /**
     * 小框护栏（2026-10-31）。模型给满分也未必能点：广告页里的角标、小字、壁纸上的
     * 竖排字都会被模型认成「跳过」，尺寸和位置是唯一可靠的区分。
     *
     * 一刀切必然误伤 —— 真跳过按钮的尺寸随样式差得很远：
     *
     * | 样本 | 整帧尺寸 | 位置 | 判定档 |
     * |---|---|---|---|
     * | 澎湃横胶囊 | 172×66 | 6% 高 | 热区 / 上半都过 |
     * | 中国移动白字压图 | 102×32 | 5.5% 高 | 只有热区放得过 |
     * | 移动云盘白字压云 | 118×34 | 8.4% 高 | 只有热区放得过 |
     * | 百分网左下圆钮 | 68×32 | 78% 高 | 只有下半放得过 |
     * | **误点样本** | 114×36 | **12.8% 高** | 两档都进不去（热区封顶 12%） |
     *
     * ★ 2026-11-02 口径修正：上表量的是**整颗按钮外框**（含胶囊背景与内边距），
     * 而 [Gate] 的调用方现在是**字形紧框**（只框「跳过」两个字），一律比整框小
     * 25–35%。真机实测（RMX5200 1264×2780，绿色中国移动开屏 + 报纸开屏）：
     * 字形命中 70×33、combo 0.682，过得了判定线却过不了 `90×26`，直接判「小框丢弃」
     * —— 4 秒窗口里每帧都命中、每帧都被丢，表现就是「OCR 完全不触发」。
     * 所以三档全部按 0.68 倍重定到紧框口径，位置判据（12% / 40%）不动：
     * 误点样本 114×36 紧框约 78×28、落在 12.8% 的「上半」档，仍然被 96×32 挡住。
     */
    data class Gate(val minW: Int, val minH: Int, val zone: String) {
        fun pass(w: Int, h: Int) = w >= minW && h >= minH
    }

    /** 热区：右 40%、上 12%（与 [BandScan.isHot] 的 20% 不同，这里是护栏口径）。 */
    const val HOT_X = 0.60f
    const val HOT_Y = 0.12f

    /** 上半屏分界。40% 以下是跳过按钮的下一个正位（左下/右下圆钮）。 */
    const val SMALL_HALF_Y = 0.40f

    fun gate(screenW: Int, screenH: Int, cx: Int, cy: Int): Gate = when {
        cx >= screenW * HOT_X && cy <= screenH * HOT_Y -> Gate(62, 22, "热区")
        cy < screenH * SMALL_HALF_Y -> Gate(96, 32, "上半")
        else -> Gate(44, 20, "下半")
    }

    /** 找框判据。面积/长宽比都按**块面积**算，换块尺寸就必须换一套参数。 */
    data class Params(
        val areaMin: Float,
        val areaMax: Float,
        val maxHeightFrac: Float,
        val minW: Int,
        val minH: Int,
        val aspectLo: Float,
        val aspectHi: Float,
        val maxOut: Int,
    )

    /** 四角块口径（0.40W × 0.24H），保留给旧口径比对。 */
    val CORNER = Params(0.002f, 0.08f, 1f / 3f, 40, 16, 1.2f, 9.0f, MAX_CANDIDATES)

    /**
     * 160×100 瓦片口径（半分辨率扫带用）。真机三张图量到的文字行（半分辨率）：46×20、
     * 52×18、81×33 —— 面积 4–17%、长宽比 2.3–2.9，所以面积上限放到 40%、高度上限 60%、
     * 长宽比 1.5–5.0（滤掉壁纸上的小字块；52×18 这种窄框必须能过，minW 放到 60 就会
     * 把它整颗丢掉）。
     *
     * 2026-10-31 最短边口径 40×16 → **30×14**：百分网开屏左下角的圆钮「跳过」整帧 68×32
     * （半分辨率 34×16、长宽比 2.12、墨迹 0.43），三项判据全过，唯独 34<40 被 minW 判死
     * —— 连候选都出不来，模型给 1.000 也没机会。它比上面那批胶囊小一号是常态：
     * 圆钮的字是塞在圆里的，字距天然比胶囊紧。放到 30 之后整带出框瓦片只从 13 涨到 16，
     * 多出来的三次推理换一颗真按钮，值。
     *
     * 下限不能再低：真机壁纸上的竖排「FASHION」（12×6）、状态栏图标（11×5）都在这个
     * 数量级，30×14 刚好把它们挡在外面。
     */
    val TILE = Params(0.002f, 0.40f, 0.60f, 30, 14, 1.5f, 5.0f, MAX_CANDIDATES)

    /**
     * 近方形（圆形/方形按钮）放行区间。**只在 [allowSquare] 调用时生效**。
     *
     * 国泰君安那张（1080×2352）的圆形「跳过」：整颗 103×111，长宽比 0.93；
     * 落到瓦片里提出 54×49（1.10）—— 旧口径 1.5 下限直接判死，模型再好也看不到。
     */
    const val SQ_LO = 0.70f
    const val SQ_HI = 1.45f

    /** 复用缓冲：一帧 25 块瓦片，每块现开三个 64K 数组是纯垃圾。 */
    class Scratch(capacity: Int = 0) {
        var mask = BooleanArray(capacity)
        var seen = BooleanArray(capacity)
        var stack = IntArray(capacity)

        /**
         * 上一次 [candidates] 里有没有「贴边且吃掉 >25% 块面积」的连通域。
         * 有就说明这一块的底色估计是错的（前景被当成背景或反过来），
         * 值得用 [BG_GAP_STRICT] 再跑一遍。用数组而不是返回对象，省一次分配。
         */
        val giant = BooleanArray(1)

        /** top-hat 第二路的两块临时缓冲（最小值和 top-hat 结果），见 [topHatCandidates]。 */
        var minBuf = IntArray(capacity)
        var topBuf = IntArray(capacity)

        fun ensure(n: Int) {
            if (mask.size >= n) return
            mask = BooleanArray(n)
            seen = BooleanArray(n)
            stack = IntArray(n)
            minBuf = IntArray(n)
            topBuf = IntArray(n)
        }
    }

    /** 四角比例区，顶边下移状态栏；`corner` 只用于日志。 */
    fun regions(w: Int, h: Int, topInset: Int): List<Pair<String, Rect>> {
        val rw = (w * 0.40f).toInt()
        // 0.12H 是按参考图定的，实测 byfen 的「5 | 跳过」在 y≈375–440（1080×2352，0.16–0.19H），
        // 带高 0.12H 时胶囊整个落在带外 —— 32 帧 0 候选就是这么来的。放到 0.24H。
        val rh = (h * 0.24f).toInt()
        val top = topInset.coerceIn(0, h / 4)
        val bottom = h
        return listOf(
            "TL" to Rect(0, top, rw, top + rh),
            "TR" to Rect(w - rw, top, w, top + rh),
            "BL" to Rect(0, bottom - rh, rw, bottom),
            "BR" to Rect(w - rw, bottom - rh, w, bottom),
        )
    }

    fun luminance(pixels: IntArray): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return out
    }

    /**
     * 候选框：先把高对比像素连成连通域，再把同一行的连通域**合并成一段文字**。
     *
     * 为什么不再拿单个连通域当框：实测 byfen 的胶囊底色亮度 34、背景 57，差只有 23，
     * 低于阈值；真正高对比的是白字（226，差 192）。所以单个连通域是「跳」「过」这些
     * 笔画块，长宽比根本不满足胶囊判据。按行合并之后，整行的外框才是 OCR 该看的框。
     */
    fun candidates(
        lum: IntArray,
        w: Int,
        h: Int,
        p: Params = CORNER,
        scratch: Scratch? = null,
        allowSquare: Boolean = false,
    ): List<Box> {
        if (w < 8 || h < 8 || lum.size < w * h) return emptyList()
        val sc = scratch ?: Scratch(w * h)
        sc.ensure(w * h)
        val first = scan(lum, w, h, p, sc, allowSquare, BG_GAP)
        // 一个候选都没有、且刚才出现过「贴边巨块吞掉整块」→ 底色估计不可信，换严格阈值再来一遍。
        // 有候选就不补：候选已经够模型判断，多跑一遍纯属浪费。
        if (first.isNotEmpty() || !sc.giant[0]) return first
        return scan(lum, w, h, p, sc, allowSquare, BG_GAP_STRICT)
    }

    /** [candidates] 的一遍本体。第一遍 [BG_GAP]，被巨块吞掉时补跑 [BG_GAP_STRICT]。 */
    private fun scan(
        lum: IntArray,
        w: Int,
        h: Int,
        p: Params,
        sc: Scratch,
        allowSquare: Boolean,
        gap: Int,
    ): List<Box> {
        val n = w * h
        val mask = sc.mask
        val seen = sc.seen
        val stack = sc.stack
        sc.giant[0] = false
        var bg = ringMedian(lum, w, h)
        var ink = buildMask(lum, mask, n, bg, gap)
        /*
         * 背景估计的兜底（2026-10-31）：框边中位数只在「背景占满框边」时成立。
         * 百分网开屏左下角那颗圆钮整颗压在**浅蓝色广告画面和纯白 banner 的分界线**上，
         * 它的瓦片半边 255、半边 221 —— 中位数取到 255，浅蓝那半边因为差 34<45 侥幸没进
         * 前景。换个深一点的底色（比如 190）就会反过来：整片背景都变成「前景」，和「跳过」
         * 的字连成一块贴边的大连通域 → onBorder 判死 → 整块瓦片一个候选都出不来。
         *
         * 所以只在**背景饱和**（≥240 或 ≤16，说明框边压根没有可用的底色）**且**前景占掉
         * 半块以上时，改用整块中位数重算一次。正常瓦片前景占比都在 20% 以下，走不到这里。
         */
        if ((bg >= 240 || bg <= 16) && ink * 2 > n) {
            bg = tileMedian(lum, w, h)
            ink = buildMask(lum, mask, n, bg, gap)
            if (ink * 2 > n) return emptyList()   // 还是没有可信底色，别硬凑候选
        }
        for (i in 0 until n) seen[i] = false
        val comps = ArrayList<IntArray>()          // x0, y0, x1, y1, area, 极性
        for (start in 0 until n) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var minX = w
            var maxX = -1
            var minY = h
            var maxY = -1
            var area = 0
            var lumSum = 0L
            var onBorder = false
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                area++
                lumSum += lum[p]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) onBorder = true
                var q = p - 1
                if (x > 0 && mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                q = p + 1
                if (x < w - 1 && mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                q = p - w
                if (y > 0 && mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                q = p + w
                if (y < h - 1 && mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
            }
            if (onBorder) {
                // 贴边巨块 = 这一块的底色估计失效（前景整片糊成一块，还通到边界）。
                // 记下来，让 candidates() 用严格阈值补跑一遍（见 BG_GAP_STRICT）。
                if (area * 4 > n) sc.giant[0] = true
                continue                       // 被块边切开的假候选
            }
            if (area < 25) continue                      // 噪点
            // 极性：这块连通域比背景亮还是暗。合并时只并同极性的（见下方合并循环）
            val sign = if (lumSum > bg.toLong() * area) 1 else -1
            comps += intArrayOf(minX, minY, maxX, maxY, area, sign)
        }
        if (comps.isEmpty()) return emptyList()
        // 花园图上一帧能出上千个连通域，合并是 O(n²)；只留面积最大的 500 个
        val pool = if (comps.size > 500) comps.sortedByDescending { it[4] }.take(500) else comps

        // 按行合并：竖直方向重叠过半、水平间距不超过较短者高度的一半，就算同一段文字
        var merged = pool
        while (true) {
            var changed = false
            val out = ArrayList<IntArray>()
            val used = BooleanArray(merged.size)
            for (i in merged.indices) {
                if (used[i]) continue
                var a = merged[i].copyOf()
                used[i] = true
                var again = true
                while (again) {
                    again = false
                    for (j in merged.indices) {
                        if (used[j]) continue
                        val b = merged[j]
                        val ha = (a[3] - a[1] + 1).coerceAtLeast(1)
                        val hb = (b[3] - b[1] + 1).coerceAtLeast(1)
                        val overlap = minOf(a[3], b[3]) - maxOf(a[1], b[1]) + 1
                        if (overlap * 2 < minOf(ha, hb)) continue
                        val gap = maxOf(b[0] - a[2], a[0] - b[2])
                        // 间距上限按「一个字高」给，不是半字高：「跳过 6」这类胶囊自带空格，
                        // 有些皮肤还会把字距拉开。按半字高切会把一颗胶囊拆成两段，第一段常常
                        // 只剩「跳」一个字，模型分就掉下去了。放宽到整字高后实测调用数反而更少
                        // （同一行的字并成一个整框，一块瓦片只出一条候选），负样本仍全 0。
                        if (gap > maxOf(minOf(ha, hb), 16)) continue
                        /*
                         * 极性必须一致（2026-10-31）。合并条件原来只看竖向重叠和横向间距，
                         * 于是「比背景亮的东西」和「比背景暗的东西」也能并成一段 ——
                         * 澎湃开屏那颗纯黑胶囊（暗）就这样跟左边那截亮青底色 + 白标题字尾
                         * 并成了一个 222×66 的框，而胶囊本身只有 161×61。模型照样给 1.000，
                         * 但框中心被拽到胶囊外 29px，再吞宽一点就直接点空。
                         * 同一行文字不可能一半比背景亮、一半比背景暗，加上这一条就够了。
                         */
                        if (a[5] != b[5]) continue
                        a = intArrayOf(
                            minOf(a[0], b[0]), minOf(a[1], b[1]),
                            maxOf(a[2], b[2]), maxOf(a[3], b[3]),
                            a[4] + b[4], a[5],
                        )
                        used[j] = true
                        again = true
                        changed = true
                    }
                }
                out += a
            }
            merged = out
            if (!changed) break
        }

        val out = ArrayList<Box>()
        val blockArea = w * h.toFloat()
        val maxH = (h * p.maxHeightFrac).toInt()
        for (c in merged) {
            val bw = c[2] - c[0] + 1
            val bh = c[3] - c[1] + 1
            val frac = c[4] / blockArea
            if (frac < p.areaMin || frac > p.areaMax) continue
            if (bw < p.minW || bh < p.minH || bh > maxH) continue
            val aspect = bw.toFloat() / bh
            // 近方形是圆形按钮的合法形状，但它跟「横排文字」是两类东西：
            // 放行区间窄（0.70–1.45），且只由调用方按瓦片位置决定是否开口。
            val square = allowSquare && aspect in SQ_LO..SQ_HI
            if (!square && (aspect < p.aspectLo || aspect > p.aspectHi)) continue
            val density = c[4].toFloat() / (bw * bh)        // 文字行的墨迹占比
            if (density < 0.06f) continue
            // c[5] 没在合并里累加（置 0），对比度改用局部亮度极差估计
            var lo = 255
            var hi = 0
            for (y in c[1]..c[3] step 2) for (x in c[0]..c[2] step 2) {
                val v = lum[y * w + x]
                if (mask[y * w + x]) { if (v < lo) lo = v; if (v > hi) hi = v }
            }
            val contrast = (hi - lo).coerceAtLeast(1)
            // 横排优先：实测同一帧里有一个 44x34 的近方块分数更高，但跳过是「一横排字」。
            // 只 OCR 第一名，所以排序必须先把非横排压下去。方形档排第二（0.85），
            // 只在没有横排字的瓦片里出头 —— 圆形按钮那类瓦片本来就只有它一个。
            val shape = when {
                aspect in 1.6f..6.0f -> 1f
                square -> 0.85f
                else -> 0.35f
            }
            // 2.3 = 真胶囊的平均长宽比（237×80 与 164×75 两处实测都在 2.2–3.0）
            val anchor = if (square) 1f else 2.3f
            val closeness = 1f / (1f + kotlin.math.abs(aspect - anchor))
            out += Box(c[0], c[1], bw, bh, contrast * density * shape * closeness)
        }
        return out.sortedByDescending { it.score }.take(p.maxOut)
    }

    /**
     * 第二路提案：top-hat（原图 − 7×7 最小值）+ 行段/列段投影。
     *
     * 和 [candidates] 的分工：那一套靠「整块一个背景色」，广告图里有大面积高亮光效时会被
     * 黏死（见 [TOP_GAP] 的注释）；这一套只看**局部**对比，光效被抹平、白字留下。
     *
     * 为什么不走连通域：白字在这一类广告里常常紧贴屏幕右缘，瓦片一对齐就把字切在边界上，
     * 连通域判贴边 = 整块丢。行段/列段投影不关心连通性，天然免疫。代价是「框」是投影外接框，
     * 不是严格墨迹框 —— 对点击没影响（中心点仍在字上）。
     *
     * 结果按长宽比接近 2.3 排序（与 [scan] 同一口径），最多两条。
     */
    fun topHatCandidates(lum: IntArray, w: Int, h: Int, scratch: Scratch? = null): List<Box> {
        if (w < 8 || h < 8 || lum.size < w * h) return emptyList()
        val n = w * h
        val sc = scratch ?: Scratch(n)
        sc.ensure(n)
        val bg = sc.minBuf
        val top = sc.topBuf
        // 可分离的最小值滤波：先横向再纵向，等效 7×7（同 numpy mode='edge' 的边缘处理）
        minAxis(lum, bg, w, h, TOP_R, true)
        minAxis(bg, top, w, h, TOP_R, false)
        for (i in 0 until n) {
            val d = lum[i] - top[i]
            top[i] = if (d > 0) d else 0
        }
        // 两档阈值：高档优先（光带强的图必须用它），出不了框再降一档（亮底白字用它）
        val mask = sc.mask
        for (gap in intArrayOf(TOP_GAP, TOP_GAP_LOOSE)) {
            for (i in 0 until n) mask[i] = top[i] > gap
            val r = collectBand(mask, w, h)
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    /**
     * [topHatCandidates] 的行段/列段投影本体。mask 是「亮细节」二值图。
     *
     * 先按行找「有字的行段」（高 14–60），再在行段内按列切段（容忍 8 列空隙，
     * 「跳」「过」之间实测 4–6 列）。不看连通性，所以白字贴着屏幕右缘也切不断。
     */
    private fun collectBand(mask: BooleanArray, w: Int, h: Int): List<Box> {
        val out = ArrayList<Box>(4)
        var y = 0
        while (y < h) {
            // 行投影：这一行有多少「亮细节」像素
            if (countRow(mask, w, y) < TOP_ROW_MIN) {
                y++
                continue
            }
            val y0 = y
            while (y < h && countRow(mask, w, y) >= TOP_ROW_MIN) y++
            val y1 = y - 1
            val bh = y1 - y0 + 1
            if (bh < 14 || bh > 60) continue
            // 列投影：在 y0..y1 内逐列计数
            var x = 0
            while (x < w) {
                if (countCol(mask, w, y0, y1, x) < 2) {
                    x++
                    continue
                }
                val x0 = x
                var x1 = x
                var miss = 0
                while (x < w) {
                    if (countCol(mask, w, y0, y1, x) >= 2) {
                        x1 = x
                        miss = 0
                    } else {
                        miss++
                        if (miss > TOP_COL_GAP) break
                    }
                    x++
                }
                val bw = x1 - x0 + 1
                if (bw < 30) {
                    x = x1 + 1
                    continue
                }
                var ink = 0
                for (yy in y0..y1) {
                    val base = yy * w
                    for (xx in x0..x1) if (mask[base + xx]) ink++
                }
                val dens = ink.toFloat() / (bw * bh)
                val aspect = bw.toFloat() / bh
                if (dens < 0.08f || dens > 0.72f) {
                    x = x1 + 1
                    continue
                }
                if (aspect < 1.2f || aspect > 6.0f) {
                    x = x1 + 1
                    continue
                }
                val shape = if (aspect in 1.6f..6.0f) 1f else 0.5f
                val closeness = 1f / (1f + kotlin.math.abs(aspect - 2.3f))
                out += Box(x0, y0, bw, bh, dens * shape * closeness * 100f)
                x = x1 + 1
            }
        }
        return out.sortedByDescending { it.score }.take(2)
    }

    /** 一行里前景像素数。 */
    private fun countRow(mask: BooleanArray, w: Int, y: Int): Int {
        val base = y * w
        var c = 0
        for (x in 0 until w) if (mask[base + x]) c++
        return c
    }

    /** 一列在 [y0, y1] 内的前景像素数。 */
    private fun countCol(mask: BooleanArray, w: Int, y0: Int, y1: Int, x: Int): Int {
        var c = 0
        for (y in y0..y1) if (mask[y * w + x]) c++
        return c
    }

    /**
     * 半径 [r] 的一维最小值滤波。`row=true` 走横向（同一行内 2r+1 个），否则走纵向。
     * 朴素实现（每个像素比 2r+1 次）：7×7 展开成两趟 7 次，160×100 约 224k 次比较，
     * 比单调队列好读且实测 <1ms。
     */
    private fun minAxis(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, row: Boolean) {
        if (row) {
            for (y in 0 until h) {
                val base = y * w
                for (x in 0 until w) {
                    var m = 255
                    val lo = if (x - r < 0) 0 else x - r
                    val hi = if (x + r >= w) w - 1 else x + r
                    for (xx in lo..hi) {
                        val v = src[base + xx]
                        if (v < m) m = v
                    }
                    dst[base + x] = m
                }
            }
        } else {
            for (x in 0 until w) {
                for (y in 0 until h) {
                    var m = 255
                    val lo = if (y - r < 0) 0 else y - r
                    val hi = if (y + r >= h) h - 1 else y + r
                    for (yy in lo..hi) {
                        val v = src[yy * w + x]
                        if (v < m) m = v
                    }
                    dst[y * w + x] = m
                }
            }
        }
    }

    /** 按背景 b、阈值 gap 重算前景掩码，返回前景像素数。 */
    private fun buildMask(lum: IntArray, mask: BooleanArray, n: Int, b: Int, gap: Int): Int {
        var cnt = 0
        for (i in 0 until n) {
            val m = kotlin.math.abs(lum[i] - b) > gap
            mask[i] = m
            if (m) cnt++
        }
        return cnt
    }

    /** 整块（隔点采样）亮度中位数，只在框边中位数不可信时兜底用。 */
    private fun tileMedian(lum: IntArray, w: Int, h: Int): Int {
        val step = 3
        val v = ArrayList<Int>((w / step + 1) * (h / step + 1))
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                v += lum[y * w + x]
                x += step
            }
            y += step
        }
        v.sort()
        return v[v.size / 2]
    }

    private fun ringMedian(lum: IntArray, w: Int, h: Int): Int {
        val ring = ArrayList<Int>((w + h) * 2)
        for (x in 0 until w) {
            ring += lum[x]
            ring += lum[(h - 1) * w + x]
        }
        for (y in 1 until h - 1) {
            ring += lum[y * w]
            ring += lum[y * w + w - 1]
        }
        ring.sort()
        return ring[ring.size / 2]
    }
}
