package com.v5yn133n3.skp.engine

import android.content.Context
import android.util.Log
import com.v5yn133n3.skp.priv.Capture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import com.v5yn133n3.skp.Logs

/**
 * 第二级：把候选框缩到 96×32 过三层卷积，输出「是跳过」的概率（阈值 0.9）。
 *
 * 权重 = tools/train_skip_torch.py --width 2 --head meanmax，42,738 参数 / 170,952 B；
 * 前处理与训练一致：单图逐通道 (x/255 − mean)/std。全部在内存里算，不建 Bitmap。
 */
object SkipNet {

    const val IN_W = 96
    const val IN_H = 32
    const val THRESHOLD = 0.9f

    /** 多尺度复测的框缩放：负=内缩，正=外扩（详见 [scoreRobust]）。 */
    private val SCALES = floatArrayOf(-0.08f, 0.08f, 0.16f)

    /**
     * 近方形框（圆形按钮）的「救分」档：只外扩。
     *
     * 白倒计时环贴着框边被切掉时，分数会**假性归零**：实测左上角那颗圆钮
     * 内框 88×96 打 0.000，每边各外扩 2%（→ 98×106）就是 1.000，扩到 1.30 倍才又掉回 0.004。
     * 这种掉点来自「白弧在框边断头」，不是「框里没字」，所以只有外扩这一个方向有效，
     * 内缩只会把它切得更狠。
     */
    private val RESCUE = floatArrayOf(0.08f, 0.16f)

    private const val TAG = "SkipNet"
    private const val TOTAL = 42738

    // 网络形状（width=2）
    private const val C0 = 32
    private const val C1 = 48
    private const val C2 = 64

    @Volatile
    private var ready = false

    @Volatile
    var lastError: String = ""
        private set

    private var c0w = FloatArray(0)
    private var c0b = FloatArray(0)
    private var c1w = FloatArray(0)
    private var c1b = FloatArray(0)
    private var c2w = FloatArray(0)
    private var c2b = FloatArray(0)
    private var fw = FloatArray(0)
    private var fb = FloatArray(0)

    // 逐层缓冲，复用；调用方在四角线程里串行跑，不需要锁
    private val in0 = FloatArray(3 * IN_H * IN_W)
    private val a1 = FloatArray(C0 * IN_H * IN_W)
    private val p1 = FloatArray(C0 * (IN_H / 2) * (IN_W / 2))
    private val a2 = FloatArray(C1 * (IN_H / 2) * (IN_W / 2))
    private val p2 = FloatArray(C1 * (IN_H / 4) * (IN_W / 4))
    private val a3 = FloatArray(C2 * (IN_H / 4) * (IN_W / 4))
    private val feat = FloatArray(2 * C2)
    private val logits = FloatArray(2)

    @Synchronized
    fun prepare(ctx: Context): Boolean {
        if (ready) return true
        return try {
            val bytes = ctx.assets.open("skipnet_f32.bin").use { it.readBytes() }
            val n = bytes.size / 4
            if (n != TOTAL) {
                lastError = "权重 $n 个 float，期望 $TOTAL"
                Logs.w(TAG, lastError)
                return false
            }
            // export 顺序：c0.w c0.b c1.w c1.b c2.w c2.b f.w f.b，顺序读即可
            val fl = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            c0w = FloatArray(C0 * 3 * 9).also { fl.get(it) }
            c0b = FloatArray(C0).also { fl.get(it) }
            c1w = FloatArray(C1 * C0 * 9).also { fl.get(it) }
            c1b = FloatArray(C1).also { fl.get(it) }
            c2w = FloatArray(C2 * C1 * 9).also { fl.get(it) }
            c2b = FloatArray(C2).also { fl.get(it) }
            fw = FloatArray(2 * 2 * C2).also { fl.get(it) }
            fb = FloatArray(2).also { fl.get(it) }
            if (fl.hasRemaining()) {
                lastError = "权重尾部多出 ${fl.remaining()} 个 float"
                Logs.w(TAG, lastError)
                return false
            }
            ready = true
            lastError = ""
            Logs.i(TAG, "ready params=$TOTAL")
            true
        } catch (e: Throwable) {
            lastError = "权重加载失败：${e.message}"
            Logs.w(TAG, lastError)
            false
        }
    }

