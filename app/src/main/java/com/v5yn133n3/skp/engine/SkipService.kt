package com.v5yn133n3.skp.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.v5yn133n3.skp.K
import com.v5yn133n3.skp.R
import com.v5yn133n3.skp.data.RuleStore
import com.v5yn133n3.skp.priv.FgWatch
import com.v5yn133n3.skp.priv.Mode
import com.v5yn133n3.skp.ui.MainActivity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import com.v5yn133n3.skp.Logs

class SkipService : AccessibilityService() {

    companion object {
        const val TAG = "SkipEngine"
        const val CHANNEL_ID = "skip.foreground.channel"
        const val NOTIF_ID = 2026

        /**
         * 四角任务启动冷却。同包内插屏广告连续弹窗口时用它压住队列。
         * 原来 1500ms：换到新应用时 lastCornerAt 会被清零，所以这个值只影响「同一个包里
         * 连续换窗口」。1.5s 意味着第二个插屏要等 1.5s 才开始扫 —— 用户实测「不同应用
         * 首次识别速度差几秒」有一部分就出自这里。压到 400ms。
         */
        private const val CORNER_COOL_MS = 400L

        /**
         * 看门狗周期。**400ms**：它现在同时承担「换应用立即接管前台包」这件事
         * （见 ensureCornerAlive 里的 retarget），不再是 800ms 的保险丝。
         * 冷启动那条 TYPE_WINDOW_STATE_CHANGED 实测晚 2.4–3.1s，且不同应用差很多
         * —— 等它等于把「启动延时」交给系统心情决定；这里取一次
         * rootInActiveWindow 走无障碍内部缓存、不截图，400ms 一次的代价可以忽略，
         * 换来的是最坏等待从 800ms 降到 400ms。
         */
        private const val WATCHDOG_MS = 400L

        /**
         * 上一次真正开扫的包名。
         *
         * 「触发切换了就匹配包名开始识别」——这句话的落地就是它：看门狗只在
         * **前台包名和这个不一样**时才新建任务。没有它，一个包扫完 4 秒 → 任务结束
         * → 看门狗看到「没有活跃任务」又起一轮，无限循环，用户看到的正是
         * 「设 4 秒实际一直在扫」。同包内要重新扫，只有包名真的变了才算。
         */
        @Volatile
        private var lastCornerPkg: String? = null

        /** 环境不具备（无 root / 无截图权限 / 权重缺失）时的重建退避。 */
        private const val CORNER_FAIL_BACKOFF_MS = 30_000L

        @Volatile
        var instance: SkipService? = null
            private set
    }

    private val pool = ThreadPoolExecutor(4, 4, 0L, TimeUnit.SECONDS,
        LinkedBlockingQueue(8), Executors.defaultThreadFactory(),
        ThreadPoolExecutor.DiscardOldestPolicy())

    private val tasks = CopyOnWriteArrayList<SkipTask>()
    private val cornerTasks = CopyOnWriteArrayList<CornerTask>()
    /**
     * 四角任务专用线程池。
     *
     * 用 2 条而不是 1 条：换应用时旧任务是设 `cancelled` 让它自己退，它至少还要走完
     * 当前这一帧（截图 170ms + 模型，最坏 600ms）；单线程时新任务会排在这后面，
     * 表现为「换个 app 要等一会儿才开始识别」。两条线程里截图/推理各自加锁，
     * 只是把「排队」换成「抢锁」，冷启动那一下能省掉半秒以上。
     */
    private val cornerPool = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastPkg: String? = null

    /**
     * 四角任务的启动冷却。同一个包里插屏广告会连续弹多个 Activity，每个都扫 10s 会把
     * single thread 占死；1.5s 是「上一个窗口的画面已经稳定、下一个还没来」的折中。
     *
     * @Volatile：现在有两个线程会写它 —— 主线程的看门狗，和 FileObserver 线程上的
     * fg-trigger（见 [onFgChanged]），不标的话冷启动那次可能读到上一轮的旧值。
     */
    @Volatile
    private var lastCornerAt = 0L

