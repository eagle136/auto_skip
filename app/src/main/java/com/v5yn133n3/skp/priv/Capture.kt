package com.v5yn133n3.skp.priv

import android.os.SystemClock
import android.util.Log
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.v5yn133n3.skp.Logs

/**
 * 整帧截图通道：`screencap` 不带文件名，裸帧（16B 头 + BGRA8888）直接从 stdout
 * 读进内存，全程不落盘、不建 Bitmap。
 *
 * 不走 `screencap -p`：PNG 编码实测 0.96–1.10 s，裸帧 210 ms（见 plan/四角OCR跳过方案.md §1）；
 * 也不写文件再回读——同样的字节多走一趟 UFS 没有收益。
 */
object Capture {

    private const val TAG = "SkipCapture"
    private const val HEADER = 16

    /** 最近一次失败原因，状态行直接显示。 */
    @Volatile
    var lastError: String = ""
        private set

    /** 最近一帧「起进程到读完」的耗时。 */
    @Volatile
    var lastMs: Long = 0
        private set

    class Frame(val width: Int, val height: Int, val bgra: ByteArray) {
        val bytes: Int get() = 4 * width * height
    }

    // 每帧 10 MB，复用同一块：按帧分配会让大对象区每秒抖动 40–50 MB
    private var buf = ByteArray(0)

    @Synchronized
    fun grab(mode: Mode, timeoutMs: Long = 2500): Frame? {
        val t0 = SystemClock.uptimeMillis()
        var w = 0
        var h = 0
        var got = false
        val err = Shell.binary("screencap", mode, timeoutMs) { input: InputStream ->
            val head = ByteArray(HEADER)
            var off = 0
            while (off < HEADER) {
                val n = input.read(head, off, HEADER - off)
                if (n < 0) throw EOFException("裸帧头只有 $off B")
                off += n
            }
            val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            w = bb.int
            h = bb.int
            val fmt = bb.int
            if (w <= 0 || h <= 0 || fmt != 1) {
                throw IllegalStateException("裸帧格式不支持 w=$w h=$h fmt=$fmt")
            }
            val need = 4 * w * h
            if (buf.size < need) buf = ByteArray(need)
            var p = 0
            while (p < need) {
                val n = input.read(buf, p, need - p)
                if (n < 0) throw EOFException("裸帧只到 $p/$need B")
                p += n
            }
            got = true
        }
        lastMs = SystemClock.uptimeMillis() - t0
        if (!got) {
            lastError = "截图失败(${lastMs}ms)：${err.ifBlank { "没有帧头" }}"
            return null
        }
        if (err.isNotBlank()) Logs.w(TAG, "screencap stderr: $err")
        lastError = ""
        return Frame(w, h, buf)
    }

    /**
     * 把一块区域的亮度抽出来（Rec.601，整数权重）。行步长 = 整帧宽，
     * 所以只需要一次内存遍历，不复制整帧。
     */
    fun luminance(frame: Frame, x0: Int, y0: Int, w: Int, h: Int): IntArray =
        IntArray(w * h).also { luminanceInto(frame, x0, y0, w, h, it) }

    /**
     * 同 [luminance]，但写到调用方给的数组里。
     *
     * 扫带一帧要切 25 块 256×256，每块现分配就是 25 × 256 KB/帧的垃圾；
     * 复用一块缓冲后整条链每帧只碰 0 次大对象分配（帧本身也是复用的）。
     */
    fun luminanceInto(frame: Frame, x0: Int, y0: Int, w: Int, h: Int, out: IntArray) {
        val src = frame.bgra
        var s = 4 * (y0 * frame.width + x0)
        var d = 0
        for (y in 0 until h) {
            var p = s
            for (x in 0 until w) {
                val b = src[p].toInt() and 0xFF
                val g = src[p + 1].toInt() and 0xFF
                val r = src[p + 2].toInt() and 0xFF
                out[d++] = (r * 299 + g * 587 + b * 114) / 1000
                p += 4
            }
            s += 4 * frame.width
        }
    }

    /**
     * 整帧压一半的亮度图（2×2 盒式平均），供 [com.v5yn133n3.skp.engine.BandScan] 扫带用。
     *
     * 一次遍历出 540×1176 的亮度，之后每块瓦片只从这块缓冲里拷 160×100；
     * 好处是像素级的连通域成本直接降到 1/4，且不用为每块瓦片重算 Rec.601。
     */
    fun halveLumaInto(frame: Frame, out: IntArray) {
        val w = frame.width
        val hw = w / 2
        val hh = frame.height / 2
        val src = frame.bgra
        for (y in 0 until hh) {
            val row0 = 4 * (2 * y * w)
            val row1 = row0 + 4 * w
            var d = y * hw
            for (x in 0 until hw) {
                val a = row0 + 8 * x
                val b = row1 + 8 * x
                val bb = (src[a].toInt() and 0xFF) + (src[a + 4].toInt() and 0xFF) +
                    (src[b].toInt() and 0xFF) + (src[b + 4].toInt() and 0xFF)
                val gg = (src[a + 1].toInt() and 0xFF) + (src[a + 5].toInt() and 0xFF) +
                    (src[b + 1].toInt() and 0xFF) + (src[b + 5].toInt() and 0xFF)
                val rr = (src[a + 2].toInt() and 0xFF) + (src[a + 6].toInt() and 0xFF) +
                    (src[b + 2].toInt() and 0xFF) + (src[b + 6].toInt() and 0xFF)
                out[d++] = (rr * 299 + gg * 587 + bb * 114) / 4000
            }
        }
    }
}
