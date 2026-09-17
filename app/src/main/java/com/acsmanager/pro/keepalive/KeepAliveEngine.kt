package com.acsmanager.pro.keepalive

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.acsmanager.pro.R
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.selfguard.SelfAccessService
import com.acsmanager.pro.util.Prefs
import com.acsmanager.pro.watchdog.EventLog
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用保活引擎（事件驱动 + 低频巡检，后台无感拉起）。
 *
 * 掉线判定（"检测到被保活的指定应用失去服务或者被杀后台"）分级：
 *  1. 当前前台 → 存活；
 *  2. 有"使用情况访问"：近 interval×2 窗口内最后一次活动是 RESUME 且未被 STOP → 存活；
 *  3. 无使用情况访问：
 *     - API ≤ 30：ActivityManager 进程列表兜底；
 *     - 全部版本：有 Root/Shizuku 通道时用 `pidof` 精准判定；
 *     - 仍无法判定：以无障碍窗口事件时间戳（近 60 秒有窗口事件 = 存活）为参考；
 *     - 都不可用：视为掉线并尝试拉起（配合"拉起后返回上一应用"保证无感）。
 *
 * 拉起策略（用户要求）：
 *  - 检测到掉线立即拉起（不再要求熄屏/闲置 3 分钟）；
 *  - 拉起后 100ms 按用户选择的"返回方案"处理（prev=切回上一应用 / stay=停留 / home=回桌面）；
 *  - 黑屏（熄屏）时是否也保活由 [Prefs.keepAliveBlackScreen] 控制（默认开）；
 *  - 低电量暂停与单应用冷却可配；
 *  - 保留 10 秒前台切换硬约束：用户最近 10 秒内切换过前台时不拉（避免刚切换的瞬间闪烁）。
 */
object KeepAliveEngine {

    private const val TAG = "AcsKeepAlive"

    private class Target(val pkg: String) {
        var lastForegroundMs = 0L
        var lastWindowMs = 0L
        var lastCheckMs = 0L
        var lastRelaunchMs = 0L
        var relaunchCount = 0
    }

    private val targets = ConcurrentHashMap<String, Target>()
    private val handler = Handler(Looper.getMainLooper())

    /** 存活判定缓存（10 秒），避免列表滚动/巡检时反复查询 UsageStats（省电、防卡顿）。
     *  带 128 条上限，超过时清理过期条目，防止长期运行内存泄漏。 */
    private val aliveCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private const val ALIVE_CACHE_MAX = 128

    @Volatile
    private var service: SelfAccessService? = null

    @Volatile
    private var lastForegroundPkg: String? = null
    private var lastForegroundChangeMs = 0L
    private var lastBatteryPct = -1

    // ---------- 生命周期（由 SelfAccessService 驱动） ----------

    fun onAccessibilityConnected(svc: SelfAccessService) {
        service = svc
        reloadTargets(svc)
        handler.removeCallbacks(checkRunnable)
        if (targets.isNotEmpty()) handler.postDelayed(checkRunnable, 5_000L)
    }

    fun onAccessibilityDisconnected() {
        service = null
        handler.removeCallbacks(checkRunnable)
    }

    /** 重新加载保活名单；名单非空时确保巡检已调度（名单为空则不调度，零开销）。 */
    fun reloadTargets(ctx: Context) {
        val selected = Prefs.keepAliveApps(ctx)
        targets.keys.removeAll { it !in selected }
        for (pkg in selected) {
            targets.getOrPut(pkg) { Target(pkg) }
        }
        handler.removeCallbacks(checkRunnable)
        if (targets.isNotEmpty() && service != null) {
            handler.postDelayed(checkRunnable, Prefs.keepAliveIntervalMs(ctx).coerceIn(10_000L, 300_000L))
        }
    }

    /** 无障碍窗口事件 → 记录前台与窗口时间戳（"最近有窗口事件 = 存活"判定依据）。 */
    fun onForeground(pkg: String, nowMs: Long) {
        val prev = lastForegroundPkg
        lastForegroundPkg = pkg
        if (prev != pkg) lastForegroundChangeMs = nowMs
        targets[pkg]?.let {
            it.lastForegroundMs = nowMs
            it.lastWindowMs = nowMs
        }
    }

    // ---------- 巡检 ----------

    private val checkRunnable = object : Runnable {
        override fun run() {
            runCheck()
            val ctx = service ?: return
            // 名单为空时不再自调度（省电）；有新目标由 reloadTargets 恢复调度
            if (targets.isEmpty()) return
            handler.postDelayed(this, Prefs.keepAliveIntervalMs(ctx).coerceIn(10_000L, 300_000L))
        }
    }

