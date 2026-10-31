package com.v5yn133n3.skp.engine

/**
 * 扫带口径：整帧 → 两条横带 → 160×100 瓦片，逐块过，任一块命中就点。
 *
 * 带的位置是照真机参考图（1080×2352 插屏广告）定的：跳出按钮只会出现在「上 0%–30%」和
 * 「65%–85%」这两段，中间是壁纸主体、最下面是导航栏，扫了纯浪费模型。上带必须从 0 起，
 * 因为贴顶的胶囊实测落在 0.1%–3.3%。
 *
 * 分辨率：先按 [SCALE] 压一半再扫（540×1176），像素级的连通域成本降到 1/4。
 * 模型那一级仍然回整帧原分辨率取像素——权重是拿原尺寸真机裁块训的，缩一半再喂会掉分。
 *
 * 网格：瓦片 [TW]×[TH]，相邻重合 [OVERLAP]（横向步长 112、纵向 52）。重合量的下限是
 * 「一个字的宽度」——字被两块各切一半就两块都判不出来；真机胶囊的字宽（半分辨率）
 * 量到 17–23 px，[OVERLAP]=48 是它的 2 倍以上，格子错位半个字也能整颗装进某一块。
 * 末尾贴边补一块，**不按回退量丢弃**。
 */
object BandScan {

    /** 工作分辨率 = 整帧 / 2。 */
    const val SCALE = 2

    const val TW = 160
    const val TH = 100

    /** 相邻瓦片重合像素（工作分辨率下）。48/160 = 30%，比原来 48/256 更密。 */
    const val OVERLAP = 48

    const val STEP_X = TW - OVERLAP
    const val STEP_Y = TH - OVERLAP

    /**
     * 上带、下带，占整帧高的比例。上带从 **0** 起：实测「2 跳过」这种胶囊会贴到屏幕
     * 顶上（真机上量到 y=3–73 / 1080×2246，也就是 0.1%–3.3%），从 5% 起扫整颗都在带外，
     * 一帧都出不来候选。上带 0–30% 只比 5–30% 多一行瓦片，代价可以接受。
     *
     * 2026-10-29 改口径：下带原来只到 0.65–0.85，两头都留盲区 ——
     * ① 0.85 以下整段不扫，而贴着导航栏的「跳过」很常见；
     * ② 0.30–0.65 不扫，国泰君安那张的**圆形「跳过」圆心在 66.4%**，胶囊体
     *    y=1507–1618（64.1%–68.8%），上沿正好落在 0.65 处，被瓦片上边界切掉 →
     *    连通域贴边 → `onBorder` 丢弃，整帧 0 候选。
     * 2026-10-31 改口径：上下带改成 **0–40% / 36–100% 重叠 4%**。
     * 原来是 0–34% + 58–100%，中间 34%–58% 整段不扫 —— 用户报「跳过在左下角，
     * 没识别到」就是撞在这条缝上：左下角的胶囊中心常常落在 40%–55%，上沿进不了
     * 下带、下沿进不了上带，两块瓦片各切一半，`onBorder` 全丢。
     * 重叠 4%（约 47 px 半分辨率）保证任意高度 ≥94 px 的胶囊至少完整落在一块里。
     */
    private val BANDS = floatArrayOf(0.00f, 0.40f, 0.36f, 1.00f)

    /**
     * 右上热区。真机 8 张「N 跳过」截图量下来：x 落在 82%–97%、y 落在 5.4%–6.9%，
     * 全在右上角 1/3 宽、1/6 高之内。先把这一块单独扫一遍，4–6 块瓦片、
     * 一两帧就能出结果；热区没命中再按节奏补全带。
     *
     * 判定用瓦片**中心**，不用左边界：块宽 160，用左边界会把中心在 30% 处的块也算进来。
     */
    private const val HOT_X = 0.60f
    private const val HOT_Y = 0.20f

    /** 工作分辨率下的一块瓦片。 */
    class Tile(val band: Int, val x: Int, val y: Int) {
        override fun toString(): String = "b$band@$x,$y"
    }

