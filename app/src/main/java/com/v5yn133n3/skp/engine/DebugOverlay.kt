package com.v5yn133n3.skp.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.v5yn133n3.skp.Logs

/**
 * 调试悬浮窗：把扫带那一刻「提案器提出的框 + 模型打分」直接画在屏幕上。
 *
 * 只在 [com.v5yn133n3.skp.data.RuleStore.debugOverlay] 打开、且用户授予了
 * SYSTEM_ALERT_WINDOW 时才会挂窗口；平时一条 View 都不建（原来的包连权限都不申请）。
 *
 * 坐标契约：Mark 用的是**整帧截图坐标**（screencap 输出的像素），本类按
 * `视图宽 / 帧宽` 换算到屏幕，因此旋转屏、异形屏都不用改扫描侧代码。
 */
object DebugOverlay {

    private const val TAG = "SkipDebug"

    /** 一个待标记的框。`hit=true` 表示模型分 ≥ 阈值（会真的点下去的那个）。 */
    class Mark(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val label: String,
        val hit: Boolean,
        val dim: Boolean = false,
    )

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var view: BoxView? = null
    private var added = false

    private val clearTask = Runnable { detach() }

    fun canDraw(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (_: Throwable) {
        false
    }

    /** 刷新一屏标记。同一帧里反复调用是安全的，后一次覆盖前一次。 */
    fun show(ctx: Context, frameW: Int, frameH: Int, marks: List<Mark>, head: String) {
        // 兜底：开关关了就绝不建窗、绝不画。任务里每轮也会重新读开关，
        // 这里再拦一道，防止「设置页关了、任务还在跑旧帧」把框画回来。
        if (!com.v5yn133n3.skp.data.RuleStore.debugOverlay) {
            main.removeCallbacks(clearTask)
            main.post { detach() }
            return
        }
        main.removeCallbacks(clearTask)
        main.post {
            val v = ensure(ctx) ?: return@post
            v.frameW = frameW
            v.frameH = frameH
            v.marks = marks
            v.head = head
            v.invalidate()
        }
    }

    /** 任务结束后留一会儿再撤，方便肉眼确认 / 截图。 */
    fun clearSoon(delayMs: Long = 2500) {
        main.removeCallbacks(clearTask)
        main.postDelayed(clearTask, delayMs)
    }

    fun hide() {
        main.removeCallbacks(clearTask)
        main.post { detach() }
    }

    private fun ensure(ctx: Context): BoxView? {
        if (added) return view
        val app = ctx.applicationContext
        val w = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val v = view ?: BoxView(app).also { view = it }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 全屏广告会把画面画到状态栏下面，标记也要能画到那里
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return try {
            w.addView(v, lp)
            wm = w
            added = true
            v
        } catch (e: Throwable) {
            Logs.w(TAG, "addView failed: ${e.message}")
            null
        }
    }

    private fun detach() {
        val v = view ?: return
        if (!added) return
        try {
            wm?.removeViewImmediate(v)
        } catch (_: Throwable) {
        }
        added = false
    }

    /**
     * 纯 Canvas 画，不建 Bitmap。三种颜色就是三种状态：
     * 青 = 提出来的框但分很低（提案器的锅）；琥珀 = 分在中段（阈值/权重差一口气）；
     * 红 = 命中，同一位置会被点。
     */
    private class BoxView(ctx: Context) : View(ctx) {

        var marks: List<Mark> = emptyList()
        var frameW = 0
        var frameH = 0
        var head = ""

        private val density = resources.displayMetrics.density
        private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * density
            color = Color.BLACK
        }
        private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            val sw = width.toFloat()
            val sh = height.toFloat()
            if (sw <= 0f || sh <= 0f) return
            val kx = if (frameW > 0) sw / frameW else 1f
            val ky = if (frameH > 0) sh / frameH else 1f

            for (m in marks) {
                val color = when {
                    m.hit -> 0xFFFF2D55.toInt()
                    m.dim -> 0xFF8A8A8A.toInt()
                    else -> 0xFFFFC400.toInt()
                }
                box.color = color
                r.set(
                    m.x * kx, m.y * ky,
                    (m.x + m.w) * kx, (m.y + m.h) * ky,
                )
                canvas.drawRect(r, box)
                // 先把中心取出来：drawChip 复用同一个 RectF，会把 r 覆盖掉，
                // 直接读 r.centerX() 会让十字标画到标签的位置上
                val cx = r.centerX()
                val cy = r.centerY()
                drawChip(canvas, r.left, r.top, m.label, color)
                if (m.hit) {
                    canvas.drawLine(cx - 12f * density, cy, cx + 12f * density, cy, box)
                    canvas.drawLine(cx, cy - 12f * density, cx, cy + 12f * density, box)
                }
            }

            if (head.isNotEmpty()) drawChip(canvas, 8f * density, 6f * density, head, 0xFF00E5FF.toInt())
        }

        private fun drawChip(canvas: Canvas, x: Float, y: Float, label: String, color: Int) {
            if (label.isEmpty()) return
            val pad = 4f * density
            val tw = text.measureText(label)
            val th = text.textSize
            val left = x.coerceAtLeast(2f * density)
            // 标签放在框上方；框贴顶时落到框内，避免画到屏幕外
            val top = (y - th - pad * 2).coerceAtLeast(2f * density)
            chip.color = color
            chip.alpha = 235
            r.set(left, top, left + tw + pad * 2, top + th + pad * 2)
            canvas.drawRoundRect(r, 3f * density, 3f * density, chip)
            canvas.drawText(label, left + pad, top + th + pad * 0.6f, text)
            chip.alpha = 255
        }
    }
}