    private fun runCheck() {
        val ctx = service ?: return
        if (targets.isEmpty()) return
        if (Prefs.keepAlivePauseLowBattery(ctx) && isLowBattery(ctx)) return

        val now = SystemClock.elapsedRealtime()
        for (target in targets.values) {
            if (now - target.lastCheckMs < Prefs.keepAliveIntervalMs(ctx) / 2) continue
            target.lastCheckMs = now
            if (target.pkg == lastForegroundPkg) continue           // 正在前台使用，不打扰
            if (now - target.lastRelaunchMs < Prefs.keepAliveCooldownMs(ctx)) continue
            if (isAlive(ctx, target.pkg)) continue                  // 还活着，无需拉起
            if (!policyAllows(ctx)) continue                        // 黑屏策略/低电量等不允许
            relaunch(ctx, target)
        }
    }

    // ---------- 存活判定 ----------

    private fun isAlive(ctx: Context, pkg: String): Boolean {
        // 缓存 10 秒
        val cached = aliveCache[pkg]
        if (cached != null && SystemClock.elapsedRealtime() - cached.first < 10_000L) {
            return cached.second
        }
        // 缓存超上限时清理过期条目（防止内存泄漏）
        if (aliveCache.size > ALIVE_CACHE_MAX) {
            val now = SystemClock.elapsedRealtime()
            aliveCache.entries.removeAll { now - it.value.first >= 10_000L }
        }
        val result = checkAlive(ctx, pkg)
        aliveCache[pkg] = SystemClock.elapsedRealtime() to result
        return result
    }

    private fun checkAlive(ctx: Context, pkg: String): Boolean {
        // 1. 当前前台
        if (pkg == lastForegroundPkg) return true

        // 2. 无障碍窗口事件快速判定：近 30 秒有窗口事件 = 存活（O(1)，无 IO，省电）
        val target = targets[pkg]
        if (target != null && SystemClock.elapsedRealtime() - target.lastWindowMs < 30_000L) {
            return true
        }

        // 3. 使用情况访问事件判定（主通道，精准）
        if (hasUsageAccess(ctx)) {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val begin = end - 2 * Prefs.keepAliveIntervalMs(ctx).coerceAtLeast(30_000L)
            var lastResume = 0L
            var lastStop = 0L
            try {
                val events = usm.queryEvents(begin, end)
                val e = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    if (e.packageName != pkg) continue
                    when (e.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> lastResume = e.timeStamp
                        UsageEvents.Event.ACTIVITY_STOPPED,
                        UsageEvents.Event.ACTIVITY_PAUSED -> lastStop = e.timeStamp
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "usage query failed", t)
            }
            if (lastResume > lastStop) return true
            if (lastResume > 0L || lastStop > 0L) {
                return false // 最近有活动且最后是停止 → 已不在前台运行
            }
        }

        // 4. 进程列表兜底（API ≤ 30；31+ 仅返回本应用进程，无意义但保留）
        if (android.os.Build.VERSION.SDK_INT <= 30) {
            val alive = try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val procs = am.runningAppProcesses
                procs?.any { it.processName == pkg || it.processName.startsWith("$pkg:") } == true
            } catch (t: Throwable) {
                true
            }
            if (alive) return true
        }

        // 5. Root / Shizuku 通道精准判定（pidof）
        if (Privilege.bestChannel(ctx) != Privilege.Channel.NONE) {
            val out = Privilege.execShell(ctx, "pidof", pkg) ?: ""
            if (out.isNotBlank() && !out.contains("error", ignoreCase = true)) {
                return true // 有 pid = 进程存活
            }
        }

        // 6. 无障碍窗口事件参考：近 60 秒有窗口事件 = 存活
        if (target != null && SystemClock.elapsedRealtime() - target.lastWindowMs < 60_000L) {
            return true
        }

