package com.v5yn133n3.skp.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.inputmethod.InputMethodManager
import com.v5yn133n3.skp.K
import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则存储：内置默认规则（assets/default_rules.json）+ 用户覆盖（SharedPreferences）。
 *
 * 读取时把两份合并，合并规则沿用旧版：数组取并集，其余字段用户值覆盖内置值。
 */
object RuleStore {

    private const val FILE_RULES = "skip.rules"
    private const val FILE_GLOBAL = "skip.global"
    private const val ASSET_DEFAULTS = "default_rules.json"

    const val CORNER_MODE_MODEL = "model"
    const val CORNER_MODE_PIXEL = "pixel"

    /**
     * 系统壳：没有广告，点了就是搜索/弹窗/设置。桌面与输入法另外动态判定。
     *
     * 拨号 / 短信 / 通话界面 2026-10-29 按用户要求补进来（真机 RMX5200 实测包名：
     * 拨号 `com.android.contacts/.DialtactsActivityAlias`、短信 `com.android.mms`、
     * 通话 `com.android.incallui`）。这几个界面里「关闭」「挂断」这类节点满地都是，
     * 节点路跑上去点一下就是挂电话 / 关短信。
     */
    private val SYSTEM_SHELL = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "android",
        // 拨号 / 联系人
        "com.android.contacts",
        "com.android.contactspicker",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.incallui",
        // 短信
        "com.android.mms",
        "com.android.mms.service",
        "com.google.android.apps.messaging",
    )

    private lateinit var rules: SharedPreferences
    private lateinit var global: SharedPreferences
    private var builtin: Map<String, JSONObject> = emptyMap()
    private lateinit var ctx: Context

    /** 桌面 + 输入法的包名缓存。只在进程内算一次：这两个列表不常变，事件链路上要零查询。 */
    private var shellPkgs: Set<String>? = null

    /** 输入法单独存一份：输入法弹/收**不算切应用**，不能重置识别计时。 */
    private var imeSet: Set<String>? = null

    fun init(context: Context) {
        ctx = context.applicationContext
        rules = context.getSharedPreferences(FILE_RULES, Context.MODE_PRIVATE)
        global = context.getSharedPreferences(FILE_GLOBAL, Context.MODE_PRIVATE)
        builtin = loadBuiltin(context)
        // 一次性把调试悬浮窗复位成关：旧版本存过 true，改默认值对它无效
        if (!global.getBoolean(K.DEBUG_OVERLAY_RESET, false)) {
            global.edit()
                .putBoolean(K.DEBUG_OVERLAY, false)
                .putBoolean(K.DEBUG_OVERLAY_RESET, true)
                .apply()
        }
    }

    private fun loadBuiltin(context: Context): Map<String, JSONObject> {
        val out = HashMap<String, JSONObject>()
        val text = try {
            context.assets.open(ASSET_DEFAULTS).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return out
        }
        val root = JSONObject(text)
        val objs = root.optJSONObject("rules") ?: return out
        for (pkg in objs.keys()) {
            out[pkg] = objs.getJSONObject(pkg)
        }
        val disabled = root.optJSONArray("disabled")
        if (disabled != null) {
            for (i in 0 until disabled.length()) {
                val pkg = disabled.optString(i)
                // 旧版 f2.x 先写不生效名单、再用规则表覆盖，所以已有规则的包保持规则原样
                if (pkg.isNotEmpty() && !out.containsKey(pkg)) out[pkg] = JSONObject().put(K.SERVICE_ON, false)
            }
        }
        return out
    }

    /** 旧版 d1.p：数组并集，标量覆盖。 */
    private fun merge(base: JSONObject?, patch: JSONObject?): JSONObject {
        val result = JSONObject()
        if (base != null) {
            for (k in base.keys()) result.put(k, base.opt(k))
        }
        if (patch != null) {
            for (k in patch.keys()) {
                val value = patch.opt(k)
                val existing = result.opt(k)
                if (value is JSONArray && existing is JSONArray && existing.length() > 0) {
                    val union = JSONArray(existing.toString())
                    for (i in 0 until value.length()) union.put(value.opt(i))
                    result.put(k, union)
                } else {
                    result.put(k, value)
                }
            }
        }
        return result
    }

    /** 该包的生效规则（内置 + 用户合并），并写入 pkg 字段。 */
    fun ruleOf(pkg: String): JSONObject {
        val user = rules.getString(pkg, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val merged = merge(builtin[pkg], user)
        // 内置禁用名单以 {"ltt_service":false} 的形式进 builtin，这里只补真正没有声明过的包。
        // 补的值跟判定开关对齐：关掉的包必须让 ltt_service 为 false，否则任务里任何遗留的
        // optBoolean(SERVICE_ON, true) 读法都会把节点路又拉起来。
        if (!merged.has(K.SERVICE_ON)) merged.put(K.SERVICE_ON, isAppOn(pkg))
        return merged.put("pkg", pkg)
    }

    /** 内置默认里是否把该包列为不生效。 */
    fun isBuiltinDisabled(pkg: String): Boolean =
        builtin[pkg]?.optBoolean(K.SERVICE_ON, true) == false

    fun setUserField(pkg: String, key: String, value: Any?) {
        val user = rules.getString(pkg, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
        if (value == null) user.remove(key) else user.put(key, value)
        rules.edit().putString(pkg, user.toString()).apply()
    }

    fun clearUserField(pkg: String, key: String) = setUserField(pkg, key, null)

    fun dropUser(pkg: String) = rules.edit().remove(pkg).apply()

    fun userKeys(): Set<String> = rules.all.keys

    /**
     * 四角任务只在用户配置过规则的应用里跑。
     * 它不看节点、直接按坐标点，跑在桌面/输入法/任意第三方应用上时误点代价太大
     * （真机实测：桌面搜索页 p=1.000、移动营业厅 p=0.984 各点了一次）。
     */
    fun hasUserRule(pkg: String): Boolean = rules.contains(pkg)

    /**
     * 每个应用只有一个判定开关。
     *
     * 开：节点路（无障碍）与视觉路（截图 → 扫带 → 小模型）**同时跑**，两条路各自命中后
     * 互相让位（见 SkipService 的 nodeHitRecently / cornerHitRecently）。
     * 关：两条都不跑。
     *
     * 存储沿用旧版两个键：`ocr_on` / `ltt_service` **任一为 true 都算开**，两个都为 false
     * 才算关。旧版「二选一」留下的数据升级后自然变成「两条都开」。
     *
     * 判定顺序（用户显式设置 > 内置禁用名单 > 默认）：
     *  1. 用户层只要写过 `ocr_on` 或 `ltt_service` 任一键，就按这两个键的或值算；
     *  2. 内置 disabled 名单里的包 = 关，不能让默认值把它们重新打开；
     *  3. **默认开**（与旧版默认无障碍一致），系统壳（设置 / 拨号 / 短信 / 通话 / 桌面 /
     *     输入法 / 自己）一律关，节点路与视觉路都不能上去点。
     */
    fun isAppOn(pkg: String): Boolean {
        val user = userJson(pkg)
        if (user != null && (user.has(K.OCR_ON) || user.has(K.SERVICE_ON))) {
            return user.optBoolean(K.OCR_ON, false) || user.optBoolean(K.SERVICE_ON, false)
        }
        if (isBuiltinDisabled(pkg)) return false
        if (!this::ctx.isInitialized) return true
        if (pkg == ctx.packageName) return false
        if (pkg in SYSTEM_SHELL) return false
        if (pkg in shellPkgs()) return false
        return true
    }

    /** 开/关一个应用的判定。两个键写同一个值，旧版本读任何一条都能理解。 */
    fun setAppOn(pkg: String, on: Boolean) {
        val user = userJson(pkg) ?: JSONObject()
        user.put(K.OCR_ON, on)
        user.put(K.SERVICE_ON, on)
        rules.edit().putString(pkg, user.toString()).apply()
    }

    private fun userJson(pkg: String): JSONObject? =
        rules.getString(pkg, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /**
     * 四角任务（扫带 / OCR）的目标应用。开关打开即同时是节点路和视觉路的目标。
     *
     * 系统壳（桌面 / 输入法 / 系统界面 / 设置）由 [isAppOn] 排除：它们没有广告，
     * 但按坐标点一下就是搜索页、权限弹窗、设置项，代价比漏一次广告大得多。
     */
    fun isCornerTarget(pkg: String): Boolean = isAppOn(pkg)

    /** 桌面（HOME 解析结果）+ 已安装/已启用输入法。查询失败就当空集，宁可多扫不误点系统壳。 */
    private fun shellPkgs(): Set<String> {
        shellPkgs?.let { return it }
        val out = HashSet<String>()
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.queryIntentActivities(home, 0)
                .forEach { out += it.activityInfo.packageName }
        } catch (_: Exception) {
        }
        out += imePkgs()
        val v = out.toSet()
        shellPkgs = v
        return v
    }

    /** 已安装 / 已启用的输入法包名。 */
    private fun imePkgs(): Set<String> {
        imeSet?.let { return it }
        val out = HashSet<String>()
        try {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.inputMethodList.forEach { out += it.packageName }
            imm.enabledInputMethodList.forEach { out += it.packageName }
        } catch (_: Exception) {
        }
        val v = out.toSet()
        imeSet = v
        return v
    }

    /**
     * 是不是输入法。输入法弹出/收起会带一条窗口事件，前台包名瞬变成输入法包 ——
     * 这条**不能**当成「切了应用」重新起 10s 计时，否则用户在聊天窗里打一行字，
     * 收起键盘就又开始满屏找跳过。
     */
    fun isIme(pkg: String): Boolean = pkg in imePkgs()

    /**
     * 抢焦点、但**不代表用户离开了当前应用**的系统浮层。
     *
     * 通知栏拉下来、弹出权限确认、输入法起来的时候，`mCurrentFocus` 会变成
     * com.android.systemui / permissioncontroller，于是 `rootInActiveWindow` 也跟着指过去。
     * 真机 2026-10-30 20:40:30 就是这一下：中国移动刚扫出第一帧 3ms，
     * `pause ... now=com.android.systemui` 把整轮识别掐死 —— 用户看到的是「移动 app 没效果」。
     * 判据的真值是 `mFocusedApp`（root 档由 FgWatch 提供），浮层不改它。
     */
    fun isSystemOverlay(pkg: String): Boolean =
        pkg == "com.android.systemui" ||
            pkg == "com.android.permissioncontroller" ||
            pkg == "android" ||
            isIme(pkg)

    /** 该包是否要处理（节点路与视觉路共用这一个判据）。 */
    fun isEnabled(pkg: String): Boolean = isAppOn(pkg)

    var foregroundServiceOn: Boolean
        get() = global.getBoolean(K.FOREGROUND_SERVICE, true)
        set(value) = global.edit().putBoolean(K.FOREGROUND_SERVICE, value).apply()

    var toastText: String
        get() = global.getString(K.TOAST_TEXT, ".🌱🌱🌱") ?: ".🌱🌱🌱"
        set(value) = global.edit().putString(K.TOAST_TEXT, value).apply()

    var hideToast: Boolean
        get() = global.getBoolean(K.HIDE_TOAST, false)
        set(value) = global.edit().putBoolean(K.HIDE_TOAST, value).apply()

    var privMode: Int
        get() = global.getInt(K.PRIV_MODE, 0)
        set(value) = global.edit().putInt(K.PRIV_MODE, value).apply()

    /**
     * 四角识别：默认开、默认 OCR 档（他的口径：测试包默认走 OCR 配置）。
     * pixel 档是不引词库的降级路线（方案 §8），认不出「跳过」和「了解详情」的区别。
     */
    var cornerOn: Boolean
        get() = global.getBoolean(K.CORNER_ON, true)
        set(value) = global.edit().putBoolean(K.CORNER_ON, value).apply()

    var cornerMode: String
        get() = global.getString(K.CORNER_MODE, CORNER_MODE_MODEL) ?: CORNER_MODE_MODEL
        set(value) = global.edit().putString(K.CORNER_MODE, value).apply()

    /**
     * 单次识别时长（秒），首页滑条口径 4–10，**默认 6**。
     * 到点就停手，等下一次**真正的应用切换**再重新计时。
     */
    var cornerTimeoutSec: Int
        get() = global.getInt(K.CORNER_TIMEOUT_SEC, K.CORNER_TIMEOUT_DEFAULT_S)
            .coerceIn(K.CORNER_TIMEOUT_MIN_S, K.CORNER_TIMEOUT_MAX_S)
        set(value) = global.edit()
            .putInt(K.CORNER_TIMEOUT_SEC, value.coerceIn(K.CORNER_TIMEOUT_MIN_S, K.CORNER_TIMEOUT_MAX_S))
            .apply()

    /** 单次识别时长（毫秒），扫带任务每轮取一次。 */
    val cornerTimeoutMs: Long get() = cornerTimeoutSec * 1000L

    /**
     * 触发延迟（毫秒）：检测到应用切换后先等这么久再开扫，口径 [500, 2000]，**默认 1500**。
     * 切应用那一瞬截到的多是上一屏残留/占位图，等一下新画面才稳。
     */
    var cornerStartDelayMs: Int
        get() = global.getInt(K.CORNER_START_DELAY_MS, K.CORNER_START_DELAY_DEFAULT_MS)
            .coerceIn(K.CORNER_START_DELAY_MIN_MS, K.CORNER_START_DELAY_MAX_MS)
        set(value) = global.edit()
            .putInt(
                K.CORNER_START_DELAY_MS,
                value.coerceIn(K.CORNER_START_DELAY_MIN_MS, K.CORNER_START_DELAY_MAX_MS),
            )
            .apply()

    /**
     * 调试悬浮窗：扫带每帧把「提案框 + 模型分」画在屏幕上。默认关。
     * 需要 SYSTEM_ALERT_WINDOW，权限没给时开关打开也不会建窗口（只记一条日志）。
     */
    var debugOverlay: Boolean
        get() = global.getBoolean(K.DEBUG_OVERLAY, false)
        set(value) = global.edit().putBoolean(K.DEBUG_OVERLAY, value).apply()

    /** 守护 ELF 实际落脚的目录，安装成功时记下，状态读取与卸载都用它。 */
    var daemonBinDir: String
        get() = global.getString(K.DAEMON_BIN_DIR, "") ?: ""
        set(value) = global.edit().putString(K.DAEMON_BIN_DIR, value).apply()
}
