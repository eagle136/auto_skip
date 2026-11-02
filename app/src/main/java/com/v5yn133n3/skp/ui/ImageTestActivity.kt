package com.v5yn133n3.skp.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.v5yn133n3.skp.data.RuleStore
import com.v5yn133n3.skp.engine.BandScan
import com.v5yn133n3.skp.engine.CornerScan
import com.v5yn133n3.skp.engine.GlyphScan
import com.v5yn133n3.skp.engine.SkipNet
import com.v5yn133n3.skp.priv.Capture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.v5yn133n3.skp.Logs

/**
 * 全屏图片测试：拿一张和真机同尺寸的整屏截图，跑**和端上完全同一套**扫带链路
 * （压半分辨率 → 两条横带切 160×100 瓦片 → 瓦片内提案 → 小模型确认），
 * 把每个候选框按分数画回图上。
 *
 * 为什么要有这个：真机上只能看到「跳了 / 没跳」，中间是黑箱。这里能直接看到
 * 「提案器在哪块瓦片提了什么框」「模型给了多少分」「是不是被状态栏保护区丢了」。
 * 扫描函数与 [com.v5yn133n3.skp.engine.CornerTask] 逐行同源，不存在两套口径。
 */
@OptIn(ExperimentalMaterial3Api::class)
class ImageTestActivity : ComponentActivity() {

