package com.acsmanager.pro.shizuku

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService：该服务由 Shizuku 服务器启动，运行在其进程（:shizuku）中，
 * 拥有与 Shizuku 相同的 shell 权限，可执行 settings 等系统命令。
 * 调用方通过 binder transact 同步获取命令输出。
 */
class CommandService : Service() {

    override fun onBind(intent: Intent?): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == CODE_EXEC) {
                data.enforceInterface(DESCRIPTOR)
                val cmd = data.createStringArray() ?: emptyArray()
                val result = run(cmd)
                reply?.writeString(result)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun run(cmd: Array<String>): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
        // 输出流读尽即进程结束；waitFor() 无参版兼容全部 API（带超时版需 API 26+）
        p.waitFor()
        out.trim()
    } catch (t: Throwable) {
        "error: ${t.message}"
    }

    companion object {
        const val DESCRIPTOR = "com.acsmanager.pro.shizuku.CommandService"
        const val CODE_EXEC = 1
    }
}
