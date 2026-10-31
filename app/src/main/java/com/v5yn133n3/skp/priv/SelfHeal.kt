package com.v5yn133n3.skp.priv

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.v5yn133n3.skp.engine.SkipService

/**
 * 提权自愈。铁律：**只补齐，不翻转**——旧版每次进主页都 toggle 一次无障碍开关，
 * 开着的时候反被关掉，这是它「上不了锁」的直接成因（进度.txt §B4）。
 * 唯一的例外是 [recoverCrashed]：系统把服务记成故障态之后自己不会再绑，
 * 那时「关掉再打开」是官方唯一的出路，所以要动，但必须带闸门。
 */
object SelfHeal {

    private const val TAG = "SkipSelfHeal"

    /** 名单写进去了但系统没登记服务时给用户的下一步。 */
    private const val NOT_REGISTERED =
        "名单已写入但系统没登记本服务；到 应用信息 → 右上角菜单 → 允许受限设置，再补齐一次"

    /** 两次自己关开的最小间隔，与他钉给守护的「拉服务至少隔十分钟」同一条口径。 */
    private const val RECOVER_MIN_INTERVAL_MS = 10 * 60 * 1000L

    /**
     * 两笔写之间的间隔：变更观察者是异步处理的，连着写可能被合并成「内容没变」，
     * crashed 集合就永远清不掉（A16/A17 的 readEnabledAccessibilityServicesLocked 内容相同就返回 false）。
     */
    private const val RECOVER_GAP_MS = 2_000L

    data class Step(val name: String, val ok: Boolean, val detail: String)

    fun componentId(context: Context) =
        "${context.packageName}/${SkipService::class.java.name}"

