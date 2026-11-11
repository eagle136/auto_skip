package com.v5yn133n3.skp.priv

import android.content.Context
import android.os.Build
import android.util.Log
import com.v5yn133n3.skp.priv.SelfHeal.Step
import java.io.File
import java.util.Locale

/**
 * realme / OPPO / OnePlus 的「最近任务锁定」白名单。
 *
 * 系统清理器（Athena / guardelf）按 OplusListManager 里的锁定名单决定跳过谁；
 * 这份名单在 system_server 内存里，`/data/oplus/os/battery/recent_lock.xml` 只是 Athena 的缓存，
 * 直接改文件会被下一次同步覆盖。写名单只有 OEM 的 `OplusCommonConfig.putConfigInfoAsUser`
 * 一条路，而它要求调用方持有签名权限 `oplus.permission.OPLUS_COMPONENT_SAFE`——普通应用拿不到，
 * 所以这里用 root 起一个 `app_process`，执行 assets 里的最小 dex（源码 native/SkpLock.java）。
 *
 * 这一步只解决「不被清理器选中」；system_server 主动 force-stop 仍然能杀任何普通应用，
 * 那一路由守护秒级拉活兜底（见 com.v5yn133n3.skp.priv.Daemon）。
 */
object OemLock {

    private const val TAG = "SkipOemLock"
    private const val ASSET = "elf/skp_lock.dex"
    private const val LOCAL_NAME = "skp_lock.dex"
    private const val REMOTE = "/data/local/tmp/skp_lock.dex"
    private const val MARK_OK = "SKP_LOCK_OK"

    /** ROM 是否带这套接口。先看厂商，再确认类真的在 boot classpath 里。 */
    fun supported(): Boolean {
        val id = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.ROOT)
        val oplus = id.contains("realme") || id.contains("oppo") ||
            id.contains("oneplus") || id.contains("oplus")
        if (!oplus) return false
        return runCatching {
            Class.forName("com.oplus.util.OplusCommonConfig", false, null)
            true
        }.getOrDefault(false)
    }

    /** 只对 Root 档生效。返回 null = 这台机器不需要/不支持这一步。 */
    @Synchronized
    fun enable(context: Context, mode: Mode): Step? {
        if (mode != Mode.ROOT || !supported()) return null
        val src = extract(context)
            ?: return Step("最近任务锁定", false, "安装包里没有 $ASSET")
        // 开机会广播和页面首屏可能同时补；.tmp + mv 保证 app_process 不会读到半份 dex
        val deploy = Shell.run(
            listOf(
                "mkdir -p /data/local/tmp 2>/dev/null",
                "cp ${src.absolutePath} $REMOTE.tmp 2>/dev/null || cat ${src.absolutePath} > $REMOTE.tmp",
                "chmod 0644 $REMOTE.tmp",
                "mv -f $REMOTE.tmp $REMOTE",
            ),
            Mode.ROOT,
            timeoutMs = 8_000,
        )
        if (!deploy.ok) {
            return Step("最近任务锁定", false, "投放 dex 失败：${lastLine(deploy.out)}")
        }
        // 名单按 user 隔离；主用户是 0，工作资料是多用户，别把 uid 当 user 传
        val user = android.os.Process.myUid() / 100_000
        val run = Shell.run(
            "CLASSPATH=$REMOTE app_process /system/bin SkpLock ${context.packageName} add $user",
            Mode.ROOT,
            timeoutMs = 15_000,
        )
        val ok = run.out.contains(MARK_OK)
        Log.i(TAG, "lock ok=$ok out=${run.out.trim().take(200)}")
        return Step(
            "最近任务锁定",
            ok,
            if (ok) "已写入系统锁定名单，清理时跳过" else "写入失败：${lastLine(run.out)}",
        )
    }

    private fun extract(context: Context): File? = try {
        val dst = File(context.codeCacheDir, LOCAL_NAME)
        context.assets.open(ASSET).use { input ->
            dst.outputStream().use { out -> input.copyTo(out) }
        }
        if (dst.length() > 0) dst else null
    } catch (e: Exception) {
        Log.w(TAG, "extract failed for $ASSET", e)
        null
    }

    private fun lastLine(text: String): String =
        text.trim().lines().lastOrNull()?.take(120)?.ifEmpty { "无输出" } ?: "无输出"
}
