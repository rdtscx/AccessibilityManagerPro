package com.acsmanager.pro.selfguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.acsmanager.pro.keepalive.KeepAliveEngine
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

        /** 本应用自身正在执行开关操作的服务集合（冷却期内忽略 ContentObserver 回调，避免误报丢失）。 */
        private val selfOperating: MutableSet<String> =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())
        private const val SELF_OP_COOLDOWN_MS = 3000L
        private val selfOpHandler = android.os.Handler(android.os.Looper.getMainLooper())

        /** 标记本应用正在操作某个服务（开启/关闭），操作后冷却期内不触发丢失检测。 */
        fun markSelfOperating(flatten: String) {
            selfOperating.add(flatten)
            selfOpHandler.removeCallbacksAndMessages(flatten)
            selfOpHandler.postDelayed({ selfOperating.remove(flatten) }, SELF_OP_COOLDOWN_MS)
        }

        /** 查询服务是否在本应用自身操作的冷却期内。 */
        fun isSelfOperating(flatten: String): Boolean = flatten in selfOperating

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
    private var screenReceiverRegistered = false

    /** 屏幕状态广播：熄屏进入休眠（暂停保活巡检），亮屏恢复。 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> enterDoze()
                Intent.ACTION_SCREEN_ON -> exitDoze()
            }
        }
    }

    /** 熄屏休眠：暂停应用保活巡检，自监控兜底巡检降频至 15 分钟。 */
    private fun enterDoze() {
        if (!Prefs.dozeModeEnabled(this)) return
        Prefs.setDozeActive(true)
        KeepAliveEngine.pause()
        handler.removeCallbacks(fallbackRunnable)
        handler.postDelayed(fallbackRunnable, 15 * 60_000L)
        Log.i(TAG, "doze: screen off, keepalive paused, fallback 15min")
    }

    /** 亮屏恢复：恢复应用保活巡检，自监控兜底巡检恢复 5 分钟。 */
    private fun exitDoze() {
        if (!Prefs.isDozeActive()) return
        Prefs.setDozeActive(false)
        KeepAliveEngine.resume()
        handler.removeCallbacks(fallbackRunnable)
        handler.postDelayed(fallbackRunnable, 5 * 60_000L)
        Log.i(TAG, "doze: screen on, keepalive resumed, fallback 5min")
    }

    /** ContentObserver：Settings.Secure 数据库任何变更都会回调（低耗电关键）。 */
    private val secureObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // 防抖：连续变更时合并为一次检测，避免用户操作开关期间频繁触发恢复造成竞态覆盖
            handler.removeCallbacks(debounceRunnable)
            handler.postDelayed(debounceRunnable, 500L)
        }
    }

    /** 防抖后的实际检测入口。 */
    private val debounceRunnable = Runnable { onSecureChanged() }

    /** 兜底慢检：ContentObserver 意外失效时的最后防线。亮屏 5 分钟，熄屏休眠 15 分钟。 */
    private val fallbackRunnable = object : Runnable {
        override fun run() {
            onSecureChanged()
            val interval = if (Prefs.isDozeActive()) 15 * 60_000L else 5 * 60_000L
            handler.postDelayed(this, interval)
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
        // 注册屏幕状态监听（熄屏休眠 / 亮屏恢复）
        if (!screenReceiverRegistered) {
            screenReceiverRegistered = true
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            registerReceiver(screenReceiver, filter)
            Log.i(TAG, "Screen state receiver registered")
        }
        // 启动后立即检测一次：覆盖开机、服务重启、应用从后台恢复等场景，
        // 确保锁定/开机保活的服务在任何情况下被关闭后都能被拉起。
        handler.post { onSecureChanged() }
        return START_STICKY
    }

    /**
     * 前台服务启动兼容：
     *  - Android 14+（targetSdk 34）：必须显式传入与 manifest 匹配的服务类型，否则抛
     *    MissingForegroundServiceTypeException 导致应用闪退；
     *  - Android 13 及以下：两参重载即可（FOREGROUND_SERVICE_TYPE_NONE 已在 API 34 废弃）。
     */
    private fun startAsForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
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
     * 被关闭则延迟后通过通道立即拉起（无感保活）。
     *
     * 修复要点：
     *  - 丢失事件按服务逐条记录，pkg 字段写入被关闭服务的真实包名（而非本应用包名），
     *    便于事件监控页准确显示"哪个软件的无障碍被关闭"；
     *  - 本应用自身操作冷却期内的服务不判定为丢失，避免开关回写触发误报；
     *  - 非自身服务使用更短的恢复延迟（300ms），自身服务保持用户可配延迟。
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
            if (flat !in enabled && flat != self && flat !in toRestore) {
                // 本应用自身正在操作的服务（冷却期内）不判定为丢失，避免误报
                if (isSelfOperating(flat)) continue
                // 用户主动关闭的服务在冷却期内不恢复，让用户能正常关闭
                if (Prefs.isUserDisabledCooldown(this, flat)) continue
                toRestore.add(flat)
            }
        }
        if (toRestore.isEmpty()) return

        val pending = mutableListOf<String>()
        for (flat in toRestore) {
            if (flat in restoringServices) continue
            restoringServices.add(flat)
            pending.add(flat)
        }
        if (pending.isEmpty()) return

        // 逐条记录丢失事件：pkg = 被关闭服务的包名，detail = 完整组件名
        for (flat in pending) {
            val pkg = flat.substringBefore('/').takeIf { it.isNotBlank() } ?: flat
            EventLog.record(this, EventLog.TYPE_LOST, pkg, "accessibility off: $flat")
        }

        // 恢复延迟策略：自身服务用用户配置的延迟（默认1s，给系统处理时间）；
        // 其他被锁定/保活的服务用更短的 300ms，减少服务中断空窗期。
        val hasSelf = self in pending
        val delay = if (hasSelf && pending.size == 1) {
            Prefs.selfGuardDelayMs(this).coerceIn(100L, 30_000L)
        } else {
            Prefs.selfGuardDelayMs(this).coerceIn(100L, 30_000L).coerceAtMost(500L)
        }
        handler.postDelayed({ attemptRestore(pending, attempt = 1) }, delay)
    }

    /**
     * 尝试恢复丢失的无障碍服务。
     *
     * 修复要点：
     *  - 写入后立即回读验证，确认服务真正出现在 enabled_accessibility_services 中；
     *  - 恢复失败时自动重试（最多 3 次，指数退避 500ms/1s/2s），应对系统瞬时拒绝；
     *  - 恢复事件按服务逐条记录，pkg = 被恢复服务的包名，detail 含恢复通道与结果；
     *  - 单个服务恢复失败不影响其他服务，逐条处理提高整体成功率。
     */
    private fun attemptRestore(comps: List<String>, attempt: Int = 1) {
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
                for (flat in missing) {
                    val pkg = flat.substringBefore('/').takeIf { it.isNotBlank() } ?: flat
                    EventLog.record(
                        this@SelfGuardService, EventLog.TYPE_WARN, pkg,
                        "restore blocked: no privilege"
                    )
                }
                return@launch
            }

            // 逐条恢复 + 回读验证，单个失败不影响其他
            val successList = mutableListOf<String>()
            val failList = mutableListOf<Pair<String, String>>()

            for (flat in missing) {
                val r = withContext(Dispatchers.IO) {
                    ServiceStateController.restoreOne(this@SelfGuardService, flat)
                }
                if (r.ok) {
                    successList.add(flat)
                } else {
                    failList.add(flat to (r.message ?: "unknown"))
                }
            }

            // 逐条记录恢复结果事件
            for (flat in successList) {
                val pkg = flat.substringBefore('/').takeIf { it.isNotBlank() } ?: flat
                EventLog.record(
                    this@SelfGuardService, EventLog.TYPE_RESTORED, pkg,
                    "restored via $channel"
                )
            }
            for ((flat, msg) in failList) {
                val pkg = flat.substringBefore('/').takeIf { it.isNotBlank() } ?: flat
                EventLog.record(
                    this@SelfGuardService, EventLog.TYPE_WARN, pkg,
                    "restore failed (attempt $attempt/3): $msg"
                )
            }

            // 恢复成功通知
            if (successList.isNotEmpty()) {
                notifyRestored(successList.size)
            }

            // 失败重试：最多 3 次，指数退避
            if (failList.isNotEmpty() && attempt < 3) {
                val retryDelay = when (attempt) {
                    1 -> 500L
                    2 -> 1000L
                    else -> 2000L
                }
                val retryComps = failList.map { it.first }
                for (c in retryComps) restoringServices.add(c)
                Log.w(TAG, "retry restore (attempt ${attempt + 1}) after ${retryDelay}ms: $retryComps")
                handler.postDelayed({ attemptRestore(retryComps, attempt + 1) }, retryDelay)
            } else if (failList.isNotEmpty() && selfComp in failList.map { it.first }) {
                // 自身服务最终恢复失败时引导用户
                notifyGuide()
            }
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

    private fun buildNotification(contentText: String? = null): Notification {
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
            .setContentText(contentText ?: getString(R.string.self_guard_notif_text))
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

    /** 恢复成功后更新前台通知文字，让用户感知到保活动作。 */
    private fun notifyRestored(count: Int) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(getString(R.string.self_guard_restored_text, count)))
        } catch (t: Throwable) {
            Log.w(TAG, "notifyRestored failed", t)
        }
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

    /**
     * 用户从最近任务中移除应用时，重启自监控服务，确保持续运行。
     * 部分厂商系统会在移除任务时杀死服务，此方法作为兜底重启。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "task removed, restarting self guard")
        val restartIntent = Intent(this, SelfGuardService::class.java)
            .setAction(ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "restart on task removed failed", t)
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
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (t: Throwable) {
            }
            screenReceiverRegistered = false
        }
        Prefs.setDozeActive(false)
        handler.removeCallbacks(fallbackRunnable)
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
