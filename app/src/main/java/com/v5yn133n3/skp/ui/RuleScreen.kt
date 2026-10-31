package com.v5yn133n3.skp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v5yn133n3.skp.K
import com.v5yn133n3.skp.data.RuleStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单应用规则页。字段名沿用旧版，规则导入过来直接能用。
 * 每次改动即时写回用户覆盖层，没有「保存」按钮这种会丢东西的设计。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleScreen(pkg: String, onBack: () -> Unit) {
    var json by remember(pkg) { mutableStateOf(RuleStore.ruleOf(pkg)) }
    val context = LocalContext.current
    val appLabel = remember(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    fun put(key: String, value: Any?) {
        RuleStore.setUserField(pkg, key, value)
        json = RuleStore.ruleOf(pkg)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        appIcon(pkg)?.let {
                            Image(
                                it,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)),
                            )
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                appLabel,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                middleEllipsized(pkg, MaterialTheme.typography.labelSmall),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                // 返回挪到左上角，和「生效应用列表」用同一个箭头（原来是右上角一个「返回」文字）
                navigationIcon = { BackArrow(onBack) },
            )
        }
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("时间窗口") {
                Text(
                    "点击前随机延迟：OCR ${K.OCR_TAP_JITTER_MIN_MS}–${K.OCR_TAP_JITTER_MAX_MS}ms / " +
                        "无障碍 ${K.DELAY_JITTER_MIN_MS}–${K.DELAY_JITTER_MAX_MS}ms",
                    style = MaterialTheme.typography.bodySmall,
                )
                NumberField("检测窗口 ms", json.optInt(K.TIMEOUT, K.DEFAULT_TIMEOUT_MS), K.MIN_TIMEOUT_MS..60_000) {
                    put(K.TIMEOUT, it)
                }
                NumberField("点击后继续查找次数", json.optInt(K.TRY_TIMES, K.DEFAULT_TRY_TIMES), 1..60) {
                    put(K.TRY_TIMES, it)
                }
            }

            Section("点击方式") {
                ChoiceField(
                    "点跳过按钮的方式",
                    clickWayOf(json),
                    listOf(
                        "自动" to K.CLICK_WAY_AUTO,
                        "控件点击" to K.CLICK_WAY_NODE,
                        "坐标点击" to K.CLICK_WAY_GESTURE,
                        "返回键" to K.CLICK_WAY_BACK,
                    ),
                ) { put(K.CLICK_WAY, it) }
            }

            Section("关键词匹配") {
                LinesField(
                    "按钮文字（每行一个）",
                    stringList(json, K.KEYWORDS),
                ) { put(K.KEYWORDS, JSONArray(it)) }
                // 「附加关键词」框删掉了：它只在「按钮文字」为空时才生效（旧版分界），
                // 摆在旁边只会让人以为两个都要填。引擎那边照旧读 keywords_append ——
                // 内置规则里有 59 条靠它带自定义词（这 59 条都没有 keywords，删了读取等于把这些包的规则废掉）。
                LinesField(
                    "不点的控件 id（每行一个）",
                    stringList(json, K.NODE_WHITELIST),
                ) { put(K.NODE_WHITELIST, JSONArray(it)) }
                LinesField(
                    "精确目标（每行 id:控件名 或 按钮文字）",
                    stringList(json, K.TEXTS_TASK2),
                ) { put(K.TEXTS_TASK2, JSONArray(it)) }
            }

            Section("弹窗规则") {
                SwitchField("自动关青少年模式弹窗", json.optBoolean("filter_young_popups", false)) {
                    put("filter_young_popups", it)
                }
                PopupRulesField(popupLines(json)) { put(K.POPUP_RULES, it) }
                NumberField("弹窗查找次数", json.optInt(K.SEARCH_TIMES_POPUP, K.POPUP_DEFAULT_SEARCH_TIMES), 1..20) {
                    put(K.SEARCH_TIMES_POPUP, it)
                }
                NumberField("弹窗查找间隔 ms", json.optInt(K.SEARCH_INTERVAL_POPUP, K.POPUP_DEFAULT_INTERVAL_MS), K.POPUP_MIN_INTERVAL_MS..2_000) {
                    put(K.SEARCH_INTERVAL_POPUP, it)
                }
                ChoiceField(
                    "弹窗按钮的点法",
                    clickWayOf(json, K.CLICK_WAY_POPUP),
                    listOf(
                        "自动" to K.CLICK_WAY_AUTO,
                        "控件点击" to K.CLICK_WAY_NODE,
                        "坐标点击" to K.CLICK_WAY_GESTURE,
                        "返回键" to K.CLICK_WAY_BACK,
                    ),
                ) { put(K.CLICK_WAY_POPUP, it) }
            }

            Section("按界面结构定位跳过控件") {
                LinesField(
                    "每行一条路径，形如 0&1,1&1,2&1",
                    doccLines(json),
                ) { put(K.DOCC, doccJson(it)) }
            }
        }
    }
}

