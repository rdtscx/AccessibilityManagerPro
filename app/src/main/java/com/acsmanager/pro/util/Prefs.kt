package com.acsmanager.pro.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Prefs {

    private const val NAME = "acs_pro"
    private const val APP_TAG = "acs_manager_pro"

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---------- 看门狗 ----------
    fun isWatchdogEnabled(ctx: Context): Boolean =
        get(ctx).getBoolean("watchdog_enabled", false)

    fun setWatchdogEnabled(ctx: Context, v: Boolean) {
        get(ctx).edit().putBoolean("watchdog_enabled", v).apply()
    }

    fun watchdogIntervalMs(ctx: Context): Long =
        get(ctx).getLong("watchdog_interval", 60_000L)

    fun setWatchdogIntervalMs(ctx: Context, v: Long) {
        get(ctx).edit().putLong("watchdog_interval", v).apply()
    }

    fun protectedServices(ctx: Context): Set<String> =
        get(ctx).getStringSet("protected", emptySet()) ?: emptySet()

    fun setProtectedServices(ctx: Context, v: Set<String>) {
        get(ctx).edit().putStringSet("protected", v.toSet()).apply()
    }

    // ---------- 快捷磁贴 ----------
    fun tileService(ctx: Context): String? =
        get(ctx).getString("tile_service", null)

    fun setTileService(ctx: Context, v: String?) {
        get(ctx).edit().putString("tile_service", v).apply()
    }

    // ---------- 外观 ----------
    fun themeMode(ctx: Context): String =
        get(ctx).getString("theme", "system") ?: "system"

    fun setThemeMode(ctx: Context, v: String) {
        get(ctx).edit().putString("theme", v).apply()
    }

    fun language(ctx: Context): String =
        get(ctx).getString("language", "system") ?: "system"

    fun setLanguage(ctx: Context, v: String) {
        get(ctx).edit().putString("language", v).apply()
    }

    // ---------- 备份 / 恢复 ----------
    fun exportJson(ctx: Context): String {
        val o = JSONObject()
        o.put("app", APP_TAG)
        o.put("version", 2)
        // 看门狗
        o.put("watchdog_enabled", isWatchdogEnabled(ctx))
        o.put("watchdog_interval", watchdogIntervalMs(ctx))
        o.put("protected", setToJson(protectedServices(ctx)))
        o.put("tile_service", tileService(ctx) ?: "")
        // 自监控
        o.put("self_guard_enabled", isSelfGuardEnabled(ctx))
        o.put("self_guard_delay", selfGuardDelayMs(ctx))
        // 应用保活
        o.put("keepalive_apps", setToJson(keepAliveApps(ctx)))
        o.put("keepalive_interval", keepAliveIntervalMs(ctx))
        o.put("keepalive_screenoff_only", keepAliveScreenOffOnly(ctx))
        o.put("keepalive_pause_low_battery", keepAlivePauseLowBattery(ctx))
        o.put("keepalive_battery_threshold", keepAliveBatteryThreshold(ctx))
        o.put("keepalive_black_screen", keepAliveBlackScreen(ctx))
        o.put("keepalive_return_mode", keepAliveReturnMode(ctx))
        // 通知
        o.put("notif_levels", get(ctx).getString(KEY_NOTIF_LEVELS, "{}") ?: "{}")
        o.put("notif_override_enabled", notifOverrideEnabled(ctx))
        o.put("notif_history_enabled", notifHistoryEnabled(ctx))
        // 无障碍锁定与开机保活
        o.put("locked_accessibility", setToJson(lockedAccessibilityServices(ctx)))
        o.put("boot_accessibility", setToJson(bootAccessibilityServices(ctx)))
        // 保活提示
        o.put("relaunch_toast", relaunchToastEnabled(ctx))
        o.put("relaunch_toast_text", relaunchToastText(ctx) ?: "")
        // 外观
        o.put("theme", themeMode(ctx))
        o.put("language", language(ctx))
        // 无障碍权限页
        o.put("accessperm_user_only", accessPermUserOnly(ctx))
        // 实验
        o.put("event_throttle", eventThrottleMs(ctx))
        // 从最近应用列表隐藏
        o.put("hide_from_recents", hideFromRecents(ctx))
        return o.toString(2)
    }

    fun importJson(ctx: Context, json: String): Boolean {
        return try {
            val o = JSONObject(json)
            if (o.optString("app") != APP_TAG) return false
            val ver = o.optInt("version", 1)
            // 看门狗
            setWatchdogEnabled(ctx, o.optBoolean("watchdog_enabled", false))
            setWatchdogIntervalMs(ctx, o.optLong("watchdog_interval", 60_000L))
            setProtectedServices(ctx, jsonToSet(o.optJSONObject("protected")))
            setTileService(ctx, o.optString("tile_service", "").ifBlank { null })
            // v2+ 字段（v1 备份中不存在时使用默认值，不覆盖用户当前设置）
            if (ver >= 2) {
                setSelfGuardEnabled(ctx, o.optBoolean("self_guard_enabled", false))
                setSelfGuardDelayMs(ctx, o.optLong("self_guard_delay", 1000L))
                setKeepAliveApps(ctx, jsonToSet(o.optJSONObject("keepalive_apps")))
                setKeepAliveIntervalMs(ctx, o.optLong("keepalive_interval", 30_000L))
                setKeepAliveScreenOffOnly(ctx, o.optBoolean("keepalive_screenoff_only", true))
                setKeepAlivePauseLowBattery(ctx, o.optBoolean("keepalive_pause_low_battery", true))
                setKeepAliveBatteryThreshold(ctx, o.optInt("keepalive_battery_threshold", 15))
                setKeepAliveBlackScreen(ctx, o.optBoolean("keepalive_black_screen", true))
                setKeepAliveReturnMode(ctx, o.optString("keepalive_return_mode", "prev"))
                o.optString("notif_levels").takeIf { it.isNotBlank() }?.let {
                    get(ctx).edit().putString(KEY_NOTIF_LEVELS, it).apply()
                }
                setNotifOverrideEnabled(ctx, o.optBoolean("notif_override_enabled", true))
                setNotifHistoryEnabled(ctx, o.optBoolean("notif_history_enabled", true))
                setLockedAccessibilityServices(ctx, jsonToSet(o.optJSONObject("locked_accessibility")))
                setBootAccessibilityServices(ctx, jsonToSet(o.optJSONObject("boot_accessibility")))
                setRelaunchToastEnabled(ctx, o.optBoolean("relaunch_toast", false))
                setRelaunchToastText(ctx, o.optString("relaunch_toast_text", "").ifBlank { null })
                setThemeMode(ctx, o.optString("theme", "system"))
                setLanguage(ctx, o.optString("language", "system"))
                setAccessPermUserOnly(ctx, o.optBoolean("accessperm_user_only", false))
                setEventThrottleMs(ctx, o.optLong("event_throttle", 300L))
                setHideFromRecents(ctx, o.optBoolean("hide_from_recents", false))
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun setToJson(set: Set<String>): JSONObject = JSONObject().apply {
        set.forEachIndexed { i, s -> put(i.toString(), s) }
    }

    private fun jsonToSet(obj: JSONObject?): Set<String> {
        if (obj == null) return emptySet()
        val result = mutableSetOf<String>()
        val it = obj.keys()
        while (it.hasNext()) {
            val v = obj.optString(it.next())
            if (v.isNotBlank()) result.add(v)
        }
        return result
    }

    // ---------- 无障碍自监控（ContentObserver 低耗电 + 延迟拉起） ----------

    fun isSelfGuardEnabled(ctx: Context): Boolean =
        get(ctx).getBoolean("self_guard_enabled", false)

    fun setSelfGuardEnabled(ctx: Context, v: Boolean) {
        get(ctx).edit().putBoolean("self_guard_enabled", v).apply()
    }

    /** 检测到无障碍被关闭后，延迟多久尝试拉起（毫秒，默认 1000 = 1 秒）。 */
    fun selfGuardDelayMs(ctx: Context): Long =
        get(ctx).getLong("self_guard_delay", 1000L)

    fun setSelfGuardDelayMs(ctx: Context, v: Long) {
        get(ctx).edit().putLong("self_guard_delay", v.coerceIn(100L, 30_000L)).apply()
    }

    // ---------- 应用保活 ----------

    fun keepAliveApps(ctx: Context): Set<String> =
        get(ctx).getStringSet("keepalive_apps", emptySet()) ?: emptySet()

    fun setKeepAliveApps(ctx: Context, v: Set<String>) {
        get(ctx).edit().putStringSet("keepalive_apps", v.toSet()).apply()
    }

    fun isKeepAlive(ctx: Context, pkg: String): Boolean = pkg in keepAliveApps(ctx)

    /** 保活巡检间隔（毫秒）。 */
    fun keepAliveIntervalMs(ctx: Context): Long =
        get(ctx).getLong("keepalive_interval", 30_000L)

    fun setKeepAliveIntervalMs(ctx: Context, v: Long) {
        get(ctx).edit().putLong("keepalive_interval", v.coerceIn(10_000L, 300_000L)).apply()
    }

    /** 实验开关：仅在屏幕关闭/用户闲置时后台无感拉起（默认开，不影响用户正在操作）。 */
    fun keepAliveScreenOffOnly(ctx: Context): Boolean =
        get(ctx).getBoolean("keepalive_screenoff_only", true)

    fun setKeepAliveScreenOffOnly(ctx: Context, v: Boolean) {
        get(ctx).edit().putBoolean("keepalive_screenoff_only", v).apply()
    }

    /** 实验开关：低电量时暂停保活。 */
    fun keepAlivePauseLowBattery(ctx: Context): Boolean =
        get(ctx).getBoolean("keepalive_pause_low_battery", true)

    fun setKeepAlivePauseLowBattery(ctx: Context, v: Boolean) {
        get(ctx).edit().putBoolean("keepalive_pause_low_battery", v).apply()
    }

    /** 低电量暂停阈值（百分比）。 */
    fun keepAliveBatteryThreshold(ctx: Context): Int =
        get(ctx).getInt("keepalive_battery_threshold", 15)

    fun setKeepAliveBatteryThreshold(ctx: Context, v: Int) {
        get(ctx).edit().putInt("keepalive_battery_threshold", v.coerceIn(5, 50)).apply()
    }

    /** 单应用拉起冷却（毫秒），防止与用户操作冲突形成循环。 */
    fun keepAliveCooldownMs(ctx: Context): Long =
        get(ctx).getLong("keepalive_cooldown", 90_000L)

    /** 黑屏（熄屏）状态下被杀后台时，是否也拉起保活应用（默认开）。 */
    fun keepAliveBlackScreen(ctx: Context): Boolean =
        get(ctx).getBoolean("keepalive_black_screen", true)

    fun setKeepAliveBlackScreen(ctx: Context, v: Boolean) {
        get(ctx).edit().putBoolean("keepalive_black_screen", v).apply()
    }

    /**
     * 拉起后的返回方案（"返回上一个应用时，提供几种返回方案供用户选择"）：
     *  - prev：拉起后 100ms 自动切回用户上一个软件（无感保活，默认）
     *  - stay：拉起后停留在目标软件
     *  - home：拉起后 100ms 返回桌面
     */
    fun keepAliveReturnMode(ctx: Context): String =
        get(ctx).getString("keepalive_return_mode", "prev") ?: "prev"

    fun setKeepAliveReturnMode(ctx: Context, v: String) {
        get(ctx).edit().putString("keepalive_return_mode", v).apply()
    }

    // ---------- 统计 ----------

    private fun today(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return sdf.format(java.util.Date())
    }

    fun relaunchCount(ctx: Context): Int {
        val d = get(ctx).getString("relaunch_date", "")
        return if (d == today()) get(ctx).getInt("relaunch_count", 0) else 0
    }

    fun bumpRelaunch(ctx: Context) {
        val e = get(ctx).edit()
        val d = get(ctx).getString("relaunch_date", "")
        val c = if (d == today()) get(ctx).getInt("relaunch_count", 0) else 0
        e.putString("relaunch_date", today())
        e.putInt("relaunch_count", c + 1)
        e.apply()
    }

    fun notifBlockCount(ctx: Context): Int =
        get(ctx).getInt("notif_block_count", 0)

    fun bumpNotifBlock(ctx: Context) {
        get(ctx).edit().putInt("notif_block_count", notifBlockCount(ctx) + 1).apply()
    }

    fun resetNotifBlockCount(ctx: Context) {
        get(ctx).edit().putInt("notif_block_count", 0).apply()
    }

    // ---------- 累计拉起次数（无障碍 + 应用保活，永久不重置） ----------

    /** 自监控/保活累计成功拉起次数（跨版本、跨天累计，不重置）。 */
    fun totalPullCount(ctx: Context): Int =
        get(ctx).getInt("total_pull_count", 0)

    /** 累计拉起次数 +1，并持久化。 */
    fun bumpTotalPull(ctx: Context) {
        get(ctx).edit().putInt("total_pull_count", totalPullCount(ctx) + 1).apply()
    }

    // ---------- 通知 0-5 级调控 ----------

    private const val KEY_NOTIF_LEVELS = "notif_levels"
    private const val KEY_NOTIF_HISTORY = "notif_history"

    /** 获取指定包名的通知调控等级（0-5，默认 3 = 标准）。 */
    fun notifLevel(ctx: Context, pkg: String): Int {
        val raw = get(ctx).getString(KEY_NOTIF_LEVELS, null) ?: return 3
        return try {
            val o = JSONObject(raw)
            o.optInt(pkg, 3)
        } catch (t: Throwable) {
            3
        }
    }

    fun setNotifLevel(ctx: Context, pkg: String, level: Int) {
        val raw = get(ctx).getString(KEY_NOTIF_LEVELS, "{}") ?: "{}"
        try {
            val o = JSONObject(raw)
            o.put(pkg, level.coerceIn(0, 5))
            get(ctx).edit().putString(KEY_NOTIF_LEVELS, o.toString()).apply()
        } catch (t: Throwable) {
        }
    }

    fun notifLevels(ctx: Context): Map<String, Int> {
        val raw = get(ctx).getString(KEY_NOTIF_LEVELS, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            val m = mutableMapOf<String, Int>()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                m[k] = o.optInt(k, 3)
            }
            m
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    fun resetNotifLevels(ctx: Context) {
        get(ctx).edit().remove(KEY_NOTIF_LEVELS).apply()
    }

    /** 实验开关：尝试用系统命令精细调控（需 Root/Shizuku/ADB + Android 14+）。 */
    fun notifOverrideEnabled(ctx: Context): Boolean =
        get(ctx).getBoolean("notif_override_enabled", true)

    fun setNotifOverrideEnabled(ctx: Context, v: Boolean) {
        get(ctx).edit().putBoolean("notif_override_enabled", v).apply()
    }

    /** 实验开关：拦截历史记录。 */
    fun notifHistoryEnabled(ctx: Context): Boolean =
        get(ctx).getBoolean("notif_history_enabled", true)

    fun setNotifHistoryEnabled(ctx: Context, v: Boolean) {
        get(ctx).edit().putBoolean("notif_history_enabled", v).apply()
    }

    fun appendNotifHistory(ctx: Context, pkg: String, action: String) {
        if (!notifHistoryEnabled(ctx)) return
        val raw = get(ctx).getString(KEY_NOTIF_HISTORY, "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            val o = JSONObject()
            o.put("t", System.currentTimeMillis())
            o.put("p", pkg)
            o.put("a", action)
            arr.put(o)
            while (arr.length() > 200) arr.remove(0)
            get(ctx).edit().putString(KEY_NOTIF_HISTORY, arr.toString()).apply()
        } catch (t: Throwable) {
        }
    }

    fun notifHistory(ctx: Context): JSONArray {
        val raw = get(ctx).getString(KEY_NOTIF_HISTORY, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (t: Throwable) {
            JSONArray()
        }
    }

    fun clearNotifHistory(ctx: Context) {
        get(ctx).edit().remove(KEY_NOTIF_HISTORY).apply()
    }

    // ---------- 无障碍权限锁定保活（应用级无障碍总控） ----------

    /** 已锁定保活的无障碍服务组件串集合（被关闭后自动无感拉起）。 */
    fun lockedAccessibilityServices(ctx: Context): Set<String> =
        get(ctx).getStringSet("locked_accessibility", emptySet()) ?: emptySet()

    fun setLockedAccessibilityServices(ctx: Context, v: Set<String>) {
        get(ctx).edit().putStringSet("locked_accessibility", v.toSet()).apply()
    }

    fun isAccessibilityLocked(ctx: Context, componentFlat: String): Boolean =
        componentFlat in lockedAccessibilityServices(ctx)

    /** 开机启动保活集合：开机/系统重启后自动恢复这些无障碍服务（并入看门狗恢复）。 */
    fun bootAccessibilityServices(ctx: Context): Set<String> =
        get(ctx).getStringSet("boot_accessibility", emptySet()) ?: emptySet()

    fun setBootAccessibilityServices(ctx: Context, v: Set<String>) {
        get(ctx).edit().putStringSet("boot_accessibility", v.toSet()).apply()
    }

    fun isAccessibilityBoot(ctx: Context, componentFlat: String): Boolean =
        componentFlat in bootAccessibilityServices(ctx)

    // ---------- 保活提示（弹层全局选项） ----------

    /** 保活拉起时是否显示 Toast 提示。 */
    fun relaunchToastEnabled(ctx: Context): Boolean =
        get(ctx).getBoolean("relaunch_toast", false)

    fun setRelaunchToastEnabled(ctx: Context, v: Boolean) {
        get(ctx).edit().putBoolean("relaunch_toast", v).apply()
    }

    /** 自定义保活提示文字；为空时使用默认文案。 */
    fun relaunchToastText(ctx: Context): String? =
        get(ctx).getString("relaunch_toast_text", null)

    fun setRelaunchToastText(ctx: Context, v: String?) {
        get(ctx).edit().putString("relaunch_toast_text", v?.takeIf { it.isNotBlank() }).apply()
    }

    /** 无障碍权限页：只显示用户应用。 */
    fun accessPermUserOnly(ctx: Context): Boolean =
        get(ctx).getBoolean("accessperm_user_only", false)

    fun setAccessPermUserOnly(ctx: Context, v: Boolean) {
        get(ctx).edit().putBoolean("accessperm_user_only", v).apply()
    }

    // ---------- 实验 / 性能 ----------

    /** 无障碍事件采样降频（毫秒）：窗口事件间隔小于该值时忽略，显著省电。 */
    fun eventThrottleMs(ctx: Context): Long =
        get(ctx).getLong("event_throttle", 300L)

    fun setEventThrottleMs(ctx: Context, v: Long) {
        get(ctx).edit().putLong("event_throttle", v.coerceIn(0L, 5_000L)).apply()
    }

    // ---------- 首次启动引导 ----------

    /** 用户是否已完成首次授权引导（完成后不再自动弹出引导页）。 */
    fun firstGuideDone(ctx: Context): Boolean =
        get(ctx).getBoolean("first_guide_done", false)

    fun setFirstGuideDone(ctx: Context, v: Boolean) {
        get(ctx).edit().putBoolean("first_guide_done", v).apply()
    }

    // ---------- 熄屏休眠省电模式 ----------

    /** 熄屏后是否进入休眠模式（暂停保活巡检、降低自监控频率，亮屏恢复）。默认开。 */
    fun dozeModeEnabled(ctx: Context): Boolean =
        get(ctx).getBoolean("doze_mode_enabled", true)

    fun setDozeModeEnabled(ctx: Context, v: Boolean) {
        get(ctx).edit().putBoolean("doze_mode_enabled", v).apply()
    }

    /** 当前是否处于熄屏休眠状态（运行时标记，不持久化）。 */
    @Volatile
    private var dozeActive = false

    fun isDozeActive(): Boolean = dozeActive

    fun setDozeActive(v: Boolean) {
        dozeActive = v
    }

    // ---------- 从最近应用列表隐藏 ----------

    /** 是否从最近应用列表隐藏本应用（防止用户划走后台导致服务被杀）。默认关。 */
    fun hideFromRecents(ctx: Context): Boolean =
        get(ctx).getBoolean("hide_from_recents", false)

    fun setHideFromRecents(ctx: Context, v: Boolean) {
        get(ctx).edit().putBoolean("hide_from_recents", v).apply()
    }

    // ---------- 用户主动关闭冷却期 ----------

    /**
     * 记录用户在本软件中主动关闭的无障碍服务（带时间戳），自监控在冷却期内不自动恢复，
     * 避免用户想关闭被锁定服务时被立即拉起。冷却期默认 5 秒。
     */
    fun markUserDisabled(ctx: Context, flatten: String) {
        get(ctx).edit().putLong("user_disabled_$flatten", System.currentTimeMillis()).apply()
    }

    /** 检查服务是否在用户主动关闭的冷却期内（默认 5 秒）。 */
    fun isUserDisabledCooldown(ctx: Context, flatten: String, cooldownMs: Long = 5_000L): Boolean {
        val t = get(ctx).getLong("user_disabled_$flatten", 0)
        if (t == 0L) return false
        if (System.currentTimeMillis() - t > cooldownMs) {
            get(ctx).edit().remove("user_disabled_$flatten").apply()
            return false
        }
        return true
    }
}
