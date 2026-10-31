package com.v5yn133n3.skp.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.v5yn133n3.skp.K
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * 弹窗规则动作。
 * - idList：需要同时命中的节点条件（`&` 分隔，富语法）
 * - action：`GLOBAL_ACTION_BACK` 走返回键；`x:<x>y:<y>` 走手势点坐标；
 *   其余文本本身也当成一条节点条件，命中的那个节点被点击。
 */
class PopupTask(
    svc: SkipService,
    json: JSONObject,
) : SkipTask(svc, json) {

    override val kind = Kind.POPUP

    data class Rule(val idList: List<String>, val action: String, val times: Int)

    private val rules: List<Rule> = run {
        val out = ArrayList<Rule>()
        val arr = json.optJSONArray(K.POPUP_RULES)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ids = o.optString("id").split("&").filter { it.isNotEmpty() }
                if (ids.isEmpty()) continue
                out.add(Rule(ids, o.optString("action"), o.optInt("times", 1)))
            }
        }
        if (json.optBoolean("filter_young_popups", false)) {
            out.add(Rule(listOf("青少年", "模式"), "-知道了", 2))
        }
        out
    }

    private var searchTimes = json.optInt(K.SEARCH_TIMES_POPUP, K.POPUP_DEFAULT_SEARCH_TIMES)
    private val popupInterval = json.optInt(K.SEARCH_INTERVAL_POPUP, K.POPUP_DEFAULT_INTERVAL_MS)
        .coerceAtLeast(K.POPUP_MIN_INTERVAL_MS)
    private val popupWay = json.optInt(K.CLICK_WAY_POPUP, K.CLICK_WAY_NODE)

    init {
        if (rules.isEmpty()) cancelled = true
    }

    private val matchers = ArrayList<Pair<(AccessibilityNodeInfo) -> Boolean, AccessibilityNodeInfo?>>()

    override fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? = null

    override fun postFilter(node: AccessibilityNodeInfo) = node

    /**
     * 一次 DFS 收集每个条件首次命中的节点；全部命中后返回「命中 action 条件」的那个节点
     * （action 是返回键/坐标时，取第一条 id 命中的节点）。
     */
    private fun locate(
        root: AccessibilityNodeInfo?,
        actionMatcher: ((AccessibilityNodeInfo) -> Boolean)?,
    ): AccessibilityNodeInfo? {
        if (root == null || root.childCount == 0 || root.packageName?.toString() != pkgName) return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.push(root)
        val collected = ArrayList<AccessibilityNodeInfo>()
        while (stack.isNotEmpty() && !cancelled) {
            val node = stack.pop()
            var matched = false
            for (i in matchers.indices) {
                val (cond, hit) = matchers[i]
                if (hit == null && cond(node)) {
                    matchers[i] = Pair(cond, node)
                    collected.add(node)
                    matched = true
                }
            }
            if (matched && matchers.all { it.second != null }) {
                return if (actionMatcher == null) collected.first()
                else collected.firstOrNull { actionMatcher(it) } ?: collected.first()
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (child.isVisibleToUser) stack.push(child)
            }
        }
        return null
    }

    private fun handleRule(rule: Rule): Boolean {
        val isBack = rule.action == "GLOBAL_ACTION_BACK"
        val isXY = rule.action.startsWith("x:") && rule.action.contains("y:")
        matchers.clear()
        rule.idList.forEach { matchers.add(Pair(RichCond(it), null)) }
        val actionMatcher: ((AccessibilityNodeInfo) -> Boolean)? = when {
            isBack || isXY -> matchers.first().first
            else -> RichCond(rule.action).also { matchers.add(Pair(it, null)) }
        }
        if (matchers.isEmpty()) return false

        val node = locate(svc.safeRoot(), actionMatcher) ?: return false
        if (bump(node.hashCode()) == 1 && rule.times > 0) svc.notifySkipped()
        if (clicked.count { it == node.hashCode() } >= rule.times) return true

        val onDone = {
            logHit(node, if (isXY) K.CLICK_WAY_GESTURE else if (isBack) K.CLICK_WAY_BACK else popupWay)
            clicked += node.hashCode()
            clicked += node.windowId
        }
        if (isXY) {
            val parts = rule.action.substring(2).split("y:")
            val x = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val y = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (x > 0 && y > 0) {
                nap()
                if (svc.gestureTap(x, y)) onDone()
            }
        } else {
            click(node, if (isBack) K.CLICK_WAY_BACK else popupWay, intArrayOf(1), onDone)
        }
        return true
    }

    override fun run() {
        while (!cancelled) {
            searchTimes--
            val anyMatch = rules.any { handleRule(it) }
            if (rules.isEmpty()) break
            val lastWindow = clicked.lastOrNull()
            if (clicked.isNotEmpty()) {
                if (lastWindow != null && !anyMatch && bump(lastWindow + 2000) > 1) break
                if (Thread.currentThread().isInterrupted) break
                sleep()
            } else {
                if (searchTimes <= 0) break
                if (Thread.currentThread().isInterrupted) break
                sleep()
            }
        }
        cancelled = true
        svc.dropTask(this)
    }

    private fun sleep() {
        try {
            Thread.sleep(popupInterval.toLong())
        } catch (_: Exception) {
        }
    }
}
