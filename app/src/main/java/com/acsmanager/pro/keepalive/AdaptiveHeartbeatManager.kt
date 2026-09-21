package com.acsmanager.pro.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.acsmanager.pro.util.Prefs

/**
 * 自适应心跳策略管理器。
 *
 * 根据设备状态动态调整保活巡检频率：
 *  - 充电 + 亮屏：高性能模式，心跳间隔最短（响应优先）
 *  - 充电 + 息屏：标准模式，心跳间隔适中
 *  - 电池 + 亮屏：标准模式，心跳间隔适中
 *  - 电池 + 息屏：低功耗模式，心跳间隔最长（省电优先）
 *  - 低电量/过热：熔断模式，暂停非必要保活动作
 */
object AdaptiveHeartbeatManager {

    private const val TAG = "AdaptiveHeartbeat"

    /** 心跳模式枚举 */
    enum class HeartbeatMode(val label: String, val baseIntervalMs: Long) {
        PERFORMANCE("高性能", 30_000L),      // 30秒
        NORMAL("标准", 60_000L),             // 60秒
        LOW_POWER("低功耗", 180_000L),       // 3分钟
        SUSPENDED("已暂停", -1L)             // 熔断暂停
    }

    @Volatile
    private var currentMode: HeartbeatMode = HeartbeatMode.NORMAL

    @Volatile
    private var isCharging: Boolean = false

    @Volatile
    private var isScreenOn: Boolean = true

    @Volatile
    private var batteryLevel: Int = 100

    @Volatile
    private var isOverheated: Boolean = false

    @Volatile
    private var isLowBattery: Boolean = false

    private var receiverRegistered: Boolean = false
    private val handler = Handler(Looper.getMainLooper())

    /** 心跳模式变化监听器 */
    interface OnModeChangedListener {
        fun onModeChanged(oldMode: HeartbeatMode, newMode: HeartbeatMode)
    }

    private val listeners = mutableListOf<OnModeChangedListener>()

    fun addOnModeChangedListener(listener: OnModeChangedListener) {
        synchronized(listeners) {
            if (listener !in listeners) listeners.add(listener)
        }
    }

    fun removeOnModeChangedListener(listener: OnModeChangedListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun notifyModeChanged(oldMode: HeartbeatMode, newMode: HeartbeatMode) {
        Log.i(TAG, "mode changed: $oldMode -> $newMode")
        synchronized(listeners) {
            listeners.forEach { it.onModeChanged(oldMode, newMode) }
        }
    }

    /**
     * 注册设备状态监听（充电、屏幕、电量、温度）。
     * 必须在 Service 或 Application 中调用，对应 onDestroy 调用 unregister。
     */
    fun register(context: Context) {
        if (receiverRegistered) return
        receiverRegistered = true

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            // Android 14+ 过热广播
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                addAction("android.os.action.POWER_SAVE_MODE_CHANGED")
            }
        }

        context.registerReceiver(stateReceiver, filter)

        // 初始化状态
        updateInitialState(context)
        recalculateMode(context)

        Log.i(TAG, "AdaptiveHeartbeatManager registered")
    }

    fun unregister(context: Context) {
        if (!receiverRegistered) return
        receiverRegistered = false
        try {
            context.unregisterReceiver(stateReceiver)
        } catch (t: Throwable) {
            Log.w(TAG, "unregister receiver failed", t)
        }
        Log.i(TAG, "AdaptiveHeartbeatManager unregistered")
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    // 充电状态
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL

                    // 电量百分比
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        batteryLevel = (level * 100 / scale.toFloat()).toInt()
                    }

                    // 电池温度（华氏度转摄氏度）
                    val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                    isOverheated = temp > 550  // 55°C 以上视为过热

                    recalculateMode(context)
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    recalculateMode(context)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    recalculateMode(context)
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    isCharging = true
                    recalculateMode(context)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isCharging = false
                    recalculateMode(context)
                }
            }
        }
    }

    private fun updateInitialState(context: Context) {
        // 屏幕状态（兼容新旧 API）
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            pm.isInteractive
        } else {
            pm.isScreenOn
        }

        // 电池状态
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        isCharging = bm.isCharging
        batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /**
     * 根据当前设备状态重新计算心跳模式。
     * 优先级：熔断(低电量/过热) > 息屏低功耗 > 充电高性能 > 标准
     */
    private fun recalculateMode(context: Context) {
        val oldMode = currentMode

        // 熔断条件：低电量 或 过热
        val threshold = Prefs.lowBatteryThreshold(context)
        isLowBattery = batteryLevel < threshold

        currentMode = when {
            // 熔断模式：低电量或过热时暂停非必要保活
            isLowBattery || isOverheated -> {
                if (Prefs.lowPowerSuspendEnabled(context)) {
                    HeartbeatMode.SUSPENDED
                } else {
                    HeartbeatMode.LOW_POWER
                }
            }
            // 息屏低功耗模式
            !isScreenOn -> HeartbeatMode.LOW_POWER
            // 充电+亮屏：高性能
            isCharging && isScreenOn -> HeartbeatMode.PERFORMANCE
            // 默认标准模式
            else -> HeartbeatMode.NORMAL
        }

        if (oldMode != currentMode) {
            notifyModeChanged(oldMode, currentMode)
        }
    }

    /** 获取当前心跳模式 */
    fun getCurrentMode(): HeartbeatMode = currentMode

    /** 获取当前推荐的心跳间隔（毫秒），-1 表示应暂停 */
    fun getRecommendedIntervalMs(): Long = currentMode.baseIntervalMs

    /** 是否处于熔断暂停状态 */
    fun isSuspended(): Boolean = currentMode == HeartbeatMode.SUSPENDED

    /** 获取当前电量百分比 */
    fun getBatteryLevel(): Int = batteryLevel

    /** 是否充电中 */
    fun isCharging(): Boolean = isCharging

    /** 是否屏幕亮着 */
    fun isScreenOn(): Boolean = isScreenOn

    /** 是否过热 */
    fun isOverheated(): Boolean = isOverheated

    /** 是否低电量 */
    fun isLowBattery(): Boolean = isLowBattery
}
