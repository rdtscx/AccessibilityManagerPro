package com.acsmanager.pro.quick

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.appcompat.app.AlertDialog
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
 * 快捷设置磁贴：一键启停配置的"磁贴目标服务"。
 */
class QuickToggleTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val target = Prefs.tileService(this)
        if (target.isNullOrBlank()) {
            collapseAndOpenMain()
            return
        }
        val cn = ComponentName.unflattenFromString(target)
        if (cn == null) {
            collapseAndOpenMain()
            return
        }
        val currentlyEnabled = AccessServiceRepo.enabledStrings(this).contains(target)
        scope.launch {
            val result = ServiceStateController.setEnabled(this@QuickToggleTileService, cn, !currentlyEnabled)
            updateTile()
            if (!result.ok) {
                showDialog(
                    AlertDialog.Builder(this@QuickToggleTileService)
                        .setTitle(R.string.result_fail)
                        .setMessage(result.message)
                        .setPositiveButton(R.string.ok, null)
                        .create()
                )
            }
        }
    }

    /**
     * 收起快捷设置并打开主页面。
     * API 33+ 使用 PendingIntent 版本（startActivityAndCollapse(Intent) 已废弃）；
     * 低版本保持两参调用。
     */
    private fun collapseAndOpenMain() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val target = Prefs.tileService(this)
        val on = !target.isNullOrBlank() && AccessServiceRepo.enabledStrings(this).contains(target)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (on) R.string.detail_enabled else R.string.detail_disabled)
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
