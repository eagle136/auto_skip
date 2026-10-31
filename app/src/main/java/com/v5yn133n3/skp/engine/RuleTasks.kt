package com.v5yn133n3.skp.engine

import android.view.accessibility.AccessibilityNodeInfo
import com.v5yn133n3.skp.K
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * 关键词任务（旧版 SkipTask，LttService.f）。
 *
 * 遍历顺序：默认把子节点插到栈头（深度优先、前序），reverse_traversal 时反向；
 * 命中一个「既不在 node_whitelist 又匹配关键词」的节点：叶子直接返回，
 * 非叶子则把它记为兜底候选并清空栈，从它的子树里继续找叶子。
 * node_whitelist 在这个任务里是**排除**名单，和弹窗任务里的语义相反，照旧版保留。
 */
class KeywordTask(
    svc: SkipService,
    json: JSONObject,
) : SkipTask(svc, json) {

    override val kind = Kind.KEYWORD

    private val excludeIds: Set<String> = arrayStrings(json, K.NODE_WHITELIST).toSet()
    private val filterCollection: Boolean = json.optBoolean(K.FILTER_COLLECTION_NODES, true)
    private val reverse: Boolean = json.optBoolean(K.REVERSE_TRAVERSAL, false)

    /** 旧版 getBoolean(is_user_check) 抛异常（缺键或非布尔）才退化到「有没有 keywords」。 */
    private val userCheck: Boolean = try {
        json.getBoolean(K.IS_USER_CHECK)
    } catch (_: Exception) {
        json.has(K.KEYWORDS)
    }

    private val conditions: List<(AccessibilityNodeInfo) -> Boolean> = run {
        val words = LinkedHashSet<String>()
        if (json.optBoolean("big_smart_pg", false)) {
            words += "count_down"
            words += "countdown"
        }
        // 旧版分界在 getJSONArray(keywords)：keywords 存在且是数组（哪怕是空数组）就不补默认词，
        // 只有取不到数组才退化到 keywords_append + 跳过/skip/跳過。
        val keywords = json.optJSONArray(K.KEYWORDS)
        if (keywords != null) {
            for (i in 0 until keywords.length()) words += keywords.optString(i)
        } else {
            val append = json.optJSONArray(K.KEYWORDS_APPEND)
            if (append != null) {
                for (i in 0 until append.length()) words += append.optString(i)
            }
            words += "跳过"
            words += "skip"
            if (!LocaleZh_CN) words += "跳過"
        }
        words.map { raw: String ->
            if (userCheck) UserCheckCond(raw, maxTextLen) else KeywordCond(raw, maxTextLen, maxDescLen)
        }
    }

    init {
        if (conditions.isEmpty()) cancelled = true
    }

    override fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null || root.childCount == 0 || root.packageName?.toString() != pkgName) return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.push(root)
        var candidate: AccessibilityNodeInfo? = null
        while (stack.isNotEmpty()) {
            if (cancelled) return null
            val node = stack.pop()
            val id = node.viewIdShort()
            val hit = (id == null || id !in excludeIds) && conditions.any { it(node) }
            if (hit) {
                if (node.childCount == 0) return node
                stack.clear()
                candidate = node
            }
            val children = ArrayList<AccessibilityNodeInfo>()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (filterInvisible && !child.isVisibleToUser) continue
                if (filterCollection && (child.isScrollable || child.collectionInfo != null)) continue
                if (reverse) children.add(child) else children.add(0, child)
            }
            stack.addAll(children)
        }
        return candidate
    }

    override fun postFilter(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable || node.isCheckable) return null
        if (svc.isInsideScrollContainer(node)) return null
        if (svc.isTooWide(node)) return null
        return node
    }

    companion object {
        val LocaleZh_CN: Boolean =
            java.util.Locale.getDefault().let {
                it.language.equals("zh", true) && it.country.equals("cn", true)
            }
    }
}

/**
 * 精确文本 / viewId 任务（旧版 SkipTask2，LttService.e）。
 *
 * `id:xxx` 走 findAccessibilityNodeInfosByViewId，候选里取 bounds 宽度最小的；
 * 其余走 findAccessibilityNodeInfosByText，取文本最短的。
 * 旧版用 HashSet.stream().anyMatch：命中第一个目标就停，后面的目标不再探测。
 * node_whitelist 在这里是**排除**条件（命中白名单描述的节点被丢掉）。
 */
