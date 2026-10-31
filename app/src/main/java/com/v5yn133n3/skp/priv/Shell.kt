package com.v5yn133n3.skp.priv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 提权档位：只选一个，Root 档向下包含 Shizuku 档能力。 */
enum class Mode(val id: Int) {
    NONE(0),
    SHIZUKU(1),
    ROOT(2);

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: NONE
    }
}

data class ShellResult(val exit: Int, val out: String) {
    val ok: Boolean get() = exit == 0
}

/**
 * 一次性把多条命令喂给同一个 shell，取回 stdout+stderr。
 *
 * 成功判定只看输出里有没有 marker，不看退出码——`su` 在部分 ROM 上退出码不可信，
 * Shizuku 的 exitValue 又经常是 0 哪怕命令报错。
 */
object Shell {

    private const val TAG = "SkipShell"
    private const val SHIZUKU_REQUEST_CODE = 6001

    /**
     * su 喂命令的两种形态。KernelSU / APatch 的新 su 在应用域里不接 stdin（没有 tty 就吞命令），
     * 只认 `su -c`；老 Magisk 两种都行。先探一次，探明白就固定下来，不做每命令重试。
     */
    private enum class SuForm { ONESHOT, STDIN }

    private var suForm: SuForm? = null

    /** 探测失败的原文，UI 直接拿去说「卡在哪」。 */
    @Volatile
    var lastError: String = ""
        private set

    fun available(context: Context, mode: Mode): Boolean = when (mode) {
        Mode.NONE -> true
        Mode.SHIZUKU -> pingShizuku() && hasShizukuPermission()
        Mode.ROOT -> probeRoot()
    }

    /**
     * 不可用的原因。**不再自己探测**：调用方刚刚才判过可用与否，这里再探一次会白等一轮超时。
     * 通道其实是通的话返回空串。
     */
    fun unavailableReason(mode: Mode): String = when (mode) {
        Mode.NONE -> ""
        Mode.SHIZUKU -> when {
            !pingShizuku() -> "Shizuku 服务没在运行，先用无线调试或 Root 把它启动"
            !hasShizukuPermission() -> "Shizuku 授权还没点到「允许」"
            else -> ""
        }
        Mode.ROOT -> lastError.ifEmpty { "su 起不来：本应用可能没被 Root 管理放行" }
    }

