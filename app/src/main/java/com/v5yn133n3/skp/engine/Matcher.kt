package com.v5yn133n3.skp.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * viewId 短名：`com.foo:id/btn_skip` -> `btn_skip`（旧版 d1.l）。
 */
fun AccessibilityNodeInfo.viewIdShort(): String? {
    val name = viewIdResourceName ?: return null
    val parts = name.split("/")
    return if (parts.size > 1) parts[1] else name
}

fun AccessibilityNodeInfo.boundsString(): String {
    val r = Rect().also { getBoundsInScreen(it) }
    return "${r.left},${r.top},${r.right},${r.bottom}"
}

/**
 * 文本匹配前的归一：去掉空白（含全角空格 U+3000、NBSP U+00A0）与零宽字符。
 * 「跳 过 5」「跳过 5」「跳过5」归一后都是「跳过5」。
 */
fun compactText(value: CharSequence): String {
    val out = StringBuilder(value.length)
    for (ch in value) {
        if (ch.isWhitespace() || ch in ZERO_WIDTH) continue
        out.append(ch)
    }
    return out.toString()
}

private val ZERO_WIDTH: Set<Char> = setOf('\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF')

/**
 * 关键词的匹配形态：归一后的小写串；末尾是倒计时数字时再补一个去掉数字的前缀，
 * 这样关键词写成「跳过5」也能点到倒计时变成「跳过 4」的按钮。
 */
fun keywordNeedles(raw: String): List<String> {
    val base = compactText(raw).ifEmpty { raw }.lowercase()
    val cut = base.trimEnd { it.isDigit() }
    return if (cut.isNotEmpty() && cut != base) listOf(base, cut) else listOf(base)
}

/** 文本在长度上限内且包含任一关键词形态（忽略大小写与空白）。 */
private fun needlesHit(value: CharSequence?, needles: List<String>, maxLen: Int): Boolean {
    if (value == null) return false
    val text = compactText(value).lowercase()
    if (text.length > maxLen) return false
    return needles.any { it.isNotEmpty() && text.contains(it) }
}

/** 关键词匹配器，宽松版：id/文本/描述三处小写 contains（空白归一），文本与描述受长度上限约束（旧版 b.a）。 */
class KeywordCond(
    raw: String,
    private val maxTextLen: Int,
    private val maxDescLen: Int,
) : (AccessibilityNodeInfo) -> Boolean {

    private val needles = keywordNeedles(raw)

    override fun invoke(node: AccessibilityNodeInfo): Boolean {
        node.viewIdShort()?.lowercase()?.let { id ->
            if (needles.any { it.isNotEmpty() && id.contains(it) }) return true
        }
        if (needlesHit(node.text, needles, maxTextLen)) return true
        if (needlesHit(node.contentDescription, needles, maxDescLen)) return true
        return false
    }
}

/**
 * 勾选式匹配器（规则里带 is_user_check 时启用，旧版 b$b）：
 * 与宽松版的差别是不比对 contentDescription，且 viewId 与 bounds 走精确相等。
 * 注意：没有描述分支是 smali 里逐条确认过的，不是遗漏。
 */
class UserCheckCond(
    private val raw: String,
    private val maxTextLen: Int,
) : (AccessibilityNodeInfo) -> Boolean {

    private val needle = compactText(raw).ifEmpty { raw }.lowercase()

    override fun invoke(node: AccessibilityNodeInfo): Boolean {
        node.viewIdShort()?.let { if (it == raw) return true }
        val text = node.text
        if (text != null) {
            val key = compactText(text).lowercase()
            if (key.length <= maxTextLen && needle.isNotEmpty() && key.contains(needle)) return true
        }
        if (node.isVisibleToUser && raw.contains(",")) {
            if (node.boundsString() == raw) return true
        }
        return false
    }
}

/**
 * 富语法匹配器（旧版 b$c）：文本/描述支持 `=` 精确、`+` 前缀、`-` 后缀、裸串包含；
 * 含逗号的串按 `left,top,right,bottom` 匹配节点 bounds，可再带一段 `,checked`。
 * 该匹配器要求节点可见。
 */
class RichCond(private val raw: String) : (AccessibilityNodeInfo) -> Boolean {

    private val full = compactText(raw)
    private val arg = compactText(if (raw.isEmpty()) raw else raw.substring(1))

    private fun textHit(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        val key = compactText(value)
        return when {
            raw.startsWith("=") -> key == arg
            raw.startsWith("+") -> key.startsWith(arg)
            raw.startsWith("-") -> key.endsWith(arg)
            else -> key.contains(full)
        }
    }

    override fun invoke(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        node.viewIdShort()?.let { if (it == raw) return true }
        if (textHit(node.text?.toString())) return true
        if (textHit(node.contentDescription?.toString())) return true
        if (raw.contains(",")) {
            val bounds = node.boundsString()
            if (raw == bounds) return true
            val checked = "$bounds,${if (node.isChecked) 1 else 0}"
            if (raw == checked) return true
        }
        return false
    }
}
