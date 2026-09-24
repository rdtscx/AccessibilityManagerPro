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
 * 开机/更新后自动拉起自监控服务。
 *
 * 优化（V3）：WatchdogService 已完全合并入 SelfGuardService（ContentObserver 驱动更省电），
 * 开机只需启动 SelfGuardService 一个前台服务，避免双服务并存浪费资源。
 * 延迟 5 秒启动，等系统服务就绪；MY_PACKAGE_REPLACED 延迟 3 秒处理更新后组件状态。
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
            Log.i(TAG, "scheduled self-guard start after ${delay}ms on $action")
            handler.postDelayed({
                // V5.2.2：开机/更新后先做授权通道联动——检测到任一授权通道（ADB 授予/Root/Shizuku）
                // 可用且自监控未开启时，自动开启"无障碍自监控（低耗电）"，无需用户操作。
                try {
                    SelfGuardService.enableIfChannelReady(appCtx)
                } catch (t: Throwable) {
                    Log.w(TAG, "auto enable self guard on boot failed", t)
                }
                // 自监控已合并看门狗全部功能：ContentObserver 实时监听 + 兜底慢检
                // 只需启动 SelfGuardService 一个前台服务，无需再启动 WatchdogService
                if (Prefs.isSelfGuardEnabled(appCtx)) {
                    Log.i(TAG, "restart self guard on $action")
                    SelfGuardService.start(appCtx)
                }
                // 兼容旧版用户开启了看门狗但没开自监控的情况：自动迁移到自监控
                else if (Prefs.isWatchdogEnabled(appCtx)) {
                    Log.i(TAG, "migrate watchdog -> self guard on $action")
                    Prefs.setSelfGuardEnabled(appCtx, true)
                    SelfGuardService.start(appCtx)
                }
            }, delay)
        }
    }
}