    fun pingShizuku(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    fun hasShizukuPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    /** 向 Shizuku 发起授权请求（弹窗由 Shizuku 自己弹）；binder 没起来时只是空操作。 */
    fun requestShizukuAuthorization(): Boolean = try {
        if (!Shizuku.pingBinder()) {
            lastError = "Shizuku 服务没在运行，先用无线调试或 Root 把它启动"
            false
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            true
        }
    } catch (e: Exception) {
        lastError = "${e.javaClass.simpleName}: ${e.message}"
        Log.w(TAG, "requestShizukuAuthorization failed", e)
        false
    }

    /** 授权弹窗要等人点，轮询到超时为止。 */
    fun awaitShizukuPermission(timeoutMs: Long = 8_000): Boolean {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (hasShizukuPermission()) return true
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return hasShizukuPermission()
            }
        }
        return hasShizukuPermission()
    }

    private fun probeRoot(): Boolean {
        suForm?.let { return true }
        val failures = ArrayList<String>()
        for (form in SuForm.entries) {
            // 第一次给足时间：KernelSU 这时可能弹「有应用请求 Root」的框，等人点允许
            val budget = if (form == SuForm.ONESHOT) 15_000L else 4_000L
            val r = runRoot(listOf("id", "echo SKIP_DONE", "exit"), form, "SKIP_DONE", budget)
            if (r.out.contains("uid=0")) {
                suForm = form
                lastError = ""
                Log.i(TAG, "root channel ok via $form")
                return true
            }
            failures += "$form: ${brief(r.out)}"
            // su 已经把话说明白了（拒绝、找不到文件），再灌 stdin 也是同一句话
            if (r.out.isNotBlank()) break
        }
        lastError = (failures.joinToString("; ")
            .ifEmpty { "su 没有任何输出" } +
            "；KernelSU 只把 su 挂给名单里的应用，按「去 Root 管理器授权」放行后再补齐一次").take(240)
        Log.w(TAG, "root unavailable: $lastError")
        return false
    }

    /**
     * KernelSU 管理器的包名可以被定制（v3.3.0 起构建时可改），所以不按名字猜：
     * 谁注册了 `ksu://` 深链，谁就是本机的 KernelSU 管理器（源码 IntentDispatcher.kt +
     * manager/AndroidManifest.xml 里 ksu://action、ksu://webui 两个 intent-filter）。
     */
    fun findRootManager(context: Context): String? {
        val pm = context.packageManager
        return listOf("ksu://action", "ksu://webui").firstNotNullOfOrNull { scheme ->
            runCatching {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(scheme)), 0
                ).firstOrNull()?.activityInfo?.packageName
            }.getOrNull()
        }
    }

    /** 把已经找到的 Root 管理器拉到前台。 */
    fun launchRootManager(context: Context, pkg: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(launch) }.isSuccess
        Log.i(TAG, "launch root manager $pkg ok=$ok")
        return ok
    }

    private fun brief(text: String): String =
        text.trim().lines().lastOrNull()?.take(90)?.ifEmpty { "无输出" } ?: "无输出"

    /** su 子进程与 Shizuku 远端进程的公共面 */
    private class ShellChannel(
        private val proc: Process?,
        private val remote: IRemoteProcess?,
    ) {
        val stdin: OutputStream = proc?.outputStream
            ?: ParcelFileDescriptor.AutoCloseOutputStream(remote!!.outputStream)
        val stdout: InputStream = proc?.inputStream
            ?: ParcelFileDescriptor.AutoCloseInputStream(remote!!.inputStream)
        val stderr: InputStream = proc?.errorStream
            ?: ParcelFileDescriptor.AutoCloseInputStream(remote!!.errorStream)

        fun kill() {
            runCatching { proc?.destroy() }
            runCatching { remote?.destroy() }
        }

        fun exitCode(): Int = runCatching {
            proc?.exitValue() ?: remote!!.exitValue()
        }.getOrDefault(1)
    }

    private fun shizukuService(): IShizukuService? =
        Shizuku.getBinder()?.let { IShizukuService.Stub.asInterface(it) }

    fun run(
        commands: List<String>,
        mode: Mode,
        marker: String = "SKIP_DONE",
        timeoutMs: Long = 15_000,
    ): ShellResult {
        if (mode == Mode.NONE) return ShellResult(1, "no privilege mode")
        val script = commands + listOf("echo $marker", "exit")
        return when (mode) {
            Mode.ROOT -> {
                // 先探明形态再发命令：没探过就盲发，su 挂住时每条命令各等一轮超时
                if (!probeRoot()) return ShellResult(1, "Root 通道不可用：$lastError")
                runRoot(script, suForm ?: SuForm.ONESHOT, marker, timeoutMs)
            }
            Mode.SHIZUKU -> {
                val service = shizukuService()
                    ?: return ShellResult(1, "Shizuku binder 不可用")
                exec({ ShellChannel(null, service.newProcess(arrayOf("sh"), null, null)) }, script, marker, timeoutMs)
            }
            Mode.NONE -> ShellResult(1, "no privilege mode")
        }
    }

    private fun runRoot(script: List<String>, form: SuForm, marker: String, timeoutMs: Long): ShellResult =
        when (form) {
            // 命令整串交给 su 的一次性 shell，不依赖 stdin
            SuForm.ONESHOT -> exec(
                {
                    ShellChannel(
                        ProcessBuilder("su", "-c", script.joinToString("\n")).redirectErrorStream(true).start(),
                        null,
                    )
                },
                emptyList(), marker, timeoutMs,
            )
            // 老式：起交互 shell，把命令从 stdin 灌进去
            SuForm.STDIN -> exec(
                { ShellChannel(ProcessBuilder("su").redirectErrorStream(true).start(), null) },
                script, marker, timeoutMs,
            )
        }

    private fun exec(
        open: () -> ShellChannel,
        feed: List<String>,
        marker: String,
        timeoutMs: Long,
    ): ShellResult {
        val channel = try {
            open()
        } catch (e: Exception) {
            Log.w(TAG, "open shell failed", e)
            return ShellResult(1, "${e.javaClass.simpleName}: ${e.message}")
        }
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
            } catch (e: InterruptedException) {
                return@Thread
            }
            channel.kill()
        }
        watchdog.isDaemon = true
        watchdog.start()
        return try {
            DataOutputStream(channel.stdin).use { os ->
                feed.forEach { os.writeBytes(it + "\n") }
                os.flush()
            }
            val sb = StringBuilder()
            channel.stdout.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append(line).append('\n')
            }
            val text = sb.toString()
            // 标记行只用来判成没成；留在 out 里会被 UI 原样显示成「…SKIP_DONE」
            val out = text.lineSequence().takeWhile { it.trim() != marker }.joinToString("\n")
            ShellResult(if (text.contains(marker)) 0 else channel.exitCode(), out)
        } catch (e: Exception) {
            ShellResult(1, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            watchdog.interrupt()
            channel.kill()
        }
    }

    /**
     * 把本地文件灌到远端路径，并用 `$dst -v` 回读校验。
     * Shizuku 档只能走这条：远端是 shell uid，读不到应用私有目录里解出来的 assets，
     * 所以把字节从子进程的 stdin 写过去；先落 .tmp 再 mv，避免半个文件被执行一次算一次投放。
     */
    fun pushFile(src: File, dst: String, timeoutMs: Long = 20_000): ShellResult {
        val service = shizukuService() ?: return ShellResult(1, "Shizuku binder 不可用")
        val script = "cat > $dst.tmp && chmod 0755 $dst.tmp && mv $dst.tmp $dst && $dst -v"
        val channel = try {
            ShellChannel(null, service.newProcess(arrayOf("sh", "-c", script), null, null))
        } catch (e: Exception) {
            Log.w(TAG, "pushFile open failed", e)
            return ShellResult(1, "${e.javaClass.simpleName}: ${e.message}")
        }
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            channel.kill()
        }
        watchdog.isDaemon = true
        watchdog.start()
        return try {
            src.inputStream().use { input ->
                channel.stdin.use { os -> input.copyTo(os, 64 * 1024) }
            }
            // stdin 关掉才算传完：对端 cat 见到 EOF 才放行后面那条 $dst -v
            ShellResult(channel.exitCode(), channel.stdout.bufferedReader().use { it.readText() }.trim())
        } catch (e: Exception) {
            ShellResult(1, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            watchdog.interrupt()
            channel.kill()
        }
    }

    fun run(command: String, mode: Mode, timeoutMs: Long = 15_000) =
        run(listOf(command), mode, timeoutMs = timeoutMs)

    /**
     * 二进制 stdout 通道：`screencap` 不带文件名时把整帧写到 stdout，这里原样交给
     * reader 直接读进内存，不落盘、不按行解码。stderr 单独抽干，避免管道写满卡死。
     * 返回空串表示命令正常结束，否则是失败原因。
     */
    fun binary(
        command: String,
        mode: Mode,
        timeoutMs: Long = 1500,
        reader: (InputStream) -> Unit,
    ): String {
        if (mode == Mode.NONE) return "no privilege mode"
        val channel = try {
            when (mode) {
                Mode.ROOT -> {
                    if (!probeRoot()) return "Root 通道不可用：$lastError"
                    if (suForm == SuForm.ONESHOT) {
                        ShellChannel(
                            ProcessBuilder("su", "-c", command)
                                .redirectErrorStream(false).start(),
                            null,
                        )
                    } else {
                        val ch = ShellChannel(
                            ProcessBuilder("su").redirectErrorStream(false).start(), null
                        )
                        DataOutputStream(ch.stdin).use { it.writeBytes("$command\nexit\n") }
                        ch
                    }
                }
                Mode.SHIZUKU -> {
                    val service = shizukuService() ?: return "Shizuku binder 不可用"
                    ShellChannel(null, service.newProcess(arrayOf("sh", "-c", command), null, null))
                }
                Mode.NONE -> return "no privilege mode"
            }
        } catch (e: Exception) {
            Log.w(TAG, "binary open failed", e)
            return "${e.javaClass.simpleName}: ${e.message}"
        }
        val err = StringBuilder()
        val drain = Thread {
            runCatching {
                channel.stderr.bufferedReader().forEachLine {
                    if (err.length < 2000) err.append(it).append(' ')
                }
            }
        }
        drain.isDaemon = true
        drain.start()
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            channel.kill()
        }
        watchdog.isDaemon = true
        watchdog.start()
        return try {
            channel.stdout.use(reader)
            if (err.isNotBlank()) err.toString().trim() else ""
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message} ${err}".trim()
        } finally {
            watchdog.interrupt()
            channel.kill()
        }
    }
}
