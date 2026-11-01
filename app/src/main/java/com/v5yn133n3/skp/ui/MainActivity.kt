package com.v5yn133n3.skp.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.v5yn133n3.skp.K
import com.v5yn133n3.skp.R
import com.v5yn133n3.skp.data.RuleStore
import com.v5yn133n3.skp.engine.DebugOverlay
import com.v5yn133n3.skp.keep.KeepAlive
import com.v5yn133n3.skp.priv.Mode
import com.v5yn133n3.skp.priv.SelfHeal.Step
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuleStore.init(applicationContext)
        setContent {
            SkpTheme {
                var appsRequested by remember { mutableStateOf(false) }
                var rulePkg by remember { mutableStateOf<String?>(null) }
                val pkg = rulePkg
                // 系统返回键和页面上的「返回」走同一条路：规则页→列表→主页；
                // 到主页这一级就不拦了，交还给系统该退就退。
                BackHandler(enabled = pkg != null) { rulePkg = null }
                BackHandler(enabled = pkg == null && appsRequested) { appsRequested = false }
                when {
                    pkg != null -> RuleScreen(pkg = pkg, onBack = { rulePkg = null })
                    appsRequested -> AppsScreen(onBack = { appsRequested = false }, onOpenRule = { rulePkg = it })
                    else -> HomeScreen(onOpenApps = { appsRequested = true })
                }
            }
        }
    }
}

/**
 * 黑底白字这套：窗口背景在 themes.xml 里已经是纯黑，这里把 Material 侧的语义色对齐，
 * 保证文字、开关、输入框在纯黑底上都是最高对比；卡片/列表框统一圆角。
 */
private val InkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Color(0xFFE8E8E8),
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF0B0B0B),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFE6E6E6),
    outline = Color(0xFFAAAAAA),
    outlineVariant = Color(0xFF5C5C5C),
    error = Color(0xFFFF6B6B),
    onError = Color.Black,
)

private val InkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** 纯黑底上唯一能看见的边界：浅灰描边；未选中的选项框用暗一档的同一支色。 */
private val BoxGray = Color(0xFFB9B9B9)
private val BoxGrayDim = Color(0xFF5C5C5C)

/** 卡壳：圆角 + 浅灰描边，比 Card 默认的黑底更接近「有边框的框」。 */
@Composable
fun InkCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.5.dp, BoxGray, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** 单个选项：整行可点，浅灰圆角框把边界画出来，选中项描边加亮。 */
@Composable
fun InkOption(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (selected) 1f else 0.35f))
            .border(1.5.dp, if (selected) BoxGray else BoxGrayDim, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun SkpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = InkColors, shapes = InkShapes, content = content)
}

/**
 * 子页面左上角的返回箭头（生效应用列表 / 单应用规则页共用）。
 *
 * 原来就是 TextButton 里一个默认字号的「←」，实机上又细又小、点起来也别扭；
 * 放大到 28sp 并保留 TextButton 自带的 48dp 触摸区。
 */
