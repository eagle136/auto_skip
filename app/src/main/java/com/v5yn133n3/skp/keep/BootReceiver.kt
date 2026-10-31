package com.v5yn133n3.skp.keep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.v5yn133n3.skp.priv.SelfHeal.Step
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 开机后把保活重新铺一遍：系统重启会带走守护进程，ELF 还在磁盘上但没人跑。
 *
 * 无障碍这一侧不再是「系统自己会拉」：A16/A17 里服务进程被杀过一次就被记进 crashed 集合，
 * `updateServicesLocked()` 对它直接 continue，从此显示故障且永不重绑。所以这里必须真的动一次
 * 名单（见 com.v5yn133n3.skp.priv.SelfHeal.recoverCrashed）。名单写的是 secure settings，
 * 本应用 `pm grant` 过 WRITE_SECURE_SETTINGS 就能自己写、不需要提权通道——
 * 这条是开机时刻 Shizuku/Root 还没就绪时唯一走得通的修复路。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val app = context.applicationContext
        val pending = goAsync()
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            val mode = runCatching { KeepAlive.currentMode() }.getOrNull()
            val steps: List<Step> = runCatching {
                // 开机统一走全量补齐：里面含 ensureAccessibility + 故障态自修 + 守护。
                // 守护没有独立开关，档位不是「仅无障碍」就会跟着起（见 KeepAlive.apply）。
                if (mode == null) emptyList() else KeepAlive.apply(app, mode)
            }.getOrElse { listOf(Step("开机补齐", false, "${it.javaClass.simpleName}: ${it.message}")) }
            // 开机广播里崩了没人看，但落一行到文件里下次就能查——
            // 「广播到没到、走到哪一步、哪一步失败」这三件事过去只能靠猜
            writeTrace(app, action, mode?.name.orEmpty(), steps)
            pending.finish()
            worker.shutdown()
        }
    }

    private fun writeTrace(context: Context, action: String, mode: String, steps: List<Step>) {
        runCatching {
            val file = File(context.filesDir, TRACE_NAME)
            // 只留最近这一段，别让它一直长
            if (file.length() > TRACE_MAX_BYTES) file.delete()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val sb = StringBuilder()
            sb.append("$stamp up=${SystemClock.elapsedRealtime()} action=$action mode=$mode\n")
            if (steps.isEmpty()) sb.append("  (没走任何一步)\n")
            steps.forEach {
                sb.append("  ${if (it.ok) "ok " else "FAIL"} ${it.name}: ${it.detail}\n")
            }
            file.appendText(sb.toString(), Charsets.UTF_8)
        }
    }

    private companion object {
        const val TRACE_NAME = "boot_trace.txt"
        const val TRACE_MAX_BYTES = 64L * 1024
    }
}
