package com.v5yn133n3.skp.priv

import android.content.Context
import android.os.Build
import android.util.Log
import com.v5yn133n3.skp.data.RuleStore
import com.v5yn133n3.skp.priv.SelfHeal.Step
import java.io.File

/**
 * 常驻守护（Shizuku / Root 两档都能跑）。方案：ELF 起来后按包名扫 /proc 拿到引擎 pid，
 * 用 pidfd 挂上去等退出事件（不是轮询）。要修两种坏法：进程没了，以及进程还在但系统把
 * 服务记成故障态不再绑（开机被 ROM 杀掉就是后者）。修法只有一个——把本组件从
 * `enabled_accessibility_services` 摘掉再挂回去，见 进度.txt §B11；翻 `accessibility_enabled`
 * 主开关不起作用（那是 AMS 自己回写的输出值）。改名单要 shell 或 root 的写权限，两档都满足。
 * 重绑拿不到进程才 `am start` 起界面兜底；有 root 能力时再把 oom_score_adj 压到 -1000
 * （内核允许的最低档，LMKD 不会选它；跨 uid 写不了，ELF 失败只记日志不退出）。
 * AMS 自己的 cur=100 是 FGS 的正常账，内核值才是 LMKD 的判据，所以 ELF 每秒重刷一次。
 *
 * 交付物只有 APK，ELF 塞在 assets 里；启动时解出来再放到可执行目录——各 ROM 的 /data 挂载
 * 常常是 noexec，所以逐个候选点试跑，`-v` 回读到版本标记才算成。
 */
object Daemon {

    private const val TAG = "SkipDaemon"

    /** 进程名特征，pgrep/pkill -f 用它匹配；同时也是落到磁盘上的文件名。 */
    const val PATH_HINT = "v5skp_watchd"

    /**
     * 给 pgrep/pkill -f 用的模式：首字母写成字符组，正则照样匹配 v5skp_watchd，
     * 但执行这条命令的 shell 自己的 argv 里是 `[v]5skp_watchd`，匹配不上——
     * `su -c` 把整串命令塞进 argv，不这么写就会自杀（pkill 掉自己）或自命中（pgrep 假在线）。
     */
    val matchPattern: String = "[${PATH_HINT.first()}]${PATH_HINT.drop(1)}"

    /** Root 把引擎 oom_score_adj 压到最低档：-1000 时 oom_score=0、oom_adj=-17，LMKD 不会选它。 */
    const val OOM_TARGET = -1000

    private const val STATUS_FILE_NAME = "$PATH_HINT.status"
    /**
     * 版本串必须整串匹配（同 FgWatch 的教训：只查前缀会让旧二进制混过验收）。
     * 改了 native/watchdog.c 就要在这里和 C 侧一起加一版，否则已运行的旧守护
     * 会被“已运行”快路径留下来，新代码永远不上机。
     */
    private const val VERSION_MARK = "skipd-ok v2"

    /** 守护往二进制同目录写 `alive <daemonPid> <enginePid> <restarts>`，Kotlin 侧读它展示状态。 */
    private const val DEFAULT_DIR = "/data/local/tmp"

    private fun assetName(): String {
        val abis = Build.SUPPORTED_ABIS.map { it.lowercase() }
        return when {
            abis.any { it.contains("arm64") || it == "aarch64" } -> "elf/watchdog-arm64"
            abis.any { it.contains("x86_64") || it == "amd64" } -> "elf/watchdog-x64"
            else -> "elf/watchdog-arm64"
        }
    }

    /** 可执行目录候选：root 可写的 tmp 优先，其次应用私有缓存（su 域通常允许 exec）。 */
    private fun candidateDirs(context: Context): List<String> = listOf(
        DEFAULT_DIR,
        "/data/adb",
        context.codeCacheDir.absolutePath,
        context.noBackupFilesDir.absolutePath,
    )

    private fun binPath(dir: String) = "$dir/$PATH_HINT"

    /** 把 assets 里的 ELF 落到应用缓存，后面再由 shell 复制到目标目录。 */
    private fun extract(context: Context): File? = try {
        val dst = File(context.codeCacheDir, PATH_HINT)
        context.assets.open(assetName()).use { input ->
            dst.outputStream().use { out -> input.copyTo(out) }
        }
        dst.setExecutable(true, true)
        if (dst.length() > 0) dst else null
    } catch (e: Exception) {
        Log.w(TAG, "extract failed for ${assetName()}", e)
        null
    }

    /**
     * 投放：Root 档直接 cp 应用解出来的那份（su 读得到私有目录）；
     * Shizuku 档的远端是 shell uid，读不到私有目录，只能把字节从子进程 stdin 灌过去。
     * 两条都靠 `$dst -v` 回读版本标记来判定，写完就算装上是不合格的。
     */
    private fun deploy(src: File, dst: String, mode: Mode, timeoutMs: Long): ShellResult =
        if (mode == Mode.ROOT) {
            val dir = dst.substringBeforeLast('/')
            // 必须先落 .tmp 再 mv：旧守护正跑着 $dst，直接 cp 会 ETXTBSY，
            // 旧版就是这么静默留在磁盘上、检查 $dst -v 还照样通过的。
            Shell.run(
                listOf(
                    "mkdir -p $dir 2>/dev/null",
                    "cp ${src.absolutePath} $dst.tmp 2>/dev/null || cat ${src.absolutePath} > $dst.tmp",
                    "chmod 0755 $dst.tmp",
                    "mv -f $dst.tmp $dst",
                    "$dst -v",
                ),
                mode,
                timeoutMs = timeoutMs,
            )
        } else {
            Shell.pushFile(src, dst, timeoutMs)
        }

