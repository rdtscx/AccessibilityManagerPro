package com.acsmanager.pro.keepalive

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.acsmanager.pro.util.Prefs
import java.io.File

/**
 * 系统资源监控与异常熔断机制。
 *
 * 监控指标：
 *  - 电量水平（低于阈值触发低功耗熔断）
 *  - CPU 温度（过热触发熔断）
 *  - 存储空间（不足时暂停日志写入等非必要操作）
 *  - 内存占用（过高时减少后台任务）
 *
 * 熔断策略：
 *  - Level 1（轻度）：延长心跳间隔，减少日志写入
 *  - Level 2（中度）：暂停应用保活，仅保留核心无障碍自监控
 *  - Level 3（重度）：暂停所有非必要服务，仅保留前台通知
 */
class ResourceMonitor(private val context: Context) {

    companion object {
        private const val TAG = "ResourceMonitor"

        const val LEVEL_NORMAL = 0
        const val LEVEL_LIGHT = 1
        const val LEVEL_MEDIUM = 2
        const val LEVEL_SEVERE = 3

        /** 检查间隔（毫秒） */
        private const val CHECK_INTERVAL_MS = 60_000L
    }

    interface OnCircuitBreakerListener {
        fun onBreakerLevelChanged(oldLevel: Int, newLevel: Int, reason: String)
    }

    private val listeners = mutableListOf<OnCircuitBreakerListener>()

    @Volatile
    private var currentLevel: Int = LEVEL_NORMAL

    @Volatile
    private var lastCheckTime: Long = 0L

    fun addOnBreakerListener(listener: OnCircuitBreakerListener) {
        synchronized(listeners) {
            if (listener !in listeners) listeners.add(listener)
        }
    }

    fun removeOnBreakerListener(listener: OnCircuitBreakerListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun getCurrentLevel(): Int = currentLevel

    /**
     * 执行资源检查，返回熔断等级。
     * 带节流：距上次检查不足 CHECK_INTERVAL_MS 时返回缓存结果。
     */
    fun checkResources(): Int {
        val now = System.currentTimeMillis()
        if (now - lastCheckTime < CHECK_INTERVAL_MS && currentLevel != LEVEL_NORMAL) {
            return currentLevel
        }
        lastCheckTime = now

        val oldLevel = currentLevel
        var newLevel = LEVEL_NORMAL
        var reason = ""

        // 1. 电量检查
        val batteryPct = AdaptiveHeartbeatManager.getBatteryLevel()
        val lowBatteryThreshold = Prefs.lowBatteryThreshold(context)
        val criticalBatteryThreshold = 10

        if (batteryPct < criticalBatteryThreshold) {
            newLevel = LEVEL_SEVERE
            reason = "电量过低 ($batteryPct%)"
        } else if (batteryPct < lowBatteryThreshold) {
            newLevel = LEVEL_MEDIUM
            reason = "低电量 ($batteryPct%)"
        }

        // 2. 过热检查
        if (AdaptiveHeartbeatManager.isOverheated()) {
            if (newLevel < LEVEL_MEDIUM) {
                newLevel = LEVEL_MEDIUM
                reason = "设备过热"
            }
        }

        // 3. 存储空间检查（剩余 < 100MB 视为紧张）
        val freeSpaceBytes = getFreeSpaceBytes()
        val freeSpaceMB = freeSpaceBytes / (1024 * 1024)
        if (freeSpaceMB < 100) {
            if (newLevel < LEVEL_LIGHT) {
                newLevel = LEVEL_LIGHT
                reason = "存储空间不足 (${freeSpaceMB}MB)"
            }
        }

        // 4. 内存检查
        val lowMemory = isLowMemory()
        if (lowMemory && newLevel < LEVEL_LIGHT) {
            newLevel = LEVEL_LIGHT
            reason = "内存紧张"
        }

        if (oldLevel != newLevel) {
            Log.w(TAG, "circuit breaker: $oldLevel -> $newLevel, reason: $reason")
            currentLevel = newLevel
            synchronized(listeners) {
                listeners.forEach { it.onBreakerLevelChanged(oldLevel, newLevel, reason) }
            }
        }

        return currentLevel
    }

    /** 获取内部存储剩余空间（字节） */
    private fun getFreeSpaceBytes(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBytes
        } catch (t: Throwable) {
            Long.MAX_VALUE
        }
    }

    /** 判断是否处于低内存状态 */
    private fun isLowMemory(): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.lowMemory
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 根据熔断等级判断是否允许执行指定操作。
     *
     * @param operation 操作类型："keepalive_app"（应用保活）、"log_write"（日志写入）、
     *                  "notification_listen"（通知监听）、"self_guard"（核心自监控）
     */
    fun isOperationAllowed(operation: String): Boolean {
        return when (operation) {
            // 核心自监控：任何等级都允许
            "self_guard" -> true

            // 通知监听：Severe 等级暂停
            "notification_listen" -> currentLevel < LEVEL_SEVERE

            // 应用保活：Medium 及以上暂停
            "keepalive_app" -> currentLevel < LEVEL_MEDIUM

            // 日志写入：Light 及以上降级（只写重要日志），Severe 完全暂停
            "log_write" -> currentLevel < LEVEL_SEVERE

            // 默认允许
            else -> true
        }
    }

    /** 获取当前熔断等级的描述文字 */
    fun getBreakerDescription(): String {
        return when (currentLevel) {
            LEVEL_NORMAL -> "正常运行"
            LEVEL_LIGHT -> "轻度节能：延长巡检间隔"
            LEVEL_MEDIUM -> "中度节能：暂停应用保活"
            LEVEL_SEVERE -> "重度节能：仅保留核心服务"
            else -> "未知"
        }
    }
}
