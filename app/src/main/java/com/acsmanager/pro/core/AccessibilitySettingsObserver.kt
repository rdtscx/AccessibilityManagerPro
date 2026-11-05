package com.acsmanager.pro.core

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * 无障碍服务状态变化监听器（统一封装，避免各页面重复注册 ContentObserver）。
 *
 * 用途：当 enabled_accessibility_services 或 accessibility_enabled 发生变化时
 * （包括用户手动开关、系统自动重置、本应用自监控后台拉起等），通知注册方刷新 UI。
 *
 * 使用方式：
 *  - 在 Activity/Fragment 的 onStart/onResume 中调用 [register]；
 *  - 在 onStop/onPause 中调用 [unregister]；
 *  - 回调在主线程执行，可直接更新 UI。
 *
 * 防抖：连续变更时合并为一次回调（默认 300ms），避免系统写入过程中频繁刷新导致闪烁。
 */
class AccessibilitySettingsObserver(
    private val context: Context,
    private val onChange: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // 防抖：合并短时间内的连续变更
            handler.removeCallbacks(debounceRunnable)
            handler.postDelayed(debounceRunnable, DEBOUNCE_MS)
        }
    }

    private val debounceRunnable = Runnable { onChange() }

    /** 注册监听。重复调用安全（已注册时跳过）。 */
    fun register() {
        if (registered) return
        try {
            context.contentResolver.registerContentObserver(
                Settings.Secure.CONTENT_URI, true, observer
            )
            registered = true
        } catch (t: Throwable) {
            // 注册失败不影响主流程
        }
    }

    /** 注销监听。重复调用安全。 */
    fun unregister() {
        if (!registered) return
        try {
            handler.removeCallbacks(debounceRunnable)
            context.contentResolver.unregisterContentObserver(observer)
        } catch (t: Throwable) {
            // 注销失败不影响主流程
        }
        registered = false
    }

    /** 立即触发一次回调（用于注册后主动刷新，不等系统变更）。 */
    fun triggerNow() {
        handler.removeCallbacks(debounceRunnable)
        onChange()
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
    }
}
