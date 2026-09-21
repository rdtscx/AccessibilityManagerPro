package com.acsmanager.pro.health

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.acsmanager.pro.R
import com.acsmanager.pro.notif.NotifGateService
import com.acsmanager.pro.selfguard.SelfAccessService
import com.acsmanager.pro.selfguard.SelfGuardService

/**
 * 服务健康诊断（V4.0 新增）
 *
 * 自动检测各项服务状态，输出健康报告和优化建议。
 * 用于一键优化和健康仪表盘展示。
 */
object HealthDiagnosis {

    data class CheckItem(
        val title: String,
        val desc: String,
        val ok: Boolean,
        val action: String? = null // 建议操作
    )

    data class DiagnosisReport(
        val items: List<CheckItem>,
        val score: Int, // 0-100
        val level: String
    )

    /** 执行全面健康诊断 */
    fun diagnose(ctx: Context): DiagnosisReport {
        val items = mutableListOf<CheckItem>()

        // 1. 无障碍服务状态
        items.add(checkAccessibilityService(ctx))

        // 2. 自监控保活服务
        items.add(checkSelfGuardService(ctx))

        // 3. 通知监听权限
        items.add(checkNotificationAccess(ctx))

        // 4. 使用情况访问权限
        items.add(checkUsageAccess(ctx))

        // 5. 电池优化白名单
        items.add(checkBatteryOptimization(ctx))

        // 6. 保活目标数量
        items.add(checkKeepAliveTargets(ctx))

        // 7. 开发者选项总开关
        items.add(checkDevOptions(ctx))

        val okCount = items.count { it.ok }
        val score = (okCount * 100.0 / items.size).toInt()
        val level = when {
            score >= 90 -> "优秀"
            score >= 70 -> "良好"
            score >= 50 -> "一般"
            else -> "较差"
        }

        return DiagnosisReport(items, score, level)
    }

    private fun checkAccessibilityService(ctx: Context): CheckItem {
        val enabled = try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE)
                    as android.view.accessibility.AccessibilityManager
            val list = am.getEnabledAccessibilityServiceList(0)
            list.any { it.id.contains(ctx.packageName) }
        } catch (t: Throwable) { false }

        return CheckItem(
            title = "无障碍服务",
            desc = if (enabled) "已启用" else "未启用",
            ok = enabled,
            action = if (!enabled) "去开启" else null
        )
    }

    private fun checkSelfGuardService(ctx: Context): CheckItem {
        val running = try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            // 用通知是否存在判断服务是否在运行
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                nm.activeNotifications.any { it.id == 102 }
            } else {
                false
            }
        } catch (t: Throwable) { false }

        return CheckItem(
            title = "自监控保活",
            desc = if (running) "运行中" else "未运行",
            ok = running,
            action = if (!running) "去启动" else null
        )
    }

    private fun checkNotificationAccess(ctx: Context): CheckItem {
        val enabled = NotifGateService.isEnabled(ctx)
        return CheckItem(
            title = "通知监听",
            desc = if (enabled) "已授权" else "未授权",
            ok = enabled,
            action = if (!enabled) "去授权" else null
        )
    }

    private fun checkUsageAccess(ctx: Context): CheckItem {
        val granted = try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            usm.queryEvents(end - 1000, end)
            true
        } catch (t: SecurityException) {
            false
        } catch (t: Throwable) {
            false
        }

        return CheckItem(
            title = "使用情况访问",
            desc = if (granted) "已授权（精准检测）" else "未授权（基础检测）",
            ok = granted,
            action = if (!granted) "去授权" else null
        )
    }

    private fun checkBatteryOptimization(ctx: Context): CheckItem {
        val exempt = try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            } else {
                true
            }
        } catch (t: Throwable) { false }

        return CheckItem(
            title = "电池优化白名单",
            desc = if (exempt) "已豁免（保活更稳）" else "未豁免（可能被系统杀死）",
            ok = exempt,
            action = if (!exempt) "去设置" else null
        )
    }

    private fun checkKeepAliveTargets(ctx: Context): CheckItem {
        val count = com.acsmanager.pro.util.Prefs.keepAliveApps(ctx).size
        return CheckItem(
            title = "保活目标",
            desc = "已保护 $count 个应用",
            ok = count > 0,
            action = if (count == 0) "去添加" else null
        )
    }

    private fun checkDevOptions(ctx: Context): CheckItem {
        val enabled = try {
            android.provider.Settings.Global.getInt(
                ctx.contentResolver,
                "development_settings_enabled",
                0
            ) != 0
        } catch (t: Throwable) {
            false
        }

        return CheckItem(
            title = "开发者选项",
            desc = if (enabled) "已启用" else "未启用（部分功能受限）",
            ok = enabled,
            action = if (!enabled) "去开启" else null
        )
    }

    /** 一键优化：自动应用推荐配置 */
    fun autoOptimize(ctx: Context): List<String> {
        val actions = mutableListOf<String>()

        // 1. 启用自监控保活
        try {
            if (!com.acsmanager.pro.util.Prefs.isSelfGuardEnabled(ctx)) {
                com.acsmanager.pro.util.Prefs.setSelfGuardEnabled(ctx, true)
                SelfGuardService.start(ctx)
                actions.add("已开启自监控保活")
            }
        } catch (t: Throwable) {}

        // 2. 启用看门狗
        try {
            if (!com.acsmanager.pro.util.Prefs.isWatchdogEnabled(ctx)) {
                com.acsmanager.pro.util.Prefs.setWatchdogEnabled(ctx, true)
                com.acsmanager.pro.watchdog.WatchdogService.start(ctx)
                actions.add("已开启看门狗守护")
            }
        } catch (t: Throwable) {}

        // 3. 启用通知历史记录
        try {
            if (!com.acsmanager.pro.util.Prefs.notifHistoryEnabled(ctx)) {
                com.acsmanager.pro.util.Prefs.setNotifHistoryEnabled(ctx, true)
                actions.add("已开启通知历史记录")
            }
        } catch (t: Throwable) {}

        // 4. 设置合理的事件节流（默认 300ms）
        try {
            val current = com.acsmanager.pro.util.Prefs.eventThrottleMs(ctx)
            if (current <= 0) {
                com.acsmanager.pro.util.Prefs.setEventThrottleMs(ctx, 300)
                actions.add("已优化事件节流（省电模式）")
            }
        } catch (t: Throwable) {}

        return actions
    }
}