    /**
     * 一帧要过的瓦片，行主序：先上带从上到下，再下带；带内每行从左到右。
     * 入参是工作分辨率（整帧 / [SCALE]）。
     *
     * [skipHot] = true 时跳过热区那块，用于「先扫热区、再补其余」的两段式
     * —— 同一帧里不重复扫同一块。
     */
    fun tiles(w: Int, h: Int, skipHot: Boolean = false): List<Tile> {
        val out = ArrayList<Tile>(64)
        var band = 0
        while (band * 2 < BANDS.size) {
            val y0 = (h * BANDS[band * 2]).toInt().coerceIn(0, (h - TH).coerceAtLeast(0))
            val y1 = (h * BANDS[band * 2 + 1]).toInt().coerceIn(y0, h)
            val ys = axis(y0, y1, TH, STEP_Y, h)
            val xs = axis(0, w, TW, STEP_X, w)
            // **每行从右往左**。跳过按钮几乎全在右上/右下角，而行主序会让它排在一帧的
            // 最后一块：一帧 610ms / 13.5 次模型调用，等轮到 x 最大的那块，倒计时「1 秒」
            // 已经走完。中国移动插屏实测：命中框恒在 b0@380，排在 5 块瓦片的队尾。
            // 逆序后第一块就是右上角，命中时 calls 从 13 降到 1。
            for (y in ys) for (x in xs.asReversed()) {
                val t = Tile(band, x, y)
                if (skipHot && isHot(t, w, h)) continue
                out += t
            }
            band++
        }
        return out
    }

    /** 只取右上热区那几块，顺序与 [tiles] 一致（先上后下、先右后左）。 */
    fun hotTiles(w: Int, h: Int): List<Tile> = tiles(w, h).filter { isHot(it, w, h) }

    /** 瓦片中心是否落在右上热区里。 */
    fun isHot(t: Tile, w: Int, h: Int): Boolean {
        if (t.band != 0) return false
        val cx = t.x + TW / 2
        val cy = t.y + TH / 2
        return cx >= w * HOT_X && cy <= h * HOT_Y
    }

    /**
     * 这块瓦片要不要放行「近方形」候选（圆形/方形按钮）。
     *
     * 圆形按钮的整颗框长宽比 ≈0.93–1.10，被 [CornerScan.TILE] 的 1.5 下限整颗判掉，
     * 所以方形必须单独开口子。但不能全帧放：底图和列表里到处是色块、图标、头像，
     * 每块都推一个方框会让模型调用翻三倍（实测整带 20 → 82 次/帧）。
     *
     * 口径：下带全宽放（右下/左下/正下方的圆钮都在这），上带只放两侧
     * （右 40% / 左 25%）—— 上带中间是标题、logo、开屏大图，不放。
     */
    fun allowsSquare(t: Tile, w: Int): Boolean {
        if (t.band == 1) return true
        val cx = t.x + TW / 2
        return cx >= w * 0.60f || cx <= w * 0.25f
    }

    /** 一维布点：等距铺，末尾贴边补一块，**永远补**。 */
    private fun axis(start: Int, end: Int, size: Int, step: Int, limit: Int): List<Int> {
        val safeEnd = end.coerceAtMost(limit)
        if (safeEnd - start <= size) {
            return listOf(start.coerceIn(0, (limit - size).coerceAtLeast(0)))
        }
        val out = ArrayList<Int>(16)
        var p = start
        while (p + size <= safeEnd) {
            out += p
            p += step
        }
        // 贴边块永远补。原来「回退量 < 重合量就丢」，理由是这条缝上一块已经扫过——
        // 但上一块的右边界正好停在缝的左边，缝里的东西两块都盖不全：实测两张真机图
        // 的胶囊右边缘都落在最右 44 px 里，两张都漏。补一块只多一次提案，换掉整条盲区。
        val last = safeEnd - size
        if (last > out.last()) out += last
        return out
    }

    /** 把一块瓦片的亮度从工作分辨率缓冲里拷出来（复用同一块目标数组）。 */
    fun copyTile(src: IntArray, srcW: Int, t: Tile, w: Int, h: Int, out: IntArray) {
        var d = 0
        for (row in 0 until h) {
            System.arraycopy(src, (t.y + row) * srcW + t.x, out, d, w)
            d += w
        }
    }
}
