package com.acsmanager.pro.notif

import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.acsmanager.pro.R
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.util.Prefs
import com.acsmanager.pro.watchdog.EventLog

/**
 * 通知 0-5 级调控引擎（通知使用权）。
 *
 * 等级语义：
 *  0 完全屏蔽 —— 拦截并取消该应用全部通知（纯监听器能力，无需 Root）
 *  1 静默收纳 —— 拦截并取消，同时记录到本地历史（纯监听器能力）
 *  2 低打扰    —— 放行；有授权通道时尝试降为 IMPORTANCE_LOW
 *  3 标准      —— 放行（系统默认，不干预）
 *  4 高亮      —— 放行；有授权通道时尝试提升为 IMPORTANCE_HIGH
 *  5 最高      —— 放行；有授权通道时尝试提升为 IMPORTANCE_MAX
 *
 * 精细调控（2/4/5 的升降级）需要授权通道（ADB 授予的 WRITE_SECURE_SETTINGS / Root /
 * Shizuku）且系统为 Android 14+（cmd notification set_importance_override）。
 * 无通道时自动降级为"放行"，UI 中会明确提示。
 */
class NotifGateService : NotificationListenerService() {

    companion object {
        private const val TAG = "AcsNotifGate"

        fun isEnabled(ctx: android.content.Context): Boolean {
            // API 27+：系统 API 检测
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                return try {
                    val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                            as NotificationManager
                    nm.isNotificationListenerAccessGranted(
                        android.content.ComponentName(ctx, NotifGateService::class.java)
                    )
                } catch (t: Throwable) {
                    false
                }
            }
            // API 21-26：从 Settings.Secure 的 enabled_notification_listeners 字符串判断
            return try {
                val raw = android.provider.Settings.Secure.getString(
                    ctx.contentResolver, "enabled_notification_listeners"
                ) ?: return false
                raw.split(':').any {
                    it == android.content.ComponentName(ctx, NotifGateService::class.java).flattenToString()
                }
            } catch (t: Throwable) {
                false
            }
        }

        fun levelLabelRes(level: Int): Int = when (level) {
            0 -> R.string.notif_level_0
            1 -> R.string.notif_level_1
            2 -> R.string.notif_level_2
            3 -> R.string.notif_level_3
            4 -> R.string.notif_level_4
            5 -> R.string.notif_level_5
            else -> R.string.notif_level_3
        }

        fun levelDescRes(level: Int): Int = when (level) {
            0 -> R.string.notif_level_0_desc
            1 -> R.string.notif_level_1_desc
            2 -> R.string.notif_level_2_desc
            3 -> R.string.notif_level_3_desc
            4 -> R.string.notif_level_4_desc
            5 -> R.string.notif_level_5_desc
            else -> R.string.notif_level_3_desc
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val level = Prefs.notifLevel(this, sbn.packageName)
        when {
            level == 0 -> {
                // 完全屏蔽：直接拦截取消
                cancelNotification(sbn.key)
                Prefs.bumpNotifBlock(this)
                Prefs.appendNotifHistory(this, sbn.packageName, "block")
                EventLog.record(this, EventLog.TYPE_DISABLE, sbn.packageName, "notification blocked (L0)")
            }
            level == 1 -> {
                // 静默收纳：拦截取消 + 记录历史
                cancelNotification(sbn.key)
                Prefs.appendNotifHistory(this, sbn.packageName, "silent")
                EventLog.record(this, EventLog.TYPE_SERVICE, sbn.packageName, "notification silenced (L1)")
            }
            else -> {
                // 2-5：尝试精细调控（升降级）
                if (Prefs.notifOverrideEnabled(this)) {
                    tryImportance(sbn, level)
                }
            }
        }
    }

    private fun tryImportance(sbn: StatusBarNotification, level: Int) {
        if (level == 3) return // 标准档无需干预
        val channel = Privilege.bestChannel(this)
        if (channel == Privilege.Channel.NONE) return

        val pkg = sbn.packageName
        // StatusBarNotification.getUid 需要 API 29；低版本无 uid 信息，无法下发 override，直接放行
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val uid = sbn.uid
        val cmd = arrayOf("cmd", "notification", "set_importance_override", pkg, uid.toString(), level.toString())

        val out: String? = when (channel) {
            Privilege.Channel.APP_GRANTED -> {
                // APP_GRANTED 只代表能写 Settings.Secure，不能直接执行 shell 命令，
                // 精细调控降级为放行（系统层面无法静默降级对方通知）。
                return
            }
            // Root / Shizuku 统一走 execShell（参数经 shell 转义）
            Privilege.Channel.ROOT, Privilege.Channel.SHIZUKU ->
                Privilege.execShell(this, *cmd)
            else -> null
        }
        // shell 命令成功时输出为空；含 error 或 Exception 才是失败
        val trimmed = out?.trim()
        val failed = trimmed == null ||
            trimmed.contains("error", ignoreCase = true) ||
            trimmed.startsWith("Exception")
        if (failed) {
            Log.w(TAG, "importance override failed for $pkg, output=${out ?: "(null)"}")
            EventLog.record(this, EventLog.TYPE_WARN, pkg, "override failed (need Android 14+ / shell channel)")
        } else {
            EventLog.record(this, EventLog.TYPE_SERVICE, pkg, "importance override -> L$level")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "notification listener connected")
    }
}