class Texts2Task(
    svc: SkipService,
    json: JSONObject,
) : SkipTask(svc, json) {

    override val kind = Kind.TEXTS2

    /**
     * 缺省目标的顺序照旧版 HashSet 的迭代序（"id:skip" 落 4 号桶、"跳过" 落 5 号桶），
     * 不是随手写的：换序会改变 8 条 task_type=2 规则先用哪个查询命中。
     */
    private val targets: List<String> =
        stringSet(json, K.TEXTS_TASK2, listOf("id:skip", "跳过")).toList()

    private val exclude: List<(AccessibilityNodeInfo) -> Boolean> =
        arrayStrings(json, K.NODE_WHITELIST).map { RichCond(it) }

    private fun notExcluded(node: AccessibilityNodeInfo) = exclude.none { it(node) }

    override fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null || root.childCount == 0 || root.packageName?.toString() != pkgName) return null
        for (target in targets) {
            if (cancelled) return null
            val picked: AccessibilityNodeInfo? = if (target.startsWith("id:")) {
                root.findAccessibilityNodeInfosByViewId(target.substring(3))
                    .filter { it.isVisibleToUser && notExcluded(it) }
                    .minByOrNull { rect(it).width() }
            } else {
                root.findAccessibilityNodeInfosByText(target)
                    .filter { it.isVisibleToUser && notExcluded(it) }
                    .minByOrNull { it.text?.length ?: Int.MAX_VALUE }
                    ?: findLooseText(root, target)
            }
            if (picked != null) return picked
        }
        return null
    }

    /**
     * 系统的 findAccessibilityNodeInfosByText 按原文包含，认不出「跳 过 5」这种字间空格；
     * 它查不到时在整棵树里按归一文本再找一遍，按文本最短取节点。
     */
    private fun findLooseText(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val needle = compactText(target)
        if (needle.isEmpty()) return null
        var best: AccessibilityNodeInfo? = null
        var bestLen = Int.MAX_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.push(root)
        while (stack.isNotEmpty()) {
            if (cancelled) return null
            val node = stack.pop()
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val hit = (text != null && compactText(text).contains(needle, ignoreCase = true)) ||
                (desc != null && compactText(desc).contains(needle, ignoreCase = true))
            if (hit && node.isVisibleToUser && notExcluded(node)) {
                val len = text?.length ?: Int.MAX_VALUE
                if (best == null || len < bestLen) {
                    best = node
                    bestLen = len
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.push(it) }
        }
        return best
    }

    override fun postFilter(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable || node.isCheckable) return null
        if (svc.isInsideScrollContainer(node)) return null
        val text = node.text
        if (text != null && compactText(text).length > maxTextLen && !svc.isTooWide(node)) return null
        return node
    }

    private fun rect(node: AccessibilityNodeInfo) =
        android.graphics.Rect().also { node.getBoundsInScreen(it) }
}

/**
 * 路径任务（旧版 DOCC，LttService.b）。
 *
 * 每条路径是 `drawingOrder&childCount` 序列，逐层下钻定位节点——它不是像素坐标，
 * 只是借用了坐标录入的形式。真正的像素点击在弹窗任务的 `x:<x>y:<y>` 动作里。
 */
class PathTask(
    svc: SkipService,
    json: JSONObject,
    private val path: List<Pair<Int, Int>>,
) : SkipTask(svc, json) {

    override val kind = Kind.PATH

    override fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null || root.childCount == 0 || root.packageName?.toString() != pkgName) return null
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(Pair(root, 0))
        while (queue.isNotEmpty() && !cancelled) {
            val (node, depth) = queue.removeFirst()
            val (order, childCount) = path[depth]
            if (node.drawingOrder == order && node.childCount == childCount) {
                if (depth == path.size - 1) return node
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(Pair(it, depth + 1)) }
                }
            }
        }
        return null
    }

    override fun postFilter(node: AccessibilityNodeInfo) = node
}
