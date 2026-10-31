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
        // 补的值跟 **识别方式** 对齐：默认走 OCR 的包必须让 ltt_service 为 false，
        // 否则任务里任何遗留的 optBoolean(SERVICE_ON, true) 读法都会把节点路又拉起来。
        if (!merged.has(K.SERVICE_ON)) merged.put(K.SERVICE_ON, modeOf(pkg) == RecogMode.A11Y)
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
     * 每个应用用哪一种方式识别 —— **二选一**。
     *
     * | 值 | 走哪条路 | 应用列表右栏 |
     * |---|---|---|
     * | [RecogMode.OCR] | 截图 → 扫带 → 小模型（像素路径） | OCR识别 开 |
     * | [RecogMode.A11Y] | 无障碍节点任务（关键词 / 文本 / 路径 / 弹窗） | 无障碍识别 开 |
     * | [RecogMode.OFF] | 都不跑 | 两颗都关（只有内置「不生效名单」会默认落到这里） |
     *
     * 互斥由 [setMode] 保证：它在**一次写入**里同时落两个键，不会出现「先关这个再开那个」
     * 的中间态。界面上的语义是「关掉一个 = 自动切到另一个」，因此不存在两颗全灭的手动路径；
     * 全灭只来自内置 disabled 名单（旧版「这些应用没有广告」的名单）。
     */
    enum class RecogMode { OFF, OCR, A11Y }

    /**
     * 读该应用当前的识别方式。
     *
     * 判定顺序（用户显式设置 > 内置禁用名单 > 默认）：
     *  1. 用户层只要写过 `ocr_on` 或 `ltt_service` 任一键，就按写过的值算；
     *     两个都是 true 属于旧版（两颗开关独立）留下的脏数据，按 OCR 优先收敛；
     *  2. 内置 disabled 名单里的包 = 都关，不能让默认值把它们重新打开；
     *  3. **默认 [RecogMode.A11Y]（无障碍）**：2026-10-29 用户口径 —— 「默认为无障碍模式，
     *     OCR 一个都不打开」，要扫带的应用由用户自己在列表里开。系统壳（设置 / 拨号 /
     *     短信 / 通话 / 桌面 / 输入法 / 自己）一律 OFF，节点路也不能上去点。
     */
    fun modeOf(pkg: String): RecogMode {
        val user = userJson(pkg)
        if (user != null) {
            if (user.has(K.OCR_ON) || user.has(K.SERVICE_ON)) {
                if (user.optBoolean(K.OCR_ON, false)) return RecogMode.OCR
                if (user.optBoolean(K.SERVICE_ON, false)) return RecogMode.A11Y
                return RecogMode.OFF
            }
        }
        if (isBuiltinDisabled(pkg)) return RecogMode.OFF
        if (!this::ctx.isInitialized) return RecogMode.A11Y
        if (pkg == ctx.packageName) return RecogMode.OFF
        if (pkg in SYSTEM_SHELL) return RecogMode.OFF
        if (pkg in shellPkgs()) return RecogMode.OFF
        return RecogMode.A11Y
    }

    /** 设置识别方式。一次写入两个键，保证「只有一个生效」不会出现中间态。 */
    fun setMode(pkg: String, mode: RecogMode) {
        val user = userJson(pkg) ?: JSONObject()
        user.put(K.OCR_ON, mode == RecogMode.OCR)
        user.put(K.SERVICE_ON, mode == RecogMode.A11Y)
        rules.edit().putString(pkg, user.toString()).apply()
    }

    private fun userJson(pkg: String): JSONObject? =
        rules.getString(pkg, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /**
     * 四角任务（扫带 / OCR）的目标应用。
     *
     * 判据是 [modeOf] == OCR，**不再叠加** [isEnabled]：两颗开关现在互斥，
     * 走 OCR 的包 `ltt_service` 必然是 false，再按「生效」判会把它们全挡掉。
     *
     * 系统壳（桌面 / 输入法 / 系统界面 / 设置）必须排除：它们没有广告，但按坐标点一下
     * 就是搜索页、权限弹窗、设置项，代价比漏一次广告大得多。
     */
    fun isCornerTarget(pkg: String): Boolean {
        if (modeOf(pkg) != RecogMode.OCR) return false
        if (!this::ctx.isInitialized) return false
        if (pkg == ctx.packageName) return false
        if (pkg in SYSTEM_SHELL) return false
        return pkg !in shellPkgs()
    }

    /** 该包是否走 OCR（扫带）识别。**默认 false**（默认全走无障碍），与「无障碍识别」互斥。 */
    fun isOcrOn(pkg: String): Boolean = modeOf(pkg) == RecogMode.OCR

    /** 关掉 OCR = 改走无障碍（两条路永远只有一条在跑），不是两颗都关。 */
    fun setOcrOn(pkg: String, on: Boolean) = setMode(pkg, if (on) RecogMode.OCR else RecogMode.A11Y)

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

    /**
     * 该包是否走**无障碍节点识别**（应用列表右侧第二颗）。等价于 [modeOf] == A11Y。
     *
     * 语义变化：这个方法以前是「生效应用列表」的总开关（默认 true，与 OCR 是「与」关系）。
     * 两颗开关改成互斥后，它只代表无障碍这一条路 —— 走 OCR 的包这里恒为 false，
     * 调用方不要再拿它当「这个包要不要处理」的判据（用 [modeOf] != OFF）。
     */
    fun isEnabled(pkg: String): Boolean = modeOf(pkg) == RecogMode.A11Y

    /** 关掉无障碍 = 改走 OCR。 */
    fun setEnabled(pkg: String, on: Boolean) = setMode(pkg, if (on) RecogMode.A11Y else RecogMode.OCR)

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
