package com.acsmanager.pro.selfguard

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.acsmanager.pro.keepalive.KeepAliveEngine
import com.acsmanager.pro.util.Prefs

/**
 * 本应用自带的无障碍服务（超低耗电配置）。
 *
 * 省电设计：
 *  1. 包名预过滤：只处理保活目标应用的窗口事件
 *  2. 息屏休眠：息屏时跳过非目标应用事件
 *  3. 事件节流：同包名 300ms 内重复事件忽略
 *  4. 事件类型精简：仅监听窗口状态变化，不读取屏幕内容
 *
 * 职责：
 *  1. 自监控目标：被关闭后由 SelfGuardService 检测并拉起
 *  2. 事件驱动感知：为 KeepAliveEngine 提供应用前台变化信号
 */
class SelfAccessService : AccessibilityService() {

    private var lastEventTs = 0L
    private var lastEventPkg: String? = null
    private var targetPkgs: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        KeepAliveEngine.onAccessibilityConnected(this)
        reloadTargetPkgs()
        // 自动化增强（5.2.1）：无障碍服务被系统/用户重新打开时，若用户已开启自监控但
        // SelfGuardService 未运行（如被系统杀后未自启），立即补拉起——保证"被关即拉起"的
        // 自监控闭环不出现空窗。无授权通道时 start 会内部静默失败，不影响主流程。
        try {
            if (com.acsmanager.pro.util.Prefs.isSelfGuardEnabled(this) &&
                !SelfGuardService.isRunning(this)
            ) {
                SelfGuardService.start(this)
            }
        } catch (t: Throwable) {
            android.util.Log.w("SelfAccessService", "self-heal self guard failed", t)
        }
    }

    fun reloadTargetPkgs() {
        targetPkgs = Prefs.keepAliveApps(this).toSet()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        val isTarget = pkg in targetPkgs
        val isOurApp = pkg == packageName

        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isInteractive && !isTarget && !isOurApp) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        val throttle = Prefs.eventThrottleMs(this)
        if (throttle > 0 && now - lastEventTs < throttle && pkg == lastEventPkg) {
            return
        }
        lastEventTs = now
        lastEventPkg = pkg

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                KeepAliveEngine.onForeground(pkg, now)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        KeepAliveEngine.onAccessibilityDisconnected()
        super.onDestroy()
    }
}
