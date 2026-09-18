package com.acsmanager.pro.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import com.acsmanager.pro.shizuku.CommandService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 授权通道探测与命令执行。
 * 三种通道：
 *  - APP_GRANTED：已通过 adb shell pm grant 授予 WRITE_SECURE_SETTINGS，可直接写 Settings.Secure/Global
 *  - ROOT：设备已 Root，通过 su -c settings ... 执行
 *  - SHIZUKU：已连接 Shizuku 且已授权，通过 Shizuku 的 binder 进程执行 settings ...
 *
 * 最终版耗电/稳定性优化：
 *  - Root 探测结果缓存（成功后进程内永久缓存，失败缓存 15s），避免后台服务反复 fork su 进程；
 *  - 所有 shell 命令带超时保护，防止命令挂起永久占用线程；
 *  - shell 参数统一转义，杜绝含空格/特殊字符参数导致的命令解析错误；
 *  - Shizuku Binder 死亡时清理 UserService 绑定状态，避免复用失效 binder。
 */
object Privilege {

    private const val TAG = "AcsPrivilege"
    const val PERM = "android.permission.WRITE_SECURE_SETTINGS"
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    /** Shizuku 官方最低支持 Android 7.0（API 24）；低于此版本硬性禁用 Shizuku 通道。 */
    private const val MIN_SHIZUKU_API = 24

    enum class Channel(val labelRes: Int) {
        APP_GRANTED(com.acsmanager.pro.R.string.status_channel_app),
        ROOT(com.acsmanager.pro.R.string.status_channel_root),
        SHIZUKU(com.acsmanager.pro.R.string.status_channel_shizuku),
        NONE(com.acsmanager.pro.R.string.status_channel_none)
    }

