package com.v5yn133n3.skp.keep

import android.content.Context
import com.v5yn133n3.skp.data.RuleStore
import com.v5yn133n3.skp.engine.SkipService
import com.v5yn133n3.skp.priv.Daemon
import com.v5yn133n3.skp.priv.Mode
import com.v5yn133n3.skp.priv.OemLock
import com.v5yn133n3.skp.priv.SelfHeal
import com.v5yn133n3.skp.priv.SelfHeal.Step
import com.v5yn133n3.skp.priv.Shell

/**
 * 保活落地：把「档位」翻译成具体动作。
 * 每一档都只补齐、不翻转（旧版 toggle 的坑，见 进度.txt §B4）。
 * 主界面不再隐藏最近任务：卡片可见才能用系统的「锁定」，Root 档还会自动写进锁定名单
 * （见 [OemLock]）；清理器选中不选中它不是靠 oom 决定的。
 */
object KeepAlive {

    fun currentMode(): Mode = Mode.of(RuleStore.privMode)

    /** 档位是否真的可用：Root 要能 su，Shizuku 要 binder 在且已授权。 */
    fun modeUsable(context: Context, mode: Mode): Boolean = Shell.available(context, mode)

    /** 全量补齐一次。UI 首屏、开机广播、以及引擎自检都走这里。 */
    fun apply(context: Context, mode: Mode = currentMode()): List<Step> {
        val steps = ArrayList<Step>()
        if (!openChannel(context, mode)) {
            steps += Step("提权通道", false, "${label(mode)} 不可用：${Shell.unavailableReason(mode)}")
            steps += SelfHeal.ensureAccessibility(context, Mode.NONE)
            // 通道没打通不代表修不了故障态：WRITE_SECURE_SETTINGS 是 pm grant 过的，
            // 本应用自己能写名单，开机时 Shizuku 还没起来也救得回来
            SelfHeal.recoverCrashed(context, Mode.NONE)?.let { steps += it }
            return steps
        }
        steps += Step("提权通道", true, label(mode))
        steps += SelfHeal.grantSecureSettings(context, mode)
        steps += SelfHeal.ensureAccessibility(context, mode)
        // 名单里有我们但系统没绑（开机被 ROM 杀掉就是这个后果）：自己关一次再开
        SelfHeal.recoverCrashed(context, mode)?.let { steps += it }
        steps += SelfHeal.ensureBatteryWhitelist(context, mode, add = true)
        // 悬浮窗这条路整个封掉（反外挂会把它判成外挂），本应用连 SYSTEM_ALERT_WINDOW 都没声明，
        // 所以这里不再走 revokeOverlay：实测 `appops set … SYSTEM_ALERT_WINDOW ignore` 在这台 A17 上
        // 写完读回仍是 `default`（手敲同一条也一样），那条 ✓ 其实是 `canDrawOverlays==false` 给的、
        // 与 appop 无关 ⇒ 一次恒失败的 shell 调用，删掉是零行为变化。
        // 守护跟着档位走，不再有独立开关：只要不是「仅无障碍」就起。
        // 拉活靠的 settings 重绑 shell uid 就够，只有 oom_score_adj 那一层要 root 能力
        //（ELF 自己会试、失败只记日志）。
        if (mode != Mode.NONE) {
            steps += Daemon.start(context, mode)
        }
        // realme/OPPO 系：把本应用写进「最近任务锁定」名单，清理器就不会把它当后台耗电清掉。
        // 普通应用没有写这名单的签名权限，只有 Root 档做得到；其它机型返回 null、不占状态行。
        OemLock.enable(context, mode)?.let { steps += it }
        return steps
    }

    /**
     * 打通提权通道。Shizuku 档缺授权时主动拉起 Shizuku 的授权弹窗并等一会儿——
     * 只读 binder 状态是永远等不到用户点「允许」的。
     */
    private fun openChannel(context: Context, mode: Mode): Boolean {
        if (Shell.available(context, mode)) return true
        if (mode != Mode.SHIZUKU || !Shell.pingShizuku()) return false
        Shell.requestShizukuAuthorization()
        return Shell.awaitShizukuPermission()
    }

