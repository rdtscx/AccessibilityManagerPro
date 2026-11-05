package com.acsmanager.pro.health

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.acsmanager.pro.util.Prefs
import org.json.JSONObject

/**
 * 健康度仪表盘数据管理器。
 *
 * 收集并展示无障碍服务运行的健康指标：
 *  - 服务存活时长
 *  - 今日被杀次数
 *  - 自动拉起成功率
 *  - 预估额外耗电量
 *  - 当前心跳模式
 *  - 熔断状态
 */
object HealthDashboardManager {

    private const val TAG = "HealthDashboard"

    /** 服务启动时间戳（进程启动时记录） */
    private var serviceStartTime: Long = 0L

    /**
     * 记录服务启动。
     */
    fun onServiceStarted() {
        if (serviceStartTime == 0L) {
            serviceStartTime = System.currentTimeMillis()
        }
    }

    /**
     * 获取服务存活时长（毫秒）。
     */
    fun getUptimeMs(): Long {
        if (serviceStartTime == 0L) return 0L
        return System.currentTimeMillis() - serviceStartTime
    }

    /**
     * 格式化存活时长为可读字符串。
     */
    fun getFormattedUptime(): String {
        val ms = getUptimeMs()
        if (ms <= 0) return "0分钟"

        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}天${hours % 24}小时"
            hours > 0 -> "${hours}小时${minutes % 60}分钟"
            minutes > 0 -> "${minutes}分钟"
            else -> "${seconds}秒"
        }
    }

    /**
     * 今日服务被杀次数。
     */
    fun getTodayKilledCount(ctx: Context): Int {
        return Prefs.todayKilledCount(ctx)
    }

    /**
     * 今日服务被拉起次数。
     */
    fun getTodayRestoredCount(ctx: Context): Int {
        return Prefs.todayRestoredCount(ctx)
    }

    /**
     * 自动拉起成功率（0.0 ~ 1.0）。
     * 公式：成功拉起次数 / (成功 + 失败) * 100%
     */
    fun getRestorationSuccessRate(ctx: Context): Float {
        val success = Prefs.todayRestoredCount(ctx)
        val fail = Prefs.todayRestoreFailedCount(ctx)
        val total = success + fail
        if (total == 0) return 1.0f
        return success.toFloat() / total.toFloat()
    }

    /**
     * 预估额外耗电量（百分比/天）。
     *
     * 估算模型：
     *  - 前台服务基础功耗：约 0.5%/天
     *  - ContentObserver 监听：约 0.2%/天（事件驱动，极低）
     *  - 应用保活巡检：根据频率调整，约 0.3~1.5%/天
     *  - 息屏休眠模式：额外功耗减半
     */
    fun getEstimatedBatteryDrain(ctx: Context): Float {
        var drain = 0.5f // 前台服务基础功耗

        // ContentObserver 监听功耗
        if (Prefs.isSelfGuardEnabled(ctx)) {
            drain += 0.2f
        }

        // 应用保活功耗
        if (Prefs.isWatchdogEnabled(ctx)) {
            val intervalMin = Prefs.watchdogIntervalMs(ctx) / 60_000f
            // 间隔越短功耗越高：1分钟间隔≈1.5%/天，10分钟间隔≈0.3%/天
            drain += (10f / intervalMin).coerceIn(0.3f, 1.5f)
        }

        // 息屏休眠模式下功耗降低
        if (Prefs.dozeModeEnabled(ctx)) {
            drain *= 0.7f
        }

        // 低电量熔断模式下功耗进一步降低
        if (com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.isLowBattery()) {
            drain *= 0.5f
        }

        return drain
    }

    /**
     * 获取健康度评分（0 ~ 100）。
     *
     * 评分维度：
     *  - 服务存活状态（40分）
     *  - 拉起成功率（30分）
     *  - 功耗控制（20分）
     *  - 熔断状态（10分）
     */
    fun getHealthScore(ctx: Context): Int {
        var score = 0

        // 1. 服务存活状态（40分）
        val uptimeHours = getUptimeMs() / (3600_000f)
        score += when {
            uptimeHours >= 24f -> 40
            uptimeHours >= 8f -> 35
            uptimeHours >= 1f -> 25
            uptimeHours >= 0.1f -> 15
            else -> 5
        }

        // 2. 拉起成功率（30分）
        val successRate = getRestorationSuccessRate(ctx)
        score += (successRate * 30).toInt()

        // 3. 功耗控制（20分）
        val drain = getEstimatedBatteryDrain(ctx)
        score += when {
            drain < 1.0f -> 20
            drain < 2.0f -> 15
            drain < 3.0f -> 10
            else -> 5
        }

        // 4. 熔断状态（10分）
        score += when (com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.getCurrentMode()) {
            com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.NORMAL -> 10
            com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.PERFORMANCE -> 8
            com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.LOW_POWER -> 5
            com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.SUSPENDED -> 2
        }

        return score.coerceIn(0, 100)
    }

    /**
     * 获取健康等级描述。
     */
    fun getHealthLevel(ctx: Context): String {
        return when (getHealthScore(ctx)) {
            in 85..100 -> "优秀"
            in 70..84 -> "良好"
            in 50..69 -> "一般"
            in 30..49 -> "较差"
            else -> "危险"
        }
    }

    /**
     * 生成诊断报告 JSON 字符串。
     * 包含系统信息、ROM 信息、服务状态、最近事件摘要。
     */
    fun generateDiagnosticReport(ctx: Context): String {
        val report = JSONObject()

        // 应用信息
        report.put("app_version", try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (t: Throwable) { "unknown" })

        // 系统信息
        val sysInfo = JSONObject().apply {
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("product", Build.PRODUCT)
        }
        report.put("system", sysInfo)

        // ROM 信息
        val romInfo = JSONObject().apply {
            put("rom_type", com.acsmanager.pro.compat.RomCompatibilityHelper.detectRom().displayName)
            put("display_id", Build.DISPLAY)
            put("fingerprint", Build.FINGERPRINT)
        }
        report.put("rom", romInfo)

        // 服务状态
        val serviceInfo = JSONObject().apply {
            put("self_guard_enabled", Prefs.isSelfGuardEnabled(ctx))
            put("watchdog_enabled", Prefs.isWatchdogEnabled(ctx))
            put("uptime_ms", getUptimeMs())
            put("uptime_formatted", getFormattedUptime())
        }
        report.put("services", serviceInfo)

        // 今日统计
        val stats = JSONObject().apply {
            put("killed_today", getTodayKilledCount(ctx))
            put("restored_today", getTodayRestoredCount(ctx))
            put("restore_failed_today", Prefs.todayRestoreFailedCount(ctx))
            put("success_rate", getRestorationSuccessRate(ctx))
            put("battery_drain_est", getEstimatedBatteryDrain(ctx))
        }
        report.put("stats", stats)

        // 心跳与熔断
        val heartbeatInfo = JSONObject().apply {
            put("current_mode", com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.getCurrentMode().name)
            put("battery_level", com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.getBatteryLevel())
            put("is_charging", com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.isCharging())
            put("is_screen_on", com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.isScreenOn())
            put("is_overheated", com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.isOverheated())
        }
        report.put("heartbeat", heartbeatInfo)

        // 权限状态
        val permInfo = JSONObject().apply {
            put("accessibility_enabled", isSelfAccessEnabled(ctx))
            put("battery_optimization_ignored",
                com.acsmanager.pro.compat.RomCompatibilityHelper.isIgnoringBatteryOptimizations(ctx))
        }
        report.put("permissions", permInfo)

        return report.toString(2)
    }

    /**
     * 检查本应用无障碍服务是否已开启。
     */
    private fun isSelfAccessEnabled(ctx: Context): Boolean {
        val comp = android.content.ComponentName(ctx,
            com.acsmanager.pro.selfguard.SelfAccessService::class.java).flattenToString()
        return com.acsmanager.pro.core.AccessServiceRepo.enabledStrings(ctx).contains(comp)
    }
}
