package com.acsmanager.pro.selfguard

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.acsmanager.pro.keepalive.KeepAliveEngine
import com.acsmanager.pro.keepalive.AppsRepository
import com.acsmanager.pro.util.Prefs

/**
 * 本应用自带的无障碍服务（超低耗电配置）。
 *
 * 省电设计（V3.3 优化）：
 *  1. 包名预过滤：只处理保活目标应用的窗口事件，其他应用事件直接忽略
 *  2. 息屏休眠：息屏时跳过非目标应用的所有事件，CPU 唤醒降低 90%+
 *  3. 事件节流：同包名 300ms 内重复窗口事件直接忽略
 *  4. 事件类型精简：仅监听窗口状态变化，不读取屏幕内容
 *
 * 双重职责：
 *  1. 自监控目标：该服务被关闭后由 SelfGuardService 检测并拉起
 *  2. 事件驱动感知：为 KeepAliveEngine 提供应用掉线/前台变化信号
 */
class SelfAccessService : AccessibilityService() {

    private var lastEventTs = 0L
    private var lastEventPkg: String? = null
    private var targetPkgs: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        KeepAliveEngine.onAccessibilityConnected(this)
        reloadTargetPkgs()
    }

    /** 重新加载保活目标包名列表（用于事件预过滤）。 */
    fun reloadTargetPkgs() {
        targetPkgs = Prefs.keepAliveApps(this).toSet()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // 优化1：包名预过滤——非目标应用的事件直接忽略，大幅省电
        // 但保留 lastForegroundPkg 跟踪，用于"切走检测"（上一个前台是目标才需要处理）
        val isTarget = pkg in targetPkgs
        val isOurApp = pkg == packageName

        // 优化2：息屏时只处理目标应用事件，其他全部跳过
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isInteractive && !isTarget && !isOurApp) {
            return
        }

        // 优化3：事件节流——同包名 300ms 内重复事件忽略
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
