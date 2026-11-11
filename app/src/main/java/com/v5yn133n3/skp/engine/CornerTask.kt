package com.v5yn133n3.skp.engine

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.v5yn133n3.skp.K
import com.v5yn133n3.skp.data.RuleStore
import com.v5yn133n3.skp.priv.Capture
import com.v5yn133n3.skp.priv.FgWatch
import com.v5yn133n3.skp.priv.Mode
import com.v5yn133n3.skp.priv.Shell
import kotlin.random.Random
import com.v5yn133n3.skp.Logs

/**
 * 第四类任务：扫带 → 瓦片内找文本行 → 小模型确认 → 点框中心。
 *
 * 口径（真机 1080×2352 定死）：
 *  1. 整帧压一半（540×1176）算亮度，只扫两条横带：上 **0%**–30%、下 65%–85%；
 *     上带从 0 起是必须的——贴顶胶囊实测落在 0.1%–3.3%，5% 起扫整颗在带外。
 *  2. 带内切 160×100 瓦片，相邻重合 48 px（步长 112 / 52，≥ 两个字宽），行主序从左到右，
 *     末尾贴边补一块；
 *  3. 每块瓦片跑一次纯像素提案器，只取排第一的框（实测一帧 50 块瓦片里只有 2–14 块
 *     出得了框，模型总调用数因此压在个位数）；
 *  4. 框按 ×2 换回整帧坐标喂模型（权重是原尺寸真机裁块训的），p ≥ 0.9 就点框中心，
 *     立刻 return —— 逐块扫，任一块命中就算成功，不做跨块投票。
 *
 * 为什么不是「整块瓦片直接压成模型输入」：实测同一帧，紧框 p=1.0000、整块 256×256
 * 压进 96×32 是 p=0.0000——方块到 3:1 的横向压缩会把字形揉死。所以模型只吃**框**，
 * 不吃瓦片；瓦片只用来限定搜索范围。
 */