@Composable
fun BackArrow(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(start = 4.dp)) {
        Text("←", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 应用列表行尾的判定开关：开 = 节点路与视觉路同时跑，关 = 两条都不跑。
 *
 * 固定宽度的容器只为了让表头「启用」和每一行的开关对齐；
 * 点击由 Switch 自己消费，不会连带触发整行的「打开规则页」。
 */
@Composable
fun AppSwitch(on: Boolean, onToggle: (Boolean) -> Unit) {
    Box(Modifier.width(APP_SWITCH_W), contentAlignment = Alignment.Center) {
        Switch(checked = on, onCheckedChange = onToggle)
    }
}

/** 开关列宽。表头那一列也按 [APP_SWITCH_W] 对齐。 */
private val APP_SWITCH_W = 72.dp

/** 生效应用列表入口固定在标题栏右上角，单击直达。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenApps: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val steps = remember { mutableStateListOf<Step>() }
    var mode by remember { mutableStateOf(KeepAlive.currentMode()) }
    var busy by remember { mutableStateOf(false) }
    var debugOn by remember { mutableStateOf(RuleStore.debugOverlay) }
    // 授权页回来不会自动重组，轮询一次最省事（1s 一次，只在主页活着时跑）
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            overlayOk = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
            kotlinx.coroutines.delay(1000)
        }
    }
    /**
     * 读一遍状态。`repair=true` 时先静默补齐一次（原「补齐保活」按钮的活，现在不用人按）：
     * 进页面那一次、以及上一次还有 ✗ 的时候才补，全绿就不去重跑守护。
     * 补齐那十几行中间步骤不再往界面上堆 —— 跑完直接按正常口径刷一遍状态，
     * 所以启动后看到的和点一次「刷新状态」是同一份列表。
     */
    fun refresh(repair: Boolean = false) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (repair && mode != Mode.NONE) {
                    // apply 内部已含故障态的「关一次再开」，不用再走一遍 recoverIfCrashed
                    KeepAlive.apply(context, mode)
                    KeepAlive.status(context)
                } else {
                    // 系统判成故障时自己关一次再开（闸门 10 分钟），再把状态读一遍——
                    // A17 上这个状态不会自己恢复，不修就一直显示故障
                    KeepAlive.recoverIfCrashed(context)?.let { listOf(it) }.orEmpty() +
                        KeepAlive.status(context)
                }
            }
            steps.clear()
            steps.addAll(result)
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh(repair = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onOpenApps, modifier = Modifier.padding(end = 12.dp)) { Text("生效应用") }
                },
            )
        }
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InkCard {
                Text("运行方式", fontWeight = FontWeight.Bold)
                fun select(candidate: Mode) {
                    mode = candidate
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { KeepAlive.switchMode(context, candidate) }
                        steps.clear()
                        steps.addAll(result)
                        busy = false
                    }
                }
                Mode.entries.forEach { candidate ->
                    InkOption(selected = candidate == mode, onClick = { select(candidate) }) {
                        RadioButton(selected = candidate == mode, onClick = { select(candidate) })
                        Column(Modifier.weight(1f)) {
                            Text(KeepAlive.label(candidate))
                            Text(
                                when (candidate) {
                                    Mode.NONE -> "被系统杀掉就没有后续"
                                    Mode.SHIZUKU -> "守护静默拉活 + 进页面自动补齐"
                                    Mode.ROOT -> "守护同上 + 引擎优先级再压一档"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                // Root 授权入口只在通道没打通时出现，打通了就收起
                val rootBlocked = mode == Mode.ROOT &&
                    steps.any { !it.ok && (it.name == "Root 通道" || it.name == "提权通道") }
                // 原「补齐保活」按钮删掉了：进页面那一次已经静默补过，
                // 这里只有上次还留着 ✗ 时才把「刷新」当成再一次静默补齐，全绿就只是读状态。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { refresh(repair = steps.any { !it.ok }) },
                        modifier = Modifier.weight(1f),
                    ) { Text("刷新状态") }
                    if (busy) {
                        CircularProgressIndicator(Modifier.padding(start = 10.dp).size(20.dp))
                    }
                }
                if (rootBlocked) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { KeepAlive.requestRoot(context) }
                                // 插在最前面而不是清掉：下面那串 ✗ 要留着，
                                // 「刷新状态」才知道放行之后还要再静默补齐一次
                                steps.addAll(0, result)
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("去 Root 授权") }
                }
            }

            InkCard {
                Text("OCR识别", fontWeight = FontWeight.Bold)
                // 全局开关已删：扫带跑不跑只看「生效应用列表」里每包的识别方式开关。
                // 识别时长滑条：4–10s，默认 6s。到点就停手，下一次**真正的应用切换**才重新计时。
                var timeoutSec by remember { mutableStateOf(RuleStore.cornerTimeoutSec) }
                Text("调整OCR识别时间", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = timeoutSec.toFloat(),
                        onValueChange = {
                            timeoutSec = it.toInt()
                                .coerceIn(K.CORNER_TIMEOUT_MIN_S, K.CORNER_TIMEOUT_MAX_S)
                            RuleStore.cornerTimeoutSec = timeoutSec
                        },
                        valueRange = K.CORNER_TIMEOUT_MIN_S.toFloat()..K.CORNER_TIMEOUT_MAX_S.toFloat(),
                        steps = K.CORNER_TIMEOUT_MAX_S - K.CORNER_TIMEOUT_MIN_S - 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${timeoutSec}s", fontWeight = FontWeight.Bold)
                }
                // 触发延迟：检测到切换后先等一段时间再开扫，避开上一屏残留/占位图
                var delayMs by remember { mutableStateOf(RuleStore.cornerStartDelayMs) }
                Text("OCR识别延迟", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = delayMs / 1000f,
                        onValueChange = {
                            // 滑条按 0.1s 取整，存的是毫秒
                            val ms = ((it * 10f).roundToInt() * 100)
                                .coerceIn(K.CORNER_START_DELAY_MIN_MS, K.CORNER_START_DELAY_MAX_MS)
                            delayMs = ms
                            RuleStore.cornerStartDelayMs = ms
                        },
                        // 0 = 不等，扫到即点；往右是「广告出得慢」的个别包手动加
                        valueRange = 0f..2.0f,
                        steps = 19,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (delayMs == 0) "关" else String.format("%.1fs", delayMs / 1000f),
                        fontWeight = FontWeight.Bold,
                    )
                }

                // 调试观测口：一个看「扫的时候屏幕上被框了什么」，一个拿静态图复现同一套扫描。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("调试悬浮窗", Modifier.weight(1f))
                    Switch(
                        checked = debugOn,
                        onCheckedChange = { on ->
                            // 第一次开如果没授权，直接送到系统的「显示在其他应用上层」页面
                            if (on && !overlayOk) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                            }
                            debugOn = on
                            RuleStore.debugOverlay = on
                            if (!on) DebugOverlay.hide()
                        },
                    )
                }
                Text(
                    if (overlayOk) "悬浮窗权限：已授予"
                    else "悬浮窗权限：未授予（打开开关会跳到系统授权页）",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overlayOk) BoxGray else MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = { context.startActivity(Intent(context, ImageTestActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("全屏图片测试（选一张整屏截图）") }
            }

            InkCard {
                Text("当前状态", fontWeight = FontWeight.Bold)
                if (steps.isEmpty()) {
                    Text("还没有检查结果", style = MaterialTheme.typography.bodySmall)
                }
                steps.forEach { step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (step.ok) "✓" else "✗", color = if (step.ok) BoxGray else MaterialTheme.colorScheme.error)
                        Text(
                            " ${step.name}：${step.detail}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(onBack: () -> Unit, onOpenRule: (String) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps = remember { mutableStateListOf<AppEntry>() }

    LaunchedEffect(query) {
        val loaded = withContext(Dispatchers.IO) { loadLaunchableApps(context, query) }
        apps.clear()
        apps.addAll(loaded)
    }

    // 行尾只有一颗判定开关，表头也只剩一列，宽度与开关一致才能对齐。
    var tick by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("生效应用列表") },
                navigationIcon = { BackArrow(onBack) },
            )
        }
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索应用名或包名") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // 表头：名称 / 启用。左边距与行内一致（12 列表内边距 + 10 行内边距），
            // 再加 46dp 让「名称」正好落在应用名上方（图标 36 + 间距 10）。
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(46.dp))
                Text("名称", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = BoxGray)
                Text(
                    "启用", Modifier.width(APP_SWITCH_W),
                    style = MaterialTheme.typography.bodySmall, color = BoxGray,
                    textAlign = TextAlign.Center,
                )
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(apps, key = { it.pkg }) { entry ->
                    // 当前判定开关。依赖 tick：点任意一行后所有行一起刷新。
                    val on = remember(entry.pkg, tick) { RuleStore.isAppOn(entry.pkg) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.5.dp, BoxGray, RoundedCornerShape(16.dp))
                            .clickable { onOpenRule(entry.pkg) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        // 应用图标：读不到就留空位，保证两列开关不左右跳
                        if (entry.icon != null) {
                            Image(
                                bitmap = entry.icon,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                        } else {
                            Spacer(Modifier.size(36.dp))
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = 10.dp, end = 4.dp),
                        ) {
                            Text(
                                entry.label,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                entry.pkg,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // 开关只决定这个应用跑不跑判定；打开后两条识别链路同时工作。
                        AppSwitch(on) {
                            RuleStore.setAppOn(entry.pkg, it)
                            tick++
                        }
                    }
                }
            }
        }
    }
}

data class AppEntry(
    val pkg: String,
    val label: String,
    /** 预装/系统应用。列表排序用：用户自己装的排前面。 */
    val system: Boolean,
    val icon: ImageBitmap?,
)

private fun loadLaunchableApps(context: android.content.Context, query: String): List<AppEntry> {
    val pm = context.packageManager
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    val q = query.trim().lowercase()
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val ai: ApplicationInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                ?: return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)
            // 图标在这里就转成 ImageBitmap（~96px），避免每行在组合期反复读 PackageManager
            val icon = runCatching {
                pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
            // 判系统应用只用 PackageManager 的 flag，不碰 root / shell：
            // FLAG_SYSTEM 就是「预装在系统分区」，用户后来装的都是 false。
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            AppEntry(pkg, label, system, icon)
        }
        .distinctBy { it.pkg }
        .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
        // 用户装的在前、系统的在后；同组按名称排
        .sortedWith(compareBy({ it.system }, { it.label }))
}
