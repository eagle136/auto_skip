package com.v5yn133n3.skp.engine

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.v5yn133n3.skp.K
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import com.v5yn133n3.skp.Logs

/**
 * 任务基类，逐条对应旧版 LttService.g。
 *
 * 时间窗：默认 10000ms（需求：切换应用后 10s 内检测），下限 1000ms；
 * 轮询间隔默认 199ms，下限 20ms。
 */
abstract class SkipTask(
    protected val svc: SkipService,
    json: JSONObject,
) : Runnable {

    enum class Kind { KEYWORD, TEXTS2, PATH, POPUP }

    abstract val kind: Kind

    /** 旧版缺 pkg 时用这个哨兵值，任何真实包名都对不上 → 任务天然不命中。 */
    val pkgName: String = json.optString("pkg", "whatever.is.ok")
    val timeoutMs: Int = json.optInt(K.TIMEOUT, K.DEFAULT_TIMEOUT_MS)
        .coerceAtLeast(K.MIN_TIMEOUT_MS)
    /** 界面上这个框已删掉，值就钉死在默认 199；连规则里残留的旧值也不读，免得留个没处看也改不动的隐藏参数。 */
    val intervalMs: Int = K.DEFAULT_INTERVAL_MS
    var clickWay: Int = json.optInt(K.CLICK_WAY, K.CLICK_WAY_AUTO)
    val tryTimes: Int = json.optInt(K.TRY_TIMES, K.DEFAULT_TRY_TIMES)
    val clickTimes: Int = json.optInt(K.CLICK_TIMES, Int.MAX_VALUE)
    val filterDefault: Boolean = json.optBoolean(K.FILTER_DEFAULT, true)
    val maxTextLen: Int = json.optInt(K.MAX_TEXT_LENGTH, K.DEFAULT_MAX_TEXT_LEN)
    val maxDescLen: Int = json.optInt(K.MAX_DESC_LENGTH, K.DEFAULT_MAX_DESC_LEN)
    val filterInvisible: Boolean = json.optBoolean(K.FILTER_INVISIBLE_NODES, false)

    /** 规则里的 clicks 是逐次尝试的允许位；缺省时按旧版内置两组模板。 */
    private val clickPattern: IntArray? = run {
        val arr = json.optJSONArray(K.CLICKS) ?: return@run null
        if (arr.length() == 0) return@run null
        IntArray(arr.length()) { arr.optInt(it) }
    }

    /** 已点过的节点 hashCode 与 windowId 混在一张表里，沿用旧版的记账方式。 */
    val clicked = CopyOnWriteArrayList<Int>()

    @Volatile
    var cancelled = false

    /** 触发本任务的事件时间，用于 VIEW_CLICKED 时判断窗口是否已换。 */
    var eventTime: Long = 0L

    private val counters = HashMap<Int, Int>()

    /** 上一次点击前实际等了多久，写进 hit 日志便于真机核对随机区间。 */
    private var lastNapMs = 0

    /** 上一次点击实际走的分支（node / back / gesture）与手势落点，只用于日志。 */
    private var lastHow = ""
    private var lastTapX = -1
    private var lastTapY = -1

    protected fun bump(key: Int): Int {
        val next = (counters[key] ?: 0) + 1
        counters[key] = next
        return next
    }

    /** 找出候选节点。 */
    abstract fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo?

    /** 命中后的二次过滤（editable/checkable/滚动容器/超长文本等）。 */
    abstract fun postFilter(node: AccessibilityNodeInfo): AccessibilityNodeInfo?

    override fun run() {
        val start = SystemClock.uptimeMillis()
        while (!cancelled) {
            val root = svc.safeRoot()
            var node = find(root)
            if (filterDefault) node = node?.let { postFilter(it) }
            if (node != null) {
                if (bump(node.hashCode()) == 1) svc.notifySkipped()
                val already = clicked.count { it == node.hashCode() }
                if (already < clickTimes) {
                    click(node, clickWay, clickPattern) {
                        logHit(node, clickWay)
                        clicked += node.hashCode()
                        clicked += node.windowId
                    }
                }
            } else if (clicked.isNotEmpty()) {
                val windowId = root?.windowId ?: 0
                if (root != null && bump(windowId) > tryTimes) cancelled = true
            }
            if (SystemClock.uptimeMillis() - start > timeoutMs) break
            if (Thread.currentThread().isInterrupted) break
            if (cancelled) break
            try {
                Thread.sleep(intervalMs.toLong())
            } catch (_: Exception) {
                break
            }
        }
        if (clicked.isEmpty()) {
            Logs.i(SkipService.TAG, "miss kind=$kind pkg=$pkgName " +
                "elapsed=${SystemClock.uptimeMillis() - start}ms window=${timeoutMs}ms")
        }
    }

    /** dt 从触发本任务的事件时间算起：10s 窗口内点到与否，就看这个数。 */
    protected fun logHit(node: AccessibilityNodeInfo, way: Int) {
        // 记来源：四角任务看到这个时间戳就让位，不再对同一颗按钮补第二发
        svc.markHit("node")
        val dt = if (eventTime > 0) SystemClock.uptimeMillis() - eventTime else -1L
        val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        val w = r.width()
        val h = r.height()
        // 框有多大、点落在哪，必须写进日志：用户报「框太大、点不准」时，
        // 之前只能靠截图猜，截图上又看不到节点框（节点路不画 Mark）。
        Logs.i(
            SkipService.TAG,
            "hit kind=$kind pkg=$pkgName way=$way how=$lastHow dt=${dt}ms nap=${lastNapMs}ms " +
                "box=${r.left},${r.top},${r.right},${r.bottom} ${w}x$h" +
                (if (bigBox(r)) " bigNode" else "") +
                (if (lastTapX >= 0) " tap=$lastTapX,$lastTapY" else "") +
                " kids=${node.childCount} node=${describe(node)}",
        )
    }

    /**
     * 这个框大得不像一颗按钮。
     *
     * 真跳过按钮整帧：横胶囊 140×50、圆钮 108×108、澎湃开屏那颗 143×57。
     * 高度超过屏高 8%（2352 上约 188px）或宽度超过屏宽 40% 的，只可能是**容器**
     * ——`KeywordTask.find` 在命中非叶节点、子树里又没有叶子匹配关键词时，会把
     * 容器节点当兜底候选返回。点这种节点的中心，落点在广告画面正中，什么也不会发生。
     */
    private fun bigBox(r: android.graphics.Rect): Boolean {
        val s = svc.screenSizePx()
        return r.height() > s.y * 0.08f || r.width() > s.x * 0.40f
    }

    private fun describe(node: AccessibilityNodeInfo): String =
        (node.text?.toString() ?: node.viewIdResourceName ?: node.contentDescription?.toString()
            ?: node.className?.toString() ?: "?").take(40)

    private fun gate(pattern: IntArray?, attempt: Int, slot: Int): Boolean {
        val p = pattern ?: if (slot == 0) intArrayOf(1, 1, 0, 1, 0, 0) else intArrayOf(1, 0, 1, 0, 0)
        return p[(attempt - 1) % p.size] == 1
    }

    /**
     * 点击派发。way：0=只走节点，1=只走手势，2=返回键，其它=先节点后手势（旧版 a() 的四个分支）。
     */
    protected fun click(
        node: AccessibilityNodeInfo,
        way: Int,
        pattern: IntArray?,
        onDone: () -> Unit,
    ) {
        // 四角刚点过（[SkipService.cornerHitRecently]）→ 这一轮让位。
        // 广告关闭有 0.4–1.2s 延迟，这段时间画面还是旧的，节点路会找到同一个按钮
        // 再点一次，用户看到的就是「点了两下」。
        if (svc.cornerHitRecently()) return
        val key = node.hashCode() + 1000
        val attempt = bump(key)

        if (way == 0) {
            if (!gate(pattern, attempt, 0)) return
            val target = svc.clickableAncestor(node) ?: return
            nap()
            if (!svc.performNodeClick(target)) return
            lastHow = "node"
            onDone()
            return
        }

        if (way == K.CLICK_WAY_BACK) {
            if (attempt != 1) return
            nap()
            if (!svc.performBack()) return
            lastHow = "back"
            onDone()
            return
        }

        /*
         * 手势落点 = 节点框中心。
         *
         * 原来是 `(centerY + bottom) / 2`，也就是框高的 75% 处 —— 对一颗 143×57 的胶囊
         * 是 y=181（框 139–196，还在里面），对**容器**就完蛋：澎湃开屏那次框到 400 高，
         * 75% 落在广告画面正中，用户看到的就是「点了没反应」。按钮的点击区永远是中心，
         * 没有任何一种跳过快按钮需要往下偏。
         */
        val gesturePoint = {
            val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            Pair(r.centerX(), r.centerY())
        }

        if (way == K.CLICK_WAY_GESTURE) {
            if (!gate(pattern, attempt, 1)) return
            if (!node.isVisibleToUser) return
            nap()
            val (x, y) = gesturePoint()
            if (!svc.gestureTap(x, y)) return
            lastHow = "gesture"
            lastTapX = x
            lastTapY = y
            onDone()
            return
        }

        // 自动档：能拿到可点击祖先就先走节点点击，失败后补一次手势
        val target = svc.clickableAncestor(node)
        if (target != null) {
            if (!gate(pattern, attempt, 0)) return
            nap()
            if (svc.performNodeClick(target)) {
                lastHow = "node"
                onDone()
                return
            }
            if (bump(key + 100) != 1) return
            val (x, y) = gesturePoint()
            if (!svc.gestureTap(x, y)) return
            lastHow = "gesture"
            lastTapX = x
            lastTapY = y
            onDone()
            return
        }
        if (!gate(pattern, attempt, 1)) return
        if (!node.isVisibleToUser) return
        nap()
        val (x, y) = gesturePoint()
        if (!svc.gestureTap(x, y)) return
        lastHow = "gesture"
        lastTapX = x
        lastTapY = y
        onDone()
    }

    /** 延迟点击强制开启且不给界面配：每次点击现取一个 50–350ms 随机值，规则里的 delay 不参与。 */
    protected fun nap() {
        lastNapMs = Random.nextInt(K.DELAY_JITTER_MIN_MS, K.DELAY_JITTER_MAX_MS + 1)
        try {
            Thread.sleep(lastNapMs.toLong())
        } catch (_: Exception) {
        }
    }

    companion object {
        fun stringSet(json: JSONObject, key: String, fallback: List<String>): Set<String> {
            val arr: JSONArray? = json.optJSONArray(key)
            val out = LinkedHashSet<String>()
            if (arr == null) {
                out += fallback
            } else {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (s.isNotEmpty()) out += s
                }
            }
            return out
        }

        /** 旧版 d1.l 之外，规则里的 "[a,b]" 字符串也要拆成条目。 */
        fun splitList(json: JSONObject, key: String): List<String> {
            val arr = json.optJSONArray(key)
            if (arr != null) return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
            val raw = json.optString(key, "")
                .removePrefix("[")
                .removeSuffix("]")
            if (raw.isBlank()) return emptyList()
            return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        /** 旧版 d1.h：白名单只认数组，缺键或非数组一律当作空。 */
        fun arrayStrings(json: JSONObject, key: String): List<String> {
            val arr = json.optJSONArray(key) ?: return emptyList()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotEmpty()) out += s
            }
            return out
        }
    }
}