class CornerTask(
    private val svc: SkipService,
    @Volatile var pkg: String,
    @Volatile private var eventTime: Long,
) : Runnable {

    /**
     * 换目标用的「会话号」。切应用时服务不再 cancel 本任务（见 [retargetTo]），
     * 只把目标包写进 pkgTarget 并把 session +1；扫描线程发现号码变了就重置本会话状态。
     * 用一个计数而不是一堆 @Volatile 字段，是为了让「重置」在扫描线程里原子完成。
     */
    @Volatile
    private var pkgTarget: String = pkg

    /** watchdog 用：本任务当前盯着哪个包。 */
    val target: String get() = pkgTarget

    @Volatile
    private var session = 0

    /** 事件里带的窗口 id：用来分辨「同包内真的换了一层窗（插屏 / 弹窗）」和普通页面切换。 */
    @Volatile
    private var lastWinId = -1

    /**
     * 本会话的识别时长是否已经用完（idle 空转态）。
     *
     * 只有在这个状态下，新窗口事件才允许**重新起算一轮**。这样做是为了同时满足两条相反的要求：
     * 扫描中不能被页面切换打断（否则「每点一下页面就重扫 4 秒」），
     * 而空转态必须能被新弹的广告拉起来（否则「广告播完才开始扫」，见 [retargetTo]）。
     */
    @Volatile
    private var idle = false

    /** 上一次重扫的时刻，防弹窗连发把重扫打成每 200ms 一轮。 */
    @Volatile
    private var lastRearmAt = 0L

    /** 重扫配额：一个 [REARM_WINDOW_MS] 窗口内最多 [REARM_MAX] 次。 */
    @Volatile
    private var rearmWindowAt = 0L

    @Volatile
    private var rearmCount = 0

    /**
     * 前台包**变化发生的那一刻**（不是任务线程醒来那一刻）。
     *
     * 计时器必须从这里起算：触发 → 线程调度 → 截图，中间可能隔几百毫秒；若从
     * 「任务醒来」起算，设定 4 秒实际会扫 4.4 秒以上，广告那边已经过去一截。
     */
    @Volatile
    private var pkgChangedAt = 0L

    @Volatile
    private var chromeTimeouts = 0

    /**
     * 换目标应用，不重启任务。
     *
     * 为什么不再 cancel：切包时系统投递的 TYPE_WINDOW_STATE_CHANGED 实测晚 **3.1 秒**
     * 才到（19:10:10.898 begin，sinceEvent=3102ms），而旧任务设 cancelled 之后还要走完
     * 当前一帧（截图 170ms + 模型，最坏 600ms）才让出线程池，新任务再排队 ——
     * 用户看到的就是「换个 app 要等几秒才识别」。本任务本来就在持续截屏，
     * 画面一换下一帧就是新应用了，换目标比重启快一个数量级。
     *
     * **同包内的窗口事件不再重新计时**（原来这里有个 restartRequest → rewake）：
     * 应用内换页面、弹软键盘/收软键盘都会发 TYPE_WINDOW_STATE_CHANGED，逐条重置的结果是
     * 用户每点一次页面就又开始扫 10 秒（「软件内切换页面/开关输入法也触发重新识别」）。
     * 现在只有**包名变了**才算新会话；同包内最多把 eventTime 更新成这一条，用于日志。
     */
    fun retargetTo(next: String, evtTime: Long, winId: Int = -1) {
        if (next != pkgTarget) {
            pkgTarget = next
            lastWinId = winId
            restartRound(evtTime)
            return
        }
        // **只认包名**（用户 2026-10-30 20:16 定的口径）：
        // 前台包名变了 = 新的一轮识别；同包内不管它在干嘛（换页面 / 弹窗 / 软键盘 /
        // 广告盖上来）一律不重置计时、不重扫。好处是行为可预测 —— 设 4 秒就扫 4 秒，
        // 不会被窗口事件偷偷延期。代价是同包内晚出现的插屏不在这 4 秒里，
        // 要它回来只需把下面两行 tryRearm 恢复。
        //
        // 例外：**息屏后亮屏解锁**由 SkipService 显式调 [restartRound]，同包也重起一轮。
        // 解锁那一下应用常会重新弹一次开屏（摇一摇），走窗口事件这条路会被上面这段挡死。
    }

    /**
     * 重新起算一轮：换目标应用、息屏后亮屏解锁（同一个包）都走这里。
     * 扫描线程在下一拍看到 session 变了就重置本轮状态（含帧数配额）。
     *
     * 计时起点 = **事件产生那一刻**（2026-10-30 23:32 改）。
     *
     * 原来写的是 `SystemClock.uptimeMillis()` —— 那是「服务处理这件事的时刻」，
     * 比真实切换晚了「事件投递 + 主线程排队」。三条通道并发之后这个差值不能忽略：
     * 无障碍事件可能晚到几百毫秒，若从「我们现在才知道」起算，设定 4 秒实际会
     * 扫到切换后 4.3 秒，广告那边已经过去一截。
     * 用 evtTime 之后，timer-reset 的 lag 就是**真实的可观测延迟**，能直接验收
     * 「触发时计时器是否立即重置」。
     *
     * 只在合理范围内采信：ELF / watchdog 给的是 now，异常重启可能给 0，
     * 超过 2s 说明是一条迟到的旧事件，别拿它把计时器往回拨太多。
     */
    fun restartRound(evtTime: Long) {
        eventTime = evtTime
        val now = SystemClock.uptimeMillis()
        pkgChangedAt = if (evtTime in 1..now && now - evtTime <= 2_000) evtTime else now
        rearmCount = 0
        rearmWindowAt = 0L
        session++
    }

    /**
     * 重新起算一轮（空转态专用）。返回是否真的重起了。
     *
     * 两道节流：最近 [REARM_MIN_MS] 内不重复起，[REARM_WINDOW_MS] 窗口内最多 [REARM_MAX] 次。
     * 应用内连翻页面时不至于变成「一直在扫」，而插屏这种低频事件 30 秒 8 次远远够用。
     */
    private fun tryRearm(src: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastRearmAt < REARM_MIN_MS) return false
        if (now - rearmWindowAt > REARM_WINDOW_MS) {
            rearmWindowAt = now
            rearmCount = 0
        }
        if (rearmCount >= REARM_MAX) return false
        rearmCount++
        lastRearmAt = now
        session++
        Logs.i(SkipService.TAG, "rearm kind=CORNER pkg=$pkg src=$src")
        return true
    }

    @Volatile
    var cancelled = false

    /** 已跑完（不管是命中、超时还是早退）。SkipService 靠它把任务从活跃列表里摘掉， */
    /** 否则「已有任务在跑就不起新的」会变成永久封印。 */
    @Volatile
    var finished = false
        private set

    /**
     * 常驻型任务的外壳：**任何异常都不许结束任务**，只重进循环。
     *
     * 为什么：这个任务要一直活到用户离开应用。旧版只有 try/finally，[doRun] 里随便一处
     * 抛出去（截图缓冲尺寸变化、提案器越界、权重一过性失败）任务就 finished，而
     * 同包内**不会再有窗口事件**来重建它 —— 用户看到的就是「点掉一次之后再也不识别」。
     * 现在异常只丢一轮：sleep 半秒重新进 doRun，局部计数（hitCount/clicks）重置，
     * 但截屏继续。外面还有 SkipService 的心跳兜底。
     */
    override fun run() {
        while (!cancelled) {
            try {
                doRun()
                break
            } catch (e: Throwable) {
                Logs.w(SkipService.TAG,
                    "err kind=CORNER pkg=$pkg ${e.javaClass.simpleName}: ${e.message}")
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        finished = true
    }

    private fun doRun() {
        val ctx = svc.applicationContext
        val mode = Mode.of(RuleStore.privMode)
        if (mode == Mode.NONE) {
            Logs.i(SkipService.TAG, "miss kind=CORNER reason=no-priv mode=NONE")
            svc.noteCornerFail()
            CornerStats.report(pkg, 0, 0, 0, 0, 0, 0, 0, false, "no-priv")
            return
        }
        if (!Shell.available(ctx, mode)) {
            Logs.i(SkipService.TAG, "miss kind=CORNER reason=priv-blocked ${Shell.unavailableReason(mode)}")
            svc.noteCornerFail()
            CornerStats.report(pkg, 0, 0, 0, 0, 0, 0, 0, false, "priv-blocked")
            return
        }
        // OCR 内部三档：glyph（字形匹配，默认）/ model（旧 CNN）/ pixel（纯启发性）
        val cornerMode = RuleStore.cornerMode
        val useGlyph = cornerMode == RuleStore.CORNER_MODE_GLYPH
        val useModel = cornerMode == RuleStore.CORNER_MODE_MODEL
        if (useModel && !SkipNet.prepare(ctx)) {
            Logs.i(SkipService.TAG, "miss kind=CORNER reason=model-unavailable ${SkipNet.lastError}")
            svc.noteCornerFail()
            CornerStats.report(pkg, 0, 0, 0, 0, 0, 0, 0, false, "model-unavailable")
            return
        }
        if (useGlyph && !GlyphBank.load(ctx)) {
            Logs.i(SkipService.TAG, "miss kind=CORNER reason=glyph-unavailable ${GlyphBank.lastError}")
            svc.noteCornerFail()
            CornerStats.report(pkg, 0, 0, 0, 0, 0, 0, 0, false, "glyph-unavailable")
            return
        }
        // 任务启动再烫一次：服务连接后可能过了几小时，JIT 热点早凉了。
        // 放在第一帧之前，宁可这里多 60ms，也不要把 1.5s 花在广告正在播的那一秒里。
        SkipNet.warmup(2)
        // **计时起点 = 触发那一刻**，不是线程醒来那一刻。排队 + warmup 这段是真花掉的时间，
        // 从「醒来」起算等于把 4 秒档偷偷变成 4.2 秒（真机 begin 的 sinceEvent 就是 192ms）。
        // 最多往回想 1.5s：任务异常重启时不至于一进来就已经「超时」。
        val wokenAt = SystemClock.uptimeMillis()
        val fromEvent = eventTime in 1..wokenAt && wokenAt - eventTime <= 1_500
        var start = if (fromEvent) eventTime else wokenAt
        // 事件 → 任务真正开跑 的排队延迟（线程池 + 冷却）。和 first 那条一起看，
        // 就能把「起任务慢」和「画面还没出来」分开。
        Logs.i(SkipService.TAG,
            "begin kind=CORNER pkg=$pkg sinceEvent=" +
                (if (eventTime > 0) wokenAt - eventTime else -1L) + "ms " +
                "anchor=${if (fromEvent) "event" else "wake"}")

        // 调试悬浮窗：开关打开但没给「显示在其他应用上层」时只记一条，不打断扫描。
        // 标记画的是整帧坐标，DebugOverlay 内部按 视图宽/帧宽 换算。
        // **每轮重新读**：任务常驻，开关在设置页一关就要立刻停画，
        // 否则 old 值一直生效，用户看到的就是「关了还照画」。
        var canDraw = RuleStore.debugOverlay && DebugOverlay.canDraw(svc)
        if (RuleStore.debugOverlay && !canDraw) {
            Logs.w(SkipService.TAG, "debug overlay on but no SYSTEM_ALERT_WINDOW, marks disabled")
        }
        val marks = ArrayList<DebugOverlay.Mark>(12)

        // 复用缓冲：半分辨率亮度 / 单块瓦片亮度 / 提案器工作区，一帧下来零大对象分配
        var half = IntArray(0)
        val tileLuma = IntArray(BandScan.TW * BandScan.TH)
        val scratch = CornerScan.Scratch(BandScan.TW * BandScan.TH)

        var frames = 0
        // 本轮已截帧数，对着识别次数配额（[K.CORNER_SCAN_FRAMES]）判收手。
        // 与 frames 分开记：frames 是整段任务的累计值，CornerStats 的「ms/帧」拿它当分母，
        // 每轮清零会把累计耗时除以本轮帧数，算出假的平均值。
        var roundFrames = 0
        var capMs = 0L
        var scanMs = 0L
        var modelMs = 0L
        var modelCalls = 0
        var tiles = 0
        var maxFrameMs = 0L
        var lastNap = 0
        var hit = false
        var hitCount = 0
        var lastHitAt = 0L
        var lastScanAt = 0L
        // 上一帧「截图 + 识别」的总耗时：决定下一帧间隔，也决定这一轮点几次。
        var lastFrameMs = 0L
        // 慢帧（>1s）这一轮只允许点一次；快帧按窗口连续点（同位置仍受冷却保护）。
        fun clickLimit(): Int =
            if (lastFrameMs > K.CORNER_FRAME_SLOW_MS) K.CORNER_CLICKS_ONCE else K.CORNER_MAX_CLICKS
        // 已经用 shell 补过刀的坐标（-1 = 没有）。同一颗按钮只补一次。
        var deadRetryX = -1
        var deadRetryY = -1
        var nodeHoldUntil = 0L
        var capFail = 0
        // 被实心块闸门丢掉的候选数，随 miss 日志一起打：它高说明这一屏全是头像/图标，
        // 那种界面本来就不该点，是「闸门在干活」而不是「模型失效」。
        var blocked = 0
        var reason = "timeout"
        // 已点过的位置（x, y, 时刻）。同一位置在 [K.CORNER_CLICK_COOL_MS] 内不重复点，
        // 不同位置照点 —— 广告常是「跳过」点完再盖一层「关闭」，只点一次会卡在第二层。
        val clicks = ArrayList<LongArray>(K.CORNER_MAX_CLICKS)
        // 本轮最高分。未命中时一起打出来：0.95 和 0.05 是两种完全不同的漏检，
        // 前者说明阈值/权重差一口气，后者说明提案器根本没框到那颗字。
        var bestP = 0f
        var bestBox = "-"
        // 画面运动量（0–255）。上一帧的半分辨率亮度留一份，用来算帧间差。
        var prevHalf = IntArray(0)
        var lastMotion = 0
        // 状态栏保护区。网速「240.0 B」+ 5G + 信号格平铺后跟「跳过」极像，
        // 真机实测在录音机界面判出 0.975 并点进了状态栏。只在状态栏可见时启用，
        // 避免把全屏沉浸式广告贴顶的胶囊一起杀掉（byfen 那张落在 y=3–73）。
        // 状态栏 / 标题栏保护带。状态栏可见才有意义：沉浸式全屏广告会把状态栏收起，
        // 这时顶部就是广告画面（byfen 那张贴顶胶囊在 y=3–73），不能挡。
        // 每次换会话重算一次 —— 从普通界面进全屏广告时状态栏会消失。
        var statusGuard = 0
        var chromeBottom = 0
        fun refreshTopGuards() {
            statusGuard = if (statusBarVisible()) statusBarHeight() else 0
            chromeBottom = if (statusGuard > 0) {
                statusGuard + (K.CHROME_GUARD_DP * svc.resources.displayMetrics.density).toInt()
            } else 0
        }
        refreshTopGuards()
        // 本次会话的识别时长（首页滑条 4–10s，默认 4s），高频档按时长比例算
        var timeoutMs = RuleStore.cornerTimeoutMs
        // 本轮帧数配额（首页滑条 1–10，默认 1）。和时长一样每轮起算时现读一次。
        var maxFrames = RuleStore.cornerScanFrames
        // 触发延迟（首页滑条 0–2s，默认 0.5s）：检测到切换后先等一等再截图。
        // 切应用那一瞬截到的多是上一屏残留/占位图。识别次数默认 1 帧时这一等尤其关键：
        // 那一帧是整轮唯一的一帧，截早了拿到占位图就等于整轮白跑。
        var delayMs = RuleStore.cornerStartDelayMs.toLong()
        // 扫描起点 = 触发时刻 + 延迟。识别时长从这里开始算，
        // **不算**延迟 —— 4s 档就是在延迟之后实打实扫 4s。
        var scanStart = start + delayMs
        var localSession = session
        var paused = false
        // 停手（暂停扫描）是被谁触发的。输入法弹收**不算切应用**，回来时不重置计时。
        var pausedBy: String? = null
        // 循环圈数。不能拿 frames 当节拍：停手态和超时态下 frames 不再增长，
        // 奇数帧会永远跳过前台包检查。
        var tick = 0
        // 切应用后立刻起扫（他钉的口径：1s 内要出结果）。和节点路并行，谁先命中谁点，
        // 另一边在下一帧看到 hitThisWindow 就退。
        try {
            while (!cancelled) {
                tick++
                // 识别方式切到「只跑无障碍」：常驻任务下一帧自己退，不用静态引用去 kill
                if (RuleStore.scanMode == K.SCAN_MODE_NODE) {
                    reason = "mode-node"
                    break
                }
                // ⓪ 调试开关现场同步：任务常驻，设置页一关就要立刻撤框、停止再画。
                val drawNow = RuleStore.debugOverlay && DebugOverlay.canDraw(svc)
                if (!drawNow && canDraw) {
                    DebugOverlay.hide()
                }
                canDraw = drawNow
                // ① 会话切换：服务把新目标写进 pkgTarget 并把 session +1。
                //    重置必须放在扫描线程里做，否则和下面的局部变量打架。
                if (session != localSession) {
                    localSession = session
                    pkg = pkgTarget
                    // 计时起点 = 包名变化那一刻（最多往回想 1.5s，防止任务异常重启后
                    // 一进来就已经「超时」）。这就是「触发识别时计时器立即重置」。
                    val nowAt = SystemClock.uptimeMillis()
                    start = if (pkgChangedAt in 1..nowAt && nowAt - pkgChangedAt <= 1_500) {
                        pkgChangedAt
                    } else {
                        nowAt
                    }
                    Logs.i(SkipService.TAG,
                        "timer-reset kind=CORNER pkg=$pkg lag=${nowAt - start}ms")
                    // 换应用 / 解锁重起时重读一次滑条值：用户可能刚在首页改过
                    timeoutMs = RuleStore.cornerTimeoutMs
                    maxFrames = RuleStore.cornerScanFrames
                    delayMs = RuleStore.cornerStartDelayMs.toLong()
                    scanStart = start + delayMs
                    lastMotion = 0
                    prevHalf = IntArray(0)
                    refreshTopGuards()
                    hitCount = 0
                    lastHitAt = 0
                    clicks.clear()
                    hit = false
                    reason = "timeout"
                    paused = false
                    pausedBy = null
                    capFail = 0
                    idle = false
                    // 新一轮 = 新的帧数配额；lastScanAt 清零保证这一轮的首帧不被上一轮的间隔节流
                    roundFrames = 0
                    lastScanAt = 0
                    svc.resetWindowHit()
                    Logs.i(SkipService.TAG,
                        "switch-to kind=CORNER pkg=$pkg session=$session timeout=${timeoutMs}ms " +
                            "delay=${delayMs}ms frames=$maxFrames")
                }
                // ①-b **不再有「同包换窗口 → 重新起算」**。应用内切页面、软键盘弹收都会发
                //      窗口事件，逐条重置会让用户每点一次页面就重扫一轮；现在只认包名变化。
                // ② 跟随前台。停手态每轮都查（切回来要最快接上），扫描态每 2 帧查一次。
                //    切到桌面/输入法/系统壳要**立刻停手**（它是按坐标点，落到桌面就是乱点）；
                //    切到另一个目标应用则直接接管，不再等系统那条晚 3s 的窗口事件。
                if (paused || tick % 2 == 0) {
                    val cur = currentPkg()
                    if (cur != null && cur != pkg) {
                        if (RuleStore.isCornerTarget(cur)) {
                            pkgTarget = cur
                            session++
                            continue
                        } else if (!paused) {
                            paused = true
                            pausedBy = cur
                            Logs.i(SkipService.TAG, "pause kind=CORNER pkg=$pkg now=$cur")
                        }
                    } else if (cur == pkg && paused) {
                        // 回来前先确认这个包**还在 OCR 名单里**：用户可能刚在应用列表把
                        // 它切成「无障碍识别」或两颗都关。这时直接退出任务 —— 留在 paused
                        // 里每 600ms 空转没有意义，watchdog 会在它被切回 OCR 时重建。
                        if (!RuleStore.isCornerTarget(pkg)) {
                            reason = "mode-off"
                            Logs.i(SkipService.TAG, "exit kind=CORNER pkg=$pkg reason=mode-off")
                            break
                        }
                        paused = false
                        // 输入法弹/收不算切应用：回来时**保留原计时**，已经到点就继续停手。
                        // 否则在聊天窗里打一行字、收起键盘，就又开始满屏扫 10 秒。
                        val by = pausedBy
                        val byIme = by != null && RuleStore.isIme(by)
                        pausedBy = null
                        if (!byIme) {
                            // 桌面/其他应用回来 = 新一轮：计时、延迟、帧数配额都重算
                            start = SystemClock.uptimeMillis()
                            timeoutMs = RuleStore.cornerTimeoutMs
                            maxFrames = RuleStore.cornerScanFrames
                            delayMs = RuleStore.cornerStartDelayMs.toLong()
                            scanStart = start + delayMs
                            roundFrames = 0
                            lastScanAt = 0
                            idle = false
                            Logs.i(SkipService.TAG,
                                "resume kind=CORNER pkg=$pkg wasPausedBy=$by frames=$maxFrames")
                        }
                    }
                }
                // 停手态：只盯前台包，不截图。同样小觉切分，切到另一个目标应用时
                // 不用等满 600ms 才接管。
                if (paused) {
                    var slept = 0L
                    while (slept < 600 && !cancelled && session == localSession) {
                        sleep(100)
                        slept += 100
                    }
                    continue
                }
                // 触发延迟闸门：检测到切换后先等 delayMs 再截图/推理。
                // 期间**不截图、不画框**，一段一小觉，切走/取消能立刻响应。
                val waitMs = scanStart - SystemClock.uptimeMillis()
                if (waitMs > 0) {
                    sleep(if (waitMs > 120) 100 else waitMs)
                    continue
                }
                // 节点路已经点过了、而自己一次还没点过 → 停手 1.5 秒（给节点把画面点掉），
                // 然后放行继续扫。**不能退出任务**：退出后 10 分钟内不会再有 corner，
                // 同包里再弹的插屏全部漏掉 —— 这正是用户说的「跳过只能按一次」。
                // 节点路刚点过 → 让位 2 秒。它点的很可能就是同一颗「跳过」：旧画面还会
                // 停留一瞬，四角能在这张图上找到同一颗按钮的另一个切框（位置差几十像素，
                // 同位置冷却拦不住），不让位就是补第二发 = 双点。
                // **只跑 OCR 时整段跳过**：节点路根本没下发，nodeHitAt 是上一轮 both 模式
                // 留下的残值。真机 2026-11-02 就是这么卡住的 —— 切到 OCR 后每次触发
                // 前 2 秒全被这条 sleep(300) 空转吃掉，4 秒窗口只剩一半，看起来「不工作」。
                if (RuleStore.scanMode != K.SCAN_MODE_OCR && svc.nodeHitRecently()) {
                    if (nodeHoldUntil == 0L) {
                        nodeHoldUntil = SystemClock.uptimeMillis()
                        Logs.i(SkipService.TAG, "hold kind=CORNER pkg=$pkg reason=node-hit")
                    }
                    sleep(300)
                    continue
                } else if (nodeHoldUntil != 0L) {
                    nodeHoldUntil = 0L
                }
                // 识别时长从**扫描起点**（触发时刻 + 延迟）算，延迟不计入窗口。
                // **严格等于设定值**：到点就停手，不续期、不偷偷延长。上一版加过
                // 12s 慢扫尾段，用户设 6s 实际扫 18s —— 调什么值都一样，等于设定失效。
                val elapsedNow = SystemClock.uptimeMillis() - scanStart
                val clickedEnough = hitCount >= clickLimit()
                // 识别次数用完：默认 1 帧时这里就是整轮的收手点，时长闸基本碰不到。
                // 收手后留在空转态（不退出任务），亮屏解锁 / 换应用才能立刻再给一轮。
                val framesEnough = roundFrames >= maxFrames
                if (elapsedNow > timeoutMs || clickedEnough || framesEnough) {
                    if (reason != "idle-timeout") {
                        reason = "idle-timeout"
                        Logs.i(SkipService.TAG,
                            "idle kind=CORNER pkg=$pkg frames=$frames hits=$hitCount " +
                                "elapsed=${elapsedNow}ms limit=${timeoutMs}ms " +
                                "shots=$roundFrames/$maxFrames " +
                                "best=${String.format("%.2f", GlyphScan.lastBest)} " +
                                "why=${when {
                                    clickedEnough -> "clicked"
                                    framesEnough -> "frames"
                                    else -> "timeout"
                                }}")
                        // 最后一帧的框必须撤掉：留着会让人以为「还在扫」
                        if (canDraw) DebugOverlay.hide()
                    }
                    // 空转态：此后只认「换了一层新窗口」的事件（见 retargetTo）。
                    // 这里**不再按窗口集合自行重起**：广告关掉之后窗口集合同样会变，
                    // 重扫会把普通页面当成新场景，去点页面里长得像「跳过」的元素 ——
                    // 真机实测这就是「直接点广告」。晚出现的按钮交给上面的尾段扫描。
                    idle = true
                    var slept = 0L
                    while (slept < 800 && !cancelled && session == localSession) {
                        sleep(100)
                        slept += 100
                    }
                    continue
                }
                // 点过之后长时间没再命中 → 这一屏已经处理完，降频等下一次弹窗。
                // 从没点过的不走这条：用户要的正是「逛半分钟才弹的插屏」也能抓。
                if (hitCount > 0 && SystemClock.uptimeMillis() - lastHitAt > K.CORNER_IDLE_EXIT_MS) {
                    hitCount = 0
                    lastHitAt = 0
                    reason = "done"
                    idle = true
                    sleep(800)
                    continue
                }
                val frameStart = SystemClock.uptimeMillis()
                val tCap = SystemClock.uptimeMillis()
                // **900ms 超时**（默认 2500）：screencap 偶尔会卡到 2.5s，一帧就吃掉
                // 大半个设定窗口 —— 真机实测「设 4000ms、实际扫 6744ms」就是同一帧里
                // 卡了两次 2.5s 超时。扫带要的是当前画面，慢截图没有价值，宁可丢帧。
                val frame = Capture.grab(mode, 900)
                val cap1 = SystemClock.uptimeMillis() - tCap
                capMs += cap1
                if (frame != null && SystemClock.uptimeMillis() - scanStart > timeoutMs) {
                    // 截图本身把设定时长吃完了：这一帧不再分析，直接收工。
                    // 不加这道，「设 4s」会被一帧慢截图拖成 6.7s。
                    reason = "timeout"
                    if (canDraw) DebugOverlay.hide()
                    Logs.i(SkipService.TAG,
                        "idle kind=CORNER pkg=$pkg frames=$frames hits=$hitCount " +
                            "elapsed=${SystemClock.uptimeMillis() - scanStart}ms " +
                            "limit=${timeoutMs}ms why=slow-cap")
                    break
                }
                if (frame == null) {
                    // 单次截图失败不算数：切应用那一瞬 screencap 经常被
                    // `InterruptedIOException: read interrupted by close() on another thread`
                    // 打断（进程被杀 / 流被 watchdog 关）。旧版是「启动超过 3s 就 return」，
                    // 而任务现在常驻，随便一次瞬时失败就把整个任务打死，之后只能等
                    // 系统那条晚 3s 的窗口事件重建 —— 用户看到的又是「等几秒」。
                    capFail++
                    if (capFail >= 5) {
                        // 连续 5 次都截不到 → 退回长睡重试，**不退任务**。
                        // 任务一退，同包里再弹的东西就没人管了。
                        Logs.w(SkipService.TAG, "capture stalled pkg=$pkg ${Capture.lastError}")
                        capFail = 0
                        sleep(5000)
                        continue
                    }
                    sleep(200)
                    continue
                }
                capFail = 0
                frames++
                roundFrames++
                if (canDraw) marks.clear()

                // 运行间隔由首页滑条决定（800–2000ms），首帧不节流。
                // 放在两条路分叉之前，glyph 分支的 continue 也绕不过它。
                if (frames > 1) {
                    val gap = RuleStore.cornerScanGapMs - (SystemClock.uptimeMillis() - lastScanAt)
                    if (gap > 0) sleep(gap.toLong())
                }
                lastScanAt = SystemClock.uptimeMillis()

                val tScan = SystemClock.uptimeMillis()
                var frameModelMs = 0L

                /*
                 * glyph 模式：整块扫描直接替代「提案器 + 实心块闸门 + CNN」。
                 *
                 * 为什么不复用提案器：它按 160x100 瓦片找连通域，细白字被光带/高亮
                 * 黏住时一个框都出不来（01-24-33 那张真机就是这么漏的）。字形匹配
                 * 自己在四角找「跳+过」，不依赖上游给框。
                 *
                 * 代价：每帧一次全角扫描（估算 30-80ms），1s 节流下可以忽略。
                 */
                if (useGlyph) {
                    val gh = GlyphScan.scan(ctx, frame)
                    // 本帧成本 = 截图 + 识别。下一帧的间隔和本轮点击上限都由它决定，
                    // 所以必须在下面任何一个 continue 之前落地。
                    lastFrameMs = cap1 + (SystemClock.uptimeMillis() - tScan)
                    scanMs += SystemClock.uptimeMillis() - tScan
                    if (gh == null) {
                        continue
                    }
                    val gw = gh.x1 - gh.x0
                    val ghh = gh.y1 - gh.y0
                    if (recentlyClicked(clicks, gh.x0, gh.y0, gw, ghh)) {
                        /*
                         * 这颗按钮刚点过却还在画面上 → 上一发没被应用接受。
                         * 无障碍注入（dispatchGesture）在部分广告 SDK / WebView 上会被吞，
                         * 这时用 InputManager 的 `input tap` 同坐标补一发（要 Root 或 Shizuku）。
                         * 每颗按钮最多补一次：补完还不动就是误报或不可点区域，继续补只会
                         * 打到别的热区上。
                         */
                        if (mode != Mode.NONE && (gh.cx != deadRetryX || gh.cy != deadRetryY)) {
                            val r = Shell.run("input tap ${gh.cx} ${gh.cy}", mode, 1_500)
                            deadRetryX = gh.cx
                            deadRetryY = gh.cy
                            Logs.w(
                                SkipService.TAG,
                                "tapretry kind=CORNER pkg=$pkg at=${gh.cx},${gh.cy} " +
                                    "shell=${if (r.ok) "ok" else "fail:${r.out.take(40)}"}",
                            )
                        }
                        continue
                    }
                    deadRetryX = -1
                    deadRetryY = -1
                    // 同上：OCR 模式没有节点路可让，这里直接判等会白退一次任务
                    if (RuleStore.scanMode != K.SCAN_MODE_OCR && svc.nodeHitRecently()) break
                    val gGate = CornerScan.gate(frame.width, frame.height, gh.cx, gh.cy)
                    if (!gGate.pass(gw, ghh)) {
                        // 必须打日志：字形命中了却被尺寸闸门丢掉，是这个项目最容易
                        // 误判成「识别不工作」的一环（2026-11-02 就是这样，命中 70×33、
                        // 闸门要求 90×26，4 秒窗口每帧都命中、每帧都丢，hits 恒为 0）。
                        Logs.i(
                            SkipService.TAG,
                            "reject kind=CORNER pkg=$pkg why=small-box zone=${gGate.zone} " +
                                "need=${gGate.minW}x${gGate.minH} got=${gw}x$ghh " +
                                "box=${gh.x0},${gh.y0},${gh.x1},${gh.y1} " +
                                "combo=${String.format("%.3f", gh.combo)}",
                        )
                        if (canDraw) {
                            marks.add(
                                DebugOverlay.Mark(
                                    gh.x0, gh.y0, gw, ghh,
                                    "字形 小框丢弃${gGate.zone} ${gw}x$ghh " +
                                        "combo=${String.format("%.2f", gh.combo)}",
                                    false, dim = true,
                                ),
                            )
                        }
                        continue
                    }
                    val curFg = FgWatch.current
                    if (curFg != null && curFg != pkg) {
                        Logs.i(
                            SkipService.TAG,
                            "abort kind=CORNER pkg=$pkg now=$curFg " +
                                "at=${gh.cx},${gh.cy} reason=fg-changed",
                        )
                        break
                    }
                    // 走到这里就是「判定全过、要按下去」——这条日志必须存在，
                    // 否则「有 hit 没点」和「压根没命中」在日志上长得一样。
                    Logs.i(
                        SkipService.TAG,
                        "pick kind=CORNER pkg=$pkg box=${gh.x0},${gh.y0},${gh.x1},${gh.y1} " +
                            "size=${gw}x$ghh zone=${gGate.zone} at=${gh.cx},${gh.cy} " +
                            "combo=${String.format("%.3f", gh.combo)} fg=${curFg ?: "null"}",
                    )
                    lastNap = nap()
                    val tapped = svc.gestureTap(gh.cx, gh.cy)
                    clicks.add(
                        longArrayOf(
                            gh.x0.toLong(), gh.y0.toLong(),
                            gh.x1.toLong(), gh.y1.toLong(),
                            SystemClock.uptimeMillis(),
                        ),
                    )
                    hitCount++
                    hit = true
                    lastHitAt = SystemClock.uptimeMillis()
                    svc.markHit()
                    Logs.i(
                        SkipService.TAG,
                        "hit kind=CORNER pkg=$pkg way=${K.CLICK_WAY_GESTURE} " +
                            "glyph cell=${gh.cell} box=${gh.x0},${gh.y0},${gh.x1},${gh.y1} " +
                            "combo=${String.format("%.3f", gh.combo)} " +
                            "跳=${String.format("%.3f", gh.tiao)} " +
                            "过=${String.format("%.3f", gh.guo)} pass=${gh.pass} " +
                            "tap=${if (tapped) "gesture" else "inject-fail"} " +
                            "n=$hitCount frame=${lastFrameMs}ms frames=$frames",
                    )
                    if (hitCount >= clickLimit()) {
                        reason = "clicked"
                        break
                    }
                    // 还在窗口内：继续认、继续点（同位置受冷却保护）。
                    continue
                }
                val halfW = frame.width / BandScan.SCALE
                val halfH = frame.height / BandScan.SCALE
                if (half.size < halfW * halfH) half = IntArray(halfW * halfH)
                Capture.halveLumaInto(frame, half)
                // 帧间亮度差（每 16 个像素抽 1 个求平均）：判「画面还在动」用。
                // 静态界面 0–1，视频/滚动 ≥8；只有它超过阈值才允许把扫描截止时间往后推。
                val npx = halfW * halfH
                if (prevHalf.size != npx) {
                    prevHalf = IntArray(npx)
                    lastMotion = 0
                } else if (npx > 0) {
                    var acc = 0L
                    var cnt = 0
                    var i = 0
                    while (i < npx) {
                        val d = half[i] - prevHalf[i]
                        acc += if (d >= 0) d else -d
                        cnt++
                        i += 16
                    }
                    lastMotion = (acc / cnt.coerceAtLeast(1)).toInt()
                }
                System.arraycopy(half, 0, prevHalf, 0, npx)
                val hvMs = SystemClock.uptimeMillis() - tScan

                // 两段式：热区（右上 6 块）每帧必扫，全带按节奏补。
                // 为什么不是「每帧扫全带」：45 块瓦片里 13 块出框、每块一次推理 ≈32ms，
                // 一帧 610ms 里 68% 花在模型上（截图另占 170ms）。倒计时只有 1–2s，
                // 这个帧率等于把窗口全浪费在左半边和下带。热区 6 块通常只有 1–3 块出框，
                // 帧时间压到 ~250ms，同样的倒计时能扫 4 倍帧数。
                // 快扫只在 timeout 的前 60% 内：再往后是尾段，按低频档跑（帧间隔 1.2s、
                // 热区 8 块 + 每 5 帧一次全带），够抓到晚出现的「跳过」，又不至于满速烧 16 秒。
                val fast = elapsedNow < timeoutMs * K.CORNER_FAST_RATIO_NUM /
                    K.CORNER_FAST_RATIO_DEN
                val fullRound = frames % (if (fast) K.CORNER_FULL_EVERY_FAST else K.CORNER_FULL_EVERY_SLOW) == 0
                val plan = ArrayList<BandScan.Tile>(8)
                val hot = BandScan.hotTiles(halfW, halfH)
                plan += hot
                if (fullRound) plan += BandScan.tiles(halfW, halfH, skipHot = true)
                val plMs = SystemClock.uptimeMillis() - tScan - hvMs
                var prMs = 0L
                var chMs = 0L
                // 首帧 1.5s 到底花在哪 —— 用户报「启动延时超过 3s」时靠这三个数定位，
                // 别再用猜的：copy=瓦片拷贝，blob=实心块闸门，其余全算 loop。
                var copyMs = 0L
                var blobMs = 0L

                loop@ for (t in plan) {
                    if (cancelled) {
                        reason = "cancelled"
                        return
                    }
                    tiles++
                    val tCopy = SystemClock.uptimeMillis()
                    BandScan.copyTile(half, halfW, t, BandScan.TW, BandScan.TH, tileLuma)
                    copyMs += SystemClock.uptimeMillis() - tCopy
                    // 每块瓦片只推一个框：负样本帧一帧 6–7 次推理就够判否，
                    // 放宽到 3 个只是把非跳过帧的耗时翻三倍。
                    val tPro = SystemClock.uptimeMillis()
                    val tileBoxes = ArrayList<CornerScan.Box>(2)
                    CornerScan.candidates(
                        tileLuma, BandScan.TW, BandScan.TH, CornerScan.TILE, scratch,
                        allowSquare = BandScan.allowsSquare(t, halfW),
                    ).firstOrNull()?.let { tileBoxes += it }
                    /*
                     * 热区第二路（2026-10-31）：中国移动开屏那两张，白字被大面积高亮光效
                     * 黏成一块贴边巨块，连通域七档阈值全 0 候选 —— 框都出不来，模型再好也没用。
                     * top-hat 只看 7px 内的局部对比，光带被抹平、白字留下，两张图都稳定给出
                     * 92×36 / 102×32，p=1.000。
                     *
                     * 只在热区补（8 块瓦片、每块 <1ms），整带不补：那一路对壁纸纹理、
                     * 图标、封面字都可能出框，热区以外放开等于把推理数翻倍。
                     */
                    if (BandScan.isHot(t, halfW, halfH)) {
                        CornerScan.topHatCandidates(tileLuma, BandScan.TW, BandScan.TH, scratch)
                            .firstOrNull()?.let { b ->
                                // 与连通域那条框高度重合就不重复推（同一颗按钮，白花一次推理）
                                val dup = tileBoxes.any { o ->
                                    b.cx >= o.x && b.cx <= o.x + o.w &&
                                        b.cy >= o.y && b.cy <= o.y + o.h
                                }
                                if (!dup) tileBoxes += b
                            }
                    }
                    prMs += SystemClock.uptimeMillis() - tPro
                    if (tileBoxes.isEmpty()) continue

                    tile@ for (box in tileBoxes) {
                    // 换回整帧坐标：半分辨率找到的框 ×2
                    val bx = (t.x + box.x) * BandScan.SCALE
                    val by = (t.y + box.y) * BandScan.SCALE
                    val bw = box.w * BandScan.SCALE
                    val bh = box.h * BandScan.SCALE
                    // 整颗落在状态栏里 → 必然是一排图标/数字，不是按钮
                    if (statusGuard > 0 && by + bh <= statusGuard) {
                        // 灰框：提案器框到了，但被状态栏保护区丢掉。区分「没框到」和「框了没要」
                        if (canDraw) {
                            marks.add(
                                DebugOverlay.Mark(bx, by, bw, bh, "$t 状态栏丢弃", false, dim = true),
                            )
                        }
                        continue@tile
                    }
                    // 标题栏保护带：状态栏可见时，状态栏以下 56dp 是应用自己的标题栏
                    // （返回箭头 + 标题 + 右侧菜单）。真机误报：微信标题里的「(427)」被模型
                    // 判 0.9x 并点了下去，它就在状态栏下面 20–50 px。这一带改用无障碍文本判：
                    // 框中心落在一个**有文字且不像跳过**的节点上 → 丢；自绘跳过按钮不在
                    // 无障碍树里（返回 null）→ 放行。
                    if (chromeBottom > 0) {
                        val ccx = bx + bw / 2
                        val ccy = by + bh / 2
                        if (ccy <= chromeBottom) {
                            val tCh = SystemClock.uptimeMillis()
                            val label = chromeLabelAtTimed(ccx, ccy)
                            chMs += SystemClock.uptimeMillis() - tCh
                            if (label != null) {
                                if (canDraw) {
                                    marks.add(
                                        DebugOverlay.Mark(
                                            bx, by, bw, bh,
                                            "$t 标题栏丢弃「${label.take(6)}」", false, dim = true,
                                        ),
                                    )
                                }
                                continue@tile
                            }
                        }
                    }
                    // 实心块闸门：**在模型之前**判，省一次推理。
                    // 模型只吃框、分不清「文字行」和「圆形头像被切出来的一截」——
                    // 真机实测头像 108×46 判 0.987 并点了下去。判据见 BlobGate。
                    // 放在模型前面是安全的：真「跳过」的墨迹占比只有 0.31–0.45，
                    // 而这条闸门要求 ≥0.55 才可能成立，碰不到正常按钮。
                    val tBlob = SystemClock.uptimeMillis()
                    val solid = BlobGate.isSolidBlob(frame, bx, by, bw, bh)
                    blobMs += SystemClock.uptimeMillis() - tBlob
                    if (solid) {
                        blocked++
                        if (canDraw) {
                            marks.add(
                                DebugOverlay.Mark(bx, by, bw, bh, "$t 实心块丢弃", false, dim = true),
                            )
                        }
                        continue@tile
                    }
                    var p = 1f
                    if (useModel) {
                        val t0 = SystemClock.uptimeMillis()
                        // 松框鲁棒打分：原框到阈值才补一次内缩 8% 复测，压掉「两个亮斑」类假阳性。
                        // 近方形框额外允许「低分也外扩复测」：圆钮的白倒计时环贴着框边被切掉时
                        // 分数会假性归零（实测 88×96 → 0.000，外扩 8% → 1.000），
                        // 而 0.000 会撞上 scoreRobust 的早退闸门，不开口子就永远救不回来。
                        val nearSquare = bw * 100 >= bh * 70 && bw * 100 <= bh * 145
                        p = SkipNet.scoreRobust(frame, bx, by, bw, bh, nearSquare)
                        val dModel = SystemClock.uptimeMillis() - t0
                        modelMs += dModel
                        frameModelMs += dModel
                        modelCalls++
                        if (p > bestP) {
                            bestP = p
                            bestBox = "${bw}x${bh}@${bx + bw / 2},${by + bh / 2}"
                        }
                        // 低分框也画：青框说明「提案器给了框、模型不认」，比只有命中框有用得多
                        if (canDraw) {
                            marks.add(
                                DebugOverlay.Mark(
                                    bx, by, bw, bh,
                                    "$t ${bw}x$bh p=${String.format("%.2f", p)}",
                                    p >= SkipNet.THRESHOLD,
                                ),
                            )
                        }
                        if (p < SkipNet.THRESHOLD) continue@tile
                    } else if (canDraw) {
                        // 纯像素档：没有分，画成红框（它接下来就会点这里）
                        marks.add(DebugOverlay.Mark(bx, by, bw, bh, "$t ${bw}x$bh px", true))
                    }
                    val x = bx + bw / 2
                    val y = by + bh / 2
                    /*
                     * 小框护栏：判据全在 CornerScan.Gate（那张表就是这个函数的说明书），
                     * 这里只负责执行。放在 CornerScan 里是为了让图片测试页用同一套口径 ——
                     * 测试页说「会点」而真机不点，比没有测试页更浪费时间。
                     */
                    val g = CornerScan.gate(frame.width, frame.height, bx + bw / 2, by + bh / 2)
                    if (!g.pass(bw, bh)) {
                        if (canDraw) {
                            marks.add(
                                DebugOverlay.Mark(
                                    bx, by, bw, bh,
                                    "$t 小框丢弃${g.zone} ${bw}x$bh 需${g.minW}x${g.minH}" +
                                        " p=${String.format("%.2f", p)}",
                                    false, dim = true,
                                ),
                            )
                        }
                        continue@tile
                    }
                    // 点前再确认一次前台：这份 frame 是几百毫秒前截的，广告可能已经
                    // 自己跳走了，此刻点下去就落在新页面上（r4 点进快搜就是这么来的）。
                    val cur = FgWatch.current
                    if (cur != null && cur != pkg) {
                        Logs.i(SkipService.TAG,
                            "abort kind=CORNER pkg=$pkg now=$cur at=$x,$y reason=fg-changed")
                        break@loop
                    }
                    // 同一个按钮刚点过 → 冷却期内不再点。否则「点了没生效」的广告
                    // 会被每帧补一刀，变成连点；而位置不同的下一层照点。
                    if (recentlyClicked(clicks, bx, by, bw, bh)) continue@tile
                    // 节点路刚在同一张画面上点过 → 整帧作废，让位。它点的可能就是这颗。
                    if (svc.nodeHitRecently()) break@loop
                    lastNap = nap()
                    val tTap = SystemClock.uptimeMillis()
                    // 点前的画面快照（独立数组，见 tapRegion 的注释：grab 复用缓冲，
                    // 拿旧 Frame 去比等于自己比自己，恒判 dead）
                    val before = tapRegion(frame, x, y)
                    if (!svc.gestureTap(x, y)) continue@tile
                    val tapMs = SystemClock.uptimeMillis() - tTap
                    // 点击有效性校验：**注入成功 ≠ 应用接受**。中国移动的插屏实测
                    // 框到了、模型 1.000、这里有 hit，广告照放 —— 无障碍注入的手势
                    // 被广告 SDK 吞了。这里复查按钮周边像素，没动就用 shell 的
                    // `input tap`（InputManager 注入，带完整 DOWN/MOVE/UP）补发一次，
                    // **同一坐标、只补一次**，绝不在画面已变之后再补刀。
                    val how = verifyTap(before, x, y, mode)
                    // tap=dead 表示三次检查画面都没动：这一下没被应用接受（注入被吞、
                    // 落在不可点区域、或框本来就是误报）。**不占点击配额、清掉冷却**，
                    // 让它在一个设定窗口内还能再试，而不是点一次就整段放弃。
                    val accepted = how != "dead"
                    // 2026-10-31 用户口径：**一次触发只允许点一次**，点空也算。
                    // 原来 dead 不占配额，于是「点空 → 350ms 后又扫下一帧 → 点下一颗候选」
                    // 会在广告页上连点几处，用户看到的就是「点到别的」。广告页上除了跳过
                    // 按钮全是可点热区，补第二下必中广告。
                    hitCount++
                    hit = true
                    lastHitAt = SystemClock.uptimeMillis()
                    svc.markHit()
                    if (accepted) {
                        clicks.add(
                            longArrayOf(
                                bx.toLong(), by.toLong(),
                                (bx + bw).toLong(), (by + bh).toLong(),
                                SystemClock.uptimeMillis(),
                            ),
                        )
                    }
                    // 点空（画面三次检查都没动）不写冷却记录，但歇 350ms 再继续，
                    // 免得对着同一颗按钮按帧连点。倒计时里的「跳过」就是这种情况：
                    // 广告 SDK 在倒计时没走完时忽略点击，过一会儿才认。
                    else sleep(350)
                    val dt = if (eventTime > 0) SystemClock.uptimeMillis() - eventTime else -1L
                    // 分段计时：cap=截图 scan=提案 model=推理 tap=手势注入。
                    // 用户报「广告播了 2s 才点」时要能一眼看出时间花在哪一段，
                    // 否则只能猜是「通知慢」还是「延迟大」。
                    Logs.i(SkipService.TAG,
                        "hit kind=CORNER pkg=$pkg way=${K.CLICK_WAY_GESTURE} dt=${dt}ms " +
                            "nap=${lastNap}ms n=$hitCount tile=$t box=${bw}x${bh}@$x,$y " +
                            "frames=$frames p=${if (useModel) String.format("%.3f", p) else "px"} " +
                            "tap=$how seg=cap${cap1}/scan${SystemClock.uptimeMillis() - tScan - frameModelMs}" +
                            "/model${frameModelMs}/tap${tapMs} delay=${delayMs} " +
                            "half${hvMs}/plan${plMs}/propose${prMs}/chrome${chMs}/to$chromeTimeouts " +
                            "mode=${if (useGlyph) "glyph" else if (useModel) "model" else "pixel"}")
                    // 命中框立刻显示（点完才画就晚了 1.2s），留 1.5s 方便肉眼核对。
                    if (canDraw) {
                        DebugOverlay.show(
                            svc, frame.width, frame.height, ArrayList(marks),
                            "命中 #$hitCount p=${String.format("%.2f", p)} $t",
                        )
                        DebugOverlay.clearSoon(1500)
                    }
                    // 本帧只点一次：点完画面就变了，余下的瓦片都是旧帧坐标，继续打分会误点。
                    // 等待由 verifyTap 内部做（它要复查画面才判得出有没有生效），这里不再
                    // 额外 sleep 1.2s —— 那段 sleep 以前是把「设定 4s」拖成「实际 7.5s」的
                    // 一半原因（另一半是下面那段 sleep(3000)）。
                    break@loop
                    }
                }
                // 一帧画一次：帧内每块瓦片都刷会白刷好几次，600ms 一帧已经够看
                if (canDraw) {
                    DebugOverlay.show(
                        svc, frame.width, frame.height, ArrayList(marks),
                        "调试 帧$frames 块$tiles 候选${marks.size}",
                    )
                }
                // scanMs 只记压半分辨率 + 提案器，模型时间单独记
                scanMs += SystemClock.uptimeMillis() - tScan - frameModelMs

                val frameMs = SystemClock.uptimeMillis() - frameStart
                lastFrameMs = frameMs
                if (frameMs > maxFrameMs) maxFrameMs = frameMs
                // 每 5 帧一条心跳：把「已经扫了多久 / 截止在哪 / 画面动没动 / 这帧多慢」
                // 一次打全。用户报「广告快播完才动」时，看这条就知道是收工了还是没框到。
                if (frames % 5 == 0) {
                    Logs.i(SkipService.TAG,
                        "beat kind=CORNER pkg=$pkg f=$frames " +
                            "elapsed=${SystemClock.uptimeMillis() - scanStart}ms " +
                            "stop=${timeoutMs}ms motion=$lastMotion " +
                            "frame=${frameMs}ms calls=$modelCalls")
                }
                CornerStats.live(pkg, frames, tiles, capMs, scanMs, modelMs, modelCalls)
                // 首帧单独打一条：用户报的「不同应用首次识别速度差几秒」要区分是
                // 起任务慢、截图慢，还是画面还没出来（首帧就截到，但没候选）。
                if (frames == 1) {
                    val sinceEvent = if (eventTime > 0) SystemClock.uptimeMillis() - eventTime else -1L
                    Logs.i(SkipService.TAG,
                        "first kind=CORNER pkg=$pkg sinceEvent=${sinceEvent}ms " +
                            "frame=${frameMs}ms cap=${capMs}ms hot=${hot.size} tiles=${plan.size} " +
                            "loop=${SystemClock.uptimeMillis() - tScan - hvMs - plMs}ms " +
                            "copy=$copyMs blob=$blobMs propose=$prMs chrome=$chMs " +
                            "delay=${delayMs}ms")
                }
                // 高频档不额外等待（截图 170ms 本身就是限速）；低频档 1.2s 一帧。
                if (!fast) sleep(K.CORNER_SLOW_GAP_MS)
                if (hitCount >= clickLimit()) {
                    // **点生效了就立刻收工**：不歇、不清零、不续扫。以前这里是
                    // sleep(3000) + 清零重来，等于「设 4s 实际扫 7.5s」，而且清零让
                    // idle 日志永远 hits=0，看起来像「压根没点过」。下一次弹窗由窗口
                    // 事件重新起任务，不需要在这儿赖着。
                    reason = "clicked"
                    break
                }
            }
        } finally {
            // 点过的留 1.5s 方便肉眼核对；没命中的赶紧撤，否则最后一帧的框会一直挂着。
            if (canDraw) DebugOverlay.clearSoon(if (hit) 1500 else 600)
            CornerStats.report(
                pkg, frames, capMs, scanMs, modelMs, modelCalls, maxFrameMs,
                SystemClock.uptimeMillis() - start, hit, reason, tiles,
            )
        }
        Logs.i(SkipService.TAG,
            "miss kind=CORNER pkg=$pkg frames=$frames tiles=$tiles blocked=$blocked " +
                "elapsed=${SystemClock.uptimeMillis() - start}ms " +
                "best=${String.format("%.3f", bestP)}@$bestBox")
    }

    /**
     * 点击有效性校验 + 兜底补发，返回「这一下是怎么落地的」写进日志。
     *
     * 判定逻辑（时间轴单位 ms）：
     * ```
     *  0        注入无障碍手势（dispatchGesture）
     *  260      截一帧，按钮周边亮度差 ≥3 → 记 "gesture"，收工
     *  380      再截一帧，和点击前那帧比，变了 → 记 "gesture-slow"（只是慢，不补刀）
     *  380+     两次都没动 → shell `input tap` 同坐标补发一次
     *  +320     再截一帧，变了 → "gesture+shell"；还没变 → "dead"
     * ```
     * 「慢生效不补刀」这一层是必须的：广告关闭有 0.4–1.2s 的收尾，短窗口内画面
     * 还是旧的，直接补发就会打到刚跳过去的新页面上 —— 那正是「点了跳进广告」。
     */
    private fun verifyTap(before: IntArray, x: Int, y: Int, mode: Mode): String {
        sleep(K.CORNER_TAP_VERIFY_MS)
        val f1 = Capture.grab(mode, 700) ?: return "unknown"
        if (tapDiff(before, f1, x, y) >= K.CORNER_TAP_VERIFY_DIFF) return "gesture"
        sleep(120)
        val f2 = Capture.grab(mode, 700) ?: return "unknown"
        if (tapDiff(before, f2, x, y) >= K.CORNER_TAP_VERIFY_DIFF) return "gesture-slow"
        // 两次都没动 → 这一下确实没被应用接受，用 InputManager 那条路补一次
        val r = Shell.run("input tap $x $y", mode, 1_500)
        Logs.w(SkipService.TAG,
            "tapretry kind=CORNER pkg=$pkg at=$x,$y shell=${if (r.ok) "ok" else "fail:${r.out.take(40)}"}")
        if (!r.ok) return "dead"
        sleep(320)
        val f3 = Capture.grab(mode, 700) ?: return "gesture+shell?"
        return if (tapDiff(before, f3, x, y) >= K.CORNER_TAP_VERIFY_DIFF) "gesture+shell" else "dead"
    }

    /**
     * 点**之前**抄下按钮周边那一小片亮度（[K.CORNER_TAP_VERIFY_RADIUS] 见方）。
     *
     * 为什么必须抄成独立数组、而不是留着上一帧的 Frame 对象去比：
     * `Capture.grab()` 每帧复用同一块 ByteArray（见 Capture.buf，省大对象抖动），
     * 于是「上一帧」和「这一帧」其实是**同一个数组**，差值恒等于 0 —— 任何点击都会被
     * 判成 dead。真机 2026-10-30 20:45 就是这么翻车的：手动 `input tap 962 214`
     * 一下就把中国移动云盘的开屏关掉了，日志里却连着三帧 `tap=dead`，
     * 程序以为没点中，于是继续补点、继续扫 —— 用户看到的就是
     * 「截图能识别，实际没效果」。
     */
    private fun tapRegion(frame: Capture.Frame, x: Int, y: Int): IntArray {
        val r = K.CORNER_TAP_VERIFY_RADIUS
        val x0 = (x - r).coerceIn(0, frame.width - 1)
        val y0 = (y - r).coerceIn(0, frame.height - 1)
        val x1 = (x + r).coerceIn(x0 + 1, frame.width)
        val y1 = (y + r).coerceIn(y0 + 1, frame.height)
        return Capture.luminance(frame, x0, y0, x1 - x0, y1 - y0)
    }

    /** 点前快照 vs 新帧，在 (x,y) 周边那一小片内的平均亮度差（0–255）。 */
    private fun tapDiff(before: IntArray, b: Capture.Frame, x: Int, y: Int): Int {
        val r = K.CORNER_TAP_VERIFY_RADIUS
        val x0 = (x - r).coerceIn(0, b.width - 1)
        val y0 = (y - r).coerceIn(0, b.height - 1)
        val x1 = (x + r).coerceIn(x0 + 1, b.width)
        val y1 = (y + r).coerceIn(y0 + 1, b.height)
        val lb = Capture.luminance(b, x0, y0, x1 - x0, y1 - y0)
        if (lb.size != before.size) return 255
        var sum = 0L
        for (i in before.indices) sum += kotlin.math.abs(before[i] - lb[i])
        return (sum / before.size).toInt()
    }

    /**
     * 这个框是不是「刚点过的那颗按钮」。
     *
     * 不能只比中心距离：同一颗「跳过」在相邻帧会被提案器切成不同大小的框 ——
     * 真机日志里同一屏先出 `106x36@945,146`、再出 `158x36@873,182`，中心相距 80 px，
     * 40 px 半径的冷却完全拦不住，于是同一颗按钮被点两次（用户说的「多点」）。
     *
     * 两条任一命中即视为同一颗：**中心 90 px 内**，或**框重叠超过小框的 40%**。
     * 前者盖住切框抖动，后者盖住「一个大框套住小框」的情况。
     */
    private fun recentlyClicked(clicks: List<LongArray>, bx: Int, by: Int, bw: Int, bh: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val cx = bx + bw / 2
        val cy = by + bh / 2
        val area = bw.toLong() * bh
        for (c in clicks) {
            if (now - c[4] > K.CORNER_CLICK_COOL_MS) continue
            val dx = cx - ((c[0] + c[2]) / 2).toInt()
            val dy = cy - ((c[1] + c[3]) / 2).toInt()
            if (dx.toLong() * dx + dy.toLong() * dy <= 90L * 90L) return true
            val ix = minOf(bx + bw, c[2].toInt()) - maxOf(bx, c[0].toInt())
            val iy = minOf(by + bh, c[3].toInt()) - maxOf(by, c[1].toInt())
            if (ix <= 0 || iy <= 0) continue
            val inter = ix.toLong() * iy
            val other = (c[2] - c[0]) * (c[3] - c[1])
            if (inter * 10 > minOf(area, other).coerceAtLeast(1) * 4) return true
        }
        return false
    }

    /** 当前前台包的包名。取不到（root 超时/无窗口）返回 null，调用方按「没变化」处理。 */
    private fun currentPkg(): String? {
        val win = runCatching { svc.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        // root 档下 ELF 报的「已 resume 的 Activity 所属包」= dumpsys 里的 mFocusedApp，
        // 比 rootInActiveWindow 权威：浮层抢焦点时后者会指到浮层上。
        val resumed = FgWatch.current
        if (win == null) return resumed
        if (win == pkg) return win
        // **浮层不算切走**：通知栏/状态栏/权限弹窗/输入法盖上来时，mCurrentFocus 变成
        // com.android.systemui，但 mFocusedApp 还是本应用（真机 20:40:30 就这样：
        // 刚扫出第一帧 3ms 后 `pause ... now=com.android.systemui`，整轮识别直接掐死）。
        // 判据是「ELF 仍说本包是前台」+「抢焦点的是已知浮层」，两个都成立才继续扫。
        if (resumed == pkg && RuleStore.isSystemOverlay(win)) return pkg
        return win
    }

    /**
     * 带超时的标题栏判据：最多等 [CHROME_BUDGET_MS]，超时就当「没有可用证据」放行。
     *
     * 为什么必须限时：`rootInActiveWindow` 是跨进程调用，落到正忙着起播放器的广告壳上
     * 一次就要 **500 ms**（真机 2026-10-29 22:58 实测 `chrome505`）。这段耗时就卡在
     * 「模型已经判 1.000、准备点」的同一条路径上，把点击硬生生推迟半秒 ——
     * 而这条判据本身按设计就是 fail-open（自绘的「跳过」不在无障碍树里，必须照点）。
     * 拿不到证据时本来就要放行，那么拿得慢也一样放行：宁可少挡一次标题栏，
     * 也不能每次点跳过都先陪广告壳等半秒。
     *
     * 超时后那次遍历不会中断（只是被放弃），它在后台线程里自己跑完，不影响点击。
     */
    private fun chromeLabelAtTimed(cx: Int, cy: Int): String? {
        val fut = chromeProbe.submit(java.util.concurrent.Callable { chromeLabelAt(cx, cy) })
        return try {
            fut.get(CHROME_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            chromeTimeouts++
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 框中心是不是落在应用自身的标题栏文字/按钮上。
     *
     * 返回 **null** = 没有可用的无障碍证据，应当放行：广告 SDK 自绘的「跳过」
     * （SurfaceView / canvas 绘制）本来就不在无障碍树里，这种情况必须照点，
     * 宁可留一点误点风险，也不能把真正要找的东西挡死。
     * 返回非空 = 该点上确实有一个带文字的节点，内容是应用自己的标题/菜单（不是跳过）。
     */
    private fun chromeLabelAt(cx: Int, cy: Int): String? {
        val root = svc.safeRoot() ?: return null
        val r = Rect()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        // 「包含该点、带文字、面积最小」的那个：标题栏外层容器也包含这个点，
        // 只有最里层那个才是真正的文本节点。
        var best: String? = null
        var bestArea = Long.MAX_VALUE
        while (stack.isNotEmpty() && visited < 300) {
            val n = stack.removeLast()
            visited++
            try {
                n.getBoundsInScreen(r)
                val inside = r.contains(cx, cy)
                if (inside) {
                    val text = n.text?.toString()?.trim().orEmpty()
                    val label = if (text.isNotEmpty()) text
                    else n.contentDescription?.toString()?.trim().orEmpty()
                    if (label.isNotEmpty()) {
                        val area = r.width().toLong() * r.height()
                        if (area < bestArea) {
                            bestArea = area
                            best = label
                        }
                    }
                }
                // **按外框剪枝**：不含该点的子树不可能给出「包含该点」的节点，
                // 一律不下探。原版无条件遍历到 300 个节点，而 `getChild` 每调一次
                // 就是一次跨进程往返 —— 真机量到这一段吃 430ms，全部花在与点击
                // 位置毫无关系的兄弟节点上。剪枝后通常只走 5–15 个节点。
                // 零尺寸节点（有些容器不报 bounds）照旧下探，避免漏掉真正的文字层。
                if (inside || r.isEmpty) {
                    for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
                }
            } catch (_: Exception) {
                // 节点在遍历途中失效（界面刚好刷新）就当这一层没证据
            }
        }
        val label = best ?: return null
        return if (looksLikeSkip(label)) null else label
    }

    /** 这串文字像不像「跳过/关闭」，或者只是倒计时（「3」「5s」「5 秒」）。 */
    private fun looksLikeSkip(label: String): Boolean {
        val s = label.lowercase()
        if (SKIP_WORDS.any { s.contains(it) }) return true
        return COUNTDOWN.matches(s)
    }

    /** 状态栏是否真的显示着。沉浸式全屏广告会把状态栏收起，这时顶部是广告画面，不能挡。 */
    private fun statusBarVisible(): Boolean = runCatching {
        svc.windows.any { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
    }.getOrDefault(false)

    private fun statusBarHeight(): Int {
        val res = svc.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id)
        else (48 * res.displayMetrics.density).toInt()
    }

    private fun nap(): Int {
        // OCR 路专属抖动 1–30ms（节点路仍是 50–350ms，见 K.DELAY_JITTER_*）
        val ms = Random.nextInt(K.OCR_TAP_JITTER_MIN_MS, K.OCR_TAP_JITTER_MAX_MS + 1)
        try {
            Thread.sleep(ms.toLong())
        } catch (_: Exception) {
        }
        return ms
    }

    private companion object {
        /** 空转态被新窗口唤醒的最小间隔。1.5s 底下弹窗连发也最多两轮，不会打成一帧一轮。 */
        const val REARM_MIN_MS = 1500L

        /** 重扫配额：30 秒内最多 4 次，防止「应用内翻页 = 无限重扫」。 */
        const val REARM_WINDOW_MS = 30_000L
        const val REARM_MAX = 4

        /** 标题栏判据的时间预算。真机实测广告壳上的无障碍查询要 500ms，这里只给 180ms。 */
        const val CHROME_BUDGET_MS = 180L

        /** 小框护栏的三档位置口径在 [CornerScan.gate]，这里不再重复定义。 */

        /** 标题栏判据的执行线程。单线程足够：一次最多同时挂一个待判的框。 */
        val chromeProbe: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "corner-chrome").apply { isDaemon = true }
            }

        /** 标题栏节点文字里出现这些词 → 它自己就是跳过/关闭，放行。 */
        val SKIP_WORDS = listOf("跳过", "跳過", "关闭", "關閉", "skip", "close", "×", "✕")

        /** 纯倒计时（「5」「5s」「5 秒」）也是跳过按钮的常见形态，不能当标题栏文字挡掉。 */
        val COUNTDOWN = Regex("^\\d{1,3}\\s*(s|sec|秒)?$")
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: Exception) {
        }
    }
}
