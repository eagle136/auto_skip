package com.v5yn133n3.skp.engine

import android.content.Context
import android.util.Log
import com.v5yn133n3.skp.Logs

/**
 * 字形模板库：把 `assets/glyph/tmpl.bin` 里的 2 字 × 6 字体展开成 u64 位平面。
 *
 * 为什么要位平面：
 *   一个 64×64 模板 = 4096 bit = 64 个 Long。IoU 的「交集像素数」就是 64 次
 *   `and` + `bitCount`，一次算完 4096 个像素；换成逐像素 Int 数组要 4096 次
 *   循环，慢两个数量级（Python 原型 83% 的时间耗在这种小数组操作上）。
 *
 * 模板由 `R:\glyph_ocr\export_tmpl.py` 从 Windows TTF 渲染后导出，**不要在
 * 端上用 Paint+Typeface 现场渲染**：Android 是 Noto Sans CJK，字形与标定用的
 * 中易黑体不同，IoU 会整体掉 0.05–0.1，等于把标定过的阈值全部作废。
 *
 * 文件格式（小端）：
 *   "GLY1" | ver=2 | nChar=2 | nFace=13 | 1B 保留
 *   nChar × nFace × 512B，每模板 64 行 × 8 字节，bit0 = 该行最左像素
 *   索引 = charIdx * nFace + faceIdx
 */
object GlyphBank {

    private const val TAG = "SkipGlyph"

    const val CH_TIAO = 0
    const val CH_GUO = 1
    const val N_CHAR = 2
    const val N_FACE = 13
    const val NORM = 64

    /** 字体顺序，与 export_tmpl.py 的 FACES 前 6 项一一对应，只用于日志。 */
    private val FACE_NAMES = arrayOf(
        "simhei", "NotoSansSC-VF", "msyh", "msyhl", "msyhbd", "deng",
        "oplus-w100", "oplus-w200", "oplus-w300", "oplus-w400",
        "oplus-w500", "oplus-w600", "oplus-w700",
    )

    private var tmpl: Array<LongArray> = emptyArray()
    private var inkArea: IntArray = IntArray(0)

    @Volatile
    var loaded: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    @Synchronized
    fun load(ctx: Context): Boolean {
        if (loaded) return true
        return try {
            val raw = ctx.assets.open("glyph/tmpl.bin").use { it.readBytes() }
            if (raw.size < 8 || raw[0] != 'G'.code.toByte() || raw[1] != 'L'.code.toByte() ||
                raw[2] != 'Y'.code.toByte() || raw[3] != '1'.code.toByte()
            ) {
                lastError = "模板头不对 size=${raw.size}"
                return false
            }
            val ver = raw[4].toInt() and 0xFF
            if (ver != 1 && ver != 2) {
                lastError = "模板版本不对 ver=$ver"
                return false
            }
            val nChar = raw[5].toInt() and 0xFF
            val nFace = raw[6].toInt() and 0xFF
            if (nChar != N_CHAR || nFace != N_FACE) {
                lastError = "模板数量不对 nChar=$nChar nFace=$nFace"
                return false
            }
            val per = NORM * 8                       // 每模板字节数 = 64 行 × 8B
            val need = 8 + nChar * nFace * per
            if (raw.size < need) {
                lastError = "模板被截断 ${raw.size} < $need"
                return false
            }
            val t = Array(nChar * nFace) { LongArray(NORM) }
            val a = IntArray(nChar * nFace)
            var off = 8
            for (i in t.indices) {
                val rows = t[i]
                var ink = 0
                for (y in 0 until NORM) {
                    var v = 0L
                    for (k in 0 until 8) {
                        v = v or ((raw[off + y * 8 + k].toLong() and 0xFF) shl (8 * k))
                    }
                    rows[y] = v
                    ink += java.lang.Long.bitCount(v)
                }
                a[i] = ink
                off += per
            }
            tmpl = t
            inkArea = a
            loaded = true
            lastError = ""
            val sb = StringBuilder("template faces=")
            for (f in 0 until N_FACE) {
                sb.append(FACE_NAMES[f]).append(':').append(a[f]).append(' ')
            }
            Logs.i(TAG, sb.toString())
            true
        } catch (e: Throwable) {
            lastError = "读取失败 ${e.message}"
            Logs.w(TAG, lastError)
            false
        }
    }

