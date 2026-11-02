package com.v5yn133n3.skp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.v5yn133n3.skp.data.RuleStore
import com.v5yn133n3.skp.priv.FgWatch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把「应用内缓冲 + logcat」打成一份 txt 放到 /sdcard，方便用户直接发出来。
 *
 * 落盘顺序（前一个成功就不往下走）：
 *
 * 1. MediaStore 的 `Download/skp/` —— Android 10+ 的正路，不需要任何权限，
 *    路径就是 `/sdcard/Download/skp/skp-<时间>.log`，微信/QQ 选文件时能直接挑到；
 * 2. 直接 `File("/sdcard/Download/skp")` —— 少数 ROM 对 legacy 路径放行；
 * 3. 应用自己的外部目录 `/sdcard/Android/data/<pkg>/files/` —— 只做兜底，
 *    文件管理器能进去，但不一定能被别的应用读到。
 *
 * 内容里带版本/机型/当前配置：只发一份日志就能判断「是不是阈值调太高」这种问题，
 * 不用再追问一轮设备信息。
 */
object LogExport {

    data class Result(
        val ok: Boolean,
        val path: String,
        val uri: Uri?,
        val bytes: Long,
        val error: String? = null,
    )

    fun export(ctx: Context): Result {
        val body = build(ctx)
        val bytes = body.toByteArray(Charsets.UTF_8)
        // 扩展名用 .txt：媒体库会按 MIME 给不认识的扩展名再补一个，`.log` 会变成 `.log.txt`
        val name = "skp-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"

        val uri = runCatching { toDownloads(ctx, name, bytes) }.getOrNull()
        if (uri != null) {
            return Result(true, "/sdcard/Download/skp/$name", uri, bytes.size.toLong())
        }

        val direct = File("/sdcard/Download/skp")
        if (runCatching { direct.exists() || direct.mkdirs() }.getOrDefault(false)) {
            val f = File(direct, name)
            if (runCatching { f.writeBytes(bytes) }.isSuccess) {
                return Result(true, f.absolutePath, null, bytes.size.toLong())
            }
        }

        val fallback = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, name)
        return runCatching {
            fallback.writeBytes(bytes)
            Result(true, fallback.absolutePath, null, bytes.size.toLong())
        }.getOrElse {
            Result(false, "", null, 0, it.javaClass.simpleName + ": " + it.message)
        }
    }

    /** 媒体库写 Downloads/skp/。API 29+ 免权限，失败返回 null 交给上层兜底。 */
    private fun toDownloads(ctx: Context, name: String, bytes: ByteArray): Uri? {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/skp")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun build(ctx: Context): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.append("=== skp 日志 ===\n")
        sb.append("导出时间: ").append(now).append('\n')
        sb.append("版本    : ").append(versionName(ctx)).append('\n')
        sb.append("设备    : ").append(Build.MANUFACTURER.trim()).append(' ')
            .append(Build.MODEL.trim()).append(" / Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(") / ")
            .append(Build.SUPPORTED_ABIS.firstOrNull() ?: "?").append('\n')
        sb.append("配置    : scanMode=").append(RuleStore.scanMode)
            .append(" gate=").append(String.format(Locale.US, "%.2f", RuleStore.glyphGate))
            .append(" timeout=").append(RuleStore.cornerTimeoutSec).append("s")
            .append(" delay=").append(RuleStore.cornerStartDelayMs).append("ms")
            .append(" overlay=").append(RuleStore.debugOverlay)
            .append(" verbose=").append(Logs.enabled).append('\n')
        sb.append("前台    : ").append(runCatching { FgWatch.current }.getOrNull() ?: "?").append('\n')

        val buf = Logs.readBuffer()
        sb.append("\n=== 应用内缓冲 (").append(if (buf.isEmpty()) "空" else "${buf.count { it == '\n' }} 行").append(") ===\n")
        if (buf.isEmpty()) {
            sb.append("（空：详细日志开关没打开，或打开之后还没跑过识别）\n")
        } else {
            sb.append(buf)
            if (!buf.endsWith("\n")) sb.append('\n')
        }

        sb.append("\n=== logcat（本进程） ===\n")
        sb.append(dumpLogcat())
        return sb.toString()
    }

    /**
     * 只捞自己这几个 tag。应用读 logcat 只能看到本 UID 的条目，
     * 所以这里既不会越权，也不会把别人家的日志混进来。
     */
    private fun dumpLogcat(): String {
        val tags = arrayOf("SkipEngine", "SkipGlyph", "SkipNet", "SkipCapture", "SkipDebug", "SkipLog")
        return try {
            val cmd = arrayListOf("logcat", "-d", "-v", "time", "-t", "2000")
            tags.forEach { cmd.add("$it:V") }
            val p = Runtime.getRuntime().exec(cmd.toTypedArray())
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            if (out.isBlank()) "（无输出：开关关闭，或系统限制了 logcat 读取）\n" else out
        } catch (t: Throwable) {
            "（读取失败：${t.javaClass.simpleName}: ${t.message}）\n"
        }
    }

    private fun versionName(ctx: Context): String = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else 0L
        "${info.versionName} ($code)"
    } catch (t: Throwable) {
        "?"
    }
}
