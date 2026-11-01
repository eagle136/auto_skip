package com.v5yn133n3.skp

/**
 * 规则 JSON 的字段名与旧版李跳跳保持一致，作为规则导入的兼容契约。
 * 取值语义见 进度.txt §B3/§B7。
 */
object K {
    // 每包规则字段
    const val SERVICE_ON = "ltt_service"
    const val KEYWORDS = "keywords"
    const val KEYWORDS_APPEND = "keywords_append"
    const val NODE_WHITELIST = "node_whitelist"
    const val TEXTS_TASK2 = "texts_task2"
    const val DOCC = "DOCC"
    const val CLICKS = "clicks"
    const val DELAY = "delay"
    const val TIMEOUT = "timeout"
    const val SEARCH_INTERVAL = "search_interval"
    const val TRY_TIMES = "try_times"
    const val CLICK_TIMES = "click_times"
    const val CLICK_WAY = "click_way"
    const val TASK_TYPE = "task_type"
    const val MAX_TEXT_LENGTH = "max_text_length"
    const val MAX_DESC_LENGTH = "max_desc_length"
    const val FILTER_DEFAULT = "filter_default"
    const val FILTER_INVISIBLE_NODES = "filter_invisible_nodes"
    const val FILTER_COLLECTION_NODES = "filter_collection_nodes"
    const val REVERSE_TRAVERSAL = "reverse_traversal"
    const val POPUP_RULES = "popup_rules"
    const val IS_USER_CHECK = "is_user_check"

    // 弹窗任务专用字段（旧版用 *_popup 后缀区分，保持同名）
    const val SEARCH_TIMES_POPUP = "search_times_popup"
    const val SEARCH_INTERVAL_POPUP = "search_interval_popup"
    const val DELAY_POPUP = "delay_popup"
    const val CLICK_WAY_POPUP = "click_way_popup"

    // 全局项
    const val FOREGROUND_SERVICE = "foreground_service"
    const val TOAST_TEXT = "toast_text"
    const val HIDE_TOAST = "hide_toast"
    const val PRIV_MODE = "priv_mode"
    const val DAEMON_BIN_DIR = "daemon_bin_dir"
    const val WHITELIST_ALL_APPS = "whitelist_all_apps"

    // 四角像素-OCR 任务（全局开关，不进每包规则）
    const val CORNER_ON = "corner_on"
    const val CORNER_MODE = "corner_mode"

    /**
     * 每包 OCR 识别开关（应用列表右侧的那一颗）。
     *
     * 与 [SERVICE_ON]（无障碍）**同开同关**，由 `RuleStore.setAppOn` 同时写两个键：
     * 开 = 两条链路同时跑，两个键都是 true；关 = 两个键都是 false。
     * 读到旧版二选一的数据时按「任一为 true 即开」兼容。
     */
    const val OCR_ON = "ocr_on"

    /** 调试悬浮窗：把候选框和模型分画到屏幕上。默认关。 */
    const val DEBUG_OVERLAY = "debug_overlay"

    /**
     * 「调试悬浮窗默认关闭」的一次性迁移标记。
     *
     * 旧版本把 debug_overlay 写成 true 存进了 prefs，光改 getter 默认值对它无效 ——
     * 用户看到的就是「这个开关关了还是照画」。带这个标记跑一次强制复位。
     */
    const val DEBUG_OVERLAY_RESET = "debug_overlay_reset_v29"

    // 引擎默认值。窗口时长按需求从旧版 8000ms 提到 10000ms。
    const val DEFAULT_TIMEOUT_MS = 10_000
    const val MIN_TIMEOUT_MS = 1_000

    /**
     * 四角扫带的单次时长上限。**到点就停手，不再截图、不再画框**，等下一次应用切换
     * 或窗口事件重新计时。
     *
     * 之前放过 600s（想覆盖「逛半分钟才弹的插屏」），实测代价太大：停不下来的截图和
     * 调试框一直刷屏，用户看到的就是「界面一直不停」。10s 是他钉的口径；
     * 现在滑条可调 4–10s，默认 **6s**。
     */
    const val CORNER_TIMEOUT_MS = 6_000

    /** 识别时长可在首页滑条上调，口径锁 [4s, 10s]，默认 6s。 */
    const val CORNER_TIMEOUT_MIN_S = 4
    const val CORNER_TIMEOUT_MAX_S = 10
    const val CORNER_TIMEOUT_DEFAULT_S = 6
    const val CORNER_TIMEOUT_SEC = "corner_timeout_sec"

    /**
     * 高频档占整个识别时长的比例。10s 口径下正好是原来的 6s：
     * 前段不额外 sleep，截完就扫（截图本身 ~170ms 已经是天然限速）。
     * 用比例而不是定值，是为了让 4s 档不至于「2s 高频 + 2s 低频」只剩一两帧。
     */
    const val CORNER_FAST_RATIO_NUM = 6
    const val CORNER_FAST_RATIO_DEN = 10

