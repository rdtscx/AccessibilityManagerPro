package com.acsmanager.pro.quick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.acsmanager.pro.R
import com.acsmanager.pro.core.AccessServiceRepo
import com.acsmanager.pro.core.ServiceStateController
import com.acsmanager.pro.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面小部件：一键启停"磁贴目标服务"（与应用内设置联动）。
 */
class QuickToggleWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val target = Prefs.tileService(context)
        if (target.isNullOrBlank()) return
        val cn = ComponentName.unflattenFromString(target) ?: return
        val enabled = AccessServiceRepo.enabledStrings(context).contains(target)
        CoroutineScope(Dispatchers.Main.immediate).launch {
            ServiceStateController.setEnabled(context, cn, !enabled)
            val mgr = AppWidgetManager.getInstance(context)
            if (appWidgetId >= 0) {
                updateWidget(context, mgr, appWidgetId)
            } else {
                val ids = mgr.getAppWidgetIds(ComponentName(context, QuickToggleWidget::class.java))
                for (id in ids) updateWidget(context, mgr, id)
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.acsmanager.pro.action.WIDGET_TOGGLE"

        fun updateWidget(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            val target = Prefs.tileService(context)
            val on = !target.isNullOrBlank() && AccessServiceRepo.enabledStrings(context).contains(target)

            val shortLabel = target
                ?.let { ComponentName.unflattenFromString(it)?.shortClassName }
                ?: context.getString(R.string.settings_tile_target_none)
            views.setTextViewText(R.id.widget_label, shortLabel)
            views.setTextViewText(
                R.id.widget_status,
                context.getString(if (on) R.string.detail_enabled else R.string.detail_disabled)
            )
            views.setImageViewResource(R.id.widget_icon, if (on) R.drawable.ic_check else R.drawable.ic_tile)

            val pi = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                Intent(context, QuickToggleWidget::class.java)
                    .setAction(ACTION_TOGGLE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            mgr.updateAppWidget(appWidgetId, views)
        }
    }
}
