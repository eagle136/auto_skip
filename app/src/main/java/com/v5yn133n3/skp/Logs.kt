package com.v5yn133n3.skp

import android.content.Context
import android.util.Log
import com.v5yn133n3.skp.data.RuleStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识别链路的日志出口。**总开关默认关**。
 *
 * 为什么不再直接调 `Log.i`：这套链路每帧要打 2～4 条（miss / hit / reject / pick），
 * 加起来一秒十几条，logd 按 tag 限流时会把真正的线索冲掉；用户侧也没有任何收益。
 * 现在关掉时连 logcat 都没有（一次 volatile 读就返回），打开时同时写两个地方：
 *
 * | 去处 | 用途 |
 * |---|---|
 * | logcat | 开发时 `adb logcat -s SkipEngine` 直接看 |
 * | `files/log/engine.log` | 「导出日志」打包，进程被杀后也还在，上限 512 KB 自动滚一份 |
 *
 * 环形文件用 `appendText` 同步追加：单条几十字节、一帧几条，代价远小于一次截图，
 * 换来的是「进程被系统杀掉之后日志还在」——这恰恰是最需要看日志的时候。
 */
object Logs {

    /** 缓冲上限。超过就滚到 `engine.log.1`，只留一份，最多占 1 MB。 */
    private const val MAX_BYTES = 512 * 1024L

    private const val FILE_NAME = "engine.log"

    @Volatile
    var enabled: Boolean = false
        private set

    private var dir: File? = null

    private val lock = Any()

    /** `MM-dd HH:mm:ss.SSS`，不带年份：日志文件本身带导出时间，行内省 5 个字。 */
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 当前缓冲文件，导出时读它。没初始化或从没写过时是 null。 */
    val file: File?
        get() = dir?.let { File(it, FILE_NAME) }

    fun init(ctx: Context) {
        val d = File(ctx.filesDir, "log")
        runCatching { if (!d.exists()) d.mkdirs() }
        dir = d
        enabled = RuleStore.debugLog
        // 开关是关的时候不留旧文件：避免上一次的日志混进这一次的导出
        if (!enabled) runCatching { File(d, FILE_NAME).delete() }
    }

    /** 首页开关调用。关掉时顺手清空缓冲，下次打开看到的就是新的。 */
    fun setEnabled(ctx: Context, on: Boolean) {
        RuleStore.debugLog = on
        enabled = on
        if (!on) clear()
        Log.i("SkipLog", if (on) "verbose logging on" else "verbose logging off")
    }

    fun clear() {
        synchronized(lock) {
            runCatching {
                dir?.listFiles()?.forEach { if (it.name.startsWith(FILE_NAME)) it.delete() }
            }
        }
    }

    fun i(tag: String, msg: String) {
        if (!enabled) return
        Log.i(tag, msg)
        write("I", tag, msg)
    }

    fun d(tag: String, msg: String) {
        if (!enabled) return
        Log.d(tag, msg)
        write("D", tag, msg)
    }

    fun v(tag: String, msg: String) {
        if (!enabled) return
        Log.v(tag, msg)
        write("V", tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (!enabled) return
        Log.w(tag, msg, tr)
        write("W", tag, if (tr == null) msg else "$msg ${tr.javaClass.simpleName}: ${tr.message}")
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (!enabled) return
        Log.e(tag, msg, tr)
        write("E", tag, if (tr == null) msg else "$msg ${tr.javaClass.simpleName}: ${tr.message}")
    }

    private fun write(level: String, tag: String, msg: String) {
        val d = dir ?: return
        synchronized(lock) {
            try {
                val f = File(d, FILE_NAME)
                if (f.length() > MAX_BYTES) {
                    val bak = File(d, "$FILE_NAME.1")
                    bak.delete()
                    f.renameTo(bak)
                }
                val line = "${stamp.format(Date())} $level/$tag: $msg\n"
                f.appendText(line)
            } catch (t: Throwable) {
                // 写文件失败不能反过来影响识别，静默降级
            }
        }
    }

    /** 导出时读缓冲。超过 [maxBytes] 只取尾部，避免手机上一份日志几十兆。 */
    fun readBuffer(maxBytes: Int = 1024 * 1024): String {
        val f = file ?: return ""
        if (!f.exists()) return ""
        return try {
            val len = f.length()
            if (len <= maxBytes) f.readText()
            else f.readText().takeLast(maxBytes)
        } catch (t: Throwable) {
            ""
        }
    }
}
