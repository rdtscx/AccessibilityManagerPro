package com.acsmanager.pro.selfguard

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.acsmanager.pro.keepalive.KeepAliveEngine
import com.acsmanager.pro.util.Prefs

/**
 * 本应用自带的无障碍服务（低耗电配置，见 res/xml/accessibility_service_config.xml）。
 *
 * 双重职责：
 *  1. 自监控目标：该服务被系统/用户关闭后，由 [SelfGuardService] 通过 ContentObserver
 *     检测到 Settings.Secure 变更，延迟 1 秒后立即通过授权通道拉起（与需求一致）。
 *  2. 事件驱动感知：仅监听窗口切换类事件（不读取屏幕内容、不轮询），为 [KeepAliveEngine]
 *     提供"应用掉线/前台变化"信号，并作为系统豁免通道执行后台无感拉起。
 *
 * 省电设计：事件采样降频（eventThrottleMs，默认 300ms 内的重复窗口事件直接忽略）。
 */
class SelfAccessService : AccessibilityService() {

    private var lastEventTs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        KeepAliveEngine.onAccessibilityConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val now = SystemClock.elapsedRealtime()
        val throttle = Prefs.eventThrottleMs(this)
        if (throttle > 0 && now - lastEventTs < throttle) return
        lastEventTs = now

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
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
