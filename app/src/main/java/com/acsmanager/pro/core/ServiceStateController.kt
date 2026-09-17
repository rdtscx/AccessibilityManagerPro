package com.acsmanager.pro.core

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.acsmanager.pro.watchdog.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 无障碍服务启停控制器。
 * 原理：维护 enabled_accessibility_services（冒号分隔的组件串）并同步 accessibility_enabled，
 * 通过当前最佳授权通道写入。与原版一致。
 */
object ServiceStateController {

    private const val TAG = "AcsStateCtrl"
    private const val KEY_SERVICES = "enabled_accessibility_services"
    private const val KEY_ENABLED = "accessibility_enabled"

    data class Result(val ok: Boolean, val channel: Privilege.Channel, val message: String)

    /** 启用或停用单个服务。 */
    suspend fun setEnabled(ctx: Context, component: ComponentName, enabled: Boolean, log: Boolean = true): Result =
        withContext(Dispatchers.IO) {
            val current = AccessServiceRepo.enabledStrings(ctx).toMutableList()
            val target = component.flattenToString()
            val changed: Boolean
            if (enabled) {
                changed = target !in current
                if (changed) current.add(target)
            } else {
                changed = current.remove(target)
            }
            if (!changed) return@withContext Result(true, Privilege.bestChannel(ctx), "no_change")

            val result = writeServices(ctx, current)
            if (result.ok) {
                writeAccessibilityEnabled(ctx)
                if (log) {
                    EventLog.record(
                        ctx,
                        if (enabled) EventLog.TYPE_ENABLE else EventLog.TYPE_DISABLE,
                        component.packageName,
                        target
                    )
                }
            }
            result
        }

    /** 恢复丢失的多个服务（看门狗调用）。 */
    suspend fun restoreAll(ctx: Context, missingFlatten: List<String>): Result =
        withContext(Dispatchers.IO) {
            val current = AccessServiceRepo.enabledStrings(ctx).toMutableList()
            var changed = false
            for (f in missingFlatten) {
                if (f !in current) {
                    current.add(f)
                    changed = true
                }
            }
            if (!changed) return@withContext Result(true, Privilege.bestChannel(ctx), "no_change")
            val result = writeServices(ctx, current)
            if (result.ok) writeAccessibilityEnabled(ctx)
            result
        }

    /** 一键停用全部无障碍服务。 */
    suspend fun disableAll(ctx: Context): Result = withContext(Dispatchers.IO) {
        val result = writeServices(ctx, emptyList())
        if (result.ok) {
            try {
                when (Privilege.bestChannel(ctx)) {
                    Privilege.Channel.APP_GRANTED ->
                        Settings.Secure.putString(ctx.contentResolver, KEY_ENABLED, "0")
                    Privilege.Channel.ROOT ->
                        Privilege.runShell(arrayOf("su", "-c", "settings put secure $KEY_ENABLED 0"))
                    Privilege.Channel.SHIZUKU ->
                        Privilege.shizukuExec(ctx, "settings", "put", "secure", KEY_ENABLED, "0")
                    else -> Unit
                }
            } catch (t: Throwable) {
                Log.w(TAG, "disable accessibility_enabled failed", t)
            }
            EventLog.record(ctx, EventLog.TYPE_DISABLE, "system", "all services disabled")
        }
        result
    }

    private fun writeServices(ctx: Context, components: List<String>): Result {
        val value = components.joinToString(":")
        return when (val channel = Privilege.bestChannel(ctx)) {
            Privilege.Channel.APP_GRANTED -> {
                val ok = try {
                    Settings.Secure.putString(ctx.contentResolver, KEY_SERVICES, value)
                    true
                } catch (t: Throwable) {
                    Log.e(TAG, "direct write failed", t)
                    false
                }
                Result(ok, channel, if (ok) "ok" else "write failed")
            }
            Privilege.Channel.ROOT -> {
                val out = Privilege.runShell(arrayOf("su", "-c", "settings put secure $KEY_SERVICES '$value'"))
                // su 成功时 settings put 无输出（空串也算成功）；失败输出错误文本
                Result(isExecOk(out), channel, out ?: "exec failed")
            }
            Privilege.Channel.SHIZUKU -> {
                val out = Privilege.shizukuExec(ctx, "settings", "put", "secure", KEY_SERVICES, value)
                Result(isExecOk(out), channel, out ?: "exec failed")
            }
            else -> Result(false, channel, "no_privilege")
        }
    }

    /** shell 命令成功判定：输出为空 = 成功；含错误标记 = 失败。 */
    private fun isExecOk(out: String?): Boolean {
        if (out == null) return false
        val t = out.trim()
        if (t.isEmpty()) return true
        if (t.contains("error", ignoreCase = true)) return false
        if (t.startsWith("Exception") || t.startsWith("SecurityException")) return false
        return true
    }

    private fun writeAccessibilityEnabled(ctx: Context) {
        try {
            when (Privilege.bestChannel(ctx)) {
                Privilege.Channel.APP_GRANTED ->
                    Settings.Secure.putString(ctx.contentResolver, KEY_ENABLED, "1")
                Privilege.Channel.ROOT ->
                    Privilege.runShell(arrayOf("su", "-c", "settings put secure $KEY_ENABLED 1"))
                Privilege.Channel.SHIZUKU ->
                    Privilege.shizukuExec(ctx, "settings", "put", "secure", KEY_ENABLED, "1")
                else -> Unit
            }
        } catch (t: Throwable) {
            Log.w(TAG, "write accessibility_enabled failed", t)
        }
    }
}
