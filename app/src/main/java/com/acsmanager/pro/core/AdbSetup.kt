package com.acsmanager.pro.core

import android.content.Context
import android.util.Log
import com.acsmanager.pro.BuildConfig

/**
 * 首次引导页的 ADB 命令：展示、复制，以及用 Root / Shizuku 在应用内执行同一组命令。
 *
 * 三行命令彼此独立，按顺序逐条执行：
 *  1. 授予 WRITE_SECURE_SETTINGS（直写系统设置）；
 *  2. 打开自由窗口支持（全局开关）；
 *  3. 授予音量键长按监听权限——该权限 protectionLevel 为 signature|privileged|development，
 *     带 development 标记才允许 pm grant，所以必须先在本应用清单里声明。
 */
object AdbSetup {

    private const val TAG = "AcsAdbSetup"

    val LINES: List<String> = listOf(
        "pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS",
        "settings put global enable_freeform_support 1",
        "pm grant ${BuildConfig.APPLICATION_ID} android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER"
    )

    /** 引导页展示与复制的文本：三行原样，可直接粘贴到电脑终端逐行执行。 */
    val TEXT: String = LINES.joinToString("\n")

    data class LineResult(val line: String, val ok: Boolean, val output: String)

    /** 依次执行三行命令，返回每行的结果；只具备 Root / Shizuku 通道时可用。 */
    fun run(ctx: Context): List<LineResult> = LINES.map { line ->
        val out = Privilege.execShell(ctx, *line.split(' ').toTypedArray())
        val ok = out != null && FAIL_MARKERS.none { out.contains(it, ignoreCase = true) }
        Log.i(TAG, "run [$line] ok=$ok out=${out?.take(200)}")
        LineResult(line, ok, out ?: "")
    }

    /** pm / settings 成功时没有输出，这些字样说明命令没有真正生效。 */
    private val FAIL_MARKERS =
        listOf("error", "exception", "denied", "unknown", "not found", "unable")
}