    /**
     * Root 档的「申请」入口。KernelSU 到 v3.3.1 都没有给第三方应用的授权接口
     * （IKsuInterface 只暴露 getPackages/getUserIds，allowSu 是管理器自己的 JNI），
     * su 也只挂给名单里的应用 ⇒ 能做的就是把管理器拉起来，让他手动放行。
     */
    fun requestRoot(context: Context): List<Step> {
        val pkg = Shell.findRootManager(context) ?: return listOf(
            Step("Root 管理器", false, "本机没有注册 ksu:// 深链的 KernelSU 管理器，请手动打开它放行")
        )
        val launched = Shell.launchRootManager(context, pkg)
        return listOf(
            Step(
                "Root 管理器", launched,
                if (launched) "已打开 $pkg：在 Superuser 里给本应用放行，再点刷新状态"
                else "找到了 $pkg 但没能拉起界面",
            )
        )
    }

    /** 换档：先撤掉高档留下的痕迹，再按新档补齐。 */
    fun switchMode(context: Context, target: Mode): List<Step> {
        val previous = currentMode()
        val steps = ArrayList<Step>()
        if (previous != target && previous != Mode.NONE) {
            // 离开哪一档就撤掉那一档留下的东西（revoke 里含「停掉并清理该档的守护」），
            // 否则 Shizuku→Root 会留下两个守护同时抢着拉活。
            steps += SelfHeal.revoke(context, previous)
        }
        RuleStore.privMode = target.id
        steps += apply(context, target)
        return steps
    }

    /**
     * 故障态的自动修复入口：只在系统判成故障时才动，闸门在 [SelfHeal.recoverCrashed] 里（10 分钟）。
     * 进页面刷新状态时调一次；返回 null 表示一切正常、没什么可修。只能在非主线程调。
     */
    fun recoverIfCrashed(context: Context): Step? = SelfHeal.recoverCrashed(context, currentMode())

    fun status(context: Context): List<Step> {
        val mode = currentMode()
        val steps = ArrayList<Step>()
        val a11yOn = SelfHeal.isServiceEnabled(context)
        val crashed = !a11yOn && SelfHeal.isInEnabledList(context)
        val engineAlive = SkipService.instance != null
        steps += Step("档位", true, label(mode))
        steps += Step(
            "无障碍开关", a11yOn,
            when {
                a11yOn -> "系统已登记"
                // 名单里有我们但系统不绑：这是被 ROM 杀过之后 A17 会一直显示故障的那个状态
                crashed -> "故障态：名单里有本服务但系统不再绑它，要关一次再开才能恢复"
                else -> "未开启"
            },
        )
        steps += Step("引擎存活", engineAlive, if (engineAlive) "任务池工作中" else "服务未连接")
        // 守护两档都跑：拉活动的是 settings，shell uid 就够；Root 只是多出 oom 那一层
        if (mode != Mode.NONE) {
            val usable = Shell.available(context, mode)
            steps += Step(
                "${label(mode)} 通道", usable,
                when {
                    usable && mode == Mode.ROOT -> "su 已放行"
                    usable -> "binder 在且已授权"
                    else -> "${label(mode)} 不可用：${Shell.unavailableReason(mode)}"
                },
            )
            steps += if (!usable) {
                Step("守护进程", false, "${label(mode)} 通道没打通，守护起不来")
            } else {
                Step("守护进程", Daemon.isRunning(mode),
                    Daemon.statusLine(mode).ifEmpty { "engine pid=${SelfHeal.appPid(context.packageName, mode)}" })
            }
        }
        return steps
    }

    fun label(mode: Mode): String = when (mode) {
        Mode.NONE -> "仅无障碍"
        Mode.SHIZUKU -> "Shizuku"
        Mode.ROOT -> "Root"
    }
}
