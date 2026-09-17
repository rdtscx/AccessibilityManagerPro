package com.acsmanager.pro.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
 * 三种通道（与原版原理一致）：
 *  - APP_GRANTED：已通过 adb shell pm grant 授予 WRITE_SECURE_SETTINGS，可直接写 Settings.Secure
 *  - ROOT：设备已 Root，通过 su -c settings ... 执行
 *  - SHIZUKU：已连接 Shizuku 且已授权，通过 Shizuku 的 binder 进程执行 settings ...
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

    /** 探测 Root。 */
    fun hasRoot(): Boolean {
        val out = runShell(arrayOf("su", "-c", "id"), 5)
        return out != null && out.contains("uid=0")
    }

    fun shizukuReady(): Boolean = if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) false else try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun shizukuConnected(): Boolean = if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) false else try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
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

    /** 当前可用的最佳通道。 */
    fun bestChannel(ctx: Context): Channel = when {
        hasAppGranted(ctx) -> Channel.APP_GRANTED
        hasRoot() -> Channel.ROOT
        shizukuReady() -> Channel.SHIZUKU
        else -> Channel.NONE
    }

    fun runShell(cmd: Array<String>, timeoutSec: Long = 10): String? {
        return try {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            // 输出流读尽即进程结束；waitFor() 无参版兼容全部 API（带超时版需 API 26+）
            p.waitFor()
            out.trim()
        } catch (t: Throwable) {
            Log.w(TAG, "shell failed: ${cmd.joinToString(" ")}", t)
            null
        }
    }

    /**
     * 经授权通道执行系统命令（Root/Shizuku 具备 shell 身份）。
     * APP_GRANTED 通道只能写 Settings.Secure，无法执行 shell 命令，返回 null。
     * 用于 pm grant/revoke、dumpsys 等系统级操作。
     */
    fun execShell(ctx: Context, vararg cmd: String): String? = when (bestChannel(ctx)) {
        Channel.ROOT -> runShell(arrayOf("su", "-c", cmd.joinToString(" ")))
        Channel.SHIZUKU -> shizukuExec(ctx, *cmd)
        else -> null
    }

    // ---------- Shizuku UserService 执行通道 ----------
    @Volatile
    private var shizukuBinder: IBinder? = null
    private var shizukuBound = false

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

    private val shizukuConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shizukuBinder = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuBinder = null
            shizukuBound = false
        }
    }

    /**
     * 确保已绑定 CommandService（运行在 Shizuku 进程，具备 shell 权限）。
     * Shizuku.bindUserService 需在主线程调用，绑定异步回调；失败自动重试一次。
     */
    fun ensureShizuku(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) return false
        if (shizukuBinder != null && shizukuBound) return true
        if (!shizukuReady()) return false
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        for (attempt in 1..2) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var connected = false
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
                main.post {
                    try {
                        val cn = ComponentName(ctx, CommandService::class.java)
                        Shizuku.bindUserService(Shizuku.UserServiceArgs(cn), conn)
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
                        main.post { ctx.unbindService(conn) }
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
        return try {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            data.writeInterfaceToken(CommandService.DESCRIPTOR)
            data.writeStringArray(cmd)
            binder.transact(CommandService.CODE_EXEC, data, reply, 0)
            val out = reply.readString()
            data.recycle()
            reply.recycle()
            out
        } catch (t: Throwable) {
            Log.w(TAG, "shizuku exec failed", t)
            shizukuBinder = null
            shizukuBound = false
            null
        }
    }
}