    /** 当前要扫的图。onNewIntent 也会改它，所以必须是 Compose 状态而不是普通变量。 */
    private val pendingUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuleStore.init(applicationContext)
        pendingUri.value = intent?.data
        setContent {
            SkpTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("全屏图片测试") },
                            actions = {
                                TextButton(onClick = { finish() }) { Text("返回") }
                            },
                        )
                    },
                ) { insets ->
                    // 支持 adb 直达：am start -n .../.ui.ImageTestActivity -d file:///sdcard/x.jpg
                    TestScreen(initialUri = pendingUri.value, modifier = Modifier.padding(insets))
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingUri.value = intent.data
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestScreen(initialUri: Uri? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var annotated by remember { mutableStateOf<Bitmap?>(null) }
    var hits by remember { mutableStateOf<List<ImageScan.Hit>>(emptyList()) }
    var status by remember { mutableStateOf("选一张整屏截图（要和真机同尺寸）") }
    var verdict by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var shots by remember { mutableStateOf<List<Shot>>(emptyList()) }

    fun loadShots() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { recentShots(context) }
            shots = list
            status = if (list.isEmpty()) {
                "读不到最近截图：给一次相册权限，或确认截图还在。也可以用下面的「选择图片」"
            } else {
                "挑一张截图（${list.size} 张）：点条目直接扫"
            }
        }
    }

    val perm = Manifest.permission.READ_MEDIA_IMAGES
    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // 不管授权与否都试一次：Android 14 的「仅选中的照片」模式下 READ_MEDIA_IMAGES
        // 报 denied，但选中的那几张仍然能查到。
        loadShots()
    }

    fun startScan(uri: Uri) {
        if (busy) return
        busy = true
        status = "解码…"
        verdict = ""
        scope.launch {
            var bmp: Bitmap? = null
            try {
                // 解码这一路以前只要抛异常（ColorOS 的 Photo Picker 就会抛 ENOENT），
                // 状态行停在「解码…」、busy 永远是 true，「选择图片」按钮从此点不动。
                // 现在无论成功失败都会走到 finally。
                val d = withContext(Dispatchers.IO) { decode(context, uri) }
                bmp = d.bitmap
                if (bmp == null) {
                    status = "读不出这张图。${d.error}"
                    return@launch
                }
                status = "解码 ${bmp.width}×${bmp.height}，扫描中…"
                val t0 = SystemClock.uptimeMillis()
                val result = withContext(Dispatchers.Default) { ImageScan.run(context, bmp) }
                hits = result
                annotated = withContext(Dispatchers.Default) { annotate(bmp, result) }
                val cost = SystemClock.uptimeMillis() - t0
                val over = result.count { it.p >= SkipNet.THRESHOLD }
                status = "完成：${result.size} 个候选，$over 个过阈值，耗时 ${cost}ms"
                verdict = verdictOf(result)
            } catch (e: Throwable) {
                Logs.w("SkipImageScan", "scan failed: ${e.message}", e)
                status = "扫描失败：${e.javaClass.simpleName} ${e.message ?: ""}"
            } finally {
                if (bmp == null && status == "解码…") status = "读不出这张图（未知原因）"
                busy = false
            }
        }
    }

    LaunchedEffect(initialUri) { if (initialUri != null) startScan(initialUri) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) startScan(uri) else status = "已取消选图"
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(status, style = MaterialTheme.typography.bodySmall, color = ComposeColor(0xFFB9B9B9))
        if (verdict.isNotEmpty()) {
            Text(
                verdict,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (verdict.startsWith("找到")) ComposeColor(0xFF7CD17C) else ComposeColor(0xFFE0B060),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { picker.launch("image/*") }) {
                Text(if (busy) "扫描中…" else "选择图片")
            }
            /*
             * 系统 Photo Picker 在 ColorOS 上不可靠（2026-10-31 真机实测）：
             * 预览页全黑、logcat 刷 `open sharedFd failed with Permission denied`、
             * 点「完成」没反应；偶尔返回一个打不开的 content:// （app 侧 openInputStream
             * 直接 ENOENT）。所以给一条不经过它的路：自己查 MediaStore 列最近截图。
             */
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, perm) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        loadShots()
                    } else {
                        askPerm.launch(perm)
                    }
                },
            ) { Text("最近截图") }
            OutlinedButton(
                enabled = !busy && annotated != null,
                onClick = {
                    annotated = null
                    hits = emptyList()
                    verdict = ""
                    status = "已清空，重新选一张"
                },
            ) { Text("清空") }
        }
        Text(
            "红=会点；青=模型不认；灰=被护栏/状态栏丢弃。列表里的「会点」与真机同一套判据。",
            style = MaterialTheme.typography.bodySmall,
            color = ComposeColor(0xFF8A8A8A),
        )

        Box(
            Modifier
                .fillMaxWidth()
                .weight(0.62f)
                .background(ComposeColor.Black),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = annotated
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    if (busy) "扫描中…" else "还没有图",
                    style = MaterialTheme.typography.bodySmall,
                    color = ComposeColor(0xFF8A8A8A),
                )
            }
        }

        if (shots.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(0.38f)
                    .verticalScroll(rememberScrollState()),
            ) {
                shots.forEach { s ->
                    Text(
                        "${s.name}  ${s.w}×${s.h}  ${s.whenText}",
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { startScan(s.uri) }
                            .padding(vertical = 7.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = ComposeColor(0xFF9CC8FF),
                    )
                }
            }
        } else if (hits.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(0.38f)
                    .verticalScroll(rememberScrollState()),
            ) {
                hits.forEachIndexed { i, h ->
                    Text(
                        "#${i + 1} ${h.tile} ${h.w}×${h.h}@${h.x},${h.y} " +
                            "p=${String.format("%.3f", h.p)} ${h.tag()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            h.guard -> ComposeColor(0xFF9E9E9E)
                            h.tap -> ComposeColor(0xFFFF6B8A)
                            h.p >= SkipNet.THRESHOLD -> ComposeColor(0xFFE0B060)
                            else -> ComposeColor(0xFF8A8A8A)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 一句话结论：这张图真机上到底会不会点。判据与 CornerTask 完全同源
 * （同一个 [CornerScan.gate]、同一个 [SkipNet.THRESHOLD]），不是另写一套。
 */
private fun verdictOf(hits: List<ImageScan.Hit>): String {
    val best = hits.firstOrNull { it.tap }
    if (best != null) {
        return "找到跳过：${best.w}×${best.h}@${best.x},${best.y} " +
            "p=${String.format("%.2f", best.p)}（真机会点）"
    }
    val near = hits.firstOrNull { it.p >= SkipNet.THRESHOLD }
    if (near != null) {
        return "提案到了但点不下去：${near.w}×${near.h} p=${String.format("%.2f", near.p)}，" +
            "被护栏拦下（${near.gate.zone} 需 ${near.gate.minW}×${near.gate.minH}）"
    }
    if (hits.isEmpty()) return "没找到候选框：这一帧提案器没给出任何框"
    return "没找到跳过：最高 p=${String.format("%.2f", hits.first().p)}，模型不认"
}

/**
 * 图片上的扫带。与 CornerTask 同源：同样的压半分辨率、同样的瓦片、同样只取每块第一框、
 * 同样走 scoreRobust 四尺度复测。唯一的差别是这里不点，只把结果返回。
 */
object ImageScan {

    class Hit(
        val tile: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val p: Float,
        val guard: Boolean,
        /** 这个框落在哪一档护栏里，以及那一档的两个下界。 */
        val gate: CornerScan.Gate,
        /** 真机上会不会点：过阈值 + 不在状态栏 + 过小框护栏，三条全满足。 */
        val tap: Boolean,
    ) {
        fun tag(): String {
            // glyph 的合格线是 0.62（两字都 ≥0.62、均分 ≥0.68），不是 CNN 的 0.98
            val thr = if (tile.startsWith("glyph")) 0.62f else SkipNet.THRESHOLD
            return when {
                guard -> "状态栏丢弃"
                p < thr -> if (p >= 0.5f) "接近" else "低分"
                tap -> "会点"
                else -> "小框丢(${gate.zone} 需${gate.minW}×${gate.minH})"
            }
        }
    }

    /** 四角区宽 40%、高 25%，与 GlyphScan 的 CELL_W/H_RATIO 同一口径。 */
    private const val CORNER_W = 0.40f
    private const val CORNER_H = 0.25f

    /** 瓦片中心是否落在四角之一（中间那一整条不扫）。 */
    private fun inCorner(t: BandScan.Tile, w: Int, h: Int): Boolean {
        val cx = t.x + BandScan.TW / 2
        val cy = t.y + BandScan.TH / 2
        val cw = (w * CORNER_W).toInt()
        val ch = (h * CORNER_H).toInt()
        val side = cx < cw || cx > w - cw
        if (!side) return false
        return cy < ch || cy > h - ch
    }

    fun run(ctx: Context, bmp: Bitmap): List<Hit> {
        val frame0 = toFrame(bmp)
        // 字形匹配：图片测试也走真机同一套 GlyphScan，不另写一份判据
        if (RuleStore.cornerMode == RuleStore.CORNER_MODE_GLYPH) {
            val gh = GlyphScan.scan(ctx, frame0) ?: return emptyList()
            val gw = gh.x1 - gh.x0
            val ghh = gh.y1 - gh.y0
            val g0 = CornerScan.gate(frame0.width, frame0.height, gh.cx, gh.cy)
            val guard0 = statusBarHeight(ctx) > 0 && gh.y1 <= statusBarHeight(ctx)
            return listOf(
                Hit(
                    "glyph c${gh.cell} p${gh.pass}", gh.x0, gh.y0, gw, ghh,
                    gh.combo, guard0, g0, !guard0 && g0.pass(gw, ghh),
                ),
            )
        }
        if (!SkipNet.prepare(ctx)) {
            Logs.w("SkipImageScan", "model unavailable: ${SkipNet.lastError}")
            return emptyList()
        }
        val frame = frame0
        val halfW = frame.width / BandScan.SCALE
        val halfH = frame.height / BandScan.SCALE
        val half = IntArray(halfW * halfH)
        Capture.halveLumaInto(frame, half)
        val tileLuma = IntArray(BandScan.TW * BandScan.TH)
        val scratch = CornerScan.Scratch(BandScan.TW * BandScan.TH)
        val guard = statusBarHeight(ctx)
        val out = ArrayList<Hit>()

        for (t in BandScan.tiles(halfW, halfH)) {
            // 图片测试也只扫四角，跟 glyph 路径、跟真机口径统一。
            // 之前这里是全带（0–40% + 36–100%，基本覆盖整屏），一张淘宝图文页
            // 要跑 20+ 块瓦片，十几秒都下不来；而「跳过」只可能出现在四角。
            if (!inCorner(t, halfW, halfH)) continue
            BandScan.copyTile(half, halfW, t, BandScan.TW, BandScan.TH, tileLuma)
            val boxes = ArrayList<CornerScan.Box>(2)
            CornerScan.candidates(
                tileLuma, BandScan.TW, BandScan.TH, CornerScan.TILE, scratch,
                allowSquare = BandScan.allowsSquare(t, halfW),
            ).firstOrNull()?.let { boxes += it }
            // 与 CornerTask 同源：热区再补一趟 top-hat（白字被高亮光效黏死的那类广告）
            if (BandScan.isHot(t, halfW, halfH)) {
                CornerScan.topHatCandidates(tileLuma, BandScan.TW, BandScan.TH, scratch)
                    .firstOrNull()?.let { b ->
                        val dup = boxes.any { o ->
                            b.cx >= o.x && b.cx <= o.x + o.w &&
                                b.cy >= o.y && b.cy <= o.y + o.h
                        }
                        if (!dup) boxes += b
                    }
            }
            if (boxes.isEmpty()) continue
            for (box in boxes) {
            val bx = (t.x + box.x) * BandScan.SCALE
            val by = (t.y + box.y) * BandScan.SCALE
            val bw = box.w * BandScan.SCALE
            val bh = box.h * BandScan.SCALE
            val inGuard = guard > 0 && by + bh <= guard
            // 状态栏里的框真机上不打分（直接丢），这里也不打分，但记下来让人看见。
            // 近方形框要带 rescue 标志，和 CornerTask 的口径保持一致，
            // 否则「图片测试里 p=0.00、真机却能点」会让人以为是两条不同的链路。
            val nearSquare = bw * 100 >= bh * 70 && bw * 100 <= bh * 145
            val p = if (inGuard) 0f else SkipNet.scoreRobust(frame, bx, by, bw, bh, nearSquare)
            // 小框护栏与真机同一个函数（CornerScan.gate），不是这边另写一份判据。
            val g = CornerScan.gate(frame.width, frame.height, bx + bw / 2, by + bh / 2)
            val tap = !inGuard && p >= SkipNet.THRESHOLD && g.pass(bw, bh)
            out += Hit(t.toString(), bx, by, bw, bh, p, inGuard, g, tap)
            }
        }
        return out.sortedByDescending { it.p }
    }

    private fun statusBarHeight(ctx: Context): Int {
        val res = ctx.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else (48 * res.displayMetrics.density).toInt()
    }

    /** ARGB_8888 的 Bitmap → BGRA 裸帧，与 screencap 的输出布局一致。 */
    private fun toFrame(bmp: Bitmap): Capture.Frame {
        val src = if (bmp.config == Bitmap.Config.ARGB_8888) bmp
        else bmp.copy(Bitmap.Config.ARGB_8888, false)
        val w = src.width
        val h = src.height
        // 不用 copyPixelsToBuffer：它按 rowBytes 写，行对齐时 buffer 会越界；
        // 逐像素搬一遍反而更稳，反正这一步在 IO 线程里只跑一次。
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val bytes = ByteArray(w * h * 4)
        var i = 0
        for (argb in px) {
            bytes[i++] = (argb and 0xFF).toByte()             // B
            bytes[i++] = ((argb shr 8) and 0xFF).toByte()     // G
            bytes[i++] = ((argb shr 16) and 0xFF).toByte()    // R
            bytes[i++] = ((argb ushr 24) and 0xFF).toByte()   // A
        }
        return Capture.Frame(w, h, bytes)
    }
}

/**
 * 一张图读不出来，原因要能说清 —— 只显示「解码失败」的话，谁也不知道是权限、
 * 文件没了、还是格式不支持。
 */
private class DecodeResult(val bitmap: Bitmap?, val error: String = "")

private inline fun tryOnce(errs: MutableList<String>, tag: String, body: () -> Bitmap?): Bitmap? {
    return try {
        val b = body()
        if (b == null) errs += "$tag=空"
        b
    } catch (e: Throwable) {
        Logs.w("SkipImageScan", "decode $tag failed: ${e.javaClass.simpleName} ${e.message}")
        errs += "$tag=${e.javaClass.simpleName}"
        null
    }
}

/**
 * 最近截图。为什么自己查 MediaStore 而不只用系统选择器：
 *
 * 2026-10-31 真机（realme GT8 Pro / Android 17）实测，ColorOS 把
 * `ACTION_GET_CONTENT` 路由到自带的 Photo Picker，而它的预览页是**全黑**的，
 * logcat 一直刷 `open sharedFd failed with Permission denied`，点「完成」没有反应。
 * 更早之前还出现过它返回的 content:// 在 app 侧 `openInputStream` 直接
 * `open failed: ENOENT` —— 用户看到的就是「选了图，什么都没发生」。
 *
 * 这个页面本来就是拿**刚才那张广告截图**来复盘的，所以直接列 Screenshots 目录最近的
 * 若干张，点一下就开始扫，完全不经过系统选择器。
 */
private class Shot(val uri: Uri, val name: String, val w: Int, val h: Int, val added: Long) {
    val whenText: String
        get() = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(added * 1000))
}

private fun recentShots(context: Context, limit: Int = 12): List<Shot> {
    val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.DATE_ADDED,
    )
    // 只要截图：路径里有 Screenshots，或文件名以 Screenshot 开头（ColorOS 是后者）。
    val sel = "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
        "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)"
    val args = arrayOf("%Screenshot%", "Screenshot%")
    val out = ArrayList<Shot>(limit)
    try {
        context.contentResolver.query(
            col, proj, sel, args, "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iW = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val iT = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext() && out.size < limit) {
                out += Shot(
                    ContentUris.withAppendedId(col, c.getLong(iId)),
                    c.getString(iName) ?: "(无名)",
                    c.getInt(iW),
                    c.getInt(iH),
                    c.getLong(iT),
                )
            }
        }
    } catch (e: Throwable) {
        Logs.w("SkipImageScan", "query shots failed: ${e.javaClass.simpleName} ${e.message}")
    }
    return out
}

