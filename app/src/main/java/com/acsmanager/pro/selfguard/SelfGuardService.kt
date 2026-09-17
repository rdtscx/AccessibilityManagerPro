package com.acsmanager.pro.selfguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.acsmanager.pro.R
import com.acsmanager.pro.core.AccessServiceRepo
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.core.ServiceStateController
import com.acsmanager.pro.ui.MainActivity
import com.acsmanager.pro.util.Prefs
import com.acsmanager.pro.watchdog.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 无障碍自监控前台服务（低耗电核心）。
 *
 * 原理：注册 [ContentObserver] 监听 Settings.Secure 数据库变更 —— 数据库只在真正变化时
 * 回调，零轮询、零常驻 CPU 开销（对比周期轮询的看门狗，这是"降低耗电"的关键实现）。
 *
 * 行为：当本应用的无障碍服务（[SelfAccessService]）被用户/系统关闭时：
 *  1. ContentObserver 立即收到变更回调；
 *  2. 延迟 [Prefs.selfGuardDelayMs]（默认 1000ms = 需求中的"延迟一秒"）；
 *  3. 通过当前最佳授权通道（ADB 授予的 WRITE_SECURE_SETTINGS / Root / Shizuku）
 *     把本组件写回 enabled_accessibility_services 并置 accessibility_enabled=1，立即拉起；
 *  4. 无任何授权通道时：发通知提醒 + 打开系统无障碍设置页（系统限制，无法静默自启）。
 */
class SelfGuardService : Service() {

    companion object {
        private const val TAG = "AcsSelfGuard"
        private const val CHANNEL_ID = "self_guard"
        private const val NOTIF_ID = 102
        const val ACTION_START = "com.acsmanager.pro.action.SELFGUARD_START"
        const val ACTION_STOP = "com.acsmanager.pro.action.SELFGUARD_STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, SelfGuardService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (t: Throwable) {
                // Android 14+ 后台启动前台服务受限（如开机广播），静默跳过，待用户打开应用时再启动
                Log.w(TAG, "startForegroundService blocked", t)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SelfGuardService::class.java))
        }

        fun isRunning(ctx: Context): Boolean {
            // activeNotifications 需要 API 23+，旧版本降级为 false（由 START_STICKY 保证重启）
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
            return try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.activeNotifications.any { it.id == NOTIF_ID }
            } catch (t: Throwable) {
                false
            }
        }

        fun selfComponent(ctx: Context): ComponentName =
            ComponentName(ctx, SelfAccessService::class.java)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var observerRegistered = false

    /** ContentObserver：Settings.Secure 数据库任何变更都会回调（低耗电关键）。 */
    private val secureObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            onSecureChanged()
        }
    }

    /** 兜底慢检：ContentObserver 意外失效时的最后防线（每 5 分钟一次，开销可忽略）。 */
    private val fallbackRunnable = object : Runnable {
        override fun run() {
            onSecureChanged()
            handler.postDelayed(this, 5 * 60_000L)
        }
    }

    private var restoringServices = mutableSetOf<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 服务合并：自监控已覆盖看门狗的恢复范围，避免两个保活前台服务并存（减服务、省内存）
        stopWatchdogIfRunning()
        createChannel()
        startAsForeground(buildNotification())
        if (!observerRegistered) {
            observerRegistered = true
            contentResolver.registerContentObserver(
                Settings.Secure.CONTENT_URI, true, secureObserver
            )
            handler.post(fallbackRunnable)
            Log.i(TAG, "ContentObserver registered on Settings.Secure")
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

    /** 看门狗与自监控功能合并：自监控运行时停掉看门狗，任意时刻最多一个保活前台服务。 */
    private fun stopWatchdogIfRunning() {
        try {
            if (com.acsmanager.pro.watchdog.WatchdogService.isRunning(this)) {
                stopService(Intent(this, com.acsmanager.pro.watchdog.WatchdogService::class.java))
            }
        } catch (t: Throwable) {
        }
    }

    /**
     * 检测本应用无障碍 + 所有需要保护的无障碍服务是否被关闭：
     *  自身、锁定（锁按钮）、看门狗保护名单、开机启动保活集合。
     * 被关闭则延迟 1 秒（可配）后通过通道立即拉起（无感保活）。
     */
    private fun onSecureChanged() {
        val enabled = AccessServiceRepo.enabledStrings(this)
        val toRestore = mutableListOf<String>()

        val self = selfComponent(this).flattenToString()
        if (self !in enabled) toRestore.add(self)
        // 锁定 + 看门狗保护 + 开机启动保活 合并去重
        val protected = mutableSetOf<String>()
        protected.addAll(Prefs.lockedAccessibilityServices(this))
        protected.addAll(Prefs.protectedServices(this))
        protected.addAll(Prefs.bootAccessibilityServices(this))
        for (flat in protected) {
            if (flat !in enabled && flat != self && flat !in toRestore) toRestore.add(flat)
        }
        if (toRestore.isEmpty()) return

        val pending = mutableListOf<String>()
        for (flat in toRestore) {
            if (flat in restoringServices) continue
            restoringServices.add(flat)
            pending.add(flat)
        }
        if (pending.isEmpty()) return

        EventLog.record(this, EventLog.TYPE_LOST, packageName, "accessibility off: ${pending.joinToString(",")}")
        val delay = Prefs.selfGuardDelayMs(this).coerceIn(100L, 30_000L)
        handler.postDelayed({ attemptRestore(pending) }, delay)
    }

    private fun attemptRestore(comps: List<String>) {
        for (c in comps) restoringServices.remove(c)
        val enabled = AccessServiceRepo.enabledStrings(this)
        val missing = comps.filter { it !in enabled }
        if (missing.isEmpty()) return
        val selfComp = selfComponent(this).flattenToString()

        scope.launch {
            // 通道探测可能执行 su 探测等耗时操作，放 IO 线程避免主线程 ANR
            val channel = withContext(Dispatchers.IO) {
                Privilege.bestChannel(this@SelfGuardService)
            }
            if (channel == Privilege.Channel.NONE) {
                // 无授权通道：自身被关闭时引导用户手动开启；锁定应用静默跳过
                if (selfComp in missing) {
                    notifyGuide()
                    openAccessibilitySettings()
                }
                EventLog.record(
                    this@SelfGuardService,
                    EventLog.TYPE_WARN, packageName, "restore blocked: no privilege"
                )
                return@launch
            }

            val r = withContext(Dispatchers.IO) {
                ServiceStateController.restoreAll(this@SelfGuardService, missing)
            }
            EventLog.record(
                this@SelfGuardService,
                if (r.ok) EventLog.TYPE_RESTORED else EventLog.TYPE_WARN,
                packageName,
                if (r.ok) "restored ${missing.size} service(s) via ${r.channel}"
                else "restore failed: ${r.message}"
            )
            if (!r.ok && selfComp in missing) notifyGuide()
        }
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_self_guard),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, SelfGuardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_keepalive)
            .setContentTitle(getString(R.string.self_guard_notif_title))
            .setContentText(getString(R.string.self_guard_notif_text))
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
            .build()
    }

    private fun notifyGuide() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.drawable.ic_keepalive)
            .setContentTitle(getString(R.string.self_guard_guide_title))
            .setContentText(getString(R.string.self_guard_guide_text))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID + 1, n)
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "open accessibility settings failed", t)
        }
    }

    override fun onDestroy() {
        if (observerRegistered) {
            try {
                contentResolver.unregisterContentObserver(secureObserver)
            } catch (t: Throwable) {
            }
            observerRegistered = false
        }
        handler.removeCallbacks(fallbackRunnable)
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