        // 7. 无法判定：按用户需求"被杀后台直接拉起"，视为掉线
        return false
    }

    private fun hasUsageAccess(ctx: Context): Boolean = try {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 1000, end)
        true
    } catch (t: SecurityException) {
        false
    } catch (t: Throwable) {
        false
    }

    // ---------- 无感策略（用户可配） ----------

    private fun policyAllows(ctx: Context): Boolean {
        // 低电量暂停
        if (Prefs.keepAlivePauseLowBattery(ctx) && isLowBattery(ctx)) return false
        // 黑屏时是否保活（默认开）：屏幕关闭且用户关闭了"黑屏保活" → 跳过
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive && !Prefs.keepAliveBlackScreen(ctx)) return false
        // 10 秒前台切换硬约束：用户刚切换过前台（可能在操作）→ 不拉，避免闪烁
        val now = SystemClock.elapsedRealtime()
        if (lastForegroundChangeMs > 0 && now - lastForegroundChangeMs < 10_000L) return false
        return true
    }

    private fun isLowBattery(ctx: Context): Boolean {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = try {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (t: Throwable) {
            -1
        }
        if (pct >= 0) lastBatteryPct = pct
        return lastBatteryPct in 0..Prefs.keepAliveBatteryThreshold(ctx)
    }

    // ---------- 拉起 + 返回上一应用 ----------

    private fun relaunch(ctx: Context, target: Target) {
        val intent = AppsRepository.launchIntent(ctx, target.pkg) ?: return
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        // 记录"用户上一个软件"：拉起前的前台应用（返回方案 prev 用）
        val prevForeground = lastForegroundPkg
        try {
            ctx.startActivity(intent)
            target.lastRelaunchMs = SystemClock.elapsedRealtime()
            target.relaunchCount++
            Prefs.bumpRelaunch(ctx)
            EventLog.record(
                ctx,
                EventLog.TYPE_RESTORED,
                target.pkg,
                ctx.getString(R.string.keepalive_log_relaunched, target.relaunchCount)
            )
            Log.i(TAG, "silently relaunched ${target.pkg}")

            // 实验选项：拉起时显示 Toast 提示（默认关闭；自定义文案优先）
            if (Prefs.relaunchToastEnabled(ctx)) {
                val label = AppsRepository.labelOf(ctx, target.pkg) ?: target.pkg
                val text = Prefs.relaunchToastText(ctx)?.takeIf { it.isNotBlank() }
                    ?: ctx.getString(R.string.keepalive_toast_default, label)
                try {
                    android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                }
            }

            // 100ms 后按"返回方案"处理（无感保活：拉起后立即切回用户上一个软件）
            handleReturn(ctx, target.pkg, prevForeground)
        } catch (t: Throwable) {
            Log.w(TAG, "relaunch ${target.pkg} failed", t)
            EventLog.record(ctx, EventLog.TYPE_WARN, target.pkg, "relaunch failed: ${t.message}")
        }
    }

    /**
     * 拉起后 100ms 按用户选择的返回方案处理：
     *  - prev：切回用户上一个软件（默认，无感保活）；
     *  - stay：停留在目标软件；
     *  - home：返回桌面。
     */
    private fun handleReturn(ctx: Context, relaunchedPkg: String, prevForeground: String?) {
        val mode = Prefs.keepAliveReturnMode(ctx)
        if (mode == "stay") return
        if (mode == "home") {
            handler.postDelayed({
                goHome(ctx)
            }, 100L)
            return
        }
        // prev（默认）：100ms 后切回上一个软件（前提：确实有上一个前台应用，且不是目标应用自身）
        if (prevForeground == null || prevForeground == relaunchedPkg) return
        if (prevForeground == ctx.packageName) return // 上一个软件是本应用自身时不切
        handler.postDelayed({
            val intent = AppsRepository.launchIntent(ctx, prevForeground) ?: return@postDelayed
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            try {
                ctx.startActivity(intent)
                Log.i(TAG, "returned to $prevForeground after relaunch")
            } catch (t: Throwable) {
                Log.w(TAG, "return to $prevForeground failed", t)
            }
        }, 100L)
    }

    private fun goHome(ctx: Context) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "go home failed", t)
        }
    }

    /** 手动测试拉起（实验页 / 保活列表单点触发）。 */
    fun manualRelaunch(ctx: Context, pkg: String): Boolean {
        val intent = AppsRepository.launchIntent(ctx, pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            ctx.startActivity(intent)
            EventLog.record(ctx, EventLog.TYPE_SERVICE, pkg, "manual relaunch")
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 前台软件包名（供 UI 展示"运行中/前台/已停止"）。 */
    fun lastForeground(): String? = lastForegroundPkg

    /** 目标软件是否在运行（UI 状态点；带缓存）。 */
    fun isTargetAlive(ctx: Context, pkg: String): Boolean = isAlive(ctx, pkg)

    fun protectedCount(): Int = targets.size
}