/**
 * 四路依次读：fd → stream → 裸路径 → MediaStore 的 _data。
 *
 * 为什么不是一条路：系统 Photo Picker（ColorOS 把 ACTION_GET_CONTENT 也路由过去）返回的
 * `content://media/picker/...` 在真机上 `openInputStream` 会抛
 * `open failed: ENOENT`，而 `openFileDescriptor` 能过。反过来某些老 provider 只实现
 * 了 openInputStream、没有 openFileDescriptor。四条路都不行才算这张图真的读不出来。
 */
private fun decode(context: Context, uri: Uri): DecodeResult {
    val errs = ArrayList<String>(4)
    val opts = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
    }

    var raw = tryOnce(errs, "fd") {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
        }
    }
    if (raw == null) raw = tryOnce(errs, "stream") {
        val input = if (uri.scheme == "file") {
            java.io.FileInputStream(uri.path ?: throw java.io.FileNotFoundException("no path"))
        } else {
            context.contentResolver.openInputStream(uri)
        }
        input?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
    if (raw == null) raw = tryOnce(errs, "path") {
        if (uri.scheme == "file") BitmapFactory.decodeFile(uri.path, opts) else null
    }
    if (raw == null) raw = tryOnce(errs, "data") {
        val path = context.contentResolver.query(
            uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        path?.let { BitmapFactory.decodeFile(it, opts) }
    }
    raw ?: return DecodeResult(null, "原因：${errs.joinToString(" / ")}")

    // 4K 截图不降采样，扫带要跑十几秒；降到 2 倍屏宽以内，坐标仍按原图算。
    val limit = context.resources.displayMetrics.widthPixels * 2
    val sized = if (raw.width > limit) {
        val h = (raw.height.toLong() * limit / raw.width).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(raw, limit, h, true).also { if (it !== raw) raw.recycle() }
    } else raw

    return DecodeResult(applyExif(context, uri, sized))
}

/** 相册原图带方向标记，不摆正就是横躺的，扫带只会扫到腰。取不到就原样返回。 */
private fun applyExif(context: Context, uri: Uri, src: Bitmap): Bitmap {
    val deg = try {
        val input = if (uri.scheme == "file") {
            java.io.FileInputStream(uri.path ?: return src)
        } else {
            context.contentResolver.openInputStream(uri) ?: return src
        }
        input.use {
            when (android.media.ExifInterface(it).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
    } catch (e: Throwable) {
        Logs.d("SkipImageScan", "exif skip: ${e.message}")
        0
    }
    if (deg == 0) return src
    val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        .also { if (it !== src) src.recycle() }
}

/** 把候选框画回图上。红=命中，琥珀=接近，青=低分，灰=状态栏丢弃。 */
private fun annotate(src: Bitmap, hits: List<ImageScan.Hit>): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val c = Canvas(out)
    val stroke = (out.width / 240f).coerceAtLeast(3f)
    val labelSize = (out.width / 42f).coerceAtLeast(18f)
    val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = labelSize
        color = Color.BLACK
    }
    val r = RectF()
    for (h in hits) {
        // 红=真机会点；琥珀=模型过了但护栏丢；青=模型不认；灰=状态栏保护区内。
        // 以前红色只表示「过阈值」，于是「框是红的、真机却不点」看起来像 bug ——
        // 现在红色就是「会点」，两边判据一致。
        val color = when {
            h.guard -> 0xFF9E9E9E.toInt()
            h.tap -> 0xFFFF2D55.toInt()
            h.p >= SkipNet.THRESHOLD -> 0xFFFFB300.toInt()
            else -> 0xFF00C8FF.toInt()
        }
        box.color = color
        r.set(h.x.toFloat(), h.y.toFloat(), (h.x + h.w).toFloat(), (h.y + h.h).toFloat())
        c.drawRect(r, box)
        val label = "${h.tile} ${h.w}×${h.h} p=${String.format("%.2f", h.p)}"
        val tw = txt.measureText(label)
        val top = (r.top - labelSize * 1.35f).coerceAtLeast(0f)
        chip.color = color
        chip.alpha = 235
        c.drawRoundRect(
            r.left, top, r.left + tw + labelSize * 0.5f, top + labelSize * 1.3f,
            labelSize * 0.2f, labelSize * 0.2f, chip,
        )
        c.drawText(label, r.left + labelSize * 0.25f, top + labelSize, txt)
        chip.alpha = 255
    }
    return out
}
