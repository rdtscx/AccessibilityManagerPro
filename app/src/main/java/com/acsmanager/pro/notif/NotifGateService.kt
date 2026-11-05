package com.acsmanager.pro.notif

import android.app.NotificationManager
import android.content.Context
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


        /**
         * 应用内等级 0~5 → Android 通知重要性真实值。
         *
         * 系统侧可下发的值只有 0(NONE 不显示) / 1(MIN 无声小图标) /
         * 2(LOW 无横幅) / 3(DEFAULT 有声) / 4(HIGH 横幅)，共五档。
         * 应用内第 3 档"标准"对应不干预（清除覆盖），第 5 档系统最高即 HIGH。
         *
         * @return -1 表示清除覆盖、交回系统默认；否则为 0~4。
         */
        fun importanceForLevel(level: Int): Int = when (level) {
            0 -> 0
            1 -> 1
            2 -> 2
            3 -> -1
            4 -> 4
            5 -> 4
            else -> -1
        }

        /**
         * 用户在界面修改等级后立即后台下发，不必等到下一条通知。
         * 由 UI 线程外调用（内部自行起线程）。
         */
        fun applyLevelNow(ctx: Context, pkg: String, channelId: String, level: Int) {
            if (pkg.isBlank()) return
            Thread {
                try {
                    var uid = -1
                    try {
                        uid = ctx.packageManager.getPackageUid(pkg, 0)
                    } catch (t: Throwable) {
                        Log.w(TAG, "getPackageUid failed for $pkg", t)
                    }
                    runImportance(ctx, pkg, uid, channelId, level)
                } catch (t: Throwable) {
                    Log.w(TAG, "applyLevelNow failed", t)
                }
            }.apply {
                isDaemon = true
                name = "acs-notif-apply"
            }.start()
        }

        /** 实际执行重要性下发：先渠道级，失败退包级。可在任意 Context 上调用。 */
        internal fun runImportance(ctx: Context, pkg: String, uid: Int, channelId: String, level: Int) {
            val importance = importanceForLevel(level)
            val channel = Privilege.bestChannel(ctx)
            if (channel == Privilege.Channel.NONE) return
            if (channel == Privilege.Channel.APP_GRANTED) return

            if (channelId.isNotBlank()) {
                val imp = if (importance < 0) 3 else importance
                val chCmd = arrayOf("cmd", "notification", "channel", "set_importance",
                    "$pkg/$channelId", imp.toString())
                val outCh = Privilege.execShell(ctx, *chCmd)
                if (!outCh.isNullOrBlank() &&
                    (outCh.contains("error", true) || outCh.startsWith("Exception"))) {
                    Log.w(TAG, "channel importance failed: $outCh")
                } else {
                    EventLog.record(ctx, EventLog.TYPE_SERVICE, pkg, "channel $channelId importance -> $imp")
                    return
                }
            }

            if (android.os.Build.VERSION.SDK_INT < 29 || uid < 0) return
            val cmd = arrayOf("cmd", "notification", "set_importance_override",
                pkg, uid.toString(), importance.toString())
            val out = Privilege.execShell(ctx, *cmd)
            val trimmed = out?.trim()
            val failed = trimmed == null ||
                trimmed.contains("error", ignoreCase = true) ||
                trimmed.startsWith("Exception")
            if (failed) {
                Log.w(TAG, "importance override failed for $pkg, output=$out")
                EventLog.record(ctx, EventLog.TYPE_WARN, pkg, "override failed")
            } else {
                EventLog.record(ctx, EventLog.TYPE_SERVICE, pkg, "importance -> $importance")
            }
        }

    }

    /**
     * 通知回调工作线程（单线程串行）。
     *
     * 关键修复（7.0.1）：onNotificationPosted 运行在主线程，原实现直接在此做
     *  - JSON 历史读写（每条通知一次全量序列化）
     *  - Privilege.bestChannel() → 可能 fork su / 绑定 Shizuku（最坏阻塞数秒）
     *  - shell 升降级命令
     * 通知高峰时主线程被反复阻塞 → ANR → 进程被杀（"偶然闪退"）。
     * 现在主线程只做轻量字段提取 + 取消动作，其余全部转交本线程。
     */
    private val workExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "acs-notif-work").apply { isDaemon = true }
        }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        val pkg = sbn.packageName ?: return
        val channelId = try { sbn.notification?.channelId ?: "" } catch (t: Throwable) { "" }
        // 提取标题和正文（供历史列表展示）——只在主线程做取字符串的轻量操作
        val title = try {
            sbn.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
        } catch (t: Throwable) { "" }
        val text = try {
            sbn.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        } catch (t: Throwable) { "" }
        val key = try { sbn.key } catch (t: Throwable) { null }
        val uid = if (android.os.Build.VERSION.SDK_INT >= 29) {
            try { sbn.uid } catch (t: Throwable) { -1 }
        } else -1

        // 关键词/渠道隐藏属于"必须立刻拦"的动作，留在主线程保证时效
        try {
            if (Prefs.notifMatchesFilter(this, pkg, title, text)) {
                if (key != null) cancelNotification(key)
                Prefs.bumpNotifBlock(this)
                EventLog.record(this, EventLog.TYPE_DISABLE, pkg, "notification blocked by keyword")
                return
            }
            if (Prefs.isChannelHidden(this, pkg, channelId)) {
                if (key != null) cancelNotification(key)
                Prefs.bumpNotifBlock(this)
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "immediate filter failed", t)
        }

        val level = try {
            val channelLevel = Prefs.notifChannelLevel(this, pkg, channelId)
            if (channelLevel >= 0) channelLevel else Prefs.notifLevel(this, pkg)
        } catch (t: Throwable) { 3 }

        if (level == 0) {
            // 完全屏蔽：立刻取消（作为系统 NONE 覆盖的双保险），历史与日志转后台
            if (key != null) {
                try { cancelNotification(key) } catch (t: Throwable) {
                }
            }
            Prefs.bumpNotifBlock(this)
        }

        // 其余（历史记录、升降级 shell）全部后台串行执行
        try {
            workExecutor.execute {
                try {
                    handleOffMain(pkg, key, channelId, title, text, uid, level)
                } catch (t: Throwable) {
                    Log.w(TAG, "notif work failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "notif executor rejected", t)
        }
    }

    /** 后台处理：历史落盘 + 分级记录 + 升降级 shell。 */
    private fun handleOffMain(
        pkg: String,
        key: String?,
        channelId: String,
        title: String,
        text: String,
        uid: Int,
        level: Int
    ) {
        // 记录完整通知历史（二级页列表用）
        try {
            Prefs.appendNotifHistoryFull(this, pkg, channelId, title, text)
        } catch (t: Throwable) {
            Log.w(TAG, "append history failed", t)
        }

        when (level) {
            0 -> {
                Prefs.appendNotifHistory(this, pkg, "block")
                EventLog.record(this, EventLog.TYPE_DISABLE, pkg, "notification blocked (L0)")
            }
            1 -> {
                Prefs.appendNotifHistory(this, pkg, "silent")
                EventLog.record(this, EventLog.TYPE_SERVICE, pkg, "notification silenced (L1)")
            }
        }
        // 所有档位（含 0/1）都尝试把等级真实下发到系统，实时生效
        if (Prefs.notifOverrideEnabled(this)) {
            tryImportance(pkg, uid, channelId, level)
        }
    }



    private fun tryImportance(pkgIn: String, uidIn: Int, channelId: String, levelIn: Int) {
        runImportance(this, pkgIn, uidIn, channelId, levelIn)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "notification listener connected")
    }
}
