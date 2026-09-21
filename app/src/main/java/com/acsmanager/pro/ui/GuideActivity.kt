package com.acsmanager.pro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.acsmanager.pro.R
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.databinding.ActivityGuideBinding
import com.acsmanager.pro.util.Prefs
import rikka.shizuku.Shizuku

/**
 * 授权向导：Root / Shizuku / ADB 三种通道的状态检测与引导。
 * 首次启动时由 MainActivity 自动弹出；实时检测授权状态，任一通道就绪即自动返回首页；
 * 未授权时按返回键或"进入首页"按钮即可回到首页。
 */
class GuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuideBinding
    private val requestCode = 1024
    private val handler = Handler(Looper.getMainLooper())

    /** 每 2 秒检测一次授权状态，检测到就绪则自动回首页。 */
    private val detectRunnable = object : Runnable {
        override fun run() {
            detect()
            if (isAnyChannelReady()) {
                Prefs.setFirstGuideDone(this@GuideActivity, true)
                Toast.makeText(this@GuideActivity, R.string.guide_auto_enter, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            handler.postDelayed(this, 2000L)
        }
    }

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == requestCode) {
            runOnUiThread { detect() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyStatusBar()

        binding.toolbar.setNavigationOnClickListener {
            Prefs.setFirstGuideDone(this, true)
            finish()
        }

        // 一条命令搞定全部功能所需权限：
        //  1. WRITE_SECURE_SETTINGS —— 直写 Settings 无障碍/开发者选项
        //  2. WRITE_SETTINGS —— 写 Settings.System（指针速度/字体缩放等）
        //  3. POST_NOTIFICATIONS —— Android 13+ 通知权限
        //  4. appops get_usage_stats —— 使用情况访问（保活引擎存活判定）
        //  5. enabled_accessibility_services —— 本应用自无障碍服务
        //  6. accessibility_enabled —— 无障碍总开关
        //  7. enabled_notification_listeners —— 本应用通知监听服务（通知分级拦截）
        //  8. notification_access_enabled —— 通知使用权总开关
        // 跑完这一条即可，永久生效，无需再去系统设置里手动开任何开关。
        val component = "$packageName/com.acsmanager.pro.selfguard.SelfAccessService"
        val notifComponent = "$packageName/com.acsmanager.pro.notif.NotifGateService"
        binding.adbCommand.text =
            "adb shell \"pm grant $packageName android.permission.WRITE_SECURE_SETTINGS && " +
            "pm grant $packageName android.permission.WRITE_SETTINGS && " +
            "pm grant $packageName android.permission.POST_NOTIFICATIONS && " +
            "appops set $packageName android:get_usage_stats allow && " +
            "settings put secure enabled_accessibility_services $component && " +
            "settings put secure accessibility_enabled 1 && " +
            "settings put secure enabled_notification_listeners $notifComponent && " +
            "settings put secure notification_access_enabled 1\""

        binding.btnShizukuInstall.setOnClickListener {
            val url = if (Privilege.hasShizukuInstalled(this)) {
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${Privilege.SHIZUKU_PKG}"))
            } else {
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=${Privilege.SHIZUKU_PKG}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(url)
            } catch (t: Throwable) {
                Toast.makeText(this, R.string.result_fail, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnShizukuStart.setOnClickListener {
            try {
                startActivity(
                    packageManager.getLaunchIntentForPackage(Privilege.SHIZUKU_PKG)
                        ?: throw IllegalStateException("not installed")
                )
            } catch (t: Throwable) {
                Toast.makeText(this, R.string.result_fail, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnShizukuGrant.setOnClickListener {
            try {
                if (!Shizuku.pingBinder()) {
                    Toast.makeText(this, R.string.guide_shizuku_start, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.guide_shizuku_ok, Toast.LENGTH_SHORT).show()
                } else {
                    Shizuku.requestPermission(requestCode)
                }
            } catch (t: Throwable) {
                Toast.makeText(this, R.string.result_fail, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adb", binding.adbCommand.text))
            Toast.makeText(this, R.string.guide_adb_copy_done, Toast.LENGTH_SHORT).show()
        }

        binding.btnDetect.setOnClickListener { detect() }

        binding.btnEnterHome.setOnClickListener {
            Prefs.setFirstGuideDone(this, true)
            finish()
        }

        detect()
    }

    override fun onResume() {
        super.onResume()
        applyStatusBar()
        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener)
        } catch (t: Throwable) {
        }
        detect()
        // 已授权任一通道：直接标记完成并返回首页，不再启动 2 秒轮询
        if (isAnyChannelReady()) {
            Prefs.setFirstGuideDone(this, true)
            finish()
            return
        }
        handler.removeCallbacks(detectRunnable)
        handler.postDelayed(detectRunnable, 2000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(detectRunnable)
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener)
        } catch (t: Throwable) {
        }
    }

    /** 状态栏颜色跟随主题。 */
    private fun applyStatusBar() {
        val isDark = when (Prefs.themeMode(this)) {
            "light" -> false
            "dark" -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        window.statusBarColor = getColor(if (isDark) R.color.status_bar_dark else R.color.status_bar_light)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isDark
    }

    /** 任一通道就绪即视为授权完成。 */
    private fun isAnyChannelReady(): Boolean =
        Privilege.hasRoot() || Privilege.shizukuReady() || Privilege.hasAppGranted(this)

    private fun detect() {
        val rootOk = Privilege.hasRoot()
        val shizukuOk = Privilege.shizukuReady()
        val adbOk = Privilege.hasAppGranted(this)

        setStatus(binding.rootStatus, rootOk, R.string.guide_root_ok, R.string.guide_root_fail)

        if (shizukuOk) {
            setStatus(binding.shizukuStatus, true, R.string.guide_shizuku_ok, R.string.guide_shizuku_ok)
        } else {
            val hintRes = when {
                Privilege.shizukuConnected() && !Privilege.shizukuGranted() -> R.string.guide_shizuku_grant
                else -> R.string.guide_shizuku_start
            }
            setStatus(binding.shizukuStatus, false, hintRes, hintRes)
        }

        setStatus(binding.adbStatus, adbOk, R.string.guide_adb_ok, R.string.guide_check)

        // 更新整体状态芯片
        val ready = rootOk || shizukuOk || adbOk
        binding.chipOverall.text = getString(if (ready) R.string.guide_ready else R.string.guide_not_ready)
        binding.chipOverall.setTextColor(
            ContextCompat.getColor(this, if (ready) R.color.status_ok else R.color.status_err)
        )
    }

    private fun setStatus(view: android.widget.TextView, ok: Boolean, okRes: Int, failRes: Int) {
        view.text = getString(if (ok) okRes else failRes)
        view.setTextColor(
            ContextCompat.getColor(
                this,
                if (ok) R.color.status_ok else R.color.status_err
            )
        )
    }
}