    /** trigger 日志节流用（同包 state=same 会连发多条，见 [triggerCorner]）。 */
    private var lastSameLogPkg: String? = null
    private var lastSameLogAt = 0L

    /**
     * 本窗口已经有任务点到过东西。四角任务靠它做「命中即停」：
     * 节点路 3s 就点掉了，四角不该再空跑到 10s（363 ms/帧的截图）。
     */
    @Volatile
    var hitThisWindow: Boolean = false
        private set

    /** 上次「环境不具备」的时刻，见 [CORNER_FAIL_BACKOFF_MS]。 */
    @Volatile
    private var cornerFailAt = 0L

    /**
     * 节点路最近一次点中的时刻。
     *
     * 必须和「有没有人点过」分开记：节点路点掉「跳过」之后，四角任务下一帧还会在同一张
     * 旧画面上找到同一颗按钮（位置差几十像素，同位置冷却拦不住），于是补第二发 ——
     * 真机日志里 `KEYWORD hit` 之后 48 ms 紧跟 `CORNER hit` 就是这种双点。
     * 现在四角看到节点路刚点过就让位，不再插手它已经处理掉的按钮。
     */
    @Volatile
    private var nodeHitAt = 0L

    /** 四角路最近一次点中的时刻。给节点路做对称保护，见 [cornerHitRecently]。 */
    @Volatile
    private var cornerHitAt = 0L

    fun markHit(by: String = "corner") {
        hitThisWindow = true
        val now = SystemClock.uptimeMillis()
        if (by == "node") nodeHitAt = now else cornerHitAt = now
    }

    /** 节点路是否刚点过（[K.NODE_HOLD_MS] 内）。 */
    fun nodeHitRecently(): Boolean =
        nodeHitAt > 0 && SystemClock.uptimeMillis() - nodeHitAt < K.NODE_HOLD_MS

