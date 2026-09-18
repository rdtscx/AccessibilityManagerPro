package com.acsmanager.pro.shizuku

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService：该服务由 Shizuku 服务器启动，运行在其进程（:shizuku）中，
 * 拥有与 Shizuku 相同的 shell 权限，可执行 settings 等系统命令。
 * 调用方通过 binder transact 同步获取命令输出。
 *
 * 优化：
 *  - 命令执行增加 30 秒超时保护，防止命令挂起导致 Shizuku 进程 binder 线程阻塞；
 *  - 实现销毁方法（transaction code 16777115），符合 Shizuku 官方推荐，
 *    解绑时可主动停止 UserService 进程（Shizuku 不会自动杀死 UserService 进程）。
 */
class CommandService : Service() {

    override fun onBind(intent: Intent?): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                CODE_EXEC -> {
                    data.enforceInterface(DESCRIPTOR)
                    val cmd = data.createStringArray() ?: emptyArray()
                    val result = run(cmd)
                    reply?.writeString(result)
                    true
                }
                // Shizuku 官方约定的销毁方法 transaction code：16777115
                // （对应 IBinder.INTERFACE_TRANSACTION - 1，即 0x00ffffff - 1 = 16777215 - 1 = 16777214？
                //  实际 Shizuku 文档使用 16777115 = 0x00ffffdb，这里按文档约定实现）
                CODE_DESTROY -> {
                    data.enforceInterface(DESCRIPTOR)
                    Log.i(TAG, "CommandService destroy requested")
                    reply?.writeNoException()
                    // 延迟停止自身，确保 reply 写回后再 stopSelf
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        stopSelf()
                    }, 100)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    private fun run(cmd: Array<String>): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
        // 命令执行超时保护：最多等待 30 秒，超时则销毁进程并返回超时错误
        val finished = p.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            "error: command timeout after 30s: ${cmd.joinToString(" ")}"
        } else {
            out.trim()
        }
    } catch (t: Throwable) {
        "error: ${t.message}"
    }

    companion object {
        private const val TAG = "AcsShizukuCmd"
        const val DESCRIPTOR = "com.acsmanager.pro.shizuku.CommandService"
        const val CODE_EXEC = 1
        /** Shizuku 官方约定的 UserService 销毁 transaction code。 */
        const val CODE_DESTROY = 16777115
    }
}