    /**
     * 标题栏保护带高度（dp）。状态栏可见时，状态栏以下这一段是应用自己的标题栏
     * （返回箭头 + 标题 + 右侧菜单），**不是**广告位。
     *
     * 真机误报：微信标题「一加真我OPPO充电深度测试群(427)」里的「(427)」被模型判 0.9x
     * 并点了下去 —— 它就在状态栏下面 20–50 px。几何上它和开屏广告的右上「跳过」
     * （实测 y=5.4%–6.9%）几乎同高，光靠坐标分不开，所以这一带另配一道
     * 无障碍文本闸门，见 [CornerTask.chromeLabelAt]。
     */
    const val CHROME_GUARD_DP = 56f

    /**
     * 触发延迟：检测到应用切换后**先等这么久再开始截图/OCR**，滑条口径 [0s, 2.0s]。
     *
     * 为什么要等：切应用那一瞬截到的常常还是上一屏的残留 / 广告的占位图。
     * **默认 0（关）**：事件本身已经晚了 ~570ms，再等 0.5–1.5s 就是「广告播了
     * 一两秒才点」的主因。扫早一点最多白扫一帧，成本 0.36s；扫晚了是直接看不到
     * 跳过按钮。留滑条只为「首页很卡 / 广告延迟很久才出」这类个别包手动加。
     */
    const val CORNER_START_DELAY_MIN_MS = 0
    const val CORNER_START_DELAY_MAX_MS = 2_000
    const val CORNER_START_DELAY_DEFAULT_MS = 0
    const val CORNER_START_DELAY_MS = "corner_start_delay_ms"

    /** 低频档帧间隔。 */
    const val CORNER_SLOW_GAP_MS = 1_200L

    /**
     * 每隔多少帧补扫一次**全带**（热区之外的瓦片）。
     * 高频档 3 帧 ≈ 1s，低频档 5 帧 ≈ 6s——热区外的跳过按钮（贴底、左上胶囊）
     * 依然能被覆盖，但不会把每一帧都拖回 600ms。
     */
    const val CORNER_FULL_EVERY_FAST = 6
    const val CORNER_FULL_EVERY_SLOW = 5

    /**
     * **点击有效性校验**。注入一次点击后隔多久复查画面：按钮周边的像素没动，
     * 说明这一下没被应用接受（广告 SDK 过滤无障碍注入 / 落在不可点区域 / 事件被吞）。
     *
     * 为什么要校验：中国移动的插屏实测「框到了、模型 1.000、日志有 hit，
     * 但广告照放」——注入返回 true 只代表系统收下了手势，不代表应用处理了它。
     */
    const val CORNER_TAP_VERIFY_MS = 260L

    /**
     * 点击后复查时，按钮周边区域的**平均亮度差**下限（0–255）。
     * 生效时画面会明显换页（几十以上），没生效时几乎为 0；取 3 留余量。
     */
    const val CORNER_TAP_VERIFY_DIFF = 3

    /** 复查区域半边长（px）。按钮中心 ±这个范围足以覆盖按钮和它周围的变化。 */
    const val CORNER_TAP_VERIFY_RADIUS = 56

    /**
     * 「画面在动」判据：帧间亮度差（半分辨率、每 16 个像素抽 1 个求平均，量纲 0–255）。
     * 静态界面实测 0–1，视频/滚动 ≥ 8。只进日志，用来解释「为什么这颗按钮现在才出现」。
     */
    const val CORNER_MOTION_DIFF = 4

    /**
     * 整颗落在状态栏里的框：模型分低于它一律丢（防点状态栏的网速/电量）。
     * 旧版是无条件丢弃 —— 而 `statusBarVisible()` 只要窗口表里存在 TYPE_SYSTEM 就返回真，
     * 沉浸式广告里也常常为真，于是贴着屏幕顶的那颗「跳过」被整颗判掉。
     * 0.995 是安全线：真机误报过的「240.0 B」是 0.975，离这条线还有距离。
     */
    const val CORNER_STATUS_BAR_MIN_P = 0.995f

    /**
     * 标题栏保护带内（状态栏以下 56dp）的框：模型分高到这个程度可以推翻
     * 「这里有文字」的判据。
     *
     * 旧版是一票否决 —— 而视频 App 的「跳过」正好就在标题栏那一行，
     * 它背后往往还压着广告壳的文字节点（「广告」「了解详情」），于是整颗被挡掉。
     * 0.99 仍高于头像/图标的 0.987，挡得住原来的误报。
     */
    const val CORNER_CHROME_OVERRIDE_P = 0.99f