    /**
     * 与某个字的 6 个字体模板比 IoU，取最高分（含 ±2px 位移精修）。
     *
     * 位移只在"不平移时最好的那个字体"上做：25 个位移 × 6 字体 = 150 次全图
     * and，而先选字体再精修只要 6 + 25 次。实测两者分值差 <0.005。
     */
    fun score(cand: LongArray, candArea: Int, ch: Int): Float {
        if (!loaded || candArea <= 0) return 0f
        val base = ch * N_FACE
        val dil = dilate3(cand)
        val dilArea = area(dil)
        val ero = erode3(cand)
        val eroArea = area(ero)
        var best = 0f
        var bestFace = -1
        var bestVar = 0
        for (f in 0 until N_FACE) {
            val area = inkArea[base + f]
            if (area <= 0) continue
            val t = tmpl[base + f]
            val inter = intersect(cand, t)
            val iou = inter.toFloat() / (candArea + area - inter).toFloat()
            if (iou > best) { best = iou; bestFace = f; bestVar = 0 }
            if (dilArea > 0) {
                val i2 = intersect(dil, t)
                val v2 = i2.toFloat() / (dilArea + area - i2).toFloat()
                if (v2 > best) { best = v2; bestFace = f; bestVar = 1 }
            }
            if (eroArea > 0) {
                val i3 = intersect(ero, t)
                val v3 = i3.toFloat() / (eroArea + area - i3).toFloat()
                if (v3 > best) { best = v3; bestFace = f; bestVar = 2 }
            }
        }
        if (bestFace < 0) return 0f
        val cv = when (bestVar) { 1 -> dil; 2 -> ero; else -> cand }
        val ca = when (bestVar) { 1 -> dilArea; 2 -> eroArea; else -> candArea }
        val t = tmpl[base + bestFace]
        val ta = inkArea[base + bestFace]
        for (dy in -SHIFT_RAD..SHIFT_RAD) {
            for (dx in -SHIFT_RAD..SHIFT_RAD) {
                if (dx == 0 && dy == 0) continue
                val inter = intersectShift(cv, t, dy, dx)
                val iou = inter.toFloat() / (ca + ta - inter).toFloat()
                if (iou > best) best = iou
            }
        }
        return best
    }

    /** 粗筛：只和每字的第一个字体（simhei）比一次，不平移。 */
    fun quick(cand: LongArray, candArea: Int): Float {
        if (!loaded || candArea <= 0) return 0f
        var best = 0f
        for (ch in 0 until N_CHAR) {
            val t = tmpl[ch * N_FACE]
            val ta = inkArea[ch * N_FACE]
            if (ta <= 0) continue
            val inter = intersect(cand, t)
            val iou = inter.toFloat() / (candArea + ta - inter).toFloat()
            if (iou > best) best = iou
        }
        return best
    }

    private fun intersect(a: LongArray, b: LongArray): Int {
        var s = 0
        for (y in 0 until NORM) s += java.lang.Long.bitCount(a[y] and b[y])
        return s
    }

    /** b 平移 (dy,dx) 后与 a 的交集。dx>0 表示 b 右移。 */
    private fun intersectShift(a: LongArray, b: LongArray, dy: Int, dx: Int): Int {
        var s = 0
        for (y in 0 until NORM) {
            val by = y + dy
            if (by < 0 || by >= NORM) continue
            val row = when {
                dx > 0 -> b[by] shl dx
                dx < 0 -> b[by] ushr (-dx)
                else -> b[by]
            }
            s += java.lang.Long.bitCount(a[y] and row)
        }
        return s
    }

    private const val SHIFT_RAD = 2

    private fun area(a: LongArray): Int {
        var s = 0; for (v in a) s += java.lang.Long.bitCount(v); return s
    }

    private fun dilate3(src: LongArray): LongArray {
        val out = LongArray(NORM)
        for (y in 0 until NORM) {
            var v = src[y]
            if (y > 0) v = v or src[y - 1]
            if (y < NORM - 1) v = v or src[y + 1]
            out[y] = v or (v shl 1) or (v ushr 1)
        }
        return out
    }

    private fun erode3(src: LongArray): LongArray {
        val out = LongArray(NORM)
        for (y in 0 until NORM) {
            if (y == 0 || y == NORM - 1) { out[y] = 0L; continue }
            val v = src[y] and src[y - 1] and src[y + 1]
            out[y] = v and (v shl 1) and (v ushr 1)
        }
        return out
    }
}
