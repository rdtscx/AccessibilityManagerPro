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

    /** 主线程 Handler（复用，避免每次 ensureShizuku 都创建新对象）。 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Shizuku Binder 死亡监听：Shizuku 服务被杀死时及时清理状态，避免后续使用失效 binder。 */
    private val binderDeadListener = rikka.shizuku.Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead, clearing state")
        shizukuBinder = null
        shizukuBound = false
    }

    @Volatile
    private var deadListenerRegistered = false

    /** 注册 Shizuku Binder 死亡监听（幂等，仅注册一次）。 */
    private fun ensureBinderDeadListener() {
        if (deadListenerRegistered) return
        if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) return
        try {
            rikka.shizuku.Shizuku.addBinderDeadListener(binderDeadListener)
            deadListenerRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "register binder dead listener failed", t)
        }
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
     *
     * 修复要点：
     *  - 解绑使用 Shizuku.unbindUserService（而非 ctx.unbindService，后者用于普通 Service）；
     *  - 注册 Binder 死亡监听，Shizuku 服务被杀时及时清理状态；
     *  - UserServiceArgs 设置 version 和 processNameSuffix，符合 Shizuku 官方推荐。
     */
    fun ensureShizuku(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) return false
        ensureBinderDeadListener()
        if (shizukuBinder != null && shizukuBound) return true
        if (!shizukuReady()) return false

        for (attempt in 1..2) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var connected = false
            val cn = ComponentName(ctx, CommandService::class.java)
            val args = rikka.shizuku.Shizuku.UserServiceArgs(cn)
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
                        rikka.shizuku.Shizuku.bindUserService(args, conn)
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
                        // 修复：Shizuku.unbindUserService 需要 (UserServiceArgs, ServiceConnection, boolean) 三个参数
                        // remove=true 表示同时杀死远程 UserService 进程（Shizuku 不会自动杀死 UserService）
                        mainHandler.post {
                            try {
                                rikka.shizuku.Shizuku.unbindUserService(args, conn, true)
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
            data.writeStringArray(cmd)
            binder.transact(CommandService.CODE_EXEC, data, reply, 0)
            reply.readString()
        } catch (t: Throwable) {
            Log.w(TAG, "shizuku exec failed", t)
            shizukuBinder = null
            shizukuBound = false
            null
        } finally {
            // 修复：使用 try-finally 确保 Parcel 资源被回收，避免 transact 异常时泄漏
            data.recycle()
            reply.recycle()
        }
    }
}