    /**
     * 同位置重复点击冷却。广告常见「跳过 → 再盖一层关闭」，位置不同照点；
     * 但同一个按钮在 3s 内不再点第二次 —— 点击到广告真正关闭有 0.4–1.2s 延迟，
     * 冷却必须长于这段延迟 + 一帧扫描时间，否则「还在收尾」会被当成「没点中」，
     * 第二发落到刚跳过去的新页面上。**一次广告只对同一颗按钮下一次手。**
     */
    const val CORNER_CLICK_COOL_MS = 3_000L

    /**
     * 一次触发最多点几次。**固定 1 次**：点完就关 OCR，不再扫、不再补。
     *
     * 用户口径：一次触发只点一下，然后彻底停 —— 广告关闭有 0.4–1.2s 延迟，多点只看
     * 到「又点了一下」，还会打到刚跳过去的新页面上。要再点必须等下一次触发
     * （应用切换，或同包内新窗口事件把会话重新唤醒）。
     */
    const val CORNER_MAX_CLICKS = 1

    /**
     * 手势点击的按压时长。原来写死 1 ms（down/up 同一时刻），部分 View —— 尤其广告 SDK
     * 自绘的「跳过」—— 会把这种瞬时触摸当抖动丢掉：日志里有 hit、广告不关，下一帧又在
     * 同一位置命中，用户看到的就是「点了不生效」。48 ms 是真实手指轻点的量级。
     */
    /**
     * 单次触摸的按压时长。
     * 48ms 太短：部分 WebView/广告 SDK 的 click 判定要求按住 ≥80ms 才认为是一次点击
     * （实测中国移动的「跳过」在 48ms 下三次检查画面都没动）。80ms 仍在正常点按范围内。
     */
    const val TAP_MS = 80L

    /**
     * 点完到「再截图」之间必须等的时长。广告关闭普遍不是瞬时的：跳过点了之后要等
     * 播放器收尾 / 网络资源释放 / 转场动画，实测大多 0.4–1.2s 才换画面。
     *
     * **这个间隔是「只点一次」的保证**：点完先睡够，再重新截图判断新画面；
     * 期间不做任何补点，不会因为「画面还没变」就把第二发打到刚跳过去的新页面上。
     */
    const val TAP_SETTLE_MS = 1_200L

    /**
     * 节点路点中之后，四角任务让位的时长。
     * 节点路点掉「跳过」后旧画面还会停留一瞬，四角在同一张图上能找到同一颗按钮的另一
     * 个切框（位置差几十像素，同位置冷却拦不住），不让位就会补第二发。
     */
    const val NODE_HOLD_MS = 2_000L

    /**
     * 「已经点过、之后连续这么久没再命中」就收工。
     * 点掉跳过说明这一屏处理完了，再跟 10 分钟纯粹是白耗电；
     * 多层广告通常 1–3s 内就盖上来，15s 留得很宽。
     * 一次都没点过的任务不走这条 —— 用户要的正是「逛半分钟才弹的插屏」也能抓。
     */
    const val CORNER_IDLE_EXIT_MS = 15_000L
    const val DEFAULT_INTERVAL_MS = 199
    const val MIN_INTERVAL_MS = 20
    /** 延迟点击强制且不可配：每次点击前现场取一个区间内随机值。（无障碍节点路） */
    const val DELAY_JITTER_MIN_MS = 50
    const val DELAY_JITTER_MAX_MS = 350

    /**
     * OCR（四角扫带）路的点击前抖动：**1–30ms**。
     *
     * 和节点路分开的理由：节点路是「读文本 → 直接点」，零延迟在部分自绘按钮上会被
     * 当成抖动丢掉，而且每次都是同一时刻，容易被风控认成脚本；四角路前面已经压了
     * 截图 + 扫带（~360ms/帧），抖动只是形式上的拟人化，50–350ms 纯属白等。
     * 留 1ms 下限是为了不发出 down/up 同刻的触摸（那才是真会被丢的那种）。
     */
    const val OCR_TAP_JITTER_MIN_MS = 1
    const val OCR_TAP_JITTER_MAX_MS = 30
    const val DEFAULT_TRY_TIMES = 2
    const val DEFAULT_MAX_TEXT_LEN = 8
    const val DEFAULT_MAX_DESC_LEN = 32
    const val POPUP_DEFAULT_SEARCH_TIMES = 3
    const val POPUP_DEFAULT_INTERVAL_MS = 200
    const val POPUP_MIN_INTERVAL_MS = 50

    const val CLICK_WAY_NODE = 0
    const val CLICK_WAY_GESTURE = 1
    const val CLICK_WAY_BACK = 2
    const val CLICK_WAY_AUTO = Int.MAX_VALUE

    const val TASK_TYPE_KEYWORD = 1
    const val TASK_TYPE_TEXTS2 = 2
}
