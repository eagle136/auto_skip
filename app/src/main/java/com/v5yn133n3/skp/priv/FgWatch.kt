package com.v5yn133n3.skp.priv

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * root 专用：前台包名监听。
 *
 * 无障碍的 `TYPE_WINDOW_STATE_CHANGED` 在真机上晚 2.4–3.1s 才投递，而且不同应用
 * 差好几秒（冷启动走 splash、Activity 复用、ROM 策略都会影响），这就是用户说的
 * 「软件启动延时太高，而且不同应用还不一样」。
 *
 * 这里换成系统自己的打点：前台 Activity 一变，WindowManager 立刻往 events buffer
 * 写 `wm_set_resumed_activity: [0,<包>/<Activity>,<reason>]`。ELF（[BIN]）用
 * `logcat -b events` 盯这一条，解析出包名后原子写进本应用私有目录的 [OUT_NAME]，
 * 本侧用 FileObserver 收到变化就回调 —— 从「切换发生」到「开始识别」是几十毫秒级。
 *
 * 没有 root 时这层自动不启用，仍然走看门狗那条路。
 */
object FgWatch {

    private const val TAG = "SkipFgWatch"
    private const val BIN = "skp_fgwatch"
    private const val OUT_NAME = "skp_fg"

    /**
     * 主通道：FIFO。ELF 每次切换往管道写一行包名，本侧**阻塞读**。
     *
     * 为什么非要多这一条（2026-10-30 23:36 实测）：
     *  - 文件 mtime 与系统 events 只差 2–3ms，ELF 侧已经实时；
     *  - 但 APK 侧 100ms 轮询的 `Thread.sleep` 在目标应用冷启动时被拉成 **3026ms**，
     *    即使把进程提到 nice -20 + top-app 也一样（试过，没用）；
     *  - 差别在唤醒方式：read 阻塞由内核在数据到达时直接 wake_up，sleep 依赖
     *    timer wheel 到期，而冷启动那几秒 timer 处理本身被推迟。
     * 所以主通道改成 FIFO，文件轮询降级为备用（FIFO 建不起来 / 老版 ELF 不写管道）。
     */
    private const val FIFO_NAME = "skp_fg.pipe"

    /**
     * 投放后用来验收的版本串，**必须整串匹配**。
     *
     * 原来只查前缀 `fgwatch-ok`：结果 20:36 那次投放 cp 静默失败、/data/local/tmp 里还是
     * v1，校验照样通过，新版本根本没上线，而日志里一切「正常」——这种假绿灯比报错难查得多。
     */
    private const val VERSION_MARK = "fgwatch-ok v13"

    /** 轮询间隔。文件 20 余字节，100ms 一次的代价远低于 FileObserver 不可控的 3s。 */
    private const val POLL_MS = 100L

    private var pollThread: Thread? = null
    private var pipeThread: Thread? = null
    private var outFile: File? = null
    private var fifo: File? = null

    /**
     * 最近一次「前台 Activity 所属包名」，由 ELF 的文件变化喂进来。
     *
     * 这是**比 rootInActiveWindow 更权威**的前台判据：它来自 WindowManager 的
     * wm_set_resumed_activity 打点，等于 dumpsys 里的 mFocusedApp；而
     * rootInActiveWindow 在通知栏/权限弹窗/输入法这类浮层盖上来时会指到浮层，
     * 真机日志里就出现过 `pause ... now=com.android.systemui` 把整轮扫描掐掉。
     * 非 root 档恒为 null，调用方要能接受 null。
     */
    @Volatile
    var current: String? = null
        private set

    private var onPkg: ((String) -> Unit)? = null

    private fun assetName(): String = "elf/fgwatch-arm64"

