package com.acsmanager.pro.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.acsmanager.pro.selfguard.SelfGuardService
import com.acsmanager.pro.util.Prefs

/**
 * 开机/更新后自动拉起看门狗与无障碍自监控。
 *
 * 优化：开机广播后延迟 5 秒启动服务，避免系统服务未就绪时启动失败；
 * MY_PACKAGE_REPLACED（应用更新）延迟 3 秒，给系统时间处理更新后的组件状态。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AcsBoot"
        private const val BOOT_DELAY_MS = 5000L
        private const val UPDATE_DELAY_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val delay = if (action == Intent.ACTION_MY_PACKAGE_REPLACED) UPDATE_DELAY_MS else BOOT_DELAY_MS
            val appCtx = context.applicationContext
            Log.i(TAG, "scheduled service start after ${delay}ms on $action")
            handler.postDelayed({
                if (Prefs.isWatchdogEnabled(appCtx)) {
                    Log.i(TAG, "restart watchdog on $action")
                    WatchdogService.start(appCtx)
                }
                if (Prefs.isSelfGuardEnabled(appCtx)) {
                    Log.i(TAG, "restart self guard on $action")
                    SelfGuardService.start(appCtx)
                }
            }, delay)
        }
    }
}