    /** 应用自身是否已被授予 WRITE_SECURE_SETTINGS（ADB 通道的产物）。 */
    fun hasAppGranted(ctx: Context): Boolean = try {
        ctx.checkPermission(PERM, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    // ---------- Root 探测（带缓存，避免反复 fork su 耗电/弹授权框） ----------

    @Volatile
    private var rootConfirmed = false

    @Volatile
    private var rootLastFailMs = 0L

    /** Root 失败结果缓存时长：15 秒内不重复探测（应对 su 授权框被拒/短暂不可用）。 */
    private const val ROOT_FAIL_CACHE_MS = 15_000L

    /** 探测 Root（结果缓存）。 */
    fun hasRoot(): Boolean {
        if (rootConfirmed) return true
        if (System.currentTimeMillis() - rootLastFailMs < ROOT_FAIL_CACHE_MS) return false
        val out = runShell(arrayOf("su", "-c", "id"), 8)
        val ok = out != null && out.contains("uid=0")
        if (ok) {
            rootConfirmed = true
        } else {
            rootLastFailMs = System.currentTimeMillis()
        }
        return ok
    }

    /**
     * su 命令明确返回权限拒绝/不存在时失效 Root 缓存，下一轮重新探测。
     */
    private fun invalidateRoot() {
        rootConfirmed = false
        rootLastFailMs = System.currentTimeMillis()
    }

    fun shizukuReady(): Boolean = if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) false else try {
        shizukuConnected() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun shizukuConnected(): Boolean {
        if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) return false
        return try {
            // 优先使用 Application 中维护的 Binder 存活状态（由 OnBinderReceived/Dead 监听器实时更新），
            // 避免每次检测都跨进程 ping 导致 Shizuku 服务端频繁校验客户端兼容性。
            if (com.acsmanager.pro.App.shizukuBinderAlive) true else Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
    }

    fun shizukuGranted(): Boolean = if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) false else try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun hasShizukuInstalled(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) return false
        return try {
            ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 当前可用的最佳通道。Root 结果已缓存，重复调用开销极小。 */
    fun bestChannel(ctx: Context): Channel = when {
        hasAppGranted(ctx) -> Channel.APP_GRANTED
        hasRoot() -> Channel.ROOT
        shizukuReady() -> Channel.SHIZUKU
        else -> Channel.NONE
    }

    /**
     * 执行本地 shell 命令（带超时保护）。
     * @param timeoutSec 超时秒数；超时后强制销毁进程，返回 null。
     */
    fun runShell(cmd: Array<String>, timeoutSec: Long = 10): String? {
        return try {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            // API 26+ 使用带超时的 waitFor；低版本用守护线程 join 兜底
            val finished = if (Build.VERSION.SDK_INT >= 26) {
                p.waitFor(timeoutSec, TimeUnit.SECONDS)
            } else {
                val proc = p
                val worker = Thread {
                    try { proc.waitFor() } catch (t: Throwable) {}
                }.apply { isDaemon = true; start() }
                worker.join(timeoutSec * 1000L)
                !worker.isAlive
            }
            if (!finished) {
                destroyProcessCompat(p)
                Log.w(TAG, "shell timeout after ${timeoutSec}s: ${cmd.joinToString(" ")}")
                null
            } else {
                out.trim().also { detectRootDenied(cmd, it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "shell failed: ${cmd.joinToString(" ")}", t)
            null
        }
    }

    /** su 返回权限拒绝/不存在时失效 Root 缓存。 */
    private fun detectRootDenied(cmd: Array<String>, out: String) {
        val isSu = cmd.any { it == "su" } || cmd.getOrNull(0)?.startsWith("su") == true
        if (isSu && (out.contains("permission denied", ignoreCase = true) ||
                out.contains("not found", ignoreCase = true))
        ) {
            invalidateRoot()
        }
    }

    private fun destroyProcessCompat(p: java.lang.Process) {
        try {
            if (Build.VERSION.SDK_INT >= 26) p.destroyForcibly() else p.destroy()
        } catch (t: Throwable) {}
    }

    /**
     * 经授权通道执行系统命令（Root/Shizuku 具备 shell 身份）。
     * APP_GRANTED 通道只能写 Settings，无法执行 shell 命令，返回 null。
     * 用于 pm grant/revoke、dumpsys、pidof 等系统级操作。
     */
    fun execShell(ctx: Context, vararg cmd: String): String? = when (bestChannel(ctx)) {
        Channel.ROOT -> runShell(arrayOf("su", "-c", joinForShell(cmd)))
        Channel.SHIZUKU -> shizukuExec(ctx, *cmd)
        else -> null
    }

    /**
     * 将命令参数拼接为单条 shell 命令字符串，逐个参数做 POSIX 单引号转义，
     * 避免含空格/特殊字符的参数被 shell 错误拆分。
     */
    private fun joinForShell(cmd: Array<out String>): String =
        cmd.joinToString(" ") { shellQuote(it) }

    /** POSIX shell 单引号转义：' -> '\''；安全字符集免引号。 */
    private fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        if (s.all { it.isLetterOrDigit() || it in "_-./:@%+=" }) return s
        return "'" + s.replace("'", "'\\''") + "'"
    }

    // ---------- Shizuku UserService 执行通道 ----------
    @Volatile
    private var shizukuBinder: IBinder? = null
    private var shizukuBound = false

    /** 主线程 Handler（复用，避免每次 ensureShizuku 都创建新对象）。 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Shizuku Binder 死亡时由 [com.acsmanager.pro.App] 回调：
     * 清理 UserService 绑定状态，避免后续复用已失效的远程 binder。
     */
    fun onShizukuBinderDead() {
        Log.w(TAG, "Shizuku binder dead, clearing UserService state")
        shizukuBinder = null
        shizukuBound = false
    }

    /** Shizuku 状态细分（首页授权卡片展示用）。 */
    enum class ShizukuStatus {
        NOT_INSTALLED,   // 未安装 Shizuku
        NOT_CONNECTED,   // 已安装但未启动
        NO_PERMISSION,   // 已连接但未授权本应用
        READY            // 已连接且已授权
    }

    fun shizukuStatus(ctx: Context): ShizukuStatus = when {
        !hasShizukuInstalled(ctx) -> ShizukuStatus.NOT_INSTALLED
        !shizukuConnected() -> ShizukuStatus.NOT_CONNECTED
        !shizukuGranted() -> ShizukuStatus.NO_PERMISSION
        else -> ShizukuStatus.READY
    }

    /**
     * 确保已绑定 CommandService（运行在 Shizuku 进程，具备 shell 权限）。
     * Shizuku.bindUserService 需在主线程调用，绑定异步回调；失败自动重试一次。
     */
    fun ensureShizuku(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) return false
        if (shizukuBinder != null && shizukuBound) return true
        if (!shizukuReady()) return false

        for (attempt in 1..2) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var connected = false
            val cn = ComponentName(ctx, CommandService::class.java)
            val args = Shizuku.UserServiceArgs(cn)
                .daemon(false)
                .version(1)
                .processNameSuffix("command")
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    shizukuBinder = service
                    shizukuBound = service != null
                    connected = service != null
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    shizukuBinder = null
                    shizukuBound = false
                    latch.countDown()
                }
            }
            try {
                mainHandler.post {
                    try {
                        Shizuku.bindUserService(args, conn)
                    } catch (t: Throwable) {
                        Log.w(TAG, "bindUserService failed", t)
                        latch.countDown()
                    }
                }
                // 等待绑定回调（最多 5 秒）
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                if (connected) return true
            } catch (t: Throwable) {
                Log.w(TAG, "ensure shizuku attempt $attempt failed", t)
            } finally {
                if (!connected) {
                    try {
                        // remove=true 同时杀死远程 UserService 进程（Shizuku 不会自动杀死 UserService）
                        mainHandler.post {
                            try {
                                Shizuku.unbindUserService(args, conn, true)
                            } catch (t: Throwable) {
                                Log.w(TAG, "unbindUserService failed", t)
                            }
                        }
                    } catch (t: Throwable) {
                    }
                }
            }
        }
        return false
    }

    fun shizukuExec(ctx: Context, vararg cmd: String): String? {
        if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) return null
        if (!shizukuReady()) return null
        if (!ensureShizuku(ctx)) return null
        val binder = shizukuBinder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CommandService.DESCRIPTOR)
            data.writeStringArray(cmd.toList().toTypedArray())
            binder.transact(CommandService.CODE_EXEC, data, reply, 0)
            reply.readString()
        } catch (t: Throwable) {
            Log.w(TAG, "shizuku exec failed", t)
            shizukuBinder = null
            shizukuBound = false
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