    /** 系统是否已经把我们的服务登记进无障碍列表（旧版 d1.n 的口径）。 */
    fun isServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as? android.view.accessibility.AccessibilityManager ?: return false
        return am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).orEmpty().any { it?.id?.contains(context.packageName) == true }
    }

    /**
     * secure 名单里的每一项（读不需要权限）。null = 读不到。
     * 这项判据在 A16/A17 里就是「已 bind」的集合：`getEnabledAccessibilityServiceList` 源码里
     * 遍历的是 AccessibilityUserState.mBoundServices，所以查不到我们 = 没绑上。
     */
    fun enabledListItems(context: Context): List<String>? = try {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(":").map { it.trim() }.filter { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "read enabled_accessibility_services failed", e)
        null
    }

    /** 名单里有没有我们这一项（只看本包名开头的，别家的项一概不认领）。 */
    fun isInEnabledList(context: Context): Boolean =
        enabledListItems(context)?.any { it.startsWith("${context.packageName}/") } == true

    /**
     * 故障态：**名单里有我们，但系统没把服务绑上**。
     * A16/A17 的 `updateServicesLocked()` 对 crashed 集合里的组件直接 continue，
     * 所以这个状态不会自己好，设置页就一直显示故障（进度.txt §B11）。
     */
    fun isCrashed(context: Context): Boolean = isInEnabledList(context) && !isServiceEnabled(context)

    /** -1 = 本轮还没修过。不能用 0 当哨兵：开机后 elapsedRealtime 本身就是个小数，会误判成「刚修过」。 */
    @Volatile
    private var lastRecoverAt = -1L

    /**
     * 自己把无障碍开关关掉再打开。
     *
     * 为什么必须是「改名单」而不是「翻主开关」：清 crashed 集合的唯一通路是
     * `removeDisabledServicesFromTemporaryStatesLocked()`，它只在 `enabled_accessibility_services`
     * 这个 URI 变化**且集合内容真的不等**时才被调；写回一模一样的名单是空操作。
     * 而 `accessibility_enabled` 是 AMS 自己按「有没有 bound service」回写的**输出值**，
     * 不参与 bind 判定，翻它拉不回任何东西（第 12 轮那条静默重绑就是这么失效的，见 §C 第 13 轮）。
     *
     * @return 没有故障态时返回 null，调用方不该凭空加一行状态。
     * 里面有 2s 的写入间隔和最长 6s 的绑定轮询，**只能在非主线程调**。
     */
    fun recoverCrashed(context: Context, mode: Mode): Step? {
        if (!isCrashed(context)) return null
        val now = SystemClock.elapsedRealtime()
        if (lastRecoverAt >= 0 && now - lastRecoverAt < RECOVER_MIN_INTERVAL_MS) {
            return Step(
                "无障碍修复", false,
                "系统仍判故障，距上次修复不足 10 分钟（再等 ${(RECOVER_MIN_INTERVAL_MS - (now - lastRecoverAt)) / 1000}s）",
            )
        }
        lastRecoverAt = now

        val items = enabledListItems(context)
            ?: return Step("无障碍修复", false, "读不到 enabled_accessibility_services")
        // 只摘自己这一项（本包名开头的都算），别家的项原样留着
        val reduced = items.filterNot { it.startsWith("${context.packageName}/") }

        val off = writeEnabledList(context, reduced, mode)
        if (!off.first) return Step("无障碍修复", false, "关这一步没落地：${off.second}")
        try {
            Thread.sleep(RECOVER_GAP_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val on = writeEnabledList(context, items, mode)
        if (!on.first) return Step("无障碍修复", false, "关成功但重挂失败：${on.second}")

        val bound = awaitRegistered(context, timeoutMs = 6_000)
        return Step(
            "无障碍修复", bound,
            if (bound) "已自己关一次再开，系统重新绑上（${on.second}）"
            else "已重新挂进名单，系统侧仍未 bind（判据：getEnabledAccessibilityServiceList 查不到本服务）",
        )
    }

    /**
     * 写名单：本应用能直写就先直写（`pm grant` 过的 WRITE_SECURE_SETTINGS 重启不掉，
     * 开机时 Shizuku 还没起来也修得动），写不动才走提权通道。
     * 判定一律以系统存下来的那份为准，不看 putString 的返回值、也不看命令退出码。
     */
    private fun writeEnabledList(context: Context, items: List<String>, mode: Mode): Pair<Boolean, String> {
        val key = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val value = items.joinToString(":")
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                android.provider.Settings.Secure.putString(context.contentResolver, key, value)
            }.onFailure { Log.w(TAG, "direct write of $key failed", it) }
            if (enabledListItems(context) == items) return true to "本应用直写"
        }
        if (mode == Mode.NONE) return false to "直写未落地且无提权通道"
        val r = Shell.run("settings put secure $key \"$value\"", mode)
        val stored = enabledListItems(context)
        return if (stored == items) true to "提权通道写入"
        else false to "写入后读回不符：现值=${stored?.joinToString(":")?.ifEmpty { "(空)" } ?: "读不到"}；" +
            "命令回话=${r.out.trim().take(80)}"
    }

    /** 读回当前的无障碍白名单，缺我们就补上，绝不删别人的项。 */
    fun ensureAccessibility(context: Context, mode: Mode): Step {
        if (mode == Mode.NONE) {
            return Step("无障碍开关", false, "无提权，请到系统设置手动开启")
        }
        val id = componentId(context)
        val get = Shell.run("settings get secure enabled_accessibility_services", mode)
        if (!get.ok) {
            return Step("无障碍开关", false, get.out.trim())
        }
        val current = get.out.trim().let { if (it == "null") "" else it }
        val items = current.split(":").filter { it.isNotBlank() }.toMutableList()
        val mine = items.filter { it.contains(context.packageName) }
        val hasTarget = items.any { it == id || it.endsWith("/${SkipService::class.java.name.substringAfterLast('.')}") }
        if (!hasTarget) {
            items += id
            val put = Shell.run(
                listOf(
                    "settings put secure enabled_accessibility_services ${items.joinToString(":")}",
                    "settings put secure accessibility_enabled 1",
                ),
                mode,
            )
            if (!put.ok) return Step("无障碍开关", false, put.out.trim())
            val registered = awaitRegistered(context)
            return Step("无障碍开关", registered, if (registered) "已补入 $id" else NOT_REGISTERED)
        }
        // 已经在名单里：先**读**主开关，没关就一个字都不写。
        // 这里是每次进页面都会走的分支，无条件写 accessibility_enabled 等于高频抖全局开关，
        // 而那个开关一动，名单里所有应用的服务都要重绑一次。
        val sw = Shell.run("settings get secure accessibility_enabled", mode)
        if (sw.out.trim() != "1") {
            val on = Shell.run("settings put secure accessibility_enabled 1", mode)
            if (!on.ok) return Step("无障碍开关", false, on.out.trim())
        }
        val live = isServiceEnabled(context)
        return Step(
            "无障碍开关", live,
            (if (live) "已存在" else NOT_REGISTERED) +
                if (mine.size > 1) "（旧包名残留 ${mine.joinToString()}）" else "",
        )
    }

    /**
     * 写完 secure settings 到 AccessibilityManagerService 真正登记服务是异步的，
     * 轮一小段再判定——「写了但没上锁」正是旧版最误导人的地方，不能只报写入成功。
     */
    private fun awaitRegistered(context: Context, timeoutMs: Long = 2_500): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (isServiceEnabled(context)) return true
            try {
                Thread.sleep(150)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return isServiceEnabled(context)
    }

    /** 电池优化白名单，shell uid 就能做，Root 同样可用。 */
    fun ensureBatteryWhitelist(context: Context, mode: Mode, add: Boolean): Step {
        if (mode == Mode.NONE) return Step("电池白名单", false, "无提权")
        val arg = if (add) "+${context.packageName}" else "-${context.packageName}"
        val r = Shell.run("dumpsys deviceidle whitelist $arg", mode)
        return Step("电池白名单", r.ok, r.out.trim().lines().lastOrNull().orEmpty())
    }

    /** 把 pm grant 也做掉，之后即使 Shizuku 退出，App 自己也能写 secure settings。 */
    fun grantSecureSettings(context: Context, mode: Mode): Step {
        if (mode == Mode.NONE) return Step("WRITE_SECURE_SETTINGS", false, "无提权")
        val r = Shell.run("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS", mode)
        return Step("WRITE_SECURE_SETTINGS", r.ok, r.out.trim().ifEmpty { if (r.ok) "已授予" else "授予失败" })
    }

    /** 档位降级时撤销本应用留下的痕迹（不动别的应用的项）。 */
    fun revoke(context: Context, mode: Mode): List<Step> {
        if (mode == Mode.NONE) return emptyList()
        val out = ArrayList<Step>()
        out += ensureBatteryWhitelist(context, mode, add = false)
        out += Daemon.stop(mode)
        return out
    }

    fun daemonRunning(mode: Mode): Boolean {
        if (mode == Mode.NONE) return false
        val r = Shell.run("pgrep -f ${Daemon.matchPattern}", mode)
        return r.out.trim().lines().any { it.toIntOrNull() != null }
    }

    /** 引擎进程 pid，按包名扫 /proc 得到，和守护程序用同一套口径。 */
    fun appPid(pkg: String, mode: Mode): Int {
        val r = Shell.run("pidof $pkg", mode)
        return r.out.trim().split(" ", "\n").firstOrNull()?.toIntOrNull() ?: 0
    }
}