/**
 * 包名超过屏幕宽度 70% 时才折中间——头尾都留着才认得出是哪个包，
 * 纯尾部截断会把最要紧的最后一段（`.plus`、`.app`）吃掉。
 */
@Composable
private fun middleEllipsized(text: String, style: TextStyle): String {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val limit = screenWidthPx * 0.7f
    return remember(text, style, limit) {
        if (measurer.measure(text, style, maxLines = 1).size.width <= limit) text
        else (text.length / 2 downTo 1)
            .map { keep -> text.take(keep) + "…" + text.takeLast(keep) }
            .firstOrNull { measurer.measure(it, style, maxLines = 1).size.width <= limit }
            ?: text
    }
}

/** 标题左边的应用图标；取不到（包被卸载、图标读不出）就返回 null，整块让位给文字。 */
@Composable
private fun appIcon(pkg: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(pkg) {
        runCatching {
            val d = context.packageManager.getApplicationIcon(pkg)
            val w = d.intrinsicWidth.takeIf { it > 0 } ?: 96
            val h = d.intrinsicHeight.takeIf { it > 0 } ?: 96
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, w, h)
            d.draw(canvas)
            bmp.asImageBitmap()
        }.getOrNull()
    }
}

private fun clickWayOf(json: JSONObject, key: String = K.CLICK_WAY): Int =
    when (val raw = json.opt(key)) {
        null, JSONObject.NULL -> K.CLICK_WAY_AUTO
        else -> raw.toString().toIntOrNull() ?: K.CLICK_WAY_AUTO
    }

private fun stringList(json: JSONObject, key: String): List<String> {
    val arr = json.optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
}

private fun popupLines(json: JSONObject): List<String> {
    val arr = json.optJSONArray(K.POPUP_RULES) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val rule = arr.optJSONObject(i) ?: return@mapNotNull null
        val ids = rule.optJSONArray("id")
        val idText = when {
            ids != null -> (0 until ids.length()).joinToString("&") { ids.optString(it) }
            else -> rule.optString("id")
        }
        "$idText|${rule.optString("action")}|${rule.optInt("times", 1)}"
    }.filter { it.contains("|") }
}

private fun doccLines(json: JSONObject): List<String> {
    val arr = json.optJSONArray(K.DOCC) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        arr.optJSONArray(i)?.let { inner ->
            (0 until inner.length()).joinToString(",") { inner.optString(it) }
        }
    }
}

private fun doccJson(lines: List<String>): JSONArray {
    val outer = JSONArray()
    lines.filter { it.isNotBlank() }.forEach { line ->
        val inner = JSONArray()
        line.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { inner.put(it) }
        outer.put(inner)
    }
    return outer
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    InkCard {
        Text(title, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun NumberField(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toIntOrNull()?.let { n -> if (n in range) onChange(n) }
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LinesField(label: String, lines: List<String>, onChange: (List<String>) -> Unit) {
    val text = remember(lines) { lines.joinToString("\n") }
    var value by remember(text) { mutableStateOf(text) }
    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            onChange(it.split("\n").map { s -> s.trim() }.filter { s -> s.isNotEmpty() })
        },
        label = { Text(label) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchField(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceField(label: String, selected: Int, options: List<Pair<String, Int>>, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        // 四个选项两列摆成 2×2，纵向只占两行（这页本来就长，纵向能省一行是一行）。
        // 奇数个时补一个空位，保证两列始终等宽。
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (name, value) ->
                    Box(Modifier.weight(1f)) {
                        InkOption(selected = value == selected, onClick = { onChange(value) }) {
                            androidx.compose.material3.RadioButton(selected = value == selected, onClick = { onChange(value) })
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 弹窗规则一行一条：`命中控件|点哪个|点几次`，动作可写按钮文字、GLOBAL_ACTION_BACK 或 x:100y:800。 */
@Composable
private fun PopupRulesField(lines: List<String>, onChange: (JSONArray) -> Unit) {
    LinesField(
        "每行：命中控件|点哪个|点几次",
        lines,
    ) { list ->
        onChange(popupJson(list))
    }
}

private fun popupJson(lines: List<String>): JSONArray {
    val arr = JSONArray()
    lines.forEach { line ->
        val parts = line.split("|")
        val rule = JSONObject()
        rule.put("id", JSONArray(parts[0].split("&").filter { it.isNotEmpty() }))
        rule.put("action", parts.getOrElse(1) { "" })
        rule.put("times", parts.getOrElse(2) { "1" }.trim().toIntOrNull() ?: 1)
        arr.put(rule)
    }
    return arr
}