    private fun extract(ctx: Context): File? = try {
        val dst = File(ctx.codeCacheDir, BIN)
        ctx.assets.open(assetName()).use { input ->
            dst.outputStream().use { out -> input.copyTo(out) }
        }
        dst.setExecutable(true, false)
        if (dst.length() > 0) dst else null
    } catch (e: Exception) {
        Log.w(TAG, "extract 失败：${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** 返回是否已跑起来（投放 + 进程 + 监听器都成立）。 */
    fun start(ctx: Context, mode: Mode, onPkg: (String) -> Unit): Boolean {
        this.onPkg = onPkg
        if (mode != Mode.ROOT) return false
        outFile = File(ctx.filesDir, OUT_NAME)
        fifo = ensureFifo(ctx)

        // 每次启动都重投放：版本可能变了，且 /data/local/tmp 会被清
        val src = extract(ctx) ?: return false
        val dst = "/data/local/tmp/$BIN"

        // ★ 顺序不能换：**先杀旧进程，再覆盖**。
        //   正在执行的 ELF 在 Linux 上不能被写（ETXTBSY：「Text file busy」），
        //   而 pkill 又必须单独一批（和启动写在同一条命令里会把自己这条 shell 也匹配掉）。
        //
        //   pkill 只发 SIGTERM，从「发出信号」到「进程真的消失」有几十毫秒。
        //   不在这里等它，紧接着就 mv + 启动，会形成**新旧两个实例同时写 skp_fg**：
        //   旧版（popen 管道，慢 3s）的迟到写入照样触发 FileObserver，测出来就像
        //   新版本也慢（真机 23:09:34 那次就是这么被骗的）。
        //
        //   ★ 用 `pkill -x` 精确匹配进程名，不要用 `-f`：
        //     `su -c "pkill -f [s]kp_fgwatch"` 这条命令自己的命令行里也带该串，
        //     实测会把执行它的 shell 一起 SIGKILL 掉（adb 侧只看到 exit 137）。
        Shell.run("pkill -x $BIN", mode)
        Thread.sleep(220)
        Shell.run("pkill -9 -x $BIN", mode)
        // 孤儿 logcat：它继承了锁 fd，ELF 一死它就变成「锁的唯一持有者」，
        // 新实例会被 flock 挡在门外（启动后 3s 读不到包名就是这个）。
        // ELF 把自己的 argv[0] 改成了 skp_lg，这里就能精确定位、不误伤别的 logcat。
        Shell.run("pkill -9 -x skp_lg", mode)
        Thread.sleep(120)

        // 落地走 .tmp + mv：mv 换的是目录项，旧 inode 就算还在跑也不会报 ETXTBSY。
        val dep = Shell.run(
            listOf(
                "cp ${src.absolutePath} $dst.tmp 2>/dev/null || cat ${src.absolutePath} > $dst.tmp",
                "chmod 0755 $dst.tmp",
                "mv -f $dst.tmp $dst",
                "$dst -v",
            ),
            mode,
            timeoutMs = 12_000,
        )
        if (!dep.ok || !dep.out.contains(VERSION_MARK)) {
            Log.w(TAG, "投放失败：${dep.out.trim().take(120)}")
            return false
        }

        val out = outFile!!.absolutePath
        val pipeArg = fifo?.let { " -p ${it.absolutePath}" } ?: ""
        // ★ 先把上一次会话留在文件里的包名删掉。
        //   不删的话下面的「在线」判断读的是**上一次**的旧值，服务一起来就「在线」，
        //   而 lastPkg 也停在旧包上 —— 真机 20:37 那次就是 last=com.chinamobile.mcloud
        //   顶着，实际前台早就换到桌面了，第一条真正的切换被当成「没变」丢掉。
        //   而且删掉之后「文件出现」本身就等于「新进程起来了」，liveness 判断才是真的。
        runCatching { outFile?.delete() }

        val launch = Shell.run(
            // -l 只是兜底：v5 的日志优先走 stderr（write(2) 无缓冲），由这里重定向到文件。
            // 别用 `>log 2>&1`：那样每次启动都会截断，排查时看不到上一次的现场。
            "setsid nohup $dst -o $out$pipeArg -l /data/local/tmp/$BIN.log " +
                "</dev/null >/dev/null 2>>/data/local/tmp/$BIN.log &",
            mode,
            timeoutMs = 8_000,
        )
        Log.i(TAG, "launch ok=${launch.ok} out=$out ${launch.out.trim().take(80)}")

        // 等 ELF 自己把「当前前台」写第一次（只有新进程会写这个文件）
        var cur = ""
        val t0 = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - t0 < 3_000) {
            cur = readOut()
            if (cur.isNotEmpty()) break
            try {
                Thread.sleep(80)
            } catch (_: InterruptedException) {
                return false
            }
        }
        if (cur.isEmpty()) {
            Log.w(TAG, "启动后 3s 没读到前台包名（ELF 没跑起来？看 /data/local/tmp/$BIN.log）")
            return false
        }

        // 当前已经在前面，不算一次切换（服务刚连上不该凭空扫一轮）
        current = cur
        observe(ctx)
        Log.i(TAG, "在线，当前前台 $cur dir=${ctx.filesDir.absolutePath}")
        return true
    }

    /**
     * ★ 不用 FileObserver，用专用线程轮询（2026-10-30 23:15 实测后改的）。
     *
     * 原来监听 filesDir 的 MOVED_TO，实测**晚了 2986ms**：
     *   ELF 写文件 mtime = 23:15:32.861（与系统 events 的 32.863 只差 2ms，ELF 是实时的）
     *   但 onEvent 到 23:15:35.847 才回调 —— 这三秒全丢在 FileObserver 的分发上，
     *   和广告时长一个量级，等于前面所有加速都白做。
     *
     * 文件只有二十几个字节，100ms 读一次的成本可以忽略（readOut 里先 exists 再读），
     * 换来的是可控的、可测量的延迟上限，不会再出现「事件什么时候来全看系统」。
     */
    private fun observe(ctx: Context) {
        pipeThread?.interrupt()
        pollThread?.interrupt()
        startPipe()
        startPoll()
    }

    /** 包名到了（FIFO 主通道 / 文件轮询备用通道共用）。去重后交给回调。 */
    private fun onPkgSeen(pkg: String, src: String) {
        if (pkg.isEmpty() || !pkg.contains(".") || pkg.length >= 128) return
        if (pkg == current) return
        current = pkg
        // ★ 先触发，后打日志（顺序不能反）：logd 忙时 Log.i 能阻塞几百毫秒甚至几秒，
        //   它绝不能挡在「开始识别」前面（真机 23:27 那条 gap=3073ms 就是它）。
        val t0 = SystemClock.uptimeMillis()
        runCatching { onPkg?.invoke(pkg) }
        Log.i(TAG, "$src -> $pkg call=${SystemClock.uptimeMillis() - t0}ms")
    }

    /** FIFO。不存在就建一个（app 自己的私有目录，类型仍是 app_data_file）。 */
    private fun ensureFifo(ctx: Context): File? {
        val f = File(ctx.filesDir, FIFO_NAME)
        if (f.exists()) return f
        return try {
            android.system.Os.mkfifo(f.absolutePath, 438 /* 0666 */)
            f
        } catch (e: Exception) {
            Log.w(TAG, "mkfifo 失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 主通道：**阻塞读 FIFO**。
     *
     * 冷启动时 `Thread.sleep(100)` 实测被拉成 3026ms —— 提到 nice -20 + top-app 也没用，
     * 因为 sleep 靠 timer wheel 到期唤醒，而那段窗口里 timer 处理本身被推迟。
     * 阻塞 read 是内核在数据到达时直接 wake_up，不经过定时器，所以和 ELF 同一条量级。
     *
     * 用 O_RDWR 打开（不是 O_RDONLY）：Linux 上 O_RDWR 打开 FIFO 不会阻塞，
     * 这样 ELF 还没起来时我们不会卡死在 open 上，它一起来就自动接上。
     */
    private fun startPipe() {
        // 已经在读就别再开一个：O_RDWR 打开的 fd 阻塞在 read 上不响应 interrupt，
        // 重复 start（onServiceConnected 偶发两次）会留下「僵尸读端」，
        // FIFO 是「一个读者拿走一份数据」，多开一个就可能把我们要的那行读走。
        if (pipeThread?.isAlive == true) return
        val f = fifo ?: run {
            Log.w(TAG, "没有 FIFO，只能用文件轮询")
            return
        }
        val t = Thread({
            var fails = 0
            while (!Thread.currentThread().isInterrupted) {
                var fdesc: java.io.FileDescriptor? = null
                try {
                    fdesc = android.system.Os.open(
                        f.absolutePath,
                        android.system.OsConstants.O_RDWR or android.system.OsConstants.O_CLOEXEC,
                        0,
                    )
                    fails = 0
                    val reader = java.io.FileInputStream(fdesc).bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        onPkgSeen(line.trim(), "fifo")
                    }
                } catch (e: Exception) {
                    if (Thread.currentThread().isInterrupted) return@Thread
                    fails++
                    if (fails <= 3) Log.w(TAG, "pipe 断开 ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    runCatching { fdesc?.let { android.system.Os.close(it) } }
                }
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "skp-fg-pipe")
        t.isDaemon = true
        t.start()
        pipeThread = t
    }

    /**
     * 备用通道：100ms 轮询文件。
     *
     * 留着有两个用处：FIFO 建不起来（老系统 / 权限）时兜底；以及 `sleep 拉长` 这条
     * 日志本身就是「当前这轮调度有多糟」的体检指标，排查时很有用。
     */
    private fun startPoll() {
        val t = Thread({
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            }
            var lastTick = SystemClock.uptimeMillis()
            while (!Thread.currentThread().isInterrupted) {
                val r0 = SystemClock.uptimeMillis()
                val pkg = readOut()
                val r1 = SystemClock.uptimeMillis()
                // 单次读文件超过 150ms = 卡在 /data 的 I/O 上
                if (r1 - r0 >= 150) Log.w(TAG, "readOut 阻塞 ${r1 - r0}ms")
                if (pkg.isNotEmpty() && pkg != current) {
                    Log.i(TAG, "前台切换 -> $pkg gap=${r0 - lastTick}ms")
                    onPkgSeen(pkg, "poll")
                }
                // 用「现在」而不是读文件那一刻：中间可能被 onPkg / Log 占掉时间
                lastTick = SystemClock.uptimeMillis()
                try {
                    val s0 = SystemClock.uptimeMillis()
                    Thread.sleep(POLL_MS)
                    val s1 = SystemClock.uptimeMillis()
                    // sleep(100) 睡成 3 秒 = 线程没被调度（定时器被压），
                    // 和文件、日志、ELF 都无关。这条是分诊用的，别删。
                    if (s1 - s0 >= 200) Log.w(TAG, "sleep 拉长 ${s1 - s0}ms")
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "skp-fg-poll")
        t.isDaemon = true
        t.start()
        pollThread = t
    }

    /** 读一行前台包名。文件里只会是包名，这里再挡一道「不像包名就当没有」。 */
    private fun readOut(): String = try {
        val f = outFile ?: return ""
        if (!f.exists()) {
            ""
        } else {
            val s = f.readText().trim().lineSequence().firstOrNull().orEmpty().trim()
            if (s.contains(".") && s.length < 128) s else ""
        }
    } catch (e: Exception) {
        ""
    }

    /** 只停监听，不杀进程 —— 进程留着也能省一次投放；真要杀见 [stopAll]。 */
    fun stop() {
        pipeThread?.interrupt()
        pipeThread = null
        pollThread?.interrupt()
        pollThread = null
        onPkg = null
        current = null
    }

    fun stopAll(mode: Mode) {
        stop()
        if (mode != Mode.NONE) {
            Shell.run("pkill -x $BIN", mode)
            Shell.run("pkill -9 -x skp_lg", mode)
        }
    }
}