    /**
     * 安装并启动。逐步返回结果给 UI 直接展示，不折叠成单个成功位——
     * 「装上了但没跑起来」和「根本没权限」是两回事。
     */
    @Synchronized
    fun start(context: Context, mode: Mode): List<Step> {
        if (mode == Mode.NONE) {
            return listOf(Step("守护进程", false, "无提权：守护要 shell 或 root 能力才起得来"))
        }
        // 先把通道打通再说投放：否则四个候选目录各报一次同一种错误，看不出根因
        if (!Shell.available(context, mode)) {
            return listOf(Step("提权通道", false, "通道不可用：${Shell.unavailableReason(mode)}"))
        }
        // 页面刷新、开机广播、系统拉活都会走到这里；守护已经在线且版本对得上就别重启它。
        // 旧版无条件先投放再 pkill，等于每进一次页面都把守护换一遍。
        val liveDir = RuleStore.daemonBinDir
        if (liveDir.isNotEmpty() && isRunning(mode)) {
            val ver = Shell.run("${binPath(liveDir)} -v", mode, timeoutMs = 4_000)
            if (ver.out.contains(VERSION_MARK)) {
                return listOf(
                    Step("守护进程", true, "已在运行：${statusLine(mode).ifEmpty { "pid 在线" }}")
                )
            }
        }
        val src = extract(context)
            ?: return listOf(Step("投放 ELF", false, "安装包内没有 ${assetName()}，构建时未产出守护程序"))

        val probes = ArrayList<String>()
        val binDir = candidateDirs(context).firstOrNull { dir ->
            val dst = binPath(dir)
            val r = deploy(src, dst, mode, timeoutMs = 12_000)
            val usable = r.ok && r.out.contains(VERSION_MARK)
            if (!usable) probes += "$dir: ${r.out.trim().lines().lastOrNull().orEmpty().take(80)}"
            usable
        } ?: return listOf(Step("投放 ELF", false, "没有可执行目录（${probes.joinToString("; ")}）"))

        val bin = binPath(binDir)
        val statusFile = "$binDir/$STATUS_FILE_NAME"
        val enginePid = SelfHeal.appPid(context.packageName, mode)
        // 清旧进程单独一批：它和拉起不能同批，同批时这条命令自己的 argv 就带着守护路径
        Shell.run("pkill -f $matchPattern", mode)
        // 拉活只能喂 Activity：`am start -n` 喂无障碍服务组件只会回 Error type 3，
        // 守护等 60s 拿不到 pid 就白跑一轮。启动入口交给 PackageManager 反查，不写死类名。
        val launchTarget = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.component?.let { "${it.packageName}/${it.className}" }.orEmpty()
        val launch = Shell.run(
            listOf(
                // nohup 必须在链上：只靠 setsid 断会话，SIGHUP 还是可能被系统回收时打到
                "setsid nohup $bin --pkg ${context.packageName} " +
                    // 守护自己关开无障碍要用组件名去改名单，不能猜
                    "--component ${SelfHeal.componentId(context)} " +
                    (if (launchTarget.isEmpty()) "" else "--launch $launchTarget ") +
                    "--status $statusFile " +
                    "--oom $OOM_TARGET " +
                    "</dev/null >$statusFile.log 2>&1 &",
            ),
            mode,
        )
        val steps = ArrayList<Step>()
        Log.i(TAG, "launch bin=$bin target=$launchTarget ok=${launch.ok} out=${launch.out.trim().take(160)}")
        steps += Step("投放 ELF", true, "$bin 可执行")
        steps += Step(
            "拉活目标", launchTarget.isNotEmpty(),
            launchTarget.ifEmpty { "反查不到启动 Activity：守护只能盯进程，死了拉不回来" },
        )
        steps += Step("定位引擎", enginePid != 0, if (enginePid != 0) "pid=$enginePid" else "pidof 暂未命中，守护会自己等")
        steps += Step("启动守护", launch.ok, if (launch.ok) "已脱离会话启动" else launch.out.trim())
        if (!launch.ok) return steps
        RuleStore.daemonBinDir = binDir
        val alive = waitForRunning(mode)
        if (!alive) Log.w(TAG, "no daemon process after launch; statusFile=$statusFile")
        steps += Step(
            "守护在线",
            alive,
            if (alive) statusLine(mode).ifEmpty { "运行中" } else "启动后未检测到进程",
        )
        return steps
    }

    private fun statusPath(): String =
        "${RuleStore.daemonBinDir.ifEmpty { DEFAULT_DIR }}/$STATUS_FILE_NAME"

    private fun waitForRunning(mode: Mode, deadlineMs: Long = 6_000): Boolean {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            if (isRunning(mode)) return true
            try {
                Thread.sleep(400)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    fun isRunning(mode: Mode): Boolean = SelfHeal.daemonRunning(mode)

    /** 守护程序维护的状态行，UI 用它显示「盯的是哪个 pid、拉活过几次」。 */
    fun statusLine(mode: Mode): String {
        val r = Shell.run("cat ${statusPath()} 2>/dev/null", mode)
        return if (r.ok) r.out.trim() else ""
    }

    /** 停止并清掉自己投放的文件（只删自己名字下的东西，不碰别的）。 */
    fun stop(mode: Mode): Step {
        if (mode == Mode.NONE) return Step("守护进程", false, "无提权")
        val dir = RuleStore.daemonBinDir
        Shell.run("pkill -f $matchPattern", mode)
        if (dir.isNotEmpty()) {
            // 删除单独一批：这条命令自己的 argv 里就写着守护路径
            Shell.run(
                listOf("rm -f ${binPath(dir)} ${statusPath()} ${statusPath()}.log"),
                mode,
            )
            RuleStore.daemonBinDir = ""
        }
        val left = isRunning(mode)
        return Step("守护进程", !left, if (left) "仍在运行" else "已停止并清理")
    }
}
