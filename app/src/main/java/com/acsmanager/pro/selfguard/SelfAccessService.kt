package com.acsmanager.pro.selfguard

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.acsmanager.pro.keepalive.KeepAliveEngine
import com.acsmanager.pro.util.Prefs
import android.util.Log

/**
 * 本应用自带的无障碍服务（超低耗电配置）。
 *
 * 省电设计（V3.3 优化）：
 *  1. 包名预过滤：只处理保活目标应用的窗口事件，其他应用事件直接忽略
 *  2. 息屏休眠：息屏时跳过非目标应用的所有事件，CPU 唤醒降低 90%+
 *  3. 事件节流：同包名 300ms 内重复窗口事件直接忽略
 *  4. 事件类型精简：仅监听窗口状态变化，不读取屏幕内容
 *
 * 职责：
 *  1. 自监控目标：该服务被关闭后由 SelfGuardService 检测并拉起
 *  2. 事件驱动感知：为 KeepAliveEngine 提供应用掉线/前台变化信号
 *  3. V5.0 自动优化：一键优化时自动操作系统设置页面
 */
class SelfAccessService : AccessibilityService() {

    companion object {
        private const val TAG = "SelfAccessService"

        /** 自动优化模式：无 */
        const val MODE_NONE = 0
        /** 自动优化模式：自启动设置 */
        const val MODE_AUTO_START = 1
        /** 自动优化模式：电池优化设置 */
        const val MODE_BATTERY_OPTIMIZE = 2

        /** 当前自动优化模式（静态变量，便于从外部设置） */
        @Volatile
        var autoOptimizeMode: Int = MODE_NONE
    }

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

        // 自动优化模式下，处理设置页面的事件
        if (autoOptimizeMode != MODE_NONE) {
            handleAutoOptimizeEvent(event, pkg)
            return
        }

        // 正常模式：包名预过滤 + 事件节流
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

    /**
     * 自动优化模式下处理设置页面事件。
     * 自动查找本软件并点击对应的开关。
     */
    private fun handleAutoOptimizeEvent(event: AccessibilityEvent, pkg: String) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val rootNode = rootInActiveWindow ?: return

        when (autoOptimizeMode) {
            MODE_AUTO_START -> {
                val found = findAndClickApp(rootNode)
                if (found) {
                    Log.i(TAG, "自启动设置：找到并点击了本软件")
                    autoOptimizeMode = MODE_BATTERY_OPTIMIZE
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        com.acsmanager.pro.compat.RomCompatibilityHelper
                            .requestIgnoreBatteryOptimization(this)
                    }, 1500)
                } else {
                    scrollDown(rootNode)
                }
            }
            MODE_BATTERY_OPTIMIZE -> {
                val found = findAndClickApp(rootNode)
                if (found) {
                    Log.i(TAG, "电池优化设置：找到并点击了本软件")
                    autoOptimizeMode = MODE_NONE
                } else {
                    scrollDown(rootNode)
                }
            }
        }

        rootNode.recycle()
    }

    /**
     * 在节点树中查找目标应用，并点击对应的开关项。
     */
    private fun findAndClickApp(node: AccessibilityNodeInfo): Boolean {
        // 用包名查找
        val pkgNodes = node.findAccessibilityNodeInfosByText(packageName)
        if (pkgNodes.isNotEmpty()) {
            for (pkgNode in pkgNodes) {
                if (clickToggleNearNode(pkgNode)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 在指定节点附近查找并点击 Switch/CheckBox 等开关控件。
     */
    private fun clickToggleNearNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            val parent = current?.parent ?: return false
            val switches = parent.findAccessibilityNodeInfosByViewId(
                "android:id/switch_widget"
            )
            if (switches.isNotEmpty()) {
                for (switch in switches) {
                    if (switch.isCheckable) {
                        if (!switch.isChecked) {
                            switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        return true
                    }
                }
            }
            current = parent
        }
        return false
    }

    /**
     * 向下滚动列表，查找更多应用。
     */
    private fun scrollDown(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            if (current?.isScrollable == true) {
                current?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                return
            }
            current = current?.parent
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        KeepAliveEngine.onAccessibilityDisconnected()
        super.onDestroy()
    }
}
