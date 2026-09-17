package com.acsmanager.pro.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.acsmanager.pro.selfguard.SelfGuardService
import com.acsmanager.pro.util.Prefs

/**
 * 开机/更新后自动拉起看门狗与无障碍自监控。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            if (Prefs.isWatchdogEnabled(context)) {
                Log.i("AcsBoot", "restart watchdog on $action")
                WatchdogService.start(context)
            }
            if (Prefs.isSelfGuardEnabled(context)) {
                Log.i("AcsBoot", "restart self guard on $action")
                SelfGuardService.start(context)
            }
        }
    }
}