    /**
     * 四角路是否刚点过。节点任务在点击前查它 —— 否则四角点了「跳过」、广告还在收尾的
     * 那 1 秒里，节点任务会在同一张旧画面上找到同一个节点再点一次，就是双点。
     * 窗口取「点击等待 + 0.6s」，正好覆盖广告关闭的延迟。
     */
    fun cornerHitRecently(): Boolean =
        cornerHitAt > 0 && SystemClock.uptimeMillis() - cornerHitAt < K.TAP_SETTLE_MS + 600

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 灭屏后重置“当前包”，回同一个小应用时也要重新跑一轮检测
            if (intent?.action == Intent.ACTION_SCREEN_OFF) lastPkg = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        if (RuleStore.foregroundServiceOn) startForegroundNotice()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            // TYPE_WINDOWS_CHANGED 是「窗口层级变了」就发，比 TYPE_WINDOW_STATE_CHANGED
            // 早得多（后者冷启动实测晚 2.4–3.1s，而且不同应用差几秒）。扫带只关心
            // 「前台是谁」，这个事件正好是它。
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS  // 合计 82，与旧版 serviceInfo 一致
            notificationTimeout = 1
        }
        Logs.i(TAG, "service connected, pkg=${packageName}")
        // 权重只有 170 KB，但第一次要从 assets 读出来；顺带用固定输入验一遍移植
        // （logits 与 torch 差 >0.01 说明权重布局或卷积接错了）
        // 主页那个全局扫带开关已经删掉：扫带是否生效完全由「生效应用列表」里每包
        // 的 OCR 开关决定，权重自检就没必要再挂一个全局条件。
        cornerPool.execute {
            SkipNet.selfTest(applicationContext)
            // 前向循环的 JIT 预热放在这里：真到广告出现那一刻再烫，第一帧就是 1.5s
            SkipNet.warmup()
            // ★ root 专用：用 ELF 盯 events buffer 里的 wm_set_resumed_activity，
            //   前台包名一变就写文件，这边 FileObserver 立刻接上。
            //   比起等无障碍那条晚 2.4–3.1s 的事件，这条是几十毫秒级。
            val m = Mode.of(RuleStore.privMode)
            if (m == Mode.ROOT) {
                val ok = FgWatch.start(this, m) { pkg -> onFgChanged(pkg) }
                Logs.i(TAG, "fgwatch started=$ok")
            }
        }
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val pkg = event.packageName?.toString() ?: safeRoot()?.packageName?.toString() ?: return
            // 用户在同一个窗口里手动点了东西：把已经点过别的窗口的旧任务撤掉，避免重复点
            tasks.filter { it.pkgName == pkg }.forEach { task ->
                val t = task.eventTime
                if (t > 0 && event.eventTime - t >= 300 &&
                    !task.clicked.contains(event.windowId)
                ) {
                    task.cancelled = true
                    tasks.remove(task)
                }
            }
            return
        }

        // 只有这两类事件参与「前台切换」触发：
        //   TYPE_WINDOW_STATE_CHANGED —— Activity 换了，**事件自带包名**，最可信；
        //   TYPE_WINDOWS_CHANGED     —— 窗口层级变了（开屏广告盖上来就会发），往往比上面
        //                              那条更早，但 event.packageName 常常是空的。
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        /*
         * ★ 包名取哪个：真值优先，事件兜底。
         *
         * 三种来源的可靠性差别很大：
         *  - FgWatch.current（ELF 写的 wm_set_resumed_activity 真值）—— 最权威；
         *  - 焦点窗口 —— 次之，冷启动时会滞后；
         *  - event.packageName —— **TYPE_WINDOWS_CHANGED 上不可信**：窗口层级一变就发，
         *    包里填的可能是刚刚消失的那层窗所属的应用。真机 23:30:50 就撞上：
         *    目标应用已经在前台，却又来一条 pkg=com.android.launcher，它把
         *    lastCornerPkg 清空，400ms 后 watchdog 以为「换了应用」又起了一轮 4 秒空扫，
         *    第二条还在主页上误点了 p=0.993 的「普通版」。
         *
         * 所以：真值（ELF/焦点）本身就是目标应用时，**无条件用它**，忽略事件包名；
         * 否则才采信事件包名（这正是 ELF 不可用 / 慢的时候 a11y 通道顶上的场景）。
         */
        val evPkg = event.packageName?.toString()?.takeIf { it.contains(".") }
        val byEvent =
            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) null else evPkg
        val byFast = fastPkg()
        val pkg = when {
            byFast != null && RuleStore.isCornerTarget(byFast) -> byFast
            byEvent != null -> byEvent
            else -> byFast
        } ?: return

        /*
         * 通知栏 / 权限弹窗 / 输入法抢焦点 ≠ 用户离开了当前应用。
         *
         * 这里原来没有这道闸：拉一下状态栏 → 收到一条 com.android.systemui 的窗口事件
         * → lastPkg 被写成 systemui、lastCornerPkg 被清空 → 收起状态栏后看门狗看到
         * 「这个包还没扫过」又开一轮。表现就是「同包只扫一轮」时灵时不灵，以及
         * 任务在广告中途被 `pause ... now=com.android.systemui` 掐掉。
         */
        if (RuleStore.isSystemOverlay(pkg)) return

        val switched = pkg != lastPkg
        if (switched) {
            lastPkg = pkg
            hitThisWindow = false
            tasks.forEach { it.cancelled = true }
            tasks.clear()
            // 四角任务**不 cancel**：交给触发入口换目标。
            // cancel 掉之后旧任务还要走完当前一帧才让出线程池，用户看到的就是
            // 「换个应用要等几秒才识别」。
            lastCornerAt = 0L
        }

        /*
         * ★ 触发即识别：无障碍事件一到就开扫，**不看还有没有别的通道更快**。
         *
         * 三条通道现在是并发的：
         *   elf      —— root，wm_set_resumed_activity，几十毫秒；
         *   a11y     —— 本函数，事件投递到就到，不再查窗口缓存；
         *   watchdog —— 400ms 轮询，最后兜底。
         * 谁先到谁开，后到的被「已有任务 / 同包已扫过」挡掉，不会重复扫。
         * 用 event.eventTime 当锚点：那是系统产生事件的时刻，比「我现在几点」更接近
         * 真实的切换时刻，计时器也就真的从切换那一刻起算。
         */
        triggerCorner("a11y", pkg, event.eventTime)

        if (!switched) return

        val json = RuleStore.ruleOf(pkg)
        // 判定开关关掉的包两条路都不跑；打开后节点路在这里生成，四角路由
        // triggerCorner 同时拉起，两者互不排斥。
        if (!RuleStore.isAppOn(pkg)) {
            Logs.i(TAG, "switch pkg=$pkg 判定关闭，不生成节点任务")
            return
        }

        val taskType = json.optInt(K.TASK_TYPE, K.TASK_TYPE_KEYWORD)
        val spawned = ArrayList<String>()
        // 只跑 OCR 时节点路整条不下发（spawn 内部也会拦），这里先判一次：
        // 否则日志照样打 spawned=keyword，看日志的人会以为节点任务在跑。
        val allowNode = RuleStore.scanMode != K.SCAN_MODE_OCR
        if (allowNode && taskType and K.TASK_TYPE_KEYWORD != 0) {
            spawn(KeywordTask(this, json), event); spawned += "keyword"
        }
        if (allowNode && taskType and K.TASK_TYPE_TEXTS2 != 0) {
            spawn(Texts2Task(this, json), event); spawned += "texts2"
        }

        if (allowNode) parsePaths(json).forEach { path ->
            spawn(PathTask(this, json, path), event); spawned += "path"
        }

        if (allowNode && (json.optJSONArray(K.POPUP_RULES) != null ||
                json.optBoolean("filter_young_popups", false))
        ) {
            spawn(PopupTask(this, json), event); spawned += "popup"
        }

        // 四角：像素找框 → 小模型确认，与节点任务并行。需要截图通道，
        // 没通道时任务自己会记 miss reason=no-priv。目标应用判定见 RuleStore.isCornerTarget：
        // 它是按坐标点，落到桌面/输入法/设置上就是乱点。
        Logs.i(TAG, "switch pkg=$pkg task_type=$taskType spawned=${spawned.joinToString("+").ifEmpty { "none" }} " +
            "timeout=${json.optInt(K.TIMEOUT, K.DEFAULT_TIMEOUT_MS)}ms")
    }

    /**
     * 三条触发通道的**唯一入口**：ELF / 无障碍事件 / 看门狗。
     *
     * 并发语义（用户 2026-10-30 23:30 定的口径「无障碍和 ocr 并发，触发就立即识别」）：
     *  - 谁先到谁开，**不做跨通道等待**。a11y 事件一到就开扫，不等 ELF；ELF 先到同理。
     *  - 后到的不会重开：已有任务 → [maybeStartCorner] 只会换目标（同包是 no-op）；
     *    没有任务但 `pkg == lastCornerPkg` → 这个包已经扫过一轮，也不再开。
     *  - 出去再回来仍然算新一轮：离开目标应用时 lastCornerPkg 被清空。
     *
     * `@Synchronized`：a11y 在主线程、ELF 在轮询线程，两边可能同一毫秒进来，
     * 不加锁会各建一个任务（`cornerTasks.firstOrNull()` 两边都读到空）。
     */
    @Synchronized
    private fun triggerCorner(src: String, pkg: String, eventAt: Long, verbose: Boolean = true) {
        if (!RuleStore.isCornerTarget(pkg)) {
            /*
             * ★ 看门狗不清「已扫过」状态（2026-10-30 23:51 实测后加的）。
             *
             * 它靠 foregroundPkg()（焦点窗口）判断前台，而冷启动那一两秒焦点窗口
             * 会短暂指回 launcher / 旧应用：真机日志 `trigger src=watchdog
             * pkg=com.android.launcher skip=not-target` 就夹在目标应用已经开扫之后。
             * 如果这时刚好任务点完退出，lastCornerPkg 已被清空 → 又开一轮空扫。
             *
             * 真正的「用户离开了」由 ELF / 无障碍事件报出来（它们带事件时刻，
             * 而且 ELF 读的是 WindowManager 的 mFocusedApp 真值），足够可靠。
             */
            if (src == "watchdog") return
            // 走了（桌面 / 别的应用 / 系统壳）→ 忘掉「上一个扫过的包」，
            // 「出去再回来」才算新的一轮；否则同包回来会被当成没变、一帧都不扫。
            val had = lastCornerPkg != null
            lastCornerPkg = null
            // Log.v 而不是 i：冷启动/切桌面时这条一秒能刷十几条，把同 tag 下
            // 真正有用的 hit / reject 挤到被 logd 限流丢掉（2026-11-02 排查时踩过）。
            if (had || verbose) Logs.v(TAG, "trigger src=$src pkg=$pkg skip=not-target")
            return
        }
        // 识别方式=只跑无障碍时，OCR 路不启动。
        // 放在 isCornerTarget 那个 if 块**之后**：那块负责「离开目标应用时清
        // lastCornerPkg」，提前 return 会让「出去再回来」不再算新一轮。
        if (RuleStore.scanMode == K.SCAN_MODE_NODE) return
        val now = SystemClock.uptimeMillis()
        // 事件时刻只在「合理范围内」才当锚点：ELF 给的是 now、异常重启时可能给 0，
        // 超过 2s 说明这是一条迟到的旧事件，别拿它把计时器往回拨。
        val at = if (eventAt in 1..now && now - eventAt <= 2_000) eventAt else now
        cornerTasks.removeAll { it.finished || it.cancelled }
        val before = cornerTasks.firstOrNull()
        maybeStartCorner(pkg, at, -1)
        val after = cornerTasks.firstOrNull()
        /*
         * ★ 只要这个包已经有任务盯着它，就更新 lastCornerPkg。
         *
         * 原来只有 startCorner 会写它，而 retarget（直接在两个应用间切换目标）不写。
         * 后果：A → B（retarget）→ B 扫完 clicked 退出 → watchdog 发现
         * `pkg != lastCornerPkg`，以为「这是一个还没扫过的包」，又开一轮 4 秒空扫
         * （真机 23:50:29 hit reason=clicked 之后紧跟 idle elapsed=5564ms）。
         * 这正是用户反复提的「设 4 秒实际一直在扫」。
         */
        if (after != null) lastCornerPkg = pkg
        val state = when {
            before != null && before === after -> if (before.target == pkg) "same" else "retarget"
            before == null && after != null -> "start"
            // 这个包已经扫完一轮、任务已退出 → 正常收手态。不是「被拦下」，
            // 不能打 dropped：watchdog 每 400ms 一次，会把它刷成日志瀑布。
            pkg == lastCornerPkg -> "done"
            else -> "dropped"      // 400ms 冷却 / 30s 退避拦下了
        }
        if (state == "done") return
        // lag = 事件产生 → 本函数拿到手。区分「系统没给事件」和「我们处理慢了」，
        // 三条通道的 lag 放一起看就知道该优化谁。
        //
        // state=same 的时间窗节流：TYPE_WINDOWS_CHANGED 在一帧启动里会连发 6 条
        // （真机 23:30:50.880–50.881 六条 1ms 内），每条都打等于把有用的信号埋掉。
        if (state == "same") {
            val noisy = pkg == lastSameLogPkg && now - lastSameLogAt < 500
            lastSameLogPkg = pkg
            lastSameLogAt = now
            if (!noisy) Logs.v(TAG, "trigger src=$src pkg=$pkg state=same lag=${now - at}ms")
        } else if (verbose || state != "same") {
            Logs.i(TAG, "trigger src=$src pkg=$pkg state=$state lag=${now - at}ms")
        }
    }

    /**
     * 最实时的前台包名，给「事件里没带包名」的那条路用。
     *
     * 优先级：ELF 的 events 真值 > 焦点窗口 > rootInActiveWindow。
     * `TYPE_WINDOWS_CHANGED` 的 packageName 恒为空，但它往往比
     * `TYPE_WINDOW_STATE_CHANGED` 早到，靠这个兜住才有意义。
     */
    private fun fastPkg(): String? = FgWatch.current ?: foregroundPkg()

    /**
     * 起/接管四角扫带任务。三道闸：
     *  1. 目标应用（[RuleStore.isCornerTarget]）——挡掉桌面/输入法/系统壳；
     *  2. 已有任务就**换目标**而不是重启（[CornerTask.retargetTo]）—— 重启要等线程池
     *     排空，换目标是下一帧就生效；
     *  3. 只有真的新建任务才看 400ms 冷却。
     */
    private fun maybeStartCorner(pkg: String, eventTime: Long, winId: Int = -1) {
        if (!RuleStore.isCornerTarget(pkg)) return
        cornerTasks.removeAll { it.finished || it.cancelled }
        val running = cornerTasks.firstOrNull()
        if (running != null) {
            running.retargetTo(pkg, eventTime, winId)
            return
        }
        // **同一个包已经扫过一轮就不再开新一轮**，只有包名真的变了才算。
        //
        // 以前这条路（无障碍事件）无条件开新任务，于是「点掉跳过 → 任务 clicked 收工」
        // 之后，系统那条晚 640ms 的 TYPE_WINDOW_STATE_CHANGED 又开了一轮 4 秒空扫
        // （真机 20:49:02：hit reason=clicked 之后紧跟着 begin sinceEvent=640ms）。
        // 用户的口径是「只看前台应用包名，同包内不管它在干嘛」。
        //
        // 离开目标应用时 lastCornerPkg 会被清空（见 [triggerCorner]），
        // 所以「出去再回来」仍然算新一轮。
        if (pkg == lastCornerPkg) return
        startCorner(pkg, eventTime)
    }

    /**
     * ELF 那条路（root 专用）报上来「前台包名变了」。
     *
     * **直接在轮询线程上开工，不 post 到主线程**：这条路存在的唯一意义就是快，
     * 而主线程随时可能被 UI / 动画占着，一次 post 排队就是几十上百毫秒，正好把省下来的
     * 时间又还回去。这里碰到的东西都是线程安全的：RuleStore 走 SharedPreferences，
     * cornerTasks 是 CopyOnWriteArrayList，startCorner 只是往线程池丢个任务。
     */
    private fun onFgChanged(pkg: String) {
        // 用「现在」当事件时间：ELF 的写入延迟已实测 2ms 级（文件 mtime 对系统 events
        // 只差 2ms），拿 now 就足够准，不必再从文件里反推时间戳。
        triggerCorner("elf", pkg, SystemClock.uptimeMillis())
    }

    /** 真正新建一个四角任务。冷却不满足返回 false。 */
    private fun startCorner(pkg: String, eventTime: Long): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastCornerAt < CORNER_COOL_MS) return false
        // 前置条件不满足（无 root / 无截图权限 / 权重没加载）时，任务一进去就退出。
        // 心跳每 2 秒拉一次，没有这条退避就会变成每 2 秒一条同样的 miss 日志轰炸。
        if (now - cornerFailAt < CORNER_FAIL_BACKOFF_MS) return false
        lastCornerAt = now
        lastCornerPkg = pkg
        // 新窗口 = 新的点击配额。上一个窗口如果已经点过，不清掉的话这个任务进来就退。
        hitThisWindow = false
        val corner = CornerTask(this, pkg, eventTime)
        cornerTasks.add(corner)
        cornerPool.execute(corner)
        return true
    }

    /** 任务因为「环境不具备」而没跑起来。心跳据此退避 30 秒再试。 */
    fun noteCornerFail() {
        cornerFailAt = SystemClock.uptimeMillis()
    }

    /**
     * 看门狗：每 2 秒确认「前台是目标应用、四角开关开着、但没有活跃任务」。
     *
     * 为什么非要有它 —— 这是「点掉一次之后再也不识别」的最后一道保险：
     *  1. 同包内不换 Activity 时系统**根本不发** TYPE_WINDOW_STATE_CHANGED，
     *     任务一旦结束就没有任何事件能把它拉起来；
     *  2. 冷启动那条事件实测晚 3.1 秒才到，等它等于每次都慢 3 秒；
     *  3. 任务虽然自带异常重入，但如果线程被系统回收 / 任务列表被清空，
     *     仍然需要外部重新拉起。
     * 代价是每 2 秒取一次 rootInActiveWindow（无障碍内部缓存，不截图），可以忽略。
     */
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                ensureCornerAlive()
            } catch (e: Throwable) {
                Logs.w(TAG, "watchdog err ${e.javaClass.simpleName}: ${e.message}")
            }
            mainHandler.postDelayed(this, WATCHDOG_MS)
        }
    }

    /**
     * 当前前台是谁。
     *
     * 为什么不能只看 `rootInActiveWindow`：它对**自绘/Flutter/WebView 首屏**经常给出
     * 上一个应用的缓存，于是 watchdog 一直以为还在旧包上，永远不 retarget ——
     * 真机实测：前台已经是中国移动云盘，旧任务还在 byfen.market 上扫到第 30 帧。
     * 焦点窗口（windows 里 isFocused 的那层）更实时，两个都试。
     */
    fun foregroundPkg(): String? {
        val byFocus = runCatching {
            windows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused &&
                    it.isActive
            }?.root?.packageName?.toString()
        }.getOrNull()
        if (!byFocus.isNullOrBlank()) return byFocus
        val byRoot = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        // 浮层抢焦点时 rootInActiveWindow 会指到 systemui / 输入法上，而 mFocusedApp 没变。
        // root 档下 ELF 手里就有那条真值，用它把「还在原应用」判回来。
        val resumed = FgWatch.current
        if (byRoot != null && resumed != null && byRoot != resumed &&
            RuleStore.isSystemOverlay(byRoot)
        ) {
            return resumed
        }
        return byRoot ?: resumed
    }

    private fun ensureCornerAlive() {
        cornerTasks.removeAll { it.finished || it.cancelled }
        val pkg = foregroundPkg() ?: return
        // 统一走 triggerCorner：它自己会处理「换目标 / 同包已扫过 / 非目标清状态」。
        // verbose=false：这是 400ms 一次的兜底，state=same 时不打日志，否则刷屏。
        //
        // 注意这里**必须**仍然传 0（= 用 now）：watchdog 没有事件时刻可用，
        // 它是「发现得晚」的那条通道，不能借用别的时间戳假装很快。
        triggerCorner("watchdog", pkg, 0L, verbose = false)
    }

    /** 换会话时重置「本窗口已点过」。CornerTask 在扫描线程里调。 */
    fun resetWindowHit() {
        hitThisWindow = false
    }

    private fun spawn(task: SkipTask, event: AccessibilityEvent) {
        // 识别方式=只跑 OCR 时，节点路整条不下发（首页一改立即生效，不缓存）
        if (RuleStore.scanMode == K.SCAN_MODE_OCR) return
        task.eventTime = event.eventTime
        tasks.add(task)
        pool.execute(task)
    }

    /** DOCC：`[[0&1,1&1,2&1,1&7,7&0]]`，每条是一串 drawingOrder&childCount。 */
    private fun parsePaths(json: org.json.JSONObject): List<List<Pair<Int, Int>>> {
        val out = ArrayList<List<Pair<Int, Int>>>()
        val arr = json.optJSONArray(K.DOCC) ?: return out
        for (i in 0 until arr.length()) {
            val inner = arr.optJSONArray(i) ?: continue
            val path = ArrayList<Pair<Int, Int>>()
            for (j in 0 until inner.length()) {
                val parts = inner.optString(j).split("&")
                val a = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val b = parts.getOrNull(1)?.toIntOrNull() ?: continue
                path.add(Pair(a, b))
            }
            if (path.isNotEmpty()) out.add(path)
        }
        return out
    }

    fun dropTask(task: SkipTask) {
        task.cancelled = true
        tasks.remove(task)
    }

    fun safeRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (e: Exception) {
        null
    }

    /** 往上找最近的可点击祖先；覆盖超过屏幕 80%x80% 的视为整屏容器，拒绝点。 */
    fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        val screen = screenSize()
        while (cur != null) {
            if (cur.isClickable) {
                val r = Rect().also { cur.getBoundsInScreen(it) }
                if (r.width() > screen.width * 0.8f && r.height() > screen.height * 0.8f) return null
                return cur
            }
            cur = try {
                cur.parent
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    fun performNodeClick(node: AccessibilityNodeInfo): Boolean = try {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null && !cur.isClickable) cur = cur.parent
        cur?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    } catch (e: Exception) {
        false
    }

    /**
     * 按坐标点一下。**按压时长必须给够**：1 ms 的 down/up 在广告 SDK 自绘的跳过按钮上
     * 有相当比例被当成抖动丢掉（日志 hit 了、广告不关），见 [K.TAP_MS]。
     */
    fun gestureTap(x: Int, y: Int, durationMs: Long = K.TAP_MS): Boolean {
        val clampedX = x.coerceAtLeast(0)
        val clampedY = y.coerceAtLeast(0)
        // **带 1px 位移**：只发 DOWN/UP 的「纯点」在部分广告 SDK（WebView / 自绘播放器）
        // 里会被当成合成事件丢掉，真机实测「模型 1.000 命中、点了画面完全不动」。
        // 真手指按下去必然有微小抖动，补一个 MOVE 让事件序列变成 DOWN→MOVE→UP，
        // 这几乎不改变落点（1px），但能过掉那些「必须看到移动才算点击」的判定。
        val path = Path().apply {
            moveTo(clampedX.toFloat(), clampedY.toFloat())
            lineTo(clampedX + 1f, clampedY + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** 命中过滚动容器/列表的节点不点，避免把广告位所在列表滑走。 */
    fun isInsideScrollContainer(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isScrollable || cur.collectionInfo != null) return true
            cur = try {
                cur.parent
            } catch (e: Exception) {
                null
            }
        }
        return false
    }

    fun isTooWide(node: AccessibilityNodeInfo): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        return r.width() > screenSize().width * 0.6f
    }

    /** 整屏像素，给节点框大小的判据用（`hit` 日志里的 bigNode）。 */
    fun screenSizePx(): android.graphics.Point = screenSize().let {
        android.graphics.Point(it.width, it.height)
    }

    private fun screenSize(): Size {
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        val dm = android.util.DisplayMetrics()
        return if (wm != null) {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Size(dm.widthPixels, dm.heightPixels)
        } else {
            Size(1080, 2340)
        }
    }

    fun notifySkipped() {
        if (RuleStore.hideToast) return
        mainHandler.post {
            Toast.makeText(this, RuleStore.toastText, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startForegroundNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.fg_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.fg_message))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(
            NOTIF_ID, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(screenOffReceiver) }
        mainHandler.removeCallbacks(watchdog)
        pool.shutdownNow()
        cornerTasks.forEach { it.cancelled = true }
        cornerPool.shutdownNow()
        // 悬浮窗是挂在 WindowManager 上的，服务没了必须撤，否则会跟着进程残留
        DebugOverlay.hide()
        // 监听器跟着服务走；ELF 进程留着无妨（下次 start 会先投再启）
        FgWatch.stop()
        instance = null
    }
}
