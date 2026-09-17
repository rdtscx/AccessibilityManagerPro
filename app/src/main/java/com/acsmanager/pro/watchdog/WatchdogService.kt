package com.acsmanager.pro.watchdog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.acsmanager.pro.R
import com.acsmanager.pro.core.AccessServiceRepo
import com.acsmanager.pro.core.ServiceStateController
import com.acsmanager.pro.ui.MainActivity
import com.acsmanager.pro.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 保活看门狗（前台服务）。
 * 原理：周期读取 enabled_accessibility_services，若保活名单中的服务被系统重置/丢失，
 * 通过当前授权通道写回设置，实现"服务被系统强杀后自动恢复"。
 */
class WatchdogService : Service() {

    companion object {
        private const val TAG = "AcsWatchdog"
        private const val CHANNEL_ID = "watchdog"
        private const val NOTIF_ID = 101
        const val ACTION_START = "com.acsmanager.pro.action.WATCHDOG_START"
        const val ACTION_STOP = "com.acsmanager.pro.action.WATCHDOG_STOP"

        fun start(ctx: Context) {
            val intent = Intent(ctx, WatchdogService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (t: Throwable) {
                // Android 14+ 后台启动前台服务受限（如开机广播），静默跳过
                Log.w(TAG, "startForegroundService blocked", t)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WatchdogService::class.java))
        }

        fun isRunning(ctx: Context): Boolean {
            // activeNotifications 需要 API 23+，旧版本降级为 false（由 START_STICKY 保证重启）
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return try {
                val list = nm.activeNotifications
                list.any { it.id == NOTIF_ID }
            } catch (t: Throwable) {
                false
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            runCheck()
            handler.postDelayed(this, intervalMs())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 服务合并：自监控（ContentObserver + 兜底巡检）已覆盖看门狗恢复范围，
        // 自监控运行时本服务不启动，避免两个保活前台服务并存（减服务、省内存）。
        try {
            if (com.acsmanager.pro.selfguard.SelfGuardService.isRunning(this)) {
                Log.i(TAG, "SelfGuardService running; watchdog merged, skip self")
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (t: Throwable) {
        }
        createChannel()
        startAsForeground(buildNotification())
        if (!running) {
            running = true
            handler.post(checkRunnable)
        }
        return START_STICKY
    }

    /**
     * 前台服务启动兼容：
     *  - Android 14+（targetSdk 34）：必须显式传入与 manifest 匹配的服务类型，否则抛
     *    MissingForegroundServiceTypeException 导致应用闪退（"看门狗开关打开即闪退"根因）；
     *  - Android 9-13：三参重载可用，传 specialUse 常量无副作用；
     *  - Android 8 及以下：仅两参重载。
     */
    private fun startAsForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            startForeground(
                NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, n)
        }
    }

    private fun intervalMs(): Long =
        Prefs.watchdogIntervalMs(this).coerceIn(5_000L, 10 * 60_000L)

    private fun runCheck() {
        // 看门狗保护集合 = 用户自选保护 + 权限页"开机启动保活"勾选的服务
        val protected = (Prefs.protectedServices(this) + Prefs.bootAccessibilityServices(this)).toList()
        if (protected.isEmpty()) return
        val current = AccessServiceRepo.enabledStrings(this).toSet()
        val missing = protected.filter { it !in current }
        if (missing.isEmpty()) return

        EventLog.record(this, EventLog.TYPE_LOST, "system", "missing: ${missing.joinToString(", ")}")
        scope.launch {
            val result = ServiceStateController.restoreAll(this@WatchdogService, missing)
            for (m in missing) {
                EventLog.record(
                    this@WatchdogService,
                    if (result.ok) EventLog.TYPE_RESTORED else EventLog.TYPE_WARN,
                    m,
                    if (result.ok) "restored" else "failed: ${result.message}"
                )
            }
            if (result.ok) {
                notifyRestored(missing.size)
            } else {
                notifyFailed(missing.size, result.message)
            }
        }
    }

    // ---------- 通知 ----------
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_watchdog),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String? = null): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, WatchdogService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val count = Prefs.protectedServices(this).size
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.watchdog_notif_title))
            .setContentText(
                contentText ?: getString(R.string.watchdog_notif_text, count)
            )
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                // int 图标构造兼容 API 16+；Icon 三参构造需要 API 23+，低版本会崩溃
                @Suppress("DEPRECATION")
                Notification.Action.Builder(
                    0,
                    getString(R.string.watchdog_notif_stop),
                    stopPi
                ).build()
            )
        return builder.build()
    }

    private fun notifyRestored(count: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIF_ID,
            buildNotification(getString(R.string.watchdog_restored_notif, count))
        )
    }

    private fun notifyFailed(count: Int, reason: String) {
        Log.w(TAG, "restore failed: $reason")
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIF_ID,
            buildNotification(getString(R.string.watchdog_fail_notif, count))
        )
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(checkRunnable)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