    /** 候选框（整帧坐标）是跳过的概率；未就绪返回 -1。缓冲是复用的，加锁防自检撞车。 */
    @Synchronized
    fun score(frame: Capture.Frame, x0: Int, y0: Int, w: Int, h: Int): Float {
        if (!ready || w < 4 || h < 4) return -1f
        resampleInto(frame, x0, y0, w, h)
        forward()
        val p = Math.exp(logits[1].toDouble()) /
            (Math.exp(logits[0].toDouble()) + Math.exp(logits[1].toDouble()))
        return p.toFloat()
    }

    /**
     * 多尺度复测：原框 + 内缩 8% + 外扩 8% + 外扩 16%，四个尺度各打一次取**最大**。
     *
     * 历史：旧版是「原框 ≥ 阈值才外扩 8%，取两者较小值」。那个判据在旧权重上能压误报
     * （金色叶片 0.933→0.070、加速版 0.795→0.023），但当前权重的决策边界对框的松紧
     * 极敏感，而且**非单调**：真机实测的那枚圆形按钮（107×95），原框 1.000，
     * 每边外扩 8%（123×111）掉到 0.209，扩到 12% 又回到 1.000，20% 才真正失效。
     * 这种孤立掉点是 42k 参数 + per-image 标准化的固有脆性，不是「多留边就认不出」
     * 的证据 —— 取 min 会把一个随机掉点直接变成漏检（这正是用户报的"这版完全失败"）。
     *
     * 改成取 max 后语义才正确：内缩能救「提案框偏松」，外扩能救「提案框偏紧、字被切边」，
     * 任一尺度认得出就算认得出。防误报靠的是「四个尺度里至少一个」这个条件本身没放松
     * —— 误报块（叶片、标题字、状态栏）在四个尺度上都不会突然变成一行完整的「跳过」。
     *
     * 代价：只在原框 p ≥ 阈值 - 0.25 时才做剩余三档，非跳过帧通常只跑 1–2 次前向。
     */
    @Synchronized
    /**
     * 预热：把卷积循环 JIT 编译 + 各种数组分配烫一遍。
     *
     * 为什么必须有 —— 冷启动第一帧实测 **1528ms**（其中 scoreRobust 之外的瓦片处理
     * 占一多半），而第二帧只要 144ms。广告的首个「跳过」往往就在这一秒里出现，
     * 用户看到的就是「应用启动了三四秒才动」。在服务连接后和任务启动前各跑几次，
     * 这点开销（几十毫秒）从关键时刻挪到后台。
     */
    fun warmup(times: Int = 3) {
        if (!ready) return
        val f = Capture.Frame(192, 80, ByteArray(4 * 192 * 80).also { java.util.Arrays.fill(it, 0x60.toByte()) })
        try {
            repeat(times) { i ->
                scoreRobust(f, 0, 0, 120 + i * 8, 40 + i * 4)
                scoreRobust(f, 0, 0, 44, 44, rescue = true)
            }
        } catch (e: Throwable) {
            Logs.w(TAG, "warmup 失败 ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun scoreRobust(frame: Capture.Frame, x0: Int, y0: Int, w: Int, h: Int, rescue: Boolean = false): Float {
        val p = score(frame, x0, y0, w, h)
        if (p >= 0.999f) return p                 // 满分不再折腾
        // 离阈值太远：一般直接认输（非跳过帧就靠这条把调用数压在 1–2 次）。
        // 例外是近方形框（圆钮）——它的低分常常是「白环在框边断头」的假象，
        // 外扩一档就能回到 1.000，所以只对它补两档外扩。
        val ks = if (p < THRESHOLD - 0.25f) {
            if (!rescue) return p
            RESCUE
        } else {
            SCALES
        }
        var best = p
        for (k in ks) {
            val dx = maxOf(1, (w * k).toInt())
            val dy = maxOf(1, (h * k).toInt())
            val nx = (x0 - dx).coerceAtLeast(0)
            val ny = (y0 - dy).coerceAtLeast(0)
            val nx1 = (x0 + w + dx).coerceAtMost(frame.width)
            val ny1 = (y0 + h + dy).coerceAtMost(frame.height)
            val w2 = nx1 - nx
            val h2 = ny1 - ny
            if (w2 < 8 || h2 < 8) continue        // 内缩到比字还小就没意义
            val q = score(frame, nx, ny, w2, h2)
            if (q > best) best = q
        }
        return best
    }

    /** 双线性缩放到 96×32，同时算逐通道均值/方差并标准化进 in0。 */
    private fun resampleInto(frame: Capture.Frame, x0: Int, y0: Int, w: Int, h: Int) {
        val src = frame.bgra
        val stride = frame.width
        val plane = IN_W * IN_H
        val xs = IntArray(IN_W)
        val xf = FloatArray(IN_W)
        for (dx in 0 until IN_W) {
            val s = (dx + 0.5f) * w / IN_W - 0.5f
            var ix = floor(s).toInt()
            var f = s - ix
            if (ix < 0) { ix = 0; f = 0f }
            if (ix >= w - 1) { ix = (w - 2).coerceAtLeast(0); f = 0f }
            xs[dx] = ix
            xf[dx] = f
        }
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var qr = 0.0
        var qg = 0.0
        var qb = 0.0
        for (dy in 0 until IN_H) {
            val s = (dy + 0.5f) * h / IN_H - 0.5f
            var iy = floor(s).toInt()
            var fy = s - iy
            if (iy < 0) { iy = 0; fy = 0f }
            if (iy >= h - 1) { iy = (h - 2).coerceAtLeast(0); fy = 0f }
            val row0 = 4 * ((y0 + iy) * stride + x0)
            val row1 = 4 * ((y0 + iy + 1) * stride + x0)
            for (dx in 0 until IN_W) {
                val ix = xs[dx]
                val fx = xf[dx]
                val a = row0 + 4 * ix
                val b = row1 + 4 * ix
                val b00 = src[a].toInt() and 0xFF
                val g00 = src[a + 1].toInt() and 0xFF
                val r00 = src[a + 2].toInt() and 0xFF
                val b01 = src[a + 4].toInt() and 0xFF
                val g01 = src[a + 5].toInt() and 0xFF
                val r01 = src[a + 6].toInt() and 0xFF
                val b10 = src[b].toInt() and 0xFF
                val g10 = src[b + 1].toInt() and 0xFF
                val r10 = src[b + 2].toInt() and 0xFF
                val b11 = src[b + 4].toInt() and 0xFF
                val g11 = src[b + 5].toInt() and 0xFF
                val r11 = src[b + 6].toInt() and 0xFF
                val r = ((r00 * (1 - fx) + r01 * fx) * (1 - fy) + (r10 * (1 - fx) + r11 * fx) * fy) / 255f
                val g = ((g00 * (1 - fx) + g01 * fx) * (1 - fy) + (g10 * (1 - fx) + g11 * fx) * fy) / 255f
                val bl = ((b00 * (1 - fx) + b01 * fx) * (1 - fy) + (b10 * (1 - fx) + b11 * fx) * fy) / 255f
                val di = dy * IN_W + dx
                sr += r; sg += g; sb += bl
                qr += r.toDouble() * r; qg += g.toDouble() * g; qb += bl.toDouble() * bl
                in0[di] = r
                in0[plane + di] = g
                in0[2 * plane + di] = bl
            }
        }
        val mr = (sr / plane).toFloat()
        val mg = (sg / plane).toFloat()
        val mb = (sb / plane).toFloat()
        val dr = (sqrt(qr / plane - mr.toDouble() * mr) + 1e-5).toFloat()
        val dg = (sqrt(qg / plane - mg.toDouble() * mg) + 1e-5).toFloat()
        val db = (sqrt(qb / plane - mb.toDouble() * mb) + 1e-5).toFloat()
        for (i in 0 until plane) {
            in0[i] = (in0[i] - mr) / dr
            in0[plane + i] = (in0[plane + i] - mg) / dg
            in0[2 * plane + i] = (in0[2 * plane + i] - mb) / db
        }
    }

    private fun forward() {
        conv(in0, 3, IN_H, IN_W, c0w, c0b, C0, a1)
        reluPool2(a1, C0, IN_H, IN_W, p1)
        conv(p1, C0, IN_H / 2, IN_W / 2, c1w, c1b, C1, a2)
        reluPool2(a2, C1, IN_H / 2, IN_W / 2, p2)
        conv(p2, C1, IN_H / 4, IN_W / 4, c2w, c2b, C2, a3)
        val sp = (IN_H / 4) * (IN_W / 4)
        for (c in 0 until C2) {
            val base = c * sp
            var sum = 0f
            var mx = Float.NEGATIVE_INFINITY
            for (i in 0 until sp) {
                val v = a3[base + i]
                val r = if (v > 0f) v else 0f
                a3[base + i] = r
                sum += r
                if (r > mx) mx = r
            }
            feat[c] = sum / sp
            feat[C2 + c] = mx
        }
        for (k in 0..1) {
            var acc = fb[k]
            for (i in 0 until 2 * C2) acc += fw[k * 2 * C2 + i] * feat[i]
            logits[k] = acc
        }
    }

    /** 3×3、步长 1、零填充；输出先填 bias 再累加，等价于 conv+bias。 */
    private fun conv(
        src: FloatArray, sc: Int, sh: Int, sw: Int,
        w: FloatArray, b: FloatArray, oc: Int, dst: FloatArray,
    ) {
        val plane = sh * sw
        for (o in 0 until oc) {
            val ob = o * plane
            val bo = b[o]
            for (i in 0 until plane) dst[ob + i] = bo
            for (c in 0 until sc) {
                val wb = (o * sc + c) * 9
                val sbase = c * plane
                for (ky in -1..1) {
                    val y0 = if (ky < 0) -ky else 0
                    val y1 = if (ky > 0) sh - 1 - ky else sh - 1
                    if (y0 > y1) continue
                    for (kx in -1..1) {
                        val wv = w[wb + (ky + 1) * 3 + (kx + 1)]
                        if (wv == 0f) continue
                        val x0 = if (kx < 0) -kx else 0
                        val x1 = if (kx > 0) sw - 1 - kx else sw - 1
                        if (x0 > x1) continue
                        for (y in y0..y1) {
                            val srow = sbase + (y + ky) * sw + kx
                            val drow = ob + y * sw
                            var i = x0
                            while (i <= x1) {
                                dst[drow + i] += wv * src[srow + i]
                                i++
                            }
                        }
                    }
                }
            }
        }
    }

    /** relu 与 2×2 最大池可交换，先取 max 再截零省一半比较。 */
    private fun reluPool2(src: FloatArray, sc: Int, sh: Int, sw: Int, dst: FloatArray) {
        val oh = sh / 2
        val ow = sw / 2
        val ip = sh * sw
        val op = oh * ow
        for (c in 0 until sc) {
            val sb = c * ip
            val db = c * op
            for (y in 0 until oh) {
                val r0 = sb + 2 * y * sw
                val r1 = r0 + sw
                val dr = db + y * ow
                for (x in 0 until ow) {
                    var m = src[r0 + 2 * x]
                    val a = src[r0 + 2 * x + 1]
                    if (a > m) m = a
                    val b = src[r1 + 2 * x]
                    if (b > m) m = b
                    val d = src[r1 + 2 * x + 1]
                    if (d > m) m = d
                    dst[dr + x] = if (m > 0f) m else 0f
                }
            }
        }
    }

    /**
     * 移植自检：喂一组固定输入，把 logits 和 torch 的结果对一下。
     * 期望值来自 `D:/tmp/skipnet_flat8/skipnet.pt`（torch 16.256311 / -16.270863，
     * 纯色 + 圆形按钮 + 界面硬负样本版）。换权重后这两个数必须同步更新，否则
     * maxdiff 会一直报 0.x —— 那是期望值过期，不是移植出错。
     *
     * 输入布局是 **CHW**（3 个 IN_W*IN_H 平面依次排开）。这一点是拿 wall3 反推确认的：
     * 同一组数按 HWC 排会得到 11.886391，与 torch 对不上，按 CHW 排则逐位相同。
     */
    @Synchronized
    fun selfTest(ctx: Context): String {
        if (!prepare(ctx)) return "prepare failed: $lastError"
        val plane = IN_W * IN_H
        for (i in 0 until 3 * plane) {
            in0[i] = ((i * 37 + 11) % 251) / 251f - 0.5f
        }
        forward()
        val d0 = abs(logits[0] - 16.256311f)
        val d1 = abs(logits[1] + 16.270863f)
        val msg = "selftest logits=${logits[0]},${logits[1]} maxdiff=${maxOf(d0, d1)}"
        Logs.i(TAG, msg)
        return msg
    }
}
